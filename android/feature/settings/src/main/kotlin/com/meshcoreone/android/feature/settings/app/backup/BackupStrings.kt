// AndroidOnly: WP-318 Feature-owned text seam so backup messages and result copy stay JVM-testable; ui/ResourceBackupStrings backs it with l10n.
package com.meshcoreone.android.feature.settings.app.backup

/** Localized copy the pure backup logic needs. */
interface BackupStrings {
    fun importRequiresDisconnect(): String
    fun modelLabel(kind: BackupModelKind): String
    fun invalidFile(): String
    fun fileTooLarge(actualMegabytes: Long, maxMegabytes: Long): String
    fun decompressedTooLarge(maxMegabytes: Long): String
    fun unsupportedVersion(found: Long, maxSupported: Long): String
    fun corruptedManifest(): String
    fun exportFailed(underlying: String): String
    fun importFailed(underlying: String): String

    /** Message for a failure that is not a typed backup error (platform text, never a stack trace). */
    fun genericFailure(failure: Throwable): String
    fun defaultExportFilename(timestamp: String): String
    fun importSuccessTitle(): String
    fun nothingToImportTitle(): String
    fun nothingToImportSubtitle(): String
    fun subtitleAdded(count: Int): String
    fun subtitleRefreshed(count: Int): String
    fun alreadyHereSummary(count: Int): String
    fun alreadyHereRefreshed(count: Int): String
    fun droppedSummary(count: Int): String
    fun droppedFooterChannels(): String
    fun droppedFooterDiscoveredNodes(cap: Int): String
    fun droppedFooterMixed(cap: Int): String
}
