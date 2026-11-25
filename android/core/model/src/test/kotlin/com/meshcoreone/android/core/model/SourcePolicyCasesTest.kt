// PortedFrom: MC1Services/Tests/MC1ServicesTests/ChannelFloodScopeTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Models/ChannelRegionScopeTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Models/RegionScopeSemanticsTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Models/ConnectionMethodTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/DevicePublicKeyDeduplicationTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Utilities/DeviceIdentityTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Utilities/VContactIdentityTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Legacy field-policy equivalents are not claims of WP-203 envelope/restore parity.
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.model.sha256
import com.meshcoreone.android.core.protocol.parser.RegionMatchResult
import com.meshcoreone.android.core.protocol.parser.RegionScopeKey
import com.meshcoreone.android.core.protocol.parser.TransportCodeRegionResolver
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.TestFactory

class SourcePolicyCasesTest {
    @TestFactory fun channelRegionCopies() = sourceCases("ChannelRegionScopeTests",
        "Default floodScope is .inherit" to { assertEquals(ChannelFloodScope.Inherit, testChannel().floodScope) },
        ".region(name) preserved on init" to { assertEquals(ChannelFloodScope.Region("Europe"), testChannel(ChannelFloodScope.Region("Europe")).floodScope) },
        "with(notificationLevel:) preserves .region(name)" to {
            val n = testChannel(ChannelFloodScope.Region("UK")).withNotificationLevel(NotificationLevel.MUTED)
            assertEquals(ChannelFloodScope.Region("UK"), n.floodScope); assertEquals(NotificationLevel.MUTED, n.notificationLevel)
        },
        "with(notificationLevel:) preserves .allRegions" to { assertEquals(ChannelFloodScope.AllRegions, testChannel(ChannelFloodScope.AllRegions).withNotificationLevel(NotificationLevel.MENTIONS_ONLY).floodScope) },
        "with(notificationLevel:) preserves .inherit" to { assertEquals(ChannelFloodScope.Inherit, testChannel().withNotificationLevel(NotificationLevel.MENTIONS_ONLY).floodScope) },
        "with(isFavorite:) preserves .region(name)" to {
            val n = testChannel(ChannelFloodScope.Region("France")).withFavorite(true)
            assertEquals(ChannelFloodScope.Region("France"), n.floodScope); assertTrue(n.isFavorite)
        },
        "with(isFavorite:) preserves .inherit" to { assertEquals(ChannelFloodScope.Inherit, testChannel().withFavorite(true).floodScope) },
        "with(floodScope:) updates from .region to .region" to { assertEquals(ChannelFloodScope.Region("UK"), testChannel(ChannelFloodScope.Region("Europe")).withFloodScope(ChannelFloodScope.Region("UK")).floodScope) },
        "with(floodScope: .inherit) clears region name in storage" to {
            val n = testChannel(ChannelFloodScope.Region("Europe")).withFloodScope(ChannelFloodScope.Inherit)
            assertEquals(ChannelFloodScope.Inherit, n.floodScope); assertNull(n.regionScope)
        },
        "with(floodScope:) sets specific region from inherit" to { assertEquals(ChannelFloodScope.Region("Asia"), testChannel().withFloodScope(ChannelFloodScope.Region("Asia")).floodScope) },
        "with(floodScope:) preserves all other fields" to {
            val d = testChannel(ChannelFloodScope.Region("Europe")); val n = d.withFloodScope(ChannelFloodScope.Region("UK"))
            assertEquals(d, n.copy(floodScopeModeRawValue = d.floodScopeModeRawValue, regionScope = d.regionScope))
        },
    )

