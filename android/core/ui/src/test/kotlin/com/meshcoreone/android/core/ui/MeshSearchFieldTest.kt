// AndroidOnly: WP-304 The shared search field shows its placeholder, accepts input and keeps its 48dp touch target.
package com.meshcoreone.android.core.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
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
class MeshSearchFieldTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    @Before fun openWindow() { controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible() }
    @After fun closeWindow() { controller.pause().stop().destroy() }

    @Test
    fun `placeholder shows while empty and typing reports the new value`() {
        var text by mutableStateOf("")
        controller.get().setContent {
            MeshCoreTheme(motionScale = 0f) {
                MeshSearchField(text, { text = it }, "Search nodes")
            }
        }
        compose.onNodeWithText("Search nodes").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Search nodes").performTextInput("relay")
        compose.waitForIdle()
        assertEquals("relay", text)
    }
}
