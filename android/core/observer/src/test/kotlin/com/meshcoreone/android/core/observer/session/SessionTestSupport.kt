// AndroidOnly: WP-101 Prizm Mobile Observer service logic tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.session

import com.meshcoreone.android.core.observer.ObserverTestSupport
import com.meshcoreone.android.core.observer.claim.NodeDeviceInfo
import com.meshcoreone.android.core.observer.claim.NodeSigner
import com.meshcoreone.android.core.observer.health.DeviceStatsReader
import com.meshcoreone.android.core.observer.upload.testNodeKey
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.parser.PacketParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

internal class FakeLink : ObserverNodeLink {
    override val nodeKey = testNodeKey
    override val deviceInfo = NodeDeviceInfo(testNodeKey, "N", 20, 22, 910.5, 62.5, 7, 5, 9, "1.2", "b", "Heltec", false, 0)
    var channels = listOf<ChannelInfo>()
    var connected = true
    var failChannels = false
    var fetches = 0
    val feed = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 16)
    override fun events(): Flow<MeshEvent> = feed
    override suspend fun fetchChannels(): List<ChannelInfo> { fetches++; if (failChannels) error("ble"); return channels }
    var stats: DeviceStatsReader? = null
    var signed = 0
    override val signer = NodeSigner { signed++; Bytes(ByteArray(64) { 9 }) }
    override fun statsReader(): DeviceStatsReader? = stats
    override fun isConnected() = connected
}

internal class FakeLocation : LocationUpdates {
    val requests = mutableListOf<Long>()
    var stopped = false
    override fun request(intervalMs: Long) { requests += intervalMs }
    override fun stop() { stopped = true }
}


/** A node that answers core, radio and packet stats (uptime 100 s). */
internal class FakeStats : DeviceStatsReader {
    override suspend fun getStatsCore() = com.meshcoreone.android.core.protocol.event.CoreStats(3_987u, 100u, 0u, 0u)
    override suspend fun getStatsRadio() = com.meshcoreone.android.core.protocol.event.RadioStats((-110).toShort(), -90, 4.0, 10u, 20u)
    override suspend fun getStatsPackets() = com.meshcoreone.android.core.protocol.event.PacketStats(50u, 30u, 1u, 2u, 3u, 4u, 5u)
    override suspend fun getBattery() = com.meshcoreone.android.core.protocol.model.BatteryInfo(3_987)
}

internal fun rxFrame(type: Int, payload: Bytes): MeshEvent {
    val raw = ObserverTestSupport.packet(route = 1, type = type, payload = payload)
    return PacketParser.parse(ObserverTestSupport.logFrame(20, -80, raw))
}

internal fun advertEvent(): MeshEvent = rxFrame(4, Bytes(ByteArray(40) { 1 }))
