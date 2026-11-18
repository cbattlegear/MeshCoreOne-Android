// PortedFrom: MC1/Theme/AppColors.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Extensions/SNRQuality+Color.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: accessible Material roles are separate from immutable source palette roles.
package com.meshcoreone.android.core.designsystem

object AppColors {
    object Radio {
        val repeatMode = ThemeColor.hex(0xFF9500)
        val connecting = ThemeColor.hex(0x007AFF)
        val ready = ThemeColor.hex(0x34C759)
    }
    object Message {
        val outgoingBubble = ThemeColor.hex(0x2463EB)
        fun outgoingBubbleFailed(highContrast: Boolean): ThemeColor =
            if (highContrast) ThemeColor.hex(0xFF3B30) else ThemeColor.hex(0xFF3B30).copy(alpha = 0.8)
    }
}

enum class SignalColorRole(val barLevel: Double) {
    EXCELLENT(1.0), GOOD(0.75), FAIR(0.5), POOR(0.25), UNKNOWN(0.0);
}

enum class RadioColorRole { DISCONNECTED, CONNECTING, CONNECTED, SYNCING, READY, REPEAT, FAILED }

data class MaterialRoles(
    val primary: ThemeColor,
    val onPrimary: ThemeColor,
    val primaryContainer: ThemeColor,
    val onPrimaryContainer: ThemeColor,
    val secondary: ThemeColor,
    val onSecondary: ThemeColor,
    val secondaryContainer: ThemeColor,
    val onSecondaryContainer: ThemeColor,
    val tertiary: ThemeColor,
    val onTertiary: ThemeColor,
    val tertiaryContainer: ThemeColor,
    val onTertiaryContainer: ThemeColor,
    val background: ThemeColor,
    val onBackground: ThemeColor,
    val surface: ThemeColor,
    val onSurface: ThemeColor,
    val surfaceVariant: ThemeColor,
    val onSurfaceVariant: ThemeColor,
    val outline: ThemeColor,
    val outlineVariant: ThemeColor,
    val error: ThemeColor,
    val onError: ThemeColor,
    val errorContainer: ThemeColor,
    val onErrorContainer: ThemeColor,
    val outgoingBubble: ThemeColor,
    val onOutgoingBubble: ThemeColor,
    val incomingBubble: ThemeColor,
    val onIncomingBubble: ThemeColor,
    val incomingHashtag: ThemeColor,
    /** AndroidOnly: contrast-fitted accent gradient stops (null = solid [primary] everywhere). */
    val accentGradient: AccentGradient? = null,
    /** AndroidOnly: text/icon color that clears the AA floor on both gradient stops. */
    val onAccentGradient: ThemeColor = onPrimary,
) {
    companion object {
        fun from(frame: ThemeFrame): MaterialRoles {
            val floor = WCAGContrast.floor(frame.highContrast)
            val surfaceTiers = listOf(frame.canvas, frame.card, frame.incomingBubble)
            val primary = accessibleForegroundOnSurfaces(frame.accent, surfaceTiers, floor)
            val gradient = frame.gradient?.let { fitGradient(it, surfaceTiers) }
            val ink = frame.ink ?: ThemeColor.BLACK
            val muted = frame.ink?.let { frame.canvas.mix(it, 0.78) } ?: ThemeColor.BLACK
            val primaryContainer = frame.canvas.mix(frame.accent, if (frame.highContrast) 0.10 else 0.15)
            val secondaryContainer = frame.card
            val secondary = accessibleForeground(frame.hashtag, frame.canvas, floor)
            val tertiarySeed = gradient?.end ?: frame.categoryAvatarColor(AvatarCategory.ROOM)
            val tertiary = accessibleForeground(tertiarySeed, frame.canvas, floor)
            val tertiaryContainer = frame.canvas.mix(tertiarySeed, 0.12)
            val error = accessibleForeground(ThemeColor.hex(0xFF3B30), frame.canvas, floor)
            val errorContainer = frame.canvas.mix(ThemeColor.hex(0xFF3B30), 0.10)
            return MaterialRoles(
                primary, readableGlyph(frame.outgoingText, primary), primaryContainer,
                accessibleForeground(primary, primaryContainer, floor),
                secondary, readableGlyph(ThemeColor.WHITE, secondary), secondaryContainer,
                accessibleForeground(secondary, secondaryContainer, floor),
                tertiary, readableGlyph(ThemeColor.WHITE, tertiary), tertiaryContainer,
                accessibleForeground(tertiary, tertiaryContainer, floor),
                frame.canvas, accessibleForeground(ink, frame.canvas, floor),
                frame.card, accessibleForeground(ink, frame.card, floor),
                frame.incomingBubble, accessibleForeground(muted, frame.incomingBubble, floor),
                outline(frame, UI_COMPONENT_FLOOR), frame.canvas.mix(primary, 0.25),
                error, readableGlyph(ThemeColor.WHITE, error), errorContainer,
                accessibleForeground(error, errorContainer, floor),
                frame.accent, readableGlyph(frame.outgoingText, frame.accent), frame.incomingBubble,
                accessibleForeground(ink, frame.incomingBubble, floor),
                accessibleForeground(frame.hashtag, frame.incomingBubble, WCAGContrast.AA_FLOOR),
                gradient, gradient?.let { glyphOnGradient(frame.outgoingText, it, floor) } ?: readableGlyph(frame.outgoingText, primary),
            )
        }

        /** Essential component boundaries (fields, toggles) need 3:1; ink-based themes use a muted ink instead of the accent. */
        private fun outline(frame: ThemeFrame, floor: Double): ThemeColor =
            accessibleForeground(frame.ink?.let { frame.canvas.mix(it, 0.55) } ?: frame.accent, frame.canvas, floor)
    }

    fun signalColor(role: SignalColorRole): ThemeColor = accessibleForeground(
        when (role) {
            SignalColorRole.EXCELLENT, SignalColorRole.GOOD -> AppColors.Radio.ready
            SignalColorRole.FAIR -> ThemeColor.hex(0xFFCC00)
            SignalColorRole.POOR -> ThemeColor.hex(0xFF3B30)
            SignalColorRole.UNKNOWN -> onSurfaceVariant
        },
        background, WCAGContrast.AA_FLOOR,
    )

    fun radioColor(role: RadioColorRole): ThemeColor = accessibleForeground(
        when (role) {
            RadioColorRole.DISCONNECTED -> onSurfaceVariant
            RadioColorRole.CONNECTING, RadioColorRole.CONNECTED, RadioColorRole.SYNCING -> AppColors.Radio.connecting
            RadioColorRole.READY -> AppColors.Radio.ready
            RadioColorRole.REPEAT -> AppColors.Radio.repeatMode
            RadioColorRole.FAILED -> error
        },
        background, WCAGContrast.AA_FLOOR,
    )
}

