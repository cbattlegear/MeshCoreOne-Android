// AndroidOnly: WP-215 Native cases for action transactions, cold-start/stale-radio guards, reactions and Swift text semantics.
package com.meshcoreone.android.core.services.notifications

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.contracts.domain.UnreadCounts
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

private val QUICK_REPLY_STEPS = listOf("send", "clearUnreadCount", "removeDelivered", "setBadgeCount", "notifyConversationsChanged")
private val CHANNEL_REPLY_STEPS =
    listOf("send", "clearChannelUnreadCount", "removeDelivered", "setBadgeCount", "notifyConversationsChanged")
private val MARK_READ_STEPS =
    listOf("markMessageAsRead", "clearUnreadCount", "removeDelivered", "setBadgeCount", "notifyConversationsChanged")

class NotificationTransactionNativeTests {
    private val messageID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")

    private fun NotificationHarness.ready(localNodeName: String? = "Me"): NotificationHarness =
        also { handler.configure(isConnectionReady = { true }, localNodeName = { localNodeName }) }

    @TestFactory
    fun directQuickReply(): List<DynamicTest> = listOf(
        nativeCase("quick reply when ready sends, clears unread, removes delivered, refreshes badge and conversations") {
            val h = NotificationHarness(this).authorize().ready()
            val contact = h.contact()
            h.delivery.show("dm", NotificationPayload.DirectMessage(contact.key, messageID))
            h.counts.counts = UnreadCounts(0, 2, 0)
            h.handler.handleQuickReply(contact.key, "on my way")
            assertEquals(listOf("on my way" to contact.id), h.sender.sends)
            assertEquals(QUICK_REPLY_STEPS, h.journal.events)
            assertEquals(listOf("clearUnreadCount" to contact.key), h.store.writes)
            assertEquals(emptyList(), h.delivery.shownIds)
            assertEquals(listOf(2L), h.delivery.badgeSets)
            assertEquals(1, h.sync.notifications)
            assertNull(h.service.consumeDraft(contact.key))
            assertEquals(emptyList(), h.delivery.posted)
        },
        nativeCase("cold start: a reply before configure() saves a draft and reports not sent without sending") {
            val h = NotificationHarness(this).authorize()
            val contact = h.contact(nickname = "Ally")
            h.handler.handleQuickReply(contact.key, "draft me")
            assertEquals(emptyList(), h.sender.sends)
            assertEquals("draft me", h.service.consumeDraft(contact.key))
            val failure = h.delivery.posted.single()
            assertEquals("Your reply to Ally couldn't be sent.", failure.body)
            assertEquals(NotificationPayload.QuickReplyFailed(contact.key), failure.payload)
            assertEquals(0, h.sync.notifications)
        },
        nativeCase("a reply while the connection is not ready behaves as Swift: draft plus failure notification") {
            val h = NotificationHarness(this).authorize()
            h.handler.configure(isConnectionReady = { false }, localNodeName = { "Me" })
            val contact = h.contact()
            h.handler.handleQuickReply(contact.key, "later")
            assertEquals(emptyList(), h.sender.sends)
            assertEquals("later", h.service.consumeDraft(contact.key))
            assertEquals(1, h.delivery.posted.size)
        },
        nativeCase("a failing readiness check counts as not ready") {
            val h = NotificationHarness(this).authorize()
            h.handler.configure(isConnectionReady = { throw IllegalStateException("state gone") }, localNodeName = { null })
            val contact = h.contact()
            h.handler.handleQuickReply(contact.key, "x")
            assertEquals(emptyList(), h.sender.sends)
            assertEquals("x", h.service.consumeDraft(contact.key))
        },
        nativeCase("a caller cancelled right after a successful send still finishes the bookkeeping") {
            val h = NotificationHarness(this).authorize().ready()
            val contact = h.contact()
            h.delivery.show("dm", NotificationPayload.DirectMessage(contact.key, messageID))
            h.journal.cancelCallerAt = "send"
            val caller = launch { h.handler.handleQuickReply(contact.key, "sent") }
            caller.join()
            assertTrue(caller.isCancelled)
            assertEquals(QUICK_REPLY_STEPS, h.journal.events)
            assertNull(h.service.consumeDraft(contact.key))
        },
        nativeCase("cancellation during the send keeps the typed text as a draft, reports not sent, then propagates") {
            val h = NotificationHarness(this).authorize().ready()
            h.sender.cancel = true
            val contact = h.contact()
            assertFailsWith<CancellationException> { h.handler.handleQuickReply(contact.key, "keep me") }
            assertEquals("keep me", h.service.consumeDraft(contact.key))
            assertEquals(NotificationPayload.QuickReplyFailed(contact.key), h.delivery.posted.single().payload)
            assertEquals(0, h.sync.notifications)
        },
        nativeCase("a failed send saves a draft and reports not sent") {
            val h = NotificationHarness(this).authorize().ready()
            h.sender.fail = true
            val contact = h.contact()
            h.handler.handleQuickReply(contact.key, "retry")
            assertEquals(1, h.sender.sends.size)
            assertEquals("retry", h.service.consumeDraft(contact.key))
            assertEquals(1, h.delivery.posted.size)
            assertEquals(emptyList(), h.store.writes)
        },
        nativeCase("an unknown or unreadable contact is ignored") {
            val h = NotificationHarness(this).authorize().ready()
            val missing = EntityKey(RADIO_A, UUID.randomUUID())
            h.handler.handleQuickReply(missing, "x")
            h.store.failing += "fetchContact"
            h.handler.handleQuickReply(h.contact().key, "x")
            assertEquals(emptyList(), h.sender.sends)
            assertEquals(emptyList(), h.delivery.posted)
        },
        nativeCase("stale radio: a reply for another radio's contact is never sent from this radio") {
            val h = NotificationHarness(this, radioId = RADIO_B).authorize().ready()
            val contact = h.contact(radioId = RADIO_A)
            h.handler.handleQuickReply(contact.key, "wrong radio")
            assertEquals(emptyList(), h.sender.sends)
            assertEquals(emptyList(), h.store.writes)
            assertEquals("wrong radio", h.service.consumeDraft(contact.key))
            // The failure notification keeps the contact's own radio, not the session's.
            assertEquals(NotificationPayload.QuickReplyFailed(contact.key), h.delivery.posted.single().payload)
        },
        nativeCase("follow-up failures after a sent reply are contained and never report not sent") {
            val h = NotificationHarness(this).authorize().ready()
            h.store.failing += "clearUnreadCount"
            h.sync.fail = true
            h.delivery.failing += "deliveredNotifications"
            val contact = h.contact()
            h.handler.handleQuickReply(contact.key, "sent")
            assertEquals(1, h.sender.sends.size)
            assertEquals(emptyList(), h.delivery.posted)
            assertNull(h.service.consumeDraft(contact.key))
            assertEquals(1, h.delivery.badgeSets.size)
        },
        nativeCase("notification permission denial never blocks a quick reply") {
            val h = NotificationHarness(this).ready()
            h.delivery.postResult = NotificationPostResult.PermissionDenied
            val contact = h.contact()
            h.handler.handleQuickReply(contact.key, "still sent")
            assertEquals(1, h.sender.sends.size)
            assertEquals(listOf("clearUnreadCount" to contact.key), h.store.writes)
            assertEquals(1, h.sync.notifications)
        },
        nativeCase("cancellation from a collaborator propagates instead of being swallowed") {
            val h = NotificationHarness(this).authorize().ready()
            h.store.cancelling = "fetchContact"
            assertFailsWith<CancellationException> { h.handler.handleQuickReply(h.contact().key, "x") }
            assertEquals(emptyList(), h.delivery.posted)
        },
    )

