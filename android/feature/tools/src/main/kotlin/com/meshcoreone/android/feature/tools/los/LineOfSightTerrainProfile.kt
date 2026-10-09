// PortedFrom: MC1/Views/Tools/LineOfSight/TerrainProfileCanvas.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/LineOfSight/TerrainProfileSectionView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.los

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import java.util.Locale

@Composable
internal fun LineOfSightTerrainProfile(
    state: LineOfSightState,
    onRepeaterDrag: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val input = state.terrainChartInput
    val title = stringResource(AppToolsStrings.toolsLineOfSightTerrainProfile)
    val attribution = stringResource(AppToolsStrings.toolsLineOfSightElevationAttribution)
    val noData = stringResource(AppToolsStrings.toolsLineOfSightNoData)
    val indirect = stringResource(AppToolsStrings.toolsLineOfSightIndirectRoute)
    val clear = stringResource(AppToolsStrings.toolsLineOfSightLegendClear)
    val obstructed = stringResource(AppToolsStrings.toolsLineOfSightLegendObstructed)
    val terrain = stringResource(AppToolsStrings.toolsLineOfSightLegendTerrain)
    val los = stringResource(AppToolsStrings.toolsLineOfSightLegendLos)
    val locale = Locale.getDefault()
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val terrainColor = MaterialTheme.colorScheme.tertiary
    val losColor = MaterialTheme.colorScheme.primary
    val obstructionColor = MaterialTheme.colorScheme.error
    val fresnelColor = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val analysisDescription = when (val status = state.analysisStatus) {
        is AnalysisStatus.Result ->
            "${stringResource(LosFormatters.statusLabel(status.result.clearanceStatus))}: " +
                "${LosFormatters.formatClearancePercent(status.result.worstClearancePercent)}%."
        is AnalysisStatus.RelayResult ->
            "${stringResource(LosFormatters.statusLabel(status.result.overallStatus))}: " +
                "${LosFormatters.formatClearancePercent(LosFormatters.relayHeadlineClearancePercent(status.result))}%."
        else -> ""
    }
    val chartDescription = if (input.showsEmptyState) {
        "$title. $noData. $attribution"
    } else {
        buildString {
            append("$title. $attribution. $terrain, $los, $clear, $obstructed.")
            append(" ${state.profileSamples.size + state.profileSamplesRB.size} elevation samples.")
            if (analysisDescription.isNotEmpty()) append(" $analysisDescription")
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(attribution, style = MaterialTheme.typography.labelSmall, color = textColor)
        }
        if (input.showsEmptyState) {
            Text(
                noData,
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(TerrainProfileChart.CHART_HEIGHT.dp)
                .semantics { contentDescription = chartDescription }
                .then(
                    if (state.isRepeaterDragEnabled) {
                        Modifier.pointerInput(input, layoutDirection) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                onRepeaterDrag(TerrainProfileChart.dragPathFraction(change.position.x.toDouble(), size.width.toDouble()))
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            val layout = input.layout(size.width.toDouble(), size.height.toDouble(), locale)
            drawChart(
                layout = layout,
                gridColor = gridColor,
                fresnelColor = fresnelColor,
                obstructionColor = obstructionColor,
                terrainColor = terrainColor,
                losColor = losColor,
                textColor = textColor,
                labelTextSize = with(density) { 10.dp.toPx() },
            )
        }
        if (input.showsIndirectRouteLabel) {
            Text(indirect, style = MaterialTheme.typography.labelSmall, color = textColor)
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ChartLegend(terrainColor, terrain)
            ChartLegend(losColor, los)
            ChartLegend(fresnelColor, clear)
            ChartLegend(obstructionColor, obstructed)
        }
    }
}

@Composable
private fun ChartLegend(color: Color, text: String) {
    Text("● $text", color = color, style = MaterialTheme.typography.labelSmall)
}

private fun DrawScope.drawChart(
    layout: TerrainProfileChartLayout,
    gridColor: Color,
    fresnelColor: Color,
    obstructionColor: Color,
    terrainColor: Color,
    losColor: Color,
    textColor: Color,
    labelTextSize: Float,
) {
    val dashed = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))
    layout.gridLines.forEach { drawLine(gridColor, it.start.offset(), it.end.offset(), 1f, pathEffect = dashed) }
    layout.fresnelSegments.forEach { segment ->
        drawPath(segment.outerFill.path(), fresnelColor.copy(alpha = 0.14f))
        drawPath(segment.innerFill.path(), fresnelColor.copy(alpha = 0.24f))
        drawPath(segment.topBoundary.path(), fresnelColor.copy(alpha = 0.75f), style = Stroke(1.5f))
        drawPath(segment.bottomBoundary.path(), fresnelColor.copy(alpha = 0.75f), style = Stroke(1.5f))
    }
    layout.obstructionBands.forEach {
        drawRect(
            obstructionColor.copy(alpha = 0.16f),
            topLeft = Offset(it.left.toFloat(), it.top.toFloat()),
            size = androidx.compose.ui.geometry.Size((it.right - it.left).toFloat(), (it.bottom - it.top).toFloat()),
        )
    }
    if (layout.terrainFill.isNotEmpty()) drawPath(layout.terrainFill.path(), terrainColor.copy(alpha = 0.34f))
    if (layout.terrainStroke.isNotEmpty()) drawPath(layout.terrainStroke.path(), terrainColor, style = Stroke(2f))
    layout.losLines.forEach { drawLine(losColor, it.start.offset(), it.end.offset(), 2f) }
    layout.junctionSeparator?.let { drawLine(textColor, it.start.offset(), it.end.offset(), 1f, pathEffect = dashed) }
    layout.endpointA?.let { drawCircle(Color(0xFF1677FF), TerrainProfileChart.ENDPOINT_MARKER_RADIUS.toFloat(), it.offset()) }
    layout.endpointB?.let { drawCircle(Color(0xFF1B8A5A), TerrainProfileChart.ENDPOINT_MARKER_RADIUS.toFloat(), it.offset()) }
    layout.repeaterMarker?.let {
        drawLine(Color(0xFF8A3FFC), it.stem.start.offset(), it.stem.end.offset(), 2f)
        drawCircle(Color(0xFF8A3FFC), TerrainProfileChart.REPEATER_MARKER_RADIUS.toFloat(), it.center.offset())
    }
    drawIntoCanvas { canvas ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor.toArgb()
            textSize = labelTextSize
        }
        layout.yLabels.forEach { canvas.nativeCanvas.drawText(it.text, it.anchor.x.toFloat(), it.anchor.y.toFloat(), paint.apply { textAlign = Paint.Align.RIGHT }) }
        layout.xLabels.forEach { canvas.nativeCanvas.drawText(it.text, it.anchor.x.toFloat(), it.anchor.y.toFloat(), paint.apply { textAlign = Paint.Align.CENTER }) }
    }
}

private fun ChartPoint.offset() = Offset(x.toFloat(), y.toFloat())

private fun List<ChartPoint>.path(): Path = Path().also { path ->
    firstOrNull()?.let { first ->
        path.moveTo(first.x.toFloat(), first.y.toFloat())
        drop(1).forEach { path.lineTo(it.x.toFloat(), it.y.toFloat()) }
        if (size > 2) path.close()
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)
