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
    fun `activity pull holds startup request until activity ownership clears`() {
        val coordinator = StartupSyncCoordinator()
        val startup = StartupSyncCoordinator.Request("account", 1, true, true)

        assertEquals(true, coordinator.beginActivity())
        assertNull(coordinator.enqueue(startup))
        assertEquals(startup, coordinator.finishActivity())
        assertEquals(startup, coordinator.enqueue(startup))
    }
}