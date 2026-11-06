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
) : ChannelPersisting, ChannelOccupantAuthority {
    override suspend fun fetchChannelOccupantEpoch(radioId: RadioId, channelIndex: UByte): String? =
        context.read("fetchChannelOccupantEpoch") {
            verifyChannelOccupantStorage()
            val rows = database.channels().forIndex(radioId.value, channelIndex.toLong())
            if (rows.size > 1) throw PersistenceStoreException(PersistenceStoreError.InvalidData)
            val row = rows.singleOrNull() ?: return@read null
            val authority = database.channelOccupantAuthorities().forSlot(radioId.value, channelIndex.toLong())
                ?: return@read null
            authority.epoch.takeIf {
                authority.isResolved && authority.boundRowId == row.id && row.isEnabled &&
                    database.devices().forRadio(radioId.value).isNotEmpty()
            }
        }

    internal suspend fun <T> withChannelOperation(
        block: suspend (ChannelRepository, ChannelIdentityWriteScope) -> T,
    ): T {
        check(identityScope == null) { "A scoped channel repository cannot reacquire its operation lane" }
        return context.channelIdentity.withChannelOperationScope { scope ->
            block(ChannelRepository(context, rooms, scope), scope)
        }
    }

    private suspend fun <T> channelWrite(
        operation: String,
        flushBeforeRollback: Boolean = false,
        block: suspend RepositoryTransaction.() -> T,
    ): T = context.channelWrite(operation, identityScope, flushBeforeRollback, block)

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
        channelWrite("saveChannelInfo", flushBeforeRollback = true) {
            val occupants = database.channels().forIndex(radioId.value, info.index.toLong())
            if (occupants.size > 1) throw PersistenceStoreException(PersistenceStoreError.InvalidData)
            val existing = occupants.singleOrNull()
            val row = if (existing != null) applyingRadioInfo(existing, info) else ChannelDTO.fromInfo(radioId, info).toEntity()
            database.channels().upsert(row)
            save()
            row.id
        }

    override suspend fun saveChannel(dto: ChannelDTO) = channelWrite("saveChannel") {
        val identified = database.channels().byId(dto.radioId.value, dto.id)
        val occupants = database.channels().forIndex(dto.radioId.value, identified?.index ?: dto.index.toLong())
        if (occupants.size > 1) throw PersistenceStoreException(PersistenceStoreError.InvalidData)
        // A replayed seed can have a different UUID after restore; it must not replace imported state.
        val existing = identified ?: occupants.singleOrNull()
        if (identified == null && existing != null &&
            (existing.secret != dto.secret || existing.isEnabled != dto.isEnabled)) {
            throw PersistenceStoreException(PersistenceStoreError.InvalidData)
        }
        if (identified == null && existing != null) return@channelWrite
        database.channels().upsert(identified?.applying(dto) ?: dto.toEntity())
        save()
    }

    override suspend fun batchSaveChannels(
        radioId: RadioId,
        configured: SnapshotList<ChannelInfo>,
        unconfiguredIndices: SnapshotList<UByte>,
        pruneBeyond: UByte?,
    ): SnapshotList<ChannelDTO> = channelWrite("batchSaveChannels", flushBeforeRollback = true) {
        val byIndex = LinkedHashMap<Long, ChannelEntity>()
        for (row in database.channels().forRadio(radioId.value)) {
            if (byIndex.putIfAbsent(row.index, row) != null) {
                throw PersistenceStoreException(PersistenceStoreError.InvalidData)
            }
        }
        for (info in configured) {
            val existing = byIndex[info.index.toLong()]
            val row = if (existing == null) ChannelDTO.fromInfo(radioId, info).toEntity() else applyingRadioInfo(existing, info)
            database.channels().upsert(row)
            byIndex[row.index] = row
        }
        for (index in unconfiguredIndices) {
            deleteChannelMessages(radioId, index)
            byIndex.remove(index.toLong())?.let { database.channels().delete(radioId.value, it.id) }
        }
        if (pruneBeyond != null) {
            for ((index, row) in byIndex.toMap()) {
                if (index >= pruneBeyond.toLong()) {
                    deleteChannelMessages(radioId, row.toDTO().index)
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
        deleteChannelMessages(RadioId(row.radioId), row.toDTO().index)
        return row.copy(name = info.name, secret = info.secret, lastMessageDate = null, unreadCount = 0, unreadMentionCount = 0)
    }

    override suspend fun deleteChannel(key: EntityKey) = channelWrite("deleteChannel", flushBeforeRollback = true) {
        val row = database.channels().byId(key.radioId.value, key.id) ?: return@channelWrite
        deleteChannelMessages(key.radioId, row.toDTO().index)
        database.channels().delete(key.radioId.value, key.id)
        save()
    }

    override suspend fun deleteMessagesForChannel(radioId: RadioId, channelIndex: UByte) =
        channelWrite("deleteMessagesForChannel", flushBeforeRollback = true) {
            deleteChannelMessages(radioId, channelIndex)
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
        channelWrite("clearChannelUnreadCountByIndex") {
            val row = database.channels().forIndex(radioId.value, index.toLong()).firstOrNull() ?: return@channelWrite
            database.channels().upsert(row.copy(unreadCount = 0))
            save()
        }

    override suspend fun setChannelMuted(key: EntityKey, isMuted: Boolean) =
        setChannelNotificationLevel(key, if (isMuted) NotificationLevel.MUTED else NotificationLevel.ALL)

    override suspend fun setChannelNotificationLevel(key: EntityKey, level: NotificationLevel) =
        mutateChannel("setChannelNotificationLevel", key, required = true) { it.copy(notificationLevelRawValue = level.rawValue) }

    override suspend fun setSessionNotificationLevel(key: EntityKey, level: NotificationLevel) =
        rooms.setSessionNotificationLevel(key, level)

    override suspend fun setChannelFavorite(key: EntityKey, isFavorite: Boolean) =
        mutateChannel("setChannelFavorite", key, required = true) { it.copy(isFavorite = isFavorite) }

    override suspend fun setChannelFloodScope(key: EntityKey, floodScope: ChannelFloodScope) =
        channelWrite("setChannelFloodScope") {
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
    ) = channelWrite(operation) {
        val row = database.channels().byId(key.radioId.value, key.id)
        if (row == null) {
            if (required) throw PersistenceStoreException(PersistenceStoreError.ChannelNotFound)
        } else {
            database.channels().upsert(mutation(row))
            save()
        }
    }
}
