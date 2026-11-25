// PortedFrom: MC1Services/Tests/MC1ServicesTests/RegionalAreasTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.model.RegionSelection
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.TestFactory

class RegionalAreasTest {
    @TestFactory
    fun sourceCases() = listOf(
        original("RegionalAreasTests", "matchSubdivision finds California from normalized state name") {
            assertEquals("US-CA", RegionalAreas.matchSubdivision("US", "ca"))
        },
        original("RegionalAreasTests", "matchSubdivision finds Queensland from short suffix") {
            assertEquals("AU-QLD", RegionalAreas.matchSubdivision("AU", "qld"))
        },
        original("RegionalAreasTests", "matchSubdivision returns nil for unknown subdivision") {
            assertNull(RegionalAreas.matchSubdivision("US", "zz"))
        },
        original("RegionalAreasTests", "matchSubdivision returns nil for nil input") {
            assertNull(RegionalAreas.matchSubdivision("US", null))
        },
        original("RegionalAreasTests", "matchCounty finds Los Angeles in US-CA") {
            assertEquals("los angeles", RegionalAreas.matchCounty("US", "US-CA", "los angeles"))
        },
        original("RegionalAreasTests", "matchCounty rejects unknown county") {
            assertNull(RegionalAreas.matchCounty("US", "US-CA", "sacramento"))
        },
        original("RegionalAreasTests", "matchCounty rejects non-US country") {
            assertNull(RegionalAreas.matchCounty("CA", "CA-ON", "york"))
        },
        original("RegionalAreasTests", "matchCounty rejects nil state") {
            assertNull(RegionalAreas.matchCounty("US", null, "los angeles"))
        },
        original("RegionalAreasTests", "continents map covers known European countries") {
            for (code in listOf("DE", "GB", "PT")) assertEquals(RadioRegion.EUROPE, RegionalAreas.continents[code])
        },
        original("RegionalAreasTests", "continents map covers Oceania and Asia") {
            assertEquals(RadioRegion.OCEANIA, RegionalAreas.continents["AU"])
            assertEquals(RadioRegion.OCEANIA, RegionalAreas.continents["NZ"])
            assertEquals(RadioRegion.ASIA, RegionalAreas.continents["VN"])
        },
        original("RegionalAreasTests", "Mexico is intentionally absent from continents") {
            assertNull(RegionalAreas.continents["MX"])
        },
        original("RegionalAreasTests", "Costa Rica and Slovakia are in both continent and country tables") {
            assertEquals(RadioRegion.NORTH_AMERICA, RegionalAreas.continents["CR"])
            assertEquals(RadioRegion.EUROPE, RegionalAreas.continents["SK"])
            assertTrue(RegionalAreas.countries.any { it.id == "CR" })
            assertTrue(RegionalAreas.countries.any { it.id == "SK" })
        },
        original("RegionalAreasTests", "displayName uses short form for US states") {
            assertEquals("California", RegionalAreas.displayName(place("US", "US-CA"), Locale.US))
        },
        original("RegionalAreasTests", "displayName uses disambiguated form for AU territories") {
            val name = RegionalAreas.displayName(place("AU", "AU-QLD"), Locale.US)
            assertTrue("Queensland" in name)
            assertTrue("Australia" in name)
        },
        original("RegionalAreasTests", "displayName falls back to country name when admin is nil") {
            assertEquals("United States", RegionalAreas.displayName(place("US"), Locale.US))
        },
        original("RegionalAreasTests", "continents and countries cover the same set of country codes") {
            assertEquals(RegionalAreas.continents.keys, RegionalAreas.countries.map { it.id }.toSet())
        },
        original("RegionalAreasTests", "usSubdivisions lists all 50 states and DC") {
            val ids = RegionalAreas.usSubdivisions.map { it.id }.toSet()
            assertEquals(51, ids.size)
            assertTrue(ids.containsAll(listOf("US-CA", "US-TX", "US-DC", "US-HI")))
        },
        original("RegionalAreasTests", "auSubdivisions lists all states and territories") {
            assertEquals(
                setOf("AU-ACT", "AU-NSW", "AU-NT", "AU-QLD", "AU-SA", "AU-TAS", "AU-VIC", "AU-WA"),
                RegionalAreas.auSubdivisions.map { it.id }.toSet(),
            )
        },
        original("RegionalAreasTests", "administrativeAreaKind is state for US and AU, province for Canada") {
            for (code in listOf("US", "AU", "PT")) {
                assertEquals(RegionalAreas.AdministrativeAreaKind.STATE, RegionalAreas.administrativeAreaKind(code))
            }
            assertEquals(RegionalAreas.AdministrativeAreaKind.PROVINCE, RegionalAreas.administrativeAreaKind("CA"))
        },
        original("RegionalAreasTests", "matchSubdivision finds Pennsylvania from long and postal names") {
            for (name in listOf("pa", "pennsylvania")) assertEquals("US-PA", RegionalAreas.matchSubdivision("US", name))
        },
        original("RegionalAreasTests", "matchSubdivision finds New Jersey from long and postal names") {
            for (name in listOf("nj", "new jersey")) assertEquals("US-NJ", RegionalAreas.matchSubdivision("US", name))
        },
        original("RegionalAreasTests", "matchSubdivision finds Delaware from long and postal names") {
            for (name in listOf("de", "delaware")) assertEquals("US-DE", RegionalAreas.matchSubdivision("US", name))
        },
        original("RegionalAreasTests", "matchSubdivision finds Maryland from long and postal names") {
            for (name in listOf("md", "maryland")) assertEquals("US-MD", RegionalAreas.matchSubdivision("US", name))
        },
        original("RegionalAreasTests", "matchSubdivision finds Texas from long and postal names") {
            for (name in listOf("tx", "texas")) assertEquals("US-TX", RegionalAreas.matchSubdivision("US", name))
        },
        original("RegionalAreasTests", "matchSubdivision finds Victoria from long and postal names") {
            for (name in listOf("vic", "victoria")) assertEquals("AU-VIC", RegionalAreas.matchSubdivision("AU", name))
        },
        original("RegionalAreasTests", "matchSubdivision returns nil for uncatalogued US territory") {
            assertNull(RegionalAreas.matchSubdivision("US", "pr"))
        },
        original("RegionalAreasTests", "displayName uses short form for Texas") {
            assertEquals("Texas", RegionalAreas.displayName(place("US", "US-TX"), Locale.US))
        },
        original("RegionalAreasTests", "matchCounty for US-PA stays nil") {
            assertNull(RegionalAreas.matchCounty("US", "US-PA", "philadelphia"))
        },
        original("RegionalAreasTests", "displayName uses short form for Pennsylvania") {
            assertEquals("Pennsylvania", RegionalAreas.displayName(place("US", "US-PA"), Locale.US))
        },
        original("RegionalAreasTests", "showsSubdivisionPicker is true for US and AU, false for PT and nil") {
            assertTrue(RegionalAreas.showsSubdivisionPicker("US"))
            assertTrue(RegionalAreas.showsSubdivisionPicker("AU"))
            for (code in listOf("PT", "CA", null)) assertFalse(RegionalAreas.showsSubdivisionPicker(code))
        },
    )

