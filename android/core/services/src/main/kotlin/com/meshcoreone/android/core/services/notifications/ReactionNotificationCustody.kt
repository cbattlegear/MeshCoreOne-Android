// AndroidOnly: WP-215 Retain committed reaction notification work through radio graph replacement.
package com.meshcoreone.android.core.services.notifications

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ReactionNotificationOutcome { POSTED, SUPPRESSED, STALE_TARGET, RETRY }

/** Process-owned; only a current graph for the occurrence's radio may attempt delivery. */
class ReactionNotificationCustody {
    private val lock = Any()
    private val pending = LinkedHashMap<EntityKey, UUID>()
    private val completed = LinkedHashSet<EntityKey>()
    private val arrivals = MutableStateFlow(0L)
    private val delivery = Mutex()

    fun retain(occurrence: EntityKey, message: UUID) = synchronized(lock) {
        if (occurrence !in completed && pending.putIfAbsent(occurrence, message) == null) {
            arrivals.value++
        }
    }

    suspend fun collect(
        radio: RadioId,
        isCurrent: () -> Boolean,
        attempt: suspend (message: UUID, occurrence: UUID) -> ReactionNotificationOutcome,
    ) {
        arrivals.collect {
            if (drain(radio, isCurrent, attempt)) {
                delay(1_000)
                drain(radio, isCurrent, attempt)
            }
        }
    }

    private suspend fun drain(
        radio: RadioId,
        isCurrent: () -> Boolean,
        attempt: suspend (UUID, UUID) -> ReactionNotificationOutcome,
    ): Boolean = delivery.withLock {
        val batch = synchronized(lock) { pending.filterKeys { it.radioId == radio } }
        var retry = false
        for ((key, message) in batch) {
            if (!isCurrent()) return@withLock false
            if (attempt(message, key.id) == ReactionNotificationOutcome.RETRY) {
                retry = true
            } else {
                synchronized(lock) {
                    pending.remove(key)
                    completed.add(key)
                    if (completed.size > 256) completed.remove(completed.first())
                }
            }
        }
        retry
    }
}
