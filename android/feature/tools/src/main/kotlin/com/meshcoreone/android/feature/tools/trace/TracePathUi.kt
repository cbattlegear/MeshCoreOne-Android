// PortedFrom: MC1/Views/Tools/TracePath/TracePathView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/TracePathListView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/TraceResultsSectionView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.trace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings as T
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings
import com.meshcoreone.android.core.maps.MapAvailability
import com.meshcoreone.android.core.maps.MapCamera
import com.meshcoreone.android.core.maps.MapLibreMapSurface
import com.meshcoreone.android.core.maps.MapLibreOpenFreeMap
import com.meshcoreone.android.core.maps.MapLibreRuntime
import com.meshcoreone.android.core.maps.MapMarker
import com.meshcoreone.android.core.maps.MapPresentationState
import com.meshcoreone.android.core.maps.MapPreferenceStore
import com.meshcoreone.android.core.maps.MapPreferences
import com.meshcoreone.android.core.maps.MapFilterHost
import com.meshcoreone.android.core.maps.MapColorPreference
import com.meshcoreone.android.core.l10n.generated.AppMapStrings as M
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import java.text.NumberFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
        val mapPreferences: MapPreferenceStore? = null,
        val nodeTableVersion: Int = 0,
    )

    @Composable
    operator fun invoke(
        dependencies: Dependencies?,
        mapSurface: TraceMapSurface = { presentation, clusters, labels, camera, marker, overlay ->
            NativeTraceMapSurface(presentation, clusters, labels, camera, marker, overlay)
        },
    ) {
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
        }
        LaunchedEffect(dependencies.generation, dependencies.radioId, dependencies.nodeTableVersion) {
            dependencies.radioId?.let { holder.loadContacts(it) }
        }
        DisposableEffect(holder) { onDispose { holder.stopListening(); holder.cancelBatchTrace() } }
        val state by holder.state.collectAsStateWithLifecycle()
        val savedState by savedPaths.state.collectAsStateWithLifecycle()
        TracePathContent(
            state, holder, savedState, savedPaths, dependencies.mapPreferences,
            dependencies.feature.bestAvailableLocation(), dependencies.diagnostics,
            mapSurface,
        )
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
    mapPreferences: MapPreferenceStore?,
    location: Coordinate?,
    diagnostics: TraceDiagnostics,
    mapSurface: TraceMapSurface,
) {
    var showMap by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
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
        if (showMap) TracePathMapContent(state, holder, mapPreferences, location, diagnostics, mapSurface)
        else TracePathListContent(state, holder)
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
private fun TracePathListContent(state: TracePathState, holder: TracePathStateHolder) {
    val scope = rememberCoroutineScope()
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
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
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
                        OutlinedButton(onClick = { holder.removeRepeater(index) }) {
                            Text(stringResource(L.commonDelete))
                        }
                    }
                }
            }
        }
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
        state.result?.let { result ->
            item { TraceResultCard(result, state) }
        }
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
}

typealias TraceMapSurface = @Composable (
    MapPresentationState, Boolean, Boolean, (MapCamera) -> Unit, (MapMarker) -> Unit,
    @Composable BoxScope.() -> Unit,
) -> Unit

