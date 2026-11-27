// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import java.time.Instant

/** A serialized record waiting for the broker. [json] is the output of [RecordSerializer.toJson]. */
class QueuedRecord(val id: Long, val rxTime: Instant, val json: String)

/**
 * Persistent offline queue. Rows are removed only through [delete] after a broker ack. Ids increase in insertion
 * order and [oldest] returns lowest ids first.
 *
 * TODO(Room): a Room-backed implementation is NOT written. core:database is an Android module with entities, DAOs,
 * exported schemas and migrations, which is out of proportion for this pure-JVM step. Suggested table: id (autogenerate),
 * rx_time_epoch_millis (indexed), json. Use [InMemoryUploadQueueRepository] until then.
 */
interface UploadQueueRepository {
    suspend fun append(rxTimes: List<Instant>, json: List<String>)
    suspend fun oldest(limit: Int): List<QueuedRecord>
    suspend fun delete(ids: Collection<Long>)
    suspend fun count(): Int
    /** Drops rows with rxTime before [cutoff]; returns how many. */
    suspend fun deleteOlderThan(cutoff: Instant): Int
    /** Keeps only the newest [max] rows, dropping the oldest; returns how many were dropped. */
    suspend fun trimToNewest(max: Int): Int
}

class InMemoryUploadQueueRepository : UploadQueueRepository {
    private val rows = ArrayList<QueuedRecord>()
    private var nextId = 1L

    override suspend fun append(rxTimes: List<Instant>, json: List<String>) {
        require(rxTimes.size == json.size)
        synchronized(rows) { rxTimes.indices.forEach { rows += QueuedRecord(nextId++, rxTimes[it], json[it]) } }
    }
    override suspend fun oldest(limit: Int): List<QueuedRecord> = synchronized(rows) { rows.take(limit) }
    override suspend fun delete(ids: Collection<Long>) { synchronized(rows) { rows.removeAll { it.id in ids } } }
    override suspend fun count(): Int = synchronized(rows) { rows.size }
    override suspend fun deleteOlderThan(cutoff: Instant): Int =
        synchronized(rows) { val before = rows.size; rows.removeAll { it.rxTime.isBefore(cutoff) }; before - rows.size }
    override suspend fun trimToNewest(max: Int): Int = synchronized(rows) {
        val excess = (rows.size - max).coerceAtLeast(0)
        repeat(excess) { rows.removeAt(0) }
        excess
    }
}
