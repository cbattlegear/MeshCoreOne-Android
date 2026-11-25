// PortedFrom: MC1/Views/Components/MapControlsToolbar.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/View+MapControlButton.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: caller-owned map policy; Material menus never fetch maps or GPS.
package com.meshcoreone.android.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.l10n.generated.AppMapStrings as M
import com.meshcoreone.android.core.model.SnapshotList

data class MapControlChoice(
    val id: String,
    val title: UiText,
    val selected: Boolean,
    val enabled: Boolean = true,
    val disabledReason: UiText? = null,
)

data class MapControlsState(
    val isCenteredOnUser: Boolean,
    val isNorthLocked: Boolean,
    val showLabels: Boolean,
    val clusteringEnabled: Boolean?,
    val styleChoicesInSourceOrder: SnapshotList<MapControlChoice>,
    val filterChoices: SnapshotList<MapControlChoice> = SnapshotList.empty(),
    val filterActive: Boolean = false,
)

data class MapControlsActions(
    val centerOnUser: () -> Unit,
    val selectStyle: (String) -> Unit,
    val selectFilter: (String) -> Unit,
    val setNorthLocked: (Boolean) -> Unit,
    val setShowLabels: (Boolean) -> Unit,
    val setClusteringEnabled: (Boolean) -> Unit,
)

@Composable
fun MapControlsToolbar(
    state: MapControlsState,
    actions: MapControlsActions,
    modifier: Modifier = Modifier,
    additionalActions: @Composable () -> Unit = {},
) {
    var optionsOpen by remember { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(false) }
    Surface(modifier.padding(8.dp), shape = MaterialTheme.shapes.large, tonalElevation = 3.dp) {
        Column {
            MapControlButton(UiText.Resource(M.mapControlsCenterOnMyLocation), MeshSymbol.MAP,
                state.isCenteredOnUser, actions.centerOnUser)
            if (state.filterChoices.isNotEmpty()) {
                HorizontalDivider()
                val filterDescription = uiString(UiText.Resource(M.mapControlsFilterActive))
                MapControlButton(UiText.Resource(M.mapControlsFilter), MeshSymbol.HASHTAG,
                    state.filterActive, { filtersOpen = true },
                    Modifier.semantics { if (state.filterActive) stateDescription = filterDescription })
                DropdownMenu(filtersOpen, { filtersOpen = false }) {
                    for (choice in state.filterChoices) ControlChoice(choice) {
                        filtersOpen = false
                        actions.selectFilter(choice.id)
                    }
                }
            }
            additionalActions()
            HorizontalDivider()
            MapControlButton(UiText.Resource(M.mapControlsMapOptions), MeshSymbol.SETTINGS,
                false, { optionsOpen = true })
            DropdownMenu(optionsOpen, { optionsOpen = false }) {
                for (choice in state.styleChoicesInSourceOrder.asReversed()) ControlChoice(choice) {
                    optionsOpen = false
                    actions.selectStyle(choice.id)
                }
                HorizontalDivider()
                ControlChoice(MapControlChoice("labels", UiText.Resource(M.mapControlsShowLabels), state.showLabels)) {
                    optionsOpen = false
                    actions.setShowLabels(!state.showLabels)
                }
                state.clusteringEnabled?.let { clustering ->
                    ControlChoice(MapControlChoice("clustering", UiText.Resource(M.mapControlsClusterNodes), clustering)) {
                        optionsOpen = false
                        actions.setClusteringEnabled(!clustering)
                    }
                }
                ControlChoice(MapControlChoice("north", UiText.Resource(M.mapControlsLockNorth), state.isNorthLocked)) {
                    optionsOpen = false
                    actions.setNorthLocked(!state.isNorthLocked)
                }
            }
        }
    }
}

@Composable
private fun ControlChoice(choice: MapControlChoice, onClick: () -> Unit) {
    val reason = choice.disabledReason?.let { uiString(it) }
    DropdownMenuItem(
        text = {
            Column {
                Text(uiString(choice.title))
                if (!choice.enabled && reason != null) Text(reason, style = MaterialTheme.typography.bodySmall)
            }
        },
        onClick = onClick,
        enabled = choice.enabled,
        modifier = Modifier.sharedTouchTarget().semantics {
            selected = choice.selected
            if (reason != null) stateDescription = reason
        },
        trailingIcon = if (choice.selected) { { Icon(MeshSymbol.CHECK.vector, null) } } else null,
    )
}

@Composable
fun MapControlButton(
    label: UiText,
    symbol: MeshSymbol,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick, modifier.sharedTouchTarget().semantics { selected = isSelected }) {
        Icon(symbol.vector, uiString(label), tint = MaterialTheme.colorScheme.primary)
    }
}
