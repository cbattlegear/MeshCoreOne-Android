// AndroidOnly: WP-215 Real AppState/session owner fencing and actual-adapter successor cleanup controls.
package com.meshcoreone.android.app.container

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationAuthorizationStatus
import com.meshcoreone.android.core.contracts.notifications.NotificationCategory
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationPostAdmission
import com.meshcoreone.android.core.contracts.notifications.NotificationPostAttempt
import com.meshcoreone.android.core.contracts.notifications.NotificationReadReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.sync.SyncDataEvent
import com.meshcoreone.android.platform.notifications.messaging.AndroidMessagingNotificationDelivery
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReactionOwnerIntegrationTest : RoomProcessTest() {
    @Test fun productionReactionStreamReachesPermitAwarePostingWithoutLegacyFallback() = runTest {
        val delivery = RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED)
        val h = ContainerHarness(this, store, deliveryOverride = delivery)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(4), "Reaction peer") }
        try {
            h.manager.connect(h.target())
            h.settle()
            val owner = requireNotNull(h.sessions.current)
            val message = seedReaction(h, owner)
            val baseline = delivery.legacyPostCalls
            owner.syncCoordinator.dataEventBroadcaster.yield(SyncDataEvent.ReactionReceived(message.id, "+1"))
            h.eventually("production reaction posted through the captured owner") {
                delivery.posted.any { it.payload is NotificationPayload.Reaction }
            }
            assertEquals(baseline, delivery.legacyPostCalls)
            assertTrue(owner.notificationPostPermit.isActive)
        } finally {
            h.container.close()
        }
    }

    @Test fun heldProductionLookupCannotPostAfterActualRuntimeRetirementAndSuccessorWiring() = runTest {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resumed = CompletableDeferred<Unit>()
        var heldMessage: UUID? = null
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun fetchMessage(key: EntityKey): MessageDTO? {
                if (key.id == heldMessage) {
                    reached.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    resumed.complete(Unit)
                }
                return store.fetchMessage(key)
            }
        }
        val delivery = RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED)
        val h = ContainerHarness(this, store, persistenceOverride = persistence, deliveryOverride = delivery)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(4), "Reaction peer") }
        try {
            h.manager.connect(h.target())
            h.settle()
            val original = requireNotNull(h.sessions.current)
            val message = seedReaction(h, original)
            heldMessage = message.id
            original.syncCoordinator.dataEventBroadcaster.yield(SyncDataEvent.ReactionReceived(message.id, "+1"))
            h.eventually("old real handler held in lookup") { reached.isCompleted }
            h.manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP)
            assertFalse(original.notificationPostPermit.isActive)
            h.manager.connect(h.target())
            h.settle()
            val successor = requireNotNull(h.sessions.current)
            assertNotSame(original, successor)
            assertTrue(successor.notificationPostPermit.isActive)
            release.complete(Unit)
            h.eventually("held old lookup returned") { resumed.isCompleted }
            h.settle()
            assertTrue(delivery.posted.none { (it.payload as? NotificationPayload.Reaction)?.messageID == message.id })
            assertEquals(0, delivery.legacyPostCalls)
        } finally {
            release.complete(Unit)
            h.container.close()
        }
    }

    @Test fun admittedFinalPostDrainsAcrossActualRuntimeRetirementBeforeSuccessorFactory() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivery = RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED)
        delivery.duringPermitPost = {
            if (it.category == NotificationCategory.REACTION) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "held synchronous post was not released" }
            }
        }
        val main = CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Default)
        val h = ContainerHarness(this, store, deliveryOverride = delivery, mainScope = main)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(4), "Reaction peer") }
        try {
            h.manager.connect(h.target())
            h.settle()
            val original = requireNotNull(h.sessions.current)
            val message = seedReaction(h, original)
            original.syncCoordinator.dataEventBroadcaster.yield(SyncDataEvent.ReactionReceived(message.id, "+1"))
            h.eventually("actual final post admitted") { entered.count == 0L }
            val disconnect = async { h.manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP) }
            h.eventually("actual runtime revoked the original root") { !original.notificationPostPermit.isActive }
            assertFalse(disconnect.isCompleted)
            h.manager.clearConnectedDevice()
            assertEquals(
                NotificationPostAdmission.Revoked,
                original.notificationPostPermit.runIfActive { error("late final post admitted") },
            )
            val replacement = async { h.manager.connect(h.target()) }
            runCurrent()
            assertEquals(1, h.lifecycle.created.size)
            release.countDown()
            disconnect.await()
            replacement.await()
            h.settle()
            assertEquals(1, delivery.posted.count { (it.payload as? NotificationPayload.Reaction)?.messageID == message.id })
            assertNotSame(original, h.sessions.current)
            assertTrue(requireNotNull(h.sessions.current).notificationPostPermit.isActive)
        } finally {
            release.countDown()
            h.container.close()
        }
    }

    @Test fun sameGraphRewireRevokesOnlyItsOldWiringAndStillPostsNewReactions() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first = true
        val delivery = RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED)
        delivery.beforePermitPost = {
            if (it.category == NotificationCategory.REACTION && first) {
                first = false
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
        }
        val h = ContainerHarness(this, store, deliveryOverride = delivery)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(4), "Reaction peer") }
        try {
            h.manager.connect(h.target())
            h.settle()
            val owner = requireNotNull(h.sessions.current)
            val old = seedReaction(h, owner)
            owner.syncCoordinator.dataEventBroadcaster.yield(SyncDataEvent.ReactionReceived(old.id, "+1"))
            h.eventually("old wiring reached final adapter") { entered.isCompleted }
            h.appState.wireServicesIfConnected()
            assertTrue("UI rewire must not retire the graph", owner.notificationPostPermit.isActive)
            release.complete(Unit)
            h.settle()
            val fresh = seedReaction(h, owner)
            owner.syncCoordinator.dataEventBroadcaster.yield(SyncDataEvent.ReactionReceived(fresh.id, "+1"))
            h.eventually("new wiring posted its reaction") {
                delivery.posted.any { (it.payload as? NotificationPayload.Reaction)?.messageID == fresh.id }
            }
            assertTrue(delivery.posted.none { (it.payload as? NotificationPayload.Reaction)?.messageID == old.id })
            assertEquals(0, delivery.legacyPostCalls)
        } finally {
            release.complete(Unit)
            h.container.close()
        }
    }

    @Test fun directGraphTeardownRevokesTheSameOriginalPermit() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            val original = requireNotNull(h.sessions.current)
            val root = original.notificationPostPermit
            assertTrue(root.isActive)
            original.tearDown()
            assertSame(root, original.notificationPostPermit)
            assertFalse(root.isActive)
            assertEquals(NotificationPostAdmission.Revoked, root.runIfActive { error("closed graph posted") })
        } finally {
            h.container.close()
        }
    }

    @Test fun oldDirectReadKeepsCommittedWriteButCannotCancelActualSuccessorPost() = runTest {
        oldActionCannotMutateSuccessor(channel = false, reply = false)
    }

    @Test fun oldChannelReadKeepsCommittedWriteButCannotCancelActualSuccessorPost() = runTest {
        oldActionCannotMutateSuccessor(channel = true, reply = false)
    }

    @Test fun acceptedOldDirectReplyCannotCancelActualSuccessorPost() = runTest {
        oldActionCannotMutateSuccessor(channel = false, reply = true)
    }

    @Test fun acceptedOldChannelReplyCannotCancelActualSuccessorPost() = runTest {
        oldActionCannotMutateSuccessor(channel = true, reply = true)
    }

    private suspend fun seedReaction(h: ContainerHarness, owner: RadioSessionContainer): MessageDTO {
        h.appState.wireServicesIfConnected()
        val contact = requireNotNull(store.fetchContact(owner.token.radioId, key(4)))
        val message = MessageDTO(
            radioId = owner.token.radioId, contactID = contact.id, text = "Original outgoing message",
            timestamp = 1u, direction = MessageDirection.OUTGOING,
        )
        store.saveMessage(message)
        store.saveReaction(ReactionDTO(
            messageID = message.id, emoji = "+1", senderName = "Other node", messageHash = "test-hash",
            rawText = "reaction", contactID = contact.id, radioId = owner.token.radioId,
        ))
        return message
    }

    private suspend fun TestScope.oldActionCannotMutateSuccessor(channel: Boolean, reply: Boolean) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var held = false
        suspend fun afterWrite() {
            if (held) { entered.complete(Unit); release.await() }
        }
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun clearUnreadCount(key: EntityKey) {
                store.clearUnreadCount(key)
                afterWrite()
            }
            override suspend fun clearChannelUnreadCount(radioId: com.meshcoreone.android.core.model.RadioId, channelIndex: UByte) {
                store.clearChannelUnreadCount(radioId, channelIndex)
                afterWrite()
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val adapter = AndroidMessagingNotificationDelivery(context, backgroundScope) { true }
        adapter.registerCategories(SnapshotList.empty())
        val h = ContainerHarness(this, store, persistenceOverride = persistence, platformDeliveryOverride = adapter)
        h.links.configureRadio = { firmware ->
            firmware.knownContacts += contactFrame(key(4), "Action peer")
            firmware.respond = { command ->
                when (command[0].toInt()) {
                    2 -> listOf(sentReply(command, firmware.publicKey))
                    3 -> listOf(Bytes.of(0))
                    else -> null
                }
            }
        }
        val actionReceipt = NotificationActionReceipt()
        val readReceipt = NotificationReadReceipt()
        try {
            h.manager.connect(h.target())
            h.settle()
            val original = requireNotNull(h.sessions.current)
            h.appState.wireServicesIfConnected()
            val contact = requireNotNull(store.fetchContact(original.token.radioId, key(4)))
            val contactKey = EntityKey(contact.radioId, contact.id)
            store.saveChannel(ChannelDTO(radioId = contact.radioId, index = 0u, name = "Action channel", unreadCount = 1))
            val message = MessageDTO(
                radioId = contact.radioId, contactID = contact.id.takeUnless { channel },
                channelIndex = 0u.toUByte().takeIf { channel }, text = "Unread original", timestamp = 1u, isRead = false,
            )
            store.saveMessage(message)
            val payload = if (channel) NotificationPayload.ChannelMessage(contact.radioId, 0u, message.id)
                else NotificationPayload.DirectMessage(contactKey, message.id)
            val request = NotificationRequest(
                NotificationId(message.id.toString().uppercase()),
                if (channel) NotificationCategory.CHANNEL_MESSAGE else NotificationCategory.DIRECT_MESSAGE,
                "Original A", "Original body", false, null, null, payload,
            )
            val originalPost = adapter.post(request, original.notificationPostPermit)
            assertTrue(
                "A actual adapter outcome: $originalPost",
                originalPost is NotificationPostAdmission.Admitted && originalPost.value is NotificationPostAttempt.Posted,
            )
            val first = requireNotNull(manager.activeNotifications.singleOrNull { it.tag == "mesh-message:${request.id.value}" }) {
                "A actual adapter reported posted but its exact tag is not visible"
            }
            val firstIdentity = first.notification.extras.getString("mc1.notification.post-id")
            held = true
            val action = launch(actionReceipt + readReceipt) {
                when {
                    reply && channel -> original.notificationActionHandler.handleChannelQuickReply(contact.radioId, 0u, "accepted reply")
                    reply -> original.notificationActionHandler.handleQuickReply(contactKey, "accepted reply")
                    channel -> original.notificationActionHandler.handleChannelMarkAsRead(contact.radioId, 0u, message.id)
                    else -> original.notificationActionHandler.handleMarkAsRead(contactKey, message.id)
                }
            }
            h.eventually("actual accepted/write action held before notification cleanup") { entered.isCompleted }
            if (reply) assertEquals(NotificationActionReceipt.State.ACCEPTED, actionReceipt.state)
            h.manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP)
            h.manager.connect(h.target())
            h.settle()
            val successor = requireNotNull(h.sessions.current)
            val result = adapter.post(request.copy(title = "Successor B"), successor.notificationPostPermit)
            assertTrue(result is NotificationPostAdmission.Admitted && result.value is NotificationPostAttempt.Posted)
            val replacement = manager.activeNotifications.single { it.tag == first.tag }
            val replacementIdentity = replacement.notification.extras.getString("mc1.notification.post-id")
            assertNotEquals(firstIdentity, replacementIdentity)
            action.cancel()
            release.complete(Unit)
            h.eventually("old action settled after retirement") { action.isCompleted }
            action.join()
            assertEquals(
                replacementIdentity,
                manager.activeNotifications.single { it.tag == first.tag }.notification.extras.getString("mc1.notification.post-id"),
            )
            if (reply) {
                assertEquals(NotificationActionReceipt.State.ACCEPTED, actionReceipt.state)
                assertEquals(1, h.links.radios.first().sent.count { it[0].toInt() == if (channel) 3 else 2 })
            } else {
                assertEquals(NotificationReadReceipt.State.COMMITTED, readReceipt.state)
                assertTrue(requireNotNull(store.fetchMessage(EntityKey(contact.radioId, message.id))).isRead)
            }
        } finally {
            release.complete(Unit)
            h.container.close()
            manager.cancelAll()
        }
    }
}
