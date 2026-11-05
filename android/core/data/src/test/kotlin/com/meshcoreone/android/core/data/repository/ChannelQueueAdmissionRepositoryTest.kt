// AndroidOnly: WP-202 Real Room channel pending lifecycle and cross-generation acceptance admission controls.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.ChannelSendStorage
import com.meshcoreone.android.core.database.toDTO
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.*
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChannelQueueAdmissionRepositoryTest : RepositoryTest() {
    private data class Work(val message: MessageDTO, val pending: PendingSendDTO)
    private val epoch = ProcessEpoch(UUID.randomUUID())
    private var lastGeneration = 0L

    private suspend fun lease(generation: Long? = null): DirectAckLease {
        val nextGeneration = maxOf(lastGeneration, generation ?: 0L) + 1L
        lastGeneration = nextGeneration
        return assertIs<DirectAckActivationResult.Activated>(store.activateChannelFailureSession(
            SessionToken(epoch, Generation(nextGeneration), RADIO_A), UUID.randomUUID(),
        )).lease
    }

    private suspend fun work(resend: Boolean = false, attempt: Long? = 0): Work {
        val row = message(contactID = null, channelIndex = 2u,
            status = if (resend) MessageStatus.SENT else MessageStatus.PENDING)
        store.saveMessage(row)
        val pending = pending(messageID = row.id, sequence = 1, attempt = attempt).copy(
            kind = PendingSendKind.CHANNEL, contactID = null, channelIndex = row.channelIndex,
            isResend = resend, messageText = row.text, messageTimestamp = row.timestamp,
        )
        store.upsertPendingSend(pending)
        return Work(row, pending)
    }

    private suspend fun birth(work: Work) =
        assertNotNull(db.channelSends().pendingLifecycle(RADIO_A.value, work.pending.id))

    private suspend fun capture(lease: DirectAckLease, work: Work) =
        assertIs<ChannelFailureCaptureResult.Captured>(store.prepareChannelFailureAttempt(
            lease, UUID.randomUUID(), entity(id = work.message.id), work.pending.id, null,
        ))

    private suspend fun committed(lease: DirectAckLease, work: Work): ChannelSendCompletionResult.Applied {
        val capture = capture(lease, work)
        return assertIs<ChannelSendCompletionResult.Applied>(store.recordChannelSendCompleted(
            capture.attempt, UUID.randomUUID(),
            if (work.pending.isResend) ChannelSendCompletionMode.RESEND else ChannelSendCompletionMode.SEND,
            work.message.timestamp,
        ))
    }

    @Test fun retiredCommittedPendingCannotBePreparedUnderReplacementLeaseOrTimestamped() = runTest {
        val first = lease()
        val work = work()
        committed(first, work)
        store.revokeChannelFailureSession(first)
        val next = lease(2)
        val before = assertNotNull(store.fetchMessage(entity(id = work.message.id)))
        assertEquals(DirectAckRejection.CONFLICT, assertIs<ChannelFailureCaptureResult.Rejected>(
            store.prepareChannelFailureAttempt(next, UUID.randomUUID(), entity(id = work.message.id),
                work.pending.id, before.timestamp + 1u),
        ).reason)
        assertEquals(before, store.fetchMessage(entity(id = work.message.id)))
        assertEquals(listOf(work.pending), store.fetchPendingSendsForMessage(entity(id = work.message.id)))
    }

    @Test fun genericBumpRegeneratesAckIdentityButCannotLaunderCommittedPendingLifecycle() = runTest {
        val first = lease()
        val work = work()
        committed(first, work)
        val birthBefore = birth(work)
        val mutation = assertNotNull(db.directAcks().incarnation(RADIO_A.value, work.pending.id, "pending"))
        store.revokeChannelFailureSession(first)
        assertEquals(1L, store.incrementPendingSendAttemptCount(entity(id = work.message.id)))
        assertEquals(birthBefore, birth(work))
        assertNotEquals(mutation.incarnation,
            assertNotNull(db.directAcks().incarnation(RADIO_A.value, work.pending.id, "pending")).incarnation)
        val next = lease(2)
        val before = assertNotNull(store.fetchMessage(entity(id = work.message.id)))
        assertIs<ChannelFailureCaptureResult.Rejected>(store.prepareChannelFailureAttempt(
            next, UUID.randomUUID(), entity(id = work.message.id), work.pending.id, before.timestamp + 1u,
        ))
        assertEquals(before, store.fetchMessage(entity(id = work.message.id)))
        assertEquals(1L, store.fetchPendingSendsForMessage(entity(id = work.message.id)).single().attemptCount)
    }

    @Test fun sameValueUpsertPreservesBirthButActualDeleteInsertSameIdIsNewWork() = runTest {
        val first = lease()
        val work = work()
        committed(first, work)
        val birthBefore = birth(work)
        store.upsertPendingSend(work.pending)
        assertEquals(birthBefore, birth(work))
        store.revokeChannelFailureSession(first)
        val next = lease(2)
        assertIs<ChannelFailureCaptureResult.Rejected>(store.prepareChannelFailureAttempt(
            next, UUID.randomUUID(), entity(id = work.message.id), work.pending.id, null,
        ))
        store.deletePendingSend(entity(id = work.pending.id))
        store.upsertPendingSend(work.pending)
        assertNotEquals(birthBefore.incarnation, birth(work).incarnation)
        assertIs<ChannelFailureCaptureResult.Captured>(store.prepareChannelFailureAttempt(
            next, UUID.randomUUID(), entity(id = work.message.id), work.pending.id, null,
        ))
    }

    @Test fun recreatedMessageCannotInheritCompletionEvenWithSameIdsAndPayload() = runTest {
        val first = lease()
        val work = work()
        committed(first, work)
        val old = assertNotNull(db.directAcks().incarnation(RADIO_A.value, work.message.id, "message"))
        store.revokeChannelFailureSession(first)
        store.deleteMessage(entity(id = work.message.id))
        store.saveMessage(work.message)
        store.upsertPendingSend(work.pending)
        assertNotEquals(old.incarnation,
            assertNotNull(db.directAcks().incarnation(RADIO_A.value, work.message.id, "message")).incarnation)
        capture(lease(2), work)
    }

    @Test fun siblingPendingHasIndependentBirthAndDoesNotInheritAnotherCompletion() = runTest {
        val first = lease()
        val work = work()
        committed(first, work)
        store.revokeChannelFailureSession(first)
        val sibling = work.pending.copy(id = UUID.randomUUID(), sequence = 2)
        store.upsertPendingSend(sibling)
        assertNotEquals(birth(work).incarnation,
            assertNotNull(db.channelSends().pendingLifecycle(RADIO_A.value, sibling.id)).incarnation)
        capture(lease(2), work.copy(pending = sibling))
    }

    @Test fun primaryKeyAndMessageRetargetProduceFreshBirthWithoutWeakeningAckMutationFences() = runTest {
        val lease = lease()
        val work = work()
        val capture = capture(lease, work)
        val old = birth(work)
        val nextId = UUID.randomUUID()
        db.openHelper.writableDatabase.execSQL("UPDATE pending_sends SET id = ? WHERE radioId = ? AND id = ?",
            arrayOf(nextId.canonicalString(), RADIO_A.value.canonicalString(), work.pending.id.canonicalString()))
        assertNull(db.channelSends().pendingLifecycle(RADIO_A.value, work.pending.id))
        val moved = assertNotNull(db.channelSends().pendingLifecycle(RADIO_A.value, nextId))
        assertNotEquals(old.incarnation, moved.incarnation)
        assertIs<ChannelFailureResult.Rejected>(store.recordChannelFailure(
            capture.attempt, UUID.randomUUID(), ChannelFailureCleanup.KEEP_PENDING,
        ))
        val other = message(contactID = null, channelIndex = 2u)
        store.saveMessage(other)
        db.openHelper.writableDatabase.execSQL("UPDATE pending_sends SET messageID = ? WHERE radioId = ? AND id = ?",
            arrayOf(other.id.canonicalString(), RADIO_A.value.canonicalString(), nextId.canonicalString()))
        val retargeted = assertNotNull(db.channelSends().pendingLifecycle(RADIO_A.value, nextId))
        assertEquals(other.id, retargeted.messageId)
        assertNotEquals(moved.incarnation, retargeted.incarnation)
        db.openHelper.writableDatabase.execSQL("UPDATE pending_sends SET radioId = ? WHERE radioId = ? AND id = ?",
            arrayOf(RADIO_B.value.canonicalString(), RADIO_A.value.canonicalString(), nextId.canonicalString()))
        assertNull(db.channelSends().pendingLifecycle(RADIO_A.value, nextId))
        val movedRadio = assertNotNull(db.channelSends().pendingLifecycle(RADIO_B.value, nextId))
        assertEquals(other.id, movedRadio.messageId)
        assertNotEquals(retargeted.incarnation, movedRadio.incarnation)
        ChannelSendStorage.verifyPendingLifecycles(db.openHelper.writableDatabase)
    }

    @Test fun insertOrReplaceSameKeyProducesFreshLifecycleEvenWithoutRecursiveDeleteTriggers() = runTest {
        val work = work()
        val old = birth(work)
        db.pendingSends().upsert(work.pending.toEntity())
        assertEquals(old, birth(work))
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO pending_sends SELECT * FROM pending_sends WHERE radioId = ? AND id = ?",
            arrayOf(RADIO_A.value.canonicalString(), work.pending.id.canonicalString()),
        )
        assertNotEquals(old.incarnation, birth(work).incarnation)
        ChannelSendStorage.verifyPendingLifecycles(db.openHelper.writableDatabase)
    }

    @Test fun missingOrAlteredLifecycleTriggersAndMissingBirthFailTypedClosedRatherThanRepairing() = runTest {
        val lease = lease()
        val work = work()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER channel_pending_lifecycle_update")
        assertIs<PersistenceStoreError.InvalidData>(assertFailsWith<PersistenceStoreException> {
            store.prepareChannelFailureAttempt(lease, UUID.randomUUID(), entity(id = work.message.id), work.pending.id, null)
        }.error)
        assertEquals(listOf(work.pending), db.pendingSends().forMessage(RADIO_A.value, work.message.id).map { it.toDTO() })
        assertFailsWith<IllegalStateException> {
            ChannelSendStorage.installPendingLifecycles(db.openHelper.writableDatabase)
        }
    }

    @Test fun atomicAdmissionUsesActualFirstPendingModeAndNullCountWithReadOnlyExactReplay() = runTest {
        val lease = lease()
        val work = work(resend = true, attempt = null)
        val later = work.pending.copy(id = UUID.randomUUID(), sequence = 2, isResend = false, attemptCount = 8)
        store.upsertPendingSend(later)
        val request = UUID.randomUUID()
        val fresh = work.message.timestamp + 77u
        val ready = assertIs<ChannelQueueAdmissionResult.Ready>(
            store.admitQueuedChannelSend(lease, request, entity(id = work.message.id), fresh),
        )
        assertEquals(1L, ready.postBumpCount)
        assertEquals(work.pending.copy(attemptCount = 1), ready.receipt.pending)
        assertEquals(work.message.copy(timestamp = fresh), ready.receipt.message)
        val mutation = assertNotNull(db.directAcks().incarnation(RADIO_A.value, work.pending.id, "pending"))
        val originalBirth = birth(work)
        val replay = assertIs<ChannelQueueAdmissionResult.AlreadyReady>(
            store.admitQueuedChannelSend(lease, request, entity(id = work.message.id), fresh),
        )
        assertEquals(ready, ChannelQueueAdmissionResult.Ready(replay.attempt, replay.receipt, replay.postBumpCount))
        assertEquals(mutation, db.directAcks().incarnation(RADIO_A.value, work.pending.id, "pending"))
        assertEquals(originalBirth, birth(work))
        assertEquals(later, store.fetchPendingSendsForMessage(entity(id = work.message.id)).last())
    }

    @Test fun sendAndLaterResendPreserveActualTimestampWithoutInventingAnAttemptCap() = runTest {
        for ((resend, previousCount) in listOf(false to 0L, true to 5L, true to 999_999L)) {
            val lease = lease()
            val work = work(resend, previousCount)
            val ready = assertIs<ChannelQueueAdmissionResult.Ready>(store.admitQueuedChannelSend(
                lease, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp + 7u,
            ))
            assertEquals(previousCount + 1, ready.postBumpCount)
            assertEquals(work.message, ready.receipt.message)
            assertEquals(work.pending.copy(attemptCount = previousCount + 1), ready.receipt.pending)
        }
    }

    @Test fun atomicReplacementLeaseAdmissionFindsCommittedWorkBeforeBumpEvenAfterGenericMutation() = runTest {
        val first = lease()
        val work = work()
        val completed = committed(first, work)
        store.revokeChannelFailureSession(first)
        store.incrementPendingSendAttemptCount(entity(id = work.message.id))
        val pendingBefore = store.fetchPendingSendsForMessage(entity(id = work.message.id))
        val messageBefore = store.fetchMessage(entity(id = work.message.id))
        val mutationBefore = db.directAcks().incarnation(RADIO_A.value, work.pending.id, "pending")
        val next = lease(2)
        val result = assertIs<ChannelQueueAdmissionResult.PreviouslyCompleted>(store.admitQueuedChannelSend(
            next, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp + 8u,
        ))
        assertEquals(completed.receipt.completionRequestId, result.receipt.completionRequestId)
        assertNull(result.receipt.capture.message); assertNull(result.receipt.capture.pending)
        assertEquals(DirectAckPermission.STALE_LEASE, result.receipt.capture.permission)
        assertEquals(messageBefore, store.fetchMessage(entity(id = work.message.id)))
        assertEquals(pendingBefore, store.fetchPendingSendsForMessage(entity(id = work.message.id)))
        assertEquals(mutationBefore, db.directAcks().incarnation(RADIO_A.value, work.pending.id, "pending"))
        assertIs<ChannelFailureCaptureResult.Rejected>(store.prepareChannelFailureAttempt(
            next, UUID.randomUUID(), entity(id = work.message.id), null, work.message.timestamp + 9u,
        ))
    }

    @Test fun sameIdNewPendingAndRecreatedMessageCanObtainNewAtomicAdmissionButNotOldRequestAuthority() = runTest {
        for (recreateMessage in listOf(false, true)) {
            val first = lease()
            val work = work()
            val request = UUID.randomUUID()
            val old = assertIs<ChannelQueueAdmissionResult.Ready>(store.admitQueuedChannelSend(
                first, request, entity(id = work.message.id), work.message.timestamp,
            ))
            assertIs<ChannelSendCompletionResult.Applied>(store.recordChannelSendCompleted(
                old.attempt, UUID.randomUUID(), ChannelSendCompletionMode.SEND, work.message.timestamp,
            ))
            val oldBirth = birth(work)
            store.revokeChannelFailureSession(first)
            if (recreateMessage) {
                store.deleteMessage(entity(id = work.message.id))
                store.saveMessage(work.message)
            } else store.deletePendingSend(entity(id = work.pending.id))
            store.upsertPendingSend(work.pending)
            assertNotEquals(oldBirth.incarnation, birth(work).incarnation)
            val next = lease(2)
            assertIs<ChannelQueueAdmissionResult.Rejected>(store.admitQueuedChannelSend(
                next, request, entity(id = work.message.id), work.message.timestamp,
            ))
            val ready = assertIs<ChannelQueueAdmissionResult.Ready>(store.admitQueuedChannelSend(
                next, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp,
            ))
            assertEquals(1L, ready.postBumpCount)
            assertEquals(work.pending.copy(attemptCount = 1), ready.receipt.pending)
        }
    }

    @Test fun synchronousOriginalAcceptanceSurvivesLeaseRetirementAndGenericBumpWithoutGrantRenewal() = runTest {
        val first = lease()
        val work = work()
        val captured = capture(first, work)
        store.updateMessageStatus(entity(id = work.message.id), work.message.status)
        val completion = UUID.randomUUID()
        val registered = assertIs<ChannelQueueAcceptanceResult.Registered>(store.noteAcceptedChannelAttempt(
            captured.attempt, completion, ChannelSendCompletionMode.SEND, work.message.timestamp,
        ))
        assertEquals(captured.receipt.captureRequestId, registered.acceptance.captureRequestId)
        assertIs<ChannelQueueAcceptanceResult.AlreadyRegistered>(store.noteAcceptedChannelAttempt(
            captured.attempt, completion, ChannelSendCompletionMode.SEND, work.message.timestamp,
        ))
        assertIs<ChannelSendCompletionResult.Rejected>(store.recordChannelSendCompleted(
            captured.attempt, completion, ChannelSendCompletionMode.SEND, work.message.timestamp,
        ))
        store.revokeChannelFailureSession(first)
        store.incrementPendingSendAttemptCount(entity(id = work.message.id))
        val before = store.fetchPendingSendsForMessage(entity(id = work.message.id))
        val next = lease(2)
        val result = assertIs<ChannelQueueAdmissionResult.Uncertain>(store.admitQueuedChannelSend(
            next, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp + 2u,
        ))
        assertEquals(ChannelQueueUncertaintyReason.RETAINED_ACCEPTANCE, result.reason)
        assertEquals(registered.acceptance, result.acceptance)
        assertEquals(before, store.fetchPendingSendsForMessage(entity(id = work.message.id)))
        assertIs<ChannelFailureCaptureResult.Rejected>(store.prepareChannelFailureAttempt(
            next, UUID.randomUUID(), entity(id = work.message.id), work.pending.id, null,
        ))
        assertIs<ChannelFailureCaptureResult.Rejected>(store.prepareChannelFailureAttempt(
            next, UUID.randomUUID(), entity(id = work.message.id), null, work.message.timestamp + 8u,
        ))
        assertEquals(work.message, store.fetchMessage(entity(id = work.message.id)))
    }

    @Test fun acceptanceReportAfterLeaseRevocationStillRetainsOnlyOriginalIssuedIdentity() = runTest {
        val first = lease()
        val work = work()
        val captured = capture(first, work)
        store.revokeChannelFailureSession(first)
        val completion = UUID.randomUUID()
        val registered = assertIs<ChannelQueueAcceptanceResult.Registered>(store.noteAcceptedChannelAttempt(
            captured.attempt, completion, ChannelSendCompletionMode.SEND, work.message.timestamp,
        ))
        val next = lease(2)
        val result = assertIs<ChannelQueueAdmissionResult.Uncertain>(store.admitQueuedChannelSend(
            next, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp,
        ))
        assertEquals(registered.acceptance, result.acceptance)
        assertEquals(listOf(work.pending), store.fetchPendingSendsForMessage(entity(id = work.message.id)))
    }

    @Test fun acceptanceRegistrationAuthenticatesImmutableTupleAndNeverOverwritesSameWorkFence() = runTest {
        val lease = lease()
        val work = work()
        val captured = capture(lease, work)
        val other = capture(lease, work)
        val completion = UUID.randomUUID()
        assertIs<ChannelQueueAcceptanceResult.Registered>(store.noteAcceptedChannelAttempt(
            captured.attempt, completion, ChannelSendCompletionMode.SEND, work.message.timestamp,
        ))
        assertIs<ChannelFailureCaptureResult.Rejected>(store.prepareChannelFailureAttempt(
            lease, captured.receipt.captureRequestId, entity(id = work.message.id), work.pending.id, null,
        ))
        for ((id, mode, timestamp) in listOf(
            Triple(UUID.randomUUID(), ChannelSendCompletionMode.SEND, work.message.timestamp),
            Triple(completion, ChannelSendCompletionMode.RESEND, work.message.timestamp),
            Triple(completion, ChannelSendCompletionMode.SEND, work.message.timestamp + 1u),
        )) {
            assertEquals(DirectAckRejection.CONFLICT, assertIs<ChannelQueueAcceptanceResult.Rejected>(
                store.noteAcceptedChannelAttempt(captured.attempt, id, mode, timestamp),
            ).reason)
        }
        assertIs<ChannelQueueAcceptanceResult.Rejected>(store.noteAcceptedChannelAttempt(
            other.attempt, UUID.randomUUID(), ChannelSendCompletionMode.SEND, work.message.timestamp,
        ))
        assertEquals(DirectAckRejection.INVALID_HANDLE, assertIs<ChannelQueueAcceptanceResult.Rejected>(
            store.noteAcceptedChannelAttempt(object : ChannelFailureAttempt {}, completion,
                ChannelSendCompletionMode.SEND, work.message.timestamp),
        ).reason)
        val inline = assertIs<ChannelFailureCaptureResult.Captured>(store.createChannelFailureAttempt(
            lease, UUID.randomUUID(), message(contactID = null, channelIndex = 2u, status = MessageStatus.PENDING),
        ))
        assertNull(assertIs<ChannelQueueAcceptanceResult.Registered>(
            store.noteAcceptedChannelAttempt(inline.attempt, UUID.randomUUID(), ChannelSendCompletionMode.SEND,
                requireNotNull(inline.receipt.message).timestamp),
        ).acceptance.pendingSendId)
    }

    @Test fun publicNullPendingPreparationCannotCaptureOrTimestampAnExistingQueuedMessage() = runTest {
        val lease = lease()
        val work = work()
        val mutationBefore = db.directAcks().incarnation(RADIO_A.value, work.message.id, "message")
        for (timestamp in listOf(null, work.message.timestamp + 9u)) {
            val request = UUID.randomUUID()
            assertEquals(DirectAckRejection.CONFLICT, assertIs<ChannelFailureCaptureResult.Rejected>(
                store.prepareChannelFailureAttempt(lease, request, entity(id = work.message.id), null, timestamp),
            ).reason)
            assertNull(db.channelSends().attempt(RADIO_A.value, request))
            assertEquals(work.message, store.fetchMessage(entity(id = work.message.id)))
            assertEquals(listOf(work.pending), store.fetchPendingSendsForMessage(entity(id = work.message.id)))
            assertEquals(mutationBefore, db.directAcks().incarnation(RADIO_A.value, work.message.id, "message"))
        }
        assertIs<ChannelQueueAdmissionResult.Ready>(store.admitQueuedChannelSend(
            lease, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp,
        ))
    }

    @Test fun exactAdmissionReplayCannotReturnCurrentGrantAfterValidOrRejectedAcceptanceReport() = runTest {
        for (invalidReport in listOf(false, true)) {
            val lease = lease()
            val work = work()
            val request = UUID.randomUUID()
            val ready = assertIs<ChannelQueueAdmissionResult.Ready>(store.admitQueuedChannelSend(
                lease, request, entity(id = work.message.id), work.message.timestamp,
            ))
            val timestamp = if (invalidReport) work.message.timestamp + 1u else work.message.timestamp
            val note = store.noteAcceptedChannelAttempt(
                ready.attempt, UUID.randomUUID(), ChannelSendCompletionMode.SEND, timestamp,
            )
            if (invalidReport) assertIs<ChannelQueueAcceptanceResult.Rejected>(note)
            else assertIs<ChannelQueueAcceptanceResult.Registered>(note)
            val before = store.fetchPendingSendsForMessage(entity(id = work.message.id))
            val replay = assertIs<ChannelQueueAdmissionResult.Uncertain>(store.admitQueuedChannelSend(
                lease, request, entity(id = work.message.id), work.message.timestamp,
            ))
            assertEquals(
                if (invalidReport) ChannelQueueUncertaintyReason.REJECTED_ACCEPTANCE_NOTE
                else ChannelQueueUncertaintyReason.RETAINED_ACCEPTANCE,
                replay.reason,
            )
            assertEquals(before, store.fetchPendingSendsForMessage(entity(id = work.message.id)))
            assertEquals(1L, before.single().attemptCount)
        }
    }

    @Test fun unboundAcceptanceBeforeALaterInsertSurvivesInvalidationAndReplacementWithoutInventingPendingIdentity() = runTest {
        val first = lease()
        val work = work()
        store.deletePendingSend(entity(id = work.pending.id))
        val captured = assertIs<ChannelFailureCaptureResult.Captured>(store.prepareChannelFailureAttempt(
            first, UUID.randomUUID(), entity(id = work.message.id), null, null,
        ))
        store.upsertPendingSend(work.pending)
        store.updateMessageStatus(entity(id = work.message.id), work.message.status)
        val completion = UUID.randomUUID()
        val registered = assertIs<ChannelQueueAcceptanceResult.Registered>(store.noteAcceptedChannelAttempt(
            captured.attempt, completion, ChannelSendCompletionMode.SEND, work.message.timestamp,
        ))
        assertNull(registered.acceptance.pendingSendId)
        assertIs<ChannelSendCompletionResult.Rejected>(store.recordChannelSendCompleted(
            captured.attempt, completion, ChannelSendCompletionMode.SEND, work.message.timestamp,
        ))
        store.revokeChannelFailureSession(first)
        val next = lease(2)
        val before = store.fetchMessage(entity(id = work.message.id))
        val mutation = db.directAcks().incarnation(RADIO_A.value, work.pending.id, "pending")
        val result = assertIs<ChannelQueueAdmissionResult.Uncertain>(store.admitQueuedChannelSend(
            next, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp + 8u,
        ))
        assertEquals(ChannelQueueUncertaintyReason.UNBOUND_ACCEPTANCE_LINEAGE, result.reason)
        assertEquals(registered.acceptance, result.acceptance)
        assertEquals(before, store.fetchMessage(entity(id = work.message.id)))
        assertEquals(listOf(work.pending), store.fetchPendingSendsForMessage(entity(id = work.message.id)))
        assertEquals(mutation, db.directAcks().incarnation(RADIO_A.value, work.pending.id, "pending"))
        store.deletePendingSend(entity(id = work.pending.id))
        store.upsertPendingSend(work.pending)
        assertIs<ChannelQueueAdmissionResult.Uncertain>(store.admitQueuedChannelSend(
            next, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp,
        ))
        val unrelated = work()
        assertIs<ChannelQueueAdmissionResult.Ready>(store.admitQueuedChannelSend(
            next, UUID.randomUUID(), entity(id = unrelated.message.id), unrelated.message.timestamp,
        ))
        store.deleteMessage(entity(id = work.message.id))
        store.saveMessage(work.message)
        store.upsertPendingSend(work.pending)
        assertIs<ChannelQueueAdmissionResult.Ready>(store.admitQueuedChannelSend(
            next, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp,
        ))
    }

    @Test fun committedUnboundHistoryIsUnknownForSameMessageIncarnationNotAnExactPendingCompletion() = runTest {
        val first = lease()
        val work = work()
        store.deletePendingSend(entity(id = work.pending.id))
        val captured = assertIs<ChannelFailureCaptureResult.Captured>(store.prepareChannelFailureAttempt(
            first, UUID.randomUUID(), entity(id = work.message.id), null, null,
        ))
        val completion = UUID.randomUUID()
        assertIs<ChannelSendCompletionResult.Applied>(store.recordChannelSendCompleted(
            captured.attempt, completion, ChannelSendCompletionMode.SEND, work.message.timestamp,
        ))
        store.upsertPendingSend(work.pending)
        store.revokeChannelFailureSession(first)
        val next = lease(2)
        val before = store.fetchMessage(entity(id = work.message.id))
        val result = assertIs<ChannelQueueAdmissionResult.Uncertain>(store.admitQueuedChannelSend(
            next, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp + 8u,
        ))
        assertEquals(ChannelQueueUncertaintyReason.UNBOUND_ACCEPTANCE_LINEAGE, result.reason)
        assertEquals(captured.receipt.captureRequestId, result.acceptance.captureRequestId)
        assertEquals(completion, result.acceptance.completionRequestId)
        assertNull(result.acceptance.pendingSendId)
        assertEquals(before, store.fetchMessage(entity(id = work.message.id)))
        assertEquals(listOf(work.pending), store.fetchPendingSendsForMessage(entity(id = work.message.id)))
        val historical = assertNotNull(db.channelSends().attempt(RADIO_A.value, captured.receipt.captureRequestId))
        assertNull(historical.pendingId); assertNull(historical.pendingLifecycleIncarnation)
        val unrelated = work()
        assertIs<ChannelQueueAdmissionResult.Ready>(store.admitQueuedChannelSend(
            next, UUID.randomUUID(), entity(id = unrelated.message.id), unrelated.message.timestamp,
        ))
        store.deleteMessage(entity(id = work.message.id))
        store.saveMessage(work.message)
        store.upsertPendingSend(work.pending)
        assertIs<ChannelQueueAdmissionResult.Ready>(store.admitQueuedChannelSend(
            next, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp,
        ))
    }

    @Test fun firstAuthenticRejectedNoteRetainsReportedTupleWithoutPretendingItWasValidatedOrDroppingTheFence() = runTest {
        for (wrongMode in listOf(false, true)) {
            val first = lease()
            val work = work()
            val capture = capture(first, work)
            val completion = UUID.randomUUID()
            val mode = if (wrongMode) ChannelSendCompletionMode.RESEND else ChannelSendCompletionMode.SEND
            val timestamp = if (wrongMode) work.message.timestamp else work.message.timestamp + 1u
            assertEquals(DirectAckRejection.CONFLICT, assertIs<ChannelQueueAcceptanceResult.Rejected>(
                store.noteAcceptedChannelAttempt(capture.attempt, completion, mode, timestamp),
            ).reason)
            assertIs<ChannelQueueAcceptanceResult.Rejected>(store.noteAcceptedChannelAttempt(
                capture.attempt, completion, ChannelSendCompletionMode.SEND, work.message.timestamp,
            ))
            store.revokeChannelFailureSession(first)
            val next = lease(2)
            val result = assertIs<ChannelQueueAdmissionResult.Uncertain>(store.admitQueuedChannelSend(
                next, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp + 9u,
            ))
            assertEquals(ChannelQueueUncertaintyReason.REJECTED_ACCEPTANCE_NOTE, result.reason)
            assertEquals(completion, result.acceptance.completionRequestId)
            assertEquals(mode, result.acceptance.mode); assertEquals(timestamp, result.acceptance.acceptedTimestamp)
            assertEquals(work.message, store.fetchMessage(entity(id = work.message.id)))
            assertEquals(listOf(work.pending), store.fetchPendingSendsForMessage(entity(id = work.message.id)))
            store.deletePendingSend(entity(id = work.pending.id))
            store.upsertPendingSend(work.pending)
            assertIs<ChannelQueueAdmissionResult.Ready>(store.admitQueuedChannelSend(
                next, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp,
            ))
        }
    }

    @Test fun durableExactCompletionTakesPrecedenceOverRetainedAcceptanceAndNewBirthDoesNotInheritEither() = runTest {
        val first = lease()
        val work = work()
        val capture = capture(first, work)
        val request = UUID.randomUUID()
        store.noteAcceptedChannelAttempt(capture.attempt, request, ChannelSendCompletionMode.SEND, work.message.timestamp)
        assertIs<ChannelSendCompletionResult.Applied>(store.recordChannelSendCompleted(
            capture.attempt, request, ChannelSendCompletionMode.SEND, work.message.timestamp,
        ))
        store.revokeChannelFailureSession(first)
        val next = lease(2)
        assertIs<ChannelQueueAdmissionResult.PreviouslyCompleted>(store.admitQueuedChannelSend(
            next, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp,
        ))
        store.deletePendingSend(entity(id = work.pending.id))
        store.upsertPendingSend(work.pending)
        assertIs<ChannelQueueAdmissionResult.Ready>(store.admitQueuedChannelSend(
            next, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp,
        ))
    }

    @Test fun retainedAcceptanceCannotQuarantineIndependentlyInsertedSiblingOrRecreatedMessage() = runTest {
        val first = lease()
        val work = work()
        val capture = capture(first, work)
        store.noteAcceptedChannelAttempt(capture.attempt, UUID.randomUUID(), ChannelSendCompletionMode.SEND, work.message.timestamp)
        store.revokeChannelFailureSession(first)
        val sibling = work.pending.copy(id = UUID.randomUUID(), sequence = 0)
        store.upsertPendingSend(sibling)
        val next = lease(2)
        val ready = assertIs<ChannelQueueAdmissionResult.Ready>(store.admitQueuedChannelSend(
            next, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp,
        ))
        assertEquals(sibling.id, ready.receipt.pendingSendId)
        store.deleteMessage(entity(id = work.message.id))
        store.saveMessage(work.message)
        store.upsertPendingSend(work.pending)
        assertIs<ChannelQueueAdmissionResult.Ready>(store.admitQueuedChannelSend(
            next, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp,
        ))
    }

    @Test fun unprovenHistoricalCompletionBirthIsExplicitUncertainAndNeverBackfilledFromLiveRows() = runTest {
        val first = lease()
        val work = work()
        val complete = committed(first, work)
        val dao = db.channelSends()
        val old = assertNotNull(dao.attempt(RADIO_A.value, complete.receipt.capture.captureRequestId))
        assertEquals(1, dao.update(old.copy(pendingLifecycleIncarnation = null)))
        store.revokeChannelFailureSession(first)
        val next = lease(2)
        val before = store.fetchPendingSendsForMessage(entity(id = work.message.id))
        val result = assertIs<ChannelQueueAdmissionResult.Uncertain>(store.admitQueuedChannelSend(
            next, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp + 5u,
        ))
        assertEquals(ChannelQueueUncertaintyReason.UNPROVEN_COMPLETION_LINEAGE, result.reason)
        assertEquals(complete.receipt.completionRequestId, result.acceptance.completionRequestId)
        assertNull(assertNotNull(dao.attempt(RADIO_A.value, old.captureRequestId)).pendingLifecycleIncarnation)
        assertEquals(before, store.fetchPendingSendsForMessage(entity(id = work.message.id)))
        val unrelated = work()
        assertIs<ChannelQueueAdmissionResult.Ready>(store.admitQueuedChannelSend(
            next, UUID.randomUUID(), entity(id = unrelated.message.id), unrelated.message.timestamp,
        ))
    }

    @Test fun provenOwnCleanupRetirementDoesNotQuarantineANewPendingEvenIfHistoricalBirthWasUncovered() = runTest {
        val first = lease()
        val work = work()
        val complete = committed(first, work)
        assertIs<ChannelSendPendingCompletionResult.Applied>(store.completeChannelSendPending(
            complete.completion, UUID.randomUUID(),
        ))
        val dao = db.channelSends()
        val old = assertNotNull(dao.attempt(RADIO_A.value, complete.receipt.capture.captureRequestId))
        assertEquals(1, dao.update(old.copy(pendingLifecycleIncarnation = null)))
        store.revokeChannelFailureSession(first)
        store.upsertPendingSend(work.pending)
        assertIs<ChannelQueueAdmissionResult.Ready>(store.admitQueuedChannelSend(
            lease(2), UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp,
        ))
    }

    @Test fun admissionCaptureFailureRollsBackBumpTimestampAndIdentitiesAndSameRequestCanRetry() = runTest {
        val lease = lease()
        val work = work(resend = true)
        val request = UUID.randomUUID()
        val messageIdentity = db.directAcks().incarnation(RADIO_A.value, work.message.id, "message")
        val pendingIdentity = db.directAcks().incarnation(RADIO_A.value, work.pending.id, "pending")
        val birthBefore = birth(work)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_queue_admission BEFORE INSERT ON channel_send_attempts " +
                "BEGIN SELECT RAISE(ABORT,'controlled admission failure'); END",
        )
        try {
            assertFailsWith<PersistenceStoreException> {
                store.admitQueuedChannelSend(lease, request, entity(id = work.message.id), work.message.timestamp + 1u)
            }
            assertEquals(work.message, store.fetchMessage(entity(id = work.message.id)))
            assertEquals(listOf(work.pending), store.fetchPendingSendsForMessage(entity(id = work.message.id)))
            assertEquals(messageIdentity, db.directAcks().incarnation(RADIO_A.value, work.message.id, "message"))
            assertEquals(pendingIdentity, db.directAcks().incarnation(RADIO_A.value, work.pending.id, "pending"))
            assertEquals(birthBefore, birth(work))
            assertNull(db.channelSends().attempt(RADIO_A.value, request))
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_queue_admission") }
        assertIs<ChannelQueueAdmissionResult.Ready>(store.admitQueuedChannelSend(
            lease, request, entity(id = work.message.id), work.message.timestamp + 1u,
        ))
    }

    @Test fun checkedAttemptOverflowIsNotANewLimitAndWritesNothing() = runTest {
        val lease = lease()
        val work = work(resend = true, attempt = Long.MAX_VALUE)
        assertIs<PersistenceStoreError.InvalidData>(assertFailsWith<PersistenceStoreException> {
            store.admitQueuedChannelSend(lease, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp + 1u)
        }.error)
        assertEquals(work.message, store.fetchMessage(entity(id = work.message.id)))
        assertEquals(listOf(work.pending), store.fetchPendingSendsForMessage(entity(id = work.message.id)))
    }

    @Test fun replayBindsRawClockKeyOperationAndInitialGrantVersionWithoutAnotherBump() = runTest {
        val lease = lease()
        val work = work()
        val request = UUID.randomUUID()
        val ready = assertIs<ChannelQueueAdmissionResult.Ready>(store.admitQueuedChannelSend(
            lease, request, entity(id = work.message.id), work.message.timestamp,
        ))
        assertEquals(DirectAckRejection.CONFLICT, assertIs<ChannelQueueAdmissionResult.Rejected>(
            store.admitQueuedChannelSend(lease, request, entity(id = work.message.id), work.message.timestamp + 1u),
        ).reason)
        assertIs<ChannelQueueAdmissionResult.Rejected>(store.admitQueuedChannelSend(
            lease, request, entity(id = UUID.randomUUID()), work.message.timestamp,
        ))
        assertIs<ChannelFailureCaptureResult.Rejected>(store.prepareChannelFailureAttempt(
            lease, request, entity(id = work.message.id), work.pending.id, work.message.timestamp,
        ))
        store.remapChannelFailureAttempt(ready.attempt, UUID.randomUUID())
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<ChannelQueueAdmissionResult.Rejected>(
            store.admitQueuedChannelSend(lease, request, entity(id = work.message.id), work.message.timestamp),
        ).reason)
        assertEquals(1L, store.fetchPendingSendsForMessage(entity(id = work.message.id)).single().attemptCount)
        val second = work()
        val oldRequest = UUID.randomUUID()
        assertIs<ChannelFailureCaptureResult.Captured>(store.prepareChannelFailureAttempt(
            lease, oldRequest, entity(id = second.message.id), second.pending.id, second.message.timestamp,
        ))
        assertEquals(DirectAckRejection.CONFLICT, assertIs<ChannelQueueAdmissionResult.Rejected>(
            store.admitQueuedChannelSend(lease, oldRequest, entity(id = second.message.id), second.message.timestamp),
        ).reason)
    }

    @Test fun missingWrongKindRadioAndRetiredLeaseNeverBumpOrReturnAuthority() = runTest {
        val lease = lease()
        val work = work()
        assertEquals(DirectAckRejection.CONFLICT, assertIs<ChannelQueueAdmissionResult.Rejected>(
            store.admitQueuedChannelSend(lease, UUID.randomUUID(), entity(RADIO_B, work.message.id), work.message.timestamp),
        ).reason)
        assertEquals(DirectAckRejection.MISSING, assertIs<ChannelQueueAdmissionResult.Rejected>(
            store.admitQueuedChannelSend(lease, UUID.randomUUID(), entity(id = UUID.randomUUID()), work.message.timestamp),
        ).reason)
        store.upsertPendingSend(work.pending.copy(kind = PendingSendKind.DM, channelIndex = null, contactID = CONTACT_A))
        assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<ChannelQueueAdmissionResult.Rejected>(
            store.admitQueuedChannelSend(lease, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp),
        ).reason)
        assertEquals(0L, store.fetchPendingSendsForMessage(entity(id = work.message.id)).single().attemptCount)
        store.revokeChannelFailureSession(lease)
        assertEquals(DirectAckRejection.STALE_LEASE, assertIs<ChannelQueueAdmissionResult.Rejected>(
            store.admitQueuedChannelSend(lease, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp),
        ).reason)
        assertEquals(work.message, store.fetchMessage(entity(id = work.message.id)))
    }

    @Test fun missingBirthCoverageCannotBeRepairedByAnOrdinaryAdmissionOrPrepareCall() = runTest {
        val lease = lease()
        val work = work()
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM channel_pending_lifecycles WHERE radioId = ? AND pendingId = ?",
            arrayOf(RADIO_A.value.canonicalString(), work.pending.id.canonicalString()),
        )
        assertIs<PersistenceStoreError.InvalidData>(assertFailsWith<PersistenceStoreException> {
            store.admitQueuedChannelSend(lease, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp)
        }.error)
        assertNull(db.channelSends().pendingLifecycle(RADIO_A.value, work.pending.id))
        assertEquals(work.pending.toEntity(), db.pendingSends().forMessage(RADIO_A.value, work.message.id).single())
    }

    @Test fun changedLifecycleTriggerDefinitionCannotMakeAnAdmissionLookValid() = runTest {
        val lease = lease()
        val work = work()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER channel_pending_lifecycle_update")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER channel_pending_lifecycle_update AFTER UPDATE ON pending_sends BEGIN SELECT 1; END",
        )
        assertIs<PersistenceStoreError.InvalidData>(assertFailsWith<PersistenceStoreException> {
            store.admitQueuedChannelSend(lease, UUID.randomUUID(), entity(id = work.message.id), work.message.timestamp)
        }.error)
        assertEquals(work.pending.toEntity(), db.pendingSends().forMessage(RADIO_A.value, work.message.id).single())
    }

    @Test fun processStoreDisposalClosesTheSynchronousRegistryInsteadOfGrantingAfterClose() = runTest {
        val lease = lease()
        val work = work()
        val captured = capture(lease, work)
        store.close()
        assertEquals(DirectAckRejection.INVALID_HANDLE, assertIs<ChannelQueueAcceptanceResult.Rejected>(
            store.noteAcceptedChannelAttempt(captured.attempt, UUID.randomUUID(),
                ChannelSendCompletionMode.SEND, work.message.timestamp),
        ).reason)
    }
}
