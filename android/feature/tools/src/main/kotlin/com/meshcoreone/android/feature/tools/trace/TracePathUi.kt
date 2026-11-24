// PortedFrom: MC1/Views/Tools/TracePath/TracePathView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/TracePathListView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/SavedPathsSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/TraceResultsSectionView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/TotalDistanceRow.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/DistanceInfoSheetView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/SavePathRowView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/ComparisonRowView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/Map/PathActionsSectionView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/Map/TracePathFloatingButtonsView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.trace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings as T
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.SavedTracePathDTO
import com.meshcoreone.android.core.model.TracePathRunDTO
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
import com.meshcoreone.android.core.maps.effectiveAppThemeIsDark
import com.meshcoreone.android.core.l10n.generated.AppMapStrings as M
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import com.meshcoreone.android.core.l10n.R as Strings
import java.text.NumberFormat

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
            override fun pathNamePrefix(prefix: String) = C.contactsPathNamePrefix(resources, prefix)
            override fun pathNameTwoEndpoints(first: String, last: String) = C.contactsPathNameTwoEndpoints(resources, first, last)
            override fun pathNameMultipleEndpoints(first: String, last: String) = C.contactsPathNameMultipleEndpoints(resources, first, last)
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
    ) {
        val originatingToken = feature.originatingSessionToken(generation)
    }

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
        val savedPaths = remember(dependencies.feature, dependencies.generation, dependencies.radioId, dependencies.originatingToken) {
            SavedPathsStateHolder(savedStrings, dependencies.diagnostics).also { saved ->
                saved.configure(object : SavedPathsDependencies {
                    override fun sessionToken() = dependencies.feature.sessionToken()
                    override fun isOriginatingSessionCurrent(generation: Int, token: com.meshcoreone.android.core.contracts.domain.SessionToken?) =
                        dependencies.feature.isOriginatingSessionCurrent(generation, token)
                    override fun savedPaths() = dependencies.feature.savedPaths()
                    override fun connectedDevice() = dependencies.feature.connectedDevice()
                }, dependencies.originatingToken, dependencies.generation)
            }
        }
        DisposableEffect(savedPaths) { onDispose { savedPaths.retireConnection() } }

        DisposableEffect(holder, dependencies.generation, dependencies.radioId, dependencies.originatingToken) {
            holder.configure(dependencies.feature)
            holder.bindConnection(dependencies.radioId, dependencies.generation, dependencies.originatingToken)
            holder.startListening()
            onDispose { holder.retireConnection() }
        }
        LaunchedEffect(
            holder,
            dependencies.generation,
            dependencies.radioId,
            dependencies.originatingToken,
            dependencies.nodeTableVersion,
        ) {
            dependencies.radioId?.let { holder.loadContacts(it) }
        }
        val state by holder.state.collectAsStateWithLifecycle()
        val savedState by savedPaths.state.collectAsStateWithLifecycle()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        var location by remember(dependencies.feature) { mutableStateOf<Coordinate?>(null) }
        LaunchedEffect(dependencies.feature, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    dependencies.feature.locations.collect { location = it }
                } finally {
                    location = null
                }
            }
        }
        TracePathContent(
            state, holder, savedState, savedPaths, dependencies.mapPreferences,
            location, dependencies.diagnostics,
            mapSurface, dependencies,
        )
    }
}

@Composable
private fun rememberSavedPathStrings(): SavedPathsStrings {
    val loadFailed = stringResource(T.toolsSavedPathsLoadFailed)
    val renameFailed = stringResource(T.toolsSavedPathsRenameFailed)
    val deleteFailed = stringResource(T.toolsSavedPathsDeleteFailed)
    return remember(loadFailed, renameFailed, deleteFailed) {
        object : SavedPathsStrings {
            override val loadFailed = loadFailed
            override val renameFailed = renameFailed
            override val deleteFailed = deleteFailed
        }
    }
}

private data class TraceSavedDeleteSelection(val path: SavedTracePathDTO)
private data class TraceSavedRenameSelection(val path: SavedTracePathDTO)
private data class TraceViewSelection(val showMap: Boolean? = null, val failed: Boolean = false)

@Composable
private fun savedPathDisplayLabel(path: SavedTracePathDTO): String {
    if (path.name.isNotBlank()) return path.name
    val hex = TracePathHex.chunks(path.pathBytes, path.hashSize.coerceIn(1, Int.MAX_VALUE.toLong()).toInt())
        .joinToString(",")
    return if (hex.isEmpty()) stringResource(C.contactsTraceMapDefaultPathName)
    else C.contactsPathNamePrefix(LocalContext.current.resources, hex)
}

