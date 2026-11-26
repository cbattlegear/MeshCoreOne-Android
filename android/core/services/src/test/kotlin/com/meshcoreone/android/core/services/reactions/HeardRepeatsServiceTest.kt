// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/HeardRepeatsServiceTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.reactions.ReactionsFixtures.TEST_NODE_NAME
import com.meshcoreone.android.core.services.reactions.ReactionsFixtures.blocking
import com.meshcoreone.android.core.services.reactions.ReactionsFixtures.bytes
import com.meshcoreone.android.core.services.reactions.ReactionsFixtures.incomingChannelMessage
import com.meshcoreone.android.core.services.reactions.ReactionsFixtures.makeEcho
import com.meshcoreone.android.core.services.reactions.ReactionsFixtures.newRadioId
import com.meshcoreone.android.core.services.reactions.ReactionsFixtures.nowSeconds
import com.meshcoreone.android.core.services.reactions.ReactionsFixtures.testChannelMessage
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * The parse cases run against the package-local WP-208 stand-in [ChannelMessageFormat]. Swift backs the
 * service cases with an in-memory SwiftData `PersistenceStore`; core:services cannot depend on core:data, so
 * they run on [ReactionsInMemoryHeardRepeatStore], which reproduces the store queries the service relies on
 * (including the sent-message lookup the "exact text disambiguates" case exercises).
 */
class HeardRepeatsServiceTest {
    @TestFactory
    fun channelMessageFormatParseTests(): List<DynamicTest> = listOf(
        case("parse with valid format returns sender and message") {
            val result = assertNotNull(ChannelMessageFormat.parse("NodeName: Hello world"))
            assertEquals("NodeName", result.senderName)
            assertEquals("Hello world", result.messageText)
        },
        case("parse with no colon returns nil") { assertNull(ChannelMessageFormat.parse("No colon here")) },
        case("parse with colon at start returns nil") { assertNull(ChannelMessageFormat.parse(": Message without sender")) },
        case("parse with empty message returns empty text") {
            assertEquals(ChannelMessageFormat.Parsed("Sender", ""), ChannelMessageFormat.parse("Sender:"))
        },
        case("parse with message containing colons only splits on first") {
            assertEquals(ChannelMessageFormat.Parsed("Sender", "Time is 10:30:00"), ChannelMessageFormat.parse("Sender: Time is 10:30:00"))
        },
        case("parse trims whitespace from message") {
            assertEquals("Padded message", ChannelMessageFormat.parse("Node:   Padded message   ")?.messageText)
        },
        case("parse preserves spaces in sender name") {
            assertEquals("Node With Spaces", ChannelMessageFormat.parse("Node With Spaces: Message")?.senderName)
        },
        case("parse trims leading and trailing whitespace from sender") {
            val result = ChannelMessageFormat.parse("Alice : hello")
            assertEquals("Alice", result?.senderName)
            assertEquals("hello", result?.messageText)
        },
    )

    private fun makeStoreAndService(): Pair<ReactionsInMemoryHeardRepeatStore, HeardRepeatsService> {
        val store = ReactionsInMemoryHeardRepeatStore()
        return store to HeardRepeatsService(store)
    }

