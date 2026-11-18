// PortedFrom: MC1/Theme/EnvironmentValues+AppTheme.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Theme/ThemedSurfaceModifiers.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: Material 3 bridge; no app/radio/database construction, dynamic colors or Apple chrome.
package com.meshcoreone.android.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme as MaterialColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

data class MeshThemeTokens(
    val frame: ThemeFrame,
    val roles: MaterialRoles,
    val appearanceToken: String,
    val motionScale: Float,
) {
    fun duration(millis: Int): Int = NativeThemeMetrics.duration(millis, motionScale)
}

private fun defaultTokens(): MeshThemeTokens {
    val frame = ThemeRegistry.default.resolve(ColorScheme.LIGHT, false)
    return MeshThemeTokens(frame, MaterialRoles.from(frame), "light-std-1065353216-1065353216-ltr", 1f)
}

val LocalMeshTheme = staticCompositionLocalOf(::defaultTokens)
val LocalAppTheme = staticCompositionLocalOf { ThemeRegistry.default }

fun ThemeColor.toComposeColor(): Color = Color(
    red.toFloat(), green.toFloat(), blue.toFloat(), alpha.toFloat(),
    if (colorSpace == ThemeColorSpace.DISPLAY_P3) ColorSpaces.DisplayP3 else ColorSpaces.Srgb,
)

fun Color.toThemeColor(): ThemeColor = convert(ColorSpaces.Srgb).let {
    ThemeColor(it.red.toDouble(), it.green.toDouble(), it.blue.toDouble(), it.alpha.toDouble())
}

fun MaterialRoles.toMaterialColorScheme(dark: Boolean): MaterialColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary.toComposeColor(), onPrimary = onPrimary.toComposeColor(),
        primaryContainer = primaryContainer.toComposeColor(), onPrimaryContainer = onPrimaryContainer.toComposeColor(),
        secondary = secondary.toComposeColor(), onSecondary = onSecondary.toComposeColor(),
        secondaryContainer = secondaryContainer.toComposeColor(), onSecondaryContainer = onSecondaryContainer.toComposeColor(),
        tertiary = tertiary.toComposeColor(), onTertiary = onTertiary.toComposeColor(),
        tertiaryContainer = tertiaryContainer.toComposeColor(), onTertiaryContainer = onTertiaryContainer.toComposeColor(),
        background = background.toComposeColor(), onBackground = onBackground.toComposeColor(),
        surface = surface.toComposeColor(), onSurface = onSurface.toComposeColor(),
        surfaceVariant = surfaceVariant.toComposeColor(), onSurfaceVariant = onSurfaceVariant.toComposeColor(),
        outline = outline.toComposeColor(), outlineVariant = outlineVariant.toComposeColor(),
        error = error.toComposeColor(), onError = onError.toComposeColor(),
        errorContainer = errorContainer.toComposeColor(), onErrorContainer = onErrorContainer.toComposeColor(),
        surfaceTint = primary.toComposeColor(), inverseSurface = onSurface.toComposeColor(),
        inverseOnSurface = surface.toComposeColor(), inversePrimary = readableGlyph(primary, onSurface).toComposeColor(),
        scrim = Color.Black,
        surfaceDim = background.toComposeColor(), surfaceBright = surface.toComposeColor(),
        surfaceContainerLowest = background.toComposeColor(), surfaceContainerLow = surface.toComposeColor(),
        surfaceContainer = surface.toComposeColor(), surfaceContainerHigh = surfaceVariant.toComposeColor(),
        surfaceContainerHighest = surfaceVariant.toComposeColor(),
    )
}

val MeshTypography = Typography(
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, lineHeight = 16.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
)

val MeshShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MeshCoreTheme(
    theme: Theme = ThemeRegistry.default,
    colorSchemePreference: AppColorSchemePreference = AppColorSchemePreference.SYSTEM,
    systemDark: Boolean = isSystemInDarkTheme(),
    highContrast: Boolean = rememberHighContrast(),
    motionScale: Float = rememberMotionDurationScale(),
    content: @Composable () -> Unit,
) {
    val scheme = theme.effectiveColorScheme(colorSchemePreference)
        ?: if (systemDark) ColorScheme.DARK else ColorScheme.LIGHT
    val frame = remember(theme, scheme, highContrast) { theme.resolve(scheme, highContrast) }
    val roles = remember(frame) { MaterialRoles.from(frame) }
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val token = AppearanceToken.native(scheme, highContrast, density.fontScale, density.density, rtl)
    val tokens = remember(frame, roles, token, motionScale) { MeshThemeTokens(frame, roles, token, motionScale) }
    val colors = remember(roles, scheme) { roles.toMaterialColorScheme(scheme == ColorScheme.DARK) }
    CompositionLocalProvider(LocalAppTheme provides theme, LocalMeshTheme provides tokens) {
        MaterialTheme(colorScheme = colors, typography = MeshTypography, shapes = MeshShapes, content = content)
    }
}

@Composable
fun ThemeServiceHost(
    service: ThemeService,
    content: @Composable (ThemeServiceState) -> Unit,
) {
    val state by service.state.collectAsStateWithLifecycle()
    val view = LocalView.current
    val reversion = (state as? ThemeServiceState.Ready)?.themeReversion
    LaunchedEffect(service, reversion) {
        if (reversion != null && service.claimThemeReversion(reversion)) {
            view.announceForAccessibility(view.context.getString(ThemeRecoveryStrings.themeReverted))
        }
    }
    val selection = when (val current = state) {
        is ThemeServiceState.Ready -> current.selection
        is ThemeServiceState.Failed -> current.previous
        is ThemeServiceState.Closed -> current.previous
        ThemeServiceState.Loading -> null
    }
    MeshCoreTheme(
        theme = selection?.current ?: ThemeRegistry.default,
        colorSchemePreference = selection?.colorSchemePreference ?: AppColorSchemePreference.SYSTEM,
    ) { content(state) }
}

fun Modifier.themedCanvas(frame: ThemeFrame): Modifier = when {
    frame.theme.surfaces == null -> this
    frame.canvasGlow == null -> background(frame.canvas.toComposeColor())
    else -> {
        val canvas = frame.canvas.toComposeColor()
        val glow = frame.canvasGlow.toComposeColor()
        drawBehind {
            drawRect(canvas)
            // AndroidOnly: soft radial glow rising from the bottom edge, fading to the flat canvas tier.
            drawRect(
                Brush.radialGradient(
                    listOf(glow, canvas),
                    center = Offset(size.width / 2f, size.height),
                    radius = maxOf(size.width, size.height * 0.6f),
                ),
            )
        }
    }
}

fun Modifier.themedRowBackground(frame: ThemeFrame, flatten: Boolean = false): Modifier =
    if (frame.theme.surfaces?.rowFill(flatten) == null) this else background(frame.card.toComposeColor())

fun Modifier.themedPlainRowBackground(frame: ThemeFrame): Modifier = themedCanvas(frame)
