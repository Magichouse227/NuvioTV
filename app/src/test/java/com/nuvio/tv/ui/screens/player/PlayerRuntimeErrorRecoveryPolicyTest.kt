package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRuntimeErrorRecoveryPolicyTest {

    @Test
    fun varintContainerFailureIsNotRetryable() {
        assertEquals(
            PlayerRuntimeErrorRecoveryPolicy.ErrorClassification.MalformedContainer,
            PlayerRuntimeErrorRecoveryPolicy.classify(
                messages = listOf("No valid varint length mask found [ERROR_CODE_IO_UNSPECIFIED]")
            )
        )
    }

    @Test
    fun malformedContainerErrorCodeIsNotRetryableWithoutParserMessage() {
        assertEquals(
            PlayerRuntimeErrorRecoveryPolicy.ErrorClassification.MalformedContainer,
            PlayerRuntimeErrorRecoveryPolicy.classify(
                messages = emptyList(),
                hasMalformedContainerErrorCode = true
            )
        )
    }

    @Test
    fun unsupportedResolutionAndCodecAreNotRetryable() {
        assertEquals(
            PlayerRuntimeErrorRecoveryPolicy.ErrorClassification.UnsupportedFormat,
            PlayerRuntimeErrorRecoveryPolicy.classify(
                messages = listOf("Video format exceeds capabilities")
            )
        )
        assertEquals(
            PlayerRuntimeErrorRecoveryPolicy.ErrorClassification.UnsupportedFormat,
            PlayerRuntimeErrorRecoveryPolicy.classify(
                messages = listOf("video codec is not supported by this device")
            )
        )
        assertEquals(
            PlayerRuntimeErrorRecoveryPolicy.ErrorClassification.UnsupportedFormat,
            PlayerRuntimeErrorRecoveryPolicy.classify(
                messages = listOf("decoder initialization failed"),
                hasUnsupportedFormatErrorCode = true
            )
        )
    }

    @Test
    fun ordinaryIoFailureRemainsRetryable() {
        assertEquals(
            PlayerRuntimeErrorRecoveryPolicy.ErrorClassification.Retryable,
            PlayerRuntimeErrorRecoveryPolicy.classify(
                messages = listOf("connection reset by peer")
            )
        )
    }

    @Test
    fun reliablePositionUsesTimelineWhenEngineResetReportsZero() {
        assertEquals(
            42_000L,
            PlayerRuntimeErrorRecoveryPolicy.reliablePositionMs(
                currentPositionMs = 0L,
                timelinePositionMs = 42_000L,
                savedPositionMs = 37_000L,
                pendingSeekPositionMs = null
            )
        )
    }

    @Test
    fun pendingSeekAndLivePositionBeatOlderSnapshots() {
        assertEquals(
            12_000L,
            PlayerRuntimeErrorRecoveryPolicy.reliablePositionMs(
                currentPositionMs = 30_000L,
                timelinePositionMs = 30_000L,
                savedPositionMs = 30_000L,
                pendingSeekPositionMs = 12_000L
            )
        )
        assertEquals(
            30_000L,
            PlayerRuntimeErrorRecoveryPolicy.reliablePositionMs(
                currentPositionMs = 30_000L,
                timelinePositionMs = 20_000L,
                savedPositionMs = 20_000L,
                pendingSeekPositionMs = null
            )
        )
    }

    @Test
    fun retryStopsWhenPlayheadRepeatsFailedPoint() {
        assertTrue(
            PlayerRuntimeErrorRecoveryPolicy.shouldRetry(
                PlayerRuntimeErrorRecoveryPolicy.RetryInput(
                    retryCount = 0,
                    maxRetries = 2,
                    reliablePositionMs = 0L,
                    failedPositionMs = null
                )
            )
        )
        assertFalse(
            PlayerRuntimeErrorRecoveryPolicy.shouldRetry(
                PlayerRuntimeErrorRecoveryPolicy.RetryInput(
                    retryCount = 1,
                    maxRetries = 2,
                    reliablePositionMs = 0L,
                    failedPositionMs = 0L
                )
            )
        )
        assertTrue(
            PlayerRuntimeErrorRecoveryPolicy.shouldRetry(
                PlayerRuntimeErrorRecoveryPolicy.RetryInput(
                    retryCount = 1,
                    maxRetries = 2,
                    reliablePositionMs = 11_000L,
                    failedPositionMs = 10_000L
                )
            )
        )
        assertFalse(
            PlayerRuntimeErrorRecoveryPolicy.shouldRetry(
                PlayerRuntimeErrorRecoveryPolicy.RetryInput(
                    retryCount = 2,
                    maxRetries = 2,
                    reliablePositionMs = 30_000L,
                    failedPositionMs = 10_000L
                )
            )
        )
    }

    @Test
    fun noPositionStillAllowsFirstRetryButNeverPretendsProgress() {
        assertFalse(
            PlayerRuntimeErrorRecoveryPolicy.hasMeaningfulForwardProgress(
                currentPositionMs = null,
                failedPositionMs = null
            )
        )
        assertFalse(
            PlayerRuntimeErrorRecoveryPolicy.hasMeaningfulForwardProgress(
                currentPositionMs = null,
                failedPositionMs = 10_000L
            )
        )
        assertNull(
            PlayerRuntimeErrorRecoveryPolicy.reliablePositionMs(
                currentPositionMs = 0L,
                timelinePositionMs = 0L,
                savedPositionMs = 0L,
                pendingSeekPositionMs = null
            )
        )
    }
}