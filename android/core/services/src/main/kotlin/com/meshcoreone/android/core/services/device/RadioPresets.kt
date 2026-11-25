// PortedFrom: MC1Services/Sources/MC1Services/Services/RadioPresets.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.SnapshotSet
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.snapshotSet
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

enum class RadioRegion(val rawValue: String, val shortCode: String) {
    NORTH_AMERICA("North America", "NA"),
    SOUTH_AMERICA("South America", "SA"),
    EUROPE("Europe", "EU"),
    OCEANIA("Oceania", "AU"),
    ASIA("Asia", "AS");

    companion object {
        fun regionsForLocale(locale: Locale = Locale.getDefault()): SnapshotList<RadioRegion> = when (locale.country) {
            "US", "CA", "CR" -> listOf(NORTH_AMERICA, EUROPE, OCEANIA, ASIA)
            "AU", "NZ" -> listOf(OCEANIA, NORTH_AMERICA, EUROPE, ASIA)
            "GB", "DE", "FR", "IT", "ES", "PT", "CH", "CZ", "IE", "NL", "BE", "AT", "HU", "SK" ->
                listOf(EUROPE, NORTH_AMERICA, OCEANIA, ASIA)
            "VN", "TH", "MY", "SG", "PH", "ID" -> listOf(ASIA, OCEANIA, EUROPE, NORTH_AMERICA)
            "CL", "BR" -> listOf(SOUTH_AMERICA, NORTH_AMERICA, EUROPE, OCEANIA, ASIA)
            else -> entries
        }.snapshot()
    }
}

sealed interface PresetAvailability {
    data class Continent(val region: RadioRegion) : PresetAvailability
    data class Countries(val codes: SnapshotSet<String>) : PresetAvailability
    data class SubRegions(val country: String, val areas: SnapshotSet<String>) : PresetAvailability
    data class Counties(val country: String, val state: String, val keys: SnapshotSet<String>) : PresetAvailability
}

data class RadioPreset(
    val id: String,
    val name: String,
    val region: RadioRegion,
    val frequencyMHz: Double,
    val bandwidthKHz: Double,
    val spreadingFactor: UByte,
    val codingRate: UByte,
    val availability: PresetAvailability,
    val repeatSectionHeader: String? = null,
    val pathHashSize: Long? = null,
    val recommendationPriority: Long = 100,
) {
    val frequencyKHz: UInt get() = scaledUInt(frequencyMHz)
    val bandwidthHz: UInt get() = scaledUInt(bandwidthKHz)
    val pathHashMode: UByte?
        get() {
            val size = pathHashSize ?: return null
            if (size !in 1L..256L) throw MeshCoreException.InvalidInput("Path hash size does not fit a firmware mode")
            return (size - 1).toUByte()
        }

    private fun scaledUInt(value: Double): UInt {
        val scaled = value * 1000.0
        if (!scaled.isFinite() || scaled <= -0.5 || scaled >= UInt.MAX_VALUE.toDouble() + 0.5) {
            throw MeshCoreException.InvalidInput("Preset value does not fit a scaled UInt32")
        }
        val rounded = scaled.roundToLong()
        if (rounded < 0) throw MeshCoreException.InvalidInput("Preset value does not fit a scaled UInt32")
        return rounded.toUInt()
    }
}

object RadioPresets {
    private fun countries(vararg codes: String) = PresetAvailability.Countries(codes.asList().snapshotSet())
    private fun areas(country: String, vararg codes: String) =
        PresetAvailability.SubRegions(country, codes.asList().snapshotSet())
    private fun continent(region: RadioRegion) = PresetAvailability.Continent(region)