    @TestFactory
    fun channelQuickReply(): List<DynamicTest> = listOf(
        nativeCase("channel reply when ready sends, clears channel unread, removes delivered, refreshes") {
            val h = NotificationHarness(this).authorize().ready()
            h.delivery.show("c", NotificationPayload.ChannelMessage(RADIO_A, 1u, messageID))
            h.handler.handleChannelQuickReply(RADIO_A, 1u, "ack")
            assertEquals(listOf("ack" to (RADIO_A to 1.toUByte())), h.sender.sends)
            assertEquals(CHANNEL_REPLY_STEPS, h.journal.events)
            assertEquals(listOf("clearChannelUnreadCount" to (RADIO_A to 1.toUByte())), h.store.writes)
            assertEquals(emptyList(), h.delivery.shownIds)
            assertEquals(1, h.delivery.badgeSets.size)
            assertEquals(1, h.sync.notifications)
        },
        nativeCase("a channel reply whose caller is cancelled after sending still finishes the bookkeeping") {
            val h = NotificationHarness(this).authorize().ready()
            h.delivery.show("c", NotificationPayload.ChannelMessage(RADIO_A, 1u, messageID))
            h.journal.cancelCallerAt = "send"
            val caller = launch { h.handler.handleChannelQuickReply(RADIO_A, 1u, "ack") }
            caller.join()
            assertTrue(caller.isCancelled)
            assertEquals(CHANNEL_REPLY_STEPS, h.journal.events)
        },
        nativeCase("cancellation during a channel send reports not sent, then propagates") {
            val h = NotificationHarness(this).authorize().ready()
            h.sender.cancel = true
            assertFailsWith<CancellationException> { h.handler.handleChannelQuickReply(RADIO_A, 1u, "x") }
            assertEquals(NotificationPayload.ChannelQuickReplyFailed(RADIO_A, 1u), h.delivery.posted.single().payload)
            assertEquals(0, h.sync.notifications)
        },
        nativeCase("channel reply not ready or failing posts a failure with the display-name fallback and no draft") {
            val h = NotificationHarness(this).authorize()
            h.store.channels[RADIO_A to 4.toUByte()] = ChannelDTO(radioId = RADIO_A, index = 4u, name = "Ops")
            h.handler.handleChannelQuickReply(RADIO_A, 4u, "x")
            h.ready()
            h.sender.fail = true
            h.handler.handleChannelQuickReply(RADIO_A, 5u, "y")
            assertEquals(listOf("Your reply to Ops couldn't be sent.", "Your reply to Channel 5 couldn't be sent."), h.delivery.posted.map { it.body })
            assertEquals(NotificationPayload.ChannelQuickReplyFailed(RADIO_A, 5u), h.delivery.posted[1].payload)
            assertEquals(1, h.sender.sends.size)
            assertEquals(emptyList(), h.store.writes)
            assertEquals(0, h.sync.notifications)
        },
        nativeCase("stale radio: a channel reply for another radio is reported not sent and never transmitted") {
            val h = NotificationHarness(this, radioId = RADIO_B).authorize().ready()
            h.handler.handleChannelQuickReply(RADIO_A, 0u, "wrong radio")
            assertEquals(emptyList(), h.sender.sends)
            assertEquals(emptyList(), h.store.writes)
            assertEquals(NotificationPayload.ChannelQuickReplyFailed(RADIO_A, 0u), h.delivery.posted.single().payload)
        },
    )

