// PortedFrom: MC1/Views/PathEditing/HopPickerSource.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/PathEditing/AddHopPickerView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.nodes.path

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.RepeaterResolvable
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.ui.hoppicker.HopPickerFilter
import com.meshcoreone.android.core.ui.hoppicker.HopPickerResults
import com.meshcoreone.android.core.ui.hoppicker.buildHopPickerResults
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import com.meshcoreone.android.core.ui.hoppicker.SwiftText
import java.util.Locale

/**
 * Data source for the shared Add-Hop picker: the contact path editor (hop-capped) and the trace path
 * builder (uncapped) both drive the same picker through it.
 */
interface HopPickerSource {
    val availableRepeaters: List<ContactDTO>
    val availableRooms: List<ContactDTO>
    val discoveredRepeaters: List<DiscoveredNodeDTO>
    val recentPublicKeys: List<Bytes>

    /** Hops currently in the path being built. */
    val currentHopCount: Int

    /** Maximum hops the path can hold, or null when unlimited (trace). */
    val hopLimit: Int?

    /** No further hops can be added; a path with no limit is never full. */
    val isPathFull: Boolean get() = hopLimit?.let { currentHopCount >= it } ?: false

    fun appendHop(node: RepeaterResolvable)
    fun addCodes(input: String): CodeInputResult
    fun classifyCodes(input: String): List<HopCodeClassification>

    /** Adopt a hash size inferred from a bulk paste; fixed-width sources ignore it. */
    fun adoptHashSize(forPastedCodes: String) = Unit
}

/** The five picker sections, built once per render so rows and the empty state agree. */
typealias PickerResults = HopPickerResults

/** Non-visual logic of `AddHopPickerView`. */
object AddHopPicker {
    /** A comma in the query switches the picker to bulk code entry. */
    fun isBulkMode(searchText: String): Boolean = "," in SwiftText.characters(searchText)

    /** Shared subtitle and row-tap announcement text. */
    fun bannerText(source: HopPickerSource, intent: AddHopIntent): NodesMessage {
        if (source.isPathFull) return NodesMessage.res(R.string.l10n_app_contacts_contacts_pathedit_maxhops_reached)
        return when (intent) {
            AddHopIntent.APPEND -> NodesMessage.res(R.string.l10n_app_contacts_contacts_pathedit_positionappend, source.currentHopCount + 1)
        }
    }

    /** Bulk-add button label: the addable codes, or the empty label when none. */
    fun bulkAddLabel(classifications: List<HopCodeClassification>): NodesMessage {
        val addable = classifications.filter { it.willBeAdded }.map { it.code }
        return if (addable.isEmpty()) NodesMessage.res(R.string.l10n_app_contacts_contacts_pathedit_bulkadd_empty)
        else NodesMessage.res(R.string.l10n_app_contacts_contacts_pathedit_bulkadd_action, addable.joinToString(", "))
    }

    /** Empty-state copy for the active filter. */
    fun emptyStateTitle(filter: AddHopFilter): Int = when (filter) {
        AddHopFilter.FAVORITES -> R.string.l10n_app_contacts_contacts_pathedit_nofavorites_title
        AddHopFilter.RECENT -> R.string.l10n_app_contacts_contacts_pathedit_norecent_title
        AddHopFilter.ALL, AddHopFilter.DISCOVERED -> R.string.l10n_app_contacts_contacts_pathedit_norepeaters_title
    }

    /**
     * Section results for [filter] and [searchText]. [sessionRecentKeys] is the recents snapshot taken
     * at presentation so a just-added row keeps its place. Cross-section de-duplication applies only in
     * [AddHopFilter.ALL], where every section is visible.
     */
    fun buildResults(
        source: HopPickerSource,
        filter: AddHopFilter,
        sessionRecentKeys: List<Bytes>,
        searchText: String,
        locale: Locale,
    ): PickerResults {
        return buildHopPickerResults(
            source.availableRepeaters, source.availableRooms, source.discoveredRepeaters,
            sessionRecentKeys, HopPickerFilter.valueOf(filter.name), searchText, locale,
        )
    }
}
