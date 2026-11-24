// PortedFrom: MC1Services/Sources/MC1Services/Models/Contact.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/ContactFrame.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/RepeaterResolvable.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.decodePathLen
import java.time.Instant
import java.util.UUID

interface RepeaterResolvable {
    val publicKey: Bytes
    val latitude: Double
    val longitude: Double
    val hasLocation: Boolean
    val lastAdvertTimestamp: UInt
    val recencyDate: Instant
    val resolvableName: String
}

data class ContactFrame(
    val publicKey: Bytes,
    val type: ContactType,
    val flags: UByte,
    val outPathLength: UByte,
    val outPath: Bytes,
    val name: String,
    val lastAdvertTimestamp: UInt,
    val latitude: Double,
    val longitude: Double,
    val lastModified: UInt,
    val typeRawValue: UByte = type.rawValue,
) {
    private val fields get() = arrayOf(publicKey, type, flags, outPathLength, outPath, name, lastAdvertTimestamp, latitude, longitude, lastModified, typeRawValue)
    override fun equals(other: Any?): Boolean = other is ContactFrame && sourceFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = sourceFieldsHash(fields)
}

data class ContactDTO(
    val id: UUID = UUID.randomUUID(),
    val radioId: RadioId,
    override val publicKey: Bytes,
    val name: String,
    val typeRawValue: UByte = 0u,
    val flags: UByte = 0u,
    val outPathLength: UByte = 255u,
    val outPath: Bytes = Bytes.EMPTY,
    override val lastAdvertTimestamp: UInt = 0u,
    override val latitude: Double = 0.0,
    override val longitude: Double = 0.0,
    val lastModified: UInt = 0u,
    val lastHeardTimestamp: UInt?,
    val nickname: String? = null,
    val isBlocked: Boolean = false,
    val isMuted: Boolean = false,
    val isFavorite: Boolean = false,
    val lastMessageDate: Instant? = null,
    val unreadCount: Long = 0,
    val unreadMentionCount: Long = 0,
    val ocvPreset: String? = null,
    val customOCVArrayString: String? = null,
    val avatarImageData: Bytes? = null,
) : RepeaterResolvable {
    private val fields get() = arrayOf(
        id, radioId, publicKey, name, typeRawValue, flags, outPathLength, outPath, lastAdvertTimestamp,
        latitude, longitude, lastModified, lastHeardTimestamp, nickname, isBlocked, isMuted, isFavorite,
        lastMessageDate, unreadCount, unreadMentionCount, ocvPreset, customOCVArrayString, avatarImageData,
    )
    override fun equals(other: Any?): Boolean = other is ContactDTO && sourceFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = sourceFieldsHash(fields)

    val type: ContactType get() = ContactType.fromRawValue(typeRawValue) ?: ContactType.CHAT
    val displayName: String get() = nickname ?: name
    val publicKeyPrefix: Bytes get() = publicKey.prefix(6)
    val publicKeyHex: String get() = publicKey.uppercaseHexString()
    val isFloodRouted: Boolean get() = outPathLength == 255.toUByte()
    val isRepeater: Boolean get() = type == ContactType.REPEATER
    val isRoom: Boolean get() = type == ContactType.ROOM
    override val hasLocation: Boolean get() = Coordinate(latitude, longitude).isValidFix
    val pathHashSize: Long get() = decodePathLen(outPathLength)?.hashSize?.toLong() ?: 1
    val pathHopCount: Long get() = decodePathLen(outPathLength)?.hopCount?.toLong() ?: 0
    val pathByteLength: Long get() = decodePathLen(outPathLength)?.byteLength?.toLong() ?: 0
    val pathHops: SnapshotList<PathHop> get() = outPath.prefix(pathByteLength.toInt()).pathHops(pathHashSize)
    val pathNodesHex: SnapshotList<String> get() = pathHops.map { it.hex }.snapshot()
    val pathString: String get() = pathNodesHex.joinToString(" \u2192 ")
    val activeOCVArray: SnapshotList<Long> get() = activeOCVArray(ocvPreset, customOCVArrayString)
    val recencyTimestamp: UInt get() = maxOf(lastModified, lastHeardTimestamp ?: 0u)
    override val recencyDate: Instant get() = Instant.ofEpochSecond(recencyTimestamp.toLong())
    override val resolvableName: String get() = displayName

    fun displayedHopCount(inboundHopCount: Long?): Long? = if (isFloodRouted) inboundHopCount else pathHopCount
    fun showsRemoteCLI(localRadioSupportsCLI: Boolean): Boolean =
        localRadioSupportsCLI && typeRawValue == ContactType.CHAT.rawValue && !isBlocked
    fun matchesStaleNodePrune(cutoff: UInt): Boolean = !isFavorite && recencyTimestamp < cutoff
    fun withMuted(isMuted: Boolean): ContactDTO = copy(isMuted = isMuted)
    fun withFavorite(isFavorite: Boolean): ContactDTO = copy(isFavorite = isFavorite)
    fun withAvatar(avatarImageData: Bytes?): ContactDTO = copy(avatarImageData = avatarImageData)

    fun toContactFrame(): ContactFrame = ContactFrame(
        publicKey, type, flags, outPathLength, outPath, name, lastAdvertTimestamp,
        latitude, longitude, lastModified, typeRawValue,
    )

    fun floodedContactFrame(asOf: UInt): ContactFrame =
        toContactFrame().copy(outPathLength = 255u, outPath = Bytes.EMPTY, lastModified = asOf)

    fun updating(frame: ContactFrame): ContactDTO = copy(
        name = frame.name, typeRawValue = frame.typeRawValue,
        flags = ((flags.toInt() and 1) or (frame.flags.toInt() and 254)).toUByte(),
        outPathLength = frame.outPathLength, outPath = frame.outPath, lastAdvertTimestamp = frame.lastAdvertTimestamp,
        latitude = frame.latitude, longitude = frame.longitude, lastModified = frame.lastModified,
    )

    companion object {
        fun fromFrame(radioId: RadioId, frame: ContactFrame, id: UUID = UUID.randomUUID()): ContactDTO = ContactDTO(
            id, radioId, frame.publicKey, frame.name, frame.typeRawValue, frame.flags,
            frame.outPathLength, frame.outPath, frame.lastAdvertTimestamp, frame.latitude,
            frame.longitude, frame.lastModified, 0u, isFavorite = frame.flags.toInt() and 1 != 0,
        )
    }
}
