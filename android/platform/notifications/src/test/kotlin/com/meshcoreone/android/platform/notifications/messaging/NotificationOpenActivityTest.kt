// AndroidOnly: WP-401 Exercise the private Activity entry and real content PendingIntent handoff.
package com.meshcoreone.android.platform.notifications.messaging

import android.content.ComponentName
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.AppStateProvider
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.domain.NotificationPreferencesPort
import com.meshcoreone.android.core.contracts.notifications.DeliveredNotification
import com.meshcoreone.android.core.contracts.notifications.NotificationCategory
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationActionDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationCategoryDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationDeliveryPort
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.contracts.notifications.SnapshotRemovalOutcome
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.NotificationPreferences
import com.meshcoreone.android.core.services.notifications.NotificationService
import com.meshcoreone.android.core.services.notifications.NotificationUnreadCounting
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class NotificationOpenActivityTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val contact = EntityKey(RadioId(UUID(0, 1)), UUID(0, 2))
    private val payload = NotificationPayload.DirectMessage(contact, UUID(0, 3))
    private val launcher = ComponentName(context.packageName, "${context.packageName}.TestLauncher")

    @Before
    @Suppress("DEPRECATION")
    fun setUp() {
        NotificationActionDispatcher.resetForTesting()
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
    }

    @After
    fun tearDown() {
        ReplacementDuringLaunchShadow.afterLaunch = null
        NotificationActionDispatcher.resetForTesting()
    }

    @Test
    fun genuineActivityPendingIntentStagesUntilReadyAndOpensGenericLauncher() = runTest {
        var ready = false
        val received = mutableListOf<NotificationResponse>()
        val delivery = AndroidMessagingNotificationDelivery(context, backgroundScope) { response ->
            if (!ready) false else true.also { received += response }
        }
        val pending = delivery.contentPendingIntent(request("cold"))
        assertTrue(pending.isActivity)
        assertTrue(pending.isImmutable)
        pending.send()
        val privateLaunch = shadowOf(context).nextStartedActivity
        assertNotNull(privateLaunch)
        assertEquals(ComponentName(context, NotificationOpenActivity::class.java), privateLaunch.component)

        val controller = Robolectric.buildActivity(NotificationOpenActivity::class.java, privateLaunch).create()
        try {
            assertTrue(controller.get().isFinishing)
            assertFalse(NotificationActionDispatcher.drainOnce())
            assertTrue(received.isEmpty())
            val opened = shadowOf(controller.get()).nextStartedActivity
            assertNotNull(opened)
            assertEquals(launcher, opened.component)
            assertEquals(Intent.ACTION_MAIN, opened.action)
            assertNull(NotificationPayloadCodec.read(opened))
            assertTrue(opened.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertTrue(opened.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
            assertTrue(opened.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
            ready = true
            assertTrue(NotificationActionDispatcher.drainOnce())
            assertTrue(NotificationActionDispatcher.drainOnce())
            assertEquals(listOf(NotificationResponse(payload, null, null)), received)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun warmDistinctTapsPreserveOrderWithoutGenericLauncherReenqueue() = runTest {
        val received = mutableListOf<NotificationResponse>()
        val delivery = AndroidMessagingNotificationDelivery(context, backgroundScope) {
            received += it
            true
        }
        val secondPayload = NotificationPayload.ChannelMessage(contact.radioId, 7u, UUID(0, 4))
        listOf(request("one"), request("two").copy(payload = secondPayload)).forEach { request ->
            val controller = Robolectric.buildActivity(
                NotificationOpenActivity::class.java,
                delivery.contentLaunchIntent(request),
            ).create()
            try {
                val opened = shadowOf(controller.get()).nextStartedActivity
                assertEquals(launcher, opened.component)
                assertNull(NotificationPayloadCodec.read(opened))
                assertTrue(NotificationActionDispatcher.drainOnce())
            } finally {
                controller.destroy()
            }
        }
        assertTrue(NotificationActionDispatcher.drainOnce())
        assertEquals(
            listOf(NotificationResponse(payload, null, null), NotificationResponse(secondPayload, null, null)),
            received,
        )
    }

    @Test
    fun builtReplacementCannotRewriteVisibleOrReversePostedReplyAndReadTokensAcrossRadios() = runTest {
        val received = mutableListOf<NotificationResponse>()
        val delivery = AndroidMessagingNotificationDelivery(context, backgroundScope) { received += it; true }
        delivery.registerCategories(SnapshotList.of(
            NotificationCategoryDefinition(
                NotificationCategory.DIRECT_MESSAGE,
                SnapshotList.of(
                    NotificationActionDefinition(NotificationAction.REPLY, "Reply", "Send", "Message"),
                    NotificationActionDefinition(NotificationAction.MARK_READ, "Read", null, null),
                ),
            ),
        ))
        val manager = context.getSystemService(NotificationManager::class.java)
        val original = request("same-message-uuid").copy(threadIdentifier = null)
        val otherRadioPayload = NotificationPayload.DirectMessage(
            EntityKey(RadioId(UUID(0, 20)), UUID(0, 21)), UUID(0, 3),
        )
        val first = delivery.buildNotification(original)
        val tag = AndroidMessagingNotificationDelivery.TAG_PREFIX + original.id.value
        manager.notify(tag, AndroidMessagingNotificationDelivery.NOTIFICATION_ID, first)
        val second = delivery.buildNotification(original.copy(payload = otherRadioPayload))
        first.actions.zip(second.actions).forEach { (a, b) ->
            assertFalse(a.actionIntent == b.actionIntent)
        }

        suspend fun assertTokens(notification: Notification, expected: NotificationPayload) {
            notification.actions.forEachIndexed { index, action ->
                val reply = index == 0
                assertTrue(action.actionIntent.isBroadcast)
                assertEquals(!reply, action.actionIntent.isImmutable)
                val saved = shadowOf(action.actionIntent).savedIntent
                assertEquals(
                    notification.extras.getString(AndroidMessagingNotificationDelivery.EXTRA_POST_ID),
                    saved.data?.getQueryParameter("post"),
                )
                val fillIn = Intent()
                if (reply) {
                    RemoteInput.addResultsToIntent(action.remoteInputs, fillIn, Bundle().apply {
                        putCharSequence(NotificationActionReceiver.REMOTE_INPUT_KEY, "  own radio reply  ")
                    })
                }
                action.actionIntent.send(context, 0, fillIn)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(NotificationActionDispatcher.drainOnce())
                assertEquals(
                    NotificationResponse(
                        expected,
                        if (reply) NotificationAction.REPLY else NotificationAction.MARK_READ,
                        if (reply) "own radio reply" else null,
                    ),
                    received.last(),
                )
                assertNull(shadowOf(context).nextStartedActivity)
            }
        }

        assertTokens(first, payload) // B is built but not published; A is still visible.
        manager.notify(tag, AndroidMessagingNotificationDelivery.NOTIFICATION_ID, second)
        assertTokens(second, otherRadioPayload)
        manager.notify(tag, AndroidMessagingNotificationDelivery.NOTIFICATION_ID, first)
        assertTokens(first, payload) // Reverse build/post ordering must not rewrite either retained token.
        assertTokens(second, otherRadioPayload)
        assertEquals(8, received.size)
    }

    @Test
    fun builtReplacementCannotRewriteVisibleOrReversePostedContentToken() = runTest {
        val delivery = AndroidMessagingNotificationDelivery(context, backgroundScope) { true }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                AndroidMessagingNotificationDelivery.CHANNEL_MESSAGES_SILENT,
                "Messages",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val firstRequest = request("reused")
        val secondPayload = NotificationPayload.DirectMessage(contact, UUID(0, 100))
        val first = delivery.buildNotification(firstRequest)
        val tag = AndroidMessagingNotificationDelivery.TAG_PREFIX + firstRequest.id.value
        manager.notify(tag, AndroidMessagingNotificationDelivery.NOTIFICATION_ID, first)
        val second = delivery.buildNotification(firstRequest.copy(payload = secondPayload))
        assertFalse(first.contentIntent == second.contentIntent)

        fun assertToken(notification: Notification, expected: NotificationPayload) {
            notification.contentIntent.send()
            val launched = shadowOf(context).nextStartedActivity
            assertEquals(expected, NotificationPayloadCodec.read(launched))
            val postId = notification.extras.getString(AndroidMessagingNotificationDelivery.EXTRA_POST_ID)
            assertNotNull(postId)
            assertEquals(postId, launched.getStringExtra(AndroidMessagingNotificationDelivery.EXTRA_POST_ID))
            assertEquals(postId, launched.data?.getQueryParameter("post"))
        }

        // B is built but unpublished: A's visible token must still represent A.
        assertToken(manager.activeNotifications.single().notification, payload)
        manager.notify(tag, AndroidMessagingNotificationDelivery.NOTIFICATION_ID, second)
        assertToken(manager.activeNotifications.single().notification, secondPayload)
        // Reverse publication relative to build order must not change either immutable token.
        manager.notify(tag, AndroidMessagingNotificationDelivery.NOTIFICATION_ID, first)
        assertToken(manager.activeNotifications.single().notification, payload)
        assertToken(second, secondPayload)
    }

    @Test
    @Config(shadows = [ReplacementDuringLaunchShadow::class])
    fun replacementPostedAfterTapSurvivesOlderPostRetirement() = runTest {
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val received = mutableListOf<NotificationResponse>()
        val delivery = AndroidMessagingNotificationDelivery(context, backgroundScope) {
            received += it
            true
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                AndroidMessagingNotificationDelivery.CHANNEL_MESSAGES_SILENT,
                "Messages",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val original = request("reused").copy(threadIdentifier = null)
        delivery.post(original)
        val oldNotification = manager.activeNotifications.single().notification
        oldNotification.contentIntent.send()
        val oldLaunch = shadowOf(context).nextStartedActivity
        val oldPostId = oldNotification.extras.getString(AndroidMessagingNotificationDelivery.EXTRA_POST_ID)
        val replacement = original.copy(payload = NotificationPayload.DirectMessage(contact, UUID(0, 99)))
        var newPostId: String? = null
        ReplacementDuringLaunchShadow.afterLaunch = {
            runBlocking { delivery.post(replacement) }
            newPostId = manager.activeNotifications.single().notification.extras
                .getString(AndroidMessagingNotificationDelivery.EXTRA_POST_ID)
        }
        val controller = Robolectric.buildActivity(NotificationOpenActivity::class.java, oldLaunch).create()
        try {
            assertEquals(launcher, shadowOf(controller.get()).nextStartedActivity.component)
            assertNotNull(newPostId)
            assertFalse(oldPostId == newPostId)
            assertTrue(NotificationActionDispatcher.drainOnce())
            assertEquals(listOf(NotificationResponse(payload, null, null)), received)
            assertEquals(
                newPostId,
                manager.activeNotifications.single().notification.extras
                    .getString(AndroidMessagingNotificationDelivery.EXTRA_POST_ID),
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Config(shadows = [DeferredPublicationShadow::class])
    fun realServiceSnapshotCleanupPreservesRealAdapterReplacementAndTap() = runTest {
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val received = mutableListOf<NotificationResponse>()
        val delivery = AndroidMessagingNotificationDelivery(context, backgroundScope) { received += it; true }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                AndroidMessagingNotificationDelivery.CHANNEL_MESSAGES_SILENT, "Messages",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val publication = Shadow.extract<DeferredPublicationShadow>(manager)
        for (delayed in listOf(false, true)) {
            val selectedGate = CompletableDeferred<SnapshotList<DeliveredNotification>>()
            val resume = CompletableDeferred<Unit>()
            var outcomes: SnapshotList<SnapshotRemovalOutcome>? = null
            val gated = object : NotificationDeliveryPort by delivery {
                override suspend fun removeDeliveredSnapshot(
                    selected: SnapshotList<DeliveredNotification>,
                ): SnapshotList<SnapshotRemovalOutcome> {
                    selectedGate.complete(selected)
                    resume.await()
                    return delivery.removeDeliveredSnapshot(selected).also { outcomes = it }
                }
            }
            val service = NotificationService(
                radioId = contact.radioId,
                delivery = gated,
                preferencesPort = object : NotificationPreferencesPort {
                    override val preferences: StateFlow<NotificationPreferences>
                        get() = error("Snapshot cleanup must not read preferences")
                    override suspend fun update(preferences: NotificationPreferences) =
                        error("Snapshot cleanup must not update preferences")
                },
                appState = object : AppStateProvider {
                    override suspend fun isInForeground(): Boolean =
                        error("Snapshot cleanup must not query app state")
                },
                unreadCounting = NotificationUnreadCounting { error("Snapshot cleanup must not query unread counts") },
                scope = backgroundScope,
            )
            val original = request("composed-$delayed").copy(threadIdentifier = null)
            delivery.post(original)
            val cleanup = launch { service.removeDeliveredNotifications(contact.id) }
            val selected = selectedGate.await()
            assertEquals(original.id, selected.single().id)
            val oldIdentity = requireNotNull(selected.single().postIdentity).value.toString()
            publication.defer = delayed
            val replacementPayload = NotificationPayload.DirectMessage(contact, UUID(0, if (delayed) 301 else 300))
            delivery.post(original.copy(payload = replacementPayload))
            val cancellations = publication.cancellations
            resume.complete(Unit)
            cleanup.join()
            assertEquals(listOf(SnapshotRemovalOutcome.SUPERSEDED), requireNotNull(outcomes).toList())
            assertEquals(cancellations, publication.cancellations)
            publication.publish()
            publication.defer = false
            val replacement = manager.activeNotifications.single().notification
            assertFalse(oldIdentity == replacement.extras.getString(AndroidMessagingNotificationDelivery.EXTRA_POST_ID))
            replacement.contentIntent.send()
            val launch = shadowOf(context).nextStartedActivity
            assertEquals(replacementPayload, NotificationPayloadCodec.read(launch))
            val controller = Robolectric.buildActivity(NotificationOpenActivity::class.java, launch).create()
            try {
                assertEquals(launcher, shadowOf(controller.get()).nextStartedActivity.component)
                assertTrue(NotificationActionDispatcher.drainOnce())
                assertEquals(replacementPayload, received.last().payload)
                assertTrue(manager.activeNotifications.isEmpty())
            } finally {
                controller.destroy()
            }
        }
    }

    @Test
    @Config(shadows = [DeferredPublicationShadow::class])
    fun snapshotCleanupPreservesNewerSubmittedPostAndItsTapIncludingDelayedPublication() = runTest {
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val received = mutableListOf<NotificationResponse>()
        val delivery = AndroidMessagingNotificationDelivery(context, backgroundScope) { received += it; true }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                AndroidMessagingNotificationDelivery.CHANNEL_MESSAGES_SILENT, "Messages",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val publication = Shadow.extract<DeferredPublicationShadow>(manager)
        for (delayed in listOf(false, true)) {
            val original = request("snapshot-$delayed").copy(threadIdentifier = null)
            delivery.post(original)
            val selected = delivery.deliveredNotifications()
            assertNotNull(selected.single().postIdentity)
            publication.defer = delayed
            val replacementPayload = NotificationPayload.DirectMessage(contact, UUID(0, if (delayed) 201 else 200))
            delivery.post(original.copy(payload = replacementPayload))
            val cancellations = publication.cancellations
            assertEquals(
                listOf(SnapshotRemovalOutcome.SUPERSEDED),
                delivery.removeDeliveredSnapshot(selected).toList(),
            )
            assertEquals(cancellations, publication.cancellations)
            publication.publish()
            publication.defer = false
            val replacement = manager.activeNotifications.single().notification
            replacement.contentIntent.send()
            val launch = shadowOf(context).nextStartedActivity
            assertEquals(replacementPayload, NotificationPayloadCodec.read(launch))
            val controller = Robolectric.buildActivity(NotificationOpenActivity::class.java, launch).create()
            try {
                assertEquals(launcher, shadowOf(controller.get()).nextStartedActivity.component)
                assertTrue(NotificationActionDispatcher.drainOnce())
                assertEquals(replacementPayload, received.last().payload)
                assertTrue(manager.activeNotifications.isEmpty())
            } finally {
                controller.destroy()
            }
        }
    }

    @Test
    fun snapshotCleanupUnknownHistoryNeverFallsBackButExplicitLogicalRemovalStillWorks() = runTest {
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val delivery = AndroidMessagingNotificationDelivery(context, backgroundScope) { true }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                AndroidMessagingNotificationDelivery.CHANNEL_MESSAGES_SILENT, "Messages",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val request = request("unknown-submission")
        val notification = delivery.buildNotification(request)
        val tag = AndroidMessagingNotificationDelivery.TAG_PREFIX + request.id.value
        // A platform post without this process's submission record models unknown history, not process death.
        manager.notify(tag, AndroidMessagingNotificationDelivery.NOTIFICATION_ID, notification)
        val selected = delivery.deliveredNotifications()
        assertNotNull(selected.single().postIdentity)
        assertEquals(
            listOf(SnapshotRemovalOutcome.UNKNOWN_IDENTITY),
            delivery.removeDeliveredSnapshot(selected).toList(),
        )
        assertEquals(
            listOf(SnapshotRemovalOutcome.UNKNOWN_IDENTITY),
            delivery.removeDeliveredSnapshot(SnapshotList.of(selected.single().copy(postIdentity = null))).toList(),
        )
        assertEquals(1, manager.activeNotifications.size)
        delivery.removeDelivered(SnapshotList.of(request.id))
        assertTrue(manager.activeNotifications.isEmpty())
        delivery.post(request.copy(threadIdentifier = null))
        val older = delivery.deliveredNotifications()
        delivery.post(request.copy(
            threadIdentifier = null,
            payload = NotificationPayload.DirectMessage(contact, UUID(0, 202)),
        ))
        assertEquals(
            listOf(SnapshotRemovalOutcome.SUPERSEDED),
            delivery.removeDeliveredSnapshot(older).toList(),
        )
        delivery.removeDelivered(SnapshotList.of(request.id))
        assertTrue(manager.activeNotifications.isEmpty())
        delivery.post(request.copy(threadIdentifier = null))
        assertEquals(
            listOf(SnapshotRemovalOutcome.REMOVED),
            delivery.removeDeliveredSnapshot(delivery.deliveredNotifications()).toList(),
        )
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun dismissedPostHistoryIsBoundedAndEvictionCannotRetireAnUnknownOrNewerPost() = runTest {
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val received = mutableListOf<NotificationResponse>()
        val delivery = AndroidMessagingNotificationDelivery(context, backgroundScope) { received += it; true }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                AndroidMessagingNotificationDelivery.CHANNEL_MESSAGES_SILENT,
                "Messages",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val original = request("evicted-history").copy(threadIdentifier = null)
        delivery.post(original)
        val originalNotification = manager.activeNotifications.single().notification
        val originalSnapshot = delivery.deliveredNotifications()
        repeat(300) { index ->
            val id = "dismissed-$index"
            delivery.post(request(id).copy(threadIdentifier = null))
            manager.cancel(
                AndroidMessagingNotificationDelivery.TAG_PREFIX + id,
                AndroidMessagingNotificationDelivery.NOTIFICATION_ID,
            )
        }
        assertEquals(
            256,
            ReflectionHelpers.getStaticField<Map<String, String>>(
                AndroidMessagingNotificationDelivery::class.java, "submittedPosts",
            ).size,
        )
        assertEquals(
            listOf(SnapshotRemovalOutcome.UNKNOWN_IDENTITY),
            delivery.removeDeliveredSnapshot(originalSnapshot).toList(),
        )
        originalNotification.contentIntent.send()
        val originalLaunch = shadowOf(context).nextStartedActivity
        val controller = Robolectric.buildActivity(NotificationOpenActivity::class.java, originalLaunch).create()
        try {
            assertEquals(launcher, shadowOf(controller.get()).nextStartedActivity.component)
            assertEquals(1, manager.activeNotifications.size)
            assertTrue(NotificationActionDispatcher.drainOnce())
            assertEquals(listOf(NotificationResponse(payload, null, null)), received)
        } finally {
            controller.destroy()
        }
        delivery.post(original.copy(payload = NotificationPayload.DirectMessage(contact, UUID(0, 102))))
        val replacementPostId = manager.activeNotifications.single().notification.extras
            .getString(AndroidMessagingNotificationDelivery.EXTRA_POST_ID)
        AndroidMessagingNotificationDelivery.retireContentNotification(
            context, original.id.value,
            requireNotNull(originalLaunch.getStringExtra(AndroidMessagingNotificationDelivery.EXTRA_POST_ID)),
        )
        assertEquals(
            replacementPostId,
            manager.activeNotifications.single().notification.extras
                .getString(AndroidMessagingNotificationDelivery.EXTRA_POST_ID),
        )
    }

    @Test
    @Config(shadows = [DeferredPublicationShadow::class])
    fun submittedReplacementSurvivesRetirementWhilePlatformStillExposesOldPost() = runTest {
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val delivery = AndroidMessagingNotificationDelivery(context, backgroundScope) { true }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                AndroidMessagingNotificationDelivery.CHANNEL_MESSAGES_SILENT,
                "Messages",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val original = request("pending-publication").copy(threadIdentifier = null)
        delivery.post(original)
        val oldNotification = manager.activeNotifications.single().notification
        oldNotification.contentIntent.send()
        val oldLaunch = shadowOf(context).nextStartedActivity
        val publication = Shadow.extract<DeferredPublicationShadow>(manager)
        publication.defer = true
        delivery.post(original.copy(payload = NotificationPayload.DirectMessage(contact, UUID(0, 101))))
        assertEquals(
            oldNotification.extras.getString(AndroidMessagingNotificationDelivery.EXTRA_POST_ID),
            manager.activeNotifications.single().notification.extras
                .getString(AndroidMessagingNotificationDelivery.EXTRA_POST_ID),
        )
        val controller = Robolectric.buildActivity(NotificationOpenActivity::class.java, oldLaunch).create()
        try {
            assertEquals(launcher, shadowOf(controller.get()).nextStartedActivity.component)
            assertEquals(0, publication.cancellations)
            publication.publish()
            assertFalse(
                oldNotification.extras.getString(AndroidMessagingNotificationDelivery.EXTRA_POST_ID) ==
                    manager.activeNotifications.single().notification.extras
                        .getString(AndroidMessagingNotificationDelivery.EXTRA_POST_ID),
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun fullQueueRetainsNotificationForRetapWithoutDroppingBackgroundActions() = runTest {
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        var ready = false
        val received = mutableListOf<NotificationResponse>()
        val delivery = AndroidMessagingNotificationDelivery(context, backgroundScope) { response ->
            if (!ready) false else true.also { received += response }
        }
        val queued = (0 until 32).map { index ->
            NotificationResponse(
                NotificationPayload.DirectMessage(contact, UUID(1, index.toLong())),
                if (index % 2 == 0) NotificationAction.REPLY else NotificationAction.MARK_READ,
                if (index % 2 == 0) "Reply $index" else null,
            )
        }
        queued.forEach {
            assertEquals(EnqueueResult.DELIVERED_OR_QUEUED, NotificationActionDispatcher.enqueue(it))
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                AndroidMessagingNotificationDelivery.CHANNEL_MESSAGES_SILENT,
                "Messages",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val request = request("full")
        delivery.post(request.copy(threadIdentifier = null))
        val notification = manager.activeNotifications.single().notification
        assertEquals(0, notification.flags and Notification.FLAG_AUTO_CANCEL)
        val tag = AndroidMessagingNotificationDelivery.TAG_PREFIX + request.id.value

        fun tap() {
            notification.contentIntent.send()
            val launched = shadowOf(context).nextStartedActivity
            assertEquals(ComponentName(context, NotificationOpenActivity::class.java), launched.component)
            val controller = Robolectric.buildActivity(NotificationOpenActivity::class.java, launched).create()
            try {
                assertTrue(controller.get().isFinishing)
                assertEquals(launcher, shadowOf(controller.get()).nextStartedActivity.component)
            } finally {
                controller.destroy()
            }
        }

        tap()
        assertEquals(1, manager.activeNotifications.count { it.tag == tag })
        assertFalse(NotificationActionDispatcher.drainOnce())
        assertTrue(received.isEmpty())
        ready = true
        assertTrue(NotificationActionDispatcher.drainOnce())
        assertEquals(queued, received)
        tap()
        assertEquals(0, manager.activeNotifications.count { it.tag == tag })
        assertTrue(NotificationActionDispatcher.drainOnce())
        assertEquals(queued + NotificationResponse(payload, null, null), received)
    }

    @Test
    fun malformedAndUnexpectedIntentsDoNotQueueOrLaunch() = runTest {
        val received = mutableListOf<NotificationResponse>()
        AndroidMessagingNotificationDelivery(context, backgroundScope) { received += it; true }
        val intents = listOf(
            Intent().setAction("com.meshcoreone.android.notification.OPEN"),
            NotificationPayloadCodec.write(Intent().setAction(Intent.ACTION_VIEW), payload),
        )
        intents.forEach { intent ->
            val controller = Robolectric.buildActivity(NotificationOpenActivity::class.java, intent).create()
            try {
                assertTrue(controller.get().isFinishing)
                assertNull(shadowOf(controller.get()).nextStartedActivity)
            } finally {
                controller.destroy()
            }
        }
        assertTrue(NotificationActionDispatcher.drainOnce())
        assertTrue(received.isEmpty())
    }

    @Test
    @Suppress("DEPRECATION")
    fun privateBoundaryIsDeclaredWithoutPublicIntentFiltersOrTaskAffinity() {
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, NotificationOpenActivity::class.java), 0,
        )
        assertFalse(info.exported)
        assertNull(info.taskAffinity)
        assertTrue(info.flags and ActivityInfo.FLAG_NO_HISTORY != 0)
        assertTrue(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
    }

    private fun request(id: String) = NotificationRequest(
        id = NotificationId(id),
        category = NotificationCategory.DIRECT_MESSAGE,
        title = "Peer",
        body = "Test",
        soundEnabled = false,
        badge = null,
        threadIdentifier = contact.id.toString(),
        payload = payload,
    )

    @Implements(Activity::class)
    class ReplacementDuringLaunchShadow : ShadowActivity() {
        @RealObject
        private lateinit var activity: Activity

        @Implementation
        fun startActivity(intent: Intent) {
            Shadow.directlyOn<Unit, Activity>(
                activity,
                Activity::class.java,
                "startActivity",
                ClassParameter.from(Intent::class.java, intent),
            )
            afterLaunch?.invoke()
        }

        companion object {
            var afterLaunch: (() -> Unit)? = null
        }
    }

    @Implements(NotificationManager::class)
    class DeferredPublicationShadow : ShadowNotificationManager() {
        var defer = false
        var cancellations = 0
            private set
        private val waiting = mutableListOf<Triple<String?, Int, Notification>>()

        @Implementation
        override fun notify(tag: String?, id: Int, notification: Notification) {
            if (defer) waiting += Triple(tag, id, notification) else super.notify(tag, id, notification)
        }

        @Implementation
        override fun cancel(tag: String?, id: Int) {
            cancellations++
            super.cancel(tag, id)
        }

        fun publish() {
            waiting.forEach { (tag, id, notification) -> super.notify(tag, id, notification) }
            waiting.clear()
        }
    }
}
