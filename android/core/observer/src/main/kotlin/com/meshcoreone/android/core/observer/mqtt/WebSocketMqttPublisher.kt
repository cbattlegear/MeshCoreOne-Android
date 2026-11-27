// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.mqtt

import com.meshcoreone.android.core.observer.broker.BrokerLoginProvider
import com.meshcoreone.android.core.observer.upload.MqttMessage
import com.meshcoreone.android.core.observer.upload.MqttPublisher
import com.meshcoreone.android.core.observer.upload.PublishResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** Reconnect delays: [initialMs], doubling to [maxMs]; [reset] after a connection that was accepted. */
class Backoff(private val initialMs: Long = 2_000, private val maxMs: Long = 60_000) {
    private var next = initialMs
    fun next(): Long = next.also { next = (next * 2).coerceAtMost(maxMs) }
    fun reset() { next = initialMs }
}

/**
 * MQTT 3.1.1 publish-only client over a WebSocket ([MqttTransport]). Real behaviour against a broker is NOT verified here; the logic is
 * tested against a scripted fake transport.
 *
 *  - [open] starts one loop in the session's scope: fetch broker config, take a fresh node-signed login ([BrokerLoginProvider]),
 *    open the socket, send CONNECT (username `v1_<PK>`, password = token, clean session, no will), wait for CONNACK, call
 *    `onConnected`, then ping at half the keep-alive until the connection drops; then wait ([Backoff]) and start over. A refused
 *    login (CONNACK code 4 or 5) or a login that could not be signed backs off at the maximum, because retrying fast cannot help.
 *  - [publish] sends a QoS 1 PUBLISH and suspends until its PUBACK; no PUBACK within [ackTimeoutMs], or a drop, is [PublishResult.Failed];
 *    with no connection it is [PublishResult.NotConnected]. The pipeline queues on either and resends oldest first on reconnect.
 *  - Nothing is ever subscribed.
 */
