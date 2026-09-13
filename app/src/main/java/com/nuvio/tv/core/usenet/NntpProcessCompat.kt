package com.nuvio.tv.core.usenet

/** java.lang.Process's newer lifecycle methods do not exist on Android 24/25. */
internal object NntpProcessCompat {
    fun isAlive(process: Process?): Boolean {
        if (process == null) return false
        return try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    @Throws(InterruptedException::class)
    fun waitForExit(process: Process, timeoutMillis: Long): Boolean {
        val started = System.nanoTime()
        val timeoutNanos = timeoutMillis.coerceAtLeast(0) * 1_000_000L
        while (isAlive(process)) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val remainingNanos = timeoutNanos - (System.nanoTime() - started)
            if (remainingNanos <= 0) return false
            Thread.sleep((remainingNanos / 1_000_000L).coerceIn(1, 25))
        }
        return true
    }
}