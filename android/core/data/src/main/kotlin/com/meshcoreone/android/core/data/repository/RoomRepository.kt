// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/RoomPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Rooms.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes

internal class RoomRepository(private val context: RoomRepositoryContext) : RoomPersisting {
    private suspend fun RepositoryTransaction.roomSnapshot(row: RemoteNodeSessionEntity): RemoteNodeSessionDTO {
        val dto = row.toDTO()
        return if (!dto.isRoom) dto else dto.copy(receiveConversationID =
            textRuleConversation(dto.radioId, "room", dto.id, dto.publicKey.hexString).identity)
    }

    override suspend fun fetchRemoteNodeSession(key: EntityKey): RemoteNodeSessionDTO? =
        context.write("fetchRemoteNodeSessionById") {
            database.sessions().byId(key.radioId.value, key.id)?.let { roomSnapshot(it) }
        }

    override suspend fun fetchRemoteNodeSession(radioId: RadioId, publicKey: Bytes): RemoteNodeSessionDTO? =
        context.write("fetchRemoteNodeSessionByPublicKey") {
            database.sessions().forPublicKey(radioId.value, publicKey).firstOrNull()?.let { roomSnapshot(it) }
        }

    override suspend fun fetchRemoteNodeSessionByPrefix(radioId: RadioId, prefix: Bytes): RemoteNodeSessionDTO? =
        context.write("fetchRemoteNodeSessionByPrefix") {
            database.sessions().forPrefix(radioId.value, prefix, 6).firstOrNull()?.let { roomSnapshot(it) }
        }

    override suspend fun fetchRemoteNodeSessions(radioId: RadioId): SnapshotList<RemoteNodeSessionDTO> =
        context.write("fetchRemoteNodeSessions") {
            database.sessions().forRadio(radioId.value).sortedBy { it.name }.map { roomSnapshot(it) }.snapshot()
        }

    override suspend fun fetchConnectedRemoteNodeSessions(radioId: RadioId): SnapshotList<RemoteNodeSessionDTO> =
        context.write("fetchConnectedRemoteNodeSessions") {
            database.sessions().connected(radioId.value).map { roomSnapshot(it) }.snapshot()
        }

    override suspend fun saveRemoteNodeSessionDTO(dto: RemoteNodeSessionDTO) =
        context.write("saveRemoteNodeSessionDTO") {
            val previous = database.sessions().byId(dto.radioId.value, dto.id)
            database.sessions().upsert(dto.toEntity().copy(legacyIsMuted = previous?.legacyIsMuted))
            save()
        }

    override suspend fun updateRemoteNodeSessionConnection(
        key: EntityKey, isConnected: Boolean, permissionLevel: RoomPermissionLevel,
    ) = mutateSession("updateRemoteNodeSessionConnection", key) {
        it.copy(isConnected = isConnected, permissionLevelRawValue = permissionLevel.rawValue.toLong(),
            lastConnectedDate = if (isConnected) StoredInstant.from(context.clock.instant()) else it.lastConnectedDate)
    }

    override suspend fun resetAllRemoteNodeSessionConnections() =
        context.write("resetAllRemoteNodeSessionConnections") {
            database.sessions().resetAllConnections()
            save()
        }

    override suspend fun cleanupDuplicateRemoteNodeSessions(publicKey: Bytes, keep: EntityKey) =
        context.write("cleanupDuplicateRemoteNodeSessions") {
            if (database.sessions().byId(keep.radioId.value, keep.id) == null) return@write
            val duplicates = database.sessions().forPublicKey(keep.radioId.value, publicKey).filter { it.id != keep.id }
            for (row in duplicates) {
                database.roomMessages().deleteForSession(keep.radioId.value, row.id)
                database.sessions().delete(keep.radioId.value, row.id)
            }
            if (duplicates.isNotEmpty()) save()
        }

    override suspend fun deleteRemoteNodeSession(key: EntityKey) = context.write("deleteRemoteNodeSession") {
        database.roomMessages().deleteForSession(key.radioId.value, key.id)
        database.sessions().delete(key.radioId.value, key.id)
        save()
    }

    override suspend fun markSessionDisconnected(key: EntityKey) = context.write("markSessionDisconnected") {
        if (database.sessions().markDisconnected(key.radioId.value, key.id) > 0) save()
    }

