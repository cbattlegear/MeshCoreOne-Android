// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType

/** A parsed over-the-air packet heard by the companion radio, plus its packet hash. Never uploaded as-is. */
class ObservedPacket(
    val raw: Bytes,
    val header: Int,
    val routeType: RouteType,
    val payloadType: PayloadType,
    val payloadTypeBits: Int,
    val version: Int,
    val transportCodes: Bytes?,
    val pathLengthByte: Int,
    val hopCount: Int,
    val bytesPerHop: Int,
    val path: Bytes,
    val payload: Bytes,
    val hash: Bytes,
    val snr: Double?,
    val rssi: Long?,
) {
    override fun toString(): String =
        "ObservedPacket(type=${payloadType.displayName}, route=${routeType.displayName}, rawLength=${raw.size})"
}

sealed interface CaptureResult {
    data class Captured(val packet: ObservedPacket) : CaptureResult
    data class Malformed(val reason: String) : CaptureResult
}
