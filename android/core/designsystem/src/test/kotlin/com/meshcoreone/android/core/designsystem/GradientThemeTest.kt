// AndroidOnly: WP-301 Gradient theme registration, palette, WCAG contrast and gradient-token tests; no Swift original exists.
package com.meshcoreone.android.core.designsystem

import kotlin.test.*
import org.junit.Test

class GradientThemeTest {
    private val theme get() = assertNotNull(ThemeRegistry.theme(ThemeId.GRADIENT))

    @Test fun isRegisteredLastAsAnAndroidOnlyDarkOnlyTheme() {
        assertSame(theme, ThemeRegistry.allThemes.last())
        assertEquals("gradient", theme.id.rawValue)
        assertEquals(ThemeId.GRADIENT, ThemeId.fromRawValue("gradient"))
        assertNull(theme.sourceProductID)
        assertEquals(ColorScheme.DARK, theme.preferredColorScheme)
        for (preference in AppColorSchemePreference.entries) assertEquals(ColorScheme.DARK, theme.effectiveColorScheme(preference))
        assertEquals(theme.accentColor, theme.chromeTint)
        assertNotNull(theme.accentGradient)
        assertEquals(2, gradientFrames().size, "dark-only: standard and high contrast")
        assertTrue(gradientFrames().all { it.colorScheme == ColorScheme.DARK })
    }

    @Test fun usesTheNeonAquaToGreenOnDeepNavyPalette() {
        val frame = theme.resolve(ColorScheme.LIGHT, false)
        assertEquals(ColorScheme.DARK, frame.colorScheme)
        assertEquals(ThemeColor.hex(0x070B14), frame.canvas)
        assertEquals(ThemeColor.hex(0x0D1B30), frame.card)
        assertEquals(ThemeColor.hex(0x19C6E8), frame.accent)
        assertEquals(ThemeColor.hex(0x14E68A), frame.hashtag)
        assertEquals(ThemeColor.hex(0x0F2A4A), frame.canvasGlow)
        assertEquals(AccentGradient(ThemeColor.hex(0x19C6E8), ThemeColor.hex(0x14E68A)), frame.gradient)
        assertTrue(frame.canvas.luminance < 0.01 && frame.card.luminance < 0.02)
        val roles = MaterialRoles.from(frame)
        assertEquals(ThemeColor.hex(0x19C6E8), roles.primary)
        assertEquals(ThemeColor.hex(0x14E68A), roles.secondary)
        assertEquals(ThemeColor.hex(0x14E68A), roles.tertiary)
        assertTrue(roles.onBackground.luminance > 0.7, "near-white text")
    }

    @Test fun everyTextRoleClearsTheAaFloorOnItsSurface() {
        for (frame in gradientFrames()) {
            val floor = WCAGContrast.floor(frame.highContrast)
            val r = MaterialRoles.from(frame)
            val pairs = listOf(
                r.primary to r.onPrimary, r.primaryContainer to r.onPrimaryContainer,
                r.secondary to r.onSecondary, r.secondaryContainer to r.onSecondaryContainer,
                r.tertiary to r.onTertiary, r.tertiaryContainer to r.onTertiaryContainer,
                r.background to r.onBackground, r.surface to r.onSurface, r.surfaceVariant to r.onSurfaceVariant,
                r.error to r.onError, r.errorContainer to r.onErrorContainer,
                r.outgoingBubble to r.onOutgoingBubble, r.incomingBubble to r.onIncomingBubble,
                r.incomingBubble to r.incomingHashtag,
            )
            for ((background, foreground) in pairs) {
                assertTrue(WCAGContrast.contrastRatio(foreground, background) >= floor, "hc=${frame.highContrast} $background/$foreground")
            }
            assertTrue(WCAGContrast.contrastRatio(frame.outgoingText, frame.accent) >= floor)
            assertTrue(WCAGContrast.contrastRatio(frame.hashtag, frame.incomingBubble) >= floor)
            // Foreground roles that sit directly on the canvas also clear the floor.
            for (color in listOf(r.primary, r.secondary, r.tertiary, r.onBackground, r.onSurfaceVariant)) {
                assertTrue(WCAGContrast.contrastRatio(color, frame.canvas) >= floor)
            }
            for (color in listOf(r.primary, r.onSurface, r.onSurfaceVariant)) {
                assertTrue(WCAGContrast.contrastRatio(color, frame.card) >= floor)
            }
        }
    }

    @Test fun textStaysReadableOverTheRadialGlow() {
        for (frame in gradientFrames()) {
            val glow = assertNotNull(frame.canvasGlow)
            val r = MaterialRoles.from(frame)
            for (color in listOf(r.onBackground, r.primary, r.secondary)) {
                assertTrue(WCAGContrast.contrastRatio(color, glow) >= WCAGContrast.floor(frame.highContrast), "${frame.highContrast} $color")
            }
        }
    }

