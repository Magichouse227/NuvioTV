package com.nuvio.tv.core.usenet

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.domain.model.Stream

internal object NntpFallbackPolicy {
    // A rate limit is about downloading the NZB, not the availability of NNTP articles.
    // Stop the whole automatic chain, including a zero/past Retry-After. Only a manual
    // selection may try again once the endpoint's cooldown allows it.
    fun shouldTryNext(error: Exception): Boolean =
        error !is kotlinx.coroutines.CancellationException &&
            (error as? NntpException)?.rateLimit == null

    fun candidates(
        selected: Stream,
        orderedStreams: List<Stream>,
        maxFallbackAttempts: Int
    ): List<Stream> {
        val selectedKey = selected.fallbackKey()
        val selectedIndex = orderedStreams.indexOfFirst { it.fallbackKey() == selectedKey }
        val remaining = if (selectedIndex >= 0) {
            orderedStreams.drop(selectedIndex + 1)
        } else {
            orderedStreams
        }
        val fallbackLimit = maxFallbackAttempts.coerceIn(
            PlayerSettings.MIN_NNTP_MAX_FALLBACK_ATTEMPTS,
            PlayerSettings.MAX_NNTP_MAX_FALLBACK_ATTEMPTS
        )

        return sequenceOf(selected)
            .plus(remaining.asSequence().filter { it.isNzb() && it.hasNntpServers() })
            .distinctBy { it.fallbackKey() }
            .take(fallbackLimit + 1)
            .toList()
    }

    private fun Stream.fallbackKey() = NntpFallbackKey(
        nzbUrl = nzbUrl.orEmpty(),
        servers = servers.orEmpty(),
        fileIdx = fileIdx,
        fileMustInclude = fileMustInclude.orEmpty()
    )

    private data class NntpFallbackKey(
        val nzbUrl: String,
        val servers: List<String>,
        val fileIdx: Int?,
        val fileMustInclude: String
    )
}
