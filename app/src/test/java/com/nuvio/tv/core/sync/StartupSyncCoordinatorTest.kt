package com.nuvio.tv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StartupSyncCoordinatorTest {
    @Test
    fun `concurrent scheduling coalesces behind one active request`() {
        val coordinator = StartupSyncCoordinator()
        val first = StartupSyncCoordinator.Request(
            userId = "account",
            profileId = 1,
            force = false,
            includeProfileSettings = false
        )
        val second = StartupSyncCoordinator.Request(
            userId = "account",
            profileId = 2,
            force = true,
            includeProfileSettings = true
        )

        assertEquals(first, coordinator.enqueue(first))
        assertNull(coordinator.enqueue(second))
        assertEquals(second, coordinator.finish(first))
        assertFalse(coordinator.hasActiveRequest())
    }

    @Test
    fun `pending followup can begin only after completed job is cleared`() {
        val coordinator = StartupSyncCoordinator()
        val active = StartupSyncCoordinator.Request("account", 1, false, true)
        val followup = StartupSyncCoordinator.Request("account", 2, false, true)

        coordinator.enqueue(active)
        coordinator.enqueue(followup)

        val pending = coordinator.finish(active)
        assertEquals(followup, pending)
        assertEquals(pending, coordinator.enqueue(requireNotNull(pending)))
    }

    @Test
    fun `stale profile request remains distinguishable from resolved profile`() {
        val coordinator = StartupSyncCoordinator()
        val stale = StartupSyncCoordinator.Request("account", 1, false, true)
        val resolved = StartupSyncCoordinator.Request("account", 2, false, true)

        coordinator.enqueue(stale)
        coordinator.enqueue(resolved)

        assertEquals(resolved, coordinator.finish(stale))
    }

    @Test
    fun `delayed request cannot apply after account or profile changes`() {
        val coordinator = StartupSyncCoordinator()
        val delayed = StartupSyncCoordinator.Request("account-a", 1, false, true)

        assertFalse(
            coordinator.matchesCurrentIdentity(
                request = delayed,
                userId = "account-a",
                profileId = 2
            )
        )
        assertFalse(
            coordinator.matchesCurrentIdentity(
                request = delayed,
                userId = "account-b",
                profileId = 1
            )
        )
    }

    @Test
    fun `manual or realtime request lifetime is invalidated by either identity change`() {
        val coordinator = StartupSyncCoordinator()
        val request = StartupSyncCoordinator.Request("account-a", 4, true, true)

        // This is the guard supplied to tracked manual/realtime jobs immediately before their
        // remote metadata and profile-store mutations.
        assertEquals(
            true,
            coordinator.matchesCurrentIdentity(request, userId = "account-a", profileId = 4)
        )
        assertFalse(
            coordinator.matchesCurrentIdentity(request, userId = "account-b", profileId = 4)
        )
        assertFalse(
            coordinator.matchesCurrentIdentity(request, userId = "account-a", profileId = 5)
        )
    }

    @Test
    fun `activity pull holds startup request until activity ownership clears`() {
        val coordinator = StartupSyncCoordinator()
        val startup = StartupSyncCoordinator.Request("account", 1, true, true)

        assertEquals(true, coordinator.beginActivity())
        assertNull(coordinator.enqueue(startup))
        assertEquals(startup, coordinator.finishActivity())
        assertEquals(startup, coordinator.enqueue(startup))
    }

    @Test
    fun `direct account change cancels every job kind while retaining activity ownership`() {
        val coordinator = StartupSyncCoordinator()
        val accountA = StartupSyncCoordinator.Request("account-a", 1, true, true)
        val accountB = StartupSyncCoordinator.Request("account-b", 1, false, true)
        val cancelled = mutableListOf<String>()
        coordinator.beginActivity()
        coordinator.enqueue(accountA)

        coordinator.cancelIdentityWork(
            cancelStartupPull = { cancelled += "startup" },
            cancelActivityPull = { cancelled += "activity" },
            cancelAuxiliaryPulls = { cancelled += "auxiliary" }
        )

        assertEquals(listOf("startup", "activity", "auxiliary"), cancelled)
        assertFalse(coordinator.beginActivity())
        assertNull(coordinator.enqueue(accountB))
        // The old account's pending force flag must not leak to B.
        assertEquals(accountB, coordinator.finishActivity())
    }

    @Test
    fun `account change drops old followups but lets a new account queue behind startup cleanup`() {
        val coordinator = StartupSyncCoordinator()
        val accountA = StartupSyncCoordinator.Request("account-a", 1, false, false)
        val oldFollowup = accountA.copy(force = true, includeProfileSettings = true)
        val accountB = StartupSyncCoordinator.Request("account-b", 1, false, false)
        var startupCancelled = false
        coordinator.enqueue(accountA)
        coordinator.enqueue(oldFollowup)

        coordinator.cancelIdentityWork(
            cancelStartupPull = { startupCancelled = true },
            cancelActivityPull = {},
            cancelAuxiliaryPulls = {}
        )

        assertEquals(true, startupCancelled)
        assertNull(coordinator.enqueue(accountB))
        assertEquals(accountB, coordinator.finish(accountA))
    }
}