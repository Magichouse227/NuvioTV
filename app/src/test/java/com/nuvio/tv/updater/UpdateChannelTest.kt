package com.nuvio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateChannelTest {
    @Test
    fun `fork test default is opt in and does not depend on BuildConfig`() {
        assertEquals(
            UpdateChannel.FORK_TEST,
            UpdateChannel.defaultForVersion("0.9.2-beta", isForkTestBuild = true)
        )
        assertEquals(
            UpdateChannel.BETA,
            UpdateChannel.defaultForVersion("0.9.2-beta")
        )
        assertEquals(
            UpdateChannel.STABLE,
            UpdateChannel.defaultForVersion("0.9.2")
        )
    }
}