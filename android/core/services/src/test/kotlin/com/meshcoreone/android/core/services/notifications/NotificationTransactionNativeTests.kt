// AndroidOnly: WP-215 Native cases for action transactions, cold-start/stale-radio guards, reactions and Swift text semantics.
package com.meshcoreone.android.core.services.notifications

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.contracts.domain.UnreadCounts
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationReadReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationReplyOutcomeUncertain
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationPostAdmission
import com.meshcoreone.android.core.contracts.notifications.NotificationPostPermit
import com.meshcoreone.android.core.contracts.notifications.NotificationPostRejection
import com.meshcoreone.android.core.contracts.notifications.NotificationPostSelfDrainException
import com.meshcoreone.android.core.contracts.notifications.SnapshotRemovalOutcome
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

private val QUICK_REPLY_STEPS = listOf("send", "clearUnreadCount", "removeDelivered", "setBadgeCount", "notifyConversationsChanged")
private val CHANNEL_REPLY_STEPS =
    listOf("send", "clearChannelUnreadCount", "removeDelivered", "setBadgeCount", "notifyConversationsChanged")
private val MARK_READ_STEPS =
    listOf("markMessageAsRead", "clearUnreadCount", "removeDelivered", "setBadgeCount", "notifyConversationsChanged")

class NotificationTransactionNativeTests {
    private val messageID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")

    private fun NotificationHarness.ready(localNodeName: String? = "Me"): NotificationHarness =
        also { handler.configure(isConnectionReady = { true }, localNodeName = { localNodeName }) }

    @TestFactory
    fun notificationPermitOperationGate(): List<DynamicTest> = listOf(
        nativeCase("self-drain preflight tracks exact nested lease ancestry, not unrelated roots or open leases") {
            val root = NotificationPostPermit()
            val child = root.childPermit()
            val unrelated = NotificationPostPermit()
            val lease = child.tryAcquire()!!
            val unrelatedLease = unrelated.tryAcquire()!!

            root.requireOutsideOperation()
            lease.runSynchronous {
                assertFailsWith<NotificationPostSelfDrainException> { root.requireOutsideOperation() }
                assertFailsWith<NotificationPostSelfDrainException> { child.requireOutsideOperation() }
                unrelated.requireOutsideOperation()
                lease.runSynchronous {
                    assertFailsWith<NotificationPostSelfDrainException> { root.requireOutsideOperation() }
                }
                assertFailsWith<NotificationPostSelfDrainException> { root.requireOutsideOperation() }
            }
            val sentinel = IllegalStateException("synchronous action failure")
            assertSame(sentinel, assertFailsWith<IllegalStateException> {
                lease.runSynchronous { throw sentinel }
            })
            root.requireOutsideOperation()

            lease.close()
            unrelatedLease.close()
            assertFailsWith<IllegalStateException> { lease.runSynchronous { Unit } }
            root.requireOutsideOperation()
            root.close().join()
            unrelated.close().join()
        },
        nativeCase("safe Unconfined subscriber closes root and suspends for the exact producer drain") {
            val root = NotificationPostPermit()
            val lease = root.tryAcquire()!!
            val events = Channel<Unit>(Channel.UNLIMITED)
            val enteredSubscriber = CompletableDeferred<Unit>()
            val resumedSubscriber = CompletableDeferred<Unit>()
            val recipient = AtomicReference<String?>("current")
            val retirement = AtomicReference<NotificationPostPermit.RetirementCapture<String>?>(null)
            val subscriber = launch(Dispatchers.Unconfined) {
                events.receive()
                val captured = root.closeAndCapture(recipient)
                retirement.set(captured)
                enteredSubscriber.complete(Unit)
                val exactDrain = CompletableDeferred<Unit>()
                captured.drained.whenComplete { _, failure ->
                    if (failure == null) exactDrain.complete(Unit)
                    else exactDrain.completeExceptionally(failure)
                }
                exactDrain.await()
                root.requireOutsideOperation()
                resumedSubscriber.complete(Unit)
            }

            lease.runSynchronous {
                assertTrue(events.trySend(Unit).isSuccess)
            }
            enteredSubscriber.await()
            val captured = retirement.get()!!
            assertEquals("current", captured.recipient)
            assertFalse(captured.drained.isDone)
            lease.close()
            subscriber.join()
            assertTrue(resumedSubscriber.isCompleted)
            captured.drained.join()
        },
        nativeCase("blocking self-drain preflight refuses before waiting for its own lease") {
            val root = NotificationPostPermit()
            val lease = root.tryAcquire()!!
            val drained = root.close()

            assertFailsWith<NotificationPostSelfDrainException> {
                lease.runSynchronous {
                    root.requireOutsideOperation()
                    drained.get(100, TimeUnit.MILLISECONDS)
                }
            }
            assertFalse(drained.isDone)
            lease.close()
            drained.get(2, TimeUnit.SECONDS)
        },
        nativeCase("cross-thread lease close cannot complete drain before a synchronous action exits") {
            val root = NotificationPostPermit()
            val lease = root.tryAcquire()!!
            val enteredAction = CountDownLatch(1)
            val releaseAction = CountDownLatch(1)
            val actionFailure = AtomicReference<Throwable?>(null)
            val worker = Thread({
                try {
                    lease.runSynchronous {
                        enteredAction.countDown()
                        if (!releaseAction.await(2, TimeUnit.SECONDS)) {
                            throw IllegalStateException("timed out waiting to release permit action")
                        }
                        actionFailure.set(
                            runCatching { root.requireOutsideOperation() }.exceptionOrNull(),
                        )
                    }
                } catch (failure: Throwable) {
                    actionFailure.compareAndSet(null, failure)
                }
            }, "notification-permit-action")
            worker.start()

            val recipient = AtomicReference<String?>("current")
            assertTrue(enteredAction.await(2, TimeUnit.SECONDS))
            val retirement = root.closeAndCapture(recipient)
            assertEquals("current", retirement.recipient)
            lease.close()
            lease.close()
            try {
                assertFalse(retirement.drained.isDone)
            } finally {
                releaseAction.countDown()
            }
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertIs<NotificationPostSelfDrainException>(actionFailure.get())
            retirement.drained.get(2, TimeUnit.SECONDS)
            root.requireOutsideOperation()
        },
        nativeCase("root publication CAS linearizes with close-and-capture") {
            val root = NotificationPostPermit()
            val recipient = AtomicReference<String?>("initial")
            val start = CountDownLatch(1)
            val publication = AtomicReference<NotificationPostAdmission<Boolean>?>(null)
            val retirement = AtomicReference<NotificationPostPermit.RetirementCapture<String>?>(null)
            val threadFailure = AtomicReference<Throwable?>(null)
            val publisher = Thread({
                try {
                    start.await()
                    publication.set(root.compareAndSetRootStateWhileActive(recipient, "initial", "published"))
                } catch (failure: Throwable) {
                    threadFailure.set(failure)
                }
            }, "notification-permit-publisher")
            val closer = Thread({
                try {
                    start.await()
                    retirement.set(root.closeAndCapture(recipient))
                } catch (failure: Throwable) {
                    threadFailure.set(failure)
                }
            }, "notification-permit-closer")
            publisher.start()
            closer.start()
            start.countDown()
            publisher.join(2_000)
            closer.join(2_000)

            assertFalse(publisher.isAlive)
            assertFalse(closer.isAlive)
            assertNull(threadFailure.get())
            when (val result = publication.get()) {
                is NotificationPostAdmission.Admitted -> {
                    assertTrue(result.value)
                    assertEquals("published", retirement.get()?.recipient)
                }
                NotificationPostAdmission.Revoked ->
                    assertEquals("initial", retirement.get()?.recipient)
                null -> fail("publisher produced no root transition result")
            }
            assertNull(recipient.get())
            retirement.get()!!.drained.get(2, TimeUnit.SECONDS)
        },
    )

