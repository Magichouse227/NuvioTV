package com.nuvio.tv.core.diagnostics

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compatibility facade for the managed-crash path.  Fatal writes are synchronous by necessity;
 * review, sharing, and discarding are handled by [DiagnosticReportStore]'s retained queue.
 */
@Singleton
class CrashReportStore @Inject constructor(
    private val reportStore: DiagnosticReportStore
) {
    fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                DiagnosticLog.recordThrowable("managed_crash", throwable)
                reportStore.enqueueFatal(
                    type = "managed_crash",
                    summary = throwable.javaClass.name,
                    body = reportStore.buildReport(
                        type = "managed_crash",
                        summary = "Uncaught ${throwable.javaClass.name}",
                        extraLines = listOf(CrashReportFormatter.stackSummary(throwable))
                    )
                )
            } catch (_: Throwable) {
                // Crash handling must never obscure the original exception.
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
    }

    suspend fun reports(): List<StoredDiagnosticReport> = reportStore.reports()
    suspend fun discard(id: String) = reportStore.discard(id)
}