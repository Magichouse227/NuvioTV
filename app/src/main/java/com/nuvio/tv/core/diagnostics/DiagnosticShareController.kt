package com.nuvio.tv.core.diagnostics

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.nuvio.tv.core.server.DeviceIpAddress
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DiagnosticShareLink(
    val url: String,
    val expiresAtElapsedMs: Long
)

/**
 * Starts only after the user explicitly asks to review a report on their phone.  The token is
 * required for every route, the report is never put in a public URL, and the server is stopped
 * after a short review window.
 */
@Singleton
class DiagnosticShareController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reportStore: DiagnosticReportStore
) {
    companion object {
        private const val SHARE_WINDOW_MS = 10 * 60 * 1_000L
    }

    private var server: ReportServer? = null
    private var expiryElapsedMs = 0L
    private var shareGeneration = 0L
    private val shareScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun open(reportId: String): Result<DiagnosticShareLink> = withContext(Dispatchers.IO) {
        runCatching {
            val report = reportStore.report(reportId) ?: error("Saved report is no longer available")
            synchronized(this@DiagnosticShareController) {
                server?.stop()
                val token = token()
                val started = (43_000..43_020).firstNotNullOfOrNull { port ->
                    runCatching {
                        ReportServer(port, token, report).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
                    }.getOrNull()
                } ?: error("Could not start local report sharing")
                val address = localIpv4Address() ?: run {
                    started.stop()
                    error("No local network address is available")
                }
                server = started
                expiryElapsedMs = SystemClock.elapsedRealtime() + SHARE_WINDOW_MS
                val generation = ++shareGeneration
                shareScope.launch {
                    delay(SHARE_WINDOW_MS)
                    synchronized(this@DiagnosticShareController) {
                        if (generation == shareGeneration && SystemClock.elapsedRealtime() >= expiryElapsedMs) close()
                    }
                }
                DiagnosticShareLink("http://$address:${started.listeningPort}/?t=$token", expiryElapsedMs)
            }
        }
    }

    fun close() = synchronized(this) {
        server?.stop()
        server = null
        expiryElapsedMs = 0L
        shareGeneration++
    }

    private fun token(): String {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun localIpv4Address(): String? = DeviceIpAddress.get(context)

    private class ReportServer(
        port: Int,
        private val token: String,
        private val report: StoredDiagnosticReport
    ) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            if (session.parameters["t"]?.singleOrNull() != token) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
            return when {
                session.method == Method.GET && session.uri == "/" ->
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", DiagnosticShareHtml.render(report, token))
                session.method == Method.GET && session.uri == "/report.txt" -> {
                    val bytes = report.body.toByteArray(Charsets.UTF_8)
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "text/plain; charset=utf-8",
                        ByteArrayInputStream(bytes),
                        bytes.size.toLong()
                    ).apply {
                        addHeader("Content-Disposition", "attachment; filename=\"nuvio-diagnostic-${report.id.take(12)}.txt\"")
                        addHeader("Cache-Control", "no-store")
                    }
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }.apply {
                addHeader("Cache-Control", "no-store")
                addHeader("Referrer-Policy", "no-referrer")
                addHeader("X-Content-Type-Options", "nosniff")
                addHeader(
                    "Content-Security-Policy",
                    "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
                        "base-uri 'none'; frame-ancestors 'none'"
                )
            }
        }

    }
}