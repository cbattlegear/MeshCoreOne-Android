// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ReactionServiceTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.services.rendering.SwiftText
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Swift's "most recently indexed" case sleeps 10 ms between indexes; here a [ReactionsTickingClock] advances
 * on every read instead, so the ordering is deterministic without a fixed sleep.
 */
class ReactionServiceTest {
    private val timestamp: UInt = 1_704_067_200u

    private fun service() = ReactionService(ReactionsTickingClock())

    @TestFactory
    fun wireFormatTests(): List<DynamicTest> = listOf(
        case("Builds correct wire format with Crockford Base32 identifier") {
            val text = service().buildReactionText("👍", "AlphaNode", "What's the situation at Main St today?", timestamp)
            assertTrue(text.startsWith("@[AlphaNode]👍\n"))
            // Verify the 8-char Crockford Base32 identifier is present (lowercase) at the end.
            assertNotNull(Regex("\n([0-9a-hj-km-np-tv-z]{8})$").find(text), text)
            assertEquals("@[AlphaNode]👍\n3pe3nahw", text) // Swift-runtime value
        },
        case("Builds wire format with short message") {
            val text = service().buildReactionText("❤\uFE0F", "Node", "ok", timestamp)
            assertTrue(text.startsWith("@[Node]❤\uFE0F\n"))
            assertTrue(text.endsWith(text.takeLast(8))) // ends with 8-char hash
            assertEquals(ReactionParser.generateMessageHash("ok", timestamp), text.takeLast(8))
        },
        case("Generated identifier is consistent") {
            val service = service()
            assertEquals(
                service.buildReactionText("👍", "Node", "Hello world", timestamp),
                service.buildReactionText("👍", "Node", "Hello world", timestamp),
            )
        },
        case("Different timestamps produce different identifiers") {
            val service = service()
            assertNotEquals(
                service.buildReactionText("👍", "Node", "Hello world", 1_704_067_200u),
                service.buildReactionText("👍", "Node", "Hello world", 1_704_067_201u),
            )
        },
    )

    @TestFactory
    fun disambiguationTests(): List<DynamicTest> = listOf(
        case("Finds indexed message by hash and preview") {
            val service = service()
            val messageID = UUID.randomUUID()
            service.indexMessage(messageID, 0u, "Node", "Hello world", timestamp)
            val parsed = assertNotNull(ReactionParser.parse(service.buildReactionText("👍", "Node", "Hello world", timestamp)))
            assertEquals(messageID, service.findTargetMessage(parsed, 0u))
        },
        case("Returns nil when no candidates exist") {
            assertNull(service().findTargetMessage(ParsedReaction("👍", "Node", "abcd1234"), 0u))
        },
        case("Returns most recently indexed when multiple candidates have same hash") {
            val service = service()
            val id1 = UUID.randomUUID()
            val id2 = UUID.randomUUID()
            // Index two messages with the same hash (same text and timestamp); the clock ticks between them.
            service.indexMessage(id1, 0u, "Node", "Same message", timestamp)
            service.indexMessage(id2, 0u, "Node", "Same message", timestamp)
            val parsed = assertNotNull(ReactionParser.parse(service.buildReactionText("👍", "Node", "Same message", timestamp)))
            // Should find the most recently indexed (id2).
            assertEquals(id2, service.findTargetMessage(parsed, 0u))
        },
    )

    @TestFactory
    fun pendingQueueTests(): List<DynamicTest> = listOf(
        case("Queued reaction matches when message indexed") {
            val service = service()
            val reactionText = service.buildReactionText("👍", "AlphaNode", "Hello world", timestamp)
            val parsed = assertNotNull(ReactionParser.parse(reactionText))
            // Queue the reaction (target message not indexed yet).
            service.queuePendingReaction(parsed, 0u, "BetaNode", reactionText, ReactionsFixtures.newRadioId())
            // Now index the target message - should return the pending reaction.
            val matches = service.indexMessage(UUID.randomUUID(), 0u, "AlphaNode", "Hello world", timestamp)
            assertEquals(1, matches.size)
            assertEquals("👍", matches.first().parsed.emoji)
            assertEquals("BetaNode", matches.first().senderNodeName)
        },
        case("Multiple reactions for same target all match") {
            val service = service()
            val radioId = ReactionsFixtures.newRadioId()
            for (emoji in listOf("👍", "❤\uFE0F", "😂")) {
                val reactionText = service.buildReactionText(emoji, "AlphaNode", "Hello world", timestamp)
                service.queuePendingReaction(assertNotNull(ReactionParser.parse(reactionText)), 0u, "BetaNode", reactionText, radioId)
            }
            val matches = service.indexMessage(UUID.randomUUID(), 0u, "AlphaNode", "Hello world", timestamp)
            assertEquals(3, matches.size)
            assertEquals(setOf("👍", "❤\uFE0F", "😂"), matches.map { it.parsed.emoji }.toSet())
        },
        case("Hash mismatch prevents false match") {
            val service = service()
            val reactionText = service.buildReactionText("👍", "AlphaNode", "Hello world", timestamp)
            service.queuePendingReaction(assertNotNull(ReactionParser.parse(reactionText)), 0u, "BetaNode", reactionText, ReactionsFixtures.newRadioId())
            // Index a different message (different hash): should NOT match.
            assertTrue(service.indexMessage(UUID.randomUUID(), 0u, "AlphaNode", "Different text", timestamp).isEmpty())
        },
        case("Clear removes all pending reactions") {
            val service = service()
            val reactionText = service.buildReactionText("👍", "AlphaNode", "Hello world", timestamp)
            service.queuePendingReaction(assertNotNull(ReactionParser.parse(reactionText)), 0u, "BetaNode", reactionText, ReactionsFixtures.newRadioId())
            service.clearPendingReactions()
            assertTrue(service.indexMessage(UUID.randomUUID(), 0u, "AlphaNode", "Hello world", timestamp).isEmpty())
        },
        case("Pending reactions are scoped by channel") {
            val service = service()
            val messageID = UUID.randomUUID()
            val reactionText = service.buildReactionText("👍", "AlphaNode", "Hello world", timestamp)
            service.queuePendingReaction(assertNotNull(ReactionParser.parse(reactionText)), 0u, "BetaNode", reactionText, ReactionsFixtures.newRadioId())
            // Index on channel 1 - should NOT match.
            assertTrue(service.indexMessage(messageID, 1u, "AlphaNode", "Hello world", timestamp).isEmpty())
            // Index on channel 0 - should match.
            assertEquals(1, service.indexMessage(messageID, 0u, "AlphaNode", "Hello world", timestamp).size)
        },
    )

