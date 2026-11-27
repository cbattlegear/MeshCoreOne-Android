// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer

import com.meshcoreone.android.core.protocol.event.PayloadType.ACK
import com.meshcoreone.android.core.protocol.event.PayloadType.ADVERT
import com.meshcoreone.android.core.protocol.event.PayloadType.CONTROL
import com.meshcoreone.android.core.protocol.event.PayloadType.GROUP_DATA
import com.meshcoreone.android.core.protocol.event.PayloadType.GROUP_TEXT
import com.meshcoreone.android.core.protocol.event.PayloadType.PATH
import com.meshcoreone.android.core.protocol.event.PayloadType.TRACE

class PrivacyFilter(
    private val allowList: ChannelAllowList,
    private val privateTrafficPolicy: PrivateTrafficPolicy = ObserverConstants.DEFAULT_PRIVATE_TRAFFIC_POLICY,
) {
    fun filter(packet: ObservedPacket): ObserverRecord = when (packet.payloadType) {
        ADVERT, TRACE, PATH, ACK, CONTROL -> full(packet, null)
        GROUP_TEXT, GROUP_DATA -> allowList.match(packet.payload)?.let { full(packet, it.name) } ?: private(packet)
        else -> private(packet) // TXT_MSG, REQ, RESPONSE, ANON_REQ, MULTIPART, RAW_CUSTOM, unknown
    }

    private fun full(packet: ObservedPacket, channel: String?): ObserverRecord =
        ObserverRecord.Full(packet.raw, packet.hash, packet.snr, packet.rssi, channel)

    private fun private(packet: ObservedPacket): ObserverRecord = when (privateTrafficPolicy) {
        PrivateTrafficPolicy.DROP -> ObserverRecord.Dropped
        PrivateTrafficPolicy.META_ONLY -> ObserverRecord.MetaOnly(
            header = packet.header,
            transportCodes = packet.transportCodes,
            pathLengthByte = packet.pathLengthByte,
            path = packet.path,
            packetLength = packet.raw.size,
            hash = packet.hash,
            snr = packet.snr,
            rssi = packet.rssi,
        )
    }
}
