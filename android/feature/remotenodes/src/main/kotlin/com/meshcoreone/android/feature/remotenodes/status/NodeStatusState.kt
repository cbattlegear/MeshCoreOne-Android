// PortedFrom: MC1/Views/RemoteNodes/NodeStatusViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.status

import com.meshcoreone.android.core.model.NodeLocationFix
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.OCVPreset
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.core.protocol.event.TelemetryResponse
import com.meshcoreone.android.core.protocol.lpp.LPPDataPoint
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText

/** Byte length of the public-key prefix that status and telemetry responses carry. */
internal const val RESPONSE_PREFIX_LENGTH = 6

/** One telemetry channel's data points, in response order (Swift `groupedDataPoints` element). */
data class TelemetryChannelGroup(val channel: UByte, val dataPoints: List<LPPDataPoint>)

/**
 * Observable state of the shared status helper (Swift `NodeStatusViewModel` stored properties).
 * Text fields carry unresolved [RemoteNodesText]; derived values mirror the Swift computed properties.
 */
data class NodeStatusState(
    val session: RemoteNodeSessionDTO? = null,
    /** Public key for login-free telemetry (Swift private `directPublicKey`). */
    val directPublicKey: Bytes? = null,
    val status: StatusResponse? = null,
    val telemetry: TelemetryResponse? = null,
    /** Decoded data points on non-zero channels. */
    val cachedDataPoints: List<LPPDataPoint> = emptyList(),
    val isLoadingStatus: Boolean = false,
    val isLoadingTelemetry: Boolean = false,
    val statusLoaded: Boolean = false,
    val statusExpanded: Boolean = false,
    val telemetryLoaded: Boolean = false,
    val telemetryExpanded: Boolean = false,
    val statusSectionError: RemoteNodesText? = null,
    val telemetrySectionError: RemoteNodesText? = null,
    val isBatteryCurveExpanded: Boolean = false,
    val selectedOCVPreset: OCVPreset = OCVPreset.LI_ION,
    val ocvValues: List<Long> = OCVPreset.LI_ION.ocvArray,
    val ocvError: RemoteNodesText? = null,
    /** Last status-bearing snapshot before the current reading (status deltas). */
    val previousStatusSnapshot: NodeStatusSnapshotDTO? = null,
    /** Last neighbor-bearing snapshot before the current capture (neighbor SNR delta). */
    val previousNeighborSnapshot: NodeStatusSnapshotDTO? = null,
    /** Every neighbor prefix seen in stored history before the current reading ("New" badge). */
    val seenNeighborPrefixes: Set<Bytes> = emptySet(),
) {
    /** Prefers the session key, falling back to the direct-telemetry key. */
    val effectivePublicKey: Bytes? get() = session?.publicKey ?: directPublicKey

    /** 6-byte prefix used to match responses. */
    val effectivePublicKeyPrefix: Bytes? get() = session?.publicKeyPrefix ?: directPublicKey?.prefix(RESPONSE_PREFIX_LENGTH)

    val hasMultipleChannels: Boolean get() = cachedDataPoints.map { it.channel }.toSet().size > 1

    /** Data points grouped by channel, channels ascending, each group in response order. */
    val groupedDataPoints: List<TelemetryChannelGroup>
        get() = cachedDataPoints.groupBy { it.channel }.toSortedMap()
            .map { (channel, points) -> TelemetryChannelGroup(channel, points) }

    /** The node's reported position from the last telemetry response, if plottable. */
    val currentLocationFix: NodeLocationFix? get() = NodeLocationFix.primaryFix(cachedDataPoints)

    val batteryDeltaMV: Int?
        get() {
            val current = status?.batteryMillivolts ?: return null
            val previous = previousStatusSnapshot?.batteryMillivolts ?: return null
            return current.toInt() - previous.toInt()
        }

    val snrDelta: Double?
        get() {
            val current = status?.lastSNR ?: return null
            val previous = previousStatusSnapshot?.lastSNR ?: return null
            return current - previous
        }

    val rssiDelta: Long?
        get() {
            val current = status?.lastRSSI ?: return null
            val previous = previousStatusSnapshot?.lastRSSI ?: return null
            return current - previous.toLong()
        }

    val noiseFloorDelta: Long?
        get() {
            val current = status?.noiseFloor ?: return null
            val previous = previousStatusSnapshot?.noiseFloor ?: return null
            return current - previous.toLong()
        }

    /** True when [publicKeyPrefix] matches the session this screen shows. */
    fun matchesSession(publicKeyPrefix: Bytes): Boolean {
        if (publicKeyPrefix.isEmpty) return false
        val publicKey = session?.publicKey ?: return false
        return publicKey.prefix(publicKeyPrefix.size) == publicKeyPrefix
    }
}

/** Swift `StatusResponse+Compatibility.batteryMillivolts`: `UInt16(clamping: battery)`. */
val StatusResponse.batteryMillivolts: UShort get() = battery.coerceIn(0L, UShort.MAX_VALUE.toLong()).toUShort()
