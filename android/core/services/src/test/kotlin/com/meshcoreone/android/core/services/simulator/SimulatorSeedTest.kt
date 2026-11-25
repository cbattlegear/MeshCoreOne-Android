// PortedFrom: MC1Services/Tests/MC1ServicesTests/SimulatorSeedTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.services.simulator.SimulatorTestSupport.blocking
import com.meshcoreone.android.core.services.simulator.SimulatorTestSupport.seededStore
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * `saveMessage` does not persist the link-preview or `reactionSummary` columns, and the wire `ChannelInfo`
 * carries no notification/favorite state. These verify the seed's dedicated mutators (and the DTO-based
 * `saveChannel`) write those through. The SwiftData in-memory container becomes [SimulatorInMemorySeedStore];
 * the re-seed cases advance the clock between passes, as a real reconnect would.
 */
class SimulatorSeedTest {
    private val radioID = MockDataProvider.simulatorRadioId

    @TestFactory
    fun seedLandsTests(): List<DynamicTest> = listOf(
        case("link preview columns land") {
            val store = seededStore()
            val message = assertNotNull(store.fetchMessage(MockDataProvider.aliceLinkPreviewMessageID))
            assertEquals("Skyline Ridge Trail Guide", message.linkPreviewTitle)
            assertEquals(true, message.linkPreviewFetched)
            val imageData = assertNotNull(message.linkPreviewImageData)
            assertFalse(imageData.isEmpty)
        },
        case("reaction summary and rows land") {
            val store = seededStore()

            val dmMessage = assertNotNull(store.fetchMessage(MockDataProvider.aliceReactedMessageID))
            assertEquals("👍:2,❤️:1", dmMessage.reactionSummary)
            assertEquals(3, store.fetchReactions(MockDataProvider.aliceReactedMessageID).size)

            val channelMessage = assertNotNull(store.fetchMessage(MockDataProvider.bayAreaReactedMessageID))
            assertEquals("🎉:2", channelMessage.reactionSummary)
            assertEquals(2, store.fetchReactions(MockDataProvider.bayAreaReactedMessageID).size)
        },
        case("channel notification state lands") {
            val store = seededStore()
            val channels = store.fetchChannels(radioID)

            val muted = assertNotNull(channels.firstOrNull { it.index == MockDataProvider.trailCrewChannelIndex })
            assertEquals(NotificationLevel.MUTED, muted.notificationLevel)

            val favorite = assertNotNull(channels.firstOrNull { it.index == MockDataProvider.bayAreaChannelIndex })
            assertTrue(favorite.isFavorite)
        },
        case("heard repeats land") {
            val store = seededStore()
            assertEquals(3, store.fetchMessageRepeats(MockDataProvider.frankRepeatMessageID).size)
        },
        case("incoming extra paths land on located repeater prefixes") {
            val store = seededStore()
            val repeaterPrefixes = store.fetchContacts(radioID)
                .filter { it.type == ContactType.REPEATER && it.hasLocation }
                .map { it.publicKey.prefix(1) }
                .toSet()
            val seeds = listOf(
                MockDataProvider.northRidgeRepeaterSeed,
                MockDataProvider.twinPeaksRepeaterSeed,
                MockDataProvider.oaklandRepeaterSeed,
            )
            for (seed in seeds) assertTrue(Bytes.of(seed.toInt()) in repeaterPrefixes, "missing repeater prefix $seed")

            for (messageID in listOf(MockDataProvider.aliceMultiPathMessageID, MockDataProvider.publicMultiPathMessageID)) {
                val message = assertNotNull(store.fetchMessage(messageID))
                assertFalse(message.isOutgoing)
                assertEquals(INCOMING_EXTRA_PATH_COUNT.toLong(), message.heardRepeats)
                assertEquals(MockDataProvider.incomingFirstPath, message.pathNodes)
                assertEquals(2L, message.hopCount)
                val repeats = store.fetchMessageRepeats(messageID)
                assertEquals(INCOMING_EXTRA_PATH_COUNT, repeats.size)
                assertEquals(setOf(1L, 2L, 3L), repeats.map { it.hopCount }.toSet())
                for (byte in repeats.flatMap { it.pathNodes }) {
                    assertTrue(Bytes.of(byte.toInt()) in repeaterPrefixes, "hop $byte is not a located repeater")
                }
            }
        },
        case("flood route fields round trip through save message") {
            val store = seededStore()
            val message = assertNotNull(store.fetchMessage(MockDataProvider.frankFloodUniqueMessageID))
            assertEquals(RouteType.TC_FLOOD, message.routeType)
            assertEquals(MockDataProvider.uniqueRegionName, message.regionScope)
            assertEquals(listOf(MockDataProvider.uniqueRegionName), message.regionScopeMatches)
        },
        case("ambiguous flood dual fields round trip") {
            val store = seededStore()

            val dm = assertNotNull(store.fetchMessage(MockDataProvider.frankFloodAmbiguousMessageID))
            assertEquals(RouteType.TC_FLOOD, dm.routeType)
            assertNull(dm.regionScope)
            assertEquals(MockDataProvider.ambiguousRegionNames, dm.regionScopeMatches)

            val channel = assertNotNull(store.fetchMessage(MockDataProvider.publicAmbiguousRegionMessageID))
            assertEquals(RouteType.TC_FLOOD, channel.routeType)
            assertNull(channel.regionScope)
            assertEquals(MockDataProvider.ambiguousRegionNames, channel.regionScopeMatches)
            assertEquals(Bytes.of(0x10, 0x20), channel.pathNodes)
        },
        case("rx log region rows land") {
            val store = seededStore()
            val entries = blocking { store.fetchRxLogEntries(radioID) }
            val unique = assertNotNull(entries.firstOrNull { it.id == MockDataProvider.uniqueRxLogEntryID })
            assertEquals(MockDataProvider.uniqueRegionName, unique.regionScope)
            assertEquals(listOf(MockDataProvider.uniqueRegionName), unique.regionScopeMatches)
            assertEquals(false, unique.transportCode?.isEmpty)

            val ambiguous = assertNotNull(entries.firstOrNull { it.id == MockDataProvider.ambiguousRxLogEntryID })
            assertNull(ambiguous.regionScope)
            assertEquals(MockDataProvider.ambiguousRegionNames, ambiguous.regionScopeMatches)
        },
        case("node status snapshots seed a GPS track for the location-history node") {
            val store = seededStore()
            val snapshots = store.fetchNodeStatusSnapshots(MockDataProvider.locationHistoryNodePublicKey, since = null)
            assertEquals(MockDataProvider.nodeStatusSnapshots(SimulatorTestSupport.oracleNow).size, snapshots.size)
            assertTrue(snapshots.any { it.latitude != null && it.longitude != null })
        },
    )

