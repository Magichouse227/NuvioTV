package com.nuvio.tv.core.usenet

import android.os.SystemClock
import okhttp3.Headers
import org.json.JSONObject
import java.net.IDN
import java.net.URI
import kotlin.math.max

/**
 * The two clocks involved in an NZB download rate limit are deliberately kept separate:
 *
 * * [retryAfterDeadlineElapsedMs] is the server's advertised minimum wait (when one was
 *   available), not a guarantee that the provider has reset.
 * * [cooldownDeadlineElapsedMs] is our local safety gate. It must never be interpreted as proof
 *   that the provider has reset.
 *
 * Both values are elapsed-realtime values, not wall-clock timestamps. This makes this object safe
 * to persist in memory while the app is backgrounded and straightforward to exercise with a fake
 * clock in unit tests.
 */
data class NntpRateLimit(
    val httpStatus: Int = HTTP_STATUS_TOO_MANY_REQUESTS,
    val retryAfterKnown: Boolean,
    val retryAfterDeadlineElapsedMs: Long? = null,
    val cooldownDeadlineElapsedMs: Long = 0L
) {
    init {
        require(httpStatus == HTTP_STATUS_TOO_MANY_REQUESTS) {
            "Rate-limit metadata must describe HTTP 429"
        }
        require(!retryAfterKnown || retryAfterDeadlineElapsedMs != null) {
            "A known Retry-After value needs a monotonic deadline"
        }
    }

    /**
     * Remaining seconds to show to a user. Provider reset is preferred when known; otherwise this
     * intentionally reports only the local safety delay and does not pretend to know the reset.
     */
    fun remainingSeconds(nowElapsedMs: Long): Long {
        val providerRemaining = if (retryAfterKnown) {
            retryAfterDeadlineElapsedMs?.let { secondsUntil(it, nowElapsedMs) } ?: 0L
        } else {
            0L
        }
        val localRemaining = secondsUntil(cooldownDeadlineElapsedMs, nowElapsedMs)
        return max(providerRemaining, localRemaining)
    }

    /** Remaining local gate, useful to diagnostics and the service's pre-POST check. */
    fun cooldownRemainingSeconds(nowElapsedMs: Long): Long =
        secondsUntil(cooldownDeadlineElapsedMs, nowElapsedMs)

    /** Remaining provider wait, or null when the provider did not advertise one. */
    fun retryAfterRemainingSeconds(nowElapsedMs: Long): Long? =
        if (retryAfterKnown) {
            retryAfterDeadlineElapsedMs?.let { secondsUntil(it, nowElapsedMs) }
        } else {
            null
        }

    /**
     * Safe, provider-independent copy for the UI. A completed wait is intentionally a manual retry
     * affordance rather than an instruction to retry automatically.
     */
    fun userMessage(nowElapsedMs: Long): String {
        val remaining = remainingSeconds(nowElapsedMs)
        return if (retryAfterKnown) {
            if (remaining > 0) {
                "NZB download rate-limited (HTTP 429). Wait at least $remaining seconds before trying again."
            } else {
                "NZB download rate-limited (HTTP 429). Wait completed. You can retry the selected stream manually."
            }
        } else {
            if (remaining > 0) {
                "NZB download rate-limited (HTTP 429). " +
                    "Reset time was not supplied. Wait $remaining seconds before trying again " +
                    "(app retry delay)."
            } else {
                "NZB download rate-limited (HTTP 429). " +
                    "Reset time was not supplied. App retry delay completed. " +
                    "You can retry the selected stream manually."
            }
        }
    }

    companion object {
        const val HTTP_STATUS_TOO_MANY_REQUESTS = 429
        const val DEFAULT_COOLDOWN_SECONDS = 60L

        internal fun deadlineAfter(nowElapsedMs: Long, seconds: Long): Long {
            val durationMs = if (seconds > Long.MAX_VALUE / 1_000L) {
                Long.MAX_VALUE
            } else {
                seconds.coerceAtLeast(0L) * 1_000L
            }
            return if (nowElapsedMs >= Long.MAX_VALUE - durationMs) {
                Long.MAX_VALUE
            } else {
                nowElapsedMs + durationMs
            }
        }

        private fun secondsUntil(deadlineElapsedMs: Long, nowElapsedMs: Long): Long {
            if (deadlineElapsedMs <= nowElapsedMs) return 0L
            val remainingMs = deadlineElapsedMs - nowElapsedMs
            val wholeSeconds = remainingMs / 1_000L
            val roundedUp = wholeSeconds + if (remainingMs % 1_000L == 0L) 0L else 1L
            return roundedUp
        }
    }
}

