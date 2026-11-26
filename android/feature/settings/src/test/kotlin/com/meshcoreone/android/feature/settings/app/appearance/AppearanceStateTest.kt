// PortedFrom: MC1Tests/AppearanceSelectionTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.app.appearance

import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.ThemeId
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.feature.settings.app.support.OriginalCase
import com.meshcoreone.android.feature.settings.app.support.scenario
import com.meshcoreone.android.feature.settings.app.support.settle
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

class AppearanceStateTest {
    private class FakeController(initial: AppearanceSelection = AppearanceSelection(ThemeId.DEFAULT, AppColorSchemePreference.SYSTEM)) : AppearanceController {
        override val selection = MutableStateFlow(initial)
        val calls = mutableListOf<String>()
        var failure: Exception? = null

        override suspend fun setTheme(id: ThemeId) {
            calls += "theme:${id.rawValue}"
            failure?.let { throw it }
            selection.value = selection.value.copy(themeId = id)
        }

        override suspend fun setColorScheme(preference: AppColorSchemePreference) {
            calls += "scheme:${preference.rawValue}"
            failure?.let { throw it }
            selection.value = selection.value.copy(colorScheme = preference)
        }
    }

    @Test
    @OriginalCase("AppearanceSelectionTests::with no purchases, only the default theme is available and Browse-more is shown()", "platform-adaptation")
    fun `every registry theme is offered, unlocked and in registry order`() {
        val state = AppearanceUiState.from(ThemeRegistry.allThemes, AppearanceSelection(ThemeId.DEFAULT, AppColorSchemePreference.SYSTEM))
        assertEquals(ThemeRegistry.allThemes.map { it.id }, state.cards.map { it.theme.id })
        assertTrue(state.cards.size > 1)
        assertEquals(listOf(ThemeId.DEFAULT), state.cards.filter { it.isSelected }.map { it.theme.id })
    }

    @Test
    @OriginalCase("AppearanceSelectionTests::a theme owned via the bundle becomes available; selecting it updates current()", "platform-adaptation")
    fun `selecting any theme applies it and marks only that card selected`() = scenario {
        val controller = FakeController()
        val holder = AppearanceStateHolder(controller, scope)
        holder.start()
        holder.selectTheme(ThemeRegistry.theme(ThemeId.MARINE)!!)
        settle()
        assertEquals(listOf("theme:marine"), controller.calls)
        assertEquals(ThemeId.MARINE, holder.state.value.selectedThemeId)
        assertEquals(1, holder.state.value.cards.count { it.isSelected })
    }

    @Test
    @OriginalCase("AppearanceSelectionTests::owning the bundle makes every theme available and hides Browse-more()", "platform-adaptation")
    fun `all themes are reachable with no purchase path`() = scenario {
        val controller = FakeController()
        val holder = AppearanceStateHolder(controller, scope)
        holder.start()
        for (theme in ThemeRegistry.allThemes.filter { it.id != ThemeId.DEFAULT }) {
            holder.selectTheme(theme)
            settle()
            assertEquals(theme.id, holder.state.value.selectedThemeId)
        }
        assertEquals(ThemeRegistry.allThemes.size, holder.state.value.cards.size)
    }

    @Test
    fun `tapping the already selected theme or scheme does nothing`() = scenario {
        val controller = FakeController(AppearanceSelection(ThemeId.NORD, AppColorSchemePreference.DARK))
        val holder = AppearanceStateHolder(controller, scope)
        holder.selectTheme(ThemeRegistry.theme(ThemeId.NORD)!!)
        holder.selectColorScheme(AppColorSchemePreference.DARK)
        settle()
        assertTrue(controller.calls.isEmpty())
    }

    @Test
    fun `scheme choices keep the Swift order and apply`() = scenario {
        assertEquals(
            listOf(AppColorSchemePreference.SYSTEM, AppColorSchemePreference.LIGHT, AppColorSchemePreference.DARK), appearanceSchemeChoices,
        )
        val controller = FakeController()
        val holder = AppearanceStateHolder(controller, scope)
        holder.start()
        holder.selectColorScheme(AppColorSchemePreference.LIGHT)
        settle()
        assertEquals(AppColorSchemePreference.LIGHT, holder.state.value.colorScheme)
    }

    @Test
    fun `a failed write surfaces the failure, keeps the persisted selection and can be dismissed`() = scenario {
        val controller = FakeController()
        controller.failure = IllegalStateException("disk full")
        val holder = AppearanceStateHolder(controller, scope)
        holder.start()
        holder.selectTheme(ThemeRegistry.theme(ThemeId.EMBER)!!)
        settle()
        assertEquals("disk full", assertNotNull(holder.state.value.failure).message)
        assertEquals(ThemeId.DEFAULT, holder.state.value.selectedThemeId)
        holder.dismissFailure()
        assertNull(holder.state.value.failure)
    }

    @Test
    fun `external selection changes are mirrored into state`() = scenario {
        val controller = FakeController()
        val holder = AppearanceStateHolder(controller, scope)
        holder.start()
        controller.selection.value = AppearanceSelection(ThemeId.FERN, AppColorSchemePreference.DARK)
        settle()
        assertEquals(ThemeId.FERN, holder.state.value.selectedThemeId)
        assertIs<AppColorSchemePreference>(holder.state.value.colorScheme)
        assertEquals(AppColorSchemePreference.DARK, holder.state.value.colorScheme)
    }
}
