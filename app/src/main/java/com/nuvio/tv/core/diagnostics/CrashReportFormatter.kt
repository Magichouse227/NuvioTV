package com.nuvio.tv.core.diagnostics

/**
 * Public reports contain exception types and code locations, never exception messages,
 * thread names, URLs, request headers or throwable.toString(). Those can contain secrets.
 */
internal object CrashReportFormatter {
    private const val MAX_CAUSES = 4
    private const val MAX_FRAMES = 10
    private const val MAX_CHARS = 4_000
    private val unsafeSymbol = Regex("[^A-Za-z0-9_.$<>]")

    fun stackSummary(throwable: Throwable): String = buildString {
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>()
        )
        var current: Throwable? = throwable
        repeat(MAX_CAUSES) {
            val cause = current ?: return@buildString
            if (!seen.add(cause)) return@buildString
            if (isNotEmpty()) append("Caused by: ")
            appendLine(cause.javaClass.name.replace(unsafeSymbol, "_").take(120))
            for (frame in cause.stackTrace.take(MAX_FRAMES)) {
                if (length >= MAX_CHARS) return@buildString
                append("  at ")
                append(frame.className.replace(unsafeSymbol, "_").take(120))
                append('.')
                append(frame.methodName.replace(unsafeSymbol, "_").take(80))
                append(':')
                appendLine(frame.lineNumber)
            }
            current = cause.cause
        }
    }.take(MAX_CHARS)
}