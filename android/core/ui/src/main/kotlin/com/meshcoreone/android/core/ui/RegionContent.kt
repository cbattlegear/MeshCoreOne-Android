// PortedFrom: MC1/Views/Components/RegionManagementView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Components/RegionDiscoveryResultsView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.ui

import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import android.icu.text.StringSearch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as C
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.SnapshotSet
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.snapshotSet
import java.util.Locale

data class RegionManagementState(
    val knownRegions: SnapshotList<String>,
    val searchText: String = "",
    val isDiscovering: Boolean = false,
    val discoveryMessage: UiText? = null,
    val addDialogVisible: Boolean = false,
    val newRegionName: String = "",
    val validationError: RegionValidationError? = null,
) {
    val searchEnabled: Boolean get() = knownRegions.size >= 15
}

data class RegionManagementActions(
    val searchChanged: (String) -> Unit,
    val removeRegion: (String) -> Unit,
    val discover: () -> Unit,
    val openAdd: () -> Unit,
    val newNameChanged: (String) -> Unit,
    val validationChanged: (RegionValidationError?) -> Unit,
    val addRegion: (String) -> Unit,
    val dismissAdd: () -> Unit,
)

fun filteredRegions(regions: List<String>, search: String, locale: Locale): SnapshotList<String> {
    val collator = Collator.getInstance(locale)
    collator.strength = Collator.TERTIARY
    if (collator is RuleBasedCollator) collator.numericCollation = true
    val sorted = regions.sortedWith { left, right ->
        val comparison = collator.compare(left, right)
        if (comparison != 0) comparison else left.compareTo(right)
    }
    if (search.isEmpty()) return sorted.snapshot()
    val searchCollator = Collator.getInstance(locale)
    require(searchCollator is RuleBasedCollator) { "The platform locale has no substring collation rules" }
    searchCollator.strength = Collator.PRIMARY
    searchCollator.numericCollation = false
    return sorted.filter { region ->
        StringSearch(search, java.text.StringCharacterIterator(region), searchCollator).first() != StringSearch.DONE
    }.snapshot()
}

fun regionValidationCopy(error: RegionValidationError): UiText? = when (error) {
    RegionValidationError.Empty -> null
    RegionValidationError.InvalidCharacters -> UiText.Resource(C.chatsChannelInfoRegionInvalidName)
    is RegionValidationError.TooLong -> generatedText("Chats.ChannelInfo.Region.nameTooLong") {
        C.chatsChannelInfoRegionNameTooLong(it, error.maxBytes)
    }
    RegionValidationError.Duplicate -> UiText.Resource(C.chatsChannelInfoRegionDuplicate)
}

@Composable
fun RegionManagementView(state: RegionManagementState, actions: RegionManagementActions, modifier: Modifier = Modifier) {
    val resources = LocalContext.current.resources
    val regions = filteredRegions(state.knownRegions, state.searchText, resourceLocale(resources))
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiString(UiText.Resource(C.chatsChannelInfoRegionManage)), Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge)
        if (state.searchEnabled) OutlinedTextField(
            state.searchText, actions.searchChanged, Modifier.fillMaxWidth(),
            label = { Text(uiString(UiText.Resource(AppContactsStrings.contactsListSearchPrompt))) },
        )
        if (state.knownRegions.isEmpty()) {
            Text(uiString(UiText.Resource(C.chatsChannelInfoRegionNoRegions)), style = MaterialTheme.typography.titleMedium)
            Text(uiString(UiText.Resource(C.chatsChannelInfoRegionNoRegionsDescription)))
        } else {
            for (region in regions) {
                val background = sharedPaintedSurface()
                Surface(shape = MaterialTheme.shapes.medium, color = background) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                            Text(region)
                            if (region.isPrivateRegion) Text(uiString(UiText.Resource(C.chatsChannelInfoRegionPrivate)),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { actions.removeRegion(region) }, modifier = Modifier.sharedTouchTarget()) {
                            Text(uiString(UiText.Resource(AppContactsStrings.contactsCommonDelete)),
                                color = sharedErrorForeground(background))
                        }
                    }
                }
            }
        }
        if (state.isDiscovering) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text(uiString(UiText.Resource(C.chatsChannelInfoRegionDiscovering)))
        } else state.discoveryMessage?.let { Text(uiString(it), style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = actions.discover, enabled = !state.isDiscovering, modifier = Modifier.sharedTouchTarget()) {
            Icon(MeshSymbol.RADIO.vector, null)
            Text(uiString(UiText.Resource(C.chatsChannelInfoRegionDiscover)))
        }
        TextButton(onClick = actions.openAdd, modifier = Modifier.sharedTouchTarget()) {
            Text(uiString(UiText.Resource(C.chatsChannelInfoRegionAddManually)))
        }
        Text(uiString(UiText.Resource(C.chatsChannelInfoRegionInvalidName)), style = MaterialTheme.typography.bodySmall)
    }
    if (state.addDialogVisible) RegionAddDialog(state, actions)
}

