// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/MeshCoreOpenReactionParserTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.reactions

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class MeshCoreOpenReactionParserTest {
    private val parser = MeshCoreOpenReactionParser

    @TestFactory
    fun parseValidFormatTests(): List<DynamicTest> = listOf(
        case("Parses valid reaction with thumbs up (index 00)") {
            assertEquals(ParsedMCOReaction("👍", "a1b2"), parser.parse("r:a1b2:00"))
        },
        case("Parses valid reaction with fire (index 05)") {
            assertEquals(ParsedMCOReaction("🔥", "ff00"), parser.parse("r:ff00:05"))
        },
        case("Parses valid reaction with heart (index 01)") {
            assertEquals(ParsedMCOReaction("❤\uFE0F", "1234"), parser.parse("r:1234:01"))
        },
        case("Parses reaction at max valid emoji index (0xb7)") {
            assertEquals(ParsedMCOReaction("🚀", "abcd"), parser.parse("r:abcd:b7"))
        },
    )

    @TestFactory
    fun parseInvalidFormatTests(): List<DynamicTest> = listOf(
        case("Rejects plain text") { assertNull(parser.parse("hello world")) },
        case("Rejects wrong prefix") { assertNull(parser.parse("x:a1b2:00")) },
        case("Rejects uppercase hex in hash") { assertNull(parser.parse("r:A1B2:00")) },
        case("Rejects uppercase hex in index") { assertNull(parser.parse("r:a1b2:0A")) },
        case("Rejects too short") { assertNull(parser.parse("r:a1b:00")) },
        case("Rejects too long") { assertNull(parser.parse("r:a1b2c:00")) },
        case("Rejects missing colons") { assertNull(parser.parse("r-a1b2-00")) },
        case("Rejects emoji index beyond table size") {
            // 0xb8 = 184, table has 184 entries (0x00–0xb7).
            assertNull(parser.parse("r:a1b2:b8"))
        },
        case("Rejects channel reaction format") { assertNull(parser.parse("@[AlphaNode]👍\n7f3a9c12")) },
        case("Rejects legacy DM reaction format") { assertNull(parser.parse("👍\n7f3a9c12")) },
        case("Rejects empty string") { assertNull(parser.parse("")) },
    )

    @TestFactory
    fun emojiIndexMappingTests(): List<DynamicTest> = listOf(
        case("Spot-check emoji indices across all categories") {
            // quickEmojis
            assertEquals("👍", parser.parse("r:0000:00")?.emoji)
            assertEquals("😂", parser.parse("r:0000:02")?.emoji)
            assertEquals("🎉", parser.parse("r:0000:03")?.emoji)
            // smileys start at 0x06
            assertEquals("😀", parser.parse("r:0000:06")?.emoji)
            assertEquals("😶", parser.parse("r:0000:45")?.emoji)
            // gestures start at 0x46
            assertEquals("👍", parser.parse("r:0000:46")?.emoji)
            assertEquals("💪", parser.parse("r:0000:66")?.emoji)
            // hearts start at 0x67
            assertEquals("❤\uFE0F", parser.parse("r:0000:67")?.emoji)
            // objects start at 0x87
            assertEquals("🎉", parser.parse("r:0000:87")?.emoji)
        },
    )

    @TestFactory
    fun dartStringHashTests(): List<DynamicTest> = listOf(
        case("Dart hash of empty input produces 1") {
            // Dart: "".hashCode should be 0, which becomes 1 (zero-guard).
            assertEquals(1u, parser.dartStringHash(emptyList()))
        },
        case("Dart hash is deterministic") {
            val units = "hello".map { it.code.toUShort() }
            assertEquals(parser.dartStringHash(units), parser.dartStringHash(units))
        },
        case("Dart hash of single character 'a'") {
            val hash = parser.dartStringHash(listOf(97.toUShort()))
            assertTrue(hash > 0u)
            assertTrue(hash < (1u shl 30))
            assertEquals(170_824_770u, hash) // Swift-runtime value
        },
        case("Dart hash result is within 30-bit range") {
            val hash = parser.dartStringHash("test string with various chars 🎉")
            assertTrue(hash > 0u)
            assertTrue(hash <= (1u shl 30) - 1u)
        },
        case("Different inputs produce different hashes") {
            assertNotEquals(parser.dartStringHash("hello"), parser.dartStringHash("world"))
        },
    )

    @TestFactory
    fun hashComputationTests(): List<DynamicTest> = listOf(
        case("computeReactionHash returns 4-char lowercase hex") {
            val hash = parser.computeReactionHash(1_700_000_000u, "AlphaNode", "Hello world")
            assertEquals(4, hash.length)
            assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' }, hash)
        },
        case("computeReactionHash is deterministic") {
            assertEquals(
                parser.computeReactionHash(1_700_000_000u, "AlphaNode", "Hello world"),
                parser.computeReactionHash(1_700_000_000u, "AlphaNode", "Hello world"),
            )
        },
        case("computeReactionHash changes with different timestamp") {
            assertNotEquals(parser.computeReactionHash(1_700_000_000u, "Node", "Hello"), parser.computeReactionHash(1_700_000_001u, "Node", "Hello"))
        },
        case("computeReactionHash changes with different sender") {
            assertNotEquals(
                parser.computeReactionHash(1_700_000_000u, "AlphaNode", "Hello"),
                parser.computeReactionHash(1_700_000_000u, "BetaNode", "Hello"),
            )
        },
        case("computeReactionHash changes with different text") {
            assertNotEquals(parser.computeReactionHash(1_700_000_000u, "Node", "Hello"), parser.computeReactionHash(1_700_000_000u, "Node", "World"))
        },
        case("computeReactionHash with nil sender (DM mode)") {
            val hash = parser.computeReactionHash(1_700_000_000u, null, "Hello world")
            assertEquals(4, hash.length)
            // Should differ from channel mode with same params.
            assertNotEquals(parser.computeReactionHash(1_700_000_000u, "Node", "Hello world"), hash)
        },
        case("computeReactionHash truncates text to 5 UTF-16 code units") {
            // "Hello" is 5 code units, "Hello world" has 11; only the first 5 are hashed.
            assertEquals(parser.computeReactionHash(1_700_000_000u, "Node", "Hello"), parser.computeReactionHash(1_700_000_000u, "Node", "Hello world"))
        },
        case("computeReactionHash handles short text (fewer than 5 code units)") {
            assertEquals(4, parser.computeReactionHash(1_700_000_000u, null, "Hi").length)
        },
    )

    @TestFactory
    fun utf16EdgeCaseTests(): List<DynamicTest> = listOf(
        case("computeReactionHash handles emoji in text (multi-code-unit)") {
            // 🎉 is 2 UTF-16 code units (surrogate pair), so "🎉abc" = 5 code units.
            val hash = parser.computeReactionHash(1_700_000_000u, null, "🎉abc")
            assertEquals(4, hash.length)
            // "🎉abcdef" should hash the same since the first 5 code units match.
            assertEquals(hash, parser.computeReactionHash(1_700_000_000u, null, "🎉abcdef"))
        },
        case("computeReactionHash handles empty text") {
            assertEquals(4, parser.computeReactionHash(1_700_000_000u, "Node", "").length)
        },
    )

    @TestFactory
    fun crossAppVectorTests(): List<DynamicTest> = listOf(
        case("Dart hash matches known Dart VM output for 'hello'") {
            // In Dart: "hello".hashCode == 150804507. This is the definitive cross-app test vector.
            assertEquals(150_804_507u, parser.dartStringHash("hello".map { it.code.toUShort() }))
        },
        case("computeReactionHash is internally consistent") {
            // Verify computeReactionHash assembles code units correctly by comparing against a manual hash.
            val masked = parser.dartStringHash("1700000000AHello") and 0xFFFFu
            val expected = masked.toString(16).padStart(4, '0')
            assertEquals(expected, parser.computeReactionHash(1_700_000_000u, "A", "Hello"))
        },
        case("Emoji table has exactly 184 entries") { assertEquals(184, parser.emojiTable.size) },
    )

    @TestFactory
    fun v1ParseTests(): List<DynamicTest> = listOf(
        case("Parses v1 reaction from real wire capture") {
            val result = assertNotNull(parser.parseV1("r:1772600903000_951919033_868488711:👍"))
            assertEquals("👍", result.emoji)
            assertEquals(1_772_600_903u, result.timestampSeconds)
            assertEquals(951_919_033u, result.senderNameHash)
            assertEquals(868_488_711u, result.textHash)
        },
        case("Parses v1 reaction with heart emoji") {
            assertEquals(ParsedMCOReactionV1("❤\uFE0F", 1_700_000_000u, 12345u, 67890u), parser.parseV1("r:1700000000000_12345_67890:❤\uFE0F"))
        },
        case("Parses v1 reaction with fire emoji") {
            assertEquals("🔥", assertNotNull(parser.parseV1("r:1772600903000_100_200:🔥")).emoji)
        },
        case("V1 rejects v3 format") { assertNull(parser.parseV1("r:a1b2:00")) },
        case("V1 rejects plain text") { assertNull(parser.parseV1("hello world")) },
        case("V1 rejects wrong prefix") { assertNull(parser.parseV1("x:1700000000000_100_200:👍")) },
        case("V1 rejects too few underscore parts") { assertNull(parser.parseV1("r:1700000000000_100:👍")) },
        case("V1 rejects too many underscore parts") { assertNull(parser.parseV1("r:1700000000000_100_200_300:👍")) },
        case("V1 rejects non-numeric timestamp") { assertNull(parser.parseV1("r:abc_100_200:👍")) },
        case("V1 rejects non-numeric hash values") {
            assertNull(parser.parseV1("r:1700000000000_abc_200:👍"))
            assertNull(parser.parseV1("r:1700000000000_100_xyz:👍"))
        },
        case("V1 rejects empty emoji") { assertNull(parser.parseV1("r:1700000000000_100_200:")) },
        case("V1 rejects channel format") { assertNull(parser.parseV1("@[AlphaNode]👍\n7f3a9c12")) },
        case("V1 rejects empty string") { assertNull(parser.parseV1("")) },
        case("V1 timestamp converts millis to seconds correctly") {
            // 1700000000500 ms → 1700000000 s (truncated, not rounded).
            assertEquals(1_700_000_000u, parser.parseV1("r:1700000000500_100_200:👍")?.timestampSeconds)
        },
    )

    @TestFactory
    fun v1HashMatchingTests(): List<DynamicTest> = listOf(
        case("dartStringHash can verify v1 sender name hash") {
            val expectedHash = parser.dartStringHash("TestNode")
            val parsed = assertNotNull(parser.parseV1("r:1700000000000_${expectedHash}_12345:👍"))
            assertEquals(expectedHash, parsed.senderNameHash)
        },
        case("dartStringHash can verify v1 text hash") {
            val expectedHash = parser.dartStringHash("Hello from mesh")
            val parsed = assertNotNull(parser.parseV1("r:1700000000000_12345_$expectedHash:👍"))
            assertEquals(expectedHash, parsed.textHash)
        },
        case("V1 round-trip: construct reaction and verify both hashes match") {
            val timestampMs = 1_772_600_903_000uL
            val senderHash = parser.dartStringHash("AVN1")
            val textHash = parser.dartStringHash("Test message content")
            val parsed = assertNotNull(parser.parseV1("r:${timestampMs}_${senderHash}_$textHash:👍"))
            assertEquals((timestampMs / 1000u).toUInt(), parsed.timestampSeconds)
            assertEquals(senderHash, parsed.senderNameHash)
            assertEquals(textHash, parsed.textHash)
            assertEquals("👍", parsed.emoji)
            assertEquals("1772600903_${senderHash}_$textHash", parsed.messageIdHash)
        },
    )

    private fun case(name: String, body: () -> Unit): DynamicTest =
        DynamicTest.dynamicTest("MeshCoreOpenReactionParserTests::$name()", body)
}
