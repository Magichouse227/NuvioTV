package com.nuvio.tv.core.livetv

import java.io.Reader
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

object M3uParser {
    const val MAX_CHANNELS = 20_000
    const val MAX_CHARACTERS = 16 * 1024 * 1024
    private val attributes = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]+))""")

    fun parse(reader: Reader, source: LiveTvSource, checkCancelled: () -> Unit = {}): List<LiveTvChannel> {
        val result = linkedMapOf<String, LiveTvChannel>()
        var name = ""
        var logo: String? = null
        var group: String? = null
        var headers = mutableMapOf<String, String>()
        var consumed = 0
        val line = StringBuilder()
        val input = reader.buffered()

        fun consume(raw: String): Boolean {
            checkCancelled()
            val value = raw.trim().trimStart('\uFEFF')
            if (value.startsWith("#EXT-X-")) {
                // This is a stream manifest, not a channel playlist. Never list its segments.
                result.clear()
                networkUrl(source.url, null)?.let { url ->
                    result[channelId(source.id, url)] = LiveTvChannel(channelId(source.id, url), source.id, source.name, url)
                }
                return false
            }
            when {
                value.startsWith("#EXTINF:", true) -> {
                    val comma = metadataComma(value)
                    name = if (comma >= 0) value.substring(comma + 1).trim() else ""
                    val fields = attributes.findAll(if (comma >= 0) value.substring(0, comma) else value)
                        .associate { it.groupValues[1].lowercase() to it.groupValues.drop(2).firstOrNull { v -> v.isNotEmpty() }.orEmpty() }
                    name = name.ifBlank { fields["tvg-name"].orEmpty() }
                    logo = fields["tvg-logo"]?.let { networkUrl(it, source.url) }
                    group = fields["group-title"]
                }
                value.startsWith("#EXTGRP:", true) -> group = value.substringAfter(':').trim()
                value.startsWith("#EXTVLCOPT:", true) -> {
                    val key = value.substringAfter(':').substringBefore('=').lowercase()
                    val header = when (key) {
                        "http-user-agent" -> "User-Agent"
                        "http-referrer", "http-referer" -> "Referer"
                        "http-origin" -> "Origin"
                        else -> null
                    }
                    if (header != null) safeHeader(value.substringAfter('=', ""))?.let { headers[header] = it }
                }
                value.isNotEmpty() && !value.startsWith("#") -> {
                    val parts = value.split('|', limit = 2)
                    val url = networkUrl(parts[0], source.url)
                    if (parts.size == 2) parts[1].split('&').forEach { field ->
                        val key = decode(field.substringBefore('='))
                        val decoded = safeHeader(decode(field.substringAfter('=', "")))
                        if (key.matches(Regex("[A-Za-z0-9-]+")) && decoded != null) headers[key] = decoded
                    }
                    if (url != null) {
                        val id = channelId(source.id, url)
                        result[id] = LiveTvChannel(id, source.id, name.ifBlank { source.name }, url, logo, group, headers.toMap())
                    }
                    name = ""; logo = null; group = null; headers = mutableMapOf()
                }
            }
            return result.size < MAX_CHANNELS
        }

        while (true) {
            if (consumed % 4096 == 0) checkCancelled()
            val c = input.read()
            if (c == -1) { if (line.isNotEmpty()) consume(line.toString()); break }
            require(++consumed <= MAX_CHARACTERS) { "Playlist exceeds the supported size" }
            if (c == '\n'.code) {
                if (!consume(line.toString())) break
                line.setLength(0)
            } else {
                require(line.length < 128 * 1024) { "Playlist line exceeds the supported size" }
                line.append(c.toChar())
            }
        }
        return result.values.toList()
    }

    fun channelId(sourceId: String, value: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$sourceId\n$value".toByteArray(Charsets.UTF_8))
        .joinToString("") { byte ->
            val valueByte = byte.toInt() and 0xff
            "${"0123456789abcdef"[valueByte ushr 4]}${"0123456789abcdef"[valueByte and 15]}"
        }

    fun networkUrl(value: String, base: String?): String? = runCatching {
        val uri = if (base == null) URI(value.trim()) else URI(base).resolve(value.trim())
        uri.takeIf { it.scheme?.lowercase() in setOf("http", "https", "rtsp") && !it.host.isNullOrBlank() }?.toASCIIString()
    }.getOrNull()

    private fun safeHeader(value: String): String? = value.takeIf { '\r' !in it && '\n' !in it }
    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun metadataComma(value: String): Int {
        var quote: Char? = null
        value.forEachIndexed { index, c ->
            if (c == quote) quote = null
            else if (quote == null && (c == '"' || c == '\'')) quote = c
            else if (quote == null && c == ',') return index
        }
        return -1
    }
}
