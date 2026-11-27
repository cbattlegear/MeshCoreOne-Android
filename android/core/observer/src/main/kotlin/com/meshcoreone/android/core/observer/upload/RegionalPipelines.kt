// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import com.meshcoreone.android.core.observer.ObserverRecord
import java.time.Instant

/**
 * One [UploadPipeline] per region topic segment (`nd`, `mn`), created on first use. The pipeline's topics are fixed at
 * construction, so near the shared border a record goes to the pipeline of the state it was heard in. The [factory] owns the
 * per-region queue repository, so queued rows are resent on the topic they were first meant for.
 * Driving it: call [tick] on the timer, [onConnected] after each (re)connect.
 */
class RegionalPipelines(private val factory: (region: String) -> UploadPipeline) {
    private val lock = Any()
    private var byRegion: Map<String, UploadPipeline> = emptyMap()

    fun pipelineFor(region: String): UploadPipeline = synchronized(lock) {
        byRegion[region] ?: factory(region).also { byRegion = byRegion + (region to it) }
    }

    fun all(): List<UploadPipeline> = synchronized(lock) { byRegion.values.toList() }

    suspend fun offer(region: String, record: TimedRecord): Boolean = pipelineFor(region).offer(record)

    suspend fun publishHealth(region: String, payload: String): Boolean = pipelineFor(region).publishHealth(payload)

    suspend fun tick() = all().forEach { it.tick() }
    suspend fun flushAll() = all().forEach { it.flush() }
    suspend fun onConnected() = all().forEach { it.onConnected() }
    fun setLowActivity(enabled: Boolean) = all().forEach { it.setLowActivity(enabled) }
    suspend fun pendingCount(): Int = all().sumOf { it.pendingCount() }
    suspend fun queuedCount(): Int = all().sumOf { it.queuedCount() }
    suspend fun nextDueAt(): Instant? = all().mapNotNull { it.nextDueAt() }.minOrNull()
}
