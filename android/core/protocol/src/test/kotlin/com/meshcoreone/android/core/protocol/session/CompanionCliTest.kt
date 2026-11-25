// AndroidOnly: WP-107 Companion CLI wire and reply attribution regressions against upstream 269d877daa.
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.parser.PacketParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionCliTest {
    @Test
    fun `tagged exchange ignores bare errors and stale replies`() = runTest {
        val f = fixture()
        start(f)
        val first = checkedRequest { f.session.runCorrelatedCliCommand("get wifi.enabled") }
        runCurrent()
        assertEquals(raw(0x42, Bytes.utf8("00|get wifi.enabled")), f.transport.sent.last())
        f.transport.error(1)
        f.transport.receive(raw(0x1d, Bytes.utf8("00|> 1")))
        runCurrent()
        assertEquals("> 1", first.await())

        val next = checkedRequest { f.session.runCorrelatedCliCommand("get wifi.ssid") }
        runCurrent()
        f.transport.receive(raw(0x1d, Bytes.utf8("00|> stale")))
        runCurrent()
        assertFalse(next.isCompleted)
        f.transport.receive(raw(0x1d, Bytes.utf8("01|> mesh")))
        runCurrent()
        assertEquals("> mesh", next.await())
        f.session.stop()
    }

    @Test
    fun `timeout denies untagged attribution but a fresh tag remains usable`() = runTest {
        val f = fixture()
        start(f)
        val first = checkedRequest { f.session.runCorrelatedCliCommand("get wifi.pwd") }
        runCurrent()
        advanceTimeBy(1100)
        runCurrent()
        assertFailsWith<MeshCoreException.Timeout> { first.await() }

        val next = checkedRequest { f.session.runCorrelatedCliCommand("get wifi.ssid") }
        runCurrent()
        f.transport.receive(raw(0x1d, Bytes.utf8("> stale")))
        f.transport.receive(raw(0x1d, Bytes.utf8("00|> stale")))
        runCurrent()
        assertFalse(next.isCompleted)
        f.transport.receive(raw(0x1d, Bytes.utf8("01|> mesh")))
        runCurrent()
        assertEquals("> mesh", next.await())
        f.session.stop()
    }

    @Test
    fun `untagged reply is accepted only with clean attribution and text is redacted in logs`() = runTest {
        val f = fixture()
        start(f)
        val query = checkedRequest { f.session.runCorrelatedCliCommand("get wifi.pwd") }
        runCurrent()
        f.transport.receive(raw(0x1d, Bytes.utf8("> secret")))
        runCurrent()
        assertEquals("> secret", query.await())
        val event = assertIs<MeshEvent.CliReply>(PacketParser.parse(raw(0x1d, Bytes.utf8("> secret"))))
        assertEquals("CliReply(<redacted>)", event.toString())
        val malformed = assertIs<MeshEvent.CliReply>(PacketParser.parse(raw(0x1d, Bytes.of(0xff))))
        assertEquals("", malformed.text)
        assertFalse(malformed.validUtf8)
        assertEquals("CliReply(<redacted>)", malformed.toString())
        f.session.stop()
    }

    @Test
    fun `malformed stale and current replies cannot satisfy this or later commands`() = runTest {
        val f = fixture()
        start(f)
        val first = checkedRequest { f.session.runCorrelatedCliCommand("get wifi.enabled") }
        runCurrent()
        f.transport.receive(raw(0x1d, Bytes.utf8("00|> 1")))
        runCurrent()
        assertEquals("> 1", first.await())

        val second = checkedRequest { f.session.runCorrelatedCliCommand("get wifi.ssid") }
        runCurrent()
        f.transport.receive(raw(0x1d, Bytes.utf8("00|> secret") + Bytes.of(0xff)))
        f.transport.receive(raw(0x1d, Bytes.utf8("01|> invalid") + Bytes.of(0xff)))
        f.transport.receive(raw(0x1d, Bytes.utf8("> untagged")))
        runCurrent()
        assertFalse(second.isCompleted)
        f.transport.receive(raw(0x1d, Bytes.utf8("01|> mesh")))
        runCurrent()
        assertEquals("> mesh", second.await())

        val third = checkedRequest { f.session.runCorrelatedCliCommand("get wifi.status") }
        runCurrent()
        f.transport.receive(raw(0x1d, Bytes.utf8("> stale")))
        runCurrent()
        assertFalse(third.isCompleted)
        f.transport.receive(raw(0x1d, Bytes.utf8("02|> connected")))
        runCurrent()
        assertEquals("> connected", third.await())
        f.session.stop()
    }

    @Test
    fun `valid empty untagged reply remains distinct from malformed UTF-8`() = runTest {
        val f = fixture()
        start(f)
        val request = checkedRequest { f.session.runCorrelatedCliCommand("get wifi.ssid") }
        runCurrent()
        f.transport.receive(raw(0x1d, Bytes.EMPTY))
        runCurrent()
        assertEquals("", request.await())
        f.session.stop()
    }

    @Test
    fun `malformed reply received between requests quarantines later untagged replies`() = runTest {
        val f = fixture()
        start(f)
        f.transport.receive(raw(0x1d, Bytes.utf8("00|secret") + Bytes.of(0xff)))
        runCurrent()
        val failure = f.diagnostics.filterIsInstance<SessionDiagnostic.ParseFailure>().single()
        assertTrue(failure.reason.contains("Invalid UTF-8"))
        assertFalse(failure.toString().contains("secret"))
        val request = checkedRequest { f.session.runCorrelatedCliCommand("get wifi.ssid") }
        runCurrent()
        f.transport.receive(raw(0x1d, Bytes.utf8("> stale")))
        runCurrent()
        assertFalse(request.isCompleted)
        f.transport.receive(raw(0x1d, Bytes.utf8("00|> mesh")))
        runCurrent()
        assertEquals("> mesh", request.await())
        f.session.stop()
    }

    @Test
    fun `unreserved prefix is not mistaken for an earlier tagged reply`() = runTest {
        val f = fixture()
        start(f)
        val request = checkedRequest { f.session.runCorrelatedCliCommand("get wifi.ssid") }
        runCurrent()
        f.transport.receive(raw(0x1d, Bytes.utf8("zz|network")))
        runCurrent()
        assertEquals("zz|network", request.await())
        f.session.stop()
    }

    @Test
    fun `invalid and oversized commands never reach the radio`() = runTest {
        val f = fixture()
        start(f)
        val sent = f.transport.sent.size
        listOf("", " ", "x", "hi\u0000", "\uD800x", "é".repeat(87), "a".repeat(173)).forEach { command ->
            assertFailsWith<MeshCoreException.InvalidInput> { f.session.runCorrelatedCliCommand(command) }
        }
        assertEquals(sent, f.transport.sent.size)
        f.session.stop()
    }

    @Test
    fun `maximum command reaches wire without truncating UTF-8`() = runTest {
        val f = fixture()
        start(f)
        val value = "é".repeat(86)
        val request = checkedRequest { f.session.runCorrelatedCliCommand(value) }
        runCurrent()
        assertEquals(176, f.transport.sent.last().size)
        assertEquals(raw(0x42, Bytes.utf8("00|$value")), f.transport.sent.last())
        f.transport.receive(raw(0x1d, Bytes.utf8("00|OK")))
        runCurrent()
        assertEquals("OK", request.await())
        f.session.stop()
    }

    @Test
    fun `all correlation tags remain reserved until generation ends`() = runTest {
        val f = fixture()
        start(f)
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        var index = 0
        f.transport.onSend = { frame ->
            val prefix = "${alphabet[index / 62]}${alphabet[index % 62]}|"
            assertEquals(raw(0x42, Bytes.utf8("${prefix}get")), frame)
            index++
            f.transport.receive(raw(0x1d, Bytes.utf8("${prefix}OK")))
        }
        repeat(62 * 62) { assertEquals("OK", f.session.runCorrelatedCliCommand("get")) }
        val sent = f.transport.sent.size
        assertFailsWith<MeshCoreException.InvalidInput> { f.session.runCorrelatedCliCommand("get") }
        assertEquals(sent, f.transport.sent.size)
        f.transport.onSend = {}
        f.session.stop()
    }

    @Test
    fun `cancellation after sending prevents an untagged reply from satisfying the next command`() = runTest {
        val f = fixture()
        start(f)
        val first = checkedRequest { f.session.runCorrelatedCliCommand("get wifi.pwd") }
        runCurrent()
        first.cancel()
        runCurrent()
        val next = checkedRequest { f.session.runCorrelatedCliCommand("get wifi.ssid") }
        runCurrent()
        f.transport.receive(raw(0x1d, Bytes.utf8("> stale")))
        runCurrent()
        assertFalse(next.isCompleted)
        f.transport.receive(raw(0x1d, Bytes.utf8("01|> mesh")))
        runCurrent()
        assertEquals("> mesh", next.await())
        f.session.stop()
    }
}
