// PortedFrom: MC1Services/Sources/MC1Services/Protocols/PersistenceStoreProtocol.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/MC1Services.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.toDTO
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.data.backup.snapshotBackupKey
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

/** The supplied scope and database are process-owned, never a radio-connection generation. */
class RoomPersistenceStore private constructor(
    private val context: RoomRepositoryContext,
    private val discovered: DiscoveredNodeRepository = DiscoveredNodeRepository(context),
    private val devices: DeviceRepository = DeviceRepository(context),
    private val rooms: RoomRepository = RoomRepository(context),
    private val contacts: ContactRepository = ContactRepository(context, discovered),
    private val channels: ChannelRepository = ChannelRepository(context, rooms),
    private val pending: PendingSendRepository = PendingSendRepository(context),
    private val messages: MessageRepository = MessageRepository(context, pending),
    private val diagnostics: DiagnosticRepository = DiagnosticRepository(context),
    private val metadata: MetadataRepository = MetadataRepository(context),
) : PersistenceStoreProtocol,
    DevicePersisting by devices, ContactPersisting by contacts, ChannelPersisting by channels,
    MessagePersisting by messages, HeardRepeatPersisting by messages, ReactionPersisting by messages,
    FailedSendPersisting by messages, RoomPersisting by rooms, DiscoveredNodePersisting by discovered,
    TracePathPersisting by diagnostics, DebugLogPersisting by diagnostics, LinkPreviewPersisting by diagnostics,
    RxLogPersisting by diagnostics, NodeSnapshotPersisting by diagnostics, MetadataPersisting by metadata {
    constructor(
        database: MeshCoreDatabase,
        scope: CoroutineScope,
        clock: Clock = Clock.systemUTC(),
        reporter: RepositoryIssueReporter = AndroidRepositoryIssueReporter,
    ) : this(RoomRepositoryContext(database, clock, scope, reporter))

    override suspend fun fetchMessage(deduplicationKey: String, radioId: RadioId): MessageDTO? =
        messages.fetchMessage(deduplicationKey, radioId)
    override suspend fun fetchContactPublicKeys(radioId: RadioId): SnapshotSet<Bytes> =
        contacts.fetchContactPublicKeys(radioId)
    override suspend fun setSessionNotificationLevel(key: EntityKey, level: NotificationLevel) =
        rooms.setSessionNotificationLevel(key, level)

    /**
     * Android-only WP-202 safety adaptation for simulator seeding after backup restore.
     * The simulator keeps its canonical identity/configuration; saved preferences survive re-seeding.
     */
    suspend fun saveSimulatorDevice(dto: DeviceDTO) = context.write("saveSimulatorDevice") {
        val current = database.devices().byId(dto.id)
        if (dto.connectionMethods.isNotEmpty() || current?.let {
                it.radioId != dto.radioId.value || it.publicKey != dto.publicKey || it.connectionMethods.isNotEmpty()
            } == true) {
            throw PersistenceStoreException(PersistenceStoreError.InvalidData)
        }
        if (database.devices().forRadio(dto.radioId.value).any { it.publicKey != dto.publicKey }) {
            throw PersistenceStoreException(PersistenceStoreError.InvalidData)
        }
        val candidates = database.devices().forPublicKey(dto.publicKey).filter {
            it.radioId == dto.radioId.value && it.id != dto.id
        }
        if (candidates.size > 1 || candidates.any { it.isActive || it.connectionMethods.isNotEmpty() }) {
            throw PersistenceStoreException(PersistenceStoreError.InvalidData)
        }
        val restored = candidates.singleOrNull()
        val saved = current?.toDTO()
        val imported = restored?.toDTO()
        val merged = dto.copy(
            defaultFloodScopeName = saved?.defaultFloodScopeName ?: imported?.defaultFloodScopeName ?: dto.defaultFloodScopeName,
            preRepeatFrequency = saved?.preRepeatFrequency ?: imported?.preRepeatFrequency ?: dto.preRepeatFrequency,
            preRepeatBandwidth = saved?.preRepeatBandwidth ?: imported?.preRepeatBandwidth ?: dto.preRepeatBandwidth,
            preRepeatSpreadingFactor = saved?.preRepeatSpreadingFactor ?: imported?.preRepeatSpreadingFactor ?: dto.preRepeatSpreadingFactor,
            preRepeatCodingRate = saved?.preRepeatCodingRate ?: imported?.preRepeatCodingRate ?: dto.preRepeatCodingRate,
            lastContactSync = maxOf(dto.lastContactSync, saved?.lastContactSync ?: 0u, imported?.lastContactSync ?: 0u),
            ocvPreset = saved?.ocvPreset ?: imported?.ocvPreset ?: dto.ocvPreset,
            appliedRadioPresetID = saved?.appliedRadioPresetID ?: imported?.appliedRadioPresetID ?: dto.appliedRadioPresetID,
            customOCVArrayString = saved?.customOCVArrayString ?: imported?.customOCVArrayString ?: dto.customOCVArrayString,
            knownRegions = (saved?.knownRegions.orEmpty() + imported?.knownRegions.orEmpty() + dto.knownRegions).distinct().snapshot(),
        )
        database.devices().upsert(merged.toEntity())
        if (restored != null) database.devices().delete(restored.id)
        save()
    }

    suspend fun existingNodeStatusSnapshotKeys(): SnapshotSet<Pair<Bytes, Long>> =
        context.read("existingNodeStatusSnapshotKeys") {
            database.nodeSnapshots().backupAll().map { snapshotBackupKey(it.toDTO()) }.snapshotSet()
        }

    /**
     * Rechecks the keys under the same Room transaction that inserts the rows. The optional node check preserves
     * the simulator's "seed history only when this node has none" rule if another writer raced its initial read.
     */
    suspend fun batchInsertNodeStatusSnapshots(
        dtos: SnapshotList<NodeStatusSnapshotDTO>,
        existingKeys: Set<Pair<Bytes, Long>>,
        skipNodesWithHistory: Boolean = false,
    ): Pair<Long, Long> = context.write("batchInsertNodeStatusSnapshots") {
        val persisted = database.nodeSnapshots().backupAll().map { it.toDTO() }
        val existingNodes = if (skipNodesWithHistory) persisted.mapTo(hashSetOf()) { it.nodePublicKey } else emptySet()
        val known = existingKeys.toMutableSet().apply { addAll(persisted.map(::snapshotBackupKey)) }
        var inserted = 0L
        var skipped = 0L
        for (dto in dtos) {
            currentCoroutineContext().ensureActive()
            if (dto.nodePublicKey in existingNodes || !known.add(snapshotBackupKey(dto))) {
                skipped = Math.incrementExact(skipped)
            } else {
                database.nodeSnapshots().insert(dto.toEntity())
                inserted = Math.incrementExact(inserted)
            }
        }
        save()
        inserted to skipped
    }

    override suspend fun warmUp() {
        context.read("warmUp") { database.devices().count() }
        try {
            pending.purgeOrphanPendingSends()
        } catch (cause: PersistenceStoreException) {
            // The source reports each independent maintenance failure and still attempts the other purge.
        }
        try {
            pending.purgeLegacyAttemptCountRows()
        } catch (cause: PersistenceStoreException) {
            // The operation has already reported its typed cause without recreating the database.
        }
    }

    suspend fun close() { context.close() }

    internal suspend fun <T> withBackupSnapshot(block: suspend (MeshCoreDatabase, Clock) -> T): T =
        context.read("fetchBackupExportSnapshot") { block(database, clock) }

    internal suspend fun <T> withBackupRestore(
        afterCommit: () -> Unit = {},
        block: suspend (MeshCoreDatabase, Clock) -> T,
    ): T = context.restoreBackup(afterCommit) { block(database, clock) }

    fun observeContacts(radioId: RadioId): Flow<SnapshotList<ContactDTO>> =
        context.observe("observeContacts", context.database.contacts().observe(radioId.value)) {
            it.map { row -> row.toDTO() }.snapshot()
        }

    fun observeChannels(radioId: RadioId): Flow<SnapshotList<ChannelDTO>> =
        context.observe("observeChannels", context.database.channels().observe(radioId.value)) {
            it.map { row -> row.toDTO() }.snapshot()
        }

    fun observeContactMessages(contact: EntityKey): Flow<SnapshotList<MessageDTO>> =
        context.observe("observeContactMessages", context.database.messages().observeContact(contact.radioId.value, contact.id)) {
            MessageDTO.reorderSameSenderClusters(it.asReversed().map { row -> row.toDTO() })
        }

    internal val rxInitiatedSaveCount: Long get() = context.rxInitiatedSaveCount

    companion object {
        const val MAX_DISCOVERED_NODES = DiscoveredNodeRepository.MAX_DISCOVERED_NODES
        /** Closes a Room database when process-store construction failed before ownership transfer. */
        fun closeUntransferredDatabase(database: MeshCoreDatabase) {
            database.close()
        }

        fun clampedPhoneClockTimestamp(stamp: UInt, now: java.time.Instant): UInt =
            com.meshcoreone.android.core.data.repository.clampedPhoneClockTimestamp(stamp, now)
    }
}

typealias PersistenceStore = RoomPersistenceStore
typealias DataStore = RoomPersistenceStore