/**
 * Origin-keyed local gate. The default clock matches Android's elapsed-realtime clock used by the
 * parent UI; tests can provide a deterministic clock through the primary constructor.
 */
class NntpCooldownPolicy @JvmOverloads constructor(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val defaultCooldownSeconds: Long = NntpRateLimit.DEFAULT_COOLDOWN_SECONDS
) {
    private data class Gate(
        val deadlineElapsedMs: Long,
        val rateLimit: NntpRateLimit
    )

    private val gates = mutableMapOf<String, Gate>()

    init {
        require(defaultCooldownSeconds >= 0) { "Default app cooldown cannot be negative" }
    }

    fun nowElapsedMs(): Long = clock()

    /**
     * Returns an active limit for any supplied origin. Completed entries are removed as they are
     * encountered, so this cache remains bounded by the number of configured endpoints.
     */
    @Synchronized
    fun activeLimit(origins: Collection<String>, nowElapsedMs: Long = clock()): NntpRateLimit? {
        origins.forEach { rawOrigin ->
            val origin = normalizeOrigin(rawOrigin)
            val gate = gates[origin] ?: return@forEach
            if (gate.deadlineElapsedMs > nowElapsedMs) return gate.rateLimit
            gates.remove(origin)
        }
        return null
    }

    /**
     * Records an NZB download response for each NZB origin used by the request. A local gate is at
     * least [cooldownSeconds] long. The effective gate also includes a known minimum wait so a
     * caller cannot accidentally POST again while Retry-After is still active.
     */
    @Synchronized
    fun record(
        origins: Collection<String>,
        retryAfterSeconds: Long?,
        cooldownSeconds: Long = defaultCooldownSeconds,
        nowElapsedMs: Long = clock()
    ): NntpRateLimit {
        val safeRetryAfterSeconds = retryAfterSeconds?.takeIf { it >= 0 }
        val safeCooldownSeconds = cooldownSeconds.takeIf { it >= 0 } ?: defaultCooldownSeconds
        val limit = NntpRateLimit(
            retryAfterKnown = safeRetryAfterSeconds != null,
            retryAfterDeadlineElapsedMs = safeRetryAfterSeconds?.let {
                NntpRateLimit.deadlineAfter(nowElapsedMs, it)
            },
            cooldownDeadlineElapsedMs = NntpRateLimit.deadlineAfter(
                nowElapsedMs,
                safeCooldownSeconds
            )
        )
        val effectiveDeadline = max(
            limit.cooldownDeadlineElapsedMs,
            limit.retryAfterDeadlineElapsedMs ?: Long.MIN_VALUE
        )
        origins.map(::normalizeOrigin).distinct().forEach { origin ->
            val old = gates[origin]
            if (old == null || old.deadlineElapsedMs <= nowElapsedMs) {
                gates[origin] = Gate(effectiveDeadline, limit)
            } else {
                // Never shorten an existing safety gate when a concurrent response arrives with a
                // smaller Retry-After value.
                gates[origin] = Gate(
                    deadlineElapsedMs = max(old.deadlineElapsedMs, effectiveDeadline),
                    rateLimit = old.rateLimit.takeIf {
                        old.deadlineElapsedMs >= effectiveDeadline
                    } ?: limit
                )
            }
        }
        return limit
    }

    @Synchronized
    fun clear() {
        gates.clear()
    }

    companion object {
        /**
         * Normalizes an NZB HTTP URL to scheme + host + effective port. Credentials, paths and
         * query strings are never part of a cooldown key.
         */
        fun originForNzbUrl(nzbUrl: String): String {
            val uri = try {
                URI(nzbUrl.trim())
            } catch (error: Exception) {
                throw IllegalArgumentException("Invalid NZB URL", error)
            }
            val scheme = uri.scheme?.lowercase()
                ?.takeIf { it == "http" || it == "https" }
                ?: throw IllegalArgumentException("NZB URL must use HTTP or HTTPS")
            var host = uri.host?.trim()?.lowercase()?.trimEnd('.')
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("NZB URL has no host")
            host = runCatching { IDN.toASCII(host) }.getOrDefault(host)
            if (':' in host && !host.startsWith('[')) host = "[$host]"
            val defaultPort = if (scheme == "https") 443 else 80
            val port = uri.port.takeIf { it >= 0 } ?: defaultPort
            require(port in 1..65535) { "NZB URL port is out of range" }
            return "$scheme://$host:$port"
        }

        fun normalizeOrigin(origin: String): String {
            return originForNzbUrl(origin)
        }
    }
}

