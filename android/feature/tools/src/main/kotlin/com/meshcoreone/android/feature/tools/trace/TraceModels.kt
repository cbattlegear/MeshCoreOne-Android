// PortedFrom: MC1/Views/Tools/TracePath/TraceHop.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Tools/TracePath/TraceResult.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Tools/TracePath/TracePathRoute.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SavedTracePathDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.TracePathRunDTO
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.TraceInfo
import java.util.UUID

/** One hop of a trace result. Start/end hops are the local device and carry no hash bytes. */
data class TraceHop(
    val hashBytes: Bytes?,
    val resolvedName: String?,
    val snr: Double,
    val isStartNode: Boolean,
    val isEndNode: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val id: UUID = UUID.randomUUID(),
) {
    /** Every hash byte as uppercase hex (multi-byte hashes are shown in full). */
    val hashDisplayString: String? get() = hashBytes?.uppercaseHexString()

    /** OR logic, matching `ContactDTO.hasLocation`: (0, 0) is the only "no location" pair. */
    val hasLocation: Boolean
        get() {
            val lat = latitude ?: return false
            val lon = longitude ?: return false
            return lat != 0.0 || lon != 0.0
        }
}

/** Outcome of one trace. [tracedPathBytes] is the path that was actually sent. */
data class TraceResult(
    val hops: SnapshotList<TraceHop>,
    val durationMs: Long,
    val success: Boolean,
    val errorMessage: String?,
    val tracedPathBytes: Bytes,
    val hashSize: Int,
    val id: UUID = UUID.randomUUID(),
) {
    /** Comma-separated uppercase hex, chunked by [hashSize]. */
    val tracedPathString: String get() = TracePathHex.chunks(tracedPathBytes, hashSize).joinToString(",")

    companion object {
        fun timeout(attemptedPath: Bytes, hashSize: Int, noResponseMessage: String): TraceResult =
            TraceResult(SnapshotList.empty(), 0, false, noResponseMessage, attemptedPath, hashSize)

        fun sendFailed(message: String, attemptedPath: Bytes, hashSize: Int): TraceResult =
            TraceResult(SnapshotList.empty(), 0, false, message, attemptedPath, hashSize)
    }
}

/**
 * Feature-local mirror of the path editor's `PathHop` (WP-311, `PathManagementViewModel.swift`).
 * [id] gives each hop its own identity, so equal bytes on two hops are still two hops.
 */
data class TracePathHop(
    val hashBytes: Bytes,
    val publicKey: Bytes? = null,
    val resolvedName: String? = null,
    val id: UUID = UUID.randomUUID(),
) {
    val hashHex: String get() = hashBytes.uppercaseHexString()
    val displayText: String get() = resolvedName?.let { "$it ($hashHex)" } ?: hashHex
}

/**
 * Feature-local mirror of WP-209 `AdvertisementEvent.TraceResponse`, which lives in core:services.
 * WP-303 maps the service event stream onto this type.
 */
data class TraceResponse(val traceInfo: TraceInfo, val radioId: RadioId?)

/** Batch SNR aggregate for one hop index. */
data class HopStats(val avg: Double, val min: Double, val max: Double)

/** Push destinations inside the trace-results stack. */
sealed interface TracePathRoute {
    data class SavedPathDetail(val savedPath: SavedTracePathDTO) : TracePathRoute
    data class RunDetail(val run: TracePathRunDTO) : TracePathRoute
}

/** Shared uppercase-hex chunking used by every comma-separated path display. */
object TracePathHex {
    fun chunks(data: Bytes, size: Int): List<String> {
        val step = size.coerceAtLeast(1)
        val result = ArrayList<String>()
        var start = 0
        while (start < data.size) {
            val end = minOf(start + step, data.size)
            result += data.slice(start, end).uppercaseHexString()
            start = end
        }
        return result
    }
}
