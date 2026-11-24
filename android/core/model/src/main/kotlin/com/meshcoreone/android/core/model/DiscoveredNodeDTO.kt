// PortedFrom: MC1Services/Sources/MC1Services/Models/DiscoveredNode.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.decodePathLen
import java.time.Instant
import java.util.UUID

data class DiscoveredNodeDTO(
    val id: UUID,
    val radioId: RadioId,
    override val publicKey: Bytes,
    val name: String,
    val typeRawValue: UByte,
    val lastHeard: Instant,
    override val lastAdvertTimestamp: UInt,
    override val latitude: Double,
    override val longitude: Double,
    val outPathLength: UByte,
    val outPath: Bytes,
    val inboundHopCount: Long?,
    val inboundHopAdvertTimestamp: UInt?,
) : RepeaterResolvable {
    private val fields get() = arrayOf(
        id, radioId, publicKey, name, typeRawValue, lastHeard, lastAdvertTimestamp, latitude,
        longitude, outPathLength, outPath, inboundHopCount, inboundHopAdvertTimestamp,
    )
    override fun equals(other: Any?): Boolean = other is DiscoveredNodeDTO && sourceFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = sourceFieldsHash(fields)

    val nodeType: ContactType get() = ContactType.fromRawValue(typeRawValue) ?: ContactType.UNKNOWN
    override val hasLocation: Boolean get() = latitude != 0.0 || longitude != 0.0
    val isFloodRouted: Boolean get() = outPathLength == 255.toUByte()
    val pathHashSize: Long get() = decodePathLen(outPathLength)?.hashSize?.toLong() ?: 1
    val pathHopCount: Long get() = decodePathLen(outPathLength)?.hopCount?.toLong() ?: 0
    val displayedHopCount: Long? get() = if (isFloodRouted) inboundHopCount else pathHopCount
    val pathByteLength: Long get() = decodePathLen(outPathLength)?.byteLength?.toLong() ?: 0
    val pathNodesHex: SnapshotList<String>
        get() = outPath.prefix(pathByteLength.toInt()).pathHops(pathHashSize).map { it.hex }.snapshot()
    override val recencyDate: Instant get() = lastHeard
    override val resolvableName: String get() = name
}
