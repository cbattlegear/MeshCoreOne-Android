// AndroidOnly: WP-318 Resource-backed implementations of the feature text seams (localization pipeline strings).
package com.meshcoreone.android.feature.settings.app.backup.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.feature.settings.app.backup.BackupModelKind
import com.meshcoreone.android.feature.settings.app.backup.BackupStrings
import com.meshcoreone.android.feature.settings.R

class ResourceBackupStrings(private val resources: Resources) : BackupStrings {
    override fun importRequiresDisconnect() = resources.getString(R.string.backup_import_requires_disconnect)
    override fun modelLabel(kind: BackupModelKind): String = resources.getString(
        when (kind) {
            BackupModelKind.MESSAGES -> S.settingsBackupImportPreviewMessages
            BackupModelKind.CONTACTS -> S.settingsBackupImportPreviewContacts
            BackupModelKind.CHANNELS -> S.settingsBackupImportPreviewChannels
            BackupModelKind.DEVICES -> S.settingsBackupImportPreviewDevices
            BackupModelKind.ROOM_MESSAGES -> S.settingsBackupImportPreviewRoomMessages
            BackupModelKind.REACTIONS -> S.settingsBackupImportPreviewReactions
            BackupModelKind.MESSAGE_REPEATS -> S.settingsBackupImportPreviewMessageRepeats
            BackupModelKind.SAVED_TRACE_PATHS -> S.settingsBackupImportPreviewSavedPaths
            BackupModelKind.REMOTE_NODE_SESSIONS -> S.settingsBackupImportPreviewRemoteNodeSessions
            BackupModelKind.BLOCKED_CHANNEL_SENDERS -> S.settingsBackupImportPreviewBlockedSenders
            BackupModelKind.NODE_STATUS_SNAPSHOTS -> S.settingsBackupImportPreviewNodeStatusSnapshots
            BackupModelKind.DISCOVERED_NODES -> S.settingsBackupImportPreviewDiscoveredNodes
        },
    )

    override fun invalidFile() = resources.getString(S.settingsBackupErrorInvalidFile)
    override fun pickerUnavailable() = resources.getString(R.string.node_config_picker_unavailable)
    override fun documentUnavailable() = resources.getString(R.string.node_config_document_unavailable)
    override fun fileTooLarge(actualMegabytes: Long, maxMegabytes: Long) =
        S.settingsBackupErrorFileTooLarge(resources, actualMegabytes.toInt(), maxMegabytes.toInt())
    override fun decompressedTooLarge(maxMegabytes: Long) = S.settingsBackupErrorDecompressedTooLarge(resources, maxMegabytes.toInt())
    override fun unsupportedVersion(found: Long, maxSupported: Long) =
        S.settingsBackupErrorUnsupportedVersion(resources, found.toInt(), maxSupported.toInt())
    override fun corruptedManifest() = resources.getString(S.settingsBackupErrorCorruptedManifest)
    override fun exportFailed(underlying: String) = S.settingsBackupErrorExportFailed(resources, underlying)
    override fun importFailed(underlying: String) = S.settingsBackupErrorImportFailed(resources, underlying)
    override fun genericFailure(failure: Throwable) = failure.message ?: failure.javaClass.simpleName
    override fun defaultExportFilename(timestamp: String) = S.settingsBackupExportDefaultFilename(resources, timestamp)
    override fun importSuccessTitle() = resources.getString(S.settingsBackupImportSuccessTitle)
    override fun nothingToImportTitle() = resources.getString(S.settingsBackupImportNothingToImportTitle)
    override fun nothingToImportSubtitle() = resources.getString(S.settingsBackupImportNothingToImportSubtitle)
    override fun subtitleAdded(count: Int) = S.settingsBackupImportSuccessSubtitleAdded(resources, count)
    override fun subtitleRefreshed(count: Int) = S.settingsBackupImportSuccessSubtitleRefreshed(resources, count)
    override fun alreadyHereSummary(count: Int) = S.settingsBackupImportSuccessAlreadyHereSummary(resources, count)
    override fun alreadyHereRefreshed(count: Int) = S.settingsBackupImportSuccessAlreadyHereRefreshed(resources, count)
    override fun droppedSummary(count: Int) = S.settingsBackupImportSuccessDroppedSummary(resources, count)
    override fun droppedFooterChannels() = resources.getString(S.settingsBackupImportSuccessDroppedFooter)
    override fun droppedFooterDiscoveredNodes(cap: Int) = S.settingsBackupImportSuccessDroppedFooterDiscoveredNodes(resources, cap)
    override fun droppedFooterMixed(cap: Int) = S.settingsBackupImportSuccessDroppedFooterMixed(resources, cap)
}

/** Recreated when the locale/configuration changes so copy never goes stale. */
@Composable
fun rememberBackupStrings(): BackupStrings {
    val configuration = LocalConfiguration.current
    val resources = LocalContext.current.resources
    return remember(configuration, resources) { ResourceBackupStrings(resources) }
}
