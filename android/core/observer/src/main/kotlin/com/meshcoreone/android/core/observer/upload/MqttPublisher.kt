// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import com.meshcoreone.android.core.observer.broker.BrokerEndpoint
import com.meshcoreone.android.core.observer.broker.BrokerLoginProvider

data class MqttMessage(val topic: String, val payload: String, val qos: Int, val retained: Boolean) {
    init { require(qos in 0..2) }
}

sealed interface PublishResult {
    /** The broker acknowledged (QoS 1 PUBACK). Only this allows a queued record to be deleted. */
    data object Acked : PublishResult
    data object NotConnected : PublishResult
    class Failed(val reason: String) : PublishResult
}

/**
 * The seam for the real MQTT library. [publish] suspends until the broker acknowledges (QoS 1) and must not
 * report [PublishResult.Acked] earlier. The library adapter is responsible for connecting with a
 * [MqttConnectionConfig] and for calling [UploadPipeline.onConnected] after every (re)connect.
 */
interface MqttPublisher {
    suspend fun publish(message: MqttMessage): PublishResult
}

/**
 * Everything the library needs to connect (contract "Broker login"): WebSocket over TLS to [BrokerEndpoint], username
 * `v1_<PK>`, and a password that is a token the node signs. [password] is called on every (re)connect and returns a fresh
 * token when the current one is close to expiry ([BrokerLoginProvider]); a token is never reused past its refresh time.
 * There is no last will: the broker accepts only the packets and rf topics, so there is no status message to retract.
 */
class MqttConnectionConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: suspend () -> String,
    val clientId: String,
    val keepAliveSeconds: Int = 60,
) {
    override fun toString(): String = "MqttConnectionConfig(host=$host:$port, username=$username, clientId=$clientId)"

    companion object {
        suspend fun forNode(logins: BrokerLoginProvider, clientId: String): MqttConnectionConfig {
            val username = logins.current().username
            return MqttConnectionConfig(BrokerEndpoint.HOST, BrokerEndpoint.PORT, username, { logins.current().password }, clientId)
        }
    }
}
