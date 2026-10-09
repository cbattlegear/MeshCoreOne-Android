// AndroidOnly: WP-002 Real Compose launcher/navigation/incomplete-state assertions on simulated SDK 31.
package com.meshcoreone.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.designsystem.ScaffoldTheme
import com.meshcoreone.android.core.l10n.R as Strings
import com.meshcoreone.android.core.ui.ScaffoldAvailabilityKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h800dp")
class ScaffoldLauncherTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun unavailable(id: String) {
        compose.onNodeWithTag("feature:$id")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(ScaffoldAvailabilityKey, "NOT_YET_PORTED"))
        compose.onNodeWithTag("unavailable-action").performScrollTo().assertIsNotEnabled()
    }

    private fun available(id: String) {
        compose.onNodeWithTag("feature:$id")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(ScaffoldAvailabilityKey, "AVAILABLE"))
    }

    @Test
    fun launcherAndFiveTabsReportFeatureAvailability() {
        unavailable("chats.root")
        compose.onNodeWithTag("bottom-navigation").assertIsDisplayed()
        AppTab.entries.forEach { tab ->
            compose.onNodeWithTag("tab:${tab.name}").performClick().assertIsSelected()
            if (tab == AppTab.TOOLS) available("tools.root") else unavailable("${tab.name.lowercase()}.root")
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        unavailable("chats.root")
    }

    @Test
    fun remoteNodesIsAnAuxiliaryEntryAndBackRestoresNodes() {
        compose.onNodeWithTag("tab:NODES").performClick()
        compose.onNodeWithText(compose.activity.getString(Strings.string.scaffold_remote_nodes_title))
            .performScrollTo().performClick()
        unavailable("remotenodes.root")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        unavailable("nodes.root")
        compose.onNodeWithTag("tab:NODES").assertIsSelected()
    }

    @Test
    fun setupIsUnavailableAndNeverCompletesPairing() {
        compose.onNodeWithTag("open-radio-setup").performClick()
        unavailable("onboarding.root")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        unavailable("chats.root")
    }

    @Test
    fun activityRecreationRetainsOnlyShellNavigationNotRadioReadiness() {
        compose.onNodeWithTag("tab:SETTINGS").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tab:SETTINGS").assertIsSelected()
        unavailable("settings.root")
    }

    @Test
    @Config(qualifiers = "w840dp-h800dp-night")
    fun twoHundredPercentFontAndResizeKeepSelectionAndAccessibleControls() {
        val width = mutableStateOf(360.dp)
        compose.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                    ScaffoldTheme {
                        Box(Modifier.width(width.value)) {
                            ScaffoldApp()
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("bottom-navigation").assertIsDisplayed()
        compose.onNodeWithTag("tab:NODES").performClick().assertIsSelected()
        AppTab.entries.forEach { tab ->
            compose.onNodeWithTag("tab:${tab.name}").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        unavailable("nodes.root")
        compose.runOnIdle { width.value = 840.dp }
        compose.onNodeWithTag("navigation-rail").assertIsDisplayed()
        compose.onNodeWithTag("tab:NODES").assertIsSelected()
        unavailable("nodes.root")
    }
}
