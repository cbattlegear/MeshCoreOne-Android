// AndroidOnly: WP-215 Retain committed reaction notification work through radio graph replacement.
package com.meshcoreone.android.core.services.notifications

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PendingReactionNotification
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.ReactionNotificationPersisting
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ReactionNotificationOutcome { POSTED, SUPPRESSED, STALE_TARGET, RETRY }

/** Process-owned worker; Room owns durable requests when available. */
class ReactionNotificationCustody(private val journal: ReactionNotificationPersisting? = null) {
    private val lock = Any()
    private val pending = LinkedHashMap<EntityKey, UUID>()
    private val completed = LinkedHashSet<EntityKey>()
    private val arrivals = MutableStateFlow(0L)
    private val delivery = Mutex()
    private val terminal = LinkedHashSet<PendingReactionNotification>()

    fun retain(occurrence: EntityKey, message: UUID) = synchronized(lock) {
        if (journal != null) {
            arrivals.value++
            return@synchronized
        }
        if (occurrence !in completed && pending.putIfAbsent(occurrence, message) == null) {
            arrivals.value++
        }
    }

    suspend fun collect(
        radio: RadioId,
        isCurrent: () -> Boolean,
        attempt: suspend (message: UUID, occurrence: UUID, token: String?) -> ReactionNotificationOutcome,
    ) {
        arrivals.collect arrival@{
            if (journal != null) {
                var firstRetry = true
                while (isCurrent() && drainJournal(radio, isCurrent, attempt)) {
                    delay(if (firstRetry) 1_000 else 30_000)
                    firstRetry = false
                }
                return@arrival
            }
            if (drain(radio, isCurrent, attempt)) {
                delay(1_000)
                drain(radio, isCurrent, attempt)
            }
        }
    }

    private suspend fun drain(
        radio: RadioId,
        isCurrent: () -> Boolean,
        attempt: suspend (UUID, UUID, String?) -> ReactionNotificationOutcome,
    ): Boolean = delivery.withLock {
        val batch = synchronized(lock) { pending.filterKeys { it.radioId == radio } }
        var retry = false
        for ((key, message) in batch) {
            if (!isCurrent()) return@withLock false
            if (attempt(message, key.id, null) == ReactionNotificationOutcome.RETRY) {
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

    private suspend fun drainJournal(
        radio: RadioId,
        isCurrent: () -> Boolean,
        attempt: suspend (UUID, UUID, String?) -> ReactionNotificationOutcome,
    ): Boolean = delivery.withLock {
        val store = checkNotNull(journal)
        try {
            // Retry only the conditional acknowledgement after a known consumer result.
            for (work in terminal.filter { it.occurrence.radioId == radio }) {
                store.acknowledgeReactionNotification(work)
                terminal.remove(work)
            }
            while (isCurrent()) {
                val batch = store.pendingReactionNotifications(radio)
                if (batch.isEmpty()) return@withLock false
                for (work in batch) {
                    if (!isCurrent()) return@withLock false
                    if (attempt(work.messageID, work.occurrence.id, work.token) == ReactionNotificationOutcome.RETRY) {
                        return@withLock true
                    }
                    terminal.add(work)
                    store.acknowledgeReactionNotification(work)
                    terminal.remove(work)
                }
            }
            false
        } catch (failure: PersistenceStoreException) {
            Logger.getLogger("ReactionNotificationCustody").log(Level.WARNING, "Notification custody storage failure", failure)
            true
        }
    }
}