    @TestFactory
    fun directQuickReply(): List<DynamicTest> = listOf(
        nativeCase("quick reply when ready sends, clears unread, removes delivered, refreshes badge and conversations") {
            val h = NotificationHarness(this).authorize().ready()
            val contact = h.contact()
            h.delivery.show("dm", NotificationPayload.DirectMessage(contact.key, messageID))
            h.counts.counts = UnreadCounts(0, 2, 0)
            h.handler.handleQuickReply(contact.key, "on my way")
            assertEquals(listOf("on my way" to contact.id), h.sender.sends)
            assertEquals(QUICK_REPLY_STEPS, h.journal.events)
            assertEquals(listOf("clearUnreadCount" to contact.key), h.store.writes)
            assertEquals(emptyList(), h.delivery.shownIds)
            assertEquals(listOf(2L), h.delivery.badgeSets)
            assertEquals(1, h.sync.notifications)
            assertNull(h.service.consumeDraft(contact.key))
            assertEquals(emptyList(), h.delivery.posted)
        },
        nativeCase("cold start: a reply before configure() saves a draft and reports not sent without sending") {
            val h = NotificationHarness(this).authorize()
            val contact = h.contact(nickname = "Ally")
            h.handler.handleQuickReply(contact.key, "draft me")
            assertEquals(emptyList(), h.sender.sends)
            assertEquals("draft me", h.service.consumeDraft(contact.key))
            val failure = h.delivery.posted.single()
            assertEquals("Your reply to Ally couldn't be sent.", failure.body)
            assertEquals(NotificationPayload.QuickReplyFailed(contact.key), failure.payload)
            assertEquals(0, h.sync.notifications)
        },
        nativeCase("a reply while the connection is not ready behaves as Swift: draft plus failure notification") {
            val h = NotificationHarness(this).authorize()
            h.handler.configure(isConnectionReady = { false }, localNodeName = { "Me" })
            val contact = h.contact()
            h.handler.handleQuickReply(contact.key, "later")
            assertEquals(emptyList(), h.sender.sends)
            assertEquals("later", h.service.consumeDraft(contact.key))
            assertEquals(1, h.delivery.posted.size)
        },
        nativeCase("a failing readiness check counts as not ready") {
            val h = NotificationHarness(this).authorize()
            h.handler.configure(isConnectionReady = { throw IllegalStateException("state gone") }, localNodeName = { null })
            val contact = h.contact()
            h.handler.handleQuickReply(contact.key, "x")
            assertEquals(emptyList(), h.sender.sends)
            assertEquals("x", h.service.consumeDraft(contact.key))
        },
        nativeCase("cancellation after a known accepted send finishes bookkeeping without restoring a retry draft") {
            val h = NotificationHarness(this).authorize().ready()
            val contact = h.contact()
            h.delivery.show("dm", NotificationPayload.DirectMessage(contact.key, messageID))
            val sendGate = CompletableDeferred<Unit>()
            h.journal.gates["send"] = sendGate
            val receipt = NotificationActionReceipt()
            val caller = launch { withContext(receipt) { h.handler.handleQuickReply(contact.key, "sent") } }
            yieldUntil("accepted direct reply held before returning") { h.journal.events.contains("send") }
            caller.cancel()
            sendGate.complete(Unit)
            caller.cancelAndJoin()
            assertTrue(caller.isCancelled)
            assertEquals(NotificationActionReceipt.State.ACCEPTED, receipt.state)
            assertEquals(QUICK_REPLY_STEPS, h.journal.events)
            assertNull(h.service.consumeDraft(contact.key))
            assertEquals(emptyList(), h.delivery.posted)
            withContext(receipt) { h.handler.handleQuickReply(contact.key, "sent") }
            assertEquals(1, h.sender.sends.size)
        },
        nativeCase("cancellation before protocol dispatch keeps the text as a retryable draft") {
            val h = NotificationHarness(this).authorize().ready()
            h.sender.cancelBeforeWire = true
            val contact = h.contact()
            val receipt = NotificationActionReceipt()
            assertFailsWith<CancellationException> {
                withContext(receipt) { h.handler.handleQuickReply(contact.key, "keep me") }
            }
            assertEquals(NotificationActionReceipt.State.READY, receipt.state)
            assertEquals(emptyList(), h.sender.sends)
            assertEquals("keep me", h.service.consumeDraft(contact.key))
            assertEquals(NotificationPayload.QuickReplyFailed(contact.key), h.delivery.posted.single().payload)
            assertEquals(0, h.sync.notifications)
        },
        nativeCase("cancellation after protocol dispatch starts preserves an uncertain outcome without reporting unsent") {
            val h = NotificationHarness(this).authorize().ready()
            h.sender.cancel = true
            val contact = h.contact()
            val receipt = NotificationActionReceipt()
            assertFailsWith<CancellationException> {
                withContext(receipt) { h.handler.handleQuickReply(contact.key, "unknown") }
            }
            assertEquals(NotificationActionReceipt.State.UNCERTAIN, receipt.state)
            assertNull(h.service.consumeDraft(contact.key))
            assertEquals(emptyList(), h.delivery.posted)
            assertEquals(0, h.sync.notifications)
        },
        nativeCase("a failed post-dispatch result remains uncertain and preserves its original cause") {
            val h = NotificationHarness(this).authorize().ready()
            val failure = IllegalStateException("dispatch result lost")
            h.sender.uncertainFailure = failure
            val contact = h.contact()
            val receipt = NotificationActionReceipt()
            val thrown = assertFailsWith<NotificationReplyOutcomeUncertain> {
                withContext(receipt) { h.handler.handleQuickReply(contact.key, "unknown") }
            }
            assertSame(failure, thrown.cause)
            assertEquals(NotificationActionReceipt.State.UNCERTAIN, receipt.state)
            assertNull(h.service.consumeDraft(contact.key))
            assertEquals(emptyList(), h.delivery.posted)
        },
        nativeCase("a failed send saves a draft and reports not sent") {
            val h = NotificationHarness(this).authorize().ready()
            h.sender.fail = true
            val contact = h.contact()
            h.handler.handleQuickReply(contact.key, "retry")
            assertEquals(1, h.sender.sends.size)
            assertEquals("retry", h.service.consumeDraft(contact.key))
            assertEquals(1, h.delivery.posted.size)
            assertEquals(emptyList(), h.store.writes)
        },
        nativeCase("an unknown or unreadable contact is ignored") {
            val h = NotificationHarness(this).authorize().ready()
            val missing = EntityKey(RADIO_A, UUID.randomUUID())
            h.handler.handleQuickReply(missing, "x")
            h.store.failing += "fetchContact"
            h.handler.handleQuickReply(h.contact().key, "x")
            assertEquals(emptyList(), h.sender.sends)
            assertEquals(emptyList(), h.delivery.posted)
        },
        nativeCase("stale radio: a reply for another radio's contact is never sent from this radio") {
            val h = NotificationHarness(this, radioId = RADIO_B).authorize().ready()
            val contact = h.contact(radioId = RADIO_A)
            h.handler.handleQuickReply(contact.key, "wrong radio")
            assertEquals(emptyList(), h.sender.sends)
            assertEquals(emptyList(), h.store.writes)
            assertEquals("wrong radio", h.service.consumeDraft(contact.key))
            // The failure notification keeps the contact's own radio, not the session's.
            assertEquals(NotificationPayload.QuickReplyFailed(contact.key), h.delivery.posted.single().payload)
        },
        nativeCase("follow-up failures after a sent reply are contained and never report not sent") {
            val h = NotificationHarness(this).authorize().ready()
            h.store.failing += "clearUnreadCount"
            h.sync.fail = true
            h.delivery.failing += "deliveredNotifications"
            val contact = h.contact()
            h.handler.handleQuickReply(contact.key, "sent")
            assertEquals(1, h.sender.sends.size)
            assertEquals(emptyList(), h.delivery.posted)
            assertNull(h.service.consumeDraft(contact.key))
            assertEquals(1, h.delivery.badgeSets.size)
        },
        nativeCase("badge failure after an accepted reply is contained and never restores a retry draft") {
            val h = NotificationHarness(this).authorize().ready()
            h.counts.fail = true
            val contact = h.contact()

            h.handler.handleQuickReply(contact.key, "already sent")

            assertEquals(1, h.sender.sends.size)
            assertNull(h.service.consumeDraft(contact.key))
            assertEquals(emptyList(), h.delivery.posted)
            assertEquals(1, h.sync.notifications)
        },
        nativeCase("notification permission denial never blocks a quick reply") {
            val h = NotificationHarness(this).ready()
            h.delivery.postResult = NotificationPostResult.PermissionDenied
            val contact = h.contact()
            h.handler.handleQuickReply(contact.key, "still sent")
            assertEquals(1, h.sender.sends.size)
            assertEquals(listOf("clearUnreadCount" to contact.key), h.store.writes)
            assertEquals(1, h.sync.notifications)
        },
        nativeCase("cancellation from a collaborator propagates instead of being swallowed") {
            val h = NotificationHarness(this).authorize().ready()
            h.store.cancelling = "fetchContact"
            assertFailsWith<CancellationException> { h.handler.handleQuickReply(h.contact().key, "x") }
            assertEquals(emptyList(), h.delivery.posted)
        },
    )

