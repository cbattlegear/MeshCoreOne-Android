// AndroidOnly: WP-401 Preserve notification display snapshots without claiming received-message origin.
package com.meshcoreone.android.core.services.notifications

import com.meshcoreone.android.core.contracts.notifications.ChannelNotificationTarget
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.model.NotificationLevel
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class NotificationDisplayTargetTest {
    @TestFactory
    fun displayTarget(): List<DynamicTest> = listOf(
        nativeCase("channel producer preserves supplied immutable display target") {
            val h = NotificationHarness(this).authorize()
            val target = ChannelNotificationTarget.Proven(RADIO_A, 7u, UUID(0, 71))
            val messageId = UUID(0, 72)
            h.service.postChannelMessageNotification(
                "Displayed A", 7u, RADIO_A, "Sender", "Text", messageId, NotificationLevel.ALL, false, target,
            )
            val request = h.delivery.posted.single()
            assertEquals("Displayed A", request.title)
            assertEquals(NotificationPayload.ChannelMessage(RADIO_A, 7u, messageId, target), request.payload)
        },
        nativeCase("channel reaction remains unproved without authentic display source") {
            val h = NotificationHarness(this).authorize()
            h.service.postReactionNotification("Sender", "Reaction", UUID(0, 73), null, 7u, RADIO_A)
            val payload = h.delivery.posted.single().payload as NotificationPayload.Reaction
            assertEquals(ChannelNotificationTarget.Unproved, payload.displayTarget)
        },
    )
}
