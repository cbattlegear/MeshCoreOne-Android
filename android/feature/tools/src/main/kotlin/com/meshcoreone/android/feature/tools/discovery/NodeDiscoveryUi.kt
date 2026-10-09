// PortedFrom: MC1/Views/Tools/NodeDiscovery/NodeDiscoveryView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/NodeDiscovery/NodeDiscoveryRowView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings as T
import com.meshcoreone.android.feature.tools.trace.SystemTraceTimeSource
import com.meshcoreone.android.feature.tools.trace.ToolDisconnected
import com.meshcoreone.android.feature.tools.trace.TraceDiagnostics
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings

@Composable
private fun rememberDiscoveryStrings(): NodeDiscoveryStrings {
    val resources = LocalContext.current.resources
    val unknown = stringResource(T.toolsNodeDiscoveryUnknownNode)
    return remember(resources, unknown) {
        object : NodeDiscoveryStrings {
            override fun filterTitle(filter: NodeDiscoveryFilter) = resources.getString(
                if (filter == NodeDiscoveryFilter.REPEATERS) T.toolsNodeDiscoveryRepeaters else T.toolsNodeDiscoverySensors,
            )
            override fun notConnectedDescription(filterTitle: String) =
                AppToolsStrings.toolsNodeDiscoveryNotConnectedDescription(resources, filterTitle)
            override val unknownNode = unknown
            override fun nodeListFull(maxContacts: Int) = "Node list full ($maxContacts)"
            override val nodeListFullSimple = "Node list full"
            override fun userFacingMessage(error: Exception) = error.message ?: error.javaClass.simpleName
        }
    }
}

object NodeDiscoveryRoute {
    data class Dependencies(
        val feature: NodeDiscoveryFeatureDependencies,
        val diagnostics: TraceDiagnostics,
    )

    @Composable
    operator fun invoke(dependencies: Dependencies?) {
        if (dependencies == null || dependencies.feature.session() == null) {
            ToolDisconnected(stringResource(T.toolsNodeDiscovery))
            return
        }
        val scope = rememberCoroutineScope()
        val strings = rememberDiscoveryStrings()
        val holder = remember(dependencies.feature) {
            NodeDiscoveryStateHolder(scope, SystemTraceTimeSource, strings, dependencies.diagnostics)
                .also { it.configure(dependencies.feature) }
        }

        DisposableEffect(holder) { onDispose(holder::stopScan) }
        val state by holder.state.collectAsStateWithLifecycle()
        NodeDiscoveryContent(state, holder)
    }
}

@Composable
private fun NodeDiscoveryContent(state: NodeDiscoveryState, holder: NodeDiscoveryStateHolder) {
    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(T.toolsNodeDiscovery),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(20.dp).semantics { heading() },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NodeDiscoveryFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { holder.stopScan(); holder.setFilter(filter) },
                    enabled = !state.isScanning,
                    label = {
                        Text(stringResource(if (filter == NodeDiscoveryFilter.REPEATERS) T.toolsNodeDiscoveryRepeaters else T.toolsNodeDiscoverySensors))
                    },
                )
            }
            FilterChip(
                selected = state.sortOrder == NodeDiscoverySortOrder.NAME,
                onClick = {
                    holder.setSortOrder(
                        if (state.sortOrder == NodeDiscoverySortOrder.NAME) NodeDiscoverySortOrder.SNR
                        else NodeDiscoverySortOrder.NAME,
                    )
                },
                label = { Text(stringResource(T.toolsNodeDiscoverySortMenu)) },
            )
        }
        val results = state.sortedResults()
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (results.isEmpty() && !state.isScanning) {
                item {
                    Text(
                        stringResource(
                            com.meshcoreone.android.core.l10n.R.string.l10n_app_tools_tools_nodediscovery_scanpromptdescription,
                            filterName(state.filter),
                        ),
                        Modifier.padding(vertical = 32.dp),
                    )
                }
            }
            items(results, key = { "${it.scanFilter}:${it.publicKey}" }) { result ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(result.name, style = MaterialTheme.typography.titleMedium)
                            Text("${"%.1f".format(result.snr)} dB · ${result.rssi} dBm")
                        }
                        Button(
                            onClick = { holder.addNode(result) },
                            enabled = !state.isAdded(result.publicKey) && state.addingPublicKey != result.publicKey,
                        ) {
                            if (state.addingPublicKey == result.publicKey) CircularProgressIndicator()
                            else Text(if (state.isAdded(result.publicKey)) "Added" else "+")
                        }
                    }
                }
            }
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        Button(
            onClick = { if (state.isScanning) holder.stopScan() else holder.scan() },
            modifier = Modifier.fillMaxWidth().padding(16.dp).sizeIn(minHeight = 48.dp),
        ) {
            if (state.isScanning) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp))
                Text(stringResource(T.toolsNodeDiscoveryStopButton))
            } else {
                Text(
                    stringResource(
                        com.meshcoreone.android.core.l10n.R.string.l10n_app_tools_tools_nodediscovery_scanbutton,
                        filterName(state.filter),
                    ),
                )
            }
        }
    }
}

@Composable
private fun filterName(filter: NodeDiscoveryFilter): String =
    stringResource(if (filter == NodeDiscoveryFilter.REPEATERS) T.toolsNodeDiscoveryRepeaters else T.toolsNodeDiscoverySensors)
