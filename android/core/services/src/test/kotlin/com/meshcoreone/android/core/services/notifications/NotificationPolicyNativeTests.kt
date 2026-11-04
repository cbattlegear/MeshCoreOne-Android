// AndroidOnly: WP-215 Native cases for posting policy, badge policy, foreground presentation, response routing and removal.
package com.meshcoreone.android.core.services.notifications

import com.meshcoreone.android.core.contracts.domain.Capability
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.contracts.domain.UnreadCounts
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationAuthorizationStatus
import com.meshcoreone.android.core.contracts.notifications.NotificationCategory
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.contracts.notifications.SnapshotRemovalOutcome
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class NotificationPolicyNativeTests {
    private val contactID = UUID.fromString("11111111-2222-4333-8444-555555555555")
    private val messageID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")

    private suspend fun NotificationService.postDM(muted: Boolean = false, id: UUID = messageID, contact: UUID = contactID) =
        postDirectMessageNotification("Alice", contact, "Hi", id, muted)

    private suspend fun NotificationService.postChannel(level: NotificationLevel, mention: Boolean, sender: String? = "Bob") =
        postChannelMessageNotification("Public", 2u, RADIO_A, sender, "Hello", messageID, level, mention)

    @TestFactory
    fun directMessagePolicy(): List<DynamicTest> = listOf(
        nativeCase("direct message request carries Swift identifiers, category, thread and next badge") {
            val h = NotificationHarness(this).authorize()
            h.counts.counts = UnreadCounts(3, 0, 0)
            h.service.postDM()
            val request = h.delivery.posted.single()
            assertEquals(NotificationId("AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE"), request.id)
            assertEquals(NotificationCategory.DIRECT_MESSAGE, request.category)
            assertEquals("Alice" to "Hi", request.title to request.body)
            assertEquals("11111111-2222-4333-8444-555555555555", request.threadIdentifier)
            assertEquals(NotificationPayload.DirectMessage(EntityKey(RADIO_A, contactID), messageID), request.payload)
            assertEquals(1L, request.badge)
            assertTrue(request.soundEnabled)
            // The badge is recomputed from the store after posting.
            assertEquals(listOf(3L), h.delivery.badgeSets)
            assertEquals(3L, h.service.badgeCount)
        },
        nativeCase("muted, unauthorized, disabled or suppressed direct messages post nothing and leave the badge") {
            val h = NotificationHarness(this)
            h.service.postDM()
            h.authorize()
            h.service.postDM(muted = true)
            h.preferences.set { it.copy(contactMessagesEnabled = false) }
            h.service.postDM()
            h.preferences.set { it.copy(contactMessagesEnabled = true) }
            h.service.isSuppressingNotifications = true
            h.service.postDM()
            assertEquals(emptyList(), h.delivery.posted)
            assertEquals(emptyList(), h.delivery.badgeSets)
        },
        nativeCase("sound and badge preferences shape the request; badge disabled skips the recompute") {
            val h = NotificationHarness(this).authorize()
            h.preferences.set { it.copy(soundEnabled = false, badgeEnabled = false) }
            h.service.postDM()
            val request = h.delivery.posted.single()
            assertFalse(request.soundEnabled)
            assertNull(request.badge)
            assertEquals(emptyList(), h.delivery.badgeSets)
            assertEquals(0, h.counts.calls)
        },
    )

    @TestFactory
    fun channelAndRoomPolicy(): List<DynamicTest> = listOf(
        nativeCase("channel levels: muted never, mentions-only needs a self mention, all always") {
            val h = NotificationHarness(this).authorize()
            h.service.postChannel(NotificationLevel.MUTED, mention = true)
            h.service.postChannel(NotificationLevel.MENTIONS_ONLY, mention = false)
            assertEquals(emptyList(), h.delivery.posted)
            h.service.postChannel(NotificationLevel.MENTIONS_ONLY, mention = true)
            h.service.postChannel(NotificationLevel.ALL, mention = false, sender = null)
            val (mention, all) = h.delivery.posted
            assertEquals("Bob: Hello", mention.body)
            assertEquals("Hello", all.body)
            assertEquals(NotificationCategory.CHANNEL_MESSAGE, mention.category)
            assertEquals("channel-${RADIO_A.canonicalString}-2", mention.threadIdentifier)
            assertEquals(NotificationPayload.ChannelMessage(RADIO_A, 2u, messageID), mention.payload)
        },
        nativeCase("channel and room preferences gate their posts") {
            val h = NotificationHarness(this).authorize()
            h.preferences.set { it.copy(channelMessagesEnabled = false, roomMessagesEnabled = false) }
            h.service.postChannel(NotificationLevel.ALL, mention = true)
            h.service.postRoomMessageNotification("Room", UUID.randomUUID(), "Bob", "Hi", messageID, NotificationLevel.ALL)
            assertEquals(emptyList(), h.delivery.posted)
        },
        nativeCase("room requests carry the session key, sender body and room thread; muted rooms post nothing") {
            val h = NotificationHarness(this).authorize()
            val session = UUID.randomUUID()
            h.service.postRoomMessageNotification("Room", session, null, "Hi", messageID, NotificationLevel.MUTED)
            assertEquals(emptyList(), h.delivery.posted)
            h.service.postRoomMessageNotification("Room", session, "Bob", "Hi", messageID, NotificationLevel.ALL)
            val request = h.delivery.posted.single()
            assertEquals("Bob: Hi", request.body)
            assertEquals(NotificationCategory.ROOM_MESSAGE, request.category)
            assertEquals("room-${session.toString().uppercase()}", request.threadIdentifier)
            assertEquals(NotificationPayload.RoomMessage("Room", EntityKey(RADIO_A, session), messageID), request.payload)
        },
    )

    @TestFactory
    fun otherNotifications(): List<DynamicTest> = listOf(
        nativeCase("new contact: per-type filter, English fallbacks, unaffected by sync suppression, no badge") {
            val h = NotificationHarness(this).authorize()
            h.service.isSuppressingNotifications = true
            h.preferences.set { it.copy(discoveryRepeaterEnabled = false) }
            h.service.postNewContactNotification("", contactID, ContactType.REPEATER)
            assertEquals(emptyList(), h.delivery.posted)
            h.service.postNewContactNotification("", contactID, ContactType.ROOM)
            val request = h.delivery.posted.single()
            assertEquals("New Room Discovered" to "Unknown Contact", request.title to request.body)
            assertEquals(NotificationId("new-contact-11111111-2222-4333-8444-555555555555"), request.id)
            assertEquals("discovery", request.threadIdentifier)
            assertNull(request.category)
            assertNull(request.badge)
            assertEquals(NotificationPayload.NewContact(EntityKey(RADIO_A, contactID)), request.payload)
            h.preferences.set { it.copy(newContactDiscoveredEnabled = false) }
            h.service.postNewContactNotification("Zed", contactID, ContactType.CHAT)
            assertEquals(1, h.delivery.posted.size)
        },
        nativeCase("new contact uses the string provider for title and unknown name") {
            val h = NotificationHarness(this).authorize()
            h.service.setStringProvider(MockStringProvider())
            h.service.postNewContactNotification("", contactID, ContactType.CHAT)
            h.service.postNewContactNotification("Zed", contactID, ContactType.CHAT)
            assertEquals(listOf("Mock Title" to "Mock Unknown", "Mock Title" to "Zed"), h.delivery.posted.map { it.title to it.body })
        },
        nativeCase("reaction posts honour preference and suppression and thread by conversation") {
            val h = NotificationHarness(this).authorize()
            h.preferences.set { it.copy(reactionNotificationsEnabled = false) }
            h.service.postReactionNotification("Bob", "body", messageID, contactID, null, null)
            h.preferences.set { it.copy(reactionNotificationsEnabled = true) }
            h.service.isSuppressingNotifications = true
            h.service.postReactionNotification("Bob", "body", messageID, contactID, null, null)
            assertEquals(emptyList(), h.delivery.posted)
            h.service.isSuppressingNotifications = false
            h.service.postReactionNotification("Bob", "body", messageID, contactID, null, null)
            h.service.postReactionNotification("Bob", "body", messageID, null, 4u, RADIO_A)
            val (dm, channel) = h.delivery.posted
            assertEquals("reaction-contact-11111111-2222-4333-8444-555555555555", dm.threadIdentifier)
            assertEquals("reaction-channel-${RADIO_A.canonicalString}-4", channel.threadIdentifier)
            assertEquals(NotificationPayload.Reaction(messageID, null, 4u, RADIO_A), channel.payload)
            assertEquals(NotificationCategory.REACTION, dm.category)
            assertTrue(dm.id.value.startsWith("reaction-AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE-Bob-"))
            assertEquals(emptyList(), h.delivery.badgeSets)
        },
        nativeCase("low battery: preference gate, English fallback, identifier by device name") {
            val h = NotificationHarness(this).authorize()
            h.service.postLowBatteryNotification("Node1", 12)
            val request = h.delivery.posted.single()
            assertEquals("Low Battery" to "Node1 battery is at 12%", request.title to request.body)
            assertEquals(NotificationId("low-battery-Node1"), request.id)
            assertEquals(NotificationCategory.LOW_BATTERY, request.category)
            h.preferences.set { it.copy(lowBatteryEnabled = false) }
            h.service.postLowBatteryNotification("Node1", 5)
            assertEquals(1, h.delivery.posted.size)
        },
        nativeCase("quick reply failure notifications always use sound and English fallbacks") {
            val h = NotificationHarness(this).authorize()
            h.preferences.set { it.copy(soundEnabled = false) }
            h.service.postQuickReplyFailedNotification("Alice", contactID)
            h.service.postChannelQuickReplyFailedNotification("Public", RADIO_A, 0u)
            val (dm, channel) = h.delivery.posted
            assertTrue(dm.soundEnabled && channel.soundEnabled)
            assertEquals("Message Not Sent" to "Your reply to Alice couldn't be sent.", dm.title to dm.body)
            assertEquals("Your reply to Public couldn't be sent.", channel.body)
            assertEquals(NotificationCategory.DIRECT_MESSAGE, dm.category)
            assertNull(channel.category)
            h.service.postChannelQuickReplyFailedNotification("Ops", RADIO_A, 7u)
            val suffix = "${ImmediateClock.NOW.epochSecond}.123456789"
            assertEquals(NotificationId("quick-reply-failed-11111111-2222-4333-8444-555555555555-$suffix"), dm.id)
            assertEquals(NotificationId("channel-reply-failed-${RADIO_A.canonicalString}-7-$suffix"), h.delivery.posted[2].id)
            assertTrue(h.delivery.posted.all { it.threadIdentifier == null && it.badge == null })
            assertEquals(emptyList(), h.delivery.badgeSets)
        },
    )

    @TestFactory
    fun badgePolicy(): List<DynamicTest> = listOf(
        nativeCase("badge sums only enabled conversation kinds") {
            val h = NotificationHarness(this)
            h.counts.counts = UnreadCounts(2, 5, 7)
            h.preferences.set { it.copy(channelMessagesEnabled = false) }
            h.service.updateBadgeCount()
            assertEquals(9L, h.service.badgeCount)
            assertEquals(listOf(9L), h.delivery.badgeSets)
        },
        nativeCase("badge disabled clears to zero without reading unread counts") {
            val h = NotificationHarness(this)
            h.counts.counts = UnreadCounts(2, 5, 7)
            h.service.updateBadgeCount()
            h.preferences.set { it.copy(badgeEnabled = false) }
            h.service.updateBadgeCount()
            assertEquals(0L, h.service.badgeCount)
            assertEquals(listOf(14L, 0L), h.delivery.badgeSets)
            assertEquals(1, h.counts.calls)
        },
        nativeCase("failing unread counts and badge delivery are contained") {
            val h = NotificationHarness(this)
            h.counts.fail = true
            h.delivery.failing += "setBadgeCount"
            h.service.updateBadgeCount()
            assertEquals(0L, h.service.badgeCount)
            assertEquals(listOf(0L), h.delivery.badgeSets)
        },
        nativeCase("rapid badge updates are debounced into one recompute") {
            val clock = VirtualClock()
            val h = NotificationHarness(this, clock = clock)
            h.counts.counts = UnreadCounts(4, 0, 0)
            val first = launch { h.service.updateBadgeCount() }
            yieldUntil("first debounce sleeping") { clock.sleeperCount == 1 }
            val second = launch { h.service.updateBadgeCount() }
            // The second call cancels the first pending update; the first caller returns without updating.
            yieldUntil("first caller released") { first.isCompleted && clock.sleeperCount == 1 }
            assertEquals(0, h.counts.calls)
            clock.wakeAll()
            second.join()
            assertEquals(1, h.counts.calls)
            assertEquals(listOf(4L), h.delivery.badgeSets)
            assertEquals(listOf(100.milliseconds, 100.milliseconds), clock.sleeps)
        },
        nativeCase("overlapping badge computations land in start order so the newest read wins") {
            val clock = ImmediateClock()
            val h = NotificationHarness(this, clock = clock)
            val gate = CompletableDeferred<Unit>()
            h.counts.gates += gate
            h.counts.counts = UnreadCounts(1, 0, 0)
            val first = launch { h.service.updateBadgeCount() }
            yieldUntil("first computation blocked in the store") { h.counts.calls == 1 }
            h.counts.counts = UnreadCounts(5, 0, 0)
            val second = launch { h.service.updateBadgeCount() }
            yieldUntil("second debounce requested") { clock.sleeps.size == 2 }
            drainRunnable()
            gate.complete(Unit)
            first.join()
            second.join()
            assertEquals(listOf(1L, 5L), h.delivery.badgeSets)
            assertEquals(5L, h.service.badgeCount)
            assertEquals(listOf(100.milliseconds, 100.milliseconds), clock.sleeps)
        },
        nativeCase("a computation already reading counts finishes even when the session scope is cancelled") {
            val serviceScope = CoroutineScope(coroutineContext + Job(coroutineContext.job))
            val h = NotificationHarness(serviceScope)
            val gate = CompletableDeferred<Unit>()
            h.counts.gates += gate
            h.counts.counts = UnreadCounts(3, 0, 0)
            val caller = launch { h.service.updateBadgeCount() }
            yieldUntil("computation blocked in the store") { h.counts.calls == 1 }
            serviceScope.cancel()
            gate.complete(Unit)
            caller.join()
            assertEquals(listOf(3L), h.delivery.badgeSets)
            assertEquals(3L, h.service.badgeCount)
        },
        nativeCase("cancellation raised by unread counts or delivery is never contained as a failure") {
            val h = NotificationHarness(this).authorize()
            h.counts.cancel = true
            h.service.updateBadgeCount()
            assertEquals(emptyList(), h.delivery.badgeSets, "a cancelled read must not become a zero badge")
            h.counts.cancel = false
            h.counts.counts = UnreadCounts(2, 0, 0)
            h.service.updateBadgeCount()
            assertEquals(listOf(2L), h.delivery.badgeSets)
            h.delivery.cancelling = "post"
            assertFailsWith<CancellationException> { h.service.postDM() }
            assertEquals(listOf(2L), h.delivery.badgeSets)
        },
    )

    @TestFactory
    fun foregroundPresentation(): List<DynamicTest> = listOf(
        nativeCase("foreground message for the active chat is not shown but the badge still updates") {
            val h = NotificationHarness(this).authorize()
            h.appState.foreground = true
            h.service.setActiveConversation(contactID = contactID)
            h.counts.counts = UnreadCounts(1, 0, 0)
            h.service.postDM()
            assertEquals(emptyList(), h.delivery.posted)
            assertEquals(listOf(1L), h.delivery.badgeSets)
            h.service.postDM(contact = UUID.randomUUID())
            assertEquals(1, h.delivery.posted.size)
        },
        nativeCase("background message for the active chat is shown") {
            val h = NotificationHarness(this).authorize()
            h.service.setActiveConversation(contactID = contactID)
            h.service.postDM()
            assertEquals(1, h.delivery.posted.size)
        },
        nativeCase("active channel must match both index and radio; active room matches session") {
            val h = NotificationHarness(this)
            val session = UUID.randomUUID()
            h.service.setActiveConversation(channelIndex = 2u, channelRadioId = RADIO_B)
            assertTrue(h.service.shouldPresentWhileForeground(NotificationPayload.ChannelMessage(RADIO_A, 2u, messageID)))
            assertFalse(h.service.shouldPresentWhileForeground(NotificationPayload.ChannelMessage(RADIO_B, 2u, messageID)))
            assertFalse(h.service.shouldPresentWhileForeground(NotificationPayload.ChannelQuickReplyFailed(RADIO_B, 2u)))
            assertTrue(h.service.shouldPresentWhileForeground(NotificationPayload.ChannelMessage(RADIO_B, 3u, messageID)))
            h.service.setActiveConversation(roomSessionID = session)
            assertFalse(h.service.shouldPresentWhileForeground(NotificationPayload.RoomMessage("R", EntityKey(RADIO_A, session), messageID)))
            assertTrue(h.service.shouldPresentWhileForeground(NotificationPayload.LowBattery(10)))
        },
        nativeCase("reactions and failures for the viewed chat are suppressed by key, as Swift's userInfo check") {
            val h = NotificationHarness(this)
            val contact = EntityKey(RADIO_A, contactID)
            h.service.setActiveConversation(contactID = contactID)
            assertFalse(h.service.shouldPresentWhileForeground(NotificationPayload.Reaction(messageID, contact, null, null)))
            assertFalse(h.service.shouldPresentWhileForeground(NotificationPayload.QuickReplyFailed(contact)))
            assertTrue(h.service.shouldPresentWhileForeground(NotificationPayload.Reaction(messageID, null, 1u, RADIO_A)))
        },
        nativeCase("a failing foreground check falls back to showing the notification") {
            val h = NotificationHarness(this).authorize()
            h.appState.fail = true
            h.service.setActiveConversation(contactID = contactID)
            h.service.postDM()
            assertEquals(1, h.delivery.posted.size)
        },
    )

    @TestFactory
    fun permissionAndDelivery(): List<DynamicTest> = listOf(
        nativeCase("setup registers Swift categories with English fallbacks and reads authorization") {
            val h = NotificationHarness(this)
            h.service.setup()
            assertTrue(h.service.isAuthorized)
            val categories = checkNotNull(h.delivery.registered).associate { it.category to it.actions }
            assertEquals(NotificationCategory.entries.toSet(), categories.keys)
            val dmActions = categories.getValue(NotificationCategory.DIRECT_MESSAGE)
            assertEquals(listOf(NotificationAction.REPLY, NotificationAction.MARK_READ), dmActions.map { it.action })
            assertEquals(listOf("Reply", "Mark as Read"), dmActions.map { it.title })
            assertEquals("Send" to "Message...", dmActions[0].textInputButtonTitle to dmActions[0].textInputPlaceholder)
            assertEquals(dmActions, categories.getValue(NotificationCategory.CHANNEL_MESSAGE))
            assertEquals(listOf(NotificationAction.MARK_READ), categories.getValue(NotificationCategory.ROOM_MESSAGE).map { it.action })
            assertTrue(categories.getValue(NotificationCategory.REACTION).isEmpty())
            assertTrue(categories.getValue(NotificationCategory.LOW_BATTERY).isEmpty())
        },
        nativeCase("setup uses provider titles and survives a failing registration") {
            val h = NotificationHarness(this)
            h.service.setStringProvider(MockStringProvider())
            h.service.setup()
            val reply = checkNotNull(h.delivery.registered).first().actions.first()
            assertEquals(listOf("Mock Reply", "Mock Send", "Mock Placeholder"), listOf(reply.title, reply.textInputButtonTitle, reply.textInputPlaceholder))
            val failing = NotificationHarness(this)
            failing.delivery.failing += "registerCategories"
            failing.service.setup()
            assertTrue(failing.service.isAuthorized)
        },
        nativeCase("authorization request failure counts as denied; a failed status check keeps the last status") {
            val h = NotificationHarness(this).authorize()
            h.delivery.failing += "authorizationStatus"
            h.service.checkAuthorizationStatus()
            assertTrue(h.service.isAuthorized)
            h.delivery.failing += "requestAuthorization"
            assertFalse(h.service.requestAuthorization())
            assertEquals(NotificationAuthorizationStatus.DENIED, h.service.authorizationStatus)
            h.delivery.failing.clear()
            assertTrue(h.service.requestAuthorization())
            assertTrue(h.service.isAuthorized)
        },
        nativeCase("denied, unsupported or failing posts never throw and still refresh the badge") {
            for (outcome in listOf(NotificationPostResult.PermissionDenied, NotificationPostResult.Unsupported(Capability.NOTIFICATIONS))) {
                val h = NotificationHarness(this).authorize()
                h.delivery.postResult = outcome
                h.service.postDM()
                assertEquals(1, h.delivery.badgeSets.size)
            }
            val h = NotificationHarness(this).authorize()
            h.delivery.failing += "post"
            h.service.postDM()
            assertEquals(1, h.delivery.badgeSets.size)
        },
        nativeCase("drafts are consumed once and keyed by radio plus contact") {
            val service = NotificationHarness(this).service
            val contact = EntityKey(RADIO_A, contactID)
            service.saveDraft(contact, "first")
            service.saveDraft(contact, "second")
            assertNull(service.consumeDraft(EntityKey(RADIO_A, UUID.randomUUID())))
            // A same-UUID contact of another radio never receives this radio's draft.
            assertNull(service.consumeDraft(EntityKey(RADIO_B, contactID)))
            assertEquals("second", service.consumeDraft(contact))
            assertNull(service.consumeDraft(contact))
        },
    )

    @TestFactory
    fun removal(): List<DynamicTest> = listOf(
        nativeCase("snapshot cleanup preserves same-ID posts arriving after contact channel and room selection") {
            val contact = EntityKey(RADIO_A, contactID)
            val session = UUID.randomUUID()
            data class RemovalCase(
                val payload: NotificationPayload,
                val cleanup: suspend (NotificationService) -> Unit,
            )
            val cases = listOf(
                RemovalCase(NotificationPayload.NewContact(contact)) { it.removeDeliveredNotifications(contactID) },
                RemovalCase(NotificationPayload.ChannelMessage(RADIO_A, 2u, messageID)) {
                    it.removeDeliveredNotifications(2u, RADIO_A)
                },
                RemovalCase(NotificationPayload.RoomMessage("Ops", EntityKey(RADIO_A, session), messageID)) {
                    it.removeDeliveredNotificationsForRoom(session)
                },
            )
            for ((payload, cleanup) in cases) {
                val h = NotificationHarness(this)
                h.delivery.show("reused", payload)
                val selected = CompletableDeferred<Unit>()
                val resume = CompletableDeferred<Unit>()
                h.delivery.beforeSnapshotRemoval = { selected.complete(Unit); resume.await() }
                val job = launch { cleanup(h.service) }
                selected.await()
                h.delivery.show("reused", payload)
                resume.complete(Unit)
                job.join()
                assertEquals(listOf(NotificationId("reused")), h.delivery.shownIds)
                assertEquals(listOf(SnapshotRemovalOutcome.SUPERSEDED), h.delivery.lastSnapshotOutcomes)
                assertTrue(h.delivery.removals.isEmpty())
            }
        },
        nativeCase("unknown snapshot identity is retained while explicit logical-ID removal still removes replacement") {
            val h = NotificationHarness(this)
            val id = "AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE"
            h.delivery.show(id, NotificationPayload.NewContact(EntityKey(RADIO_A, contactID)))
            h.delivery.forgetPostIdentity(id)
            h.service.removeDeliveredNotifications(contactID)
            assertEquals(listOf(SnapshotRemovalOutcome.UNKNOWN_IDENTITY), h.delivery.lastSnapshotOutcomes)
            assertEquals(listOf(NotificationId(id)), h.delivery.shownIds)
            h.delivery.show(id, NotificationPayload.NewContact(EntityKey(RADIO_A, contactID)))
            h.service.removeDeliveredNotification(messageID)
            assertTrue(h.delivery.shownIds.isEmpty())
            assertEquals(listOf(listOf(NotificationId(id))), h.delivery.removals)
        },
        nativeCase("snapshot cleanup failure is contained and cancellation propagates without logical fallback") {
            val h = NotificationHarness(this)
            h.delivery.show("reused", NotificationPayload.NewContact(EntityKey(RADIO_A, contactID)))
            h.delivery.failing += "removeDeliveredSnapshot"
            h.service.removeDeliveredNotifications(contactID)
            assertEquals(listOf(NotificationId("reused")), h.delivery.shownIds)
            assertTrue(h.delivery.removals.isEmpty())
            h.delivery.failing.clear()
            h.delivery.cancelling = "removeDeliveredSnapshot"
            assertFailsWith<CancellationException> { h.service.removeDeliveredNotifications(contactID) }
        },
        nativeCase("contact removal lists once and removes every contact-keyed notification only") {
            val h = NotificationHarness(this)
            val contact = EntityKey(RADIO_A, contactID)
            val otherContact = EntityKey(RADIO_A, UUID.randomUUID())
            h.delivery.show("dm", NotificationPayload.DirectMessage(contact, messageID))
            h.delivery.show("new", NotificationPayload.NewContact(contact))
            h.delivery.show("failed", NotificationPayload.QuickReplyFailed(contact))
            h.delivery.show("reaction", NotificationPayload.Reaction(messageID, contact, null, null))
            h.delivery.show("other", NotificationPayload.DirectMessage(otherContact, UUID.randomUUID()))
            h.delivery.show("channel", NotificationPayload.ChannelMessage(RADIO_A, 0u, UUID.randomUUID()))
            h.delivery.showForeign("connection-status")
            h.service.removeDeliveredNotifications(contactID)
            assertEquals(1, h.delivery.listCalls)
            assertEquals(listOf("other", "channel", "connection-status").map(::NotificationId), h.delivery.shownIds)
            h.service.removeDeliveredNotifications(emptySet())
            assertEquals(1, h.delivery.listCalls)
        },
        nativeCase("channel removal needs both index and radio; room removal matches the session") {
            val h = NotificationHarness(this)
            val session = UUID.randomUUID()
            h.delivery.show("a2", NotificationPayload.ChannelMessage(RADIO_A, 2u, UUID.randomUUID()))
            h.delivery.show("b2", NotificationPayload.ChannelMessage(RADIO_B, 2u, UUID.randomUUID()))
            h.delivery.show("a2failed", NotificationPayload.ChannelQuickReplyFailed(RADIO_A, 2u))
            h.delivery.show("a2reaction", NotificationPayload.Reaction(messageID, null, 2u, RADIO_A))
            h.delivery.show("a3", NotificationPayload.ChannelMessage(RADIO_A, 3u, UUID.randomUUID()))
            h.delivery.show("room", NotificationPayload.RoomMessage("R", EntityKey(RADIO_A, session), UUID.randomUUID()))
            h.service.removeDeliveredNotifications(2u, RADIO_A)
            assertEquals(listOf("b2", "a3", "room").map(::NotificationId), h.delivery.shownIds)
            h.service.removeDeliveredNotificationsForRoom(session)
            assertEquals(listOf("b2", "a3").map(::NotificationId), h.delivery.shownIds)
        },
        nativeCase("removal by message id and delivery failures are contained") {
            val h = NotificationHarness(this)
            h.service.removeDeliveredNotification(messageID)
            assertEquals(listOf(listOf(NotificationId("AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE"))), h.delivery.removals)
            h.delivery.failing += "deliveredNotifications"
            h.service.removeDeliveredNotifications(contactID)
            h.delivery.failing += "removeDelivered"
            h.service.removeDeliveredNotification(messageID)
            assertEquals(2, h.delivery.removals.size)
        },
    )

    @TestFactory
    fun responseRouting(): List<DynamicTest> = listOf(
        nativeCase("reply and mark-read actions route by payload keys") {
            val h = NotificationHarness(this)
            val contact = EntityKey(RADIO_A, contactID)
            val session = EntityKey(RADIO_A, UUID.randomUUID())
            val calls = ArrayList<List<Any?>>()
            h.service.onQuickReply = { c, t -> calls += listOf("reply", c, t) }
            h.service.onChannelQuickReply = { r, i, t -> calls += listOf("channelReply", r, i, t) }
            h.service.onMarkAsRead = { c, m -> calls += listOf("read", c, m) }
            h.service.onChannelMarkAsRead = { r, i, m -> calls += listOf("channelRead", r, i, m) }
            h.service.onRoomMarkAsRead = { s, m -> calls += listOf("roomRead", s, m) }
            val dm = NotificationPayload.DirectMessage(contact, messageID)
            val channel = NotificationPayload.ChannelMessage(RADIO_B, 1u, messageID)
            h.service.didReceive(NotificationResponse(dm, NotificationAction.REPLY, "yo"))
            h.service.didReceive(NotificationResponse(dm, NotificationAction.REPLY, null))
            h.service.didReceive(NotificationResponse(channel, NotificationAction.REPLY, "all"))
            h.service.didReceive(NotificationResponse(NotificationPayload.QuickReplyFailed(contact), NotificationAction.REPLY, "again"))
            h.service.didReceive(NotificationResponse(dm, NotificationAction.MARK_READ, null))
            h.service.didReceive(NotificationResponse(channel, NotificationAction.MARK_READ, null))
            h.service.didReceive(NotificationResponse(NotificationPayload.RoomMessage("R", session, messageID), NotificationAction.MARK_READ, null))
            h.service.didReceive(NotificationResponse(NotificationPayload.QuickReplyFailed(contact), NotificationAction.MARK_READ, null))
            h.service.didReceive(NotificationResponse(dm, NotificationAction.DISMISS, null))
            assertEquals<List<List<Any?>>>(
                listOf(
                    listOf("reply", contact, "yo"), listOf("channelReply", RADIO_B, 1.toUByte(), "all"),
                    listOf("reply", contact, "again"), listOf("read", contact, messageID),
                    listOf("channelRead", RADIO_B, 1.toUByte(), messageID), listOf("roomRead", session, messageID),
                ),
                calls,
            )
        },
        nativeCase("taps route reactions, new contacts, chats, channels and rooms") {
            val h = NotificationHarness(this)
            val contact = EntityKey(RADIO_A, contactID)
            val session = EntityKey(RADIO_A, UUID.randomUUID())
            val calls = ArrayList<List<Any?>>()
            h.service.onReactionNotificationTapped = { c, i, r, m -> calls += listOf("reaction", c, i, r, m) }
            h.service.onNewContactNotificationTapped = { c -> calls += listOf("new", c) }
            h.service.onNotificationTapped = { c -> calls += listOf("chat", c) }
            h.service.onChannelNotificationTapped = { r, i -> calls += listOf("channel", r, i) }
            h.service.onRoomNotificationTapped = { s -> calls += listOf("room", s) }
            listOf(
                NotificationPayload.Reaction(messageID, null, 3u, RADIO_A), NotificationPayload.NewContact(contact),
                NotificationPayload.DirectMessage(contact, messageID), NotificationPayload.QuickReplyFailed(contact),
                NotificationPayload.ChannelQuickReplyFailed(RADIO_A, 3u), NotificationPayload.RoomMessage("R", session, messageID),
                NotificationPayload.LowBattery(5),
            ).forEach { h.service.didReceive(NotificationResponse(it, null, null)) }
            assertEquals<List<List<Any?>>>(
                listOf(
                    listOf("reaction", null, 3.toUByte(), RADIO_A, messageID), listOf("new", contact), listOf("chat", contact),
                    listOf("chat", contact), listOf("channel", RADIO_A, 3.toUByte()), listOf("room", session),
                ),
                calls,
            )
        },
        nativeCase("responses before forwarders are installed are dropped and failing callbacks are contained") {
            val h = NotificationHarness(this)
            val dm = NotificationPayload.DirectMessage(EntityKey(RADIO_A, contactID), messageID)
            h.service.didReceive(NotificationResponse(dm, NotificationAction.REPLY, "cold start"))
            h.service.onQuickReply = { _, _ -> throw IllegalStateException("forwarder failed") }
            h.service.didReceive(NotificationResponse(dm, NotificationAction.REPLY, "boom"))
            assertEquals(emptyList(), h.store.calls)
        },
        nativeCase("action dispatch distinguishes missing forwarders from handled actions") {
            val h = NotificationHarness(this)
            val contact = EntityKey(RADIO_A, contactID)
            val session = EntityKey(RADIO_A, UUID.randomUUID())
            val direct = NotificationPayload.DirectMessage(contact, messageID)
            val channel = NotificationPayload.ChannelMessage(RADIO_A, 3u, messageID)
            val room = NotificationPayload.RoomMessage("Room", session, messageID)
            val responses = listOf(
                NotificationResponse(direct, NotificationAction.REPLY, "direct"),
                NotificationResponse(channel, NotificationAction.REPLY, "channel"),
                NotificationResponse(direct, NotificationAction.MARK_READ, null),
                NotificationResponse(channel, NotificationAction.MARK_READ, null),
                NotificationResponse(room, NotificationAction.MARK_READ, null),
            )
            responses.forEach {
                assertEquals(NotificationResponseDispatch.ACTION_CALLBACK_MISSING, h.service.didReceiveWithResult(it))
            }
            val calls = mutableListOf<String>()
            h.service.onQuickReply = { _, _ -> calls += "direct reply" }
            h.service.onChannelQuickReply = { _, _, _ -> calls += "channel reply" }
            h.service.onMarkAsRead = { _, _ -> calls += "direct read" }
            h.service.onChannelMarkAsRead = { _, _, _ -> calls += "channel read" }
            h.service.onRoomMarkAsRead = { _, _ -> calls += "room read" }
            responses.forEach {
                assertEquals(NotificationResponseDispatch.ACTION_HANDLED, h.service.didReceiveWithResult(it))
            }
            assertEquals(listOf("direct reply", "channel reply", "direct read", "channel read", "room read"), calls)
            assertEquals(
                NotificationResponseDispatch.ACTION_HANDLED,
                h.service.didReceiveWithResult(NotificationResponse(direct, NotificationAction.REPLY, null)),
            )
        },
        nativeCase("tap dispatch reports optional and invoked callbacks without changing cancellation") {
            val h = NotificationHarness(this)
            val contact = EntityKey(RADIO_A, contactID)
            val response = NotificationResponse(NotificationPayload.DirectMessage(contact, messageID), null, null)
            assertEquals(NotificationResponseDispatch.TAP_CALLBACK_MISSING, h.service.didReceiveWithResult(response))
            var calls = 0
            h.service.onNotificationTapped = {
                assertEquals(contact, it)
                calls++
            }
            assertEquals(NotificationResponseDispatch.TAP_CALLBACK_INVOKED, h.service.didReceiveWithResult(response))
            assertEquals(1, calls)
            h.service.onNotificationTapped = { throw IllegalStateException("callback failed") }
            assertEquals(NotificationResponseDispatch.TAP_CALLBACK_INVOKED, h.service.didReceiveWithResult(response))
            h.service.onNotificationTapped = { throw CancellationException("cancelled") }
            assertFailsWith<CancellationException> { h.service.didReceiveWithResult(response) }
            assertEquals(
                NotificationResponseDispatch.ACTION_HANDLED,
                h.service.didReceiveWithResult(NotificationResponse(response.payload, NotificationAction.REPLY, null)),
            )
        },
    )
}
