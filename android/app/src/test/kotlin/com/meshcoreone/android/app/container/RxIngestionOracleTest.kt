// PortedFrom: MC1Services/Tests/MC1ServicesTests/SyncCoordinatorMessageHandlerTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/RxLogServiceReprocessTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/RxLogServiceAdvertHopTests.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-212: Supplemental real AES/HMAC, SHA32, session ingestion and Room guard oracles.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.crypto.WireCrypto
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.ParsedRxLogData
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.parser.RxLogParser
import com.meshcoreone.android.core.protocol.parser.TransportCodeRegionResolver
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

internal class RxIngestionFixture(
    test: TestScope,
    val store: RoomPersistenceStore,
    persistence: com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol = store,
    identity: (com.meshcoreone.android.core.contracts.domain.ConnectionTarget) -> Bytes = { key(7) },
) {
    val harness = ContainerHarness(test, store, identity = identity, persistenceOverride = persistence)
    lateinit var live: RadioSessionContainer
    val radio get() = live.token.radioId
    val secret = Bytes(ByteArray(16) { (it + 1).toByte() })

    suspend fun connect() {
        harness.links.configureRadio = { firmware ->
            firmware.respond = { command ->
                if (command[0] == 0x1f.toUByte()) {
                    val slot = command[1].toInt()
                    listOf(Bytes.of(0x12, slot) + Bytes.utf8(if (slot == 0) "Public" else "")
                        .paddedOrTruncated(32) + if (slot == 0) secret else Bytes(ByteArray(16)))
                } else null
            }
        }
        harness.container.foreground.activityStarted()
        harness.manager.connect(harness.target())
        harness.eventually("production message handlers ready") {
            harness.sessions.current?.messagePollingService?.hasMessageHandlersWired == true &&
                harness.appState.canRunSettingsStartupReads
        }
        live = assertNotNull(harness.sessions.current)
        assertEquals(secret, assertNotNull(store.fetchChannel(radio, 0u)).secret)
        live.rxLogService.updateChannels(store.fetchChannels(radio))
    }

    fun encrypted(stamp: UInt, text: String, key: Bytes = secret): Bytes {
        val cipher = WireCrypto.encryptAes128EcbZeroPadded(le32(stamp.toLong()) + Bytes.of(0) + Bytes.utf8(text), key)
        val hash = Bytes(MessageDigest.getInstance("SHA-256").digest(key.toByteArray())).prefix(1)
        return hash + WireCrypto.truncatedHmacSha256(cipher, key) + cipher
    }

    fun packet(payload: Bytes, path: Bytes = Bytes.EMPTY, region: String? = null, type: Int = 5): Bytes {
        val route = if (region == null) RouteType.FLOOD else RouteType.TC_FLOOD
        val transport = region?.let {
            val key = assertNotNull(TransportCodeRegionResolver.deriveScopeKey(it))
            val code = TransportCodeRegionResolver.calcTransportCode(key, type.toUByte(), payload).toInt()
            Bytes.of(code and 255, (code shr 8) and 255, 0, 0)
        } ?: Bytes.EMPTY
        return Bytes.of((type shl 2) or route.rawValue.toInt()) + transport + Bytes.of(path.size) + path + payload
    }

    suspend fun ingest(packet: Bytes): RxLogEntryDTO {
        val before = store.fetchRxLogEntries(radio).map { it.id }.toSet()
        harness.links.radios.last().push(Bytes.of(0x88, 32, 186) + packet)
        var entry: RxLogEntryDTO? = null
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            harness.scheduler.runCurrent()
            entry = store.fetchRxLogEntries(radio).singleOrNull { it.id !in before }
            if (entry != null) break
            Thread.sleep(2)
        }
        return assertNotNull(entry, "real session RX ingestion must persist exactly one new row")
    }

    suspend fun poll(stamp: UInt, text: String, pathLength: Int = 1) {
        pollFrame(Bytes.of(0x11, 20, 0, 0, 0, pathLength, 0) + le32(stamp.toLong()) + Bytes.utf8(text))
    }

    suspend fun pollFrame(frame: Bytes) {
        val firmware = harness.links.radios.last()
        val prior = firmware.respond
        val frames = ConcurrentLinkedQueue(listOf(frame))
        firmware.respond = { command ->
            if (command[0] == 0x0a.toUByte()) listOf(frames.poll() ?: Bytes.of(0x0a)) else prior(command)
        }
        try { assertEquals(1L, live.messagePollingService.pollAllMessages()) }
        finally { firmware.respond = prior }
    }

    fun independentKey(stamp: UInt, body: String, sender: String = "NodeAlpha"): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
            .take(4).joinToString("") { "%02X".format(it.toInt() and 255) }
        return "ch-0-$stamp-$sender-$hash"
    }
}

