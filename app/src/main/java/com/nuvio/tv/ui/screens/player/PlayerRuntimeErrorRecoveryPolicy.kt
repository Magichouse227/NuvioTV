package com.nuvio.tv.ui.screens.player

/**
 * Pure decisions used by the player error-recovery ladder.
 *
 * Keeping the source/format classification and progress gate independent of Android and Media3
 * makes it possible to test the two failure modes which are otherwise easy to regress:
 * malformed containers must not be retried forever, and a retry must not be allowed to reset the
 * playhead to the same point indefinitely.
 */
internal object PlayerRuntimeErrorRecoveryPolicy {

    const val MIN_MEANINGFUL_FORWARD_PROGRESS_MS = 1_000L

    enum class ErrorClassification {
        Retryable,
        MalformedContainer,
        UnsupportedFormat,
    }

    data class RetryInput(
        val retryCount: Int,
        val maxRetries: Int,
        val reliablePositionMs: Long?,
        val failedPositionMs: Long?,
        val minimumForwardProgressMs: Long = MIN_MEANINGFUL_FORWARD_PROGRESS_MS,
    )

    /**
     * A failed player can report zero immediately after its engine has reset. Prefer a queued
     * seek, then the live position, and finally the controller's timeline/saved snapshot. A
     * positive engine position is preferred over an older timeline value so an intentional manual
     * backward seek is never replaced by stale progress.
     */
    fun reliablePositionMs(
        currentPositionMs: Long?,
        timelinePositionMs: Long?,
        savedPositionMs: Long?,
        pendingSeekPositionMs: Long?,
    ): Long? {
        val pending = pendingSeekPositionMs.positiveOrNull()
        if (pending != null) return pending

        val current = currentPositionMs.positiveOrNull()
        if (current != null) return current

        val timeline = timelinePositionMs.positiveOrNull()
        if (timeline != null) return timeline

        return savedPositionMs.positiveOrNull()
    }

    fun hasMeaningfulForwardProgress(
        currentPositionMs: Long?,
        failedPositionMs: Long?,
        minimumForwardProgressMs: Long = MIN_MEANINGFUL_FORWARD_PROGRESS_MS,
    ): Boolean {
        val current = currentPositionMs ?: return false
        val failed = failedPositionMs ?: return true
        val minimumProgress = minimumForwardProgressMs.coerceAtLeast(1L)
        val required = if (failed > Long.MAX_VALUE - minimumProgress) {
            Long.MAX_VALUE
        } else {
            failed + minimumProgress
        }
        return current >= required
    }

    fun shouldRetry(input: RetryInput): Boolean {
        if (input.retryCount < 0 || input.retryCount >= input.maxRetries) return false
        // The first retry is allowed to recover a transient failure. Subsequent retries must
        // demonstrate that playback moved beyond the previous failure point; this blocks a source
        // or decoder that repeatedly restarts at zero.
        if (input.retryCount == 0) return true
        return hasMeaningfulForwardProgress(
            currentPositionMs = input.reliablePositionMs,
            failedPositionMs = input.failedPositionMs,
            minimumForwardProgressMs = input.minimumForwardProgressMs,
        )
    }

    fun classify(
        messages: Iterable<String>,
        hasUnsupportedFormatErrorCode: Boolean = false,
        hasMalformedContainerErrorCode: Boolean = false,
    ): ErrorClassification {
        if (hasUnsupportedFormatErrorCode) return ErrorClassification.UnsupportedFormat
        if (hasMalformedContainerErrorCode) return ErrorClassification.MalformedContainer

        val normalized = messages
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n")
            .lowercase()
            .replace('-', ' ')
            .replace('_', ' ')

        if (knownMalformedContainerMarkers.any(normalized::contains)) {
            return ErrorClassification.MalformedContainer
        }
        if (knownUnsupportedFormatMarkers.any(normalized::contains)) {
            return ErrorClassification.UnsupportedFormat
        }
        return ErrorClassification.Retryable
    }

    /**
     * Classifies a Media3/provider exception without assuming its cause chain is acyclic.
     *
     * This overload keeps Throwable graph walking in a small pure helper so the exact parser
     * classification can be exercised by JVM tests without Android or Media3 runtime classes.
     */
    fun classify(
        throwable: Throwable,
        additionalMessages: Iterable<String> = emptyList(),
        hasUnsupportedFormatErrorCode: Boolean = false,
        hasMalformedContainerErrorCode: Boolean = false,
    ): ErrorClassification {
        val throwableMessages = ThrowableGraphTraversal.walk(throwable).flatMap { current ->
            buildList {
                current.message?.let(::add)
                add(current.toString())
            }
        }.toList()
        return classify(
            messages = throwableMessages + additionalMessages,
            hasUnsupportedFormatErrorCode = hasUnsupportedFormatErrorCode,
            hasMalformedContainerErrorCode = hasMalformedContainerErrorCode
        )
    }

    private val knownMalformedContainerMarkers = listOf(
        "no valid varint length mask found",
        "invalid varint",
        "invalid ebml varint",
        "ebml varint",
        "malformed ebml",
        "malformed matroska",
        "container malformed",
    )

    private val knownUnsupportedFormatMarkers = listOf(
        "error code decoding format unsupported",
        "error code decoding format exceeds capabilities",
        "format exceeds capabilities",
        "unsupported format",
        "format not supported",
        "format is not supported",
        "unsupported codec",
        "codec unsupported",
        "codec not supported",
        "codec is not supported",
        "decoder does not support",
        "no suitable decoder",
        "no decoder available",
        "resolution exceeds",
        "resolution unsupported",
        "unsupported resolution",
        "maximum resolution",
        "max resolution",
        "too many pixels",
    )

    private fun Long?.positiveOrNull(): Long? = this?.takeIf { it > 0L }
}