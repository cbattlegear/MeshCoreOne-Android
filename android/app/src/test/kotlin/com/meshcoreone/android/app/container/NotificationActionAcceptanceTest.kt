// AndroidOnly: WP-215 Posted RemoteInput -> dispatcher -> real session/service/Room acceptance witnesses.
package com.meshcoreone.android.app.container

import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryPersisting
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryScope
import com.meshcoreone.android.core.contracts.domain.ChannelMessageCreation
import com.meshcoreone.android.core.contracts.domain.DirectAckAuthority
import com.meshcoreone.android.core.contracts.domain.DirectAckLease
import com.meshcoreone.android.core.contracts.domain.DirectMessageCreationAuthority
import com.meshcoreone.android.core.contracts.domain.DirectMessageCreationResult
import com.meshcoreone.android.core.contracts.domain.DirectPendingCompletionAuthority
import com.meshcoreone.android.core.contracts.domain.DirectQueueAuthority
import com.meshcoreone.android.core.contracts.domain.DisplayedChannelReactionPersisting
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.InlineChannelAttemptPersisting
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.QueuedChannelTerminalPersisting
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.ChannelNotificationTarget
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
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.notifications.didReceive
import com.meshcoreone.android.platform.notifications.messaging.AndroidMessagingNotificationDelivery
import com.meshcoreone.android.platform.notifications.messaging.NotificationActionRecoveryStore
import com.meshcoreone.android.platform.notifications.messaging.NotificationRecoveryKind
import com.meshcoreone.android.platform.notifications.messaging.NotificationRecoveryRead
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
import com.meshcoreone.android.platform.notifications.R as NotificationStrings
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationActionAcceptanceTest : RoomProcessTest() {
    @Before
    @After
    fun resetProcessDispatcher() {
        // Quarantined actions deliberately outlive router replacement, but not a simulated process.
        resetProcessDispatcherState()
    }

    private fun resetProcessDispatcherState() {
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
    @Test fun lostChannelReplyResponseIsRetainedWithoutAutomaticReplay() = runTest {
        replyBoundary(channel = true, unknown = true, responseLoss = true)
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
        responseLoss: Boolean = false,
        beforeWire: Boolean = false,
        leaveDraftAcrossRetry: Boolean = false,
        newerDraft: String? = null,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entered = CompletableDeferred<Unit>()
        val previousDeliveryFinished = CompletableDeferred<Unit>()
        var holdBeforeWire = beforeWire
        val persistence = object : PersistenceStoreProtocol by store,
            ChannelHistoryPersisting by store,
            DisplayedChannelReactionPersisting by store,
            QueuedChannelTerminalPersisting by store,
            InlineChannelAttemptPersisting by store,
            DirectAckAuthority by store,
            DirectPendingCompletionAuthority by store,
            DirectQueueAuthority by store,
            DirectMessageCreationAuthority by store {
            override suspend fun saveMessage(dto: MessageDTO) {
                if (holdBeforeWire) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                store.saveMessage(dto)
            }
            override suspend fun createDirectMessage(
                lease: DirectAckLease, message: MessageDTO, creationRequestId: UUID, claimRequestId: UUID,
            ): DirectMessageCreationResult {
                if (holdBeforeWire) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                return store.createDirectMessage(lease, message, creationRequestId, claimRequestId)
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
            sessionClock = sessionClock(SchedulerClock { testScheduler.currentTime }),
        )
        val peerKey = key(4)
        val holdFreshReply = AtomicBoolean(false)
        val heldFreshReply = AtomicReference<Bytes>()
        h.links.configureRadio = { firmware ->
            firmware.knownContacts += contactFrame(peerKey, "Quick reply peer")
            firmware.respond = { command ->
                when (command[0].toInt()) {
                    2 -> if (unknown) {
                        emptyList()
                    } else if (holdFreshReply.get()) {
                        heldFreshReply.set(sentReply(command, firmware.publicKey))
                        emptyList()
                    } else {
                        listOf(sentReply(command, firmware.publicKey))
                    }
                    3 -> if (unknown) emptyList() else listOf(Bytes.of(0))
                    31 -> if (channel && command[1] == 0.toUByte()) {
                        listOf(Bytes.of(0x12, 0) + Bytes.utf8("Reply channel") +
                            Bytes(ByteArray(32 - Bytes.utf8("Reply channel").size)) + Bytes(ByteArray(16) { 1 }))
                    } else {
                        listOf(Bytes.of(0x12, command[1].toInt()) + Bytes(ByteArray(48)))
                    }
                    else -> null
                }
            }
        }
        val firstOwner = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val replacementOwner = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val sendCommand = if (channel) 3 else 2
        try {
            h.container.foreground.activityStarted()
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val session = requireNotNull(h.sessions.current)
            h.eventually("notification action handlers are wired for the ready session") {
                val navigation = h.container.navigation.currentState
                h.sessions.current === session &&
                    h.appState.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY &&
                    navigation.boundSessionToken == session.token && navigation.navigationReady
            }
            session.notificationService.checkAuthorizationStatus()
            assertTrue(session.notificationService.isAuthorized)
            val radio = session.token.radioId
            val contact = requireNotNull(store.fetchContact(radio, peerKey))
            val contactKey = EntityKey(radio, contact.id)
            if (channel) {
                assertNotNull(store.fetchChannelOccupantSnapshot(radio, 0u))
            }
            val messageId = UUID.randomUUID()
            val payload = if (channel && unknown) {
                val occupant = requireNotNull(store.fetchChannelOccupantSnapshot(radio, 0u)).occupantID
                val message = MessageDTO(
                    id = messageId, radioId = radio, channelIndex = 0u, text = "Incoming", timestamp = 1u,
                    direction = MessageDirection.INCOMING, isRead = false,
                )
                store.saveMessage(message)
                val source = requireNotNull(
                    store.fetchChannelTimelineMessage(ChannelHistoryScope(radio, 0u, occupant), EntityKey(radio, messageId)),
                )
                NotificationPayload.ChannelMessage(
                    radio, 0u, messageId, ChannelNotificationTarget.Proven(radio, 0u, occupant),
                    requireNotNull(source.channelReactionIncarnation),
                )
            } else if (channel) NotificationPayload.ChannelMessage(radio, 0u, messageId)
                else NotificationPayload.DirectMessage(contactKey, messageId)
            var attempts = 0
            var receipt: NotificationActionReceipt? = null
            val adapter = AndroidMessagingNotificationDelivery(context, firstOwner) { response ->
                attempts++
                receipt = currentCoroutineContext()[NotificationActionReceipt]
                try {
                    if (channel && unknown) {
                        assertTrue(h.container.handleNotificationResponse(response))
                    } else {
                        session.notificationService.didReceive(response)
                    }
                    entered.complete(Unit)
                    awaitCancellation()
                } finally { previousDeliveryFinished.complete(Unit) }
            }
            val categories = SnapshotList.of(
                NotificationCategoryDefinition(
                    if (channel) NotificationCategory.CHANNEL_MESSAGE else NotificationCategory.DIRECT_MESSAGE,
                    SnapshotList.of(NotificationActionDefinition(NotificationAction.REPLY, "Reply", "Send", "Message")),
                ),
            )
            adapter.registerCategories(categories)
            val request = NotificationRequest(
                NotificationId("wp215-acceptance"), if (channel) NotificationCategory.CHANNEL_MESSAGE else NotificationCategory.DIRECT_MESSAGE,
                "Peer", "Incoming message", false, null, null, payload,
            )
            assertEquals(NotificationPostResult.Posted, adapter.post(request))
            val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
                .single { it.tag?.endsWith("wp215-acceptance") == true }.notification
            val reply = notification.actions.single()
            fun sendReply(action: Notification.Action, text: String) {
                val input = action.remoteInputs.single()
                val fillIn = Intent()
                RemoteInput.addResultsToIntent(
                    arrayOf(input),
                    fillIn,
                    Bundle().apply { putCharSequence(input.resultKey, text) },
                )
                action.actionIntent.send(context, 0, fillIn)
                shadowOf(Looper.getMainLooper()).idle()
            }
            sendReply(reply, "one reply")

            if (responseLoss) {
                h.eventually("lost radio response became an uncertain action", virtualBudgetMillis = 60_000, virtualStepMillis = 100) {
                    entered.isCompleted
                }
                assertEquals(NotificationActionReceipt.State.UNCERTAIN, requireNotNull(receipt).state)
            } else if (unknown) {
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
            if (unknown) {
                h.eventually("uncertain reply retained for copy-only recovery") {
                    val recovery = NotificationActionRecoveryStore(context).read()
                    recovery is NotificationRecoveryRead.Ready && recovery.entries.any {
                        it.response.payload == payload && it.response.userText == "one reply" &&
                            it.kind == NotificationRecoveryKind.OUTCOME_UNKNOWN
                    }
                }
                val recovered = (NotificationActionRecoveryStore(context).read() as NotificationRecoveryRead.Ready)
                    .entries.single {
                        it.response.payload == payload && it.response.userText == "one reply"
                    }
                assertEquals(NotificationRecoveryKind.OUTCOME_UNKNOWN, recovered.kind)
                // A fresh dispatcher has no permission to replay the prior process's uncertain action.
                if (!channel) resetProcessDispatcherState()
            } else if (!beforeWire && !channel) {
                val persistedReply = store.fetchMessages(contactKey).filter {
                    it.direction == MessageDirection.OUTGOING && it.text == "one reply"
                }
                assertEquals("The accepted direct reply remains in Room", 1, persistedReply.size)
                resetProcessDispatcherState()
            }
            val retryFinished = CompletableDeferred<Unit>()
            val replacementAdapter = AndroidMessagingNotificationDelivery(context, replacementOwner) { response ->
                attempts++
                session.notificationService.didReceive(response)
                retryFinished.complete(Unit)
                true
            }
            replacementAdapter.registerCategories(categories)
            if (beforeWire) h.eventually("pre-acceptance retry completed") { retryFinished.isCompleted }
            if (!beforeWire && !channel) {
                sendReply(reply, "one reply")
                sendReply(reply, "different text")
                h.eventually("edited reply retained without reusing the posted action") {
                    val recovery = NotificationActionRecoveryStore(context).read()
                    recovery is NotificationRecoveryRead.Ready && recovery.entries.any {
                        it.response.payload == payload && it.response.userText == "different text" &&
                            it.kind == NotificationRecoveryKind.NOT_ADMITTED
                    }
                }
                val recovered = (NotificationActionRecoveryStore(context).read() as NotificationRecoveryRead.Ready)
                    .entries.filter { it.response.payload == payload }
                assertEquals(if (unknown) listOf("one reply", "different text") else listOf("different text"),
                    recovered.map { it.response.userText })
                h.eventually("edited reply shows recovery feedback") {
                    shadowOf(Looper.getMainLooper()).idle()
                    ShadowToast.getTextOfLatestToast() ==
                        context.getString(NotificationStrings.string.notification_action_recovery_saved)
                }
                assertEquals(1, attempts)
                assertEquals(1, h.links.radios.single().sent.count { it[0].toInt() == sendCommand })
            }
            if (!beforeWire && !unknown && !channel) {
                assertEquals(NotificationPostResult.Posted, replacementAdapter.post(request))
                val manager = context.getSystemService(NotificationManager::class.java)
                val freshAction = manager.activeNotifications
                    .single { it.tag?.endsWith("wp215-acceptance") == true }.notification.actions.single()
                assertNotEquals(reply.actionIntent, freshAction.actionIntent)
                h.scheduler.advanceTimeBy(1_000)
                h.scheduler.runCurrent()
                holdFreshReply.set(true)
                sendReply(freshAction, "one reply")
                h.eventually("fresh action reaches the radio while its response is held", virtualBudgetMillis = 0) {
                    heldFreshReply.get() != null
                }
                assertEquals(2, h.links.radios.single().sent.count { it[0].toInt() == sendCommand })
                val beforeResponse = store.fetchMessages(contactKey).filter {
                    it.direction == MessageDirection.OUTGOING && it.text == "one reply"
                }
                assertEquals(2, beforeResponse.size)
                assertEquals(1, beforeResponse.count { it.status == MessageStatus.SENT })
                assertEquals(1, beforeResponse.count { it.status == MessageStatus.PENDING })
                assertFalse(retryFinished.isCompleted)
                h.links.radios.single().push(requireNotNull(heldFreshReply.get()))
                h.eventually("fresh action completes Room bookkeeping") { retryFinished.isCompleted }
                val outgoing = store.fetchMessages(contactKey).filter {
                    it.direction == MessageDirection.OUTGOING && it.status == MessageStatus.SENT
                }
                assertEquals(2, outgoing.count { it.text == "one reply" })
                assertEquals(0, outgoing.count { it.text == "different text" })
                assertEquals(2, attempts)
            } else {
                repeat(10) { runCurrent() }
                assertEquals(if (beforeWire) 2 else 1, attempts)
                assertEquals(1, h.links.radios.single().sent.count { it[0].toInt() == sendCommand })
            }
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