class RxIngestionOracleTest : RoomProcessTest() {
    private suspend fun TestScope.withFixture(block: suspend (RxIngestionFixture) -> Unit) {
        val f = RxIngestionFixture(this, store)
        try { f.connect(); block(f) } finally { f.harness.container.close() }
    }

    @Test
    @OriginalCase("RxLogServiceAdvertHopTests::advert RX-log entry stamps the inbound hop count keyed by full pubkey()", "native-equivalent")
    @OriginalCase("RxLogServiceAdvertHopTests::a directly-heard advert stamps a hop count of zero()", "native-equivalent")
    @OriginalCase("RxLogServiceAdvertHopTests::a truncated advert payload (shorter than a public key) is never stamped()", "native-equivalent")
    @OriginalCase("RxLogServiceAdvertHopTests::advert timestamp is extracted from the payload and passed to the store()", "native-equivalent")
    @OriginalCase("RxLogServiceAdvertHopTests::a payload exactly 32 bytes (no timestamp) passes nil advertTimestamp()", "native-equivalent")
    @OriginalCase("RxLogServiceAdvertHopTests::a non-advert entry never stamps an inbound hop count()", "native-equivalent")
    @OriginalCase("RxLogServiceAdvertHopTests::a direct-routed advert is never stamped: its path length is route, not hops traversed()", "native-equivalent")
    fun allSevenOriginalAdvertStimuliReachTheRealDiscoveredNodeRepository() = runTest {
        withFixture { f ->
            val publicKey = Bytes(ByteArray(32) { it.toByte() })
            val payload = publicKey + Bytes.of(0xAA, 0xBB, 0xCC, 0xDD)
            data class Stimulus(
                val payload: Bytes, val hops: UByte, val expectedHops: Long?, val expectedStamp: UInt?,
                val type: PayloadType = PayloadType.ADVERT, val route: RouteType = RouteType.FLOOD,
            )
            val stimuli = listOf(
                Stimulus(payload, 3u, 3, 0xDDCCBBAAu),
                Stimulus(payload, 0u, 0, 0xDDCCBBAAu),
                Stimulus(Bytes.of(1, 2, 3), 1u, null, null),
                Stimulus(publicKey + le32(100), 2u, 2, 100u),
                Stimulus(publicKey, 1u, 1, null),
                Stimulus(payload, 2u, null, null, type = PayloadType.GROUP_TEXT),
                Stimulus(payload, 2u, null, null, route = RouteType.DIRECT),
            )
            for (stimulus in stimuli) {
                val node = store.upsertDiscoveredNode(f.radio, ContactFrame(
                    publicKey, ContactType.REPEATER, 0u, 255u, Bytes.EMPTY, "Advertiser", 0u, 0.0, 0.0, 0u,
                )).node
                assertNull(node.inboundHopCount)
                // Retain the source's parsed-event inputs, including its empty pathNodes with nonzero pathLength.
                f.live.rxLogService.process(ParsedRxLogData(
                    8.0, -70, stimulus.payload, stimulus.route, stimulus.type, 0u, 0u, null,
                    stimulus.hops, emptyList<UByte>(), stimulus.payload,
                ))
                val saved = store.fetchDiscoveredNodes(f.radio).single { it.publicKey == publicKey }
                assertEquals(stimulus.expectedHops, saved.inboundHopCount, stimulus.toString())
                assertEquals(stimulus.expectedStamp, saved.inboundHopAdvertTimestamp, stimulus.toString())
                assertEquals(publicKey, saved.publicKey)
                store.deleteDiscoveredNode(EntityKey(f.radio, saved.id))
            }
        }
    }

