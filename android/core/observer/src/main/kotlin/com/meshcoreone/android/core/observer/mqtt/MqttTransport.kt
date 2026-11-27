// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.mqtt

/** Where to connect: MQTT over WebSocket (subprotocol `mqtt`), TLS unless [useTls] is false (tests only). */
data class MqttEndpoint(val host: String, val port: Int, val path: String = "/", val useTls: Boolean = true) {
    val url: String get() = "${if (useTls) "wss" else "ws"}://$host:$port$path"
}

/** Events from one open connection. Called on the transport's own thread; implementations must not block. */
interface MqttTransportListener {
    fun onOpen()
    fun onBytes(bytes: ByteArray)
    /** Closed or failed; called at most once. [reason] is for logs only. */
    fun onClosed(reason: String)
}

interface MqttConnection {
    /** False when the connection is already closed or the send queue refused it. */
    fun send(bytes: ByteArray): Boolean
    fun close()
}

/** The seam to a WebSocket stack (OkHttp in the app). */
fun interface MqttTransport {
    fun open(endpoint: MqttEndpoint, listener: MqttTransportListener): MqttConnection
}
