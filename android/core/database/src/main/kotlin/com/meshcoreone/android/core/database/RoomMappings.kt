// PortedFrom: MC1Services/Sources/MC1Services/Models/RemoteNodeSession.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/RoomMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Scoped room rows preserve raw status, permissions and exact timestamps.
package com.meshcoreone.android.core.database

import com.meshcoreone.android.core.model.*
import java.time.Instant

fun RemoteNodeSessionDTO.toEntity(): RemoteNodeSessionEntity = RemoteNodeSessionEntity(
    radioId.value, id, publicKey, name, role.rawValue.toLong(), latitude, longitude, isConnected,
    permissionLevel.rawValue.toLong(), lastConnectedDate?.let(StoredInstant::from), lastBatteryMillivolts?.toLong(),
    lastUptimeSeconds?.toLong(), lastNoiseFloor?.toLong(), unreadCount, notificationLevel.rawValue, null,
    isFavorite, lastRxAirtimeSeconds?.toLong(), neighborCount, lastSyncTimestamp.toLong(), lastMessageDate?.let(StoredInstant::from),
)
fun RemoteNodeSessionEntity.toDTO(): RemoteNodeSessionDTO = RemoteNodeSessionDTO(
    id, RadioId(radioId), publicKey, name,
    RemoteNodeRole.fromRawValue(roleRawValue.ubyte("session.role")) ?: throw DatabaseValueException("session.role", "Unknown raw value $roleRawValue"),
    latitude, longitude, isConnected,
    RoomPermissionLevel.fromRawValue(permissionLevelRawValue.ubyte("session.permissionLevel"))
        ?: throw DatabaseValueException("session.permissionLevel", "Unknown raw value $permissionLevelRawValue"),
    lastConnectedDate?.toInstant(), lastBatteryMillivolts?.ushort("session.lastBatteryMillivolts"),
    lastUptimeSeconds?.uint("session.lastUptimeSeconds"), lastNoiseFloor?.short("session.lastNoiseFloor"), unreadCount,
    storedNotificationLevel(notificationLevelRawValue, legacyIsMuted), isFavorite, lastRxAirtimeSeconds?.uint("session.lastRxAirtimeSeconds"),
    neighborCount, lastSyncTimestamp.uint("session.lastSyncTimestamp"),
    lastMessageDate?.toInstant() ?: if (lastSyncTimestamp > 0) Instant.ofEpochSecond(lastSyncTimestamp.uint("session.lastSyncTimestamp").toLong()) else null,
)

fun RoomMessageDTO.toEntity(radioId: RadioId): RoomMessageEntity = RoomMessageEntity(
    radioId.value, id, sessionID, authorKeyPrefix, authorName, text, timestamp.toLong(), StoredInstant.from(createdAt),
    isFromSelf, deduplicationKey, statusRawValue, ackCode?.toLong(), roundTripTime?.toLong(), retryAttempt, maxRetryAttempts, failureSeen,
)
fun RoomMessageEntity.toDTO(): RoomMessageDTO = RoomMessageDTO(
    id, sessionID, authorKeyPrefix, authorName, text, timestamp.uint("roomMessage.timestamp"), createdAt.toInstant(),
    isFromSelf, deduplicationKey, statusRawValue, ackCode?.uint("roomMessage.ackCode"), roundTripTime?.uint("roomMessage.roundTripTime"),
    retryAttempt, maxRetryAttempts, failureSeen,
)