    @Test
    @OriginalCase("SyncCoordinatorMessageHandlerTests::refreshBlockedContactsCache loads blocked contacts by name()", "native-equivalent")
    @OriginalCase("SyncCoordinatorMessageHandlerTests::refreshBlockedContactsCache does not cache non-blocked contacts()", "native-equivalent")
    @OriginalCase("SyncCoordinatorMessageHandlerTests::refreshBlockedContactsCache replaces previous cache()", "native-equivalent")
    fun blockedNameCacheReadsAndReplacesActualRoomContacts() = runTest {
        withFixture { f ->
            val blocked = ContactDTO(
                radioId = f.radio, publicKey = key(1), name = "BlockedPerson", isBlocked = true, lastHeardTimestamp = 0u,
            )
            val normal = ContactDTO(radioId = f.radio, publicKey = key(2), name = "NormalPerson", lastHeardTimestamp = 0u)
            store.saveContact(blocked)
            store.saveContact(normal)
            f.live.syncCoordinator.refreshBlockedContactsCache(f.radio, store)
            assertTrue(f.live.syncCoordinator.isBlockedSender("BlockedPerson"))
            assertFalse(f.live.syncCoordinator.isBlockedSender("NormalPerson"))
            store.deleteContact(EntityKey(f.radio, blocked.id))
            f.live.syncCoordinator.refreshBlockedContactsCache(f.radio, store)
            assertFalse(f.live.syncCoordinator.isBlockedSender("BlockedPerson"))
        }
    }

    @Test
    @OriginalCase("SyncCoordinatorMessageHandlerTests::inbound DM stamps contact lastHeard()", "native-equivalent")
    @OriginalCase("SyncCoordinatorMessageHandlerTests::inbound channel message does not stamp lastHeard on a contact()", "native-equivalent")
    fun realPollerStampsOnlyTheDirectContactAndKeepsChannelPeerUnchanged() = runTest {
        withFixture { f ->
            val peer = ContactDTO(radioId = f.radio, publicKey = key(0xAB), name = "Peer", lastHeardTimestamp = 0u)
            val channelPeer = ContactDTO(radioId = f.radio, publicKey = key(0xCD), name = "ChannelPeer", lastHeardTimestamp = 0u)
            store.saveContact(peer)
            store.saveContact(channelPeer)
            f.pollFrame(Bytes.of(0x10, 20, 0, 0) + peer.publicKey.prefix(6) +
                Bytes.of(0, 0) + le32(EPOCH.epochSecond) + Bytes.utf8("hello mesh"))
            assertTrue((assertNotNull(store.fetchContact(f.radio, peer.publicKey)).lastHeardTimestamp ?: 0u) > 0u)
            assertEquals(listOf("hello mesh"), store.fetchMessages(EntityKey(f.radio, peer.id)).map { it.text })
            f.poll(EPOCH.epochSecond.toUInt(), "ChannelPeer: hello channel", 0)
            assertEquals(0u, assertNotNull(store.fetchContact(f.radio, channelPeer.publicKey)).lastHeardTimestamp)
        }
    }

    @Test
    @OriginalCase("SyncCoordinatorMessageHandlerTests::duplicate channel receive with unknown path does not save a second message()", "native-equivalent")
    fun originalUnknownPathStimulusKeepsOneRowNoExtrasAndNoUnread() = runTest {
        originalDuplicate(1_704_000_800u, "dup path", Bytes.of(0xB2))
    }

    @Test
    @OriginalCase("SyncCoordinatorMessageHandlerTests::same-path duplicate channel receive inserts nothing()", "native-equivalent")
    fun originalSamePathStimulusKeepsOneRowAndNoExtras() = runTest {
        originalDuplicate(1_704_000_900u, "same path", Bytes.of(0xA1))
    }

