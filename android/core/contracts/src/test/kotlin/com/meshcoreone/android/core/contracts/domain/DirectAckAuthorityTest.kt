// AndroidOnly: WP-201 Direct-ACK receipt value semantics, not storage enforcement evidence.
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class DirectAckAuthorityTest {
    private val requestId = UUID(0, 1)
    private val key = EntityKey(RadioId(UUID(0, 2)), UUID(0, 3))
    private val message = MessageDTO(
        id = key.id,
        radioId = key.radioId,
        text = "original",
        timestamp = 1u,
        createdAt = Instant.EPOCH,
    )

    @Test
    fun predictedAndActualCodesRemainDistinctAndUnsigned() {
        val wires = mutableListOf(DirectAckWireReceipt(UUID(0, 4), 0u, UInt.MAX_VALUE))
        val receipt = receipt(wires = SnapshotList(wires))
        wires.clear()

        assertEquals(1, receipt.wires.size)
        assertEquals(0u, receipt.wires.single().predictedAckCode)
        assertEquals(UInt.MAX_VALUE, receipt.wires.single().acceptedAckCode)
        assertNull(DirectAckWireReceipt(UUID(0, 5), 0x8000_0000u, null).acceptedAckCode)
    }

    @Test
    fun historicalDeliveryDoesNotDescribeReplacementOrGrantCurrentPermission() {
        val delivered = message.copy(status = MessageStatus.DELIVERED, ackCode = UInt.MAX_VALUE)
        val delivery = DirectAckDelivery(UInt.MAX_VALUE, null, delivered)
        val history = receipt().copy(
            phase = DirectAckPhase.DELIVERED,
            message = delivered,
            delivery = delivery,
            permission = DirectAckPermission.INVALIDATED,
        )
        val replacement = message.copy(text = "replacement", status = MessageStatus.FAILED)
        val result = DirectAckMutationResult.AlreadyApplied(history)

        assertEquals(DirectAckPermission.INVALIDATED, result.receipt.permission)
        assertSame(delivered, result.receipt.delivery?.message)
        assertEquals(MessageStatus.DELIVERED, result.receipt.delivery?.message?.status)
        assertEquals(MessageStatus.FAILED, replacement.status)
        assertNotEquals(replacement, result.receipt.delivery?.message)
        assertNull(result.receipt.delivery?.roundTripTime)
    }

    @Test
    fun deliveryTupleRetainsExactRttIncludingUnsignedBoundaries() {
        val delivered = message.copy(status = MessageStatus.DELIVERED)
        val first = DirectAckDelivery(0x8000_0000u, 0u, delivered)
        val changedRtt = first.copy(roundTripTime = UInt.MAX_VALUE)
        val missingRtt = first.copy(roundTripTime = null)

        assertNotEquals(first, changedRtt)
        assertNotEquals(first, missingRtt)
        assertEquals(0x8000_0000u, changedRtt.ackCode)
        assertEquals(UInt.MAX_VALUE, changedRtt.roundTripTime)
    }

    @Test
    fun operationHistoryPreservesRequestAndPayloadWithoutMutableListAliases() {
        val retry = DirectAckBookkeeping.RetryStarted(2, 4)
        val operations = mutableListOf(
            DirectAckBookkeepingReceipt(UUID(0, 6), retry),
            DirectAckBookkeepingReceipt(UUID(0, 7), DirectAckBookkeeping.RetryExhausted),
            DirectAckBookkeepingReceipt(UUID(0, 8), DirectAckBookkeeping.ResendCountIncrement),
        )
        val receipt = receipt().copy(bookkeeping = SnapshotList(operations))
        operations.clear()

        assertEquals(3, receipt.bookkeeping.size)
        assertEquals(UUID(0, 6), receipt.bookkeeping[0].requestId)
        assertEquals(retry, receipt.bookkeeping[0].change)
        assertNotEquals(retry, retry.copy(retryAttempt = 3))
        assertSame(DirectAckBookkeeping.RetryExhausted, receipt.bookkeeping[1].change)
        assertSame(DirectAckBookkeeping.ResendCountIncrement, receipt.bookkeeping[2].change)
    }

    @Test
    fun resendBaselineDeliveryIsNotAnOwnDeliveryCommit() {
        val baseline = message.copy(status = MessageStatus.DELIVERED, ackCode = 7u)
        val registered = receipt().copy(
            mode = DirectAckMode.RESEND,
            initialStatus = MessageStatus.DELIVERED,
            message = baseline,
        )

        assertEquals(DirectAckPhase.REGISTERED, registered.phase)
        assertEquals(MessageStatus.DELIVERED, registered.initialStatus)
        assertNull(registered.delivery)
        assertEquals(DirectAckMode.RESEND, registered.mode)
        assertEquals(7u, assertNotNull(registered.message).ackCode)
    }

    @Test
    fun pendingCompletionHistoryKeepsOperationAndPendingIdentitiesDistinct() {
        val completions = mutableListOf(DirectAckPendingReceipt(UUID(0, 9), UUID(0, 10)))
        val history = receipt().copy(pendingCompletions = SnapshotList(completions))
        completions.clear()

        assertEquals(UUID(0, 9), history.pendingCompletions.single().requestId)
        assertEquals(UUID(0, 10), history.pendingCompletions.single().pendingSendId)
        assertNotEquals(
            history.pendingCompletions.single(),
            history.pendingCompletions.single().copy(pendingSendId = UUID(0, 11)),
        )
    }

    @Test
    fun typedRejectionsAndObservationsPreserveTheirPayloads() {
        for (reason in DirectAckRejection.entries) {
            assertEquals(reason, DirectAckActivationResult.Rejected(reason).reason)
            assertEquals(reason, DirectAckBeginResult.Rejected(reason).reason)
            assertEquals(reason, DirectAckMutationResult.Rejected(reason).reason)
            assertEquals(reason, DirectAckObservation.Rejected(reason).reason)
            assertEquals(reason, DirectAckRevocationResult.Rejected(reason).reason)
        }
        val observed = DirectAckObservation.Found(receipt())
        assertSame(message, observed.receipt.message)
        assertNotEquals<DirectAckRevocationResult>(
            DirectAckRevocationResult.Revoked,
            DirectAckRevocationResult.AlreadyRevoked,
        )
    }

    @Test
    fun issuedIntentPayloadIsSeparateFromLaterSameKeyReplacement() {
        val issued = DirectAckIntentReceipt(
            key, requestId, DirectAckMode.SEND, message, null, null, DirectAckPermission.CURRENT,
        )
        val replacement = message.copy(text = "replacement", timestamp = UInt.MAX_VALUE)

        assertSame(message, issued.message)
        assertEquals("original", issued.message?.text)
        assertNotEquals(replacement, issued.message)
        assertNull(issued.pendingSendId)
        assertNull(issued.pending)
        assertEquals(DirectAckPermission.CURRENT, issued.permission)
    }

    @Test
    fun erasedIntentAndDeliveryKeepOnlyHistoricalIdentityAndTuple() {
        val erasedIntent = DirectAckIntentReceipt(
            key, requestId, DirectAckMode.RESEND, null, UUID(0, 12), null, DirectAckPermission.INVALIDATED,
        )
        val delivery = DirectAckDelivery(UInt.MAX_VALUE, 123u, null)
        val history = receipt().copy(
            phase = DirectAckPhase.DELIVERED,
            message = null,
            delivery = delivery,
            permission = DirectAckPermission.MISSING,
        )

        assertEquals(key, erasedIntent.key)
        assertEquals(requestId, erasedIntent.requestId)
        assertEquals(UUID(0, 12), erasedIntent.pendingSendId)
        assertNull(erasedIntent.message)
        assertNull(erasedIntent.pending)
        assertNull(history.message)
        assertNull(history.delivery?.message)
        assertEquals(UInt.MAX_VALUE, history.delivery?.ackCode)
        assertEquals(123u, history.delivery?.roundTripTime)
        assertEquals(DirectAckPermission.MISSING, history.permission)
        for (reason in DirectAckRejection.entries) {
            assertEquals(reason, DirectAckIntentResult.Rejected(reason).reason)
            assertEquals(reason, DirectAckIntentObservation.Rejected(reason).reason)
        }
    }

    @Test
    fun retiredLeaseReceiptErasesPayloadWithoutImplyingMessageDeletion() {
        val ordinaryMessageStillExists = message
        val retired = receipt().copy(
            message = null,
            delivery = DirectAckDelivery(UInt.MAX_VALUE, 123u, null),
            permission = DirectAckPermission.STALE_LEASE,
        )

        assertSame(message, ordinaryMessageStillExists)
        assertNull(retired.message)
        assertNull(retired.delivery?.message)
        assertEquals(DirectAckPermission.STALE_LEASE, retired.permission)
        assertEquals(key, retired.key)
        assertEquals(requestId, retired.requestId)
        assertEquals(UInt.MAX_VALUE, retired.delivery?.ackCode)
        assertEquals(123u, retired.delivery?.roundTripTime)
    }

    private fun receipt(wires: SnapshotList<DirectAckWireReceipt> = SnapshotList.empty()) = DirectAckReceipt(
        key = key,
        requestId = requestId,
        mode = DirectAckMode.SEND,
        initialStatus = MessageStatus.PENDING,
        phase = DirectAckPhase.REGISTERED,
        wires = wires,
        bookkeeping = SnapshotList.empty(),
        pendingCompletions = SnapshotList.empty(),
        message = message,
        delivery = null,
        permission = DirectAckPermission.CURRENT,
    )
}