    @TestFactory fun channelStorageValues() = sourceCases("ChannelFloodScopeTests",
        "Three distinct cases compare as equal only to themselves" to {
            assertEquals(ChannelFloodScope.Inherit, ChannelFloodScope.Inherit); assertEquals(ChannelFloodScope.AllRegions, ChannelFloodScope.AllRegions)
            assertEquals(ChannelFloodScope.Region("Germany"), ChannelFloodScope.Region("Germany")); assertNotEquals<ChannelFloodScope>(ChannelFloodScope.Inherit, ChannelFloodScope.AllRegions)
            assertNotEquals<ChannelFloodScope>(ChannelFloodScope.Inherit, ChannelFloodScope.Region("")); assertNotEquals(ChannelFloodScope.Region("Germany"), ChannelFloodScope.Region("France"))
        },
        "DTO default floodScope is .inherit" to { assertEquals(ChannelFloodScope.Inherit, testChannel().floodScope) },
        "DTO floodScope roundtrips for .inherit" to { val d = testChannel(); assertEquals(ChannelFloodScope.Inherit, d.floodScope); assertNull(d.regionScope) },
        "DTO floodScope roundtrips for .allRegions" to { val d = testChannel(ChannelFloodScope.AllRegions); assertEquals(ChannelFloodScope.AllRegions, d.floodScope); assertNull(d.regionScope) },
        "DTO floodScope roundtrips for .region(name)" to { val d = testChannel(ChannelFloodScope.Region("Germany")); assertEquals(ChannelFloodScope.Region("Germany"), d.floodScope); assertEquals("Germany", d.regionScope) },
        "DTO treats .region with empty name as .inherit defensively" to {
            assertEquals(ChannelFloodScope.Inherit, testChannel(ChannelFloodScope.Region("Germany")).copy(regionScope = null).floodScope)
            assertEquals(ChannelFloodScope.Inherit, testChannel(ChannelFloodScope.Region("")).floodScope)
        },
    )

    private fun legacy(
        mode: String? = null, region: String? = null, level: Long? = 2, mentions: Long? = 0, favorite: Boolean? = false,
    ): ChannelDTO = ChannelDTO.fromLegacyFields(
        UUID.randomUUID(), RADIO, 1u, "General", Bytes(ByteArray(16)), true, null, 0,
        mentions, level, favorite, mode, region,
    )

    // These execute the exact missing/null field rules; JSON/framing/restore remain WP-203.
    @TestFactory fun legacyFieldPolicies() = sourceCases("ChannelFloodScopeTests",
        "Codable round-trips all three cases for current envelope format" to {
            listOf(ChannelFloodScope.Inherit, ChannelFloodScope.AllRegions, ChannelFloodScope.Region("Germany")).forEach {
                val d = testChannel(it); assertEquals(it, legacy(d.floodScopeModeRawValue, d.regionScope).floodScope)
            }
        },
        "Legacy envelope (missing mode key, nil regionScope) decodes as .inherit" to { assertEquals(ChannelFloodScope.Inherit, legacy().floodScope) },
        "Legacy envelope (missing mode key, named regionScope) decodes as .region" to { assertEquals(ChannelFloodScope.Region("Germany"), legacy(region = "Germany").floodScope) },
        "Legacy envelope omitting notificationLevel key decodes as .all" to { assertEquals(NotificationLevel.ALL, legacy(level = null).notificationLevel) },
        "Legacy envelope omitting unreadMentionCount key decodes as 0" to { assertEquals(0L, legacy(mentions = null).unreadMentionCount) },
        "Legacy envelope omitting isFavorite key decodes as false" to { assertFalse(legacy(favorite = null).isFavorite) },
    )

