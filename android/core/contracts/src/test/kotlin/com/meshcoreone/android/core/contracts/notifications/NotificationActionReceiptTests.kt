// AndroidOnly: WP-215 Independent process-memory action receipt transition assertions.
package com.meshcoreone.android.core.contracts.notifications

import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NotificationActionReceiptTests {
    @Test
    fun freshReceiptIsRetryableAndAvailableByItsContextKey() {
        val receipt = NotificationActionReceipt()
        assertEquals(NotificationActionReceipt.State.READY, receipt.state)
        assertFalse(receipt.preventsReplay)
        assertFalse(receipt.isAccepted)
        assertSame(receipt, (EmptyCoroutineContext + receipt)[NotificationActionReceipt])
    }

    @Test
    fun explicitRejectionPermitsAnotherAttempt() {
        val receipt = NotificationActionReceipt()
        receipt.wireStarted()
        assertEquals(NotificationActionReceipt.State.IN_FLIGHT, receipt.state)
        assertTrue(receipt.preventsReplay)
        receipt.wireRejected()
        assertEquals(NotificationActionReceipt.State.READY, receipt.state)
        assertFalse(receipt.preventsReplay)
        receipt.wireStarted()
        receipt.accepted()
        assertTrue(receipt.isAccepted)
    }

    @Test
    fun producerProvenNotDispatchedPermitsAnotherAttempt() {
        val receipt = NotificationActionReceipt()
        receipt.wireStarted()
        receipt.wireNotDispatched()
        assertEquals(NotificationActionReceipt.State.READY, receipt.state)
        assertFalse(receipt.preventsReplay)
        receipt.wireStarted()
        receipt.accepted()
        assertEquals(NotificationActionReceipt.State.ACCEPTED, receipt.state)
        assertTrue(receipt.preventsReplay)
    }

    @Test
    fun acceptanceSupportsSimpleCollaboratorAndIsIdempotent() {
        for (startWire in listOf(false, true)) {
            val receipt = NotificationActionReceipt()
            if (startWire) receipt.wireStarted()
            receipt.accepted()
            receipt.accepted()
            receipt.uncertain()
            assertEquals(NotificationActionReceipt.State.ACCEPTED, receipt.state)
            assertTrue(receipt.isAccepted)
            assertTrue(receipt.preventsReplay)
        }
    }

    @Test
    fun uncertaintyOnlyChangesAnInFlightReceipt() {
        val ready = NotificationActionReceipt()
        ready.uncertain()
        assertEquals(NotificationActionReceipt.State.READY, ready.state)
        assertFalse(ready.preventsReplay)
        ready.wireStarted()
        ready.uncertain()
        ready.uncertain()
        assertEquals(NotificationActionReceipt.State.UNCERTAIN, ready.state)
        assertTrue(ready.preventsReplay)
        assertFalse(ready.isAccepted)
    }

    @Test
    fun missingDurableProofPreventsReplayWithoutDowngradingAcceptedOrCommittedActions() {
        for (state in NotificationActionReceipt.State.entries) {
            val receipt = receiptAt(state)
            receipt.retainUncertain()
            val expected = when (state) {
                NotificationActionReceipt.State.READY, NotificationActionReceipt.State.IN_FLIGHT ->
                    NotificationActionReceipt.State.UNCERTAIN
                else -> state
            }
            assertEquals(expected, receipt.state)
            assertTrue(receipt.preventsReplay)
        }
    }

    @Test
    fun committedReadIsNotWireAcceptanceAndCannotBeDowngraded() {
        val receipt = NotificationActionReceipt()
        receipt.committed()
        receipt.uncertain()
        assertEquals(NotificationActionReceipt.State.COMMITTED, receipt.state)
        assertTrue(receipt.preventsReplay)
        assertFalse(receipt.isAccepted)
    }

    @Test
    fun onlyInFlightCanReturnToReady() {
        for (state in NotificationActionReceipt.State.entries) {
            if (state == NotificationActionReceipt.State.IN_FLIGHT) continue
            val receipt = receiptAt(state)
            assertFailsWith<IllegalStateException> { receipt.wireRejected() }
            assertEquals(state, receipt.state)
            assertFailsWith<IllegalStateException> { receipt.wireNotDispatched() }
            assertEquals(state, receipt.state)
        }
    }

    @Test
    fun startedAndCommittedRequireReadyWithoutMutatingRejectedState() {
        for (state in NotificationActionReceipt.State.entries) {
            if (state == NotificationActionReceipt.State.READY) continue
            val receipt = receiptAt(state)
            assertFailsWith<IllegalStateException> { receipt.wireStarted() }
            assertEquals(state, receipt.state)
            assertFailsWith<IllegalStateException> { receipt.committed() }
            assertEquals(state, receipt.state)
        }
    }

    @Test
    fun uncertainOrCommittedCannotBecomeAccepted() {
        for (state in listOf(NotificationActionReceipt.State.UNCERTAIN, NotificationActionReceipt.State.COMMITTED)) {
            val receipt = receiptAt(state)
            assertFailsWith<IllegalStateException> { receipt.accepted() }
            assertEquals(state, receipt.state)
            assertTrue(receipt.preventsReplay)
        }
    }

    @Test
    fun uncertainOutcomePreservesTheOriginalFailure() {
        val cause = IllegalStateException("response unavailable")
        val failure = NotificationReplyOutcomeUncertain(cause)
        assertSame(cause, failure.cause)
        assertEquals(
            "Notification reply acceptance is unknown; automatic retransmission is unsafe",
            failure.message,
        )
    }

    private fun receiptAt(state: NotificationActionReceipt.State): NotificationActionReceipt =
        NotificationActionReceipt().also { receipt ->
            when (state) {
                NotificationActionReceipt.State.READY -> Unit
                NotificationActionReceipt.State.IN_FLIGHT -> receipt.wireStarted()
                NotificationActionReceipt.State.ACCEPTED -> receipt.accepted()
                NotificationActionReceipt.State.UNCERTAIN -> {
                    receipt.wireStarted()
                    receipt.uncertain()
                }
                NotificationActionReceipt.State.COMMITTED -> receipt.committed()
            }
        }
}
