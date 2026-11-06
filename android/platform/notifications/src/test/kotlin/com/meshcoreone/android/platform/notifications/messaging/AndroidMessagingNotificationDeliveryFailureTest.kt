// AndroidOnly: WP-401 Verify per-post action identity and uncertain platform post failures.
package com.meshcoreone.android.platform.notifications.messaging

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.contracts.notifications.DeliveredPostIdentity
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationActionDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationCategory
import com.meshcoreone.android.core.contracts.notifications.NotificationCategoryDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationPostAdmission
import com.meshcoreone.android.core.contracts.notifications.NotificationPostAttempt
import com.meshcoreone.android.core.contracts.notifications.NotificationPostPermit
import com.meshcoreone.android.core.contracts.notifications.NotificationPostRejection
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.contracts.notifications.SnapshotRemovalOutcome
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidMessagingNotificationDeliveryFailureTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val contact = EntityKey(
        RadioId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
        UUID.fromString("20000000-0000-0000-0000-000000000002"),
    )
    private val payloadA = NotificationPayload.DirectMessage(
        contact,
        UUID.fromString("30000000-0000-0000-0000-000000000003"),
    )
    private val payloadB = NotificationPayload.DirectMessage(
        contact,
        UUID.fromString("30000000-0000-0000-0000-000000000004"),
    )
    private val postIdentityA = UUID.fromString("40000000-0000-0000-0000-000000000004")
    private val postIdentityB = UUID.fromString("50000000-0000-0000-0000-000000000005")

    @Before
    fun resetDispatcher() {
        NotificationActionDispatcher.resetForTesting()
    }

    @After
    fun cleanup() {
        NotificationActionDispatcher.resetForTesting()
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    @Test
    fun failedSameIdReplacementKeepsOldActionAndReportsUncertainPost() = runTest {
        replacementFailure(afterNotify = false, SecurityException("notification post failed after build"))
    }

    @Test
    fun runtimeFailureBeforeNotifyKeepsOldActionAndReportsUncertainPost() = runTest {
        replacementFailure(afterNotify = false, IllegalStateException("notification post failed after build"))
    }

    @Test
    fun securityFailureAfterNotifyDoesNotMisidentifyTheVisibleReplacement() = runTest {
        replacementFailure(afterNotify = true, SecurityException("notification post failed after build"))
    }

    @Test
    fun runtimeFailureAfterNotifyDoesNotMisidentifyTheVisibleReplacement() = runTest {
        replacementFailure(afterNotify = true, IllegalStateException("notification post failed after build"))
    }

    private suspend fun TestScope.replacementFailure(afterNotify: Boolean, failure: Exception) {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val responses = mutableListOf<NotificationResponse>()
        var identityIndex = 0
        var postCount = 0
        var failedBuild: Notification? = null
        val delivery = AndroidMessagingNotificationDelivery(
            context,
            this,
            { response ->
                responses += response
                true
            },
            { if (identityIndex++ == 0) postIdentityA else postIdentityB },
            { tag, notification ->
                postCount += 1
                if (postCount == 2) {
                    failedBuild = notification
                    if (afterNotify) {
                        manager.notify(tag, AndroidMessagingNotificationDelivery.NOTIFICATION_ID, notification)
                    }
                    throw failure
                }
                manager.notify(tag, AndroidMessagingNotificationDelivery.NOTIFICATION_ID, notification)
            },
        )
        registerMessageActions(delivery)
        val originalRequest = request(payloadA)
        val replacementRequest = request(payloadB)

        assertEquals(NotificationPostResult.Posted, delivery.post(originalRequest))
        val originalSnapshot = delivery.deliveredNotifications()
        val visibleBefore = manager.activeNotifications.single().notification
        val originalReply = visibleBefore.actions.first()
        val originalMarkRead = visibleBefore.actions[1]
        assertEquals(
            postIdentityA.toString(),
            visibleBefore.extras.getString(AndroidMessagingNotificationDelivery.EXTRA_POST_ID),
        )
        val originalContentIntent = shadowOf(requireNotNull(visibleBefore.contentIntent)).savedIntent
        val originalReplyIntent = shadowOf(originalReply.actionIntent).savedIntent
        val originalMarkReadIntent = shadowOf(originalMarkRead.actionIntent).savedIntent
        assertEquals(postIdentityA.toString(), originalContentIntent.data?.getQueryParameter("post"))
        assertEquals(postIdentityA.toString(), originalReplyIntent.data?.getQueryParameter("post"))
        assertEquals(postIdentityA.toString(), originalMarkReadIntent.data?.getQueryParameter("post"))

        val replacement = delivery.post(replacementRequest, NotificationPostPermit())
        val attempt = (replacement as NotificationPostAdmission.Admitted).value as NotificationPostAttempt.Uncertain
        assertSame(failure, attempt.failure)

        val attemptedNotification = requireNotNull(failedBuild)
        assertEquals(
            postIdentityB.toString(),
            attemptedNotification.extras.getString(AndroidMessagingNotificationDelivery.EXTRA_POST_ID),
        )
        val attemptedContentIntent = shadowOf(requireNotNull(attemptedNotification.contentIntent)).savedIntent
        val attemptedReplyIntent = shadowOf(attemptedNotification.actions.first().actionIntent).savedIntent
        val attemptedMarkReadIntent = shadowOf(attemptedNotification.actions[1].actionIntent).savedIntent
        assertEquals(postIdentityB.toString(), attemptedContentIntent.data?.getQueryParameter("post"))
        assertEquals(postIdentityB.toString(), attemptedReplyIntent.data?.getQueryParameter("post"))
        assertEquals(postIdentityB.toString(), attemptedMarkReadIntent.data?.getQueryParameter("post"))
        assertNotEquals(originalContentIntent.data, attemptedContentIntent.data)
        assertNotEquals(originalReplyIntent.data, attemptedReplyIntent.data)
        assertNotEquals(originalMarkReadIntent.data, attemptedMarkReadIntent.data)

        val visibleAfter = manager.activeNotifications.single().notification
        val expectedVisible = if (afterNotify) attemptedNotification else visibleBefore
        val expectedPayload = if (afterNotify) payloadB else payloadA
        val expectedIdentity = if (afterNotify) postIdentityB else postIdentityA
        assertEquals(expectedPayload, NotificationPayloadCodec.read(Intent().putExtras(visibleAfter.extras)))
        assertEquals(
            shadowOf(requireNotNull(expectedVisible.contentIntent)).savedIntent.data,
            shadowOf(requireNotNull(visibleAfter.contentIntent)).savedIntent.data,
        )
        assertEquals(
            shadowOf(expectedVisible.actions.first().actionIntent).savedIntent.data,
            shadowOf(visibleAfter.actions.first().actionIntent).savedIntent.data,
        )
        assertEquals(
            shadowOf(expectedVisible.actions[1].actionIntent).savedIntent.data,
            shadowOf(visibleAfter.actions[1].actionIntent).savedIntent.data,
        )
        val readback = delivery.deliveredNotifications().single()
        assertEquals(expectedPayload, readback.payload)
        assertEquals(DeliveredPostIdentity(expectedIdentity), readback.postIdentity)
        assertEquals(
            listOf(SnapshotRemovalOutcome.UNKNOWN_IDENTITY),
            delivery.removeDeliveredSnapshot(originalSnapshot).toList(),
        )
        assertEquals(1, manager.activeNotifications.size)

        val fillIn = Intent()
        RemoteInput.addResultsToIntent(
            requireNotNull(originalReply.remoteInputs),
            fillIn,
            Bundle().apply {
                putCharSequence(NotificationActionReceiver.REMOTE_INPUT_KEY, "reply to A")
            },
        )
        val previousBroadcastCount = shadowOf(context).broadcastIntents.size
        originalReply.actionIntent.send(context, 0, fillIn)
        val deliveredAction = shadowOf(context).broadcastIntents.last()
        assertEquals(previousBroadcastCount + 1, shadowOf(context).broadcastIntents.size)
        assertEquals(payloadA, NotificationPayloadCodec.read(deliveredAction))
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(payloadA, responses.single().payload)
        assertEquals(NotificationAction.REPLY, responses.single().action)
        assertEquals("reply to A", responses.single().userText)
    }

    @Test
    fun buildFailureAfterContentTokenCreationLeavesOldPostAndTokensUntouched() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val failure = IllegalStateException("label unavailable after content token creation")
        var failLabel = false
        val failingApplicationInfo = object : ApplicationInfo(context.applicationInfo) {
            override fun loadLabel(pm: PackageManager): CharSequence {
                if (failLabel) throw failure
                return super.loadLabel(pm)
            }
        }
        val wrappedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo(): ApplicationInfo = failingApplicationInfo
        }
        var identities = 0
        var posts = 0
        val responses = mutableListOf<NotificationResponse>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val delivery = AndroidMessagingNotificationDelivery(
            wrappedContext,
            backgroundScope,
            { response ->
                responses += response
                true
            },
            { if (identities++ == 0) postIdentityA else postIdentityB },
            { tag, notification ->
                posts++
                manager.notify(tag, AndroidMessagingNotificationDelivery.NOTIFICATION_ID, notification)
            },
        )
        registerMessageActions(delivery)
        assertEquals(NotificationPostResult.Posted, delivery.post(request(payloadA)))
        val original = manager.activeNotifications.single().notification
        val snapshot = delivery.deliveredNotifications()
        failLabel = true

        val result = delivery.post(request(payloadB), NotificationPostPermit())
        val attempt = (result as NotificationPostAdmission.Admitted).value
            as NotificationPostAttempt.RejectedBeforeDispatch
        assertEquals(NotificationPostRejection.REQUEST_BUILD_FAILED, attempt.reason)
        assertSame(failure, attempt.cause)
        assertEquals(1, posts)
        assertNotNull(findPendingIntent(request(payloadB), null, postIdentityB))
        assertEquals(payloadB, NotificationPayloadCodec.read(
            shadowOf(requireNotNull(findPendingIntent(request(payloadB), null, postIdentityB))).savedIntent,
        ))
        assertEquals(payloadA, NotificationPayloadCodec.read(
            shadowOf(original.actions.first().actionIntent).savedIntent,
        ))
        assertEquals(payloadA, NotificationPayloadCodec.read(
            shadowOf(original.actions[1].actionIntent).savedIntent,
        ))
        assertEquals(payloadA, NotificationPayloadCodec.read(
            Intent().putExtras(manager.activeNotifications.single().notification.extras),
        ))
        assertEquals(snapshot, delivery.deliveredNotifications())
        val fillIn = Intent()
        RemoteInput.addResultsToIntent(
            requireNotNull(original.actions.first().remoteInputs),
            fillIn,
            Bundle().apply {
                putCharSequence(NotificationActionReceiver.REMOTE_INPUT_KEY, "A after failed build")
            },
        )
        original.actions.first().actionIntent.send(context, 0, fillIn)
        original.actions[1].actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(
            listOf(
                NotificationResponse(payloadA, NotificationAction.REPLY, "A after failed build"),
                NotificationResponse(payloadA, NotificationAction.MARK_READ, null),
            ),
            responses,
        )
    }

    @Test
    fun revokedPermitDoesNotGenerateAnyPostIdentityOrPendingIntent() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var identities = 0
        var posts = 0
        val delivery = AndroidMessagingNotificationDelivery(
            context,
            backgroundScope,
            { true },
            { identities++; postIdentityA },
            { _, _ -> posts++ },
        )
        registerMessageActions(delivery)
        val request = request(payloadA)
        val permit = NotificationPostPermit().also { it.close() }

        assertEquals(NotificationPostAdmission.Revoked, delivery.post(request, permit))
        assertEquals(0, identities)
        assertEquals(0, posts)
        assertNull(findPendingIntent(request, null, postIdentityA))
        assertNull(findPendingIntent(request, NotificationAction.REPLY, postIdentityA))
        assertNull(findPendingIntent(request, NotificationAction.MARK_READ, postIdentityA))
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    private suspend fun registerMessageActions(delivery: AndroidMessagingNotificationDelivery) {
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
    }

    private fun findPendingIntent(
        request: NotificationRequest,
        action: NotificationAction?,
        postIdentity: UUID,
    ): PendingIntent? {
        val actionName = action?.rawValue ?: AndroidMessagingNotificationDelivery.ACTION_OPEN
        val intent = Intent(context, NotificationActionReceiver::class.java)
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
        val mutability = if (action == NotificationAction.REPLY) {
            PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(
            context,
            31 * request.id.value.hashCode() + actionName.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or mutability,
        )
    }

    private fun request(payload: NotificationPayload) = NotificationRequest(
        id = NotificationId("same-notification"),
        category = NotificationCategory.DIRECT_MESSAGE,
        title = "Sender",
        body = "Message",
        soundEnabled = false,
        badge = null,
        threadIdentifier = null,
        payload = payload,
    )
}
