// AndroidOnly: WP-201 Channel failure immutable result and history value controls.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ChannelFailureAuthorityTest {
    private val key = EntityKey(RadioId(UUID(0, 1)), UUID(0, 2))
    private val captureId = UUID(0, 3)
    private val requestId = UUID(0, 4)
    private val message = MessageDTO(
        id = key.id,
        radioId = key.radioId,
        channelIndex = 0u,
        text = "channel",
        timestamp = 42u,
        createdAt = Instant.EPOCH,
    )
    private val receipt = ChannelFailureReceipt(
        captureId, key, message, null, null, DirectAckPermission.CURRENT, SnapshotList.empty(),
    )

    @Test
    fun captureRetainsActualSnapshotAndOpaqueHandleWithoutFabricatingPendingIdentity() {
        val handle = object : ChannelFailureAttempt {}
        val captured = ChannelFailureCaptureResult.Captured(handle, receipt)
        val replay = ChannelFailureCaptureResult.AlreadyCaptured(handle, receipt)

        assertSame(handle, captured.attempt)
        assertSame(handle, replay.attempt)
        assertEquals(message, captured.receipt.message)
        assertEquals(captureId, replay.receipt.captureRequestId)
        assertNull(captured.receipt.pendingSendId)
        assertNull(captured.receipt.pending)
    }

    @Test
    fun remapReturnsDistinctSuccessorWhileKeepingOriginalCaptureIdentity() {
        val prior = object : ChannelFailureAttempt {}
        val successor = object : ChannelFailureAttempt {}
        val operation = ChannelFailureOperationReceipt(requestId, ChannelFailureOperation.REMAPPED_TO_PENDING)
        val remapped = ChannelFailureRemapResult.Remapped(
            successor, receipt.copy(operations = SnapshotList(listOf(operation))),
        )
        val replay = ChannelFailureRemapResult.AlreadyRemapped(successor, remapped.receipt)

        assertNotEquals(prior, remapped.attempt)
        assertSame(successor, replay.attempt)
        assertEquals(captureId, replay.receipt.captureRequestId)
        assertEquals(listOf(operation), replay.receipt.operations)
    }

    @Test
    fun ownFailureAndTerminalCleanupAreDistinctOperations() {
        val failed = ChannelFailureOperationReceipt(requestId, ChannelFailureOperation.FAILED)
        val terminal = ChannelFailureOperationReceipt(
            UUID(0, 5), ChannelFailureOperation.FAILED_AND_PENDING_DELETED,
        )
        val operations = mutableListOf(failed, terminal)
        val history = receipt.copy(operations = SnapshotList(operations))
        operations.clear()

        assertEquals(listOf(failed, terminal), history.operations)
        assertNotEquals(ChannelFailureCleanup.KEEP_PENDING, ChannelFailureCleanup.DELETE_CAPTURED_PENDING)
        assertNotEquals(failed.operation, terminal.operation)
    }

    @Test
    fun historicalCleanupReplayRetainsCommitAndExactPendingIdWithoutReplacementPayload() {
        val pendingId = UUID(0, 6)
        val operation = ChannelFailureOperationReceipt(
            requestId, ChannelFailureOperation.FAILED_AND_PENDING_DELETED,
        )
        val historical = receipt.copy(
            message = null,
            pendingSendId = pendingId,
            permission = DirectAckPermission.INVALIDATED,
            operations = SnapshotList(listOf(operation)),
        )
        val replay = ChannelFailureResult.AlreadyApplied(historical)

        assertEquals(pendingId, replay.receipt.pendingSendId)
        assertEquals(operation, replay.receipt.operations.single())
        assertEquals(DirectAckPermission.INVALIDATED, replay.receipt.permission)
        assertNull(replay.receipt.message)
        assertNull(replay.receipt.pending)
        assertEquals(historical, ChannelFailureObservation.Found(historical).receipt)
    }

    @Test
    fun deliveredOutcomeDoesNotMasqueradeAsAppliedFailureOrCleanup() {
        val delivered = receipt.copy(message = message.copy(status = MessageStatus.DELIVERED))
        val result = ChannelFailureResult.AlreadyDelivered(delivered)
        val remap = ChannelFailureRemapResult.AlreadyDelivered(delivered)

        assertEquals(MessageStatus.DELIVERED, result.receipt.message?.status)
        assertEquals(delivered, remap.receipt)
        assertEquals(emptyList(), delivered.operations)
        assertNotEquals<ChannelFailureResult>(ChannelFailureResult.Applied(delivered), result)
    }

    @Test
    fun everyRejectionRemainsExplicitAcrossCaptureRemapMutationAndInspection() {
        for (reason in DirectAckRejection.entries) {
            assertEquals(reason, ChannelFailureCaptureResult.Rejected(reason).reason)
            assertEquals(reason, ChannelFailureRemapResult.Rejected(reason).reason)
            assertEquals(reason, ChannelFailureResult.Rejected(reason).reason)
            assertEquals(reason, ChannelFailureObservation.Rejected(reason).reason)
        }
    }

    @Test
    fun retiredLeaseHistoryKeepsIdsButNoPayloadOrReissuedHandle() {
        val retired = receipt.copy(message = null, permission = DirectAckPermission.STALE_LEASE)
        val observed = ChannelFailureObservation.Found(retired)

        assertEquals(key, observed.receipt.key)
        assertEquals(captureId, observed.receipt.captureRequestId)
        assertEquals(DirectAckPermission.STALE_LEASE, observed.receipt.permission)
        assertNull(observed.receipt.message)
        assertNull(observed.receipt.pending)
    }
}
