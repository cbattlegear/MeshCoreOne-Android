// PortedFrom: MC1Services/Sources/MC1Services/Services/RxLogService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: narrow consumer-side seams for the RX log service's store, heard-repeat sink and logging.
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.contracts.domain.ChannelPersisting
import com.meshcoreone.android.core.contracts.domain.ChannelRegionUpdate
import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.DevicePersisting
import com.meshcoreone.android.core.contracts.domain.DirectRegionUpdate
import com.meshcoreone.android.core.contracts.domain.DiscoveredNodePersisting
import com.meshcoreone.android.core.contracts.domain.RxLogDecryptionUpdate
import com.meshcoreone.android.core.contracts.domain.RxLogPersisting
import com.meshcoreone.android.core.contracts.domain.RxLogRegionUpdate
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.SnapshotMap
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID

/**
 * Exactly the persistence operations `RxLogService` performs. The source takes the whole
 * `PersistenceStoreProtocol`; [from] adapts any store that implements the contract slices.
 */
interface RxLogServiceStore {
    suspend fun fetchChannels(radioId: RadioId): SnapshotList<ChannelDTO>
    suspend fun fetchContactPublicKeysByPrefix(radioId: RadioId): SnapshotMap<UByte, SnapshotList<Bytes>>
    suspend fun fetchDevice(radioId: RadioId): DeviceDTO?
    suspend fun setInboundHopCount(radioId: RadioId, publicKey: Bytes, hopCount: Long, advertTimestamp: UInt?)
    suspend fun saveRxLogEntry(dto: RxLogEntryDTO)
    suspend fun fetchRxLogEntries(radioId: RadioId, limit: Long): SnapshotList<RxLogEntryDTO>
    suspend fun clearRxLogEntries(radioId: RadioId)
    suspend fun fetchRecentEntriesByDecryptStatus(
        radioId: RadioId, status: DecryptStatus, since: Instant,
    ): SnapshotList<RxLogEntryDTO>
    suspend fun batchUpdateRxLogDecryption(radioId: RadioId, updates: SnapshotList<RxLogDecryptionUpdate>)
    suspend fun fetchEntriesWithTransportCode(radioId: RadioId, limit: Long): SnapshotList<RxLogEntryDTO>
    suspend fun batchUpdateRxLogRegion(radioId: RadioId, updates: SnapshotList<RxLogRegionUpdate>)
    suspend fun batchUpdateChannelMessageRegion(
        radioId: RadioId, updates: SnapshotList<ChannelRegionUpdate>,
    ): SnapshotList<UUID>
    suspend fun batchUpdateDMMessageRegion(radioId: RadioId, updates: SnapshotList<DirectRegionUpdate>): SnapshotList<UUID>

    companion object {
        /** Adapts a full persistence store (for example `PersistenceStoreProtocol`) to this seam. */
        fun <S> from(store: S): RxLogServiceStore
            where S : RxLogPersisting, S : ChannelPersisting, S : ContactPersisting,
                  S : DevicePersisting, S : DiscoveredNodePersisting = ContractBackedRxLogStore(store, store, store, store, store)
    }
}

private class ContractBackedRxLogStore(
    private val rxLogs: RxLogPersisting,
    private val channels: ChannelPersisting,
    private val contacts: ContactPersisting,
    private val devices: DevicePersisting,
    private val nodes: DiscoveredNodePersisting,
) : RxLogServiceStore {
    override suspend fun fetchChannels(radioId: RadioId) = channels.fetchChannels(radioId)
    override suspend fun fetchContactPublicKeysByPrefix(radioId: RadioId) = contacts.fetchContactPublicKeysByPrefix(radioId)
    override suspend fun fetchDevice(radioId: RadioId) = devices.fetchDevice(radioId)
    override suspend fun setInboundHopCount(radioId: RadioId, publicKey: Bytes, hopCount: Long, advertTimestamp: UInt?) =
        nodes.setInboundHopCount(radioId, publicKey, hopCount, advertTimestamp)
    override suspend fun saveRxLogEntry(dto: RxLogEntryDTO) = rxLogs.saveRxLogEntry(dto)
    override suspend fun fetchRxLogEntries(radioId: RadioId, limit: Long) = rxLogs.fetchRxLogEntries(radioId, limit)
    override suspend fun clearRxLogEntries(radioId: RadioId) = rxLogs.clearRxLogEntries(radioId)
    override suspend fun fetchRecentEntriesByDecryptStatus(radioId: RadioId, status: DecryptStatus, since: Instant) =
        rxLogs.fetchRecentEntriesByDecryptStatus(radioId, status, since)
    override suspend fun batchUpdateRxLogDecryption(radioId: RadioId, updates: SnapshotList<RxLogDecryptionUpdate>) =
        rxLogs.batchUpdateRxLogDecryption(radioId, updates)
    override suspend fun fetchEntriesWithTransportCode(radioId: RadioId, limit: Long) =
        rxLogs.fetchEntriesWithTransportCode(radioId, limit)
    override suspend fun batchUpdateRxLogRegion(radioId: RadioId, updates: SnapshotList<RxLogRegionUpdate>) =
        rxLogs.batchUpdateRxLogRegion(radioId, updates)
    override suspend fun batchUpdateChannelMessageRegion(radioId: RadioId, updates: SnapshotList<ChannelRegionUpdate>) =
        rxLogs.batchUpdateChannelMessageRegion(radioId, updates)
    override suspend fun batchUpdateDMMessageRegion(radioId: RadioId, updates: SnapshotList<DirectRegionUpdate>) =
        rxLogs.batchUpdateDMMessageRegion(radioId, updates)
}

/**
 * The single `HeardRepeatsService` operation RX logging drives. Implemented by the heard-repeats
 * service port; `null` mirrors the source's optional injection.
 */
fun interface RxLogRepeatProcessing {
    suspend fun processForRepeats(entry: RxLogEntryDTO)
}

/**
 * Minimal logging seam replacing the source's file-private `PersistentLogger` instances
 * (categories `RxLogService` and `RxLogService.Region`). Messages mirror the source text.
 */
fun interface RxLogDiagnostics {
    fun log(level: DebugLogLevel, category: String, message: String)

    companion object {
        val NONE: RxLogDiagnostics = RxLogDiagnostics { _, _, _ -> }
    }
}

/** Stand-in for Swift's `error.localizedDescription` in log lines and export text. */
internal fun Throwable.rxLogErrorDescription(): String = message ?: javaClass.simpleName