    @TestFactory
    fun dmReactionTests(): List<DynamicTest> = listOf(
        case("Builds DM wire format") {
            val text = service().buildDMReactionText("👍", "Hello world", timestamp)
            assertTrue(text.startsWith("👍\n"))
            assertEquals(10, SwiftText.graphemeCount(text)) // emoji + newline + 8 char hash
            assertFalse(text.contains("@["))
        },
        case("Indexes DM message and finds by hash") {
            val service = service()
            val messageID = UUID.randomUUID()
            val contactID = UUID.randomUUID()
            service.indexDMMessage(messageID, contactID, "Hello world", timestamp)
            val hash = ReactionParser.generateMessageHash("Hello world", timestamp)
            assertEquals(messageID, service.findDMTargetMessage(hash, contactID))
        },
        case("DM pending reactions match when message indexed") {
            val service = service()
            val contactID = UUID.randomUUID()
            val reactionText = service.buildDMReactionText("👍", "Hello world", timestamp)
            val parsed = assertNotNull(ReactionParser.parseDM(reactionText))
            service.queuePendingDMReaction(parsed, contactID, "Alice", reactionText, ReactionsFixtures.newRadioId())
            val matches = service.indexDMMessage(UUID.randomUUID(), contactID, "Hello world", timestamp)
            assertEquals(1, matches.size)
            assertEquals("👍", matches.first().parsed.emoji)
        },
        case("DM reactions scoped by contact") {
            val service = service()
            val messageID = UUID.randomUUID()
            val contactID1 = UUID.randomUUID()
            val contactID2 = UUID.randomUUID()
            val reactionText = service.buildDMReactionText("👍", "Hello world", timestamp)
            val parsed = assertNotNull(ReactionParser.parseDM(reactionText))
            // Queue for contact1.
            service.queuePendingDMReaction(parsed, contactID1, "Alice", reactionText, ReactionsFixtures.newRadioId())
            // Index for contact2 - should NOT match.
            assertTrue(service.indexDMMessage(messageID, contactID2, "Hello world", timestamp).isEmpty())
            // Index for contact1 - should match.
            assertEquals(1, service.indexDMMessage(messageID, contactID1, "Hello world", timestamp).size)
        },
        case("DM returns nil when no candidates in cache") {
            assertNull(service().findDMTargetMessage("abcd1234", UUID.randomUUID()))
        },
        case("DM hash mismatch prevents false match") {
            val service = service()
            val contactID = UUID.randomUUID()
            val reactionText = service.buildDMReactionText("👍", "Hello world", timestamp)
            service.queuePendingDMReaction(assertNotNull(ReactionParser.parseDM(reactionText)), contactID, "Alice", reactionText, ReactionsFixtures.newRadioId())
            // Index a different message (different hash): should NOT match.
            assertTrue(service.indexDMMessage(UUID.randomUUID(), contactID, "Different text", timestamp).isEmpty())
        },
        case("Clear removes DM pending reactions") {
            val service = service()
            val contactID = UUID.randomUUID()
            val reactionText = service.buildDMReactionText("👍", "Hello world", timestamp)
            service.queuePendingDMReaction(assertNotNull(ReactionParser.parseDM(reactionText)), contactID, "Alice", reactionText, ReactionsFixtures.newRadioId())
            service.clearPendingReactions()
            assertTrue(service.indexDMMessage(UUID.randomUUID(), contactID, "Hello world", timestamp).isEmpty())
        },
    )

    private fun case(name: String, body: suspend () -> Unit): DynamicTest =
        DynamicTest.dynamicTest("ReactionServiceTests::$name()") { ReactionsFixtures.blocking { body() } }
}
