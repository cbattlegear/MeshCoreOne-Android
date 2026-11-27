// AndroidOnly: WP-101 Prizm Mobile Observer tests of the OkHttp adapters against an in-process JDK HTTP server (no external network).
package com.meshcoreone.android.app.prizm

import com.meshcoreone.android.core.observer.mqtt.MqttEndpoint
import com.meshcoreone.android.core.observer.mqtt.MqttTransportListener
import com.meshcoreone.android.core.observer.prizm.HttpRequest
import com.meshcoreone.android.core.observer.prizm.HttpPrizmClient
import com.meshcoreone.android.core.observer.prizm.PrizmUnauthorizedException
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class OkHttpPrizmTransportTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val transport = OkHttpPrizmTransport()
    private val seen = java.util.Collections.synchronizedList(mutableListOf<Triple<String, String, Map<String, String>>>())
    private val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())

    @AfterTest fun stop() { server.stop(0) }

    private fun route(path: String, status: Int, body: String, extra: Map<String, String> = emptyMap()) {
        server.createContext(path) { ex ->
            seen += Triple(ex.requestMethod, ex.requestURI.toString(), ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() })
            bodies += ex.requestBody.readBytes().decodeToString()
            extra.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
    }

    @Test fun postSendsJsonBodyAndHeadersAndReturnsStatusAndBody() = runBlocking {
        route("/api/x", 200, """{"ok":true}""")
        val response = transport.execute(HttpRequest("POST", "$base/api/x", mapOf("Authorization" to "Bearer pza_t", "Accept" to "application/json"), """{"a":1}"""))
        assertEquals(200, response.status); assertEquals("""{"ok":true}""", response.body)
        val (method, _, headers) = seen.single()
        assertEquals("POST", method); assertEquals("Bearer pza_t", headers["authorization"]); assertEquals("""{"a":1}""", bodies.single())
        assertTrue(headers["content-type"]!!.startsWith("application/json"))
    }

    @Test fun getPutAndDeleteUseTheirMethods() = runBlocking {
        route("/g", 200, "g"); route("/p", 204, ""); route("/d", 200, "d")
        assertEquals("g", transport.execute(HttpRequest("GET", "$base/g")).body)
        assertEquals(204, transport.execute(HttpRequest("PUT", "$base/p", body = "{}")).status)
        assertEquals(200, transport.execute(HttpRequest("DELETE", "$base/d")).status)
        assertEquals(listOf("GET", "PUT", "DELETE"), seen.map { it.first })
    }

    @Test fun errorStatusesAreReturnedNotThrown() = runBlocking {
        route("/e", 401, "nope")
        val r = transport.execute(HttpRequest("GET", "$base/e"))
        assertEquals(401, r.status); assertEquals("nope", r.body)
    }

    @Test fun redirectsAreNotFollowedSoABearerTokenCannotLeave() = runBlocking {
        route("/r", 302, "", mapOf("Location" to "http://example.invalid/steal"))
        assertEquals(302, transport.execute(HttpRequest("GET", "$base/r", mapOf("Authorization" to "Bearer pza_t"))).status)
        assertEquals(1, seen.size)
    }

    @Test fun anUnreachableServerIsAnIoException() {
        server.stop(0)
        assertFailsWith<IOException> { runBlocking { transport.execute(HttpRequest("GET", "$base/x")) } }
    }

    @Test fun theHttpClientWorksEndToEndOverRealSocketsAndMaps401() = runBlocking {
        route("/api/prizm/app/token", 200, """{"token":"pza_abcdefgh12345678","expiresAt":1795000000,"user":{"name":"Josh"}}""")
        route("/api/prizm/me", 401, "revoked")
        val client = HttpPrizmClient({ base }, transport)
        val session = client.exchangeCode("c", "v")
        assertEquals("Josh", session.user.displayName)
        assertFailsWith<PrizmUnauthorizedException> { client.claimedNodes(session) }
        assertEquals("""{"code":"c","codeVerifier":"v"}""", bodies.first())
    }

    @Test fun theWebSocketHandshakeAsksForTheMqttSubprotocolOnTheRootPath() {
        val latch = CountDownLatch(1)
        var upgrade: Map<String, String> = emptyMap()
        server.createContext("/") { ex ->
            upgrade = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() } + ("path" to ex.requestURI.toString())
            ex.sendResponseHeaders(400, -1); ex.close()
        }
        var closedReason: String? = null
        OkHttpMqttTransport().open(MqttEndpoint("127.0.0.1", server.address.port, "/", useTls = false), object : MqttTransportListener {
            override fun onOpen() = Unit
            override fun onBytes(bytes: ByteArray) = Unit
            override fun onClosed(reason: String) { closedReason = reason; latch.countDown() }
        })
        assertTrue(latch.await(5, TimeUnit.SECONDS), "a failed handshake is reported as closed")
        assertEquals("mqtt", upgrade["sec-websocket-protocol"]); assertEquals("websocket", upgrade["upgrade"]?.lowercase()); assertEquals("/", upgrade["path"])
        assertTrue(closedReason!!.startsWith("failure"), closedReason)
    }
}