    @TestFactory
    fun channelQuickReply(): List<DynamicTest> = listOf(
        nativeCase("channel reply when ready sends, clears channel unread, removes delivered, refreshes") {
            val h = NotificationHarness(this).authorize().ready()
            h.delivery.show("c", NotificationPayload.ChannelMessage(RADIO_A, 1u, messageID))
            h.handler.handleChannelQuickReply(RADIO_A, 1u, "ack")
            assertEquals(listOf("ack" to (RADIO_A to 1.toUByte())), h.sender.sends)
            assertEquals(CHANNEL_REPLY_STEPS, h.journal.events)
            assertEquals(listOf("clearChannelUnreadCount" to (RADIO_A to 1.toUByte())), h.store.writes)
            assertEquals(emptyList(), h.delivery.shownIds)
            assertEquals(1, h.delivery.badgeSets.size)
            assertEquals(1, h.sync.notifications)
        },
        nativeCase("a channel reply accepted before caller cancellation finishes bookkeeping without a failure post") {
            val h = NotificationHarness(this).authorize().ready()
            h.delivery.show("c", NotificationPayload.ChannelMessage(RADIO_A, 1u, messageID))
            val sendGate = CompletableDeferred<Unit>()
            h.journal.gates["send"] = sendGate
            val receipt = NotificationActionReceipt()
            val caller = launch { withContext(receipt) { h.handler.handleChannelQuickReply(RADIO_A, 1u, "ack") } }
            yieldUntil("accepted channel reply held before returning") { h.journal.events.contains("send") }
            caller.cancel()
            sendGate.complete(Unit)
            caller.cancelAndJoin()
            assertTrue(caller.isCancelled)
            assertEquals(NotificationActionReceipt.State.ACCEPTED, receipt.state)
            assertEquals(CHANNEL_REPLY_STEPS, h.journal.events)
            assertEquals(emptyList(), h.delivery.posted)
        },
        nativeCase("cancellation during a channel protocol dispatch stays uncertain and does not report unsent") {
            val h = NotificationHarness(this).authorize().ready()
            h.sender.cancel = true
            val receipt = NotificationActionReceipt()
            assertFailsWith<CancellationException> {
                withContext(receipt) { h.handler.handleChannelQuickReply(RADIO_A, 1u, "x") }
            }
            assertEquals(NotificationActionReceipt.State.UNCERTAIN, receipt.state)
            assertEquals(emptyList(), h.delivery.posted)
            assertEquals(0, h.sync.notifications)
        },
        nativeCase("channel reply not ready or failing posts a failure with the display-name fallback and no draft") {
            val h = NotificationHarness(this).authorize()
            h.store.channels[RADIO_A to 4.toUByte()] = ChannelDTO(radioId = RADIO_A, index = 4u, name = "Ops")
            h.handler.handleChannelQuickReply(RADIO_A, 4u, "x")
            h.ready()
            h.sender.fail = true
            h.handler.handleChannelQuickReply(RADIO_A, 5u, "y")
            assertEquals(listOf("Your reply to Ops couldn't be sent.", "Your reply to Channel 5 couldn't be sent."), h.delivery.posted.map { it.body })
            assertEquals(NotificationPayload.ChannelQuickReplyFailed(RADIO_A, 5u), h.delivery.posted[1].payload)
            assertEquals(1, h.sender.sends.size)
            assertEquals(emptyList(), h.store.writes)
            assertEquals(0, h.sync.notifications)
        },
        nativeCase("stale radio: a channel reply for another radio is reported not sent and never transmitted") {
            val h = NotificationHarness(this, radioId = RADIO_B).authorize().ready()
            h.handler.handleChannelQuickReply(RADIO_A, 0u, "wrong radio")
            assertEquals(emptyList(), h.sender.sends)
            assertEquals(emptyList(), h.store.writes)
            assertEquals(NotificationPayload.ChannelQuickReplyFailed(RADIO_A, 0u), h.delivery.posted.single().payload)
        },
    )

