// PortedFrom: MC1Services/Tests/MC1ServicesTests/RadioPresetRecommendationTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import java.util.Locale
import kotlin.test.*
import org.junit.jupiter.api.TestFactory

class RadioPresetTest {
    @TestFactory
    fun recommendations() = listOf(
        original("RadioPresetRecommendationTests", "LA, CA \u2192 WCMesh") {
            assertEquals("wcmesh", recommended("US", "US-CA", "los angeles"))
        },
        original("RadioPresetRecommendationTests", "Sacramento (no countyKey match) \u2192 us-ca") {
            assertEquals("us-ca", recommended("US", "US-CA", "sacramento"))
        },
        original("RadioPresetRecommendationTests", "Manual California pick (countyKey nil) \u2192 us-ca") {
            assertEquals("us-ca", RadioPresets.recommended(place("US", "US-CA", source = RegionSelection.Source.MANUAL))?.id)
        },
        original("RadioPresetRecommendationTests", "Queensland \u2192 au-qld") {
            assertEquals("au-qld", recommended("AU", "AU-QLD"))
        },
        original("RadioPresetRecommendationTests", "Western Australia \u2192 au-sa-wa") {
            assertEquals("au-sa-wa", recommended("AU", "AU-WA"))
        },
        original("RadioPresetRecommendationTests", "Pennsylvania \u2192 lvmesh") {
            assertEquals("lvmesh", recommended("US", "US-PA"))
        },
        original("RadioPresetRecommendationTests", "New Jersey \u2192 lvmesh") {
            assertEquals("lvmesh", recommended("US", "US-NJ"))
        },
        original("RadioPresetRecommendationTests", "Delaware \u2192 phillymesh") {
            assertEquals("phillymesh", recommended("US", "US-DE"))
        },
        original("RadioPresetRecommendationTests", "Maryland \u2192 phillymesh") {
            assertEquals("phillymesh", recommended("US", "US-MD"))
        },
        original("RadioPresetRecommendationTests", "Manual US with no admin \u2192 us-ca") {
            assertEquals("us-ca", RadioPresets.recommended(place("US", source = RegionSelection.Source.MANUAL))?.id)
        },
        original("RadioPresetRecommendationTests", "LVMesh preset carries the expected radio parameters") {
            val actual = preset("lvmesh")
            assertEquals("LVMesh", actual.name)
            assertRF(actual, 910.525, 500.0, 10u, 5u)
            assertEquals(2L, actual.pathHashSize)
            assertEquals(RadioRegion.NORTH_AMERICA, actual.region)
        },
        original("RadioPresetRecommendationTests", "USA catalog name is USA") {
            assertEquals("USA", preset("us-ca").name)
        },
        original("RadioPresetRecommendationTests", "Canada \u2192 ca") {
            assertEquals("ca", recommended("CA"))
        },
        original("RadioPresetRecommendationTests", "British Columbia \u2192 ca-bc") {
            assertEquals("ca-bc", recommended("CA", "CA-BC"))
            assertEquals("ca", recommended("CA", "CA-ON"))
            assertTrue(selectable("ca-bc", place("CA", "CA-BC")))
            assertFalse(selectable("ca-bc", place("CA", "CA-ON")))
            assertRF(preset("ca-bc"), 909.000, 62.5, 7u, 5u)
            assertNull(preset("ca-bc").pathHashSize)
        },
        original("RadioPresetRecommendationTests", "PhillyMesh preset carries the expected radio parameters") {
            assertRF(preset("phillymesh"), 919.500, 500.0, 10u, 5u)
            assertEquals(2L, preset("phillymesh").pathHashSize)
            assertTrue(selectable("phillymesh", place("US", "US-PA")))
            assertFalse(selectable("phillymesh", place("US", "US-TX")))
        },
        original("RadioPresetRecommendationTests", "Canada preset carries the expected radio parameters") {
            val actual = preset("ca")
            assertEquals("Canada", actual.name)
            assertRF(actual, 910.525, 62.5, 7u, 5u)
            assertEquals(3L, actual.pathHashSize)
            assertEquals(RadioRegion.NORTH_AMERICA, actual.region)
        },
        original("RadioPresetRecommendationTests", "Limburg preset carries the expected radio parameters") {
            val actual = preset("nl-li")
            assertEquals("Netherlands (Limburg)", actual.name)
            assertRF(actual, 869.618, 62.5, 8u, 8u)
            assertEquals(2L, actual.pathHashSize)
            assertEquals(RadioRegion.EUROPE, actual.region)
        },
        original("RadioPresetRecommendationTests", "Victoria, AU (no sub-region preset) \u2192 au-915 (Tier 2)") {
            assertEquals("au-915", recommended("AU", "AU-VIC"))
        },
        original("RadioPresetRecommendationTests", "Texas \u2192 us-ca") {
            assertEquals("us-ca", recommended("US", "US-TX"))
        },
        original("RadioPresetRecommendationTests", "Lisbon (PT) \u2192 pt-868 (priority 110 beats pt-433)") {
            assertEquals("pt-868", recommended("PT"))
        },
        original("RadioPresetRecommendationTests", "Vietnam \u2192 vn-narrow (priority 110 beats deprecated vn)") {
            assertEquals("vn-narrow", recommended("VN"))
        },
        original("RadioPresetRecommendationTests", "Netherlands \u2192 nl (country tier beats EU continent)") {
            assertEquals("nl", recommended("NL"))
        },
        original("RadioPresetRecommendationTests", "Hungary, Slovakia, and Costa Rica recommend their country presets") {
            assertEquals("hu", recommended("HU"))
            assertEquals("sk", recommended("SK"))
            assertEquals("cr", recommended("CR"))
        },
        original("RadioPresetRecommendationTests", "catalog pathHashSize is set only on presets that name a hash") {
            for (id in listOf("hu", "sk", "nz-narrow", "lvmesh", "nl-li", "cz-narrow", "phillymesh")) {
                assertEquals(2L, preset(id).pathHashSize)
            }
            assertEquals(7u.toUByte(), preset("au-narrow").codingRate)
            assertEquals(1L, preset("nz-lr").pathHashSize)
            for (id in listOf("ca", "wcmesh")) assertEquals(3L, preset(id).pathHashSize)
            for (id in listOf("nl", "us-ca", "cr")) assertNull(preset(id).pathHashSize)
        },
        original("RadioPresetRecommendationTests", "Chile \u2192 cl") {
            assertEquals("cl", recommended("CL"))
        },
        original("RadioPresetRecommendationTests", "Chile preset carries the expected radio parameters") {
            assertRF(preset("cl"), 927.875, 62.5, 8u, 5u)
            assertEquals(RadioRegion.SOUTH_AMERICA, preset("cl").region)
        },
        original("RadioPresetRecommendationTests", "Brazil \u2192 br") {
            assertEquals("br", recommended("BR"))
        },
        original("RadioPresetRecommendationTests", "Brazil preset carries the expected radio parameters") {
            assertRF(preset("br"), 923.125, 62.5, 8u, 8u)
            assertEquals(RadioRegion.SOUTH_AMERICA, preset("br").region)
        },
        original("RadioPresetRecommendationTests", "Berlin (DE) \u2192 eu-narrow (priority 110 beats eu-lr)") {
            assertEquals("eu-narrow", recommended("DE"))
        },
        original("RadioPresetRecommendationTests", "Bermuda \u2192 nil (no continent mapping)") {
            assertNull(recommended("BM"))
        },
        original("RadioPresetRecommendationTests", "presets(for: Sacramento) includes wcmesh in alternatives") {
            assertTrue(alternatives(place("US", "US-CA", "sacramento")).containsAll(listOf("wcmesh", "us-ca")))
        },
        original("RadioPresetRecommendationTests", "presets(for: Texas) includes lvmesh in membership") {
            assertTrue(alternatives(place("US", "US-TX")).containsAll(listOf("lvmesh", "us-ca", "wcmesh")))
        },
        original("RadioPresetRecommendationTests", "presets(for: DE) returns continent-tier presets") {
            val ids = alternatives(place("DE"))
            assertTrue(ids.containsAll(listOf("eu-narrow", "eu-lr")))
            assertFalse("us-ca" in ids)
        },
        original("RadioPresetRecommendationTests", "presets(for: PT) returns country-and-below only") {
            val ids = alternatives(place("PT"))
            assertTrue(ids.containsAll(listOf("pt-868", "pt-433")))
            assertFalse("eu-narrow" in ids)
        },
        original("RadioPresetRecommendationTests", "presets(for: VN) includes both vn-narrow and vn") {
            assertTrue(alternatives(place("VN")).containsAll(listOf("vn-narrow", "vn")))
        },
        original("RadioPresetRecommendationTests", "presets(for: BR) includes br") {
            assertTrue("br" in alternatives(place("BR")))
        },
        original("RadioPresetRecommendationTests", "presets(for: CA) includes ca and excludes us-ca") {
            val ids = alternatives(place("CA"))
            assertTrue("ca" in ids)
            for (id in listOf("us-ca", "wcmesh", "lvmesh")) assertFalse(id in ids)
        },
        original("RadioPresetRecommendationTests", "presets(for: NL) includes nl and nl-li") {
            val ids = alternatives(place("NL"))
            assertTrue(ids.containsAll(listOf("nl", "nl-li")))
            assertFalse("eu-narrow" in ids)
        },
    )

