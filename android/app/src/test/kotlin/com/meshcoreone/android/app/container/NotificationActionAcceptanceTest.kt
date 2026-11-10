// AndroidOnly: WP-215 Posted RemoteInput -> dispatcher -> real session/service/Room acceptance witnesses.
package com.meshcoreone.android.app.container

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryPersisting
import com.meshcoreone.android.core.contracts.domain.ChannelMessageCreation
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.InlineChannelAttemptPersisting
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.QueuedChannelTerminalPersisting
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationActionDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationAuthorizationStatus
import com.meshcoreone.android.core.contracts.notifications.NotificationReadReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationCategory
import com.meshcoreone.android.core.contracts.notifications.NotificationCategoryDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.notifications.didReceive
import com.meshcoreone.android.platform.notifications.messaging.AndroidMessagingNotificationDelivery
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationActionAcceptanceTest : RoomProcessTest() {
    @Before
    @After
    fun resetProcessDispatcher() {
        // Quarantined actions deliberately outlive router replacement, but not a simulated process.
        val type = Class.forName("com.meshcoreone.android.platform.notifications.messaging.NotificationActionDispatcher")
        type.getMethod("resetForTesting\$notifications").invoke(type.getField("INSTANCE").get(null))
    }

    @Test fun directAcceptedReplyIsNotReplayedByReplacementDispatcher() = runTest { replyBoundary(channel = false) }
    @Test fun channelAcceptedReplyIsNotReplayedByReplacementDispatcher() = runTest { replyBoundary(channel = true) }
    @Test fun unknownDirectAcceptanceIsNotReplayedOrRestoredAsUnsent() = runTest {
        replyBoundary(channel = false, unknown = true)
    }
    @Test fun unknownChannelAcceptanceIsNotReplayedOrReportedAsUnsent() = runTest {
        replyBoundary(channel = true, unknown = true)
    }
    @Test fun replyCancelledBeforeWireCanBeRetriedByReplacementDispatcher() = runTest {
        replyBoundary(channel = false, beforeWire = true)
    }
    @Test fun channelReplyCancelledBeforeWireCanBeRetriedByReplacementDispatcher() = runTest {
        replyBoundary(channel = true, beforeWire = true)
    }
    @Test fun acceptedRetryClearsItsUnconsumedActionDraft() = runTest {
        replyBoundary(channel = false, beforeWire = true, leaveDraftAcrossRetry = true)
    }
    @Test fun acceptedRetryDoesNotDeleteANewerUserDraft() = runTest {
        replyBoundary(channel = false, beforeWire = true, leaveDraftAcrossRetry = true, newerDraft = "User's newer draft")
    }
    @Test fun acceptedRetryDoesNotDeleteANewerEqualTextUserDraft() = runTest {
        replyBoundary(channel = false, beforeWire = true, leaveDraftAcrossRetry = true, newerDraft = "one reply")
    }

    @Test fun realRoomMarkReadRecordsCommitBeforeReturningToCancelledCaller() = runTest {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var hold = false
        val persistence = object : PersistenceStoreProtocol by store,
            ChannelHistoryPersisting by store, QueuedChannelTerminalPersisting by store,
            InlineChannelAttemptPersisting by store {
            override suspend fun clearUnreadCount(key: EntityKey) {
                store.clearUnreadCount(key)
                if (hold) {
                    reached.complete(Unit)
                    release.await()
                }
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        val peerKey = key(4)
        h.links.configureRadio = { it.knownContacts += contactFrame(peerKey, "Mark read peer") }
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val session = requireNotNull(h.sessions.current)
            val contact = requireNotNull(store.fetchContact(session.token.radioId, peerKey))
            val contactKey = EntityKey(contact.radioId, contact.id)
            store.saveContact(contact.copy(unreadCount = 1))
            val message = MessageDTO(
                radioId = contact.radioId, contactID = contact.id, text = "Unread", timestamp = 1u,
                direction = MessageDirection.INCOMING, isRead = false,
            )
            store.saveMessage(message)
            hold = true
            val receipt = NotificationActionReceipt()
            val caller = launch(receipt) { session.notificationActionHandler.handleMarkAsRead(contactKey, message.id) }
            h.eventually("actual Room writes committed before held return") { reached.isCompleted }
            assertTrue(requireNotNull(store.fetchMessage(EntityKey(contact.radioId, message.id))).isRead)
            assertEquals(0L, requireNotNull(store.fetchContact(contactKey)).unreadCount)
            assertEquals(NotificationActionReceipt.State.READY, receipt.state)
            caller.cancel()
            release.complete(Unit)
            h.eventually("bounded mark-read finishes after caller cancellation") { caller.isCompleted }
            caller.join()
            assertTrue(caller.isCancelled)
            assertEquals(NotificationActionReceipt.State.COMMITTED, receipt.state)
            assertTrue(requireNotNull(store.fetchMessage(EntityKey(contact.radioId, message.id))).isRead)
        } finally {
            release.complete(Unit)
            h.container.close()
        }
    }

    @Test fun realRoomPartialReadRemainsUncertainWhenResponseRouterReturnsTrue() = runTest {
        var clearCalls = 0
        var failClear = false
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun clearUnreadCount(key: EntityKey) {
                if (failClear) {
                    clearCalls++
                    throw IllegalStateException("Unread clear refused after the message write")
                }
                store.clearUnreadCount(key)
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        val peerKey = key(4)
        h.links.configureRadio = { it.knownContacts += contactFrame(peerKey, "Partial read peer") }
        val owner = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val replacement = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val session = requireNotNull(h.sessions.current)
            val contact = requireNotNull(store.fetchContact(session.token.radioId, peerKey))
            val key = EntityKey(contact.radioId, contact.id)
            store.saveContact(contact.copy(unreadCount = 1))
            val message = MessageDTO(
                radioId = contact.radioId, contactID = contact.id, text = "Unread", timestamp = 1u,
                direction = MessageDirection.INCOMING, isRead = false,
            )
            store.saveMessage(message)
            failClear = true
            var actions = 0
            var progress: NotificationReadReceipt? = null
            val returned = CompletableDeferred<Unit>()
            val adapter = AndroidMessagingNotificationDelivery(context = ApplicationProvider.getApplicationContext(), scope = owner) { response ->
                actions++
                progress = currentCoroutineContext()[NotificationReadReceipt]
                session.notificationService.didReceive(response)
                returned.complete(Unit)
                true
            }
            adapter.registerCategories(SnapshotList.of(NotificationCategoryDefinition(
                NotificationCategory.DIRECT_MESSAGE,
                SnapshotList.of(NotificationActionDefinition(NotificationAction.MARK_READ, "Mark read", null, null)),
            )))
            assertEquals(NotificationPostResult.Posted, adapter.post(NotificationRequest(
                NotificationId("wp215-partial-read"), NotificationCategory.DIRECT_MESSAGE,
                "Peer", "Unread", false, null, null, NotificationPayload.DirectMessage(key, message.id),
            )))
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.getSystemService(NotificationManager::class.java).activeNotifications
                .single { it.tag?.endsWith("wp215-partial-read") == true }.notification
                .actions.single().actionIntent.send()
            shadowOf(Looper.getMainLooper()).idle()
            h.eventually("partial write returned through contained response") { returned.isCompleted }
            assertTrue(requireNotNull(store.fetchMessage(EntityKey(contact.radioId, message.id))).isRead)
            assertEquals(1L, requireNotNull(store.fetchContact(key)).unreadCount)
            assertEquals(NotificationReadReceipt.State.UNCERTAIN, requireNotNull(progress).state)
            assertEquals(1, requireNotNull(progress).completedWrites)
            AndroidMessagingNotificationDelivery(context, replacement) { response ->
                actions++
                session.notificationService.didReceive(response)
                true
            }
            repeat(10) { runCurrent() }
            assertEquals(1, actions)
            assertEquals("An uncertain unread clear must not be blindly repeated", 1, clearCalls)
        } finally {
            owner.cancel()
            replacement.cancel()
            h.container.close()
            ApplicationProvider.getApplicationContext<Context>().getSystemService(NotificationManager::class.java).cancelAll()
        }
    }

    private suspend fun TestScope.replyBoundary(
        channel: Boolean,
        unknown: Boolean = false,
        beforeWire: Boolean = false,
        leaveDraftAcrossRetry: Boolean = false,
        newerDraft: String? = null,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entered = CompletableDeferred<Unit>()
        val previousDeliveryFinished = CompletableDeferred<Unit>()
        var holdBeforeWire = beforeWire
        val persistence = object : PersistenceStoreProtocol by store,
            ChannelHistoryPersisting by store, QueuedChannelTerminalPersisting by store,
            InlineChannelAttemptPersisting by store {
            override suspend fun saveMessage(dto: MessageDTO) {
                if (holdBeforeWire) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                store.saveMessage(dto)
            }
            override suspend fun createOrReconcileChannelMessage(
                creation: ChannelMessageCreation, timestamp: UInt, createdAt: Instant,
            ): MessageDTO {
                if (holdBeforeWire) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                return store.createOrReconcileChannelMessage(creation, timestamp, createdAt)
            }
        }
        val h = ContainerHarness(
            this, store, persistenceOverride = persistence,
            deliveryOverride = RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED),
        )
        val peerKey = key(4)
        h.links.configureRadio = { firmware ->
            firmware.knownContacts += contactFrame(peerKey, "Quick reply peer")
            firmware.respond = { command ->
                when (command[0].toInt()) {
                    2 -> if (unknown) emptyList() else listOf(sentReply(command, firmware.publicKey))
                    3 -> if (unknown) emptyList() else listOf(Bytes.of(0))
                    31 -> if (channel && command[1] == 0.toUByte()) {
                        listOf(Bytes.of(0x12, 0) + Bytes.utf8("Reply channel") +
                            Bytes(ByteArray(32 - Bytes.utf8("Reply channel").size)) + Bytes(ByteArray(16) { 1 }))
                    } else if (channel) {
                        listOf(Bytes.of(0x12, command[1].toInt()) + Bytes(ByteArray(48)))
                    } else null
                    else -> null
                }
            }
        }
        val firstOwner = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val replacementOwner = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val sendCommand = if (channel) 3 else 2
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val session = requireNotNull(h.sessions.current)
            session.notificationService.checkAuthorizationStatus()
            assertTrue(session.notificationService.isAuthorized)
            val radio = session.token.radioId
            val contact = requireNotNull(store.fetchContact(radio, peerKey))
            val contactKey = EntityKey(radio, contact.id)
            if (channel) {
                assertNotNull(store.fetchChannelOccupantSnapshot(radio, 0u))
            }
            var attempts = 0
            var receipt: NotificationActionReceipt? = null
            val adapter = AndroidMessagingNotificationDelivery(context, firstOwner) { response ->
                attempts++
                receipt = currentCoroutineContext()[NotificationActionReceipt]
                try {
                    session.notificationService.didReceive(response)
                    entered.complete(Unit)
                    awaitCancellation()
                } finally { previousDeliveryFinished.complete(Unit) }
            }
            adapter.registerCategories(SnapshotList.of(
                NotificationCategoryDefinition(
                    if (channel) NotificationCategory.CHANNEL_MESSAGE else NotificationCategory.DIRECT_MESSAGE,
                    SnapshotList.of(NotificationActionDefinition(NotificationAction.REPLY, "Reply", "Send", "Message")),
                ),
            ))
            val messageId = UUID.randomUUID()
            val payload = if (channel) NotificationPayload.ChannelMessage(radio, 0u, messageId)
                else NotificationPayload.DirectMessage(contactKey, messageId)
            assertEquals(NotificationPostResult.Posted, adapter.post(NotificationRequest(
                NotificationId("wp215-acceptance"), if (channel) NotificationCategory.CHANNEL_MESSAGE else NotificationCategory.DIRECT_MESSAGE,
                "Peer", "Incoming message", false, null, null, payload,
            )))
            val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
                .single { it.tag?.endsWith("wp215-acceptance") == true }.notification
            val reply = notification.actions.single()
            val input = reply.remoteInputs.single()
            val fillIn = Intent()
            RemoteInput.addResultsToIntent(arrayOf(input), fillIn, Bundle().apply { putCharSequence(input.resultKey, "one reply") })
            reply.actionIntent.send(context, 0, fillIn)
            shadowOf(Looper.getMainLooper()).idle()

            if (unknown) {
                h.eventually("wire command awaiting an unobserved reply", virtualBudgetMillis = 0) {
                    h.links.radios.single().sent.any { it[0].toInt() == sendCommand }
                }
                assertEquals(NotificationActionReceipt.State.IN_FLIGHT, requireNotNull(receipt).state)
            } else {
                h.eventually("action reached deterministic cancellation boundary") { entered.isCompleted }
                assertEquals(
                    if (beforeWire) NotificationActionReceipt.State.READY else NotificationActionReceipt.State.ACCEPTED,
                    requireNotNull(receipt).state,
                )
            }
            firstOwner.cancel()
            h.eventually("cancelled router unwound", virtualBudgetMillis = 0) { previousDeliveryFinished.isCompleted }
            if (beforeWire) {
                if (channel) {
                    assertNull(session.notificationService.consumeDraft(contactKey))
                    assertEquals(1, h.delivery.posted.size)
                    assertEquals(
                        NotificationPayload.ChannelQuickReplyFailed(radio, 0u),
                        h.delivery.posted.single().payload,
                    )
                } else if (!leaveDraftAcrossRetry) {
                    assertEquals("one reply", session.notificationService.consumeDraft(contactKey))
                }
                // Dedicated recovery cases leave the draft intact; acceptance must clean up its own copy.
                if (newerDraft != null) session.notificationService.saveDraft(contactKey, newerDraft)
                assertFalse(requireNotNull(receipt).preventsReplay)
                assertTrue(h.links.radios.single().sent.none { it[0].toInt() == sendCommand })
                holdBeforeWire = false
            } else {
                assertNull(session.notificationService.consumeDraft(contactKey))
                assertEquals(
                    if (unknown) NotificationActionReceipt.State.UNCERTAIN else NotificationActionReceipt.State.ACCEPTED,
                    requireNotNull(receipt).state,
                )
            }
            val retryFinished = CompletableDeferred<Unit>()
            AndroidMessagingNotificationDelivery(context, replacementOwner) { response ->
                attempts++
                session.notificationService.didReceive(response)
                retryFinished.complete(Unit)
                true
            }
            if (beforeWire) h.eventually("pre-acceptance retry completed") { retryFinished.isCompleted }
            repeat(10) { runCurrent() }
            assertEquals(if (beforeWire) 2 else 1, attempts)
            assertEquals(1, h.links.radios.single().sent.count { it[0].toInt() == sendCommand })
            assertEquals(newerDraft, session.notificationService.consumeDraft(contactKey))
            if (!beforeWire) assertTrue(h.delivery.posted.isEmpty())
        } finally {
            firstOwner.cancel()
            replacementOwner.cancel()
            h.container.close()
            context.getSystemService(NotificationManager::class.java).cancelAll()
        }
    }
}
