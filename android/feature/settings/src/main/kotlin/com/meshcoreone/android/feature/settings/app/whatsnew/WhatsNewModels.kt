// PortedFrom: MC1/Models/WhatsNew/WhatsNewVersion.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Models/WhatsNew/WhatsNewItem.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Models/WhatsNew/WhatsNewRelease.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Models/WhatsNew/WhatsNewCatalog.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: SF Symbols become MeshSymbol glyphs; titles/descriptions are string-resource ids from the l10n pipeline.
package com.meshcoreone.android.feature.settings.app.whatsnew

import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.l10n.generated.AppWhatsNewStrings

/**
 * The leading `major.minor` of a marketing version, ignoring patch. Comparison is lexicographic on
 * `(major, minor)`, so "a 0.1 bump or more" is just `>`.
 */
data class WhatsNewVersion(val major: Int, val minor: Int) : Comparable<WhatsNewVersion> {
    override fun compareTo(other: WhatsNewVersion): Int =
        compareValuesBy(this, other, WhatsNewVersion::major, WhatsNewVersion::minor)

    companion object {
        /**
         * Parses the leading `major.minor`; `null` for anything unparseable (`"unknown"`, `"2"`, `"2.0-beta"`,
         * `"1.0 (123)"`) so the sheet fails closed. Empty components are dropped like Swift's `split(separator:)`.
         */
        fun parse(marketingVersion: String): WhatsNewVersion? {
            val components = marketingVersion.split('.').filter { it.isNotEmpty() }
            if (components.size < 2) return null
            val major = asciiInt(components[0]) ?: return null
            val minor = asciiInt(components[1]) ?: return null
            return WhatsNewVersion(major, minor)
        }

        /** `Int(String)` semantics: optional sign then ASCII digits only (no Unicode digits, no whitespace). */
        private fun asciiInt(text: String): Int? {
            val digits = if (text.startsWith('+') || text.startsWith('-')) text.drop(1) else text
            if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
            return text.toIntOrNull()
        }
    }
}

/** One feature row: a glyph plus localized title and description string resources. */
data class WhatsNewItem(val symbol: MeshSymbol, val titleResource: Int, val descriptionResource: Int)

data class WhatsNewRelease(val version: WhatsNewVersion, val items: List<WhatsNewItem>, val releaseNotesUrl: String)

/** Per-release notes keyed by the `major.minor` they belong to; each presents once on upgrade. */
object WhatsNewCatalog {
    val releases: List<WhatsNewRelease> = listOf(
        WhatsNewRelease(
            version = WhatsNewVersion(1, 5),
            items = listOf(
                WhatsNewItem(MeshSymbol.SIGNAL, AppWhatsNewStrings.whatsNewPathDetailsTitle, AppWhatsNewStrings.whatsNewPathDetailsDescription),
                WhatsNewItem(MeshSymbol.MESSAGES, AppWhatsNewStrings.whatsNewWhoReactedTitle, AppWhatsNewStrings.whatsNewWhoReactedDescription),
                WhatsNewItem(MeshSymbol.SYNC, AppWhatsNewStrings.whatsNewFasterChatLoadingTitle, AppWhatsNewStrings.whatsNewFasterChatLoadingDescription),
            ),
            releaseNotesUrl = "https://github.com/Avi0n/MeshCoreOne/releases/tag/v1.5.0",
        ),
    )
}
