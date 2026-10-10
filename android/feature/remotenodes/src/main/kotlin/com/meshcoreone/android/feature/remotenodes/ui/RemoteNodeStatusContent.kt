// PortedFrom: MC1/Views/RemoteNodes/Repeaters/RepeaterStatusContent.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomStatusContent.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/SharedNodeStatusViews.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.core.model.OCVPreset
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.feature.remotenodes.auth.*
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodesCatalog
import com.meshcoreone.android.feature.remotenodes.map.*
import com.meshcoreone.android.feature.remotenodes.status.*
import com.meshcoreone.android.feature.remotenodes.telemetry.MeasurementSystem
import com.meshcoreone.android.feature.remotenodes.telemetry.formattedValue
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
internal fun RemoteNodeStatusContent(
    session: RemoteNodeSessionDTO,
    helper: NodeStatusStateHolder,
    repeater: RepeaterStatusStateHolder?,
    room: RoomStatusStateHolder?,
    catalog: RemoteNodesCatalog,
    ready: Boolean,
    modifier: Modifier,
    mapSurface: RemoteNodesMapSurface,
) {
    val state by helper.state.collectAsStateWithLifecycle()
    val repeaterState = repeater?.state?.collectAsStateWithLifecycle()?.value
    val scope = rememberCoroutineScope()
    val locale = Locale.getDefault()
    val system = MeasurementSystem.of(locale)
    var map by remember { mutableStateOf<String?>(null) }
    var discoverConfirmation by remember { mutableStateOf(false) }
    var curveConfirmation by remember { mutableStateOf(false) }
    var curve by remember(state.ocvValues) { mutableStateOf(OcvCustomCurve.format(state.ocvValues)) }
    val connected by rememberUpdatedState(ready)
    val statusRequest: () -> Unit = { if (ready) scope.launch { if (repeater != null) repeater.requestStatus(session) else room?.requestStatus(session) } }
    val telemetryRequest: () -> Unit = { if (ready) scope.launch { if (repeater != null) repeater.requestTelemetry(session) else room?.requestTelemetry(session) } }
    BackHandler(map != null) { map = null }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (map != null) {
            TextButton({ map = null }) { Text(stringResource(L.remoteNodesDone)) }
            if (map == "neighbors" && repeaterState != null) {
                var filter by remember { mutableStateOf(MapFilterState()) }
                val plotted = remember(repeaterState.neighbors, catalog, filter) {
                    NeighborSNRMapBuilder.build(
                        session, repeaterState.neighbors, catalog.contacts, catalog.discoveredNodes, null,
                        filter, repeaterState.neighborKeyDisplayByteCount, locale,
                    )
                }
                RemoteToggle(
                    com.meshcoreone.android.core.l10n.generated.AppSettingsStrings.trustedContactsFavoritesOnly,
                    filter.favoritesOnly, { filter = filter.copy(favoritesOnly = it) },
                )
                RemoteToggle(
                    com.meshcoreone.android.core.l10n.generated.AppMapStrings.mapCalloutDiscovered,
                    filter.showDiscovered, { filter = filter.withShowDiscovered(it) }, !filter.favoritesOnly,
                )
                RemoteNodeMapContent(stringResource(L.remoteNodesStatusNeighborsMapTitle), plotted.points, plotted.lines, plotted.region, surface = mapSurface)
                plotted.unplottable.forEach { item ->
                    RemoteValue(item.displayName, remoteText(NeighborRows.snrText(item.neighbor.snr, locale)))
                }
            } else {
                val fix = state.currentLocationFix
                if (fix != null) {
                    val point = MapPoint(
                        UUID.nameUUIDFromBytes(session.publicKey.toByteArray()), fix.coordinate,
                        PinStyle.LOCATION_FIX_LATEST, session.name, false, null, null,
                    )
                    RemoteNodeMapContent(stringResource(L.remoteNodesStatusLocationMapTitle), listOf(point), emptyList(), CoordinateRegion.around(fix.coordinate, .05), surface = mapSurface)
                }
            }
        } else {
            RemoteSection(
                L.remoteNodesStatusTitle, state.statusExpanded,
                { helper.setStatusExpanded(!state.statusExpanded); if (!state.statusLoaded && !state.isLoadingStatus) statusRequest() },
                state.isLoadingStatus, false, ready, statusRequest,
            ) {
                RemoteFailure(state.statusSectionError)
                val response = state.status
                if (response != null) {
                    RemoteStatusMetric(L.remoteNodesStatusBattery, NodeStatusDisplay.battery(response, state.ocvValues, locale), StatusDelta.battery(state.batteryDeltaMV), locale)
                    RemoteValue(stringResource(L.remoteNodesStatusUptime), remoteText(NodeStatusDisplay.uptime(response)))
                    RemoteValue(stringResource(L.remoteNodesStatusAirtime), NodeStatusDisplay.airtime(response)?.let { airtime ->
                        "TX ${remoteText(airtime.tx)} / RX ${remoteText(airtime.rx)}"
                    }.orEmpty())
                    RemoteValue(stringResource(L.remoteNodesStatusAirtimePercent), NodeStatusDisplay.airtimePercent(response, locale))
                    RemoteStatusMetric(L.remoteNodesStatusLastSnr, NodeStatusDisplay.lastSnr(response, locale), StatusDelta.snr(state.snrDelta), locale)
                    RemoteStatusMetric(L.remoteNodesStatusLastRssi, NodeStatusDisplay.lastRssi(response), StatusDelta.rssi(state.rssiDelta), locale)
                    RemoteStatusMetric(L.remoteNodesStatusNoiseFloor, NodeStatusDisplay.noiseFloor(response), StatusDelta.noiseFloor(state.noiseFloorDelta), locale)
                    RemoteValue(stringResource(L.remoteNodesHistoryPacketsSent), NodeStatusDisplay.packetsSent(response, locale))
                    RemoteValue(stringResource(L.remoteNodesHistoryPacketsReceived), NodeStatusDisplay.packetsReceived(response, locale))
                    RemoteValue(stringResource(L.remoteNodesStatusDuplicates), NodeStatusDisplay.duplicates(response, locale))
                    if (repeater != null) repeater.receiveErrorsDisplay(locale)?.let { RemoteValue(stringResource(L.remoteNodesHistoryReceiveErrors), it) }
                    if (room != null) {
                        RemoteValue(stringResource(L.remoteNodesRoomStatusPostsReceived), room.postsReceivedDisplay(locale))
                        RemoteValue(stringResource(L.remoteNodesRoomStatusPostsPushed), room.postsPushedDisplay(locale))
                    }
                }
            }
            RemoteSection(
                L.remoteNodesStatusTelemetry, state.telemetryExpanded,
                { helper.setTelemetryExpanded(!state.telemetryExpanded); if (!state.telemetryLoaded && !state.isLoadingTelemetry) telemetryRequest() },
                state.isLoadingTelemetry, false, ready, telemetryRequest,
            ) {
                RemoteFailure(state.telemetrySectionError)
                if (state.telemetryLoaded && state.cachedDataPoints.isEmpty()) Text(stringResource(L.remoteNodesStatusNoSensorData))
                state.groupedDataPoints.forEach { group ->
                    if (state.hasMultipleChannels) RemoteHeading(remoteText(com.meshcoreone.android.feature.remotenodes.history.TelemetrySensorCharts.channelHeader(group.channel.toLong())))
                    telemetryRows(group.dataPoints, state.ocvValues).forEach { row ->
                        RemoteValue(stringResource(row.labelRes), row.dataPoint.formattedValue(locale, system))
                        row.batteryPercentage?.let { Text("$it%") }
                    }
                }
                if (state.currentLocationFix != null) TextButton({ map = "location" }) { Text(stringResource(L.remoteNodesStatusViewOnMap)) }
                else if (state.telemetryLoaded) Text(stringResource(L.remoteNodesStatusNoTelemetryData))
            }
            if (repeaterState != null && repeater != null) {
                val context = NeighborResolutionContext(catalog.contacts, catalog.discoveredNodes, null, locale)
                RemoteSection(
                    L.remoteNodesStatusNeighbors, repeaterState.neighborsExpanded,
                    { repeater.setNeighborsExpanded(!repeaterState.neighborsExpanded); if (!repeaterState.neighborsLoaded && ready) scope.launch { repeater.requestNeighbors(session) } },
                    repeaterState.isLoadingNeighbors && !repeaterState.isDiscovering, false, ready,
                    { scope.launch { repeater.requestNeighbors(session) } },
                ) {
                    RemoteFailure(repeaterState.neighborsSectionError)
                    if (repeaterState.neighborsLoaded && repeaterState.neighbors.isEmpty()) Text(stringResource(L.remoteNodesStatusNoNeighbors))
                    NeighborRows.rows(
                        repeaterState.neighbors, state.previousNeighborSnapshot, state.seenNeighborPrefixes,
                        repeaterState.neighborKeyDisplayByteCount, context,
                    ).forEach { row ->
                        RemoteValue(remoteText(row.displayName), "${row.keyHex} / ${remoteText(row.snrText)} / ${remoteText(row.lastSeen)}")
                        if (row.isNew) Text(stringResource(L.remoteNodesHistoryNew))
                        row.snrDelta?.let { Text(remoteText(it.accessibilityDescription(locale))) }
                    }
                    NeighborRows.disappeared(repeaterState.neighbors, state.previousNeighborSnapshot, repeaterState.neighborKeyDisplayByteCount, context).forEach {
                        RemoteValue(remoteText(it.displayName), stringResource(L.remoteNodesHistoryNotSeen))
                    }
                    TextButton({ map = "neighbors" }) { Text(stringResource(L.remoteNodesStatusViewOnMap)) }
                    Button({ discoverConfirmation = true }, enabled = ready && !repeaterState.isDiscovering) { Text(remoteText(StatusSections.discoverButtonText(repeaterState))) }
                    if (repeaterState.isDiscovering) TextButton(repeater::stopDiscovery) { Text(stringResource(L.remoteNodesCancel)) }
                }
                RemoteSection(
                    L.remoteNodesStatusOwnerInfo, repeaterState.ownerInfoExpanded,
                    { repeater.setOwnerInfoExpanded(!repeaterState.ownerInfoExpanded); if (!repeaterState.ownerInfoLoaded && ready) scope.launch { repeater.requestOwnerInfo(session) } },
                    repeaterState.isLoadingOwnerInfo, false, ready, { scope.launch { repeater.requestOwnerInfo(session) } },
                ) {
                    RemoteFailure(repeaterState.ownerInfoError)
                    Text(remoteText(StatusSections.ownerInfoText(repeaterState)))
                    repeaterState.firmwareVersion?.let { RemoteValue(stringResource(L.remoteNodesSettingsFirmware), it) }
                }
            }
            RemoteSection(L.remoteNodesStatusBatteryCurve, state.isBatteryCurveExpanded, { helper.setBatteryCurveExpanded(!state.isBatteryCurveExpanded) }) {
                var choose by remember { mutableStateOf(false) }
                Box {
                    TextButton({ choose = true }) { Text(state.selectedOCVPreset.displayName) }
                    DropdownMenu(choose, { choose = false }) {
                        OCVPreset.nodePresets.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.displayName) },
                                onClick = { choose = false; helper.setSelectedOCVPreset(preset); helper.setOcvValues(preset.ocvArray) },
                            )
                        }
                        DropdownMenuItem(text = { Text(OCVPreset.CUSTOM.displayName) }, onClick = { choose = false; helper.setSelectedOCVPreset(OCVPreset.CUSTOM) })
                    }
                }
                if (state.selectedOCVPreset == OCVPreset.CUSTOM) OutlinedTextField(curve, { curve = it }, Modifier.fillMaxWidth(), label = { Text("mV (100%–0%)") })
                RemoteFailure(state.ocvError)
                RemoteApply(L.remoteNodesSettingsSettingsApplied, ready && (state.selectedOCVPreset != OCVPreset.CUSTOM || OcvCustomCurve.parse(curve).size == OcvCustomCurve.POINT_COUNT)) { curveConfirmation = true }
                Text(stringResource(L.remoteNodesStatusBatteryCurveFooter))
            }
            catalog.contacts.firstOrNull { it.publicKey == session.publicKey && it.radioId == session.radioId }?.let { contact ->
                RemoteHeading(stringResource(L.remoteNodesAuthPath))
                when (val route = NodeRoutePathPresentation.of(contact, catalog.contacts, catalog.discoveredNodes, null, locale)) {
                    NodeRoutePath.NoRoute -> Text(stringResource(L.remoteNodesAuthNoRouteSet))
                    is NodeRoutePath.Route -> {
                        Text(remoteText(route.summary))
                        route.hops.forEach { RemoteValue(it.hex, remoteText(it.name)) }
                    }
                }
            }
        }
    }
    if (discoverConfirmation) RemoteConfirmation(
        stringResource(L.remoteNodesStatusDiscoverNeighbors), stringResource(L.remoteNodesStatusNeighborsFooter),
        { discoverConfirmation = false },
    ) {
        discoverConfirmation = false
        if (connected) repeater?.startDiscovery(session)
        else helper.setStatusSectionError(RemoteNodesText.Resource(L.remoteNodesSettingsNoService))
    }
    if (curveConfirmation) RemoteConfirmation(
        stringResource(L.remoteNodesStatusBatteryCurve), stringResource(L.remoteNodesStatusBatteryCurveFooter),
        { curveConfirmation = false },
    ) {
        curveConfirmation = false
        scope.launch {
            if (connected) helper.saveOCVSettings(state.selectedOCVPreset, if (state.selectedOCVPreset == OCVPreset.CUSTOM) OcvCustomCurve.parse(curve) else state.ocvValues)
            else helper.setStatusSectionError(RemoteNodesText.Resource(L.remoteNodesSettingsNoService))
        }
    }
}

@Composable
private fun RemoteStatusMetric(label: Int, value: String, delta: StatusDelta?, locale: Locale) {
    RemoteValue(stringResource(label), value)
    if (delta != null) Text(remoteText(delta.accessibilityDescription(locale)))
}
