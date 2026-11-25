// PortedFrom: MC1/Views/Settings/ImportSuccessContent.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: the SwiftUI view's derived text/section rules as a pure function so they are JVM-testable.
package com.meshcoreone.android.feature.settings.app.backup

/** Mirror of `PersistenceStore.maxDiscoveredNodes` (the per-radio discover-list cap quoted in the dropped footer). */
const val MAX_DISCOVERED_NODES = 1000

data class CountRow(val kind: BackupModelKind, val label: String, val count: Int)

enum class ImportHeroIcon { SUCCESS, INFO }

data class ImportResultPresentation(
    val heroTitle: String,
    val heroSubtitle: String,
    val heroIcon: ImportHeroIcon,
    val addedRows: List<CountRow>,
    val skippedRows: List<CountRow>,
    val droppedRows: List<CountRow>,
    val alreadyHereSummary: String?,
    val alreadyHereRefreshed: String?,
    val droppedSummary: String?,
    val droppedFooter: String?,
    val hasRestoredChanges: Boolean,
) {
    val announcement: String get() = "$heroTitle. $heroSubtitle"
}

object ImportResultPresenter {
    /** Footer copy for the dropped section depends on which kinds were dropped. */
    fun droppedFooter(result: ImportResult, strings: BackupStrings, cap: Int = MAX_DISCOVERED_NODES): String {
        val channelDropped = result.counts(BackupModelKind.CHANNELS).dropped > 0
        val discoverDropped = result.counts(BackupModelKind.DISCOVERED_NODES).dropped > 0
        return when {
            channelDropped && discoverDropped -> strings.droppedFooterMixed(cap)
            discoverDropped -> strings.droppedFooterDiscoveredNodes(cap)
            else -> strings.droppedFooterChannels()
        }
    }

    fun present(result: ImportResult, strings: BackupStrings, cap: Int = MAX_DISCOVERED_NODES): ImportResultPresentation {
        val didAdd = result.totalInserted > 0
        val didMerge = result.totalMerged > 0
        val hasSkipped = result.totalSkipped > 0
        val hasDropped = result.totalDropped > 0
        val subtitle = when {
            didAdd -> strings.subtitleAdded(result.totalInserted)
            didMerge -> strings.subtitleRefreshed(result.totalMerged)
            else -> strings.nothingToImportSubtitle()
        }
        return ImportResultPresentation(
            heroTitle = if (result.hasRestoredChanges) strings.importSuccessTitle() else strings.nothingToImportTitle(),
            heroSubtitle = subtitle,
            heroIcon = if (result.hasRestoredChanges) ImportHeroIcon.SUCCESS else ImportHeroIcon.INFO,
            addedRows = if (didAdd) rows(result, strings) { it.inserted } else emptyList(),
            skippedRows = if (hasSkipped) rows(result, strings) { it.skipped } else emptyList(),
            droppedRows = if (hasDropped) rows(result, strings) { it.dropped } else emptyList(),
            alreadyHereSummary = if (hasSkipped) strings.alreadyHereSummary(result.totalSkipped) else null,
            alreadyHereRefreshed = if (hasSkipped && didMerge) strings.alreadyHereRefreshed(result.totalMerged) else null,
            droppedSummary = if (hasDropped) strings.droppedSummary(result.totalDropped) else null,
            droppedFooter = if (hasDropped) droppedFooter(result, strings, cap) else null,
            hasRestoredChanges = result.hasRestoredChanges,
        )
    }

    private fun rows(result: ImportResult, strings: BackupStrings, pick: (PerTypeCounts) -> Int): List<CountRow> =
        BackupModelKind.entries.mapNotNull { kind ->
            val count = pick(result.counts(kind))
            if (count > 0) CountRow(kind, strings.modelLabel(kind), count) else null
        }
}

/** Rows shown by the preview and export-success lists: only kinds with a nonzero declared count. */
fun manifestRows(manifest: BackupManifest, strings: BackupStrings): List<CountRow> =
    BackupModelKind.entries.mapNotNull { kind ->
        val count = manifest.count(kind)
        if (count > 0) CountRow(kind, strings.modelLabel(kind), count) else null
    }
