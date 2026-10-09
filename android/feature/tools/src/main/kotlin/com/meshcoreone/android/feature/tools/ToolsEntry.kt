// PortedFrom: MC1/Views/Tools/ToolsView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/ToolsContentColumn.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/ToolDestinationView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings as T
import com.meshcoreone.android.core.ui.FeatureShellCopy
import com.meshcoreone.android.core.ui.ScaffoldFeatureContent
import com.meshcoreone.android.feature.tools.discovery.NodeDiscoveryRoute
import com.meshcoreone.android.feature.tools.discovery.NodeDiscoveryFeatureDependencies
import com.meshcoreone.android.feature.tools.navigation.ToolSelection
import com.meshcoreone.android.feature.tools.trace.RecentHopsStorage
import com.meshcoreone.android.feature.tools.trace.TraceDiagnostics
import com.meshcoreone.android.feature.tools.trace.TracePathFeatureDependencies
import com.meshcoreone.android.feature.tools.trace.TracePathScreen
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle

data class ToolsFeatureDependencies(
    val trace: TracePathFeatureDependencies,
    val discovery: NodeDiscoveryFeatureDependencies,
    val recentHops: RecentHopsStorage,
    val diagnostics: TraceDiagnostics,
    val radioId: () -> com.meshcoreone.android.core.model.RadioId?,
    val generation: StateFlow<Int>,
)

@Composable
fun ToolsEntry(
    route: FeatureRoute,
    onNavigate: (FeatureRoute) -> Unit,
    selection: ToolSelection? = null,
    onSelect: (ToolSelection) -> Unit = {},
    dependencies: ToolsFeatureDependencies? = null,
) {
    val generation by dependencies?.generation?.collectAsStateWithLifecycle()
        ?: androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    when (selection) {
        null -> ToolsHome(onSelect)
        ToolSelection.TRACE_PATH -> TracePathScreen(
            dependencies?.let {
                TracePathScreen.Dependencies(it.trace, it.recentHops, it.diagnostics, it.radioId(), generation)
            },
        )
        ToolSelection.NODE_DISCOVERY -> NodeDiscoveryRoute(
            dependencies?.let { NodeDiscoveryRoute.Dependencies(it.discovery, it.diagnostics) },
        )
        else -> ScaffoldFeatureContent(
            FeatureId.TOOLS,
            route,
            FeatureShellCopy(
                toolTitle(selection),
                stringResource(com.meshcoreone.android.core.l10n.R.string.scaffold_tools_description),
                stringResource(com.meshcoreone.android.core.l10n.R.string.scaffold_run_radio_tool),
            ),
            onNavigate,
        )
    }
}

@Composable
private fun ToolsHome(onSelect: (ToolSelection) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(T.toolsTitle),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(24.dp).semantics { heading() },
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(ToolSelection.entries, key = { it.name }) { tool ->
                Row(
                    Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp).clickable { onSelect(tool) }.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(toolTitle(tool), style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun toolTitle(tool: ToolSelection): String = stringResource(
    when (tool) {
        ToolSelection.TRACE_PATH -> T.toolsTracePath
        ToolSelection.LINE_OF_SIGHT -> T.toolsLineOfSight
        ToolSelection.RX_LOG -> T.toolsRxLog
        ToolSelection.NOISE_FLOOR -> T.toolsNoiseFloor
        ToolSelection.NODE_DISCOVERY -> T.toolsNodeDiscovery
        ToolSelection.CLI -> T.toolsCli
    },
)