    @TestFactory
    fun markAsRead(): List<DynamicTest> = listOf(
        nativeCase("direct mark-read writes the message and contact of the action's radio, then refreshes") {
            val h = NotificationHarness(this)
            val contact = EntityKey(RADIO_A, UUID.randomUUID())
            h.delivery.show("AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE", NotificationPayload.DirectMessage(contact, messageID))
            h.handler.handleMarkAsRead(contact, messageID)
            assertEquals(listOf("markMessageAsRead" to EntityKey(RADIO_A, messageID), "clearUnreadCount" to contact), h.store.writes)
            assertEquals(MARK_READ_STEPS, h.journal.events)
            assertEquals(emptyList(), h.delivery.shownIds)
            assertEquals(1, h.delivery.badgeSets.size)
            assertEquals(1, h.sync.notifications)
        },
        nativeCase("mark-read cancellation before its first write leaves the action retryable") {
            val h = NotificationHarness(this)
            val contact = EntityKey(RADIO_A, UUID.randomUUID())
            val actionReceipt = NotificationActionReceipt()
            val readReceipt = NotificationReadReceipt()
            val caller = launch(actionReceipt + readReceipt) {
                currentCoroutineContext().cancel()
                h.handler.handleMarkAsRead(contact, messageID)
            }

            caller.join()

            assertTrue(caller.isCancelled)
            assertEquals(NotificationActionReceipt.State.READY, actionReceipt.state)
            assertEquals(NotificationReadReceipt.State.READY, readReceipt.state)
            assertEquals(emptyList(), h.store.writes)
            assertEquals(emptyList(), h.delivery.removals)
        },
        nativeCase("caller cancellation during a held mark-read write completes the commit and propagates afterward") {
            val h = NotificationHarness(this)
            val contact = EntityKey(RADIO_A, UUID.randomUUID())
            h.delivery.show("AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE", NotificationPayload.DirectMessage(contact, messageID))
            val actionReceipt = NotificationActionReceipt()
            val readReceipt = NotificationReadReceipt()
            val writeGate = CompletableDeferred<Unit>()
            h.store.gates["clearUnreadCount"] = writeGate
            val caller = async {
                withContext(actionReceipt + readReceipt) { h.handler.handleMarkAsRead(contact, messageID) }
            }
            yieldUntil("clearUnreadCount write held") { h.store.writes.any { it.first == "clearUnreadCount" } }
            caller.cancel()
            writeGate.complete(Unit)
            caller.cancelAndJoin()
            assertTrue(caller.isCancelled)
            assertEquals(NotificationActionReceipt.State.COMMITTED, actionReceipt.state)
            assertEquals(NotificationReadReceipt.State.COMMITTED, readReceipt.state)
            assertEquals(2, readReceipt.completedWrites)
            assertEquals(MARK_READ_STEPS, h.journal.events)
            val room = NotificationHarness(this)
            room.delivery.show("AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE", NotificationPayload.RoomMessage("R", contact, messageID))
            val roomActionReceipt = NotificationActionReceipt()
            val roomReadReceipt = NotificationReadReceipt()
            val roomGate = CompletableDeferred<Unit>()
            room.journal.gates["roomMarkAsRead"] = roomGate
            val roomCaller = async {
                withContext(roomActionReceipt + roomReadReceipt) {
                    room.handler.handleRoomMarkAsRead(contact, messageID)
                }
            }
            yieldUntil("room mark-read write held") { room.journal.events.contains("roomMarkAsRead") }
            roomCaller.cancel()
            roomGate.complete(Unit)
            roomCaller.cancelAndJoin()
            assertEquals(NotificationActionReceipt.State.COMMITTED, roomActionReceipt.state)
            assertEquals(NotificationReadReceipt.State.COMMITTED, roomReadReceipt.state)
            assertEquals(listOf("roomMarkAsRead", "removeDelivered", "setBadgeCount", "notifyConversationsChanged"), room.journal.events)
        },
        nativeCase("a partial mark-read failure is uncertain and cannot be replayed") {
            val h = NotificationHarness(this)
            val contact = EntityKey(RADIO_A, UUID.randomUUID())
            val actionReceipt = NotificationActionReceipt()
            val readReceipt = NotificationReadReceipt()
            h.store.failing += "clearUnreadCount"
            val failure = IllegalStateException("unread clear failed")
            h.store.failures["clearUnreadCount"] = failure

            withContext(actionReceipt + readReceipt) { h.handler.handleMarkAsRead(contact, messageID) }

            assertEquals(NotificationActionReceipt.State.UNCERTAIN, actionReceipt.state)
            assertEquals(NotificationReadReceipt.State.UNCERTAIN, readReceipt.state)
            assertEquals(1, readReceipt.completedWrites)
            assertEquals(emptyList(), h.delivery.removals)
            assertEquals(0, h.sync.notifications)
            val writeCount = h.store.writes.size
            assertFailsWith<IllegalStateException> {
                withContext(actionReceipt + readReceipt) { h.handler.handleMarkAsRead(contact, messageID) }
            }
            assertEquals(writeCount, h.store.writes.size)
        },
        nativeCase("stale radio: mark-read from another radio writes only that radio's rows") {
            val h = NotificationHarness(this, radioId = RADIO_B)
            val contact = EntityKey(RADIO_A, UUID.randomUUID())
            h.handler.handleMarkAsRead(contact, messageID)
            h.handler.handleChannelMarkAsRead(RADIO_A, 3u, messageID)
            h.handler.handleRoomMarkAsRead(EntityKey(RADIO_A, contact.id), messageID)
            val radios = h.store.writes.map { (_, argument) ->
                when (argument) {
                    is EntityKey -> argument.radioId
                    is Pair<*, *> -> argument.first as RadioId
                    else -> error("unexpected $argument")
                }
            } + h.rooms.marked.map { it.radioId }
            assertEquals(5, radios.size)
            assertTrue(radios.all { it == RADIO_A })
        },
        nativeCase("a failing mark-read write stops the transaction silently, as Swift's do/catch") {
            val h = NotificationHarness(this)
            h.store.failing += "markMessageAsRead"
            h.handler.handleMarkAsRead(EntityKey(RADIO_A, UUID.randomUUID()), messageID)
            h.handler.handleChannelMarkAsRead(RADIO_A, 1u, messageID)
            assertEquals(listOf("markMessageAsRead", "markMessageAsRead"), h.store.writes.map { it.first })
            assertEquals(emptyList(), h.delivery.removals)
            assertEquals(0, h.sync.notifications)
        },
        nativeCase("channel mark-read writes message and channel unread, then refreshes") {
            val h = NotificationHarness(this)
            h.handler.handleChannelMarkAsRead(RADIO_A, 6u, messageID)
            assertEquals(
                listOf("markMessageAsRead" to EntityKey(RADIO_A, messageID), "clearChannelUnreadCount" to (RADIO_A to 6.toUByte())),
                h.store.writes,
            )
            assertEquals(1, h.delivery.removals.size)
            assertEquals(1, h.sync.notifications)
            assertEquals(
                listOf("markMessageAsRead", "clearChannelUnreadCount", "removeDelivered", "setBadgeCount", "notifyConversationsChanged"),
                h.journal.events,
            )
        },
        nativeCase("room mark-read resets the session through the room service; a failure stops the refresh") {
            val h = NotificationHarness(this)
            val session = EntityKey(RADIO_A, UUID.randomUUID())
            h.handler.handleRoomMarkAsRead(session, messageID)
            assertEquals(listOf(session), h.rooms.marked)
            assertEquals(1, h.sync.notifications)
            h.rooms.fail = true
            h.handler.handleRoomMarkAsRead(session, messageID)
            assertEquals(1, h.sync.notifications)
            assertEquals(1, h.delivery.removals.size)
        },
    )

