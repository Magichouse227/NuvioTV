package com.nuvio.tv.core.startup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupWorkReleasePolicyTest {
    @Test
    fun `profile picker interaction does not release work for the previously active profile`() {
        assertFalse(
            shouldReleaseDeferredStartupWork(
                hasFirstInteraction = true,
                homeShellReady = false,
                fallbackElapsed = false
            )
        )
    }

    @Test
    fun `profile scoped home shell releases work after interaction`() {
        assertTrue(
            shouldReleaseDeferredStartupWork(
                hasFirstInteraction = true,
                homeShellReady = true,
                fallbackElapsed = false
            )
        )
    }

    @Test
    fun `bounded fallback releases work without input`() {
        assertTrue(
            shouldReleaseDeferredStartupWork(
                hasFirstInteraction = false,
                homeShellReady = false,
                fallbackElapsed = true
            )
        )
    }
}