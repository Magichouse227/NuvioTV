package com.nuvio.tv.core.usenet

import com.nuvio.tv.core.network.IPv4FirstDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
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
        private val SESSION_ID_PATTERN = Regex("[0-9a-f]{32}")
    }

    private val client = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val cooldownPolicy = NntpCooldownPolicy()

    suspend fun createSession(
        sessionRequest: NntpSessionRequest,
        onSessionCreated: (String) -> Unit = {}
    ): NntpSession = withContext(Dispatchers.IO) {
        val origin = NntpCooldownPolicy.originForNzbUrl(sessionRequest.nzbUrl)
        val requestedSessionId = UUID.randomUUID().toString().replace("-", "")
        val payload = JSONObject().apply {
            put("sessionId", requestedSessionId)
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

        // This check intentionally lives immediately before enqueue. The binary can be restarted
        // or a stop/start cycle can occur without losing this in-memory Android-side gate.
        val nowElapsedMs = cooldownPolicy.nowElapsedMs()
        cooldownPolicy.activeLimit(listOf(origin), nowElapsedMs)?.let { limit ->
            throw NntpException(limit.userMessage(nowElapsedMs), rateLimit = limit)
        }

        // Claim the protocol identity before enqueue. If cancellation races a committed POST and
        // loses the response body, the service still has an authenticated DELETE target.
        onSessionCreated(requestedSessionId)

        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation {
                // Do not make cancellation wait for the 30 second call timeout. If a response
                // races this cancellation, the requested ID was already claimed before enqueue
                // and createNntpSessionSafely can schedule its authenticated DELETE.
                call.cancel()
            }
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: okhttp3.Call, error: IOException) {
                        continuation.resumeWith(
                            Result.failure(NntpException(NntpErrorMessages.transport(error), error))
                        )
                    }

                    override fun onResponse(call: okhttp3.Call, response: Response) {
                        response.use {
                            try {
                                val responseText = response.body?.string().orEmpty()
                                if (response.code == NntpRateLimit.HTTP_STATUS_TOO_MANY_REQUESTS) {
                                    val responseNow = cooldownPolicy.nowElapsedMs()
                                    val rateLimit = NntpRateLimitDecoder.decode(
                                        statusCode = response.code,
                                        headers = response.headers,
                                        body = responseText,
                                        nowElapsedMs = responseNow
                                    ) ?: error("Rate-limit response could not be decoded")
                                    cooldownPolicy.record(
                                        origins = listOf(origin),
                                        retryAfterSeconds = rateLimit.retryAfterRemainingSeconds(responseNow),
                                        cooldownSeconds = rateLimit.cooldownRemainingSeconds(responseNow),
                                        nowElapsedMs = responseNow
                                    )
                                    continuation.resumeWith(
                                        Result.failure(
                                            NntpException(
                                                rateLimit.userMessage(responseNow),
                                                rateLimit = rateLimit
                                            )
                                        )
                                    )
                                    return
                                }
                                if (!response.isSuccessful) {
                                    continuation.resumeWith(
                                        Result.failure(
                                            NntpException(
                                                NntpErrorMessages.response(response.code, responseText)
                                            )
                                        )
                                    )
                                    return
                                }
                                val json = JSONObject(responseText)
                                val id = json.optString("id")
                                if (!SESSION_ID_PATTERN.matches(id) || id != requestedSessionId) {
                                    throw NntpException("NNTP engine returned an invalid session")
                                }
                                val streamUrl = json.optString("streamUrl")
                                if (streamUrl.isBlank()) {
                                    throw NntpException("NNTP engine returned an invalid session")
                                }
                                continuation.resumeWith(
                                    Result.success(NntpSession(id = id, streamUrl = streamUrl))
                                )
                            } catch (error: Throwable) {
                                continuation.resumeWith(Result.failure(error))
                            }
                        }
                    }
                })
            } catch (error: Throwable) {
                continuation.resumeWith(Result.failure(error))
            }
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
                val json = JSONObject(response.body?.string().orEmpty())
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
