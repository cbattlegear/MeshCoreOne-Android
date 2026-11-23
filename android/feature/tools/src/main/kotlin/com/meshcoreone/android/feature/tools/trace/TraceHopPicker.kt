// PortedFrom: MC1/Views/PathEditing/AddHopPickerView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.trace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.model.applicationBytesFromHex
import com.meshcoreone.android.core.model.RepeaterResolvable
import com.meshcoreone.android.core.ui.hoppicker.HopPickerFilter
import com.meshcoreone.android.core.ui.hoppicker.HopNodeMatching
import com.meshcoreone.android.core.ui.hoppicker.PickerNode
import com.meshcoreone.android.core.ui.hoppicker.buildHopPickerResults

@Composable
internal fun TraceHopPicker(
    state: TracePathState,
    holder: TracePathStateHolder,
    canEdit: () -> Boolean,
    onDismiss: () -> Unit,
) {
    val recentHex = rememberSaveable { ArrayList(state.recentPublicKeys.map { it.hexString }) }
    val recent = recentHex.mapNotNull(::applicationBytesFromHex)
    var filterName by rememberSaveable { mutableStateOf(HopPickerFilter.ALL.name) }
    var query by rememberSaveable { mutableStateOf("") }
    val filter = HopPickerFilter.valueOf(filterName)
    val locale = LocalConfiguration.current.locales[0]
    val results = buildHopPickerResults(
        state.availableRepeaters, state.availableRooms, state.discoveredRepeaters, recent,
        filter, query, locale, includeRoomsInRecent = true,
    )
    val sections = listOf(
        R.string.l10n_app_contacts_contacts_pathedit_sections_recent to results.recent,
        R.string.l10n_app_contacts_contacts_pathedit_sections_favorites to results.favorites,
        R.string.l10n_app_contacts_contacts_pathedit_sections_contacts to results.contacts,
        R.string.l10n_app_contacts_contacts_pathedit_sections_discovered to results.discovered,
        R.string.l10n_app_contacts_contacts_pathedit_sections_rooms to results.rooms,
    )
    val labels = mapOf(
        HopPickerFilter.ALL to C.contactsPathEditFilterAll,
        HopPickerFilter.FAVORITES to C.contactsPathEditFilterFavorites,
        HopPickerFilter.RECENT to C.contactsPathEditFilterRecent,
        HopPickerFilter.DISCOVERED to C.contactsPathEditFilterDiscovered,
    )
    val resources = LocalContext.current.resources
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(C.contactsPathEditAddHop)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(C.contactsCommonDone)) } },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                item {
                    Text(
                        C.contactsPathEditPositionAppend(resources, state.outboundPath.size + 1),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text(stringResource(C.contactsPathEditSearchPrompt)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HopPickerFilter.entries.forEach { option ->
                            FilterChip(
                                selected = filter == option, onClick = { filterName = option.name },
                                label = { Text(stringResource(labels.getValue(option))) },
                                modifier = Modifier.sizeIn(minHeight = 48.dp),
                            )
                        }
                    }
                }
                if (results.isEmpty) {
                    item {
                        Text(stringResource(when (filter) {
                            HopPickerFilter.RECENT -> R.string.l10n_app_contacts_contacts_pathedit_norecent_title
                            HopPickerFilter.FAVORITES -> R.string.l10n_app_contacts_contacts_pathedit_nofavorites_title
                            else -> R.string.l10n_app_contacts_contacts_pathedit_norepeaters_title
                        }))
                        if (query.isNotEmpty()) {
                            val roomsMatch = filter != HopPickerFilter.ALL && state.availableRooms.any {
                                HopNodeMatching.matches(PickerNode.Contact(it), query)
                            }
                            Text(stringResource(
                                if (roomsMatch) C.contactsPathEditSearchNoMatchesDescriptionWithRoomsHint
                                else C.contactsPathEditSearchNoMatchesDescription,
                            ))
                        }
                    }
                }
                sections.forEach { (title, nodes) ->
                    if (nodes.isNotEmpty()) {
                        item(key = title) {
                            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                        }
                        items(nodes, key = { "$title:${it.underlying.publicKey.hexString}" }) { node ->
                            val label = C.contactsPathEditAddToPathAsHop(resources, node.displayName, state.outboundPath.size + 1)
                            val add = {
                                val live = holder.state.value
                                val currentNode: RepeaterResolvable? = when (node) {
                                    is PickerNode.Contact -> live.availableNodes.firstOrNull { it.publicKey == node.underlying.publicKey }
                                    is PickerNode.Discovered -> live.discoveredRepeaters.firstOrNull { it.publicKey == node.underlying.publicKey }
                                }
                                if (canEdit() && currentNode != null) {
                                    holder.appendHop(currentNode)
                                }
                            }
                            TextButton(
                                onClick = add, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).semantics {
                                    contentDescription = label
                                    onClick { add(); true }
                                },
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(node.displayName)
                                    Text(node.publicKeyHex, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}
