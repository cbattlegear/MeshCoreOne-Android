// PortedFrom: MC1/Views/Contacts/DiscoveryView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Contacts/DiscoverSegmentPicker.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.nodes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.feature.nodes.discovery.DiscoveryStateHolder
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.model.DiscoverSegment
import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import com.meshcoreone.android.feature.nodes.model.localizedNameRes
import kotlinx.coroutines.launch

@Composable
internal fun DiscoveryScreen(
    dependencies: NodesFeatureDependencies,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val holder = remember(dependencies) { DiscoveryStateHolder(dependencies, scope) }
    val state by holder.state.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    var segment by remember { mutableStateOf(DiscoverSegment.ALL) }
    var sort by remember { mutableStateOf(NodeSortOrder.LAST_HEARD) }
    var menu by remember { mutableStateOf(false) }
    var clearConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(holder) { holder.loadDiscoveredNodes() }
    LaunchedEffect(search, segment, sort, state.discoveredNodes) {
        holder.updateVisibleNodes(search, segment, sort, null)
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(C.contactsDiscoverySearchPrompt)) },
                singleLine = true,
            )
            Box {
                Button(onClick = { menu = true }) { Text(stringResource(C.contactsListSort)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    NodeSortOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            text = { Text(stringResource(order.titleRes)) },
                            onClick = { sort = order; menu = false },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(C.contactsDiscoveryClear)) },
                        enabled = state.discoveredNodes.isNotEmpty(),
                        onClick = { menu = false; clearConfirmation = true },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DiscoverSegment.entries.forEach { item ->
                FilterChip(
                    selected = segment == item,
                    onClick = { segment = item },
                    label = { Text(stringResource(item.titleRes)) },
                    modifier = Modifier.heightIn(min = 48.dp).weight(1f),
                )
            }
        }
        when {
            !state.hasLoadedOnce || state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.visibleNodes.isEmpty() -> DiscoveryEmpty(search, Modifier.fillMaxSize())
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.visibleNodes, key = { it.id }) { node ->
                    DiscoveryRow(
                        node = node,
                        added = holder.isAdded(node),
                        adding = state.addingNodeId == node.id,
                        onAdd = { scope.launch { holder.addNode(node) } },
                        onDelete = { scope.launch { holder.deleteDiscoveredNode(node) } },
                    )
                }
            }
        }
    }
    if (clearConfirmation) {
        AlertDialog(
            onDismissRequest = { clearConfirmation = false },
            confirmButton = {
                TextButton(onClick = {
                    clearConfirmation = false
                    scope.launch { holder.clearAllAndAnnounce() }
                }) { Text(stringResource(C.contactsDiscoveryClearConfirm)) }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmation = false }) { Text(stringResource(C.contactsCommonCancel)) }
            },
            title = { Text(stringResource(C.contactsDiscoveryClearTitle)) },
            text = { Text(stringResource(C.contactsDiscoveryClearMessage)) },
        )
    }
    state.errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = holder::clearError,
            confirmButton = { TextButton(onClick = holder::clearError) { Text(stringResource(C.contactsCommonOk)) } },
            title = { Text(stringResource(C.contactsCommonError)) },
            text = { Text(nodesString(error)) },
        )
    }
}

@Composable
private fun DiscoveryRow(
    node: DiscoveredNodeDTO,
    added: Boolean,
    adding: Boolean,
    onAdd: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(node.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    node.publicKey.uppercaseHexString(),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${stringResource(node.nodeType.localizedNameRes())} • ${discoveryRoute(node)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!node.isFloodRouted && node.pathNodesHex.isNotEmpty()) {
                    Text(
                        DiscoveryStateHolder.formattedPath(node.pathNodesHex),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (added) {
                Text(stringResource(C.contactsDiscoveryAdded), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Button(onClick = onAdd, enabled = !adding, modifier = Modifier.heightIn(min = 48.dp)) {
                    if (adding) CircularProgressIndicator(Modifier.sizeIn(maxWidth = 20.dp, maxHeight = 20.dp))
                    else Text(stringResource(C.contactsDiscoveryAdd))
                }
            }
            TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(C.contactsDiscoveryRemove))
            }
        }
    }
}

@Composable
private fun discoveryRoute(node: DiscoveredNodeDTO): String = when {
    !node.isFloodRouted && node.pathHopCount == 0L -> stringResource(C.contactsRouteDirect)
    node.displayedHopCount != null -> stringResource(R.string.l10n_app_contacts_contacts_route_hops, node.displayedHopCount!!)
    else -> stringResource(C.contactsRouteFlood)
}

@Composable
private fun DiscoveryEmpty(search: String, modifier: Modifier) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(if (search.isEmpty()) C.contactsDiscoveryEmptyTitle else C.contactsDiscoveryEmptySearchTitle),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                if (search.isEmpty()) stringResource(C.contactsDiscoveryEmptyDescription)
                else stringResource(R.string.l10n_app_contacts_contacts_discovery_empty_search_description, search),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
