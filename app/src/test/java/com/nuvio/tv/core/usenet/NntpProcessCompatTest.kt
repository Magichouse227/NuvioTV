package com.nuvio.tv.core.usenet

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class NntpProcessCompatTest {
    private class LegacyProcess : Process() {
        var running = true
        override fun exitValue(): Int {
            if (running) throw IllegalThreadStateException()
            return 0
        }
        override fun destroy() { running = false }
        override fun waitFor(): Int = error("Must not block")
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun isAlive(): Boolean = throw NoSuchMethodError("Android 24")
        override fun destroyForcibly(): Process = throw NoSuchMethodError("Android 24")
        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean =
            throw NoSuchMethodError("Android 24")
    }

    @Test fun usesOnlyLegacyMethods() {
        val process = LegacyProcess()
        assertTrue(NntpProcessCompat.isAlive(process))
        assertFalse(NntpProcessCompat.waitForExit(process, 0))
        process.destroy()
        assertFalse(NntpProcessCompat.isAlive(process))
        assertTrue(NntpProcessCompat.waitForExit(process, 10))
        assertFalse(NntpProcessCompat.isAlive(null))
    }

    @Test fun waitingIsBounded() {
        val start = System.nanoTime()
        assertFalse(NntpProcessCompat.waitForExit(LegacyProcess(), 30))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000)
    }

    @Test fun interruptionIsNotSwallowed() {
        Thread.currentThread().interrupt()
        try {
            NntpProcessCompat.waitForExit(LegacyProcess(), 1000)
            fail("Expected interruption")
        } catch (_: InterruptedException) {
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}