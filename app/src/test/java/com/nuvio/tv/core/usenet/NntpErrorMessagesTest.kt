package com.nuvio.tv.core.usenet

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NntpErrorMessagesTest {
    @Test
    fun knownCodesGiveActionableMessagesWithoutReflectingProviderText() {
        val expectedHints = mapOf(
            "provider_configuration" to "NZB addon",
            "provider_connection" to "connection limits",
            "nzb_load_failed" to "indexer",
            "no_playable_content" to "no playable media",
            "incomplete_release" to "missing required articles",
            "setup_timeout" to "timed out",
            "session_failed" to "another source"
        )
        expectedHints.forEach { (code, hint) ->
            val message = NntpErrorMessages.response(
                400, """{"code":"$code","error":"private-user:private-password?apikey=private-key"}"""
            )
            assertTrue(message.contains(hint))
            assertFalse(message.contains("private-"))
        }
    }

    @Test
    fun unknownAndMalformedResponsesUseSafeHttpFallback() {
        listOf("", "<html>private-key</html>", """{"error":"private-key"}""",
            """{"code":"private-key","error":"private-password"}""").forEach { body ->
            val message = NntpErrorMessages.response(502, body)
            assertTrue(message.contains("HTTP 502"))
            assertFalse(message.contains("private-"))
        }
    }

    @Test
    fun timeoutAndTransportFailuresNeverExposeExceptionDetails() {
        assertTrue(NntpErrorMessages.transport(SocketTimeoutException("private-key")).contains("timed out"))
        val message = NntpErrorMessages.transport(IOException("private-key"))
        assertTrue(message.contains("local NNTP engine"))
        assertFalse(message.contains("private-"))
        assertEquals(NntpErrorMessages.PROVIDER_SETUP,
            NntpErrorMessages.response(400, """{"code":"provider_configuration"}"""))
    }
}