    @TestFactory
    fun selectability() = listOf(
        original("RadioPresetSelectabilityTests", "SoCal county \u2192 WCMesh selectable") {
            assertTrue(selectable("wcmesh", place("US", "US-CA", "los angeles")))
        },
        original("RadioPresetSelectabilityTests", "NorCal county \u2192 WCMesh hidden, us-ca still selectable") {
            val place = place("US", "US-CA", "sacramento")
            assertFalse(selectable("wcmesh", place))
            assertTrue(selectable("us-ca", place))
        },
        original("RadioPresetSelectabilityTests", "California with no county \u2192 WCMesh hidden") {
            assertFalse(selectable("wcmesh", place("US", "US-CA", source = RegionSelection.Source.MANUAL)))
        },
        original("RadioPresetSelectabilityTests", "Non-CA US state \u2192 WCMesh hidden, us-ca selectable") {
            assertFalse(selectable("wcmesh", place("US", "US-TX")))
            assertTrue(selectable("us-ca", place("US", "US-TX")))
        },
        original("RadioPresetSelectabilityTests", "nil region \u2192 WCMesh hidden, global presets selectable") {
            for (id in listOf("wcmesh", "lvmesh", "au-qld", "au-sa-wa")) assertFalse(selectable(id, null))
            for (id in listOf("us-ca", "eu-narrow")) assertTrue(selectable(id, null))
        },
        original("RadioPresetSelectabilityTests", "SoCal county \u2192 WCMesh selectable, LVMesh hidden") {
            val place = place("US", "US-CA", "los angeles")
            assertTrue(selectable("wcmesh", place))
            assertFalse(selectable("lvmesh", place))
        },
        original("RadioPresetSelectabilityTests", "Pennsylvania \u2192 LVMesh selectable, WCMesh hidden, us-ca selectable") {
            val place = place("US", "US-PA")
            assertTrue(selectable("lvmesh", place))
            assertFalse(selectable("wcmesh", place))
            assertTrue(selectable("us-ca", place))
        },
        original("RadioPresetSelectabilityTests", "New Jersey \u2192 LVMesh selectable") {
            assertTrue(selectable("lvmesh", place("US", "US-NJ")))
        },
        original("RadioPresetSelectabilityTests", "Delaware and Maryland \u2192 LVMesh hidden, us-ca selectable") {
            for (state in listOf("US-DE", "US-MD")) {
                assertFalse(selectable("lvmesh", place("US", state)))
                assertTrue(selectable("us-ca", place("US", state)))
            }
        },
        original("RadioPresetSelectabilityTests", "Manual US with no admin \u2192 LVMesh hidden") {
            val place = place("US", source = RegionSelection.Source.MANUAL)
            assertFalse(selectable("lvmesh", place))
            assertTrue(selectable("us-ca", place))
        },
        original("RadioPresetSelectabilityTests", "nil region \u2192 LVMesh hidden, us-ca selectable") {
            assertFalse(selectable("lvmesh", null))
            assertTrue(selectable("us-ca", null))
        },
        original("RadioPresetSelectabilityTests", "Queensland \u2192 au-qld selectable, au-sa-wa hidden") {
            assertTrue(selectable("au-qld", place("AU", "AU-QLD")))
            assertFalse(selectable("au-sa-wa", place("AU", "AU-QLD")))
        },
        original("RadioPresetSelectabilityTests", "South Australia \u2192 au-sa-wa selectable, au-qld hidden") {
            assertTrue(selectable("au-sa-wa", place("AU", "AU-SA")))
            assertFalse(selectable("au-qld", place("AU", "AU-SA")))
        },
        original("RadioPresetSelectabilityTests", "Manual AU with no admin \u2192 both AU sub-region presets hidden, au-915 selectable") {
            val place = place("AU", source = RegionSelection.Source.MANUAL)
            assertFalse(selectable("au-qld", place))
            assertFalse(selectable("au-sa-wa", place))
            assertTrue(selectable("au-915", place))
        },
        original("RadioPresetSelectabilityTests", "Victoria \u2192 both AU sub-region presets hidden, au-915 selectable") {
            val place = place("AU", "AU-VIC")
            assertFalse(selectable("au-qld", place))
            assertFalse(selectable("au-sa-wa", place))
            assertTrue(selectable("au-915", place))
        },
        original("RadioPresetSelectabilityTests", "Continent/country presets selectable for any region including nil") {
            for (place in listOf(null, place("US", "US-TX"), place("DE"))) {
                assertTrue(selectable("eu-narrow", place))
                assertTrue(selectable("us-ca", place))
            }
        },
    )

