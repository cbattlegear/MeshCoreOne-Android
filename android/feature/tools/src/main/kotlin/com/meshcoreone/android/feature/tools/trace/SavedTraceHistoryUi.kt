// PortedFrom: MC1/Views/Tools/TracePath/SavedPathDetailView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.trace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.model.SavedTracePathDTO
import com.meshcoreone.android.core.model.TracePathRunDTO
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavedTraceHistorySheet(
    path: SavedTracePathDTO,
    connection: TracePathScreen.Dependencies,
    diagnostics: TraceDiagnostics,
    onDismiss: () -> Unit,
) {
    val holder = remember(path.id, path.radioId, connection.feature, connection.generation, connection.originatingToken) {
        SavedPathDetailStateHolder(path, diagnostics).also {
            it.configure(isCurrent = {
                connection.feature.connectedDevice()?.radioId == path.radioId &&
                    connection.feature.sessionToken() == connection.originatingToken &&
                    connection.feature.isOriginatingSessionCurrent(connection.generation, connection.originatingToken)
            }) { connection.feature.savedPaths() }
        }
    }
    DisposableEffect(holder) { onDispose { holder.retireConnection() } }
    LaunchedEffect(holder) { holder.refresh() }
    val state by holder.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var runId by rememberSaveable(path.id.toString(), path.radioId.toString(), connection.generation, connection.originatingToken) {
        mutableStateOf<String?>(null)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SavedTraceHistoryContent(state, { scope.launch { holder.refresh() } }, {
            if (connection.feature.connectedDevice()?.radioId == path.radioId &&
                connection.feature.sessionToken() == connection.originatingToken &&
                connection.feature.isOriginatingSessionCurrent(connection.generation, connection.originatingToken)
            ) runId = it.id.toString()
        }, onDismiss)
    }
    val available = state.error != SavedPathDetailError.NOT_FOUND && state.error != SavedPathDetailError.STALE_SESSION
    if (available) state.sortedRuns.firstOrNull { it.id.toString() == runId }?.let { run ->
        SavedTraceRunDialog(run, onDismiss = { runId = null })
    }
}

@Composable
internal fun SavedTraceHistoryContent(
    state: SavedPathDetailState,
    onRefresh: () -> Unit,
    onRun: (TracePathRunDTO) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    LazyColumn(
        Modifier.fillMaxWidth().testTag("trace-history"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                HistoryHeading(savedPathDisplayLabel(state.savedPath))
                FlowRow {
                    TextButton(onClick = onRefresh, enabled = !state.isLoading, modifier = Modifier.testTag("trace-history-refresh")) {
                        Text(stringResource(R.string.trace_history_refresh))
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(L.commonDone)) }
                }
                if (state.isLoading) CircularProgressIndicator(Modifier.testTag("trace-history-loading"))
                state.error?.let { error ->
                    Text(
                        stringResource(when (error) {
                            SavedPathDetailError.LOAD -> com.meshcoreone.android.core.l10n.generated.AppToolsStrings.toolsSavedPathsLoadFailed
                            SavedPathDetailError.NOT_FOUND -> R.string.trace_history_missing
                            SavedPathDetailError.STALE_SESSION -> C.contactsResultsUnavailable
                        }),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
        if (state.error != SavedPathDetailError.NOT_FOUND && state.error != SavedPathDetailError.STALE_SESSION) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    HistoryHeading(stringResource(C.contactsPathDetailPath))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TraceHopDisplay.hopHexStrings(
                            state.savedPath.pathBytes, state.hashSize.coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
                        ).forEach { hex ->
                            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                                Text(hex, Modifier.padding(8.dp))
                            }
                        }
                    }
                    HistoryHeading(stringResource(C.contactsPathDetailPerformance))
                    if (state.showsChart) TraceRunChart(state.successfulRuns.asReversed(), dateFormat)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        HistoryStat(stringResource(C.contactsPathDetailAvg), state.averageRoundTrip?.let { "$it ms" } ?: "-")
                        HistoryStat(stringResource(C.contactsPathDetailBest), state.bestRoundTrip?.let { "$it ms" } ?: "-")
                        HistoryStat(stringResource(C.contactsPathDetailSuccess), state.successRateText)
                    }
                    HistoryHeading(stringResource(C.contactsPathDetailHistory))
                    if (state.sortedRuns.isEmpty() && !state.isLoading) Text(stringResource(R.string.trace_history_empty))
                }
            }
            items(state.sortedRuns, key = { it.id }) { run ->
                TextButton(
                    onClick = { onRun(run) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("trace-run:${run.id}"),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(dateFormat.format(Date.from(run.date)))
                        Text(
                            if (run.success) "${run.roundTripMs} ms" else stringResource(C.contactsPathDetailFailed),
                            color = if (run.success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp).semantics { heading() })
}

@Composable
private fun HistoryStat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TraceRunChart(runs: List<TracePathRunDTO>, dateFormat: DateFormat) {
    val label = stringResource(C.contactsPathDetailRoundTripMs)
    val description = label + "\n" + runs.joinToString("\n") { "${dateFormat.format(Date.from(it.date))}: ${it.roundTripMs} ms" }
    val color = MaterialTheme.colorScheme.primary
    val minimum = runs.minOf { it.roundTripMs }.toDouble()
    val maximum = runs.maxOf { it.roundTripMs }.toDouble()
    val first = runs.first().date.toEpochMilli().toDouble()
    val duration = (runs.last().date.toEpochMilli().toDouble() - first).coerceAtLeast(1.0)
    Text(label, style = MaterialTheme.typography.bodySmall)
    Text("${minimum.toLong()} - ${maximum.toLong()} ms", style = MaterialTheme.typography.bodySmall)
    Canvas(Modifier.fillMaxWidth().height(150.dp).padding(8.dp).testTag("trace-history-chart").semantics {
        contentDescription = description
    }) {
        val points = runs.map { run ->
            Offset(
                ((run.date.toEpochMilli().toDouble() - first) / duration * size.width).toFloat(),
                (size.height - (run.roundTripMs.toDouble() - minimum) / (maximum - minimum).coerceAtLeast(1.0) * size.height).toFloat(),
            )
        }
        points.zipWithNext().forEach { (start, end) -> drawLine(color, start, end, strokeWidth = 2.dp.toPx()) }
        points.forEach { drawCircle(color, radius = 3.dp.toPx(), center = it) }
    }
    Text(
        "${dateFormat.format(Date.from(runs.first().date))} - ${dateFormat.format(Date.from(runs.last().date))}",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
internal fun SavedTraceRunDialog(run: TracePathRunDTO, onDismiss: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    val snr = remember(locale) { NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    } }
    val resources = LocalContext.current.resources
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(C.contactsPathDetailRunDetails)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("trace-run-detail")) {
                HistoryHeading(stringResource(C.contactsPathDetailOverview))
                HistoryStat(stringResource(C.contactsPathDetailDate), format.format(Date.from(run.date)))
                HistoryStat(stringResource(C.contactsPathDetailRoundTrip), "${run.roundTripMs} ms")
                HistoryStat(
                    stringResource(C.contactsPathDetailStatus),
                    stringResource(if (run.success) C.contactsPathDetailSuccess else C.contactsPathDetailFailed),
                )
                if (run.success && run.hopsSNR.isNotEmpty()) {
                    HistoryHeading(stringResource(C.contactsPathDetailPerHopSNR))
                    run.hopsSNR.forEachIndexed { index, value ->
                        HistoryStat(C.contactsPathDetailHop(resources, index + 1), "${snr.format(value)} dB")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(L.commonDone)) } },
    )
}
