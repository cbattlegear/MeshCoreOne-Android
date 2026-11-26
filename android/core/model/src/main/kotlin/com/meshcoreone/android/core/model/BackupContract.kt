// PortedFrom: MC1Services/Sources/MC1Services/Errors/AppBackupError.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/AppBackupEnvelope.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Wire constraints/errors only; WP-203 owns the envelope codec and atomic restore.
package com.meshcoreone.android.core.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

object BackupContract {
    const val LEGACY_VERSION = 1L
    const val CURRENT_VERSION = 2L
    const val MAX_COMPRESSED_BYTES = 50L * 1_048_576
    const val MAX_EXPANDED_BYTES = 512L * 1_048_576
    val modelArrayKeys = SnapshotList.of(
        "devices", "contacts", "channels", "messages", "messageRepeats", "reactions",
        "roomMessages", "remoteNodeSessions", "savedTracePaths", "blockedChannelSenders",
        "nodeStatusSnapshots", "discoveredNodes",
    )
    val legacyOptionalArrayKeys = SnapshotSet(listOf("discoveredNodes"))

    fun validateVersion(version: Long) {
        if (version > CURRENT_VERSION) {
            throw AppBackupException(AppBackupError.UnsupportedVersion(version, CURRENT_VERSION))
        }
        if (version < LEGACY_VERSION) throw AppBackupException(AppBackupError.InvalidFile)
    }

    fun validateCompressedSize(actualBytes: Long) {
        require(actualBytes >= 0)
        if (actualBytes > MAX_COMPRESSED_BYTES) {
            throw AppBackupException(AppBackupError.FileTooLarge(actualBytes, MAX_COMPRESSED_BYTES))
        }
    }
}

sealed interface AppBackupError {
    data object InvalidFile : AppBackupError
    data class FileTooLarge(val actualBytes: Long, val maxBytes: Long) : AppBackupError
    data class DecompressedTooLarge(val maxBytes: Long) : AppBackupError
    data class UnsupportedVersion(val found: Long, val maxSupported: Long) : AppBackupError
    data object CorruptedManifest : AppBackupError
    data class ExportFailed(val underlying: Throwable) : AppBackupError
    data class ImportFailed(val underlying: Throwable) : AppBackupError
}

class AppBackupException(val error: AppBackupError, cause: Throwable? = null) :
    Exception(
        error.javaClass.simpleName,
        cause ?: when (error) {
            is AppBackupError.ExportFailed -> error.underlying
            is AppBackupError.ImportFailed -> error.underlying
            else -> null
        },
    )

fun instantFromUnixSeconds(seconds: BigDecimal): Instant {
    val integral = seconds.setScale(0, RoundingMode.FLOOR)
    val nanos = seconds.subtract(integral).movePointRight(9).setScale(0, RoundingMode.HALF_EVEN).longValueExact()
    return Instant.ofEpochSecond(integral.longValueExact(), nanos)
}

fun instantFromUnixSeconds(seconds: Double): Instant {
    require(seconds.isFinite()) { "Date must be a finite Unix-seconds value" }
    return instantFromUnixSeconds(BigDecimal.valueOf(seconds))
}

fun Instant.unixSeconds(): BigDecimal =
    BigDecimal.valueOf(epochSecond).add(BigDecimal.valueOf(nano.toLong(), 9))
