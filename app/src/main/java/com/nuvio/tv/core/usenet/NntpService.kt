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
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a session ID observable across the cancellation boundary around a blocking create call.
 * The create implementation must invoke [onCreated] from its non-cancellable IO section.
 */
internal suspend fun <T> createNntpSessionSafely(
    create: suspend (onCreated: (String) -> Unit) -> T,
    publish: suspend (T) -> Unit,
    cleanup: suspend (String) -> Unit
): T {
    val createdSessionId = AtomicReference<String?>()
    try {
        val result = create { id -> createdSessionId.set(id) }
        currentCoroutineContext().ensureActive()
        publish(result)
        createdSessionId.set(null)
        return result
    } catch (error: CancellationException) {
        createdSessionId.getAndSet(null)?.let { id ->
            withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { cleanup(id) }
            }
        }
        throw error
    } catch (error: Exception) {
        createdSessionId.getAndSet(null)?.let { id ->
            withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { cleanup(id) }
            }
        }
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
    private var generation = 0L
    private var activeStartJob: Job? = null
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
        val start = beginStart(callerJob)
        val startupStartedAt = SystemClock.elapsedRealtime()
        try {
            // A previous start can still be finishing a non-cancellable create request. Wait for
            // that coroutine and its session release before starting another request.
            start.previousStart?.let { previous ->
                if (previous !== callerJob) previous.join()
            }
            start.prewarmStop?.join()
            start.previousCleanup?.join()

            require(nzbUrl.isNotBlank()) { "NZB URL is blank" }
            require(servers.isNotEmpty()) { "NNTP servers are missing" }
            servers.forEach(NntpServerConfig::parse)
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
                            nzbUrl = nzbUrl,
                            servers = servers,
                            fileIdx = fileIdx,
                            fileMustInclude = fileMustInclude,
                            season = season,
                            episode = episode
                        ),
                        onSessionCreated = onCreated
                    )
                },
                publish = { created ->
                    // createSession deliberately completes its bounded IO call in NonCancellable.
                    // The cancellation/generation check must happen before this result is visible.
                    ensureCurrentGeneration(start.generation)
                    publishSession(start.generation, created)
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
            DiagnosticLog.recordThrowable("nntp_failure", error)
            val message = error.message ?: "Failed to start NNTP stream"
            publishErrorIfCurrent(start.generation, message)
            throw if (error is NntpException) error else NntpException(message)
        } finally {
            synchronized(lifecycleLock) {
                if (activeStartJob === callerJob) {
                    activeStartJob = null
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
        val previousCleanup: Job?,
        val prewarmStop: Job?
    )

    /**
     * Invalidate the old generation before a new request can produce a session ID. The old start
     * remains represented by [previousStart] so a replacement cannot race its late cleanup.
     */
    private fun beginStart(callerJob: Job): StartReservation = synchronized(lifecycleLock) {
        val previousStart = activeStartJob?.takeIf { it !== callerJob }
        generation++
        val nextGeneration = generation
        activeStartJob = callerJob

        statsJob?.cancel()
        statsJob = null
        val sessionId = currentSessionId
        currentSessionId = null
        scheduleSessionCleanupLocked(sessionId)
        val previousCleanup = cleanupJob

        val prewarmStop = prewarmShutdownJob
        prewarmShutdownJob = null
        previousStart?.cancel()
        prewarmStop?.cancel()
        _state.value = NntpState.Idle
        StartReservation(nextGeneration, previousStart, previousCleanup, prewarmStop)
    }

    private fun scheduleSessionCleanupLocked(sessionId: String?) {
        if (sessionId == null) return
        val previousCleanup = cleanupJob
        cleanupJob = scope.launch {
            previousCleanup?.join()
            withContext(kotlinx.coroutines.NonCancellable) {
                api.deleteSession(sessionId)
            }
            DiagnosticLog.record("nntp", "event=session_released")
        }
    }

    private suspend fun cleanupLateSession(sessionId: String?) {
        if (sessionId == null) return
        withContext(kotlinx.coroutines.NonCancellable) {
            try {
                api.deleteSession(sessionId)
                DiagnosticLog.record("nntp", "event=session_released_late")
            } catch (error: Exception) {
                Log.w(TAG, "Failed to release late NNTP session", error)
            }
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

    private fun publishSession(expectedGeneration: Long, session: NntpSession) {
        synchronized(lifecycleLock) {
            if (!isGenerationCurrent(expectedGeneration)) {
                throw CancellationException("NNTP stream start was superseded")
            }
            currentSessionId = session.id
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

    private fun publishErrorIfCurrent(expectedGeneration: Long, message: String) {
        synchronized(lifecycleLock) {
            if (isGenerationCurrent(expectedGeneration)) {
                _state.value = NntpState.Error(message)
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