    @Test fun uiComponentsClearThreeToOneAndGradientTextClearsTheFloorOnBothStops() {
        for (frame in gradientFrames()) {
            val r = MaterialRoles.from(frame)
            val surfaces = listOf(frame.canvas, frame.card, frame.incomingBubble)
            val gradient = assertNotNull(r.accentGradient)
            for (surface in surfaces) {
                assertTrue(WCAGContrast.contrastRatio(r.outline, surface) >= 3.0, "outline on $surface")
                for (stop in gradient.stops) assertTrue(WCAGContrast.contrastRatio(stop, surface) >= 3.0, "stop $stop on $surface")
            }
            for (stop in gradient.stops) {
                assertTrue(WCAGContrast.contrastRatio(r.onAccentGradient, stop) >= WCAGContrast.floor(frame.highContrast), "glyph on $stop")
                assertTrue(WCAGContrast.contrastRatio(r.onPrimary, stop) >= WCAGContrast.floor(frame.highContrast), "onPrimary on $stop")
            }
            assertNotEquals(gradient.start, gradient.end)
            assertEquals(gradient.start, gradient.fallback)
        }
    }

    @Test fun outlineVariantStaysASubtleStrokeComparedWithTheEssentialOutline() {
        val r = MaterialRoles.from(gradientFrames().first())
        val frame = gradientFrames().first()
        assertTrue(WCAGContrast.contrastRatio(r.outlineVariant, frame.canvas) < WCAGContrast.contrastRatio(r.outline, frame.canvas))
        assertTrue(WCAGContrast.contrastRatio(r.outlineVariant, frame.canvas) < 3.0)
    }

    @Test fun identityAndCategoryAvatarColorsClearTheFloor() {
        for (frame in gradientFrames()) {
            val floor = WCAGContrast.floor(frame.highContrast)
            for (name in themeNames) {
                val color = frame.identityColor(name)
                for (surface in frame.avatarSurfaceLuminances) assertTrue(WCAGContrast.contrastRatio(color.luminance, surface) >= floor, name)
            }
            val hues = AvatarCategory.anchorPriority.map(theme::categoryHue)
            assertEquals(3, hues.toSet().size)
            assertTrue(hues.all { it in theme.identityGamut.sortedAnchors })
            for (category in AvatarCategory.entries) {
                val fill = frame.categoryAvatarColor(category)
                assertTrue(WCAGContrast.contrastRatio(fill, frame.canvas) >= floor)
                assertTrue(WCAGContrast.contrastRatio(readableGlyph(frame.avatarGlyphColor(fill, false), fill), fill) >= WCAGContrast.AA_FLOOR)
            }
        }
    }

    @Test fun onlyTheGradientThemeProducesAGradientBrushAndEveryOtherThemeKeepsSolidRoles() {
        for (frame in effectiveFrames()) {
            val roles = MaterialRoles.from(frame)
            assertNull(frame.gradient, frame.theme.id.rawValue)
            assertNull(roles.accentGradient)
            assertNull(MeshThemeTokens(frame, roles, "t", 1f).accentBrush)
            assertEquals(roles.onPrimary, roles.onAccentGradient)
        }
        val frame = gradientFrames().first()
        assertNotNull(MeshThemeTokens(frame, MaterialRoles.from(frame), "t", 1f).accentBrush)
    }

    @Test fun nativeMaterialSchemeUsesTheCyanPrimaryAndGreenSecondary() {
        val frame = gradientFrames().first { !it.highContrast }
        val roles = MaterialRoles.from(frame)
        val scheme = roles.toMaterialColorScheme(dark = true)
        assertEquals(ThemeColor.hex(0x19C6E8).toComposeColor(), scheme.primary)
        assertEquals(ThemeColor.hex(0x14E68A).toComposeColor(), scheme.secondary)
        assertEquals(roles.tertiary.toComposeColor(), scheme.tertiary)
    }

    @Test fun lowContrastGradientStopsAreFittedAndGlyphSelectionIsFailSafe() {
        val canvas = ThemeColor.hex(0x070B14)
        val dimmed = AccentGradient(ThemeColor.hex(0x0B2A3A), ThemeColor.hex(0x0A3A2A))
        val fitted = fitGradient(dimmed, listOf(canvas))
        for (stop in fitted.stops) assertTrue(WCAGContrast.contrastRatio(stop, canvas) >= 3.0)
        val mid = AccentGradient(ThemeColor.hex(0x19C6E8), ThemeColor.hex(0x14E68A))
        assertEquals(ThemeColor.hex(0x04101C), glyphOnGradient(ThemeColor.hex(0x04101C), mid, 4.5))
        assertEquals(ThemeColor.BLACK, glyphOnGradient(ThemeColor.WHITE, mid, 4.5), "white fails on bright stops, so black is chosen")
    }
}
