// PortedFrom: MC1Services/Sources/MC1Services/Services/AppBackupEnvelope.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Settings/BackupModelKind+Label.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: feature-owned value types (the envelope codec and atomic restore live behind BackupEngine); every update returns a copy.
package com.meshcoreone.android.feature.settings.app.backup

import java.time.Instant
import java.util.UUID

/** Backup model kinds in the Swift `CaseIterable` order; the UI iterates this order. */
enum class BackupModelKind {
    MESSAGES, CONTACTS, CHANNELS, DEVICES, ROOM_MESSAGES, REACTIONS, MESSAGE_REPEATS, SAVED_TRACE_PATHS,
    REMOTE_NODE_SESSIONS, BLOCKED_CHANNEL_SENDERS, NODE_STATUS_SNAPSHOTS, DISCOVERED_NODES,
}

/** Insert/merge/skip/dropped counts for a single model type. `dropped` rows could not be restored at all. */
data class PerTypeCounts(val inserted: Int = 0, val merged: Int = 0, val skipped: Int = 0, val dropped: Int = 0) {
    operator fun plus(other: PerTypeCounts) = PerTypeCounts(
        inserted + other.inserted, merged + other.merged, skipped + other.skipped, dropped + other.dropped,
    )

    companion object { val ZERO = PerTypeCounts() }
}

/** Declared counts per model type (the manifest shown in the preview and on export success). */
data class BackupManifest(val counts: Map<BackupModelKind, Int> = emptyMap()) {
    fun count(kind: BackupModelKind): Int = counts[kind] ?: 0

    companion object {
        fun of(vararg entries: Pair<BackupModelKind, Int>) = BackupManifest(entries.toMap())
    }
}

/** What the preview needs from a parsed backup; the engine keeps the concrete envelope behind it. */
interface ParsedBackup {
    val exportDate: Instant
    val appVersion: String
    val appBuild: String
    val manifest: BackupManifest
}

/** Bytes ready to save plus the manifest summarising them. */
class BackupExport(val data: ByteArray, val manifest: BackupManifest)

/**
 * Per-kind restore outcome. Storage is a kind-keyed map so totals and UI row iteration cannot drift
 * when a kind is added; [record] returns a copy.
 */
data class ImportResult(
    val counts: Map<BackupModelKind, PerTypeCounts> = BackupModelKind.entries.associateWith { PerTypeCounts.ZERO },
    val settingsRestored: Boolean = false,
    /** Local channel slots (by radio id) whose occupancy changed; drafts typed against them must be cleared. */
    val channelSlotsAffectedByImport: Map<UUID, Set<Int>> = emptyMap(),
) {
    fun counts(kind: BackupModelKind): PerTypeCounts = counts[kind] ?: PerTypeCounts.ZERO

    fun record(kind: BackupModelKind, inserted: Int = 0, merged: Int = 0, skipped: Int = 0, dropped: Int = 0): ImportResult =
        copy(counts = counts + (kind to (counts(kind) + PerTypeCounts(inserted, merged, skipped, dropped))))

    val totalInserted: Int get() = counts.values.sumOf { it.inserted }
    val totalMerged: Int get() = counts.values.sumOf { it.merged }
    val totalSkipped: Int get() = counts.values.sumOf { it.skipped }
    val totalDropped: Int get() = counts.values.sumOf { it.dropped }
    val totalRestoredRecordCount: Int get() = totalInserted + totalMerged
    val hasRestoredChanges: Boolean get() = totalRestoredRecordCount > 0 || settingsRestored
}
