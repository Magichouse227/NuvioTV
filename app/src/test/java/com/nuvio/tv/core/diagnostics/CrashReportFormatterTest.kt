package com.nuvio.tv.core.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportFormatterTest {
    @Test
    fun `messages headers URLs and nested secrets never enter the report`() {
        val secrets = listOf(
            "Authorization: Bearer private-token",
            "https://username:password@example.com/nzb?api_key=private-key",
            "Cookie: session=private-cookie",
            "email=person@example.com"
        )
        val error = IllegalStateException(
            secrets.joinToString(" "),
            IllegalArgumentException("Nested private-token")
        )
        val report = CrashReportFormatter.stackSummary(error)
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("java.lang.IllegalArgumentException"))
        secrets.forEach { assertFalse(report.contains(it)) }
        assertFalse(report.contains("private-token"))
        assertFalse(report.contains("person@example.com"))
    }

    @Test
    fun `does not invoke custom throwable string rendering`() {
        val error = object : RuntimeException() {
            override fun toString(): String = error("Do not render exception messages")
        }
        assertTrue(CrashReportFormatter.stackSummary(error).isNotEmpty())
    }

    @Test
    fun `bounds large traces and cyclic causes`() {
        val first = RuntimeException("first")
        val second = RuntimeException("second")
        first.initCause(second)
        second.initCause(first)
        first.stackTrace = Array(1_000) {
            StackTraceElement("example.Class", "method", "IgnoredFile.kt", it)
        }
        val report = CrashReportFormatter.stackSummary(first)
        assertTrue(report.length <= 4_000)
        assertTrue(report.lines().count { it.trimStart().startsWith("at ") } <= 20)
        assertFalse(report.contains("IgnoredFile.kt"))
    }
}