@Composable
private fun RegionAddDialog(state: RegionManagementState, actions: RegionManagementActions) {
    val focus = remember { FocusRequester() }
    val latestState by rememberUpdatedState(state)
    AlertDialog(
        onDismissRequest = { actions.validationChanged(null); actions.dismissAdd() },
        title = { Text(uiString(UiText.Resource(C.chatsChannelInfoRegionAddRegionTitle))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val windowFocused = LocalWindowInfo.current.isWindowFocused
                val validationCopy = state.validationError?.let(::regionValidationCopy)
                OutlinedTextField(state.newRegionName, actions.newNameChanged,
                    Modifier.fillMaxWidth().focusRequester(focus),
                    label = { Text(uiString(UiText.Resource(C.chatsChannelInfoRegionAddRegionPlaceholder))) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    isError = validationCopy != null,
                    supportingText = validationCopy?.let { copy -> { Text(uiString(copy)) } },
                )
                LaunchedEffect(focus, windowFocused) { if (windowFocused) focus.requestFocus() }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val current = latestState
                val error = RegionNameValidator.validate(current.newRegionName, current.knownRegions)
                actions.validationChanged(error)
                if (error == null) {
                    actions.addRegion(RegionNameValidator.normalized(current.newRegionName))
                    actions.dismissAdd()
                }
            }, modifier = Modifier.sharedTouchTarget()) {
                Text(uiString(UiText.Resource(C.chatsChannelInfoRegionAddSelected)))
            }
        },
        dismissButton = {
            TextButton(onClick = { actions.validationChanged(null); actions.dismissAdd() }, modifier = Modifier.sharedTouchTarget()) {
                Text(uiString(UiText.Resource(C.chatsCommonCancel)))
            }
        },
    )
}

data class RegionDiscoveryState(val sortedRegions: SnapshotList<String>, val selectedRegions: SnapshotSet<String>) {
    fun toggled(region: String): RegionDiscoveryState {
        val selected = selectedRegions.toMutableSet()
        if (!selected.add(region)) selected.remove(region)
        return copy(selectedRegions = selected.snapshotSet())
    }
    val selectedInDisplayOrder: SnapshotList<String>
        get() = sortedRegions.filter { it in selectedRegions }.distinct().snapshot()

    companion object {
        fun from(regions: List<String>): RegionDiscoveryState =
            RegionDiscoveryState(regions.sorted().snapshot(), regions.snapshotSet())
    }
}

@Composable
fun RegionDiscoveryResultsView(
    state: RegionDiscoveryState,
    onSelectionChange: (RegionDiscoveryState) -> Unit,
    onAdd: (SnapshotList<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(uiString(UiText.Resource(C.chatsChannelInfoRegionDiscover)), Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge)
        for (region in state.sortedRegions) {
            Row(Modifier.fillMaxWidth().sharedTouchTarget().semantics { selected = region in state.selectedRegions }) {
                Checkbox(region in state.selectedRegions, { onSelectionChange(state.toggled(region)) })
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Text(region)
                    if (region.isPrivateRegion) Text(uiString(UiText.Resource(C.chatsChannelInfoRegionPrivate)),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        TextButton(onClick = { onAdd(state.selectedInDisplayOrder); onDismiss() },
            enabled = state.selectedRegions.isNotEmpty(), modifier = Modifier.sharedTouchTarget()) {
            Text(uiString(UiText.Resource(C.chatsChannelInfoRegionAddSelected)))
        }
    }
}