    @TestFactory fun regionLabels() = sourceCases("RegionScopeSemanticsTests",
        "storageFields maps none unique and ambiguous" to {
            assertNull(RegionScopeSemantics.storageFields(RegionMatchResult.None).regionScope)
            assertEquals(emptyList(), RegionScopeSemantics.storageFields(RegionMatchResult.None).regionScopeMatches)
            assertEquals(RegionStorageFields("Germany", SnapshotList.of("Germany")), RegionScopeSemantics.storageFields(RegionMatchResult.Unique("Germany")))
            assertEquals(RegionStorageFields(null, SnapshotList.of("de-by", "de-hh")), RegionScopeSemantics.storageFields(RegionMatchResult.Ambiguous(listOf("de-hh", "de-by"))))
        },
        "coalesce prefers multi-match over sticky scope" to { assertEquals(RegionMatchResult.Ambiguous(listOf("de-by", "de-hh")), RegionScopeSemantics.coalesce("Germany", listOf("de-hh", "de-by"))) },
        "coalesce unique from single match" to { assertEquals(RegionMatchResult.Unique("USA"), RegionScopeSemantics.coalesce(null, listOf("USA"))) },
        "coalesce legacy unique from scope only" to { assertEquals(RegionMatchResult.Unique("Bavaria"), RegionScopeSemantics.coalesce("Bavaria", emptyList())) },
        "coalesce none when both empty" to {
            assertEquals(RegionMatchResult.None, RegionScopeSemantics.coalesce(null, emptyList()))
            assertEquals(RegionMatchResult.None, RegionScopeSemantics.coalesce("  ", listOf("", " ")))
        },
        "chipLabel joins ambiguous with slash separator" to {
            assertNull(RegionScopeSemantics.chipLabel(RegionMatchResult.None)); assertEquals("Germany", RegionScopeSemantics.chipLabel(RegionMatchResult.Unique("Germany")))
            assertEquals("de-by / de-hh", RegionScopeSemantics.chipLabel(RegionMatchResult.Ambiguous(listOf("de-by", "de-hh"))))
        },
        "matchRegions multi-match never stores first-match as regionScope" to {
            val key = assertNotNull(TransportCodeRegionResolver.deriveScopeKey("Germany"))
            val payload = Bytes.of(1, 2, 3, 4)
            val code = TransportCodeRegionResolver.calcTransportCode(key, 5u, payload)
            val match = TransportCodeRegionResolver.matchRegions(
                listOf(RegionScopeKey("First", key), RegionScopeKey("Second", key)), code, 5u, payload,
            )
            val fields = RegionScopeSemantics.storageFields(match)
            assertNull(fields.regionScope); assertEquals(2, fields.regionScopeMatches.size)
            assertEquals(setOf("First", "Second"), fields.regionScopeMatches.toSet())
        },
    )

    @TestFactory fun connectionValues() = sourceCases("ConnectionMethodTests",
        "Bluetooth method has correct identifier" to { val id = UUID.randomUUID(); assertEquals("ble:${id.canonicalString()}", ConnectionMethod.Bluetooth(id, "My Device").id) },
        "WiFi method has correct identifier" to { assertEquals("wifi:192.168.1.50:5000", ConnectionMethod.WiFi("192.168.1.50", 5000u, "Home").id) },
        "Display name returns custom name when set" to { assertEquals("Office Router", ConnectionMethod.WiFi("192.168.1.1", 5000u, "Office Router").displayName) },
        "Display name returns nil when not set" to { assertNull(ConnectionMethod.WiFi("192.168.1.1", 5000u).displayName) },
    )

    private fun connected(
        existing: DeviceDTO? = null, methods: List<ConnectionMethod> = emptyList(), id: UUID = UUID.randomUUID(), radio: RadioId = RADIO,
    ): DeviceDTO = DeviceDTO.fromConnection(
        id, radio, testSelfInfo(), DeviceCapabilities(9u, 100, 8, 0u, "01 Jan 2025", "T-Deck", "v1.13.0"),
        AutoAddConfig(0u), existing, methods, AT,
    )

