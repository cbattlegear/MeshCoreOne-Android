// PortedFrom: MC1Services/Sources/MC1Services/Models/RemoteNodeSession.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/RoomMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Sessions/room messages retain value references, not automatic cascades.
package com.meshcoreone.android.core.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID

@Entity(tableName = "remote_node_sessions", primaryKeys = ["radioId", "id"], indices = [Index("radioId"), Index(value = ["radioId", "publicKey"])])
data class RemoteNodeSessionEntity(
    val radioId: UUID, val id: UUID, val publicKey: Bytes, val name: String, val roleRawValue: Long,
    val latitude: Double, val longitude: Double, val isConnected: Boolean, val permissionLevelRawValue: Long,
    @Embedded(prefix = "lastConnectedDate_") val lastConnectedDate: StoredInstant?,
    val lastBatteryMillivolts: Long?, val lastUptimeSeconds: Long?, val lastNoiseFloor: Long?,
    val unreadCount: Long, val notificationLevelRawValue: Long, val legacyIsMuted: Boolean?,
    val isFavorite: Boolean, val lastRxAirtimeSeconds: Long?, val neighborCount: Long, val lastSyncTimestamp: Long,
    @Embedded(prefix = "lastMessageDate_") val lastMessageDate: StoredInstant?,
)

@Entity(
    tableName = "room_messages", primaryKeys = ["radioId", "id"],
    indices = [Index(value = ["radioId", "sessionID", "timestamp"]), Index(value = ["radioId", "sessionID", "deduplicationKey"])],
)
data class RoomMessageEntity(
    val radioId: UUID, val id: UUID, val sessionID: UUID, val authorKeyPrefix: Bytes,
    val authorName: String?, val text: String, val timestamp: Long,
    @Embedded(prefix = "createdAt_") val createdAt: StoredInstant, val isFromSelf: Boolean,
    val deduplicationKey: String, val statusRawValue: Long, val ackCode: Long?, val roundTripTime: Long?,
    val retryAttempt: Long, val maxRetryAttempts: Long, val failureSeen: Boolean,
)