    @TestFactory
    fun nativeCases() = listOf(
        nativeCase("subdivision names preserve country case and normalized-input contracts") {
            assertNull(RegionalAreas.matchSubdivision("us", "ca"))
            assertNull(RegionalAreas.matchSubdivision("US", "CA"))
            assertEquals("US-DC", RegionalAreas.matchSubdivision("US", "washington d.c."))
            assertEquals("US-DC", RegionalAreas.matchSubdivision("US", "washington dc"))
            assertNull(RegionalAreas.matchCounty("US", "US-CA", null))
        },
        nativeCase("subdivision localization is injected without changing stable keys or fallback names") {
            val locale = Locale.forLanguageTag("fr")
            val names = SubdivisionNameResolver { code, actual ->
                assertEquals(locale, actual)
                if (code == "US-CA") "Californie" else null
            }
            assertEquals("Californie", RegionalAreas.subdivisionDisplayName("US-CA", locale, names))
            assertEquals("Texas", RegionalAreas.subdivisionDisplayName("US-TX", locale, names))
            assertNull(RegionalAreas.subdivisionDisplayName("US-ZZ", locale, names))
            assertEquals("Californie", RegionalAreas.displayName(place("US", "US-CA"), locale, names))
            assertEquals(51, RegionalAreas.subdivisions("US", locale, names).size)
            assertTrue(RegionalAreas.subdivisions(null, locale, names).isEmpty())
        },
        nativeCase("the geographic catalog retains all source rows and county matcher keys") {
            assertEquals(36, RegionalAreas.countries.size)
            assertEquals(59, RegionalAreas.usSubdivisions.size + RegionalAreas.auSubdivisions.size)
            assertEquals(10, RegionalAreas.usCounties.getValue("US-CA").size)
            assertEquals("AU-QLD", RegionalAreas.matchSubdivision("AU", "queensland"))
            assertEquals("unknown", RegionalAreas.displayName(place("unknown"), Locale.US))
        },
    )

    private fun place(country: String, state: String? = null) =
        RegionSelection(country, RegionSelection.Source.MANUAL, state)
}
