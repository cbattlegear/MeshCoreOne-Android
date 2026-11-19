// AndroidOnly: WP-301 Original-family identifiers and real assertion accounting; not production helpers.
package com.meshcoreone.android.core.designsystem

import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
internal annotation class OriginalCase(val id: String, val parameterCount: Int = 1)

internal const val sourcePin = "db14559b39d32322b06477c6ae676112f583db50"
internal val gamutNames = (0 until 600).map { "Sender $it" } + listOf("Bob", "obB", "Alice", "alice", "\u706f\u706b", "S\u00f8ren")
internal val themeNames = (0 until 400).map { "Sender $it" } +
    listOf("Bob", "obB", "Alice", "alice", "\u706f\u706b", "S\u00f8ren", "#general")
/** Frames of the ten pinned Swift themes; the original parameter families keep their exact source counts. */
internal fun effectiveFrames(): List<ThemeFrame> = framesOf(ThemeRegistry.allThemes.filter { it.id != ThemeId.GRADIENT })
/** Frames of the Android-only Gradient theme (dark-only: standard and high contrast). */
internal fun gradientFrames(): List<ThemeFrame> = framesOf(ThemeRegistry.allThemes.filter { it.id == ThemeId.GRADIENT })
private fun framesOf(themes: List<Theme>): List<ThemeFrame> = themes.flatMap { theme ->
    ColorScheme.entries.filter { theme.preferredColorScheme == null || theme.preferredColorScheme == it }.flatMap { scheme ->
        listOf(false, true).map { high -> theme.resolve(scheme, high) }
    }
}
internal fun recordFamily(id: String, parameters: Int, expected: Int, assertions: Int) {
    assertEquals(expected, parameters, "Original parameter family must not be narrowed")
    assertTrue(assertions > 0)
    println("WP301_FAMILY|$id|$parameters|$assertions")
}
