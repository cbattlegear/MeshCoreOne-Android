// PortedFrom: MC1Tests/Utilities/MeshCoreURLParserTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.deeplinks

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.services.contacts.ChannelService
import com.meshcoreone.android.core.services.contacts.ContactService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class MeshCoreUriParserTest {
    @Test
    @SourceCases(
        "ContactShareContentTests::uri is meshcore contact/add and round-trips()",
        "ContactShareContentTests::uri preserves contact type()",
    )
    fun contactShareContentUsesRealEncoderAndParser() {
        val key = Bytes.parseHex("B7".repeat(32))!!
        ContactType.entries.forEach { type ->
            val uri = ContactService.exportContactURI("Alice Repeater", key, type)
            val parsed = assertContact(uri)
            assertTrue(uri.startsWith("meshcore://contact/add?"))
            assertEquals("Alice Repeater", parsed.name)
            assertEquals(key, parsed.publicKey)
            assertEquals(type, parsed.contactType)
        }
    }

    @Test
    @SourceCases(
        "MeshCoreURLParserTests::parseContactURL falls back to .chat for an out-of-range type without trapping()",
        "MeshCoreURLParserTests::parseContactURL maps type=2 to .repeater()",
        "MeshCoreURLParserTests::Every contact type round-trips through export and parse()",
    )
    fun contactTypesPreserveRawPolicy() {
        val key = "A1".repeat(32)
        assertEquals(ContactType.CHAT, assertContact("meshcore://contact/add?name=Node&public_key=$key&type=300").contactType)
        assertEquals(ContactType.REPEATER, assertContact("meshcore://contact/add?name=Node&public_key=$key&type=2").contactType)
        ContactType.entries.forEach { type ->
            assertEquals(type, assertContact(ContactService.exportContactURI("Node", Bytes.parseHex(key)!!, type)).contactType)
        }
    }

    @Test
    @SourceCases(
        "MeshCoreURLParserTests::A name carrying an injected public_key/type query does not override the declared key or type()",
        "MeshCoreURLParserTests::Names containing query-significant characters round-trip intact()",
        "MeshCoreURLParserTests::A literal plus in a name is preserved, not turned into a space()",
        "MeshCoreURLParserTests::Spaces, colons, and unicode round-trip intact()",
    )
    fun exportedContactNamesRoundTripWithoutQueryInjection() {
        val declared = Bytes.parseHex("A1432C142E1615EAB6414856F58C90CD61E7C5901650142E5EFE4D2F1332654D")!!
        val other = "BC".repeat(32)
        val names = listOf(
            "Alice&public_key=$other&type=3", "a & b", "a=b", "key & value = pair",
            "100% sure?", "a#b", "C++ dev", "Field Base", "12:30 rally point", "Café au lait", "北京",
        )
        names.forEach { name ->
            val parsed = assertContact(ContactService.exportContactURI(name, declared, ContactType.CHAT))
            assertEquals(name, parsed.name)
            assertEquals(declared, parsed.publicKey)
            assertEquals(ContactType.CHAT, parsed.contactType)
        }
    }

    @Test
    @SourceCases("MeshCoreURLParserTests::parseContactURL decodes a stock MeshCore plus-encoded space in the name()")
    fun contactFormPlusIsSpace() {
        val parsed = assertContact("meshcore://contact/add?name=Example+Repeater&public_key=${"AB".repeat(32)}&type=2")
        assertEquals("Example Repeater", parsed.name)
        assertEquals(ContactType.REPEATER, parsed.contactType)
    }

    @Test
    fun malformedContactLinksAreRejected() {
        val key = "AB".repeat(32)
        listOf(
            "meshcore://contact/add?name=&public_key=$key",
            "meshcore://contact/add?name=Node&public_key=AB",
            "meshcore://contact/add?name=Bad%2&public_key=$key",
            "meshcore://contact/add?name=Bad%FF&public_key=$key",
            "meshcore://channel/add?name=Node&public_key=$key",
        ).forEach { assertNull(MeshCoreUriParser.parseContact(it), it) }
    }

    @Test
    @SourceCases(
        "MeshCoreURLParserTests::parseChannelURL accepts a private channel with explicit secret()",
        "MeshCoreURLParserTests::parseChannelURL decodes a plus-encoded space in the name()",
        "MeshCoreURLParserTests::parseChannelURL keeps explicit secret when name is hashtag-shaped()",
        "MeshCoreURLParserTests::Public channel golden secret parses as explicit fixed key not name-hash()",
    )
    fun explicitChannelSecretsAlwaysWin() {
        val secret = "00112233445566778899AABBCCDDEEFF"
        val private = assertChannel("meshcore://channel/add?name=Ops+Base&secret=$secret")
        assertEquals("Ops Base", private.name)
        assertEquals(secret.lowercase(), private.secret.hexString)
        val hashtag = assertChannel("meshcore://channel/add?name=%23test&secret=$secret")
        assertTrue(hashtag.hasHashtagSecretMismatch)
        assertNotEquals(ChannelService.hashSecret("#test"), hashtag.secret)
        val public = assertChannel("meshcore://channel/add?name=Public&secret=8b3387e9c5cdea6ac9e5edbaa115cd72")
        assertNotEquals(ChannelService.hashSecret("Public"), public.secret)
    }

    @Test
    @SourceCases(
        "MeshCoreURLParserTests::parseChannelURL derives secret for secretless hashtag names()",
        "MeshCoreURLParserTests::parseChannelURL normalizes hashtag case when deriving secret()",
        "MeshCoreURLParserTests::parseChannelURL treats empty secret as missing for hashtag derivation()",
    )
    fun secretlessHashtagsUseGoldenHashes() {
        val vectors = mapOf("#test" to "9cd8fcf22a47333b591d96a2b848b73f", "#avion-testing2" to "3976fbac9120f147576900ac90d41dd2")
        vectors.forEach { (name, expected) ->
            val parsed = assertChannel("meshcore://channel/add?name=${name.replace("#", "%23")}")
            assertEquals(name, parsed.name)
            assertEquals(expected, parsed.secret.hexString)
        }
        val normalized = assertChannel("meshcore://channel/add?name=%23Test&secret=")
        assertEquals("#test", normalized.name)
        assertEquals(vectors.getValue("#test"), normalized.secret.hexString)
    }

    @Test
    @SourceCases(
        "MeshCoreURLParserTests::parseChannelURL rejects secretless bare names()",
        "MeshCoreURLParserTests::parseChannelURL rejects empty secret with bare non-hashtag name()",
        "MeshCoreURLParserTests::parseChannelURL rejects secretless invalid hashtag bodies()",
        "MeshCoreURLParserTests::parseChannelURL rejects present but invalid secrets without falling back to name-hash()",
    )
    fun invalidChannelInputsNeverFallBack() {
        listOf(
            "meshcore://channel/add?name=Ops",
            "meshcore://channel/add?name=Ops&secret=",
            "meshcore://channel/add?name=%23",
            "meshcore://channel/add?name=%23-bad",
            "meshcore://channel/add?name=%23has_underscore",
            "meshcore://channel/add?name=%23has%20space",
            "meshcore://channel/add?name=%23test&secret=ZZ",
            "meshcore://channel/add?name=%23test&secret=AABB",
            "meshcore://channel/add?name=%23test&secret=00112233445566778899AABBCCDDEE",
        ).forEach { assertNull(MeshCoreUriParser.parseChannel(it), it) }
    }

    @Test
    @SourceCases(
        "MeshCoreURLParserTests::parseChannelURL reads optional region_scope()",
        "MeshCoreURLParserTests::parseChannelURL ignores empty region_scope()",
        "MeshCoreURLParserTests::parseChannelURL trims whitespace-only region_scope to nil()",
        "MeshCoreURLParserTests::parseChannelURL trims and caps region_scope length()",
    )
    fun regionScopeIsTrimmedAndUtf8Capped() {
        val base = "meshcore://channel/add?name=Ops&secret=${"AB".repeat(16)}"
        assertEquals("testregion", assertChannel("$base&region_scope=%20testregion%20").regionScope)
        assertNull(assertChannel("$base&region_scope=").regionScope)
        assertNull(assertChannel("$base&region_scope=%20%20").regionScope)
        val long = "a".repeat(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES + 10)
        assertEquals("a".repeat(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES), assertChannel("$base&region_scope=$long").regionScope)
        val unicode = MeshCoreUriParser.normalizedRegionScope("é".repeat(30))
        assertEquals(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES, unicode!!.toByteArray().size)
    }

    @Test
    @SourceCases("MeshCoreURLParserTests::hasHashtagSecretMismatch is true only when secret is not the public hashtag hash()")
    fun hashtagSecretMismatchOnlyAppliesToValidHashtags() {
        val private = Bytes(ByteArray(16) { 0xAB.toByte() })
        assertTrue(MeshCoreUriParser.hasHashtagSecretMismatch("#test", private))
        assertFalse(MeshCoreUriParser.hasHashtagSecretMismatch("#test", ChannelService.hashSecret("#test")))
        assertFalse(MeshCoreUriParser.hasHashtagSecretMismatch("Ops", private))
        assertFalse(MeshCoreUriParser.hasHashtagSecretMismatch("#bad_name", private))
    }

    @Test
    @SourceCases(
        "MeshCoreURLParserTests::export and parse round-trip preserves name secret and region()",
        "MeshCoreURLParserTests::export omits region_scope for inherit and allRegions()",
        "MeshCoreURLParserTests::export includes secret for hashtag channels so older clients still work()",
    )
    fun exportedChannelsRoundTrip() {
        val secret = Bytes.parseHex("00112233445566778899AABBCCDDEEFF")!!
        val parsed = assertChannel(ChannelService.exportChannelURI("C++ & #1", secret, ChannelFloodScope.Region("testregion")))
        assertEquals("C++ & #1", parsed.name)
        assertEquals(secret, parsed.secret)
        assertEquals("testregion", parsed.regionScope)
        listOf(ChannelFloodScope.Inherit, ChannelFloodScope.AllRegions).forEach { scope ->
            assertNull(assertChannel(ChannelService.exportChannelURI("Ops", secret, scope)).regionScope)
        }
        val hashtagUri = ChannelService.exportChannelURI("#avion-testing2", ChannelService.hashSecret("#avion-testing2"))
        assertTrue(hashtagUri.contains("secret="))
    }

    @Test
    @SourceCases(
        "MeshCoreURLParserTests::parseMapURL parses a valid map link into the correct coordinate()",
        "MeshCoreURLParserTests::parseMapURL returns nil when lat or lon is missing()",
        "MeshCoreURLParserTests::parseMapURL rejects out-of-range coordinates()",
        "MeshCoreURLParserTests::parseMapURL rejects a non-map host()",
        "MeshCoreURLParserTests::parseMapURL rejects non-finite and hex-float values that Double would otherwise accept()",
    )
    fun mapCoordinatesAreStrictDecimalDegrees() {
        val valid = assertNotNull(MeshCoreUriParser.parseMap("meshcore://map?lat=37.334900&lon=-122.009020"))
        assertEquals(37.3349, valid.latitude, 0.000001)
        assertEquals(-122.00902, valid.longitude, 0.000001)
        listOf(
            "meshcore://map?lat=37.0", "meshcore://map?lon=-122.0", "meshcore://map",
            "meshcore://map?lat=91.0&lon=0.0", "meshcore://map?lat=0.0&lon=181.0",
            "meshcore://contact?lat=10.0&lon=10.0", "meshcore://map?lat=nan&lon=0.0",
            "meshcore://map?lat=0.0&lon=inf", "meshcore://map?lat=0x1p4&lon=0.0",
        ).forEach { assertNull(MeshCoreUriParser.parseMap(it), it) }
    }

    @Test
    @SourceCases("MeshCoreURLParserTests::preferredFloodScope maps region names and ignores empty()")
    fun preferredFloodScopeOnlyUsesNonblankRegions() {
        assertNull(ChannelJoinFloodScopeApplier.preferredFloodScope(null))
        assertNull(ChannelJoinFloodScopeApplier.preferredFloodScope(""))
        assertNull(ChannelJoinFloodScopeApplier.preferredFloodScope("  "))
        assertEquals(ChannelFloodScope.Region("testregion"), ChannelJoinFloodScopeApplier.preferredFloodScope("  testregion  "))
    }

    @Test
    @SourceCases(
        "MeshCoreURLParserTests::applyIfNeeded persists region scope and uses with floodScope on success()",
        "MeshCoreURLParserTests::applyIfNeeded keeps original channel when flood write fails()",
        "MeshCoreURLParserTests::applyIfNeeded does not write when regionScope is nil()",
    )
    fun floodScopeApplyIsBestEffortButCancellationPropagates() = runTest {
        val channel = ChannelDTO(radioId = RadioId(UUID.randomUUID()), index = 1u, name = "Ops")
        var writes = 0
        val updated = ChannelJoinFloodScopeApplier.applyIfNeeded(channel, "testregion") { id, scope ->
            assertEquals(channel.id, id)
            assertEquals(ChannelFloodScope.Region("testregion"), scope)
            writes++
        }
        assertEquals(1, writes)
        assertEquals(ChannelFloodScope.Region("testregion"), updated.floodScope)
        val unchanged = ChannelJoinFloodScopeApplier.applyIfNeeded(channel, "testregion") { _, _ -> error("store") }
        assertEquals(channel, unchanged)
        ChannelJoinFloodScopeApplier.applyIfNeeded(channel, null) { _, _ -> writes++ }
        assertEquals(1, writes)
        var cancelled = false
        try {
            ChannelJoinFloodScopeApplier.applyIfNeeded(channel, "testregion") { _, _ -> throw CancellationException() }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    private fun assertContact(uri: String): MeshCoreDeepLink.Contact = assertNotNull(MeshCoreUriParser.parseContact(uri), uri)
    private fun assertChannel(uri: String): MeshCoreDeepLink.Channel = assertNotNull(MeshCoreUriParser.parseChannel(uri), uri)
}
