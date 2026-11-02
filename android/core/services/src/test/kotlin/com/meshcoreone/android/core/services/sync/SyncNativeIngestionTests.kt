// AndroidOnly: WP-214 Native ingestion, reaction, discovery, containment and Swift text-semantics cases.
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.model.BlockedChannelSenderDTO
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Notification fake whose posts throw (a Kotlin collaborator failure Swift could not produce). */
private class ThrowingPosts(private val base: FakeNotificationService) : SyncNotificationServicing by base {
    override suspend fun postDirectMessageNotification(from: String, contactID: UUID, messageText: String, messageID: UUID, isMuted: Boolean) =
        throw IllegalStateException("post failed")
    override suspend fun postNewContactNotification(contactName: String, contactID: UUID, contactType: ContactType) =
        throw IllegalStateException("post failed")
}

private suspend fun SyncTestScope.wired(services: SyncTestServices, radioId: com.meshcoreone.android.core.model.RadioId, deps: SyncDependencies = services.dependencies()): SyncCoordinator =
    coordinator().also { it.wireMessageHandlers(deps, radioId) }

class SyncNativeIngestionTests {
    @TestFactory
    fun receiveClockRecovery(): List<DynamicTest> =
        listOf(Instant.ofEpochSecond(-1), Instant.ofEpochSecond(UInt.MAX_VALUE.toLong() + 1)).flatMap { receiveTime ->
            listOf(false, true).flatMap { direct ->
                listOf(false, true).map { backlog ->
                    nativeCase("range recovery receive=$receiveTime direct=$direct backlog=$backlog") {
                        val radioId = newRadio()
                        val store = SyncInMemoryStore.createTestDataStore(radioId)
                        val contact = testContact(radioId, "Peer", key(0x31))
                        val channel = ChannelDTO(radioId = radioId, index = 0u, name = "Public")
                        store.saveContact(contact)
                        store.saveChannel(channel)
                        val services = SyncTestServices(store)
                        val logs = mutableListOf<Pair<DebugLogLevel, String>>()
                        val receiveClock = object : SyncClock by clock {
                            override fun now() = receiveTime
                        }
                        val coordinator = SyncCoordinator(scope, receiveClock, SyncLogSink { level, _, message -> logs += level to message })
                        coordinator.wireMessageHandlers(services.dependencies(), radioId)
                        val received = ValueTracker<SyncDataEvent>()
                        val events = coordinator.dataEvents()
                        val collector = launch { events.collect { received.record(it) } }
                        val context = if (backlog) DeliveryContext.InitialSync(clock.now()) else DeliveryContext.Live
                        repeat(2) {
                            if (direct) {
                                checkNotNull(services.polling.capturedContactMessageHandler)(
                                    contactMessage(contact.publicKey.prefix(6), "original text", Instant.ofEpochSecond(1000)),
                                    contact, context,
                                )
                            } else {
                                checkNotNull(services.polling.capturedChannelMessageHandler)(
                                    channelMessage("Peer: original text", Instant.ofEpochSecond(1000)), channel, context,
                                )
                            }
                        }
                        settle()
                        val saved = store.allMessages().single()
                        assertEquals("original text", saved.text)
                        assertEquals(1000u, saved.timestamp)
                        assertFalse(saved.timestampCorrected)
                        assertNull(saved.senderTimestamp)
                        assertEquals(receiveTime, saved.createdAt)
                        assertEquals(if (backlog) clock.now() else receiveTime, saved.sortDate)
                        assertEquals(
                            fakeDeduplicationKey.contentBased(
                                if (direct) contact.id else null, if (direct) null else 0u,
                                if (direct) null else "Peer", 1000u, "original text",
                            ),
                            saved.deduplicationKey,
                        )
                        assertEquals(1, services.notifications.posts.size)
                        assertEquals(1, coordinator.conversationsVersion)
                        assertEquals(1, received.values.count {
                            it is SyncDataEvent.DirectMessageReceived || it is SyncDataEvent.ChannelMessageReceived
                        })
                        assertEquals(2, logs.count { (level, message) ->
                            level == DebugLogLevel.ERROR && message.contains("TimestampRangeError") &&
                                message.contains(receiveTime.toString())
                        })
                        collector.cancel()
                    }
                }
            }
        }