    @TestFactory
    fun markAsRead(): List<DynamicTest> = listOf(
        nativeCase("direct mark-read writes the message and contact of the action's radio, then refreshes") {
            val h = NotificationHarness(this)
            val contact = EntityKey(RADIO_A, UUID.randomUUID())
            h.delivery.show("AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE", NotificationPayload.DirectMessage(contact, messageID))
            h.handler.handleMarkAsRead(contact, messageID)
            assertEquals(listOf("markMessageAsRead" to EntityKey(RADIO_A, messageID), "clearUnreadCount" to contact), h.store.writes)
            assertEquals(MARK_READ_STEPS, h.journal.events)
            assertEquals(emptyList(), h.delivery.shownIds)
            assertEquals(1, h.delivery.badgeSets.size)
            assertEquals(1, h.sync.notifications)
        },
        nativeCase("a caller cancelled after the mark-read writes still removes, refreshes the badge and notifies") {
            val h = NotificationHarness(this)
            val contact = EntityKey(RADIO_A, UUID.randomUUID())
            h.delivery.show("AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE", NotificationPayload.DirectMessage(contact, messageID))
            h.journal.cancelCallerAt = "clearUnreadCount"
            val caller = launch(start = CoroutineStart.LAZY) { h.handler.handleMarkAsRead(contact, messageID) }
            h.journal.cancellationTarget = caller
            caller.start()
            caller.join()
            assertTrue(caller.isCancelled)
            assertEquals(MARK_READ_STEPS, h.journal.events)
            val room = NotificationHarness(this)
            room.delivery.show("AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE", NotificationPayload.RoomMessage("R", contact, messageID))
            room.journal.cancelCallerAt = "roomMarkAsRead"
            val roomCaller = launch(start = CoroutineStart.LAZY) { room.handler.handleRoomMarkAsRead(contact, messageID) }
            room.journal.cancellationTarget = roomCaller
            roomCaller.start()
            roomCaller.join()
            assertTrue(roomCaller.isCancelled)
            assertEquals(listOf("roomMarkAsRead", "removeDelivered", "setBadgeCount", "notifyConversationsChanged"), room.journal.events)
        },
        nativeCase("stale radio: mark-read from another radio writes only that radio's rows") {
            val h = NotificationHarness(this, radioId = RADIO_B)
            val contact = EntityKey(RADIO_A, UUID.randomUUID())
            h.handler.handleMarkAsRead(contact, messageID)
            h.handler.handleChannelMarkAsRead(RADIO_A, 3u, messageID)
            h.handler.handleRoomMarkAsRead(EntityKey(RADIO_A, contact.id), messageID)
            val radios = h.store.writes.map { (_, argument) ->
                when (argument) {
                    is EntityKey -> argument.radioId
                    is Pair<*, *> -> argument.first as RadioId
                    else -> error("unexpected $argument")
                }
            } + h.rooms.marked.map { it.radioId }
            assertEquals(5, radios.size)
            assertTrue(radios.all { it == RADIO_A })
        },
        nativeCase("a failing mark-read write stops the transaction silently, as Swift's do/catch") {
            val h = NotificationHarness(this)
            h.store.failing += "markMessageAsRead"
            h.handler.handleMarkAsRead(EntityKey(RADIO_A, UUID.randomUUID()), messageID)
            h.handler.handleChannelMarkAsRead(RADIO_A, 1u, messageID)
            assertEquals(listOf("markMessageAsRead", "markMessageAsRead"), h.store.writes.map { it.first })
            assertEquals(emptyList(), h.delivery.removals)
            assertEquals(0, h.sync.notifications)
        },
        nativeCase("channel mark-read writes message and channel unread, then refreshes") {
            val h = NotificationHarness(this)
            h.handler.handleChannelMarkAsRead(RADIO_A, 6u, messageID)
            assertEquals(
                listOf("markMessageAsRead" to EntityKey(RADIO_A, messageID), "clearChannelUnreadCount" to (RADIO_A to 6.toUByte())),
                h.store.writes,
            )
            assertEquals(1, h.delivery.removals.size)
            assertEquals(1, h.sync.notifications)
            assertEquals(
                listOf("markMessageAsRead", "clearChannelUnreadCount", "removeDelivered", "setBadgeCount", "notifyConversationsChanged"),
                h.journal.events,
            )
        },
        nativeCase("room mark-read resets the session through the room service; a failure stops the refresh") {
            val h = NotificationHarness(this)
            val session = EntityKey(RADIO_A, UUID.randomUUID())
            h.handler.handleRoomMarkAsRead(session, messageID)
            assertEquals(listOf(session), h.rooms.marked)
            assertEquals(1, h.sync.notifications)
            h.rooms.fail = true
            h.handler.handleRoomMarkAsRead(session, messageID)
            assertEquals(1, h.sync.notifications)
            assertEquals(1, h.delivery.removals.size)
        },
    )

