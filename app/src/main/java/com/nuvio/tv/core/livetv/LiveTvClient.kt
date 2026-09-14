package com.nuvio.tv.core.livetv

import android.content.Context
import android.net.Uri
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.FilterReader
import java.io.IOException
import java.io.Reader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * TV adapters for the Live TV features in NuvioMobile-Enhanced.
 * See docs/enhanced-tv.md for attribution, limits, and device verification.
 */
@Singleton
class LiveTvClient @Inject constructor(@ApplicationContext private val context: Context) {
    // Dedicated client: provider credentials must not reach diagnostic request interceptors.
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()

    suspend fun channels(source: LiveTvSource): List<LiveTvChannel> = when (source.type) {
        LiveTvSourceType.M3U -> request(source.url) { reader, cancelled ->
            M3uParser.parse(reader, source, cancelled)
        }
        LiveTvSourceType.LOCAL_M3U -> withContext(Dispatchers.IO) {
            val jobContext = coroutineContext
            val input = context.contentResolver.openInputStream(Uri.parse(source.url))
                ?: throw IOException("Playlist cannot be opened")
            input.bufferedReader().use { M3uParser.parse(it, source) { jobContext.ensureActive() } }
        }
        LiveTvSourceType.XTREAM -> xtream(source)
        LiveTvSourceType.STALKER -> stalkerChannels(source)
    }

    suspend fun prepare(channel: LiveTvChannel, source: LiveTvSource): LiveTvChannel {
        val command = channel.stalkerCommand ?: return channel
        val token = handshake(source)
        val headers = portalHeaders(source, token)
        val payload = portal(source, "itv", "create_link", headers, mapOf(
            "cmd" to command, "series" to "", "forced_storage" to "0", "disable_ad" to "0", "download" to "0"
        ))
        val link = payload.objectOrNull()?.string("cmd").orEmpty()
            .removePrefix("ffmpeg ").removePrefix("auto ").trim()
        val url = M3uParser.networkUrl(link, source.url) ?: throw IOException("Provider did not return a playable channel")
        return channel.copy(url = url, headers = mapOf("User-Agent" to MAG_USER_AGENT))
    }