    val all: SnapshotList<RadioPreset> = listOf(
        RadioPreset("au-915", "Australia", RadioRegion.OCEANIA, 915.800, 250.0, 10u, 5u, countries("AU")),
        RadioPreset("au-narrow", "Australia (Narrow)", RadioRegion.OCEANIA, 916.575, 62.5, 7u, 7u, countries("AU")),
        RadioPreset("au-mid", "Australia (Mid)", RadioRegion.OCEANIA, 915.075, 125.0, 9u, 5u, countries("AU")),
        RadioPreset("au-sa-wa", "Australia: SA, WA", RadioRegion.OCEANIA, 923.125, 62.5, 8u, 8u, areas("AU", "AU-SA", "AU-WA")),
        RadioPreset("au-qld", "Australia: QLD", RadioRegion.OCEANIA, 923.125, 62.5, 8u, 5u, areas("AU", "AU-QLD")),
        RadioPreset("nz-lr", "New Zealand (Gisborne)", RadioRegion.OCEANIA, 917.375, 250.0, 11u, 5u, countries("NZ"), pathHashSize = 1),
        RadioPreset("nz-narrow", "New Zealand (Narrow)", RadioRegion.OCEANIA, 917.375, 62.5, 7u, 5u, countries("NZ"), pathHashSize = 2),
        RadioPreset("eu-narrow", "EU/UK (Narrow)", RadioRegion.EUROPE, 869.618, 62.5, 8u, 8u, continent(RadioRegion.EUROPE), recommendationPriority = 110),
        RadioPreset("eu-lr", "EU/UK (Deprecated)", RadioRegion.EUROPE, 869.525, 250.0, 11u, 5u, continent(RadioRegion.EUROPE)),
        RadioPreset("cz-narrow", "Czech Republic (Narrow)", RadioRegion.EUROPE, 869.432, 62.5, 7u, 5u, countries("CZ"), pathHashSize = 2),
        RadioPreset("eu-433-lr", "EU 433MHz (Long Range)", RadioRegion.EUROPE, 433.650, 250.0, 11u, 5u, continent(RadioRegion.EUROPE)),
        RadioPreset("eu-433-narrow", "EU 433MHz (Narrow)", RadioRegion.EUROPE, 433.650, 62.5, 8u, 8u, continent(RadioRegion.EUROPE)),
        RadioPreset("pt-433", "Portugal 433", RadioRegion.EUROPE, 433.375, 62.5, 9u, 6u, countries("PT")),
        RadioPreset("pt-868", "Portugal 868", RadioRegion.EUROPE, 869.618, 62.5, 7u, 6u, countries("PT"), recommendationPriority = 110),
        RadioPreset("ch", "Switzerland", RadioRegion.EUROPE, 869.618, 62.5, 8u, 8u, countries("CH")),
        RadioPreset("hu", "Hungary", RadioRegion.EUROPE, 869.618, 62.5, 7u, 5u, countries("HU"), pathHashSize = 2),
        RadioPreset("nl", "Netherlands", RadioRegion.EUROPE, 869.618, 62.5, 7u, 5u, countries("NL"), recommendationPriority = 110),
        RadioPreset("nl-li", "Netherlands (Limburg)", RadioRegion.EUROPE, 869.618, 62.5, 8u, 8u, countries("NL"), pathHashSize = 2),
        RadioPreset("sk", "Slovakia", RadioRegion.EUROPE, 869.618, 62.5, 7u, 5u, countries("SK"), pathHashSize = 2),
        RadioPreset("us-ca", "USA", RadioRegion.NORTH_AMERICA, 910.525, 62.5, 7u, 5u, countries("US"), recommendationPriority = 110),
        RadioPreset("ca", "Canada", RadioRegion.NORTH_AMERICA, 910.525, 62.5, 7u, 5u, countries("CA"), pathHashSize = 3, recommendationPriority = 110),
        RadioPreset("ca-bc", "Canada: BC", RadioRegion.NORTH_AMERICA, 909.000, 62.5, 7u, 5u, areas("CA", "CA-BC")),
        RadioPreset("cr", "Costa Rica", RadioRegion.NORTH_AMERICA, 910.525, 125.0, 11u, 5u, countries("CR")),
        RadioPreset(
            "wcmesh", "WCMesh (SoCal)", RadioRegion.NORTH_AMERICA, 927.875, 62.5, 7u, 5u,
            PresetAvailability.Counties(
                "US", "US-CA", listOf(
                    "los angeles", "orange", "san diego", "riverside", "san bernardino",
                    "ventura", "imperial", "kern", "santa barbara", "san luis obispo",
                ).snapshotSet(),
            ),
            pathHashSize = 3,
        ),
        RadioPreset("phillymesh", "PhillyMesh", RadioRegion.NORTH_AMERICA, 919.500, 500.0, 10u, 5u, areas("US", "US-PA", "US-NJ", "US-DE", "US-MD"), pathHashSize = 2),
        RadioPreset("lvmesh", "LVMesh", RadioRegion.NORTH_AMERICA, 910.525, 500.0, 10u, 5u, areas("US", "US-PA", "US-NJ"), pathHashSize = 2),
        // Chile's source catalog cites the MeshChile community: https://meshchile.cl.
        RadioPreset("cl", "Chile", RadioRegion.SOUTH_AMERICA, 927.875, 62.5, 8u, 5u, countries("CL")),
        RadioPreset("br", "Brazil", RadioRegion.SOUTH_AMERICA, 923.125, 62.5, 8u, 8u, countries("BR")),
        RadioPreset("vn-narrow", "Vietnam (Narrow)", RadioRegion.ASIA, 920.250, 62.5, 8u, 5u, countries("VN"), recommendationPriority = 110),
        RadioPreset("vn", "Vietnam (Deprecated)", RadioRegion.ASIA, 920.250, 250.0, 11u, 5u, countries("VN")),
    ).snapshot()

    // Repeat-mode consumers apply only frequency; the source's BW/SF/CR fields are inert.
    val repeatPresets: SnapshotList<RadioPreset> = listOf(
        RadioPreset(
            "repeat-433", "433 MHz", RadioRegion.EUROPE, 433.000, 62.5, 9u, 8u,
            continent(RadioRegion.EUROPE), repeatSectionHeader = "EU/Asia",
        ),
        RadioPreset(
            "repeat-869", "869 MHz", RadioRegion.EUROPE, 869.495, 62.5, 8u, 8u,
            continent(RadioRegion.EUROPE), repeatSectionHeader = "EU",
        ),
        RadioPreset(
            "repeat-918", "918 MHz", RadioRegion.NORTH_AMERICA, 918.000, 62.5, 7u, 8u,
            continent(RadioRegion.NORTH_AMERICA), repeatSectionHeader = "US/AU/NZ",
        ),
    ).snapshot()

