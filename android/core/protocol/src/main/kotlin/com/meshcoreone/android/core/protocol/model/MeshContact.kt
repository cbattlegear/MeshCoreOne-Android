// PortedFrom: MeshCore/Sources/MeshCore/Models/Contact.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Models/ContactTypes.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant

enum class ContactType(val rawValue: UByte) {
    CHAT(0x01u),
    REPEATER(0x02u),
    ROOM(0x03u);

    companion object {
        fun fromRawValue(rawValue: UByte): ContactType? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

@JvmInline
value class ContactFlags(val rawValue: UByte) {
    operator fun contains(flag: ContactFlags): Boolean =
        (rawValue.toInt() and flag.rawValue.toInt()) == flag.rawValue.toInt()

    infix fun or(other: ContactFlags): ContactFlags =
        ContactFlags((rawValue.toInt() or other.rawValue.toInt()).toUByte())

    companion object {
        val FAVORITE = ContactFlags(0x01u)
        val TELEMETRY_BASE = ContactFlags(0x02u)
        val TELEMETRY_LOCATION = ContactFlags(0x04u)
        val TELEMETRY_ENVIRONMENT = ContactFlags(0x08u)
        val TELEMETRY_ALL = ContactFlags(0x0eu)
        val REMOTE_CLI_ALLOWED = ContactFlags(0x10u)
    }
}

data class MeshContact(
    val id: String,
    val publicKey: Bytes,
    val type: ContactType,
    val flags: ContactFlags,
    val outPathLength: UByte,
    val outPath: Bytes,
    val advertisedName: String,
    val lastAdvertisement: Instant,
    val latitude: Double,
    val longitude: Double,
    val lastModified: Instant,
    val typeRawValue: UByte = type.rawValue,
) {
    val publicKeyPrefix: String get() = publicKey.prefix(6).hexString
    val isFloodPath: Boolean get() = outPathLength == 0xff.toUByte()
    val pathHashSize: Int get() = decodePathLen(outPathLength)?.hashSize ?: 1
    val pathHopCount: Int get() = decodePathLen(outPathLength)?.hopCount ?: 0
    val pathByteLength: Int get() = decodePathLen(outPathLength)?.byteLength ?: 0
}
