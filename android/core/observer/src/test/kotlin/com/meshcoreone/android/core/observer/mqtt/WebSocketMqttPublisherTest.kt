// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.mqtt

import com.meshcoreone.android.core.observer.broker.BrokerLoginProvider
import com.meshcoreone.android.core.observer.claim.NodeSigner
import com.meshcoreone.android.core.observer.prizm.HttpResponse
import com.meshcoreone.android.core.observer.prizm.HttpTransport
import com.meshcoreone.android.core.observer.upload.MqttMessage
import com.meshcoreone.android.core.observer.upload.PublishResult
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private val KEY = Bytes(ByteArray(32) { (it + 1).toByte() })

/** One scripted broker behind a fake WebSocket: answers CONNECT and PUBLISH according to the knobs. */
private class FakeBroker : MqttTransport {
    inner class Conn(val listener: MqttTransportListener) : MqttConnection {
        val sent = mutableListOf<ByteArray>()
        @Volatile var closed = false
        override fun send(bytes: ByteArray): Boolean {
            if (closed) return false
            sent += bytes
            when ((bytes[0].toInt() and 0xff) ushr 4) {
                1 -> connackCode?.let { listener.onBytes(byteArrayOf(0x20, 2, 0, it.toByte())) }
                3 -> if (ack) listener.onBytes(byteArrayOf(0x40, 2, bytes[publishIdOffset(bytes)], bytes[publishIdOffset(bytes) + 1]))
            }
            return true
        }
        override fun close() { if (!closed) { closed = true; listener.onClosed("closed") } }
        fun drop() { closed = true; listener.onClosed("network lost") }
    }

    val endpoints = mutableListOf<MqttEndpoint>()
    val conns = mutableListOf<Conn>()
    var connackCode: Int? = 0
    var ack = true
    var failToOpen = false

    override fun open(endpoint: MqttEndpoint, listener: MqttTransportListener): MqttConnection {
        endpoints += endpoint
        val c = Conn(listener); conns += c
        if (failToOpen) listener.onClosed("unreachable") else listener.onOpen()
        return c
    }

    private fun publishIdOffset(b: ByteArray): Int {
        var i = 1; while (b[i].toInt() and 0x80 != 0) i++
        i++
        return i + 2 + (((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff))
    }

    /** Topic, id and payload of a PUBLISH the client sent. */
    fun decodePublish(b: ByteArray): Triple<String, Int, String> {
        var i = 1; while (b[i].toInt() and 0x80 != 0) i++
        i++
        val n = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
        val topic = String(b, i + 2, n)
        val id = ((b[i + 2 + n].toInt() and 0xff) shl 8) or (b[i + 3 + n].toInt() and 0xff)
        return Triple(topic, id, String(b, i + 4 + n, b.size - (i + 4 + n)))
    }
}

class WebSocketMqttPublisherTest {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val broker = FakeBroker()
    private var now = Instant.parse("2026-11-27T12:00:00Z")
    private var signs = 0
    private val signer = NodeSigner { signs++; Bytes(ByteArray(64) { 5 }) }
    private val logins = BrokerLoginProvider(KEY, signer, { now }, Duration.ofHours(24), Duration.ofHours(2))
    private var joinBody = """{"enabled":true,"host":"mqtt.nodakmesh.org","port":443,"tls":true,"audience":"mqtt.nodakmesh.org"}"""
    private val directory = BrokerDirectory({ "https://mqtt.nodakmesh.org" }, HttpTransport { HttpResponse(200, joinBody) })
    private val waits = java.util.Collections.synchronizedList(mutableListOf<Long>())
    private val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val connectedCalls = java.util.concurrent.atomic.AtomicInteger()
    private var onConnectedBlock: suspend () -> Unit = {}