    private fun NotificationHarness.outgoing(text: String = "Hello there", contactID: UUID? = null, channelIndex: UByte? = null,
                                             direction: MessageDirection = MessageDirection.OUTGOING): MessageDTO {
        val message = MessageDTO(messageID, RADIO_A, contactID, channelIndex, text, 1u, direction = direction)
        store.messages[EntityKey(RADIO_A, messageID)] = message
        return message
    }

    private fun NotificationHarness.react(sender: String, emoji: String = "👍") {
        store.reactions[EntityKey(RADIO_A, messageID)] = listOf(
            ReactionDTO(messageID = messageID, emoji = emoji, senderName = sender, messageHash = "h", rawText = "r", radioId = RADIO_A),
        )
    }

    @TestFactory
    fun reactions(): List<DynamicTest> = listOf(
        nativeCase("reaction before configure() is suppressed without reading the store") {
            val h = NotificationHarness(this).authorize()
            h.handler.handleReactionNotification(messageID)
            assertEquals(emptyList(), h.store.calls)
            assertEquals(emptyList(), h.delivery.posted)
        },
        nativeCase("reaction to an outgoing DM posts the English body with the truncated preview") {
            val h = NotificationHarness(this).authorize().ready()
            val contact = h.contact()
            h.outgoing(text = "z".repeat(60), contactID = contact.id)
            h.react("Bob")
            h.handler.handleReactionNotification(messageID)
            val request = h.delivery.posted.single()
            assertEquals("Bob", request.title)
            assertEquals("Reacted 👍 to your message: \"${"z".repeat(47)}...\"", request.body)
            assertEquals(NotificationPayload.Reaction(messageID, contact.key, null, null), request.payload)
            assertEquals(1L, h.store.calls.single { it.first == "fetchReactions" }.let { (it.second as Pair<*, *>).second })
        },
        nativeCase("channel reaction carries the message radio and uses the provider body") {
            val h = NotificationHarness(this).authorize().ready(localNodeName = null)
            h.service.setStringProvider(MockStringProvider())
            h.store.channels[RADIO_A to 2.toUByte()] = ChannelDTO(radioId = RADIO_A, index = 2u, name = "Ops")
            h.outgoing(channelIndex = 2u)
            h.react("Me")
            h.handler.handleReactionNotification(messageID)
            val request = h.delivery.posted.single()
            assertEquals("Mock reacted 👍 to Hello there", request.body)
            assertEquals(NotificationPayload.Reaction(messageID, null, 2u, RADIO_A), request.payload)
        },
        nativeCase("incoming messages, missing reactions, self reactions and muted conversations post nothing") {
            val h = NotificationHarness(this).authorize().ready(localNodeName = "Zo\u00EB")
            h.outgoing(direction = MessageDirection.INCOMING)
            h.react("Bob")
            h.handler.handleReactionNotification(messageID)
            h.outgoing()
            h.store.reactions.clear()
            h.handler.handleReactionNotification(messageID)
            // Canonically equivalent (decomposed) spelling of the local node name is a self reaction in Swift.
            h.react("Zoë")
            h.handler.handleReactionNotification(messageID)
            h.react("Bob")
            h.outgoing(contactID = h.contact(muted = true).id)
            h.handler.handleReactionNotification(messageID)
            h.store.channels[RADIO_A to 1.toUByte()] =
                ChannelDTO(radioId = RADIO_A, index = 1u, name = "Q", notificationLevel = NotificationLevel.MUTED)
            h.outgoing(channelIndex = 1u)
            h.handler.handleReactionNotification(messageID)
            assertEquals(emptyList(), h.delivery.posted)
        },
        nativeCase("a failing node-name closure suppresses; unreadable mute state counts as unmuted") {
            val h = NotificationHarness(this).authorize()
            h.handler.configure(isConnectionReady = { true }, localNodeName = { throw IllegalStateException("device gone") })
            h.outgoing(contactID = UUID.randomUUID())
            h.react("Bob")
            h.handler.handleReactionNotification(messageID)
            assertEquals(emptyList(), h.delivery.posted)
            h.ready()
            h.store.failing += "fetchContact"
            h.handler.handleReactionNotification(messageID)
            assertEquals(1, h.delivery.posted.size)
        },
    )

