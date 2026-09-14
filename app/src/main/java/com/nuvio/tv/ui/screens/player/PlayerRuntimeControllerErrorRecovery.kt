package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import com.nuvio.tv.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_STARTUP_AUTO_RETRIES = 2
private const val MAX_AUTO_RETRIES = 2
private const val RETRY_DELAY_MS = 1_500L
private const val STABLE_PROGRESS_RESET_DELAY_MS = 5_000L

internal fun PlayerRuntimeController.showRecoveryOverlay() {
    _uiState.update { state ->
        state.copy(
            error = null,
            isBuffering = true,
            showLoadingOverlay = true,
            loadingMessage = context.getString(R.string.player_loading_buffering),
            showPauseOverlay = false
        )
    }
}

internal fun PlayerRuntimeController.attemptStartupRecovery(
    error: PlaybackException,
    detailedError: String
): Boolean {
    if (currentVideoTrackIsLikelyVc1 ||
        Vc1VideoFormatHeuristics.isLikelyVc1(streamName = _uiState.value.currentStreamName ?: streamName)
    ) {
        return false
    }
    if (hasRenderedFirstFrame) return false
    if (!isRetryablePlaybackError(error)) return false

    val savedPosition = reliableRecoveryPosition()
    if (!PlayerRuntimeErrorRecoveryPolicy.shouldRetry(
            PlayerRuntimeErrorRecoveryPolicy.RetryInput(
                retryCount = startupRetryCount,
                maxRetries = MAX_STARTUP_AUTO_RETRIES,
                reliablePositionMs = savedPosition,
                failedPositionMs = errorRecoveryFailurePositionMs
            )
        )
    ) {
        return false
    }

    val paused = userPausedManually
    val attempt = startupRetryCount
    startupRetryCount++
    errorRecoveryFailurePositionMs = savedPosition ?: 0L

    Log.w(
        PlayerRuntimeController.TAG,
        "Startup recovery ${attempt + 1}/$MAX_STARTUP_AUTO_RETRIES after ${RETRY_DELAY_MS}ms " +
            "at position=${savedPosition ?: 0L}ms for: $detailedError"
    )

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(
                error = null,
                isBuffering = true,
                showLoadingOverlay = it.loadingOverlayEnabled,
                loadingMessage = context.getString(R.string.player_loading_buffering),
                showPauseOverlay = false
            )
        }

        delay(RETRY_DELAY_MS)

        releasePlayer(flushPlaybackState = false)
        if (savedPosition != null) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }
    return true
}

/**
 * Determines whether the given [PlaybackException] is transient and worth retrying.
 *
 * Retryable errors include source/IO errors, recoverable parsing glitches, and unexpected runtime
 * exceptions that commonly occur after pause/resume or seek on flaky streams. Known malformed
 * containers and explicit unsupported formats are considered fatal.
 */
internal fun isRetryablePlaybackError(error: PlaybackException): Boolean {
    if (error.isKnownNonRetryableRecoveryError()) return false

    return when (error.errorCode) {
        // --- Source / IO errors (the 2xxx range) ---
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, -> true

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val httpCause = error.findCauseOfType<HttpDataSource.InvalidResponseCodeException>()
            if (httpCause != null) {
                val code = httpCause.responseCode
                !(code == 400 || code == 401 || code == 403 || code == 404 || code == 410)
            } else {
                true
            }
        }
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,

        // --- Decoder errors (often transient after pause/resume on some hardware) ---
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true

        // --- Behind-the-scenes / unexpected errors (often IllegalStateException / NPE) ---
        PlaybackException.ERROR_CODE_UNSPECIFIED -> {
            val cause = error.cause
            cause is IllegalStateException || cause is NullPointerException
        }

        else -> false
    }
}

/**
 * Audio-track failures that the safe-audio → audio-disabled fallback ladder can recover from.
 *
 * - [PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED] (5001): the AudioTrack could not be
 *   created (e.g. the requested passthrough/offload encoding is not actually accepted by the sink).
 * - [PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED] (5002): a write to the AudioTrack
 *   failed, most commonly with `AudioTrack.ERROR_DEAD_OBJECT` (-6) when an HDMI/audio-route
 *   renegotiation invalidates an E-AC-3/AC-3 passthrough or offload track mid-playback.
 *
 * Both are remedied by re-selecting audio with tunneling/passthrough off and the channel count
 * constrained to the device's capabilities (safe-audio mode), or by dropping audio entirely — so
 * a write failure must take the same recovery path as an init failure rather than landing on the
 * fatal error screen.
 *
 * [combinedMessage] is the concatenated exception/cause messages; the string checks are a safety
 * net for devices that surface the same failure under a generic error code.
 */
