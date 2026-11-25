// PortedFrom: MC1Services/Tests/MC1ServicesTests/Models/DevicePlatformTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceKeysThemeTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/RegionSelectionTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Cross-owner source inputs augment, not replace, the owning WP's consumer tests.
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.parser.RegionMatchResult
import java.util.Locale
import kotlin.test.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class CrossInputPolicyTest {
    @TestFactory fun everySourcePlatformRuleAndConservativePacing(): List<DynamicTest> {
        val esp = listOf("Heltec V2", "Heltec V3", "Heltec V4", "Heltec Tracker", "Heltec E290", "Heltec E213", "Heltec T190",
            "Heltec CT62", "T-Beam", "T-Deck", "T-LoRa", "TLora", "Xiao S3 WIO", "Xiao C3", "Xiao C6", "RAK 3112",
            "Unit C6L", "Station G2", "Meshadventurer", "Generic ESP32", "ThinkNode M2", "ThinkNode M5")
        val nrf = listOf("MeshPocket", "Mesh Pocket", "T114", "Mesh Solar", "Xiao-nrf52", "Xiao_nrf52", "WM1110", "Wio Tracker",
            "T1000-E", "SenseCap Solar", "WisMesh Tag", "RAK 4631", "RAK 3401", "T-Echo", "ThinkNode-M1", "ThinkNode M3",
            "ThinkNode-M6", "GAT562", "Ikoka", "ProMicro", "Minewsemi", "Meshtiny", "Keepteen", "Nano G2 Ultra")
        return (esp.map { it to DevicePlatform.ESP32 } + nrf.map { it to DevicePlatform.NRF52 }).map { (model, expected) ->
            DynamicTest.dynamicTest("DevicePlatform source rule: $model") {
                assertEquals(expected, DevicePlatform.detect(model))
                assertEquals(expected, DevicePlatform.detect(model.lowercase(Locale.ROOT)))
                assertEquals(if (expected == DevicePlatform.NRF52) 0.025 else 0.060, expected.recommendedWritePacingSeconds)
            }
        }
    }

    @Test fun sourceUnknownModelsRemainConservativeAndBareVendorIsNotChipIdentity() {
        listOf("", "Heltec", "SomeNewDevice XYZ").forEach { assertEquals(DevicePlatform.UNKNOWN, DevicePlatform.detect(it)) }
        assertEquals(0.060, DevicePlatform.UNKNOWN.recommendedWritePacingSeconds)
        assertEquals(25L, DevicePlatform.NRF52.recommendedWritePacing.toMillis())
    }

    @Test fun sourceThemeKeysAreBareStringsAndCountrySubdivisionWritesPreserveRetapSemantics() {
        assertEquals("selectedThemeID", PersistenceKeys.SELECTED_THEME_ID); assertEquals("appColorSchemePreference", PersistenceKeys.APP_COLOR_SCHEME_PREFERENCE)
        assertFalse(PersistenceKeys.SELECTED_THEME_ID.contains("com.pocketmesh")); assertFalse(PersistenceKeys.APP_COLOR_SCHEME_PREFERENCE.contains("com.pocketmesh"))
        val current = RegionSelection("US", RegionSelection.Source.LOCATION, "US-CA", "los angeles")
        assertNull(RegionSelection.afterChoosingCountry("US", current))
        assertEquals(RegionSelection("PT", RegionSelection.Source.MANUAL), RegionSelection.afterChoosingCountry("PT", current))
        assertEquals(RegionSelection("US", RegionSelection.Source.MANUAL), RegionSelection.afterChoosingCountry("US", null))
        assertNull(RegionSelection.afterChoosingSubdivision("US-CA", current))
        assertEquals(RegionSelection("US", RegionSelection.Source.MANUAL, "US-TX"), RegionSelection.afterChoosingSubdivision("US-TX", current))
        assertNull(RegionSelection.afterChoosingSubdivision("US-CA", null))
    }

    @Test fun normalizationRemovesBlankDuplicateRegionsAndUsesNaturalLocalizedDisplayOrderOnly() {
        val fields = RegionScopeSemantics.storageFields(RegionMatchResult.Ambiguous(listOf(" r10 ", "r2", "r2", "", "r1")), Locale.US)
        assertNull(fields.regionScope); assertEquals(listOf("r1", "r2", "r10"), fields.regionScopeMatches)
        val malformed = testChannel().copy(floodScopeModeRawValue = "future", regionScope = "preserve raw")
        assertEquals(ChannelFloodScope.Inherit, malformed.floodScope)
        assertEquals("future", malformed.copy(index = 255u).floodScopeModeRawValue)
        assertEquals("preserve raw", malformed.copy(id = java.util.UUID.randomUUID()).regionScope)
        assertNull(malformed.withFavorite(true).regionScope)
        assertFailsWith<InvalidRawEnumException> {
            ChannelDTO.fromLegacyFields(malformed.id, RADIO, 1u, "x", malformed.secret, true, null, 0, null, 999, null, null, null)
        }
    }

    @Test fun sourceDiscoveryPreferenceOnlyEnablesFreshChildrenOnFirstActivation() {
        val initial = NotificationPreferences(false, false, false, false, false, false, false, false, false, false, false)
        val first = initial.enablingDiscovery(true, false, false, false)
        assertTrue(first.discoveryContactEnabled); assertTrue(first.discoveryRepeaterEnabled); assertTrue(first.discoveryRoomEnabled)
        assertFalse(initial.enablingDiscovery(true, true, false, false).discoveryContactEnabled)
        assertFalse(initial.copy(newContactDiscoveredEnabled = true).enablingDiscovery(true, false, false, false).discoveryContactEnabled)
        assertFalse(first.enablingDiscovery(false, false, false, false).newContactDiscoveredEnabled)
    }

    @Test fun allOwnedSupportFactoriesRetainTheirPrimitiveDefaultsAndNullability() {
        assertEquals("TestContact", testContact().name); assertNull(testContact().lastHeardTimestamp)
        assertEquals("General", testChannel().name); assertEquals(0L, testChannel().unreadMentionCount)
        assertEquals(1L, testMessage().sendCount); assertEquals(-90L, testRepeat().rssi)
        assertEquals("SpammerNode", testBlocked().name); assertEquals("a1b2c3d4", testReaction().messageHash)
        assertEquals(RemoteNodeRole.ROOM_SERVER, testSession().role); assertEquals(MessageStatus.DELIVERED, testRoomMessage().status)
        assertEquals(3800.toUShort(), testSnapshot().batteryMillivolts); assertEquals(250L, testRun().roundTripMs)
        assertEquals(1L, testPath().hashSize); assertTrue(testPath().runs.isEmpty())
    }
}
