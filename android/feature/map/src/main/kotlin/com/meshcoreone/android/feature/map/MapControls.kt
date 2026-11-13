// PortedFrom: MC1/Views/Components/MapControlsToolbar.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: one compact toolbar row (layer menu, filter toggle, map options menu) plus a collapsible filter panel,
// instead of a stack of text chips covering the top of the map.
package com.meshcoreone.android.feature.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.maps.ContactMapType
import com.meshcoreone.android.core.maps.MapFilterHost
import com.meshcoreone.android.core.maps.MapFilterState

private val ControlSize = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)

/**
 * Compact map toolbar. The always-visible row is a single scrollable line; node filters live in a panel that is
 * collapsed by default so the map stays mostly visible.
 */
@Composable
internal fun MapControls(
    filter: MapFilterState,
    onFilter: ((MapFilterState) -> MapFilterState) -> Unit,
    clustering: Boolean,
    onClustering: (Boolean) -> Unit,
    labels: Boolean,
    onLabels: (Boolean) -> Unit,
    listVisible: Boolean,
    onListVisible: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onOffline: () -> Unit,
    canCenterAll: Boolean,
    onCenterAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val filtersActive = filter.differsFromSeed(MapFilterHost.MAIN_MAP)
    Card(
        modifier.padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LayerMenu()
                FilterChip(
                    selected = filtersOpen || filtersActive,
                    onClick = { filtersOpen = !filtersOpen },
                    modifier = ControlSize.testTag("map:filters-toggle"),
                    label = {
                        Text(stringResource(
                            if (filtersActive) R.string.l10n_app_map_map_controls_filteractive
                            else R.string.l10n_app_map_map_controls_filter,
                        ))
                    },
                )
                OptionsMenu(listVisible, onListVisible, onRefresh, onOffline, canCenterAll, onCenterAll)
            }
            AnimatedVisibility(filtersOpen) {
                FilterPanel(filter, onFilter, clustering, onClustering, labels, onLabels)
            }
        }
    }
}

/** Layer picker. Only the OpenFreeMap standard style is available; the others stay listed but disabled. */
@Composable
private fun LayerMenu() {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.l10n_app_map_map_style_accessibilitylabel)
    Box {
        AssistChip(
            onClick = { open = true },
            modifier = ControlSize.testTag("map:layer-menu").semantics { contentDescription = label },
            label = { Text(stringResource(R.string.l10n_app_map_map_style_standard) + " ▾") },
        )
        DropdownMenu(open, { open = false }) {
            LayerItem(R.string.l10n_app_map_map_style_standard, selected = true, enabled = true) { open = false }
            LayerItem(R.string.l10n_app_map_map_style_satellite, selected = false, enabled = false) { open = false }
            LayerItem(R.string.l10n_app_map_map_style_topo, selected = false, enabled = false) { open = false }
        }
    }
}

@Composable
private fun LayerItem(titleRes: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(titleRes)) },
        enabled = enabled,
        onClick = onClick,
        modifier = ControlSize.semantics { role = Role.RadioButton; this.selected = selected },
        trailingIcon = if (selected) ({ Text("✓") }) else null,
    )
}

@Composable
private fun OptionsMenu(
    listVisible: Boolean,
    onListVisible: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onOffline: () -> Unit,
    canCenterAll: Boolean,
    onCenterAll: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            modifier = ControlSize.testTag("map:options-menu"),
            label = { Text(stringResource(R.string.l10n_app_map_map_controls_mapoptions)) },
        )
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.l10n_app_map_map_controls_centerall)) },
                enabled = canCenterAll,
                onClick = { open = false; onCenterAll() },
                modifier = ControlSize,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.l10n_app_map_map_controls_refresh)) },
                onClick = { open = false; onRefresh() },
                modifier = ControlSize,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.l10n_app_settings_offlinemaps_title)) },
                onClick = { open = false; onOffline() },
                modifier = ControlSize,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.l10n_app_contacts_contacts_trace_mode_list)) },
                onClick = { open = false; onListVisible(!listVisible) },
                trailingIcon = if (listVisible) ({ Text("✓") }) else null,
                modifier = ControlSize.semantics { selected = listVisible },
            )
        }
    }
}

@Composable
private fun FilterPanel(
    filter: MapFilterState,
    onFilter: ((MapFilterState) -> MapFilterState) -> Unit,
    clustering: Boolean,
    onClustering: (Boolean) -> Unit,
    labels: Boolean,
    onLabels: (Boolean) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(
            selected = filter.favoritesOnly,
            modifier = ControlSize,
            onClick = { onFilter { it.setFavoritesOnly(!it.favoritesOnly) } },
            label = { Text(stringResource(R.string.l10n_app_contacts_contacts_pathedit_filter_favorites)) },
        )
        FilterChip(
            selected = filter.showDiscovered,
            modifier = ControlSize,
            enabled = !filter.favoritesOnly,
            onClick = { onFilter { it.setShowDiscovered(!it.showDiscovered) } },
            label = { Text(stringResource(R.string.l10n_app_map_map_callout_discovered)) },
        )
        FilterChip(
            selected = clustering,
            modifier = ControlSize,
            onClick = { onClustering(!clustering) },
            label = { Text(stringResource(R.string.l10n_app_map_map_controls_clusternodes)) },
        )
        FilterChip(
            selected = labels,
            modifier = ControlSize,
            onClick = { onLabels(!labels) },
            label = { Text(stringResource(R.string.l10n_app_map_map_controls_showlabels)) },
        )
        ContactMapType.entries.forEach { type ->
            FilterChip(
                selected = filter.allows(type),
                modifier = ControlSize,
                enabled = !filter.favoritesOnly,
                onClick = { onFilter { it.setContactType(type, !it.allows(type), MapFilterHost.MAIN_MAP) } },
                label = {
                    Text(stringResource(when (type) {
                        ContactMapType.CHAT -> R.string.l10n_app_map_map_nodekind_chatcontact
                        ContactMapType.REPEATER -> R.string.l10n_app_map_map_nodekind_repeater
                        ContactMapType.ROOM -> R.string.l10n_app_map_map_nodekind_room
                    }))
                },
            )
        }
    }
}
