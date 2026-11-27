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
     * Routing metadata only. Deliberately has no payload and no whole-packet field. What leaves the phone is [metaRaw]:
     * the header, the 4 transport codes when the route type has them, the path-length byte and the path (the Prizm server
     * reads exactly that and ignores anything after the path). [packetLength] is the total over-the-air length and stays local.
     */
    class MetaOnly(
        val header: Int,
        val transportCodes: Bytes?,
        val pathLengthByte: Int,
        val path: Bytes,
        val packetLength: Int,
        val hash: Bytes,
        val snr: Double?,
        val rssi: Long?,
    ) : ObserverRecord {
        /** Header, transport codes (if any), path-length byte, path: never any payload byte. */
        val metaRaw: Bytes
            get() {
                val out = java.io.ByteArrayOutputStream()
                out.write(header and 0xff)
                transportCodes?.let { out.write(it.toByteArray()) }
                out.write(pathLengthByte and 0xff)
                out.write(path.toByteArray())
                return Bytes(out.toByteArray())
            }

        override fun toString(): String = "MetaOnly(header=$header, packetLength=$packetLength)"
    }

    /** Only produced when [PrivateTrafficPolicy.DROP] is selected. */
    data object Dropped : ObserverRecord
}
