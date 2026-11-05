// AndroidOnly: WP-201 No-dispatch pending completion value contracts, not database enforcement.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.PendingSendDTO
import com.meshcoreone.android.core.model.PendingSendKind
import com.meshcoreone.android.core.model.RadioId
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class DirectPendingCompletionAuthorityTest {
    private val key = EntityKey(RadioId(UUID(0, 1)), UUID(0, 2))
    private val message = MessageDTO(
        id = key.id, radioId = key.radioId, text = "original", timestamp = 0u,
        createdAt = Instant.EPOCH, status = MessageStatus.DELIVERED,
    )
    private val pending = PendingSendDTO(
        id = UUID(0, 3), radioId = key.radioId, messageID = key.id,
        kind = PendingSendKind.DM, contactID = UUID(0, 4), channelIndex = null,
        isResend = false, messageText = "", messageTimestamp = 0u, localNodeName = null,
        sequence = 5, enqueuedAt = Instant.EPOCH,
    )
    private val receipt = DirectPendingCompletionReceipt(
        captureRequestId = UUID(0, 6), key = key,
        reason = DirectPendingCompletionReason.AlreadyDeliveredSend,
        message = message, pendingSendId = pending.id, pending = pending, completedRequestId = null,
        permission = DirectPendingCompletionPermission.CURRENT,
    )

    @Test
    fun captureSnapshotDoesNotFabricateDispatchOrCompletion() {
        val capturedMessage = assertNotNull(receipt.message)
        assertSame(message, receipt.message)
        assertSame(pending, receipt.pending)
        assertNull(receipt.completedRequestId)
        assertEquals(MessageStatus.DELIVERED, capturedMessage.status)
        assertNull(capturedMessage.ackCode)
        assertEquals(1L, capturedMessage.sendCount)
        assertSame(DirectPendingCompletionReason.AlreadyDeliveredSend, receipt.reason)
    }

    @Test
    fun zeroBudgetMetadataPreservesExactInputForProducerValidation() {
        assertEquals(0L, DirectPendingCompletionReason.ZeroAttempts(0).configuredMaximumAttempts)
        assertNotEquals(
            DirectPendingCompletionReason.ZeroAttempts(0),
            DirectPendingCompletionReason.ZeroAttempts(1),
        )
        assertEquals(-1L, DirectPendingCompletionReason.ZeroAttempts(-1).configuredMaximumAttempts)
    }

    @Test
    fun historicalCompletionDoesNotDescribeOrDeleteReplacement() {
        val history = receipt.copy(
            completedRequestId = UUID(0, 7),
            permission = DirectPendingCompletionPermission.INVALIDATED,
        )
        val result = DirectPendingCompletionResult.AlreadyApplied(history)
        val replacement = pending.copy(attemptCount = 99, sequence = 100)

        assertEquals(UUID(0, 7), result.receipt.completedRequestId)
        assertEquals(DirectPendingCompletionPermission.INVALIDATED, result.receipt.permission)
        assertSame(pending, result.receipt.pending)
        assertNotEquals(replacement, result.receipt.pending)
        assertSame(message, result.receipt.message)
    }

    @Test
    fun typedRejectionsAndInspectionRetainExactPayloads() {
        for (reason in DirectAckRejection.entries) {
            assertEquals(reason, DirectPendingCaptureResult.Rejected(reason).reason)
            assertEquals(reason, DirectPendingCompletionResult.Rejected(reason).reason)
            assertEquals(reason, DirectPendingCompletionObservation.Rejected(reason).reason)
        }
        val observed = DirectPendingCompletionObservation.Found(receipt)
        assertSame(receipt, observed.receipt)
        assertNotEquals(receipt.captureRequestId, pending.id)
    }

    @Test
    fun erasedCompletionRetainsDeletionIdentityWithoutPersistedPendingPayload() {
        val completed = receipt.copy(
            pending = null,
            completedRequestId = UUID(0, 7),
            permission = DirectPendingCompletionPermission.INVALIDATED,
        )
        val erased = completed.copy(message = null, permission = DirectPendingCompletionPermission.MISSING)

        assertEquals(pending.id, completed.pendingSendId)
        assertEquals(UUID(0, 7), completed.completedRequestId)
        assertNull(completed.pending)
        assertSame(message, completed.message)
        assertNull(erased.message)
        assertNull(erased.pending)
        assertEquals(key, erased.key)
        assertEquals(receipt.captureRequestId, erased.captureRequestId)
        assertEquals(UUID(0, 7), erased.completedRequestId)
    }
}
