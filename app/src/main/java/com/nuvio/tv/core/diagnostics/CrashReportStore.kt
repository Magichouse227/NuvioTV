package com.nuvio.tv.core.diagnostics

import android.content.Context
import android.os.Build
import com.nuvio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores only the most recent uncaught crash. The payload is encrypted with a device-local
 * Android Keystore key and is removed as soon as the user dismisses the next-launch prompt.
 */
@Singleton
class CrashReportStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val KEY_ALIAS = "nuvio_crash_report"
        private const val FILE_NAME = "pending_crash_report"
        private const val MAX_STACK_CHARS = 6_000
        private const val MAX_REPORT_CHARS = 10_000
        private const val VERSION = 1
    }

    fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(CrashReport.from(thread, throwable))
            } catch (_: Exception) {
                // Crash handling must never obscure the original exception.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    @Synchronized
    fun read(): String? {
        val file = context.filesDir.resolve(FILE_NAME)
        if (!file.exists()) return null
        return runCatching {
            val encoded = file.readBytes()
            if (encoded.size < 13) return@runCatching null
            val ivLength = encoded[0].toInt()
            if (ivLength !in 12..16 || encoded.size <= ivLength) return@runCatching null
            decrypt(encoded.copyOfRange(1, 1 + ivLength), encoded.copyOfRange(1 + ivLength, encoded.size))
                .take(MAX_REPORT_CHARS)
        }.getOrNull()
    }

    @Synchronized
    fun clear() {
        context.filesDir.resolve(FILE_NAME).delete()
    }

    @Synchronized
    private fun write(report: String) {
        val encrypted = encrypt(report.take(MAX_REPORT_CHARS).toByteArray(StandardCharsets.UTF_8))
        val output = ByteArray(1 + encrypted.first.size + encrypted.second.size)
        output[0] = encrypted.first.size.toByte()
        encrypted.first.copyInto(output, 1)
        encrypted.second.copyInto(output, 1 + encrypted.first.size)
        val file = context.filesDir.resolve(FILE_NAME)
        val temporary = context.filesDir.resolve("$FILE_NAME.tmp")
        temporary.writeBytes(output)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            error("Unable to atomically save crash report")
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun encrypt(value: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv to cipher.doFinal(value)
    }

    private fun decrypt(iv: ByteArray, value: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, iv)
        )
        return String(cipher.doFinal(value), StandardCharsets.UTF_8)
    }

    private data class CrashReport(
        val thread: String,
        val throwable: Throwable
    ) {
        fun render(): String {
            val trace = StringWriter().also { writer ->
                throwable.printStackTrace(PrintWriter(writer))
            }.toString().take(MAX_STACK_CHARS)
            return buildString {
                appendLine("NuvioTV uncaught crash report (format $VERSION)")
                appendLine("appVersion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("package=${BuildConfig.APPLICATION_ID}")
                appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("thread=${thread.take(80)}")
                appendLine()
                appendLine(redact(trace))
            }.take(MAX_REPORT_CHARS)
        }

        companion object {
            fun from(thread: Thread, throwable: Throwable): String =
                CrashReport(thread.name, throwable).render()

            private fun redact(value: String): String {
                var redacted = value
                redacted = redacted.replace(
                    Regex("(?i)(authorization|bearer|token|password|secret|api[_-]?key)(\\s*[:=]\\s*)[^\\s,;]+"),
                    "$1$2[REDACTED]"
                )
                redacted = redacted.replace(
                    Regex("(?i)([?&](?:token|password|secret|api[_-]?key)=)[^&\\s]+"),
                    "$1[REDACTED]"
                )
                return redacted
            }
        }
    }
}