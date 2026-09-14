package com.nuvio.tv.core.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupTimingMarkerBufferTest {
    @Test
    fun `records each fixed marker once and clamps a clock anomaly`() {
        val markers = StartupTimingMarkerBuffer(maxMarkers = 2)

        assertTrue(markers.record("cold_start", -20L))
        assertFalse(markers.record("cold_start", 40L))
        assertTrue(markers.record("first_frame", 55L))

        assertEquals(
            mapOf("cold_start" to 0L, "first_frame" to 55L),
            markers.snapshot()
        )
    }

    @Test
    fun `bounded marker buffer rejects later values`() {
        val markers = StartupTimingMarkerBuffer(maxMarkers = 1)

        assertTrue(markers.record("cold_start", 1L))
        assertFalse(markers.record("first_interactive", 2L))
        assertEquals(1, markers.snapshot().size)
    }
}