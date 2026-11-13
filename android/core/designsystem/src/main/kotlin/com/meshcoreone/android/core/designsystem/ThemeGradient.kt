// AndroidOnly: WP-301 Gradient theme tokens (cyan-to-green accent brush) with a solid-color fallback; not in the Swift source.
package com.meshcoreone.android.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Appearance-adaptive gradient endpoints as declared by a [Theme]. */
data class AdaptiveGradient(val start: AdaptiveColor, val end: AdaptiveColor) {
    fun resolve(scheme: ColorScheme, highContrast: Boolean): AccentGradient =
        AccentGradient(start.resolve(scheme, highContrast), end.resolve(scheme, highContrast))
}

/** Resolved linear gradient endpoints. [fallback] is the solid color used wherever a brush cannot be drawn. */
data class AccentGradient(val start: ThemeColor, val end: ThemeColor) {
    val fallback: ThemeColor get() = start
    val stops: List<ThemeColor> get() = listOf(start, end)
}

/** Each stop must stay visible (>= 3:1) on every surface tier so icons and strokes drawn with it remain perceivable. */
internal fun fitGradient(gradient: AccentGradient, surfaces: List<ThemeColor>): AccentGradient = AccentGradient(
    accessibleForegroundOnSurfaces(gradient.start, surfaces, UI_COMPONENT_FLOOR),
    accessibleForegroundOnSurfaces(gradient.end, surfaces, UI_COMPONENT_FLOOR),
)

/** Text color that clears [floor] on both gradient stops, preferring [preferred]. */
internal fun glyphOnGradient(preferred: ThemeColor, gradient: AccentGradient, floor: Double): ThemeColor {
    fun worst(color: ThemeColor) = gradient.stops.minOf { WCAGContrast.contrastRatio(color, it) }
    return listOf(preferred, ThemeColor.BLACK, ThemeColor.WHITE).firstOrNull { worst(it) >= floor + 0.05 }
        ?: listOf(ThemeColor.BLACK, ThemeColor.WHITE).maxBy(::worst)
}

internal const val UI_COMPONENT_FLOOR = 3.0

/** The linear brush for the active theme, or null when the theme has no gradient (callers fall back to solid colors). */
val MeshThemeTokens.accentBrush: Brush?
    get() = roles.accentGradient?.let { Brush.linearGradient(it.stops.map(ThemeColor::toComposeColor)) }

@Composable
fun accentBrushOrNull(): Brush? = LocalMeshTheme.current.accentBrush

/** Fills with the accent gradient when the theme has one, otherwise with [fallback]. */
@Composable
fun Modifier.accentBackground(shape: Shape, fallback: Color? = null): Modifier {
    val brush = accentBrushOrNull()
    return when {
        brush != null -> background(brush, shape)
        fallback != null -> background(fallback, shape)
        else -> this
    }
}

/** Thin gradient stroke when the theme has a gradient; unchanged otherwise. */
@Composable
fun Modifier.accentBorder(width: Dp, shape: Shape): Modifier =
    accentBrushOrNull()?.let { border(width, it, shape) } ?: this

/** Tints the drawn content (an icon) with the accent gradient when [enabled] and the theme has one. */
@Composable
fun Modifier.accentTint(enabled: Boolean = true): Modifier {
    val brush = accentBrushOrNull()
    if (!enabled || brush == null) return this
    return graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        drawRect(brush, blendMode = BlendMode.SrcIn)
    }
}

/** App-bar / header accent: a 2dp gradient hairline, drawn only for themes that have a gradient. */
@Composable
fun Modifier.accentUnderline(thickness: Dp = 2.dp): Modifier {
    val brush = accentBrushOrNull() ?: return this
    return drawWithContent {
        drawContent()
        drawRect(brush, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - thickness.toPx()),
            size = androidx.compose.ui.geometry.Size(size.width, thickness.toPx()))
    }
}

@Composable
fun AccentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val brush = accentBrushOrNull()
    if (brush == null || !enabled) {
        Button(onClick, modifier, enabled, content = content)
    } else {
        Button(
            onClick,
            modifier.background(brush, ButtonDefaults.shape),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            content = content,
        )
    }
}

@Composable
fun AccentExtendedFab(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val brush = accentBrushOrNull()
    if (brush == null) {
        ExtendedFloatingActionButton(onClick, modifier, content = { content() })
    } else {
        ExtendedFloatingActionButton(
            onClick,
            modifier.background(brush, FloatingActionButtonDefaults.extendedFabShape),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
            content = { content() },
        )
    }
}

@Composable
fun AccentBadge(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val brush = accentBrushOrNull()
    if (brush == null) Badge(modifier, content = content)
    else Badge(
        modifier.background(brush, CircleShape),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        content = content,
    )
}
