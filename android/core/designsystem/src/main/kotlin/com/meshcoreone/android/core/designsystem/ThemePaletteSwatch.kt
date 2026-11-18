// PortedFrom: MC1/Theme/ThemePaletteSwatch.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings

enum class PaletteSwatchSize { TAPPABLE, THUMBNAIL }

@Composable
fun themeName(theme: Theme): String {
    val resource = when (theme.id) {
        ThemeId.DEFAULT -> AppSettingsStrings.supportThemeDefault
        ThemeId.EMBER -> AppSettingsStrings.supportThemeEmber
        ThemeId.FERN -> AppSettingsStrings.supportThemeFern
        ThemeId.MARINE -> AppSettingsStrings.supportThemeMarine
        ThemeId.OLIVE -> AppSettingsStrings.supportThemeOlive
        ThemeId.LAVENDER -> AppSettingsStrings.supportThemeLavender
        else -> null
    }
    return resource?.let { stringResource(it) } ?: theme.localizedName(LocalContext.current.resources)
}

@Composable
fun ThemePaletteSwatch(
    theme: Theme,
    modifier: Modifier = Modifier,
    sizeStyle: PaletteSwatchSize = PaletteSwatchSize.TAPPABLE,
) {
    val name = themeName(theme)
    val resources = LocalContext.current.resources
    val label = if (theme.preferredColorScheme == null) {
        AppSettingsStrings.appearanceAccessibilitySwatchDualMode(resources, name)
    } else AppSettingsStrings.appearanceAccessibilitySwatchDarkOnly(resources, name)
    val highContrast = LocalMeshTheme.current.frame.highContrast
    val minSize = if (sizeStyle == PaletteSwatchSize.TAPPABLE) {
        Modifier.sizeIn(minWidth = NativeThemeMetrics.MINIMUM_TOUCH_TARGET.dp, minHeight = NativeThemeMetrics.MINIMUM_TOUCH_TARGET.dp)
    } else Modifier
    Canvas(modifier.then(minSize).clip(RoundedCornerShape(12.dp)).semantics { contentDescription = label }) {
        val light = theme.resolve(ColorScheme.LIGHT, highContrast)
        val dark = theme.resolve(ColorScheme.DARK, highContrast)
        if (theme.preferredColorScheme == null) {
            palette(light)
            val bottom = Path().apply {
                moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            clipPath(bottom) { palette(dark) }
        } else palette(dark)
    }
}

private fun DrawScope.palette(frame: ThemeFrame) {
    drawRect((if (frame.theme.surfaces == null) frame.card else frame.canvas).toComposeColor())
    val inset = minOf(8.dp.toPx(), size.minDimension / 4)
    val topLeft = Offset(inset, inset)
    val inner = Size(size.width - 2 * inset, size.height - 2 * inset)
    val gradient = frame.gradient
    if (gradient == null) {
        drawRoundRect(frame.accent.toComposeColor(), topLeft = topLeft, size = inner, cornerRadius = CornerRadius(6.dp.toPx()))
    } else {
        // AndroidOnly: gradient themes preview their cyan-to-green accent instead of a flat fill.
        drawRoundRect(
            Brush.linearGradient(gradient.stops.map(ThemeColor::toComposeColor), start = topLeft,
                end = Offset(topLeft.x + inner.width, topLeft.y + inner.height)),
            topLeft = topLeft, size = inner, cornerRadius = CornerRadius(6.dp.toPx()),
        )
    }
}
