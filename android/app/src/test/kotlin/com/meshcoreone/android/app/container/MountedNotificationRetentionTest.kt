// AndroidOnly: WP-302 Retain platform notification retries across mounted conversation reads.
// AndroidOnly: WP-401 Compose the published retention controls against the existing action callback boundary.
package com.meshcoreone.android.app.container

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.contracts.notifications.ChannelNotificationTarget
import com.meshcoreone.android.core.contracts.notifications.DeliveredNotification
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationCategory
import com.meshcoreone.android.core.contracts.notifications.NotificationCategoryDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.contracts.notifications.SnapshotRemovalOutcome
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.canonicalString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.notifications.didReceive
import com.meshcoreone.android.feature.chats.rooms.RoomConversationStateHolder
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import com.meshcoreone.android.platform.notifications.messaging.AndroidMessagingNotificationDelivery
import com.meshcoreone.android.platform.notifications.messaging.NotificationOpenActivity
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [31, 37], application = Application::class)
class MountedNotificationRetentionTest : RoomProcessTest() {
    @Test
    fun directReadRetainsRetryButExplicitMarkReadRemovesIt() = runTest {
        verifyTimelineRead(direct = true)
    }

    @Test
    fun channelReadRetainsUnprovedNotificationRetry() = runTest {
        verifyTimelineRead(direct = false)
    }

    @Test
    fun roomReadRetainsRetryButExplicitMarkReadRemovesIt() = runTest {
        val fixture = fixture()
        val screenScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        var postedRequest: NotificationRequest? = null
        try {
            val graph = fixture.connect()
            val radio = graph.token.radioId
            val room = RemoteNodeSessionDTO(
                radioId = radio, publicKey = key(4), name = "Room",
                role = RemoteNodeRole.ROOM_SERVER, isConnected = true, unreadCount = 1,
            )
            store.saveRemoteNodeSessionDTO(room)
            assertEquals(1L, assertNotNull(store.fetchRemoteNodeSession(EntityKey(radio, room.id))).unreadCount)
            val message = RoomMessageDTO(
                sessionID = room.id, authorKeyPrefix = Bytes.of(1, 2, 3, 4, 5, 6),
                text = "Room message", timestamp = EPOCH.epochSecond.toUInt(), createdAt = EPOCH,
            )
            store.saveRoomMessage(radio, message)
            val payload = NotificationPayload.RoomMessage(room.name, EntityKey(radio, room.id), message.id)
            val post = notificationRequest(NotificationCategory.ROOM_MESSAGE, payload, message.id.canonicalString())
            postedRequest = post
            val before = fixture.post(post)
            fixture.assertRetappable(post, before)

            val holder = RoomConversationStateHolder(room, fixture.harness.container.chatsFeature.room(room), screenScope)
            holder.start()
            holder.load()

            assertTrue(holder.state.value.hasLoadedOnce)
            assertNull(holder.state.value.failure)
            assertTrue(holder.state.value.messages.any { it.id == message.id })
            assertEquals(0L, assertNotNull(store.fetchRemoteNodeSession(EntityKey(radio, room.id))).unreadCount)
            fixture.assertRetappable(post, before)
            graph.notificationService.didReceive(NotificationResponse(payload, NotificationAction.MARK_READ, null))
            assertFalse(fixture.delivery.deliveredNotifications().any { it.id == post.id })
        } finally {
            screenScope.cancel()
            postedRequest?.let { fixture.delivery.removeDelivered(SnapshotList.of(it.id)) }
            fixture.harness.container.close()
        }
    }

    private suspend fun TestScope.verifyTimelineRead(direct: Boolean) {
        val fixture = fixture()
        val screenScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        var postedRequest: NotificationRequest? = null
        var viewModel: ChatViewModel? = null
        try {
            val graph = fixture.connect()
            val radio = graph.token.radioId
            graph.channelService.syncChannels(radio, 1u)
            val peer = assertNotNull(store.fetchContact(radio, key(3))).copy(unreadCount = 1)
            store.saveContact(peer)
            val channel = assertNotNull(store.fetchChannel(radio, 0u))
            store.incrementChannelUnreadCount(EntityKey(radio, channel.id))
            val currentChannel = assertNotNull(store.fetchChannel(radio, 0u))
            assertEquals(1L, assertNotNull(store.fetchContact(EntityKey(radio, peer.id))).unreadCount)
            assertEquals(1L, currentChannel.unreadCount)
            val conversation = if (direct) {
                TimelineConversation.Direct(peer)
            } else {
                TimelineConversation.Channel(currentChannel)
            }
            val message = MessageDTO(
                radioId = radio, contactID = peer.id.takeIf { direct },
                channelIndex = 0u.toUByte().takeUnless { direct },
                text = "Timeline message", timestamp = EPOCH.epochSecond.toUInt(), createdAt = EPOCH,
                sortDate = EPOCH, direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
            )
            store.saveMessage(message)
            val payload = if (direct) {
                NotificationPayload.DirectMessage(EntityKey(radio, peer.id), message.id)
            } else {
                NotificationPayload.ChannelMessage(radio, 0u, message.id)
            }
            if (payload is NotificationPayload.ChannelMessage) {
                assertEquals(ChannelNotificationTarget.Unproved, payload.displayTarget)
            }
            val post = notificationRequest(
                if (direct) NotificationCategory.DIRECT_MESSAGE else NotificationCategory.CHANNEL_MESSAGE,
                payload, message.id.canonicalString(),
            )
            postedRequest = post
            val before = fixture.post(post)
            fixture.assertRetappable(post, before)
            val mounted = ChatViewModel(conversation, fixture.harness.container.chatsFeature.timeline(conversation), screenScope)
            viewModel = mounted
            mounted.start()
            mounted.open()

            assertTrue(mounted.state.value.hasLoadedOnce)
            assertNull(mounted.state.value.loadError)
            assertTrue(mounted.state.value.messages.any { it.id == message.id })
            if (direct) {
                assertEquals(0L, assertNotNull(store.fetchContact(EntityKey(radio, peer.id))).unreadCount)
                assertEquals(1L, assertNotNull(store.fetchChannel(radio, 0u)).unreadCount)
            } else {
                assertEquals(0L, assertNotNull(store.fetchChannel(radio, 0u)).unreadCount)
                assertEquals(1L, assertNotNull(store.fetchContact(EntityKey(radio, peer.id))).unreadCount)
            }
            fixture.assertRetappable(post, before)
            if (direct) {
                graph.notificationService.didReceive(NotificationResponse(payload, NotificationAction.MARK_READ, null))
                assertFalse(fixture.delivery.deliveredNotifications().any { it.id == post.id })
            }
        } finally {
            viewModel?.close()
            screenScope.cancel()
            postedRequest?.let { fixture.delivery.removeDelivered(SnapshotList.of(it.id)) }
            fixture.harness.container.close()
        }
    }

