// AndroidOnly: WP-215 Real-session acceptance and caller-result cancellation regression cases.
package com.meshcoreone.android.core.services.notifications

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationReplyOutcomeUncertain
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
    private fun sender(h: Harness) = object : NotificationMessageSending {
        override suspend fun sendDirectMessage(text: String, contact: ContactDTO): MessageDTO =
            h.service.sendDirectMessage(text, contact)
        override suspend fun sendChannelMessage(text: String, channelIndex: UByte, radioId: RadioId) {
            h.service.sendChannelMessage(text, channelIndex, radioId)
        }
    }

    @Test fun directAcceptanceSurvivesCancellationBeforeOwnedResultReturns() = acceptedReply(channel = false)
    @Test fun channelAcceptanceSurvivesCancellationBeforeOwnedResultReturns() = acceptedReply(channel = true)

    private fun acceptedReply(channel: Boolean) = runTest {
        val h = Harness(this)
        val n = NotificationHarness(this, RADIO).authorize()
        try {
            h.start()
            n.store.contacts[h.contact.key] = h.contact
            val handler = NotificationActionHandler(n.store, sender(h), n.service, n.rooms, n.sync)
            handler.configure({ true }, { "Me" })
            val receipt = NotificationActionReceipt()
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            h.store.before = { operation ->
                if (operation == if (channel) "updateMessageStatus" else "updateMessageAck") {
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
        } finally { h.close() }
    }

    @Test fun completedSessionChildCannotLoseItsAcceptanceReceiptAtAwaitHandoff() = runTest {
        val h = Harness(this)
        try {
            h.start()
            val receipt = NotificationActionReceipt()
            lateinit var caller: Job
            h.store.before = { operation ->
                // fetchSaved is the final read after postDMSent. Cancel the *waiting caller*,
                // not the session-owned child, before its completed result can resume await.
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
                val receipt = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[NotificationActionReceipt])
                receipt.wireStarted()
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
        val caller = launch(receipt) { handler.handleMarkAsRead(contact, message) }
        runCurrent()
        assertTrue(reached.isCompleted)
        assertEquals(NotificationActionReceipt.State.READY, receipt.state)
        caller.cancel()
        release.complete(Unit)
        caller.join()
        assertTrue(caller.isCancelled)
        assertEquals(NotificationActionReceipt.State.COMMITTED, receipt.state)
        assertEquals(2, n.store.writes.size)
        assertEquals(1, n.sync.notifications)
    }

    @Test fun partialMarkReadFailureIsNotACommittedAction() = runTest {
        val n = NotificationHarness(this)
        n.store.cancelling = "clearUnreadCount"
        val receipt = NotificationActionReceipt()
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            withContext(receipt) { n.handler.handleMarkAsRead(EntityKey(RADIO_A, UUID.randomUUID()), UUID.randomUUID()) }
        }
        assertFalse(receipt.preventsReplay)
        assertEquals(2, n.store.writes.size)
        assertEquals(0, n.sync.notifications)
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
