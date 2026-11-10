// PortedFrom: MC1/Views/Tools/TracePath/TracePathView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/TracePathListView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/TraceResultsSectionView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/SavePathRowView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/ComparisonRowView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.trace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import com.meshcoreone.android.core.model.Coordinate
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
import kotlinx.coroutines.flow.collect
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
        TracePathContent(
            state, holder, savedState, savedPaths, dependencies.mapPreferences,
            dependencies.feature.bestAvailableLocation(), dependencies.diagnostics,
            mapSurface, dependencies,
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
    connection: TracePathScreen.Dependencies,
) {
    val connectionGeneration = connection.generation
    val radioId = connection.radioId
    var showMap by remember { mutableStateOf(false) }
    var showSaved by remember(connectionGeneration, radioId) { mutableStateOf(false) }
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
        if (showMap) TracePathMapContent(state, holder, mapPreferences, location, diagnostics, mapSurface, connection)
        else TracePathListContent(state, holder, codeInput, connection)
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
                            val deleteLabel = stringResource(L.commonDelete)
                            Row(
                                Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                TextButton(onClick = {
                                    if (savedPaths.isCurrentPath(path)) {
                                        holder.loadSavedPath(path)
                                        showSaved = false
                                    }
                                }) {
                                    Text(path.name)
                                }
                                TextButton(modifier = Modifier.semantics {
                                    contentDescription = "$deleteLabel: ${path.name}"
                                }, onClick = {
                                    scope.launch {
                                        if (savedPaths.isCurrentPath(path) && savedPaths.deletePath(path) &&
                                            savedPaths.isCurrentConnection(path.radioId)
                                        ) {
                                            holder.handleSavedPathDeleted(path.id)
                                        }
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
internal fun TracePathListContent(
    state: TracePathState,
    holder: TracePathStateHolder,
    codeInput: TraceCodeInputState,
    connection: TracePathScreen.Dependencies? = null,
) {
    val scope = rememberCoroutineScope()
    val canEdit = {
        if (connection == null) holder.isEditingCurrent()
        else holder.isBoundEditingCurrent(connection.radioId, connection.generation, connection.originatingToken)
    }
    var showClearConfirmation by remember(connection?.radioId, connection?.generation, connection?.originatingToken) {
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
        item(key = "trace-code-input") { TraceCodeInput(state, holder, codeInput, canEdit) }
        if (state.availableRepeaters.isNotEmpty()) {
            item {
                Text(stringResource(T.toolsNodeDiscoveryRepeaters), style = MaterialTheme.typography.titleMedium)
            }
            items(state.availableRepeaters, key = { it.id }) { repeater ->
                val addRepeater = { if (canEdit()) holder.addNode(repeater) }
                OutlinedButton(
                    onClick = addRepeater,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).semantics {
                        onClick { addRepeater(); true }
                    },
                ) {
                    Text(repeater.name)
                }
            }
        }
        val knownKeys = state.availableNodes.map { it.publicKey }.toSet()
        val discoveredCandidates = state.discoveredRepeaters.filterNot { it.publicKey in knownKeys }
        if (discoveredCandidates.isNotEmpty()) {
            item {
                Text(
                    stringResource(com.meshcoreone.android.core.l10n.R.string.l10n_app_map_map_callout_discovered),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(discoveredCandidates, key = { it.id }) { repeater ->
                val addDiscoveredRepeater = { if (canEdit()) holder.addNode(repeater) }
                OutlinedButton(
                    onClick = addDiscoveredRepeater,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).semantics {
                        onClick { addDiscoveredRepeater(); true }
                    },
                ) {
                    Text(repeater.name)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0u.toUByte(), 1u.toUByte(), 2u.toUByte()).forEach { mode ->
                    val bytes = 1 shl mode.toInt()
                    val setMode = { if (canEdit()) holder.setTraceHashMode(mode) }
                    FilterChip(
                        selected = holder.effectiveTraceMode == mode,
                        onClick = setMode,
                        modifier = Modifier.semantics { onClick { setMode(); true } },
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
                val setBatch: (Boolean) -> Unit = { if (canEdit()) holder.setBatchEnabled(it) }
                Switch(state.batchEnabled, setBatch, modifier = Modifier.semantics {
                    onClick { setBatch(!state.batchEnabled); true }
                })
            }
        }
        state.errorMessage?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { contentDescription = error }) }
        }
        item { TraceResults(state) }
        item(key = "trace-save-path") { TraceSavePathAction(state, holder, connection, scope, savingPath) }
        item {
            val canExecute = { holder.deps.connectedDevice() != null && canEdit() }
            val runOrCancel = {
                if (canExecute()) {
                    if (state.isRunning) holder.cancelBatchTrace()
                    else scope.launch { if (canExecute()) holder.runBatchTrace() }
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
                TextButton(onClick = confirmClear, modifier = Modifier.semantics {
                    onClick { confirmClear(); true }
                }) {
                    Text(stringResource(C.contactsTraceClearPath))
                }
            },
        )
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
    val preferences = checkNotNull(preferenceStore) { "Trace map requires process preference wiring" }
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
    val locale = LocalConfiguration.current.locales[0]
    val unit = stringResource(com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings.remoteNodesStatusSnrBadgeUnit)
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
        loaded != null && state.canRunTraceWhenConnected &&
            holder.deps.connectedDevice() != null && canEdit()
    }
    val runTrace = {
        if (canRunTrace()) {
            requestCamera(map.pathCamera())
            scope.launch { if (canRunTrace()) map.runTrace() }
        }
    }
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
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            ) { Text(stringResource(C.contactsTraceListRunTrace)) }
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
