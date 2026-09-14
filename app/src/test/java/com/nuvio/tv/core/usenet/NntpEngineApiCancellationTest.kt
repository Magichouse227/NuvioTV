package com.nuvio.tv.core.usenet

import io.mockk.every
import io.mockk.mockk
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test

class NntpEngineApiCancellationTest {
    @Test
    fun cancellationCancelsTheRealOkHttpPostWithoutWaitingForReadTimeout() = runTest {
        val server = ServerSocket(
            NntpEngineBinary.PORT,
            1,
            InetAddress.getByName("127.0.0.1")
        )
        val requestReceived = CountDownLatch(1)
        val allowResponse = CountDownLatch(1)
        val serverThread = thread(isDaemon = true, name = "nntp-api-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequestHeaders(socket)
                    requestReceived.countDown()
                    allowResponse.await(3, TimeUnit.SECONDS)
                    val body = """{"id":"late","streamUrl":"http://127.0.0.1/late"}"""
                    socket.getOutputStream().bufferedWriter().use { output ->
                        output.write(
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${body.toByteArray().size}\r\n" +
                                "Connection: close\r\n\r\n" +
                                body
                        )
                        output.flush()
                    }
                }
            }
        }

        try {
            val binary = mockk<NntpEngineBinary>()
            every { binary.baseUrl } returns "http://127.0.0.1:${NntpEngineBinary.PORT}"
            every { binary.managementToken } returns "test-management-token"
            val api = NntpEngineApi(binary)
            val request = NntpSessionRequest(
                nzbUrl = "https://nzb.example/title.nzb",
                servers = listOf("nntps://news.example"),
                fileIdx = null,
                fileMustInclude = null,
                season = null,
                episode = null
            )
            val knownIds = mutableListOf<String>()
            val cleanedIds = mutableListOf<String>()

            val call = async(Dispatchers.IO) {
                createNntpSessionSafely(
                    create = { onCreated ->
                        api.createSession(request) { id ->
                            knownIds += id
                            onCreated(id)
                        }
                    },
                    publish = {},
                    cleanup = { cleanedIds += it }
                )
            }
            assertTrue(requestReceived.await(10, TimeUnit.SECONDS))
            assertTrue(knownIds.single().matches(Regex("[0-9a-f]{32}")))
            call.cancel()
            // Use a wall-clock timeout for real IO, not runTest's virtual clock. Keep the
            // response blocked until cleanup so a blocking POST cannot accidentally pass.
            withContext(Dispatchers.IO) {
                withTimeout(2_000L) { call.join() }
            }
            assertTrue(call.isCancelled)
            assertTrue(cleanedIds == knownIds)
        } finally {
            allowResponse.countDown()
            server.close()
            serverThread.join(2_000L)
        }
    }

    private fun readRequestHeaders(socket: Socket) {
        val input = socket.getInputStream()
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        var matched = 0
        while (true) {
            val current = input.read()
            if (current < 0) return
            matched = if (current == terminator[matched].toInt()) matched + 1 else 0
            if (matched == terminator.size) return
        }
    }
}