// AndroidOnly: WP-304 The accent control wrappers stay clickable, enabled/selected-aware and visible in the solid and Gradient themes.
package com.meshcoreone.android.core.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeId
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
class AccentControlsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    @Before fun openWindow() { controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible() }
    @After fun closeWindow() { controller.pause().stop().destroy() }

    private fun mount(themeId: ThemeId, content: @Composable () -> Unit) {
        val theme = checkNotNull(ThemeRegistry.theme(themeId.rawValue))
        controller.get().setContent {
            MeshCoreTheme(theme, AppColorSchemePreference.DARK, systemDark = true, highContrast = false, motionScale = 0f, content = content)
        }
    }

    @Test fun `button clicks and stays enabled in the solid and gradient themes`() {
        for (id in listOf(ThemeId.DEFAULT, ThemeId.GRADIENT)) {
            var clicks = 0
            mount(id) { Button({ clicks++ }) { Text("Go ${id.rawValue}") } }
            compose.onNodeWithText("Go ${id.rawValue}").assertIsDisplayed().assertIsEnabled().performClick()
            assertEquals(1, clicks, id.rawValue)
        }
    }

    @Test fun `disabled button reports disabled in both themes`() {
        for (id in listOf(ThemeId.DEFAULT, ThemeId.GRADIENT)) {
            mount(id) { Button({}, enabled = false) { Text("Off ${id.rawValue}") } }
            compose.onNodeWithText("Off ${id.rawValue}").assertIsNotEnabled()
        }
    }

    @Test fun `filter chip selection reaches the semantics`() {
        for (id in listOf(ThemeId.DEFAULT, ThemeId.GRADIENT)) {
            mount(id) { FilterChip(selected = true, onClick = {}, label = { Text("Chip ${id.rawValue}") }) }
            compose.onNodeWithText("Chip ${id.rawValue}").assertIsSelected()
        }
    }
}