    override suspend fun markRoomSessionConnected(key: EntityKey): Boolean = context.write("markRoomSessionConnected") {
        val changed = database.sessions().markConnected(key.radioId.value, key.id) > 0
        if (changed) save()
        changed
    }

    override suspend fun updateRoomActivity(key: EntityKey, syncTimestamp: UInt?) =
        mutateSession("updateRoomActivity", key) {
            it.copy(lastSyncTimestamp = maxOf(it.lastSyncTimestamp, syncTimestamp?.toLong() ?: it.lastSyncTimestamp),
                lastMessageDate = StoredInstant.from(context.clock.instant()))
        }

    override suspend fun saveRoomMessage(radioId: RadioId, dto: RoomMessageDTO) = context.write("saveRoomMessage") {
        if (!database.roomMessages().duplicate(radioId.value, dto.sessionID, dto.deduplicationKey)) {
            database.roomMessages().upsert(dto.toEntity(radioId))
            save()
        }
    }

    override suspend fun fetchRoomMessage(key: EntityKey): RoomMessageDTO? = context.read("fetchRoomMessage") {
        database.roomMessages().byId(key.radioId.value, key.id)?.toDTO()
    }

    override suspend fun fetchRoomMessages(session: EntityKey, limit: Long?, offset: Long?): SnapshotList<RoomMessageDTO> =
        context.read("fetchRoomMessages") {
            queryBounds(limit ?: 0, offset ?: 0)
            database.roomMessages().forSession(session.radioId.value, session.id, limit ?: -1, offset ?: 0)
                .map { it.toDTO() }.snapshot()
        }

    override suspend fun isDuplicateRoomMessage(session: EntityKey, deduplicationKey: String): Boolean =
        context.read("isDuplicateRoomMessage") {
            database.roomMessages().duplicate(session.radioId.value, session.id, deduplicationKey)
        }

    override suspend fun updateRoomMessageStatus(
        key: EntityKey, status: MessageStatus, ackCode: UInt?, roundTripTime: UInt?,
    ) = context.write("updateRoomMessageStatus") {
        if (database.roomMessages().setStatus(
                key.radioId.value, key.id, status.rawValue, ackCode?.toLong(), roundTripTime?.toLong(),
            ) > 0) save()
    }

    override suspend fun updateRoomMessageRetryStatus(
        key: EntityKey, status: MessageStatus, retryAttempt: Long, maxRetryAttempts: Long,
    ) = context.write("updateRoomMessageRetryStatus") {
        if (database.roomMessages().setRetryStatus(
                key.radioId.value, key.id, status.rawValue, retryAttempt, maxRetryAttempts,
            ) > 0) save()
    }

    override suspend fun incrementRoomUnreadCount(key: EntityKey) =
        mutateSession("incrementRoomUnreadCount", key) { it.copy(unreadCount = checkedIncrement(it.unreadCount)) }
    override suspend fun resetRoomUnreadCount(key: EntityKey) =
        mutateSession("resetRoomUnreadCount", key) { it.copy(unreadCount = 0) }
    override suspend fun setSessionMuted(key: EntityKey, isMuted: Boolean) =
        setSessionNotificationLevel(key, if (isMuted) NotificationLevel.MUTED else NotificationLevel.ALL)
    override suspend fun setSessionNotificationLevel(key: EntityKey, level: NotificationLevel) =
        mutateSession("setSessionNotificationLevel", key, required = true) { it.copy(notificationLevelRawValue = level.rawValue) }
    override suspend fun setSessionFavorite(key: EntityKey, isFavorite: Boolean) =
        mutateSession("setSessionFavorite", key, required = true) { it.copy(isFavorite = isFavorite) }

    private suspend fun mutateSession(
        operation: String, key: EntityKey, required: Boolean = false,
        mutation: (RemoteNodeSessionEntity) -> RemoteNodeSessionEntity,
    ) = context.write(operation) {
        val row = database.sessions().byId(key.radioId.value, key.id)
        if (row == null) {
            if (required) throw PersistenceStoreException(PersistenceStoreError.RemoteNodeSessionNotFound)
        } else {
            database.sessions().upsert(mutation(row))
            save()
        }
    }
}
