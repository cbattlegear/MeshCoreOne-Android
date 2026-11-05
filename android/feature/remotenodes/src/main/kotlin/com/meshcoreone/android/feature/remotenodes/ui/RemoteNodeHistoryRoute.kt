// PortedFrom: MC1/Views/RemoteNodes/TelemetryHistoryOverviewView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/Location/LocationReportRow.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/Location/LocationReportCallout.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.feature.remotenodes.common.*
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeHistoryStore
import com.meshcoreone.android.feature.remotenodes.history.*
import com.meshcoreone.android.feature.remotenodes.map.*
import com.meshcoreone.android.feature.remotenodes.telemetry.MeasurementSystem
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private class RetainedRemoteHistory(
    private var clock: RemoteNodesClock,
    private var zone: ZoneId,
    initialPresentation: HistoryPresentation,
) : ViewModel() {
    var presentation by mutableStateOf(initialPresentation)
    var holder by mutableStateOf(newHolder(clock, zone, initialPresentation))
        private set
    private var configured = false
    private var authorityIdentity: Any? = null
    private var store: RemoteNodeHistoryStore? = null
    private var started = false
    private var loadJob: Job? = null

    private fun newHolder(clock: RemoteNodesClock, zone: ZoneId, presentation: HistoryPresentation) =
        TelemetryHistoryOverviewStateHolder(
            clock, zone, presentation.locale, presentation.system, presentation.titleText,
        ) { this.presentation }

    fun configure(
        store: RemoteNodeHistoryStore?,
        publicKey: com.meshcoreone.android.core.protocol.bytes.Bytes,
        radioId: com.meshcoreone.android.core.model.RadioId,
        clock: RemoteNodesClock,
        zone: ZoneId,
        presentation: HistoryPresentation,
    ) {
        this.presentation = presentation
        val changed = !configured ||
            authorityIdentity != store?.authorityIdentity ||
            this.clock !== clock ||
            this.zone != zone ||
            (this.store == null) != (store == null)
        if (changed) {
            loadJob?.cancel()
            this.clock = clock
            this.zone = zone
            authorityIdentity = store?.authorityIdentity
            // A detached holder fences late results even if a canceled store read completes.
            holder = newHolder(clock, zone, presentation)
            started = false
            configured = true
        }
        this.store = store
        if (store != null && !started) load(store, publicKey, radioId)
    }

    fun refresh(publicKey: com.meshcoreone.android.core.protocol.bytes.Bytes, radioId: com.meshcoreone.android.core.model.RadioId) {
        store?.let { load(it, publicKey, radioId, refresh = true) }
    }

    fun leave() {
        loadJob?.cancel()
        configured = false
    }

    private fun load(
        store: RemoteNodeHistoryStore,
        publicKey: com.meshcoreone.android.core.protocol.bytes.Bytes,
        radioId: com.meshcoreone.android.core.model.RadioId,
        refresh: Boolean = false,
    ) {
        if (started && !refresh) return
        started = true
        loadJob?.cancel()
        val target = holder
        loadJob = viewModelScope.launch { target.loadData(store, publicKey, radioId) }
    }
}

internal fun NeighborChart.asStableMetricChart(): MetricChartModel =
    chart.copy(selectionIdentity = "neighbor:${prefix.uppercaseHexString()}")

