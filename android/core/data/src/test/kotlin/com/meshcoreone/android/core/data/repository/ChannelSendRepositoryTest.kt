// AndroidOnly: WP-202 Real Room incarnation-fenced channel failure and exact pending cleanup controls.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.ChannelSendStorage
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChannelSendRepositoryTest : RepositoryTest() {
    private fun channel(status: MessageStatus = MessageStatus.PENDING): MessageDTO =
        message(contactID = null, channelIndex = 2u, status = status)

    private fun channelPending(row: MessageDTO): PendingSendDTO =
        pending(messageID = row.id, sequence = 1).copy(
            kind = PendingSendKind.CHANNEL, contactID = null, channelIndex = row.channelIndex,
            messageText = row.text, messageTimestamp = row.timestamp,
        )

    private suspend fun lease(target: RoomPersistenceStore = store): DirectAckLease =
        assertIs<DirectAckActivationResult.Activated>(target.activateChannelFailureSession(
            SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), RADIO_A), UUID.randomUUID(),
        )).lease

    private suspend fun queued(lease: DirectAckLease): Pair<ChannelFailureCaptureResult.Captured, PendingSendDTO> {
        val row = channel()
        val pending = channelPending(row)
        store.saveMessage(row)
        store.upsertPendingSend(pending)
        return assertIs<ChannelFailureCaptureResult.Captured>(store.prepareChannelFailureAttempt(
            lease, UUID.randomUUID(), entity(id = row.id), pending.id, null,
        )) to pending
    }

    @Test fun atomicCreationCannotOverwriteExistingKeyAndReplayBindsEveryInputField() = runTest {
        val lease = lease()
        val request = UUID.randomUUID()
        val row = channel()
        val created = assertIs<ChannelFailureCaptureResult.Captured>(
            store.createChannelFailureAttempt(lease, request, row),
        )
        assertEquals(row, created.receipt.message)
        assertEquals(DirectAckPermission.CURRENT, created.receipt.permission)
        assertIs<ChannelFailureCaptureResult.AlreadyCaptured>(store.createChannelFailureAttempt(lease, request, row))
        for (different in listOf(
            row.copy(text = row.text + "!"),
            row.copy(timestamp = 0u),
            row.copy(linkPreviewImageData = Bytes.of(1)),
            row.copy(linkPreviewImageData = Bytes.of(2)),
            row.copy(regionScopeMatches = listOf("x", "y").snapshot()),
        )) {
            assertEquals(DirectAckRejection.CONFLICT, assertIs<ChannelFailureCaptureResult.Rejected>(
                store.createChannelFailureAttempt(lease, request, different),
            ).reason)
        }
        assertEquals(DirectAckRejection.CONFLICT, assertIs<ChannelFailureCaptureResult.Rejected>(
            store.createChannelFailureAttempt(lease, UUID.randomUUID(), row),
        ).reason)
        assertEquals(row, store.fetchMessage(entity(id = row.id)))
    }

    @Test fun creationRejectsDirectIncomingTerminalAndWrongRadioRowsWithoutCreatingThem() = runTest {
        val lease = lease()
        for (row in listOf(
            channel().copy(contactID = CONTACT_A),
            channel().copy(channelIndex = null),
            channel().copy(direction = MessageDirection.INCOMING),
            channel(MessageStatus.SENT),
            channel().copy(radioId = RADIO_B),
        )) {
            assertIs<ChannelFailureCaptureResult.Rejected>(
                store.createChannelFailureAttempt(lease, UUID.randomUUID(), row),
            )
            assertNull(store.fetchMessage(EntityKey(row.radioId, row.id)))
        }
    }

    @Test fun pausedFailureCannotFailOrDeleteSameKeyReplacementDirectMessageAndPending() = runTest {
        val lease = lease()
        val (capture, pending) = queued(lease)
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val failure = async(start = CoroutineStart.UNDISPATCHED) {
            ready.complete(Unit)
            release.await()
            store.recordChannelFailure(capture.attempt, UUID.randomUUID(), ChannelFailureCleanup.DELETE_CAPTURED_PENDING)
        }
        ready.await()
        val old = assertNotNull(capture.receipt.message)
        store.deleteMessage(entity(id = old.id))
        val replacement = old.copy(contactID = CONTACT_A, channelIndex = null, text = "replacement DM")
        val directPending = pending.copy(kind = PendingSendKind.DM, contactID = CONTACT_A, channelIndex = null,
            messageText = replacement.text)
        store.saveMessage(replacement)
        store.upsertPendingSend(directPending)
        release.complete(Unit)
        assertIs<ChannelFailureResult.Rejected>(failure.await())
        assertEquals(replacement, store.fetchMessage(entity(id = old.id)))
        assertEquals(listOf(directPending), store.fetchPendingSendsForMessage(entity(id = old.id)))
        val observed = assertIs<ChannelFailureObservation.Found>(
            store.inspectChannelFailureAttempt(lease, capture.receipt.captureRequestId),
        ).receipt
        assertNotEquals(DirectAckPermission.CURRENT, observed.permission)
        assertNull(observed.message)
        assertNull(observed.pending)
    }

    @Test fun equalLookingChannelReplacementIsNotTheOriginalChannelIncarnation() = runTest {
        val lease = lease()
        val row = channel()
        val capture = assertIs<ChannelFailureCaptureResult.Captured>(
            store.createChannelFailureAttempt(lease, UUID.randomUUID(), row),
        )
        db.messages().delete(row.radioId.value, row.id)
        db.messages().insert(row.toEntity())
        assertEquals(row, store.fetchMessage(entity(id = row.id)))
        assertIs<ChannelFailureResult.Rejected>(
            store.recordChannelFailure(capture.attempt, UUID.randomUUID(), ChannelFailureCleanup.KEEP_PENDING),
        )
        assertEquals(MessageStatus.PENDING, store.fetchMessage(entity(id = row.id))?.status)
    }

    @Test fun genericSameValueMessageAndPendingWritesFenceTheCapture() = runTest {
        val lease = lease()
        val (capture, pending) = queued(lease)
        val row = assertNotNull(capture.receipt.message)
        db.messages().setStatus(row.radioId.value, row.id, row.status.rawValue)
        assertIs<ChannelFailureResult.Rejected>(
            store.recordChannelFailure(capture.attempt, UUID.randomUUID(), ChannelFailureCleanup.KEEP_PENDING),
        )
        val next = assertIs<ChannelFailureCaptureResult.Captured>(store.prepareChannelFailureAttempt(
            lease, UUID.randomUUID(), entity(id = row.id), pending.id, null,
        ))
        db.pendingSends().setAttemptCount(pending.radioId.value, pending.id, assertNotNull(pending.attemptCount))
        assertIs<ChannelFailureResult.Rejected>(
            store.recordChannelFailure(next.attempt, UUID.randomUUID(), ChannelFailureCleanup.DELETE_CAPTURED_PENDING),
        )
        assertEquals(row, store.fetchMessage(entity(id = row.id)))
        assertEquals(listOf(pending), store.fetchPendingSendsForMessage(entity(id = row.id)))
    }

    @Test fun channelFailureAndExactCleanupCommitTogetherRetainingOtherPendingRowsAndRadio() = runTest {
        val lease = lease()
        val row = channel().copy(failureSeen = true)
        val pending = channelPending(row)
        val sibling = pending.copy(id = UUID.randomUUID(), sequence = 2)
        val foreign = pending.copy(radioId = RADIO_B)
        store.saveMessage(row)
        for (dto in listOf(pending, sibling, foreign)) store.upsertPendingSend(dto)
        val captured = assertIs<ChannelFailureCaptureResult.Captured>(store.prepareChannelFailureAttempt(
            lease, UUID.randomUUID(), entity(id = row.id), pending.id, null,
        ))
        val request = UUID.randomUUID()
        val result = assertIs<ChannelFailureResult.Applied>(store.recordChannelFailure(
            captured.attempt, request, ChannelFailureCleanup.DELETE_CAPTURED_PENDING,
        ))
        assertEquals(MessageStatus.FAILED, store.fetchMessage(entity(id = row.id))?.status)
        assertFalse(assertNotNull(store.fetchMessage(entity(id = row.id))).failureSeen)
        assertEquals(listOf(sibling), store.fetchPendingSendsForMessage(entity(id = row.id)))
        assertEquals(listOf(foreign), store.fetchPendingSends(RADIO_B))
        assertEquals(DirectAckPermission.INVALIDATED, result.receipt.permission)
        assertNull(result.receipt.message)
        assertNull(result.receipt.pending)
        assertEquals(ChannelFailureOperation.FAILED_AND_PENDING_DELETED,
            result.receipt.operations.single().operation)
        assertIs<ChannelFailureResult.AlreadyApplied>(store.recordChannelFailure(
            captured.attempt, request, ChannelFailureCleanup.DELETE_CAPTURED_PENDING,
        ))
        assertEquals(DirectAckRejection.CONFLICT, assertIs<ChannelFailureResult.Rejected>(store.recordChannelFailure(
            captured.attempt, request, ChannelFailureCleanup.KEEP_PENDING,
        )).reason)
    }

    @Test fun ownFailureThenRemapReturnsSuccessorForSameRowsAndEventuallyDeletesExactlyCapturedPending() = runTest {
        val lease = lease()
        val (capture, pending) = queued(lease)
        val row = assertNotNull(capture.receipt.message)
        val failure = assertIs<ChannelFailureResult.Applied>(store.recordChannelFailure(
            capture.attempt, UUID.randomUUID(), ChannelFailureCleanup.KEEP_PENDING,
        ))
        assertEquals(DirectAckPermission.CURRENT, failure.receipt.permission)
        assertEquals(MessageStatus.FAILED, failure.receipt.message?.status)
        assertEquals(pending, failure.receipt.pending)
        val request = UUID.randomUUID()
        val remapped = assertIs<ChannelFailureRemapResult.Remapped>(
            store.remapChannelFailureAttempt(capture.attempt, request),
        )
        assertEquals(row.copy(status = MessageStatus.PENDING), remapped.receipt.message)
        assertEquals(pending, remapped.receipt.pending)
        assertIs<ChannelFailureRemapResult.AlreadyRemapped>(
            store.remapChannelFailureAttempt(capture.attempt, request),
        )
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<ChannelFailureResult.Rejected>(store.recordChannelFailure(
            capture.attempt, UUID.randomUUID(), ChannelFailureCleanup.DELETE_CAPTURED_PENDING,
        )).reason)
        assertIs<ChannelFailureResult.Applied>(store.recordChannelFailure(
            remapped.attempt, UUID.randomUUID(), ChannelFailureCleanup.DELETE_CAPTURED_PENDING,
        ))
        assertEquals(MessageStatus.FAILED, store.fetchMessage(entity(id = row.id))?.status)
        assertTrue(store.fetchPendingSendsForMessage(entity(id = row.id)).isEmpty())
    }

    @Test fun ownFailureRetainsGrantForLaterTerminalCleanupWithoutAnyKeyRecapture() = runTest {
        val lease = lease()
        val (capture, pending) = queued(lease)
        assertIs<ChannelFailureResult.Applied>(store.recordChannelFailure(
            capture.attempt, UUID.randomUUID(), ChannelFailureCleanup.KEEP_PENDING,
        ))
        assertIs<ChannelFailureResult.Applied>(store.recordChannelFailure(
            capture.attempt, UUID.randomUUID(), ChannelFailureCleanup.DELETE_CAPTURED_PENDING,
        ))
        assertEquals(MessageStatus.FAILED, store.fetchMessage(entity(id = pending.messageID))?.status)
        assertTrue(store.fetchPendingSendsForMessage(entity(id = pending.messageID)).isEmpty())
    }

    @Test fun missingOrWrongKindPendingIsRejectedBeforeResendTimestampMutation() = runTest {
        val lease = lease()
        val row = channel()
        store.saveMessage(row)
        assertEquals(DirectAckRejection.MISSING, assertIs<ChannelFailureCaptureResult.Rejected>(
            store.prepareChannelFailureAttempt(lease, UUID.randomUUID(), entity(id = row.id), UUID.randomUUID(), 123u),
        ).reason)
        val directPending = pending(messageID = row.id)
        store.upsertPendingSend(directPending)
        assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<ChannelFailureCaptureResult.Rejected>(
            store.prepareChannelFailureAttempt(lease, UUID.randomUUID(), entity(id = row.id), directPending.id, 123u),
        ).reason)
        assertEquals(row, store.fetchMessage(entity(id = row.id)))
        assertEquals(listOf(directPending), store.fetchPendingSendsForMessage(entity(id = row.id)))
    }

    @Test fun malformedPersistedCaptureIsTypedInvalidDataNotCurrentPermission() = runTest {
        val lease = lease()
        val (capture, pending) = queued(lease)
        val dao = db.channelSends()
        val row = assertNotNull(dao.attempt(RADIO_A.value, capture.receipt.captureRequestId))
        assertEquals(1, dao.update(row.copy(messageRevision = -1)))
        val failure = assertFailsWith<PersistenceStoreException> {
            store.recordChannelFailure(capture.attempt, UUID.randomUUID(), ChannelFailureCleanup.DELETE_CAPTURED_PENDING)
        }
        assertIs<PersistenceStoreError.InvalidData>(failure.error)
        assertEquals(MessageStatus.PENDING, store.fetchMessage(entity(id = pending.messageID))?.status)
        assertEquals(listOf(pending), store.fetchPendingSendsForMessage(entity(id = pending.messageID)))
    }

    @Test fun postCommitReplacementDoesNotTurnHistoricalReplayIntoDeletionPermission() = runTest {
        val lease = lease()
        val (capture, pending) = queued(lease)
        val request = UUID.randomUUID()
        assertIs<ChannelFailureResult.Applied>(store.recordChannelFailure(
            capture.attempt, request, ChannelFailureCleanup.KEEP_PENDING,
        ))
        val old = assertNotNull(capture.receipt.message)
        val direct = old.copy(channelIndex = null, contactID = CONTACT_A, status = MessageStatus.SENT)
        store.saveMessage(direct)
        val replay = assertIs<ChannelFailureResult.AlreadyApplied>(store.recordChannelFailure(
            capture.attempt, request, ChannelFailureCleanup.KEEP_PENDING,
        ))
        assertEquals(DirectAckPermission.INVALIDATED, replay.receipt.permission)
        assertNull(replay.receipt.message)
        assertNull(replay.receipt.pending)
        assertIs<ChannelFailureResult.Rejected>(store.recordChannelFailure(
            capture.attempt, UUID.randomUUID(), ChannelFailureCleanup.DELETE_CAPTURED_PENDING,
        ))
        assertEquals(direct, store.fetchMessage(entity(id = old.id)))
        assertEquals(listOf(pending), store.fetchPendingSendsForMessage(entity(id = old.id)))
    }

    @Test fun exactPendingDeletionFailureRollsBackFailureRevisionAndJournalThenAllowsSameRequest() = runTest {
        val lease = lease()
        val (capture, pending) = queued(lease)
        val row = assertNotNull(capture.receipt.message)
        val request = UUID.randomUUID()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_channel_cleanup BEFORE DELETE ON pending_sends " +
                "BEGIN SELECT RAISE(ABORT,'controlled channel cleanup failure'); END",
        )
        try {
            assertFailsWith<PersistenceStoreException> {
                store.recordChannelFailure(capture.attempt, request, ChannelFailureCleanup.DELETE_CAPTURED_PENDING)
            }
            assertEquals(row, store.fetchMessage(entity(id = row.id)))
            assertEquals(listOf(pending), store.fetchPendingSendsForMessage(entity(id = row.id)))
            val receipt = assertIs<ChannelFailureObservation.Found>(
                store.inspectChannelFailureAttempt(lease, capture.receipt.captureRequestId),
            ).receipt
            assertEquals(DirectAckPermission.CURRENT, receipt.permission)
            assertTrue(receipt.operations.isEmpty())
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_channel_cleanup")
        }
        assertIs<ChannelFailureResult.Applied>(store.recordChannelFailure(
            capture.attempt, request, ChannelFailureCleanup.DELETE_CAPTURED_PENDING,
        ))
    }

    @Test fun prepareUsesActualRowsAndTimestampOnlyOnceWithoutBumpingAttemptOrRecapturingOnReplay() = runTest {
        val lease = lease()
        val row = channel()
        val pending = channelPending(row).copy(attemptCount = 2)
        store.saveMessage(row)
        store.upsertPendingSend(pending)
        val request = UUID.randomUUID()
        val prepared = assertIs<ChannelFailureCaptureResult.Captured>(store.prepareChannelFailureAttempt(
            lease, request, entity(id = row.id), pending.id, 123u,
        ))
        assertEquals(row.copy(timestamp = 123u), prepared.receipt.message)
        assertEquals(pending, prepared.receipt.pending)
        assertIs<ChannelFailureCaptureResult.AlreadyCaptured>(store.prepareChannelFailureAttempt(
            lease, request, entity(id = row.id), pending.id, 123u,
        ))
        assertIs<ChannelFailureCaptureResult.Rejected>(store.prepareChannelFailureAttempt(
            lease, request, entity(id = row.id), pending.id, 124u,
        ))
        store.saveMessage(row.copy(timestamp = 123u))
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<ChannelFailureCaptureResult.Rejected>(
            store.prepareChannelFailureAttempt(lease, request, entity(id = row.id), pending.id, 123u),
        ).reason)
        assertEquals(2L, store.fetchPendingSendsForMessage(entity(id = row.id)).single().attemptCount)
    }

    @Test fun invalidLeaseOpaqueHandleDeliveredAndMissingPendingNeverWrite() = runTest {
        val lease = lease()
        val (capture, pending) = queued(lease)
        val row = assertNotNull(capture.receipt.message)
        assertEquals(DirectAckRejection.INVALID_HANDLE, assertIs<ChannelFailureResult.Rejected>(
            store.recordChannelFailure(object : ChannelFailureAttempt {}, UUID.randomUUID(),
                ChannelFailureCleanup.DELETE_CAPTURED_PENDING),
        ).reason)
        store.updateMessageStatus(entity(id = row.id), MessageStatus.DELIVERED)
        assertIs<ChannelFailureResult.Rejected>(
            store.recordChannelFailure(capture.attempt, UUID.randomUUID(), ChannelFailureCleanup.DELETE_CAPTURED_PENDING),
        )
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(entity(id = row.id))?.status)
        assertEquals(listOf(pending), store.fetchPendingSendsForMessage(entity(id = row.id)))
        assertIs<ChannelFailureCaptureResult.Rejected>(
            store.prepareChannelFailureAttempt(lease, UUID.randomUUID(), entity(id = row.id), pending.id, 123u),
        )
        assertIs<DirectAckRevocationResult.Revoked>(store.revokeChannelFailureSession(lease))
        assertEquals(DirectAckRejection.STALE_LEASE, assertIs<ChannelFailureCaptureResult.Rejected>(
            store.createChannelFailureAttempt(lease, UUID.randomUUID(), channel()),
        ).reason)
    }

    @Test fun restoreInvalidatesGrantsWithoutErasingOperationTombstonesOrCertifyingRows() = runTest {
        val lease = lease()
        val (capture, pending) = queued(lease)
        assertIs<ChannelFailureResult.Applied>(store.recordChannelFailure(
            capture.attempt, UUID.randomUUID(), ChannelFailureCleanup.KEEP_PENDING,
        ))
        store.withBackupRestore { database, _ ->
            ChannelSendStorage.invalidateRestore(database.openHelper.writableDatabase)
        }
        val observed = assertIs<ChannelFailureObservation.Found>(
            store.inspectChannelFailureAttempt(lease, capture.receipt.captureRequestId),
        ).receipt
        assertEquals(DirectAckPermission.INVALIDATED, observed.permission)
        assertNull(observed.message)
        assertNull(observed.pending)
        assertEquals(1, observed.operations.size)
        assertIs<ChannelFailureResult.Rejected>(store.recordChannelFailure(
            capture.attempt, UUID.randomUUID(), ChannelFailureCleanup.DELETE_CAPTURED_PENDING,
        ))
        assertEquals(listOf(pending), store.fetchPendingSendsForMessage(entity(id = pending.messageID)))
    }

    @Test fun fileAndProcessReopenRetainHistoricalCommitButOldLeaseCannotGrantReplacementAccess() = runTest {
        val name = "channel-failure-${UUID.randomUUID()}.db"
        var database = MeshCoreDatabase.open(context, name)
        var target = RoomPersistenceStore(database, owner, clock)
        try {
            val lease = lease(target)
            val row = channel()
            val captured = assertIs<ChannelFailureCaptureResult.Captured>(
                target.createChannelFailureAttempt(lease, UUID.randomUUID(), row),
            )
            val request = UUID.randomUUID()
            assertIs<ChannelFailureResult.Applied>(
                target.recordChannelFailure(captured.attempt, request, ChannelFailureCleanup.KEEP_PENDING),
            )
            target.close()
            database.close()
            database = MeshCoreDatabase.open(context, name)
            target = RoomPersistenceStore(database, owner, clock)
            assertEquals(MessageStatus.FAILED, target.fetchMessage(entity(id = row.id))?.status)
            assertEquals(ChannelFailureOperation.FAILED.name,
                database.channelSends().operation(RADIO_A.value, captured.receipt.captureRequestId, request)?.kind)
            assertEquals(DirectAckRejection.INVALID_HANDLE, assertIs<ChannelFailureResult.Rejected>(
                target.recordChannelFailure(captured.attempt, UUID.randomUUID(), ChannelFailureCleanup.KEEP_PENDING),
            ).reason)
            assertEquals(MessageStatus.FAILED, target.fetchMessage(entity(id = row.id))?.status)
        } finally {
            target.close()
            database.close()
            context.deleteDatabase(name)
        }
    }
}