@Composable
internal fun TracePathContent(
    state: TracePathState,
    holder: TracePathStateHolder,
    savedState: SavedPathsState,
    savedPaths: SavedPathsStateHolder,
    mapPreferences: MapPreferenceStore?,
    location: Coordinate?,
    diagnostics: TraceDiagnostics,
    mapSurface: TraceMapSurface,
    connection: TracePathScreen.Dependencies,
) {
    val connectionGeneration = connection.generation
    val radioId = connection.radioId
    val viewPreferences = connection.feature.viewPreferences
    var viewRetry by remember(viewPreferences) { mutableStateOf(0) }
    var viewWriteFailed by remember(viewPreferences) { mutableStateOf(false) }
    var writingView by remember(viewPreferences) { mutableStateOf(false) }
    var showMap by remember(viewPreferences) { mutableStateOf(false) }
    val viewSelections = remember(viewPreferences, viewRetry) {
        viewPreferences?.showMap?.map { TraceViewSelection(it) }?.catch { error ->
            if (error is CancellationException) throw error
            if (error !is Exception) throw error
            diagnostics.failure("trace-read-view-mode", error)
            emit(TraceViewSelection(failed = true))
        } ?: flowOf(TraceViewSelection(failed = true))
    }
    val viewSelection by viewSelections.collectAsStateWithLifecycle(initialValue = TraceViewSelection())
    val viewWriteFailures = remember(viewPreferences) { viewPreferences?.writeFailure ?: flowOf(null) }
    val lastWriteFailure by viewWriteFailures.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(viewSelection, lastWriteFailure) {
        if (!writingView && !viewWriteFailed) {
            viewSelection.showMap?.let { showMap = it }
        }
    }
    var showSaved by remember(connectionGeneration, radioId, connection.originatingToken) { mutableStateOf(false) }
    var renameSelection by remember(savedPaths) { mutableStateOf<TraceSavedRenameSelection?>(null) }
    var renameText by rememberSaveable(connectionGeneration, radioId?.toString(), connection.originatingToken) { mutableStateOf("") }
    var renaming by remember(savedPaths) { mutableStateOf(false) }
    val pathToDelete = remember(savedPaths) { mutableStateOf<TraceSavedDeleteSelection?>(null) }
    val savedListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun selectView(map: Boolean) {
        if (writingView) return
        showMap = map
        if (viewPreferences == null) return
        writingView = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                viewPreferences.setShowMap(map)
                viewWriteFailed = false
                if (viewSelection.failed) viewRetry++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                diagnostics.failure("trace-write-view-mode", error)
                viewWriteFailed = true
            } finally {
                writingView = false
            }
        }
    }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state.errorHapticTrigger) {
        if (state.errorHapticTrigger > 0) haptics.performHapticFeedback(HapticFeedbackType.Reject)
    }
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
                    onClick = { selectView(false) },
                    enabled = !writingView,
                    label = { Text(stringResource(C.contactsTraceModeList)) },
                )
                FilterChip(
                    selected = showMap,
                    onClick = { selectView(true) },
                    enabled = !writingView,
                    label = { Text(stringResource(C.contactsTraceModeMap)) },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            TextButton(onClick = { showSaved = true; scope.launch { savedPaths.loadSavedPaths() } }) {
                Text(stringResource(C.contactsTraceSaved))
            }
        }
        if (viewSelection.failed || viewWriteFailed || lastWriteFailure != null) {
            Text(
                if (viewWriteFailed || lastWriteFailure != null) L.errorPersistenceSaveFailed(
                    LocalContext.current.resources, stringResource(C.contactsTraceViewMode),
                ) else stringResource(L.commonErrorFailedToLoad),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (viewSelection.failed && viewPreferences != null) {
                TextButton(onClick = { viewRetry++ }) { Text(stringResource(M.mapPreviewRetryButtonAccessibilityLabel)) }
            }
        }
        if (showMap) TracePathMapContent(state, holder, mapPreferences, location, diagnostics, mapSurface, connection)
        else TracePathListContent(state, holder, codeInput, connection, location)
    }
    val deletion = pathToDelete.value
    if (showSaved && deletion != null) {
        val path = deletion.path
        val dismissDelete = {
            if (pathToDelete.value === deletion) pathToDelete.value = null
        }
        val confirmDelete = {
            if (pathToDelete.value === deletion && savedPaths.isCurrentPath(path)) {
                pathToDelete.value = null
                scope.launch {
                    if (savedPaths.isCurrentPath(path) && savedPaths.deletePath(path) &&
                        savedPaths.isCurrentConnection(path.radioId)
                    ) {
                        holder.handleSavedPathDeleted(path.id)
                    }
                }
            }
        }
        AlertDialog(
            onDismissRequest = dismissDelete,
            title = { Text(stringResource(C.contactsSavedPathsDeleteTitle)) },
            text = { Text(C.contactsSavedPathsDeleteMessage(LocalContext.current.resources, savedPathDisplayLabel(path))) },
            confirmButton = {
                TextButton(
                    onClick = confirmDelete,
                    modifier = Modifier.semantics { onClick { confirmDelete(); true } },
                ) { Text(stringResource(C.contactsCommonDelete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = dismissDelete) { Text(stringResource(C.contactsCommonCancel)) }
            },
        )
    } else if (showSaved && renameSelection == null) {
        AlertDialog(
            onDismissRequest = { showSaved = false; renameSelection = null },
            title = { Text(stringResource(C.contactsTraceSaved)) },
            text = {
                when {
                    savedState.isLoading -> CircularProgressIndicator()
                    else -> Column {
                        savedState.errorMessage?.let { error ->
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            if (savedState.error == SavedPathsError.LOAD) {
                                TextButton(
                                    onClick = { scope.launch { savedPaths.loadSavedPaths() } },
                                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                                ) { Text(stringResource(T.toolsLineOfSightRetry)) }
                            }
                        }
                        if (savedState.savedPaths.isEmpty() && savedState.errorMessage == null) {
                            Text(stringResource(C.contactsTraceListEmptyPath))
                        }
                        LazyColumn(Modifier.weight(1f, fill = false), state = savedListState) {
                            items(savedState.savedPaths, key = { it.id }) { path ->
                                val deleteLabel = stringResource(L.commonDelete)
                                val pathLabel = savedPathDisplayLabel(path)
                                FlowRow(
                                    Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp),
                                    itemVerticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    TextButton(onClick = {
                                        if (savedPaths.isCurrentPath(path)) {
                                            holder.loadSavedPath(path)
                                            showSaved = false
                                        }
                                    }) {
                                        Text(pathLabel)
                                    }
                                    val renameLabel = stringResource(C.contactsSavedPathsRename)
                                    TextButton(
                                        modifier = Modifier.semantics { contentDescription = "$renameLabel: $pathLabel" },
                                        onClick = {
                                            if (savedPaths.isCurrentPath(path)) {
                                                savedPaths.clearRenameError()
                                                renameText = path.name
                                                renameSelection = TraceSavedRenameSelection(path)
                                            }
                                        },
                                    ) { Text(renameLabel) }
                                    TextButton(modifier = Modifier.semantics {
                                        contentDescription = "$deleteLabel: $pathLabel"
                                    }, onClick = {
                                        if (savedPaths.isCurrentPath(path)) pathToDelete.value = TraceSavedDeleteSelection(path)
                                    }) {
                                        Text(stringResource(L.commonDelete))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSaved = false; renameSelection = null }) { Text(stringResource(L.commonDone)) }
            },
        )
    }
    if (showSaved) renameSelection?.let { selection ->
        val path = selection.path
        val dismissRename = {
            if (renameSelection === selection && !renaming) renameSelection = null
        }
        AlertDialog(
            onDismissRequest = dismissRename,
            title = { Text(stringResource(C.contactsSavedPathsRenameTitle)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { if (renameSelection === selection && !renaming) renameText = it },
                        label = { Text(stringResource(C.contactsDetailName)) },
                        singleLine = true,
                        enabled = !renaming,
                    )
                    if (savedState.error == SavedPathsError.RENAME) {
                        Text(
                            savedState.errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = dismissRename, enabled = !renaming) {
                    Text(stringResource(L.commonCancel))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !renaming,
                    onClick = {
                        if (renameSelection === selection && !renaming && savedPaths.isCurrentPath(path)) {
                            val name = renameText
                            renaming = true
                            scope.launch {
                                try {
                                    if (savedPaths.renamePath(path, name) && renameSelection === selection) {
                                        renameSelection = null
                                    }
                                } finally {
                                    renaming = false
                                }
                            }
                        }
                    },
                ) { Text(stringResource(L.commonSave)) }
            },
        )
    }
    state.errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = holder::clearError,
            title = { Text(stringResource(C.contactsTraceFailed)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = holder::clearError) {
                    Text(stringResource(C.contactsCommonOk))
                }
            },
        )
    }
}

@Composable
internal fun TracePathListContent(
    state: TracePathState,
    holder: TracePathStateHolder,
    codeInput: TraceCodeInputState,
    connection: TracePathScreen.Dependencies? = null,
    location: Coordinate? = null,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val canEdit = {
        if (connection == null) holder.isEditingCurrent()
        else holder.isBoundEditingCurrent(connection.radioId, connection.generation, connection.originatingToken)
    }
    var showClearConfirmation by remember(connection?.radioId, connection?.generation, connection?.originatingToken) {
        mutableStateOf(false)
    }
    var showHopPicker by rememberSaveable(connection?.radioId?.toString(), connection?.generation, connection?.originatingToken) {
        mutableStateOf(false)
    }
    val requestClear = { if (canEdit()) showClearConfirmation = true }
    val confirmClear = {
        if (canEdit()) {
            showClearConfirmation = false
            holder.clearPath()
        }
    }
    val savingPath = remember(connection?.radioId, connection?.generation, connection?.originatingToken) {
        mutableStateOf(false)
    }
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
                val setAutoReturn: (Boolean) -> Unit = { if (canEdit()) holder.setAutoReturnPath(it) }
                Switch(state.autoReturnPath, setAutoReturn, modifier = Modifier.semantics {
                    onClick { setAutoReturn(!state.autoReturnPath); true }
                })
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
                    if (canEdit() && index > 0 && holder.state.value.outboundPath.getOrNull(index)?.id == hop.id) {
                        holder.moveRepeater(setOf(index), index - 1)
                    }
                }
                val moveDown = {
                    val livePath = holder.state.value.outboundPath
                    if (canEdit() && index < livePath.lastIndex && livePath.getOrNull(index)?.id == hop.id) {
                        holder.moveRepeater(setOf(index), index + 2)
                    }
                }
                val remove = {
                    if (canEdit() && holder.state.value.outboundPath.getOrNull(index)?.id == hop.id) {
                        holder.removeRepeater(index)
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(
                                    com.meshcoreone.android.core.l10n.R.string.l10n_app_contacts_contacts_trace_list_hoplabel,
                                    index + 1,
                                    hop.displayText,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(hop.hashHex, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                        }
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
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
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                                    contentDescription = "$deleteLabel: $hopLabel"
                                    onClick { remove(); true }
                                },
                            ) {
                                Text(deleteLabel)
                            }
                        }
                    }
                }
            }
            item(key = "trace-clear-path") {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.outboundPath.isNotEmpty()) {
                        Text(
                            state.fullPathString,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (state.displayedResult == null) {
                            val copyPath = {
                                if (canEdit() && holder.state.value.fullPathData == state.fullPathData &&
                                    holder.state.value.fullPathString == state.fullPathString
                                ) {
                                    clipboard.setText(AnnotatedString(state.fullPathString))
                                }
                            }
                            TextButton(onClick = copyPath, modifier = Modifier.sizeIn(minHeight = 48.dp).semantics {
                                onClick { copyPath(); true }
                            }) { Text(stringResource(C.contactsTraceListCopyPath)) }
                        }
                    }
                    OutlinedButton(
                        onClick = requestClear,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).semantics {
                            onClick { requestClear(); true }
                        },
                    ) {
                        Text(stringResource(C.contactsTraceClearPath))
                    }
                }
            }
        }
        item(key = "trace-code-input") { TraceCodeInput(state, holder, codeInput, canEdit) }
        item(key = "trace-hop-picker") {
            val label = stringResource(C.contactsPathEditAddHop)
            val open = { if (canEdit()) showHopPicker = true }
            OutlinedButton(
                onClick = open,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).semantics {
                    contentDescription = label
                    onClick { open(); true }
                },
            ) { Text(label) }
        }
        item(key = "trace-hash-width") {
            val supportsWidthChoice = holder.deps.connectedDevice()?.supportsTraceHashSizeOverride != false
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0u.toUByte(), 1u.toUByte(), 2u.toUByte()).forEach { mode ->
                    val bytes = 1 shl mode.toInt()
                    val setMode = {
                        if (canEdit() && holder.deps.connectedDevice()?.supportsTraceHashSizeOverride != false) {
                            holder.setTraceHashMode(mode)
                        }
                    }
                    FilterChip(
                        selected = holder.effectiveTraceMode == mode,
                        onClick = setMode,
                        enabled = supportsWidthChoice,
                        modifier = Modifier.semantics { onClick { setMode(); true } },
                        label = { Text("$bytes B") },
                    )
                }
            }
        }
        item(key = "trace-batch") {
            TraceBatchControls(state, holder, canEdit)
        }
        item(key = "trace-results") {
            TraceResults(state, location) { result ->
                canEdit() && holder.state.value.displayedResult?.id == result.id
            }
        }
        item(key = "trace-save-path") { TraceSavePathAction(state, holder, connection, scope, savingPath) }
        item(key = "trace-run") {
            val canExecute = { holder.deps.connectedDevice() != null && canEdit() }
            val runOrCancel = {
                if (canExecute()) {
                    if (state.isRunning) holder.cancelBatchTrace()
                    else holder.scope.launch { if (canExecute()) holder.runBatchTrace() }
                }
            }
            Button(
                onClick = runOrCancel,
                enabled = state.isRunning || state.canRunTraceWhenConnected && holder.deps.connectedDevice() != null,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).semantics {
                    onClick { runOrCancel(); true }
                },
            ) {
                if (state.isRunning) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp))
                    Text(stringResource(C.contactsTraceListRunningTrace))
                } else {
                    Text(stringResource(C.contactsTraceListRunTrace))
                }
            }
        }
        item(key = "trace-end") { Column(Modifier.padding(bottom = 24.dp)) {} }
    }
    if (showHopPicker) {
        TraceHopPicker(state, holder, canEdit) { showHopPicker = false }
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
                TextButton(onClick = confirmClear, modifier = Modifier.semantics {
                    onClick { confirmClear(); true }
                }) {
                    Text(stringResource(C.contactsTraceClearPath))
                }
            },
        )
    }
}

