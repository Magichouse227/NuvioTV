package com.nuvio.tv.core.usenet

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NntpServiceLifecycleTest {

    @Test
    fun cancellationDuringCommittedCreateCleansLateSessionWithoutPublishing() = runTest {
        val createStarted = CompletableDeferred<Unit>()
        val allowResponse = CompletableDeferred<Unit>()
        val cleaned = mutableListOf<String>()
        var published = false

        val start = launch {
            createNntpSessionSafely(
                create = { onCreated ->
                    withContext(NonCancellable) {
                        onCreated("late-session")
                        createStarted.complete(Unit)
                        allowResponse.await()
                    }
                    "stream-url"
                },
                publish = { published = true },
                cleanup = { cleaned += it }
            )
        }

        createStarted.await()
        start.cancel()
        assertFalse(start.isCompleted)
        allowResponse.complete(Unit)
        start.join()

        assertTrue(start.isCancelled)
        assertFalse(published)
        assertEquals(listOf("late-session"), cleaned)
    }

    @Test
    fun stopGenerationDuringCommittedCreateCleansSessionAndRejectsPublish() = runTest {
        val createStarted = CompletableDeferred<Unit>()
        val allowResponse = CompletableDeferred<Unit>()
        val cleaned = mutableListOf<String>()
        var generation = 1
        var published = false

        val start = launch {
            try {
                createNntpSessionSafely(
                    create = { onCreated ->
                        withContext(NonCancellable) {
                            onCreated("superseded-session")
                            createStarted.complete(Unit)
                            allowResponse.await()
                        }
                        "stream-url"
                    },
                    publish = {
                        if (generation != 1) {
                            throw CancellationException("superseded")
                        }
                        published = true
                    },
                    cleanup = { cleaned += it }
                )
            } catch (_: CancellationException) {
                // A stop invalidates the generation and uses cancellation semantics for the old
                // start, just like NntpService.ensureCurrentGeneration.
            }
        }

        createStarted.await()
        generation = 2
        allowResponse.complete(Unit)
        start.join()

        assertFalse(published)
        assertEquals(listOf("superseded-session"), cleaned)
    }
}