internal fun isAudioTrackFailure(errorCode: Int, combinedMessage: String): Boolean {
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return true
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED) return true
    return combinedMessage.contains("audiotrack init failed", ignoreCase = true) ||
        combinedMessage.contains("audiotrack write failed", ignoreCase = true)
}

internal fun PlaybackException.findInvalidResponseCodeException(): HttpDataSource.InvalidResponseCodeException? {
    for (current in ThrowableGraphTraversal.walk(this)) {
        if (current is HttpDataSource.InvalidResponseCodeException) return current
    }
    return null
}

/**
 * Malformed Matroska/EBML and explicit unsupported-format failures are deterministic for the
 * selected source. Retrying the same bytes or silently switching engines only repeats the same
 * failure, so these are allowed to fall through to the normal fatal-error presentation.
 */
internal fun PlaybackException.isKnownNonRetryableRecoveryError(
    additionalMessage: String? = null
): Boolean {
    val unsupportedFormatErrorCode =
        errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
            errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
    val malformedContainerErrorCode =
        errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
    return recoveryErrorClassification(
        additionalMessages = listOfNotNull(additionalMessage),
        hasUnsupportedFormatErrorCode = unsupportedFormatErrorCode,
        hasMalformedContainerErrorCode = malformedContainerErrorCode
    ) != PlayerRuntimeErrorRecoveryPolicy.ErrorClassification.Retryable
}

internal fun isKnownNonRetryableRecoveryMessage(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    return PlayerRuntimeErrorRecoveryPolicy.classify(
        messages = listOf(message)
    ) != PlayerRuntimeErrorRecoveryPolicy.ErrorClassification.Retryable
}

