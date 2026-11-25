// PortedFrom: MC1Services/Sources/MC1Services/Models/RemoteNodeSession.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/RoomMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.sha256
import java.time.Instant
import java.util.UUID

data class RemoteNodeSessionDTO(
    val id: UUID = UUID.randomUUID(),
    val radioId: RadioId,
    val publicKey: Bytes,
    val name: String,
    val role: RemoteNodeRole,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val isConnected: Boolean = false,
    val permissionLevel: RoomPermissionLevel = RoomPermissionLevel.GUEST,
    val lastConnectedDate: Instant? = null,
    val lastBatteryMillivolts: UShort? = null,
    val lastUptimeSeconds: UInt? = null,
    val lastNoiseFloor: Short? = null,
    val unreadCount: Long = 0,
    val notificationLevel: NotificationLevel = NotificationLevel.ALL,
    val isFavorite: Boolean = false,
    val lastRxAirtimeSeconds: UInt? = null,
    val neighborCount: Long = 0,
    val lastSyncTimestamp: UInt = 0u,
    val lastMessageDate: Instant? = null,
    // Android-only receive witness; excluded from source equality and backup data.
    val receiveConversationID: UUID? = null,
) {
    private val fields get() = arrayOf(
        id, radioId, publicKey, name, role, latitude, longitude, isConnected, permissionLevel, lastConnectedDate,
        lastBatteryMillivolts, lastUptimeSeconds, lastNoiseFloor, unreadCount, notificationLevel, isFavorite,
        lastRxAirtimeSeconds, neighborCount, lastSyncTimestamp, lastMessageDate,
    )
    override fun equals(other: Any?): Boolean = other is RemoteNodeSessionDTO && sourceFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = sourceFieldsHash(fields)

    val isMuted: Boolean get() = notificationLevel == NotificationLevel.MUTED
    val publicKeyPrefix: Bytes get() = publicKey.prefix(6)
    val publicKeyHex: String get() = publicKey.uppercaseHexString()
    val isRoom: Boolean get() = role == RemoteNodeRole.ROOM_SERVER
    val isRepeater: Boolean get() = role == RemoteNodeRole.REPEATER
    val isSensor: Boolean get() = role == RemoteNodeRole.SENSOR
    val canPost: Boolean get() = isRoom && permissionLevel.canPost
    val isAdmin: Boolean get() = permissionLevel.isAdmin
    val hasLocation: Boolean get() = Coordinate(latitude, longitude).isValidFix
    val coordinate: Coordinate? get() = if (hasLocation) Coordinate(latitude, longitude) else null
    fun withNotificationLevel(level: NotificationLevel): RemoteNodeSessionDTO = copy(notificationLevel = level)
    fun withFavorite(favorite: Boolean): RemoteNodeSessionDTO = copy(isFavorite = favorite)
}

data class RoomMessageDTO(
    val id: UUID = UUID.randomUUID(),
    val sessionID: UUID,
    val authorKeyPrefix: Bytes,
    val authorName: String? = null,
    val text: String,
    val timestamp: UInt,
    val createdAt: Instant = Instant.now(),
    val isFromSelf: Boolean = false,
    val deduplicationKey: String = generateDeduplicationKey(timestamp, authorKeyPrefix, text),
    val statusRawValue: Long = MessageStatus.DELIVERED.rawValue,
    val ackCode: UInt? = null,
    val roundTripTime: UInt? = null,
    val retryAttempt: Long = 0,
    val maxRetryAttempts: Long = 0,
    val failureSeen: Boolean = false,
) {
    val authorDisplayName: String get() = authorName ?: authorKeyPrefix.uppercaseHexString()
    val date: Instant get() = Instant.ofEpochSecond(timestamp.toLong())
    val knownStatus: MessageStatus? get() = MessageStatus.fromRawValue(statusRawValue)
    val status: MessageStatus get() = knownStatus ?: MessageStatus.DELIVERED

    companion object {
        fun generateDeduplicationKey(timestamp: UInt, authorKeyPrefix: Bytes, text: String): String =
            "$timestamp-${authorKeyPrefix.uppercaseHexString()}-${sha256(Bytes.utf8(text)).prefix(4).uppercaseHexString()}"
    }
}