/**
 * Decoder for the deliberately small 429 contract emitted by the local engine. It is kept
 * separate from OkHttp execution so malformed/null metadata can be tested without a server.
 */
internal object NntpRateLimitDecoder {
    fun decode(
        statusCode: Int,
        headers: Headers,
        body: String,
        nowElapsedMs: Long,
        defaultCooldownSeconds: Long = NntpRateLimit.DEFAULT_COOLDOWN_SECONDS
    ): NntpRateLimit? {
        if (statusCode != NntpRateLimit.HTTP_STATUS_TOO_MANY_REQUESTS) return null

        val json = runCatching { JSONObject(body) }.getOrNull()
        val bodyRetryAfter = json?.let { integerField(it, "retryAfterSeconds") }
        // A direct engine response may only have the standard header. The JSON field, when
        // present, is authoritative because it is already normalized by the Go worker.
        val retryAfterSeconds = if (json?.has("retryAfterSeconds") == true) {
            bodyRetryAfter
        } else {
            parseRetryAfterHeader(headers["Retry-After"])
        }
        val bodyCooldown = json?.let { integerField(it, "cooldownSeconds") }
        // cooldownSeconds is the engine's remaining local gate. Respect it even when the provider
        // did not supply a reset time; only a missing or malformed value uses the 60-second
        // fallback.
        val cooldownSeconds = bodyCooldown?.takeIf { it >= 0 } ?: defaultCooldownSeconds
        return NntpRateLimit(
            retryAfterKnown = retryAfterSeconds != null,
            retryAfterDeadlineElapsedMs = retryAfterSeconds?.let {
                NntpRateLimit.deadlineAfter(nowElapsedMs, it)
            },
            cooldownDeadlineElapsedMs = NntpRateLimit.deadlineAfter(
                nowElapsedMs,
                cooldownSeconds
            )
        )
    }

    private fun integerField(json: JSONObject, name: String): Long? {
        if (!json.has(name) || json.isNull(name)) return null
        val value = runCatching { json.get(name) }.getOrNull() ?: return null
        val text = when (value) {
            is Number -> value.toString()
            is String -> value.trim()
            else -> return null
        }
        if (!text.matches(Regex("[0-9]+"))) return null
        return text.toLongOrNull() ?: Long.MAX_VALUE
    }

    private fun parseRetryAfterHeader(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (!text.matches(Regex("[0-9]+"))) return null
        return text.toLongOrNull() ?: Long.MAX_VALUE
    }
}