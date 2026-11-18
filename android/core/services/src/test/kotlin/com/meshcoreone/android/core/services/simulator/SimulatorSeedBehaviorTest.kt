// AndroidOnly: WP-217 native seed checks: determinism, clock injection, write order, linkage, skips and failures.
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.canonicalString
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.services.simulator.SimulatorTestSupport.blocking
import com.meshcoreone.android.core.services.simulator.SimulatorTestSupport.native
import com.meshcoreone.android.core.services.simulator.SimulatorTestSupport.oracleNow
import com.meshcoreone.android.core.services.simulator.SimulatorTestSupport.seededStore
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class SimulatorSeedBehaviorTest {
    private val radioId = MockDataProvider.simulatorRadioId

    private fun assertSeedFailurePropagated(expected: Exception, observed: Exception) {
        if (expected is CancellationException) {
            assertTrue(observed is CancellationException)
            assertTrue(observed === expected || observed.cause === expected)
        } else {
            assertSame(expected, observed)
        }
    }

    @TestFactory
    fun determinismTests(): List<DynamicTest> = listOf(
        native("seed is deterministic for a fixed clock") {
            val first = seededStore()
            val second = seededStore()
            assertEquals(first.allMessages().sortedBy { it.id }, second.allMessages().sortedBy { it.id })
            assertEquals(first.allReactions().sortedBy { it.id }, second.allReactions().sortedBy { it.id })
            assertEquals(first.allRepeats().sortedBy { it.id }, second.allRepeats().sortedBy { it.id })
            assertEquals(first.allRxEntries(), second.allRxEntries())
            val key = MockDataProvider.locationHistoryNodePublicKey
            assertEquals(first.fetchNodeStatusSnapshots(key, null), second.fetchNodeStatusSnapshots(key, null))
            assertEquals(first.fetchContacts(radioId), second.fetchContacts(radioId))
            assertEquals(first.device(MockDataProvider.simulatorDeviceID), second.device(MockDataProvider.simulatorDeviceID))
        },
        native("seed timestamps move with the injected clock") {
            val shift = 3_600L
            val base = seededStore(oracleNow)
            val later = seededStore(oracleNow.plusSeconds(shift))
            for (message in base.allMessages()) {
                val moved = assertNotNull(later.fetchMessage(message.id))
                assertEquals(message.createdAt.plusSeconds(shift), moved.createdAt, message.text)
                assertEquals(message.timestamp + shift.toUInt(), moved.timestamp, message.text)
                assertEquals(message.senderTimestamp?.plus(shift.toUInt()), moved.senderTimestamp, message.text)
            }
            for (contact in base.fetchContacts(radioId)) {
                val moved = later.fetchContacts(radioId).first { it.id == contact.id }
                assertEquals(contact.lastAdvertTimestamp + shift.toUInt(), moved.lastAdvertTimestamp)
                assertEquals(contact.lastMessageDate?.plusSeconds(shift), moved.lastMessageDate)
            }
            for (repeat in base.allRepeats()) {
                assertEquals(repeat.receivedAt.plusSeconds(shift), later.allRepeats().first { it.id == repeat.id }.receivedAt)
            }
            assertEquals(oracleNow.plusSeconds(shift), later.device(MockDataProvider.simulatorDeviceID)?.lastConnected)
        },
        native("every seeded row stays in the simulator radio") {
            val store = seededStore()
            assertTrue(store.allMessages().all { it.radioId == radioId })
            assertTrue(store.allReactions().all { it.radioId == radioId })
            assertTrue(store.allRxEntries().all { it.radioId == radioId })
            assertTrue(store.fetchChannels(radioId).size == 4 && store.fetchContacts(radioId).size == 11)
        },
    )

    @TestFactory
    fun writeOrderTests(): List<DynamicTest> = listOf(
        native("seed writes in Swift order with messages before their dependent rows") {
            val store = seededStore()
            val now = oracleNow
            val contacts = MockDataProvider.contacts(now)
            val channels = MockDataProvider.channels(now)
            val messageCount = contacts.sumOf { MockDataProvider.messages(it.id, now).size } +
                channels.sumOf { MockDataProvider.channelMessages(it.index, now).size }
            val expected = buildList {
                add("saveDevice")
                repeat(contacts.size) { add("fetchContact"); add("saveContact") }
                repeat(channels.size) { add("saveChannel") }
                repeat(messageCount) { add("saveMessage") }
                repeat(MockDataProvider.linkPreviewSeeds.size) { add("updateMessageLinkPreview") }
                for (reacted in MockDataProvider.reactedMessages) {
                    repeat(MockDataProvider.reactions(reacted.messageID, now).size) { add("saveReaction") }
                    add("updateMessageReactionSummary")
                }
                for (id in MockDataProvider.messagesWithRepeats) {
                    repeat(MockDataProvider.messageRepeats(id, now).size) { add("saveMessageRepeat") }
                }
                add("fetchRxLogEntries")
                repeat(2) { add("saveRxLogEntry") }
                add("fetchLatestNodeStatusSnapshot")
                add("existingNodeStatusSnapshotKeys")
                add("batchInsertNodeStatusSnapshots")
            }
            assertEquals(expected, store.recordedCalls)
        },
        native("default replay retains writes before a later fixture timestamp fails") {
            val now = Instant.ofEpochSecond(100_000)
            val contacts = MockDataProvider.contacts(now)
            val channels = MockDataProvider.channels(now)
            val aliceMessages = MockDataProvider.messages(MockDataProvider.aliceChenID, now)
            assertFailsWith<IllegalArgumentException> { MockDataProvider.messages(MockDataProvider.bobMartinezID, now) }
            val store = SimulatorInMemorySeedStore()

            assertFailsWith<IllegalArgumentException> {
                blocking { SimulatorTestSupport.mode(SettableClock(now)).seedDataStore(store) }
            }

            val expected = buildList {
                add("saveDevice")
                repeat(contacts.size) { add("fetchContact"); add("saveContact") }
                repeat(channels.size) { add("saveChannel") }
                repeat(aliceMessages.size) { add("saveMessage") }
            }
            assertEquals(expected, store.recordedCalls)
            assertEquals(aliceMessages.map { it.id }.toSet(), store.allMessages().map { it.id }.toSet())
        },
        native("default replay preserves earlier contact failure and cancellation") {
            val now = Instant.ofEpochSecond(100_000)
            val failures = listOf(
                PersistenceStoreException(PersistenceStoreError.InvalidData),
                CancellationException("contact seed cancelled"),
            )
            for (failure in failures) {
                val store = SimulatorInMemorySeedStore().apply { failOn("saveContact") { failure } }
                assertSeedFailurePropagated(failure, assertFailsWith<Exception> {
                    blocking { SimulatorTestSupport.mode(SettableClock(now)).seedDataStore(store) }
                })
                assertEquals(listOf("saveDevice", "fetchContact", "saveContact"), store.recordedCalls)
            }
        },
    )

    @TestFactory
    fun linkageTests(): List<DynamicTest> = listOf(
        native("conversation bundles contain the original parent and complete message sidecars") {
            val backing = SimulatorInMemorySeedStore()
            var received: SnapshotList<SimulatorFixtureConversation>? = null
            val store = object : SimulatorSeedStore by backing {
                override suspend fun seedConversations(
                    buildPlan: () -> SimulatorDeferredFixturePlan,
                    replayReference: suspend () -> Unit,
                ): SimulatorFixtureSeedResult {
                    val plan = buildPlan()
                    assertSame(plan, buildPlan())
                    received = plan.families.map { it.build() }.snapshot()
                    replayReference()
                    return SimulatorFixtureSeedResult.ReferenceReplay
                }
            }
            blocking { SimulatorTestSupport.mode().seedDataStore(store) }
            val conversations = assertNotNull(received)
            val contacts = MockDataProvider.contacts(oracleNow)
            val channels = MockDataProvider.channels(oracleNow)
            assertEquals(contacts.size + channels.size, conversations.size)
            assertEquals(contacts, conversations.mapNotNull { (it.parent as? SimulatorFixtureParent.Direct)?.contact })
            assertEquals(channels, conversations.mapNotNull { (it.parent as? SimulatorFixtureParent.Channel)?.channel })
            val previews = MockDataProvider.linkPreviewSeeds.associateBy { it.messageID }
            val summaries = MockDataProvider.reactedMessages.associateBy { it.messageID }
            for (conversation in conversations) {
                val raw = when (val parent = conversation.parent) {
                    is SimulatorFixtureParent.Direct -> MockDataProvider.messages(parent.contact.id, oracleNow)
                    is SimulatorFixtureParent.Channel -> MockDataProvider.channelMessages(parent.channel.index, oracleNow)
                }
                assertEquals(raw.map { it.id }, conversation.messages.map { it.id })
                for ((original, actual) in raw.zip(conversation.messages)) {
                    val preview = previews[original.id]
                    assertEquals(
                        original.copy(
                            linkPreviewURL = preview?.url ?: original.linkPreviewURL,
                            linkPreviewTitle = preview?.title ?: original.linkPreviewTitle,
                            linkPreviewImageData = preview?.imageData ?: original.linkPreviewImageData,
                            linkPreviewIconData = if (preview != null) null else original.linkPreviewIconData,
                            linkPreviewFetched = preview != null || original.linkPreviewFetched,
                            reactionSummary = summaries[original.id]?.summary ?: original.reactionSummary,
                        ),
                        actual,
                    )
                }
                val ids = raw.map { it.id }.toSet()
                assertEquals(
                    MockDataProvider.reactedMessages.filter { it.messageID in ids }
                        .flatMap { MockDataProvider.reactions(it.messageID, oracleNow) }.sortedBy { it.id },
                    conversation.reactions.sortedBy { it.id },
                )
                assertEquals(
                    MockDataProvider.messagesWithRepeats.filter { it in ids }
                        .flatMap { MockDataProvider.messageRepeats(it, oracleNow) }.sortedBy { it.id },
                    conversation.repeats.sortedBy { it.id },
                )
            }
            val alice = conversations.single {
                (it.parent as? SimulatorFixtureParent.Direct)?.contact?.id == MockDataProvider.aliceChenID
            }
            assertEquals(3L, (alice.parent as SimulatorFixtureParent.Direct).contact.unreadCount)
            assertEquals(5, alice.messages.count { it.direction == MessageDirection.INCOMING && !it.isRead })
            val mesh = conversations.single {
                (it.parent as? SimulatorFixtureParent.Channel)?.channel?.index == MockDataProvider.meshHQChannelIndex
            }
            assertEquals(60L, (mesh.parent as SimulatorFixtureParent.Channel).channel.unreadCount)
            assertEquals(60, mesh.messages.count { !it.isRead })
            assertEquals(8, mesh.messages.count { !it.isRead && it.isOutgoing })
        },
        native("every reaction summary matches its seeded reaction rows") {
            val store = seededStore()
            for (reacted in MockDataProvider.reactedMessages) {
                val message = assertNotNull(store.fetchMessage(reacted.messageID))
                assertEquals(reacted.summary, message.reactionSummary)
                val rows = MockDataProvider.reactions(reacted.messageID, oracleNow)
                val counted = rows.groupBy { it.emoji }.map { (emoji, group) -> "$emoji:${group.size}" }.joinToString(",")
                assertEquals(reacted.summary, counted, "summary must count the seeded rows in first-appearance order")
                for (row in rows) {
                    assertEquals(reacted.messageID.canonicalString().take(8), row.messageHash)
                    assertEquals(row.emoji, row.rawText)
                    assertEquals(message.contactID, row.contactID, "reaction must target the message's DM")
                    assertEquals(message.channelIndex, row.channelIndex, "reaction must target the message's channel")
                }
                assertEquals(rows.size, store.fetchReactions(reacted.messageID).size)
            }
        },
        native("every repeated message has heardRepeats equal to its repeat rows heard after it") {
            val store = seededStore()
            for (messageID in MockDataProvider.messagesWithRepeats) {
                val message = assertNotNull(store.fetchMessage(messageID))
                val repeats = store.fetchMessageRepeats(messageID)
                assertEquals(message.heardRepeats, repeats.size.toLong(), message.text)
                assertTrue(repeats.all { it.receivedAt.isAfter(message.createdAt) }, message.text)
                assertEquals(repeats.size, repeats.map { it.pathNodes }.toSet().size, "routes must be distinct")
            }
            val linkedRepeats = MockDataProvider.messagesWithRepeats.sumOf { store.fetchMessageRepeats(it).size }
            assertEquals(store.allRepeats().size, linkedRepeats, "no orphan repeat rows")
        },
        native("Mesh HQ backlog puts the first unread message past one page") {
            val messages = MockDataProvider.channelMessages(MockDataProvider.meshHQChannelIndex, oracleNow)
            assertEquals(MESH_HQ_TOTAL_MESSAGES, messages.size)
            val unread = messages.count { !it.isRead }
            assertEquals(MESH_HQ_UNREAD_COUNT, unread)
            assertTrue(unread > 50, "unread must exceed a 50-row page")
            val channel = MockDataProvider.channels(oracleNow).first { it.index == MockDataProvider.meshHQChannelIndex }
            assertEquals(unread.toLong(), channel.unreadCount)
            assertEquals(messages.sortedBy { it.createdAt }, messages, "backlog is chronological")
            assertEquals(MESH_HQ_TOTAL_MESSAGES / 8, messages.count { it.isOutgoing })
        },
    )

    @TestFactory
    fun skipAndFailureTests(): List<DynamicTest> = listOf(
        native("conversation seed failure and cancellation propagate before diagnostics are seeded") {
            val failures = listOf(
                PersistenceStoreException(PersistenceStoreError.InvalidData),
                CancellationException("conversation seed cancelled"),
            )
            for (failure in failures) {
                val backing = SimulatorInMemorySeedStore()
                val store = object : SimulatorSeedStore by backing {
                    override suspend fun seedConversations(
                        buildPlan: () -> SimulatorDeferredFixturePlan,
                        replayReference: suspend () -> Unit,
                    ): SimulatorFixtureSeedResult = throw failure
                }
                assertSeedFailurePropagated(failure, assertFailsWith<Exception> {
                    blocking { SimulatorTestSupport.mode().seedDataStore(store) }
                })
                assertEquals(listOf("saveDevice"), backing.recordedCalls)
            }
        },
        native("a durable result must account for every conversation") {
            val backing = SimulatorInMemorySeedStore()
            val store = object : SimulatorSeedStore by backing {
                override suspend fun seedConversations(
                    buildPlan: () -> SimulatorDeferredFixturePlan,
                    replayReference: suspend () -> Unit,
                ): SimulatorFixtureSeedResult = SimulatorFixtureSeedResult.Durable(0, 0)
            }
            assertFailsWith<IllegalStateException> {
                blocking { SimulatorTestSupport.mode().seedDataStore(store) }
            }
            assertEquals(listOf("saveDevice"), backing.recordedCalls)
        },
        native("a contact seed override receives the raw fixture without a separate avatar lookup") {
            val backing = SimulatorInMemorySeedStore()
            val received = mutableListOf<ContactDTO>()
            val store = object : SimulatorSeedStore by backing {
                override suspend fun seedContact(dto: ContactDTO) {
                    received += dto
                    backing.saveContact(dto)
                }
                override suspend fun fetchContact(key: EntityKey): ContactDTO? =
                    error("The override must receive the raw fixture")
                override suspend fun saveContact(dto: ContactDTO): Unit =
                    error("The override must own the complete seed operation")
            }

            blocking { SimulatorTestSupport.mode().seedDataStore(store) }

            assertEquals(MockDataProvider.contacts(oracleNow).toList(), received.toList())
            assertTrue(received.all { it.avatarImageData == null })
            assertTrue("fetchContact" !in backing.recordedCalls)
        },
        native("default contact seed propagates avatar read and save failures and cancellation") {
            for (operation in listOf("fetchContact", "saveContact")) {
                val failing = SimulatorInMemorySeedStore().apply { failOn(operation) }
                assertFailsWith<PersistenceStoreException> {
                    blocking { SimulatorTestSupport.mode().seedDataStore(failing) }
                }
                assertTrue("saveChannel" !in failing.recordedCalls)

                val cancelled = SimulatorInMemorySeedStore().apply {
                    failOn(operation) { CancellationException("contact seed cancelled") }
                }
                assertFailsWith<CancellationException> {
                    blocking { SimulatorTestSupport.mode().seedDataStore(cancelled) }
                }
                assertTrue("saveChannel" !in cancelled.recordedCalls)
            }
        },
        native("contact seed override propagates its failure and cancellation") {
            val failures = listOf(
                PersistenceStoreException(PersistenceStoreError.InvalidData),
                CancellationException("contact seed cancelled"),
            )
            for (failure in failures) {
                val backing = SimulatorInMemorySeedStore()
                val store = object : SimulatorSeedStore by backing {
                    override suspend fun seedContact(dto: ContactDTO): Unit = throw failure
                }
                assertSeedFailurePropagated(failure, assertFailsWith<Exception> {
                    blocking { SimulatorTestSupport.mode().seedDataStore(store) }
                })
                assertEquals(listOf("saveDevice"), backing.recordedCalls)
            }
        },
        native("seeding inserts only the RX log entries that are missing") {
            val store = SimulatorInMemorySeedStore()
            store.insertRxEntry(MockDataProvider.rxLogEntries(oracleNow).first())
            blocking { SimulatorTestSupport.mode().seedDataStore(store) }
            assertEquals(2, store.allRxEntries().size)
            assertEquals(1, store.recordedCalls.count { it == "saveRxLogEntry" })
        },
        native("node snapshots are not restacked once the node has history") {
            val store = SimulatorInMemorySeedStore()
            val existing = NodeStatusSnapshotDTO(timestamp = oracleNow.minus(Duration.ofDays(400)), nodePublicKey = MockDataProvider.locationHistoryNodePublicKey)
            store.insertSnapshot(existing)
            blocking { SimulatorTestSupport.mode().seedDataStore(store) }
            assertEquals(listOf(existing), store.fetchNodeStatusSnapshots(MockDataProvider.locationHistoryNodePublicKey, null))
            assertTrue("batchInsertNodeStatusSnapshots" !in store.recordedCalls)
        },
        native("snapshot ids follow their store key and never repeat across seed instants") {
            val first = MockDataProvider.nodeStatusSnapshots(oracleNow)
            val again = MockDataProvider.nodeStatusSnapshots(oracleNow)
            val later = MockDataProvider.nodeStatusSnapshots(oracleNow.plusSeconds(90))
            assertEquals(first.map { it.id }, again.map { it.id }, "fixed clock, fixed ids")
            assertEquals(first.size, first.map { it.id }.toSet().size, "ids are unique within a pass")
            assertTrue(first.map { it.id }.toSet().intersect(later.map { it.id }.toSet()).isEmpty(), "a later pass reuses no id")
            for (row in first) assertEquals(nodeStatusSnapshotID(NodeStatusSnapshotKey.of(row)), row.id)
        },
        native("re-seeding reapplies the preview blobs that saveMessage resets") {
            val clock = SettableClock(oracleNow)
            val store = SimulatorInMemorySeedStore()
            val mode = SimulatorTestSupport.mode(clock)
            blocking { mode.seedDataStore(store) }
            clock.advance(90)
            blocking { mode.seedDataStore(store) }
            val message = assertNotNull(store.fetchMessage(MockDataProvider.aliceLinkPreviewMessageID))
            assertEquals(MockDataProvider.demoImageData, message.linkPreviewImageData)
            assertEquals(true, message.linkPreviewFetched)
            assertEquals("https://meshcoreone.com/trails/skyline", message.linkPreviewURL)
        },
        native("snapshot keys truncate to whole milliseconds like Swift") {
            val key = MockDataProvider.locationHistoryNodePublicKey
            assertEquals(1_000L, NodeStatusSnapshotKey.of(key, java.time.Instant.ofEpochSecond(1, 999_999)).milliseconds)
            assertEquals(0L, NodeStatusSnapshotKey.of(key, java.time.Instant.ofEpochSecond(-1, 999_500_000)).milliseconds)
            assertEquals(-500L, NodeStatusSnapshotKey.of(key, java.time.Instant.ofEpochSecond(-1, 500_000_000)).milliseconds)
        },
        native("store failures propagate out of the seed") {
            val store = SimulatorInMemorySeedStore()
            store.failOn("saveChannel")
            assertFailsWith<PersistenceStoreException> { blocking { SimulatorTestSupport.mode().seedDataStore(store) } }
            assertTrue("saveMessage" !in store.recordedCalls, "seed must stop at the first failure")
        },
        native("store cancellation propagates out of the seed") {
            val store = SimulatorInMemorySeedStore()
            store.failOn("saveReaction") { CancellationException("store cancelled") }
            assertFailsWith<CancellationException> { blocking { SimulatorTestSupport.mode().seedDataStore(store) } }
            assertTrue("updateMessageReactionSummary" !in store.recordedCalls)
        },
        native("concurrent seed passes are serialized") {
            val store = SimulatorInMemorySeedStore()
            val gate = SimulatorInMemorySeedStore.RxGate()
            store.rxGate = gate
            val mode = SimulatorTestSupport.mode()
            blocking {
                val first = launch { mode.seedDataStore(store) }
                gate.reached.await()
                val second = launch { mode.seedDataStore(store) }
                repeat(SCHEDULER_TURNS) { yield() }
                assertEquals(1, store.recordedCalls.count { it == "saveDevice" }, "second pass must wait for the first")
                gate.release.complete(Unit)
                first.join()
                second.join()
            }
            assertEquals(2, store.recordedCalls.count { it == "saveDevice" })
            assertEquals(2, store.allRxEntries().size, "RX fixtures must not be inserted twice")
        },
    )

    private companion object {
        const val SCHEDULER_TURNS = 50
    }
}