    private val recommendationOrder = all.sortedWith(
        compareByDescending<RadioPreset> { it.recommendationPriority }.thenBy { it.id },
    ).snapshot()

    fun presetsForLocale(locale: Locale = Locale.getDefault()): SnapshotList<RadioPreset> {
        val preferred = RadioRegion.regionsForLocale(locale)
        return all.sortedWith(compareBy<RadioPreset> {
            preferred.indexOf(it.region).takeIf { index -> index >= 0 } ?: preferred.size
        }.thenBy { it.name }).snapshot()
    }

    fun matchingPresets(
        frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte, codingRate: UByte,
    ): SnapshotList<RadioPreset> = all.filter {
        abs(it.frequencyMHz - frequencyKHz.toDouble() / 1000.0) < 0.1 &&
            abs(it.bandwidthKHz - bandwidthKHz.toDouble() / 1000.0) < 1.0 &&
            it.spreadingFactor == spreadingFactor && it.codingRate == codingRate
    }.snapshot()

    fun resolvedPreset(
        frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte, codingRate: UByte,
        preferredID: String?, region: RegionSelection?,
    ): RadioPreset? {
        val matches = matchingPresets(frequencyKHz, bandwidthKHz, spreadingFactor, codingRate)
        if (preferredID != null) matches.firstOrNull { it.id == preferredID }?.let { return it }
        if (region != null) {
            val recommendedID = recommended(region)?.id
            matches.firstOrNull { it.id == recommendedID }?.let { return it }
        }
        return matches.singleOrNull()
    }

    fun matchingPreset(
        frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte, codingRate: UByte,
    ): RadioPreset? = resolvedPreset(frequencyKHz, bandwidthKHz, spreadingFactor, codingRate, null, null)

    fun matchingRepeatPreset(frequencyKHz: UInt): RadioPreset? =
        repeatPresets.firstOrNull { it.frequencyKHz == frequencyKHz }

    fun nearestRepeatPreset(frequencyKHz: UInt): RadioPreset? =
        repeatPresets.minByOrNull { abs(it.frequencyKHz.toLong() - frequencyKHz.toLong()) }

    fun recommended(region: RegionSelection): RadioPreset? {
        recommendationOrder.firstOrNull {
            val availability = it.availability
            availability is PresetAvailability.Counties && availability.country == region.countryCode &&
                availability.state == region.administrativeAreaCode && region.countyKey != null &&
                region.countyKey in availability.keys
        }?.let { return it }
        recommendationOrder.firstOrNull {
            val availability = it.availability
            availability is PresetAvailability.SubRegions && availability.country == region.countryCode &&
                region.administrativeAreaCode != null && region.administrativeAreaCode in availability.areas
        }?.let { return it }
        recommendationOrder.firstOrNull {
            val availability = it.availability
            availability is PresetAvailability.Countries && region.countryCode in availability.codes
        }?.let { return it }
        val continent = RegionalAreas.continents[region.countryCode] ?: return null
        return recommendationOrder.firstOrNull { it.availability == PresetAvailability.Continent(continent) }
    }

    fun presets(region: RegionSelection): SnapshotList<RadioPreset> {
        val countryAndBelow = all.filter {
            when (val availability = it.availability) {
                is PresetAvailability.Counties -> availability.country == region.countryCode
                is PresetAvailability.SubRegions -> availability.country == region.countryCode
                is PresetAvailability.Countries -> region.countryCode in availability.codes
                is PresetAvailability.Continent -> false
            }
        }
        if (countryAndBelow.isNotEmpty()) return countryAndBelow.snapshot()
        val continent = RegionalAreas.continents[region.countryCode] ?: return SnapshotList.empty()
        return all.filter { it.availability == PresetAvailability.Continent(continent) }.snapshot()
    }

    fun isSelectable(preset: RadioPreset, region: RegionSelection?): Boolean = when (val available = preset.availability) {
        is PresetAvailability.Counties -> region != null && available.country == region.countryCode &&
            available.state == region.administrativeAreaCode && region.countyKey != null &&
            region.countyKey in available.keys
        is PresetAvailability.SubRegions -> region != null && available.country == region.countryCode &&
            region.administrativeAreaCode != null && region.administrativeAreaCode in available.areas
        is PresetAvailability.Countries, is PresetAvailability.Continent -> true
    }

    fun visiblePresets(
        region: RegionSelection?, activeID: String?, locale: Locale = Locale.getDefault(),
    ): SnapshotList<RadioPreset> {
        val regional = region?.let(::presets)
        val start = if (regional.isNullOrEmpty()) presetsForLocale(locale) else regional
        val result = start.filter { isSelectable(it, region) || it.id == activeID }.toMutableList()
        if (activeID != null && result.none { it.id == activeID }) {
            all.firstOrNull { it.id == activeID }?.let(result::add)
        }
        return result.snapshot()
    }
}
