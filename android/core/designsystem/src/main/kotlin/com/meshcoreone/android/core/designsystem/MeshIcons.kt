// AndroidOnly: WP-301 Original GPLv3 geometry in Material's 24dp grid; SF names are meaning references only.
// GeneratedSourceMap: docs/android/generated/icon-map.json
package com.meshcoreone.android.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

enum class MeshSymbol {
    MESSAGES, NODES, MAP, TOOLS, SETTINGS, GLOBE, HASHTAG, LOCK, RADIO, ROOM,
    CHECK, READY, WARNING, ERROR, SYNC, REPEAT, SIGNAL;

    val vector: ImageVector get() = MeshIcons[this]
}

object MeshIcons {
    private val vectors = MeshSymbol.entries.associateWith(::build)
    operator fun get(symbol: MeshSymbol): ImageVector = requireNotNull(vectors[symbol])

    private fun build(symbol: MeshSymbol): ImageVector =
        ImageVector.Builder(symbol.name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) {
                when (symbol) {
                    MeshSymbol.MESSAGES -> {
                        moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 16f); lineTo(10f, 16f)
                        lineTo(4f, 20f); close()
                        moveTo(8f, 8f); lineTo(16f, 8f); moveTo(8f, 12f); lineTo(13f, 12f)
                    }
                    MeshSymbol.NODES -> {
                        moveTo(8f, 4f); lineTo(8f, 2f); moveTo(6f, 5f); lineTo(17f, 5f)
                        lineTo(17f, 21f); lineTo(6f, 21f); close()
                        moveTo(9f, 8f); lineTo(14f, 8f); lineTo(14f, 12f); lineTo(9f, 12f); close()
                        moveTo(9f, 16f); lineTo(10f, 16f); moveTo(13f, 16f); lineTo(14f, 16f)
                    }
                    MeshSymbol.MAP -> {
                        moveTo(3f, 5f); lineTo(9f, 3f); lineTo(15f, 5f); lineTo(21f, 3f)
                        lineTo(21f, 19f); lineTo(15f, 21f); lineTo(9f, 19f); lineTo(3f, 21f); close()
                        moveTo(9f, 3f); lineTo(9f, 19f); moveTo(15f, 5f); lineTo(15f, 21f)
                    }
                    MeshSymbol.TOOLS -> {
                        // Open-end wrench: round head with a jaw notch toward the upper right,
                        // and a straight handle running down to the lower left.
                        moveTo(20.48f, 7.61f)
                        arcTo(4.5f, 4.5f, 0f, true, true, 16.39f, 3.52f)
                        lineTo(15.3f, 6.7f); lineTo(17.3f, 8.7f); close()
                        moveTo(14.36f, 11.76f); lineTo(6.56f, 19.56f); lineTo(4.44f, 17.44f)
                        lineTo(12.24f, 9.64f)
                    }
                    MeshSymbol.SETTINGS -> {
                        circle(12f, 12f, 5f); circle(12f, 12f, 1.7f)
                        moveTo(12f, 2f); lineTo(12f, 5f); moveTo(12f, 19f); lineTo(12f, 22f)
                        moveTo(2f, 12f); lineTo(5f, 12f); moveTo(19f, 12f); lineTo(22f, 12f)
                        moveTo(5f, 5f); lineTo(7f, 7f); moveTo(17f, 17f); lineTo(19f, 19f)
                        moveTo(5f, 19f); lineTo(7f, 17f); moveTo(17f, 7f); lineTo(19f, 5f)
                    }
                    MeshSymbol.GLOBE -> {
                        circle(12f, 12f, 9f)
                        moveTo(3f, 12f); lineTo(21f, 12f)
                        moveTo(12f, 3f); curveTo(5f, 7f, 5f, 17f, 12f, 21f)
                        curveTo(19f, 17f, 19f, 7f, 12f, 3f)
                    }
                    MeshSymbol.HASHTAG -> {
                        moveTo(9f, 3f); lineTo(6f, 21f); moveTo(17f, 3f); lineTo(14f, 21f)
                        moveTo(4f, 8f); lineTo(21f, 8f); moveTo(3f, 16f); lineTo(20f, 16f)
                    }
                    MeshSymbol.LOCK -> {
                        moveTo(7f, 10f); lineTo(7f, 7f); curveTo(7f, 1f, 17f, 1f, 17f, 7f); lineTo(17f, 10f)
                        moveTo(5f, 10f); lineTo(19f, 10f); lineTo(19f, 21f); lineTo(5f, 21f); close()
                        moveTo(12f, 14f); lineTo(12f, 17f)
                    }
                    MeshSymbol.RADIO -> {
                        circle(12f, 9f, 1.2f); moveTo(12f, 11f); lineTo(12f, 21f)
                        moveTo(8f, 5f); curveTo(4f, 8f, 4f, 11f, 8f, 14f)
                        moveTo(16f, 5f); curveTo(20f, 8f, 20f, 11f, 16f, 14f)
                        moveTo(5f, 2f); curveTo(0f, 7f, 0f, 12f, 5f, 17f)
                        moveTo(19f, 2f); curveTo(24f, 7f, 24f, 12f, 19f, 17f)
                    }
                    MeshSymbol.ROOM -> {
                        moveTo(5f, 21f); lineTo(5f, 3f); lineTo(19f, 3f); lineTo(19f, 21f)
                        moveTo(8f, 5f); lineTo(16f, 5f); lineTo(16f, 21f); lineTo(8f, 21f); close()
                        moveTo(13f, 13f); lineTo(14f, 13f)
                    }
                    MeshSymbol.CHECK -> check()
                    MeshSymbol.READY -> { circle(12f, 12f, 9f); check() }
                    MeshSymbol.WARNING, MeshSymbol.ERROR -> {
                        moveTo(12f, 3f); lineTo(22f, 21f); lineTo(2f, 21f); close()
                        moveTo(12f, 9f); lineTo(12f, 14f); moveTo(12f, 17f); lineTo(12f, 17.2f)
                    }
                    MeshSymbol.SYNC -> {
                        moveTo(4f, 9f); curveTo(7f, 2f, 17f, 2f, 20f, 9f)
                        lineTo(20f, 4f); moveTo(20f, 9f); lineTo(15f, 9f)
                        moveTo(20f, 15f); curveTo(17f, 22f, 7f, 22f, 4f, 15f)
                        lineTo(4f, 20f); moveTo(4f, 15f); lineTo(9f, 15f)
                    }
                    MeshSymbol.REPEAT -> {
                        moveTo(3f, 9f); lineTo(3f, 6f); lineTo(20f, 6f); lineTo(17f, 3f)
                        moveTo(20f, 6f); lineTo(17f, 9f)
                        moveTo(21f, 15f); lineTo(21f, 18f); lineTo(4f, 18f); lineTo(7f, 21f)
                        moveTo(4f, 18f); lineTo(7f, 15f)
                    }
                    MeshSymbol.SIGNAL -> {
                        moveTo(4f, 20f); lineTo(4f, 16f); moveTo(9f, 20f); lineTo(9f, 12f)
                        moveTo(14f, 20f); lineTo(14f, 8f); moveTo(19f, 20f); lineTo(19f, 4f)
                    }
                }
            }
        }.build()

    private fun PathBuilder.check() {
        moveTo(6f, 12f); lineTo(10f, 16f); lineTo(18f, 8f)
    }
    private fun PathBuilder.circle(x: Float, y: Float, radius: Float) {
        val c = radius * 0.55228475f
        moveTo(x + radius, y)
        curveTo(x + radius, y + c, x + c, y + radius, x, y + radius)
        curveTo(x - c, y + radius, x - radius, y + c, x - radius, y)
        curveTo(x - radius, y - c, x - c, y - radius, x, y - radius)
        curveTo(x + c, y - radius, x + radius, y - c, x + radius, y)
        close()
    }
}
