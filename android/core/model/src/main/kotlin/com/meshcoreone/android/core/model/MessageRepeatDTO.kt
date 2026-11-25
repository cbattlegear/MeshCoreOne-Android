// PortedFrom: MC1Services/Sources/MC1Services/Models/MessageRepeat.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.decodePathLen
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class MessageRepeatDTO(
    val id: UUID = UUID.randomUUID(),
    val messageID: UUID,
    val receivedAt: Instant,
    val pathNodes: Bytes,
    val pathLength: UByte = 0u,
    val snr: Double? = null,
    val rssi: Long? = null,
    val rxLogEntryID: UUID? = null,
) {
    private val fields get() = arrayOf(id, messageID, receivedAt, pathNodes, pathLength, snr, rssi, rxLogEntryID)
    override fun equals(other: Any?): Boolean = other is MessageRepeatDTO && sourceFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = sourceFieldsHash(fields)

    val hashSize: Long get() = decodePathLen(pathLength)?.hashSize?.toLong() ?: 1
    val repeaterHash: Bytes?
        get() = if (pathNodes.isEmpty) null
        else pathNodes.slice(maxOf(0, pathNodes.size - hashSize.toInt()), pathNodes.size)
    val hopCount: Long get() = pathNodes.size.toLong() / hashSize
    val repeaterHashFormatted: String get() = repeaterHash?.uppercaseHexString() ?: "00"
    val pathNodesHex: SnapshotList<String> get() = pathHops.map { it.hex }.snapshot()
    val rssiFormatted: String get() = rssi?.let { "$it dBm" } ?: "\u2014"
    val pathHashSizeIfKnown: Long? get() = decodePathLen(pathLength)?.hashSize?.toLong()
    val pathHops: SnapshotList<PathHop> get() = pathNodes.pathHops(hashSize)
    val pathString: String get() = pathNodesHex.joinToString(" \u2192 ")
    val pathStringForClipboard: String get() = pathNodesHex.joinToString(",")

    fun snrFormatted(locale: Locale = Locale.getDefault()): String = snr?.let {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(it) + " dB"
    } ?: "\u2014"
}