    @TestFactory
    fun visibility() = listOf(
        original("RadioPresetVisiblePresetsTests", "US-CA includes us-ca and excludes eu-narrow") {
            val ids = visible(place("US", "US-CA"), null)
            assertTrue("us-ca" in ids)
            assertFalse("eu-narrow" in ids)
        },
        original("RadioPresetVisiblePresetsTests", "US-CA with active eu-narrow keeps both and presets(for:) does not contain eu-narrow") {
            val place = place("US", "US-CA")
            assertTrue(visible(place, "eu-narrow").containsAll(listOf("us-ca", "eu-narrow")))
            assertFalse("eu-narrow" in alternatives(place))
        },
        original("RadioPresetVisiblePresetsTests", "WCMesh only when the county matches; us-ca remains for California without that county") {
            val ids = visible(place("US", "US-CA", "los angeles"), null)
            assertTrue(ids.containsAll(listOf("wcmesh", "us-ca")))
            val manual = visible(place("US", "US-CA", source = RegionSelection.Source.MANUAL), null)
            assertFalse("wcmesh" in manual)
            assertTrue("us-ca" in manual)
        },
        original("RadioPresetVisiblePresetsTests", "Pennsylvania shows LVMesh; Texas hides it") {
            assertTrue(visible(place("US", "US-PA"), null).containsAll(listOf("lvmesh", "us-ca")))
            val texas = visible(place("US", "US-TX"), null)
            assertFalse("lvmesh" in texas)
            assertTrue("us-ca" in texas)
        },
        original("RadioPresetVisiblePresetsTests", "nil place uses locale list, hides county and sub-region presets, keeps country presets") {
            val ids = visible(null, null)
            for (id in listOf("wcmesh", "lvmesh", "au-qld")) assertFalse(id in ids)
            assertTrue(ids.containsAll(listOf("us-ca", "eu-narrow")))
        },
        original("RadioPresetVisiblePresetsTests", "Bermuda empty regional list falls back to locale list") {
            val ids = visible(place("BM", source = RegionSelection.Source.MANUAL), null)
            assertEquals(visible(null, null).toSet(), ids.toSet())
            assertTrue(ids.isNotEmpty())
        },
    )

