// AndroidOnly: WP-202 Real Room controls for atomic channel completion and incarnation-fenced cleanup.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.ChannelSendStorage
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChannelSendCompletionRepositoryTest : RepositoryTest() {
    private val leaseEpochs = IdentityHashMap<RoomPersistenceStore, ProcessEpoch>()
    private val leaseGenerations = IdentityHashMap<RoomPersistenceStore, Long>()

    private data class Fixture(
        val lease: DirectAckLease,
        val capture: ChannelFailureCaptureResult.Captured,
        val message: MessageDTO,
        val pending: PendingSendDTO?,
        val repeat: MessageRepeatDTO,
        val request: UUID = UUID.randomUUID(),
        val mode: ChannelSendCompletionMode,
    )

    private suspend fun lease(target: RoomPersistenceStore = store): DirectAckLease {
        val epoch = leaseEpochs.getOrPut(target) { ProcessEpoch(UUID.randomUUID()) }
        val generation = (leaseGenerations[target] ?: 0L) + 1L
        leaseGenerations[target] = generation
        return assertIs<DirectAckActivationResult.Activated>(target.activateChannelFailureSession(
            SessionToken(epoch, Generation(generation), RADIO_A), UUID.randomUUID(),
        )).lease
    }

    private suspend fun fixture(
        resend: Boolean = false,
        queued: Boolean = true,
        sendCount: Long = 7,
    ): Fixture {
        val lease = lease()
        val row = message(contactID = null, channelIndex = 2u,
            status = if (resend) MessageStatus.SENT else MessageStatus.PENDING)
            .copy(heardRepeats = 4, sendCount = sendCount)
        store.saveMessage(row)
        val repeat = MessageRepeatDTO(messageID = row.id, receivedAt = row.createdAt, pathNodes = Bytes.of(42))
        store.saveMessageRepeat(RADIO_A, repeat)
        val pending = if (queued) {
            pending(messageID = row.id, sequence = 1).copy(kind = PendingSendKind.CHANNEL,
                contactID = null, channelIndex = 2u, isResend = resend, messageText = row.text,
                messageTimestamp = row.timestamp).also { store.upsertPendingSend(it) }
        } else null
        val capture = assertIs<ChannelFailureCaptureResult.Captured>(store.prepareChannelFailureAttempt(
            lease, UUID.randomUUID(), entity(id = row.id), pending?.id, null,
        ))
        return Fixture(lease, capture, row, pending, repeat,
            mode = if (resend) ChannelSendCompletionMode.RESEND else ChannelSendCompletionMode.SEND)
    }

    private suspend fun complete(f: Fixture): ChannelSendCompletionResult.Applied =
        assertIs<ChannelSendCompletionResult.Applied>(store.recordChannelSendCompleted(
            f.capture.attempt, f.request, f.mode, f.message.timestamp,
        ))

    private suspend fun unchanged(f: Fixture) {
        assertEquals(f.message, store.fetchMessage(entity(id = f.message.id)))
        assertEquals(listOfNotNull(f.pending), store.fetchPendingSendsForMessage(entity(id = f.message.id)))
        assertEquals(listOf(f.repeat), store.fetchMessageRepeats(entity(id = f.message.id)))
    }

    private fun noDTOs(receipt: ChannelSendCompletionReceipt) {
        assertNotEquals(DirectAckPermission.CURRENT, receipt.capture.permission)
        assertNull(receipt.capture.message)
        assertNull(receipt.capture.pending)
    }

    @Test fun sendCommitsSentWithoutChangingCountsRepeatsOrPendingAndInspectIssuesNoHandle() = runTest {
        val f = fixture()
        assertIs<ChannelSendCompletionObservation.NotRecorded>(store.inspectChannelSendCompletion(
            f.lease, f.capture.receipt.captureRequestId, f.request,
        ))
        val applied = complete(f)
        assertEquals(f.message.copy(status = MessageStatus.SENT), applied.receipt.capture.message)
        assertEquals(f.pending, applied.receipt.capture.pending)
        assertEquals(f.request, applied.receipt.completionRequestId)
        assertEquals(f.message.timestamp, applied.receipt.acceptedTimestamp)
        assertEquals(ChannelSendCompletionMode.SEND, applied.receipt.mode)
        assertEquals(listOf(f.repeat), store.fetchMessageRepeats(entity(id = f.message.id)))
        assertEquals(applied.receipt, assertIs<ChannelSendCompletionObservation.Found>(
            store.inspectChannelSendCompletion(f.lease, f.capture.receipt.captureRequestId, f.request),
        ).receipt)
    }

    @Test fun resendIsOneAtomicCheckedMutationAndExactReplayNeverIncrementsTwice() = runTest {
        val f = fixture(resend = true)
        val applied = complete(f)
        val expected = f.message.copy(status = MessageStatus.SENT, sendCount = 8, heardRepeats = 0)
        assertEquals(expected, store.fetchMessage(entity(id = f.message.id)))
        assertTrue(store.fetchMessageRepeats(entity(id = f.message.id)).isEmpty())
        val replay = assertIs<ChannelSendCompletionResult.AlreadyApplied>(store.recordChannelSendCompleted(
            f.capture.attempt, f.request, f.mode, f.message.timestamp,
        ))
        assertNotNull(replay.completion)
        assertEquals(applied.receipt, replay.receipt)
        assertEquals(expected, store.fetchMessage(entity(id = f.message.id)))
        assertEquals(listOf(f.pending), store.fetchPendingSendsForMessage(entity(id = f.message.id)))
        assertEquals(DirectAckRejection.CONFLICT, assertIs<ChannelFailureResult.Rejected>(store.recordChannelFailure(
            f.capture.attempt, UUID.randomUUID(), ChannelFailureCleanup.KEEP_PENDING,
        )).reason)
        assertEquals(DirectAckRejection.CONFLICT, assertIs<ChannelFailureRemapResult.Rejected>(
            store.remapChannelFailureAttempt(f.capture.attempt, UUID.randomUUID()),
        ).reason)
    }

    @Test fun priorFailureJournalStillReplaysReadOnlyAfterCompletionWithoutReapplyingFailed() = runTest {
        val f = fixture()
        val failureRequest = UUID.randomUUID()
        assertIs<ChannelFailureResult.Applied>(store.recordChannelFailure(
            f.capture.attempt, failureRequest, ChannelFailureCleanup.KEEP_PENDING,
        ))
        complete(f)
        val before = assertNotNull(store.fetchMessage(entity(id = f.message.id)))
        assertEquals(MessageStatus.SENT, before.status)
        val replay = assertIs<ChannelFailureResult.AlreadyApplied>(store.recordChannelFailure(
            f.capture.attempt, failureRequest, ChannelFailureCleanup.KEEP_PENDING,
        ))
        assertEquals(before, replay.receipt.message)
        assertEquals(ChannelFailureOperation.FAILED, replay.receipt.operations.single().operation)
        assertEquals(before, store.fetchMessage(entity(id = f.message.id)))
    }

    @Test fun acceptanceTimestampAndPendingModeMustMatchBeforeAnyCommit() = runTest {
        val f = fixture(resend = true)
        for ((mode, timestamp) in listOf(
            ChannelSendCompletionMode.SEND to f.message.timestamp,
            ChannelSendCompletionMode.RESEND to (f.message.timestamp + 1u),
        )) {
            assertEquals(DirectAckRejection.CONFLICT, assertIs<ChannelSendCompletionResult.Rejected>(
                store.recordChannelSendCompleted(f.capture.attempt, f.request, mode, timestamp),
            ).reason)
            unchanged(f)
            assertIs<ChannelSendCompletionObservation.NotRecorded>(store.inspectChannelSendCompletion(
                f.lease, f.capture.receipt.captureRequestId, f.request,
            ))
        }
        complete(f)
    }

    @Test fun heldCompletionRejectsBothDirectAndEqualLookingChannelReplacement() = runTest {
        for (direct in listOf(true, false)) {
            val f = fixture(resend = true)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val commit = async(start = CoroutineStart.UNDISPATCHED) {
                entered.complete(Unit); release.await()
                store.recordChannelSendCompleted(f.capture.attempt, f.request, f.mode, f.message.timestamp)
            }
            entered.await()
            db.messages().delete(RADIO_A.value, f.message.id)
            val replacement = if (direct) f.message.copy(channelIndex = null, contactID = CONTACT_A) else f.message
            db.messages().insert(replacement.toEntity())
            val replacementPending = requireNotNull(f.pending).copy(kind = if (direct) PendingSendKind.DM else PendingSendKind.CHANNEL,
                contactID = if (direct) CONTACT_A else null, channelIndex = replacement.channelIndex)
            store.upsertPendingSend(replacementPending)
            store.saveMessageRepeat(RADIO_A, f.repeat)
            release.complete(Unit)
            assertIs<ChannelSendCompletionResult.Rejected>(commit.await())
            assertEquals(replacement, store.fetchMessage(entity(id = f.message.id)))
            assertEquals(listOf(replacementPending), store.fetchPendingSendsForMessage(entity(id = f.message.id)))
            assertEquals(listOf(f.repeat), store.fetchMessageRepeats(entity(id = f.message.id)))
            assertIs<ChannelSendCompletionObservation.NotRecorded>(store.inspectChannelSendCompletion(
                f.lease, f.capture.receipt.captureRequestId, f.request,
            ))
        }
    }

    @Test fun sameValueMessageOrPendingDmlInvalidatesAcceptanceRatherThanRenewingByReadback() = runTest {
        for (pendingWrite in listOf(false, true)) {
            val f = fixture()
            if (pendingWrite) {
                val pending = requireNotNull(f.pending)
                db.pendingSends().setAttemptCount(RADIO_A.value, pending.id, requireNotNull(pending.attemptCount))
            } else db.messages().setStatus(RADIO_A.value, f.message.id, f.message.status.rawValue)
            assertIs<ChannelSendCompletionResult.Rejected>(store.recordChannelSendCompleted(
                f.capture.attempt, f.request, f.mode, f.message.timestamp,
            ))
            unchanged(f)
        }
    }

    @Test fun cleanupDeletesOnlyCapturedPendingAndExactReplayCannotDeleteReplacement() = runTest {
        val f = fixture()
        val sibling = requireNotNull(f.pending).copy(id = UUID.randomUUID(), sequence = 2)
        store.upsertPendingSend(sibling)
        val capture = assertIs<ChannelFailureCaptureResult.Captured>(store.prepareChannelFailureAttempt(
            f.lease, UUID.randomUUID(), entity(id = f.message.id), f.pending.id, null,
        ))
        val current = f.copy(capture = capture)
        val applied = complete(current)
        val cleanup = UUID.randomUUID()
        val cleaned = assertIs<ChannelSendPendingCompletionResult.Applied>(
            store.completeChannelSendPending(applied.completion, cleanup),
        ).receipt
        assertEquals(cleanup, cleaned.pendingCompletionRequestId)
        noDTOs(cleaned)
        assertEquals(listOf(sibling), store.fetchPendingSendsForMessage(entity(id = f.message.id)))
        assertEquals(f.message.copy(status = MessageStatus.SENT), store.fetchMessage(entity(id = f.message.id)))
        val replacement = f.pending.copy(messageText = "replacement envelope")
        store.upsertPendingSend(replacement)
        val before = store.fetchPendingSendsForMessage(entity(id = f.message.id))
        noDTOs(assertIs<ChannelSendPendingCompletionResult.AlreadyApplied>(
            store.completeChannelSendPending(applied.completion, cleanup),
        ).receipt)
        assertEquals(before, store.fetchPendingSendsForMessage(entity(id = f.message.id)))
        assertEquals(DirectAckRejection.CONFLICT, assertIs<ChannelSendPendingCompletionResult.Rejected>(
            store.completeChannelSendPending(applied.completion, UUID.randomUUID()),
        ).reason)
        val replay = assertIs<ChannelSendCompletionResult.AlreadyApplied>(store.recordChannelSendCompleted(
            capture.attempt, f.request, f.mode, f.message.timestamp,
        ))
        assertNull(replay.completion); noDTOs(replay.receipt)
    }

    @Test fun replacementPendingOrNewSiblingAfterSentInvalidatesCleanupWithoutDeletingEither() = runTest {
        for (sameId in listOf(true, false)) {
            val f = fixture()
            val applied = complete(f)
            val pending = requireNotNull(f.pending)
            store.upsertPendingSend(pending.copy(id = if (sameId) pending.id else UUID.randomUUID(),
                messageText = "new pending", sequence = 2))
            val before = store.fetchPendingSendsForMessage(entity(id = f.message.id))
            assertIs<ChannelSendPendingCompletionResult.Rejected>(
                store.completeChannelSendPending(applied.completion, UUID.randomUUID()),
            )
            assertEquals(before, store.fetchPendingSendsForMessage(entity(id = f.message.id)))
            val replay = assertIs<ChannelSendCompletionResult.AlreadyApplied>(store.recordChannelSendCompleted(
                f.capture.attempt, f.request, f.mode, f.message.timestamp,
            ))
            assertNull(replay.completion); noDTOs(replay.receipt)
        }
    }

    @Test fun postCommitMessageReplacementRetainsTupleButNeitherHandleNorFailureAuthority() = runTest {
        val f = fixture(resend = true)
        val applied = complete(f)
        store.deleteMessage(entity(id = f.message.id))
        val replacement = f.message.copy(text = "replacement", sendCount = 91, heardRepeats = 92)
        store.saveMessage(replacement)
        store.upsertPendingSend(requireNotNull(f.pending))
        val replay = assertIs<ChannelSendCompletionResult.AlreadyApplied>(store.recordChannelSendCompleted(
            f.capture.attempt, f.request, f.mode, f.message.timestamp,
        ))
        assertNull(replay.completion); noDTOs(replay.receipt)
        assertEquals(applied.receipt.completionRequestId, replay.receipt.completionRequestId)
        assertEquals(applied.receipt.mode, replay.receipt.mode)
        assertEquals(applied.receipt.acceptedTimestamp, replay.receipt.acceptedTimestamp)
        assertIs<ChannelSendPendingCompletionResult.Rejected>(
            store.completeChannelSendPending(applied.completion, UUID.randomUUID()),
        )
        assertEquals(replacement, store.fetchMessage(entity(id = f.message.id)))
        assertIs<ChannelSendCompletionResult.Rejected>(store.recordChannelSendCompleted(
            f.capture.attempt, UUID.randomUUID(), f.mode, f.message.timestamp,
        ))
        assertIs<ChannelSendCompletionResult.Rejected>(store.recordChannelSendCompleted(
            f.capture.attempt, f.request, ChannelSendCompletionMode.SEND, f.message.timestamp,
        ))
        assertIs<ChannelSendCompletionResult.Rejected>(store.recordChannelSendCompleted(
            f.capture.attempt, f.request, f.mode, f.message.timestamp + 1u,
        ))
        assertIs<ChannelSendCompletionObservation.Rejected>(store.inspectChannelSendCompletion(
            f.lease, f.capture.receipt.captureRequestId, UUID.randomUUID(),
        ))
    }

    @Test fun completionJournalFailureRollsBackSentCountRepeatsAndRevisionThenExactRequestSucceeds() = runTest {
        val f = fixture(resend = true)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_completion BEFORE INSERT ON channel_send_completions " +
                "BEGIN SELECT RAISE(ABORT,'controlled completion journal failure'); END",
        )
        try {
            assertFailsWith<PersistenceStoreException> {
                store.recordChannelSendCompleted(f.capture.attempt, f.request, f.mode, f.message.timestamp)
            }
            unchanged(f)
            assertIs<ChannelSendCompletionObservation.NotRecorded>(store.inspectChannelSendCompletion(
                f.lease, f.capture.receipt.captureRequestId, f.request,
            ))
            assertEquals(DirectAckPermission.CURRENT, assertIs<ChannelFailureObservation.Found>(
                store.inspectChannelFailureAttempt(f.lease, f.capture.receipt.captureRequestId),
            ).receipt.permission)
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_completion") }
        complete(f)
    }

    @Test fun cleanupJournalFailureRollsBackDeletionAndRetainsIssuedCompletionForExactRetry() = runTest {
        val f = fixture()
        val applied = complete(f)
        val cleanup = UUID.randomUUID()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_completion_cleanup BEFORE UPDATE ON channel_send_completions " +
                "BEGIN SELECT RAISE(ABORT,'controlled cleanup journal failure'); END",
        )
        try {
            assertFailsWith<PersistenceStoreException> { store.completeChannelSendPending(applied.completion, cleanup) }
            assertEquals(listOf(f.pending), store.fetchPendingSendsForMessage(entity(id = f.message.id)))
            val receipt = assertIs<ChannelSendCompletionObservation.Found>(store.inspectChannelSendCompletion(
                f.lease, f.capture.receipt.captureRequestId, f.request,
            )).receipt
            assertEquals(DirectAckPermission.CURRENT, receipt.capture.permission)
            assertNull(receipt.pendingCompletionRequestId)
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_completion_cleanup") }
        assertIs<ChannelSendPendingCompletionResult.Applied>(store.completeChannelSendPending(applied.completion, cleanup))
    }

    @Test fun overflowIsTypedInvalidDataWithNoPartialAcceptance() = runTest {
        val f = fixture(resend = true, sendCount = Long.MAX_VALUE)
        assertIs<PersistenceStoreError.InvalidData>(assertFailsWith<PersistenceStoreException> {
            store.recordChannelSendCompleted(f.capture.attempt, f.request, f.mode, f.message.timestamp)
        }.error)
        unchanged(f)
        assertIs<ChannelSendCompletionObservation.NotRecorded>(store.inspectChannelSendCompletion(
            f.lease, f.capture.receipt.captureRequestId, f.request,
        ))
    }

    @Test fun staleLeaseAndForeignHandlesRejectButCommittedReplayAndInspectionRetainHistory() = runTest {
        val f = fixture()
        val applied = complete(f)
        assertIs<ChannelSendCompletionResult.Rejected>(store.recordChannelSendCompleted(
            object : ChannelFailureAttempt {}, UUID.randomUUID(), f.mode, f.message.timestamp,
        ))
        assertIs<ChannelSendPendingCompletionResult.Rejected>(store.completeChannelSendPending(
            object : ChannelSendCompletion {}, UUID.randomUUID(),
        ))
        store.revokeChannelFailureSession(f.lease)
        assertEquals(DirectAckRejection.STALE_LEASE, assertIs<ChannelSendPendingCompletionResult.Rejected>(
            store.completeChannelSendPending(applied.completion, UUID.randomUUID()),
        ).reason)
        val replay = assertIs<ChannelSendCompletionResult.AlreadyApplied>(store.recordChannelSendCompleted(
            f.capture.attempt, f.request, f.mode, f.message.timestamp,
        ))
        assertNull(replay.completion); noDTOs(replay.receipt)
        assertEquals(DirectAckPermission.STALE_LEASE, replay.receipt.capture.permission)
        noDTOs(assertIs<ChannelSendCompletionObservation.Found>(store.inspectChannelSendCompletion(
            f.lease, f.capture.receipt.captureRequestId, f.request,
        )).receipt)
    }

    @Test fun deliveredReplacementIsNeverRegressedOrCountedByOldAcceptance() = runTest {
        val f = fixture(resend = true)
        store.updateMessageStatus(entity(id = f.message.id), MessageStatus.DELIVERED)
        assertIs<ChannelSendCompletionResult.Rejected>(store.recordChannelSendCompleted(
            f.capture.attempt, f.request, f.mode, f.message.timestamp,
        ))
        assertEquals(f.message.copy(status = MessageStatus.DELIVERED), store.fetchMessage(entity(id = f.message.id)))
        assertEquals(listOf(f.repeat), store.fetchMessageRepeats(entity(id = f.message.id)))
    }

    @Test fun revokedLeaseCannotCreateAcceptanceAndOldRemapVersionCannotCompleteItsSuccessor() = runTest {
        val stale = fixture()
        store.revokeChannelFailureSession(stale.lease)
        assertEquals(DirectAckRejection.STALE_LEASE, assertIs<ChannelSendCompletionResult.Rejected>(
            store.recordChannelSendCompleted(stale.capture.attempt, stale.request, stale.mode, stale.message.timestamp),
        ).reason)
        unchanged(stale)
        val f = fixture(resend = true)
        val successor = assertIs<ChannelFailureRemapResult.Remapped>(
            store.remapChannelFailureAttempt(f.capture.attempt, UUID.randomUUID()),
        )
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<ChannelSendCompletionResult.Rejected>(
            store.recordChannelSendCompleted(f.capture.attempt, f.request, f.mode, f.message.timestamp),
        ).reason)
        assertIs<ChannelSendCompletionResult.Applied>(store.recordChannelSendCompleted(
            successor.attempt, f.request, f.mode, f.message.timestamp,
        ))
    }

    @Test fun resendDeletesNoOtherRadiosSameKeyRepeatsAndKeepsForeignMessageCounters() = runTest {
        val f = fixture(resend = true)
        val foreign = f.message.copy(radioId = RADIO_B)
        store.saveMessage(foreign)
        store.saveMessageRepeat(RADIO_B, f.repeat)
        complete(f)
        assertTrue(store.fetchMessageRepeats(entity(id = f.message.id)).isEmpty())
        assertEquals(listOf(f.repeat), store.fetchMessageRepeats(entity(RADIO_B, f.message.id)))
        assertEquals(foreign, store.fetchMessage(entity(RADIO_B, f.message.id)))
    }

    @Test fun inlineCompletionHasNoPendingDeletionGrantAndRestoreRetiresItWithoutErasingHistory() = runTest {
        val f = fixture(queued = false)
        val applied = complete(f)
        assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<ChannelSendPendingCompletionResult.Rejected>(
            store.completeChannelSendPending(applied.completion, UUID.randomUUID()),
        ).reason)
        store.withBackupRestore { database, _ ->
            ChannelSendStorage.invalidateRestore(database.openHelper.writableDatabase)
        }
        val replay = assertIs<ChannelSendCompletionResult.AlreadyApplied>(store.recordChannelSendCompleted(
            f.capture.attempt, f.request, f.mode, f.message.timestamp,
        ))
        assertNull(replay.completion); noDTOs(replay.receipt)
    }

    @Test fun malformedCompletionModeOrTimestampIsTypedInvalidDataNotAcceptedHistory() = runTest {
        val f = fixture()
        complete(f)
        val dao = db.channelSends()
        val original = assertNotNull(dao.completion(RADIO_A.value, f.capture.receipt.captureRequestId))
        for (malformed in listOf(original.copy(mode = "UNKNOWN"), original.copy(acceptedTimestamp = -1),
            original.copy(outputVersion = 0))) {
            assertEquals(1, dao.update(malformed))
            assertIs<PersistenceStoreError.InvalidData>(assertFailsWith<PersistenceStoreException> {
                store.inspectChannelSendCompletion(f.lease, f.capture.receipt.captureRequestId, f.request)
            }.error)
        }
    }

    @Test fun fileReopenKeepsCompletionButRejectsOldHandle() = runTest {
        val name = "channel-completion-${UUID.randomUUID()}.db"
        var database = MeshCoreDatabase.open(context, name)
        var target = RoomPersistenceStore(database, owner, clock)
        try {
            val lease = lease(target)
            val row = message(contactID = null, channelIndex = 2u, status = MessageStatus.PENDING)
            target.saveMessage(row)
            val capture = assertIs<ChannelFailureCaptureResult.Captured>(target.prepareChannelFailureAttempt(
                lease, UUID.randomUUID(), entity(id = row.id), null, null,
            ))
            val request = UUID.randomUUID()
            val applied = assertIs<ChannelSendCompletionResult.Applied>(target.recordChannelSendCompleted(
                capture.attempt, request, ChannelSendCompletionMode.SEND, row.timestamp,
            ))
            target.close(); database.close()
            database = MeshCoreDatabase.open(context, name)
            target = RoomPersistenceStore(database, owner, clock)
            assertEquals(row.copy(status = MessageStatus.SENT), target.fetchMessage(entity(id = row.id)))
            val journal = assertNotNull(database.channelSends().completion(RADIO_A.value, capture.receipt.captureRequestId))
            assertEquals(request, journal.completionRequestId)
            assertEquals(row.timestamp.toLong(), journal.acceptedTimestamp)
            assertEquals(DirectAckRejection.INVALID_HANDLE, assertIs<ChannelSendCompletionResult.Rejected>(
                target.recordChannelSendCompleted(capture.attempt, request, ChannelSendCompletionMode.SEND, row.timestamp),
            ).reason)
            assertEquals(DirectAckRejection.INVALID_HANDLE, assertIs<ChannelSendPendingCompletionResult.Rejected>(
                target.completeChannelSendPending(applied.completion, UUID.randomUUID()),
            ).reason)
            assertEquals(DirectAckRejection.INVALID_HANDLE, assertIs<ChannelSendCompletionObservation.Rejected>(
                target.inspectChannelSendCompletion(lease, capture.receipt.captureRequestId, request),
            ).reason)
        } finally { target.close(); database.close(); context.deleteDatabase(name) }
    }
}
