// PortedFrom: MeshCore/Tests/MeshCoreTests/Protocol/FloodScopeMappingTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Tests/MeshCoreTests/Protocol/RegionTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.parser

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.AnonRequestType
import com.meshcoreone.android.core.protocol.model.FloodScope
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class OriginalRegionsCasesTest {
    @TestFactory
    fun floodScopeMapping() = listOf(
        original("FloodScopeMappingTests", "disabled scope key differs from any region scope key") {
            assertNotEquals(FloodScope.Disabled.scopeKey(), FloodScope.Region("Europe").scopeKey())
        },
        original("FloodScopeMappingTests", "different region names produce different scope keys") { assertNotEquals(FloodScope.Region("Europe").scopeKey(), FloodScope.Region("UK").scopeKey()) },
        original("FloodScopeMappingTests", "setFloodScopeUnscoped emits sub-command 1 with no scope key") { assertEquals(hex("3601"), PacketBuilder.setFloodScopeUnscoped()) },
        original("FloodScopeMappingTests", "setFloodScope (sub-command 0) differs from the unscoped override") {
            val zero = PacketBuilder.setFloodScope(FloodScope.Disabled.scopeKey()); val unscoped = PacketBuilder.setFloodScopeUnscoped()
            assertNotEquals(zero, unscoped); assertEquals(0u.toUByte(), zero[1]); assertEquals(1u.toUByte(), unscoped[1])
        },
    )

    @TestFactory
    fun regionKeys() = listOf(
        original("FloodScopeRegionTests", "region key matches SHA256 of #-prefixed name") { assertEquals(hex("47a33374e5302e7bfc38a71edfddf156"), FloodScope.Region("Europe").scopeKey()) },
        original("FloodScopeRegionTests", "region handles explicit # prefix idempotently") { assertEquals(FloodScope.Region("Europe").scopeKey(), FloodScope.Region("#Europe").scopeKey()) },
        original("FloodScopeRegionTests", "region differs from channelName for the same string") { assertNotEquals(FloodScope.Region("Europe").scopeKey(), FloodScope.ChannelName("Europe").scopeKey()) },
        original("FloodScopeRegionTests", "disabled still produces 16 zero bytes") {
            assertEquals(filled(size = 16), FloodScope.Disabled.scopeKey()); assertEquals(16, FloodScope.Disabled.scopeKey().size)
        },
    )

    @TestFactory
    fun anonRequests() = listOf(
        original("SendAnonReqTests", "regions request with path") {
            val key = filled(0xaa, 32)
            val packet = PacketBuilder.sendAnonReq(key, AnonRequestType.REGIONS, 0x41u, hex("1122"))
            assertEquals(0x39u.toUByte(), packet[0]); assertEquals(key, packet.slice(1, 33))
            assertEquals(1u.toUByte(), packet[33]); assertEquals(0x41u.toUByte(), packet[34])
            assertEquals(hex("2211"), packet.slice(35, 37)); assertEquals(37, packet.size)
        },
        original("SendAnonReqTests", "regions request zero-hop (no path)") {
            val packet = PacketBuilder.sendAnonReq(filled(0xbb, 32), AnonRequestType.REGIONS, 0u, Bytes.EMPTY)
            assertEquals(0x39u.toUByte(), packet[0]); assertEquals(1u.toUByte(), packet[33]); assertEquals(0u.toUByte(), packet[34]); assertEquals(35, packet.size)
        },
        original("SendAnonReqTests", "pubkey longer than 32 bytes is truncated") {
            val packet = PacketBuilder.sendAnonReq(filled(0xcc, 64), AnonRequestType.REGIONS, 0u, Bytes.EMPTY)
            assertEquals(filled(0xcc, 32), packet.slice(1, 33)); assertEquals(35, packet.size)
        },
    )

    private fun response(text: String) = le(0x12345678u) + Bytes.utf8(text)

    @TestFactory
    fun regionResponses() = listOf(
        original("RegionsParserTests", "parses comma-separated regions") { assertEquals(listOf("Europe", "UK", "France"), RegionsParser.parse(response("Europe,UK,France"))) },
        original("RegionsParserTests", "parses single region") { assertEquals(listOf("Europe"), RegionsParser.parse(response("Europe"))) },
        original("RegionsParserTests", "parses empty string to empty array") { assertEquals(emptyList(), RegionsParser.parse(response(""))) },
        original("RegionsParserTests", "strips null terminators") { assertEquals(listOf("Europe", "UK"), RegionsParser.parse(response("Europe,UK\u0000\u0000"))) },
        original("RegionsParserTests", "throws on response shorter than 4 bytes") { assertFailsWith<MeshCoreException.ParseError> { RegionsParser.parse(hex("0102")) } },
        original("RegionsParserTests", "throws on invalid UTF-8") { assertFailsWith<MeshCoreException.ParseError> { RegionsParser.parse(filled(size = 4) + hex("fffe")) } },
        original("RegionsParserTests", "filters out wildcard region") { assertEquals(listOf("Europe", "UK"), RegionsParser.parse(response("*,Europe,UK"))) },
        original("RegionsParserTests", "filters out wildcard-only response to empty array") { assertEquals(emptyList(), RegionsParser.parse(response("*"))) },
        original("RegionsParserTests", "filters out whitespace-only entries") { assertEquals(listOf("Europe", "UK"), RegionsParser.parse(response("Europe, ,UK"))) },
        original("RegionsParserTests", "trims whitespace around region names") { assertEquals(listOf("Europe", "UK"), RegionsParser.parse(response(" Europe , UK "))) },
    )
}
