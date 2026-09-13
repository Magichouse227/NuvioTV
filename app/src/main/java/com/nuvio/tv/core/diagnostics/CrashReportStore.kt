package com.nuvio.tv.core.diagnostics

import android.content.Context
import android.os.Build
import com.nuvio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
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
        private const val MAX_REPORT_CHARS = 10_000
        private const val VERSION = 1
    }

    fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(CrashReport.from(throwable))
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
        val throwable: Throwable
    ) {
        fun render(): String {
            return buildString {
                appendLine("NuvioTV uncaught crash report (format $VERSION)")
                appendLine("appVersion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("build=${BuildConfig.TEST_BUILD_SHA}")
                appendLine("package=${BuildConfig.APPLICATION_ID}")
                appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Exception messages and thread names omitted for privacy.")
                appendLine()
                appendLine(CrashReportFormatter.stackSummary(throwable))
            }.take(MAX_REPORT_CHARS)
        }

        companion object {
            fun from(throwable: Throwable): String = CrashReport(throwable).render()
        }
    }
}