    private fun NotificationHarness.outgoing(text: String = "Hello there", contactID: UUID? = null, channelIndex: UByte? = null,
                                             direction: MessageDirection = MessageDirection.OUTGOING): MessageDTO {
        val message = MessageDTO(messageID, RADIO_A, contactID, channelIndex, text, 1u, direction = direction)
        store.messages[EntityKey(RADIO_A, messageID)] = message
        return message
    }

    private fun NotificationHarness.react(sender: String, emoji: String = "👍") {
        store.reactions[EntityKey(RADIO_A, messageID)] = listOf(
            ReactionDTO(messageID = messageID, emoji = emoji, senderName = sender, messageHash = "h", rawText = "r", radioId = RADIO_A),
        )
    }

    @TestFactory
    fun reactions(): List<DynamicTest> = listOf(
        nativeCase("reaction before configure() is suppressed without reading the store") {
            val h = NotificationHarness(this).authorize()
            h.handler.handleReactionNotification(messageID)
            assertEquals(emptyList(), h.store.calls)
            assertEquals(emptyList(), h.delivery.posted)
        },
        nativeCase("reaction to an outgoing DM posts the English body with the truncated preview") {
            val h = NotificationHarness(this).authorize().ready()
            val contact = h.contact()
            h.outgoing(text = "z".repeat(60), contactID = contact.id)
            h.react("Bob")
            h.handler.handleReactionNotification(messageID)
            val request = h.delivery.posted.single()
            assertEquals("Bob", request.title)
            assertEquals("Reacted 👍 to your message: \"${"z".repeat(47)}...\"", request.body)
            assertEquals(NotificationPayload.Reaction(messageID, contact.key, null, null), request.payload)
            assertEquals(1L, h.store.calls.single { it.first == "fetchReactions" }.let { (it.second as Pair<*, *>).second })
        },
        nativeCase("channel reaction carries the message radio and uses the provider body") {
            val h = NotificationHarness(this).authorize().ready(localNodeName = null)
            h.service.setStringProvider(MockStringProvider())
            h.store.channels[RADIO_A to 2.toUByte()] = ChannelDTO(radioId = RADIO_A, index = 2u, name = "Ops")
            h.outgoing(channelIndex = 2u)
            h.react("Me")
            h.handler.handleReactionNotification(messageID)
            val request = h.delivery.posted.single()
            assertEquals("Mock reacted 👍 to Hello there", request.body)
            assertEquals(NotificationPayload.Reaction(messageID, null, 2u, RADIO_A), request.payload)
        },
        nativeCase("incoming messages, missing reactions, self reactions and muted conversations post nothing") {
            val h = NotificationHarness(this).authorize().ready(localNodeName = "Zo\u00EB")
            h.outgoing(direction = MessageDirection.INCOMING)
            h.react("Bob")
            h.handler.handleReactionNotification(messageID)
            h.outgoing()
            h.store.reactions.clear()
            h.handler.handleReactionNotification(messageID)
            // Canonically equivalent (decomposed) spelling of the local node name is a self reaction in Swift.
            h.react("Zoë")
            h.handler.handleReactionNotification(messageID)
            h.react("Bob")
            h.outgoing(contactID = h.contact(muted = true).id)
            h.handler.handleReactionNotification(messageID)
            h.store.channels[RADIO_A to 1.toUByte()] =
                ChannelDTO(radioId = RADIO_A, index = 1u, name = "Q", notificationLevel = NotificationLevel.MUTED)
            h.outgoing(channelIndex = 1u)
            h.handler.handleReactionNotification(messageID)
            assertEquals(emptyList(), h.delivery.posted)
        },
        nativeCase("a failing node-name closure suppresses; unreadable mute state counts as unmuted") {
            val h = NotificationHarness(this).authorize()
            h.handler.configure(isConnectionReady = { true }, localNodeName = { throw IllegalStateException("device gone") })
            h.outgoing(contactID = UUID.randomUUID())
            h.react("Bob")
            h.handler.handleReactionNotification(messageID)
            assertEquals(emptyList(), h.delivery.posted)
            h.ready()
            h.store.failing += "fetchContact"
            h.handler.handleReactionNotification(messageID)
            assertEquals(1, h.delivery.posted.size)
        },
    )

