// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.sha256

object PacketHasher {
    const val TRACE_PAYLOAD_TYPE = 0x09

    /**
     * Firmware `Packet::calculatePacketHash`: SHA-256 over the payload-type byte, then (TRACE only) the
     * path-length byte, then the payload; truncated to [ObserverConstants.PACKET_HASH_LENGTH] bytes.
     */
    fun compute(payloadTypeBits: Int, pathLengthByte: Int, payload: Bytes): Bytes {
        val prefix = if (payloadTypeBits == TRACE_PAYLOAD_TYPE) {
            Bytes.of(payloadTypeBits, pathLengthByte)
        } else {
            Bytes.of(payloadTypeBits)
        }
        return sha256(prefix + payload).prefix(ObserverConstants.PACKET_HASH_LENGTH)
    }
}
