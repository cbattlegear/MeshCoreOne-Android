// PortedFrom: MC1/Views/RemoteNodes/TelemetryHistoryOverviewView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.feature.remotenodes.common.*
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeHistoryStore
import com.meshcoreone.android.feature.remotenodes.history.*
import com.meshcoreone.android.feature.remotenodes.map.*
import com.meshcoreone.android.feature.remotenodes.telemetry.MeasurementSystem
import java.time.ZoneId
import java.util.Locale

@Composable
internal fun RemoteNodeHistoryRoute(session: RemoteNodeSessionDTO, store: RemoteNodeHistoryStore?, modifier: Modifier, mapSurface: RemoteNodesMapSurface) {
    val context = LocalContext.current
    val locale = Locale.getDefault()
    val system = MeasurementSystem.of(locale)
    val zone = ZoneId.systemDefault()
    val holder = remember(session.id, store, locale, system, zone) {
        TelemetryHistoryOverviewStateHolder(SystemRemoteNodesClock, zone, locale, system, context::getString)
    }
    val state by holder.state.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    var fullMap by remember { mutableStateOf(false) }
    var selectedReport by remember { mutableStateOf<NodeStatusSnapshotDTO?>(null) }
    LaunchedEffect(holder, refresh) {
        if (store != null) holder.loadData(store, session.publicKey, session.radioId)
    }
    Column(modifier.verticalScroll(rememberScrollState()).testTag("remote-history-content"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RemoteHeading(stringResource(L.remoteNodesHistoryOverviewTitle))
        TextButton({ refresh++ }) { Text(stringResource(L.remoteNodesStatusRefresh)) }
        if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (store == null) RemoteFailure(RemoteNodesText.Resource(L.remoteNodesSettingsNoService))
        RemoteFailure(state.error)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            HistoryTimeRange.entries.forEach { range ->
                FilterChip(state.timeRange == range, { holder.setTimeRange(range) }, label = { Text(remoteText(range.label)) })
            }
        }
        when (val content = TelemetryHistoryOverviewContent.build(holder, session.isRepeater, system)) {
            TelemetryHistoryOverviewContent.Empty -> if (!state.isLoading && state.error == null && store != null) Text(remoteText(TelemetryHistoryOverviewContent.Empty.message))
            is TelemetryHistoryOverviewContent.Loaded -> {
                content.radio?.let { items ->
                    RemoteHeading(remoteText(content.radioTitle))
                    items.forEach { item ->
                        when (item) {
                            is RadioChartItem.Chart -> RemoteMetricChart(item.chart)
                            is RadioChartItem.PacketSection -> {
                                RemoteHeading(remoteText(item.header))
                                item.charts.forEach { RemoteMetricChart(it) }
                            }
                        }
                    }
                }
                RemoteHeading(remoteText(content.sensorsTitle))
                when (val sensors = content.sensors) {
                    is OverviewSection.NotCaptured -> Text(remoteText(sensors.notice.text))
                    is OverviewSection.Content -> sensors.value.forEach { section ->
                        section.header?.let { RemoteHeading(remoteText(it)) }
                        section.charts.forEach { RemoteMetricChart(it) }
                    }
                }
                val reports = LocationHistoryPreview.locationReports(content.filteredSnapshots)
                if (reports.isNotEmpty()) {
                    val ascending = content.filteredSnapshots.sortedBy { it.timestamp }
                    val path = remember(ascending) { LocationPathMapBuilder.build(ascending) }
                    val region = path.points.map { it.coordinate }.boundingRegion()
                    RemoteNodeMapContent(
                        stringResource(L.remoteNodesStatusLocationMapTitle), path.points, path.lines, region,
                        surface = mapSurface,
                        onSelect = { marker ->
                            val report = path.reports[marker.id]
                            selectedReport = reports.firstOrNull { it.id == report?.id }
                        },
                    )
                    selectedReport?.let { Text(LocationReportRowText.detailLine(it, locale, zone, system)) }
                    TextButton({ fullMap = true }) { Text(stringResource(L.remoteNodesStatusViewOnMap)) }
                    RemoteHeading(stringResource(L.remoteNodesHistoryLocationReportsHeader))
                    reports.forEach { report ->
                        TextButton({ selectedReport = report; fullMap = true }) {
                            Text(LocationReportRowText.detailLine(report, locale, zone, system))
                        }
                    }
                    if (fullMap) Dialog(
                        onDismissRequest = { fullMap = false },
                        properties = DialogProperties(usePlatformDefaultWidth = false),
                    ) {
                        Surface(Modifier.fillMaxSize().testTag("remote-history-full-map")) {
                            BoxWithConstraints {
                                val mapHeight = maxHeight * .65f
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                                    TextButton({ fullMap = false }) { Text(stringResource(L.remoteNodesDone)) }
                                    val fullPath = remember(ascending) { LocationPathMapBuilder.build(ascending, decimatePins = false) }
                                    RemoteNodeMapContent(
                                        stringResource(L.remoteNodesStatusLocationMapTitle), fullPath.points, fullPath.lines,
                                        selectedReport?.validCoordinate?.let { CoordinateRegion.around(it, .02) } ?: region,
                                        surface = mapSurface, mapHeight = mapHeight,
                                        onSelect = { marker ->
                                            selectedReport = reports.firstOrNull { it.id == fullPath.reports[marker.id]?.id }
                                        },
                                    )
                                    selectedReport?.let { Text(LocationReportRowText.detailLine(it, locale, zone, system)) }
                                }
                            }
                        }
                    }
                } else {
                    Text(stringResource(L.remoteNodesStatusNoTelemetryData))
                }
                content.neighbors?.let { neighbors ->
                    RemoteHeading(remoteText(content.neighborsTitle))
                    when (neighbors) {
                        is OverviewSection.NotCaptured -> Text(remoteText(neighbors.notice.text))
                        is OverviewSection.Content -> neighbors.value.forEach { RemoteMetricChart(it.chart) }
                    }
                }
                Text(remoteText(content.retentionNotice))
            }
        }
    }
}
