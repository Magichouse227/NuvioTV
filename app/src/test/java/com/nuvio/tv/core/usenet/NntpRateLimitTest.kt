package com.nuvio.tv.core.usenet

import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NntpRateLimitTest {
    @Test
    fun knownRetryAfterRoundsUpAndExpiresAsManualRetry() {
        val limit = NntpRateLimit(
            retryAfterKnown = true,
            retryAfterDeadlineElapsedMs = 12_501L,
            cooldownDeadlineElapsedMs = 12_000L
        )

        assertEquals(3L, limit.remainingSeconds(10_001L))
        assertEquals(1L, limit.remainingSeconds(11_999L))
        assertEquals(0L, limit.remainingSeconds(12_501L))
        assertEquals(
            "NZB download rate-limited (HTTP 429). " +
                "Wait completed. You can retry the selected stream manually.",
            limit.userMessage(12_501L)
        )
    }

    @Test
    fun unknownRetryAfterShowsLocalDelayAndExplicitUnknownReset() {
        val limit = NntpRateLimit(
            retryAfterKnown = false,
            retryAfterDeadlineElapsedMs = null,
            cooldownDeadlineElapsedMs = 60_000L
        )

        assertEquals(60L, limit.remainingSeconds(0L))
        assertEquals(
            "NZB download rate-limited (HTTP 429). " +
                "Reset time was not supplied. Wait 59 seconds before trying again " +
                "(app retry delay).",
            limit.userMessage(1_000L)
        )
        assertTrue(limit.userMessage(60_000L).contains("retry manually"))
    }

    @Test
    fun remainingUsesTheLongerProviderMinimumOrLocalGate() {
        val limit = NntpRateLimit(
            retryAfterKnown = true,
            retryAfterDeadlineElapsedMs = 2_000L,
            cooldownDeadlineElapsedMs = 5_000L
        )

        assertEquals(5L, limit.remainingSeconds(0L))
    }

    @Test
    fun decoderTreatsMissingAndInvalidRetryAfterAsUnknownAndUsesLocalGate() {
        val missing = NntpRateLimitDecoder.decode(
            statusCode = 429,
            headers = Headers.headersOf(),
            body = """{"code":"nzb_rate_limited","retryAfterSeconds":null,"cooldownSeconds":3}""",
            nowElapsedMs = 5_000L
        )
        val invalid = NntpRateLimitDecoder.decode(
            statusCode = 429,
            headers = Headers.headersOf("Retry-After", "tomorrow"),
            body = """{"code":"nzb_rate_limited","retryAfterSeconds":"not-a-number"}""",
            nowElapsedMs = 5_000L
        )

        assertNotKnownWithGate(missing, expectedSeconds = 3L)
        assertNotKnownWithGate(invalid, expectedSeconds = 60L)
    }

    @Test
    fun decoderUsesIntegerHeaderAndEngineCooldownWhenAvailable() {
        val limit = NntpRateLimitDecoder.decode(
            statusCode = 429,
            headers = Headers.headersOf("Retry-After", "7"),
            body = """{"code":"nzb_rate_limited","cooldownSeconds":3}""",
            nowElapsedMs = 1_000L
        )

        requireNotNull(limit)
        assertTrue(limit.retryAfterKnown)
        assertEquals(7L, limit.remainingSeconds(1_000L))
        assertEquals(3L, limit.cooldownRemainingSeconds(1_000L))
    }

    @Test
    fun decoderSaturatesHugeDigitOnlyValuesAsLong() {
        val limit = NntpRateLimitDecoder.decode(
            statusCode = 429,
            headers = Headers.headersOf(),
            body = """{"retryAfterSeconds":"999999999999999999999999999999"}""",
            nowElapsedMs = 0L
        )

        requireNotNull(limit)
        assertTrue(limit.retryAfterKnown)
        assertTrue(limit.remainingSeconds(0L) > Int.MAX_VALUE.toLong())
    }

    @Test
    fun decoderRespectsHugeLocalCooldownWhenRetryAfterIsUnknown() {
        val limit = NntpRateLimitDecoder.decode(
            statusCode = 429,
            headers = Headers.headersOf(),
            body = """{"retryAfterSeconds":null,"cooldownSeconds":"999999999999999999999999999999"}""",
            nowElapsedMs = 0L
        )

        requireNotNull(limit)
        assertFalse(limit.retryAfterKnown)
        assertTrue(limit.cooldownRemainingSeconds(0L) > Int.MAX_VALUE.toLong())
    }

    @Test
    fun cooldownIsNzbOriginScopedAndIndependentOfNntpProvider() {
        val policy = NntpCooldownPolicy({ 10_000L })
        val first = policy.originForNzbUrl("HTTP://user:secret@Nzb.Example.:80/path/a.nzb?token=one")
        val equivalent = policy.originForNzbUrl("http://other:password@nzb.example/path/b.nzb")
        val other = policy.originForNzbUrl("https://nzb.example/path/a.nzb")

        assertEquals("http://nzb.example:80", first)
        assertEquals(first, equivalent)
        assertFalse(first == other)

        policy.record(listOf(first), retryAfterSeconds = null, cooldownSeconds = 60L)
        assertTrue(policy.activeLimit(listOf(equivalent)) != null)
        assertNull(policy.activeLimit(listOf(other), nowElapsedMs = 10_000L))
        assertNull(policy.activeLimit(listOf(equivalent), nowElapsedMs = 70_000L))
    }

    private fun assertNotKnownWithGate(limit: NntpRateLimit?, expectedSeconds: Long) {
        requireNotNull(limit)
        assertFalse(limit.retryAfterKnown)
        assertNull(limit.retryAfterDeadlineElapsedMs)
        assertEquals(expectedSeconds, limit.remainingSeconds(5_000L))
        assertEquals(expectedSeconds, limit.cooldownRemainingSeconds(5_000L))
    }
}