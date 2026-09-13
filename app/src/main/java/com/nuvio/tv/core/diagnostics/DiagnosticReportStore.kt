package com.nuvio.tv.core.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Base64
import com.nuvio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A device-local encrypted retained queue.  Nothing in this class uploads data: a person must
 * explicitly open the short-lived LAN page and submit the resulting GitHub issue themselves.
 */
@Singleton
class DiagnosticReportStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val KEY_ALIAS = "nuvio_diagnostic_reports_v2"
        private const val FILE_NAME = "diagnostic_reports_v2"
        private const val SESSION_FILE_NAME = "diagnostic_session_v2"
        private const val LOG_FILE_NAME = "diagnostic_log_v2"
        private const val MAX_REPORTS = 12
        private const val MAX_REPORT_CHARS = 48_000
        private const val MAX_LOG_ENTRIES = 240
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val diskLock = Any()
    private val logLock = Any()
    private val initialized = CompletableDeferred<Unit>()
    private var pendingLogEntries: List<String> = emptyList()
    private var logWriteScheduled = false

    fun persistLogAsync(entries: List<String>) {
        synchronized(logLock) {
            pendingLogEntries = entries.takeLast(MAX_LOG_ENTRIES)
            if (logWriteScheduled) return
            logWriteScheduled = true
        }
        ioScope.launch {
            // Coalesce bursty player events, while preserving a durable bounded trail.
            delay(250)
            while (true) {
                val toWrite = synchronized(logLock) {
                    val current = pendingLogEntries
                    pendingLogEntries = emptyList()
                    current
                }
                synchronized(diskLock) { writeLogBlocking(toWrite) }
                val finished = synchronized(logLock) {
                    if (pendingLogEntries.isNotEmpty()) {
                        false
                    } else {
                        logWriteScheduled = false
                        true
                    }
                }
                if (finished) {
                    return@launch
                }
            }
        }
    }

    suspend fun initialize() {
        try {
            withContext(Dispatchers.IO) {
                synchronized(diskLock) {
                    DiagnosticLog.restore(readLogBlocking())
                    beginProcessSessionBlocking()
                }
            }
        } finally {
            // Do not hold the startup UI indefinitely if encrypted storage is unavailable.
            initialized.complete(Unit)
        }
    }

    suspend fun awaitInitialization() = initialized.await()

    suspend fun enqueue(type: String, summary: String, body: String): StoredDiagnosticReport =
        withContext(Dispatchers.IO) { synchronized(diskLock) { enqueueBlocking(type, summary, body) } }

    fun enqueueFatal(type: String, summary: String, body: String): StoredDiagnosticReport =
        synchronized(diskLock) {
            // Fault handling must not leave the last accepted trail only in memory.
            writeLogBlocking(DiagnosticLog.snapshot())
            enqueueBlocking(type, summary, body)
        }

    suspend fun reports(): List<StoredDiagnosticReport> =
        withContext(Dispatchers.IO) { synchronized(diskLock) { readBlocking() } }

    suspend fun discard(id: String) {
        withContext(Dispatchers.IO) {
            synchronized(diskLock) {
                writeBlocking(readBlocking().filterNot { it.id == id })
            }
        }
    }

    suspend fun report(id: String): StoredDiagnosticReport? =
        withContext(Dispatchers.IO) { synchronized(diskLock) { readBlocking().firstOrNull { it.id == id } } }

    private fun beginProcessSessionBlocking() {
        val previous = runCatching { decryptFile(SESSION_FILE_NAME) }.getOrNull()
        val previousSession = Session.parse(previous)
        val exit = previousSession?.let(::abnormalExitDescription)
        val managedCrashAlreadyRecorded = previousSession?.let { session ->
            readBlocking().any { report ->
                report.type == "managed_crash" && report.createdAtMs >= session.startedAtMs
            }
        } == true
        if (exit != null && !managedCrashAlreadyRecorded) {
            enqueueFatal(
                type = "unclean_exit",
                summary = exit,
                body = buildReport(
                    "unclean_exit",
                    exit,
                    DiagnosticLog.snapshot()
                )
            )
        }
        writeSession("foreground")
    }

    fun markAppForeground() = updateSession("foreground")

    fun markAppBackground() = updateSession("background")

    private fun updateSession(state: String) {
        ioScope.launch {
            awaitInitialization()
            synchronized(diskLock) { writeSession(state) }
        }
    }

    private fun enqueueBlocking(type: String, summary: String, body: String): StoredDiagnosticReport {
        val now = System.currentTimeMillis()
        val record = StoredDiagnosticReport(
            id = newId(),
            type = DiagnosticSanitizer.category(type),
            createdAtMs = now,
            summary = DiagnosticSanitizer.text(summary, 240),
            body = DiagnosticSanitizer.text(body, MAX_REPORT_CHARS)
        )
        val updated = retainReports(readBlocking() + record)
        writeBlocking(updated)
        return record
    }

    private fun retainReports(records: List<StoredDiagnosticReport>): List<StoredDiagnosticReport> {
        val kept = records.toMutableList()
        val protectedCrashId = kept.lastOrNull { it.type == "managed_crash" }?.id
        while (kept.size > MAX_REPORTS) {
            val removeAt = kept.indexOfFirst { it.id != protectedCrashId }.takeIf { it >= 0 } ?: 0
            kept.removeAt(removeAt)
        }
        return kept
    }

    fun buildReport(type: String, summary: String, extraLines: List<String>): String = buildString {
        appendLine("NuvioTV diagnostic report")
        appendLine("format=2")
        appendLine("type=${DiagnosticSanitizer.category(type)}")
        appendLine("createdAtMs=${System.currentTimeMillis()}")
        appendLine("appVersion=${DiagnosticSanitizer.text(BuildConfig.VERSION_NAME, 80)} (${BuildConfig.VERSION_CODE})")
        appendLine("build=${DiagnosticSanitizer.text(BuildConfig.TEST_BUILD_SHA, 80)}")
        appendLine("package=${BuildConfig.APPLICATION_ID}")
        appendLine("device=${DiagnosticSanitizer.text(Build.MANUFACTURER, 60)} ${DiagnosticSanitizer.text(Build.MODEL, 100)}")
        appendLine("android=${DiagnosticSanitizer.text(Build.VERSION.RELEASE, 40)} API=${Build.VERSION.SDK_INT}")
        appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",") { DiagnosticSanitizer.text(it, 40) }}")
        appendLine("summary=${DiagnosticSanitizer.text(summary, 1_000)}")
        appendLine()
        appendLine("Safe events (URLs, credentials, headers, and thread names are removed):")
        (DiagnosticLog.snapshot() + extraLines.map { DiagnosticSanitizer.text(it, 1_000) })
            .takeLast(240)
            .joinTo(this, separator = "\n", postfix = "\n")
    }.take(MAX_REPORT_CHARS)

    private fun abnormalExitDescription(session: Session): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val exit = runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getHistoricalProcessExitReasons(null, 0, 20)
                .firstOrNull { it.pid == session.pid && it.timestamp >= session.startedAtMs }
        }.getOrNull() ?: return null
        val abnormalReasons = setOf(
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_SIGNALED
        )
        if (exit.reason !in abnormalReasons) return null
        return "Android correlated this prior process exit as abnormal (reason=${exit.reason})"
    }

    private fun readBlocking(): List<StoredDiagnosticReport> {
        val plaintext = runCatching { decryptFile(FILE_NAME) }.getOrNull() ?: return emptyList()
        return plaintext.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 5) return@mapNotNull null
            runCatching {
                StoredDiagnosticReport(
                    id = parts[0],
                    type = parts[1],
                    createdAtMs = parts[2].toLong(),
                    summary = decode(parts[3]),
                    body = decode(parts[4])
                )
            }.getOrNull()
        }.toList().let(::retainReports)
    }

    private fun writeBlocking(reports: List<StoredDiagnosticReport>) {
        val payload = retainReports(reports).joinToString("\n") {
            listOf(it.id, it.type, it.createdAtMs.toString(), encode(it.summary), encode(it.body)).joinToString("\t")
        }
        encryptFile(FILE_NAME, payload)
    }

    private fun readLogBlocking(): List<String> =
        runCatching { decryptFile(LOG_FILE_NAME) }.getOrNull()
            ?.lineSequence()
            ?.map { DiagnosticSanitizer.text(it, 1_000) }
            ?.filter(String::isNotBlank)
            ?.takeLast(MAX_LOG_ENTRIES)
            ?.toList()
            ?: emptyList()

    private fun writeLogBlocking(entries: List<String>) {
        encryptFile(
            LOG_FILE_NAME,
            entries.takeLast(MAX_LOG_ENTRIES).joinToString("\n") { DiagnosticSanitizer.text(it, 1_000) }
        )
    }

    private fun writeSession(state: String) {
        encryptFile(SESSION_FILE_NAME, "${Process.myPid()}\t${System.currentTimeMillis()}\t$state")
    }

    private data class Session(val pid: Int, val startedAtMs: Long, val state: String) {
        companion object {
            fun parse(value: String?): Session? = value?.split('\t')?.takeIf { it.size == 3 }?.let {
                runCatching { Session(it[0].toInt(), it[1].toLong(), it[2]) }.getOrNull()
            }
        }
    }

    private fun newId(): String {
        val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    private fun decode(value: String): String =
        String(Base64.decode(value, Base64.NO_WRAP), StandardCharsets.UTF_8)

    private fun encryptFile(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val output = ByteArray(1 + cipher.iv.size + encrypted.size)
        output[0] = cipher.iv.size.toByte()
        cipher.iv.copyInto(output, 1)
        encrypted.copyInto(output, 1 + cipher.iv.size)
        val temporary = context.filesDir.resolve("$name.tmp")
        temporary.writeBytes(output)
        if (!temporary.renameTo(context.filesDir.resolve(name))) {
            temporary.delete()
            error("Unable to atomically save diagnostic report")
        }
    }

    private fun decryptFile(name: String): String? {
        val encoded = context.filesDir.resolve(name).takeIf { it.exists() }?.readBytes() ?: return null
        if (encoded.size < 13) return null
        val ivLength = encoded[0].toInt()
        if (ivLength !in 12..16 || encoded.size <= ivLength) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encoded.copyOfRange(1, 1 + ivLength)))
        }
        return String(cipher.doFinal(encoded.copyOfRange(1 + ivLength, encoded.size)), StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }
}