package com.nuvio.tv.core.diagnostics

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Process-wide, privacy-filtered diagnostic trail.  This is intentionally not Android Log:
 * callers may use it for native/NNTP events without exposing connection data to logcat.
 */
object DiagnosticLog {
    private const val MAX_ENTRIES = 240
    private val lock = Any()
    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    @Volatile private var store: DiagnosticReportStore? = null

    fun install(reportStore: DiagnosticReportStore) {
        store = reportStore
    }

    /** Restores the previous bounded trail before the first startup event is recorded. */
    internal fun restore(previousEntries: List<String>) {
        synchronized(lock) {
            entries.clear()
            previousEntries.takeLast(MAX_ENTRIES).forEach(entries::addLast)
        }
    }

    fun record(category: String, message: String) {
        val line = "${System.currentTimeMillis()} ${DiagnosticSanitizer.category(category)}: " +
            DiagnosticSanitizer.text(message, 1_000)
        synchronized(lock) {
            if (entries.size == MAX_ENTRIES) entries.removeFirst()
            entries.addLast(line)
        }
        store?.persistLogAsync(snapshot())
    }

    fun recordThrowable(category: String, throwable: Throwable) {
        record(category, throwableSummary(throwable))
    }

    internal fun snapshot(): List<String> = synchronized(lock) { entries.toList() }

    internal fun throwableSummary(throwable: Throwable): String = buildString {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = throwable
        repeat(4) {
            val cause = current ?: return@buildString
            if (!seen.add(cause)) return@buildString
            if (isNotEmpty()) append(" caused_by ")
            append(DiagnosticSanitizer.symbol(cause.javaClass.name, 120))
            cause.stackTrace.take(8).forEach { frame ->
                append(" at ")
                append(DiagnosticSanitizer.symbol(frame.className, 120))
                append('.')
                append(DiagnosticSanitizer.symbol(frame.methodName, 80))
                append(':')
                append(frame.lineNumber)
            }
            current = cause.cause
        }
    }
}