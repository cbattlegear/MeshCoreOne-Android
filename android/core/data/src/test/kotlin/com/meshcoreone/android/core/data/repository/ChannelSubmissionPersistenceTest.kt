// AndroidOnly: WP-202 Real Room retained submission replay, conflict, cleanup, cancellation and restart controls.
package com.meshcoreone.android.core.data.repository

import androidx.room.Room
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.*
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChannelSubmissionPersistenceTest : RepositoryTest() {
    private fun row(radio: RadioId = RADIO_A) =
        message(radio = radio, contactID = null, channelIndex = 5u, text = "retained original\n", status = MessageStatus.PENDING)
            .copy(channelOccupantID = UUID.randomUUID())

    private fun submission(row: MessageDTO, id: UUID = UUID.randomUUID(), resend: Boolean = false) =
        ChannelSubmission(id, ChannelHistoryScope(row.radioId, 5u, assertNotNull(row.channelOccupantID)),
            ChannelMessageEnvelope(row.id, 5u, resend, row.text, row.timestamp, "original node", row.channelOccupantID))

    private suspend fun reject(reason: ChannelSubmissionConflict, action: suspend () -> Unit) {
        val failure = assertFailsWith<PersistenceStoreException> { action() }
        assertEquals(reason, assertIs<ChannelSubmissionRejected>(failure.cause).reason)
    }

    @Test fun exactReplayKeepsFirstSequenceTimeAndPayloadWithoutBumpingOrSamplingCurrentSlot() = runTest {
        val message = row()
        store.saveMessage(message)
        val input = submission(message)
        val first = store.admitChannelSubmission(input, AT.plusNanos(123))
        assertEquals(input, first.submission)
        assertEquals(ChannelSubmissionPhase.ADMITTED, first.phase)
        assertEquals(QueuedChannelDispatchState.READY, first.pending?.channelDispatchState)
        assertEquals(first, store.admitChannelSubmission(input, AT.plusSeconds(100)))
        assertEquals(first, assertIs<ChannelSubmissionReconciliation.Known>(store.reconcileChannelSubmission(input)).evidence)
        assertEquals(1, store.fetchPendingSends(RADIO_A).size)
        assertNull(store.fetchChannel(RADIO_A, 5u))
    }

    @Test fun sameStableIdRejectsChangedRadioMessageOriginTextTimestampNodeAndResendBeforeWrites() = runTest {
        val message = row()
        store.saveMessage(message)
        val input = submission(message)
        val first = store.admitChannelSubmission(input, AT)
        val otherOrigin = input.origin.copy(expectedOccupantID = UUID.randomUUID())
        for (changed in listOf(
            input.copy(origin = input.origin.copy(radioId = RADIO_B)),
            input.copy(envelope = input.envelope.copy(messageID = UUID.randomUUID())),
            input.copy(origin = otherOrigin, envelope = input.envelope.copy(channelOccupantID = otherOrigin.expectedOccupantID)),
            input.copy(origin = input.origin.copy(channelIndex = 6u), envelope = input.envelope.copy(channelIndex = 6u)),
            input.copy(envelope = input.envelope.copy(messageText = "changed")),
            input.copy(envelope = input.envelope.copy(messageTimestamp = 1u)),
            input.copy(envelope = input.envelope.copy(localNodeName = null)),
            input.copy(envelope = input.envelope.copy(isResend = true)),
        )) {
            reject(ChannelSubmissionConflict.IDENTITY_CONFLICT) { store.admitChannelSubmission(changed, AT) }
            reject(ChannelSubmissionConflict.IDENTITY_CONFLICT) { store.reconcileChannelSubmission(changed) }
        }
        assertEquals(first, store.admitChannelSubmission(input, AT))
    }

    @Test fun missingProofChangedSavedIntentAndLegacySameIdCannotManufactureAdmission() = runTest {
        val message = row()
        val input = submission(message)
        reject(ChannelSubmissionConflict.MESSAGE_MISMATCH) { store.admitChannelSubmission(input, AT) }
        for (changed in listOf(
            message.copy(channelOccupantID = null), message.copy(text = "different"),
            message.copy(direction = MessageDirection.INCOMING), message.copy(timestamp = 1u),
        )) {
            store.saveMessage(changed)
            reject(ChannelSubmissionConflict.MESSAGE_MISMATCH) { store.admitChannelSubmission(input, AT) }
        }
        store.saveMessage(message)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(input.envelope, RADIO_A, id = input.pendingID, enqueuedAt = AT))
        reject(ChannelSubmissionConflict.UNKNOWN_LEGACY_SUBMISSION) { store.admitChannelSubmission(input, AT) }
        assertEquals(ChannelSubmissionReconciliation.Unknown(ChannelSubmissionUnknown.ABSENT), store.reconcileChannelSubmission(input))
        assertNull(store.fetchPendingSends(RADIO_A).single().channelDispatchState)
    }

    @Test fun originalWriterNotYetEnteredRemainsUnknownThenCommitsAndConcurrentReplaysInsertOnce() = runTest(scheduler) {
        val message = row()
        store.saveMessage(message)
        val input = submission(message)
        val original = async { store.admitChannelSubmission(input, AT) }
        assertEquals(ChannelSubmissionReconciliation.Unknown(ChannelSubmissionUnknown.ABSENT), store.reconcileChannelSubmission(input))
        runCurrent()
        val committed = original.await()
        val second = newStore()
        try {
            val replays = listOf(
                async { store.admitChannelSubmission(input, AT.plusSeconds(1)) },
                async { second.admitChannelSubmission(input, AT.plusSeconds(2)) },
            ).awaitAll()
            assertEquals(listOf(committed, committed), replays)
            assertEquals(1, store.fetchPendingSends(RADIO_A).size)
        } finally { second.close() }
    }

    @Test fun pendingInsertFailureRollsBackEarlierReceiptAndNeverProvesNotCommitted() = runTest {
        val message = row()
        store.saveMessage(message)
        val input = submission(message)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_submission_pending BEFORE INSERT ON pending_sends BEGIN SELECT RAISE(ABORT, 'controlled submission failure'); END")
        try {
            assertFailsWith<PersistenceStoreException> { store.admitChannelSubmission(input, AT) }
            assertNull(db.channelSubmissions().byId(input.pendingID))
            assertTrue(store.fetchPendingSends(RADIO_A).isEmpty())
            assertEquals(ChannelSubmissionReconciliation.Unknown(ChannelSubmissionUnknown.ABSENT), store.reconcileChannelSubmission(input))
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_submission_pending") }
        assertEquals(input, store.admitChannelSubmission(input, AT).submission)
    }

    @Test fun admittedReceiptDoesNotRearmDispatchingUncertainOrAcceptedPendingState() = runTest {
        for (outcome in listOf(null, QueuedChannelAttemptOutcome.UNCERTAIN, QueuedChannelAttemptOutcome.ACCEPTED)) {
            val message = row()
            store.saveMessage(message)
            val input = submission(message)
            store.admitChannelSubmission(input, AT)
            val key = QueuedChannelAttemptKey(RADIO_A, input.pendingID, message.id, UUID.randomUUID())
            store.beginQueuedChannelAttempt(key, input.origin.expectedOccupantID, 2u)
            if (outcome != null) store.settleQueuedChannelAttempt(key, outcome)
            val before = store.fetchPendingSendsForMessage(EntityKey(RADIO_A, message.id)).single()
            val replay = store.admitChannelSubmission(input, AT)
            assertEquals(ChannelSubmissionPhase.ADMITTED, replay.phase)
            assertEquals(before, replay.pending)
            assertEquals(key, replay.attempts.single().key)
        }
    }

    @Test fun acceptedAndDefinitelyUnsentTerminalEvidenceSurviveCleanupAndDiskReopenWithoutReinsertion() = runTest {
        for (accepted in listOf(true, false)) {
            val name = "submission-${UUID.randomUUID()}.db"
            var disk = MeshCoreDatabase.open(context, name)
            var persisted = RoomPersistenceStore(disk, owner, clock)
            val message = row()
            val input = submission(message, resend = true)
            val key = QueuedChannelAttemptKey(RADIO_A, input.pendingID, message.id, UUID.randomUUID())
            try {
                persisted.saveMessage(message)
                persisted.admitChannelSubmission(input, AT)
                persisted.beginQueuedChannelAttempt(key, input.origin.expectedOccupantID, 42u)
                persisted.settleQueuedChannelAttempt(key, if (accepted) QueuedChannelAttemptOutcome.ACCEPTED else QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
                if (accepted) persisted.completeAcceptedQueuedChannelAttempt(key)
                else persisted.retireDefinitelyUnsentQueuedChannelAttempt(key)
                persisted.close()
                disk.close()
                disk = MeshCoreDatabase.open(context, name)
                persisted = RoomPersistenceStore(disk, owner, clock)
                val evidence = persisted.admitChannelSubmission(input, AT.plusSeconds(50))
                assertEquals(input, evidence.submission)
                assertEquals(if (accepted) ChannelSubmissionPhase.ACCEPTED_COMPLETED else ChannelSubmissionPhase.RETIRED, evidence.phase)
                assertNull(evidence.pending)
                assertEquals(key, evidence.attempts.single().key)
                assertEquals(accepted, evidence.attempts.single().isCompleted)
                assertTrue(persisted.fetchPendingSends(RADIO_A).isEmpty())
            } finally {
                persisted.close()
                disk.close()
                context.deleteDatabase(name)
            }
        }
    }

    @Test fun lostAdmissionReturnRetainsExactReceiptAndReplayDoesNotInsertAnotherRow() = runTest {
        var cancelledOperation: Job? = null
        val immediate = Executor { it.run() }
        val cancelling = Executor { command ->
            command.run()
            cancelledOperation?.cancel(CancellationException("controlled admission return"))
        }
        val database = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(cancelling).build()
        val persisted = RoomPersistenceStore(database, owner, clock)
        try {
            val message = row()
            val input = submission(message)
            persisted.saveMessage(message)
            var returned = false
            val original = launch(start = CoroutineStart.UNDISPATCHED) {
                cancelledOperation = currentCoroutineContext()[Job]
                persisted.admitChannelSubmission(input, AT)
                returned = true
            }
            original.join()
            cancelledOperation = null
            assertTrue(original.isCancelled)
            assertFalse(returned)
            val evidence = assertIs<ChannelSubmissionReconciliation.Known>(persisted.reconcileChannelSubmission(input)).evidence
            assertEquals(evidence, persisted.admitChannelSubmission(input, AT.plusSeconds(1)))
            assertEquals(1, persisted.fetchPendingSends(RADIO_A).size)
        } finally {
            cancelledOperation = null
            persisted.close()
            database.close()
        }
    }

    @Test fun genericInsertionCannotOverwriteOrResurrectAReceiptAndExplicitDeletionRedactsIntent() = runTest {
        val message = row()
        store.saveMessage(message)
        val input = submission(message)
        val evidence = store.admitChannelSubmission(input, AT)
        reject(ChannelSubmissionConflict.IDENTITY_CONFLICT) { store.insertPendingSendAssigningSequence(assertNotNull(evidence.pending)) }
        store.deleteMessage(EntityKey(RADIO_A, message.id))
        val tombstone = assertNotNull(db.channelSubmissions().byId(input.pendingID))
        assertNull(tombstone.messageText)
        assertNull(tombstone.localNodeName)
        assertEquals(ChannelSubmissionReconciliation.Unknown(ChannelSubmissionUnknown.REDACTED), store.reconcileChannelSubmission(input))
        reject(ChannelSubmissionConflict.REDACTED_SUBMISSION) { store.admitChannelSubmission(input, AT) }
        reject(ChannelSubmissionConflict.IDENTITY_CONFLICT) { store.upsertPendingSend(assertNotNull(evidence.pending)) }
        assertTrue(store.fetchPendingSends(RADIO_A).isEmpty())
    }

    @Test fun deviceDeletionRedactsOrphanReceiptsAndKeepsForeignRadioIntent() = runTest {
        val device = device()
        store.saveDevice(device)
        val message = row()
        val foreign = row(RADIO_B)
        store.saveMessage(message)
        store.saveMessage(foreign)
        val input = submission(message)
        val foreignInput = submission(foreign)
        store.admitChannelSubmission(input, AT)
        val foreignEvidence = store.admitChannelSubmission(foreignInput, AT)
        store.deleteDeviceData(device.id)
        assertEquals(ChannelSubmissionReconciliation.Unknown(ChannelSubmissionUnknown.REDACTED), store.reconcileChannelSubmission(input))
        assertEquals(foreignEvidence, store.admitChannelSubmission(foreignInput, AT))
    }

    @Test fun conflictingConcurrentWritersAcrossContextsCommitOnlyOneFullIntent() = runTest(scheduler) {
        val message = row()
        store.saveMessage(message)
        val original = submission(message)
        val changed = original.copy(envelope = original.envelope.copy(localNodeName = "another node"))
        val second = newStore()
        try {
            suspend fun write(owner: RoomPersistenceStore, input: ChannelSubmission): ChannelSubmissionEvidence? =
                try { owner.admitChannelSubmission(input, AT) }
                catch (failure: PersistenceStoreException) {
                    assertEquals(ChannelSubmissionConflict.IDENTITY_CONFLICT,
                        assertIs<ChannelSubmissionRejected>(failure.cause).reason)
                    null
                }
            val results = listOf(async { write(store, original) }, async { write(second, changed) }).awaitAll()
            assertEquals(1, results.count { it == null })
            val committed = results.filterNotNull().single()
            assertEquals(committed, assertIs<ChannelSubmissionReconciliation.Known>(
                second.reconcileChannelSubmission(committed.submission)).evidence)
            assertEquals(1, store.fetchPendingSends(RADIO_A).size)
        } finally { second.close() }
    }

    @Test fun receiptCompletionUpdateFailureRollsBackCleanupAndPreservesAcceptedEvidence() = runTest {
        val message = row()
        store.saveMessage(message)
        val input = submission(message)
        store.admitChannelSubmission(input, AT)
        val key = QueuedChannelAttemptKey(RADIO_A, input.pendingID, message.id, UUID.randomUUID())
        store.beginQueuedChannelAttempt(key, input.origin.expectedOccupantID, 42u)
        store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.ACCEPTED)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_submission_finish BEFORE UPDATE OF phaseRawValue ON channel_submissions BEGIN SELECT RAISE(ABORT, 'controlled completion failure'); END")
        try {
            assertFailsWith<PersistenceStoreException> { store.completeAcceptedQueuedChannelAttempt(key) }
            val evidence = store.admitChannelSubmission(input, AT)
            assertEquals(ChannelSubmissionPhase.ADMITTED, evidence.phase)
            assertEquals(QueuedChannelDispatchState.ACCEPTED, evidence.pending?.channelDispatchState)
            assertFalse(evidence.attempts.single().isCompleted)
            assertEquals(message, store.fetchMessage(EntityKey(RADIO_A, message.id)))
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_submission_finish") }
        store.completeAcceptedQueuedChannelAttempt(key)
        assertEquals(ChannelSubmissionPhase.ACCEPTED_COMPLETED, store.admitChannelSubmission(input, AT).phase)
    }

    @Test fun foreignLegacyIdentityAndDmOverwriteCannotBecomeAChannelAdmission() = runTest {
        val message = row()
        store.saveMessage(message)
        val input = submission(message)
        val foreignLegacy = PendingSendDTO.fromEnvelope(
            DirectMessageEnvelope(UUID.randomUUID(), CONTACT_A), RADIO_B, id = input.pendingID, enqueuedAt = AT)
        store.upsertPendingSend(foreignLegacy)
        reject(ChannelSubmissionConflict.UNKNOWN_LEGACY_SUBMISSION) { store.admitChannelSubmission(input, AT) }
        store.deletePendingSend(EntityKey(RADIO_B, input.pendingID))
        store.admitChannelSubmission(input, AT)
        reject(ChannelSubmissionConflict.IDENTITY_CONFLICT) { store.upsertPendingSend(foreignLegacy) }
        assertEquals(input.envelope, store.fetchPendingSends(RADIO_A).single().channelMessageEnvelope())
    }
}
