// AndroidOnly: WP-201 Queue admission and retained acceptance shape/value controls, not producer tests.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.PendingSendDTO
import com.meshcoreone.android.core.model.PendingSendKind
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class ChannelQueueAdmissionAuthorityTest {
    private val key = EntityKey(RadioId(UUID(0, 1)), UUID(0, 2))
    private val captureId = UUID(0, 3)
    private val pendingId = UUID(0, 4)
    private val completionId = UUID(0, 5)
    private val message = MessageDTO(
        id = key.id,
        radioId = key.radioId,
        channelIndex = 0u,
        text = "queued channel",
        timestamp = 42u,
        createdAt = Instant.EPOCH,
        status = MessageStatus.PENDING,
    )
    private val pending = PendingSendDTO(
        id = pendingId,
        radioId = key.radioId,
        messageID = key.id,
        kind = PendingSendKind.CHANNEL,
        contactID = null,
        channelIndex = 0u,
        isResend = false,
        messageText = message.text,
        messageTimestamp = message.timestamp,
        localNodeName = null,
        sequence = 0,
        enqueuedAt = Instant.EPOCH,
        attemptCount = 1,
    )
    private val capture = ChannelFailureReceipt(
        captureId, key, message, pendingId, pending, DirectAckPermission.CURRENT, SnapshotList.empty(),
    )
    private val acceptance = ChannelQueueAcceptanceReceipt(
        captureId, key, pendingId, completionId, ChannelSendCompletionMode.SEND, 42u,
    )

    @Test
    fun readyBindsIssuedAttemptToActualMessageAndSelectedPending() {
        val handle = object : ChannelFailureAttempt {}
        val ready = ChannelQueueAdmissionResult.Ready(handle, capture, 1)

        assertSame(handle, ready.attempt)
        assertEquals(DirectAckPermission.CURRENT, ready.receipt.permission)
        assertEquals(message, ready.receipt.message)
        assertEquals(pending, ready.receipt.pending)
        assertEquals(pendingId, ready.receipt.pendingSendId)
        assertEquals(ready.postBumpCount, ready.receipt.pending?.attemptCount)
    }

    @Test
    fun readyCarriesActualModeAndTimestampNotAnEnvelopeOrUnusedClockCandidate() {
        for (isResend in listOf(false, true)) {
            val selected = pending.copy(isResend = isResend, attemptCount = 2)
            val receipt = capture.copy(pending = selected)
            val ready = ChannelQueueAdmissionResult.Ready(object : ChannelFailureAttempt {}, receipt, 2)

            assertEquals(isResend, ready.receipt.pending?.isResend)
            assertEquals(42u, ready.receipt.message?.timestamp)
            assertEquals(2L, ready.postBumpCount)
            assertNotEquals(999u, ready.receipt.message?.timestamp)
        }
    }

    @Test
    fun countValuesDoNotIntroduceAnAttemptCap() {
        for (count in listOf(1L, 2L, Long.MAX_VALUE)) {
            val receipt = capture.copy(pending = pending.copy(attemptCount = count))
            val ready = ChannelQueueAdmissionResult.Ready(object : ChannelFailureAttempt {}, receipt, count)

            assertEquals(count, ready.postBumpCount)
            assertEquals(count, ready.receipt.pending?.attemptCount)
        }
    }

    @Test
    fun alreadyReadyRetainsOnlyTheSameIssuedHandleReceiptAndCount() {
        val handle = object : ChannelFailureAttempt {}
        val ready = ChannelQueueAdmissionResult.Ready(handle, capture, 1)
        val replay = ChannelQueueAdmissionResult.AlreadyReady(handle, capture, 1)

        assertSame(ready.attempt, replay.attempt)
        assertEquals(ready.receipt, replay.receipt)
        assertEquals(ready.postBumpCount, replay.postBumpCount)
        assertNotEquals<ChannelQueueAdmissionResult>(ready, replay)
    }

    @Test
    fun previouslyCompletedRetainsHistoricalTupleWithoutReplacementPayload() {
        val historical = capture.copy(
            message = null, pending = null, permission = DirectAckPermission.STALE_LEASE,
        )
        val completion = ChannelSendCompletionReceipt(
            historical, completionId, ChannelSendCompletionMode.SEND, 42u, null,
        )
        val result = ChannelQueueAdmissionResult.PreviouslyCompleted(completion)

        assertEquals(completionId, result.receipt.completionRequestId)
        assertEquals(captureId, result.receipt.capture.captureRequestId)
        assertEquals(pendingId, result.receipt.capture.pendingSendId)
        assertNull(result.receipt.capture.message)
        assertNull(result.receipt.capture.pending)
        assertNull(result.receipt.pendingCompletionRequestId)
    }

    @Test
    fun retainedAcceptanceAndUnprovenHistoryStayDistinctNoDispatchObservations() {
        val retained = ChannelQueueAdmissionResult.Uncertain(
            ChannelQueueUncertaintyReason.RETAINED_ACCEPTANCE, acceptance,
        )
        val unproven = ChannelQueueAdmissionResult.Uncertain(
            ChannelQueueUncertaintyReason.UNPROVEN_COMPLETION_LINEAGE, acceptance,
        )

        assertNotEquals(retained, unproven)
        for (result in listOf(retained, unproven)) {
            assertEquals(acceptance, result.acceptance)
            assertEquals(key, result.acceptance.key)
            assertEquals(pendingId, result.acceptance.pendingSendId)
            assertEquals(completionId, result.acceptance.completionRequestId)
        }
    }

    @Test
    fun registeredAndReplayObservationsPreserveOriginalTuple() {
        val first = ChannelQueueAcceptanceResult.Registered(acceptance)
        val replay = ChannelQueueAcceptanceResult.AlreadyRegistered(acceptance)

        assertEquals(first.acceptance, replay.acceptance)
        assertEquals(captureId, replay.acceptance.captureRequestId)
        assertEquals(42u, replay.acceptance.acceptedTimestamp)
        assertNotEquals<ChannelQueueAcceptanceResult>(first, replay)
    }

    @Test
    fun acceptanceSupportsBothModesAndFullUIntTimestampWithoutDeliveryFields() {
        for (mode in ChannelSendCompletionMode.entries) {
            for (timestamp in listOf(0u, 42u, UInt.MAX_VALUE)) {
                val value = acceptance.copy(mode = mode, acceptedTimestamp = timestamp)
                assertEquals(mode, value.mode)
                assertEquals(timestamp, value.acceptedTimestamp)
                assertEquals(completionId, value.completionRequestId)
            }
        }
        assertNotEquals(acceptance, acceptance.copy(completionRequestId = UUID(0, 6)))
        assertNotEquals(acceptance, acceptance.copy(acceptedTimestamp = 43u))
        assertNotEquals(acceptance, acceptance.copy(mode = ChannelSendCompletionMode.RESEND))
    }

    @Test
    fun publicIdentityValuesDoNotIdentifyOrConstructAnOpaqueAttempt() {
        val first = object : ChannelFailureAttempt {}
        val replacement = object : ChannelFailureAttempt {}
        val old = ChannelQueueAdmissionResult.Ready(first, capture, 1)
        val other = ChannelQueueAdmissionResult.Ready(replacement, capture, 1)

        assertEquals(old.receipt, other.receipt)
        assertNotSame(old.attempt, other.attempt)
        assertNotEquals(acceptance, acceptance.copy(captureRequestId = UUID(0, 7)))
        assertNotEquals(acceptance, acceptance.copy(pendingSendId = UUID(0, 8)))
        assertNotEquals(acceptance, acceptance.copy(key = key.copy(radioId = RadioId(UUID(0, 9)))))
    }

    @Test
    fun admissionAndAcceptanceKeepEveryTypedRejectionExplicit() {
        for (reason in DirectAckRejection.entries) {
            assertEquals(reason, ChannelQueueAdmissionResult.Rejected(reason).reason)
            assertEquals(reason, ChannelQueueAcceptanceResult.Rejected(reason).reason)
        }
    }

    @Test
    fun acceptanceNoteIsSynchronousWhileAdmissionIsSuspending() {
        val methods = ChannelQueueAdmissionAuthority::class.java.declaredMethods
        val note = methods.single { it.name.substringBefore('-') == "noteAcceptedChannelAttempt" }
        val admit = methods.single { it.name.substringBefore('-') == "admitQueuedChannelSend" }

        assertEquals(4, note.parameterCount)
        assertEquals(ChannelQueueAcceptanceResult::class.java, note.returnType)
        assertEquals(5, admit.parameterCount)
        assertEquals("kotlin.coroutines.Continuation", admit.parameterTypes.last().name)
    }
}
