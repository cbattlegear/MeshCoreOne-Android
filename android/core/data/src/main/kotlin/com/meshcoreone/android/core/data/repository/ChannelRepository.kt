// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/ChannelPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Channels.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class ChannelRepository(
    private val context: RoomRepositoryContext,
    private val rooms: RoomRepository,
) : ChannelPersisting {
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
        context.write("saveChannelInfo", flushBeforeRollback = true) {
            val existing = database.channels().forIndex(radioId.value, info.index.toLong()).firstOrNull()
            val row = if (existing != null) applyingRadioInfo(existing, info) else ChannelDTO.fromInfo(radioId, info).toEntity()
            database.channels().upsert(row)
            save()
            row.id
        }

    override suspend fun saveChannel(dto: ChannelDTO) = context.write("saveChannel") {
        val identified = database.channels().byId(dto.radioId.value, dto.id)
        val occupants = database.channels().forIndex(dto.radioId.value, identified?.index ?: dto.index.toLong())
        if (occupants.size > 1) throw PersistenceStoreException(PersistenceStoreError.InvalidData)
        // A replayed seed can have a different UUID after restore; it must not replace imported state.
        val existing = identified ?: occupants.singleOrNull()
        if (identified == null && existing != null &&
            (existing.secret != dto.secret || existing.isEnabled != dto.isEnabled)) {
            throw PersistenceStoreException(PersistenceStoreError.InvalidData)
        }
        if (identified == null && existing != null) return@write
        database.channels().upsert(identified?.applying(dto) ?: dto.toEntity())
        save()
    }

    override suspend fun batchSaveChannels(
        radioId: RadioId,
        configured: SnapshotList<ChannelInfo>,
        unconfiguredIndices: SnapshotList<UByte>,
        pruneBeyond: UByte?,
    ): SnapshotList<ChannelDTO> = context.write("batchSaveChannels", flushBeforeRollback = true) {
        val byIndex = LinkedHashMap<Long, ChannelEntity>()
        for (row in database.channels().forRadio(radioId.value)) byIndex.putIfAbsent(row.index, row)
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

    override suspend fun deleteChannel(key: EntityKey) = context.write("deleteChannel", flushBeforeRollback = true) {
        val row = database.channels().byId(key.radioId.value, key.id) ?: return@write
        deleteChannelMessages(key.radioId, row.toDTO().index)
        database.channels().delete(key.radioId.value, key.id)
        save()
    }

    override suspend fun deleteMessagesForChannel(radioId: RadioId, channelIndex: UByte) =
        context.write("deleteMessagesForChannel", flushBeforeRollback = true) {
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
        context.write("clearChannelUnreadCountByIndex") {
            val row = database.channels().forIndex(radioId.value, index.toLong()).firstOrNull() ?: return@write
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
        context.write("setChannelFloodScope") {
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

    /** Simulator fixtures may insert an empty slot but must never replace a persisted occupant. */
    suspend fun saveSimulatorChannel(dto: ChannelDTO): Unit = context.write("saveSimulatorChannel") {
        applySimulatorChannel(prepareSimulatorChannel(dto))
        Unit
    }

    private suspend fun mutateChannel(
        operation: String, key: EntityKey, required: Boolean = false, mutation: (ChannelEntity) -> ChannelEntity,
    ) = context.write(operation) {
        val row = database.channels().byId(key.radioId.value, key.id)
        if (row == null) {
            if (required) throw PersistenceStoreException(PersistenceStoreError.ChannelNotFound)
        } else {
            database.channels().upsert(mutation(row))
            save()
        }
    }

    internal sealed interface SimulatorChannelSeedPlan {
        sealed interface Existing : SimulatorChannelSeedPlan
        sealed interface New : SimulatorChannelSeedPlan
    }

    private sealed class PreparedSimulatorChannelPlan(
        val transaction: RepositoryTransaction,
        val job: Job,
        val fixture: ChannelDTO,
        val row: ChannelEntity,
    ) : SimulatorChannelSeedPlan {
        class Existing(transaction: RepositoryTransaction, job: Job, fixture: ChannelDTO, row: ChannelEntity) :
            PreparedSimulatorChannelPlan(transaction, job, fixture, row), SimulatorChannelSeedPlan.Existing

        class New(transaction: RepositoryTransaction, job: Job, fixture: ChannelDTO, row: ChannelEntity) :
            PreparedSimulatorChannelPlan(transaction, job, fixture, row), SimulatorChannelSeedPlan.New
    }

    internal suspend fun RepositoryTransaction.prepareSimulatorChannel(dto: ChannelDTO): SimulatorChannelSeedPlan {
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        val job = coroutine[Job]
        if (job == null || !database.inTransaction()) {
            throw PersistenceStoreException(PersistenceStoreError.InvalidData)
        }
        val identified = database.channels().byId(dto.radioId.value, dto.id)
        val fixtureIdentities = database.channels().simulatorIdentityCohort(dto.radioId.value, dto.id)
        if (fixtureIdentities.size > 1 || fixtureIdentities.singleOrNull() != identified ||
            (identified != null && identified.index != dto.index.toLong())) {
            throw PersistenceStoreException(PersistenceStoreError.InvalidData)
        }
        val occupants = database.channels().simulatorSlotCohort(dto.radioId.value, dto.index.toLong())
        val existing = occupants.singleOrNull()
        if (occupants.size > 1 || (identified != null && identified != existing)) {
            throw PersistenceStoreException(PersistenceStoreError.InvalidData)
        }
        if (existing != null) {
            if (database.channels().byId(existing.radioId, existing.id) != existing ||
                database.channels().simulatorIdentityCohort(existing.radioId, existing.id).singleOrNull() != existing ||
                existing.secret != dto.secret || existing.isEnabled != dto.isEnabled) {
                throw PersistenceStoreException(PersistenceStoreError.InvalidData)
            }
            return PreparedSimulatorChannelPlan.Existing(this, job, dto, existing)
        }
        return PreparedSimulatorChannelPlan.New(this, job, dto, dto.toEntity())
    }

    internal suspend fun RepositoryTransaction.applySimulatorChannel(plan: SimulatorChannelSeedPlan): ChannelEntity {
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        if (plan !is PreparedSimulatorChannelPlan || plan.transaction !== this ||
            plan.job !== coroutine[Job] || !database.inTransaction()) {
            throw PersistenceStoreException(PersistenceStoreError.InvalidData)
        }
        val current = prepareSimulatorChannel(plan.fixture)
        if (current !is PreparedSimulatorChannelPlan ||
            (current is SimulatorChannelSeedPlan.Existing) != (plan is SimulatorChannelSeedPlan.Existing) ||
            current.row != plan.row) {
            throw PersistenceStoreException(
                PersistenceStoreError.SaveFailed("saveSimulatorChannel"),
                IllegalStateException("Simulator channel plan no longer matches the persisted occupant"),
            )
        }
        coroutine.ensureActive()
        if (plan is SimulatorChannelSeedPlan.Existing) return plan.row
        val inserted = plan.row
        database.channels().insert(inserted)
        val stored = database.channels().byId(inserted.radioId, inserted.id)
        val identities = database.channels().simulatorIdentityCohort(inserted.radioId, inserted.id)
        val occupants = database.channels().simulatorSlotCohort(inserted.radioId, inserted.index)
        if (stored == null || stored != inserted || identities.singleOrNull() != inserted ||
            occupants.singleOrNull() != inserted) {
            throw PersistenceStoreException(
                PersistenceStoreError.SaveFailed("saveSimulatorChannel"),
                IllegalStateException("Simulator channel insert did not persist the expected row"),
            )
        }
        coroutine.ensureActive()
        save()
        return stored
    }
}
