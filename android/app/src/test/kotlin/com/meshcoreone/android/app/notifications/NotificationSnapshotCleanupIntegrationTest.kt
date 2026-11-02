// AndroidOnly: WP-401 Compose real notification policy and Android delivery across selected-post cleanup.
package com.meshcoreone.android.app.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.AppStateProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.contracts.domain.NotificationPreferencesPort
import com.meshcoreone.android.core.contracts.notifications.DeliveredNotification
import com.meshcoreone.android.core.contracts.notifications.NotificationCategory
import com.meshcoreone.android.core.contracts.notifications.NotificationDeliveryPort
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.contracts.notifications.SnapshotRemovalOutcome
import com.meshcoreone.android.core.model.NotificationPreferences
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.services.notifications.NotificationService
import com.meshcoreone.android.core.services.notifications.NotificationUnreadCounting
import com.meshcoreone.android.platform.notifications.messaging.AndroidMessagingNotificationDelivery
import com.meshcoreone.android.platform.notifications.messaging.NotificationOpenActivity
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], application = Application::class, shadows = [NotificationSnapshotCleanupIntegrationTest.DeferredPublicationShadow::class])
class NotificationSnapshotCleanupIntegrationTest {
    @Test
    @Suppress("DEPRECATION")
    fun realServiceCleanupRetainsReplacementIdentityAndRealTapIncludingDelayedPublication() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val launcher = ComponentName(context.packageName, "${context.packageName}.CleanupTestLauncher")
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
        val delivery = AndroidMessagingNotificationDelivery(context, backgroundScope) { received += it; true }
        delivery.registerCategories(SnapshotList.empty())
        val manager = context.getSystemService(NotificationManager::class.java)
        val publication = Shadow.extract<DeferredPublicationShadow>(manager)
        val contact = EntityKey(RadioId(UUID(0, 401)), UUID(0, 402))
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
            val original = NotificationRequest(
                NotificationId("composed-$delayed"), NotificationCategory.DIRECT_MESSAGE,
                "Peer", "First post", false, null, null,
                NotificationPayload.DirectMessage(contact, UUID(0, 403)),
            )
            assertEquals(NotificationPostResult.Posted, delivery.post(original))
            val cleanup = launch { service.removeDeliveredNotifications(contact.id) }
            val selected = selectedGate.await()
            assertEquals(original.id, selected.single().id)
            val oldIdentity = requireNotNull(selected.single().postIdentity)
            publication.defer = delayed
            val replacementPayload = NotificationPayload.DirectMessage(contact, UUID(0, if (delayed) 405 else 404))
            assertEquals(NotificationPostResult.Posted, delivery.post(original.copy(payload = replacementPayload)))
            val cancellations = publication.cancellations
            resume.complete(Unit)
            cleanup.join()
            assertEquals(listOf(SnapshotRemovalOutcome.SUPERSEDED), requireNotNull(outcomes).toList())
            assertEquals(cancellations, publication.cancellations)
            publication.publish()
            publication.defer = false
            val replacementSnapshot = delivery.deliveredNotifications().single()
            assertNotEquals(oldIdentity, requireNotNull(replacementSnapshot.postIdentity))
            assertEquals(replacementPayload, replacementSnapshot.payload)
            val replacement = manager.activeNotifications.single().notification
            replacement.contentIntent.send()
            val launch = shadowOf(context).nextStartedActivity
            assertEquals(ComponentName(context, NotificationOpenActivity::class.java), launch.component)
            val controller = Robolectric.buildActivity(NotificationOpenActivity::class.java, launch).create()
            try {
                assertEquals(launcher, shadowOf(controller.get()).nextStartedActivity.component)
                runCurrent()
                assertEquals(NotificationResponse(replacementPayload, null, null), received.last())
                assertTrue(manager.activeNotifications.isEmpty())
            } finally {
                controller.destroy()
            }
        }
        assertEquals(2, received.size)
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
