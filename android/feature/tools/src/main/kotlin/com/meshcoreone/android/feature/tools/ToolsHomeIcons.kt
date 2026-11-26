// AndroidOnly: WP-314 Original GPLv3 glyphs for Tools home, matching the existing 24dp stroke set.
package com.meshcoreone.android.feature.tools

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object ToolsHomeIcons {
    val Sight: ImageVector by lazy {
        glyph("Sight") {
            moveTo(3f, 12f); curveTo(5.5f, 8f, 8.5f, 6f, 12f, 6f)
            curveTo(15.5f, 6f, 18.5f, 8f, 21f, 12f)
            curveTo(18.5f, 16f, 15.5f, 18f, 12f, 18f)
            curveTo(8.5f, 18f, 5.5f, 16f, 3f, 12f); close()
            moveTo(15f, 12f); arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
        }
    }
    val Terminal: ImageVector by lazy {
        glyph("Terminal") {
            moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
            moveTo(7f, 9f); lineTo(10f, 12f); lineTo(7f, 15f)
            moveTo(12f, 15f); lineTo(16f, 15f)
        }
    }
    val Chevron: ImageVector by lazy {
        glyph("Chevron") { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
    }

    private fun glyph(name: String, build: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = build,
            )
        }.build()
}
