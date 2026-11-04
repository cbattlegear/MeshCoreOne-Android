// AndroidOnly: WP-202 Real Room pre-wire claims, exact attempt settlement, restart uncertainty and cleanup regressions.
package com.meshcoreone.android.core.data.repository

import androidx.room.Room
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryScope
import com.meshcoreone.android.core.contracts.domain.ChannelSubmission
import com.meshcoreone.android.core.contracts.domain.ChannelSubmissionConflict
import com.meshcoreone.android.core.contracts.domain.ChannelSubmissionReconciliation
import com.meshcoreone.android.core.contracts.domain.ChannelSubmissionPhase
import com.meshcoreone.android.core.contracts.domain.ChannelSubmissionRejected
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.QueuedChannelAttemptRejected
import com.meshcoreone.android.core.contracts.domain.QueuedChannelReadinessPersisting
import com.meshcoreone.android.core.contracts.domain.QueuedChannelRetirement
import com.meshcoreone.android.core.contracts.domain.QueuedChannelTerminalPersisting
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class QueuedChannelAttemptPersistenceTest : RepositoryTest() {
    private fun channelMessage(resend: Boolean = false) =
        message(contactID = null, channelIndex = 5u, text = "original queued text\n",
            status = if (resend) MessageStatus.SENT else MessageStatus.PENDING)
            .copy(channelOccupantID = UUID.randomUUID())

    private fun queued(row: MessageDTO, resend: Boolean = false) = PendingSendDTO.fromEnvelope(
        ChannelMessageEnvelope(row.id, 5u, resend, row.text, row.timestamp, null, row.channelOccupantID),
        row.radioId, enqueuedAt = AT,
    )

    private fun attempt(row: PendingSendDTO) = QueuedChannelAttemptKey(row.radioId, row.id, row.messageID, UUID.randomUUID())

    private suspend fun seed(row: MessageDTO, pending: PendingSendDTO = queued(row)) {
        store.saveMessage(row)
        store.insertPendingSendAssigningSequence(pending)
    }

    private suspend fun rejection(
        reason: QueuedChannelAttemptConflict, action: suspend () -> Unit,
    ) {
        val failure = assertFailsWith<PersistenceStoreException> { action() }
        assertEquals(reason, assertIs<QueuedChannelAttemptRejected>(failure.cause).reason)
    }

    private suspend fun submissionRejection(
        reason: ChannelSubmissionConflict, action: suspend () -> Unit,
    ) {
        val failure = assertFailsWith<PersistenceStoreException> { action() }
        assertEquals(reason, assertIs<ChannelSubmissionRejected>(failure.cause).reason)
    }

    private suspend fun insertReadySibling(pending: PendingSendDTO) {
        db.pendingSends().insert(pending.toEntity().copy(
            channelDispatchStateRawValue = QueuedChannelDispatchState.READY.rawValue,
            channelAttemptID = null,
        ))
    }

    @Test fun exactReadyCountChangesOnlyItsSubmissionAndNotPhysicalBackoffAttemptAccounting() = runTest {
        val row = channelMessage()
        val first = queued(row)
        val secondRow = channelMessage()
        val second = queued(secondRow)
        seed(row, first)
        seed(secondRow, second)
        val foreign = row.copy(radioId = RADIO_B)
        val foreignPending = queued(foreign)
        seed(foreign, foreignPending)
        assertEquals(1L, store.incrementReadyQueuedChannelAttemptCount(EntityKey(RADIO_A, second.id), secondRow.id))
        assertEquals(0L, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).single().attemptCount)
        assertEquals(1L, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, secondRow.id)).single().attemptCount)
        assertEquals(0L, store.fetchPendingSends(RADIO_B).single().attemptCount)
        val wire1 = attempt(second)
        store.beginQueuedChannelAttempt(wire1, assertNotNull(secondRow.channelOccupantID), 1u)
        store.settleQueuedChannelAttempt(wire1, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
        val wire2 = attempt(second)
        store.beginQueuedChannelAttempt(wire2, assertNotNull(secondRow.channelOccupantID), 2u)
        store.settleQueuedChannelAttempt(wire2, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
        assertEquals(1L, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, secondRow.id)).single().attemptCount)
        assertEquals(listOf(wire1, wire2), store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, second.id)).map { it.key })
        assertEquals(2L, store.incrementReadyQueuedChannelAttemptCount(EntityKey(RADIO_A, second.id), secondRow.id))
    }

    @Test fun concurrentReadySubmissionsForOneMessageAdmitOneExactWinner() = runTest {
        val row = channelMessage()
        store.saveMessage(row)
        val candidates = listOf(queued(row), queued(row))
        val other = newStore()
        try {
            val admitted = listOf(store, other).mapIndexed { index, repository ->
                async {
                    try {
                        repository.insertPendingSendAssigningSequence(candidates[index])
                        true
                    } catch (failure: PersistenceStoreException) {
                        assertEquals(
                            QueuedChannelAttemptConflict.RETAINED_SUBMISSION,
                            assertIs<QueuedChannelAttemptRejected>(failure.cause).reason,
                        )
                        false
                    }
                }
            }.awaitAll()
            assertEquals(1, admitted.count { it })
            val winner = store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).single()
            assertEquals(QueuedChannelDispatchState.READY, winner.channelDispatchState)
            val key = attempt(winner)
            store.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 9u)
            store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.ACCEPTED)
            store.completeAcceptedQueuedChannelAttempt(key)
            assertTrue(store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).isEmpty())
            assertTrue(store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, winner.id)).single().isCompleted)
            assertEquals(MessageStatus.SENT, store.fetchMessage(EntityKey(RADIO_A, row.id))?.status)
        } finally {
            other.close()
        }
    }

    @Test fun beginRechecksForReadySiblingAddedAfterQueuePreflightAndPreservesBothRows() = runTest {
        val row = channelMessage()
        val pending = queued(row)
        seed(row, pending)
        assertEquals(1, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).size)
        val sibling = queued(row).copy(sequence = 2)
        insertReadySibling(sibling)
        val before = store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id))

        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.beginQueuedChannelAttempt(attempt(pending), assertNotNull(row.channelOccupantID), 10u)
        }

        assertEquals(before, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)))
        assertTrue(store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, pending.id)).isEmpty())
    }

    @Test fun channelEnqueueAndUpsertRejectPreexistingDirectSiblingsWithoutChangingEitherRow() = runTest {
        val row = channelMessage()
        val direct = pending(messageID = row.id, sequence = 7)
        store.saveMessage(row)
        store.upsertPendingSend(direct)

        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.insertPendingSendAssigningSequence(queued(row))
        }
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.upsertPendingSend(queued(row).copy(sequence = 8))
        }

        assertEquals(listOf(direct), store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)))
    }

    @Test fun directInsertAndUpsertRejectChannelSiblingsBeforeAndAfterBegin() = runTest {
        val row = channelMessage()
        val channel = queued(row)
        seed(row, channel)
        val ready = store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).single()
        assertEquals(channel.id, ready.id)
        assertEquals(1L, ready.sequence)
        assertEquals(QueuedChannelDispatchState.READY, ready.channelDispatchState)
        assertNull(ready.channelAttemptID)
        assertEquals(0L, ready.attemptCount)

        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.insertPendingSendAssigningSequence(pending(messageID = row.id))
        }
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.upsertPendingSend(pending(messageID = row.id))
        }
        assertEquals(ready, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).single())

        val key = attempt(channel)
        store.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 11u)
        val dispatching = store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).single()
        assertEquals(channel.id, dispatching.id)
        assertEquals(ready.sequence, dispatching.sequence)
        assertEquals(QueuedChannelDispatchState.DISPATCHING, dispatching.channelDispatchState)
        assertEquals(key.attemptID, dispatching.channelAttemptID)
        assertEquals(0L, dispatching.attemptCount)
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.insertPendingSendAssigningSequence(pending(messageID = row.id))
        }
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.upsertPendingSend(pending(messageID = row.id))
        }
        assertEquals(dispatching, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).single())
        assertEquals(key, assertNotNull(store.fetchQueuedChannelAttempt(key)).key)
    }

    @Test fun directOnlyUpsertRemainsReplaceableForTheSameIdentity() = runTest {
        val first = pending(sequence = 1)
        val updated = first.copy(messageText = "updated direct payload", attemptCount = 4)

        store.upsertPendingSend(first)
        store.upsertPendingSend(updated)

        assertEquals(listOf(updated), store.fetchPendingSends(RADIO_A))
    }

    @Test fun directPendingIdentityCannotBeReboundToAnotherMessage() = runTest {
        val firstMessageID = UUID.randomUUID()
        val otherMessageID = UUID.randomUUID()
        val pendingID = UUID.randomUUID()
        val original = pending(messageID = firstMessageID, sequence = 1).copy(id = pendingID)
        store.upsertPendingSend(original)
        val sameMessageUpdate = original.copy(messageText = "same-message update", attemptCount = 3)
        store.upsertPendingSend(sameMessageUpdate)
        val rebind = pending(messageID = otherMessageID, sequence = 2).copy(id = pendingID)

        rejection(QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION) {
            store.upsertPendingSend(rebind)
        }
        rejection(QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION) {
            store.insertPendingSendAssigningSequence(rebind)
        }
        rejection(QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION) {
            store.replacePendingSendForRetry(otherMessageID, rebind)
        }

        assertEquals(listOf(sameMessageUpdate),
            store.fetchPendingSendsForMessage(EntityKey(RADIO_A, firstMessageID)))
        assertTrue(store.fetchPendingSendsForMessage(EntityKey(RADIO_A, otherMessageID)).isEmpty())
    }

    @Test fun explicitChannelUpsertPreservesUnknownDispatchStateAndCannotBeginOrBump() = runTest {
        val row = channelMessage()
        val explicit = queued(row).copy(sequence = 9)
        store.saveMessage(row)

        store.upsertPendingSend(explicit)

        val stored = store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).single()
        assertEquals(explicit, stored)
        assertNull(stored.channelDispatchState)
        rejection(QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION) {
            store.incrementPendingSendAttemptCount(EntityKey(RADIO_A, row.id))
        }
        rejection(QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION) {
            store.beginQueuedChannelAttempt(attempt(explicit), assertNotNull(row.channelOccupantID), 15u)
        }
        assertEquals(stored, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).single())
        assertTrue(store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, explicit.id)).isEmpty())
    }

    @Test fun explicitUpsertCannotReusePendingIdentityWithRetainedChannelAttempt() = runTest {
        val row = channelMessage()
        val original = queued(row)
        seed(row, original)
        val key = attempt(original)
        store.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 16u)
        store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
        store.retireDefinitelyUnsentQueuedChannelAttempt(key)
        assertTrue(store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).isEmpty())
        val retained = store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, original.id))
        assertEquals(listOf(key), retained.map { it.key })

        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.upsertPendingSend(original)
        }

        assertTrue(store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).isEmpty())
        assertEquals(retained, store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, original.id)))
    }

    @Test fun retainedChannelAttemptIdentityCannotBeReusedForDirectInsertOrRetry() = runTest {
        val channelMessage = channelMessage()
        val channelPending = queued(channelMessage)
        seed(channelMessage, channelPending)
        val key = attempt(channelPending)
        store.beginQueuedChannelAttempt(key, assertNotNull(channelMessage.channelOccupantID), 17u)
        store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
        store.retireDefinitelyUnsentQueuedChannelAttempt(key)
        assertTrue(store.fetchPendingSendsForMessage(EntityKey(RADIO_A, channelMessage.id)).isEmpty())
        val retained = store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, channelPending.id))
        assertEquals(listOf(key), retained.map { it.key })

        val otherMessageID = UUID.randomUUID()
        val direct = pending(messageID = otherMessageID).copy(id = channelPending.id)
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.insertPendingSendAssigningSequence(direct)
        }
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.replacePendingSendForRetry(otherMessageID, direct)
        }

        assertTrue(store.fetchPendingSendsForMessage(EntityKey(RADIO_A, otherMessageID)).isEmpty())
        assertTrue(store.fetchPendingSendsForMessage(EntityKey(RADIO_A, channelMessage.id)).isEmpty())
        assertEquals(retained, store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, channelPending.id)))
    }

    @Test fun retainedChannelAttemptIdentityIsScopedToItsRadioAndDirectOnlyRetryRemainsSupported() = runTest {
        val channelMessage = channelMessage()
        val channelPending = queued(channelMessage)
        seed(channelMessage, channelPending)
        val key = attempt(channelPending)
        store.beginQueuedChannelAttempt(key, assertNotNull(channelMessage.channelOccupantID), 18u)
        store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
        store.retireDefinitelyUnsentQueuedChannelAttempt(key)

        val firstMessageID = UUID.randomUUID()
        val first = pending(RADIO_B, messageID = firstMessageID).copy(id = channelPending.id, sequence = 1)
        assertEquals(1L, store.insertPendingSendAssigningSequence(first))
        val retryMessageID = UUID.randomUUID()
        val directRetry = pending(RADIO_A, messageID = retryMessageID)
        assertEquals(1L, store.replacePendingSendForRetry(retryMessageID, directRetry))

        assertEquals(listOf(first.copy(sequence = 1)), store.fetchPendingSendsForMessage(EntityKey(RADIO_B, firstMessageID)))
        assertEquals(listOf(directRetry.copy(sequence = 1)), store.fetchPendingSendsForMessage(EntityKey(RADIO_A, retryMessageID)))
        assertEquals(listOf(key), store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, channelPending.id)).map { it.key })
    }

    @Test fun rejectedDirectRetryWithRetainedChannelIDPreservesFailedMessageAndExistingPendingRow() = runTest {
        val channelMessage = channelMessage()
        val channelPending = queued(channelMessage)
        seed(channelMessage, channelPending)
        val key = attempt(channelPending)
        store.beginQueuedChannelAttempt(key, assertNotNull(channelMessage.channelOccupantID), 19u)
        store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
        store.retireDefinitelyUnsentQueuedChannelAttempt(key)
        val retained = store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, channelPending.id))
        assertEquals(listOf(key), retained.map { it.key })

        val directMessage = message(text = "failed direct message", status = MessageStatus.FAILED)
        store.saveMessage(directMessage)
        val previous = pending(messageID = directMessage.id).copy(
            id = UUID.fromString("00000000-0000-0000-0000-000000000101"),
        )
        store.insertPendingSendAssigningSequence(previous)
        val before = store.fetchPendingSendsForMessage(EntityKey(RADIO_A, directMessage.id))
        val collidingRetry = pending(messageID = directMessage.id).copy(id = channelPending.id)

        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.replacePendingSendForRetry(directMessage.id, collidingRetry)
        }

        assertEquals(before, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, directMessage.id)))
        assertEquals(MessageStatus.FAILED, assertNotNull(
            store.fetchMessage(EntityKey(RADIO_A, directMessage.id)),
        ).status)
        assertEquals(retained, store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, channelPending.id)))
    }

    @Test fun directRetryReplacesExistingPendingRowAndUpdatesFailedMessageStatus() = runTest {
        val directMessage = message(text = "retry this direct message", status = MessageStatus.FAILED)
        store.saveMessage(directMessage)
        val previous = pending(messageID = directMessage.id).copy(
            id = UUID.fromString("00000000-0000-0000-0000-000000000201"),
        )
        store.insertPendingSendAssigningSequence(previous)
        val previousStored = store.fetchPendingSendsForMessage(EntityKey(RADIO_A, directMessage.id)).single()
        val replacement = pending(messageID = directMessage.id).copy(
            id = UUID.fromString("00000000-0000-0000-0000-000000000202"),
        )

        val sequence = store.replacePendingSendForRetry(directMessage.id, replacement)

        assertEquals(1L, sequence)
        assertEquals(listOf(replacement.copy(sequence = sequence)),
            store.fetchPendingSendsForMessage(EntityKey(RADIO_A, directMessage.id)))
        assertNotEquals(previousStored.id, replacement.id)
        assertEquals(MessageStatus.PENDING, assertNotNull(
            store.fetchMessage(EntityKey(RADIO_A, directMessage.id)),
        ).status)
    }

    @Test fun retainedAttemptIdentityBlocksMutationAndDeletionOfExistingDirectRow() = runTest {
        val channelMessage = channelMessage()
        val channelPending = queued(channelMessage)
        seed(channelMessage, channelPending)
        val key = attempt(channelPending)
        store.beginQueuedChannelAttempt(key, assertNotNull(channelMessage.channelOccupantID), 20u)
        store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
        store.retireDefinitelyUnsentQueuedChannelAttempt(key)
        val retained = store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, channelPending.id))
        assertEquals(listOf(key), retained.map { it.key })

        val directMessage = message(text = "retained direct row", status = MessageStatus.FAILED)
        store.saveMessage(directMessage)
        val direct = pending(messageID = directMessage.id).copy(id = channelPending.id)
        val directEntity = direct.toEntity()
        db.pendingSends().insert(directEntity)
        val status = assertNotNull(store.fetchMessage(EntityKey(RADIO_A, directMessage.id))).status

        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.upsertPendingSend(direct.copy(messageText = "attempted overwrite"))
        }
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.incrementPendingSendAttemptCount(EntityKey(RADIO_A, direct.messageID))
        }
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.deletePendingSend(EntityKey(RADIO_A, direct.id))
        }
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.deletePendingSendsForMessage(EntityKey(RADIO_A, direct.messageID))
        }
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.replacePendingSendForRetry(direct.messageID, pending(messageID = direct.messageID))
        }
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.deleteMessage(EntityKey(RADIO_A, direct.messageID))
        }

        assertEquals(directEntity, db.pendingSends().byId(RADIO_A.value, direct.id))
        assertEquals(status, store.fetchMessage(EntityKey(RADIO_A, directMessage.id))?.status)
        assertEquals(retained, store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, channelPending.id)))
    }

    @Test fun retainedAttemptIdentityKeepsDirectRowsOutOfOrphanAndLegacyPurges() = runTest {
        val channelMessage = channelMessage()
        val channelPending = queued(channelMessage)
        seed(channelMessage, channelPending)
        val key = attempt(channelPending)
        store.beginQueuedChannelAttempt(key, assertNotNull(channelMessage.channelOccupantID), 21u)
        store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
        store.retireDefinitelyUnsentQueuedChannelAttempt(key)

        val directMessage = message(text = "orphan legacy direct row", status = MessageStatus.FAILED)
        store.saveMessage(directMessage)
        val direct = pending(messageID = directMessage.id, attempt = null).copy(id = channelPending.id)
        val directEntity = direct.toEntity()
        db.pendingSends().insert(directEntity)
        val issueCount = issues.size

        assertEquals(0L, store.purgeOrphanPendingSends())
        assertEquals(0L, store.purgeLegacyAttemptCountRows())

        assertEquals(directEntity, db.pendingSends().byId(RADIO_A.value, direct.id))
        assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(RADIO_A, directMessage.id))?.status)
        assertEquals(listOf(key), store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, channelPending.id)).map { it.key })
        assertEquals(2, issues.size - issueCount)
        assertTrue(issues.drop(issueCount).all {
            assertIs<QueuedChannelAttemptRejected>(it.cause).reason ==
                QueuedChannelAttemptConflict.RETAINED_SUBMISSION
        })
    }

    @Test fun attemptHistoryIsRadioScopedForExistingDirectUpdateCountAndDelete() = runTest {
        val channelMessage = channelMessage()
        val channelPending = queued(channelMessage)
        seed(channelMessage, channelPending)
        val key = attempt(channelPending)
        store.beginQueuedChannelAttempt(key, assertNotNull(channelMessage.channelOccupantID), 22u)
        store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
        store.retireDefinitelyUnsentQueuedChannelAttempt(key)
        val retained = store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, channelPending.id))

        val directMessage = message(radio = RADIO_B, text = "other radio direct", status = MessageStatus.FAILED)
        store.saveMessage(directMessage)
        val direct = pending(RADIO_B, messageID = directMessage.id).copy(id = channelPending.id)
        store.insertPendingSendAssigningSequence(direct)
        val updated = direct.copy(messageText = "updated direct payload", sequence = 1)

        store.upsertPendingSend(updated)
        assertEquals(1L, store.incrementPendingSendAttemptCount(EntityKey(RADIO_B, direct.messageID)))
        assertEquals(updated.copy(attemptCount = 1),
            store.fetchPendingSendsForMessage(EntityKey(RADIO_B, direct.messageID)).single())
        store.deletePendingSend(EntityKey(RADIO_B, direct.id))

        assertTrue(store.fetchPendingSendsForMessage(EntityKey(RADIO_B, direct.messageID)).isEmpty())
        assertEquals(retained, store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, channelPending.id)))
    }

    @Test fun globallyKeyedSubmissionReceiptBlocksCollidingDirectRowsAcrossRadios() = runTest {
        val channelMessage = channelMessage()
        val channelPending = queued(channelMessage)
        store.saveMessage(channelMessage)
        val envelope = ChannelMessageEnvelope(
            channelMessage.id, 5u, false, channelMessage.text, channelMessage.timestamp,
            "retained node", channelMessage.channelOccupantID,
        )
        val submission = ChannelSubmission(
            channelPending.id,
            ChannelHistoryScope(RADIO_A, 5u, assertNotNull(channelMessage.channelOccupantID)),
            envelope,
        )
        val evidence = store.admitChannelSubmission(submission, AT)
        store.upsertPendingSend(assertNotNull(evidence.pending))

        val directMessage = message(radio = RADIO_B, text = "cross-radio direct", status = MessageStatus.FAILED)
        store.saveMessage(directMessage)
        val direct = pending(RADIO_B, messageID = directMessage.id, attempt = null).copy(id = channelPending.id)
        val directEntity = direct.toEntity()
        db.pendingSends().insert(directEntity)

        submissionRejection(ChannelSubmissionConflict.IDENTITY_CONFLICT) {
            store.upsertPendingSend(direct.copy(messageText = "attempted overwrite"))
        }
        submissionRejection(ChannelSubmissionConflict.IDENTITY_CONFLICT) {
            store.incrementPendingSendAttemptCount(EntityKey(RADIO_B, direct.messageID))
        }
        submissionRejection(ChannelSubmissionConflict.IDENTITY_CONFLICT) {
            store.deletePendingSend(EntityKey(RADIO_B, direct.id))
        }
        submissionRejection(ChannelSubmissionConflict.IDENTITY_CONFLICT) {
            store.deletePendingSendsForMessage(EntityKey(RADIO_B, direct.messageID))
        }
        submissionRejection(ChannelSubmissionConflict.IDENTITY_CONFLICT) {
            store.replacePendingSendForRetry(direct.messageID, pending(RADIO_B, messageID = direct.messageID))
        }
        submissionRejection(ChannelSubmissionConflict.IDENTITY_CONFLICT) {
            store.deleteMessage(EntityKey(RADIO_B, direct.messageID))
        }
        val issueCount = issues.size
        assertEquals(0L, store.purgeOrphanPendingSends())
        assertEquals(0L, store.purgeLegacyAttemptCountRows())

        assertEquals(directEntity, db.pendingSends().byId(RADIO_B.value, direct.id))
        assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(RADIO_B, direct.messageID))?.status)
        assertEquals(evidence, store.reconcileChannelSubmission(submission).let {
            assertIs<ChannelSubmissionReconciliation.Known>(it).evidence
        })
        assertEquals(2, issues.size - issueCount)
        assertTrue(issues.takeLast(2).all { failure ->
            val cause = failure.cause
            cause is ChannelSubmissionRejected && cause.reason == ChannelSubmissionConflict.IDENTITY_CONFLICT
        })
    }

    @Test fun channelSubmissionReceiptMustRemainAdmittedAndMatchPendingIntentBeforeMutationOrBegin() = runTest {
        val channelMessage = channelMessage()
        store.saveMessage(channelMessage)
        val pending = queued(channelMessage)
        val submission = ChannelSubmission(
            pending.id,
            ChannelHistoryScope(RADIO_A, 5u, assertNotNull(channelMessage.channelOccupantID)),
            ChannelMessageEnvelope(
                channelMessage.id, 5u, false, channelMessage.text, channelMessage.timestamp,
                "receipt node", channelMessage.channelOccupantID,
            ),
        )
        val evidence = store.admitChannelSubmission(submission, AT)
        val originalPending = assertNotNull(evidence.pending)
        val receipt = assertNotNull(db.channelSubmissions().byId(pending.id))
        val previousAttempt = attempt(originalPending)
        store.beginQueuedChannelAttempt(previousAttempt, assertNotNull(channelMessage.channelOccupantID), 22u)
        store.settleQueuedChannelAttempt(previousAttempt, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
        assertEquals(originalPending,
            store.fetchPendingSendsForMessage(EntityKey(RADIO_A, channelMessage.id)).single())
        val previousAttempts = store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, pending.id))
        val mismatches = listOf(
            receipt.copy(radioId = RADIO_B.value),
            receipt.copy(messageID = UUID.randomUUID()),
            receipt.copy(channelIndex = 6),
            receipt.copy(channelOccupantID = UUID.randomUUID()),
            receipt.copy(messageText = "different receipt text"),
            receipt.copy(messageTimestamp = receipt.messageTimestamp + 1),
            receipt.copy(localNodeName = "different node"),
            receipt.copy(isResend = true),
            receipt.copy(sequence = receipt.sequence + 1),
            receipt.copy(enqueuedSeconds = receipt.enqueuedSeconds + 1),
        ).map { it to ChannelSubmissionConflict.IDENTITY_CONFLICT } + listOf(
            receipt.copy(phaseRawValue = ChannelSubmissionPhase.ACCEPTED_COMPLETED.rawValue) to
                ChannelSubmissionConflict.IDENTITY_CONFLICT,
            receipt.copy(phaseRawValue = ChannelSubmissionPhase.RETIRED.rawValue) to
                ChannelSubmissionConflict.IDENTITY_CONFLICT,
            receipt.copy(phaseRawValue = 3) to ChannelSubmissionConflict.REDACTED_SUBMISSION,
        )

        for ((invalid, reason) in mismatches) {
            db.channelSubmissions().upsert(invalid)
            submissionRejection(reason) { store.upsertPendingSend(originalPending) }
            submissionRejection(reason) {
                store.incrementReadyQueuedChannelAttemptCount(EntityKey(RADIO_A, pending.id), channelMessage.id)
            }
            submissionRejection(reason) {
                store.beginQueuedChannelAttempt(
                    attempt(originalPending), assertNotNull(channelMessage.channelOccupantID), 23u,
                )
            }
            assertEquals(originalPending, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, channelMessage.id)).single())
            assertEquals(channelMessage, store.fetchMessage(EntityKey(RADIO_A, channelMessage.id)))
            assertEquals(invalid, db.channelSubmissions().byId(pending.id))
            assertEquals(previousAttempts, store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, pending.id)))
        }
    }

    @Test fun matchingAdmittedReceiptAllowsChannelReplayAndUnreceiptedLegacyUpsertStaysUnknown() = runTest {
        val channelMessage = channelMessage()
        store.saveMessage(channelMessage)
        val pending = queued(channelMessage)
        val submission = ChannelSubmission(
            pending.id,
            ChannelHistoryScope(RADIO_A, 5u, assertNotNull(channelMessage.channelOccupantID)),
            ChannelMessageEnvelope(
                channelMessage.id, 5u, false, channelMessage.text, channelMessage.timestamp,
                "receipt node", channelMessage.channelOccupantID,
            ),
        )
        val evidence = store.admitChannelSubmission(submission, AT)
        val persisted = assertNotNull(evidence.pending)

        store.upsertPendingSend(persisted)
        assertEquals(1L,
            store.incrementReadyQueuedChannelAttemptCount(EntityKey(RADIO_A, pending.id), channelMessage.id))
        val attemptKey = attempt(persisted)
        store.beginQueuedChannelAttempt(attemptKey, assertNotNull(channelMessage.channelOccupantID), 24u)
        assertEquals(listOf(attemptKey), store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, pending.id)).map { it.key })

        val legacyMessage = channelMessage().copy(id = UUID.randomUUID())
        val legacyPending = queued(legacyMessage)
        store.saveMessage(legacyMessage)
        store.upsertPendingSend(legacyPending)
        val storedLegacy = store.fetchPendingSendsForMessage(EntityKey(RADIO_A, legacyMessage.id)).single()
        assertNull(storedLegacy.channelDispatchState)
        rejection(QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION) {
            store.beginQueuedChannelAttempt(
                attempt(storedLegacy), assertNotNull(legacyMessage.channelOccupantID), 25u,
            )
        }
        assertEquals(storedLegacy, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, legacyMessage.id)).single())
        assertTrue(store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, storedLegacy.id)).isEmpty())
    }

    @Test fun changedReceiptDoesNotBlockSettlementOfAlreadyJournaledAttempt() = runTest {
        val channelMessage = channelMessage()
        val channelPending = queued(channelMessage)
        store.saveMessage(channelMessage)
        val submission = ChannelSubmission(
            channelPending.id,
            ChannelHistoryScope(RADIO_A, 5u, assertNotNull(channelMessage.channelOccupantID)),
            ChannelMessageEnvelope(
                channelMessage.id, 5u, false, channelMessage.text, channelMessage.timestamp,
                "recovery node", channelMessage.channelOccupantID,
            ),
        )
        val evidence = store.admitChannelSubmission(submission, AT)
        val key = attempt(assertNotNull(evidence.pending))
        store.beginQueuedChannelAttempt(key, assertNotNull(channelMessage.channelOccupantID), 29u)
        val invalidReceipt = assertNotNull(db.channelSubmissions().byId(key.pendingID))
            .copy(phaseRawValue = ChannelSubmissionPhase.RETIRED.rawValue)
        db.channelSubmissions().upsert(invalidReceipt)

        assertEquals(
            QueuedChannelAttemptPhase.DEFINITELY_UNSENT,
            store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT).phase,
        )
        submissionRejection(ChannelSubmissionConflict.IDENTITY_CONFLICT) {
            store.beginQueuedChannelAttempt(key.copy(attemptID = UUID.randomUUID()),
                assertNotNull(channelMessage.channelOccupantID), 30u)
        }
        assertEquals(invalidReceipt, db.channelSubmissions().byId(key.pendingID))
        assertEquals(listOf(key), store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, key.pendingID)).map { it.key })
        assertEquals(QueuedChannelDispatchState.READY,
            store.fetchPendingSendsForMessage(EntityKey(RADIO_A, channelMessage.id)).single().channelDispatchState)
    }

    @Test fun replayRejectsPendingSequenceOrEnqueueTimeThatDisagreesWithReceipt() = runTest {
        val channelMessage = channelMessage()
        store.saveMessage(channelMessage)
        val pending = queued(channelMessage)
        val submission = ChannelSubmission(
            pending.id,
            ChannelHistoryScope(RADIO_A, 5u, assertNotNull(channelMessage.channelOccupantID)),
            ChannelMessageEnvelope(
                channelMessage.id, 5u, false, channelMessage.text, channelMessage.timestamp,
                "receipt node", channelMessage.channelOccupantID,
            ),
        )
        val evidence = store.admitChannelSubmission(submission, AT.plusNanos(123))
        val originalPending = assertNotNull(evidence.pending)
        val receipt = assertNotNull(db.channelSubmissions().byId(pending.id))
        val originalEntity = assertNotNull(db.pendingSends().byId(RADIO_A.value, pending.id))

        val inconsistentRows = listOf(
            originalEntity.copy(sequence = originalEntity.sequence + 1),
            originalEntity.copy(
                enqueuedSeconds = originalEntity.enqueuedSeconds + 1,
                enqueuedNanos = originalEntity.enqueuedNanos,
            ),
        )
        for (inconsistent in inconsistentRows) {
            db.pendingSends().upsert(inconsistent)

            submissionRejection(ChannelSubmissionConflict.IDENTITY_CONFLICT) {
                store.admitChannelSubmission(submission, AT.plusSeconds(10))
            }
            submissionRejection(ChannelSubmissionConflict.IDENTITY_CONFLICT) {
                store.reconcileChannelSubmission(submission)
            }

            assertEquals(inconsistent, db.pendingSends().byId(RADIO_A.value, pending.id))
            assertEquals(receipt, db.channelSubmissions().byId(pending.id))
            assertEquals(channelMessage, store.fetchMessage(EntityKey(RADIO_A, channelMessage.id)))
            assertTrue(store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, pending.id)).isEmpty())
        }
        assertEquals(originalPending.sequence, evidence.sequence)
        assertEquals(AT.plusNanos(123), evidence.enqueuedAt)
    }

    @Test fun contactCascadePreservesDirectRowAndMessageWhenAttemptIdentityConflicts() = runTest {
        val contactRow = contact()
        store.saveContact(contactRow)
        val channelMessage = channelMessage()
        val channelPending = queued(channelMessage)
        seed(channelMessage, channelPending)
        val key = attempt(channelPending)
        store.beginQueuedChannelAttempt(key, assertNotNull(channelMessage.channelOccupantID), 26u)
        store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
        store.retireDefinitelyUnsentQueuedChannelAttempt(key)
        val retained = store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, channelPending.id))

        val directMessage = message(contactID = contactRow.id, status = MessageStatus.FAILED)
        store.saveMessage(directMessage)
        val direct = pending(messageID = directMessage.id).copy(id = channelPending.id)
        val directEntity = direct.toEntity()
        db.pendingSends().insert(directEntity)

        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.deleteContact(EntityKey(RADIO_A, contactRow.id))
        }

        assertEquals(contactRow, store.fetchContact(EntityKey(RADIO_A, contactRow.id)))
        assertEquals(directMessage, store.fetchMessage(EntityKey(RADIO_A, directMessage.id)))
        assertEquals(directEntity, db.pendingSends().byId(RADIO_A.value, direct.id))
        assertEquals(retained, store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, channelPending.id)))
    }

    @Test fun unresolvedChannelRemovalDoesNotFailDirectMessageBeforeIdentityConflictCheck() = runTest {
        val channelID = store.saveChannel(
            RADIO_A, ChannelInfo(5u, "Channel", Bytes(ByteArray(16) { 0x31 })),
        )
        val channelMessage = channelMessage().copy(
            channelOccupantID = assertNotNull(store.fetchChannelOccupantID(RADIO_A, 5u)),
        )
        val channelPending = queued(channelMessage)
        seed(channelMessage, channelPending)
        val key = attempt(channelPending)
        store.beginQueuedChannelAttempt(key, assertNotNull(channelMessage.channelOccupantID), 27u)
        store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
        store.retireDefinitelyUnsentQueuedChannelAttempt(key)
        store.updateMessageStatus(EntityKey(RADIO_A, channelMessage.id), MessageStatus.PENDING)
        val unresolved = assertNotNull(store.fetchMessage(EntityKey(RADIO_A, channelMessage.id)))
        assertEquals(MessageStatus.PENDING, unresolved.status)
        val retained = store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, channelPending.id))
        val direct = pending(messageID = channelMessage.id).copy(id = channelPending.id)
        val directEntity = direct.toEntity()
        db.pendingSends().insert(directEntity)

        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.deleteChannel(EntityKey(RADIO_A, channelID))
        }

        assertNotNull(store.fetchChannel(EntityKey(RADIO_A, channelID)))
        assertEquals(unresolved, store.fetchMessage(EntityKey(RADIO_A, channelMessage.id)))
        assertEquals(directEntity, db.pendingSends().byId(RADIO_A.value, direct.id))
        assertEquals(retained, store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, channelPending.id)))
    }

    @Test fun explicitFullRadioRemovalClearsCollidingRowsAndAttemptsAndRedactsReceipt() = runTest {
        val radioDevice = device()
        store.saveDevice(radioDevice)
        val channelMessage = channelMessage()
        store.saveMessage(channelMessage)
        val channelPending = queued(channelMessage)
        val submission = ChannelSubmission(
            channelPending.id,
            ChannelHistoryScope(RADIO_A, 5u, assertNotNull(channelMessage.channelOccupantID)),
            ChannelMessageEnvelope(
                channelMessage.id, 5u, false, channelMessage.text, channelMessage.timestamp,
                "radio removal", channelMessage.channelOccupantID,
            ),
        )
        val evidence = store.admitChannelSubmission(submission, AT)
        val key = attempt(assertNotNull(evidence.pending))
        store.beginQueuedChannelAttempt(key, assertNotNull(channelMessage.channelOccupantID), 28u)
        store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
        store.retireDefinitelyUnsentQueuedChannelAttempt(key)
        val foreign = pending(RADIO_B)
        store.insertPendingSendAssigningSequence(foreign)
        val persistedForeign = assertNotNull(
            store.fetchPendingSendsForMessage(EntityKey(RADIO_B, foreign.messageID)).singleOrNull(),
        )
        assertEquals(foreign.id, persistedForeign.id)
        assertEquals(1L, persistedForeign.sequence)
        assertEquals(foreign.copy(sequence = persistedForeign.sequence), persistedForeign)
        val directMessage = message(text = "radio-owned direct", status = MessageStatus.FAILED)
        store.saveMessage(directMessage)
        val collision = pending(messageID = directMessage.id).copy(id = channelPending.id)
        db.pendingSends().insert(collision.toEntity())

        store.deleteDeviceData(radioDevice.id)

        assertTrue(store.fetchPendingSends(RADIO_A).isEmpty())
        assertTrue(store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, channelPending.id)).isEmpty())
        assertNull(store.fetchMessage(EntityKey(RADIO_A, directMessage.id)))
        assertEquals(persistedForeign,
            store.fetchPendingSendsForMessage(EntityKey(RADIO_B, foreign.messageID)).single())
        val redacted = assertNotNull(db.channelSubmissions().byId(channelPending.id))
        assertEquals(3L, redacted.phaseRawValue)
        assertNull(redacted.messageText)
    }

    @Test fun directRetryCannotDeleteOrReclassifyUnknownSiblingRows() = runTest {
        val row = channelMessage()
        val unknownDTO = pending(messageID = row.id, sequence = 1)
        val unknown = unknownDTO.toEntity()
            .copy(kindRawValue = 99, contactID = null)
        db.pendingSends().insert(unknown)
        val retry = pending(messageID = row.id, sequence = 2)

        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.upsertPendingSend(unknownDTO)
        }
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.replacePendingSendForRetry(row.id, retry)
        }
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.upsertPendingSend(retry)
        }

        assertEquals(listOf(unknown), db.pendingSends().forMessage(RADIO_A.value, row.id))
    }

    @Test fun channelRetryCannotDeleteDirectSiblingRows() = runTest {
        val row = channelMessage().copy(status = MessageStatus.FAILED)
        val channel = queued(row)
        store.saveMessage(row)
        store.insertPendingSendAssigningSequence(channel)
        val direct = pending(messageID = row.id, sequence = 2).toEntity()
        db.pendingSends().insert(direct)
        val before = db.pendingSends().forMessage(RADIO_A.value, row.id)

        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.replacePendingSendForRetry(row.id, queued(row, resend = true))
        }

        assertEquals(before, db.pendingSends().forMessage(RADIO_A.value, row.id))
        assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(RADIO_A, row.id))?.status)
    }

    @Test fun readyCountAndBeginRejectDirectSiblingAppearingAfterPreflight() = runTest {
        val row = channelMessage()
        val channel = queued(row)
        seed(row, channel)
        val ready = store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).single()
        assertEquals(channel.id, ready.id)
        assertEquals(1L, ready.sequence)
        assertEquals(QueuedChannelDispatchState.READY, ready.channelDispatchState)
        assertNull(ready.channelAttemptID)
        assertEquals(0L, ready.attemptCount)
        val direct = pending(messageID = row.id, sequence = 2)
        db.pendingSends().insert(direct.toEntity())

        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.incrementReadyQueuedChannelAttemptCount(EntityKey(RADIO_A, channel.id), row.id)
        }
        val afterRejectedCount = store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).single { it.id == channel.id }
        assertEquals(ready, afterRejectedCount)
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.beginQueuedChannelAttempt(attempt(channel), assertNotNull(row.channelOccupantID), 12u)
        }

        assertEquals(ready, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).single { it.id == channel.id })
        assertEquals(setOf(channel.id, direct.id),
            store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).map { it.id }.toSet())
        assertTrue(store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, channel.id)).isEmpty())
    }

    @Test fun genericQueueCountRejectsMixedSiblingsAfterPreflightWithoutBumpingEitherRow() = runTest {
        val row = channelMessage()
        val channel = queued(row)
        seed(row, channel)
        assertEquals(1, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).size)
        val direct = pending(messageID = row.id, sequence = 2).toEntity()
        db.pendingSends().insert(direct)

        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.incrementPendingSendAttemptCount(EntityKey(RADIO_A, row.id))
        }

        assertEquals(setOf(channel.id, direct.id),
            store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).map { it.id }.toSet())
        assertTrue(store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).all { it.attemptCount == 0L })
    }

    @Test fun genericQueueCountRejectsMixedSiblingsWhenDirectRowWouldBeFirst() = runTest {
        val row = channelMessage()
        val channel = queued(row)
        seed(row, channel)
        val direct = pending(messageID = row.id, sequence = 0).toEntity()
        db.pendingSends().insert(direct)

        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.incrementPendingSendAttemptCount(EntityKey(RADIO_A, row.id))
        }

        assertEquals(0L, db.pendingSends().byId(RADIO_A.value, direct.id)?.attemptCount)
        assertEquals(0L, db.pendingSends().byId(RADIO_A.value, channel.id)?.attemptCount)
    }

    @Test fun genericQueueCountPreservesTheFirstDirectOnlyRowBehavior() = runTest {
        val first = pending(sequence = 1, attempt = 2)
        val second = pending(messageID = first.messageID, sequence = 2, attempt = 5)
        store.upsertPendingSend(first)
        store.upsertPendingSend(second)

        assertEquals(3L, store.incrementPendingSendAttemptCount(EntityKey(RADIO_A, first.messageID)))

        assertEquals(3L, store.fetchPendingSends(RADIO_A).single { it.id == first.id }.attemptCount)
        assertEquals(5L, store.fetchPendingSends(RADIO_A).single { it.id == second.id }.attemptCount)
    }

    @Test fun genericQueueCountDoesNotMutateLegacyOrInFlightChannelRows() = runTest {
        val legacyRow = channelMessage().copy(channelOccupantID = null)
        val legacyPending = queued(legacyRow)
        seed(legacyRow, legacyPending)
        rejection(QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION) {
            store.incrementPendingSendAttemptCount(EntityKey(RADIO_A, legacyRow.id))
        }
        assertEquals(0L, store.fetchPendingSends(RADIO_A).single { it.id == legacyPending.id }.attemptCount)

        val activeRow = channelMessage()
        val active = queued(activeRow)
        seed(activeRow, active)
        val key = attempt(active)
        store.beginQueuedChannelAttempt(key, assertNotNull(activeRow.channelOccupantID), 14u)
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.incrementPendingSendAttemptCount(EntityKey(RADIO_A, activeRow.id))
        }
        assertEquals(0L, store.fetchPendingSends(RADIO_A).single { it.id == active.id }.attemptCount)
        assertEquals(QueuedChannelDispatchState.DISPATCHING,
            store.fetchPendingSends(RADIO_A).single { it.id == active.id }.channelDispatchState)
    }

    @Test fun standalonePurgesStillRemoveUnambiguousDirectOrphanAndLegacyRows() = runTest {
        store.saveDevice(device())
        val orphan = pending(RADIO_B)
        val legacy = pending(attempt = null)
        store.upsertPendingSend(orphan)
        store.upsertPendingSend(legacy)

        assertEquals(1L, store.purgeOrphanPendingSends())
        assertEquals(0L, store.purgeOrphanPendingSends())
        assertTrue(store.fetchPendingSends(RADIO_B).isEmpty())
        assertEquals(1L, store.purgeLegacyAttemptCountRows())
        assertEquals(0L, store.purgeLegacyAttemptCountRows())
        assertTrue(store.fetchPendingSends(RADIO_A).isEmpty())
    }

    @Test fun warmUpRetainsAndReportsAmbiguousAndUnknownPurgeCandidates() = runTest {
        store.saveDevice(device())
        val orphanChannel = channelMessage().copy(radioId = RADIO_B)
        val channel = queued(orphanChannel)
        seed(orphanChannel, channel)
        val orphanDirect = pending(RADIO_B, messageID = orphanChannel.id, attempt = null)
        val directEntity = orphanDirect.toEntity()
        db.pendingSends().insert(directEntity)
        val unknownEntity = pending(RADIO_B, messageID = orphanChannel.id, attempt = null).toEntity()
            .copy(kindRawValue = 99, contactID = null)
        db.pendingSends().insert(unknownEntity)
        val standaloneOrphan = pending(RADIO_B)
        store.upsertPendingSend(standaloneOrphan)
        val standaloneLegacy = pending(attempt = null)
        store.upsertPendingSend(standaloneLegacy)

        store.warmUp()

        assertEquals(directEntity, db.pendingSends().byId(RADIO_B.value, directEntity.id))
        assertEquals(channel.toEntity().copy(
            sequence = 1,
            channelDispatchStateRawValue = QueuedChannelDispatchState.READY.rawValue,
        ), db.pendingSends().byId(RADIO_B.value, channel.id))
        assertEquals(unknownEntity, db.pendingSends().byId(RADIO_B.value, unknownEntity.id))
        assertNull(db.pendingSends().byId(RADIO_B.value, standaloneOrphan.id))
        assertNull(db.pendingSends().byId(RADIO_A.value, standaloneLegacy.id))
        val reported = issues.mapNotNull { it.cause as? QueuedChannelAttemptRejected }.map { it.reason }
        assertTrue(QueuedChannelAttemptConflict.RETAINED_SUBMISSION in reported)
        assertTrue(QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION in reported)
    }

    @Test fun acceptedCompletionPreservesAcceptedEvidenceAndDirectSiblingWithoutCompleting() = runTest {
        val row = channelMessage()
        val channel = queued(row)
        seed(row, channel)
        val key = attempt(channel)
        store.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 13u)
        store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.ACCEPTED)
        val direct = pending(messageID = row.id, sequence = 2).toEntity()
        db.pendingSends().insert(direct)

        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.completeAcceptedQueuedChannelAttempt(key)
        }

        assertEquals(QueuedChannelDispatchState.ACCEPTED,
            store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).single { it.id == channel.id }.channelDispatchState)
        assertEquals(QueuedChannelAttemptPhase.ACCEPTED, assertNotNull(store.fetchQueuedChannelAttempt(key)).phase)
        assertFalse(assertNotNull(store.fetchQueuedChannelAttempt(key)).isCompleted)
        assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
        assertEquals(direct, db.pendingSends().byId(RADIO_A.value, direct.id))
    }

    @Test fun explicitRetryReplacesHistoricalReadySiblingsWithoutTouchingOtherMessage() = runTest {
        val row = channelMessage().copy(status = MessageStatus.FAILED)
        val original = queued(row)
        seed(row, original)
        val sibling = queued(row).copy(sequence = 2)
        insertReadySibling(sibling)
        val otherRow = channelMessage()
        val otherPending = queued(otherRow)
        seed(otherRow, otherPending)
        val retry = queued(row, resend = true)

        val sequence = store.replacePendingSendForRetry(row.id, retry)

        assertEquals(4L, sequence)
        assertEquals(
            listOf(retry.copy(sequence = sequence, channelDispatchState = QueuedChannelDispatchState.READY)),
            store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).toList(),
        )
        assertEquals(
            listOf(otherPending.copy(sequence = 3, channelDispatchState = QueuedChannelDispatchState.READY)),
            store.fetchPendingSendsForMessage(EntityKey(RADIO_A, otherRow.id)).toList(),
        )
        assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(RADIO_A, row.id))?.status)
    }

    @Test fun exactReadyCountRejectsMissingWrongUnknownInflightUncertainAndAcceptedWithoutBumpingAnyRow() = runTest {
        val row = channelMessage()
        val ready = queued(row)
        seed(row, ready)
        rejection(QueuedChannelAttemptConflict.MISSING_SUBMISSION) {
            store.incrementReadyQueuedChannelAttemptCount(EntityKey(RADIO_A, UUID.randomUUID()), row.id)
        }
        rejection(QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION) {
            store.incrementReadyQueuedChannelAttemptCount(EntityKey(RADIO_A, ready.id), UUID.randomUUID())
        }
        val legacyRow = channelMessage().copy(channelOccupantID = null)
        val legacy = queued(legacyRow)
        store.saveMessage(legacyRow)
        store.upsertPendingSend(legacy)
        rejection(QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION) {
            store.incrementReadyQueuedChannelAttemptCount(EntityKey(RADIO_A, legacy.id), legacyRow.id)
        }
        for (outcome in listOf(null, QueuedChannelAttemptOutcome.UNCERTAIN, QueuedChannelAttemptOutcome.ACCEPTED)) {
            val saved = channelMessage()
            val pending = queued(saved)
            seed(saved, pending)
            val key = attempt(pending)
            store.beginQueuedChannelAttempt(key, assertNotNull(saved.channelOccupantID), 3u)
            if (outcome != null) store.settleQueuedChannelAttempt(key, outcome)
            val before = store.fetchPendingSendsForMessage(EntityKey(RADIO_A, saved.id))
            rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
                store.incrementReadyQueuedChannelAttemptCount(EntityKey(RADIO_A, pending.id), saved.id)
            }
            assertEquals(before, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, saved.id)))
            assertEquals(saved, store.fetchMessage(EntityKey(RADIO_A, saved.id)))
        }
        assertEquals(0L, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).single().attemptCount)
        assertEquals(legacy, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, legacyRow.id)).single())
    }

    @Test fun replacementBetweenPreflightAndExactCountCannotBumpItsSuccessorOrRetainedUnknownSibling() = runTest {
        val row = channelMessage()
        val original = queued(row)
        seed(row, original)
        assertEquals(QueuedChannelDispatchState.READY, store.fetchPendingSends(RADIO_A).single().channelDispatchState)
        val other = RoomPersistenceStore(db, owner, clock)
        try {
            val replacement = queued(row, resend = true)
            other.replacePendingSendForRetry(row.id, replacement)
            rejection(QueuedChannelAttemptConflict.MISSING_SUBMISSION) {
                store.incrementReadyQueuedChannelAttemptCount(EntityKey(RADIO_A, original.id), row.id)
            }
            assertEquals(0L, store.fetchPendingSends(RADIO_A).single().attemptCount)
            val unknown = queued(row)
            other.upsertPendingSend(unknown)
            val before = store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id))
            rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
                store.incrementReadyQueuedChannelAttemptCount(EntityKey(RADIO_A, replacement.id), row.id)
            }
            assertEquals(before, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)))
        } finally {
            other.close()
        }
    }

    @Test fun exactReadyCountRollsBackOnOverflowSqlFailureOrChangedSavedIntent() = runTest {
        val row = channelMessage()
        val pending = queued(row).copy(attemptCount = Long.MAX_VALUE)
        seed(row, pending)
        assertFailsWith<PersistenceStoreException> {
            store.incrementReadyQueuedChannelAttemptCount(EntityKey(RADIO_A, pending.id), row.id)
        }
        assertEquals(Long.MAX_VALUE, store.fetchPendingSends(RADIO_A).single().attemptCount)
        val ordinary = channelMessage()
        val pendingOrdinary = queued(ordinary)
        seed(ordinary, pendingOrdinary)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_exact_count BEFORE UPDATE OF attemptCount ON pending_sends " +
                "BEGIN SELECT RAISE(ABORT, 'controlled exact count failure'); END",
        )
        try {
            assertFailsWith<PersistenceStoreException> {
                store.incrementReadyQueuedChannelAttemptCount(EntityKey(RADIO_A, pendingOrdinary.id), ordinary.id)
            }
            assertEquals(0L, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, ordinary.id)).single().attemptCount)
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_exact_count")
        }
        store.saveMessage(ordinary.copy(text = "changed after preflight"))
        rejection(QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION) {
            store.incrementReadyQueuedChannelAttemptCount(EntityKey(RADIO_A, pendingOrdinary.id), ordinary.id)
        }
        assertEquals(0L, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, ordinary.id)).single().attemptCount)
    }

    @Test fun exactReadyCountAndOriginalIntentSurviveDiskReopenWithoutCreatingAPhysicalAttempt() = runTest {
        val name = "ready-count-${UUID.randomUUID()}.db"
        var disk = MeshCoreDatabase.open(context, name)
        var persisted = RoomPersistenceStore(disk, owner, clock)
        val row = channelMessage()
        val pending = queued(row)
        try {
            persisted.saveMessage(row)
            persisted.insertPendingSendAssigningSequence(pending)
            assertTrue(QueuedChannelReadinessPersisting::class.java.isInstance(persisted))
            assertEquals(1L, persisted.incrementReadyQueuedChannelAttemptCount(EntityKey(RADIO_A, pending.id), row.id))
            persisted.close()
            disk.close()
            disk = MeshCoreDatabase.open(context, name)
            persisted = RoomPersistenceStore(disk, owner, clock)
            val retained = persisted.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).single()
            assertEquals(pending.id, retained.id)
            assertEquals(1L, retained.attemptCount)
            assertEquals(QueuedChannelDispatchState.READY, retained.channelDispatchState)
            assertNull(retained.channelAttemptID)
            assertEquals(row, persisted.fetchMessage(EntityKey(RADIO_A, row.id)))
            assertTrue(persisted.fetchQueuedChannelAttempts(EntityKey(RADIO_A, pending.id)).isEmpty())
        } finally {
            persisted.close()
            disk.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun exactReadyCountLostCommitReturnRetainsTheIncrementWithoutFabricatingAReceiptOrWireAttempt() = runTest {
        var operationToCancel: Job? = null
        val immediate = Executor { it.run() }
        val cancellingExecutor = Executor { command ->
            command.run()
            operationToCancel?.cancel(CancellationException("controlled ready-count commit return"))
        }
        val database = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(cancellingExecutor).build()
        val persisted = RoomPersistenceStore(database, owner, clock)
        try {
            val row = channelMessage()
            val pending = queued(row)
            persisted.saveMessage(row)
            persisted.insertPendingSendAssigningSequence(pending)
            var returned = false
            val operation = launch(start = CoroutineStart.UNDISPATCHED) {
                operationToCancel = currentCoroutineContext()[Job]
                persisted.incrementReadyQueuedChannelAttemptCount(EntityKey(RADIO_A, pending.id), row.id)
                returned = true
            }
            operation.join()
            operationToCancel = null
            assertTrue(operation.isCancelled)
            assertFalse(returned)
            val retained = persisted.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).single()
            assertEquals(1L, retained.attemptCount)
            assertEquals(QueuedChannelDispatchState.READY, retained.channelDispatchState)
            assertNull(retained.channelAttemptID)
            assertTrue(persisted.fetchQueuedChannelAttempts(EntityKey(RADIO_A, pending.id)).isEmpty())
            assertEquals(row, persisted.fetchMessage(EntityKey(RADIO_A, row.id)))
        } finally {
            operationToCancel = null
            persisted.close()
            database.close()
        }
    }

    @Test fun terminalRetirementUsesOnlyLatestDefinitelyUnsentAttemptAndCommitsFailureWithExactDeletion() = runTest {
        val row = channelMessage(resend = true).copy(sendCount = 8, heardRepeats = 4, failureSeen = true)
        val pending = queued(row, resend = true)
        seed(row, pending)
        val first = attempt(pending)
        store.beginQueuedChannelAttempt(first, assertNotNull(row.channelOccupantID), 1u)
        store.settleQueuedChannelAttempt(first, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
        val last = attempt(pending)
        store.beginQueuedChannelAttempt(last, assertNotNull(row.channelOccupantID), 2u)
        store.settleQueuedChannelAttempt(last, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
        rejection(QueuedChannelAttemptConflict.CONFLICTING_SETTLEMENT) {
            store.retireDefinitelyUnsentQueuedChannelAttempt(first)
        }
        val foreign = row.copy(radioId = RADIO_B)
        val foreignPending = queued(foreign, resend = true)
        seed(foreign, foreignPending)
        val repeat = MessageRepeatDTO(UUID.randomUUID(), row.id, AT, Bytes.of(1), 1u, null, null)
        store.saveMessageRepeat(RADIO_A, repeat)
        assertEquals(QueuedChannelRetirement(MessageStatus.FAILED, true), store.retireDefinitelyUnsentQueuedChannelAttempt(last))
        assertTrue(store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).isEmpty())
        assertEquals(row.copy(status = MessageStatus.FAILED, failureSeen = false), store.fetchMessage(EntityKey(RADIO_A, row.id)))
        assertEquals(listOf(repeat), store.fetchMessageRepeats(EntityKey(RADIO_A, row.id)).toList())
        assertEquals(listOf(first, last), store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, pending.id)).map { it.key })
        assertEquals(foreign, store.fetchMessage(EntityKey(RADIO_B, row.id)))
        assertEquals(foreignPending.id, store.fetchPendingSends(RADIO_B).single().id)
        rejection(QueuedChannelAttemptConflict.MISSING_SUBMISSION) {
            store.retireDefinitelyUnsentQueuedChannelAttempt(last)
        }
        assertTrue(QueuedChannelTerminalPersisting::class.java.isInstance(store))
    }

    @Test fun terminalRetirementNeverOverwritesDeliveredOrRepublishesAnAlreadyFailedMessage() = runTest {
        for (status in listOf(MessageStatus.DELIVERED, MessageStatus.FAILED)) {
            val row = channelMessage().copy(status = status, failureSeen = true)
            val pending = queued(row)
            seed(row, pending)
            val key = attempt(pending)
            store.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 3u)
            store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
            assertEquals(QueuedChannelRetirement(status, false), store.retireDefinitelyUnsentQueuedChannelAttempt(key))
            assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
            assertTrue(store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).isEmpty())
        }
    }

    @Test fun terminalRetirementRejectsUnknownUnattemptedDispatchingUncertainAndAcceptedWithoutWrites() = runTest {
        val legacy = channelMessage().copy(channelOccupantID = null)
        val legacyPending = queued(legacy)
        store.saveMessage(legacy)
        store.upsertPendingSend(legacyPending)
        rejection(QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION) {
            store.retireDefinitelyUnsentQueuedChannelAttempt(attempt(legacyPending))
        }
        assertEquals(legacy, store.fetchMessage(EntityKey(RADIO_A, legacy.id)))
        assertEquals(legacyPending, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, legacy.id)).single())
        val ready = channelMessage()
        val readyPending = queued(ready)
        seed(ready, readyPending)
        rejection(QueuedChannelAttemptConflict.MISSING_ATTEMPT) {
            store.retireDefinitelyUnsentQueuedChannelAttempt(attempt(readyPending))
        }
        for (outcome in listOf(null, QueuedChannelAttemptOutcome.UNCERTAIN, QueuedChannelAttemptOutcome.ACCEPTED)) {
            val row = channelMessage()
            val pending = queued(row)
            seed(row, pending)
            val key = attempt(pending)
            store.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 4u)
            if (outcome != null) store.settleQueuedChannelAttempt(key, outcome)
            val retained = store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id))
            val history = store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, pending.id))
            rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
                store.retireDefinitelyUnsentQueuedChannelAttempt(key)
            }
            assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
            assertEquals(retained, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)))
            assertEquals(history, store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, pending.id)))
        }
    }

    @Test fun terminalRetirementRefusesNewerSubmissionBeforeMutationAndOldHandleCannotTouchLaterRetry() = runTest {
        val row = channelMessage()
        val pending = queued(row)
        seed(row, pending)
        val key = attempt(pending)
        store.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 5u)
        store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
        val otherContext = RoomPersistenceStore(db, owner, clock)
        try {
            val newer = queued(row)
            otherContext.insertPendingSendAssigningSequence(newer)
            rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
                store.retireDefinitelyUnsentQueuedChannelAttempt(key)
            }
            assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
            assertEquals(setOf(pending.id, newer.id),
                store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).map { it.id }.toSet())
            otherContext.deletePendingSend(EntityKey(RADIO_A, newer.id))
            assertEquals(QueuedChannelRetirement(MessageStatus.FAILED, true), store.retireDefinitelyUnsentQueuedChannelAttempt(key))
            val retry = queued(row, resend = true)
            otherContext.replacePendingSendForRetry(row.id, retry)
            val current = assertNotNull(otherContext.fetchMessage(EntityKey(RADIO_A, row.id)))
            assertEquals(MessageStatus.PENDING, current.status)
            rejection(QueuedChannelAttemptConflict.MISSING_SUBMISSION) {
                store.retireDefinitelyUnsentQueuedChannelAttempt(key)
            }
            assertEquals(current, store.fetchMessage(EntityKey(RADIO_A, row.id)))
            assertEquals(retry.id, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).single().id)
        } finally {
            otherContext.close()
        }
    }

    @Test fun terminalStatusFailureRollsBackEarlierExactPendingDeletionAndRetainsAttemptEvidence() = runTest {
        val row = channelMessage()
        val pending = queued(row)
        seed(row, pending)
        val key = attempt(pending)
        store.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 6u)
        store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
        val before = store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id))
        val evidence = store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, pending.id))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_terminal_status BEFORE UPDATE OF statusRawValue ON messages " +
                "BEGIN SELECT RAISE(ABORT, 'controlled terminal status failure'); END",
        )
        try {
            assertFailsWith<PersistenceStoreException> { store.retireDefinitelyUnsentQueuedChannelAttempt(key) }
            assertEquals(before, store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)))
            assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
            assertEquals(evidence, store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, pending.id)))
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_terminal_status")
        }
        assertEquals(QueuedChannelRetirement(MessageStatus.FAILED, true), store.retireDefinitelyUnsentQueuedChannelAttempt(key))
    }

    @Test fun definiteUnsentReopenRetirementCommitsDurableFailureWithoutErasingOriginalTextOrAttempt() = runTest {
        val name = "terminal-channel-${UUID.randomUUID()}.db"
        var disk = MeshCoreDatabase.open(context, name)
        var persisted = RoomPersistenceStore(disk, owner, clock)
        val row = channelMessage()
        val pending = queued(row)
        val key = attempt(pending)
        try {
            persisted.saveMessage(row)
            persisted.insertPendingSendAssigningSequence(pending)
            persisted.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 7u)
            val evidence = persisted.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
            persisted.close()
            disk.close()
            disk = MeshCoreDatabase.open(context, name)
            persisted = RoomPersistenceStore(disk, owner, clock)
            assertEquals(QueuedChannelRetirement(MessageStatus.FAILED, true), persisted.retireDefinitelyUnsentQueuedChannelAttempt(key))
            persisted.close()
            disk.close()
            disk = MeshCoreDatabase.open(context, name)
            persisted = RoomPersistenceStore(disk, owner, clock)
            assertEquals(row.copy(status = MessageStatus.FAILED), persisted.fetchMessage(EntityKey(RADIO_A, row.id)))
            assertTrue(persisted.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).isEmpty())
            assertEquals(listOf(evidence), persisted.fetchQueuedChannelAttempts(EntityKey(RADIO_A, pending.id)).toList())
            rejection(QueuedChannelAttemptConflict.MISSING_SUBMISSION) {
                persisted.retireDefinitelyUnsentQueuedChannelAttempt(key)
            }
        } finally {
            persisted.close()
            disk.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun terminalCommitReturnCancellationPreservesCommittedStateWithoutInventingAnotherReceipt() = runTest {
        var operationToCancel: Job? = null
        val immediate = Executor { it.run() }
        val cancellingExecutor = Executor { command ->
            command.run()
            operationToCancel?.cancel(CancellationException("controlled terminal commit return"))
        }
        val database = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(cancellingExecutor).build()
        val persisted = RoomPersistenceStore(database, owner, clock)
        try {
            val row = channelMessage()
            val pending = queued(row)
            val key = attempt(pending)
            persisted.saveMessage(row)
            persisted.insertPendingSendAssigningSequence(pending)
            persisted.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 8u)
            val evidence = persisted.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
            var returned = false
            val operation = launch(start = CoroutineStart.UNDISPATCHED) {
                operationToCancel = currentCoroutineContext()[Job]
                persisted.retireDefinitelyUnsentQueuedChannelAttempt(key)
                returned = true
            }
            operation.join()
            operationToCancel = null
            assertTrue(operation.isCancelled)
            assertFalse(returned)
            assertEquals(row.copy(status = MessageStatus.FAILED), persisted.fetchMessage(EntityKey(RADIO_A, row.id)))
            assertTrue(persisted.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).isEmpty())
            assertEquals(evidence, persisted.fetchQueuedChannelAttempt(key))
            rejection(QueuedChannelAttemptConflict.MISSING_SUBMISSION) {
                persisted.retireDefinitelyUnsentQueuedChannelAttempt(key)
            }
        } finally {
            operationToCancel = null
            persisted.close()
            database.close()
        }
    }

    @Test fun beginBindsExactSubmissionSavedIntentAndWireMetadataWithoutChangingTheMessage() = runTest {
        val row = channelMessage()
        val pending = queued(row, resend = true)
        seed(row, pending)
        val key = attempt(pending)
        assertEquals(QueuedChannelDispatchState.READY, store.fetchPendingSends(RADIO_A).single().channelDispatchState)
        val begun = store.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), UInt.MAX_VALUE)
        assertEquals(key, begun.key)
        assertEquals(5u.toUByte(), begun.channelIndex)
        assertEquals(row.channelOccupantID, begun.channelOccupantID)
        assertEquals(UInt.MAX_VALUE, begun.wireTimestamp)
        assertTrue(begun.isResend)
        assertEquals(QueuedChannelAttemptPhase.DISPATCHING, begun.phase)
        assertFalse(begun.isCompleted)
        val captured = store.fetchPendingSends(RADIO_A).single()
        assertEquals(QueuedChannelDispatchState.DISPATCHING, captured.channelDispatchState)
        assertEquals(key.attemptID, captured.channelAttemptID)
        assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
        assertEquals(listOf(begun), store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, pending.id)))
    }

    @Test fun missingWrongMessageOrOccupantAndChangedTextCannotArmAnAttempt() = runTest {
        val row = channelMessage()
        val pending = queued(row)
        seed(row, pending)
        val key = attempt(pending)
        rejection(QueuedChannelAttemptConflict.MISSING_SUBMISSION) {
            store.beginQueuedChannelAttempt(key.copy(pendingID = UUID.randomUUID()), assertNotNull(row.channelOccupantID), 1u)
        }
        rejection(QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION) {
            store.beginQueuedChannelAttempt(key.copy(messageID = UUID.randomUUID()), assertNotNull(row.channelOccupantID), 1u)
        }
        rejection(QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION) {
            store.beginQueuedChannelAttempt(key, UUID.randomUUID(), 1u)
        }
        store.saveMessage(row.copy(text = "edited after envelope capture"))
        rejection(QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION) {
            store.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 1u)
        }
        assertTrue(store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, pending.id)).isEmpty())
        assertEquals(QueuedChannelDispatchState.READY, store.fetchPendingSends(RADIO_A).single().channelDispatchState)
    }

    @Test fun independentRepositoryContextsCanCommitOnlyOneCurrentAttempt() = runTest {
        val row = channelMessage()
        val pending = queued(row)
        seed(row, pending)
        val second = RoomPersistenceStore(db, owner, clock)
        try {
            val outcomes = listOf(store, second).map { repository ->
                async {
                    try {
                        repository.beginQueuedChannelAttempt(attempt(pending), assertNotNull(row.channelOccupantID), 1u)
                    } catch (failure: PersistenceStoreException) {
                        assertEquals(QueuedChannelAttemptConflict.SUBMISSION_NOT_READY,
                            assertIs<QueuedChannelAttemptRejected>(failure.cause).reason)
                        null
                    }
                }
            }.awaitAll()
            val winner = outcomes.filterNotNull().single()
            assertEquals(winner.key.attemptID, store.fetchPendingSends(RADIO_A).single().channelAttemptID)
            assertEquals(listOf(winner), store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, pending.id)))
        } finally {
            second.close()
        }
    }

    @Test fun failedPreWireJournalInsertRollsBackThePendingCasRatherThanArmingAClaim() = runTest {
        val row = channelMessage()
        val pending = queued(row)
        seed(row, pending)
        val key = attempt(pending)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_attempt_insert BEFORE INSERT ON queued_channel_attempts " +
                "BEGIN SELECT RAISE(ABORT, 'controlled journal insertion failure'); END",
        )
        try {
            assertFailsWith<PersistenceStoreException> {
                store.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 1u)
            }
            val retained = store.fetchPendingSends(RADIO_A).single()
            assertEquals(QueuedChannelDispatchState.READY, retained.channelDispatchState)
            assertNull(retained.channelAttemptID)
            assertNull(store.fetchQueuedChannelAttempt(key))
            assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_attempt_insert")
        }
    }

    @Test fun cancellationBeforeBeginRunsCannotArmButCommittedReturnCancellationRetainsExactDispatching() = runTest {
        for (cancelBeforeRun in listOf(true, false)) {
            var operationToCancel: Job? = null
            val immediate = Executor { it.run() }
            val cancellingExecutor = Executor { command ->
                if (cancelBeforeRun) operationToCancel?.cancel(CancellationException("Controlled pre-transaction cancellation"))
                command.run()
                if (!cancelBeforeRun) operationToCancel?.cancel(CancellationException("Controlled committed-return cancellation"))
            }
            val database = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
                .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(cancellingExecutor).build()
            val repository = RoomPersistenceStore(database, owner, clock)
            val row = channelMessage()
            val pending = queued(row)
            val key = attempt(pending)
            var returned = false
            try {
                repository.saveMessage(row)
                repository.insertPendingSendAssigningSequence(pending)
                val operation = launch(start = CoroutineStart.UNDISPATCHED) {
                    operationToCancel = currentCoroutineContext()[Job]
                    repository.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 1u)
                    returned = true
                }
                operation.join()
                operationToCancel = null
                assertTrue(operation.isCancelled)
                assertFalse(returned)
                val retained = repository.fetchPendingSends(RADIO_A).single()
                if (cancelBeforeRun) {
                    assertEquals(QueuedChannelDispatchState.READY, retained.channelDispatchState)
                    assertNull(retained.channelAttemptID)
                    assertNull(repository.fetchQueuedChannelAttempt(key))
                } else {
                    assertEquals(QueuedChannelDispatchState.DISPATCHING, retained.channelDispatchState)
                    assertEquals(key.attemptID, retained.channelAttemptID)
                    assertEquals(QueuedChannelAttemptPhase.DISPATCHING, assertNotNull(repository.fetchQueuedChannelAttempt(key)).phase)
                }
                assertEquals(row, repository.fetchMessage(EntityKey(RADIO_A, row.id)))
            } finally {
                operationToCancel = null
                repository.close()
                database.close()
            }
        }
    }

    @Test fun cancelledCommittedAcceptanceAndCompletionReturnsRemainExactlyRecoverableWithoutRepeatingBookkeeping() = runTest {
        var operationToCancel: Job? = null
        val immediate = Executor { it.run() }
        val cancellingExecutor = Executor { command ->
            command.run()
            operationToCancel?.cancel(CancellationException("Controlled committed journal return cancellation"))
        }
        val database = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(cancellingExecutor).build()
        val repository = RoomPersistenceStore(database, owner, clock)
        val row = channelMessage(resend = true).copy(heardRepeats = 4)
        val pending = queued(row, true)
        val key = attempt(pending)
        try {
            repository.saveMessage(row)
            repository.insertPendingSendAssigningSequence(pending)
            repository.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 2u)
            val acceptance = launch(start = CoroutineStart.UNDISPATCHED) {
                operationToCancel = currentCoroutineContext()[Job]
                repository.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.ACCEPTED)
            }
            acceptance.join()
            operationToCancel = null
            assertTrue(acceptance.isCancelled)
            assertEquals(QueuedChannelDispatchState.ACCEPTED, repository.fetchPendingSends(RADIO_A).single().channelDispatchState)
            val accepted = assertNotNull(repository.fetchQueuedChannelAttempt(key))
            assertEquals(QueuedChannelAttemptPhase.ACCEPTED, accepted.phase)
            assertFalse(accepted.isCompleted)
            val completion = launch(start = CoroutineStart.UNDISPATCHED) {
                operationToCancel = currentCoroutineContext()[Job]
                repository.completeAcceptedQueuedChannelAttempt(key)
            }
            completion.join()
            operationToCancel = null
            assertTrue(completion.isCancelled)
            assertTrue(assertNotNull(repository.fetchQueuedChannelAttempt(key)).isCompleted)
            assertTrue(repository.fetchPendingSends(RADIO_A).isEmpty())
            val sent = assertNotNull(repository.fetchMessage(EntityKey(RADIO_A, row.id)))
            assertEquals(row.sendCount + 1, sent.sendCount)
            assertEquals(0L, sent.heardRepeats)
            assertEquals(2u, sent.timestamp)
            repository.completeAcceptedQueuedChannelAttempt(key)
            assertEquals(sent, repository.fetchMessage(EntityKey(RADIO_A, row.id)))
        } finally {
            operationToCancel = null
            repository.close()
            database.close()
        }
    }

    @Test fun definitelyUnsentMakesOnlyTheSameSubmissionReadyAndOldHandlesCannotRearmOrSettleItsSuccessor() = runTest {
        val row = channelMessage()
        val pending = queued(row)
        seed(row, pending)
        val first = attempt(pending)
        store.beginQueuedChannelAttempt(first, assertNotNull(row.channelOccupantID), 1u)
        val unsent = store.settleQueuedChannelAttempt(first, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
        assertEquals(QueuedChannelAttemptPhase.DEFINITELY_UNSENT, unsent.phase)
        assertNull(store.fetchPendingSends(RADIO_A).single().channelAttemptID)
        rejection(QueuedChannelAttemptConflict.ATTEMPT_ID_REUSED) {
            store.beginQueuedChannelAttempt(first, assertNotNull(row.channelOccupantID), 2u)
        }
        val next = attempt(pending)
        store.beginQueuedChannelAttempt(next, assertNotNull(row.channelOccupantID), 2u)
        assertEquals(unsent, store.settleQueuedChannelAttempt(first, QueuedChannelAttemptOutcome.DEFINITELY_UNSENT))
        assertEquals(next.attemptID, store.fetchPendingSends(RADIO_A).single().channelAttemptID)
        rejection(QueuedChannelAttemptConflict.CONFLICTING_SETTLEMENT) {
            store.settleQueuedChannelAttempt(first, QueuedChannelAttemptOutcome.ACCEPTED)
        }
        assertEquals(QueuedChannelAttemptPhase.DISPATCHING, assertNotNull(store.fetchQueuedChannelAttempt(next)).phase)
    }

    @Test fun uncertaintyAndLegacyNullRemainVisibleAndCannotBeErasedByAutomaticMaintenanceOrFreshRetryIds() = runTest {
        val row = channelMessage()
        val pending = queued(row).copy(attemptCount = null)
        seed(row, pending)
        val key = attempt(pending)
        store.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 1u)
        store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.UNCERTAIN)
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) { store.deletePendingSend(EntityKey(RADIO_A, pending.id)) }
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) { store.deletePendingSendsForMessage(EntityKey(RADIO_A, row.id)) }
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) { store.insertPendingSendAssigningSequence(queued(row, true)) }
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) { store.replacePendingSendForRetry(row.id, queued(row, true)) }
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) { store.upsertPendingSend(pending.copy(messageText = "overwrite")) }
        assertEquals(0L, store.purgeOrphanPendingSends())
        assertEquals(0L, store.purgeLegacyAttemptCountRows())
        assertEquals(row.text, store.fetchPendingSends(RADIO_A).single().messageText)
        assertEquals(QueuedChannelDispatchState.UNCERTAIN, store.fetchPendingSends(RADIO_A).single().channelDispatchState)
        assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
        val legacy = channelMessage()
        store.saveMessage(legacy)
        val legacyPending = queued(legacy)
        store.upsertPendingSend(legacyPending)
        rejection(QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION) {
            store.beginQueuedChannelAttempt(attempt(legacyPending), assertNotNull(legacy.channelOccupantID), 1u)
        }
        assertNull(store.fetchPendingSends(RADIO_A).single { it.id == legacyPending.id }.channelDispatchState)
    }

    @Test fun crashLeftDispatchingAndUnknownOutcomeSurviveActualDatabaseReopenWithoutInferringAcceptance() = runTest {
        val name = "queued-channel-dispatching-${UUID.randomUUID()}.db"
        var disk = MeshCoreDatabase.open(context, name)
        var persisted = RoomPersistenceStore(disk, owner, clock)
        val row = channelMessage()
        val pending = queued(row)
        val key = attempt(pending)
        try {
            persisted.saveMessage(row)
            persisted.insertPendingSendAssigningSequence(pending)
            persisted.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 1u)
            persisted.close()
            disk.close()
            disk = MeshCoreDatabase.open(context, name)
            persisted = RoomPersistenceStore(disk, owner, clock)
            val retained = persisted.fetchPendingSends(RADIO_A).single()
            assertEquals(QueuedChannelDispatchState.DISPATCHING, retained.channelDispatchState)
            assertEquals(key.attemptID, retained.channelAttemptID)
            assertEquals(QueuedChannelAttemptPhase.DISPATCHING, assertNotNull(persisted.fetchQueuedChannelAttempt(key)).phase)
            rejection(QueuedChannelAttemptConflict.SUBMISSION_NOT_READY) {
                persisted.beginQueuedChannelAttempt(attempt(pending), assertNotNull(row.channelOccupantID), 2u)
            }
            rejection(QueuedChannelAttemptConflict.NOT_ACCEPTED) { persisted.completeAcceptedQueuedChannelAttempt(key) }
            assertEquals(row.text, retained.messageText)
            persisted.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.UNCERTAIN)
            persisted.close()
            disk.close()
            disk = MeshCoreDatabase.open(context, name)
            persisted = RoomPersistenceStore(disk, owner, clock)
            assertEquals(QueuedChannelDispatchState.UNCERTAIN, persisted.fetchPendingSends(RADIO_A).single().channelDispatchState)
            assertEquals(row, persisted.fetchMessage(EntityKey(RADIO_A, row.id)))
        } finally {
            persisted.close()
            disk.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun acceptedRestartIsCleanupOnlyAndRepeatedCompletionCannotOverwriteANewerExplicitSubmission() = runTest {
        val name = "queued-channel-accepted-${UUID.randomUUID()}.db"
        var disk = MeshCoreDatabase.open(context, name)
        var persisted = RoomPersistenceStore(disk, owner, clock)
        val row = channelMessage(resend = true).copy(heardRepeats = 4)
        val pending = queued(row, resend = true)
        val key = attempt(pending)
        try {
            persisted.saveMessage(row)
            persisted.saveMessageRepeat(RADIO_A, MessageRepeatDTO(messageID = row.id, receivedAt = AT, pathNodes = Bytes.of(1)))
            persisted.insertPendingSendAssigningSequence(pending)
            persisted.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), UInt.MAX_VALUE)
            persisted.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.ACCEPTED)
            persisted.close()
            disk.close()
            disk = MeshCoreDatabase.open(context, name)
            persisted = RoomPersistenceStore(disk, owner, clock)
            val accepted = assertNotNull(persisted.fetchQueuedChannelAttempt(key))
            assertEquals(QueuedChannelAttemptPhase.ACCEPTED, accepted.phase)
            assertTrue(accepted.isResend)
            rejection(QueuedChannelAttemptConflict.SUBMISSION_NOT_READY) {
                persisted.beginQueuedChannelAttempt(attempt(pending), assertNotNull(row.channelOccupantID), 2u)
            }
            persisted.completeAcceptedQueuedChannelAttempt(key)
            val sent = assertNotNull(persisted.fetchMessage(EntityKey(RADIO_A, row.id)))
            assertEquals(MessageStatus.SENT, sent.status)
            assertEquals(UInt.MAX_VALUE, sent.timestamp)
            assertEquals(row.sendCount + 1, sent.sendCount)
            assertEquals(0L, sent.heardRepeats)
            assertTrue(persisted.fetchMessageRepeats(EntityKey(RADIO_A, row.id)).isEmpty())
            assertTrue(persisted.fetchPendingSends(RADIO_A).isEmpty())
            val completed = assertNotNull(persisted.fetchQueuedChannelAttempt(key))
            assertTrue(completed.isCompleted)
            val next = queued(sent, resend = true)
            persisted.replacePendingSendForRetry(row.id, next)
            val retrying = persisted.fetchMessage(EntityKey(RADIO_A, row.id))
            persisted.completeAcceptedQueuedChannelAttempt(key)
            assertEquals(completed, persisted.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.ACCEPTED))
            assertEquals(retrying, persisted.fetchMessage(EntityKey(RADIO_A, row.id)))
            assertEquals(next.id, persisted.fetchPendingSends(RADIO_A).single().id)
            assertEquals(QueuedChannelDispatchState.READY, persisted.fetchPendingSends(RADIO_A).single().channelDispatchState)
        } finally {
            persisted.close()
            disk.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun acceptanceAndCompletionFailuresRollbackBothSidesAndExactReadbackRemainsRetryableWithoutDispatch() = runTest {
        val row = channelMessage(resend = true)
        val pending = queued(row, true)
        seed(row, pending)
        val key = attempt(pending)
        store.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 1u)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_attempt_accept BEFORE UPDATE ON queued_channel_attempts WHEN NEW.phaseRawValue = 1 " +
                "BEGIN SELECT RAISE(ABORT, 'controlled acceptance failure'); END",
        )
        try {
            assertFailsWith<PersistenceStoreException> { store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.ACCEPTED) }
            assertEquals(QueuedChannelDispatchState.DISPATCHING, store.fetchPendingSends(RADIO_A).single().channelDispatchState)
            assertEquals(QueuedChannelAttemptPhase.DISPATCHING, assertNotNull(store.fetchQueuedChannelAttempt(key)).phase)
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_attempt_accept")
        }
        val accepted = store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.ACCEPTED)
        assertEquals(accepted, store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.ACCEPTED))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_attempt_complete BEFORE UPDATE ON queued_channel_attempts WHEN NEW.isCompleted = 1 " +
                "BEGIN SELECT RAISE(ABORT, 'controlled completion failure'); END",
        )
        try {
            assertFailsWith<PersistenceStoreException> { store.completeAcceptedQueuedChannelAttempt(key) }
            assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
            assertEquals(QueuedChannelDispatchState.ACCEPTED, store.fetchPendingSends(RADIO_A).single().channelDispatchState)
            assertEquals(accepted, store.fetchQueuedChannelAttempt(key))
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_attempt_complete")
        }
        store.completeAcceptedQueuedChannelAttempt(key)
        val completed = store.fetchMessage(EntityKey(RADIO_A, row.id))
        store.completeAcceptedQueuedChannelAttempt(key)
        assertEquals(completed, store.fetchMessage(EntityKey(RADIO_A, row.id)))
    }

    @Test fun olderAcceptedCompletionAtomicallyRefusesToTouchANewerSubmissionOrItsMessageOutcome() = runTest {
        val row = channelMessage()
        val pending = queued(row)
        seed(row, pending)
        val key = attempt(pending)
        store.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 1u)
        store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.ACCEPTED)
        val next = queued(row, true).copy(sequence = 2, channelDispatchState = QueuedChannelDispatchState.READY)
        db.pendingSends().insert(next.toEntity())
        val before = store.fetchPendingSends(RADIO_A)
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) {
            store.beginQueuedChannelAttempt(attempt(next), assertNotNull(row.channelOccupantID), 2u)
        }
        rejection(QueuedChannelAttemptConflict.RETAINED_SUBMISSION) { store.completeAcceptedQueuedChannelAttempt(key) }
        assertEquals(before, store.fetchPendingSends(RADIO_A))
        assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
        assertFalse(assertNotNull(store.fetchQueuedChannelAttempt(key)).isCompleted)
    }

    @Test fun identicalIdsRemainRadioPartitionedAndWrongAttemptKeysCannotSettleOrCleanUp() = runTest {
        val a = channelMessage()
        val first = queued(a)
        val b = a.copy(radioId = RADIO_B)
        val second = first.copy(radioId = RADIO_B)
        seed(a, first)
        seed(b, second)
        val keyA = attempt(first)
        val keyB = keyA.copy(radioId = RADIO_B)
        store.beginQueuedChannelAttempt(keyA, assertNotNull(a.channelOccupantID), 1u)
        store.beginQueuedChannelAttempt(keyB, assertNotNull(b.channelOccupantID), 2u)
        rejection(QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION) {
            store.settleQueuedChannelAttempt(keyA.copy(pendingID = UUID.randomUUID()), QueuedChannelAttemptOutcome.ACCEPTED)
        }
        rejection(QueuedChannelAttemptConflict.MISSING_ATTEMPT) {
            store.completeAcceptedQueuedChannelAttempt(keyA.copy(attemptID = UUID.randomUUID()))
        }
        store.settleQueuedChannelAttempt(keyA, QueuedChannelAttemptOutcome.ACCEPTED)
        store.completeAcceptedQueuedChannelAttempt(keyA)
        assertTrue(store.fetchPendingSends(RADIO_A).isEmpty())
        assertEquals(keyB.attemptID, store.fetchPendingSends(RADIO_B).single().channelAttemptID)
        assertEquals(b, store.fetchMessage(EntityKey(RADIO_B, b.id)))
    }

    @Test fun malformedDispatchStateOrAttemptPhaseFailsExplicitlyWithoutBecomingReadyOrLosingText() = runTest {
        val row = channelMessage()
        val pending = queued(row)
        seed(row, pending)
        db.pendingSends().upsert(pending.toEntity().copy(channelDispatchStateRawValue = 99))
        assertFailsWith<PersistenceStoreException> { store.fetchPendingSends(RADIO_A) }
        val retained = db.pendingSends().forRadio(RADIO_A.value).single()
        assertEquals(99L, retained.channelDispatchStateRawValue)
        assertEquals(row.text, retained.messageText)
        assertTrue(db.queuedChannelAttempts().forPending(RADIO_A.value, pending.id).isEmpty())
        db.pendingSends().upsert(pending.toEntity().copy(channelDispatchStateRawValue = QueuedChannelDispatchState.READY.rawValue))
        val key = attempt(pending)
        store.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 1u)
        val original = assertNotNull(db.queuedChannelAttempts().byId(RADIO_A.value, key.attemptID))
        db.queuedChannelAttempts().upsert(original.copy(phaseRawValue = 99))
        assertFailsWith<PersistenceStoreException> { store.fetchQueuedChannelAttempt(key) }
        assertEquals(99L, db.queuedChannelAttempts().byId(RADIO_A.value, key.attemptID)?.phaseRawValue)
        db.queuedChannelAttempts().upsert(original.copy(isCompleted = true))
        assertFailsWith<PersistenceStoreException> { store.fetchQueuedChannelAttempts(EntityKey(RADIO_A, pending.id)) }
        assertEquals(row.text, store.fetchPendingSends(RADIO_A).single().messageText)
    }

    @Test fun nonResendCompletionPreservesLateDeliverySendCountAndRepeatHistory() = runTest {
        val row = channelMessage().copy(status = MessageStatus.DELIVERED, heardRepeats = 4)
        val pending = queued(row)
        seed(row, pending)
        store.saveMessageRepeat(RADIO_A, MessageRepeatDTO(messageID = row.id, receivedAt = AT, pathNodes = Bytes.of(1)))
        val repeats = store.fetchMessageRepeats(EntityKey(RADIO_A, row.id))
        val key = attempt(pending)
        store.beginQueuedChannelAttempt(key, assertNotNull(row.channelOccupantID), 2u)
        store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.ACCEPTED)
        store.completeAcceptedQueuedChannelAttempt(key)
        val completed = assertNotNull(store.fetchMessage(EntityKey(RADIO_A, row.id)))
        assertEquals(MessageStatus.DELIVERED, completed.status)
        assertEquals(row.sendCount, completed.sendCount)
        assertEquals(row.heardRepeats, completed.heardRepeats)
        assertEquals(repeats, store.fetchMessageRepeats(EntityKey(RADIO_A, row.id)))
    }

    @Test fun explicitDeviceDataEraseClearsItsJournalButRetainsAnotherRadiosUncertainty() = runTest {
        val device = device()
        store.saveDevice(device)
        val a = channelMessage()
        val pendingA = queued(a)
        seed(a, pendingA)
        val keyA = attempt(pendingA)
        store.beginQueuedChannelAttempt(keyA, assertNotNull(a.channelOccupantID), 1u)
        val b = channelMessage().copy(radioId = RADIO_B)
        val pendingB = queued(b)
        seed(b, pendingB)
        val keyB = attempt(pendingB)
        store.beginQueuedChannelAttempt(keyB, assertNotNull(b.channelOccupantID), 1u)
        store.deleteDeviceData(device.id)
        assertNull(store.fetchQueuedChannelAttempt(keyA))
        assertTrue(store.fetchPendingSends(RADIO_A).isEmpty())
        assertEquals(QueuedChannelAttemptPhase.DISPATCHING, assertNotNull(store.fetchQueuedChannelAttempt(keyB)).phase)
        assertEquals(b.text, store.fetchPendingSends(RADIO_B).single().messageText)
    }
}
