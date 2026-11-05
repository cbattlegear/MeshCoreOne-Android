// AndroidOnly: WP-201 Channel success immutable tuple, authority and cleanup value controls.
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

class ChannelSendCompletionAuthorityTest {
    private val key = EntityKey(RadioId(UUID(0, 1)), UUID(0, 2))
    private val captureId = UUID(0, 3)
    private val completionId = UUID(0, 4)
    private val pendingId = UUID(0, 5)
    private val message = MessageDTO(
        id = key.id,
        radioId = key.radioId,
        channelIndex = 0u,
        text = "accepted channel",
        timestamp = 42u,
        createdAt = Instant.EPOCH,
        status = MessageStatus.SENT,
    )
    private val capture = ChannelFailureReceipt(
        captureId, key, message, null, null, DirectAckPermission.CURRENT, SnapshotList.empty(),
    )
    private val receipt = ChannelSendCompletionReceipt(
        capture, completionId, ChannelSendCompletionMode.SEND, 42u, null,
    )

    @Test
    fun appliedBindsIssuedCompletionAndOriginalCaptureWithoutCreatingDeliveryTuple() {
        val handle = object : ChannelSendCompletion {}
        val applied = ChannelSendCompletionResult.Applied(handle, receipt)

        assertSame(handle, applied.completion)
        assertEquals(captureId, applied.receipt.capture.captureRequestId)
        assertEquals(completionId, applied.receipt.completionRequestId)
        assertEquals(MessageStatus.SENT, applied.receipt.capture.message?.status)
        assertNull(applied.receipt.capture.message?.ackCode)
        assertNull(applied.receipt.capture.message?.roundTripTime)
    }

    @Test
    fun originalAcceptanceTupleRetainsModeAndFullUIntTimestamp() {
        for (mode in ChannelSendCompletionMode.entries) {
            for (timestamp in listOf(0u, 42u, UInt.MAX_VALUE)) {
                val value = receipt.copy(mode = mode, acceptedTimestamp = timestamp)
                assertEquals(mode, value.mode)
                assertEquals(timestamp, value.acceptedTimestamp)
                assertEquals(completionId, value.completionRequestId)
            }
        }
        assertNotEquals(
            receipt,
            receipt.copy(mode = ChannelSendCompletionMode.RESEND),
        )
    }

    @Test
    fun currentExactReplayCanRetainOnlyItsIssuedCompletionHandle() {
        val handle = object : ChannelSendCompletion {}
        val replay = ChannelSendCompletionResult.AlreadyApplied(handle, receipt)

        assertSame(handle, replay.completion)
        assertEquals(receipt, replay.receipt)
        assertEquals(DirectAckPermission.CURRENT, replay.receipt.capture.permission)
    }

    @Test
    fun invalidatedHistoricalCommitDoesNotReissueHandleOrReplacementPayload() {
        val historical = receipt.copy(
            capture = capture.copy(
                message = null,
                pendingSendId = pendingId,
                permission = DirectAckPermission.INVALIDATED,
            ),
        )
        val replay = ChannelSendCompletionResult.AlreadyApplied(null, historical)

        assertNull(replay.completion)
        assertNull(replay.receipt.capture.message)
        assertNull(replay.receipt.capture.pending)
        assertEquals(pendingId, replay.receipt.capture.pendingSendId)
        assertEquals(completionId, replay.receipt.completionRequestId)
        assertEquals(42u, replay.receipt.acceptedTimestamp)
        assertEquals(historical, ChannelSendCompletionObservation.Found(historical).receipt)
    }

    @Test
    fun exactCleanupHistoryRetainsBothOperationIdsAndNoPayloadOrRenewedGrant() {
        val cleanupId = UUID(0, 6)
        val cleaned = receipt.copy(
            capture = capture.copy(
                message = null,
                pendingSendId = pendingId,
                permission = DirectAckPermission.INVALIDATED,
            ),
            pendingCompletionRequestId = cleanupId,
        )
        val applied = ChannelSendPendingCompletionResult.Applied(cleaned)
        val replay = ChannelSendPendingCompletionResult.AlreadyApplied(cleaned)

        assertEquals(applied.receipt, replay.receipt)
        assertEquals(completionId, replay.receipt.completionRequestId)
        assertEquals(cleanupId, replay.receipt.pendingCompletionRequestId)
        assertEquals(pendingId, replay.receipt.capture.pendingSendId)
        assertNull(replay.receipt.capture.message)
        assertNull(replay.receipt.capture.pending)
        assertNotEquals(cleaned, cleaned.copy(pendingCompletionRequestId = UUID(0, 7)))
    }

    @Test
    fun deliveredGuardIsNotAnAppliedCompletionOrCleanup() {
        val delivered = capture.copy(message = message.copy(status = MessageStatus.DELIVERED))
        val result = ChannelSendCompletionResult.AlreadyDelivered(delivered)

        assertEquals(MessageStatus.DELIVERED, result.capture.message?.status)
        assertEquals(captureId, result.capture.captureRequestId)
        assertNotEquals<ChannelSendCompletionResult>(
            ChannelSendCompletionResult.AlreadyApplied(null, receipt), result,
        )
    }

    @Test
    fun notRecordedRetainsExactRequestsAndDoesNotMasqueradeAsACommittedOutcome() {
        val missing = ChannelSendCompletionObservation.NotRecorded(captureId, completionId)

        assertEquals(captureId, missing.captureRequestId)
        assertEquals(completionId, missing.completionRequestId)
        assertNotEquals<ChannelSendCompletionObservation>(
            ChannelSendCompletionObservation.Found(receipt), missing,
        )
        assertNotEquals(missing, missing.copy(completionRequestId = UUID(0, 8)))
        assertNotEquals(missing, missing.copy(captureRequestId = UUID(0, 9)))
    }

    @Test
    fun allRejectionsStayExplicitAcrossCompletionCleanupAndInspection() {
        for (reason in DirectAckRejection.entries) {
            assertEquals(reason, ChannelSendCompletionResult.Rejected(reason).reason)
            assertEquals(reason, ChannelSendPendingCompletionResult.Rejected(reason).reason)
            assertEquals(reason, ChannelSendCompletionObservation.Rejected(reason).reason)
        }
    }

    @Test
    fun historicalIdentityCannotTransferSuccessAcrossRadioOrCapture() {
        val otherRadio = capture.copy(key = key.copy(radioId = RadioId(UUID(0, 10))))
        val otherCapture = capture.copy(captureRequestId = UUID(0, 11))
        val retired = receipt.copy(
            capture = capture.copy(message = null, permission = DirectAckPermission.STALE_LEASE),
        )

        assertNotEquals(receipt, receipt.copy(capture = otherRadio))
        assertNotEquals(receipt, receipt.copy(capture = otherCapture))
        assertEquals(key, retired.capture.key)
        assertEquals(captureId, retired.capture.captureRequestId)
        assertEquals(completionId, retired.completionRequestId)
        assertNull(retired.capture.message)
        assertNull(ChannelSendCompletionResult.AlreadyApplied(null, retired).completion)
    }
}
