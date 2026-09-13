package com.nuvio.tv.core.diagnostics

/**
 * The diagnostic export is deliberately hostile to credentials.  It is used at every boundary
 * (log, stored report, and LAN share page), rather than relying on individual callers to
 * remember what is sensitive.
 */
internal object DiagnosticSanitizer {
    private const val REDACTED = "[redacted]"
    private val url = Regex("""(?i)\b(?:https?|wss?)://[^\s"'<>]+""")
    private val encodedUrl = Regex("""(?i)(?:https?%3a%2f%2f|https?%3A%2F%2F)[^\s"'<>]+""")
    private val userInfo = Regex("""(?i)\b[\w.+-]+:[^\s@/]+@""")
    private val credential = Regex(
        """(?i)\b(password|pass|username|token|access_token|refresh_token|client_secret|clientsecret|api[_-]?key|authorization|proxy-authorization|cookie|set-cookie|bearer|nntp[_-]?(?:user|username|password))\b\s*(?:[:=]\s*|"\s*:\s*")[^,\s}"\]]+"""
    )
    private val bearerValue = Regex("""(?i)\bbearer\s+[^\s,;]+""")
    private val basicValue = Regex("""(?i)\bbasic\s+[A-Za-z0-9+/=_-]+""")
    private val nntpAuth = Regex("""(?i)\bAUTHINFO\s+(?:USER|PASS)\s+\S+""")
    private val jsonCredential = Regex(
        "(?i)\"(password|pass|token|access_token|refresh_token|client_secret|clientsecret|api[_-]?key|authorization|cookie)\"\\s*:\\s*\"[^\"]*\""
    )

    fun text(value: String?, maxLength: Int = 1_500): String {
        if (value.isNullOrBlank()) return ""
        return value
            .replace('\u0000', ' ')
            .replace(encodedUrl, REDACTED)
            .replace(url, REDACTED)
            .replace(userInfo, REDACTED)
            .replace(bearerValue, "Bearer $REDACTED")
            .replace(basicValue, "Basic $REDACTED")
            .replace(nntpAuth, "AUTHINFO [redacted]")
            .replace(jsonCredential) { match -> "\"${match.groupValues[1]}\":\"$REDACTED\"" }
            .replace(credential) { match -> "${match.groupValues[1]}=$REDACTED" }
            .replace(Regex("""[\t\r ]+"""), " ")
            .trim()
            .take(maxLength)
    }

    fun category(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
            .take(64)
            .ifBlank { "general" }

    fun symbol(value: String, maxLength: Int): String =
        value.filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '$' || it == '<' || it == '>' }
            .take(maxLength)
            .ifBlank { "unknown" }
}