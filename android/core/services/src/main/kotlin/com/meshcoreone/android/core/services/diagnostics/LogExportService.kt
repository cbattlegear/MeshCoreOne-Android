// PortedFrom: MC1/Services/LogExportService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: JVM text assembly over injected app-state/store/clock seams, written to a caller-owned
// OutputStream. Android SAF document creation and the share sheet are platform UI and stay with the app layer.
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.contracts.domain.DebugLogPersisting
import com.meshcoreone.android.core.contracts.domain.DebugLogRetention
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.DevicePersisting
import com.meshcoreone.android.core.model.DebugLogEntryDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.model.BatteryInfo
import java.io.OutputStream
import java.text.NumberFormat
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Flushes buffered debug log entries to the store; implemented by the debug log buffer port. */
fun interface DebugLogFlushing {
    suspend fun flush()
}

/** The app-state reads the export performs (source: `AppState`, its connection manager and battery monitor). */
interface LogExportAppState {
    val connectedDevice: DeviceDTO?
    val connectionState: DeviceConnectionState
    val connectionIntentSummary: String
    val lastDisconnectDiagnostic: String?
    val deviceBattery: BatteryInfo?
    suspend fun currentBLEDiagnosticsSummary(): String
}

/** The two store reads the export performs. */
interface LogExportStore {
    suspend fun fetchActiveDevice(): DeviceDTO?
    suspend fun fetchDebugLogEntries(since: Instant, limit: Long): SnapshotList<DebugLogEntryDTO>

    companion object {
        fun <S> from(store: S): LogExportStore where S : DevicePersisting, S : DebugLogPersisting = object : LogExportStore {
            override suspend fun fetchActiveDevice(): DeviceDTO? = store.fetchActiveDevice()
            override suspend fun fetchDebugLogEntries(since: Instant, limit: Long) = store.fetchDebugLogEntries(since, limit)
        }
    }
}

/**
 * Build and host facts for the export header. The source hard-codes `iOS` before the system
 * version; Android supplies its own [systemName].
 */
data class LogExportEnvironment(
    val appVersion: String,
    val buildNumber: String,
    val deviceModel: String,
    val systemName: String,
    val systemVersion: String,
)

/** Logging seam for export failures (source category `LogExportService`). */
fun interface LogExportDiagnostics {
    fun error(message: String)

    companion object {
        val NONE: LogExportDiagnostics = LogExportDiagnostics { }
    }
}

