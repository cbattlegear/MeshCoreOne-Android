// AndroidOnly: WP-214 Verify display-target forwarding through the production notification adapter.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.AppStateProvider
import com.meshcoreone.android.core.contracts.domain.UnreadCounts
import com.meshcoreone.android.core.contracts.notifications.ChannelNotificationTarget
import com.meshcoreone.android.core.contracts.notifications.NotificationDeliveryPort
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.services.notifications.NotificationService
import com.meshcoreone.android.core.services.notifications.NotificationUnreadCounting
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SyncDisplayTargetAdapterTest {
    private val radio = RadioId(UUID.fromString("01010101-0000-4000-8000-000000000001"))
    private val messageId = UUID.fromString("02020202-0000-4000-8000-000000000002")
    private val occupantId = UUID.fromString("03030303-0000-4000-8000-000000000003")

    private class AuthorizedDelivery(
        private val backing: RecordingDelivery = RecordingDelivery(),
    ) : NotificationDeliveryPort by backing {
        val posted get() = backing.posted
        override suspend fun requestAuthorization(): Boolean = true
    }

    private suspend fun TestScope.fixture(): Pair<SyncNotificationsAdapter, AuthorizedDelivery> {
        val delivery = AuthorizedDelivery()
        val preferences = FixedNotificationPreferences()
        preferences.update(preferences.preferences.value.copy(badgeEnabled = false))
        val service = NotificationService(
            radio, delivery, preferences,
            object : AppStateProvider { override suspend fun isInForeground() = false },
            NotificationUnreadCounting { UnreadCounts(0, 0, 0) },
            backgroundScope,
        )
        assertTrue(service.requestAuthorization())
        return SyncNotificationsAdapter(service) to delivery
    }

    private suspend fun SyncNotificationsAdapter.post(
        target: ChannelNotificationTarget,
        level: NotificationLevel = NotificationLevel.ALL,
        mention: Boolean = false,
    ) =
        postChannelMessageNotification(
            "Displayed", 2u, radio, "Peer", "hello", messageId, level, mention, target,
        )

    @Test fun forwardsDisplayedIdentityIntoTheRealNotificationPayload() = runTest {
        val (adapter, delivery) = fixture()
        val target = ChannelNotificationTarget.Proven(radio, 2u, occupantId)

        adapter.post(target)

        val request = delivery.posted.single()
        val payload = assertIs<NotificationPayload.ChannelMessage>(request.payload)
        assertEquals("Displayed", request.title)
        assertEquals(radio, payload.radioId)
        assertEquals(2u.toUByte(), payload.channelIndex)
        assertEquals(messageId, payload.messageID)
        assertEquals(target, payload.displayTarget)
    }

    @Test fun forwardsUnprovedWithoutInventingAnOccupant() = runTest {
        val (adapter, delivery) = fixture()

        adapter.post(ChannelNotificationTarget.Unproved)

        val payload = assertIs<NotificationPayload.ChannelMessage>(delivery.posted.single().payload)
        assertEquals(ChannelNotificationTarget.Unproved, payload.displayTarget)
    }

    @Test fun preservesMuteAndMentionOnlyPolicyWithADisplayTarget() = runTest {
        val (adapter, delivery) = fixture()
        val target = ChannelNotificationTarget.Proven(radio, 2u, occupantId)

        adapter.post(target, NotificationLevel.MUTED, true)
        adapter.post(target, NotificationLevel.MENTIONS_ONLY, false)
        assertTrue(delivery.posted.isEmpty())
        adapter.post(target, NotificationLevel.MENTIONS_ONLY, true)

        val payload = assertIs<NotificationPayload.ChannelMessage>(delivery.posted.single().payload)
        assertEquals(target, payload.displayTarget)
    }

    @Test fun rejectsADisplayTargetForAnotherRadioOrSlot() = runTest {
        val (adapter, delivery) = fixture()
        val otherRadio = RadioId(UUID.fromString("04040404-0000-4000-8000-000000000004"))
        val targets = listOf(
            ChannelNotificationTarget.Proven(otherRadio, 2u, occupantId),
            ChannelNotificationTarget.Proven(radio, 3u, occupantId),
        )

        targets.forEach { target -> assertFailsWith<IllegalArgumentException> { adapter.post(target) } }

        assertTrue(delivery.posted.isEmpty())
    }
}
