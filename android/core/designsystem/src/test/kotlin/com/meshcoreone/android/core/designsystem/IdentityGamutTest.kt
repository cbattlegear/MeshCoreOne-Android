// PortedFrom: MC1Tests/Theme/IdentityGamutTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.designsystem

import kotlin.test.*
import org.junit.Test

class IdentityGamutTest {
    private val gamut = IdentityGamut(listOf(20.0, 50.0, 95.0, 150.0, 195.0, 235.0, 280.0, 320.0), 0.45, 0.75)
    private fun surfaces(scheme: ColorScheme): List<Double> =
        NativeSystemSurfaces.forAppearance(scheme, false).let { listOf(it.canvas.luminance, it.card.luminance, it.incomingBubble.luminance) }

    @OriginalCase("IdentityGamutTests::every identity color clears AA against its surfaces in both appearances and contrasts()", 2424)
    @Test fun everyOriginalIdentityClearsEverySurface() {
        var parameters = 0
        var assertions = 0
        for (scheme in ColorScheme.entries) for (high in listOf(false, true)) {
            val backgrounds = surfaces(scheme)
            for (name in gamutNames) {
                val color = gamut.color(name, backgrounds, high)
                for (background in backgrounds) {
                    assertTrue(WCAGContrast.contrastRatio(color.luminance, background) >= WCAGContrast.floor(high), name)
                    assertions++
                }
                parameters++
            }
        }
        recordFamily("IdentityGamutTests::every identity color clears AA against its surfaces in both appearances and contrasts()",
            parameters, 2424, assertions)
    }
    @OriginalCase("IdentityGamutTests::avatar glyph clears AA against the identity fill in both appearances()", 1212)
    @Test fun everyOriginalGlyphClearsItsFill() {
        var parameters = 0
        for (scheme in ColorScheme.entries) for (name in gamutNames) {
            val fill = gamut.color(name, surfaces(scheme), false)
            assertTrue(WCAGContrast.contrastRatio(IdentityGamut.glyphColor(fill.luminance), fill) >= 4.5, name)
            parameters++
        }
        recordFamily("IdentityGamutTests::avatar glyph clears AA against the identity fill in both appearances()", parameters, 1212, parameters)
    }
    @OriginalCase("IdentityGamutTests::hue is stable across appearance and contrast for a given name()", 50)
    @Test fun originalHueStabilityFamily() {
        for (name in gamutNames.take(50)) {
            val expected = gamut.resolve(name, surfaces(ColorScheme.LIGHT), false).hue
            for (scheme in ColorScheme.entries) for (high in listOf(false, true)) {
                assertEquals(expected, gamut.resolve(name, surfaces(scheme), high).hue)
            }
        }
        recordFamily("IdentityGamutTests::hue is stable across appearance and contrast for a given name()", 50, 50, 200)
    }
    @Test fun independentFnvVectorsUseUnsignedWrapAndExactUnicodeBytes() {
        val values = mapOf(
            "Bob" to "16566419b10316b4", "obB" to "1a318f1921ed33e2", "Alice" to "123909cb9f15d167",
            "alice" to "508b2abb65a03907", "\u706f\u706b" to "afb9c980c62511ff", "S\u00f8ren" to "b1bbe42929daa3ee",
            "\u00e9" to "0ac21707b7181e01", "e\u0301" to "c0c9d418ee802e1f",
            "\uD83D\uDC69\u200d\uD83D\uDCBB" to "86e60cf9dd49e381",
            AvatarCategory.CHANNEL.gamutSeed to "dc17bd08396ecc95",
            AvatarCategory.REPEATER.gamutSeed to "93c69ddbd820723e",
            AvatarCategory.ROOM.gamutSeed to "e4afa67046c9be5d",
        )
        for ((name, expected) in values) assertEquals(expected, java.lang.Long.toUnsignedString(IdentityGamut.fnv1a(name), 16).padStart(16, '0'))
        assertNotEquals(IdentityGamut.fnv1a("\u00e9"), IdentityGamut.fnv1a("e\u0301"))
        assertNotEquals(IdentityGamut.fnv1a("Bob"), IdentityGamut.fnv1a("obB"))
        assertFailsWith<IllegalArgumentException> { IdentityGamut.fnv1a("\uD800") }
        assertFailsWith<IllegalArgumentException> { IdentityGamut.fnv1a("\uDC00") }
    }
    @Test fun sourceHsbSectorsAndWrapping() {
        val expected = listOf(ThemeColor(1.0, 0.0, 0.0), ThemeColor(1.0, 1.0, 0.0),
            ThemeColor(0.0, 1.0, 0.0), ThemeColor(0.0, 1.0, 1.0), ThemeColor(0.0, 0.0, 1.0), ThemeColor(1.0, 0.0, 1.0))
        for (index in expected.indices) assertEquals(expected[index], IdentityGamut.hsbToRgb(index * 60.0, 1.0, 1.0))
        assertEquals(expected[0], IdentityGamut.hsbToRgb(360.0, 1.0, 1.0))
        assertEquals(expected[5], IdentityGamut.hsbToRgb(-60.0, 1.0, 1.0))
    }
    @Test fun sourceEmptyBackgroundFallbackAndCategoryCollisions() {
        assertEquals(gamut.resolve("Alice", listOf(1.0), false), gamut.resolve("Alice", emptyList(), false))
        val names = listOf("same", "same", "same")
        val assigned = gamut.distinctAnchorHues(names)
        assertEquals(3, assigned.distinct().size)
        assertTrue(assigned.all { it in gamut.sortedAnchors })
        assertFailsWith<IllegalArgumentException> { gamut.distinctAnchorHues(List(9) { "same" }) }
    }
    @Test fun saturationRelaxationReachesDarkHighContrastWithoutChangingHue() {
        val normal = gamut.resolve("Alice", listOf(0.07), false)
        val high = gamut.resolve("Alice", listOf(0.07), true)
        assertEquals(normal.hue, high.hue)
        assertTrue(high.saturation <= gamut.maximumSaturation)
        assertTrue(WCAGContrast.contrastRatio(high.color.luminance, 0.07) >= 7.0)
        assertTrue(high.brightness in 0.0..1.0)
    }
    @Test fun invalidConfigurationAndNonfiniteInputsFailExplicitly() {
        assertFailsWith<IllegalArgumentException> { IdentityGamut(emptyList(), 0.4, 0.7) }
        assertFailsWith<IllegalArgumentException> { IdentityGamut(listOf(360.0), 0.4, 0.7) }
        assertFailsWith<IllegalArgumentException> { IdentityGamut(listOf(1.0, 1.0), 0.4, 0.7) }
        assertFailsWith<IllegalArgumentException> { IdentityGamut(listOf(1.0), 0.7, 0.4) }
        assertFailsWith<IllegalArgumentException> { gamut.resolve("Alice", listOf(Double.NaN), false) }
        assertFailsWith<IllegalArgumentException> { gamut.resolve("Alice", listOf(1.0), false, atVariety = 1.1) }
        assertFailsWith<IllegalArgumentException> { gamut.resolve("Alice", listOf(1.0), false, atHue = Double.NaN) }
    }
}
