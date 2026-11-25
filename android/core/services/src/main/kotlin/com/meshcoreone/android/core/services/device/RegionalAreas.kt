// PortedFrom: MC1Services/Sources/MC1Services/Services/RegionalAreas.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.SnapshotMap
import com.meshcoreone.android.core.model.SnapshotSet
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.snapshotMap
import com.meshcoreone.android.core.model.snapshotSet
import java.text.Collator
import java.util.Locale

fun interface SubdivisionNameResolver {
    fun localizedName(code: String, locale: Locale): String?
}

object RegionalAreas {
    data class Country(val id: String, val subdivisions: SnapshotList<Subdivision>?) {
        val localizedName: String get() = name(Locale.getDefault())
        fun name(locale: Locale): String =
            Locale.forLanguageTag("und-$id").getDisplayCountry(locale).ifEmpty { id }
    }

    data class Subdivision(
        val id: String,
        val englishName: String,
        val normalizedNames: SnapshotSet<String>,
    )

    enum class AdministrativeAreaKind { STATE, PROVINCE }

    private val englishNames = SubdivisionNameResolver { _, _ -> null }

    fun administrativeAreaKind(countryCode: String?): AdministrativeAreaKind =
        if (countryCode == "CA") AdministrativeAreaKind.PROVINCE else AdministrativeAreaKind.STATE

    private fun subdivision(country: String, code: String, name: String, vararg extra: String): Subdivision =
        Subdivision(
            "$country-$code", name,
            (listOf(name, code) + extra).map { it.lowercase(Locale.ROOT) }.snapshotSet(),
        )

    val usSubdivisions: SnapshotList<Subdivision> = listOf(
        subdivision("US", "AL", "Alabama"), subdivision("US", "AK", "Alaska"),
        subdivision("US", "AZ", "Arizona"), subdivision("US", "AR", "Arkansas"),
        subdivision("US", "CA", "California"), subdivision("US", "CO", "Colorado"),
        subdivision("US", "CT", "Connecticut"), subdivision("US", "DE", "Delaware"),
        subdivision("US", "DC", "District of Columbia", "washington dc", "washington d.c."),
        subdivision("US", "FL", "Florida"), subdivision("US", "GA", "Georgia"),
        subdivision("US", "HI", "Hawaii"), subdivision("US", "ID", "Idaho"),
        subdivision("US", "IL", "Illinois"), subdivision("US", "IN", "Indiana"),
        subdivision("US", "IA", "Iowa"), subdivision("US", "KS", "Kansas"),
        subdivision("US", "KY", "Kentucky"), subdivision("US", "LA", "Louisiana"),
        subdivision("US", "ME", "Maine"), subdivision("US", "MD", "Maryland"),
        subdivision("US", "MA", "Massachusetts"), subdivision("US", "MI", "Michigan"),
        subdivision("US", "MN", "Minnesota"), subdivision("US", "MS", "Mississippi"),
        subdivision("US", "MO", "Missouri"), subdivision("US", "MT", "Montana"),
        subdivision("US", "NE", "Nebraska"), subdivision("US", "NV", "Nevada"),
        subdivision("US", "NH", "New Hampshire"), subdivision("US", "NJ", "New Jersey"),
        subdivision("US", "NM", "New Mexico"), subdivision("US", "NY", "New York"),
        subdivision("US", "NC", "North Carolina"), subdivision("US", "ND", "North Dakota"),
        subdivision("US", "OH", "Ohio"), subdivision("US", "OK", "Oklahoma"),
        subdivision("US", "OR", "Oregon"), subdivision("US", "PA", "Pennsylvania"),
        subdivision("US", "RI", "Rhode Island"), subdivision("US", "SC", "South Carolina"),
        subdivision("US", "SD", "South Dakota"), subdivision("US", "TN", "Tennessee"),
        subdivision("US", "TX", "Texas"), subdivision("US", "UT", "Utah"),
        subdivision("US", "VT", "Vermont"), subdivision("US", "VA", "Virginia"),
        subdivision("US", "WA", "Washington"), subdivision("US", "WV", "West Virginia"),
        subdivision("US", "WI", "Wisconsin"), subdivision("US", "WY", "Wyoming"),
    ).snapshot()

    val auSubdivisions: SnapshotList<Subdivision> = listOf(
        subdivision("AU", "ACT", "Australian Capital Territory"),
        subdivision("AU", "NSW", "New South Wales"), subdivision("AU", "NT", "Northern Territory"),
        subdivision("AU", "QLD", "Queensland"), subdivision("AU", "SA", "South Australia"),
        subdivision("AU", "TAS", "Tasmania"), subdivision("AU", "VIC", "Victoria"),
        subdivision("AU", "WA", "Western Australia"),
    ).snapshot()