    @TestFactory
    fun ingestion(): List<DynamicTest> = listOf(
        nativeCase("direct message saves, bumps unread, notifies and broadcasts once") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, nodeName = "Me")
            val contact = testContact(radioId, "Peer", key(0x31))
            store.saveContact(contact)
            val services = SyncTestServices(store)
            val coordinator = wired(services, radioId)
            val events = coordinator.dataEventBroadcaster.subscribe()
            val received = ValueTracker<SyncDataEvent>()
            val collector = launch { events.collect { received.record(it) } }
            val handler = checkNotNull(services.polling.capturedContactMessageHandler)
            val message = contactMessage(contact.publicKey.prefix(6), "hi @[Me]", clock.now())
            handler(message, contact, DeliveryContext.Live)
            handler(message, contact, DeliveryContext.Live)
            settle()
            val saved = store.allMessages().single()
            assertTrue(saved.containsSelfMention)
            val row = checkNotNull(store.fetchContact(EntityKey(radioId, contact.id)))
            assertEquals(1, row.unreadCount, "the duplicate must not bump unread twice")
            assertEquals(1, row.unreadMentionCount)
            assertEquals(1, services.notifications.posts.count { it.kind == "dm" })
            assertEquals(1, received.values.count { it is SyncDataEvent.DirectMessageReceived })
            assertEquals(1, coordinator.conversationsVersion)
            collector.cancel()
        },
        nativeCase("corrected sender timestamp keeps the original stamp for dedup and reaction matching") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val contact = testContact(radioId, "Peer", key(0x32))
            store.saveContact(contact)
            val services = SyncTestServices(store)
            wired(services, radioId)
            checkNotNull(services.polling.capturedContactMessageHandler)(
                contactMessage(contact.publicKey.prefix(6), "old clock", Instant.ofEpochSecond(1000)), contact, DeliveryContext.Live,
            )
            val saved = store.allMessages().single()
            assertTrue(saved.timestampCorrected)
            assertEquals(clock.now().uint32Seconds(), saved.timestamp)
            assertEquals(1000u, saved.senderTimestamp)
            assertEquals(fakeDeduplicationKey.contentBased(contact.id, null, null, 1000u, "old clock"), saved.deduplicationKey)
        },
        nativeCase("backlog delivery sorts by the drain anchor") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val contact = testContact(radioId, "Peer", key(0x33))
            store.saveContact(contact)
            val services = SyncTestServices(store)
            wired(services, radioId)
            val anchor = clock.now().minusSeconds(30)
            checkNotNull(services.polling.capturedContactMessageHandler)(
                contactMessage(contact.publicKey.prefix(6), "backlog", clock.now()), contact, DeliveryContext.InitialSync(anchor),
            )
            assertEquals(anchor, store.allMessages().single().sortDate)
        },
        nativeCase("DM from an unknown sender materializes the pending advert contact before saving") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val contact = testContact(radioId, "Fresh", key(0x34))
            store.saveContact(contact)
            val services = SyncTestServices(store).apply { adverts.materialized = contact }
            wired(services, radioId)
            checkNotNull(services.polling.capturedContactMessageHandler)(
                contactMessage(contact.publicKey.prefix(6), "first", clock.now()), null, DeliveryContext.Live,
            )
            assertEquals(contact.id, store.allMessages().single().contactID)
            assertEquals(1, services.notifications.posts.count { it.kind == "dm" })
        },
        nativeCase("blocked channel senders are dropped, including canonically equivalent names") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            store.saveBlockedChannelSender(BlockedChannelSenderDTO(name = "Cafe\u0301", radioId = radioId))
            val services = SyncTestServices(store)
            val coordinator = wired(services, radioId)
            assertTrue(coordinator.isBlockedSender("Caf\u00E9"), "Swift String equality is canonical equivalence")
            checkNotNull(services.polling.capturedChannelMessageHandler)(channelMessage("Caf\u00E9: spam", clock.now()), null, DeliveryContext.Live)
            assertTrue(store.allMessages().isEmpty())
        },
        nativeCase("channel message on an unresolved slot is saved and broadcast without a notification") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val services = SyncTestServices(store)
            val coordinator = wired(services, radioId)
            val handler = checkNotNull(services.polling.capturedChannelMessageHandler)
            handler(channelMessage("Node: on slot 4", clock.now(), channelIndex = 4u), null, DeliveryContext.Live)
            assertEquals(1, store.allMessages().size)
            assertTrue(services.notifications.posts.none { it.kind == "channel" })
            assertEquals(setOf<UByte>(4u), coordinator.unresolvedChannelIndexSnapshot)
            coordinator.onDisconnected(services.notifications)
            assertTrue(coordinator.unresolvedChannelIndexSnapshot.isEmpty(), "unresolved tracking is per connection")
        },
        nativeCase("a resolved channel message notifies unless the user is viewing that channel") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, nodeName = "Me")
            val channel = ChannelDTO(radioId = radioId, index = 1u, name = "Ops")
            store.saveChannel(channel)
            val services = SyncTestServices(store)
            wired(services, radioId)
            val handler = checkNotNull(services.polling.capturedChannelMessageHandler)
            handler(channelMessage("Me: echo of @[Me]", clock.now(), channelIndex = 1u), channel, DeliveryContext.Live)
            assertFalse(store.allMessages().single().containsSelfMention, "our own node's message is never a self-mention")
            services.notifications.activeChannel = 1u
            services.notifications.activeChannelRadio = radioId
            handler(channelMessage("Bob: seen", clock.now().plusSeconds(1), channelIndex = 1u), channel, DeliveryContext.Live)
            assertEquals(1, store.fetchChannel(EntityKey(radioId, channel.id))?.unreadCount, "viewing the channel suppresses unread")
            assertEquals(2, services.notifications.posts.count { it.kind == "channel" })
        },
        nativeCase("duplicate channel receive with a distinct correlated path records one extra") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val ts = 1_704_001_000u
            val dedupKey = fakeDeduplicationKey.contentBased(null, 0u, "NodeAlpha", ts, "extra path")
            val existing = incomingChannelRow(radioId, "extra path", ts, Bytes.of(0xA1), dedupKey)
            store.saveMessage(existing)
            store.saveRxLogEntry(groupTextRX(radioId, 0u, ts, Bytes.of(0xB2), clock.now(), decodedText = "NodeAlpha: extra path"))
            val services = SyncTestServices(store)
            wired(services, radioId)
            val handler = checkNotNull(services.polling.capturedChannelMessageHandler)
            repeat(2) { handler(channelMessage("NodeAlpha: extra path", Instant.ofEpochSecond(ts.toLong()), pathLength = 1u), null, DeliveryContext.Live) }
            assertEquals(1, store.allMessages().size)
            assertEquals(listOf(Bytes.of(0xB2)), store.fetchMessageRepeats(EntityKey(radioId, existing.id)).map { it.pathNodes })
            assertEquals(1, store.fetchMessage(EntityKey(radioId, existing.id))?.heardRepeats)
        },
        nativeCase("a new channel message correlates its RX path and harvests later distinct paths") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val ts = 1_704_001_100u
            val at = Instant.ofEpochSecond(ts.toLong())
            store.saveRxLogEntry(groupTextRX(radioId, 0u, ts, Bytes.of(0xA1), at, decodedText = "NodeAlpha: fresh", regionScope = "EU"))
            store.saveRxLogEntry(groupTextRX(radioId, 0u, ts, Bytes.of(0xC3), at.plusSeconds(2), decodedText = "NodeAlpha: fresh"))
            val services = SyncTestServices(store)
            wired(services, radioId)
            checkNotNull(services.polling.capturedChannelMessageHandler)(channelMessage("NodeAlpha: fresh", at, pathLength = 1u), null, DeliveryContext.Live)
            val saved = store.allMessages().single()
            assertEquals(Bytes.of(0xA1), saved.pathNodes, "earliest correlated row supplies the canonical path")
            assertEquals("EU", saved.regionScope)
            assertEquals(1, saved.heardRepeats, "the later distinct path is an extra")
        },
        nativeCase("unknown wire textType is clamped to plain") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val contact = testContact(radioId, "Peer", key(0x35))
            store.saveContact(contact)
            val services = SyncTestServices(store)
            wired(services, radioId)
            checkNotNull(services.polling.capturedContactMessageHandler)(
                contactMessage(contact.publicKey.prefix(6), "odd", clock.now(), textType = 9u), contact, DeliveryContext.Live,
            )
            assertEquals(com.meshcoreone.android.core.model.TextType.PLAIN, store.allMessages().single().textType)
        },
        nativeCase("a throwing notification collaborator does not stop message ingestion") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val contact = testContact(radioId, "Peer", key(0x36))
            store.saveContact(contact)
            val services = SyncTestServices(store)
            val coordinator = wired(services, radioId, services.dependencies().copy(notificationService = ThrowingPosts(services.notifications)))
            val events = coordinator.dataEventBroadcaster.subscribe()
            val received = ValueTracker<SyncDataEvent>()
            val collector = launch { events.collect { received.record(it) } }
            checkNotNull(services.polling.capturedContactMessageHandler)(contactMessage(contact.publicKey.prefix(6), "still saved", clock.now()), contact, DeliveryContext.Live)
            settle()
            assertEquals(1, store.allMessages().size)
            assertEquals(1, services.notifications.badgeUpdates, "later steps still run after the contained throw")
            assertEquals(1, received.values.count { it is SyncDataEvent.DirectMessageReceived })
            collector.cancel()
        },
        nativeCase("a store failure during save is logged, not thrown to the poller") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId).apply { failures["saveMessage"] = IllegalStateException("disk full") }
            val services = SyncTestServices(store)
            val coordinator = wired(services, radioId)
            checkNotNull(services.polling.capturedChannelMessageHandler)(channelMessage("A: b", clock.now()), null, DeliveryContext.Live)
            assertEquals(0, coordinator.conversationsVersion)
        },
    )

    @TestFactory
    fun reactionsRoomsAndCLI(): List<DynamicTest> = listOf(
        nativeCase("channel reaction to a cached target persists once and broadcasts the summary") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val target = MessageDTO(radioId = radioId, channelIndex = 0u, text = "target", timestamp = 1u, direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED)
            store.saveMessage(target)
            val services = SyncTestServices(store).apply {
                reactions.parseChannelReaction = { text -> if (text.startsWith("+1")) ParsedReaction("\uD83D\uDC4D", "Alice", "abcd") else null }
                reactions.cachedTargets["abcd"] = target.id
            }
            val coordinator = wired(services, radioId)
            val events = coordinator.dataEventBroadcaster.subscribe()
            val received = ValueTracker<SyncDataEvent>()
            val collector = launch { events.collect { received.record(it) } }
            val handler = checkNotNull(services.polling.capturedChannelMessageHandler)
            handler(channelMessage("Bob: +1 abcd", clock.now()), null, DeliveryContext.Live)
            handler(channelMessage("Bob: +1 abcd", clock.now().plusSeconds(1)), null, DeliveryContext.Live)
            settle()
            assertEquals(listOf(target.id), store.allMessages().map { it.id }, "a reaction is never saved as a message")
            assertEquals(1, store.allReactions().size)
            assertEquals(1, received.values.count { it is SyncDataEvent.ReactionReceived })
            collector.cancel()
        },
        nativeCase("channel reaction without a target is queued, not saved") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val services = SyncTestServices(store).apply { reactions.parseChannelReaction = { ParsedReaction("x", "Alice", "zz") } }
            wired(services, radioId)
            checkNotNull(services.polling.capturedChannelMessageHandler)(channelMessage("Bob: x", clock.now()), null, DeliveryContext.Live)
            assertTrue(store.allMessages().isEmpty())
            assertEquals(listOf("x"), services.reactions.queued.toList())
        },
        nativeCase("DM reaction without a target is queued and found later through the store fallback") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val contact = testContact(radioId, "Peer", key(0x37))
            store.saveContact(contact)
            val target = MessageDTO(radioId = radioId, contactID = contact.id, text = "t", timestamp = 1u)
            store.saveMessage(target)
            val dmParser = object : SyncReactionParsing by noReactionParsing {
                override fun parseDM(text: String) = if (text.startsWith("react")) SyncParsedDMReaction("\u2764", text.removePrefix("react ")) else null
            }
            val services = SyncTestServices(store)
            wired(services, radioId, services.dependencies(codecs = fakeCodecs.copy(reactionParser = dmParser)))
            val handler = checkNotNull(services.polling.capturedContactMessageHandler)
            handler(contactMessage(contact.publicKey.prefix(6), "react h1", clock.now()), contact, DeliveryContext.Live)
            assertEquals(listOf("react h1"), services.reactions.queued.toList())
            store.reactionTargets["h2"] = target.id
            handler(contactMessage(contact.publicKey.prefix(6), "react h2", clock.now()), contact, DeliveryContext.Live)
            assertEquals(listOf(target.id), store.allReactions().map { it.messageID })
            assertEquals(1, store.allMessages().size)
        },
        nativeCase("meshcore-open reactions match DB candidates by Dart hash, using our node name for outgoing rows") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, nodeName = "Me")
            val now = clock.now().uint32Seconds()
            val mine = MessageDTO(radioId = radioId, channelIndex = 0u, text = "my post", timestamp = now, direction = MessageDirection.OUTGOING)
            val theirs = MessageDTO(radioId = radioId, channelIndex = 0u, text = "their post", timestamp = now, direction = MessageDirection.INCOMING, senderNodeName = "Bob")
            store.saveMessage(mine)
            store.saveMessage(theirs)
            val mco = object : SyncMeshCoreOpenReactionParsing {
                override fun parse(text: String) = if (text.startsWith("r:")) SyncParsedMCOReaction("\uD83D\uDE00", text.removePrefix("r:")) else null
                override fun parseV1(text: String) = if (text.startsWith("v1:")) SyncParsedMCOReactionV1("\u2764", now, dartStringHash("Bob"), dartStringHash(text.removePrefix("v1:"))) else null
                override fun computeReactionHash(timestamp: UInt, senderName: String?, text: String) = "$timestamp/$senderName/$text"
                override fun dartStringHash(string: String) = string.hashCode().toUInt()
            }
            val services = SyncTestServices(store)
            wired(services, radioId, services.dependencies(codecs = fakeCodecs.copy(meshCoreOpenParser = mco)))
            val handler = checkNotNull(services.polling.capturedChannelMessageHandler)
            handler(channelMessage("Alice: r:$now/Me/my post", clock.now()), null, DeliveryContext.Live)
            handler(channelMessage("Alice: v1:their post", clock.now()), null, DeliveryContext.Live)
            handler(channelMessage("Alice: r:nomatch", clock.now()), null, DeliveryContext.Live)
            assertEquals(listOf(mine.id, theirs.id), store.allReactions().map { it.messageID })
            assertEquals(listOf("Alice", "Alice"), store.allReactions().map { it.senderName })
            assertEquals(2, store.allMessages().size, "an unmatched MCO reaction is consumed, not saved")
        },
        nativeCase("signed room messages need a 4-byte author prefix and notify when saved") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val session = RemoteNodeSessionDTO(radioId = radioId, publicKey = key(0x50), name = "Lounge", role = RemoteNodeRole.ROOM_SERVER)
            store.saveRemoteNodeSessionDTO(session)
            val services = SyncTestServices(store)
            val coordinator = wired(services, radioId)
            val handler = checkNotNull(services.polling.capturedSignedMessageHandler)
            handler(contactMessage(session.publicKey.prefix(6), "short", clock.now(), signature = Bytes.of(1, 2, 3)), null)
            assertTrue(services.roomServer.calls.isEmpty(), "missing author prefix is dropped")
            services.roomServer.result = RoomMessageDTO(sessionID = session.id, authorKeyPrefix = Bytes.of(1, 2, 3, 4), text = "hello", timestamp = 5u)
            handler(contactMessage(session.publicKey.prefix(6), "hello", clock.now(), signature = Bytes.of(1, 2, 3, 4, 5)), null)
            assertEquals(listOf(Bytes.of(1, 2, 3, 4)), services.roomServer.calls.toList())
            assertEquals("Lounge", services.notifications.posts.single { it.kind == "room" }.title)
            assertEquals(1, coordinator.conversationsVersion)
        },
        nativeCase("CLI responses strip an echoed prefix and route by contact type") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val services = SyncTestServices(store)
            wired(services, radioId)
            val handler = checkNotNull(services.polling.capturedCLIMessageHandler)
            val room = testContact(radioId, "Room", key(0x60)).copy(typeRawValue = ContactType.ROOM.rawValue)
            val repeater = testContact(radioId, "Rpt", key(0x61)).copy(typeRawValue = ContactType.REPEATER.rawValue)
            handler(contactMessage(room.publicKey.prefix(6), "ECHO> ver 1.2", clock.now(), textType = 1u), room)
            handler(contactMessage(repeater.publicKey.prefix(6), "OK", clock.now(), textType = 1u), repeater)
            handler(contactMessage(key(0x62).prefix(6), "lost", clock.now(), textType = 1u), null)
            assertEquals(listOf("ver 1.2"), services.roomAdmin.routed.toList())
            assertEquals(listOf("OK"), services.repeaterAdmin.routed.toList())
        },
    )

    @TestFactory
    fun discovery(): List<DynamicTest> = listOf(
        nativeCase("discovery monitor survives a throwing notification and keeps relaying contact changes") {
            val services = SyncTestServices()
            val coordinator = coordinator()
            coordinator.startDiscoveryEventMonitoring(services.dependencies().copy(notificationService = ThrowingPosts(services.notifications)), newRadio())
            services.adverts.emit(SyncDiscoveryEvent.NewContactDiscovered("A", UUID.randomUUID(), ContactType.CHAT))
            services.adverts.emit(SyncDiscoveryEvent.Other)
            services.adverts.emit(SyncDiscoveryEvent.NewContactDiscovered("B", UUID.randomUUID(), ContactType.CHAT))
            settle()
            assertEquals(2, coordinator.contactsVersion)
            assertTrue(coordinator.isDiscoveryMonitoring)
        },
        nativeCase("adopted orphan DMs notify non-blocked contacts once each and refresh the badge") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val open = testContact(radioId, "Open", key(0x70))
            val blocked = testContact(radioId, "Blocked", key(0x71), isBlocked = true)
            store.saveContact(open)
            store.saveContact(blocked)
            for (contact in listOf(open, blocked)) {
                store.saveMessage(MessageDTO(radioId = radioId, contactID = contact.id, text = "early ${contact.name}", timestamp = 1u, direction = MessageDirection.INCOMING))
            }
            val services = SyncTestServices(store)
            val coordinator = coordinator()
            coordinator.startDiscoveryEventMonitoring(services.dependencies(), radioId)
            services.adverts.emit(SyncDiscoveryEvent.OrphanDirectMessagesAdopted(listOf(open.id, blocked.id, UUID.randomUUID())))
            settle()
            assertEquals(listOf("early Open"), services.notifications.posts.filter { it.kind == "dm" }.map { it.text })
            assertEquals(1, services.notifications.badgeUpdates)
        },
        nativeCase("discovery events yielded before monitoring starts are not replayed") {
            val services = SyncTestServices()
            val coordinator = coordinator()
            services.adverts.emit(SyncDiscoveryEvent.NewContactDiscovered("Early", UUID.randomUUID(), ContactType.CHAT))
            coordinator.startDiscoveryEventMonitoring(services.dependencies(), newRadio())
            settle()
            assertEquals(0, coordinator.contactsVersion)
            assertNull(services.notifications.posts.firstOrNull())
        },
    )

    @TestFactory
    fun swiftSemantics(): List<DynamicTest> = listOf(
        nativeCase("parseChannelMessage follows Swift Character splitting and Foundation whitespace trimming") {
            fun check(input: String, sender: String?, text: String) {
                val parsed = parseChannelMessage(input)
                assertEquals(sender, parsed.senderNodeName, "sender for ${input.map { it.code }}")
                assertEquals(text, parsed.messageText, "text for ${input.map { it.code }}")
            }
            // Values pinned from swiftc against Foundation on macOS.
            check(":\u0301x:y", ":\u0301x", "y")
            check("a:\u0301b", null, "a:\u0301b")
            check("::b", null, "::b")
            check("a::b", "a", ":b")
            check("::a:b", "a", "b")
            check(" ::b", "", ":b")
            check("\u3000N\u00A0:\u2003t\t", "N", "t")
            check("N:\n x \n", "N", "\n x \n")
            check("N\u200B: x", "N", "x")
            check("N\u180E: x", "N\u180E", "x")
        },
        nativeCase("other date conversions retain their existing clamp independently of receive-clock recovery") {
            assertEquals(0u, Instant.ofEpochSecond(-50).uint32Seconds())
            assertEquals(UInt.MAX_VALUE, Instant.ofEpochSecond(1L shl 40).uint32Seconds())
            val result = SyncCoordinator.correctTimestampIfNeeded(1000u, Instant.ofEpochSecond(-100))
            assertFalse(result.wasCorrected)
            assertEquals(1000u, result.correctedTimestamp)
            assertEquals(Instant.ofEpochSecond(-100), checkNotNull(result.rangeError).receiveTime)
        },
        nativeCase("reaction timestamp window saturates at both UInt32 ends") {
            assertEquals(0u..400u, reactionTimestampWindow(100u))
            assertEquals((UInt.MAX_VALUE - 300u)..UInt.MAX_VALUE, reactionTimestampWindow(UInt.MAX_VALUE))
        },
        nativeCase("watermark plausibility bound saturates near UInt32 max") {
            val nearMax = Instant.ofEpochSecond(UInt.MAX_VALUE.toLong() - 10)
            assertEquals(ContactWatermarkUse.Incremental(UInt.MAX_VALUE), contactWatermarkUse(UInt.MAX_VALUE, nearMax))
            assertEquals(Instant.ofEpochSecond(1_704_067_199), incrementalSince(1_704_067_200u))
        },
        nativeCase("channel skip compares whole elapsed seconds against the window") {
            val now = clock.now()
            val config = ChannelSyncConfig(kotlin.time.Duration.parse("30.9s"), lastCleanChannelSync = now.minusMillis(30_500))
            assertFalse(shouldSkipChannels(false, config, now), "Swift uses the window's whole seconds (30)")
            assertTrue(shouldSkipChannels(false, config.copy(lastCleanChannelSync = now.minusMillis(29_900)), now))
            assertTrue(shouldSkipChannels(false, config.copy(lastCleanChannelSync = now.plusSeconds(5)), now), "a future stamp is recent")
        },
    )
}
