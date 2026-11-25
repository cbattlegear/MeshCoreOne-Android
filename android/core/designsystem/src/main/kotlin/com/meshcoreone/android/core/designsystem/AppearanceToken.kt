// PortedFrom: MC1/Theme/AppearanceToken.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.designsystem

enum class ContentSizeCategory(val token: String) {
    X_SMALL("xSmall"), SMALL("small"), MEDIUM("medium"), LARGE("large"), X_LARGE("xLarge"),
    XX_LARGE("xxLarge"), XXX_LARGE("xxxLarge"), ACCESSIBILITY_1("accessibility1"),
    ACCESSIBILITY_2("accessibility2"), ACCESSIBILITY_3("accessibility3"), ACCESSIBILITY_4("accessibility4"),
    ACCESSIBILITY_5("accessibility5"), UNKNOWN("unknown");
}

object AppearanceToken {
    fun make(scheme: ColorScheme, highContrast: Boolean, category: ContentSizeCategory): String =
        "${if (scheme == ColorScheme.DARK) "dark" else "light"}-${if (highContrast) "hc" else "std"}-${category.token}"

    fun native(scheme: ColorScheme, highContrast: Boolean, fontScale: Float, density: Float, rtl: Boolean): String {
        require(fontScale.isFinite() && fontScale > 0 && density.isFinite() && density > 0)
        return "${if (scheme == ColorScheme.DARK) "dark" else "light"}-${if (highContrast) "hc" else "std"}-" +
            "${fontScale.toRawBits()}-${density.toRawBits()}-${if (rtl) "rtl" else "ltr"}"
    }
}

// PortedFrom: MC1/Theme/ThemeCardMetrics.swift@5e980a53e077c45a1270170612fdf5dae97792ff
object ThemeCardMetrics {
    const val CORNER_RADIUS = 14
    const val CONTENT_PADDING = 8
    const val SELECTION_STROKE_WIDTH = 2
    const val BADGE_ICON_SPACING = 3
    const val GRID_ITEM_MINIMUM = 160
    const val GRID_SPACING = 12
    const val GRID_VERTICAL_INSET = 8
    const val GRID_HORIZONTAL_INSET = 16
    const val ALL_UNLOCKED_EMOJI_SIZE = 56
    const val ALL_UNLOCKED_SPACING = 12
    const val ALL_UNLOCKED_VERTICAL_PADDING = 24
}