    @TestFactory
    fun reseedTests(): List<DynamicTest> = listOf(
        case("reseeding preserves a user-set avatarImageData") {
            val clock = SettableClock(SimulatorTestSupport.oracleNow)
            val store = SimulatorInMemorySeedStore()
            val mode = SimulatorTestSupport.mode(clock)
            blocking { mode.seedDataStore(store) }

            val jpeg = Bytes(ByteArray(16) { 0xCD.toByte() })
            val aliceKey = EntityKey(radioID, MockDataProvider.aliceChenID)
            val existing = assertNotNull(blocking { store.fetchContact(aliceKey) })
            blocking { store.saveContact(existing.withAvatar(jpeg)) }
            assertEquals(jpeg, blocking { store.fetchContact(aliceKey) }?.avatarImageData)

            clock.advance(90)
            blocking { mode.seedDataStore(store) }

            assertEquals(jpeg, blocking { store.fetchContact(aliceKey) }?.avatarImageData)
        },
        case("reseeding is idempotent") {
            val clock = SettableClock(SimulatorTestSupport.oracleNow)
            val store = SimulatorInMemorySeedStore()
            val mode = SimulatorTestSupport.mode(clock)
            blocking { mode.seedDataStore(store) }
            clock.advance(90)
            blocking { mode.seedDataStore(store) }

            // Unique-id upsert means a second pass does not duplicate rows.
            assertEquals(5, store.fetchChannels(radioID).size)
            assertEquals(3, store.fetchMessageRepeats(MockDataProvider.frankRepeatMessageID).size)
            assertEquals(INCOMING_EXTRA_PATH_COUNT, store.fetchMessageRepeats(MockDataProvider.aliceMultiPathMessageID).size)
            assertEquals(3, store.fetchReactions(MockDataProvider.aliceReactedMessageID).size)
            assertEquals(2, blocking { store.fetchRxLogEntries(radioID) }.size)
        },
    )

    private fun case(name: String, body: () -> Unit): DynamicTest = SimulatorTestSupport.caseNamed("SimulatorSeedTests", name, body)
}
