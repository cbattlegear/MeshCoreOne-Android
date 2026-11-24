// PortedFrom: MC1Services/Sources/MC1Services/Services/AppBackupEnvelope.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/RestoreOutcome.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.model.*
import java.time.Instant

enum class BackupModelKind(val arrayKey: String, val countKey: String) {
    MESSAGES("messages", "messageCount"),
    CONTACTS("contacts", "contactCount"),
    CHANNELS("channels", "channelCount"),
    DEVICES("devices", "deviceCount"),
    ROOM_MESSAGES("roomMessages", "roomMessageCount"),
    REACTIONS("reactions", "reactionCount"),
    MESSAGE_REPEATS("messageRepeats", "messageRepeatCount"),
    SAVED_TRACE_PATHS("savedTracePaths", "savedTracePathCount"),
    REMOTE_NODE_SESSIONS("remoteNodeSessions", "remoteNodeSessionCount"),
    BLOCKED_CHANNEL_SENDERS("blockedChannelSenders", "blockedChannelSenderCount"),
    NODE_STATUS_SNAPSHOTS("nodeStatusSnapshots", "nodeStatusSnapshotCount"),
    DISCOVERED_NODES("discoveredNodes", "discoveredNodeCount"),
}

data class BackupManifest(
    val deviceCount: Long = 0,
    val contactCount: Long = 0,
    val channelCount: Long = 0,
    val messageCount: Long = 0,
    val messageRepeatCount: Long = 0,
    val reactionCount: Long = 0,
    val roomMessageCount: Long = 0,
    val remoteNodeSessionCount: Long = 0,
    val savedTracePathCount: Long = 0,
    val blockedChannelSenderCount: Long = 0,
    val nodeStatusSnapshotCount: Long = 0,
    val discoveredNodeCount: Long = 0,
) {
    fun count(kind: BackupModelKind): Long = when (kind) {
        BackupModelKind.DEVICES -> deviceCount
        BackupModelKind.CONTACTS -> contactCount
        BackupModelKind.CHANNELS -> channelCount
        BackupModelKind.MESSAGES -> messageCount
        BackupModelKind.MESSAGE_REPEATS -> messageRepeatCount
        BackupModelKind.REACTIONS -> reactionCount
        BackupModelKind.ROOM_MESSAGES -> roomMessageCount
        BackupModelKind.REMOTE_NODE_SESSIONS -> remoteNodeSessionCount
        BackupModelKind.SAVED_TRACE_PATHS -> savedTracePathCount
        BackupModelKind.BLOCKED_CHANNEL_SENDERS -> blockedChannelSenderCount
        BackupModelKind.NODE_STATUS_SNAPSHOTS -> nodeStatusSnapshotCount
        BackupModelKind.DISCOVERED_NODES -> discoveredNodeCount
    }

    fun validate(envelope: AppBackupEnvelope): Boolean = this == from(envelope)

    companion object {
        fun from(envelope: AppBackupEnvelope): BackupManifest = BackupManifest(
            envelope.devices.size.toLong(), envelope.contacts.size.toLong(), envelope.channels.size.toLong(),
            envelope.messages.size.toLong(), envelope.messageRepeats.size.toLong(), envelope.reactions.size.toLong(),
            envelope.roomMessages.size.toLong(), envelope.remoteNodeSessions.size.toLong(),
            envelope.savedTracePaths.size.toLong(), envelope.blockedChannelSenders.size.toLong(),
            envelope.nodeStatusSnapshots.size.toLong(), envelope.discoveredNodes.size.toLong(),
        )
    }
}

