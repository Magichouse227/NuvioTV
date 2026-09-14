package com.nuvio.tv.core.usenet

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NntpStartupGateTest {
    @Test
    fun `repeated taps do not queue downloads or replace the selected stream`() = runBlocking {
        val gate = NntpStartupGate()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var downloads = 0
        var selected = ""
        val first = async {
            gate.run {
                downloads++
                selected = "original"
                started.complete(Unit)
                release.await()
                selected
            }
        }
        started.await()
        repeat(10) {
            assertNull(gate.run { downloads++; selected = "replacement" })
        }
        release.complete(Unit)
        assertEquals("original", first.await())
        assertEquals(1, downloads)
    }

    @Test
    fun `cancellation releases gate without queued retries`() = runBlocking {
        val gate = NntpStartupGate()
        val started = CompletableDeferred<Unit>()
        val first = async {
            gate.run {
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        started.await()
        first.cancelAndJoin()
        assertEquals("manual retry", gate.run { "manual retry" })
    }

    @Test
    fun `failure releases gate for later manual selection`() = runBlocking {
        val gate = NntpStartupGate()
        runCatching { gate.run { throw NntpException("failed") } }
        assertEquals("manual retry", gate.run { "manual retry" })
    }
}