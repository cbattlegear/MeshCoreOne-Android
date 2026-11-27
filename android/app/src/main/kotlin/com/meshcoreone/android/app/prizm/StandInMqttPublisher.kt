// AndroidOnly: WP-101 Prizm Mobile Observer STAND-IN publisher: acknowledges and logs sizes only; nothing leaves the phone.
package com.meshcoreone.android.app.prizm

import com.meshcoreone.android.core.observer.upload.MqttMessage
import com.meshcoreone.android.core.observer.upload.MqttPublisher
import com.meshcoreone.android.core.observer.upload.PublishResult

/**
 * NOT an MQTT client. No MQTT library is in the dependency lock, so this publisher acknowledges every message locally.
 * It logs the topic and payload size (never the payload, which may carry a location) so a run can be followed in logcat.
 * Replace it with a real client that connects with `PrizmClient.mqttCredentials` and the last will from
 * `MqttConnectionConfig.forNode`.
 */
class StandInMqttPublisher(private val log: (String) -> Unit) : MqttPublisher {
    override suspend fun publish(message: MqttMessage): PublishResult {
        log("STAND-IN publish ${message.topic} qos=${message.qos} retained=${message.retained} bytes=${message.payload.length}")
        return PublishResult.Acked
    }
}
