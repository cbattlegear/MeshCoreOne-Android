// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Batches records and delivers them at-least-once-with-ack to the broker.
 *
 * Live records are buffered in memory and sent when the buffer reaches [UploadPolicy.maxBatchRecords] or the oldest
 * buffered record is [UploadPolicy.batchInterval] old (60 s while [setLowActivity] is true). A live batch that is not
 * acknowledged goes to the [repository]. Queued rows are resent oldest-first with `delayed=true` before any live batch
 * and are deleted only after the broker acks that batch. rx_time is serialized at [offer] and never changes.
 *
 * Driving it: call [offer] per record, [tick] on a timer (see [nextDueAt]) and [onConnected] after every (re)connect.
 * Not crash-proof for the few seconds of in-memory live records; queued rows survive when the repository is persistent.
 */
class UploadPipeline(
    private val publisher: MqttPublisher,
    private val repository: UploadQueueRepository,
    private val topics: ObserverTopics,
    private val sessionId: String,
    private val clock: UploadClock,
    private val policy: UploadPolicy = UploadPolicy(),
) {
    private val lock = Mutex()
    private val pendingTimes = ArrayList<Instant>()
    private val pendingJson = ArrayList<String>()
    private var oldestPendingAt: Instant? = null
    private var backlogRetryAt: Instant? = null

    @Volatile private var lowActivity = false

    /** True while the screen is off and the phone is still: stretches the time trigger to [UploadPolicy.lowActivityInterval]. */
    fun setLowActivity(enabled: Boolean) { lowActivity = enabled }

    private val interval: Duration get() = if (lowActivity) policy.lowActivityInterval else policy.batchInterval

    /** Returns false (and keeps nothing) for a dropped record. */
    suspend fun offer(record: TimedRecord): Boolean {
        val json = RecordSerializer.toJson(record) ?: return false
        lock.withLock {
            if (pendingJson.isEmpty()) oldestPendingAt = clock.now()
            pendingTimes += record.rxTime
            pendingJson += json
            if (pendingJson.size >= policy.maxBatchRecords) flushLocked()
        }
        return true
    }

    /** Timer entry point: flushes when the time trigger is due, retries the backlog, and enforces queue limits. */
    suspend fun tick() = lock.withLock {
        val now = clock.now()
        val oldest = oldestPendingAt
        if (oldest != null && !now.isBefore(oldest.plus(interval))) {
            flushLocked()
        } else {
            val retryAt = backlogRetryAt
            if (retryAt == null || !now.isBefore(retryAt)) drainBacklogLocked()
        }
        enforceLimitsLocked()
    }

    /** The broker (re)connected: send everything now, queued rows first. */
    suspend fun onConnected() = lock.withLock { backlogRetryAt = null; flushLocked() }

    suspend fun flush() = lock.withLock { flushLocked() }

    /** When the next [tick] is needed for the time trigger, or null when nothing is buffered. */
    suspend fun nextDueAt(): Instant? = lock.withLock { oldestPendingAt?.plus(interval) }

    /**
     * Publishes one health record (QoS 1, not retained) on the health topic and returns whether the broker acked it.
     * Health is a point-in-time reading, so it is never queued or retried: a stale reading is worse than none.
     */
    suspend fun publishHealth(payload: String): Boolean {
        val result = try {
            publisher.publish(MqttMessage(topics.health, payload, qos = 1, retained = false))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PublishResult.Failed(error.message ?: error.javaClass.simpleName)
        }
        return result == PublishResult.Acked
    }

    suspend fun pendingCount(): Int = lock.withLock { pendingJson.size }
    suspend fun queuedCount(): Int = repository.count()

    private suspend fun flushLocked() {
        val backlogClear = drainBacklogLocked()
        if (pendingJson.isEmpty()) return
        if (backlogClear && publishBatch(pendingJson.toList(), delayed = false)) {
            clearPending()
        } else {
            repository.append(pendingTimes.toList(), pendingJson.toList())
            clearPending()
            if (backlogClear) backlogRetryAt = clock.now().plus(interval)
            enforceLimitsLocked()
        }
    }

    /** Sends queued rows oldest-first. Returns true only when the queue is empty afterwards. */
    private suspend fun drainBacklogLocked(): Boolean {
        repeat(policy.maxBacklogBatchesPerFlush) {
            val rows = repository.oldest(policy.maxBatchRecords)
            if (rows.isEmpty()) return true
            if (!publishBatch(rows.map { it.json }, delayed = true)) {
                backlogRetryAt = clock.now().plus(interval)
                return false
            }
            repository.delete(rows.map { it.id })
        }
        return repository.count() == 0
    }

    private suspend fun publishBatch(records: List<String>, delayed: Boolean): Boolean {
        val payload = RecordSerializer.batchJson(sessionId, delayed, records)
        val result = try {
            publisher.publish(MqttMessage(topics.packets, payload, qos = 1, retained = false))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PublishResult.Failed(error.message ?: error.javaClass.simpleName)
        }
        return result == PublishResult.Acked
    }

    private suspend fun enforceLimitsLocked() {
        repository.deleteOlderThan(clock.now().minus(policy.maxQueueAge))
        repository.trimToNewest(policy.maxQueueRecords)
    }

    private fun clearPending() {
        pendingTimes.clear(); pendingJson.clear(); oldestPendingAt = null
    }
}
