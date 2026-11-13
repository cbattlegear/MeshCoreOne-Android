// AndroidOnly: WP-304 Original glyphs the locked icon set lacks (no material-icons dependency), drawn like core:designsystem MeshIcons.
package com.meshcoreone.android.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Shared 24dp glyphs for row/overflow affordances. */
object SharedGlyphs {
    /** Three vertical dots: the conventional overflow / more-actions affordance. */
    val MoreVertical: ImageVector by lazy {
        ImageVector.Builder("MoreVertical", 24.dp, 24.dp, 24f, 24f).apply {
            for (centerY in listOf(5f, 12f, 19f)) {
                path(fill = SolidColor(Color.Black)) {
                    moveTo(10f, centerY)
                    arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
                    arcToRelative(2f, 2f, 0f, true, true, -4f, 0f)
                    close()
                }
            }
        }.build()
    }
}