private fun PlaybackException.recoveryErrorClassification(
    additionalMessages: Iterable<String> = emptyList(),
    hasUnsupportedFormatErrorCode: Boolean =
        errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
            errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    hasMalformedContainerErrorCode: Boolean =
        errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
): PlayerRuntimeErrorRecoveryPolicy.ErrorClassification {
    return PlayerRuntimeErrorRecoveryPolicy.classify(
        throwable = this,
        additionalMessages = additionalMessages,
        hasUnsupportedFormatErrorCode = hasUnsupportedFormatErrorCode,
        hasMalformedContainerErrorCode = hasMalformedContainerErrorCode
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlaybackException.toDisplayMessage(context: android.content.Context): String {
    val responseException = findInvalidResponseCodeException()
    if (responseException != null) {
        val code = responseException.responseCode
        val statusText = responseException.responseMessage?.takeIf { it.isNotBlank() }
        val providerHint = when (code) {
            400 -> context.getString(com.nuvio.tv.R.string.player_error_stream_blocked)
            401 -> context.getString(com.nuvio.tv.R.string.player_error_stream_expired)
            403 -> context.getString(com.nuvio.tv.R.string.player_error_stream_blocked)
            404 -> context.getString(com.nuvio.tv.R.string.player_error_stream_removed)
            410 -> context.getString(com.nuvio.tv.R.string.player_error_stream_expired)
            429 -> context.getString(com.nuvio.tv.R.string.player_error_stream_rate_limited)
            500, 502, 503, 504 -> context.getString(com.nuvio.tv.R.string.player_error_stream_unavailable)
            else -> ""
        }
        return buildString {
            append("HTTP $code")
            statusText?.let { append(" $it") }
            append(" [$errorCodeName]")
            append(providerHint)
        }
    }

    // Check for unrecognized format (provider returned non-video content)
    val isUnrecognizedFormat = findCauseOfType<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null
    if (isUnrecognizedFormat) {
        return context.getString(com.nuvio.tv.R.string.player_error_source_invalid_content, errorCodeName)
    }

    val decoderInit = findCauseOfType<MediaCodecRenderer.DecoderInitializationException>()
    if (decoderInit != null) {
        val decoderMessage = decoderInit.message?.trim()?.takeIf { it.isNotBlank() }
            ?: decoderInit.diagnosticInfo?.trim()?.takeIf { it.isNotBlank() }
        return if (decoderMessage != null) {
            "$decoderMessage [$errorCodeName]"
        } else {
            errorCodeName
        }
    }

    val meaningfulMessage = findMostRelevantCauseMessage() ?: cause?.message ?: message
    return if (meaningfulMessage != null) {
        "$meaningfulMessage [$errorCodeName]"
    } else {
        errorCodeName
    }
}

private inline fun <reified T : Throwable> Throwable.findCauseOfType(): T? {
    for (current in ThrowableGraphTraversal.walk(this)) {
        if (current is T) return current
    }
    return null
}

internal fun Throwable.toDisplayMessage(context: android.content.Context, fallback: String? = null): String {
    val meaningfulMessage = findMostRelevantCauseMessage()
    return meaningfulMessage
        ?: message?.takeIf { it.isNotBlank() }
        ?: fallback
        ?: context.getString(com.nuvio.tv.R.string.player_error_playback_fallback)
}

private fun Throwable.findMostRelevantCauseMessage(): String? {
    val candidates = ThrowableGraphTraversal.walk(this).mapNotNull { throwable ->
        throwable.message
            ?.trim()
            ?.takeIf {
                it.isNotBlank() &&
                    !it.equals("Playback error", ignoreCase = true) &&
                    !it.equals("Source error", ignoreCase = true) &&
                    !it.equals("Unexpected runtime error", ignoreCase = true)
            }
    }.toList()
    return candidates.firstOrNull()
}

/**
 * ExoPlayer can reset its reported position to zero while dispatching an error even though the
 * controller timeline still contains the last useful playhead. Capture that timeline before any
 * release/prepare call so recovery never silently starts from the beginning.
 */
internal fun PlayerRuntimeController.reliableRecoveryPosition(
    currentPositionMs: Long? = currentPlaybackPositionMs()
): Long? {
    return PlayerRuntimeErrorRecoveryPolicy.reliablePositionMs(
        currentPositionMs = currentPositionMs,
        timelinePositionMs = playbackTimeline.value.currentPosition,
        savedPositionMs = lastSavedPosition,
        pendingSeekPositionMs = _uiState.value.pendingSeekPosition
    )
}

/**
 * Attempts an automatic retry of the current stream, preserving the playback position.
 *
 * The first retry re-prepares the current player, and the second retry fully rebuilds it,
 * so recovery stays on the loading overlay until playback succeeds or finally fails.
 *
 * Returns `true` if a retry was scheduled, `false` if the error should be shown to the user.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.attemptAutoRetry(
    error: PlaybackException,
    detailedError: String
): Boolean {
    if (currentVideoTrackIsLikelyVc1 ||
        Vc1VideoFormatHeuristics.isLikelyVc1(streamName = _uiState.value.currentStreamName ?: streamName)
    ) {
        return false
    }
    if (!isRetryablePlaybackError(error)) return false
    if (error.isKnownNonRetryableRecoveryError(additionalMessage = detailedError)) return false

    val savedPosition = reliableRecoveryPosition()
    if (!PlayerRuntimeErrorRecoveryPolicy.shouldRetry(
            PlayerRuntimeErrorRecoveryPolicy.RetryInput(
                retryCount = errorRetryCount,
                maxRetries = MAX_AUTO_RETRIES,
                reliablePositionMs = savedPosition,
                failedPositionMs = errorRecoveryFailurePositionMs
            )
        )
    ) {
        Log.w(
            PlayerRuntimeController.TAG,
            "Auto-retry suppressed: playhead did not move beyond " +
                "${errorRecoveryFailurePositionMs ?: 0L}ms " +
                "(current=${savedPosition ?: 0L}ms)"
        )
        return false
    }

    val paused = userPausedManually
    val attempt = errorRetryCount
    errorRetryCount++
    errorRecoveryFailurePositionMs = savedPosition ?: 0L

    Log.w(
        PlayerRuntimeController.TAG,
        "Auto-retry ${attempt + 1}/$MAX_AUTO_RETRIES after ${RETRY_DELAY_MS}ms " +
            "at position=${savedPosition ?: 0L}ms for: $detailedError"
    )

    // Capture the current position so we can resume after re-init.
    val savedPositionMs = savedPosition ?: 0L
    val isFirstAttempt = attempt == 0

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(
                error = null,
                showLoadingOverlay = if (isFirstAttempt) false else it.loadingOverlayEnabled,
                showPauseOverlay = false
            )
        }

        delay(RETRY_DELAY_MS)

        if (isFirstAttempt) {
            // Lightweight recovery: re-prepare the same source without destroying the player.
            val player = _exoPlayer
            if (player != null) {
                if (savedPositionMs > 0L) {
                    player.seekTo((savedPositionMs - 1).coerceAtLeast(0L))
                }
                player.prepare()
                // Only resume playback if the user hadn't paused.
                player.playWhenReady = !paused
            } else {
                releasePlayer(flushPlaybackState = false)
                if (savedPositionMs > 0L) {
                    _uiState.update { it.copy(pendingSeekPosition = savedPositionMs) }
                }
                initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
            }
        } else {
            // Full teardown — clears any corrupt decoder/internal state.
            releasePlayer(flushPlaybackState = false)
            if (savedPositionMs > 0L) {
                _uiState.update { it.copy(pendingSeekPosition = savedPositionMs) }
            }
            initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
        }
    }
    return true
}

/**
 * Resets the retry counter. Call this whenever playback enters a healthy state
 * (first frame rendered, or user-initiated retry).
 */
internal fun PlayerRuntimeController.resetErrorRetryState() {
    startupRetryCount = 0
    errorRetryCount = 0
    errorRecoveryFailurePositionMs = null
    parsingErrorProbeAttempted = false
    pendingAudioPcmFallbackRebuild = false
    errorRetryJob?.cancel()
    errorRetryJob = null
}

internal fun PlayerRuntimeController.scheduleStableProgressReset() {
    stableProgressResetJob?.cancel()
    stableProgressResetJob = scope.launch {
        delay(STABLE_PROGRESS_RESET_DELAY_MS)
        val player = _exoPlayer ?: return@launch
        val position = reliableRecoveryPosition(currentPositionMs = player.currentPosition)
        val progressed = PlayerRuntimeErrorRecoveryPolicy.hasMeaningfulForwardProgress(
            currentPositionMs = position,
            failedPositionMs = errorRecoveryFailurePositionMs
        )
        if (player.playbackState == Player.STATE_READY && player.isPlaying && progressed) {
            resetErrorRetryState()
        }
    }
}

internal fun PlayerRuntimeController.cancelStableProgressReset() {
    stableProgressResetJob?.cancel()
    stableProgressResetJob = null
}

internal fun PlayerRuntimeController.refreshStableProgressResetGate() {
    if (!hasRenderedFirstFrame) return
    val player = _exoPlayer ?: return
    val healthy = player.playbackState == Player.STATE_READY && player.isPlaying
    if (healthy) {
        if (stableProgressResetJob?.isActive != true) {
            scheduleStableProgressReset()
        }
    } else {
        cancelStableProgressReset()
    }
}

/**
 * Silent PCM audio fallback for ERROR_CODE_AUDIO_TRACK_INIT_FAILED (5001).
 *
 * When the decoder is set to EXTENSION_RENDERER_MODE_ON (decoderPriority == 1,
 * the default) and tunneling is NOT active, audio passthrough may fail on certain devices/formats.
 * Instead of tearing down and re-building the entire player, we apply an
 * imperceptible speed change (1.00001×) which forces ExoPlayer to decode audio
 * through the software PCM pipeline — identical to what happens when the user
 * manually changes playback speed.
 *
 * This is a one-shot attempt per stream; if it fails again the normal retry
 * logic takes over.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryAudioTrackPcmFallback(
    error: PlaybackException
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return false
    if (hasTriedAudioPcmFallback) return false
    if (cachedDecoderPriority != 1) return false // Only for EXTENSION_RENDERER_MODE_ON
    if (_uiState.value.tunnelingEnabled) return false

    hasTriedAudioPcmFallback = true
    pendingAudioPcmFallbackRebuild = true

    val player = _exoPlayer ?: return false
    val savedPosition = reliableRecoveryPosition(player.currentPosition) ?: 0L
    val paused = userPausedManually

    Log.d(PlayerRuntimeController.TAG, "Audio track init failed (5001) — rebuilding player with PCM forcing, position=${savedPosition}ms")
    showRecoveryOverlay()

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        releasePlayer(flushPlaybackState = false)
        if (savedPosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }

    return true
}

/**
 * DV7-to-HEVC decoder fallback for ERROR_CODE_DECODER_INIT_FAILED (4003).
 *
 * When decoderPriority == 1 (EXTENSION_RENDERER_MODE_ON) and the decoder
 * fails to initialise, this is often caused by Dolby Vision profile 7
 * content on devices without a DV decoder.  Enabling the DV7-to-HEVC
 * mapping allows the HEVC decoder to handle the stream instead.
 *
 * Unlike the PCM fallback this requires a full player rebuild because
 * the mapping is baked into the renderers factory at build time.
 * Tunneling state does not matter for this fallback.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryDv7HevcFallback(
    error: PlaybackException
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) return false
    if (error.isKnownNonRetryableRecoveryError()) return false
    if (hasTriedDv7HevcFallback) return false
    if (cachedDecoderPriority != 1) return false
    // Skip if DV7-to-HEVC is already active — nothing more we can do.
    if (forceDv7ToHevc) return false

    hasTriedDv7HevcFallback = true
    forceDv7ToHevc = true

    val paused = userPausedManually
    val savedPosition = reliableRecoveryPosition() ?: 0L

    Log.d(
        PlayerRuntimeController.TAG,
        "Decoder init failed (4003) — retrying with DV7-to-HEVC mapping, position=${savedPosition}ms"
    )

    resetErrorRetryState()

    // Show loading overlay with fallback info instead of error screen.
    errorRetryJob = scope.launch {
        showRecoveryOverlay()

        releasePlayer(flushPlaybackState = false)
        if (savedPosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }
    return true
}

internal fun PlayerRuntimeController.tryParsingErrorProbeFallback(
    error: PlaybackException,
    detailedError: String,
    allowEngineFailover: Boolean,
    savedPosition: Long = 0L,
    paused: Boolean = userPausedManually
): Boolean {
    if (currentVideoTrackIsLikelyVc1 ||
        Vc1VideoFormatHeuristics.isLikelyVc1(streamName = _uiState.value.currentStreamName ?: streamName)
    ) {
        return false
    }
    val isSourceOrParsingError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
        error.findCauseOfType<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null ||
        error.cause?.toString()?.contains("UnrecognizedInputFormatException") == true

    if (!isSourceOrParsingError) return false
    if (error.isKnownNonRetryableRecoveryError(additionalMessage = detailedError)) {
        // Do not probe/rebuild known-corrupt Matroska bytes. Let the caller's fatal path show the
        // actual parser message instead of turning a deterministic source error into a retry loop.
        return false
    }
    if (parsingErrorProbeAttempted) return false
    parsingErrorProbeAttempted = true

    val reliableSavedPosition = reliableRecoveryPosition(
        currentPositionMs = savedPosition.takeIf { it > 0L }
    )

    val previousMimeType = currentStreamMimeType
    Log.w(
        PlayerRuntimeController.TAG,
        "Source/parsing error [${error.errorCode}] detected (previous mimeType=$previousMimeType). " +
            "Probing stream format..."
    )

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        showRecoveryOverlay()
        val probedMime = PlayerMediaSourceFactory.probeNetworkMimeType(
            url = currentStreamUrl,
            headers = currentHeaders
        )

        if (probedMime != null && probedMime != previousMimeType) {
            Log.i(
                PlayerRuntimeController.TAG,
                "Stream probe resolved mimeType=$probedMime (was $previousMimeType). Retrying playback..."
            )
            currentStreamMimeType = probedMime
            currentStreamResponseHeaders = emptyMap()
            errorRetryCount = maxOf(errorRetryCount, 1)
            errorRecoveryFailurePositionMs = reliableSavedPosition ?: 0L
            releasePlayer(flushPlaybackState = false)
            if (reliableSavedPosition != null) {
                _uiState.update { it.copy(pendingSeekPosition = reliableSavedPosition) }
            }
            initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
        } else if (previousMimeType == androidx.media3.common.MimeTypes.APPLICATION_M3U8) {
            currentStreamMimeType = null
            currentStreamResponseHeaders = emptyMap()
            errorRetryCount = maxOf(errorRetryCount, 1)
            errorRecoveryFailurePositionMs = reliableSavedPosition ?: 0L
            releasePlayer(flushPlaybackState = false)
            if (reliableSavedPosition != null) {
                _uiState.update { it.copy(pendingSeekPosition = reliableSavedPosition) }
            }
            initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
        } else {
            if (maybeAutoSwitchInternalPlayerOnStartupError(detailedError = detailedError, allowEngineFailover = allowEngineFailover)) {
                return@launch
            }
            if (attemptAutoRetry(error, detailedError)) {
                return@launch
            }
            val userFacingError = error.toDisplayMessage(context)
            _uiState.update {
                it.copy(
                    error = userFacingError,
                    isBuffering = false,
                    showLoadingOverlay = false,
                    showPauseOverlay = false
                )
            }
        }
    }
    return true
}
