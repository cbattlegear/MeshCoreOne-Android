// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/RoomPersisting.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/DiscoveredNodePersisting.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/PersistenceStoreProtocol.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Rooms.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Metadata.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID

interface RoomPersisting {
    suspend fun fetchRemoteNodeSession(key: EntityKey): RemoteNodeSessionDTO?
    suspend fun fetchRemoteNodeSession(radioId: RadioId, publicKey: Bytes): RemoteNodeSessionDTO?
    suspend fun fetchRemoteNodeSessionByPrefix(radioId: RadioId, prefix: Bytes): RemoteNodeSessionDTO?
    suspend fun fetchRemoteNodeSessions(radioId: RadioId): SnapshotList<RemoteNodeSessionDTO>
    suspend fun fetchConnectedRemoteNodeSessions(radioId: RadioId): SnapshotList<RemoteNodeSessionDTO>
    suspend fun saveRemoteNodeSessionDTO(dto: RemoteNodeSessionDTO)
    suspend fun updateRemoteNodeSessionConnection(key: EntityKey, isConnected: Boolean, permissionLevel: RoomPermissionLevel)
    suspend fun resetAllRemoteNodeSessionConnections()
    suspend fun cleanupDuplicateRemoteNodeSessions(publicKey: Bytes, keep: EntityKey)
    suspend fun deleteRemoteNodeSession(key: EntityKey)
    suspend fun markSessionDisconnected(key: EntityKey)
    suspend fun markRoomSessionConnected(key: EntityKey): Boolean
    suspend fun updateRoomActivity(key: EntityKey, syncTimestamp: UInt? = null)
    suspend fun saveRoomMessage(radioId: RadioId, dto: RoomMessageDTO)
    suspend fun fetchRoomMessage(key: EntityKey): RoomMessageDTO?
    suspend fun fetchRoomMessages(session: EntityKey, limit: Long? = null, offset: Long? = null): SnapshotList<RoomMessageDTO>
    suspend fun isDuplicateRoomMessage(session: EntityKey, deduplicationKey: String): Boolean
    suspend fun updateRoomMessageStatus(key: EntityKey, status: MessageStatus, ackCode: UInt?, roundTripTime: UInt?)
    suspend fun updateRoomMessageRetryStatus(key: EntityKey, status: MessageStatus, retryAttempt: Long, maxRetryAttempts: Long)
    suspend fun incrementRoomUnreadCount(key: EntityKey)
    suspend fun resetRoomUnreadCount(key: EntityKey)
    suspend fun setSessionMuted(key: EntityKey, isMuted: Boolean)
    suspend fun setSessionNotificationLevel(key: EntityKey, level: NotificationLevel)
    suspend fun setSessionFavorite(key: EntityKey, isFavorite: Boolean)
}

interface DiscoveredNodePersisting {
    suspend fun upsertDiscoveredNode(radioId: RadioId, frame: ContactFrame): DiscoveredNodeSaveResult
    suspend fun setInboundHopCount(radioId: RadioId, publicKey: Bytes, hopCount: Long, advertTimestamp: UInt?)
    suspend fun fetchDiscoveredNodes(radioId: RadioId): SnapshotList<DiscoveredNodeDTO>
    suspend fun deleteDiscoveredNode(key: EntityKey)
    suspend fun clearDiscoveredNodes(radioId: RadioId)
    suspend fun fetchContactPublicKeys(radioId: RadioId): SnapshotSet<Bytes>
}

interface MetadataPersisting {
    suspend fun getTotalUnreadCounts(radioId: RadioId): UnreadCounts
    suspend fun getUnreadCount(contact: EntityKey): Long
    suspend fun getChannelUnreadCount(channel: EntityKey): Long
}

interface PersistenceStoreProtocol :
    DeviceSessionPersisting, ContactPersisting, ChannelPersisting, MessagePersisting, HeardRepeatPersisting,
    TracePathPersisting, DebugLogPersisting, LinkPreviewPersisting, RxLogPersisting, RoomPersisting,
    DiscoveredNodePersisting, ReactionPersisting, NodeSnapshotPersisting, FailedSendPersisting, MetadataPersisting,
    ChannelOccupantMetadataPersisting {
    /** Optional local custody, forwarded by protocol decorators as well as the production Room store. */
    val reactionQueue: ReactionQueuePersisting? get() = null
    val reactionNotifications: ReactionNotificationPersisting? get() = null
    suspend fun warmUp()
}