    @TestFactory
    fun swiftText(): List<DynamicTest> {
        val family = "👨‍👩‍👧"
        val flag = "🇺🇸"
        val eAcute = "é"
        // Expected Character/UTF-16 counts from wp215_text_oracle.swift.txt (swiftc 6.3.2).
        fun check(text: String, previewCharacters: Int, previewUtf16: Int, unchanged: Boolean) {
            val preview = NotificationActionHandler.reactionPreview(text)
            assertEquals(previewCharacters, NotificationSwiftText.characterCount(preview))
            assertEquals(previewUtf16, preview.length)
            assertEquals(unchanged, preview == text)
        }
        return listOf(
            nativeCase("preview truncation counts grapheme clusters exactly as Swift Character") {
                check(family.repeat(50), 50, 400, unchanged = true)
                check(family.repeat(51), 50, 379, unchanged = false)
                check(flag.repeat(51), 50, 191, unchanged = false)
                check(eAcute.repeat(51), 50, 97, unchanged = false)
                check("\r\n".repeat(51), 50, 97, unchanged = false)
                val mixed = "x".repeat(46) + family + eAcute + "yz" + flag
                assertEquals("x".repeat(46) + family + "...", NotificationActionHandler.reactionPreview(mixed))
            },
            nativeCase("node-name comparison is canonical equivalence, not case folding") {
                assertTrue(NotificationSwiftText.equal("é", eAcute))
                assertTrue(NotificationSwiftText.equal("Å", "Å"))
                assertFalse(NotificationSwiftText.equal("Bob", "bob"))
                assertFalse(NotificationSwiftText.equal("ﬁ", "fi"))
            },
        )
    }

