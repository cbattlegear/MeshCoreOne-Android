// PortedFrom: MC1/Views/RemoteNodes/Repeaters/NeighborSNRMapView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/Location/NodeLocationMapView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppMapStrings
import com.meshcoreone.android.core.maps.*
import com.meshcoreone.android.feature.remotenodes.map.CoordinateRegion
import com.meshcoreone.android.feature.remotenodes.map.MapPoint
import com.meshcoreone.android.feature.remotenodes.map.SnrBadge
import com.meshcoreone.android.feature.remotenodes.telemetry.MeasurementSystem
import java.util.Locale
import com.meshcoreone.android.feature.remotenodes.map.MapLine as NodeMapLine

internal fun remoteMapPresentation(
    points: List<MapPoint>,
    lines: List<NodeMapLine>,
    region: CoordinateRegion?,
    locale: Locale,
    system: MeasurementSystem,
    snrUnit: String,
): MapPresentationState = MapPresentationState(
    availability = MapAvailability.Available(MapLibreOpenFreeMap.catalog),
    points = points.map { point ->
        MapMarker(
            point.id, GeoPoint(point.coordinate.latitude, point.coordinate.longitude),
            MapPinStyle.valueOf(point.pinStyle.name), point.label, point.isClusterable, point.hopIndex,
            point.badge?.text(locale, system, snrUnit),
        )
    },
    lines = lines.map { line ->
        MapLine(
            line.id, line.coordinates.map { GeoPoint(it.latitude, it.longitude) },
            MapLineStyle.valueOf(line.style.name), line.opacity.toFloat(),
        )
    },
    camera = region?.let { MapCamera(GeoPoint(it.center.latitude, it.center.longitude), it.latitudeDelta.coerceAtLeast(.01), it.longitudeDelta.coerceAtLeast(.01)) },
)

@Composable
internal fun RemoteNodeMapContent(
    title: String,
    points: List<MapPoint>,
    lines: List<NodeMapLine>,
    region: CoordinateRegion?,
    onSelect: (MapMarker) -> Unit = {},
    surface: @Composable (MapPresentationState, Boolean, Boolean, String, (MapCamera) -> Unit, (MapMarker) -> Unit, Modifier) -> Unit = { state, clusters, labels, description, camera, marker, modifier ->
        MapLibreMapSurface(state, clusters, labels, description, camera, marker, modifier)
    },
) {
    val locale = Locale.getDefault()
    val system = MeasurementSystem.of(locale)
    val unit = stringResource(SnrBadge.UNIT_TEXT_RESOURCE)
    val presentation = remember(points, lines, region, locale, system, unit) { remoteMapPresentation(points, lines, region, locale, system, unit) }
    var encodedCamera by rememberSaveable { mutableStateOf<String?>(null) }
    var labels by rememberSaveable { mutableStateOf(true) }
    var clusters by rememberSaveable { mutableStateOf(true) }
    var style by rememberSaveable { mutableStateOf(MapStyle.STANDARD) }
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteHeading(title)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            MapStyle.entries.forEach { candidate ->
                FilterChip(
                    style == candidate, { style = candidate },
                    label = { Text(stringResource(when (candidate) {
                        MapStyle.STANDARD -> AppMapStrings.mapStyleStandard
                        MapStyle.SATELLITE -> AppMapStrings.mapStyleSatellite
                        MapStyle.TOPO -> AppMapStrings.mapStyleTopo
                    })) },
                )
            }
        }
        val availability = MapLibreOpenFreeMap.catalog.layers.getValue(style).availability
        when {
            points.isEmpty() -> Text(stringResource(com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings.remoteNodesHistoryNoSnapshotsMessage))
            !MapLibreRuntime.isSupported -> Text("MapLibre requires a 64-bit Android device.", color = MaterialTheme.colorScheme.error)
            availability is MapLayerAvailability.Unavailable -> Text(availability.reason, color = MaterialTheme.colorScheme.error)
            else -> surface(
                presentation.copy(style = style, camera = encodedCamera?.let(MapCamera::decode) ?: presentation.camera),
                clusters, labels, title, { encodedCamera = it.encode() }, onSelect, Modifier.fillMaxWidth().height(320.dp),
            )
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(labels, { labels = it })
            Text(stringResource(AppMapStrings.mapControlsShowLabels))
            Checkbox(clusters, { clusters = it })
            Text(stringResource(AppMapStrings.mapControlsClusterNodes))
        }
        presentation.attribution.forEach { attribution ->
            TextButton({ uri.openUri(attribution.legalUri) }) { Text(attribution.label) }
        }
    }
}