@Composable
internal fun TracePathMapContent(
    state: TracePathState,
    holder: TracePathStateHolder,
    preferenceStore: MapPreferenceStore?,
    location: Coordinate?,
    diagnostics: TraceDiagnostics,
    mapSurface: TraceMapSurface = { presentation, clusters, labels, camera, marker, overlay ->
        NativeTraceMapSurface(presentation, clusters, labels, camera, marker, overlay)
    },
) {
    val preferences = checkNotNull(preferenceStore) { "Trace map requires process preference wiring" }
    val map = remember(holder) { TraceMapPathLogic(holder).also { it.configure(location) } }
    val scope = rememberCoroutineScope()
    var settings by remember(preferences) { mutableStateOf<MapPreferences?>(null) }
    val haptics = LocalHapticFeedback.current
    var preferenceError by remember(preferences) { mutableStateOf(false) }
    var retry by remember(preferences) { mutableStateOf(0) }
    var camera by remember(holder) { mutableStateOf<MapCamera?>(null) }
    var presentation by remember(holder) { mutableStateOf(MapPresentationState()) }
    var confirmClear by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val unit = stringResource(com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings.remoteNodesStatusSnrBadgeUnit)
    val distanceFormat = remember(locale) { MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT) }
    val snrFormat = remember(locale) { NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    } }
    val formatBadge: (Double, Double) -> String = remember(distanceFormat, snrFormat, unit) {
        { meters, snr ->
            val distance = if (meters >= 1_000) Measure(meters / 1_000, MeasureUnit.KILOMETER)
            else Measure(meters, MeasureUnit.METER)
            "${distanceFormat.formatMeasures(distance)} \u00b7 ${snrFormat.format(snr)} $unit"
        }
    }
    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(preferences, retry) {
        try {
            preferences.observe(MapFilterHost.TRACE_PATH).collect {
                settings = it
                preferenceError = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            diagnostics.failure("trace-map-read-preferences", error)
            preferenceError = true
        }
    }
    fun refresh() {
        presentation = MapPresentationState(
            availability = MapAvailability.Available(MapLibreOpenFreeMap.catalog),
            points = map.mapMarkers(formatBadge), lines = map.lines,
        )
        if (camera == null && settings != null) camera = map.initialCamera()
    }
    LaunchedEffect(state.availableNodes, state.discoveredRepeaters, state.outboundPath, state.result, settings, formatBadge) {
        settings?.let {
            map.applyFilter(TraceMapPinFilter(it.filter.favoritesOnly, it.filter.showDiscovered))
            map.setShowLabels(it.labels)
        }
        map.rebuildPathState()
        refresh()
    }
    LaunchedEffect(location) { map.updateUserLocation(location); refresh() }
    fun write(operation: String, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                diagnostics.failure(operation, error)
                preferenceError = true
            }
        }
    }
    val loaded = settings
    Column(Modifier.fillMaxSize()) {
        if (loaded == null) CircularProgressIndicator(Modifier.padding(16.dp))
        if (preferenceError) {
            Text(stringResource(L.commonErrorFailedToLoad), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { retry++ }) { Text(stringResource(M.mapPreviewRetryButtonAccessibilityLabel)) }
        }
        if (loaded != null) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = loaded.filter.favoritesOnly,
                    onClick = { write("trace-map-write-filter") {
                        preferences.setFilter(MapFilterHost.TRACE_PATH, loaded.filter.setFavoritesOnly(!loaded.filter.favoritesOnly))
                    } },
                    label = { Text(stringResource(C.contactsPathEditFilterFavorites)) },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                )
                FilterChip(
                    selected = loaded.filter.effectiveShowDiscovered, enabled = !loaded.filter.favoritesOnly,
                    onClick = { write("trace-map-write-filter") {
                        preferences.setFilter(MapFilterHost.TRACE_PATH, loaded.filter.setShowDiscovered(!loaded.filter.showDiscovered))
                    } },
                    label = { Text(stringResource(C.contactsPathEditFilterDiscovered)) },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                )
                FilterChip(
                    selected = loaded.labels,
                    onClick = { write("trace-map-write-labels") { preferences.setLabels(!loaded.labels) } },
                    label = { Text(stringResource(M.mapControlsShowLabels)) },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                )
            }
        }
        Box(Modifier.weight(1f)) {
            mapSurface(
                presentation.copy(camera = camera, isDark = when (loaded?.appearance) {
                    MapColorPreference.DARK -> true
                    MapColorPreference.LIGHT -> false
                    else -> systemDark
                }),
                loaded?.clustering ?: false, loaded?.labels ?: true, { camera = it },
                { marker -> if (loaded != null) {
                    if (map.handleMapPointTap(marker.id) == PathPinTapResult.REJECTED_MIDDLE_HOP) {
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    }
                    refresh()
                } },
                {
                    Column(Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                        TextButton(
                            onClick = { camera = map.allPinsCamera() }, enabled = loaded != null && map.pins.isNotEmpty(),
                            modifier = Modifier.testTag("trace-map:center-all").sizeIn(minHeight = 48.dp),
                        ) { Text(stringResource(M.mapControlsCenterAll)) }
                        TextButton(onClick = { camera = map.pathCamera() }, enabled = loaded != null && map.hasPath) {
                            Text(stringResource(C.contactsTraceMapCenterOnPath))
                        }
                    }
                },
            )
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { confirmClear = true }, enabled = state.outboundPath.isNotEmpty()) {
                Text(stringResource(C.contactsTraceMapClear))
            }
            Button(
                onClick = { camera = map.pathCamera(); scope.launch { map.runTrace() } },
                enabled = loaded != null && state.canRunTraceWhenConnected,
            ) { Text(stringResource(C.contactsTraceListRunTrace)) }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(C.contactsTraceClearPath)) },
        text = { Text(stringResource(C.contactsTraceClearPathMessage)) },
        confirmButton = { TextButton(
            onClick = { map.clearPath(); refresh(); confirmClear = false },
            modifier = Modifier.testTag("trace-map:confirm-clear"),
        ) {
            Text(stringResource(C.contactsTraceClearPath))
        } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(L.commonCancel)) } },
    )
}

@Composable
private fun NativeTraceMapSurface(
    presentation: MapPresentationState, clusters: Boolean, labels: Boolean,
    camera: (MapCamera) -> Unit, marker: (MapMarker) -> Unit, overlay: @Composable BoxScope.() -> Unit,
) {
    if (MapLibreRuntime.isSupported) {
        MapLibreMapSurface(
            presentation, clusters, labels, stringResource(C.contactsTraceModeMap), camera, marker, overlay = overlay,
        )
    } else Box(Modifier.fillMaxSize()) {
        Text(stringResource(com.meshcoreone.android.core.l10n.R.string.scaffold_map_description))
        overlay()
    }
}

@Composable
private fun TraceResultCard(result: TraceResult, state: TracePathState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (result.success) "${result.durationMs} ms" else result.errorMessage.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = if (result.success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
            result.hops.forEachIndexed { index, hop ->
                Text("${index + 1}. ${hop.resolvedName ?: hop.hashDisplayString ?: "Local"} — ${"%.1f".format(hop.snr)} dB")
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
