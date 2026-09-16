package com.nuvio.tv.core.usenet

import java.io.IOException
import java.io.InterruptedIOException
import org.json.JSONObject

/** Never display arbitrary native/provider response text: it may contain credentials. */
internal object NntpErrorMessages {
    const val PROVIDER_SETUP =
        "Check the Usenet server settings in your NZB addon: host, TLS/port, username, password and connection count."

    fun response(statusCode: Int, body: String): String {
        val code = runCatching { JSONObject(body).optString("code") }.getOrDefault("")
        return when (code) {
            "provider_configuration" -> PROVIDER_SETUP
            "provider_connection" ->
                "Cannot connect to your Usenet provider. Check its host, TLS/port, credentials and connection limits in your addon."
            "nzb_load_failed" ->
                "Cannot load the NZB. Check your indexer or addon access, or try another source."
            "no_playable_content" ->
                "The NZB contains no playable media for this selection. Try another source."
            "incomplete_release" ->
                "This release is missing required articles. Try another source."
            "setup_timeout" ->
                "NNTP setup timed out. Check the provider connection or try another source."
            "session_failed" ->
                "Cannot prepare this NNTP stream. Try another source."
            else -> "NNTP engine request failed (HTTP $statusCode). Try another source or restart playback."
        }
    }

    fun transport(error: IOException): String = if (error is InterruptedIOException) {
        "NNTP setup timed out. Check the provider connection or try another source."
    } else {
        "Cannot reach the local NNTP engine. Restart playback; if this continues, restart the app."
    }
}