    private suspend fun TestScope.originalDuplicate(stamp: UInt, body: String, path: Bytes) = withFixture { f ->
        val key = f.independentKey(stamp, body)
        assertEquals(key, DeduplicationKey.contentBased(null, 0u, "NodeAlpha", stamp, body))
        val original = MessageDTO(
            radioId = f.radio, channelIndex = 0u, text = body, timestamp = stamp,
            direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
            pathLength = 1u, pathNodes = Bytes.of(0xA1), senderNodeName = "NodeAlpha", deduplicationKey = key,
        )
        store.saveMessage(original)
        val parsed = assertNotNull(RxLogParser.parse(5.0, -80, f.packet(Bytes.of(0xAA, 0xBB, 0xCC), path)))
        val row = RxLogEntryDTO.fromParsed(
            f.radio, parsed, receivedAt = Instant.now(), channelIndex = 0u, channelName = "Public",
            decryptStatus = DecryptStatus.SUCCESS, senderTimestamp = stamp,
        )
        store.saveRxLogEntry(row)
        assertNull(f.live.rxLogService.decryptEntry(row).decodedText)
        f.poll(stamp, "NodeAlpha: $body")
        val saved = store.fetchMessages(f.radio, 0u)
        assertEquals(listOf(original.id), saved.map { it.id })
        assertEquals(0L, saved.single().heardRepeats)
        assertTrue(store.fetchMessageRepeats(EntityKey(f.radio, original.id)).isEmpty())
        assertEquals(0, assertNotNull(store.fetchChannel(f.radio, 0u)).unreadCount)
    }

    @Test fun decryptableEqualPathAndDistinctPathReachRealGuardsAfterTwoActualReceives() = runTest {
        withFixture { f ->
            val stamp = 1_704_000_900u
            val text = "NodeAlpha: same path"
            val payload = f.encrypted(stamp, text)
            val first = f.ingest(f.packet(payload, Bytes.of(0xA1)))
            assertEquals(DecryptStatus.SUCCESS, first.decryptStatus)
            assertEquals(text, f.live.rxLogService.decryptEntry(first).decodedText)
            f.poll(stamp, text)
            val key = f.independentKey(stamp, "same path")
            val saved = assertNotNull(store.fetchMessage(key, f.radio))
            assertEquals(Bytes.of(0xA1), saved.pathNodes)
            assertEquals(0L, saved.heardRepeats)
            val unread = assertNotNull(store.fetchChannel(f.radio, 0u)).unreadCount

            f.ingest(f.packet(payload, Bytes.of(0xA1)))
            f.poll(stamp, text)
            assertEquals(listOf(saved.id), store.fetchMessages(f.radio, 0u).map { it.id })
            assertTrue(store.fetchMessageRepeats(EntityKey(f.radio, saved.id)).isEmpty())
            assertEquals(0L, assertNotNull(store.fetchMessage(key, f.radio)).heardRepeats)
            assertEquals(unread, assertNotNull(store.fetchChannel(f.radio, 0u)).unreadCount)

            val distinct = f.ingest(f.packet(payload, Bytes.of(0xB2)))
            val deadline = System.nanoTime() + 5_000_000_000L
            while (store.fetchMessageRepeats(EntityKey(f.radio, saved.id)).isEmpty() && System.nanoTime() < deadline) {
                f.harness.scheduler.runCurrent()
                Thread.sleep(2)
            }
            val repeat = store.fetchMessageRepeats(EntityKey(f.radio, saved.id)).single()
            assertEquals(distinct.id, repeat.rxLogEntryID)
            assertEquals(Bytes.of(0xB2), repeat.pathNodes)
            assertEquals(1L, assertNotNull(store.fetchMessage(key, f.radio)).heardRepeats)
            f.poll(stamp, text)
            assertEquals(listOf(repeat), store.fetchMessageRepeats(EntityKey(f.radio, saved.id)))
            assertEquals(unread, assertNotNull(store.fetchChannel(f.radio, 0u)).unreadCount)
        }
    }

