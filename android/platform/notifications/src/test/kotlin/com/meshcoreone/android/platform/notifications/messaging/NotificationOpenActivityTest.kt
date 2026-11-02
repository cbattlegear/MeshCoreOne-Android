// AndroidOnly: WP-401 Exercise the private Activity entry and real content PendingIntent handoff.
package com.meshcoreone.android.platform.notifications.messaging

import android.content.ComponentName
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.notifications.NotificationCategory
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlinx.coroutines.test.runTest
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
    fun fullQueueRetainsNotificationForRetapWithoutDroppingBackgroundActions() = runTest {
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
        val notification = delivery.buildNotification(request)
        assertEquals(0, notification.flags and Notification.FLAG_AUTO_CANCEL)
        val tag = AndroidMessagingNotificationDelivery.TAG_PREFIX + request.id.value
        manager.notify(tag, AndroidMessagingNotificationDelivery.NOTIFICATION_ID, notification)

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
}
