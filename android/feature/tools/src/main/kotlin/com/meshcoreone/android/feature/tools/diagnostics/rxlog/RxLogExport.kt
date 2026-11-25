// PortedFrom: MC1/Views/Tools/RxLogView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.diagnostics.rxlog

import com.meshcoreone.android.core.model.RxLogEntryDTO
import java.time.format.DateTimeFormatter
import java.util.Locale

object RxLogExport {
    private val instant = DateTimeFormatter.ISO_INSTANT

    fun csv(entries: List<RxLogEntryDTO>): String = buildString {
        appendLine("timestamp,route,type,rssi,snr,decrypt_status,packet_hash,decoded_text,raw_payload_hex")
        entries.forEach { entry ->
            append(csvField(instant.format(entry.receivedAt))).append(',')
            append(csvField(entry.routeTypeSimple)).append(',')
            append(csvField(entry.payloadType.displayName)).append(',')
            append(csvField(entry.rssi?.toString().orEmpty())).append(',')
            append(csvField(entry.snr?.toString().orEmpty())).append(',')
            append(csvField(entry.decryptStatus.name)).append(',')
            append(csvField(entry.packetHash)).append(',')
            append(csvField(entry.decodedText.orEmpty())).append(',')
            append(csvField(entry.rawPayload.joinToString("") { String.format(Locale.ROOT, "%02X", it.toInt()) }))
            appendLine()
        }
    }

    private fun csvField(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