    @Test fun realCorrelationAdoptsUnknownPathAndOutgoingHarvestDoesNotAdopt() = runTest {
        withFixture { f ->
            val stamp = 1_704_000_800u
            val text = "NodeAlpha: dup path"
            f.poll(stamp, text)
            val key = f.independentKey(stamp, "dup path")
            assertEquals(key, DeduplicationKey.contentBased(null, 0u, "NodeAlpha", stamp, "dup path"))
            val unknown = assertNotNull(store.fetchMessage(key, f.radio))
            assertNull(unknown.pathNodes)
            val entry = f.ingest(f.packet(f.encrypted(stamp, text), Bytes.of(0xA1)))
            val messageKey = EntityKey(f.radio, unknown.id)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (store.fetchMessage(messageKey)?.pathNodes == null && System.nanoTime() < deadline) {
                f.harness.scheduler.runCurrent()
                Thread.sleep(2)
            }
            assertEquals(Bytes.of(0xA1), assertNotNull(store.fetchMessage(messageKey)).pathNodes)
            assertFalse(store.adoptIncomingPathIfUnknown(messageKey, Bytes.of(0xB2), 1u))
            assertEquals(Bytes.of(0xA1), assertNotNull(store.fetchMessage(messageKey)).pathNodes)
            assertTrue(store.fetchMessageRepeats(messageKey).isEmpty())
            assertFalse(store.messageRepeatExists(EntityKey(f.radio, entry.id)))
            val decoded = assertNotNull(f.live.rxLogService.decryptEntry(entry).decodedText)
            assertEquals(text, decoded)
            val candidate = entry.copy(decodedText = decoded)
            val incoming = unknown.copy(id = UUID.randomUUID())
            val incomingKey = EntityKey(f.radio, incoming.id)
            store.saveMessage(incoming)
            f.live.heardRepeatsService.harvestIncomingPaths(incoming, listOf(candidate))
            assertEquals(Bytes.of(0xA1), assertNotNull(store.fetchMessage(incomingKey)).pathNodes)

            val outgoing = unknown.copy(id = UUID.randomUUID(), direction = MessageDirection.OUTGOING)
            val outgoingKey = EntityKey(f.radio, outgoing.id)
            store.saveMessage(outgoing)
            f.live.heardRepeatsService.harvestIncomingPaths(outgoing, listOf(candidate))
            assertNull(assertNotNull(store.fetchMessage(outgoingKey)).pathNodes)
            assertTrue(store.fetchMessageRepeats(outgoingKey).isEmpty())
            assertTrue(store.adoptIncomingPathIfUnknown(outgoingKey, Bytes.of(0xC3), 1u))
            assertFalse(store.adoptIncomingPathIfUnknown(outgoingKey, Bytes.of(0xB2), 1u))
            assertEquals(Bytes.of(0xC3), assertNotNull(store.fetchMessage(outgoingKey)).pathNodes)
        }
    }

