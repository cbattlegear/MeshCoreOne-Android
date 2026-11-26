// AndroidOnly: WP-302 system-bar legibility follows the effective app theme.
package com.meshcoreone.android.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.WindowCompat
import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SystemBarsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()

    @After fun close() { host.pause().stop().destroy() }

    @Test fun appPreferenceAndForcedDarkUpdateBothSystemBars() {
        val theme = mutableStateOf(ThemeRegistry.default)
        val preference = mutableStateOf(AppColorSchemePreference.LIGHT)
        host.get().setContent {
            MeshCoreTheme(theme.value, preference.value, systemDark = false) {
                MeshSystemBars(host.get().window)
                Text("Content")
            }
        }
        val controller = WindowCompat.getInsetsController(host.get().window, host.get().window.decorView)
        compose.runOnIdle {
            assertTrue(controller.isAppearanceLightStatusBars)
            assertTrue(controller.isAppearanceLightNavigationBars)
            assertFalse(host.get().window.isNavigationBarContrastEnforced)
            preference.value = AppColorSchemePreference.DARK
        }
        compose.runOnIdle {
            assertFalse(controller.isAppearanceLightStatusBars)
            assertFalse(controller.isAppearanceLightNavigationBars)
            preference.value = AppColorSchemePreference.LIGHT
            theme.value = checkNotNull(ThemeRegistry.theme("ember"))
        }
        compose.runOnIdle {
            assertFalse(controller.isAppearanceLightStatusBars)
            assertFalse(controller.isAppearanceLightNavigationBars)
            theme.value = ThemeRegistry.default
        }
        compose.runOnIdle {
            assertTrue(controller.isAppearanceLightStatusBars)
            assertTrue(controller.isAppearanceLightNavigationBars)
        }
    }
}
