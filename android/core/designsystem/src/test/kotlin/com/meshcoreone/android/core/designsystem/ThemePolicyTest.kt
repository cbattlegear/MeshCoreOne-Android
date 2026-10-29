// PortedFrom: MC1Tests/AppColorSchemePreferenceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/ThemeRegistryTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Theme/ThemeStructureTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.designsystem

import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.test.*
import org.junit.Test

class ThemePolicyTest {
    @OriginalCase("AppColorSchemePreferenceTests::raw values are pinned to the on-disk format()")
    @Test fun preferenceRawValues() {
        assertEquals(listOf("system", "light", "dark"), AppColorSchemePreference.entries.map { it.rawValue })
    }
    @OriginalCase("AppColorSchemePreferenceTests::colorScheme maps system to nil and light/dark to their schemes()")
    @Test fun preferenceSchemes() {
        assertNull(AppColorSchemePreference.SYSTEM.colorScheme)
        assertEquals(ColorScheme.LIGHT, AppColorSchemePreference.LIGHT.colorScheme)
        assertEquals(ColorScheme.DARK, AppColorSchemePreference.DARK.colorScheme)
    }
    @OriginalCase("AppColorSchemePreferenceTests::allCases is exactly system, light, dark and id equals rawValue()")
    @Test fun preferenceCases() {
        assertEquals(listOf(AppColorSchemePreference.SYSTEM, AppColorSchemePreference.LIGHT, AppColorSchemePreference.DARK),
            AppColorSchemePreference.entries)
        assertEquals(listOf("system", "light", "dark"), AppColorSchemePreference.entries.map { it.id })
        for (preference in AppColorSchemePreference.entries) {
            assertEquals(preference.rawValue, preference.id)
            assertEquals(preference, AppColorSchemePreference.fromRawValue(preference.id))
        }
        assertEquals(AppColorSchemePreference.DARK, AppColorSchemePreference.fromRawValue("dark"))
        assertNull(AppColorSchemePreference.fromRawValue("auto"))
    }
    @OriginalCase("ThemeRegistryTests::allThemes holds the ten built-ins()")
    @Test fun registryOrder() {
        assertEquals(listOf("default", "ember", "fern", "marine", "olive", "lavender", "sakura", "solarized", "nord", "catppuccin"),
            ThemeRegistry.allThemes.map { it.id.rawValue })
    }
    @OriginalCase("ThemeRegistryTests::theme(forID:) resolves known IDs and returns nil for unknown()")
    @Test fun registryLookup() {
        assertEquals(ThemeId.DEFAULT, ThemeRegistry.theme("default")?.id)
        assertEquals(ThemeId.EMBER, ThemeRegistry.theme("ember")?.id)
        assertNull(ThemeRegistry.theme("does-not-exist"))
        assertNull(ThemeRegistry.theme("EMBER"))
    }
    @OriginalCase("ThemeRegistryTests::default theme has no productID; all paid themes do()")
    @Test fun originalProductMetadata() {
        assertNull(ThemeRegistry.default.sourceProductID)
        assertEquals(9, ThemeRegistry.allThemes.count { it.sourceProductID != null })
    }
    @OriginalCase("ThemeRegistryTests::paid theme productIDs match StoreCatalog's bundled theme IDs()")
    @Test fun productIDsAreInertSourceProvenance() {
        val expected = listOf("ember", "fern", "marine", "olive", "lavender", "sakura", "solarized", "nord", "catppuccin")
            .map { "io.pocketmesh.app.theme.$it" }.toSet()
        assertEquals(expected, ThemeRegistry.allThemes.mapNotNull { it.sourceProductID }.toSet())
        assertEquals("io.pocketmesh.app.theme.ember", ThemeRegistry.theme("ember")?.sourceProductID)
    }
    @OriginalCase("ThemeRegistryTests::only Ember forces a color scheme, and it forces dark()")
    @Test fun forcedScheme() {
        assertEquals(listOf(ThemeId.EMBER), ThemeRegistry.allThemes.filter { it.preferredColorScheme != null }.map { it.id })
        assertEquals(ColorScheme.DARK, ThemeRegistry.theme("ember")?.preferredColorScheme)
    }
    @OriginalCase("ThemeRegistryTests::theme IDs are unique()")
    @Test fun uniqueIDs() {
        assertEquals(10, ThemeRegistry.allThemes.map { it.id }.toSet().size)
    }
    @OriginalCase("ThemeStructureTests::Default theme paints no surfaces()")
    @Test fun defaultHasNoSurfaces() { assertNull(ThemeRegistry.default.surfaces) }
    @OriginalCase("ThemeStructureTests::Default theme imposes no chrome tint, deferring to the system()")
    @Test fun defaultHasNoChromeTint() { assertNull(ThemeRegistry.default.chromeTint) }
    @OriginalCase("ThemeStructureTests::Paid themes impose their accent on chrome()")
    @Test fun namedThemeChrome() {
        for (theme in ThemeRegistry.allThemes.drop(1)) assertEquals(theme.accentColor, theme.chromeTint)
    }
    @OriginalCase("ThemeStructureTests::Ember paints the canvas but not the card tier()")
    @Test fun emberCanvasOnly() {
        val surfaces = assertNotNull(ThemeRegistry.theme("ember")?.surfaces)
        assertEquals(ThemeColor.BLACK, surfaces.canvas.dark)
        assertNull(surfaces.card)
    }
    @OriginalCase("ThemeStructureTests::Every painted theme defines both canvas and card tiers()")
    @Test fun allOtherSurfaceTiers() {
        for (theme in ThemeRegistry.allThemes.filter { it.id !in setOf(ThemeId.DEFAULT, ThemeId.EMBER) }) {
            assertNotNull(assertNotNull(theme.surfaces).card, theme.id.rawValue)
        }
    }
    @OriginalCase("ThemeStructureTests::Every theme defines a usable identity gamut()")
    @Test fun usableGamuts() {
        for (theme in ThemeRegistry.allThemes) {
            assertTrue(theme.identityGamut.hueAnchors.isNotEmpty())
            assertTrue(theme.identityGamut.hueAnchors.all { it >= 0 && it < 360 })
            assertTrue(theme.identityGamut.minimumSaturation >= 0)
            assertTrue(theme.identityGamut.maximumSaturation <= 1)
            assertTrue(theme.identityGamut.minimumSaturation < theme.identityGamut.maximumSaturation)
        }
    }
    @OriginalCase("ThemeStructureTests::Only the System theme pins fixed category avatar colors()")
    @Test fun systemCategoryOverrides() {
        val overrides = assertNotNull(ThemeRegistry.default.categoryAvatarOverride)
        assertEquals(ThemeColor.hex(0x336688), overrides.channel)
        assertEquals(ThemeColor.hex(0x00AAFF), overrides.repeaterNode)
        assertEquals(ThemeColor.hex(0xFF8800), overrides.room)
        assertTrue(ThemeRegistry.allThemes.drop(1).all { it.categoryAvatarOverride == null })
        for (category in AvatarCategory.entries) {
            val frame = ThemeRegistry.default.resolve(ColorScheme.LIGHT, true)
            assertEquals(overrides.color(category), frame.categoryAvatarColor(category))
            assertEquals(ThemeColor.WHITE, frame.avatarGlyphColor(overrides.color(category), true))
        }
    }
    @OriginalCase("ThemeStructureTests::Migrated themes' outgoingTextColor resolves differently in light vs dark()")
    @Test fun migratedOutgoingText() {
        for (id in listOf("fern", "olive", "lavender", "sakura")) {
            val theme = assertNotNull(ThemeRegistry.theme(id))
            assertNotEquals(theme.outgoingTextColor.light, theme.outgoingTextColor.dark)
        }
    }
    @Test fun everyPreferenceAndSystemCombinationRespectsForcedSchemes() {
        var combinations = 0
        for (theme in ThemeRegistry.allThemes) for (preference in AppColorSchemePreference.entries) {
            for (system in ColorScheme.entries) for (high in listOf(false, true)) {
                val chosen = theme.effectiveColorScheme(preference) ?: system
                assertEquals(if (theme.id == ThemeId.EMBER) ColorScheme.DARK else preference.colorScheme ?: system,
                    theme.resolve(chosen, high).colorScheme)
                combinations++
            }
        }
        assertEquals(120, combinations)
    }
    @Test fun rowFlatteningAndSourceCardMetrics() {
        val surfaces = assertNotNull(ThemeRegistry.theme("marine")?.surfaces)
        assertSame(surfaces.card, surfaces.rowFill(false))
        assertNull(surfaces.rowFill(true))
        assertNull(ThemeRegistry.theme("ember")?.surfaces?.rowFill(false))
        assertEquals(listOf(14, 8, 2, 3, 160, 12, 8, 16),
            listOf(ThemeCardMetrics.CORNER_RADIUS, ThemeCardMetrics.CONTENT_PADDING,
                ThemeCardMetrics.SELECTION_STROKE_WIDTH, ThemeCardMetrics.BADGE_ICON_SPACING,
                ThemeCardMetrics.GRID_ITEM_MINIMUM, ThemeCardMetrics.GRID_SPACING,
                ThemeCardMetrics.GRID_VERTICAL_INSET, ThemeCardMetrics.GRID_HORIZONTAL_INSET))
    }
    @Test fun framesAndPaletteCollectionsHaveValueSemantics() {
        val anchors = mutableListOf(20.0, 80.0, 180.0)
        val gamut = IdentityGamut(anchors, 0.4, 0.7)
        val before = gamut.resolve("Alice", listOf(1.0), false)
        anchors[0] = 300.0
        assertEquals(before, gamut.resolve("Alice", listOf(1.0), false))
        val bytes = byteArrayOf(0, -1, 5)
        val first = AvatarColorInput("Alice", Bytes(bytes))
        val second = AvatarColorInput("Alice", Bytes(byteArrayOf(0, -1, 5)))
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(byteArrayOf(0, -1, 5), byteArrayOf(0, -1, 5), "Raw Kotlin arrays are not identity values")
        bytes[1] = 0
        assertEquals(first, second)
        val frame = ThemeRegistry.default.resolve(ColorScheme.LIGHT, false)
        assertEquals(frame, frame.copy())
        assertNotEquals(frame, frame.copy(highContrast = true))
        assertFails {
            (ThemeRegistry.allThemes as MutableList<Theme>).clear()
        }
    }
    @Test fun sourceAndNativeAppearanceTokensInvalidateAllRelevantInputs() {
        assertEquals("light-std-large", AppearanceToken.make(ColorScheme.LIGHT, false, ContentSizeCategory.LARGE))
        assertEquals("dark-hc-accessibility5", AppearanceToken.make(ColorScheme.DARK, true, ContentSizeCategory.ACCESSIBILITY_5))
        assertEquals(13, ContentSizeCategory.entries.map { it.token }.toSet().size)
        val base = AppearanceToken.native(ColorScheme.LIGHT, false, 1f, 1f, false)
        assertNotEquals(base, AppearanceToken.native(ColorScheme.LIGHT, false, 2f, 1f, false))
        assertNotEquals(base, AppearanceToken.native(ColorScheme.LIGHT, true, 1f, 1f, false))
        assertNotEquals(base, AppearanceToken.native(ColorScheme.DARK, false, 1f, 1f, false))
        assertNotEquals(base, AppearanceToken.native(ColorScheme.LIGHT, false, 1f, 2f, false))
        assertNotEquals(base, AppearanceToken.native(ColorScheme.LIGHT, false, 1f, 1f, true))
        assertEquals(0, NativeThemeMetrics.duration(200, 0f))
        assertEquals(400, NativeThemeMetrics.duration(200, 2f))
        assertFailsWith<IllegalArgumentException> { NativeThemeMetrics.duration(200, Float.NaN) }
    }
}