/** Assembles the debug export: header, connection, device, battery and the last seven days of logs. */
class LogExportService(
    private val environment: LogExportEnvironment,
    private val store: LogExportStore,
    private val debugLogBuffer: DebugLogFlushing?,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.getDefault(),
    private val diagnostics: LogExportDiagnostics = LogExportDiagnostics.NONE,
) {
    /** Generates the export text; sections are separated by one blank line. */
    suspend fun generateExport(appState: LogExportAppState): String {
        // Flush buffered logs first so the export includes the latest lifecycle events.
        debugLogBuffer?.flush()

        val connectedDevice = appState.connectedDevice
        val isConnected = connectedDevice != null
        val device = connectedDevice ?: try {
            store.fetchActiveDevice()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            diagnostics.error("Failed to fetch active device for export: ${error.rxLogErrorDescription()}")
            null
        }

        val sections = buildList {
            add(generateHeader())
            add(generateConnectionSection(appState, device))
            if (device != null) add(generateDeviceSection(device, isConnected))
            appState.deviceBattery?.let { add(generateBatterySection(it)) }
            add(generateLogsSection())
        }
        return sections.joinToString(SECTION_SEPARATOR)
    }

    /**
     * Generates the export and writes it as UTF-8 to the stream [openSink] returns for the export
     * file name. Returns that file name, or `null` when opening or writing fails (source: temp-file URL or nil).
     */
    suspend fun createExportFile(appState: LogExportAppState, openSink: (fileName: String) -> OutputStream): String? {
        val content = generateExport(appState)
        val fileName = exportFileName()
        return try {
            withContext(Dispatchers.IO) { openSink(fileName).use { it.write(content.toByteArray(Charsets.UTF_8)) } }
            fileName
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            diagnostics.error("Failed to write export file: ${error.rxLogErrorDescription()}")
            null
        }
    }

    /** `MeshCore-One-Debug-yyyy-MM-dd-HHmmss.txt` in the local zone. */
    fun exportFileName(): String = "MeshCore-One-Debug-${fileTimestampFormatter.format(clock.instant())}.txt"

    private fun generateHeader(): String = listOf(
        "=== MeshCore One Debug Export ===",
        "Exported: ${internetDateTime(clock.instant())}",
        "App Version: ${environment.appVersion} (${environment.buildNumber})",
        "Device: ${environment.deviceModel}, ${environment.systemName} ${environment.systemVersion}",
    ).joinToString(LINE_SEPARATOR)

    private suspend fun generateConnectionSection(appState: LogExportAppState, device: DeviceDTO?): String {
        val lines = mutableListOf(
            "=== Connection ===",
            "State: ${appState.connectionState.exportLabel}",
            "Intent: ${appState.connectionIntentSummary}",
        )
        val disconnectDiagnostic = appState.lastDisconnectDiagnostic ?: UNAVAILABLE_DISCONNECT_DIAGNOSTIC
        lines += "Last Disconnect Diagnostic: $disconnectDiagnostic"
        lines += appState.currentBLEDiagnosticsSummary()
        if (device != null) {
            lines += "Device: ${device.nodeName} (${device.id.toString().uppercase(Locale.ROOT).take(8)}...)"
            lines += "Last Connected: ${internetDateTime(device.lastConnected)}"
        }
        return lines.joinToString(LINE_SEPARATOR)
    }

    private fun generateDeviceSection(device: DeviceDTO, isConnected: Boolean): String {
        val frequencyMHz = device.frequency.toDouble() / 1000.0
        val header = if (isConnected) "=== Device Info ===" else "=== Device Info (Last Connected) ==="
        return listOf(
            header,
            "Name: ${device.nodeName}",
            "Firmware: ${device.firmwareVersionString} (v${device.firmwareVersion})",
            "Manufacturer: ${device.manufacturerName}",
            "Build Date: ${device.buildDate}",
            "Radio: ${fixed(frequencyMHz, 3)} MHz, BW ${device.bandwidth} kHz, " +
                "SF${device.spreadingFactor}, CR${device.codingRate}",
            "TX Power: ${device.txPower} dBm (max ${device.maxTxPower})",
            "Max Nodes: ${device.maxContacts}",
            "Max Channels: ${device.maxChannels}",
            "Manual Add Nodes: ${device.manualAddContacts}",
            "Multi-ACKs: ${device.multiAcks}",
        ).joinToString(LINE_SEPARATOR)
    }

    private fun generateBatterySection(battery: BatteryInfo): String {
        // Source `BatteryInfo+Display`: volts = mV / 1000; linear LiPo estimate 3.0 V = 0 %, 4.2 V = 100 %.
        val voltage = battery.level.toDouble() / 1000.0
        val percentage = (((voltage - 3.0) / 1.2) * 100).coerceIn(0.0, 100.0).toInt()
        return listOf(
            "=== Battery ===",
            "Level: $percentage%",
            "Voltage: ${fixed(voltage, 2)} V",
            "Raw: ${battery.level} mV",
        ).joinToString(LINE_SEPARATOR)
    }

    private suspend fun generateLogsSection(): String {
        val lines = mutableListOf("=== Logs (Last 7 Days) ===")
        try {
            val retentionStart = clock.instant().minus(DebugLogRetention.window)
            val entries = store.fetchDebugLogEntries(retentionStart, DebugLogRetention.MAX_ENTRIES)
            if (entries.isEmpty()) {
                lines += "(No logs found)"
            } else {
                for (entry in entries) {
                    val timestamp = logTimestampFormatter.format(entry.timestamp)
                    lines += "$timestamp [${entry.level.label}] ${entry.category}: ${entry.message}"
                }
                lines += ""
                lines += "Total entries: ${entries.size}"
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            lines += "(Failed to fetch logs: ${error.rxLogErrorDescription()})"
            diagnostics.error("Debug log fetch failed: ${error.rxLogErrorDescription()}")
        }
        return lines.joinToString(LINE_SEPARATOR)
    }

    private val fileTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss", locale).withZone(zone)
    private val logTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", locale).withZone(zone)

    /** Locale-aware fixed fraction digits with grouping, like `.number.precision(.fractionLength(n))`. */
    private fun fixed(value: Double, fractionDigits: Int): String = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = fractionDigits
        maximumFractionDigits = fractionDigits
    }.format(value)

    companion object {
        const val SECTION_SEPARATOR = "\n\n"
        private const val LINE_SEPARATOR = "\n"
        const val UNAVAILABLE_DISCONNECT_DIAGNOSTIC =
            "Unavailable (no disconnect callback captured; app may have been suspended)"

        private val internetDateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).withZone(ZoneOffset.UTC)

        /** `ISO8601DateFormatter` with `.withInternetDateTime`: UTC, whole seconds, `Z` suffix. */
        fun internetDateTime(instant: Instant): String =
            internetDateTimeFormatter.format(instant.truncatedTo(ChronoUnit.SECONDS))
    }
}

private val DeviceConnectionState.exportLabel: String
    get() = when (this) {
        DeviceConnectionState.DISCONNECTED -> "disconnected"
        DeviceConnectionState.CONNECTING -> "connecting"
        DeviceConnectionState.CONNECTED -> "connected"
        DeviceConnectionState.SYNCING -> "syncing"
        DeviceConnectionState.READY -> "ready"
    }