@Composable
private fun TraceBatchControls(state: TracePathState, holder: TracePathStateHolder, canEdit: () -> Boolean) {
    val batchLabel = stringResource(C.contactsTraceListBatchTrace)
    Column {
        Row(
            Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(batchLabel, style = MaterialTheme.typography.titleMedium)
            val setBatch: (Boolean) -> Unit = {
                if (canEdit()) holder.setBatchEnabled(it)
            }
            Switch(state.batchEnabled, setBatch, modifier = Modifier.semantics {
                contentDescription = batchLabel
                onClick { setBatch(!state.batchEnabled); true }
            })
        }
        if (state.batchEnabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                Text(stringResource(C.contactsTraceListTraces), modifier = Modifier.align(Alignment.CenterVertically))
                listOf(3, 5).forEach { size ->
                    val select = {
                        if (canEdit() && !holder.state.value.isRunning && holder.state.value.batchEnabled) {
                            holder.setBatchSize(size)
                        }
                    }
                    FilterChip(
                        selected = state.batchSize == size,
                        onClick = select,
                        enabled = !state.isRunning,
                        label = { Text(size.toString()) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                            onClick { select(); true }
                        },
                    )
                }
            }
        }
    }
}

private data class TraceSaveDialogState(
    val open: Boolean = false,
    val name: String = "",
    val failed: Boolean = false,
)