class WebSocketMqttPublisher(
    private val transport: MqttTransport,
    private val directory: BrokerDirectory,
    private val clientId: String,
    private val keepAliveSeconds: Int = 60,
    private val ackTimeoutMs: Long = 15_000,
    private val connectTimeoutMs: Long = 20_000,
    private val backoff: Backoff = Backoff(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
) : MqttPublisher {

    private class Connection {
        lateinit var socket: MqttConnection
        val opened = CompletableDeferred<Unit>()
        val connack = CompletableDeferred<MqttIncoming.ConnAck>()
        val closed = CompletableDeferred<String>()
        val pending = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
        val ids = AtomicInteger(0)
        @Volatile var lastReceiveMs = System.currentTimeMillis()

        fun nextId(): Int {
            repeat(MAX_IDS) {
                val id = ids.updateAndGet { if (it >= MAX_IDS) 1 else it + 1 }
                if (!pending.containsKey(id)) return id
            }
            return -1
        }
    }

    @Volatile private var current: Connection? = null
    private var loop: Job? = null

    override suspend fun open(scope: CoroutineScope, logins: BrokerLoginProvider, onConnected: suspend () -> Unit) {
        loop?.cancel()
        loop = scope.launch { runLoop(logins, onConnected) }
    }

    override suspend fun close() {
        loop?.cancel(); loop = null
        current?.let { c -> try { c.socket.send(MqttPackets.disconnect()) } catch (_: Exception) {}; c.socket.close() }
        current = null
    }

    override suspend fun publish(message: MqttMessage): PublishResult {
        val connection = current ?: return PublishResult.NotConnected
        if (message.qos != 1) return PublishResult.Failed("only QoS 1 is supported")
        val id = connection.nextId()
        if (id < 0) return PublishResult.Failed("too many publishes in flight")
        val ack = CompletableDeferred<Unit>()
        connection.pending[id] = ack
        val sent = try {
            connection.socket.send(MqttPackets.publish(message.topic, message.payload.toByteArray(Charsets.UTF_8), id))
        } catch (error: IllegalArgumentException) {
            connection.pending.remove(id)
            return PublishResult.Failed(error.message ?: "invalid message")
        }
        if (!sent) { connection.pending.remove(id); return PublishResult.NotConnected }
        val acked = try {
            withTimeoutOrNull(ackTimeoutMs) { runCatching { ack.await() }.isSuccess } ?: false
        } finally {
            connection.pending.remove(id)
        }
        return if (acked) PublishResult.Acked else PublishResult.Failed("no PUBACK")
    }

    // --- connection loop ---

    private suspend fun runLoop(logins: BrokerLoginProvider, onConnected: suspend () -> Unit) {
        while (true) {
            val outcome = try {
                connectOnce(logins, onConnected)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log("mqtt attempt failed: ${error.message ?: error.javaClass.simpleName}")
                Outcome.FAILED
            }
            val wait = when (outcome) {
                Outcome.WAS_CONNECTED -> { backoff.reset(); backoff.next() }
                Outcome.FAILED -> backoff.next()
                Outcome.REFUSED -> maxOf(backoff.next(), REFUSED_WAIT_MS)
            }
            log("mqtt reconnecting in ${wait} ms")
            sleep(wait)
        }
    }

    private enum class Outcome { WAS_CONNECTED, FAILED, REFUSED }

    private suspend fun connectOnce(logins: BrokerLoginProvider, onConnected: suspend () -> Unit): Outcome {
        val config = directory.refresh()
        if (!config.enabled) { log("broker logins are switched off on the server"); return Outcome.REFUSED }
        val login = try {
            logins.current()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log("could not sign the broker login: ${error.message ?: error.javaClass.simpleName}")
            return Outcome.REFUSED
        }
        val decoder = MqttPackets.Decoder()
        val connection = Connection()
        val listener = object : MqttTransportListener {
            override fun onOpen() { connection.opened.complete(Unit) }
            override fun onBytes(bytes: ByteArray) {
                connection.lastReceiveMs = System.currentTimeMillis()
                try {
                    for (packet in decoder.feed(bytes)) handleIncoming(connection, packet)
                } catch (error: MqttProtocolException) {
                    connection.closed.complete("protocol error: ${error.message}")
                    connection.socket.close()
                }
            }
            override fun onClosed(reason: String) { connection.closed.complete(reason) }
        }
        val socket = transport.open(config.endpoint(), listener)
        connection.socket = socket
        try {
            withTimeoutOrNull(connectTimeoutMs) { select<Unit> { connection.opened.onAwait {}; connection.closed.onAwait {} } }
            if (!connection.opened.isCompleted || connection.closed.isCompleted) {
                log(if (connection.closed.isCompleted) "mqtt socket failed: ${connection.closed.getCompleted()}" else "mqtt socket open timed out")
                return Outcome.FAILED
            }
            socket.send(MqttPackets.connect(clientId, login.username, login.password.toByteArray(Charsets.UTF_8), keepAliveSeconds))
            withTimeoutOrNull(connectTimeoutMs) { select<Unit> { connection.connack.onAwait {}; connection.closed.onAwait {} } }
            val ack = if (connection.connack.isCompleted) connection.connack.getCompleted() else null
            if (ack == null) { log("no CONNACK"); return Outcome.FAILED }
            if (!ack.accepted) {
                log("broker refused the login (code ${ack.returnCode}); is the node claimed on Prizm?")
                return if (ack.returnCode == 4 || ack.returnCode == 5) Outcome.REFUSED else Outcome.FAILED
            }
            current = connection
            log("mqtt connected")
            try {
                onConnected()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log("onConnected failed: ${error.message ?: error.javaClass.simpleName}")
            }
            keepAlive(connection)
            return Outcome.WAS_CONNECTED
        } finally {
            if (current === connection) current = null
            connection.pending.values.forEach { it.completeExceptionally(IllegalStateException("connection closed")) }
            socket.close()
        }
    }

    /** Returns when the connection is closed or the broker has been silent for 1.5 keep-alive periods. */
    private suspend fun keepAlive(connection: Connection) {
        val pingMs = (keepAliveSeconds * 1000L / 2).coerceAtLeast(1_000)
        while (!connection.closed.isCompleted) {
            val reason = withTimeoutOrNull(pingMs) { connection.closed.await() }
            if (reason != null) { log("mqtt closed: $reason"); return }
            if (System.currentTimeMillis() - connection.lastReceiveMs > keepAliveSeconds * 1500L) { log("mqtt keep-alive timed out"); return }
            if (!connection.socket.send(MqttPackets.pingReq())) return
        }
    }

    private fun handleIncoming(connection: Connection, packet: MqttIncoming) {
        when (packet) {
            is MqttIncoming.ConnAck -> connection.connack.complete(packet)
            is MqttIncoming.PubAck -> connection.pending[packet.packetId]?.complete(Unit)
            MqttIncoming.PingResp, is MqttIncoming.Other -> Unit
        }
    }

    private companion object {
        const val MAX_IDS = 65_535
        const val REFUSED_WAIT_MS = 60_000L
    }
}
