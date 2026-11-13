// AndroidOnly: WP-304 Drop-in replacements for the Material 3 filled button, chip and progress indicators that paint the theme's
// accent gradient when it has one and fall back to the stock solid-color Material control for every other theme. Features
// import these instead of androidx.compose.material3 so the gradient reaches every call site without per-screen branching.
package com.meshcoreone.android.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.background
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.CircularProgressIndicator as M3CircularProgressIndicator
import androidx.compose.material3.FilterChip as M3FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledIconButton as M3FilledIconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator as M3LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.LocalMeshTheme
import com.meshcoreone.android.core.designsystem.accentBrushOrNull
import com.meshcoreone.android.core.designsystem.accentTint
import com.meshcoreone.android.core.designsystem.toComposeColor

/** Filled button: gradient container on gradient themes (when enabled and no custom [colors]), stock Material otherwise. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val brush = accentBrushOrNull()
    if (brush != null && enabled && colors == null) {
        M3Button(
            onClick, modifier.background(brush, shape), enabled, shape,
            ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = LocalMeshTheme.current.roles.onAccentGradient.toComposeColor(),
            ),
            ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp), border, contentPadding, interactionSource, content,
        )
    } else {
        M3Button(onClick, modifier, enabled, shape, colors ?: ButtonDefaults.buttonColors(), elevation, border, contentPadding, interactionSource, content)
    }
}

/** Indeterminate spinner; accent-gradient tinted when the theme has a gradient and no explicit [color] is requested. */
@Composable
fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
) {
    val tinted = color == Color.Unspecified
    M3CircularProgressIndicator(
        modifier.accentTint(tinted),
        if (tinted) MaterialTheme.colorScheme.primary else color,
        strokeWidth,
    )
}

/** Determinate spinner counterpart of [CircularProgressIndicator]. */
@Composable
fun CircularProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
) {
    val tinted = color == Color.Unspecified
    M3CircularProgressIndicator(
        progress, modifier.accentTint(tinted), if (tinted) MaterialTheme.colorScheme.primary else color, strokeWidth,
    )
}

@Composable
fun LinearProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val tinted = color == Color.Unspecified
    M3LinearProgressIndicator(modifier.accentTint(tinted), if (tinted) MaterialTheme.colorScheme.primary else color)
}

@Composable
fun LinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val tinted = color == Color.Unspecified
    M3LinearProgressIndicator(progress, modifier.accentTint(tinted), if (tinted) MaterialTheme.colorScheme.primary else color)
}

/**
 * Compact filter chip: borderless when selected (gradient fill on gradient themes, secondary container otherwise) and a
 * hairline outline-variant stroke when not, so a row of chips reads light instead of boxed.
 */
@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val brush = accentBrushOrNull()
    val gradientSelected = brush != null && selected && enabled
    val onAccent = LocalMeshTheme.current.roles.onAccentGradient.toComposeColor()
    val colors = if (gradientSelected) {
        FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color.Transparent, selectedLabelColor = onAccent,
            selectedLeadingIconColor = onAccent, selectedTrailingIconColor = onAccent,
        )
    } else FilterChipDefaults.filterChipColors()
    M3FilterChip(
        selected, onClick, label,
        if (gradientSelected) modifier.background(brush!!, FilterChipDefaults.shape) else modifier,
        enabled, leadingIcon, trailingIcon, FilterChipDefaults.shape, colors,
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled, selected = selected,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            selectedBorderColor = Color.Transparent, borderWidth = 1.dp,
        ),
    )
}

/** Filled round icon button (send, confirm): gradient container on gradient themes while enabled, stock Material otherwise. */
@Composable
fun FilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = IconButtonDefaults.filledShape,
    colors: IconButtonColors? = null,
    content: @Composable () -> Unit,
) {
    val brush = accentBrushOrNull()
    if (brush != null && enabled && colors == null) {
        M3FilledIconButton(
            onClick, modifier.background(brush, shape), enabled, shape,
            IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.Transparent,
                contentColor = LocalMeshTheme.current.roles.onAccentGradient.toComposeColor(),
            ),
            content = content,
        )
    } else {
        M3FilledIconButton(onClick, modifier, enabled, shape, colors ?: IconButtonDefaults.filledIconButtonColors(), content = content)
    }
}
