// PortedFrom: MC1/Services/LogExportService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: numbers use Locale.ROOT (Foundation used the user locale); header names Android instead of iOS.
package com.meshcoreone.android.feature.settings.app.diagnostics

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object LogExportFormatter {
    const val NO_DISCONNECT_CALLBACK = "Unavailable (no disconnect callback captured; app may have been suspended)"
    const val RETENTION_DAYS = 7

    private val iso: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val fileStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")
    private val logStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /** Sections joined by a blank line: header, connection, device, battery, logs. */
    fun format(snapshot: LogExportSnapshot, zone: ZoneId = ZoneId.systemDefault()): String =
        listOfNotNull(
            header(snapshot),
            connection(snapshot.connection, snapshot.device),
            snapshot.device?.let(::device),
            snapshot.battery?.let(::battery),
            logs(snapshot.logs, zone),
        ).joinToString("\n\n")

    fun fileName(now: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        "MeshCore-One-Debug-${fileStamp.format(now.atZone(zone))}.txt"

    private fun utc(instant: Instant): String = iso.format(instant.atZone(ZoneOffset.UTC).withNano(0))

    private fun header(s: LogExportSnapshot) = """
        |=== MeshCore One Debug Export ===
        |Exported: ${utc(s.exportedAt)}
        |App Version: ${s.appVersion} (${s.appBuild})
        |Device: ${s.deviceModel}, Android ${s.androidRelease}
    """.trimMargin()

    private fun connection(c: ExportConnection, device: ExportDevice?): String {
        val lines = mutableListOf(
            "=== Connection ===",
            "State: ${c.state}",
            "Intent: ${c.intentSummary}",
            "Last Disconnect Diagnostic: ${c.lastDisconnectDiagnostic ?: NO_DISCONNECT_CALLBACK}",
        )
        c.bleDiagnostics?.let(lines::add)
        if (device != null) {
            lines += "Device: ${device.nodeName} (${device.shortId}...)"
            lines += "Last Connected: ${utc(device.lastConnected)}"
        }
        return lines.joinToString("\n")
    }

    private fun device(d: ExportDevice): String {
        val header = if (d.isConnected) "=== Device Info ===" else "=== Device Info (Last Connected) ==="
        val mhz = String.format(Locale.ROOT, "%.3f", d.frequencyKilohertz / 1000.0)
        return """
            |$header
            |Name: ${d.nodeName}
            |Firmware: ${d.firmwareVersionString} (v${d.firmwareVersion})
            |Manufacturer: ${d.manufacturerName}
            |Build Date: ${d.buildDate}
            |Radio: $mhz MHz, BW ${d.bandwidthKilohertz} kHz, SF${d.spreadingFactor}, CR${d.codingRate}
            |TX Power: ${d.txPower} dBm (max ${d.maxTxPower})
            |Max Nodes: ${d.maxContacts}
            |Max Channels: ${d.maxChannels}
            |Manual Add Nodes: ${d.manualAddContacts}
            |Multi-ACKs: ${d.multiAcks}
        """.trimMargin()
    }

    private fun battery(b: ExportBattery) = """
        |=== Battery ===
        |Level: ${b.percentage}%
        |Voltage: ${String.format(Locale.ROOT, "%.2f", b.voltage)} V
        |Raw: ${b.rawMillivolts} mV
    """.trimMargin()

    private fun logs(logs: ExportLogs, zone: ZoneId): String {
        val lines = mutableListOf("=== Logs (Last $RETENTION_DAYS Days) ===")
        when (logs) {
            is ExportLogs.FetchFailed -> lines += "(Failed to fetch logs: ${logs.reason})"
            is ExportLogs.Entries -> if (logs.entries.isEmpty()) {
                lines += "(No logs found)"
            } else {
                logs.entries.forEach { lines += "${logStamp.format(it.timestamp.atZone(zone))} [${it.level}] ${it.category}: ${it.message}" }
                lines += ""
                lines += "Total entries: ${logs.entries.size}"
            }
        }
        return lines.joinToString("\n")
    }
}
