// AndroidOnly: WP-312 Zoom and fit-to-nodes buttons for the MapLibre surface (MapKit draws these natively on iOS).
package com.meshcoreone.android.feature.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.R as L10n
import com.meshcoreone.android.core.maps.MapCamera
import com.meshcoreone.android.core.maps.MapCameraPose
import kotlin.math.pow

private val ControlTarget = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
private const val MAX_ZOOM = 22.0
private const val MIN_SPAN = 0.000001
private const val MAX_SPAN = 180.0

/** One-step camera zoom: shifts the pose zoom when the map reported one, otherwise scales the visible span. */
internal fun MapCamera.zoomedBy(steps: Double): MapCamera {
    val factor = 2.0.pow(-steps)
    val lat = (latitudeSpan * factor).coerceIn(MIN_SPAN, MAX_SPAN)
    val lon = (longitudeSpan * factor).coerceIn(MIN_SPAN, MAX_SPAN)
    val nextPose = pose?.let { MapCameraPose((it.zoom + steps).coerceIn(0.0, MAX_ZOOM), it.bearing, it.tilt) }
    return copy(latitudeSpan = lat, longitudeSpan = lon, pose = nextPose)
}

/** Floating zoom in / zoom out / fit-all column; sits mid-edge so it clears the toolbar, callout card and attribution. */
@Composable
internal fun MapZoomControls(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFitAll: () -> Unit,
    canFitAll: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Column {
            IconButton(onZoomIn, ControlTarget.testTag("map:zoom-in")) {
                Icon(MapGlyphs.Plus, stringResource(R.string.map_zoom_in))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            IconButton(onZoomOut, ControlTarget.testTag("map:zoom-out")) {
                Icon(MapGlyphs.Minus, stringResource(R.string.map_zoom_out))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            IconButton(onFitAll, ControlTarget.testTag("map:fit-all"), enabled = canFitAll) {
                Icon(MapGlyphs.Fit, stringResource(L10n.string.l10n_app_map_map_controls_centerall))
            }
        }
    }
}

private object MapGlyphs {
    private fun glyph(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, pathBuilder = block)
        }.build()

    val Plus: ImageVector by lazy { glyph("Plus") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) } }
    val Minus: ImageVector by lazy { glyph("Minus") { moveTo(5f, 12f); lineTo(19f, 12f) } }

    /** Four corner brackets: fit the camera to every node. */
    val Fit: ImageVector by lazy {
        glyph("Fit") {
            moveTo(4f, 9f); lineTo(4f, 4f); lineTo(9f, 4f)
            moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
            moveTo(20f, 15f); lineTo(20f, 20f); lineTo(15f, 20f)
            moveTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f)
        }
    }
}