    @TestFactory fun identityFactory() = sourceCases("DevicePublicKeyDeduplicationTests",
        "createDevice preserves radioID from existing device" to {
            val existing = testDevice().copy(radioId = RADIO, publicKey = KEY); val id = UUID.randomUUID()
            val d = connected(existing, id = id); assertEquals(id, d.id); assertEquals(RADIO, d.radioId)
        },
        "createDevice uses the provided radioID for new pairings" to { val id = UUID.randomUUID(); val radio = RadioId(UUID.randomUUID()); val d = connected(id = id, radio = radio); assertEquals(id, d.id); assertEquals(radio, d.radioId) },
        "createDevice copies appliedRadioPresetID from existingDevice" to {
            val d = connected(testDevice().copy(appliedRadioPresetID = "br", ocvPreset = "liIon"))
            assertEquals("br", d.appliedRadioPresetID); assertEquals("liIon", d.ocvPreset)
        },
        "createDevice leaves appliedRadioPresetID nil without an existingDevice" to { assertNull(connected().appliedRadioPresetID) },
        "createDevice persists the Bluetooth method supplied by the BLE connect path" to { assertTrue(connected(methods = listOf(ConnectionMethod.Bluetooth(UUID.randomUUID()))).connectionMethods.any { it.isBluetooth }) },
        "createDevice merges a Bluetooth method with an existing WiFi method" to {
            val d = connected(testDevice().copy(connectionMethods = SnapshotList.of(ConnectionMethod.WiFi("10.0.0.2", 5000u))), listOf(ConnectionMethod.Bluetooth(UUID.randomUUID())))
            assertEquals(1, d.connectionMethods.count { it.isBluetooth }); assertEquals(1, d.connectionMethods.count { it.isWiFi })
        },
        "createDevice replaces a stale Bluetooth peripheral UUID instead of accumulating" to {
            val fresh = UUID.randomUUID(); val old = testDevice().copy(connectionMethods = SnapshotList.of(ConnectionMethod.Bluetooth(UUID.randomUUID(), "Old")))
            val methods = connected(old, listOf(ConnectionMethod.Bluetooth(fresh))).connectionMethods.filter { it.isBluetooth }
            assertEquals(1, methods.size); assertEquals(ConnectionMethod.Bluetooth(fresh), methods.single())
        },
    )

    @TestFactory fun deviceIdentity() = sourceCases("DeviceIdentityTests",
        "Derives consistent UUID from public key" to { assertEquals(DeviceIdentity.deriveUUID(KEY), DeviceIdentity.deriveUUID(KEY)) },
        "Different public keys produce different UUIDs" to { assertNotEquals(DeviceIdentity.deriveUUID(Bytes(ByteArray(32) { 0xAA.toByte() })), DeviceIdentity.deriveUUID(Bytes(ByteArray(32) { 0xBB.toByte() }))) },
        "UUID derivation uses SHA256" to {
            val key = Bytes(byteArrayOf(1, 2, 3, 4) + ByteArray(28))
            assertEquals(UUID.fromString("BAF32448-92DD-2BDE-05E4-2420386D4BD2"), DeviceIdentity.deriveUUID(key))
            assertEquals(sha256(key).prefix(16).toUUID(), DeviceIdentity.deriveUUID(key))
        },
    )

    private val selfKeyA = Bytes(byteArrayOf(1) + ByteArray(31))
    private val va = Bytes.fromHex("a987552bd0518c37b4366e1cfb6df735a2e6d4c913385c32f5ea45980b459a73")
    private val vb = Bytes.fromHex("b02d900beb616f0ed4ff090cdce125630b8149f423bd7498442d66c4ca7bc083")
    @TestFactory fun vContactIdentity() = sourceCases("VContactIdentityTests",
        "Salt is eleven bytes without null terminator" to { assertEquals(11, VContactIdentity.salt.size); assertEquals(Bytes.utf8("zc-vcontact"), VContactIdentity.salt) },
        "Derives golden V-contact public key for known self key" to { val d = assertNotNull(VContactIdentity.publicKey(selfKeyA)); assertEquals(va, d); assertEquals(32, d.size) },
        "Different self keys produce different V-contact keys" to { assertEquals(va, VContactIdentity.publicKey(selfKeyA)); assertEquals(vb, VContactIdentity.publicKey(KEY)); assertNotEquals(va, vb) },
        "Derivation matches manual CryptoKit concatenation" to { assertEquals(sha256(VContactIdentity.salt + selfKeyA), VContactIdentity.publicKey(selfKeyA)) },
        "isVContact matches only derived key" to { assertTrue(VContactIdentity.isVContact(va, selfKeyA)); assertFalse(VContactIdentity.isVContact(vb, selfKeyA)); assertFalse(VContactIdentity.isVContact(selfKeyA, selfKeyA)) },
        "Invalid key lengths fail open as non-V" to {
            assertNull(VContactIdentity.publicKey(Bytes.EMPTY)); assertNull(VContactIdentity.publicKey(Bytes(ByteArray(16) { 1 })))
            assertFalse(VContactIdentity.isVContact(va, Bytes.EMPTY)); assertFalse(VContactIdentity.isVContact(Bytes.EMPTY, selfKeyA))
        },
    )
}
