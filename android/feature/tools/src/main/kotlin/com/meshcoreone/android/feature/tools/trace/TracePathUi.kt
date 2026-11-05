// PortedFrom: MC1/Views/Tools/TracePath/TracePathView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/TracePathListView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/TraceResultsSectionView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.trace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings as T
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings
import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.maps.MapAvailability
import com.meshcoreone.android.core.maps.MapCamera
import com.meshcoreone.android.core.maps.MapLibreMapSurface
import com.meshcoreone.android.core.maps.MapLibreOpenFreeMap
import com.meshcoreone.android.core.maps.MapLibreRuntime
import com.meshcoreone.android.core.maps.MapLine
import com.meshcoreone.android.core.maps.MapLineStyle
import com.meshcoreone.android.core.maps.MapMarker
import com.meshcoreone.android.core.maps.MapPinStyle
import com.meshcoreone.android.core.maps.MapPresentationState
import com.meshcoreone.android.core.maps.boundingCamera
import kotlinx.coroutines.launch
import java.text.NumberFormat
import com.meshcoreone.android.core.l10n.R as Strings

@Composable
private fun rememberTraceStrings(): TracePathStrings {
    val resources = LocalContext.current.resources
    val myDevice = stringResource(C.contactsResultsHopMyDevice)
    val sendFailed = stringResource(C.contactsTraceErrorSendFailed)
    val noResponse = stringResource(C.contactsTraceErrorNoResponse)
    val defaultName = stringResource(C.contactsTraceMapDefaultPathName)
    return remember(resources, myDevice, sendFailed, noResponse, defaultName) {
        object : TracePathStrings {
            override val myDevice = myDevice
            override val sendFailed = sendFailed
            override val noResponse = noResponse
            override fun allFailed(count: Int) = AppContactsStrings.contactsTraceErrorAllFailed(resources, count)
            override fun codeInvalidFormat(codes: String) = codes
            override fun codeNotFound(codes: String) = codes
            override fun codeAlreadyInPath(codes: String) = codes
            override fun pathNamePrefix(prefix: String) = prefix
            override fun pathNameTwoEndpoints(first: String, last: String) = "$first → $last"
            override fun pathNameMultipleEndpoints(first: String, last: String) = "$first → … → $last"
            override val defaultMapPathName = defaultName
        }
    }
}

object TracePathScreen {
    data class Dependencies(
        val feature: TracePathFeatureDependencies,
        val recentHops: RecentHopsStorage,
        val diagnostics: TraceDiagnostics,
        val radioId: RadioId?,
        val generation: Int,
    )

    @Composable
    operator fun invoke(dependencies: Dependencies?) {
        if (dependencies == null) {
            ToolDisconnected(stringResource(C.contactsTraceTitle))
            return
        }
        val scope = rememberCoroutineScope()
        val strings = rememberTraceStrings()
        val holder = remember(dependencies.feature) {
            TracePathStateHolder(
                scope,
                SystemTraceTimeSource,
                strings,
                dependencies.recentHops,
                dependencies.diagnostics,
            ).also { it.configure(dependencies.feature) }
        }
        val savedStrings = rememberSavedPathStrings()
        val savedPaths = remember(dependencies.feature) {
            SavedPathsStateHolder(savedStrings, dependencies.diagnostics).also { saved ->
                saved.configure(object : SavedPathsDependencies {
                    override fun savedPaths() = dependencies.feature.savedPaths()
                    override fun connectedDevice() = dependencies.feature.connectedDevice()
                })
            }
        }

        LaunchedEffect(dependencies.generation, dependencies.radioId) {
            holder.configure(dependencies.feature)
            holder.startListening()
            dependencies.radioId?.let { holder.loadContacts(it) }
        }
        DisposableEffect(holder) { onDispose { holder.stopListening(); holder.cancelBatchTrace() } }
        val state by holder.state.collectAsStateWithLifecycle()
        val savedState by savedPaths.state.collectAsStateWithLifecycle()
        TracePathContent(state, holder, savedState, savedPaths, dependencies.generation, dependencies.radioId)
    }
}

@Composable
private fun rememberSavedPathStrings(): SavedPathsStrings {
    val failed = stringResource(L.commonErrorFailedToLoad)
    return remember(failed) {
        object : SavedPathsStrings {
            override val loadFailed = failed
            override val renameFailed = failed
            override val deleteFailed = failed
        }
    }
}

