// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.mqtt

import com.meshcoreone.android.core.observer.broker.BrokerEndpoint
import com.meshcoreone.android.core.observer.json.MiniJson
import com.meshcoreone.android.core.observer.prizm.HttpRequest
import com.meshcoreone.android.core.observer.prizm.HttpTransport
import com.meshcoreone.android.core.observer.prizm.PrizmEndpoints
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException

/** Broker coordinates. [enabled] false means the server has observer logins switched off. */
data class BrokerConfig(val host: String, val port: Int, val useTls: Boolean, val audience: String, val enabled: Boolean = true) {
    fun endpoint() = MqttEndpoint(host, port, BrokerEndpoint.WEBSOCKET_PATH, useTls)

    companion object {
        /** Used when `observer-join` cannot be read: the base URL's host on 443 with TLS, audience = host. */
        fun defaultsFor(baseUrl: String): BrokerConfig {
            val host = try { URI(baseUrl).host } catch (_: Exception) { null } ?: BrokerEndpoint.HOST
            return BrokerConfig(host, BrokerEndpoint.PORT, true, host)
        }
    }
}

/**
 * Host, port and token audience, from `GET /api/config/observer-join` (public, no secrets) with the base URL's host as the fallback,
 * so the beta override works even if the endpoint is missing. The last good answer is kept.
 */
class BrokerDirectory(private val baseUrl: () -> String, private val http: HttpTransport) {
    @Volatile private var last: BrokerConfig? = null
    @Volatile private var lastBase: String? = null

    /** Best known config without any I/O. */
    fun current(): BrokerConfig {
        val base = baseUrl()
        return last?.takeIf { lastBase == base } ?: BrokerConfig.defaultsFor(base)
    }

    fun audience(): String = current().audience

    suspend fun refresh(): BrokerConfig {
        val base = baseUrl()
        val fetched = try {
            val response = http.execute(HttpRequest("GET", base.trimEnd('/') + PrizmEndpoints.OBSERVER_JOIN, mapOf("Accept" to "application/json")))
            if (response.status == 200) parse(response.body, base) else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            null
        }
        if (fetched != null) { last = fetched; lastBase = base }
        return current()
    }

    private fun parse(body: String, base: String): BrokerConfig? {
        val json = runCatching { MiniJson.parse(body) }.getOrNull() as? Map<*, *> ?: return null
        val defaults = BrokerConfig.defaultsFor(base)
        val host = (json["host"] as? String)?.takeIf { HOST.matches(it) } ?: defaults.host
        val port = (json["port"] as? Double)?.toInt()?.takeIf { it in 1..65_535 } ?: defaults.port
        val audience = (json["audience"] as? String)?.takeIf { it.isNotBlank() && it.length <= 253 } ?: host
        // Never downgrade: a server that says tls:false is treated as unusable rather than sending a login in the clear.
        if (json["tls"] == false) return null
        return BrokerConfig(host, port, true, audience, enabled = json["enabled"] as? Boolean ?: false)
    }

    private companion object { val HOST = Regex("^[A-Za-z0-9.-]{1,253}$") }
}