    private fun publisher(ackTimeoutMs: Long = 2_000) = WebSocketMqttPublisher(
        broker, directory, "client-1", keepAliveSeconds = 60, ackTimeoutMs = ackTimeoutMs, connectTimeoutMs = 1_000,
        backoff = Backoff(2_000, 60_000), sleep = { waits += it; delay(20) }, log = { logs += it },
    )

    private fun start(p: WebSocketMqttPublisher) = runBlocking { p.open(scope, logins) { connectedCalls.incrementAndGet(); onConnectedBlock() } }

    private fun until(what: String, cond: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!cond()) delay(5) }
    }

    @AfterTest fun tearDown() { scope.coroutineContext[Job]!!.cancel() }

    private val message = MqttMessage("meshcore/client/${KEY.hexString}/packets", """{"records":[]}""", 1, false)

    @Test fun connectsWithTheNodeSignedLoginAndCallsOnConnected() {
        val p = publisher(); start(p)
        until("connected") { connectedCalls.get() == 1 }
        assertEquals("wss://mqtt.nodakmesh.org:443/", broker.endpoints.single().url)
        val connect = broker.conns.single().sent.first()
        val text = String(connect, Charsets.ISO_8859_1)
        assertTrue(text.contains("MQTT") && text.contains("client-1") && text.contains("v1_" + KEY.hexString.uppercase()))
        assertTrue(text.contains("eyJhbGciOiJFZDI1NTE5IiwidHlwIjoiSldUIn0."), "the password is the signed token")
        val level = text.indexOf("MQTT") + 4
        assertEquals(4, connect[level].toInt()); assertEquals(0xC2, connect[level + 1].toInt() and 0xff, "username+password+clean session, no will")
        assertEquals(1, signs)
        assertEquals(1, broker.conns.single().sent.size, "nothing but CONNECT so far (no subscribe)")
    }

    @Test fun publishSuspendsUntilThePubackAndSendsQos1OnTheGivenTopic() {
        val p = publisher(); start(p); until("connected") { connectedCalls.get() == 1 }
        assertEquals(PublishResult.Acked, runBlocking { p.publish(message) })
        val (topic, id, payload) = broker.decodePublish(broker.conns.single().sent.last())
        assertEquals(message.topic, topic); assertEquals(message.payload, payload); assertTrue(id in 1..65_535)
        assertEquals(0x32, broker.conns.single().sent.last()[0].toInt() and 0xff)
    }

    @Test fun publishBeforeConnectionAndAfterCloseIsNotConnected() {
        val p = publisher()
        assertEquals(PublishResult.NotConnected, runBlocking { p.publish(message) })
        start(p); until("connected") { connectedCalls.get() == 1 }
        runBlocking { p.close() }
        assertEquals(PublishResult.NotConnected, runBlocking { p.publish(message) })
        assertEquals(0xE0, broker.conns.single().sent.last()[0].toInt() and 0xff, "DISCONNECT sent")
        assertTrue(broker.conns.single().closed)
    }

    @Test fun aMissingPubackIsAFailureNotAnAck() {
        broker.ack = false
        val p = publisher(ackTimeoutMs = 150); start(p); until("connected") { connectedCalls.get() == 1 }
        assertIs<PublishResult.Failed>(runBlocking { p.publish(message) })
        broker.ack = true
        assertEquals(PublishResult.Acked, runBlocking { p.publish(message) })
    }

    @Test fun onlyQos1IsSupported() {
        val p = publisher(); start(p); until("connected") { connectedCalls.get() == 1 }
        assertIs<PublishResult.Failed>(runBlocking { p.publish(message.copy(qos = 0)) })
        assertIs<PublishResult.Failed>(runBlocking { p.publish(message.copy(topic = "a/#")) })
    }

    @Test fun aDropReconnectsWithBackoffAndReusesTheTokenUntilItNearsExpiry() {
        val p = publisher(); start(p); until("first") { connectedCalls.get() == 1 }
        val first = String(broker.conns[0].sent.first(), Charsets.ISO_8859_1)
        broker.conns[0].drop()
        until("second") { connectedCalls.get() == 2 }
        assertEquals(1, signs, "the cached token is still valid")
        assertTrue(String(broker.conns[1].sent.first(), Charsets.ISO_8859_1).contains(first.substringAfter("eyJ").take(40)), "same token reused")
        now = now.plus(Duration.ofHours(23))
        broker.conns[1].drop()
        until("third") { connectedCalls.get() == 3 }
        assertEquals(2, signs, "a fresh node-signed token on the reconnect after it neared expiry")
        assertFalse(String(broker.conns[2].sent.first(), Charsets.ISO_8859_1) == first)
        assertEquals(2_000L, waits.first(), "the wait after a connection that worked starts at the initial backoff")
    }

    @Test fun pendingPublishesFailAtOnceWhenTheConnectionDrops() {
        broker.ack = false
        val p = publisher(ackTimeoutMs = 4_000); start(p); until("connected") { connectedCalls.get() == 1 }
        val result = runBlocking {
            val job = async { p.publish(message) }
            delay(100); broker.conns[0].drop(); withTimeout(2_000) { job.await() }
        }
        assertIs<PublishResult.Failed>(result)
    }

    @Test fun failedSocketsBackOffExponentiallyUpToTheCap() {
        broker.failToOpen = true
        val p = publisher(); start(p)
        until("five waits") { waits.size >= 5 }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L), waits.take(5))
        assertEquals(0, connectedCalls.get())
        runBlocking { p.close() }
    }

    @Test fun aRefusedLoginWaitsAtLeastAMinuteAndNeverCallsOnConnected() {
        broker.connackCode = 5
        val p = publisher(); start(p)
        until("wait") { waits.isNotEmpty() }
        assertTrue(waits.first() >= 60_000, "waited ${waits.first()}")
        assertEquals(0, connectedCalls.get())
        assertTrue(logs.any { it.contains("claimed") })
        assertEquals(PublishResult.NotConnected, runBlocking { p.publish(message) })
        broker.connackCode = 0
        until("later connect") { connectedCalls.get() == 1 }
    }

    @Test fun noConnackMeansFailureAndARetry() {
        broker.connackCode = null
        val p = publisher(); start(p)
        until("retry") { broker.conns.size >= 2 }
        assertEquals(0, connectedCalls.get())
    }

    @Test fun anOnConnectedFailureDoesNotDropTheConnection() {
        onConnectedBlock = { error("boom") }
        val p = publisher(); start(p); until("connected") { connectedCalls.get() == 1 }
        assertEquals(PublishResult.Acked, runBlocking { p.publish(message) })
    }

    @Test fun logoutsAreServerSideSoAServerThatSwitchedLoginsOffIsNotContacted() {
        joinBody = """{"enabled":false}"""
        val p = publisher(); start(p)
        until("wait") { waits.isNotEmpty() }
        assertTrue(broker.conns.isEmpty(), "no socket while observer logins are off")
        runBlocking { p.close() }
    }

    @Test fun aProtocolErrorClosesTheConnectionAndReconnects() {
        val p = publisher(); start(p); until("connected") { connectedCalls.get() == 1 }
        broker.conns[0].listener.onBytes(byteArrayOf(0x40, 3, 0, 0, 0))
        until("reconnect") { connectedCalls.get() == 2 }
        assertTrue(broker.conns[0].closed)
    }

    @Test fun theLoginSigningFailingBacksOffAndTriesAgain() {
        var fail = true
        val flaky = BrokerLoginProvider(KEY, NodeSigner { if (fail) error("node busy") else Bytes(ByteArray(64) { 5 }) }, { now })
        val p = publisher()
        runBlocking { p.open(scope, flaky) { connectedCalls.incrementAndGet() } }
        until("wait") { waits.isNotEmpty() }
        assertTrue(broker.conns.isEmpty()); assertTrue(logs.any { it.contains("could not sign") })
        fail = false
        until("connected") { connectedCalls.get() == 1 }
    }
}
