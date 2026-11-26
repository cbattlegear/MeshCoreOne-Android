// PortedFrom: MC1/Views/Appearance/AppearanceView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Settings/Sections/AppearanceSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: every registry theme is unlocked, so there is no "Purchase More Themes" link and no entitlement filtering.
package com.meshcoreone.android.feature.settings.app.appearance

import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.Theme
import com.meshcoreone.android.core.designsystem.ThemeId
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppearanceSelection(val themeId: ThemeId, val colorScheme: AppColorSchemePreference)

/** Theme/scheme persistence seam (the app binds `core:designsystem`'s `ThemeService`; see [ThemeServiceAppearanceController]). */
interface AppearanceController {
    val selection: StateFlow<AppearanceSelection>
    suspend fun setTheme(id: ThemeId)
    suspend fun setColorScheme(preference: AppColorSchemePreference)
}

data class ThemeCard(val theme: Theme, val isSelected: Boolean)

data class AppearanceUiState(
    val cards: List<ThemeCard>,
    val colorScheme: AppColorSchemePreference,
    /** Set when persisting a choice failed; the selection shown is the last persisted one. */
    val failure: Throwable? = null,
) {
    val selectedThemeId: ThemeId? get() = cards.firstOrNull { it.isSelected }?.theme?.id

    companion object {
        /** Cards for every theme in [themes], in registry order; the unlocked-theme list is never filtered. */
        fun from(themes: List<Theme>, selection: AppearanceSelection, failure: Throwable? = null) = AppearanceUiState(
            cards = themes.map { ThemeCard(it, it.id == selection.themeId) },
            colorScheme = selection.colorScheme,
            failure = failure,
        )
    }
}

/** Schemes offered by the picker, in the Swift `CaseIterable` order. */
val appearanceSchemeChoices: List<AppColorSchemePreference> = AppColorSchemePreference.entries

class AppearanceStateHolder(
    private val controller: AppearanceController,
    private val scope: CoroutineScope,
    private val themes: List<Theme> = ThemeRegistry.allThemes,
) {
    private val failure = MutableStateFlow<Throwable?>(null)
    private val mutableState = MutableStateFlow(AppearanceUiState.from(themes, controller.selection.value))
    val state: StateFlow<AppearanceUiState> = mutableState

    /** Starts mirroring [AppearanceController.selection] into [state]. */
    fun start() {
        scope.launch {
            controller.selection.collect { selection ->
                mutableState.value = AppearanceUiState.from(themes, selection, failure.value)
            }
        }
    }

    /** Applies [theme]; a tap on the already-selected card is a no-op (the card is disabled in the UI). */
    fun selectTheme(theme: Theme) {
        if (theme.id == mutableState.value.selectedThemeId) return
        persist { controller.setTheme(theme.id) }
    }

    fun selectColorScheme(preference: AppColorSchemePreference) {
        if (preference == mutableState.value.colorScheme) return
        persist { controller.setColorScheme(preference) }
    }

    fun dismissFailure() {
        failure.value = null
        mutableState.update { it.copy(failure = null) }
    }

    private fun persist(change: suspend () -> Unit) {
        scope.launch {
            try {
                change()
                failure.value = null
                mutableState.update { it.copy(failure = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                failure.value = problem
                mutableState.update { it.copy(failure = problem) }
            }
        }
    }
}
