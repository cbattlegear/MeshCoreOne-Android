// AndroidOnly: WP-215 Tests committed notification delivery without a live subscriber.
package com.meshcoreone.android.core.services.notifications

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PendingReactionNotification
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.ReactionNotificationPersisting
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import java.util.UUID
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReactionNotificationCustodyTest {
    @Test fun queuedOccurrencesSurviveNoCollectorAndOnlyTransientFailuresRetry() = runTest {
        val custody = ReactionNotificationCustody()
        val radio = RadioId(UUID(0, 1))
        val otherRadio = RadioId(UUID(0, 2))
        val message = UUID(0, 3)
        val transient = UUID(0, 4)
        val suppressed = UUID(0, 5)
        custody.retain(EntityKey(radio, transient), message)
        custody.retain(EntityKey(radio, suppressed), message)
        custody.retain(EntityKey(otherRadio, transient), message)

        val attempted = mutableListOf<UUID>()
        backgroundScope.launch {
            custody.collect(radio, isCurrent = { true }) { target, occurrence, _ ->
                assertEquals(message, target)
                val seen = occurrence in attempted
                attempted += occurrence
                when {
                    occurrence == suppressed -> ReactionNotificationOutcome.SUPPRESSED
                    seen -> ReactionNotificationOutcome.POSTED
                    else -> ReactionNotificationOutcome.RETRY
                }
            }
        }
        runCurrent()
        assertEquals(listOf(transient, suppressed), attempted)
        advanceTimeBy(1_000)
        runCurrent()
        custody.retain(EntityKey(radio, suppressed), message)
        custody.retain(EntityKey(radio, transient), message)
        runCurrent()
        assertEquals(listOf(transient, suppressed, transient), attempted)
    }

    @Test fun durableWorkerPacesStorageFailuresAndDoesNotRedeliverKnownTerminalOutcomes() = runTest {
        val radio = RadioId(UUID(0, 1))
        val work = PendingReactionNotification(EntityKey(radio, UUID(0, 2)), UUID(0, 3), "storage-generation")
        val pending = mutableListOf(work)
        var failAck = true
        var reads = 0
        var acknowledgements = 0
        val journal = object : ReactionNotificationPersisting {
            override suspend fun pendingReactionNotifications(radioId: RadioId): SnapshotList<PendingReactionNotification> {
                reads++
                return SnapshotList.of(*pending.filter { it.occurrence.radioId == radioId }.toTypedArray())
            }
            override suspend fun fetchPendingReactionNotification(work: PendingReactionNotification) =
                error("Proof belongs to the actual notification consumer")
            override suspend fun acknowledgeReactionNotification(work: PendingReactionNotification) {
                acknowledgements++
                if (failAck) throw PersistenceStoreException(PersistenceStoreError.SaveFailed("ack fixture"))
                pending.remove(work)
            }
        }
        val custody = ReactionNotificationCustody(journal)
        var attempts = 0
        backgroundScope.launch {
            custody.collect(radio, { true }) { _, occurrence, token ->
                attempts++
                assertEquals(work.occurrence.id, occurrence)
                assertEquals(work.token, token)
                ReactionNotificationOutcome.POSTED
            }
        }
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(1, acknowledgements)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(2, acknowledgements)
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(2, acknowledgements)
        failAck = false
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, acknowledgements)
        assertEquals(1, attempts)
        assertEquals(2, reads)
        assertEquals(emptyList(), pending)
        custody.retain(work.occurrence, work.messageID)
        runCurrent()
        assertEquals(1, attempts)
    }

    @Test fun retryClockStartsAfterTheHeldAttemptReturns() = runTest {
        val radio = RadioId(UUID(0, 1))
        val work = PendingReactionNotification(EntityKey(radio, UUID(0, 2)), UUID(0, 3), "generation")
        val pending = mutableListOf(work)
        val release = CompletableDeferred<Unit>()
        val journal = object : ReactionNotificationPersisting {
            override suspend fun pendingReactionNotifications(radioId: RadioId) =
                SnapshotList.of(*pending.filter { it.occurrence.radioId == radioId }.toTypedArray())
            override suspend fun fetchPendingReactionNotification(work: PendingReactionNotification) =
                error("Proof belongs to the actual notification consumer")
            override suspend fun acknowledgeReactionNotification(work: PendingReactionNotification) {
                pending.remove(work)
            }
        }
        var attempts = 0
        backgroundScope.launch {
            ReactionNotificationCustody(journal).collect(radio, { true }) { _, _, _ ->
                if (++attempts == 1) {
                    release.await()
                    ReactionNotificationOutcome.RETRY
                } else ReactionNotificationOutcome.POSTED
            }
        }
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(listOf(work), pending)
        release.complete(Unit)
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(emptyList(), pending)
    }
}
