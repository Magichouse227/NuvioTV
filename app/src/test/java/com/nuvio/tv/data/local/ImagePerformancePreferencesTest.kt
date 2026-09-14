package com.nuvio.tv.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePerformancePreferencesTest {

    @Test
    fun `reduce home effects follows low RAM default when no override exists`() {
        assertTrue(resolveReduceHomeEffects(lowRamDevice = true, override = null))
        assertFalse(resolveReduceHomeEffects(lowRamDevice = false, override = null))
    }

    @Test
    fun `explicit reduce home effects value overrides device default`() {
        assertTrue(resolveReduceHomeEffects(lowRamDevice = false, override = true))
        assertFalse(resolveReduceHomeEffects(lowRamDevice = true, override = false))
    }
}