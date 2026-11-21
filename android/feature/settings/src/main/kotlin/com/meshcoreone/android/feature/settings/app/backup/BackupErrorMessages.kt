// PortedFrom: MC1/Views/Settings/AppBackupError+Localized.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: also maps the document-layer failures (unavailable URI, revoked grant, low space).
package com.meshcoreone.android.feature.settings.app.backup

import com.meshcoreone.android.core.model.AppBackupError
import com.meshcoreone.android.core.model.AppBackupException

private const val BYTES_PER_MEGABYTE = 1_048_576L

/** User-facing text for any backup failure; typed errors use the localized copy, others the platform detail. */
fun backupUserFacingMessage(failure: Throwable, strings: BackupStrings): String = when (failure) {
    is AppBackupException -> backupErrorMessage(failure.error, strings)
    is BackupDocumentException.PickerUnavailable, is BackupDocumentException.UriUnavailable,
    is BackupDocumentException.PermissionRevoked -> strings.invalidFile()
    else -> strings.genericFailure(failure)
}

private fun backupErrorMessage(error: AppBackupError, strings: BackupStrings): String = when (error) {
    AppBackupError.InvalidFile -> strings.invalidFile()
    is AppBackupError.FileTooLarge ->
        strings.fileTooLarge(error.actualBytes / BYTES_PER_MEGABYTE, error.maxBytes / BYTES_PER_MEGABYTE)
    is AppBackupError.DecompressedTooLarge -> strings.decompressedTooLarge(error.maxBytes / BYTES_PER_MEGABYTE)
    is AppBackupError.UnsupportedVersion -> strings.unsupportedVersion(error.found, error.maxSupported)
    AppBackupError.CorruptedManifest -> strings.corruptedManifest()
    is AppBackupError.ExportFailed -> strings.exportFailed(backupUserFacingMessage(error.underlying, strings))
    is AppBackupError.ImportFailed -> strings.importFailed(backupUserFacingMessage(error.underlying, strings))
}
