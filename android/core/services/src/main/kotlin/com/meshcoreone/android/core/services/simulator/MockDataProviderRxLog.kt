// PortedFrom: MC1Services/Sources/MC1Services/Simulator/MockDataProvider+RxLog.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ParsedRxLogData
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import java.time.Instant
import java.util.UUID

/**
 * Unique and ambiguous flood-region rows, received relative to [now]. `decodedText` is omitted because it is
 * transient on the stored entry and `saveRxLogEntry` does not persist it.
 */
internal fun MockDataProvider.rxLogEntries(now: Instant): SnapshotList<RxLogEntryDTO> = SnapshotList.of(
    rxLogEntry(
        id = uniqueRxLogEntryID,
        receivedAtOffsetSeconds = -3600,
        packetPayload = Bytes.of(0x01, 0xAA, 0xBB),
        regionScope = uniqueRegionName,
        regionScopeMatches = listOf(uniqueRegionName),
        now = now,
    ),
    rxLogEntry(
        id = ambiguousRxLogEntryID,
        receivedAtOffsetSeconds = -1800,
        packetPayload = Bytes.of(0x02, 0xCC, 0xDD),
        regionScope = null,
        regionScopeMatches = ambiguousRegionNames,
        now = now,
    ),
)

private val seedTransportCode: Bytes = Bytes.of(0x11, 0x22, 0x33, 0x44)

@Suppress("LongParameterList")
private fun MockDataProvider.rxLogEntry(
    id: UUID,
    receivedAtOffsetSeconds: Long,
    packetPayload: Bytes,
    regionScope: String?,
    regionScopeMatches: List<String>,
    now: Instant,
): RxLogEntryDTO {
    val parsed = ParsedRxLogData(
        snr = 8.0,
        rssi = -70,
        rawPayload = Bytes.of(0x15, 0x01, 0x02, 0x03),
        routeType = RouteType.TC_FLOOD,
        payloadType = PayloadType.GROUP_TEXT,
        payloadVersion = 0u,
        payloadTypeBits = 5u,
        transportCode = seedTransportCode,
        pathLength = 1u,
        pathNodes = listOf<UByte>(0x42u),
        packetPayload = packetPayload,
    )
    return RxLogEntryDTO.fromParsed(
        radioId = simulatorRadioId,
        parsed = parsed,
        id = id,
        receivedAt = now.addingInterval(receivedAtOffsetSeconds),
        channelIndex = publicChannelIndex,
        channelName = "Public",
        decryptStatus = DecryptStatus.SUCCESS,
        regionScope = regionScope,
        regionScopeMatches = regionScopeMatches.snapshot(),
    )
}
