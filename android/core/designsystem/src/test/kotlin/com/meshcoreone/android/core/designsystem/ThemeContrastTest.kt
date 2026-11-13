// PortedFrom: MC1Tests/Theme/ThemeContrastTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.designsystem

import kotlin.test.*
import org.junit.Test

class ThemeContrastTest {
    @OriginalCase("ThemeContrastTests::outgoing text clears WCAG AA 4.5:1 against the accent in every appearance()", 34)
    @Test fun sourceOutgoingContrastFamily() {
        val frames = effectiveFrames().filter { it.theme.id != ThemeId.DEFAULT }
        for (frame in frames) assertTrue(WCAGContrast.contrastRatio(frame.outgoingText, frame.accent) >= 4.5, frame.theme.id.rawValue)
        recordFamily("ThemeContrastTests::outgoing text clears WCAG AA 4.5:1 against the accent in every appearance()", frames.size, 34, frames.size)
    }
    @OriginalCase("ThemeContrastTests::incoming hashtag links clear WCAG AA 4.5:1 against the incoming bubble in every appearance()", 34)
    @Test fun sourceHashtagContrastFamily() {
        val frames = effectiveFrames().filter { it.theme.id != ThemeId.DEFAULT }
        for (frame in frames) assertTrue(WCAGContrast.contrastRatio(frame.hashtag, frame.incomingBubble) >= 4.5, frame.theme.id.rawValue)
        recordFamily("ThemeContrastTests::incoming hashtag links clear WCAG AA 4.5:1 against the incoming bubble in every appearance()", frames.size, 34, frames.size)
    }
    @OriginalCase("ThemeContrastTests::identity colors clear AA against their surfaces for every theme and appearance()", 15466)
    @Test fun allOriginalThemeIdentityParameters() {
        var parameters = 0
        for (frame in effectiveFrames()) for (name in themeNames) {
            val color = frame.identityColor(name)
            for (surface in frame.avatarSurfaceLuminances) {
                assertTrue(WCAGContrast.contrastRatio(color.luminance, surface) >= WCAGContrast.floor(frame.highContrast),
                    "${frame.theme.id.rawValue}:$name")
            }
            parameters++
        }
        recordFamily("ThemeContrastTests::identity colors clear AA against their surfaces for every theme and appearance()", parameters, 15466, parameters * 2)
    }
    @OriginalCase("ThemeContrastTests::gamut-derived category colors clear AA against the list canvas()", 102)
    @Test fun originalCategoryContrastParameters() {
        var parameters = 0
        for (frame in effectiveFrames().filter { it.theme.categoryAvatarOverride == null }) for (category in AvatarCategory.anchorPriority) {
            val color = frame.categoryAvatarColor(category)
            assertTrue(WCAGContrast.contrastRatio(color, frame.canvas) >= WCAGContrast.floor(frame.highContrast), frame.theme.id.rawValue)
            parameters++
        }
        recordFamily("ThemeContrastTests::gamut-derived category colors clear AA against the list canvas()", parameters, 102, parameters)
    }
    @OriginalCase("ThemeContrastTests::channel, repeater, and room avatars resolve to distinct on-anchor hues for every gamut theme()", 9)
    @Test fun originalCategoryDistinctHueParameters() {
        val themes = ThemeRegistry.allThemes.filter { it.categoryAvatarOverride == null && it.id != ThemeId.GRADIENT }
        for (theme in themes) {
            val hues = AvatarCategory.anchorPriority.map(theme::categoryHue)
            assertEquals(3, hues.toSet().size)
            assertTrue(hues.all { it in theme.identityGamut.sortedAnchors })
        }
        recordFamily("ThemeContrastTests::channel, repeater, and room avatars resolve to distinct on-anchor hues for every gamut theme()", themes.size, 9, 18)
    }
    @OriginalCase("ThemeContrastTests::avatar glyph clears AA against the identity fill for every theme and appearance()", 3800)
    @Test fun allOriginalThemeGlyphParameters() {
        var parameters = 0
        for (frame in effectiveFrames()) for (name in themeNames.take(100)) {
            val fill = frame.theme.identityGamut.color(name, frame.avatarSurfaceLuminances, false)
            val glyph = IdentityGamut.glyphColor(fill.luminance)
            assertTrue(WCAGContrast.contrastRatio(glyph, fill) >= 4.5, frame.theme.id.rawValue)
            parameters++
        }
        recordFamily("ThemeContrastTests::avatar glyph clears AA against the identity fill for every theme and appearance()", parameters, 3800, parameters)
    }
    @Test fun systemHighContrastExceptionIsPreservedAndNativeTextIsCorrected() {
        val frame = ThemeRegistry.default.resolve(ColorScheme.LIGHT, true)
        assertEquals(ThemeColor(0.196, 0.471, 0.961), frame.accent)
        assertEquals(ThemeColor.WHITE, frame.outgoingText)
        assertEquals(4.080562510192782, WCAGContrast.contrastRatio(frame.outgoingText, frame.accent), 1e-12)
        val roles = MaterialRoles.from(frame)
        assertTrue(WCAGContrast.contrastRatio(roles.onOutgoingBubble, roles.outgoingBubble) >= 4.5)
        assertEquals(frame.accent, roles.outgoingBubble)
    }
    @Test fun everyMaterialAndNativeMessageForegroundClearsItsActualSurface() {
        var pairs = 0
        for (frame in effectiveFrames()) {
            val role = MaterialRoles.from(frame)
            val colors = listOf(role.primary to role.onPrimary, role.primaryContainer to role.onPrimaryContainer,
                role.secondary to role.onSecondary, role.secondaryContainer to role.onSecondaryContainer,
                role.tertiary to role.onTertiary, role.tertiaryContainer to role.onTertiaryContainer,
                role.background to role.onBackground, role.surface to role.onSurface,
                role.surfaceVariant to role.onSurfaceVariant, role.error to role.onError,
                role.errorContainer to role.onErrorContainer, role.outgoingBubble to role.onOutgoingBubble,
                role.incomingBubble to role.onIncomingBubble, role.incomingBubble to role.incomingHashtag)
            for ((background, foreground) in colors) {
                assertTrue(WCAGContrast.contrastRatio(foreground, background) >= 4.5, frame.theme.id.rawValue)
                pairs++
            }
            for (category in AvatarCategory.entries) {
                val fill = frame.categoryAvatarColor(category)
                val nativeGlyph = readableGlyph(frame.avatarGlyphColor(fill, frame.theme.usesCategoryAvatarOverride), fill)
                assertTrue(WCAGContrast.contrastRatio(nativeGlyph, fill) >= 4.5)
            }
        }
        assertEquals(532, pairs)
    }
    @Test fun sourceWcagConstantsClampingAlphaAndWideGamutAreDeliberate() {
        assertEquals(4.5, WCAGContrast.AA_FLOOR)
        assertEquals(7.0, WCAGContrast.INCREASED_CONTRAST_FLOOR)
        assertEquals(0.0, WCAGContrast.relativeLuminance(-1.0, -2.0, -3.0))
        assertEquals(1.0, WCAGContrast.relativeLuminance(2.0, 3.0, 4.0), 1e-12)
        assertEquals(21.0, WCAGContrast.contrastRatio(0.0, 1.0))
        assertEquals(21.0, WCAGContrast.contrastRatio(1.0, 0.0))
        assertEquals(ThemeColor.WHITE, ThemeColor.hex(-1))
        assertEquals(ThemeColor.hex(0x2463EB), ThemeColor.hex(0x1002463EB))
        val p3Red = ThemeColor(1.0, 0.0, 0.0, 0.5, ThemeColorSpace.DISPLAY_P3).toSrgb()
        assertEquals(1.0, p3Red.red)
        assertEquals(0.0, p3Red.green)
        assertEquals(0.0, p3Red.blue)
        assertEquals(0.5, p3Red.alpha)
        assertEquals(ThemeColor(1.0, 0.5, 0.5), ThemeColor(1.0, 0.0, 0.0, 0.5).compositeOver(ThemeColor.WHITE))
        assertEquals(0.8, AppColors.Message.outgoingBubbleFailed(false).alpha)
        assertEquals(1.0, AppColors.Message.outgoingBubbleFailed(true).alpha)
        assertFailsWith<IllegalArgumentException> { ThemeColor(Double.NaN, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { accessibleForeground(ThemeColor.BLACK, ThemeColor(0.5, 0.5, 0.5), 21.0) }
    }
    @Test fun materialPrimaryTextClearsCanvasCardAndAllExportedSurfaceTiers() {
        assertFailsWith<IllegalArgumentException> {
            accessibleForegroundOnSurfaces(ThemeColor.BLACK, emptyList(), 4.5)
        }
        assertFailsWith<IllegalArgumentException> {
            accessibleForegroundOnSurfaces(ThemeColor.BLACK, listOf(ThemeColor.WHITE.copy(alpha = 0.5)), 4.5)
        }
        assertFailsWith<IllegalArgumentException> {
            accessibleForegroundOnSurfaces(ThemeColor.BLACK, listOf(ThemeColor.WHITE, ThemeColor.BLACK), 4.5)
        }
        val marine = assertNotNull(ThemeRegistry.theme("marine")).resolve(ColorScheme.DARK, false)
        assertEquals(ThemeColor(0.055, 0.086, 0.125), marine.canvas)
        assertEquals(ThemeColor(0.102, 0.137, 0.188), marine.card)
        for (frame in effectiveFrames()) {
            val native = MaterialRoles.from(frame).toMaterialColorScheme(frame.colorScheme == ColorScheme.DARK)
            for (background in listOf(native.background, native.surface, native.surfaceVariant,
                native.surfaceContainerLow, native.surfaceContainer, native.surfaceContainerHigh,
                native.surfaceContainerHighest)) {
                assertTrue(WCAGContrast.contrastRatio(native.primary.toThemeColor(), background.toThemeColor()) >= WCAGContrast.floor(frame.highContrast),
                    "${frame.theme.id.rawValue}:${frame.colorScheme}:${frame.highContrast}")
            }
            assertTrue(WCAGContrast.contrastRatio(native.onPrimary.toThemeColor(), native.primary.toThemeColor()) >= 4.5)
            assertEquals(frame.theme.accentColor.resolve(frame.colorScheme, frame.highContrast), frame.accent)
        }
    }
}