@Composable
private fun TraceSavePathAction(
    state: TracePathState,
    holder: TracePathStateHolder,
    connection: TracePathScreen.Dependencies?,
    scope: CoroutineScope,
    saving: MutableState<Boolean>,
) {
    val dialog = remember(
        connection?.radioId, connection?.generation, connection?.originatingToken,
        state.fullPathData,
    ) { mutableStateOf(TraceSaveDialogState()) }
    LaunchedEffect(state.displayedResult?.success, state.isRunningSavedPath) {
        if (!saving.value && (state.displayedResult?.success != true || state.isRunningSavedPath)) {
            dialog.value = TraceSaveDialogState()
        }
    }
    val canSave = {
        val live = holder.state.value
        val currentConnection = if (connection == null) holder.isConnectionCurrent()
        else holder.isBoundConnectionCurrent(connection.radioId, connection.generation, connection.originatingToken)
        currentConnection && holder.deps.connectedDevice() != null &&
            live.canSavePath && !live.isRunningSavedPath &&
            live.fullPathData == state.fullPathData && live.result?.id == state.result?.id &&
            live.completedResults == state.completedResults
    }
    if (state.displayedResult?.success != true || state.isRunningSavedPath) return
    val requestSave = {
        if (canSave() && !saving.value) {
            dialog.value = TraceSaveDialogState(open = true, name = holder.generatePathName())
        }
    }
    OutlinedButton(
        onClick = requestSave,
        enabled = state.canSavePath && holder.deps.connectedDevice() != null && !saving.value,
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).semantics {
            onClick { requestSave(); true }
        },
    ) {
        Text(stringResource(C.contactsResultsSavePath))
    }
    if (dialog.value.open) {
        val dismiss = {
            if (!saving.value) dialog.value = TraceSaveDialogState()
        }
        val confirmSave = {
            if (dialog.value.open && !saving.value && dialog.value.name.isNotBlank() && canSave()) {
                val name = dialog.value.name
                saving.value = true
                dialog.value = dialog.value.copy(failed = false)
                scope.launch {
                    try {
                        if (canSave()) {
                            val saved = holder.savePath(name)
                            dialog.value = if (saved) TraceSaveDialogState()
                            else dialog.value.copy(failed = true)
                        } else {
                            dialog.value = TraceSaveDialogState()
                        }
                    } finally {
                        saving.value = false
                    }
                }
            }
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(C.contactsTraceMapSaveTitle)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = dialog.value.name,
                        onValueChange = { name ->
                            if (!saving.value && canSave()) dialog.value = dialog.value.copy(name = name, failed = false)
                        },
                        label = { Text(stringResource(C.contactsTraceMapPathName)) },
                        singleLine = true,
                        enabled = !saving.value,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (saving.value) CircularProgressIndicator()
                    if (dialog.value.failed) {
                        Text(
                            stringResource(C.contactsTraceMapSaveFailedMessage),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss, enabled = !saving.value, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                    Text(stringResource(L.commonCancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = confirmSave,
                    enabled = dialog.value.name.isNotBlank() && state.canSavePath && !saving.value,
                    modifier = Modifier.sizeIn(minHeight = 48.dp).semantics { onClick { confirmSave(); true } },
                ) {
                    Text(stringResource(L.commonSave))
                }
            },
        )
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
    connection: TracePathScreen.Dependencies,
) {
    if (preferenceStore == null) {
        Text(
            stringResource(L.commonErrorFailedToLoad),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    val preferences = preferenceStore
    val map = remember(holder) { TraceMapPathLogic(holder).also { it.configure(location) } }
    val radioId = connection.radioId
    val generation = connection.generation
    val originatingToken = connection.originatingToken
    val canEdit = remember(holder, radioId, generation, originatingToken) {
        { holder.isBoundEditingCurrent(radioId, generation, originatingToken) }
    }
    val scope = rememberCoroutineScope()
    var settings by remember(preferences) { mutableStateOf<MapPreferences?>(null) }
    val haptics = LocalHapticFeedback.current
    var preferenceError by remember(preferences) { mutableStateOf(false) }
    var retry by remember(preferences) { mutableStateOf(0) }
    var camera by remember(holder) { mutableStateOf<MapCamera?>(null) }
    var cameraCommandRevision by remember(holder) { mutableLongStateOf(0L) }
    var presentation by remember(holder) { mutableStateOf(MapPresentationState()) }
    var confirmClear by remember(holder, radioId, generation, originatingToken) { mutableStateOf(false) }
    var showResults by rememberSaveable(holder, radioId, generation, originatingToken) { mutableStateOf(false) }
    val savingPath = remember(holder, radioId, generation, originatingToken) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val locale = LocalConfiguration.current.locales[0]
    val unit = stringResource(com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings.remoteNodesStatusSnrBadgeUnit)
    val runningDescription = stringResource(C.contactsTraceListRunningLabel)
    val badgeFormatter = remember(locale, unit) { TraceMapBadgeFormat(locale, unit) }
    val formatBadge: (Double, Double) -> String = remember(badgeFormatter) { badgeFormatter::format }
    val appIsDark = effectiveAppThemeIsDark()
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
    fun requestCamera(target: MapCamera?) {
        if (target != null) {
            camera = target
            cameraCommandRevision++
        }
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
    val requestClear = { if (canEdit()) confirmClear = true }
    val clearPath = {
        if (canEdit()) {
            map.clearPath()
            refresh()
        }
        confirmClear = false
    }
    val canRunTrace = {
        loaded != null && holder.state.value.canRunTraceWhenConnected &&
            holder.deps.connectedDevice() != null && canEdit()
    }
    val runTrace = {
        if (canRunTrace()) {
            requestCamera(map.pathCamera())
            holder.scope.launch { if (canRunTrace()) holder.runBatchTrace() }
        }
    }
    val hasResults = state.displayedResult != null || state.batchStatus != null
    LaunchedEffect(hasResults) {
        if (!hasResults) showResults = false
    }
    val viewResults = {
        val current = holder.state.value
        if (canEdit() && (current.displayedResult != null || current.batchStatus != null)) {
            showResults = true
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (loaded == null && !preferenceError) {
            CircularProgressIndicator(Modifier.padding(16.dp).testTag("trace-map:preferences-loading"))
        }
        if (preferenceError) {
            Text(stringResource(L.commonErrorFailedToLoad), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { retry++ }) { Text(stringResource(M.mapPreviewRetryButtonAccessibilityLabel)) }
        }
        if (loaded != null) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = loaded.filter.favoritesOnly,
                    onClick = { write("trace-map-write-filter") {
                        preferences.updateFilter(MapFilterHost.TRACE_PATH) {
                            it.setFavoritesOnly(!it.favoritesOnly)
                        }
                    } },
                    label = { Text(stringResource(C.contactsPathEditFilterFavorites)) },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                )
                FilterChip(
                    selected = loaded.filter.effectiveShowDiscovered, enabled = !loaded.filter.favoritesOnly,
                    onClick = { write("trace-map-write-filter") {
                        preferences.updateFilter(MapFilterHost.TRACE_PATH) {
                            it.setShowDiscovered(!it.showDiscovered)
                        }
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
                FilterChip(
                    selected = loaded.clustering,
                    onClick = { write("trace-map-write-clustering") { preferences.setClustering(!loaded.clustering) } },
                    label = { Text(stringResource(M.mapControlsClusterNodes)) },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                )
            }
        }
        Box(Modifier.weight(1f)) {
            mapSurface(
                presentation.copy(camera = camera, cameraCommandRevision = cameraCommandRevision, isDark = when (loaded?.appearance) {
                    MapColorPreference.DARK -> true
                    MapColorPreference.LIGHT -> false
                    else -> appIsDark
                }),
                loaded?.clustering ?: false, loaded?.labels ?: true, { camera = it },
                { marker -> if (loaded != null && canEdit()) {
                    if (map.handleMapPointTap(marker.id) == PathPinTapResult.REJECTED_MIDDLE_HOP) {
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    }
                    refresh()
                } },
                {
                    Column(Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                        TextButton(
                            onClick = { requestCamera(map.allPinsCamera()) }, enabled = loaded != null && map.pins.isNotEmpty(),
                            modifier = Modifier.testTag("trace-map:center-all").sizeIn(minHeight = 48.dp),
                        ) { Text(stringResource(M.mapControlsCenterAll)) }
                        TextButton(onClick = { requestCamera(map.pathCamera()) }, enabled = loaded != null && map.hasPath) {
                            Text(stringResource(C.contactsTraceMapCenterOnPath))
                        }
                    }
                },
            )
        }
        TraceBatchControls(state, holder, canEdit)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.outboundPath.isNotEmpty() && state.displayedResult == null) {
                val copyPath = {
                    if (canEdit() && holder.state.value.fullPathData == state.fullPathData &&
                        holder.state.value.fullPathString == state.fullPathString
                    ) {
                        clipboard.setText(AnnotatedString(state.fullPathString))
                    }
                }
                TextButton(onClick = copyPath, modifier = Modifier.sizeIn(minHeight = 48.dp).semantics {
                    onClick { copyPath(); true }
                }) { Text(stringResource(C.contactsTraceListCopyPath)) }
            }
            TextButton(
                onClick = requestClear,
                enabled = state.outboundPath.isNotEmpty() && canEdit(),
                modifier = Modifier.semantics { onClick { requestClear(); true } },
            ) {
                Text(stringResource(C.contactsTraceMapClear))
            }
            Button(
                onClick = runTrace,
                enabled = canRunTrace(),
                modifier = Modifier.sizeIn(minHeight = 48.dp).semantics {
                    if (state.isRunning) {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = runningDescription
                    }
                    onClick { runTrace(); true }
                },
            ) {
                if (state.isRunning) {
                    CircularProgressIndicator(
                        Modifier.padding(end = 8.dp).size(18.dp).clearAndSetSemantics {},
                        strokeWidth = 2.dp,
                    )
                    Text(stringResource(C.contactsTraceListRunningTrace))
                } else {
                    Text(stringResource(C.contactsTraceListRunTrace))
                }
            }
            if (hasResults) {
                TextButton(
                    onClick = viewResults,
                    modifier = Modifier.sizeIn(minHeight = 48.dp).semantics { onClick { viewResults(); true } },
                ) { Text(stringResource(C.contactsTraceMapViewResults)) }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(C.contactsTraceClearPath)) },
        text = { Text(stringResource(C.contactsTraceClearPathMessage)) },
        confirmButton = { TextButton(
            onClick = clearPath,
            enabled = canEdit(),
            modifier = Modifier.testTag("trace-map:confirm-clear").semantics {
                onClick { clearPath(); true }
            },
        ) {
            Text(stringResource(C.contactsTraceClearPath))
        } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(L.commonCancel)) } },
    )
    if (showResults && hasResults) AlertDialog(
        onDismissRequest = { showResults = false },
        title = { Text(stringResource(C.contactsTraceMapViewResults)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TraceResults(state, location) { result ->
                    canEdit() && holder.state.value.displayedResult?.id == result.id
                }
                TraceSavePathAction(state, holder, connection, scope, savingPath)
            }
        },
        confirmButton = {
            TextButton(onClick = { showResults = false }) { Text(stringResource(L.commonDone)) }
        },
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
internal fun TraceResults(
    state: TracePathState,
    location: Coordinate? = null,
    canCopyResult: (TraceResult) -> Boolean,
) {
    val result = state.displayedResult
    val distanceState = result?.let { state.copy(result = it).withResultLocation(location) } ?: state
    if (result != null || state.batchStatus != null) TraceResultCard(result, state, distanceState, canCopyResult)
}

@Composable
private fun TraceResultCard(
    result: TraceResult?,
    state: TracePathState,
    distanceState: TracePathState,
    canCopyResult: (TraceResult) -> Boolean,
) {
    val resources = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0]
    val clipboard = LocalClipboardManager.current
    var showDistanceInfo by rememberSaveable(result?.id?.toString()) { mutableStateOf(false) }
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
            } else if (result?.success == true && state.previousRun != null) {
                TraceRttComparison(result.durationMs, checkNotNull(state.previousRun))
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
            if (result != null) {
                Text(stringResource(C.contactsTraceListRoundTripPath), style = MaterialTheme.typography.bodySmall)
                Text(result.tracedPathString, style = MaterialTheme.typography.bodyMedium)
                val copyResult = {
                    if (canCopyResult(result)) clipboard.setText(AnnotatedString(result.tracedPathString))
                }
                TextButton(
                    onClick = copyResult,
                    modifier = Modifier.sizeIn(minHeight = 48.dp).semantics {
                        onClick { copyResult(); true }
                    },
                ) { Text(stringResource(C.contactsTraceListCopyPath)) }
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
            if (result?.success == true) {
                TraceDistanceRow(distanceState) { showDistanceInfo = true }
            }
        }
    }
    if (showDistanceInfo && result?.success == true) {
        TraceDistanceInfoSheet(result, distanceState) { showDistanceInfo = false }
    }
}

@Composable
private fun TraceDistanceRow(state: TracePathState, onShowInfo: () -> Unit) {
    val distance = state.totalPathDistance
    val label = stringResource(
        if (state.isDistanceUsingFallback) C.contactsResultsPartialDistanceLabel
        else C.contactsResultsDistanceUnavailableLabel,
    )
    val hint = stringResource(
        if (state.isDistanceUsingFallback) C.contactsResultsPartialDistanceHint
        else C.contactsResultsDistanceInfoHint,
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(C.contactsResultsTotalDistance),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        if (distance != null) {
            Text(
                "${"%.0f".format(distance)} m",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            if (state.isDistanceUsingFallback) {
                DistanceInfoButton(label, hint, onShowInfo)
            }
        } else {
            Text(stringResource(C.contactsResultsUnavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
            DistanceInfoButton(label, hint, onShowInfo)
        }
    }
}

@Composable
private fun DistanceInfoButton(label: String, hint: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.sizeIn(minHeight = 48.dp).semantics {
            contentDescription = "$label. $hint"
        },
    ) {
        Text(stringResource(C.contactsResultsDistanceInfo))
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TraceDistanceInfoSheet(
    result: TraceResult,
    state: TracePathState,
    onDismiss: () -> Unit,
) {
    val title = stringResource(
        if (state.isDistanceUsingFallback) C.contactsResultsDistanceInfoTitlePartial
        else C.contactsResultsDistanceInfoTitle,
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onClick = onDismiss, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                    Text(stringResource(L.commonDone))
                }
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when {
                    state.isDistanceUsingFallback -> {
                        DistanceInfoSection(
                            stringResource(C.contactsResultsPartialDistanceHeader),
                            stringResource(C.contactsResultsPartialDistanceExplanation),
                        )
                        DistanceInfoSection(
                            stringResource(C.contactsResultsFullPathHeader),
                            stringResource(C.contactsResultsFullPathTip),
                        )
                    }
                    result.hops.count { !it.isStartNode && !it.isEndNode } < 2 ->
                        Text(stringResource(C.contactsResultsNeedsRepeaters))
                    state.repeatersWithoutLocation.isEmpty() ->
                        Text(stringResource(C.contactsResultsDistanceError))
                    else -> {
                        Text(stringResource(C.contactsResultsMissingLocations))
                        Text(
                            stringResource(C.contactsResultsRepeatersWithoutLocations),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                        state.repeatersWithoutLocation.forEach { name -> Text(name) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DistanceInfoSection(heading: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            heading,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(body)
    }
}

@Composable
private fun TraceRttComparison(currentMs: Long, previous: TracePathRunDTO) {
    val locale = LocalConfiguration.current.locales[0]
    val comparison = RttComparison.of(currentMs, previous.roundTripMs)
    val percent = remember(locale, comparison.percentChange) {
        NumberFormat.getPercentInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 0
        }.format(abs(comparison.percentChange) / 100)
    }
    val date = remember(locale, previous.date) {
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date.from(previous.date))
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(C.contactsPathDetailRoundTrip), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$currentMs ms", style = MaterialTheme.typography.titleMedium)
            if (comparison.changed) {
                val direction = stringResource(
                    if (comparison.increased) C.contactsResultsComparisonIncreased else C.contactsResultsComparisonDecreased,
                )
                Text(
                    "${if (comparison.increased) "\u25B2" else "\u25BC"} $percent",
                    color = if (comparison.increased) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = "$direction, $percent" },
                )
            }
        }
        Text(
            stringResource(Strings.string.l10n_app_contacts_contacts_results_comparison, previous.roundTripMs, date),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
