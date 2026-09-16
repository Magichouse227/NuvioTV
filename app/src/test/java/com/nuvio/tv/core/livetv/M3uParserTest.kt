package com.nuvio.tv.core.livetv

import java.io.StringReader
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class M3uParserTest {
    private val source = LiveTvSource("source-a", "Playlist", LiveTvSourceType.M3U, "https://example.test/folder/list.m3u")

    @Test fun quotedCommasRelativeUrlsAndHeaders() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://example.test/logo.png" group-title="News, world",Channel, One
            #EXTVLCOPT:http-user-agent=TV Player
            ../live.m3u8|Referer=https%3A%2F%2Fexample.test%2Fwatch
        """.trimIndent()
        val channel = M3uParser.parse(StringReader(playlist), source).single()
        assertEquals("Channel, One", channel.name)
        assertEquals("News, world", channel.group)
        assertEquals("https://example.test/live.m3u8", channel.url)
        assertEquals("TV Player", channel.headers["User-Agent"])
        assertEquals("https://example.test/watch", channel.headers["Referer"])
    }

    @Test fun idsSurviveReorderingAndSeparateProfilesSources() {
        val first = M3uParser.parse(StringReader("https://example.test/one\nhttps://example.test/two"), source)
        val reordered = M3uParser.parse(StringReader("https://example.test/two\nhttps://example.test/one"), source)
        assertEquals(first[0].id, reordered[1].id)
        assertNotEquals(first[0].id, M3uParser.parse(StringReader(first[0].url), source.copy(id = "other")).single().id)
        assertTrue(first[0].id.startsWith("source-a:"))
    }

    @Test fun rejectsLocalSchemesAndHeaderInjection() {
        val playlist = "file:///etc/passwd\njavascript:bad\nhttps://example.test/one|User-Agent=bad%0D%0AInjected%3Ayes"
        val channel = M3uParser.parse(StringReader(playlist), source).single()
        assertTrue(channel.headers.isEmpty())
        assertEquals("https://example.test/one", channel.url)
    }

    @Test fun hlsManifestIsOneStreamAndNeverItsSegments() {
        val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nsegment.ts"
        val channel = M3uParser.parse(StringReader(playlist), source).single()
        assertEquals(source.url, channel.url)
    }

    @Test fun emptyMetadataAndDuplicateUrlsAreAccepted() {
        val playlist = "#EXTINF:-1 tvg-logo=\"\" group-title=\"\",Name\nhttps://example.test/one\nhttps://example.test/one"
        val channels = M3uParser.parse(StringReader(playlist), source)
        assertEquals(1, channels.size)
        assertNull(channels.single().logo)
    }

    @Test(expected = CancellationException::class)
    fun cancelledProfileStopsParsing() {
        M3uParser.parse(StringReader("https://example.test/one"), source) { throw CancellationException() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun boundsMalformedLongLines() {
        M3uParser.parse(StringReader("x".repeat(128 * 1024 + 1)), source)
    }
}