    @TestFactory
    fun aliasesAndEncoding() = listOf(
        original("RadioPresetAliasIdentityTests", "Brazil RF matches au-sa-wa and br in catalog order") {
            assertEquals(listOf("au-sa-wa", "br"), matches("br"))
        },
        original("RadioPresetAliasIdentityTests", "EU Narrow RF matches eu-narrow, ch, and nl-li in catalog order") {
            assertEquals(listOf("eu-narrow", "ch", "nl-li"), matches("ch"))
        },
        original("RadioPresetAliasIdentityTests", "Netherlands RF matches hu, nl, and sk in catalog order") {
            assertEquals(listOf("hu", "nl", "sk"), matches("nl"))
        },
        original("RadioPresetAliasIdentityTests", "unlabeled NL-family collision uses country recommendation") {
            for ((country, id) in listOf("HU" to "hu", "NL" to "nl", "SK" to "sk")) {
                assertEquals(id, resolved("nl", null, place(country)))
            }
            assertNull(resolved("nl", null, place("DE")))
        },
        original("RadioPresetAliasIdentityTests", "unique RF still returns a one-element set") {
            assertEquals(listOf("au-qld"), matches("au-qld"))
            assertEquals(listOf("lvmesh"), matches("lvmesh"))
        },
        original("RadioPresetAliasIdentityTests", "USA RF matches us-ca and ca in catalog order") {
            assertEquals(listOf("us-ca", "ca"), matches("us-ca"))
        },
        original("RadioPresetAliasIdentityTests", "preferredID wins among aliases") {
            assertEquals("br", resolved("br", "br", null))
            assertEquals("au-sa-wa", resolved("br", "au-sa-wa", null))
            assertEquals("ch", resolved("ch", "ch", null))
        },
        original("RadioPresetAliasIdentityTests", "stale preferredID does not win") {
            assertNull(resolved("br", "us-ca", null))
        },
        original("RadioPresetAliasIdentityTests", "stale preferredID on unique RF yields that unique preset") {
            assertEquals("au-qld", resolved("au-qld", "br", null))
        },
        original("RadioPresetAliasIdentityTests", "stale preferredID on USA/Canada RF uses recommendation or Custom") {
            assertEquals("us-ca", resolved("us-ca", "br", place("US", source = RegionSelection.Source.MANUAL)))
            assertEquals("ca", resolved("us-ca", "br", place("CA")))
            assertNull(resolved("us-ca", "br", null))
        },
        original("RadioPresetAliasIdentityTests", "unlabeled collision uses recommended when it is in the set") {
            assertEquals("au-sa-wa", resolved("br", null, place("AU", "AU-SA")))
            assertEquals("br", resolved("br", null, place("BR")))
            assertEquals("ch", resolved("ch", null, place("CH")))
            assertEquals("eu-narrow", resolved("ch", null, place("DE")))
        },
        original("RadioPresetAliasIdentityTests", "unlabeled collision with no recommended in set is Custom") {
            assertNull(resolved("br", null, place("US", source = RegionSelection.Source.MANUAL)))
            assertNull(resolved("br", null, null))
        },
        original("RadioPresetAliasIdentityTests", "preferredID beats recommended") {
            assertEquals("br", resolved("br", "br", place("AU", "AU-SA")))
        },
        original("RadioPresetAliasIdentityTests", "matchingPreset is unique-or-nil, not first-wins") {
            assertNull(unique("br"))
            assertEquals("au-qld", unique("au-qld")?.id)
        },
        original("RadioPresetAliasIdentityTests", "alreadyConfigured is RF membership not first-match id") {
            assertTrue("br" in matches("br"))
            assertNotEquals("br", unique("br")?.id)
        },
        original("RadioPresetEncodingTests", "frequencyKHz rounds to the nearest kHz") {
            assertEquals(512_002u, testPreset(512.002, 62.5).frequencyKHz)
        },
        original("RadioPresetEncodingTests", "bandwidthHz rounds to the nearest Hz") {
            assertEquals(62_501u, testPreset(915.0, 62.501).bandwidthHz)
        },
        original("RadioPresetPathHashSizeTests", "pathHashMode is pathHashSize minus one, or nil") {
            assertNull(testPreset().pathHashMode)
            assertEquals(0.toUByte(), testPreset().copy(pathHashSize = 1).pathHashMode)
            assertEquals(1.toUByte(), testPreset().copy(pathHashSize = 2).pathHashMode)
        },
    )