    private suspend fun xtream(source: LiveTvSource): List<LiveTvChannel> {
        val categories = objectRows(xtreamUrl(source, "get_live_categories"))
            .associate { it.string("category_id") to it.string("category_name") }
        return objectRows(xtreamUrl(source, "get_live_streams")).mapNotNull { row ->
            val streamId = row.string("stream_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val direct = row.string("direct_source").takeIf { it.isNotBlank() }
            val streamUrl = if (direct != null) M3uParser.networkUrl(direct, source.url) else {
                serverBase(source.url).newBuilder().addPathSegment("live")
                    .addPathSegment(source.username).addPathSegment(source.password)
                    .addPathSegment("$streamId.${row.string("container_extension").ifBlank { "ts" }}").build().toString()
            }
            streamUrl?.let {
                LiveTvChannel(M3uParser.channelId(source.id, streamId), source.id,
                    row.string("name").ifBlank { streamId }, it,
                    row.string("stream_icon").takeIf { v -> v.isNotBlank() }?.let { v -> M3uParser.networkUrl(v, source.url) },
                    categories[row.string("category_id")])
            }
        }
    }

    private fun serverBase(value: String): HttpUrl {
        val url = value.toHttpUrl()
        val builder = url.newBuilder().query(null).fragment(null)
        if (url.pathSegments.lastOrNull() in listOf("player_api.php", "get.php")) {
            builder.removePathSegment(url.pathSegments.lastIndex)
        }
        return builder.build()
    }

    private fun xtreamUrl(source: LiveTvSource, action: String): String =
        serverBase(source.url).newBuilder().addPathSegment("player_api.php")
            .addQueryParameter("username", source.username).addQueryParameter("password", source.password)
            .addQueryParameter("action", action).build().toString()

    private suspend fun stalkerChannels(source: LiveTvSource): List<LiveTvChannel> {
        val headers = portalHeaders(source, handshake(source))
        val genresPayload = portal(source, "itv", "get_genres", headers)
        val genres = arrayRows(genresPayload).associate { it.string("id") to it.string("title") }
        val channels = linkedMapOf<String, LiveTvChannel>()
        for (page in 1..2000) {
            coroutineContext.ensureActive()
            val payload = portal(source, "itv", "get_ordered_list", headers,
                mapOf("genre" to "*", "p" to page.toString(), "fav" to "0", "sortby" to "number", "hd" to "0"))
            val obj = payload.objectOrNull()
            val rows = arrayRows(obj?.get("data") ?: payload)
            if (rows.isEmpty()) break
            val previousSize = channels.size
            for (row in rows) {
                val command = row.string("cmd")
                val providerId = row.string("id").ifBlank { command }
                if (providerId.isBlank() || command.isBlank()) continue
                val id = M3uParser.channelId(source.id, providerId)
                channels[id] = LiveTvChannel(id, source.id, row.string("name").ifBlank { providerId }, "",
                    row.string("logo").takeIf { it.isNotBlank() }?.let { M3uParser.networkUrl(it, source.url) },
                    genres[row.string("tv_genre_id")], stalkerCommand = command)
                if (channels.size >= M3uParser.MAX_CHANNELS) break
            }
            val total = obj?.string("total_items")?.toIntOrNull()
            if (channels.size == previousSize || channels.size >= M3uParser.MAX_CHANNELS ||
                (total != null && channels.size >= total)) break
        }
        return channels.values.toList()
    }

    private suspend fun handshake(source: LiveTvSource): String {
        val payload = portal(source, "stb", "handshake", portalHeaders(source), mapOf("token" to ""))
        val token = payload.objectOrNull()?.string("token").orEmpty()
        if (token.isBlank()) throw IOException("Provider authentication failed")
        return token
    }

    private fun portalHeaders(source: LiveTvSource, token: String? = null): Map<String, String> = buildMap {
        put("User-Agent", MAG_USER_AGENT)
        put("X-User-Agent", "Model: MAG254; Link: WiFi")
        put("Referer", source.url.toHttpUrl().newBuilder().query(null).fragment(null).build().toString())
        put("Cookie", "mac=${URLEncoder.encode(source.macAddress.uppercase(), "UTF-8")}; stb_lang=en; timezone=UTC")
        if (token != null) put("Authorization", "Bearer $token")
    }

    private suspend fun portal(source: LiveTvSource, type: String, action: String,
                               headers: Map<String, String>, extra: Map<String, String> = emptyMap()): JsonElement {
        val original = source.url.toHttpUrl()
        val builder = original.newBuilder().query(null).fragment(null)
        val segments = original.pathSegments.filter { it.isNotBlank() }
        if (segments.lastOrNull() !in listOf("portal.php", "load.php")) {
            if (segments.lastOrNull() == "c") {
                val index = original.pathSegments.indexOfLast { it == "c" }
                while (builder.build().pathSegments.size > index) {
                    builder.removePathSegment(index)
                    if (builder.build().encodedPath == "/") break
                }
            }
            if (builder.build().pathSegments.filter { it.isNotBlank() }.lastOrNull() != "server") builder.addPathSegment("server")
            builder.addPathSegment("load.php")
        }
        builder.addQueryParameter("type", type).addQueryParameter("action", action).addQueryParameter("JsHttpRequest", "1-xml")
        if (source.username.isNotBlank()) builder.addQueryParameter("login", source.username)
        if (source.password.isNotBlank()) builder.addQueryParameter("password", source.password)
        extra.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return request(builder.build().toString(), headers) { reader, _ ->
            val root = JsonParser.parseReader(reader)
            root.objectOrNull()?.get("js") ?: root
        }
    }

    private suspend fun objectRows(url: String): List<JsonObject> = request(url) { reader, cancelled ->
        val json = JsonReader(reader)
        val rows = ArrayList<JsonObject>()
        json.beginArray()
        while (json.hasNext() && rows.size < M3uParser.MAX_CHANNELS) {
            cancelled()
            val value = JsonParser.parseReader(json)
            value.objectOrNull()?.let { rows.add(it) }
        }
        rows
    }

    private fun arrayRows(element: JsonElement): List<JsonObject> = when {
        element.isJsonArray -> element.asJsonArray.take(M3uParser.MAX_CHANNELS).mapNotNull { it.objectOrNull() }
        element.isJsonObject -> element.asJsonObject.entrySet().take(M3uParser.MAX_CHANNELS).mapNotNull { it.value.objectOrNull() }
        else -> emptyList()
    }

    private suspend fun <T> request(url: String, headers: Map<String, String> = emptyMap(),
                                    parse: (Reader, () -> Unit) -> T): T = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).apply { headers.forEach { (key, value) -> header(key, value) } }.build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    // Parse on OkHttp's worker and close the response before resuming the UI coroutine.
                    val result = response.use {
                        if (!it.isSuccessful) throw IOException("Provider request failed (HTTP ${it.code})")
                        val body = it.body ?: throw IOException("Empty provider response")
                        LimitedReader(body.charStream()).use { reader ->
                            parse(reader) { continuation.context.ensureActive() }
                        }
                    }
                    continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    private class LimitedReader(reader: Reader) : FilterReader(reader) {
        private var count = 0
        override fun read(): Int = super.read().also { if (it != -1) record(1) }
        override fun read(buffer: CharArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) record(it) }
        private fun record(n: Int) {
            count += n
            if (count > M3uParser.MAX_CHARACTERS) throw IOException("Provider response exceeds the supported size")
        }
    }

    private fun JsonElement.objectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null
    private fun JsonObject.string(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    companion object {
        private const val MAG_USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG254 stbapp"
    }
}
