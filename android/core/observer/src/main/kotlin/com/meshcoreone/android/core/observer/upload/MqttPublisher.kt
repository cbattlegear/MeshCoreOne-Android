// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import com.meshcoreone.android.core.observer.broker.BrokerLoginProvider
import kotlinx.coroutines.CoroutineScope

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
 * The seam to the broker connection. [publish] suspends until the broker acknowledges (QoS 1) and must not report
 * [PublishResult.Acked] earlier. The connecting implementation is `WebSocketMqttPublisher`; [open] hands it what it needs.
 */
interface MqttPublisher {
    suspend fun publish(message: MqttMessage): PublishResult

    /**
     * Called once by [com.meshcoreone.android.core.observer.session.ObserverSession] when it starts. A connecting publisher starts
     * its connect and reconnect loop in [scope], takes a fresh node-signed token from [logins] for every (re)connect (username
     * `v1_<PK>`, the password is the token) and calls [onConnected] after each successful connect so the pipeline resends its backlog.
     * The default does nothing (stand-in, fakes).
     */
    suspend fun open(scope: CoroutineScope, logins: BrokerLoginProvider, onConnected: suspend () -> Unit) {}

    /** Ends the connection and the reconnect loop; publishing returns [PublishResult.NotConnected] afterwards. */
    suspend fun close() {}
}
