// PortedFrom: MC1Tests/Localization/RegionalSubdivisionLocalizationTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/RegionalAreas.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.l10n

import com.meshcoreone.android.core.l10n.generated.RegionalSubdivisionNames
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RegionalSubdivisionLocalizationTest {
    @Test
    fun everyCatalogSubdivisionKeyResolvesInEveryLocale() {
        assertEquals(72, RegionalSubdivisionNames.codes.size)
        assertEquals(51, RegionalSubdivisionNames.codes.count { it.startsWith("US-") })
        assertEquals(8, RegionalSubdivisionNames.codes.count { it.startsWith("AU-") })
        assertEquals(13, RegionalSubdivisionNames.codes.count { it.startsWith("CA-") })
        sourceLocales.forEach { locale ->
            val resources = resourcesForLocale(locale)
            val expected = sourceRecords(locale).associateBy(SourceRecord::name)
            RegionalSubdivisionNames.codes.forEach { code ->
                val actual = assertNotNull(RegionalSubdivisionNames.displayName(resources, code), "$locale/$code")
                val key = "l10n_app_settings_region_subdivision_" + code.lowercase().replace('-', '_')
                assertEquals(expected.getValue(key).text, actual, "$locale/$code")
                assertNotEquals("region.subdivision.$code", actual)
                assertNotEquals("", actual)
            }
        }
    }

    @Test
    fun unknownSubdivisionRetainsTheOriginalAbsentValueContract() {
        assertNull(RegionalSubdivisionNames.displayName(resourcesForLocale("en"), "US-UNKNOWN"))
        assertNull(RegionalSubdivisionNames.displayName(resourcesForLocale("en"), "CA-UNKNOWN"))
    }

    @Test
    fun exposedCodeSnapshotsCannotChangeThePrivateCatalog() {
        val snapshot = RegionalSubdivisionNames.codes
        if (snapshot is MutableSet<String>) snapshot.remove("US-CA")
        assertEquals(72, RegionalSubdivisionNames.codes.size)
        assertNotNull(RegionalSubdivisionNames.displayName(resourcesForLocale("en"), "US-CA"))
    }
}
