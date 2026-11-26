// PortedFrom: MC1/Services/LogExportService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: the app gathers a LogExportSnapshot (flushing buffered logs first) behind LogExportDataSource; formatting is pure.
package com.meshcoreone.android.feature.settings.app.diagnostics

import java.time.Instant

/** Connection facts shown in the export; [bleDiagnostics] is the transport summary text, already formatted. */
data class ExportConnection(
    val state: String,
    val intentSummary: String,
    val lastDisconnectDiagnostic: String?,
    val bleDiagnostics: String?,
)

/** The device block; [isConnected] false means the last-connected device from persistence. */
data class ExportDevice(
    val nodeName: String,
    val shortId: String,
    val lastConnected: Instant,
    val firmwareVersion: Int,
    val firmwareVersionString: String,
    val manufacturerName: String,
    val buildDate: String,
    val frequencyKilohertz: Long,
    val bandwidthKilohertz: Long,
    val spreadingFactor: Int,
    val codingRate: Int,
    val txPower: Int,
    val maxTxPower: Int,
    val maxContacts: Int,
    val maxChannels: Int,
    val manualAddContacts: Boolean,
    val multiAcks: Int,
    val isConnected: Boolean,
)

data class ExportBattery(val percentage: Int, val voltage: Double, val rawMillivolts: Int)

data class ExportLogEntry(val timestamp: Instant, val level: String, val category: String, val message: String)

sealed interface ExportLogs {
    data class Entries(val entries: List<ExportLogEntry>) : ExportLogs
    data class FetchFailed(val reason: String) : ExportLogs
}

data class LogExportSnapshot(
    val exportedAt: Instant,
    val appVersion: String,
    val appBuild: String,
    val deviceModel: String,
    val androidRelease: String,
    val connection: ExportConnection,
    val device: ExportDevice?,
    val battery: ExportBattery?,
    val logs: ExportLogs,
)

/** Debug-log source and clear action behind the Diagnostics section. */
interface LogExportDataSource {
    /** Flushes buffered logs first so the export includes the latest lifecycle events. */
    suspend fun snapshot(): LogExportSnapshot
    suspend fun clearLogs()
}

sealed interface LogSaveOutcome {
    data class Saved(val displayName: String) : LogSaveOutcome
    data object Cancelled : LogSaveOutcome
}

/** System file picker slot (ACTION_CREATE_DOCUMENT) for the text export; low-space and write failures are thrown. */
interface LogDocumentPort {
    suspend fun saveText(suggestedName: String, content: String): LogSaveOutcome
}

interface LogExportStrings {
    fun exportFailed(): String
    fun genericFailure(failure: Throwable): String
}

fun interface LogExportDiagnostics {
    fun report(message: String, failure: Throwable?)
}

class LogExportDependencies(
    val data: LogExportDataSource,
    val documents: LogDocumentPort,
    val strings: LogExportStrings,
    val diagnostics: LogExportDiagnostics,
)
