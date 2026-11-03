// AndroidOnly: WP-202 Real Room pre-wire claims, exact attempt settlement, restart uncertainty and cleanup regressions.
package com.meshcoreone.android.core.data.repository

import androidx.room.Room
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.QueuedChannelAttemptRejected
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
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

    @Test fun malformedFutureDispatchStateFailsExplicitlyAndDoesNotBecomeReadyOrLoseText() = runTest {
        val row = channelMessage()
        val pending = queued(row)
        seed(row, pending)
        db.pendingSends().upsert(pending.toEntity().copy(channelDispatchStateRawValue = 99))
        assertFailsWith<PersistenceStoreException> { store.fetchPendingSends(RADIO_A) }
        val retained = db.pendingSends().forRadio(RADIO_A.value).single()
        assertEquals(99L, retained.channelDispatchStateRawValue)
        assertEquals(row.text, retained.messageText)
        assertTrue(db.queuedChannelAttempts().forPending(RADIO_A.value, pending.id).isEmpty())
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