    @TestFactory
    fun nativeCases() = listOf(
        nativeCase("all catalog rows retain independent RF tuples and raw region values") {
            assertEquals(28, RadioPresets.all.size)
            assertEquals(28, RadioPresets.all.map { it.id }.toSet().size)
            assertEquals(
                listOf("North America", "South America", "Europe", "Oceania", "Asia"),
                RadioRegion.entries.map { it.rawValue },
            )
            assertEquals(listOf("NA", "SA", "EU", "AU", "AS"), RadioRegion.entries.map { it.shortCode })
            assertEquals(869_618u, preset("eu-narrow").frequencyKHz)
            assertEquals(62_500u, preset("eu-narrow").bandwidthHz)
            assertEquals(110L, preset("us-ca").recommendationPriority)
            assertEquals(100L, preset("au-915").recommendationPriority)
        },
        nativeCase("locale preference order preserves excluded-region fallback and source country groups") {
            assertEquals(
                listOf(RadioRegion.NORTH_AMERICA, RadioRegion.EUROPE, RadioRegion.OCEANIA, RadioRegion.ASIA),
                RadioRegion.regionsForLocale(Locale.US),
            )
            assertEquals(RadioRegion.OCEANIA, RadioRegion.regionsForLocale(Locale.forLanguageTag("en-AU")).first())
            assertEquals(RadioRegion.EUROPE, RadioRegion.regionsForLocale(Locale.forLanguageTag("sk-SK")).first())
            assertEquals(RadioRegion.ASIA, RadioRegion.regionsForLocale(Locale.forLanguageTag("vi-VN")).first())
            assertEquals(RadioRegion.SOUTH_AMERICA, RadioRegion.regionsForLocale(Locale.forLanguageTag("pt-BR")).first())
            assertEquals(RadioRegion.entries.toList(), RadioRegion.regionsForLocale(Locale.ROOT).toList())
            assertEquals(RadioRegion.entries.toList(), RadioRegion.regionsForLocale(Locale.forLanguageTag("ja-JP")).toList())
            assertEquals(RadioRegion.NORTH_AMERICA, RadioPresets.presetsForLocale(Locale.US).first().region)
        },
        nativeCase("unknown active catalog IDs never fabricate a preset and traveler IDs occur once") {
            assertEquals(visible(place("US"), null), visible(place("US"), "not-a-preset"))
            val ids = visible(place("US"), "eu-narrow")
            assertEquals(1, ids.count { it == "eu-narrow" })
            assertEquals("eu-narrow", ids.last())
        },
        nativeCase("RF matching uses the source strict frequency and bandwidth tolerances") {
            assertTrue("au-qld" in RadioPresets.matchingPresets(923_224u, 62_500u, 8u, 5u).map { it.id })
            assertFalse("au-qld" in RadioPresets.matchingPresets(923_226u, 62_500u, 8u, 5u).map { it.id })
            assertFalse("au-qld" in RadioPresets.matchingPresets(923_125u, 63_500u, 8u, 5u).map { it.id })
            assertTrue(RadioPresets.matchingPresets(0u, 0u, 0u, 0u).isEmpty())
        },
        nativeCase("invalid preset conversions throw typed failures instead of trapping or clipping") {
            for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.0005, 4_294_967.296)) {
                assertFailsWith<MeshCoreException.InvalidInput> { testPreset(value, 62.5).frequencyKHz }
            }
            assertFailsWith<MeshCoreException.InvalidInput> { testPreset().copy(pathHashSize = 0).pathHashMode }
            assertFailsWith<MeshCoreException.InvalidInput> { testPreset().copy(pathHashSize = 257).pathHashMode }
        },
        nativeCase("bandwidth options units formatting unsigned bounds and nearest ties are source-correct") {
            assertEquals(
                listOf(7800u, 10400u, 15600u, 20800u, 31250u, 41700u, 62500u, 125000u, 250000u, 500000u),
                RadioOptions.bandwidthsHz,
            )
            assertEquals(listOf("7.8", "10.4", "15.6", "20.8", "31.25", "41.7", "62.5", "125", "250", "500"),
                RadioOptions.bandwidthsHz.map(RadioOptions::formatBandwidth))
            assertEquals(5L..12L, RadioOptions.spreadingFactors)
            assertEquals(5L..8L, RadioOptions.codingRates)
            assertEquals(7.8, RadioOptions.bandwidthsKHz.first())
            assertEquals(7_800u, RadioOptions.nearestBandwidth(7_799u))
            assertEquals(7_800u, RadioOptions.nearestBandwidth(7_801u))
            assertEquals(7_800u, RadioOptions.nearestBandwidth(9_100u))
            assertEquals(500_000u, RadioOptions.nearestBandwidth(UInt.MAX_VALUE))
            assertEquals("12.34", RadioOptions.formatBandwidth(12_345u))
            assertEquals("12", RadioOptions.formatBandwidth(12_000u))
            assertEquals("0", RadioOptions.formatBandwidth(0u))
        },
    )

    private fun preset(id: String) = assertNotNull(RadioPresets.all.firstOrNull { it.id == id })
    private fun place(
        country: String, state: String? = null, county: String? = null,
        source: RegionSelection.Source = RegionSelection.Source.LOCATION,
    ) = RegionSelection(country, source, state, county)
    private fun recommended(country: String, state: String? = null, county: String? = null) =
        RadioPresets.recommended(place(country, state, county))?.id
    private fun alternatives(place: RegionSelection) = RadioPresets.presets(place).map { it.id }
    private fun selectable(id: String, place: RegionSelection?) = RadioPresets.isSelectable(preset(id), place)
    private fun visible(place: RegionSelection?, active: String?) = RadioPresets.visiblePresets(place, active, Locale.US).map { it.id }
    private fun matches(id: String): List<String> = preset(id).let {
        RadioPresets.matchingPresets(it.frequencyKHz, it.bandwidthHz, it.spreadingFactor, it.codingRate).map { row -> row.id }
    }
    private fun resolved(id: String, preferred: String?, place: RegionSelection?): String? = preset(id).let {
        RadioPresets.resolvedPreset(it.frequencyKHz, it.bandwidthHz, it.spreadingFactor, it.codingRate, preferred, place)?.id
    }
    private fun unique(id: String): RadioPreset? = preset(id).let {
        RadioPresets.matchingPreset(it.frequencyKHz, it.bandwidthHz, it.spreadingFactor, it.codingRate)
    }
    private fun assertRF(preset: RadioPreset, frequency: Double, bandwidth: Double, sf: UByte, cr: UByte) {
        assertEquals(frequency, preset.frequencyMHz)
        assertEquals(bandwidth, preset.bandwidthKHz)
        assertEquals(sf, preset.spreadingFactor)
        assertEquals(cr, preset.codingRate)
    }
    private fun testPreset(frequency: Double = 869.618, bandwidth: Double = 62.5) =
        RadioPreset("test", "Test", RadioRegion.EUROPE, frequency, bandwidth, 7u, 5u,
            PresetAvailability.Continent(RadioRegion.EUROPE))
}