    private fun TestScope.fixture(): Fixture {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val launcher = ComponentName(context.packageName, "${context.packageName}.RetentionTestLauncher")
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                name = launcher.className
                packageName = context.packageName
                applicationInfo = context.applicationInfo
                exported = true
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),
            resolveInfo,
        )
        val received = mutableListOf<NotificationResponse>()
        val delivery = AndroidMessagingNotificationDelivery(context, backgroundScope) { response ->
            received += response
            true
        }
        val harness = ContainerHarness(this, store, deliveryOverride = PlatformRecordingDelivery(delivery))
        harness.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(key(3), "Peer")
            radio.respond = { command ->
                if (command[0] == 0x1f.toUByte() && command[1] == 0.toUByte()) {
                    listOf(
                        Bytes.of(0x12, 0) + Bytes.utf8("Channel").paddedOrTruncated(32) +
                            Bytes(ByteArray(16) { 1 }),
                    )
                } else {
                    null
                }
            }
        }
        return Fixture(harness, context, launcher, delivery, received, this)
    }

    private fun notificationRequest(category: NotificationCategory, payload: NotificationPayload, id: String) =
        NotificationRequest(NotificationId(id), category, "Conversation", "Message", false, null, null, payload)

    private class Fixture(
        val harness: ContainerHarness,
        private val context: Application,
        private val launcher: ComponentName,
        val delivery: AndroidMessagingNotificationDelivery,
        private val received: MutableList<NotificationResponse>,
        private val test: TestScope,
    ) {
        suspend fun connect(): RadioSessionContainer {
            delivery.registerCategories(SnapshotList.empty())
            harness.manager.connect(harness.target())
            harness.settle()
            harness.assertReady()
            val graph = assertNotNull(harness.sessions.current)
            harness.eventually("notification action handlers are wired for the ready session") {
                graph.notificationService.onMarkAsRead != null && graph.notificationService.onRoomMarkAsRead != null &&
                    harness.sessions.current === graph && harness.appState.connectionState == DeviceConnectionState.READY
            }
            return graph
        }

        suspend fun post(request: NotificationRequest): DeliveredNotification {
            assertEquals(NotificationPostResult.Posted, delivery.post(request))
            return delivery.deliveredNotifications().single { it.id == request.id }.also {
                assertEquals(request.payload, it.payload)
                assertNotNull(it.postIdentity)
            }
        }

        suspend fun assertRetappable(request: NotificationRequest, before: DeliveredNotification) {
            assertEquals(before, delivery.deliveredNotifications().single { it.id == request.id })
            val expected = NotificationResponse(request.payload, null, null)
            val previousDeliveries = received.count { it == expected }
            val manager = context.getSystemService(NotificationManager::class.java)
            val posted = manager.activeNotifications.single { it.tag?.endsWith(request.id.value) == true }
            assertNotNull(posted.notification.contentIntent).send()
            val intent = shadowOf(context).nextStartedActivity
            assertEquals(ComponentName(context, NotificationOpenActivity::class.java), intent.component)
            val controller = Robolectric.buildActivity(NotificationOpenActivity::class.java, intent).create()
            try {
                assertEquals(launcher, shadowOf(controller.get()).nextStartedActivity.component)
                test.runCurrent()
                assertEquals(previousDeliveries + 1, received.count { it == expected })
                assertEquals(before, delivery.deliveredNotifications().single { it.id == request.id })
            } finally {
                controller.destroy()
            }
        }
    }

    private class PlatformRecordingDelivery(
        private val platform: AndroidMessagingNotificationDelivery,
    ) : RecordingDelivery() {
        override suspend fun authorizationStatus() = platform.authorizationStatus()
        override suspend fun requestAuthorization() = platform.requestAuthorization()
        override suspend fun registerCategories(categories: SnapshotList<NotificationCategoryDefinition>) =
            platform.registerCategories(categories)
        override suspend fun post(request: NotificationRequest) = platform.post(request)
        override suspend fun setBadgeCount(count: Long) = platform.setBadgeCount(count)
        override suspend fun deliveredNotifications() = platform.deliveredNotifications()
        override suspend fun removeDelivered(ids: SnapshotList<NotificationId>) = platform.removeDelivered(ids)
        override suspend fun removeDeliveredSnapshot(
            selected: SnapshotList<DeliveredNotification>,
        ): SnapshotList<SnapshotRemovalOutcome> = platform.removeDeliveredSnapshot(selected)
    }
}
