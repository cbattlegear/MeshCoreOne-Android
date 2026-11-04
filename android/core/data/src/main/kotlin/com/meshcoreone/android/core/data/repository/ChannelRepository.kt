// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/ChannelPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Channels.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.time.Instant
import java.util.UUID

internal class ChannelRepository(
    private val context: RoomRepositoryContext,
    private val rooms: RoomRepository,
    private val identityScope: ChannelIdentityWriteScope? = null,
) : ChannelPersisting, ChannelOccupantMetadataPersisting {
    override suspend fun <T> withChannelOperation(block: suspend (ChannelPersisting, ChannelIdentityWriteScope) -> T): T {
        check(identityScope == null) { "A scoped channel repository cannot start another channel operation" }
        return context.channelOperation { scope -> block(ChannelRepository(context, rooms, scope), scope) }
    }

    override suspend fun fetchChannelOccupantID(radioId: RadioId, index: UByte): UUID? =
        fetchChannelOccupantSnapshot(radioId, index)?.occupantID

    override suspend fun fetchChannelOccupantSnapshot(radioId: RadioId, index: UByte): ChannelOccupantSnapshot? =
        context.read("fetchChannelOccupantID") {
            val channel = database.channels().forIndex(radioId.value, index.toLong()).singleOrNull()
            if (channel == null || !channel.isEnabled) null
            else database.channelOccupants().forIndex(radioId.value, index.toLong())
                ?.let { ChannelOccupantSnapshot(channel.toDTO(), it) }
        }

    override suspend fun applyChannelMessageMetadataIfCurrentOccupant(
        scope: ChannelHistoryScope,
        lastMessageDate: Instant?,
        incrementUnreadCount: Boolean,
    ): ChannelOccupantMetadataMutationOutcome = context.channelWrite(
        "applyChannelMessageMetadataIfCurrentOccupant", identityScope = identityScope,
    ) {
        val channel = database.channels().forIndex(scope.radioId.value, scope.channelIndex.toLong()).singleOrNull()
        val occupantID = database.channelOccupants().forIndex(scope.radioId.value, scope.channelIndex.toLong())
        if (channel == null || !channel.isEnabled || occupantID != scope.expectedOccupantID) {
            return@channelWrite ChannelOccupantMetadataMutationOutcome.STALE_OR_UNPROVED
        }
        val storedDate = lastMessageDate?.let(StoredInstant::from)
        val unreadCount = if (incrementUnreadCount) checkedIncrement(channel.unreadCount) else channel.unreadCount
        val updated = database.channels().updateMessageMetadata(
            channel.radioId, channel.id, channel.index,
            storedDate?.seconds, storedDate?.nanos, unreadCount,
        )
        if (updated != 1) {
            throw PersistenceStoreException(
                PersistenceStoreError.SaveFailed("Channel message metadata update was not applied"),
                IllegalStateException("Expected one channel row update but affected $updated"),
            )
        }
        save()
        ChannelOccupantMetadataMutationOutcome.APPLIED
    }

    override suspend fun invalidateChannelOccupant(radioId: RadioId, index: UByte) =
        context.channelWrite("invalidateChannelOccupant", identityScope = identityScope) {
            if (database.channelOccupants().invalidate(radioId.value, index.toLong()) > 0) save()
        }

    override suspend fun beginChannelMutation(radioId: RadioId, index: UByte) {
        check(identityScope != null) { "A radio mutation requires an active scoped channel operation" }
        context.channelWrite("beginChannelMutation", identityScope = identityScope) {
            val current = database.channelOccupants().stateForIndex(radioId.value, index.toLong())
            database.channelOccupants().upsert(
                current?.copy(unresolved = true)
                    ?: ChannelOccupantEntity(radioId.value, index.toLong(), UUID.randomUUID(), unresolved = true),
            )
            save()
        }
    }

    override suspend fun fetchChannels(radioId: RadioId): SnapshotList<ChannelDTO> = context.read("fetchChannels") {
        database.channels().forRadio(radioId.value).map { it.toDTO() }.snapshot()
    }

    override suspend fun fetchChannel(radioId: RadioId, index: UByte): ChannelDTO? = context.read("fetchChannelByIndex") {
        database.channels().forIndex(radioId.value, index.toLong()).firstOrNull()?.toDTO()
    }

    override suspend fun fetchChannel(key: EntityKey): ChannelDTO? = context.read("fetchChannelById") {
        database.channels().byId(key.radioId.value, key.id)?.toDTO()
    }

    override suspend fun saveChannel(radioId: RadioId, info: ChannelInfo): UUID =
        context.channelWrite("saveChannelInfo", flushBeforeRollback = true, identityScope = identityScope) {
            val existing = database.channels().forIndex(radioId.value, info.index.toLong()).firstOrNull()
            val row = if (existing != null) applyingRadioInfo(existing, info) else ChannelDTO.fromInfo(radioId, info).toEntity()
            database.channels().upsert(row)
            updateOccupant(existing, row, authoritative = true)
            save()
            row.id
        }

    override suspend fun saveChannel(dto: ChannelDTO) = context.channelWrite("saveChannel", identityScope = identityScope) {
        val existing = database.channels().byId(dto.radioId.value, dto.id)
        val row = existing?.applying(dto) ?: dto.toEntity()
        database.channels().upsert(row)
        updateOccupant(existing, row, authoritative = false)
        save()
    }

    override suspend fun batchSaveChannels(
        radioId: RadioId,
        configured: SnapshotList<ChannelInfo>,
        unconfiguredIndices: SnapshotList<UByte>,
        pruneBeyond: UByte?,
    ): SnapshotList<ChannelDTO> = context.channelWrite(
        "batchSaveChannels", flushBeforeRollback = true, identityScope = identityScope,
    ) {
        val byIndex = LinkedHashMap<Long, ChannelEntity>()
        for (row in database.channels().forRadio(radioId.value)) byIndex.putIfAbsent(row.index, row)
        for (info in configured) {
            val existing = byIndex[info.index.toLong()]
            val row = if (existing == null) ChannelDTO.fromInfo(radioId, info).toEntity() else applyingRadioInfo(existing, info)
            database.channels().upsert(row)
            updateOccupant(existing, row, authoritative = true)
            byIndex[row.index] = row
        }
        for (index in unconfiguredIndices) {
            deleteChannelMessages(radioId, index, preserveUnresolved = true)
            database.channelOccupants().invalidate(radioId.value, index.toLong())
            byIndex.remove(index.toLong())?.let { database.channels().delete(radioId.value, it.id) }
        }
        if (pruneBeyond != null) {
            for ((index, row) in byIndex.toMap()) {
                if (index >= pruneBeyond.toLong()) {
                    deleteChannelMessages(radioId, row.toDTO().index, preserveUnresolved = true)
                    database.channelOccupants().invalidate(radioId.value, index)
                    database.channels().delete(radioId.value, row.id)
                    byIndex.remove(index)
                }
            }
        }
        save()
        database.channels().forRadio(radioId.value).map { it.toDTO() }.snapshot()
    }

    private suspend fun RepositoryTransaction.applyingRadioInfo(row: ChannelEntity, info: ChannelInfo): ChannelEntity {
        if (row.secret == info.secret) return row.copy(name = info.name, secret = info.secret)
        deleteChannelMessages(RadioId(row.radioId), row.toDTO().index, preserveUnresolved = true)
        return row.copy(name = info.name, secret = info.secret, lastMessageDate = null, unreadCount = 0, unreadMentionCount = 0)
    }

    override suspend fun deleteChannel(key: EntityKey) = context.channelWrite(
        "deleteChannel", flushBeforeRollback = true, identityScope = identityScope,
    ) {
        val row = database.channels().byId(key.radioId.value, key.id) ?: return@channelWrite
        deleteChannelMessages(key.radioId, row.toDTO().index, preserveUnresolved = true)
        database.channelOccupants().invalidate(key.radioId.value, row.index)
        database.channels().delete(key.radioId.value, key.id)
        save()
    }

    private suspend fun RepositoryTransaction.updateOccupant(previous: ChannelEntity?, row: ChannelEntity, authoritative: Boolean) {
        val occupants = database.channelOccupants()
        val current = occupants.stateForIndex(row.radioId, row.index)
        if (!row.isEnabled || database.channels().forIndex(row.radioId, row.index).size != 1) {
            occupants.invalidate(row.radioId, row.index)
        } else if (previous == null || !previous.isEnabled || previous.secret != row.secret ||
            current == null
        ) {
            occupants.upsert(ChannelOccupantEntity(
                row.radioId, row.index, UUID.randomUUID(), unresolved = !authoritative,
            ))
        } else if (authoritative && current.unresolved) {
            occupants.upsert(current.copy(unresolved = false))
        }
    }

    override suspend fun deleteMessagesForChannel(radioId: RadioId, channelIndex: UByte) =
        metadataWrite("deleteMessagesForChannel", flushBeforeRollback = true) {
            deleteChannelMessages(radioId, channelIndex, preserveUnresolved = identityScope != null)
            save()
        }

    override suspend fun updateChannelLastMessage(key: EntityKey, date: Instant?) =
        mutateChannel("updateChannelLastMessage", key) { it.copy(lastMessageDate = date?.let(StoredInstant::from)) }
    override suspend fun incrementChannelUnreadCount(key: EntityKey) =
        mutateChannel("incrementChannelUnreadCount", key) { it.copy(unreadCount = checkedIncrement(it.unreadCount)) }
    override suspend fun clearChannelUnreadCount(key: EntityKey) =
        mutateChannel("clearChannelUnreadCount", key) { it.copy(unreadCount = 0) }
    override suspend fun incrementChannelUnreadMentionCount(key: EntityKey) =
        mutateChannel("incrementChannelUnreadMentionCount", key) { it.copy(unreadMentionCount = checkedIncrement(it.unreadMentionCount)) }
    override suspend fun decrementChannelUnreadMentionCount(key: EntityKey) =
        mutateChannel("decrementChannelUnreadMentionCount", key) { it.copy(unreadMentionCount = decrementedUnread(it.unreadMentionCount)) }
    override suspend fun clearChannelUnreadMentionCount(key: EntityKey) =
        mutateChannel("clearChannelUnreadMentionCount", key) { it.copy(unreadMentionCount = 0) }

    override suspend fun clearChannelUnreadCount(radioId: RadioId, index: UByte) =
        metadataWrite("clearChannelUnreadCountByIndex") {
            val row = database.channels().forIndex(radioId.value, index.toLong()).firstOrNull() ?: return@metadataWrite
            database.channels().upsert(row.copy(unreadCount = 0))
            save()
        }

    override suspend fun setChannelMuted(key: EntityKey, isMuted: Boolean) =
        setChannelNotificationLevel(key, if (isMuted) NotificationLevel.MUTED else NotificationLevel.ALL)

    override suspend fun setChannelNotificationLevel(key: EntityKey, level: NotificationLevel) =
        mutateChannel("setChannelNotificationLevel", key, required = true) { it.copy(notificationLevelRawValue = level.rawValue) }

    override suspend fun setSessionNotificationLevel(key: EntityKey, level: NotificationLevel) {
        if (identityScope == null) rooms.setSessionNotificationLevel(key, level)
        else identityScope.withChannelIdentityMutation { rooms.setSessionNotificationLevel(key, level) }
    }

    override suspend fun setChannelFavorite(key: EntityKey, isFavorite: Boolean) =
        mutateChannel("setChannelFavorite", key, required = true) { it.copy(isFavorite = isFavorite) }

    override suspend fun setChannelFloodScope(key: EntityKey, floodScope: ChannelFloodScope) =
        metadataWrite("setChannelFloodScope") {
            if (database.channels().byId(key.radioId.value, key.id) == null) {
                throw PersistenceStoreException(PersistenceStoreError.ChannelNotFound)
            }
            val storage = ChannelFloodScopeStorage.decompose(floodScope)
            database.channels().setFloodScope(key.radioId.value, key.id, storage.modeRawValue, storage.regionName)
            save()
        }

    override suspend fun fetchUnseenChannelMentionIDs(radioId: RadioId, channelIndex: UByte): SnapshotList<UUID> =
        context.read("fetchUnseenChannelMentionIDs") {
            database.messages().unseenChannelMentionIDs(radioId.value, channelIndex.toLong()).snapshot()
        }

    private suspend fun mutateChannel(
        operation: String, key: EntityKey, required: Boolean = false, mutation: (ChannelEntity) -> ChannelEntity,
    ) = metadataWrite(operation) {
        val row = database.channels().byId(key.radioId.value, key.id)
        if (row == null) {
            if (required) throw PersistenceStoreException(PersistenceStoreError.ChannelNotFound)
        } else {
            database.channels().upsert(mutation(row))
            save()
        }
    }

    private suspend fun <T> metadataWrite(
        operation: String,
        flushBeforeRollback: Boolean = false,
        block: suspend RepositoryTransaction.() -> T,
    ): T = if (identityScope == null) context.write(operation, flushBeforeRollback, block)
        else context.channelWrite(operation, flushBeforeRollback, identityScope, block)
}