    val continents: SnapshotMap<String, RadioRegion> = linkedMapOf(
        "US" to RadioRegion.NORTH_AMERICA, "CA" to RadioRegion.NORTH_AMERICA, "CR" to RadioRegion.NORTH_AMERICA,
        "CL" to RadioRegion.SOUTH_AMERICA, "BR" to RadioRegion.SOUTH_AMERICA,
        "GB" to RadioRegion.EUROPE, "IE" to RadioRegion.EUROPE, "DE" to RadioRegion.EUROPE,
        "FR" to RadioRegion.EUROPE, "IT" to RadioRegion.EUROPE, "ES" to RadioRegion.EUROPE,
        "PT" to RadioRegion.EUROPE, "NL" to RadioRegion.EUROPE, "BE" to RadioRegion.EUROPE,
        "CH" to RadioRegion.EUROPE, "AT" to RadioRegion.EUROPE, "CZ" to RadioRegion.EUROPE,
        "PL" to RadioRegion.EUROPE, "DK" to RadioRegion.EUROPE, "SE" to RadioRegion.EUROPE,
        "NO" to RadioRegion.EUROPE, "FI" to RadioRegion.EUROPE, "GR" to RadioRegion.EUROPE,
        "HU" to RadioRegion.EUROPE, "SK" to RadioRegion.EUROPE, "RO" to RadioRegion.EUROPE,
        "AU" to RadioRegion.OCEANIA, "NZ" to RadioRegion.OCEANIA,
        "VN" to RadioRegion.ASIA, "TH" to RadioRegion.ASIA, "MY" to RadioRegion.ASIA,
        "SG" to RadioRegion.ASIA, "PH" to RadioRegion.ASIA, "ID" to RadioRegion.ASIA,
        "JP" to RadioRegion.ASIA, "KR" to RadioRegion.ASIA,
    ).snapshotMap()

    val countries: SnapshotList<Country> = listOf(
        Country("US", usSubdivisions), Country("CA", null), Country("CR", null),
        Country("CL", null), Country("BR", null), Country("AU", auSubdivisions), Country("NZ", null),
        Country("GB", null), Country("IE", null), Country("DE", null), Country("FR", null),
        Country("IT", null), Country("ES", null), Country("PT", null), Country("NL", null),
        Country("BE", null), Country("CH", null), Country("AT", null), Country("CZ", null),
        Country("PL", null), Country("DK", null), Country("SE", null), Country("NO", null),
        Country("FI", null), Country("GR", null), Country("HU", null), Country("SK", null),
        Country("RO", null), Country("VN", null), Country("TH", null), Country("MY", null),
        Country("SG", null), Country("PH", null), Country("ID", null), Country("JP", null), Country("KR", null),
    ).snapshot()

    val countriesSortedByLocalizedName: SnapshotList<Country> by lazy {
        countries.sortedBy { it.localizedName }.snapshot()
    }

    val usCounties: SnapshotMap<String, SnapshotSet<String>> = mapOf(
        "US-CA" to listOf(
            "los angeles", "orange", "san diego", "riverside", "san bernardino",
            "ventura", "imperial", "kern", "santa barbara", "san luis obispo",
        ).snapshotSet(),
    ).snapshotMap()

    private val englishSubdivisionFallbacks = (usSubdivisions + auSubdivisions).associate { it.id to it.englishName }

    fun showsSubdivisionPicker(countryCode: String?): Boolean = subdivisions(countryCode).size > 1

    fun subdivisions(
        country: String?, locale: Locale = Locale.getDefault(), names: SubdivisionNameResolver = englishNames,
    ): SnapshotList<Subdivision> {
        val list = countries.firstOrNull { it.id == country }?.subdivisions ?: return SnapshotList.empty()
        val collator = Collator.getInstance(locale).apply {
            strength = Collator.PRIMARY
            decomposition = Collator.CANONICAL_DECOMPOSITION
        }
        return list.sortedWith { left, right ->
            collator.compare(
                subdivisionDisplayName(left.id, locale, names),
                subdivisionDisplayName(right.id, locale, names),
            )
        }.snapshot()
    }

    fun matchSubdivision(country: String, normalized: String?): String? {
        if (normalized == null) return null
        return countries.firstOrNull { it.id == country }?.subdivisions
            ?.firstOrNull { normalized in it.normalizedNames }?.id
    }

    fun matchCounty(country: String, state: String?, normalized: String?): String? =
        normalized?.takeIf { country == "US" && state != null && it in (usCounties[state] ?: emptySet()) }

    fun displayName(
        region: RegionSelection, locale: Locale = Locale.getDefault(), names: SubdivisionNameResolver = englishNames,
    ): String {
        val countryName = Country(region.countryCode, null).name(locale)
        val admin = region.administrativeAreaCode ?: return countryName
        val stateName = subdivisionDisplayName(admin, locale, names) ?: admin
        return if (region.countryCode == "US" || region.countryCode == "CA") stateName else "$stateName, $countryName"
    }

    fun subdivisionDisplayName(
        code: String, locale: Locale = Locale.getDefault(), names: SubdivisionNameResolver = englishNames,
    ): String? {
        val fallback = englishSubdivisionFallbacks[code] ?: return null
        return names.localizedName(code, locale) ?: fallback
    }
}