data class AppBackupEnvelope(
    val version: Long = BackupContract.LEGACY_VERSION,
    val exportDate: Instant,
    val appVersion: String,
    val appBuild: String,
    val manifest: BackupManifest = BackupManifest(),
    val devices: SnapshotList<DeviceDTO> = SnapshotList.empty(),
    val contacts: SnapshotList<ContactDTO> = SnapshotList.empty(),
    val channels: SnapshotList<ChannelDTO> = SnapshotList.empty(),
    val messages: SnapshotList<MessageDTO> = SnapshotList.empty(),
    val messageRepeats: SnapshotList<MessageRepeatDTO> = SnapshotList.empty(),
    val reactions: SnapshotList<ReactionDTO> = SnapshotList.empty(),
    val roomMessages: SnapshotList<RoomMessageDTO> = SnapshotList.empty(),
    val remoteNodeSessions: SnapshotList<RemoteNodeSessionDTO> = SnapshotList.empty(),
    val savedTracePaths: SnapshotList<SavedTracePathDTO> = SnapshotList.empty(),
    val blockedChannelSenders: SnapshotList<BlockedChannelSenderDTO> = SnapshotList.empty(),
    val nodeStatusSnapshots: SnapshotList<NodeStatusSnapshotDTO> = SnapshotList.empty(),
    val discoveredNodes: SnapshotList<DiscoveredNodeDTO> = SnapshotList.empty(),
    val userDefaults: BackupUserDefaults? = null,
) {
    fun withActualManifest(): AppBackupEnvelope = copy(manifest = BackupManifest.from(this))

    fun validate() {
        validateVersionAndOwnership()
        if (!manifest.validate(this)) throw AppBackupException(AppBackupError.CorruptedManifest)
    }

    internal fun validateVersionAndOwnership() {
        BackupContract.validateVersion(version)
        if (version == BackupContract.LEGACY_VERSION && nodeStatusSnapshots.any { it.selfRadioID != null }) {
            throw AppBackupException(AppBackupError.InvalidFile)
        }
    }
}

data class PerTypeCounts(
    val inserted: Long = 0,
    val merged: Long = 0,
    val skipped: Long = 0,
    val dropped: Long = 0,
) {
    internal fun adding(inserted: Long, merged: Long, skipped: Long, dropped: Long): PerTypeCounts = PerTypeCounts(
        Math.addExact(this.inserted, inserted), Math.addExact(this.merged, merged),
        Math.addExact(this.skipped, skipped), Math.addExact(this.dropped, dropped),
    )
}

data class ImportResult(
    val counts: SnapshotMap<BackupModelKind, PerTypeCounts> =
        BackupModelKind.entries.associateWith { PerTypeCounts() }.snapshotMap(),
    val userDefaultsRestored: Boolean = false,
    val channelSlotsAffectedByImport: SnapshotMap<RadioId, SnapshotSet<UByte>> = emptyMap<RadioId, SnapshotSet<UByte>>().snapshotMap(),
) {
    val totalInserted: Long get() = total { it.inserted }
    val totalMerged: Long get() = total { it.merged }
    val totalSkipped: Long get() = total { it.skipped }
    val totalDropped: Long get() = total { it.dropped }
    val totalRestoredRecordCount: Long get() = Math.addExact(totalInserted, totalMerged)
    val hasRestoredChanges: Boolean get() = totalRestoredRecordCount > 0 || userDefaultsRestored

    private fun total(value: (PerTypeCounts) -> Long): Long = counts.values.fold(0L) { sum, count ->
        Math.addExact(sum, value(count))
    }
}

internal class ImportAccounting {
    private val counts = BackupModelKind.entries.associateWithTo(linkedMapOf()) { PerTypeCounts() }
    private val affected = linkedMapOf<RadioId, MutableSet<UByte>>()

    fun record(kind: BackupModelKind, inserted: Long = 0, merged: Long = 0, skipped: Long = 0, dropped: Long = 0) {
        counts[kind] = counts.getValue(kind).adding(inserted, merged, skipped, dropped)
    }

    fun affectedSlot(radioId: RadioId, index: UByte) { affected.getOrPut(radioId) { linkedSetOf() }.add(index) }

    fun result(): ImportResult = ImportResult(
        counts.snapshotMap(),
        channelSlotsAffectedByImport = affected.mapValues { SnapshotSet(it.value) }.snapshotMap(),
    )
}

enum class RestoreOutcome { COMPLETED, CANCELLED }
