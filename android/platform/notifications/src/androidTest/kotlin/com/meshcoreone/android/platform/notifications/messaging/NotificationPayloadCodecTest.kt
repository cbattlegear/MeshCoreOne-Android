// AndroidOnly: WP-401 Instrument typed action payloads without copying notification text into PendingIntent extras.
package com.meshcoreone.android.platform.notifications.messaging

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationPayloadCodecTest {
    private val radio = RadioId(UUID.fromString("10000000-0000-0000-0000-000000000001"))
    private val other = RadioId(UUID.fromString("20000000-0000-0000-0000-000000000002"))
    private val contact = EntityKey(radio, UUID.fromString("30000000-0000-0000-0000-000000000003"))
    private val session = EntityKey(other, UUID.fromString("40000000-0000-0000-0000-000000000004"))
    private val message = UUID.fromString("50000000-0000-0000-0000-000000000005")

    @Test
    fun everyTypedPayloadRoundTripsWithoutMessageContent() {
        val cases = listOf(
            NotificationPayload.DirectMessage(contact, message),
            NotificationPayload.ChannelMessage(radio, 255u, message),
            NotificationPayload.RoomMessage("Ops", session, message),
            NotificationPayload.NewContact(contact),
            NotificationPayload.Reaction(message, contact, null, null),
            NotificationPayload.Reaction(message, null, 7u, other),
            NotificationPayload.Reaction(message, null, 7u, other, occurrenceId = UUID(0, 42)),
            NotificationPayload.LowBattery(12),
            NotificationPayload.QuickReplyFailed(contact),
            NotificationPayload.ChannelQuickReplyFailed(other, 9u),
        )

        cases.forEach { payload ->
            val intent = NotificationPayloadCodec.write(Intent(), payload)
            assertEquals(payload, NotificationPayloadCodec.read(intent))
            assertEquals(NotificationPayloadCodec.radioId(payload), NotificationPayloadCodec.radioId(requireNotNull(NotificationPayloadCodec.read(intent))))
            assertNull(intent.getStringExtra("body"))
            assertNull(intent.getStringExtra("text"))
        }
    }

    @Test
    fun malformedOrOutOfRangePayloadFailsClosed() {
        assertNull(NotificationPayloadCodec.read(Intent()))
        assertNull(NotificationPayloadCodec.read(Intent().putExtra("mc1.notification.type", "channel")))
        assertNull(
            NotificationPayloadCodec.read(
                Intent()
                    .putExtra("mc1.notification.type", "channel")
                    .putExtra("mc1.notification.radio", radio.value.toString())
                    .putExtra("mc1.notification.channel", 256)
                    .putExtra("mc1.notification.message", message.toString()),
            ),
        )
    }
}
