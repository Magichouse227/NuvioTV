package com.nuvio.tv.core.usenet

import com.nuvio.tv.core.network.IPv4FirstDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class NntpSessionRequest(
    val nzbUrl: String,
    val servers: List<String>,
    val fileIdx: Int?,
    val fileMustInclude: String?,
    val season: Int?,
    val episode: Int?
) {
    override fun toString(): String =
        "NntpSessionRequest(fileIdx=$fileIdx, season=$season, episode=$episode, servers=${servers.size})"
}

data class NntpSession(
    val id: String,
    val streamUrl: String
)

data class NntpSessionStats(
    val downloadedBytes: Long,
    val downloadSpeed: Long,
    val connections: Int
)

@Singleton
class NntpEngineApi @Inject constructor(
    private val binary: NntpEngineBinary
) {
    companion object {
        private val JSON_TYPE = "application/json".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun createSession(
        sessionRequest: NntpSessionRequest,
        onSessionCreated: (String) -> Unit = {}
    ): NntpSession = withContext(NonCancellable + Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("nzbUrl", sessionRequest.nzbUrl)
            put("servers", JSONArray(sessionRequest.servers))
            sessionRequest.fileIdx?.let { put("fileIdx", it) }
            sessionRequest.fileMustInclude?.takeIf { it.isNotBlank() }
                ?.let { put("fileMustInclude", it) }
            sessionRequest.season?.takeIf { it > 0 }?.let { put("season", it) }
            sessionRequest.episode?.takeIf { it > 0 }?.let { put("episode", it) }
        }
        val request = Request.Builder()
            .url("${binary.baseUrl}/v1/sessions")
            .header(NntpEngineBinary.MANAGEMENT_TOKEN_HEADER, binary.managementToken)
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body.string()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(responseText).optString("error")
                }.getOrNull().orEmpty().ifBlank { "HTTP ${response.code}" }
                throw NntpException(message)
            }
            val json = JSONObject(responseText)
            val id = json.optString("id")
            if (id.isBlank()) {
                throw NntpException("NNTP engine returned an invalid session")
            }
            // This callback must run before the NonCancellable IO context returns. The
            // caller can therefore delete a session even if cancellation wins while the
            // response is being dispatched back to its cancelled coroutine.
            onSessionCreated(id)
            val streamUrl = json.optString("streamUrl")
            if (streamUrl.isBlank()) {
                throw NntpException("NNTP engine returned an invalid session")
            }
            NntpSession(id = id, streamUrl = streamUrl)
        }
    }

    suspend fun getStats(sessionId: String): NntpSessionStats? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${binary.baseUrl}/v1/sessions/$sessionId")
            .header(NntpEngineBinary.MANAGEMENT_TOKEN_HEADER, binary.managementToken)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(response.body.string())
                NntpSessionStats(
                    downloadedBytes = json.optLong("downloadedBytes"),
                    downloadSpeed = json.optLong("downloadSpeed"),
                    connections = json.optInt("connections")
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun deleteSession(sessionId: String) = withContext(NonCancellable + Dispatchers.IO) {
        val request = Request.Builder()
            .url("${binary.baseUrl}/v1/sessions/$sessionId")
            .header(NntpEngineBinary.MANAGEMENT_TOKEN_HEADER, binary.managementToken)
            .delete()
            .build()
        try {
            client.newCall(request).execute().close()
        } catch (_: Exception) {
            // Session cleanup is best effort during player teardown.
        }
    }
}
