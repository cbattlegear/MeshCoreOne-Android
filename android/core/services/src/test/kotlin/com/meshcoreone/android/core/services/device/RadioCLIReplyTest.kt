// AndroidOnly: WP-211 Companion CLI framing and credential validation regressions against current upstream.
package com.meshcoreone.android.core.services.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioCLIReplyTest {
    @Test
    fun valuePayloadIsPreservedVerbatim() {
        listOf("-7", "", "\r\n", "my net ", "my net \r\n").forEach { value ->
            assertEquals(RadioCLIReply.Value(value), RadioCLIReply.parse("> $value"))
        }
    }

    @Test
    fun markersStripOnlyTrailingLineEndings() {
        assertEquals(RadioCLIReply.UnknownCommand, RadioCLIReply.parse("Unknown command\r\n"))
        assertEquals(RadioCLIReply.Ok("OK"), RadioCLIReply.parse("OK\r\n"))
        listOf("Error, bad ssid", "ERROR: unsupported", "ERR - deprecated").forEach { error ->
            assertEquals(RadioCLIReply.Error(error), RadioCLIReply.parse("$error\r\n"))
        }
        assertEquals(RadioCLIReply.Other(""), RadioCLIReply.parse("\r\n"))
        assertEquals(RadioCLIReply.Other("nope "), RadioCLIReply.parse("nope \r\n"))
    }

    @Test
    fun allPayloadBearingReplyStringsAreRedacted() {
        listOf("> secret", "OK secret", "Error secret", "secret").forEach { text ->
            assertFalse(RadioCLIReply.parse(text).toString().contains("secret"))
        }
    }

    @Test
    fun credentialsAreBoundedByUtf8BytesAndCannotContainNulOrMalformedSurrogates() {
        assertTrue(RadioWiFiCredentials.valid("é".repeat(16), "a".repeat(63)))
        assertTrue(RadioWiFiCredentials.valid("a", ""))
        listOf("", "é".repeat(17), "\u0000net", "\uD800x").forEach { ssid ->
            assertFalse(RadioWiFiCredentials.valid(ssid, "password"))
        }
        listOf("é".repeat(32), "a\u0000", "\uD800x").forEach { password ->
            assertFalse(RadioWiFiCredentials.valid("net", password))
        }
        assertFalse(RadioWiFiCredentials.hasSameBytes("é", "e\u0301"))
        assertTrue(RadioWiFiCredentials.hasSameBytes("é", "é"))
    }
}
