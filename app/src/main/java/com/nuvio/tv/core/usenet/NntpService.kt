package com.nuvio.tv.core.usenet

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.diagnostics.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Cancellation can enqueue cleanup while the previous start is finishing. Snapshot it afterward. */
internal suspend fun awaitNntpStartCleanup(previousStart: Job?, latestCleanup: () -> Job?) {
    previousStart?.join()
    latestCleanup()?.join()
}

/**
 * Keeps a session ID observable across the cancellation boundary around an in-flight create call.
 * The create implementation must invoke [onCreated] once it has a protocol identity, before
 * enqueueing a cancellable request. [cleanup] must only enqueue cleanup work; it is intentionally
 * never awaited from a cancelled OkHttp callback.
 */
internal suspend fun <T> createNntpSessionSafely(
    create: suspend (onCreated: (String) -> Unit) -> T,
    publish: suspend (T) -> Unit,
    cleanup: (String) -> Unit
): T {
    val ownershipLock = Any()
    val createdSessionIds = LinkedHashSet<String>()
    val cleanedSessionIds = HashSet<String>()
    var abandoned = false
    var completed = false

    fun dispatchCleanup(sessionId: String) {
        val shouldCleanup = synchronized(ownershipLock) {
            cleanedSessionIds.add(sessionId)
        }
        if (!shouldCleanup) return
        cleanup(sessionId)
    }

    val onCreated: (String) -> Unit = { sessionId ->
        val cleanupNow = synchronized(ownershipLock) {
            if (abandoned || completed) {
                true
            } else {
                createdSessionIds += sessionId
                false
            }
        }
        if (cleanupNow) dispatchCleanup(sessionId)
    }

    fun cleanupAbandonedSessions() {
        val sessionIds = synchronized(ownershipLock) {
            abandoned = true
            createdSessionIds.toList().also {
                createdSessionIds.clear()
            }
        }
        sessionIds.forEach(::dispatchCleanup)
    }

    try {
        val result = create(onCreated)
        currentCoroutineContext().ensureActive()
        publish(result)
        synchronized(ownershipLock) {
            completed = true
            createdSessionIds.clear()
        }
        return result
    } catch (error: CancellationException) {
        cleanupAbandonedSessions()
        throw error
    } catch (error: Exception) {
        cleanupAbandonedSessions()
        throw error
    }
}

