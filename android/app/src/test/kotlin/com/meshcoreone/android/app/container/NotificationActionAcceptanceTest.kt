// AndroidOnly: WP-215 Posted reply PendingIntent -> dispatcher -> real session/service/Room acceptance witnesses.
package com.meshcoreone.android.app.container

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationActionDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationCategory
import com.meshcoreone.android.core.contracts.notifications.NotificationCategoryDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.notifications.didReceive
import com.meshcoreone.android.platform.notifications.messaging.AndroidMessagingNotificationDelivery
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationActionAcceptanceTest : RoomProcessTest() {
    @Test fun directAcceptedReplyIsNotReplayedByReplacementDispatcher() = runTest { replyBoundary(channel = false) }
    @Test fun channelAcceptedReplyIsNotReplayedByReplacementDispatcher() = runTest { replyBoundary(channel = true) }
    @Test fun unknownDirectAcceptanceIsNotReplayedOrRestoredAsUnsent() = runTest {
        replyBoundary(channel = false, unknown = true)
    }
    @Test fun replyCancelledBeforeWireCanBeRetriedByReplacementDispatcher() = runTest {
        replyBoundary(channel = false, beforeWire = true)
    }

    private suspend fun TestScope.replyBoundary(
        channel: Boolean,
        unknown: Boolean = false,
        beforeWire: Boolean = false,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entered = CompletableDeferred<Unit>()
        val previousDeliveryFinished = CompletableDeferred<Unit>()
        var holdBeforeWire = beforeWire
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun saveMessage(dto: MessageDTO) {
                if (holdBeforeWire) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                store.saveMessage(dto)
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        val peerKey = key(4)
        h.links.configureRadio = { firmware ->
            firmware.knownContacts += contactFrame(peerKey, "Quick reply peer")
            firmware.respond = { command ->
                when (command[0].toInt()) {
                    2 -> if (unknown) emptyList() else listOf(sentReply(command, firmware.publicKey))
                    3 -> listOf(Bytes.of(0))
                    else -> null
                }
            }
        }
        val firstOwner = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val replacementOwner = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val session = requireNotNull(h.sessions.current)
            val radio = session.token.radioId
            val contact = requireNotNull(store.fetchContact(radio, peerKey))
            val contactKey = EntityKey(radio, contact.id)
            store.saveChannel(ChannelDTO(radioId = radio, index = 0u, name = "Reply channel"))
            var attempts = 0
            var receipt: NotificationActionReceipt? = null
            val adapter = AndroidMessagingNotificationDelivery(context, firstOwner) { response ->
                attempts++
                receipt = currentCoroutineContext()[NotificationActionReceipt]
                try {
                    session.notificationService.didReceive(response)
                    entered.complete(Unit)
                    awaitCancellation() // Cancel before the dispatcher receives its Boolean acknowledgement.
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
                .single { it.tag.endsWith("wp215-acceptance") }.notification
            val reply = notification.actions.single()
            val input = reply.remoteInputs.single()
            val fillIn = Intent()
            RemoteInput.addResultsToIntent(arrayOf(input), fillIn, Bundle().apply { putCharSequence(input.resultKey, "one reply") })
            reply.actionIntent.send(context, 0, fillIn)
            shadowOf(Looper.getMainLooper()).idle()

            if (unknown) {
                h.eventually("wire command awaiting an unobserved reply", virtualBudgetMillis = 0) {
                    h.links.radios.single().sent.any { it[0].toInt() == 2 }
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
                assertEquals("one reply", session.notificationService.consumeDraft(contactKey))
                assertFalse(requireNotNull(receipt).preventsReplay)
                assertTrue(h.links.radios.single().sent.none { it[0].toInt() == 2 })
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
            assertEquals(1, h.links.radios.single().sent.count { it[0].toInt() == if (channel) 3 else 2 })
            assertNull(session.notificationService.consumeDraft(contactKey))
            if (!beforeWire) assertTrue(h.delivery.posted.isEmpty())
        } finally {
            firstOwner.cancel()
            replacementOwner.cancel()
            h.container.close()
            context.getSystemService(NotificationManager::class.java).cancelAll()
        }
    }
}
