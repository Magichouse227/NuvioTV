package com.nuvio.tv.core.usenet

/** Fail closed: Go logs may contain credentials, article IDs and NZB URLs. */
internal object NntpNativeDiagnostics {
    private const val MARKER = "NUVIO_DIAG "
    private val allowed = Regex(
        "event=(media_read_error|session_created|session_closed|segment_unavailable|" +
            "archive_short_read|process_ready)" +
            "( (offset|length|bytes|count|status)=[0-9]{1,19}){0,8}"
    )

    fun parse(line: String): String? {
        val marker = line.indexOf(MARKER)
        if (marker < 0) return null
        val event = line.substring(marker + MARKER.length)
        return event.takeIf { allowed.matches(it) }
    }
}