    @TestFactory
    fun capturedReactionOutcomes(): List<DynamicTest> = listOf(
        nativeCase("captured handler posts through the original permit and returns the actual post id") {
            val h = NotificationHarness(this).authorize().ready()
            val contact = h.contact()
            h.outgoing(contactID = contact.id)
            h.react("Bob")
            val permit = NotificationPostPermit().childPermit()

            val posted = assertIs<ReactionNotificationOutcome.Posted>(
                h.handler.handleReactionNotification(messageID, permit),
            )
            assertEquals(h.delivery.posted.single().id, posted.id)
        },
        nativeCase("missing handler wiring and failed source lookups remain unresolved") {
            val h = NotificationHarness(this).authorize()
            val permit = NotificationPostPermit().childPermit()
            val beforeConfiguration = assertIs<ReactionNotificationOutcome.Unresolved>(
                h.handler.handleReactionNotification(messageID, permit),
            )
            assertEquals(
                ReactionNotificationOutcome.UnresolvedReason.HANDLER_NOT_CONFIGURED,
                beforeConfiguration.reason,
            )
            assertEquals(emptyList(), h.store.calls)

            h.ready()
            h.store.failing += "fetchMessage"
            val lookupFailure = IllegalStateException("sentinel reaction lookup failure")
            h.store.failures["fetchMessage"] = lookupFailure
            val failedLookup = assertIs<ReactionNotificationOutcome.Unresolved>(
                h.handler.handleReactionNotification(messageID, permit),
            )
            assertEquals(ReactionNotificationOutcome.UnresolvedReason.LOOKUP_FAILED, failedLookup.reason)
            assertSame(lookupFailure, failedLookup.cause)
            assertTrue(h.delivery.posted.isEmpty())
        },
        nativeCase("positive self-reaction suppression is distinct from unresolved lookup state") {
            val h = NotificationHarness(this).authorize().ready(localNodeName = "Me")
            h.outgoing(contactID = h.contact().id)
            h.react("Me")

            val suppressed = assertIs<ReactionNotificationOutcome.Suppressed>(
                h.handler.handleReactionNotification(messageID, NotificationPostPermit()),
            )
            assertEquals(ReactionNotificationOutcome.SuppressionReason.SELF_REACTION, suppressed.reason)
            assertTrue(h.delivery.posted.isEmpty())
        },
        nativeCase("post exceptions are uncertain after the adapter begins the attempt") {
            val h = NotificationHarness(this).authorize().ready()
            h.outgoing(contactID = h.contact().id)
            h.react("Bob")
            h.delivery.failing += "post"

            val uncertain = assertIs<ReactionNotificationOutcome.Uncertain>(
                h.handler.handleReactionNotification(messageID, NotificationPostPermit()),
            )
            assertEquals("delivery post failed", uncertain.failure.message)
            assertEquals(1, h.delivery.posted.size)
        },
        nativeCase("retired owner rejects before reading message state or posting") {
            val h = NotificationHarness(this).authorize().ready()
            val root = NotificationPostPermit()
            val permit = root.childPermit()
            root.close()

            val rejected = assertIs<ReactionNotificationOutcome.RejectedBeforeDispatch>(
                h.handler.handleReactionNotification(messageID, permit),
            )
            assertEquals(NotificationPostRejection.OWNER_RETIRED, rejected.reason)
            assertTrue(h.store.calls.isEmpty())
            assertTrue(h.delivery.posted.isEmpty())
        },
        nativeCase("captured reaction lookup cancellation propagates without posting") {
            val h = NotificationHarness(this).authorize().ready()
            h.store.cancelling = "fetchMessage"

            assertFailsWith<CancellationException> {
                h.handler.handleReactionNotification(messageID, NotificationPostPermit())
            }
            assertTrue(h.delivery.posted.isEmpty())
        },
        nativeCase("captured reaction post cancellation propagates without fabricating a terminal outcome") {
            val h = NotificationHarness(this).authorize().ready()
            h.outgoing(contactID = h.contact().id)
            h.react("Bob")
            h.delivery.cancelling = "post"

            assertFailsWith<CancellationException> {
                h.handler.handleReactionNotification(messageID, NotificationPostPermit())
            }

            assertEquals(1, h.delivery.posted.size)
        },
    )

