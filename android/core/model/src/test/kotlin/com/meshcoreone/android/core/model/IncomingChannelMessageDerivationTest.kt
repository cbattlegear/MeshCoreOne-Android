// PortedFrom: MC1Services/Tests/MC1ServicesTests/SyncCoordinatorMessageHandlerTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/SyncCoordinatorTimestampTests.swift@db14559b39d32322b06477c6ae676112f583db50
// AndroidOnly: WP-201 Shared derivation controls, including existing WP-214 Unicode/clock adaptations.
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.event.ChannelMessage
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class IncomingChannelMessageDerivationTest {
    private val receive = Instant.ofEpochSecond(1_800_000_000, 250_000_000)
    private val radio = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

    private fun wire(text: String = "Peer: original", timestamp: Instant = receive, type: UByte = 0u) =
        ChannelMessage(1u, 3u, type, timestamp, text, -2.0)

    private fun dto(wire: ChannelMessage, at: Instant = receive): MessageDTO {
        val fields = IncomingChannelMessageDerivation.derive(wire, at)
        return MessageDTO(
            radioId = radio, channelIndex = wire.channelIndex, text = fields.text, timestamp = fields.timestamp,
            createdAt = at, direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
            textType = fields.textType, pathLength = wire.pathLength, snr = wire.snr,
            senderNodeName = fields.senderNodeName, deduplicationKey = fields.deduplicationKey,
            timestampCorrected = fields.wasCorrected, senderTimestamp = fields.senderTimestamp,
        )
    }

    @Test fun originalParserCasesRetainSenderBodyAndOmittedEmptyParts() {
        data class Case(val input: String, val sender: String?, val text: String)
        val cases = listOf(
            Case("NodeAlpha: Hello world", "NodeAlpha", "Hello world"),
            Case("Node: time is 12:30:00", "Node", "time is 12:30:00"),
            Case("just plain text", null, "just plain text"),
            Case("", null, ""),
            Case(":", null, ":"),
            Case("  NodeName  :  hello there  ", "NodeName", "hello there"),
            Case(": some text", null, ": some text"),
            Case("Node\uD83D\uDD25: hello", "Node\uD83D\uDD25", "hello"),
            Case("\u00D1o\u00F1o: caf\u00E9 time", "\u00D1o\u00F1o", "caf\u00E9 time"),
            Case("NodeName:", null, "NodeName:"),
        )
        for (case in cases) {
            assertEquals(
                ParsedChannelMessageText(case.sender, case.text),
                IncomingChannelMessageDerivation.parse(case.input), case.input,
            )
        }
    }

    @Test fun existingSwiftCharacterAndFoundationWhitespaceVectorsRemainExact() {
        data class Case(val input: String, val sender: String?, val text: String)
        val cases = listOf(
            Case(":\u0301x:y", ":\u0301x", "y"),
            Case("a:\u0301b", null, "a:\u0301b"),
            Case("::b", null, "::b"),
            Case("a::b", "a", ":b"),
            Case("::a:b", "a", "b"),
            Case(" ::b", "", ":b"),
            Case("\u3000N\u00A0:\u2003t\t", "N", "t"),
            Case("N:\n x \n", "N", "\n x \n"),
            Case("N\u200B: x", "N", "x"),
            Case("N\u180E: x", "N\u180E", "x"),
        )
        for (case in cases) {
            assertEquals(
                ParsedChannelMessageText(case.sender, case.text),
                IncomingChannelMessageDerivation.parse(case.input), case.input,
            )
        }
        val decomposed = wire("Cafe\u0301: e\u0301")
        val fields = IncomingChannelMessageDerivation.derive(decomposed, receive)
        assertEquals("Cafe\u0301", fields.senderNodeName)
        assertEquals("e\u0301", fields.text)
    }

    @Test fun correctionPreservesStrictWindowsAndFractionalReceiveTime() {
        val whole = Instant.ofEpochSecond(receive.epochSecond)
        val sixMonths = 6L * 30 * 24 * 60 * 60
        for (offset in listOf(0L, 60L, 300L, -7L * 24 * 60 * 60, -sixMonths)) {
            val original = (whole.epochSecond + offset).toUInt()
            val result = IncomingChannelMessageDerivation.correctTimestamp(original, whole)
            assertEquals(original, result.correctedTimestamp)
            assertFalse(result.wasCorrected)
            assertNull(result.rangeError)
        }
        for (offset in listOf(301L, -sixMonths - 1)) {
            val result = IncomingChannelMessageDerivation.correctTimestamp(
                (whole.epochSecond + offset).toUInt(), receive,
            )
            assertTrue(result.wasCorrected)
            assertEquals(whole.epochSecond.toUInt(), result.correctedTimestamp)
            assertNull(result.rangeError)
        }
        val fractionalBoundary = IncomingChannelMessageDerivation.correctTimestamp(
            (whole.epochSecond - sixMonths).toUInt(), receive,
        )
        assertTrue(fractionalBoundary.wasCorrected)
    }

    @Test fun receiveClockRangeRetainsTheOriginalOnErrorAndTruncatesTowardZero() {
        val maximum = UInt.MAX_VALUE.toLong()
        val boundaries = listOf(
            Instant.ofEpochSecond(-1) to null,
            Instant.EPOCH.minusNanos(1) to 0u,
            Instant.EPOCH to 0u,
            Instant.EPOCH.plusNanos(1) to 0u,
            Instant.ofEpochSecond(maximum) to UInt.MAX_VALUE,
            Instant.ofEpochSecond(maximum, 999_999_999) to UInt.MAX_VALUE,
            Instant.ofEpochSecond(maximum + 1) to null,
            Instant.MIN to null,
            Instant.MAX to null,
        )
        for ((at, expected) in boundaries) {
            val result = IncomingChannelMessageDerivation.correctTimestamp(1_000_000u, at)
            if (expected == null) {
                assertEquals(1_000_000u, result.correctedTimestamp)
                assertFalse(result.wasCorrected)
                assertEquals(at, assertNotNull(result.rangeError).receiveTime)
            } else {
                assertEquals(expected, result.correctedTimestamp)
                assertTrue(result.wasCorrected)
                assertNull(result.rangeError)
            }
        }
    }

    @Test fun wireDateClampRemainsSeparateFromReceiveClockRangeErrors() {
        assertEquals(0u, IncomingChannelMessageDerivation.uint32Seconds(Instant.MIN))
        assertEquals(0u, IncomingChannelMessageDerivation.uint32Seconds(Instant.EPOCH.minusNanos(1)))
        assertEquals(1u, IncomingChannelMessageDerivation.uint32Seconds(Instant.ofEpochSecond(1, 999_999_999)))
        assertEquals(UInt.MAX_VALUE, IncomingChannelMessageDerivation.uint32Seconds(Instant.MAX))
        val maximum = Instant.ofEpochSecond(UInt.MAX_VALUE.toLong(), 999_999_999)
        assertEquals(UInt.MAX_VALUE, IncomingChannelMessageDerivation.uint32Seconds(maximum))
    }

    @Test fun correctedOriginalStampDrivesDedupAndReactionRatherThanCorrectedTimestamp() {
        val wire = wire(timestamp = Instant.ofEpochSecond(1000))
        val fields = IncomingChannelMessageDerivation.derive(wire, receive)
        assertEquals(1000u, fields.originalTimestamp)
        assertEquals(1000u, fields.senderTimestamp)
        assertTrue(fields.wasCorrected)
        assertEquals(receive.epochSecond.toUInt(), fields.timestamp)
        assertEquals(DeduplicationKey.contentBased(null, 1u, "Peer", 1000u, "original"), fields.deduplicationKey)
        val message = dto(wire)
        assertEquals(1000u, message.reactionTimestamp)
        assertTrue(IncomingChannelMessageDerivation.mismatches(wire, message).isEmpty())
    }

    @Test fun unchangedAndRangeErrorPathsNeverInventAnOriginalSenderTimestamp() {
        val normal = IncomingChannelMessageDerivation.derive(wire(), receive)
        assertFalse(normal.wasCorrected)
        assertNull(normal.senderTimestamp)
        assertNull(normal.rangeError)
        val wire = wire(timestamp = Instant.ofEpochSecond(1000))
        val at = Instant.ofEpochSecond(-1)
        val fields = IncomingChannelMessageDerivation.derive(wire, at)
        assertEquals(1000u, fields.timestamp)
        assertFalse(fields.wasCorrected)
        assertNull(fields.senderTimestamp)
        assertEquals(at, assertNotNull(fields.rangeError).receiveTime)
        assertTrue(IncomingChannelMessageDerivation.mismatches(wire, dto(wire, at)).isEmpty())
    }

    @Test fun everyRawTextTypeKeepsExistingKnownMappingOrExplicitPlainClampWarning() {
        for (raw in 0..255) {
            val fields = IncomingChannelMessageDerivation.derive(wire(type = raw.toUByte()), receive)
            val expected = when (raw) {
                0 -> TextType.PLAIN
                1 -> TextType.CLI_DATA
                2 -> TextType.SIGNED_PLAIN
                else -> TextType.PLAIN
            }
            assertEquals(expected, fields.textType)
            assertEquals(if (raw <= 2) null else raw.toUByte(), fields.unknownTextTypeRaw)
        }
    }

    @Test fun mismatchedFieldsAreComputedFromTheWireRatherThanCallerAssertions() {
        val wire = wire()
        val message = dto(wire)
        val cases = listOf(
            message.copy(text = "upsert successor") to IncomingChannelMessageField.TEXT,
            message.copy(senderNodeName = "Other") to IncomingChannelMessageField.SENDER_NODE_NAME,
            message.copy(timestamp = message.timestamp + 1u) to IncomingChannelMessageField.TIMESTAMP,
            message.copy(senderTimestamp = message.timestamp) to IncomingChannelMessageField.SENDER_TIMESTAMP,
            message.copy(timestampCorrected = true) to IncomingChannelMessageField.TIMESTAMP_CORRECTED,
            message.copy(textType = TextType.CLI_DATA) to IncomingChannelMessageField.TEXT_TYPE,
            message.copy(deduplicationKey = "caller asserted") to IncomingChannelMessageField.DEDUPLICATION_KEY,
        )
        assertTrue(IncomingChannelMessageDerivation.mismatches(wire, message).isEmpty())
        for ((changed, field) in cases) {
            assertEquals(listOf(field), IncomingChannelMessageDerivation.mismatches(wire, changed).toList())
        }
        val changed = message.copy(text = "wrong", senderNodeName = null, timestamp = 0u, deduplicationKey = null)
        assertEquals(
            listOf(
                IncomingChannelMessageField.TEXT, IncomingChannelMessageField.SENDER_NODE_NAME,
                IncomingChannelMessageField.TIMESTAMP, IncomingChannelMessageField.DEDUPLICATION_KEY,
            ),
            IncomingChannelMessageDerivation.mismatches(wire, changed).toList(),
        )
        assertEquals(
            listOf(IncomingChannelMessageField.SENDER_NODE_NAME, IncomingChannelMessageField.DEDUPLICATION_KEY),
            IncomingChannelMessageDerivation.mismatches(wire.copy(text = "Other: original"), message).toList(),
        )
    }
}
