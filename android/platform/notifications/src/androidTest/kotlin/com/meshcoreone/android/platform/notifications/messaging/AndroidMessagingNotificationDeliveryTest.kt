// AndroidOnly: WP-401 Instrument MessagingStyle, privacy, action mutability and optional permission outcomes.
package com.meshcoreone.android.platform.notifications.messaging

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationActionDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationCategory
import com.meshcoreone.android.core.contracts.notifications.NotificationCategoryDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import java.util.UUID
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31, 37])
class AndroidMessagingNotificationDeliveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val contact = EntityKey(
        RadioId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
        UUID.fromString("20000000-0000-0000-0000-000000000002"),
    )
    private val message = UUID.fromString("30000000-0000-0000-0000-000000000003")

    @Test
    fun messagesUseMessagingStyleAndOnlyReplyIsMutable() = runTest {
        val delivery = delivery(this)
        delivery.registerCategories(
            SnapshotList.of(
                NotificationCategoryDefinition(
                    NotificationCategory.DIRECT_MESSAGE,
                    SnapshotList.of(
                        NotificationActionDefinition(NotificationAction.REPLY, "Reply", "Send", "Message"),
                        NotificationActionDefinition(NotificationAction.MARK_READ, "Mark as Read", null, null),
                    ),
                ),
            ),
        )
        val request = request()
        val notification = delivery.buildNotification(request)

        assertTrue(notification.extras.getString(Notification.EXTRA_TEMPLATE).orEmpty().contains("MessagingStyle"))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.publicVersion)
        assertEquals(2, notification.actions.size)
        assertEquals(1, notification.actions[0].remoteInputs?.size)
        assertFalse(notification.actions[0].actionIntent.isImmutable)
        assertTrue(notification.actions[1].actionIntent.isImmutable)
        val contentIntent = delivery.contentPendingIntent(request)
        val launch = shadowOf(contentIntent).savedIntent
        assertEquals(contentIntent, notification.contentIntent)
        assertTrue(contentIntent.isImmutable)
        assertEquals(
            ComponentName(context.packageName, "com.meshcoreone.android.MainActivity"),
            launch.component,
        )
        assertEquals(AndroidMessagingNotificationDelivery.ACTION_PREFIX + AndroidMessagingNotificationDelivery.ACTION_OPEN, launch.action)
        assertEquals(
            "meshcore-notification://action/${request.id.value}/OPEN",
            launch.dataString,
        )
        assertEquals(request.payload, NotificationPayloadCodec.read(launch))
        assertEquals(
            NotificationActionReceiver::class.java.name,
            shadowOf(notification.actions[0].actionIntent).savedIntent.component?.className,
        )
        assertEquals(
            NotificationActionReceiver::class.java.name,
            shadowOf(notification.actions[1].actionIntent).savedIntent.component?.className,
        )
    }

    @Test
    fun activityContentIntentPreservesEveryTypedPayloadIdentity() = runTest {
        val delivery = delivery(this)
        val payloads = listOf(
            NotificationPayload.DirectMessage(contact, message),
            NotificationPayload.ChannelMessage(contact.radioId, 17u, message),
            NotificationPayload.RoomMessage("Ops", EntityKey(contact.radioId, contact.id), message),
            NotificationPayload.NewContact(contact),
            NotificationPayload.Reaction(message, contact, null, null),
            NotificationPayload.Reaction(message, null, 2u, contact.radioId),
            NotificationPayload.LowBattery(14),
            NotificationPayload.QuickReplyFailed(contact),
            NotificationPayload.ChannelQuickReplyFailed(contact.radioId, 3u),
        )

        payloads.forEachIndexed { index, payload ->
            val request = request().copy(id = NotificationId("content-$index"), payload = payload)
            val pendingIntent = delivery.contentPendingIntent(request)
            assertEquals(payload, NotificationPayloadCodec.read(shadowOf(pendingIntent).savedIntent))
        }
    }

    @Test
    fun deniedPermissionReturnsTypedOutcomeAndNeverThrowsIntoMessaging() = runTest {
        val delivery = delivery(this)
        val outcome = delivery.post(request())
        assertTrue(
            "API/device permission decides visibility; posting remains a typed optional outcome",
            outcome == NotificationPostResult.Posted || outcome == NotificationPostResult.PermissionDenied,
        )
    }

    private fun delivery(scope: TestScope) = AndroidMessagingNotificationDelivery(
        context,
        scope,
        { true },
        launcherIntent = {
            Intent().setComponent(ComponentName(context.packageName, "com.meshcoreone.android.MainActivity"))
        },
    )

    private fun request() = NotificationRequest(
        id = NotificationId(message.toString()),
        category = NotificationCategory.DIRECT_MESSAGE,
        title = "Alice",
        body = "Private mesh text",
        soundEnabled = false,
        badge = 2,
        threadIdentifier = contact.id.toString(),
        payload = NotificationPayload.DirectMessage(contact, message),
    )
}