    @TestFactory
    fun capturedCleanupOutcomes(): List<DynamicTest> = listOf(
        nativeCase("legacy badge update preserves prior state and propagates the original unread failure") {
            val h = NotificationHarness(this).authorize()
            h.counts.counts = UnreadCounts(2, 3, 4)
            h.service.updateBadgeCount()
            val priorBadge = h.service.badgeCount
            val priorDelivery = h.delivery.badgeSets
            assertEquals(9L, priorBadge)
            assertEquals(listOf(9L), priorDelivery)

            h.counts.counts = UnreadCounts(50, 60, 70)
            val sentinel = IllegalStateException("sentinel unread query failure")
            h.counts.failure = sentinel
            h.counts.fail = true
            val failure = assertFailsWith<IllegalStateException> { h.service.updateBadgeCount() }

            assertSame(sentinel, failure)
            assertEquals(priorBadge, h.service.badgeCount)
            assertEquals(priorDelivery, h.delivery.badgeSets)
        },
        nativeCase("legacy badge update uses valid unread counts") {
            val h = NotificationHarness(this).authorize()
            h.counts.counts = UnreadCounts(4, 5, 6)

            h.service.updateBadgeCount()

            assertEquals(15L, h.service.badgeCount)
            assertEquals(listOf(15L), h.delivery.badgeSets)
        },
        nativeCase("badge delivery failure is contained and does not imply the computed count was delivered") {
            val h = NotificationHarness(this).authorize()
            h.counts.counts = UnreadCounts(4, 5, 6)
            h.delivery.failing += "setBadgeCount"

            h.service.updateBadgeCount()

            assertEquals(15L, h.service.badgeCount)
            assertTrue(h.delivery.badgeSets.isEmpty())
        },
        nativeCase("a failed badge refresh after posting does not turn the post into an operation failure") {
            val h = NotificationHarness(this).authorize()
            h.counts.fail = true

            h.service.postDirectMessageNotification(
                from = "Alice",
                contactID = UUID.randomUUID(),
                messageText = "hello",
                messageID = messageID,
            )

            assertEquals(1, h.delivery.posted.size)
            assertEquals(0L, h.service.badgeCount)
            assertTrue(h.delivery.badgeSets.isEmpty())
        },
        nativeCase("disabled badge explicitly resets to zero without querying unread counts") {
            val h = NotificationHarness(this).authorize()
            h.counts.counts = UnreadCounts(2, 3, 4)
            h.service.updateBadgeCount()
            h.preferences.set { it.copy(badgeEnabled = false) }
            h.counts.fail = true
            val priorCountCalls = h.counts.calls

            h.service.updateBadgeCount()

            assertEquals(0L, h.service.badgeCount)
            assertEquals(listOf(9L, 0L), h.delivery.badgeSets)
            assertEquals(priorCountCalls, h.counts.calls)
        },
        nativeCase("legacy badge cancellation from unread counting propagates without changing prior state") {
            val h = NotificationHarness(this).authorize()
            h.counts.counts = UnreadCounts(2, 3, 4)
            h.service.updateBadgeCount()
            val priorBadge = h.service.badgeCount
            val priorDelivery = h.delivery.badgeSets
            h.counts.cancel = true

            assertFailsWith<CancellationException> { h.service.updateBadgeCount() }

            assertEquals(priorBadge, h.service.badgeCount)
            assertEquals(priorDelivery, h.delivery.badgeSets)
        },
        nativeCase("badge retirement during debounce prevents the final mutation") {
            val clock = VirtualClock()
            val h = NotificationHarness(this, clock = clock).authorize()
            h.counts.counts = UnreadCounts(2, 3, 4)
            val root = NotificationPostPermit()
            val result = async { h.service.updateBadgeCount(root.childPermit()) }
            yieldUntil("badge debounce started") { clock.sleeperCount == 1 }

            root.close()
            clock.wakeAll()
            assertEquals(NotificationPostAdmission.Revoked, result.await())
            assertTrue(h.delivery.badgeSets.isEmpty())
        },
        nativeCase("badge caller cancellation during debounce propagates before the final mutation") {
            val clock = VirtualClock()
            val h = NotificationHarness(this, clock = clock).authorize()
            val update = async { h.service.updateBadgeCount(NotificationPostPermit()) }
            yieldUntil("badge debounce started") { clock.sleeperCount == 1 }

            update.cancelAndJoin()
            assertTrue(update.isCancelled)
            assertTrue(h.delivery.badgeSets.isEmpty())
        },
        nativeCase("superseding a pending debounce returns normally to the earlier caller") {
            val clock = VirtualClock()
            val h = NotificationHarness(this, clock = clock).authorize()
            h.counts.counts = UnreadCounts(2, 3, 4)
            val first = async { h.service.updateBadgeCount() }
            yieldUntil("first badge debounce started") { clock.sleeperCount == 1 }
            val second = async { h.service.updateBadgeCount() }
            yieldUntil("replacement badge debounce started") { clock.sleeps.size == 2 && clock.sleeperCount == 1 }

            first.await()
            clock.wakeAll()
            second.await()

            assertEquals(listOf(9L), h.delivery.badgeSets)
        },
        nativeCase("superseding an already-started update waits for it and then runs the replacement") {
            val clock = VirtualClock()
            val h = NotificationHarness(this, clock = clock).authorize()
            val firstRead = CompletableDeferred<Unit>()
            h.counts.gates += firstRead
            h.counts.counts = UnreadCounts(1, 2, 3)
            val first = async { h.service.updateBadgeCount() }
            yieldUntil("first badge debounce started") { clock.sleeperCount == 1 }
            clock.wakeAll()
            yieldUntil("first unread query started") { h.counts.calls == 1 }

            h.counts.counts = UnreadCounts(10, 20, 30)
            val second = async { h.service.updateBadgeCount() }
            yieldUntil("replacement badge debounce started") { clock.sleeps.size == 2 && clock.sleeperCount == 1 }
            clock.wakeAll()
            firstRead.complete(Unit)

            first.await()
            second.await()

            assertEquals(listOf(6L, 60L), h.delivery.badgeSets)
        },
        nativeCase("snapshot cleanup retirement during the delivered query prevents cancellation") {
            val h = NotificationHarness(this).authorize()
            val contact = h.contact()
            h.delivery.show("dm", NotificationPayload.DirectMessage(contact.key, messageID))
            val queryGate = CompletableDeferred<Unit>()
            h.delivery.deliveredGate = queryGate
            val root = NotificationPostPermit()
            val result = async {
                h.service.removeDeliveredNotifications(setOf(contact.id), root.childPermit())
            }
            yieldUntil("delivered-notification query started") { h.delivery.listCalls == 1 }

            root.close()
            queryGate.complete(Unit)
            assertEquals(NotificationPostAdmission.Revoked, result.await())
            assertTrue(h.delivery.snapshotRemovalRequests.isEmpty())
            assertEquals(listOf(NotificationId("dm")), h.delivery.shownIds)
        },
        nativeCase("snapshot cleanup retirement during an empty query is not reported as admitted") {
            val h = NotificationHarness(this).authorize()
            val queryGate = CompletableDeferred<Unit>()
            h.delivery.deliveredGate = queryGate
            val root = NotificationPostPermit()
            val result = async {
                h.service.removeDeliveredNotifications(setOf(UUID.randomUUID()), root.childPermit())
            }
            yieldUntil("empty delivered-notification query started") { h.delivery.listCalls == 1 }

            root.close()
            queryGate.complete(Unit)
            assertEquals(NotificationPostAdmission.Revoked, result.await())
            assertTrue(h.delivery.snapshotRemovalRequests.isEmpty())
        },
        nativeCase("unknown post identity remains an explicit unresolved snapshot outcome") {
            val h = NotificationHarness(this).authorize()
            val contact = h.contact()
            h.delivery.showWithoutIdentity("dm", NotificationPayload.DirectMessage(contact.key, messageID))

            val admission = assertIs<NotificationPostAdmission.Admitted<*>>(
                h.service.removeDeliveredNotifications(setOf(contact.id), NotificationPostPermit()),
            )
            val outcomes = assertIs<SnapshotList<*>>(admission.value)
            assertEquals(
                listOf(SnapshotRemovalOutcome.UNKNOWN_IDENTITY),
                outcomes.toList(),
            )
            assertEquals(listOf(NotificationId("dm")), h.delivery.shownIds)
        },
        nativeCase("permit-aware badge query failure preserves prior state and propagates the original error") {
            val h = NotificationHarness(this).authorize()
            h.counts.counts = UnreadCounts(2, 3, 4)
            h.service.updateBadgeCount()
            val priorBadge = h.service.badgeCount
            val priorDelivery = h.delivery.badgeSets
            val sentinel = IllegalStateException("sentinel permit unread failure")
            h.counts.failure = sentinel
            h.counts.fail = true
            val failure = assertFailsWith<IllegalStateException> {
                h.service.updateBadgeCount(NotificationPostPermit())
            }
            assertSame(sentinel, failure)
            assertEquals(priorBadge, h.service.badgeCount)
            assertEquals(priorDelivery, h.delivery.badgeSets)
        },
        nativeCase("snapshot query cancellation propagates before any removal attempt") {
            val h = NotificationHarness(this).authorize()
            val contact = h.contact()
            h.delivery.show("dm", NotificationPayload.DirectMessage(contact.key, messageID))
            h.delivery.cancelling = "deliveredNotifications"

            assertFailsWith<CancellationException> {
                h.service.removeDeliveredNotifications(setOf(contact.id), NotificationPostPermit())
            }
            assertTrue(h.delivery.snapshotRemovalRequests.isEmpty())
            assertEquals(listOf(NotificationId("dm")), h.delivery.shownIds)
        },
    )