    @TestFactory
    fun processForRepeatsMatchingTests(): List<DynamicTest> = listOf(
        case("counts a repeat whose send is far outside the old 10s window") {
            blocking {
                val (store, service) = makeStoreAndService()
                val radioId = newRadioId()
                val channelIndex: UByte = 2u
                // Sent two minutes ago: beyond the removed 10-second wall-clock gate.
                val sendTimestamp = nowSeconds() - 120u
                val messageID = UUID.randomUUID()
                store.saveMessage(testChannelMessage(id = messageID, radioId = radioId, channelIndex = channelIndex, text = "north repeater check", timestamp = sendTimestamp))
                service.configure(radioId)

                val events = service.events()
                val echo = makeEcho(radioId, channelIndex, sendTimestamp, "north repeater check")
                assertEquals(1L, service.processForRepeats(echo))
                val repeats = store.fetchMessageRepeats(EntityKey(radioId, messageID))
                assertEquals(1, repeats.size)
                assertEquals(echo.id, repeats.first().rxLogEntryID)

                val event = events.first()
                assertEquals(messageID, event.messageID)
                assertEquals(1L, event.count)
            }
        },
        case("same RX log entry is counted once") {
            blocking {
                val (store, service) = makeStoreAndService()
                val radioId = newRadioId()
                val sendTimestamp = nowSeconds()
                val messageID = UUID.randomUUID()
                store.saveMessage(testChannelMessage(id = messageID, radioId = radioId, channelIndex = 0u, text = "hello", timestamp = sendTimestamp))
                service.configure(radioId)

                val echo = makeEcho(radioId, 0u, sendTimestamp, "hello")
                assertEquals(1L, service.processForRepeats(echo))
                assertNull(service.processForRepeats(echo))
                assertEquals(1, store.fetchMessageRepeats(EntityKey(radioId, messageID)).size)
            }
        },
        case("no match for unknown timestamp") {
            blocking {
                val (store, service) = makeStoreAndService()
                val radioId = newRadioId()
                val sendTimestamp = nowSeconds()
                store.saveMessage(testChannelMessage(radioId = radioId, channelIndex = 1u, text = "hello", timestamp = sendTimestamp))
                service.configure(radioId)
                assertNull(service.processForRepeats(makeEcho(radioId, 1u, sendTimestamp + 5u, "hello")))
            }
        },
        case("exact text disambiguates messages sharing channel and timestamp") {
            blocking {
                val store = ReactionsInMemoryHeardRepeatStore()
                val radioId = newRadioId()
                val channelIndex: UByte = 3u
                val timestamp = nowSeconds()
                val aID = UUID.randomUUID()
                val bID = UUID.randomUUID()
                store.saveMessage(testChannelMessage(id = aID, radioId = radioId, channelIndex = channelIndex, text = "message A", timestamp = timestamp))
                store.saveMessage(testChannelMessage(id = bID, radioId = radioId, channelIndex = channelIndex, text = "message B", timestamp = timestamp))
                assertEquals(bID, store.findSentChannelMessage(radioId, channelIndex, timestamp, "message B")?.id)
                assertEquals(aID, store.findSentChannelMessage(radioId, channelIndex, timestamp, "message A")?.id)
            }
        },
        case("new-node rename then three TEST Hello echoes attach") {
            // Air prefix is not a join key. Body, timestamp, and channel still match.
            blocking {
                val (store, service) = makeStoreAndService()
                val radioId = newRadioId()
                val sendTimestamp = nowSeconds()
                val messageID = UUID.randomUUID()
                store.saveMessage(testChannelMessage(id = messageID, radioId = radioId, channelIndex = 0u, text = "Hello", timestamp = sendTimestamp))
                service.configure(radioId)

                var lastCount: Long? = null
                repeat(3) {
                    lastCount = service.processForRepeats(makeEcho(radioId, 0u, sendTimestamp, "Hello", senderName = "TEST"))
                }
                assertEquals(3L, lastCount)
                assertEquals(3, store.fetchMessageRepeats(EntityKey(radioId, messageID)).size)
            }
        },
    )

