// PortedFrom: MC1/Views/Onboarding/MeshAnimationView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Onboarding/PulsingAntenna.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.onboarding.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import kotlin.math.sin

/** Pure geometry/timing of the welcome mesh animation, separated so it can be unit tested. */
object MeshAnimationModel {
    data class Node(val x: Float, val y: Float, val isUser: Boolean)
    data class Edge(val from: Int, val to: Int)
    data class MessagePoint(val x: Float, val y: Float)

    const val NODE_RADIUS = 8f
    const val USER_NODE_RADIUS = 12f
    const val MESSAGE_RADIUS = 4f
    const val EDGE_LINE_WIDTH = 1.5f
    const val EDGE_FADE_FREQUENCY = 0.8
    const val EDGE_MIN_OPACITY = 0.3
    const val EDGE_FADE_AMPLITUDE = 0.3
    const val EDGE_PHASE_OFFSET = 0.7
    const val NODE_PULSE_FREQUENCY = 1.2
    const val NODE_PULSE_AMPLITUDE = 0.15
    const val NODE_PHASE_OFFSET = 0.5
    const val MESSAGE_CYCLE_SECONDS = 4.0

    val nodes = listOf(
        Node(0.2f, 0.3f, false), Node(0.5f, 0.15f, false), Node(0.8f, 0.25f, false),
        Node(0.15f, 0.7f, false), Node(0.5f, 0.55f, true), Node(0.85f, 0.75f, false),
    )
    val edges = listOf(Edge(0, 1), Edge(1, 2), Edge(0, 4), Edge(1, 4), Edge(3, 4), Edge(4, 5), Edge(2, 5))
    val messagePath = listOf(3, 4, 1, 2)

    fun edgeOpacity(index: Int, time: Double): Double =
        EDGE_MIN_OPACITY + EDGE_FADE_AMPLITUDE * sin(time * EDGE_FADE_FREQUENCY + index * EDGE_PHASE_OFFSET)

    fun nodeRadius(index: Int, time: Double): Double {
        val scale = 1.0 + NODE_PULSE_AMPLITUDE * sin(time * NODE_PULSE_FREQUENCY + index * NODE_PHASE_OFFSET)
        return (if (nodes[index].isUser) USER_NODE_RADIUS else NODE_RADIUS) * scale
    }

    /** Position of the traveling message in unit coordinates for [time] seconds. */
    fun messagePosition(time: Double): MessagePoint {
        val progress = (time % MESSAGE_CYCLE_SECONDS) / MESSAGE_CYCLE_SECONDS
        val segments = messagePath.size - 1
        val total = progress * segments
        val index = minOf(total.toInt(), segments - 1)
        val local = (total - index).toFloat()
        val from = nodes[messagePath[index]]
        val to = nodes[messagePath[index + 1]]
        return MessagePoint(from.x + (to.x - from.x) * local, from.y + (to.y - from.y) * local)
    }
}

@Composable
fun MeshAnimation(modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    var time by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            time = 0.0
            return@LaunchedEffect
        }
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) androidx.compose.runtime.withFrameNanos { time = (it - start) / NANOS_PER_SECOND }
    }
    val accent = MaterialTheme.colorScheme.primary
    val description = stringResource(O.meshAnimationAccessibilityLabel)
    Canvas(
        modifier.fillMaxWidth().height(OnboardingMetrics.meshAnimationHeight)
            .semantics { contentDescription = description; role = Role.Image },
    ) { drawMesh(accent, time) }
}

private fun DrawScope.drawMesh(accent: Color, time: Double) {
    val m = MeshAnimationModel
    fun point(x: Float, y: Float) = Offset(x * size.width, y * size.height)
    m.edges.forEachIndexed { index, edge ->
        val a = m.nodes[edge.from]
        val b = m.nodes[edge.to]
        drawLine(
            accent.copy(alpha = m.edgeOpacity(index, time).toFloat().coerceIn(0f, 1f)),
            point(a.x, a.y), point(b.x, b.y), strokeWidth = m.EDGE_LINE_WIDTH.dp.toPx(),
        )
    }
    m.nodes.forEachIndexed { index, node ->
        drawCircle(
            accent.copy(alpha = if (node.isUser) 1f else 0.7f),
            radius = m.nodeRadius(index, time).toFloat().dp.toPx(), center = point(node.x, node.y),
        )
    }
    val message = m.messagePosition(time)
    drawCircle(accent, m.MESSAGE_RADIUS.dp.toPx(), point(message.x, message.y))
}

/** Hero icon for the pair step; pulses unless animations are disabled. Decorative, hidden from TalkBack. */
@Composable
fun PulsingAntenna(modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val transition = rememberInfiniteTransition(label = "antenna")
    val pulse by transition.animateFloat(
        1f, 0.55f, infiniteRepeatable(tween(PULSE_MILLIS, easing = LinearEasing), RepeatMode.Reverse), label = "pulse",
    )
    val value = if (reduceMotion) 1f else pulse
    Box(modifier.height(OnboardingMetrics.heroSize), contentAlignment = Alignment.Center) {
        Icon(
            MeshSymbol.RADIO.vector, contentDescription = null,
            modifier = Modifier.size(OnboardingMetrics.heroSize / 2).scale(0.9f + 0.1f * value).alpha(value),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val PULSE_MILLIS = 1000
