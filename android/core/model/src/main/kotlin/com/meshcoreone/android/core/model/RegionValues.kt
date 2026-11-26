// PortedFrom: MC1Services/Sources/MC1Services/Models/RegionScopeSemantics.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/RegionSelection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.parser.RegionMatchResult
import java.math.BigInteger
import java.text.Collator
import java.util.Locale

data class RegionStorageFields(val regionScope: String?, val regionScopeMatches: SnapshotList<String>)

object RegionScopeSemantics {
    const val CHIP_NAME_SEPARATOR = " / "

    fun storageFields(match: RegionMatchResult, locale: Locale = Locale.getDefault()): RegionStorageFields {
        val names = when (match) {
            RegionMatchResult.None -> emptyList()
            is RegionMatchResult.Unique -> listOf(match.name)
            is RegionMatchResult.Ambiguous -> match.names
        }
        val filtered = filteredSortedNames(names, locale)
        return RegionStorageFields(filtered.singleOrNull(), filtered)
    }

    fun coalesce(scope: String?, matches: Iterable<String>, locale: Locale = Locale.getDefault()): RegionMatchResult {
        val filtered = filteredSortedNames(matches, locale)
        return when (filtered.size) {
            0 -> scope?.trim()?.takeIf { it.isNotEmpty() }?.let(RegionMatchResult::Unique) ?: RegionMatchResult.None
            1 -> RegionMatchResult.Unique(filtered[0])
            else -> RegionMatchResult.Ambiguous(filtered)
        }
    }

    fun chipLabel(match: RegionMatchResult): String? = when (match) {
        RegionMatchResult.None -> null
        is RegionMatchResult.Unique -> match.name
        is RegionMatchResult.Ambiguous -> match.names.joinToString(CHIP_NAME_SEPARATOR)
    }

    fun matchNames(match: RegionMatchResult): SnapshotList<String> = when (match) {
        RegionMatchResult.None -> SnapshotList.empty()
        is RegionMatchResult.Unique -> SnapshotList.of(match.name)
        is RegionMatchResult.Ambiguous -> match.names.snapshot()
    }

    private fun filteredSortedNames(names: Iterable<String>, locale: Locale): SnapshotList<String> {
        val collator = Collator.getInstance(locale).apply {
            strength = Collator.PRIMARY
            decomposition = Collator.CANONICAL_DECOMPOSITION
        }
        val tokens = Regex("[0-9]+|[^0-9]+")
        val comparator = Comparator<String> { first, second ->
            val left = tokens.findAll(first).map { it.value }.toList()
            val right = tokens.findAll(second).map { it.value }.toList()
            var result = 0
            for (index in 0 until minOf(left.size, right.size)) {
                val a = left[index]
                val b = right[index]
                result = if (a[0].isDigit() && b[0].isDigit()) BigInteger(a).compareTo(BigInteger(b))
                else collator.compare(a, b)
                if (result != 0) break
            }
            if (result == 0) left.size.compareTo(right.size) else result
        }
        return names.map(String::trim).filter(String::isNotEmpty).distinct().sortedWith(comparator).snapshot()
    }
}

data class RegionSelection(
    val countryCode: String,
    val source: Source,
    val administrativeAreaCode: String? = null,
    val countyKey: String? = null,
) {
    enum class Source(val rawValue: String) { LOCATION("location"), MANUAL("manual") }
    companion object {
        fun afterChoosingCountry(newCountry: String, current: RegionSelection?): RegionSelection? =
            if (current?.countryCode == newCountry) null else RegionSelection(newCountry, Source.MANUAL)
        fun afterChoosingSubdivision(newSubdivision: String, current: RegionSelection?): RegionSelection? =
            if (current == null || current.administrativeAreaCode == newSubdivision) null
            else RegionSelection(current.countryCode, Source.MANUAL, newSubdivision)
    }
}
