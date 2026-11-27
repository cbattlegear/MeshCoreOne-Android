// AndroidOnly: WP-101 Prizm Mobile Observer OkHttp adapters for the HTTP and WebSocket seams (OkHttp is already an app dependency).
package com.meshcoreone.android.app.prizm

import com.meshcoreone.android.core.observer.mqtt.MqttConnection
import com.meshcoreone.android.core.observer.mqtt.MqttEndpoint
import com.meshcoreone.android.core.observer.mqtt.MqttTransport
import com.meshcoreone.android.core.observer.mqtt.MqttTransportListener
import com.meshcoreone.android.core.observer.prizm.HttpRequest
import com.meshcoreone.android.core.observer.prizm.HttpResponse
import com.meshcoreone.android.core.observer.prizm.HttpTransport
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** The one OkHttp client the Prizm observer uses; no cache, no cookies, no redirects (so a bearer token cannot follow one). */
internal object PrizmHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/** REST calls to Prizm. Bodies are capped at 1 MiB; a non-HTTP failure (offline, DNS, TLS, timeout) is an [IOException]. */
class OkHttpPrizmTransport(private val client: OkHttpClient = PrizmHttp.client) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse = suspendCancellableCoroutine { continuation ->
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val body = request.body?.toRequestBody(JSON)
        when {
            body != null -> builder.method(request.method, body)
            request.method == "GET" || request.method == "HEAD" -> builder.method(request.method, null)
            else -> builder.method(request.method, ByteArray(0).toRequestBody(null))
        }
        val call = client.newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val text = it.body.source().let { source -> source.request(MAX_BODY + 1); source.buffer.readUtf8(minOf(source.buffer.size, MAX_BODY)) }
                        continuation.resume(HttpResponse(it.code, text))
                    } catch (e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val MAX_BODY = 1L shl 20
    }
}

/** MQTT over WebSocket: binary frames carry MQTT packets, subprotocol `mqtt`, no OkHttp ping (MQTT has its own keep-alive). */
class OkHttpMqttTransport(private val client: OkHttpClient = PrizmHttp.client) : MqttTransport {
    override fun open(endpoint: MqttEndpoint, listener: MqttTransportListener): MqttConnection {
        val request = Request.Builder().url(endpoint.url).header("Sec-WebSocket-Protocol", "mqtt").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onBytes(bytes.toByteArray())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); listener.onClosed("closing $code") }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed("closed $code")
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                listener.onClosed("failure ${t.javaClass.simpleName}${response?.let { " HTTP ${it.code}" }.orEmpty()}")
        })
        return object : MqttConnection {
            override fun send(bytes: ByteArray): Boolean = socket.send(ByteString.of(*bytes))
            override fun close() { socket.close(1000, null) }
        }
    }
}
