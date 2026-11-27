// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import com.meshcoreone.android.core.observer.ObserverRecord
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Duration
import java.time.Instant

class MutableUploadClock(var current: Instant = Instant.parse("2026-11-27T12:00:00Z")) : UploadClock {
    override fun now(): Instant = current
    fun advance(duration: Duration) { current = current.plus(duration) }
}

/** Records every publish; [connected] = false answers NotConnected, [failNext] answers Failed that many times. */
class FakePublisher : MqttPublisher {
    var connected = true
    var failNext = 0
    var throwNext = false
    val acked = mutableListOf<MqttMessage>()
    val attempts = mutableListOf<MqttMessage>()

    override suspend fun publish(message: MqttMessage): PublishResult {
        attempts += message
        if (throwNext) { throwNext = false; throw IllegalStateException("socket closed") }
        if (!connected) return PublishResult.NotConnected
        if (failNext > 0) { failNext--; return PublishResult.Failed("timeout") }
        acked += message
        return PublishResult.Acked
    }

    var opens = 0
    var closes = 0
    var onConnected: (suspend () -> Unit)? = null
    var logins: com.meshcoreone.android.core.observer.broker.BrokerLoginProvider? = null
    override suspend fun open(scope: kotlinx.coroutines.CoroutineScope, logins: com.meshcoreone.android.core.observer.broker.BrokerLoginProvider, onConnected: suspend () -> Unit) {
        opens++; this.logins = logins; this.onConnected = onConnected
    }
    override suspend fun close() { closes++ }

    val ackedPackets: List<MqttMessage> get() = acked.filter { it.topic.endsWith("/packets") }
    /** Every record's "raw" in order of acked delivery (the tests give each record a distinct raw). */
    fun deliveries(): List<String> = ackedPackets.flatMap { m ->
        Regex("\"raw\":\"([0-9a-f]+)\"").findAll(m.payload).map { it.groupValues[1] }.toList()
    }
}

val testNodeKey = Bytes(ByteArray(32) { (it + 1).toByte() })
fun testTopics() = ObserverTopics(testNodeKey)
const val TEST_ORIGIN = "Test Node"
val testLocation = RecordLocation(46.8772, -96.7898, 5.0, provider = "gps")

fun fullRecord(n: Int, at: Instant, channel: String? = null, location: RecordLocation = testLocation) = TimedRecord(
    ObserverRecord.Full(Bytes.of(0x11, n shr 8 and 0xff, n and 0xff), Bytes.of(n shr 24 and 0xff, n shr 16 and 0xff, n shr 8 and 0xff, n and 0xff, 0, 0, 0, 0), 5.25, -80, channel),
    at,
    location,
)
/** The "raw" hex fullRecord(n) puts on the wire. */
fun rawOf(n: Int): String = Bytes.of(0x11, n shr 8 and 0xff, n and 0xff).hexString
