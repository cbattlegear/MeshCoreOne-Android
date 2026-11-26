// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/NotificationServiceTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.notifications

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.notifications.NotificationCategory
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Original NotificationServiceTests; `NotificationService()` becomes a fake-backed session service. */
class NotificationServiceTests {
    private fun case(name: String, body: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        originalCase("NotificationServiceTests", name, body)

    @TestFactory
    fun suppression(): List<DynamicTest> = listOf(
        case("Suppression flag defaults to false") {
            assertFalse(NotificationHarness(this).service.isSuppressingNotifications)
        },
        case("Suppression flag can be set and cleared") {
            val service = NotificationHarness(this).service
            service.isSuppressingNotifications = true
            assertTrue(service.isSuppressingNotifications)
            service.isSuppressingNotifications = false
            assertFalse(service.isSuppressingNotifications)
        },
        case("Suppression flag can be toggled multiple times") {
            val service = NotificationHarness(this).service
            service.isSuppressingNotifications = true
            service.isSuppressingNotifications = false
            service.isSuppressingNotifications = true
            service.isSuppressingNotifications = true // Setting same value
            service.isSuppressingNotifications = false
            assertFalse(service.isSuppressingNotifications)
        },
    )

    @TestFactory
    fun reactionCallbacks(): List<DynamicTest> = listOf(
        case("onReactionNotificationTapped callback can be set") {
            val service = NotificationHarness(this).service
            var callbackInvoked = false
            service.onReactionNotificationTapped = { _, _, _, _ -> callbackInvoked = true }
            assertNotNull(service.onReactionNotificationTapped)
            service.onReactionNotificationTapped?.invoke(EntityKey(RADIO_A, UUID.randomUUID()), null, null, UUID.randomUUID())
            assertTrue(callbackInvoked)
        },
        case("onReactionNotificationTapped receives all parameters") {
            val service = NotificationHarness(this).service
            val expectedContact = EntityKey(RADIO_A, UUID.randomUUID())
            val expectedChannelIndex: UByte = 5u
            val expectedRadio = RadioId(UUID.randomUUID())
            val expectedMessageID = UUID.randomUUID()
            var received: List<Any?>? = null
            service.onReactionNotificationTapped = { contact, channelIndex, radioId, messageID ->
                received = listOf(contact, channelIndex, radioId, messageID)
            }
            service.onReactionNotificationTapped?.invoke(expectedContact, expectedChannelIndex, expectedRadio, expectedMessageID)
            assertEquals(listOf(expectedContact, expectedChannelIndex, expectedRadio, expectedMessageID), received)
        },
    )

    @TestFactory
    fun roomNotifications(): List<DynamicTest> = listOf(
        case("Room message notification is suppressed when isSuppressingNotifications is true") {
            // Authorized first so the suppression guard, not the permission guard, is what stops the post.
            val harness = NotificationHarness(this).authorize()
            val service = harness.service
            service.isSuppressingNotifications = true
            service.postRoomMessageNotification(
                roomName = "TestRoom", sessionID = UUID.randomUUID(), senderName = "Alice", messageText = "Hello",
                messageID = UUID.randomUUID(), notificationLevel = NotificationLevel.ALL,
            )
            assertEquals(emptyList(), harness.delivery.posted)
            // Badge count should not increment when suppressed.
            assertEquals(0L, service.badgeCount)
            assertEquals(emptyList(), harness.delivery.badgeSets)
        },
        case("Notification category includes reaction") {
            assertEquals("REACTION", NotificationCategory.REACTION.rawValue)
        },
        case("Active room session tracking can be set and cleared") {
            val service = NotificationHarness(this).service
            val sessionID = UUID.randomUUID()
            assertNull(service.activeRoomSessionID)
            service.activeRoomSessionID = sessionID
            assertEquals(sessionID, service.activeRoomSessionID)
            service.activeRoomSessionID = null
            assertNull(service.activeRoomSessionID)
        },
        case("setActiveConversation populates only the passed slot and clears the rest") {
            val service = NotificationHarness(this).service
            val contactID = UUID.randomUUID()
            val channelRadio = RadioId(UUID.randomUUID())
            val roomSessionID = UUID.randomUUID()

            // Pre-populate every slot so the setter must clear the unpassed ones.
            service.activeContactID = contactID
            service.activeChannelIndex = 3u
            service.activeChannelRadioId = channelRadio
            service.activeRoomSessionID = roomSessionID

            // Opening a DM clears channel and room slots.
            service.setActiveConversation(contactID = contactID)
            assertEquals(ActiveConversation(contactID = contactID), service.activeConversation)
            assertEquals(contactID, service.activeContactID)
            assertNull(service.activeChannelIndex)
            assertNull(service.activeChannelRadioId)
            assertNull(service.activeRoomSessionID)

            // Opening a channel clears the contact slot.
            service.setActiveConversation(channelIndex = 5u, channelRadioId = channelRadio)
            assertNull(service.activeContactID)
            assertEquals(5.toUByte(), service.activeChannelIndex)
            assertEquals(channelRadio, service.activeChannelRadioId)
            assertNull(service.activeRoomSessionID)

            // Opening a room clears the channel slots.
            service.setActiveConversation(roomSessionID = roomSessionID)
            assertNull(service.activeContactID)
            assertNull(service.activeChannelIndex)
            assertNull(service.activeChannelRadioId)
            assertEquals(roomSessionID, service.activeRoomSessionID)
        },
        case("onRoomMarkAsRead callback can be set and receives parameters") {
            val service = NotificationHarness(this).service
            val expectedSession = EntityKey(RADIO_A, UUID.randomUUID())
            val expectedMessageID = UUID.randomUUID()
            var received: Pair<EntityKey, UUID>? = null
            service.onRoomMarkAsRead = { session, messageID -> received = session to messageID }
            assertNotNull(service.onRoomMarkAsRead)
            service.onRoomMarkAsRead?.invoke(expectedSession, expectedMessageID)
            assertEquals(expectedSession to expectedMessageID, received)
        },
        case("onRoomNotificationTapped callback can be set and receives the session ID") {
            val service = NotificationHarness(this).service
            val expectedSession = EntityKey(RADIO_A, UUID.randomUUID())
            var received: EntityKey? = null
            service.onRoomNotificationTapped = { session -> received = session }
            assertNotNull(service.onRoomNotificationTapped)
            service.onRoomNotificationTapped?.invoke(expectedSession)
            assertEquals(expectedSession, received)
        },
    )
}
