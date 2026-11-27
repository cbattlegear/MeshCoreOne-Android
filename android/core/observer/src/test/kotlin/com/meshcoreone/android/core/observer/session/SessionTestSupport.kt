// AndroidOnly: WP-101 Prizm Mobile Observer service logic tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.session

import com.meshcoreone.android.core.observer.ObserverTestSupport
import com.meshcoreone.android.core.observer.claim.NodeDeviceInfo
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
    override fun statsReader(): DeviceStatsReader? = null
    override fun isConnected() = connected
}

internal class FakeLocation : LocationUpdates {
    val requests = mutableListOf<Long>()
    var stopped = false
    override fun request(intervalMs: Long) { requests += intervalMs }
    override fun stop() { stopped = true }
}


internal fun rxFrame(type: Int, payload: Bytes): MeshEvent {
    val raw = ObserverTestSupport.packet(route = 1, type = type, payload = payload)
    return PacketParser.parse(ObserverTestSupport.logFrame(20, -80, raw))
}

internal fun advertEvent(): MeshEvent = rxFrame(4, Bytes(ByteArray(40) { 1 }))
