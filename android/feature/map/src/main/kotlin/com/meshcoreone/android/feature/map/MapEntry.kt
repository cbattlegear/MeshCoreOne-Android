// PortedFrom: MC1/Views/Map/MapView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Map/MapViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.map

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.maps.ContactMapType
import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.maps.GeoBounds
import com.meshcoreone.android.core.maps.MapAvailability
import com.meshcoreone.android.core.maps.MapCamera
import com.meshcoreone.android.core.maps.MapFilterHost
import com.meshcoreone.android.core.maps.MapFilterState
import com.meshcoreone.android.core.maps.MapPreferences
import com.meshcoreone.android.core.maps.resolvedMapIsDark
import com.meshcoreone.android.core.maps.effectiveAppThemeIsDark
import com.meshcoreone.android.core.maps.MapLibreMapSurface
import com.meshcoreone.android.core.maps.MapLibreOpenFreeMap
import com.meshcoreone.android.core.maps.MapLibreRuntime
import com.meshcoreone.android.core.maps.MapMarker
import com.meshcoreone.android.core.maps.MapPinStyle
import com.meshcoreone.android.core.maps.MapPresentationState
import com.meshcoreone.android.core.maps.MapStyle
import com.meshcoreone.android.core.maps.OfflineLayer
import com.meshcoreone.android.core.maps.OfflineMapError
import com.meshcoreone.android.core.maps.OfflinePack
import com.meshcoreone.android.core.maps.OfflinePackState
import com.meshcoreone.android.core.maps.boundingCamera
import com.meshcoreone.android.core.maps.semanticText
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.core.ui.FeatureShellCopy
import com.meshcoreone.android.core.ui.ScaffoldFeatureContent
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

typealias OperationalMapSurface = @Composable (
    MapPresentationState, Boolean, Boolean, (MapCamera) -> Unit, (MapMarker) -> Unit,
    @Composable BoxScope.() -> Unit,
) -> Unit

@Composable
fun MapEntry(
    route: FeatureRoute,
    onNavigate: (FeatureRoute) -> Unit,
    dependencies: MapFeatureDependencies? = null,
    focus: GeoPoint? = null,
    onFocusConsumed: () -> Unit = {},
    mapSurface: OperationalMapSurface =
        { state, clusters, labels, camera, marker, overlay -> NativeMapSurface(state, clusters, labels, camera, marker, overlay) },
) {
    if (dependencies == null) {
        ScaffoldFeatureContent(
            FeatureId.MAP,
            route,
            FeatureShellCopy(
                stringResource(R.string.tab_map),
                stringResource(R.string.scaffold_map_description),
                stringResource(R.string.scaffold_open_map),
            ),
            onNavigate,
        )
        return
    }
    MapContent(dependencies, focus, onFocusConsumed, mapSurface)
}

@Composable
private fun NativeMapSurface(
    state: MapPresentationState,
    clusters: Boolean,
    labels: Boolean,
    camera: (MapCamera) -> Unit,
    marker: (MapMarker) -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    if (!MapLibreRuntime.isSupported) {
        Box(Modifier.fillMaxSize()) {
            MapRuntimeUnavailable()
            overlay()
        }
    } else {
        MapLibreMapSurface(state, clusters, labels, stringResource(R.string.tab_map), camera, marker, overlay = overlay)
    }
}

@Composable
private fun MapRuntimeUnavailable() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.tab_map), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.scaffold_map_description))
            }
        }
    }
}