@Singleton
class NntpService @Inject constructor(
    private val binary: NntpEngineBinary,
    private val api: NntpEngineApi
) {
    companion object {
        private const val TAG = "NntpService"
        private const val PREWARM_IDLE_TIMEOUT_MS = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<NntpState>(NntpState.Idle)
    val state: StateFlow<NntpState> = _state.asStateFlow()

    private val lifecycleLock = Any()
    @Volatile
    private var currentSessionId: String? = null
    @Volatile
    private var currentStreamUrl: String? = null
    private var currentStartRequest: StartParameters? = null
    @Volatile
    private var generation = 0L
    private var activeStartJob: Job? = null
    private var activeStartRequest: StartParameters? = null
    private var statsJob: Job? = null
    private var prewarmShutdownJob: Job? = null
    private var cleanupJob: Job? = null

    suspend fun prewarm() = withContext(Dispatchers.IO) {
        synchronized(lifecycleLock) {
            if (currentSessionId != null || activeStartJob != null) return@withContext
        }
        val startedAt = SystemClock.elapsedRealtime()
        binary.start()
        Log.i(TAG, "NNTP engine prewarm completed in ${SystemClock.elapsedRealtime() - startedAt} ms")
        synchronized(lifecycleLock) {
            // A stream may have started while the binary was coming up. Never schedule an idle
            // stop that could race that in-flight session.
            if (currentSessionId != null || activeStartJob != null) return@withContext
            prewarmShutdownJob?.cancel()
            val prewarmGeneration = generation
            prewarmShutdownJob = scope.launch {
                delay(PREWARM_IDLE_TIMEOUT_MS)
                val shouldStop = synchronized(lifecycleLock) {
                    generation == prewarmGeneration &&
                        currentSessionId == null &&
                        activeStartJob == null
                }
                if (shouldStop) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        binary.stop()
                    }
                }
            }
        }
    }

    suspend fun startStream(
        nzbUrl: String,
        servers: List<String>,
        fileIdx: Int?,
        fileMustInclude: String?,
        season: Int?,
        episode: Int?
    ): String = withContext(Dispatchers.IO) {
        val callerJob = currentCoroutineContext()[Job]
            ?: error("NNTP stream start requires a coroutine job")
        val parameters = StartParameters(
            nzbUrl = nzbUrl,
            servers = servers.toList(),
            fileIdx = fileIdx,
            fileMustInclude = fileMustInclude,
            season = season,
            episode = episode
        )
        val start = beginStart(callerJob, parameters)
        start.existingStreamUrl?.let { return@withContext it }
        if (start.duplicate) {
            // A duplicate tap must not cancel and recreate the request that is already doing the
            // work. The UI normally prevents this with its own mutex; this safe error keeps the
            // first request authoritative if two callers still race.
            throw NntpException("NNTP stream start is already in progress")
        }
        val startupStartedAt = SystemClock.elapsedRealtime()
        try {
            // A previous start can still be finishing its cancellation and session release. Wait
            // for that coroutine before starting another request.
            start.prewarmStop?.join()
            awaitNntpStartCleanup(start.previousStart?.takeIf { it !== callerJob }) {
                synchronized(lifecycleLock) { cleanupJob }
            }

            require(nzbUrl.isNotBlank()) { "The addon did not provide an NZB link. Try another source." }
            try {
                require(servers.isNotEmpty())
                servers.forEach(NntpServerConfig::parse)
            } catch (error: IllegalArgumentException) {
                throw NntpException(NntpErrorMessages.PROVIDER_SETUP)
            }
            publishConnecting(start.generation)
            DiagnosticLog.record("nntp", "event=session_start providers=${servers.size}")

            val binaryStartedAt = SystemClock.elapsedRealtime()
            binary.start()
            ensureCurrentGeneration(start.generation)
            Log.i(TAG, "NNTP engine ready in ${SystemClock.elapsedRealtime() - binaryStartedAt} ms")
            val sessionStartedAt = SystemClock.elapsedRealtime()
            val session = createNntpSessionSafely(
                create = { onCreated ->
                    api.createSession(
                        NntpSessionRequest(
                            nzbUrl = parameters.nzbUrl,
                            servers = parameters.servers,
                            fileIdx = parameters.fileIdx,
                            fileMustInclude = parameters.fileMustInclude,
                            season = parameters.season,
                            episode = parameters.episode
                        ),
                        onSessionCreated = onCreated
                    )
                },
                publish = { created ->
                    // The cancellation/generation check must happen before this result is visible.
                    ensureCurrentGeneration(start.generation)
                    publishSession(start.generation, parameters, created)
                },
                cleanup = { id -> cleanupLateSession(id) }
            )
            Log.i(TAG, "NNTP session created in ${SystemClock.elapsedRealtime() - sessionStartedAt} ms")
            DiagnosticLog.record(
                "nntp",
                "event=session_ready elapsed_ms=${SystemClock.elapsedRealtime() - sessionStartedAt}"
            )
            Log.i(TAG, "NNTP startup completed in ${SystemClock.elapsedRealtime() - startupStartedAt} ms")
            session.streamUrl
        } catch (error: CancellationException) {
            DiagnosticLog.record("nntp", "event=session_cancelled")
            publishIdleIfCurrent(start.generation)
            throw error
        } catch (error: Exception) {
            if (error is NntpException && error.rateLimit != null) {
                val limit = error.rateLimit
                val nowElapsedMs = SystemClock.elapsedRealtime()
                DiagnosticLog.record(
                    "nntp",
                    "event=rate_limited status=${limit.httpStatus} " +
                        "retry_known=${if (limit.retryAfterKnown) 1 else 0} " +
                        "retry_after_s=${limit.retryAfterRemainingSeconds(nowElapsedMs) ?: -1} " +
                        "cooldown_s=${limit.cooldownRemainingSeconds(nowElapsedMs)}"
                )
            } else {
                DiagnosticLog.recordThrowable("nntp_failure", error)
            }
            val message = error.message ?: "Failed to start NNTP stream"
            publishErrorIfCurrent(
                expectedGeneration = start.generation,
                message = message,
                rateLimit = (error as? NntpException)?.rateLimit
            )
            throw if (error is NntpException) error else NntpException(message)
        } finally {
            synchronized(lifecycleLock) {
                if (activeStartJob === callerJob) {
                    activeStartJob = null
                    activeStartRequest = null
                }
            }
        }
    }

    fun stopStream() {
        val activeJob = synchronized(lifecycleLock) {
            generation++
            statsJob?.cancel()
            statsJob = null
            val sessionId = currentSessionId
            currentSessionId = null
            currentStreamUrl = null
            currentStartRequest = null
            activeStartRequest = null
            scheduleSessionCleanupLocked(sessionId)
            _state.value = NntpState.Idle
            // Keep this reference until the cancelled start runs its late-session cleanup. A
            // replacement start will join it instead of racing another POST.
            activeStartJob
        }
        activeJob?.cancel()
    }

    /** Leaves playback alive for an external player while dropping Kotlin ownership. */
    fun detachStream() {
        val activeJob = synchronized(lifecycleLock) {
            generation++
            statsJob?.cancel()
            statsJob = null
            currentSessionId = null
            currentStreamUrl = null
            currentStartRequest = null
            activeStartRequest = null
            _state.value = NntpState.Idle
            activeStartJob
        }
        activeJob?.cancel()
        DiagnosticLog.record("nntp", "event=external_player_detached")
    }

    suspend fun shutdown() {
        val callerJob = currentCoroutineContext()[Job]
        stopStream()
        withContext(kotlinx.coroutines.NonCancellable) {
            val activeJob = synchronized(lifecycleLock) {
                prewarmShutdownJob?.cancel()
                prewarmShutdownJob
            }
            activeJob?.join()
            val startJob = synchronized(lifecycleLock) { activeStartJob }
            if (startJob != null && startJob !== callerJob) {
                startJob.join()
            }
            val releaseJob = synchronized(lifecycleLock) { cleanupJob }
            releaseJob?.join()
            synchronized(lifecycleLock) {
                _state.value = NntpState.Idle
            }
            binary.stop()
        }
    }

    fun ownsLocalUrl(url: String?): Boolean =
        url?.startsWith("${binary.baseUrl}/v1/sessions/") == true

    private data class StartReservation(
        val generation: Long,
        val previousStart: Job?,
        val prewarmStop: Job?,
        val duplicate: Boolean = false,
        val existingStreamUrl: String? = null
    )

    private data class StartParameters(
        val nzbUrl: String,
        val servers: List<String>,
        val fileIdx: Int?,
        val fileMustInclude: String?,
        val season: Int?,
        val episode: Int?
    )

    /**
     * Invalidate the old generation before a new request can produce a session ID. The old start
     * remains represented by [previousStart] so a replacement cannot race its late cleanup.
     */
    private fun beginStart(
        callerJob: Job,
        parameters: StartParameters
    ): StartReservation = synchronized(lifecycleLock) {
        if (currentSessionId != null && currentStartRequest == parameters) {
            return StartReservation(
                generation = generation,
                previousStart = null,
                prewarmStop = null,
                existingStreamUrl = currentStreamUrl
            )
        }
        if (activeStartJob != null &&
            activeStartJob !== callerJob &&
            activeStartRequest == parameters
        ) {
            return StartReservation(
                generation = generation,
                previousStart = null,
                prewarmStop = null,
                duplicate = true
            )
        }
        val previousStart = activeStartJob?.takeIf { it !== callerJob }
        generation++
        val nextGeneration = generation
        activeStartJob = callerJob
        activeStartRequest = parameters

        statsJob?.cancel()
        statsJob = null
        val sessionId = currentSessionId
        currentSessionId = null
        currentStreamUrl = null
        currentStartRequest = null
        scheduleSessionCleanupLocked(sessionId)

        val prewarmStop = prewarmShutdownJob
        prewarmShutdownJob = null
        previousStart?.cancel()
        prewarmStop?.cancel()
        _state.value = NntpState.Idle
        StartReservation(nextGeneration, previousStart, prewarmStop)
    }

    private fun scheduleSessionCleanupLocked(
        sessionId: String?,
        late: Boolean = false
    ) {
        if (sessionId == null) return
        val previousCleanup = cleanupJob
        cleanupJob = scope.launch {
            previousCleanup?.join()
            withContext(kotlinx.coroutines.NonCancellable) {
                api.deleteSession(sessionId)
            }
            DiagnosticLog.record(
                "nntp",
                if (late) "event=session_released_late" else "event=session_released"
            )
        }
    }

    private fun cleanupLateSession(sessionId: String?) {
        if (sessionId == null) return
        synchronized(lifecycleLock) {
            // createNntpSessionSafely calls this from either the cancelled caller or an OkHttp
            // callback. Queue it on the same serialized cleanup chain used by stopStream; never
            // make either of those callers await a potentially slow DELETE.
            scheduleSessionCleanupLocked(sessionId, late = true)
        }
    }

    private suspend fun ensureCurrentGeneration(expectedGeneration: Long) {
        currentCoroutineContext().ensureActive()
        if (!isGenerationCurrent(expectedGeneration)) {
            throw CancellationException("NNTP stream start was superseded")
        }
    }

    private fun isGenerationCurrent(expectedGeneration: Long): Boolean =
        generation == expectedGeneration

    private fun publishConnecting(expectedGeneration: Long) {
        synchronized(lifecycleLock) {
            if (!isGenerationCurrent(expectedGeneration)) {
                throw CancellationException("NNTP stream start was superseded")
            }
            _state.value = NntpState.Connecting
        }
    }

    private fun publishSession(
        expectedGeneration: Long,
        parameters: StartParameters,
        session: NntpSession
    ) {
        synchronized(lifecycleLock) {
            if (!isGenerationCurrent(expectedGeneration)) {
                throw CancellationException("NNTP stream start was superseded")
            }
            currentSessionId = session.id
            currentStreamUrl = session.streamUrl
            currentStartRequest = parameters
            _state.value = NntpState.Streaming(localUrl = session.streamUrl)
            startStatsPollingLocked(expectedGeneration, session)
        }
    }

    private fun publishIdleIfCurrent(expectedGeneration: Long) {
        synchronized(lifecycleLock) {
            if (isGenerationCurrent(expectedGeneration)) {
                _state.value = NntpState.Idle
            }
        }
    }

    private fun publishErrorIfCurrent(
        expectedGeneration: Long,
        message: String,
        rateLimit: NntpRateLimit?
    ) {
        synchronized(lifecycleLock) {
            if (isGenerationCurrent(expectedGeneration)) {
                _state.value = NntpState.Error(message = message, rateLimit = rateLimit)
            }
        }
    }

    private fun startStatsPollingLocked(expectedGeneration: Long, session: NntpSession) {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (
                isActive &&
                generation == expectedGeneration &&
                currentSessionId == session.id
            ) {
                try {
                    val stats = api.getStats(session.id)
                    if (stats != null) {
                        synchronized(lifecycleLock) {
                            if (
                                generation == expectedGeneration &&
                                currentSessionId == session.id
                            ) {
                                val current = _state.value
                                if (current is NntpState.Streaming) {
                                    _state.value = current.copy(
                                        downloadedBytes = stats.downloadedBytes,
                                        downloadSpeed = stats.downloadSpeed,
                                        connections = stats.connections
                                    )
                                }
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                }
                delay(1_000L)
            }
        }
    }
}
