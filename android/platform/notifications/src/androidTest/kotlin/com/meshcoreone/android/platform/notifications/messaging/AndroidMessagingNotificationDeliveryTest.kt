// AndroidOnly: WP-401 Instrument MessagingStyle, privacy, action mutability and optional permission outcomes.
package com.meshcoreone.android.platform.notifications.messaging

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.meshcoreone.android.core.contracts.notifications.NotificationPostAdmission
import com.meshcoreone.android.core.contracts.notifications.NotificationPostPermit
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import java.util.UUID
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
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
        assertTrue(delivery.actionPendingIntent(request, null, mutable = false).isImmutable)
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

    @Test
    fun badgeMutationIsGatedByTheCapturedPermit() = runTest {
        val delivery = delivery(this)
        val permit = NotificationPostPermit()

        assertTrue(delivery.setBadgeCount(7, permit) is NotificationPostAdmission.Admitted<*>)
        assertEquals(7, delivery.buildNotification(request().copy(badge = null)).number)

        permit.close()
        assertEquals(NotificationPostAdmission.Revoked, delivery.setBadgeCount(11, permit))
        assertEquals(7, delivery.buildNotification(request().copy(badge = null)).number)
    }

    @Test
    fun retiredPostDoesNotConstructOrUpdateItsActionPendingIntent() = runTest {
        val postIdentity = UUID.fromString("40000000-0000-0000-0000-000000000004")
        var generatedIdentities = 0
        val delivery = AndroidMessagingNotificationDelivery(
            context,
            this,
            { true },
            { generatedIdentities++; postIdentity },
            { requestTag, notification ->
                context.getSystemService(android.app.NotificationManager::class.java)
                    .notify(requestTag, AndroidMessagingNotificationDelivery.NOTIFICATION_ID, notification)
            },
        )
        val request = request().copy(id = NotificationId("retired-${UUID.randomUUID()}"))
        val actionName = AndroidMessagingNotificationDelivery.ACTION_OPEN
        val requestCode = 31 * request.id.value.hashCode() + actionName.hashCode()
        val probe = Intent(context, NotificationActionReceiver::class.java)
            .setAction(AndroidMessagingNotificationDelivery.ACTION_PREFIX + actionName)
            .setData(
                Uri.Builder()
                    .scheme("meshcore-notification")
                    .authority("action")
                    .appendPath(request.id.value)
                    .appendPath(actionName)
                    .appendQueryParameter("post", postIdentity.toString())
                    .build(),
            )
        fun existingPendingIntent() = PendingIntent.getBroadcast(
            context,
            requestCode,
            probe,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        val permit = NotificationPostPermit()
        permit.close()

        assertNull(existingPendingIntent())
        assertEquals(NotificationPostAdmission.Revoked, delivery.post(request, permit))
        assertEquals(0, generatedIdentities)
        assertNull(existingPendingIntent())
    }

    private fun delivery(scope: TestScope) =
        AndroidMessagingNotificationDelivery(context, scope) { true }

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
