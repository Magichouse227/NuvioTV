package com.nuvio.tv.core.usenet

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
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
    fun replacementWaitsForCleanupQueuedByCancelledStart() = runTest {
        val started = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var cleanupJob: Job? = null
        var replacementCreated = false
        val cleanupScope = backgroundScope
        val previous = launch {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                cleanupJob = cleanupScope.launch {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                }
            }
        }
        started.await()
        previous.cancel()
        val replacement = launch {
            awaitNntpStartCleanup(previous) { cleanupJob }
            replacementCreated = true
        }
        cleanupStarted.await()
        assertFalse(replacementCreated)
        releaseCleanup.complete(Unit)
        replacement.join()
        assertTrue(replacementCreated)
    }

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

    @Test
    fun callbackAfterCallerCancellationIsCleanedByAbandonedOwnership() = runTest {
        val callbackReady = CompletableDeferred<Unit>()
        val cleaned = mutableListOf<String>()
        lateinit var lateOnCreated: (String) -> Unit

        val start = launch {
            try {
                createNntpSessionSafely(
                    create = { onCreated ->
                        lateOnCreated = onCreated
                        callbackReady.complete(Unit)
                        "stream-url"
                    },
                    publish = {
                        // Keep the caller in the ownership window until cancellation wins.
                        kotlinx.coroutines.awaitCancellation()
                    },
                    cleanup = { cleaned += it }
                )
            } catch (_: CancellationException) {
                // Expected: cancellation marks the ownership callback as abandoned.
            }
        }

        callbackReady.await()
        start.cancel()
        start.join()
        assertTrue(cleaned.isEmpty())

        // Simulates an OkHttp response callback that was already queued when call.cancel() ran.
        lateOnCreated("late-after-cancel")
        assertEquals(listOf("late-after-cancel"), cleaned)
    }
}