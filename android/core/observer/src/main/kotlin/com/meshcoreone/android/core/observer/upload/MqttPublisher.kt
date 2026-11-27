// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import com.meshcoreone.android.core.observer.claim.NodeDeviceInfo
import com.meshcoreone.android.core.observer.json.jsonObject
import com.meshcoreone.android.core.observer.prizm.MqttCredentials

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

/** Everything the library needs to connect, including the last will that marks the observer offline. */
class MqttConnectionConfig(
    val credentials: MqttCredentials,
    val clientId: String,
    val lastWill: MqttMessage,
    val keepAliveSeconds: Int = 60,
) {
    companion object {
        fun forNode(credentials: MqttCredentials, topics: ObserverTopics, clientId: String) =
            MqttConnectionConfig(credentials, clientId, ObserverPresence.offline(topics))
    }
}

/** Retained QoS 1 status and device messages. The will and the "offline" status are the same message. */
object ObserverPresence {
    fun online(topics: ObserverTopics) = MqttMessage(topics.status, jsonObject { string("state", "online") }, 1, true)
    fun offline(topics: ObserverTopics) = MqttMessage(topics.status, jsonObject { string("state", "offline") }, 1, true)
    fun device(topics: ObserverTopics, info: NodeDeviceInfo) = MqttMessage(topics.device, info.toJson(), 1, true)

    /** Call after each successful connect: status online first, then the device info. */
    suspend fun announce(publisher: MqttPublisher, topics: ObserverTopics, info: NodeDeviceInfo): Boolean =
        publisher.publish(online(topics)) == PublishResult.Acked &&
            publisher.publish(device(topics, info)) == PublishResult.Acked
}
