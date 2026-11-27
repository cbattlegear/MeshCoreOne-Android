// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer

import com.meshcoreone.android.core.protocol.bytes.Bytes

object ObserverConstants {
    /**
     * Bytes of the SHA-256 packet hash that are kept. Firmware `Packet::calculatePacketHash` yields a full
     * 32-byte digest; 8 bytes is the truncation used by the existing parser.
     * OPEN QUESTION: this must match the hash length of Prizm's fixed observers.
     */
    const val PACKET_HASH_LENGTH = 8

    /** Firmware `MAX_PATH_SIZE`: path bytes (hops * bytes-per-hop) may not exceed this. */
    const val MAX_PATH_BYTES = 64

    /** Firmware `MAX_PACKET_PAYLOAD`. */
    const val MAX_PAYLOAD_BYTES = 184

    /** Channel hash is the first byte of SHA-256(secret). */
    const val CHANNEL_HASH_LENGTH = 1

    const val PUBLIC_CHANNEL_NAME = "Public"
    const val PUBLIC_CHANNEL_KEY_HEX = "8b3387e9c5cdea6ac9e5edbaa115cd72"
    val PUBLIC_CHANNEL_KEY: Bytes = Bytes.fromHex(PUBLIC_CHANNEL_KEY_HEX)

    /**
     * OPEN QUESTION: what to do with traffic that may be private (direct messages, requests, non-allow-listed
     * channels). Single switch; defaults to uploading metadata only.
     */
    val DEFAULT_PRIVATE_TRAFFIC_POLICY: PrivateTrafficPolicy = PrivateTrafficPolicy.META_ONLY
}

enum class PrivateTrafficPolicy { META_ONLY, DROP }
