package com.nuvio.tv.core.performance

import org.junit.Assert.*
import org.junit.Test

class TvMemoryPolicyTest {
    @Test fun fireHdUsesBoundedBudgetsEvenWhenRamIsUnavailable() {
        val policy = TvMemoryPolicy.detect("Amazon", "AFTSS", 0, false)
        assertTrue(policy.lightweight)
        assertEquals(48, policy.playerBufferMb)
        assertEquals(16L * 1024 * 1024, policy.imageCacheBytes)
        assertEquals(1, policy.imageDecoders)
    }

    @Test fun physicalRamAndAndroidLowRamFlagBothSelectLightweightPolicy() {
        assertTrue(TvMemoryPolicy.detect("Other", "TV", 1024L * 1024 * 1024, false).lightweight)
        assertTrue(TvMemoryPolicy.detect("Other", "TV", 2048L * 1024 * 1024, true).lightweight)
    }

    @Test fun unknownOrLargerDevicesKeepStandardPolicy() {
        assertFalse(TvMemoryPolicy.detect("Other", "TV", 0, false).lightweight)
        assertFalse(TvMemoryPolicy.detect("Amazon", "Other", 2048L * 1024 * 1024, false).lightweight)
    }
}