    @Test
    @OriginalCase("SyncCoordinatorMessageHandlerTests::channel save with undecryptable RX harvests no extras and still broadcasts()", "native-equivalent")
    fun originalUndecryptableRowsDoNotAcquirePathsRegionsOrExtrasButStillBroadcast() = runTest {
        withFixture { f ->
            val stamp = 1_704_000_700u
            for ((index, path) in listOf(Bytes.of(0xA1), Bytes.of(0xB2)).withIndex()) {
                val parsed = assertNotNull(RxLogParser.parse(5.0, -80, f.packet(Bytes.of(0xAA, 0xBB, 0xCC), path)))
                store.saveRxLogEntry(RxLogEntryDTO.fromParsed(
                    f.radio, parsed, receivedAt = Instant.ofEpochSecond(stamp.toLong() + index),
                    channelIndex = 0u, channelName = "Public", decryptStatus = DecryptStatus.SUCCESS,
                    senderTimestamp = stamp, regionScope = "Germany", regionScopeMatches = SnapshotList.of("Germany"),
                ))
            }
            val events = CopyOnWriteArrayList<MessageEvent>()
            val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.harness.appState.messageEventStream.events().collect { events += it }
            }
            try {
                f.poll(stamp, "NodeAlpha: harvest broadcast")
                val saved = store.fetchMessages(f.radio, 0u).single()
                assertNull(saved.pathNodes)
                assertNull(saved.regionScope)
                assertTrue(saved.regionScopeMatches.isEmpty())
                assertEquals(0L, saved.heardRepeats)
                assertTrue(store.fetchMessageRepeats(EntityKey(f.radio, saved.id)).isEmpty())
                f.harness.eventually("undecryptable channel still broadcasts") {
                    events.filterIsInstance<MessageEvent.ChannelMessageReceived>().any { it.message.id == saved.id && it.message.heardRepeats == 0L }
                }
            } finally { observer.cancelAndJoin() }
        }
    }

    @Test
    @OriginalCase("SyncCoordinatorMessageHandlerTests::live channel receive with undecryptable RX does not copy regionScope onto Message()", "native-equivalent")
    fun originalUndecryptableRegionStimulusDoesNotCopyScopeOntoMessage() = runTest {
        withFixture { f ->
            val stamp = 1_704_000_500u
            val parsed = assertNotNull(RxLogParser.parse(5.0, -80, f.packet(Bytes.of(0xAA, 0xBB, 0xCC))))
                .copy(rawPayload = Bytes.of(0x10, 0x20, 0x30))
            val row = RxLogEntryDTO.fromParsed(
                f.radio, parsed, channelIndex = 0u, channelName = "Public", decryptStatus = DecryptStatus.SUCCESS,
                senderTimestamp = stamp, regionScope = "Germany", regionScopeMatches = SnapshotList.of("Germany"),
            )
            store.saveRxLogEntry(row)
            assertNull(f.live.rxLogService.decryptEntry(row).decodedText)
            f.poll(stamp, "NodeAlpha: region scope test", 0)
            val saved = store.fetchMessages(f.radio, 0u).single()
            assertNull(saved.regionScope)
            assertTrue(saved.regionScopeMatches.isEmpty())
            assertNull(saved.pathNodes)
        }
    }

    @Test
    @OriginalCase("RxLogServiceReprocessTests::late-decrypted channel entries keep the matching channel index and name()", "native-equivalent")
    fun realLateDecryptionKeepsChannelThreeAndPersistsTheWireTimestamp() = runTest {
        withFixture { f ->
            val zero = Bytes(ByteArray(16) { 0x99.toByte() })
            val three = Bytes(ByteArray(16) { 0x42 })
            val stamp = 1_700_000_000u
            f.live.rxLogService.updateChannels(mapOf(0.toUByte() to zero), mapOf(0.toUByte() to "Public"))
            val entry = f.ingest(f.packet(f.encrypted(stamp, "hello", three)))
            assertEquals(DecryptStatus.NO_MATCHING_KEY, entry.decryptStatus)
            f.live.rxLogService.updateChannels(
                mapOf(0.toUByte() to zero, 3.toUByte() to three),
                mapOf(0.toUByte() to "Public", 3.toUByte() to "Three"),
            )
            store.flushPendingRxLogEntries()
            val updated = store.fetchRxLogEntries(f.radio).single { it.id == entry.id }
            assertEquals(3.toUByte(), updated.channelIndex)
            assertEquals("Three", updated.channelName)
            assertEquals(stamp, updated.senderTimestamp)
            assertEquals(DecryptStatus.SUCCESS, updated.decryptStatus)
            assertEquals("hello", f.live.rxLogService.decryptEntry(updated).decodedText)
        }
    }

    @Test
    @OriginalCase("RxLogServiceReprocessTests::coexisting entry stream subscribers each receive every entry()", "native-equivalent")
    @OriginalCase("RxLogServiceReprocessTests::finishEntryStream ends every subscriber's iteration()", "native-equivalent")
    fun realSessionIngestionMulticastsTheSamePersistedEntryAndFinishesBothSubscribers() = runTest {
        withFixture { f ->
            val a = CopyOnWriteArrayList<RxLogEntryDTO>()
            val b = CopyOnWriteArrayList<RxLogEntryDTO>()
            val streamA = f.live.rxLogService.entryStream()
            val streamB = f.live.rxLogService.entryStream()
            val taskA = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { streamA.collect { a += it }; true }
            val taskB = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { streamB.collect { b += it }; true }
            val entry = f.ingest(f.packet(Bytes.of(1, 2, 3, 4), type = 4))
            f.harness.eventually("both real RX subscribers receive") { a.size == 1 && b.size == 1 }
            assertEquals(entry.id, a.single().id)
            assertEquals(entry.id, b.single().id)
            f.live.rxLogService.finishEntryStream()
            assertTrue(taskA.await())
            assertTrue(taskB.await())
        }
    }
}
