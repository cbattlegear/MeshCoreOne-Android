// AndroidOnly: WP-215 Real-session acceptance and caller-result cancellation regression cases.
package com.meshcoreone.android.core.services.notifications

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationReplyOutcomeUncertain
import com.meshcoreone.android.core.contracts.notifications.NotificationReadReceipt
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.services.messaging.Harness
import com.meshcoreone.android.core.services.messaging.RADIO
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationAcceptanceRecoveryTests {
    @Test fun acceptedRetryClearsOnlyItsOwnUnconsumedDraft() = retryDraft(null)
    @Test fun acceptedRetryPreservesANewerUserDraft() = retryDraft("newer user text")
    @Test fun acceptedRetryPreservesANewerEqualTextUserDraft() = retryDraft("retry text")

    @Test fun uncertainRetryCannotRestoreItsPriorUnsentDraft() = uncertainRetryDraft(null)
    @Test fun uncertainRetryPreservesANewerUserDraft() = uncertainRetryDraft("newer user text")
    @Test fun uncertainRetryPreservesANewerEqualTextUserDraft() = uncertainRetryDraft("retry text")

    @Test fun repeatedFailureThenSuccessCannotReclaimANewerUserDraft() =
        revokedDraftAfterRepeatedFailure("newer user text", uncertain = false)
    @Test fun repeatedFailureThenSuccessCannotReclaimANewerEqualTextUserDraft() =
        revokedDraftAfterRepeatedFailure("retry text", uncertain = false)
    @Test fun repeatedFailureThenUncertaintyCannotHideANewerUserDraft() =
        revokedDraftAfterRepeatedFailure("newer user text", uncertain = true)
    @Test fun repeatedFailureThenUncertaintyCannotHideANewerEqualTextUserDraft() =
        revokedDraftAfterRepeatedFailure("retry text", uncertain = true)
    @Test fun repeatedFailureCannotResurrectAnActionAfterPublicDraftConsumption() =
        revokedDraftAfterRepeatedFailure("newer user text", uncertain = false, consumePublicDraft = true)
    @Test fun uncertainRetryCannotResurrectAnActionAfterEqualPublicDraftConsumption() =
        revokedDraftAfterRepeatedFailure("retry text", uncertain = true, consumePublicDraft = true)

    @Test fun publicSaveDuringFirstAttemptRevokesLaterFailureRestoration() = runTest {
        val h = Harness(this)
        val n = NotificationHarness(this, RADIO).authorize()
        try {
            h.start()
            n.store.contacts[h.contact.key] = h.contact
            val handler = NotificationActionHandler(n.store, sender(h), n.service, n.rooms, n.sync)
            handler.configure({ true }, { "Me" })
            h.store.before = { if (it == "saveMessage") awaitCancellation() }
            val receipt = NotificationActionReceipt()
            val caller = launch(receipt) { handler.handleQuickReply(h.contact.key, "older notification text") }
            runCurrent()
            n.service.saveDraft(h.contact.key, "newer user text")
            caller.cancelAndJoin()
            assertEquals(NotificationActionReceipt.State.READY, receipt.state)
            assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
            assertEquals("newer user text", n.service.consumeDraft(h.contact.key))
        } finally { h.close() }
    }

    private fun revokedDraftAfterRepeatedFailure(
        newerDraft: String,
        uncertain: Boolean,
        consumePublicDraft: Boolean = false,
    ) = runTest {
        val h = Harness(this)
        val n = NotificationHarness(this, RADIO).authorize()
        try {
            h.start()
            n.store.contacts[h.contact.key] = h.contact
            val handler = NotificationActionHandler(n.store, sender(h), n.service, n.rooms, n.sync)
            handler.configure({ true }, { "Me" })
            h.store.before = { if (it == "saveMessage") awaitCancellation() }
            val receipt = NotificationActionReceipt()
            suspend fun cancelBeforeWire() {
                val caller = launch(receipt) { handler.handleQuickReply(h.contact.key, "retry text") }
                runCurrent()
                caller.cancelAndJoin()
                assertEquals(NotificationActionReceipt.State.READY, receipt.state)
                assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
            }
            cancelBeforeWire()
            n.service.saveDraft(h.contact.key, newerDraft)
            if (consumePublicDraft) assertEquals(newerDraft, n.service.consumeDraft(h.contact.key))
            cancelBeforeWire()
            assertEquals(2, n.delivery.posted.size)
            if (consumePublicDraft) assertNull(n.service.consumeDraft(h.contact.key))
            h.store.before = null
            h.transport.holdMessageReplies = uncertain
            val retry = launch(receipt) { handler.handleQuickReply(h.contact.key, "retry text") }
            runCurrent()
            if (uncertain) retry.cancelAndJoin() else retry.join()
            assertEquals(
                if (uncertain) NotificationActionReceipt.State.UNCERTAIN else NotificationActionReceipt.State.ACCEPTED,
                receipt.state,
            )
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertEquals(if (consumePublicDraft) null else newerDraft, n.service.consumeDraft(h.contact.key))
            assertNull(n.service.consumeDraft(h.contact.key))
        } finally { h.close() }
    }

    @Test fun actionDraftIsRetainedUntilDispatchOwnerProvesItWasNotSent() = runTest {
        val n = NotificationHarness(this)
        val contact = n.contact()
        val receipt = NotificationActionReceipt()
        n.service.trackActionDraft(contact.key, receipt)
        n.service.saveActionDraft(contact.key, "retained original", receipt)
        receipt.wireStarted()
        assertNull(n.service.consumeDraft(contact.key))
        receipt.wireNotDispatched()
        assertEquals("retained original", n.service.consumeDraft(contact.key))
        assertNull(n.service.consumeDraft(contact.key))
    }

    private fun uncertainRetryDraft(newerDraft: String?) = runTest {
        val h = Harness(this)
        val n = NotificationHarness(this, RADIO).authorize()
        try {
            h.start()
            n.store.contacts[h.contact.key] = h.contact
            val handler = NotificationActionHandler(n.store, sender(h), n.service, n.rooms, n.sync)
            handler.configure({ true }, { "Me" })
            h.store.before = { if (it == "saveMessage") awaitCancellation() }
            val receipt = NotificationActionReceipt()
            val first = launch(receipt) { handler.handleQuickReply(h.contact.key, "retry text") }
            runCurrent()
            first.cancelAndJoin()
            assertEquals(NotificationActionReceipt.State.READY, receipt.state)
            assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
            assertEquals(1, n.delivery.posted.size)
            if (newerDraft != null) n.service.saveDraft(h.contact.key, newerDraft)

            h.store.before = null
            h.transport.holdMessageReplies = true
            val retry = launch(receipt) { handler.handleQuickReply(h.contact.key, "retry text") }
            runCurrent()
            assertEquals(NotificationActionReceipt.State.IN_FLIGHT, receipt.state)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            retry.cancelAndJoin()
            assertEquals(NotificationActionReceipt.State.UNCERTAIN, receipt.state)
            assertEquals(newerDraft, n.service.consumeDraft(h.contact.key))
            assertNull(n.service.consumeDraft(h.contact.key))
            assertEquals(1, n.delivery.posted.size, "Uncertainty must not report another definite failure")
        } finally { h.close() }
    }

    private fun retryDraft(newerDraft: String?) = runTest {
        val h = Harness(this)
        val n = NotificationHarness(this, RADIO).authorize()
        try {
            h.start()
            n.store.contacts[h.contact.key] = h.contact
            val handler = NotificationActionHandler(n.store, sender(h), n.service, n.rooms, n.sync)
            handler.configure({ true }, { "Me" })
            h.store.before = { if (it == "saveMessage") awaitCancellation() }
            val receipt = NotificationActionReceipt()
            val caller = launch(receipt) { handler.handleQuickReply(h.contact.key, "retry text") }
            runCurrent()
            caller.cancelAndJoin()
            assertEquals(NotificationActionReceipt.State.READY, receipt.state)
            assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
            // Leave the restored draft in place across the actual successful session retry.
            if (newerDraft != null) n.service.saveDraft(h.contact.key, newerDraft)
            h.store.before = null
            withContext(receipt) { handler.handleQuickReply(h.contact.key, "retry text") }
            assertEquals(NotificationActionReceipt.State.ACCEPTED, receipt.state)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertEquals(newerDraft, n.service.consumeDraft(h.contact.key))
        } finally { h.close() }
    }

    private fun sender(h: Harness) = object : NotificationMessageSending {
        override suspend fun sendDirectMessage(text: String, contact: ContactDTO): MessageDTO =
            h.service.sendDirectMessage(text, contact)
        override suspend fun sendChannelMessage(text: String, channelIndex: UByte, radioId: RadioId) {
            h.service.sendChannelMessage(text, channelIndex, radioId)
        }
    }

    @Test fun directAcceptanceSurvivesCancellationBeforeOwnedResultReturns() = acceptedReply(channel = false)
    @Test fun channelAcceptanceSurvivesCancellationBeforeOwnedResultReturns() = acceptedReply(channel = true)

    @Test fun directCancellationBehindProtocolSerializerKeepsDraftAndPermitsRetry() = unsentBehindSerializer(channel = false)
    @Test fun channelCancellationBehindProtocolSerializerReportsFailureAndPermitsRetry() = unsentBehindSerializer(channel = true)

    private fun unsentBehindSerializer(channel: Boolean) = runTest {
        val h = Harness(this)
        val n = NotificationHarness(this, RADIO).authorize()
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            if (channel) h.store.seedChannelCreation()
            h.start()
            n.store.contacts[h.contact.key] = h.contact
            h.transport.beforeReply = {
                if (it[0] == CommandCode.RESET_PATH.rawValue) {
                    holding.complete(Unit)
                    release.await()
                }
            }
            val prior = backgroundScope.async { h.session.resetPath(h.contact.publicKey) }
            holding.await()
            val handler = NotificationActionHandler(n.store, sender(h), n.service, n.rooms, n.sync)
            handler.configure({ true }, { "Me" })
            val receipt = NotificationActionReceipt()
            suspend fun reply() {
                if (channel) handler.handleChannelQuickReply(RADIO, 0u, "retry safely")
                else handler.handleQuickReply(h.contact.key, "retry safely")
            }
            val caller = launch(receipt) { reply() }
            runCurrent()
            val wire = if (channel) CommandCode.SEND_CHANNEL_MESSAGE else CommandCode.SEND_MESSAGE
            assertEquals(NotificationActionReceipt.State.IN_FLIGHT, receipt.state)
            assertTrue(h.sends(wire).isEmpty())
            caller.cancelAndJoin()
            assertEquals(NotificationActionReceipt.State.READY, receipt.state)
            assertFalse(receipt.preventsReplay)
            if (!channel) assertEquals("retry safely", n.service.consumeDraft(h.contact.key))
            assertEquals(1, n.delivery.posted.size)
            release.complete(Unit)
            prior.await()
            runCurrent()
            assertTrue(h.sends(wire).isEmpty(), "A cancelled request cannot dispatch after serializer release")
            withContext(receipt) { reply() }
            assertEquals(NotificationActionReceipt.State.ACCEPTED, receipt.state)
            assertEquals(1, h.sends(wire).size)
            assertEquals(1, n.sync.notifications)
        } finally {
            release.complete(Unit)
            h.close()
        }
    }

    private fun acceptedReply(channel: Boolean) = runTest {
        val h = Harness(this)
        val n = NotificationHarness(this, RADIO).authorize()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            if (channel) h.store.seedChannelCreation()
            h.start()
            n.store.contacts[h.contact.key] = h.contact
            val handler = NotificationActionHandler(n.store, sender(h), n.service, n.rooms, n.sync)
            handler.configure({ true }, { "Me" })
            val receipt = NotificationActionReceipt()
            h.store.before = { operation ->
                if (operation == if (channel) "completeAcceptedQueuedChannelAttempt" else "updateMessageAck") {
                    reached.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
            val caller = launch(receipt) {
                if (channel) handler.handleChannelQuickReply(RADIO, 0u, "accepted")
                else handler.handleQuickReply(h.contact.key, "accepted")
            }
            runCurrent()
            assertTrue(reached.isCompleted, "Real firmware acceptance must precede the held persistence tail")
            assertEquals(NotificationActionReceipt.State.ACCEPTED, receipt.state)
            caller.cancel()
            release.complete(Unit)
            caller.join()
            assertTrue(caller.isCancelled)
            assertEquals(1, h.sends(if (channel) CommandCode.SEND_CHANNEL_MESSAGE else CommandCode.SEND_MESSAGE).size)
            assertNull(n.service.consumeDraft(h.contact.key))
            assertTrue(n.delivery.posted.isEmpty())
            assertEquals(1, n.sync.notifications)
            assertTrue(receipt.preventsReplay)
        } finally {
            release.complete(Unit)
            h.close()
        }
    }

    @Test fun completedSessionChildCannotLoseItsAcceptanceReceiptAtAwaitHandoff() = runTest {
        val h = Harness(this)
        try {
            h.start()
            val receipt = NotificationActionReceipt()
            lateinit var caller: Job
            h.store.before = { operation ->
                // Cancel the waiting caller before the session-owned child's final read returns.
                if (operation == "fetchMessage") caller.cancel()
            }
            caller = launch(receipt, start = CoroutineStart.LAZY) { h.service.sendDirectMessage("accepted", h.contact) }
            caller.start()
            caller.join()
            assertTrue(caller.isCancelled)
            assertEquals(NotificationActionReceipt.State.ACCEPTED, receipt.state)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
        } finally { h.close() }
    }

    @Test fun cancellationBeforeWireKeepsDraftAndRetryEligibility() = runTest {
        val h = Harness(this)
        val n = NotificationHarness(this, RADIO).authorize()
        try {
            h.start()
            n.store.contacts[h.contact.key] = h.contact
            val handler = NotificationActionHandler(n.store, sender(h), n.service, n.rooms, n.sync)
            handler.configure({ true }, { "Me" })
            h.store.before = { if (it == "saveMessage") awaitCancellation() }
            val receipt = NotificationActionReceipt()
            val caller = launch(receipt) { handler.handleQuickReply(h.contact.key, "keep") }
            runCurrent()
            caller.cancelAndJoin()
            assertFalse(receipt.preventsReplay)
            assertEquals("keep", n.service.consumeDraft(h.contact.key))
            assertEquals(1, n.delivery.posted.size)
            assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
        } finally { h.close() }
    }

    @Test fun cancellationWithUnobservedFirmwareResponseIsUncertainNotDefinitelyUnsent() = runTest {
        val h = Harness(this)
        val n = NotificationHarness(this, RADIO).authorize()
        try {
            h.start()
            n.store.contacts[h.contact.key] = h.contact
            h.transport.holdMessageReplies = true
            val handler = NotificationActionHandler(n.store, sender(h), n.service, n.rooms, n.sync)
            handler.configure({ true }, { "Me" })
            val receipt = NotificationActionReceipt()
            val caller = launch(receipt) { handler.handleQuickReply(h.contact.key, "unknown") }
            runCurrent()
            assertEquals(NotificationActionReceipt.State.IN_FLIGHT, receipt.state)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            caller.cancelAndJoin()
            assertEquals(NotificationActionReceipt.State.UNCERTAIN, receipt.state)
            assertTrue(receipt.preventsReplay)
            assertNull(n.service.consumeDraft(h.contact.key))
            assertTrue(n.delivery.posted.isEmpty())
            assertEquals(0, n.sync.notifications)
        } finally { h.close() }
    }

    @Test fun channelWithUnobservedFirmwareResponseIsAlsoUncertain() = runTest {
        val h = Harness(this)
        val n = NotificationHarness(this, RADIO).authorize()
        try {
            h.store.seedChannelCreation()
            h.start()
            h.transport.beforeReply = {
                if (it[0] == CommandCode.SEND_CHANNEL_MESSAGE.rawValue) awaitCancellation()
            }
            val handler = NotificationActionHandler(n.store, sender(h), n.service, n.rooms, n.sync)
            handler.configure({ true }, { "Me" })
            val receipt = NotificationActionReceipt()
            val caller = launch(receipt) { handler.handleChannelQuickReply(RADIO, 0u, "unknown") }
            runCurrent()
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size)
            caller.cancelAndJoin()
            assertEquals(NotificationActionReceipt.State.UNCERTAIN, receipt.state)
            assertTrue(n.delivery.posted.isEmpty())
            assertEquals(0, n.sync.notifications)
        } finally { h.close() }
    }

    @Test fun deviceRejectionStillReportsFailureAndRestoresDraft() = runTest {
        val h = Harness(this)
        val n = NotificationHarness(this, RADIO).authorize()
        try {
            h.start()
            n.store.contacts[h.contact.key] = h.contact
            h.transport.directError = 2u
            val handler = NotificationActionHandler(n.store, sender(h), n.service, n.rooms, n.sync)
            handler.configure({ true }, { "Me" })
            val receipt = NotificationActionReceipt()
            withContext(receipt) { handler.handleQuickReply(h.contact.key, "rejected") }
            assertEquals(NotificationActionReceipt.State.READY, receipt.state)
            assertEquals("rejected", n.service.consumeDraft(h.contact.key))
            assertEquals(1, n.delivery.posted.size)
        } finally { h.close() }
    }

    @Test fun nonCancellationUncertaintyIsTypedAndDoesNotRestoreAnUnsentDraft() = runTest {
        val n = NotificationHarness(this).authorize()
        val contact = n.contact()
        val sender = object : NotificationMessageSending {
            override suspend fun sendDirectMessage(text: String, contact: ContactDTO): MessageDTO {
                requireNotNull(currentCoroutineContext()[NotificationActionReceipt]).wireStarted()
                throw IllegalStateException("wire response lost")
            }
            override suspend fun sendChannelMessage(text: String, channelIndex: UByte, radioId: RadioId) =
                error("Unexpected channel send")
        }
        val handler = NotificationActionHandler(n.store, sender, n.service, n.rooms, n.sync)
        handler.configure({ true }, { "Me" })
        val receipt = NotificationActionReceipt()
        assertFailsWith<NotificationReplyOutcomeUncertain> {
            withContext(receipt) { handler.handleQuickReply(contact.key, "unknown") }
        }
        assertEquals(NotificationActionReceipt.State.UNCERTAIN, receipt.state)
        assertNull(n.service.consumeDraft(contact.key))
        assertTrue(n.delivery.posted.isEmpty())
    }

    @Test fun markReadFinishesStartedWritesAndRecordsCommitBeforeCancelledHandoff() = runTest {
        val n = NotificationHarness(this)
        val contact = EntityKey(RADIO_A, UUID.randomUUID())
        val message = UUID.randomUUID()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = object : PersistenceStoreProtocol by n.store {
            override suspend fun clearUnreadCount(key: EntityKey) {
                n.store.clearUnreadCount(key)
                reached.complete(Unit)
                release.await()
            }
        }
        val handler = NotificationActionHandler(store, n.sender, n.service, n.rooms, n.sync)
        val receipt = NotificationActionReceipt()
        val progress = NotificationReadReceipt()
        val caller = launch(receipt + progress) { handler.handleMarkAsRead(contact, message) }
        runCurrent()
        assertTrue(reached.isCompleted)
        assertEquals(NotificationActionReceipt.State.READY, receipt.state)
        caller.cancel()
        release.complete(Unit)
        caller.join()
        assertTrue(caller.isCancelled)
        assertEquals(NotificationActionReceipt.State.COMMITTED, receipt.state)
        assertEquals(NotificationReadReceipt.State.COMMITTED, progress.state)
        assertEquals(2, progress.completedWrites)
        assertEquals(2, n.store.writes.size)
        assertEquals(1, n.sync.notifications)
    }

    @Test fun partialMarkReadFailureIsNotACommittedAction() = runTest {
        val n = NotificationHarness(this)
        n.store.cancelling = "clearUnreadCount"
        val receipt = NotificationActionReceipt()
        val progress = NotificationReadReceipt()
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            withContext(receipt + progress) { n.handler.handleMarkAsRead(EntityKey(RADIO_A, UUID.randomUUID()), UUID.randomUUID()) }
        }

        assertFalse(receipt.preventsReplay)
        assertEquals(NotificationReadReceipt.State.UNCERTAIN, progress.state)
        assertEquals(1, progress.completedWrites)
        assertEquals(2, n.store.writes.size)
        assertEquals(0, n.sync.notifications)
    }

    @Test fun partialReadKeepsAnExplicitReceiptAfterContainedFailure() = runTest {
        val n = NotificationHarness(this)
        n.store.failing += "clearUnreadCount"
        val wire = NotificationActionReceipt()
        val progress = NotificationReadReceipt()
        val contact = EntityKey(RADIO_A, UUID.randomUUID())
        val message = UUID.randomUUID()
        withContext(wire + progress) { n.handler.handleMarkAsRead(contact, message) }
        assertEquals(NotificationActionReceipt.State.READY, wire.state)
        assertEquals(NotificationReadReceipt.State.UNCERTAIN, progress.state)
        assertEquals(1, progress.completedWrites)
        assertTrue(progress.preventsReplay)
        assertEquals(2, n.store.writes.size)
        assertEquals(0, n.sync.notifications)
        assertFailsWith<IllegalStateException> {
            withContext(wire + progress) { n.handler.handleMarkAsRead(contact, message) }
        }
        assertEquals(2, n.store.writes.size, "No repeated unread clear after an uncertain partial action")
    }

    @Test fun throwingFirstWriteIsUncertainEvenWithoutAReturnedWrite() = runTest {
        val n = NotificationHarness(this)
        n.store.failing += "markMessageAsRead"
        val progress = NotificationReadReceipt()
        withContext(progress) {
            n.handler.handleMarkAsRead(EntityKey(RADIO_A, UUID.randomUUID()), UUID.randomUUID())
        }
        assertEquals(NotificationReadReceipt.State.UNCERTAIN, progress.state)
        assertEquals(0, progress.completedWrites, "A thrown call may still have committed; zero is not unsent proof")
        assertTrue(progress.preventsReplay)
        assertEquals(1, n.store.writes.size)
    }

    @Test fun alreadyCancelledMarkReadDoesNotStartWrites() = runTest {
        val n = NotificationHarness(this)
        val receipt = NotificationActionReceipt()
        val caller = launch(receipt, start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            n.handler.handleMarkAsRead(EntityKey(RADIO_A, UUID.randomUUID()), UUID.randomUUID())
        }
        caller.join()
        assertTrue(caller.isCancelled)
        assertTrue(n.store.writes.isEmpty())
        assertFalse(receipt.preventsReplay)
    }
}
