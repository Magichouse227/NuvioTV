package com.nuvio.tv.core.usenet

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

class NntpFallbackPolicyTest {
    @Test
    fun `rate limited selection stops all fallback and remains the original stream`() {
        val selected = nzb("selected")
        val candidates = NntpFallbackPolicy.candidates(
            selected,
            listOf(selected, nzb("alternative")),
            maxFallbackAttempts = 5
        )
        val attempted = mutableListOf<Stream>()
        for (candidate in candidates) {
            attempted += candidate
            val failure = NntpException(
                "NZB download rate-limited",
                rateLimit = NntpRateLimit(retryAfterKnown = false, cooldownDeadlineElapsedMs = 60_000L)
            )
            if (!NntpFallbackPolicy.shouldTryNext(failure)) break
        }
        assertEquals(1, attempted.size)
        assertSame(selected, attempted.single())
    }

    @Test
    fun `zero retry after still stops automatic fallback`() {
        val failure = NntpException(
            "NZB download rate-limited",
            rateLimit = NntpRateLimit(
                retryAfterKnown = true,
                retryAfterDeadlineElapsedMs = 0L,
                cooldownDeadlineElapsedMs = 0L
            )
        )
        assertFalse(NntpFallbackPolicy.shouldTryNext(failure))
    }

    @Test
    fun `article unavailable and generic failures remain eligible for bounded fallback`() {
        assertTrue(NntpFallbackPolicy.shouldTryNext(NntpException("NNTP 430 article unavailable")))
        assertTrue(NntpFallbackPolicy.shouldTryNext(NntpException("Failed to parse NZB")))
        assertFalse(NntpFallbackPolicy.shouldTryNext(kotlinx.coroutines.CancellationException()))
    }

    @Test
    fun `uses selected result then following playable NZB results`() {
        val before = nzb("before")
        val selected = nzb("selected")
        val direct = http("direct")
        val missingServer = nzb("missing-server", servers = emptyList())
        val next = nzb("next")
        val last = nzb("last")

        val candidates = NntpFallbackPolicy.candidates(
            selected = selected,
            orderedStreams = listOf(before, selected, direct, missingServer, next, last),
            maxFallbackAttempts = 2
        )

        assertEquals(listOf(selected, next, last), candidates)
    }

    @Test
    fun `does not retry the same NZB selection metadata`() {
        val selected = nzb("selected")
        val duplicate = selected.copy(name = "duplicate label")
        val alternative = nzb("alternative")

        val candidates = NntpFallbackPolicy.candidates(
            selected = selected,
            orderedStreams = listOf(selected, duplicate, alternative),
            maxFallbackAttempts = 5
        )

        assertEquals(listOf(selected, alternative), candidates)
    }

    @Test
    fun `default allows twenty alternatives`() {
        val selected = nzb("selected")
        val alternatives = (1..25).map { nzb("alternative-$it") }

        val candidates = NntpFallbackPolicy.candidates(
            selected = selected,
            orderedStreams = listOf(selected) + alternatives,
            maxFallbackAttempts = PlayerSettings.DEFAULT_NNTP_MAX_FALLBACK_ATTEMPTS
        )

        assertEquals(21, candidates.size)
        assertEquals(alternatives.take(20), candidates.drop(1))
    }

    @Test
    fun `limits fallback attempts to fifty`() {
        val selected = nzb("selected")
        val alternatives = (1..60).map { nzb("alternative-$it") }

        val candidates = NntpFallbackPolicy.candidates(
            selected = selected,
            orderedStreams = listOf(selected) + alternatives,
            maxFallbackAttempts = 99
        )

        assertEquals(51, candidates.size)
        assertEquals(alternatives.take(50), candidates.drop(1))
    }

    private fun nzb(
        id: String,
        servers: List<String> = listOf("nntps://user:password@news.example:563/5")
    ) = stream(
        id = id,
        nzbUrl = "https://indexer.example/$id.nzb",
        servers = servers
    )

    private fun http(id: String) = stream(
        id = id,
        url = "https://media.example/$id.mkv"
    )

    private fun stream(
        id: String,
        url: String? = null,
        nzbUrl: String? = null,
        servers: List<String>? = null
    ) = Stream(
        name = id,
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "AIOStreams",
        addonLogo = null,
        nzbUrl = nzbUrl,
        servers = servers
    )
}