    @TestFactory
    fun swiftText(): List<DynamicTest> {
        val family = "👨‍👩‍👧"
        val flag = "🇺🇸"
        val eAcute = "é"
        // Expected Character/UTF-16 counts from wp215_text_oracle.swift.txt (swiftc 6.3.2).
        fun check(text: String, previewCharacters: Int, previewUtf16: Int, unchanged: Boolean) {
            val preview = NotificationActionHandler.reactionPreview(text)
            assertEquals(previewCharacters, NotificationSwiftText.characterCount(preview))
            assertEquals(previewUtf16, preview.length)
            assertEquals(unchanged, preview == text)
        }
        return listOf(
            nativeCase("preview truncation counts grapheme clusters exactly as Swift Character") {
                check(family.repeat(50), 50, 400, unchanged = true)
                check(family.repeat(51), 50, 379, unchanged = false)
                check(flag.repeat(51), 50, 191, unchanged = false)
                check(eAcute.repeat(51), 50, 97, unchanged = false)
                check("\r\n".repeat(51), 50, 97, unchanged = false)
                val mixed = "x".repeat(46) + family + eAcute + "yz" + flag
                assertEquals("x".repeat(46) + family + "...", NotificationActionHandler.reactionPreview(mixed))
            },
            nativeCase("node-name comparison is canonical equivalence, not case folding") {
                assertTrue(NotificationSwiftText.equal("é", eAcute))
                assertTrue(NotificationSwiftText.equal("Å", "Å"))
                assertFalse(NotificationSwiftText.equal("Bob", "bob"))
                assertFalse(NotificationSwiftText.equal("ﬁ", "fi"))
            },
        )
    }

    /** Test-local copies of WP-209/WP-214 ports; the adapters compile only if the service keeps their signatures. */
    private interface ContactCleanupNotificationsMirror {
        suspend fun removeDeliveredNotifications(contactId: UUID)
        suspend fun updateBadgeCount()
    }

    private interface SyncNotificationPostingMirror {
        suspend fun postDirectMessageNotification(from: String, contactID: UUID, messageText: String, messageID: UUID, isMuted: Boolean)
        suspend fun postChannelMessageNotification(
            channelName: String, channelIndex: UByte, radioId: RadioId, senderName: String?, messageText: String,
            messageID: UUID, notificationLevel: NotificationLevel, hasSelfMention: Boolean,
        )
        suspend fun postRoomMessageNotification(
            roomName: String, sessionID: UUID, senderName: String?, messageText: String, messageID: UUID,
            notificationLevel: NotificationLevel,
        )
        suspend fun postNewContactNotification(contactName: String, contactID: UUID, contactType: ContactType)
    }

    @TestFactory
    fun collaboratorPorts(): List<DynamicTest> = listOf(
        nativeCase("service satisfies the WP-209 cleanup and WP-214 posting ports with one-line adapters") {
            val h = NotificationHarness(this).authorize()
            val s = h.service
            val cleanup = object : ContactCleanupNotificationsMirror {
                override suspend fun removeDeliveredNotifications(contactId: UUID) = s.removeDeliveredNotifications(contactId = contactId)
                override suspend fun updateBadgeCount() = s.updateBadgeCount()
            }
            val sync = object : SyncNotificationPostingMirror {
                override suspend fun postDirectMessageNotification(from: String, contactID: UUID, messageText: String, messageID: UUID, isMuted: Boolean) =
                    s.postDirectMessageNotification(from = from, contactID = contactID, messageText = messageText, messageID = messageID, isMuted = isMuted)
                override suspend fun postChannelMessageNotification(
                    channelName: String, channelIndex: UByte, radioId: RadioId, senderName: String?, messageText: String,
                    messageID: UUID, notificationLevel: NotificationLevel, hasSelfMention: Boolean,
                ) = s.postChannelMessageNotification(
                    channelName = channelName, channelIndex = channelIndex, radioId = radioId, senderName = senderName,
                    messageText = messageText, messageID = messageID, notificationLevel = notificationLevel, hasSelfMention = hasSelfMention,
                )
                override suspend fun postRoomMessageNotification(
                    roomName: String, sessionID: UUID, senderName: String?, messageText: String, messageID: UUID,
                    notificationLevel: NotificationLevel,
                ) = s.postRoomMessageNotification(
                    roomName = roomName, sessionID = sessionID, senderName = senderName, messageText = messageText,
                    messageID = messageID, notificationLevel = notificationLevel,
                )
                override suspend fun postNewContactNotification(contactName: String, contactID: UUID, contactType: ContactType) =
                    s.postNewContactNotification(contactName = contactName, contactID = contactID, contactType = contactType)
            }
            val contactID = UUID.randomUUID()
            sync.postDirectMessageNotification("A", contactID, "t", messageID, isMuted = false)
            cleanup.removeDeliveredNotifications(contactID)
            cleanup.updateBadgeCount()
            assertEquals(emptyList(), h.delivery.shownIds)
            assertEquals(2, h.delivery.badgeSets.size)
        },
    )
}
