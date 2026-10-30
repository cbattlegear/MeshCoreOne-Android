// PortedFrom: MC1Tests/Services/LinkPreviewServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Utilities/URLSafetyCheckerTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.content

import com.meshcoreone.android.core.services.content.HttpFetchAttempt
import com.meshcoreone.android.core.services.content.LinkPreviewScraper
import com.meshcoreone.android.core.services.content.UrlSafetyChecker
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.security.NetworkSecurityPolicy
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], shadows = [FixtureNetworkSecurityPolicyShadow::class])
class OkHttpContentFetchingTest {
    private class HttpFixture(
        private val beforeResponse: (Socket) -> Unit = {},
        private val afterResponse: (Socket) -> Unit = {},
        private val respond: (String) -> ByteArray,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        private val sockets = ConcurrentLinkedQueue<Socket>()
        val requests = ConcurrentLinkedQueue<String>()
        val accepts = AtomicInteger()
        val port: Int get() = server.localPort
        private val worker = Thread {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (error: java.net.SocketException) {
                    if (server.isClosed) break else throw error
                }
                sockets.add(socket)
                accepts.incrementAndGet()
                socket.use {
                    val reader = it.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val request = reader.readLine() ?: return@use
                    val headers = mutableListOf(request)
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        headers.add(header)
                    }
                    requests.add(headers.joinToString("\n"))
                    beforeResponse(it)
                    it.getOutputStream().write(respond(request))
                    it.getOutputStream().flush()
                    afterResponse(it)
                }
            }
        }.apply { name = "WP218-owned-http-fixture"; start() }

        override fun close() {
            server.close()
            sockets.forEach(Socket::close)
            worker.join(5_000)
            check(!worker.isAlive) { "HTTP fixture worker leaked" }
        }
    }

    private fun response(
        body: ByteArray, type: String = "image/png", status: String = "200 OK", extra: String = "",
    ): ByteArray = (
        "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            extra + "Connection: close\r\n\r\n"
        ).toByteArray(Charsets.US_ASCII) + body

    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val fixtureHost = "preview-fixture.example"

    private fun controlledClient(lookups: AtomicInteger = AtomicInteger()) = OkHttpContentFetching(
        lookup = { lookups.incrementAndGet(); listOf(loopback) },
        // This exception is only the loopback test transport; the public production default
        // always uses URLSafetyChecker. Redirect IP literals receive no fixture exception.
        addressSafe = { host, address -> host == fixtureHost && address.isLoopbackAddress },
    )

    @Test
    fun `loadImageData rejects a private redirect before connecting to its listening target`() = runBlocking {
        HttpFixture { response(byteArrayOf(1)) }.use { privateTarget ->
            HttpFixture {
                response(
                    ByteArray(0),
                    status = "302 Found",
                    extra = "Location: http://127.0.0.1:${privateTarget.port}/secret.jpg\r\n",
                )
            }.use { server ->
                val safetyAttempts = ConcurrentLinkedQueue<Pair<String, String>>()
                OkHttpContentFetching(
                    lookup = { host ->
                        if (host == fixtureHost) listOf(loopback) else InetAddress.getAllByName(host).toList()
                    },
                    addressSafe = { host, address ->
                        val addressText = address.hostAddress.orEmpty()
                        safetyAttempts.add(host to addressText)
                        (host == fixtureHost && address.isLoopbackAddress) ||
                            !UrlSafetyChecker.isPrivateOrReserved(addressText)
                    },
                ).use { client ->
                    val scraper = LinkPreviewScraper(client, BitmapPreviewImageProcessor(), isUrlSafe = { true })
                    assertNull(scraper.loadImageData("http://$fixtureHost:${server.port}/photo.jpg"))
                    assertEquals(1, server.accepts.get(), "the public fixture must receive the original request")
                    assertTrue(
                        safetyAttempts.any { (host, address) ->
                            host == "127.0.0.1" && UrlSafetyChecker.isPrivateOrReserved(address)
                        },
                        "the redirect target must reach the production private-address predicate",
                    )
                    assertEquals(0, privateTarget.accepts.get(), "the private redirect target must receive no connection")
                }
            }
        }
    }

    @Test
    fun `production DNS policy rejects private and mixed results before any TCP connection`() = runBlocking {
        HttpFixture { response(byteArrayOf(1)) }.use { server ->
            for (addresses in listOf(
                emptyList(), listOf(loopback),
                listOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)), loopback),
            )) {
                OkHttpContentFetching(lookup = { addresses }).use { client ->
                    assertIs<HttpFetchAttempt.Failed>(
                        client.fetch("http://$fixtureHost:${server.port}/photo.png", 2_000),
                    )
                }
            }
            assertEquals(0, server.accepts.get())
        }
    }

    @Test
    fun `one validated DNS snapshot reaches the actual socket and Range and source user-agent survive`() = runBlocking {
        val lookups = AtomicInteger()
        HttpFixture { response(byteArrayOf(1, 2, 3)) }.use { server ->
            controlledClient(lookups).use { client ->
                val attempt = client.fetch("http://$fixtureHost:${server.port}/photo.png", 2_000, "bytes=0-65535")
                assertIs<HttpFetchAttempt.Started>(attempt)
                attempt.use { assertEquals(listOf<Byte>(1, 2, 3), it.readBounded(3)?.toList()) }
                assertEquals(1, lookups.get())
                val request = server.requests.single()
                assertTrue(request.contains("Range: bytes=0-65535", ignoreCase = true))
                assertTrue(request.contains("User-Agent: MC1LinkPreview/1.0", ignoreCase = true))
            }
        }
    }

    @Test
    fun `decoded gzip bytes enforce the actual cap rather than compressed Content-Length`() = runBlocking {
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(ByteArray(16_384)) }
        }.toByteArray()
        HttpFixture { response(compressed, extra = "Content-Encoding: gzip\r\n") }.use { server ->
            controlledClient().use { client ->
                val attempt = client.fetch("http://$fixtureHost:${server.port}/photo.png", 2_000)
                assertIs<HttpFetchAttempt.Started>(attempt)
                attempt.use {
                    assertNull(it.expectedContentLength)
                    assertNull(it.readBounded(8_192))
                }
            }
        }
    }

    @Test
    fun `cancelling before headers closes the actual socket and never returns a response`() = runBlocking {
        val requestReceived = CompletableDeferred<Unit>()
        val socketClosed = CompletableDeferred<Boolean>()
        HttpFixture(beforeResponse = { socket ->
            requestReceived.complete(Unit)
            socketClosed.complete(socket.getInputStream().read() == -1)
        }) { ByteArray(0) }.use { server ->
            controlledClient().use { client ->
                val pending = async { client.fetch("http://$fixtureHost:${server.port}/waiting.png", 10_000) }
                withTimeout(5_000) {
                    requestReceived.await()
                    pending.cancelAndJoin()
                    assertTrue(socketClosed.await())
                }
                assertTrue(pending.isCancelled)
                assertEquals(1, server.accepts.get())
            }
        }
    }

    @Test
    fun `cancelling a blocked body read closes the real response without leaking its IO child`() = runBlocking {
        val chunkReceived = CompletableDeferred<Unit>()
        val socketClosed = CompletableDeferred<Boolean>()
        HttpFixture(afterResponse = { socket ->
            socketClosed.complete(socket.getInputStream().read() == -1)
        }) {
            "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 4096\r\n\r\nx"
                .toByteArray(Charsets.US_ASCII)
        }.use { server ->
            controlledClient().use { client ->
                val attempt = client.fetch("http://$fixtureHost:${server.port}/waiting.png", 10_000)
                assertIs<HttpFetchAttempt.Started>(attempt)
                attempt.use {
                    val reading = async(Dispatchers.Default) {
                        it.chunks { chunk ->
                            assertEquals(listOf('x'.code.toByte()), chunk.toList())
                            chunkReceived.complete(Unit)
                            true
                        }
                    }
                    withTimeout(5_000) {
                        chunkReceived.await()
                        reading.cancelAndJoin()
                        assertTrue(socketClosed.await())
                    }
                    assertTrue(reading.isCancelled)
                }
            }
        }
    }

    @Test
    fun `call deadline still applies after headers while the body is blocked`() = runBlocking {
        val socketClosed = CompletableDeferred<Boolean>()
        HttpFixture(afterResponse = { socket ->
            socketClosed.complete(socket.getInputStream().read() == -1)
        }) {
            "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 4096\r\n\r\nx"
                .toByteArray(Charsets.US_ASCII)
        }.use { server ->
            controlledClient().use { client ->
                val attempt = client.fetch("http://$fixtureHost:${server.port}/waiting.png", 1_000)
                assertIs<HttpFetchAttempt.Started>(attempt)
                attempt.use {
                    withTimeout(5_000) {
                        assertFailsWith<IOException> { it.readBounded(4096) }
                        assertTrue(socketClosed.await())
                    }
                }
            }
        }
    }

    @Test
    fun `HTTPS sends a real TLS handshake and rejects plaintext rather than downgrading`() = runBlocking {
        ServerSocket(0, 1, loopback).use { server ->
            server.soTimeout = 3_000
            val handshake = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val record = socket.getInputStream().readNBytes(5)
                    socket.getOutputStream().write(response(ByteArray(0)))
                    socket.getOutputStream().flush()
                    record
                }
            }
            controlledClient().use { client ->
                assertIs<HttpFetchAttempt.Failed>(
                    client.fetch("https://$fixtureHost:${server.localPort}/photo.png", 2_000),
                )
                val record = withTimeout(5_000) { handshake.await() }
                assertEquals(5, record.size)
                assertEquals(0x16, record[0].toInt() and 255, "The first record must be a TLS handshake, not HTTP")
            }
        }
    }

    @Test
    fun `closed HTTP producer cannot make another request`() = runBlocking {
        val client = OkHttpContentFetching()
        client.close()
        client.close()
        assertIs<HttpFetchAttempt.Failed>(client.fetch("https://example.com", 2_000))
        Unit
    }
}

@Implements(NetworkSecurityPolicy::class)
class FixtureNetworkSecurityPolicyShadow {
    @Implementation
    fun isCleartextTrafficPermitted(): Boolean = true
    @Implementation
    fun isCleartextTrafficPermitted(hostname: String): Boolean = true
}
