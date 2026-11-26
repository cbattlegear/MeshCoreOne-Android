// PortedFrom: MC1Services/Sources/MC1Services/Models/SavedTracePath.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID

data class TracePathRunDTO(
    val id: UUID, val date: Instant, val success: Boolean, val roundTripMs: Long, val hopsSNR: SnapshotList<Double>,
) {
    private val fields get() = arrayOf(id, date, success, roundTripMs, hopsSNR)
    override fun equals(other: Any?): Boolean = other is TracePathRunDTO && sourceFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = sourceFieldsHash(fields)
}

data class SavedTracePathDTO(
    val id: UUID,
    val radioId: RadioId,
    val name: String,
    val pathBytes: Bytes,
    val hashSize: Long = 1,
    val createdDate: Instant,
    val runs: SnapshotList<TracePathRunDTO>,
) {
    val runCount: Long get() = runs.size.toLong()
    val lastRunDate: Instant? get() = runs.maxOfOrNull { it.date }
    val averageRoundTripMs: Long?
        get() {
            val successful = runs.filter { it.success }
            if (successful.isEmpty()) return null
            return successful.fold(0L) { total, run -> Math.addExact(total, run.roundTripMs) } / successful.size
        }
    val successRate: Long get() = if (runs.isEmpty()) 100 else runs.count { it.success }.toLong() * 100 / runs.size
    val recentRTTs: SnapshotList<Long>
        get() = runs.filter { it.success }.sortedByDescending { it.date }.take(10).asReversed().map { it.roundTripMs }.snapshot()
    val pathHashBytes: SnapshotList<UByte> get() = pathBytes.toList().snapshot()
}