@Composable
private fun LocationReportText(
    report: NodeStatusSnapshotDTO,
    now: Instant,
    locale: Locale,
    zone: ZoneId,
    system: MeasurementSystem,
) {
    Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            LocationReportRowText.relativeTime(report, now, zone).format(locale),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            LocationReportRowText.detailLine(report, locale, zone, system),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun RemoteNodeHistoryRoute(
    session: RemoteNodeSessionDTO,
    store: RemoteNodeHistoryStore?,
    modifier: Modifier,
    mapSurface: RemoteNodesMapSurface,
    clock: RemoteNodesClock = SystemRemoteNodesClock,
) = RemoteNodeHistoryRoute(session.publicKey, session.radioId, session.isRepeater, store, modifier, mapSurface, clock)

@Composable
internal fun RemoteNodeHistoryRoute(
    publicKey: com.meshcoreone.android.core.protocol.bytes.Bytes,
    radioId: com.meshcoreone.android.core.model.RadioId,
    isRepeater: Boolean,
    store: RemoteNodeHistoryStore?,
    modifier: Modifier,
    mapSurface: RemoteNodesMapSurface,
    clock: RemoteNodesClock = SystemRemoteNodesClock,
) {
    val locale = LocalConfiguration.current.locales[0]
    val resources = LocalResources.current
    val system = MeasurementSystem.of(locale)
    val zone = ZoneId.systemDefault()
    val presentation = remember(resources, locale, system) {
        HistoryPresentation(locale, system, resources::getString)
    }
    val owner = checkNotNull(LocalView.current.findViewTreeViewModelStoreOwner())
    val historyKey = "remote-history:$radioId:${publicKey.uppercaseHexString()}"
    val retained = remember(owner, historyKey) {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                modelClass.cast(RetainedRemoteHistory(clock, zone, presentation))
        }
        ViewModelProvider(owner, factory)[historyKey, RetainedRemoteHistory::class.java]
    }
    SideEffect { retained.configure(store, publicKey, radioId, clock, zone, presentation) }
    val activity = checkNotNull(LocalActivity.current)
    DisposableEffect(retained, activity) {
        onDispose {
            if (!activity.isChangingConfigurations) retained.leave()
        }
    }
    val holder = retained.holder
    val state by holder.state.collectAsStateWithLifecycle()
    var fullMap by remember(publicKey, radioId, holder) { mutableStateOf(false) }
    var selectedReport by remember(publicKey, radioId, holder) { mutableStateOf<NodeStatusSnapshotDTO?>(null) }
    key(publicKey, radioId, holder) {
    Column(modifier.verticalScroll(rememberScrollState()).testTag("remote-history-content"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RemoteHeading(stringResource(L.remoteNodesHistoryOverviewTitle))
        TextButton({ retained.refresh(publicKey, radioId) }) {
            Text(stringResource(L.remoteNodesStatusRefresh))
        }
        if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (store == null) RemoteFailure(RemoteNodesText.Resource(L.remoteNodesSettingsNoService))
        RemoteFailure(state.error)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            HistoryTimeRange.entries.forEach { range ->
                FilterChip(state.timeRange == range, { holder.setTimeRange(range) }, label = { Text(remoteText(range.label)) })
            }
        }
        when (val content = TelemetryHistoryOverviewContent.build(holder, isRepeater, system)) {
            TelemetryHistoryOverviewContent.Empty -> if (!state.isLoading && state.error == null && store != null) Text(remoteText(TelemetryHistoryOverviewContent.Empty.message))
            is TelemetryHistoryOverviewContent.Loaded -> {
                content.radio?.let { items ->
                    RemoteHeading(remoteText(content.radioTitle))
                    items.forEach { item ->
                        when (item) {
                            is RadioChartItem.Chart -> key(item.chart.title) { RemoteMetricChart(item.chart) }
                            is RadioChartItem.PacketSection -> {
                                RemoteHeading(remoteText(item.header))
                                item.charts.forEach { chart -> key(chart.title) { RemoteMetricChart(chart) } }
                            }
                        }
                    }
                }
                RemoteHeading(remoteText(content.sensorsTitle))
                when (val sensors = content.sensors) {
                    is OverviewSection.NotCaptured -> Text(remoteText(sensors.notice.text))
                    is OverviewSection.Content -> sensors.value.forEach { section ->
                        section.header?.let { RemoteHeading(remoteText(it)) }
                        section.charts.forEach { chart ->
                            key(chart.identity) { RemoteMetricChart(chart) }
                        }
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
                    selectedReport?.let { LocationReportText(it, clock.now, locale, zone, system) }
                    TextButton({ fullMap = true }) { Text(stringResource(L.remoteNodesStatusViewOnMap)) }
                    RemoteHeading(stringResource(L.remoteNodesHistoryLocationReportsHeader))
                    reports.forEach { report ->
                        TextButton({ selectedReport = report; fullMap = true }) {
                            LocationReportText(report, clock.now, locale, zone, system)
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
                                    selectedReport?.let { LocationReportText(it, clock.now, locale, zone, system) }
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
                        is OverviewSection.Content -> neighbors.value.forEach { neighbor ->
                            key(neighbor.prefix) { RemoteMetricChart(neighbor.asStableMetricChart()) }
                        }
                    }
                }
                Text(remoteText(content.retentionNotice))
            }
        }
    }
    }
}
