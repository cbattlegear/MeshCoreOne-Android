// PortedFrom: MC1Services/Sources/MC1Services/Utilities/LogRedaction.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native coverage: the Swift source has no dedicated test; expectations follow Swift Character semantics.
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class LogRedactionTest {
    @TestFactory
    fun publicKeyCases(): List<DynamicTest> = listOf(
        logCoreNative("publicKeyHex formats the first 6 bytes as lowercase two-digit hex") {
            val key = Bytes.of(0xAB, 0xCD, 0xEF, 0x01, 0x02, 0x0A, 0xFF, 0x10)
            assertEquals("abcdef01020a", LogRedaction.publicKeyHex(key))
        },
        logCoreNative("publicKeyHex honours a custom prefix length and short or empty keys") {
            val key = Bytes.of(0x00, 0x7F, 0x80, 0xFF)
            assertEquals("007f", LogRedaction.publicKeyHex(key, prefixLength = 2))
            assertEquals("007f80ff", LogRedaction.publicKeyHex(key))
            assertEquals("007f80ff", LogRedaction.publicKeyHex(key, prefixLength = 32))
            assertEquals("", LogRedaction.publicKeyHex(key, prefixLength = 0))
            assertEquals("", LogRedaction.publicKeyHex(Bytes.EMPTY))
        },
        logCoreNative("publicKeyHex rejects a negative prefix length like Swift's precondition") {
            assertFailsWith<IllegalArgumentException> { LogRedaction.publicKeyHex(Bytes.of(1), prefixLength = -1) }
        },
        logCoreNative("hex renders every byte without truncation") {
            assertEquals("0001fe", LogRedaction.hex(Bytes.of(0x00, 0x01, 0xFE)))
            assertEquals("", LogRedaction.hex(Bytes.EMPTY))
        },
    )

    @TestFactory
    fun nodeNameCases(): List<DynamicTest> = listOf(
        "" to "***",
        "ab" to "***",
        "abc" to "***",
        "abcd" to "abc***",
        "Repeater North" to "Rep***",
        // Grapheme clusters, not UTF-16 units: a ZWJ family emoji and a flag are one Character each.
        "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67ab" to "***",
        "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67abc" to "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67ab***",
        "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDE9\uD83C\uDDEAxy" to "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDE9\uD83C\uDDEAx***",
        "e\u0301te\u0301" to "***",
        "e\u0301te\u0301s" to "e\u0301te\u0301***",
    ).map { (input, expected) ->
        logCoreNative("nodeName redacts ${input.codePoints().toArray().joinToString(",") { "%x".format(it) }.ifEmpty { "<empty>" }}") {
            assertEquals(expected, LogRedaction.nodeName(input))
        }
    }

    @TestFactory
    fun cliCommandCases(): List<DynamicTest> = listOf(
        "password secret" to "password [REDACTED]",
        "PASSWORD Secret" to "PASSWORD [REDACTED]",
        "Password hunter2 extra words" to "Password hunter2 [REDACTED]",
        "set password hunter2" to "set password [REDACTED]",
        "SET PASSWORD hunter2" to "SET PASSWORD [REDACTED]",
        "set password a b c" to "set password [REDACTED]",
        "set password" to "set [REDACTED]",
        "foo set password bar" to "foo set [REDACTED]",
        // split omits empty pieces; the remainder after two splits keeps its leading spaces and is dropped.
        "password  two   spaces" to "password two [REDACTED]",
        "password   secret" to "password [REDACTED]",
        // One piece only: nothing to redact, falls through to truncation (unchanged under 40).
        "password" to "password",
        "passwordX" to "passwordX",
        "password\u00A0nbsp" to "password\u00A0nbsp",
        // A leading space defeats hasPrefix and there is no "set password": unchanged (matches Swift).
        " password x" to " password x",
        // Space + combining mark is one Character, not a separator.
        "password \u0301secret" to "password \u0301secret",
        // Canonical Character comparison: "d" + combining acute is not "d".
        "passwor" + "d\u0301 x" to "passwor" + "d\u0301 x",
        "get name" to "get name",
    ).map { (input, expected) ->
        logCoreNative("cliCommand '${input.replace("\u0301", "\\u0301").replace("\u00A0", "\\u00A0")}'") {
            assertEquals(expected, LogRedaction.cliCommand(input))
        }
    } + listOf(
        logCoreNative("cliCommand keeps exactly 40 characters and truncates 41 to 40 plus ellipsis") {
            val forty = "a".repeat(40)
            assertEquals(forty, LogRedaction.cliCommand(forty))
            assertEquals(forty + "...", LogRedaction.cliCommand(forty + "b"))
        },
        logCoreNative("cliCommand truncates by grapheme cluster, never splitting an emoji") {
            val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
            val forty = family.repeat(40)
            assertEquals(forty, LogRedaction.cliCommand(forty))
            assertEquals(forty + "...", LogRedaction.cliCommand(forty + family))
        },
        logCoreNative("a long password command is redacted, not truncated") {
            val command = "set password " + "x".repeat(60)
            assertEquals("set password [REDACTED]", LogRedaction.cliCommand(command))
        },
        logCoreNative("password placeholder constant matches Swift") {
            assertEquals("[REDACTED]", LogRedaction.PASSWORD_PLACEHOLDER)
        },
        logCoreNative("grapheme helpers count and prefix extended clusters") {
            assertEquals(0, LogRedaction.graphemeCount(""))
            assertEquals(3, LogRedaction.graphemeCount("e\u0301\uD83C\uDDFA\uD83C\uDDF8\r\n"))
            assertEquals("e\u0301", LogRedaction.graphemePrefix("e\u0301x", 1))
            assertEquals("abc", LogRedaction.graphemePrefix("abc", 10))
            assertFailsWith<IllegalArgumentException> { LogRedaction.graphemePrefix("abc", -1) }
        },
    )
}
