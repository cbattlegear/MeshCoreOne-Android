// PortedFrom: MC1/Views/RemoteNodes/MetricChartView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.feature.remotenodes.history.*
import com.meshcoreone.android.feature.remotenodes.telemetry.ChartAccent
import com.meshcoreone.android.feature.remotenodes.common.SwiftNumberFormat
import java.time.ZoneId
import java.util.Locale

@Composable
internal fun RemoteMetricChart(chart: MetricChartModel) {
    val locale = Locale.getDefault()
    val zone = ZoneId.systemDefault()
    val title = remoteText(chart.title)
    val dates = remember(chart) { chart.drawnSeries.flatMap { it.dataPoints.map { point -> point.date } }.distinct().sorted() }
    var selected by remember(chart) { mutableIntStateOf(dates.lastIndex.coerceAtLeast(0)) }
    val readout = chart.readout(dates.getOrNull(selected), locale)
    val descriptions = readout?.entries?.map { "${remoteText(it.seriesName)}: ${it.valueText} ${if (readout.isMultiSeries) chart.unit else ""}" }.orEmpty()
    val dateText = readout?.let { HistoryDateFormat.monthDayTime(it.date, locale, zone) }.orEmpty()
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteHeading(title)
            if (!chart.hasEnoughData) {
                val empty = chart.emptyState(locale)
                empty.valueText?.let { Text(it) }
                Text(remoteText(empty.message))
            } else {
                descriptions.forEach { Text(it) }
                Text(dateText)
                Canvas(
                    Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = "$title. ${descriptions.joinToString()}. $dateText" }
                        .pointerInput(chart) {
                            detectDragGestures { change, _ ->
                                val date = MetricChartGeometry(chart, size.width.toFloat(), size.height.toFloat()).dateAt(change.position.x)
                                val snapped = chart.scrubDate(date)
                                selected = dates.indexOf(snapped).coerceAtLeast(0)
                                change.consume()
                            }
                        },
                ) {
                    val geometry = MetricChartGeometry(chart, size.width, size.height)
                    chart.drawnSeries.forEach { series ->
                        val color = chartColor(series.color)
                        val path = Path()
                        series.dataPoints.forEachIndexed { index, point ->
                            val x = geometry.x(point.date)
                            val y = geometry.y(point.value)
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            drawCircle(color, 3.dp.toPx(), Offset(x, y))
                        }
                        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    }
                    dates.getOrNull(selected)?.let { date ->
                        val x = geometry.x(date)
                        drawLine(Color.Gray, Offset(x, 0f), Offset(x, size.height))
                    }
                }
                Slider(
                    selected.toFloat(), { selected = it.toInt() },
                    valueRange = 0f..dates.lastIndex.coerceAtLeast(1).toFloat(),
                    steps = (dates.size - 2).coerceAtLeast(0),
                    modifier = Modifier.semantics { contentDescription = "$title. $dateText. ${descriptions.joinToString()}" },
                )
                val geometry = MetricChartGeometry(chart, 1f, 1f)
                geometry.yDomain?.let { domain ->
                    Text("${SwiftNumberFormat.number(domain.start, locale)} – ${SwiftNumberFormat.number(domain.endInclusive, locale)} ${chart.unit}")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    geometry.startDate?.let { Text(HistoryDateFormat.monthDay(it, locale, zone)) }
                    geometry.endDate?.let { Text(HistoryDateFormat.monthDay(it, locale, zone)) }
                }
            }
        }
    }
}

private fun chartColor(accent: ChartAccent): Color = when (accent) {
    ChartAccent.ORANGE -> Color(0xFFE98025)
    ChartAccent.RED -> Color(0xFFCC3344)
    ChartAccent.TEAL -> Color(0xFF008577)
    ChartAccent.PURPLE -> Color(0xFF9955BB)
    ChartAccent.YELLOW -> Color(0xFFAF8700)
    ChartAccent.MINT -> Color(0xFF258774)
    ChartAccent.PINK -> Color(0xFFBB4488)
    ChartAccent.BLUE -> Color(0xFF3377CC)
    ChartAccent.GREEN -> Color(0xFF348844)
    ChartAccent.INDIGO -> Color(0xFF6655BB)
    ChartAccent.CYAN -> Color(0xFF0088AA)
    ChartAccent.GRAY -> Color.Gray
}
