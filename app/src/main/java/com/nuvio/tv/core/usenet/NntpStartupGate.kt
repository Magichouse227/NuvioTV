package com.nuvio.tv.core.usenet

import kotlinx.coroutines.sync.Mutex

/** Repeated taps must neither queue a retry nor cancel the user's original selection. */
internal class NntpStartupGate {
    private val mutex = Mutex()

    suspend fun <T> run(start: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        return try {
            start()
        } finally {
            mutex.unlock()
        }
    }
}