    @TestFactory
    fun incomingExtraPathTests(): List<DynamicTest> = listOf(
        case("incoming channel RX with a different path inserts a repeat") {
            blocking {
                val (store, service) = makeStoreAndService()
                val radioId = newRadioId()
                val wireTimestamp = 1_704_067_200u
                val message = incomingChannelMessage(
                    radioId = radioId, channelIndex = 0u, text = "flood copy", senderName = TEST_NODE_NAME,
                    wireTimestamp = wireTimestamp, pathNodes = bytes(0xAA), pathLength = 1u,
                )
                store.saveMessage(message)
                service.configure(radioId)

                val extra = makeEcho(radioId, 0u, wireTimestamp, "flood copy", pathNodes = listOf(0xBB))
                assertEquals(1L, service.processForRepeats(extra))
                val key = EntityKey(radioId, message.id)
                val repeats = store.fetchMessageRepeats(key)
                assertEquals(1, repeats.size)
                assertEquals(bytes(0xBB), repeats.first().pathNodes)
                assertEquals(1L, store.fetchMessage(key)?.heardRepeats)
            }
        },
        case("incoming RX whose path equals message pathNodes inserts nothing") {
            blocking {
                val (store, service) = makeStoreAndService()
                val radioId = newRadioId()
                val wireTimestamp = 1_704_067_201u
                val message = incomingChannelMessage(
                    radioId = radioId, channelIndex = 0u, text = "same path", senderName = TEST_NODE_NAME,
                    wireTimestamp = wireTimestamp, pathNodes = bytes(0xAA), pathLength = 1u,
                )
                store.saveMessage(message)
                service.configure(radioId)

                assertNull(service.processForRepeats(makeEcho(radioId, 0u, wireTimestamp, "same path", pathNodes = listOf(0xAA))))
                val key = EntityKey(radioId, message.id)
                assertTrue(store.fetchMessageRepeats(key).isEmpty())
                assertEquals(0L, store.fetchMessage(key)?.heardRepeats)
            }
        },
        case("second incoming RX with the same extra path inserts nothing") {
            blocking {
                val (store, service) = makeStoreAndService()
                val radioId = newRadioId()
                val wireTimestamp = 1_704_067_202u
                val message = incomingChannelMessage(
                    radioId = radioId, channelIndex = 0u, text = "collapse", senderName = TEST_NODE_NAME,
                    wireTimestamp = wireTimestamp, pathNodes = bytes(0xAA), pathLength = 1u,
                )
                store.saveMessage(message)
                service.configure(radioId)

                assertEquals(1L, service.processForRepeats(makeEcho(radioId, 0u, wireTimestamp, "collapse", pathNodes = listOf(0xBB))))
                assertNull(service.processForRepeats(makeEcho(radioId, 0u, wireTimestamp, "collapse", pathNodes = listOf(0xBB))))
                assertEquals(1, store.fetchMessageRepeats(EntityKey(radioId, message.id)).size)
            }
        },
        case("clock-corrected incoming message still joins extra RX by wire timestamp") {
            blocking {
                val (store, service) = makeStoreAndService()
                val radioId = newRadioId()
                val wireTimestamp = 100u
                val message = incomingChannelMessage(
                    radioId = radioId, channelIndex = 1u, text = "skewed clock", senderName = TEST_NODE_NAME,
                    wireTimestamp = wireTimestamp, pathNodes = bytes(0xAA), pathLength = 1u,
                    timestampCorrected = true, receiveTime = Instant.ofEpochSecond(1_704_067_200),
                )
                store.saveMessage(message)
                service.configure(radioId)

                assertEquals(1L, service.processForRepeats(makeEcho(radioId, 1u, wireTimestamp, "skewed clock", pathNodes = listOf(0xCC))))
                assertEquals(bytes(0xCC), store.fetchMessageRepeats(EntityKey(radioId, message.id)).firstOrNull()?.pathNodes)
            }
        },
        case("empty path extra is recorded as a 0-hop arrival") {
            blocking {
                val (store, service) = makeStoreAndService()
                val radioId = newRadioId()
                val wireTimestamp = 1_704_067_203u
                val message = incomingChannelMessage(
                    radioId = radioId, channelIndex = 0u, text = "zero hop extra", senderName = TEST_NODE_NAME,
                    wireTimestamp = wireTimestamp, pathNodes = bytes(0xAA), pathLength = 1u,
                )
                store.saveMessage(message)
                service.configure(radioId)

                val extra = makeEcho(radioId, 0u, wireTimestamp, "zero hop extra", pathNodes = emptyList(), pathLength = 0u)
                assertEquals(1L, service.processForRepeats(extra))
                val repeat = assertNotNull(store.fetchMessageRepeats(EntityKey(radioId, message.id)).firstOrNull())
                assertEquals(Bytes(ByteArray(0)), repeat.pathNodes)
                assertEquals(0u.toUByte(), repeat.pathLength)
            }
        },
        case("harvest after save records extras from RX rows that arrived first") {
            blocking {
                val (store, service) = makeStoreAndService()
                val radioId = newRadioId()
                val wireTimestamp = 1_704_067_204u
                val earlier = Instant.ofEpochSecond(1_700_000_000)
                val later = earlier.plusSeconds(1)
                val rxA = makeEcho(radioId, 0u, wireTimestamp, "harvest me", pathNodes = listOf(0xA1), receivedAt = earlier)
                val rxB = makeEcho(radioId, 0u, wireTimestamp, "harvest me", pathNodes = listOf(0xB2), receivedAt = later)
                service.configure(radioId)

                assertNull(service.processForRepeats(rxA))
                assertNull(service.processForRepeats(rxB))

                val message = incomingChannelMessage(
                    radioId = radioId, channelIndex = 0u, text = "harvest me", senderName = TEST_NODE_NAME,
                    wireTimestamp = wireTimestamp, pathNodes = bytes(0xB2), pathLength = 1u, receiveTime = later,
                )
                store.saveMessage(message)
                service.harvestIncomingPaths(message, listOf(rxA, rxB))

                val key = EntityKey(radioId, message.id)
                assertEquals(1L, store.fetchMessage(key)?.heardRepeats)
                val repeats = store.fetchMessageRepeats(key)
                assertEquals(1, repeats.size)
                assertEquals(bytes(0xA1), repeats.first().pathNodes)
            }
        },
        case("harvest ignores a same-stamp 0x88 whose body does not match") {
            blocking {
                val (store, service) = makeStoreAndService()
                val radioId = newRadioId()
                val stamp = 42u
                val aliceRX = makeEcho(radioId, 0u, stamp, "one", senderName = "Alice", pathNodes = listOf(0xA1))
                val bobRX = makeEcho(radioId, 0u, stamp, "two", senderName = "Bob", pathNodes = listOf(0xB2))
                val message = incomingChannelMessage(
                    radioId = radioId, channelIndex = 0u, text = "one", senderName = "Alice",
                    wireTimestamp = stamp, pathNodes = bytes(0xC0), pathLength = 1u,
                )
                store.saveMessage(message)
                service.harvestIncomingPaths(message, listOf(aliceRX, bobRX))

                val key = EntityKey(radioId, message.id)
                assertEquals(1L, store.fetchMessage(key)?.heardRepeats)
                val repeats = store.fetchMessageRepeats(key)
                assertEquals(1, repeats.size)
                assertEquals(bytes(0xA1), repeats.first().pathNodes)
                assertEquals(aliceRX.id, repeats.first().rxLogEntryID)
                assertFalse(store.messageRepeatExists(EntityKey(radioId, bobRX.id)))
            }
        },
        case("harvest adopts a matching path onto an unknown incoming message") {
            blocking {
                val (store, service) = makeStoreAndService()
                val radioId = newRadioId()
                val wireTimestamp = 1_704_067_205u
                val message = incomingChannelMessage(
                    radioId = radioId, channelIndex = 0u, text = "adopt me", senderName = TEST_NODE_NAME,
                    wireTimestamp = wireTimestamp, pathNodes = null, pathLength = 0u,
                )
                store.saveMessage(message)
                service.harvestIncomingPaths(message, listOf(makeEcho(radioId, 0u, wireTimestamp, "adopt me", pathNodes = listOf(0xAA))))

                val key = EntityKey(radioId, message.id)
                val updated = assertNotNull(store.fetchMessage(key))
                assertEquals(bytes(0xAA), updated.pathNodes)
                assertEquals(1u.toUByte(), updated.pathLength)
                assertEquals(0L, updated.heardRepeats)
                assertTrue(store.fetchMessageRepeats(key).isEmpty())
            }
        },
        case("harvest adopts the first matching path and records the later extra") {
            blocking {
                val (store, service) = makeStoreAndService()
                val radioId = newRadioId()
                val wireTimestamp = 1_704_067_206u
                val earlier = Instant.ofEpochSecond(1_700_000_000)
                val later = earlier.plusSeconds(1)
                val message = incomingChannelMessage(
                    radioId = radioId, channelIndex = 0u, text = "two paths", senderName = TEST_NODE_NAME,
                    wireTimestamp = wireTimestamp, pathNodes = null, pathLength = 0u,
                )
                store.saveMessage(message)
                val first = makeEcho(radioId, 0u, wireTimestamp, "two paths", pathNodes = listOf(0xAA), receivedAt = earlier)
                val second = makeEcho(radioId, 0u, wireTimestamp, "two paths", pathNodes = listOf(0xBB), receivedAt = later)
                service.harvestIncomingPaths(message, listOf(second, first))

                val key = EntityKey(radioId, message.id)
                val updated = assertNotNull(store.fetchMessage(key))
                assertEquals(bytes(0xAA), updated.pathNodes)
                assertEquals(1u.toUByte(), updated.pathLength)
                assertEquals(1L, updated.heardRepeats)
                val repeats = store.fetchMessageRepeats(key)
                assertEquals(1, repeats.size)
                assertEquals(bytes(0xBB), repeats.first().pathNodes)
            }
        },
    )

    private fun case(name: String, body: () -> Unit): DynamicTest = DynamicTest.dynamicTest("HeardRepeatsServiceTests::$name()", body)
}
