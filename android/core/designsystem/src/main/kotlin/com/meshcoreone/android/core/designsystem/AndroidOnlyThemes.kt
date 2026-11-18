// AndroidOnly: WP-301 themes that do not exist in the pinned Swift registry. GeneratedThemes.kt stays byte-exact with the
// converter output; these are appended by ThemeRegistry. See docs/android/deviations/WP-301.md (Gradient theme).
package com.meshcoreone.android.core.designsystem

import com.meshcoreone.android.core.model.SnapshotList

internal object AndroidOnlyThemes {
    /** Dark-only palette: the same value for the light slots, with a brighter/darker high-contrast variant. */
    private fun darkOnly(standard: Long, highContrast: Long = standard): AdaptiveColor {
        val std = ThemeColor.hex(standard)
        val high = ThemeColor.hex(highContrast)
        return AdaptiveColor(std, std, high, high)
    }

    val gradient = Theme(
        id = ThemeId.GRADIENT,
        displayNameKey = null,
        sourceProductID = null,
        accentColor = darkOnly(0x19C6E8, 0x4FD8F0),
        outgoingTextColor = darkOnly(0x04101C),
        hashtagColor = darkOnly(0x14E68A, 0x4DF0A5),
        preferredColorScheme = ColorScheme.DARK,
        identityGamut = IdentityGamut(listOf(150.0, 160.0, 170.0, 180.0, 190.0, 200.0, 210.0), 0.45, 0.8),
        surfaces = ThemeSurfaces(darkOnly(0x070B14, 0x03060C), darkOnly(0x0D1B30, 0x091426)),
        categoryHues = CategoryHues(190.0, 170.0, 150.0),
        accentGradient = AdaptiveGradient(darkOnly(0x19C6E8, 0x4FD8F0), darkOnly(0x14E68A, 0x4DF0A5)),
        inkColor = darkOnly(0xE6F1FF, 0xFFFFFF),
        canvasGlow = darkOnly(0x0F2A4A, 0x0A1C33),
    )

    val all: SnapshotList<Theme> = SnapshotList.of(gradient)
}