    /** Test-local copies of WP-209/WP-214 ports; the adapters compile only if the service keeps their signatures. */
    private interface ContactCleanupNotificationsMirror {
        suspend fun removeDeliveredNotifications(contactId: UUID)
        suspend fun updateBadgeCount()
    }

    private interface SyncNotificationPostingMirror {
        suspend fun postDirectMessageNotification(from: String, contactID: UUID, messageText: String, messageID: UUID, isMuted: Boolean)
        suspend fun postChannelMessageNotification(
            channelName: String, channelIndex: UByte, radioId: RadioId, senderName: String?, messageText: String,
            messageID: UUID, notificationLevel: NotificationLevel, hasSelfMention: Boolean,
        )
        suspend fun postRoomMessageNotification(
            roomName: String, sessionID: UUID, senderName: String?, messageText: String, messageID: UUID,
            notificationLevel: NotificationLevel,
        )
        suspend fun postNewContactNotification(contactName: String, contactID: UUID, contactType: ContactType)
    }

    @TestFactory
    fun collaboratorPorts(): List<DynamicTest> = listOf(
        nativeCase("service satisfies the WP-209 cleanup and WP-214 posting ports with one-line adapters") {
            val h = NotificationHarness(this).authorize()
            val s = h.service
            val cleanup = object : ContactCleanupNotificationsMirror {
                override suspend fun removeDeliveredNotifications(contactId: UUID) = s.removeDeliveredNotifications(contactId = contactId)
                override suspend fun updateBadgeCount() = s.updateBadgeCount()
            }
            val sync = object : SyncNotificationPostingMirror {
                override suspend fun postDirectMessageNotification(from: String, contactID: UUID, messageText: String, messageID: UUID, isMuted: Boolean) =
                    s.postDirectMessageNotification(from = from, contactID = contactID, messageText = messageText, messageID = messageID, isMuted = isMuted)
                override suspend fun postChannelMessageNotification(
                    channelName: String, channelIndex: UByte, radioId: RadioId, senderName: String?, messageText: String,
                    messageID: UUID, notificationLevel: NotificationLevel, hasSelfMention: Boolean,
                ) = s.postChannelMessageNotification(
                    channelName = channelName, channelIndex = channelIndex, radioId = radioId, senderName = senderName,
                    messageText = messageText, messageID = messageID, notificationLevel = notificationLevel, hasSelfMention = hasSelfMention,
                )
                override suspend fun postRoomMessageNotification(
                    roomName: String, sessionID: UUID, senderName: String?, messageText: String, messageID: UUID,
                    notificationLevel: NotificationLevel,
                ) = s.postRoomMessageNotification(
                    roomName = roomName, sessionID = sessionID, senderName = senderName, messageText = messageText,
                    messageID = messageID, notificationLevel = notificationLevel,
                )
                override suspend fun postNewContactNotification(contactName: String, contactID: UUID, contactType: ContactType) =
                    s.postNewContactNotification(contactName = contactName, contactID = contactID, contactType = contactType)
            }
            val contactID = UUID.randomUUID()
            sync.postDirectMessageNotification("A", contactID, "t", messageID, isMuted = false)
            cleanup.removeDeliveredNotifications(contactID)
            cleanup.updateBadgeCount()
            assertEquals(emptyList(), h.delivery.shownIds)
            assertEquals(2, h.delivery.badgeSets.size)
        },
    )
}