@Composable
private fun TracePathContent(
    state: TracePathState,
    holder: TracePathStateHolder,
    savedState: SavedPathsState,
    savedPaths: SavedPathsStateHolder,
    connectionGeneration: Int,
    radioId: RadioId?,
) {
    var showMap by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val codeInput = rememberTraceCodeInputState(holder, connectionGeneration, radioId)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row {
                FilterChip(
                    selected = !showMap,
                    onClick = { showMap = false },
                    label = { Text(stringResource(C.contactsTraceModeList)) },
                )
                FilterChip(
                    selected = showMap,
                    onClick = { showMap = true },
                    label = { Text(stringResource(C.contactsTraceModeMap)) },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            TextButton(onClick = { showSaved = true; scope.launch { savedPaths.loadSavedPaths() } }) {
                Text(stringResource(C.contactsTraceSaved))
            }
        }
        if (showMap) TracePathMapContent(state) else TracePathListContent(state, holder, codeInput)
    }
    if (showSaved) {
        AlertDialog(
            onDismissRequest = { showSaved = false },
            title = { Text(stringResource(C.contactsTraceSaved)) },
            text = {
                when {
                    savedState.isLoading -> CircularProgressIndicator()
                    savedState.savedPaths.isEmpty() -> Text(stringResource(C.contactsTraceListEmptyPath))
                    else -> LazyColumn {
                        items(savedState.savedPaths, key = { it.id }) { path ->
                            Row(
                                Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                TextButton(onClick = { holder.loadSavedPath(path); showSaved = false }) {
                                    Text(path.name)
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        savedPaths.deletePath(path)
                                        holder.handleSavedPathDeleted(path.id)
                                    }
                                }) {
                                    Text(stringResource(L.commonDelete))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSaved = false }) { Text(stringResource(L.commonDone)) }
            },
        )
    }
}

@Composable
internal fun TracePathListContent(state: TracePathState, holder: TracePathStateHolder, codeInput: TraceCodeInputState) {
    val scope = rememberCoroutineScope()
    var showClearConfirmation by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(C.contactsTraceTitle),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 20.dp).semantics { heading() },
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(C.contactsTraceListAutoReturn), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(C.contactsTraceListAutoReturnDescription), style = MaterialTheme.typography.bodySmall)
                }
                Switch(state.autoReturnPath, holder::setAutoReturnPath)
            }
        }

        if (state.outboundPath.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(stringResource(C.contactsTraceListEmptyPath), Modifier.padding(20.dp))
                }
            }
        } else {
            itemsIndexed(state.outboundPath, key = { _, hop -> hop.id }) { index, hop ->
                val resources = LocalContext.current.resources
                val hopLabel = AppContactsStrings.contactsTraceListHopLabel(resources, index + 1, hop.displayText)
                val moveUpLabel = stringResource(C.contactsDetailAvatarCropMoveUp)
                val moveDownLabel = stringResource(C.contactsDetailAvatarCropMoveDown)
                val deleteLabel = stringResource(L.commonDelete)
                val moveUp = {
                    if (index > 0 && holder.state.value.outboundPath.getOrNull(index)?.id == hop.id) {
                        holder.moveRepeater(setOf(index), index - 1)
                    }
                }
                val moveDown = {
                    if (index < state.outboundPath.lastIndex && holder.state.value.outboundPath.getOrNull(index)?.id == hop.id) {
                        holder.moveRepeater(setOf(index), index + 2)
                    }
                }
                val remove = {
                    if (holder.state.value.outboundPath.getOrNull(index)?.id == hop.id) {
                        holder.removeRepeater(index)
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(
                                    com.meshcoreone.android.core.l10n.R.string.l10n_app_contacts_contacts_trace_list_hoplabel,
                                    index + 1,
                                    hop.displayText,
                                ),
                            )
                            Text(hop.hashHex, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(
                            onClick = moveUp,
                            enabled = index > 0,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                                contentDescription = "$moveUpLabel: $hopLabel"
                                // Freeze the rendered action, not Clickable's mutable delegate.
                                onClick { moveUp(); true }
                            },
                        ) {
                            Text("↑")
                        }
                        OutlinedButton(
                            onClick = moveDown,
                            enabled = index < state.outboundPath.lastIndex,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                                contentDescription = "$moveDownLabel: $hopLabel"
                                onClick { moveDown(); true }
                            },
                        ) {
                            Text("↓")
                        }
                        OutlinedButton(
                            onClick = remove,
                            modifier = Modifier.sizeIn(minHeight = 48.dp).semantics {
                                contentDescription = "$deleteLabel: $hopLabel"
                                onClick { remove(); true }
                            },
                        ) {
                            Text(deleteLabel)
                        }
                    }
                }
            }
            item(key = "trace-clear-path") {
                OutlinedButton(
                    onClick = { showClearConfirmation = true },
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                ) {
                    Text(stringResource(C.contactsTraceClearPath))
                }
            }
        }
        item(key = "trace-code-input") { TraceCodeInput(state, holder, codeInput) }
        if (state.availableRepeaters.isNotEmpty()) {
            item {
                Text(stringResource(T.toolsNodeDiscoveryRepeaters), style = MaterialTheme.typography.titleMedium)
            }
            items(state.availableRepeaters, key = { it.id }) { repeater ->
                OutlinedButton(
                    onClick = { holder.addNode(repeater) },
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                ) {
                    Text(repeater.name)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0u.toUByte(), 1u.toUByte(), 2u.toUByte()).forEach { mode ->
                    val bytes = 1 shl mode.toInt()
                    FilterChip(
                        selected = holder.effectiveTraceMode == mode,
                        onClick = { holder.setTraceHashMode(mode) },
                        label = { Text("$bytes B") },
                    )
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(C.contactsTraceListBatchTrace), style = MaterialTheme.typography.titleMedium)
                Switch(state.batchEnabled, holder::setBatchEnabled)
            }
        }
        state.errorMessage?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { contentDescription = error }) }
        }
        item { TraceResults(state) }
        item {
            Button(
                onClick = { if (state.isRunning) holder.cancelBatchTrace() else scope.launch { holder.runBatchTrace() } },
                enabled = state.isRunning || state.canRunTraceWhenConnected,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
            ) {
                if (state.isRunning) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp))
                    Text(stringResource(C.contactsTraceListRunningTrace))
                } else {
                    Text(stringResource(C.contactsTraceListRunTrace))
                }
            }
        }
        item { Column(Modifier.padding(bottom = 24.dp)) {} }
    }
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(C.contactsTraceClearPath)) },
            text = { Text(stringResource(C.contactsTraceClearPathMessage)) },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text(stringResource(L.commonCancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirmation = false
                    holder.clearPath()
                }) {
                    Text(stringResource(C.contactsTraceClearPath))
                }
            },
        )
    }
}