fun readableGlyph(preferred: ThemeColor, background: ThemeColor): ThemeColor {
    if (WCAGContrast.contrastRatio(preferred, background) >= WCAGContrast.AA_FLOOR + 0.05) return preferred
    return if (WCAGContrast.contrastRatio(ThemeColor.WHITE, background) >=
        WCAGContrast.contrastRatio(ThemeColor.BLACK, background)
    ) ThemeColor.WHITE else ThemeColor.BLACK
}

fun accessibleForeground(preferred: ThemeColor, background: ThemeColor, floor: Double): ThemeColor =
    accessibleForegroundOnSurfaces(preferred, listOf(background), floor)

fun accessibleForegroundOnSurfaces(preferred: ThemeColor, backgrounds: List<ThemeColor>, floor: Double): ThemeColor {
    require(backgrounds.isNotEmpty() && backgrounds.all { it.alpha == 1.0 })
    require(floor.isFinite() && floor in 1.0..21.0)
    fun minimumRatio(color: ThemeColor): Double = backgrounds.minOf { WCAGContrast.contrastRatio(color, it) }
    if (minimumRatio(preferred) >= floor + 0.05) return preferred
    val target = if (minimumRatio(ThemeColor.WHITE) >= minimumRatio(ThemeColor.BLACK)) ThemeColor.WHITE else ThemeColor.BLACK
    require(minimumRatio(target) >= floor) { "The requested contrast is unreachable across these surfaces" }
    val desired = minOf(floor + 0.05, minimumRatio(target))
    var low = 0.0
    var high = 1.0
    repeat(32) {
        val mid = (low + high) / 2
        if (minimumRatio(preferred.mix(target, mid)) < desired) low = mid else high = mid
    }
    return preferred.mix(target, high)
}
