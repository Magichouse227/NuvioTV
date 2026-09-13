package com.nuvio.tv.core.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSanitizerTest {
    @Test
    fun `redacts raw and encoded URLs plus query credentials`() {
        val secret = "raw-secret"
        val output = DiagnosticSanitizer.text(
            "failed https://user:pass@example.test/nzb/a?api_key=$secret and https%3A%2F%2Fexample.test%2Fpath%3Ftoken%3D$secret"
        )
        assertFalse(output.contains(secret))
        assertFalse(output.contains("example.test"))
        assertTrue(output.contains("[redacted]"))
    }

    @Test
    fun `redacts headers bearer JSON passwords and NNTP auth`() {
        val output = DiagnosticSanitizer.text(
            """Authorization: Bearer abc123 Cookie=session42 {"password":"pw-value","api_key":"key-value"} AUTHINFO USER news-user AUTHINFO PASS news-pass"""
        )
        listOf("abc123", "session42", "pw-value", "key-value", "news-user", "news-pass").forEach {
            assertFalse(output.contains(it))
        }
    }

    @Test
    fun `redacts basic credentials and oauth client secrets`() {
        val output = DiagnosticSanitizer.text(
            """Authorization: Basic dXNlcjpzdXBlcnNlY3JldA== client_secret=oauth-secret {"client_secret":"json-oauth-secret"}"""
        )
        listOf("dXNlcjpzdXBlcnNlY3JldA==", "oauth-secret", "json-oauth-secret").forEach {
            assertFalse(output.contains(it))
        }
        assertTrue(output.contains("[redacted]"))
    }

    @Test
    fun `preserves report line boundaries while bounding text`() {
        val output = DiagnosticSanitizer.text("one\n two\nthree", 100)
        assertTrue(output.lines().size >= 3)
        assertTrue(DiagnosticSanitizer.text("x".repeat(200), 20).length <= 20)
    }
}