@Composable
private fun TracePathMapContent(state: TracePathState) {
    val located = state.result?.hops.orEmpty().filter { it.hasLocation }
    if (!MapLibreRuntime.isSupported || located.size < 2) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(C.contactsTraceModeMap), style = MaterialTheme.typography.headlineSmall)
            Text(
                state.repeatersWithoutLocation.takeIf(List<String>::isNotEmpty)?.joinToString()
                    ?: stringResource(C.contactsTraceListEmptyPath),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }
    val markers = located.mapIndexed { index, hop ->
        MapMarker(
            id = hop.id,
            position = GeoPoint(checkNotNull(hop.latitude), checkNotNull(hop.longitude)),
            style = MapPinStyle.REPEATER_HOP,
            label = hop.resolvedName ?: hop.hashDisplayString,
            clusterable = false,
            hopIndex = index,
            badgeText = "${"%.1f".format(hop.snr)} dB",
        )
    }
    val lines = markers.zipWithNext().mapIndexed { index, pair ->
        MapLine(
            id = "trace-$index",
            points = listOf(pair.first.position, pair.second.position),
            style = when (traceLineQuality(located[index + 1].snr)) {
                TraceLineQuality.GOOD -> MapLineStyle.TRACE_GOOD
                TraceLineQuality.MEDIUM -> MapLineStyle.TRACE_MEDIUM
                TraceLineQuality.WEAK -> MapLineStyle.TRACE_WEAK
                TraceLineQuality.UNTRACED -> MapLineStyle.TRACE_UNTRACED
            },
            pathIndex = index,
        )
    }
    var camera by remember(markers) { mutableStateOf(markers.map { it.position }.boundingCamera()) }
    MapLibreMapSurface(
        presentation = MapPresentationState(
            availability = MapAvailability.Available(MapLibreOpenFreeMap.catalog),
            camera = camera,
            points = markers,
            lines = lines,
        ),
        clusteringEnabled = false,
        labelsEnabled = true,
        accessibilityLabel = located.joinToString(" → ") {
            "${it.resolvedName ?: it.hashDisplayString ?: "Local"}, ${"%.1f".format(it.snr)} dB"
        },
        onCameraChanged = { camera = it },
        onMarkerSelected = {},
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
internal fun TraceResults(state: TracePathState) {
    val result = state.displayedResult
    if (result != null || state.batchStatus != null) TraceResultCard(result, state)
}

@Composable
internal fun TraceResultCard(result: TraceResult?, state: TracePathState) {
    val resources = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0]
    val snrFormat = remember(locale) {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
    }
    val batchStatus = state.batchStatus
    val batchRtt = state.batchRtt
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (batchRtt != null) {
                val rttLabel = stringResource(
                    Strings.string.l10n_app_contacts_contacts_results_avgrttlabel,
                    batchRtt.averageMs, batchRtt.minMs, batchRtt.maxMs,
                )
                Column(Modifier.clearAndSetSemantics { contentDescription = rttLabel }) {
                    Text(stringResource(C.contactsResultsAvgRoundTrip), style = MaterialTheme.typography.bodySmall)
                    Text("${batchRtt.averageMs} ms", style = MaterialTheme.typography.titleMedium)
                    Text("(${batchRtt.minMs} – ${batchRtt.maxMs})", style = MaterialTheme.typography.bodySmall)
                }
            } else if (result != null) {
                Text(
                    if (result.success) "${result.durationMs} ms" else result.errorMessage.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (result.success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
            }
            if (batchStatus != null) {
                val batchRunning = state.isBatchInProgress && !batchStatus.isComplete
                val text = if (!batchRunning) {
                    C.contactsResultsBatchSuccess(resources, batchStatus.successCount, batchStatus.size, batchStatus.successPercent)
                } else {
                    C.contactsResultsBatchProgress(resources, batchStatus.currentIndex, batchStatus.size)
                }
                val label = if (batchStatus.isComplete) {
                    C.contactsResultsBatchCompleteLabel(resources, batchStatus.successCount, batchStatus.size, batchStatus.successPercent)
                } else if (batchRunning) {
                    C.contactsResultsBatchProgressLabel(resources, batchStatus.currentIndex, batchStatus.size)
                } else text
                Row(
                    Modifier.clearAndSetSemantics { contentDescription = label },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (batchRunning) CircularProgressIndicator(Modifier.sizeIn(maxWidth = 16.dp, maxHeight = 16.dp), strokeWidth = 2.dp)
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            val showBatchStats = state.batchEnabled && state.batchPresentationActive
            result?.takeIf { it.success }?.hops?.forEachIndexed { index, hop ->
                val stats = if (showBatchStats) state.hopStats(index) else null
                val latest = if (showBatchStats) state.latestHopSNR(index) else null
                val snr = TraceHopDisplay.displaySnr(hop.snr, stats, latest, showBatchStats && state.isBatchInProgress)
                val name = hop.resolvedName ?: hop.hashDisplayString ?: stringResource(C.contactsResultsHopMyDevice)
                Text("${index + 1}. $name")
                if (!hop.isStartNode) Text(C.contactsResultsHopSnr(resources, snrFormat.format(snr)))
                if (stats != null) {
                    val range = Triple(snrFormat.format(stats.avg), snrFormat.format(stats.min), snrFormat.format(stats.max))
                    val hopLabel = C.contactsResultsHopAvgSNRLabel(resources, range.first, range.second, range.third)
                    Text(
                        C.contactsResultsHopAvgSNR(resources, range.first, range.second, range.third),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = hopLabel },
                    )
                }
            }
            state.totalPathDistance?.let { Text("${"%.0f".format(it)} m") }
            if (state.isDistanceUsingFallback) Text(state.repeatersWithoutLocation.joinToString(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ToolDisconnected(title: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(L.errorConnectionNotConnected), modifier = Modifier.padding(top = 8.dp))
    }
}
