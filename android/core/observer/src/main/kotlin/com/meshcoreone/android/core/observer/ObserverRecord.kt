// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer

import com.meshcoreone.android.core.protocol.bytes.Bytes

/** What may leave the device for one heard packet. */
sealed interface ObserverRecord {
    /** Public-by-design traffic: the complete over-the-air packet. [channel] is set for channel traffic. */
    class Full(
        val raw: Bytes,
        val hash: Bytes,
        val snr: Double?,
        val rssi: Long?,
        val channel: String?,
    ) : ObserverRecord {
        override fun toString(): String = "Full(rawLength=${raw.size}, channel=$channel)"
    }

    /**
     * Routing metadata only. Deliberately has no payload, transport-code or raw-packet field.
     * [packetLength] is the total over-the-air length; [pathLengthByte] is the encoded path-length byte.
     */
    class MetaOnly(
        val header: Int,
        val pathLengthByte: Int,
        val path: Bytes,
        val packetLength: Int,
        val hash: Bytes,
        val snr: Double?,
        val rssi: Long?,
    ) : ObserverRecord {
        override fun toString(): String = "MetaOnly(header=$header, packetLength=$packetLength)"
    }

    /** Only produced when [PrivateTrafficPolicy.DROP] is selected. */
    data object Dropped : ObserverRecord
}
