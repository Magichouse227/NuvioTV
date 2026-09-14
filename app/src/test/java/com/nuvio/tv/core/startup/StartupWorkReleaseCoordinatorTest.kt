package com.nuvio.tv.core.startup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupWorkReleaseCoordinatorTest {
    @Test
    fun `stale shell is cleared when active profile changes`() {
        val foreground = transitionStartupWorkRelease(
            StartupWorkReleaseState(),
            StartupWorkReleaseEvent.Foreground
        ).state
        val shell = transitionStartupWorkRelease(
            foreground,
            StartupWorkReleaseEvent.HomeShellReady(profileId = 1)
        ).state

        val switched = transitionStartupWorkRelease(
            shell,
            StartupWorkReleaseEvent.ActiveProfileChanged(profileId = 2)
        )

        assertTrue(switched.state.shellProfileId == null)
        assertFalse(switched.releaseNow)
    }

    @Test
    fun `fallback is cancelled on stop and deferred until foreground returns`() {
        val armed = transitionStartupWorkRelease(
            StartupWorkReleaseState(),
            StartupWorkReleaseEvent.Foreground
        ).state
        val stopped = transitionStartupWorkRelease(armed, StartupWorkReleaseEvent.Background)
        val elapsedWhileStopped = transitionStartupWorkRelease(
            stopped.state,
            StartupWorkReleaseEvent.FallbackElapsed
        )
        val resumed = transitionStartupWorkRelease(
            elapsedWhileStopped.state,
            StartupWorkReleaseEvent.Foreground
        )

        assertTrue(stopped.cancelFallback)
        assertFalse(elapsedWhileStopped.releaseNow)
        assertFalse(elapsedWhileStopped.state.released)
        assertTrue(resumed.armFallback)
    }

    @Test
    fun `interaction releases only for a foreground profile shell`() {
        val foreground = transitionStartupWorkRelease(
            StartupWorkReleaseState(),
            StartupWorkReleaseEvent.Foreground
        ).state
        val shell = transitionStartupWorkRelease(
            foreground,
            StartupWorkReleaseEvent.HomeShellReady(profileId = 3)
        ).state

        val released = transitionStartupWorkRelease(
            shell,
            StartupWorkReleaseEvent.FirstInteraction
        )

        assertTrue(released.releaseNow)
        assertTrue(released.state.released)
    }
}