@Composable
private fun MapContent(
    dependencies: MapFeatureDependencies,
    focus: GeoPoint?,
    onFocusConsumed: () -> Unit,
    mapSurface: OperationalMapSurface,
) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    val preferenceStore = checkNotNull(dependencies.preferences) { "Process map preferences were not installed" }
    var settings by remember(dependencies) { mutableStateOf<MapPreferences?>(null) }
    var preferenceObservationError by remember { mutableStateOf<Exception?>(null) }
    var preferenceWriteError by remember { mutableStateOf<Exception?>(null) }
    var preferenceRetry by remember { mutableStateOf(0) }
    val filter = settings?.filter ?: MapFilterState.seed(MapFilterHost.MAIN_MAP)
    val clustering = settings?.clustering ?: true
    val labels = settings?.labels ?: true
    val appIsDark = effectiveAppThemeIsDark()
    val scope = rememberCoroutineScope()
    val holder = remember(dependencies, scope) { MapFeatureStateHolder(dependencies, scope, filter) }
    val feature by holder.state.collectAsStateWithLifecycle()
    DisposableEffect(holder) { onDispose { holder.close() } }
    var listVisible by remember { mutableStateOf(false) }
    var focusedPoint by rememberSaveable(stateSaver = FocusedPointSaver) { mutableStateOf(focus) }
    val focusedPinId = UUID.fromString(rememberSaveable { UUID.randomUUID().toString() })
    var focusedPinSelected by rememberSaveable { mutableStateOf(false) }
    var offlineVisible by remember { mutableStateOf(false) }
    var camera by rememberSaveable(stateSaver = CameraSaver) {
        mutableStateOf(
            if (focus == null) preferences.getString(CAMERA_KEY, null)?.let(MapCamera::decode) else null,
        )
    }
    var cameraCommandRevision by remember { mutableLongStateOf(0L) }

    LaunchedEffect(preferenceStore, holder, preferenceRetry) {
        try {
            preferenceStore.observe(MapFilterHost.MAIN_MAP).collect { next ->
                if (holder.state.value.filter != next.filter) {
                    holder.setFilter(next.filter)
                    if (settings != null) focusedPinSelected = false
                }
                settings = next
                preferenceObservationError = null
                preferenceWriteError = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            preferenceObservationError = failure
        }
    }
    fun writePreference(write: suspend () -> Unit) {
        scope.launch {
            try {
                write()
                preferenceWriteError = null
                if (preferenceObservationError != null) preferenceRetry++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                preferenceWriteError = failure
            }
        }
    }
    val preferenceError = preferenceObservationError ?: preferenceWriteError

    LaunchedEffect(feature.visibleMarkers, feature.loading, settings) {
        if (settings != null && settings?.filter == feature.filter && camera == null && focusedPoint == null) {
            camera = feature.visibleMarkers.map { it.marker.position }.boundingCamera()
        }
    }
    LaunchedEffect(focus) {
        if (focus != null) {
            focusedPoint = focus
            focusedPinSelected = false
            camera = MapCamera(focus, 0.01, 0.01)
            cameraCommandRevision++
            onFocusConsumed()
        }
    }

    val filtered = feature.visibleMarkers.map { it.marker }.toMutableList()
    (focus ?: focusedPoint)?.let {
        filtered += MapMarker(
            id = focusedPinId,
            position = it,
            style = MapPinStyle.DROPPED_PIN,
            label = stringResource(R.string.l10n_app_map_map_callout_message),
            clusterable = false,
        )
    }

    val state = MapPresentationState(
        availability = MapAvailability.Available(MapLibreOpenFreeMap.catalog),
        style = MapStyle.STANDARD,
        camera = focus?.let { MapCamera(it, 0.01, 0.01) } ?: camera,
        cameraCommandRevision = cameraCommandRevision,
        points = filtered,
        lines = feature.content.lines,
        isDark = settings?.let {
            resolvedMapIsDark(it.appearance, appIsDark)
        } ?: false,
    )

    mapSurface(
        state, clustering, labels,
        {
            camera = it
            preferences.edit().putString(CAMERA_KEY, it.encode()).apply()
        },
        {
            focusedPinSelected = it.style == MapPinStyle.DROPPED_PIN
            holder.select(it.id)
        },
    ) {
        if (settings != null) MapControls(
            filter = filter,
            onFilter = { transform ->
                writePreference { preferenceStore.updateFilter(MapFilterHost.MAIN_MAP, transform) }
            },
            clustering = clustering,
            onClustering = {
                writePreference { preferenceStore.setClustering(it) }
            },
            labels = labels,
            onLabels = {
                writePreference { preferenceStore.setLabels(it) }
            },
            listVisible = listVisible,
            onListVisible = { listVisible = it },
            onRefresh = holder::refresh,
            onOffline = { offlineVisible = !offlineVisible },
            canCenterAll = filtered.isNotEmpty(),
            onCenterAll = {
                filtered.map { it.position }.boundingCamera()?.let {
                    camera = it
                    cameraCommandRevision++
                }
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
        if ((feature.loading || settings == null) && feature.error == null && preferenceError == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        (preferenceError ?: feature.error)?.let { error ->
            val presented = remember(error) { UiErrorMapper().present(error) }
            Card(Modifier.align(Alignment.Center).padding(16.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(uiString(presented.content.message))
                    Button(onClick = {
                        if (preferenceError != null) preferenceRetry++ else holder.refresh()
                    }, modifier = Modifier.testTag("map:preference-retry")) {
                        Text(stringResource(R.string.l10n_app_map_map_controls_refresh))
                    }
                }
            }
        }
        val selectedItem = feature.selected ?: filtered.firstOrNull {
            focus == null && focusedPinSelected && it.id == focusedPinId
        }?.let {
            MapFeatureMarker(it, ContactMapType.CHAT, false, false)
        }
        selectedItem?.let { item ->
            val marker = item.marker
            Card(Modifier.align(Alignment.BottomStart).padding(bottom = 52.dp, start = 12.dp, end = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(marker.semanticText.ifBlank { stringResource(R.string.l10n_app_contacts_contacts_list_selectnode) })
                    Text("${marker.position.latitude}, ${marker.position.longitude}", style = MaterialTheme.typography.bodySmall)
                    when (val selection = item.selection) {
                        is MapSelection.Contact -> if (dependencies.actions != null) {
                            FlowRow {
                                TextButton(onClick = holder::openContact) {
                                    Text(stringResource(R.string.l10n_app_map_map_callout_details))
                                }
                                if (selection.contact.type == ContactType.CHAT) {
                                    TextButton(onClick = holder::openChat) {
                                        Text(stringResource(R.string.l10n_app_map_map_callout_message))
                                    }
                                }
                            }
                        }
                        is MapSelection.Discovered -> {
                            Text(stringResource(R.string.l10n_app_map_map_discovereddetail_title))
                            Text(
                                stringResource(R.string.l10n_app_map_map_detail_publickey) + ": " + selection.node.publicKey.hexString,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (dependencies.actions != null) {
                                TextButton(onClick = holder::addDiscovered, enabled = !feature.adding) {
                                    Text(stringResource(R.string.l10n_app_map_map_discovereddetail_add))
                                }
                            }
                        }
                        null -> Unit
                    }
                    TextButton(onClick = {
                        holder.select(null)
                        focusedPinSelected = false
                        focusedPoint = null
                    }) {
                        Text(stringResource(R.string.l10n_app_map_map_common_done))
                    }
                }
            }
        }
        if (listVisible) {
            val mapListDescription = stringResource(R.string.tab_map)
            Card(Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.68f).fillMaxHeight(0.55f).padding(12.dp)) {
                LazyColumn(
                    Modifier.semantics { contentDescription = mapListDescription },
                ) {
                    items(filtered, key = { it.id }) { marker ->
                        TextButton(
                            onClick = {
                                focusedPinSelected = marker.style == MapPinStyle.DROPPED_PIN
                                holder.select(marker.id)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(marker.semanticText.ifBlank { "${marker.position.latitude}, ${marker.position.longitude}" })
                        }
                    }
                }
            }
        }
        if (offlineVisible) {
            OfflineMapsPanel(
                controller = dependencies.offlineMaps,
                camera = camera,
                onDismiss = { offlineVisible = false },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun MapControls(
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
    val controlModifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
    Card(modifier.padding(8.dp)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AssistChip(
                    onClick = onCenterAll,
                    enabled = canCenterAll,
                    modifier = controlModifier,
                    label = { Text(stringResource(R.string.l10n_app_map_map_controls_centerall)) },
                )
                AssistChip(
                    onClick = onRefresh,
                    modifier = controlModifier,
                    label = { Text(stringResource(R.string.l10n_app_map_map_controls_refresh)) },
                )
                AssistChip(
                    onClick = onOffline,
                    modifier = controlModifier,
                    label = { Text(stringResource(R.string.l10n_app_settings_offlinemaps_title)) },
                )
                FilterChip(
                    selected = listVisible,
                    modifier = controlModifier,
                    onClick = { onListVisible(!listVisible) },
                    label = { Text(stringResource(R.string.l10n_app_contacts_contacts_trace_mode_list)) },
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = true,
                    modifier = controlModifier,
                    onClick = {},
                    label = { Text(stringResource(R.string.l10n_app_map_map_style_standard)) },
                )
                FilterChip(
                    selected = false,
                    modifier = controlModifier,
                    enabled = false,
                    onClick = {},
                    label = { Text(stringResource(R.string.l10n_app_map_map_style_satellite)) },
                )
                FilterChip(
                    selected = false,
                    modifier = controlModifier,
                    enabled = false,
                    onClick = {},
                    label = { Text(stringResource(R.string.l10n_app_map_map_style_topo)) },
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = filter.favoritesOnly,
                    modifier = controlModifier,
                    onClick = { onFilter { it.setFavoritesOnly(!it.favoritesOnly) } },
                    label = { Text(stringResource(R.string.l10n_app_contacts_contacts_pathedit_filter_favorites)) },
                )
                FilterChip(
                    selected = filter.showDiscovered,
                    modifier = controlModifier,
                    enabled = !filter.favoritesOnly,
                    onClick = { onFilter { it.setShowDiscovered(!it.showDiscovered) } },
                    label = { Text(stringResource(R.string.l10n_app_map_map_callout_discovered)) },
                )
                FilterChip(
                    selected = clustering,
                    modifier = controlModifier,
                    onClick = { onClustering(!clustering) },
                    label = { Text(stringResource(R.string.l10n_app_map_map_controls_clusternodes)) },
                )
                FilterChip(
                    selected = labels,
                    modifier = controlModifier,
                    onClick = { onLabels(!labels) },
                    label = { Text(stringResource(R.string.l10n_app_map_map_controls_showlabels)) },
                )
                ContactMapType.entries.forEach { type ->
                    FilterChip(
                        selected = filter.allows(type),
                        modifier = controlModifier,
                        enabled = !filter.favoritesOnly,
                        onClick = {
                            onFilter { it.setContactType(type, !it.allows(type), MapFilterHost.MAIN_MAP) }
                        },
                        label = {
                            Text(
                                stringResource(
                                    when (type) {
                                        ContactMapType.CHAT -> R.string.l10n_app_map_map_nodekind_chatcontact
                                        ContactMapType.REPEATER -> R.string.l10n_app_map_map_nodekind_repeater
                                        ContactMapType.ROOM -> R.string.l10n_app_map_map_nodekind_room
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineMapsPanel(
    controller: com.meshcoreone.android.core.maps.OfflineMapController,
    camera: MapCamera?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val packs by controller.packs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var regionName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var deleting by remember { mutableStateOf<OfflinePack?>(null) }

    Card(modifier.fillMaxWidth(0.9f).padding(12.dp)) {
        LazyColumn(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.l10n_app_settings_offlinemaps_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.l10n_app_map_map_common_done))
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = regionName,
                    onValueChange = { regionName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.l10n_app_settings_offlinemaps_regionname)) },
                )
            }
            item {
                Button(
                    enabled = regionName.isNotBlank() && camera != null,
                    onClick = {
                        val bounds = camera?.toBounds() ?: return@Button
                        scope.launch {
                            controller.downloadRegion(
                                name = regionName.trim(),
                                bounds = bounds,
                                layers = setOf(OfflineLayer.BASE),
                            ).onSuccess {
                                regionName = ""
                            }.onFailure {
                                error = it
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.l10n_app_settings_offlinemaps_downloadregion))
                }
            }
            if (packs.isEmpty()) {
                item { Text(stringResource(R.string.l10n_app_settings_offlinemaps_emptydescription)) }
            } else {
                items(packs, key = { it.id }) { pack ->
                    OfflinePackRow(
                        pack = pack,
                        onPauseResume = {
                            scope.launch {
                                try {
                                    if (pack.state == OfflinePackState.PAUSED) controller.resume(pack) else controller.pause(pack)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure
                                }
                            }
                        },
                        onDelete = { deleting = pack },
                    )
                }
            }
        }
    }

    error?.let { failure ->
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = {
                TextButton(onClick = { error = null }) {
                    Text(stringResource(R.string.l10n_app_map_map_common_done))
                }
            },
            text = { Text(offlineErrorMessage(failure)) },
        )
    }
    deleting?.let { pack ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        scope.launch {
                            try {
                                controller.delete(pack)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.l10n_app_localizable_common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.l10n_app_settings_offlinemaps_cancel))
                }
            },
            text = { Text(pack.name) },
        )
    }
}

@Composable
private fun OfflinePackRow(
    pack: OfflinePack,
    onPauseResume: () -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(pack.name, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    when (pack.state) {
                        OfflinePackState.COMPLETE -> R.string.l10n_app_settings_offlinemaps_complete
                        OfflinePackState.PAUSED -> R.string.l10n_app_settings_offlinemaps_paused
                        else -> R.string.l10n_app_settings_offlinemaps_downloading
                    },
                ),
            )
            LinearProgressIndicator(
                progress = { pack.completedFraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pack.state != OfflinePackState.COMPLETE) {
                    TextButton(onClick = onPauseResume) {
                        Text(
                            stringResource(
                                if (pack.state == OfflinePackState.PAUSED) {
                                    R.string.l10n_app_settings_offlinemaps_resume
                                } else {
                                    R.string.l10n_app_settings_offlinemaps_pause
                                },
                            ),
                        )
                    }
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.l10n_app_localizable_common_delete))
                }
            }
        }
    }
}

@Composable
private fun offlineErrorMessage(failure: Throwable): String = when (failure) {
    OfflineMapError.InsufficientDiskSpace ->
        stringResource(R.string.l10n_app_settings_offlinemaps_error_insufficientdiskspace)
    OfflineMapError.NetworkUnavailable ->
        stringResource(R.string.l10n_app_settings_offlinemaps_nonetwork)
    else -> failure.message ?: stringResource(R.string.l10n_app_settings_offlinemaps_unknownregion)
}

private fun MapCamera.toBounds(): GeoBounds {
    val halfLatitude = latitudeSpan / 2.0
    val halfLongitude = longitudeSpan / 2.0
    val south = (center.latitude - halfLatitude).coerceAtLeast(-85.0)
    val north = (center.latitude + halfLatitude).coerceAtMost(85.0)
    val west = normalizeLongitude(center.longitude - halfLongitude)
    val east = normalizeLongitude(center.longitude + halfLongitude)
    return GeoBounds(GeoPoint(south, west), GeoPoint(north, east))
}

private fun normalizeLongitude(value: Double): Double {
    var normalized = value
    while (normalized < -180.0) normalized += 360.0
    while (normalized > 180.0) normalized -= 360.0
    return normalized
}


private val FocusedPointSaver = Saver<GeoPoint?, List<Double>>(
    save = { point -> point?.let { listOf(it.latitude, it.longitude) } },
    restore = { coordinates -> GeoPoint(coordinates[0], coordinates[1]) },
)
private val CameraSaver = Saver<MapCamera?, String>(
    save = { it?.encode() },
    restore = MapCamera::decode,
)

private const val PREFERENCES = "shared-map"
private const val CAMERA_KEY = "camera-v1"
