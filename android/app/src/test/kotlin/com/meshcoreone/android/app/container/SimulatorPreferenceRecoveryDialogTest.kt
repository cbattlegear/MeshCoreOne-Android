// AndroidOnly: WP-217 Verify localized simulator choice and saved UI state.
package com.meshcoreone.android.app.container

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.R
import com.meshcoreone.android.app.platform.AppLanguages
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.data.repository.SimulatorDevicePreferenceConflict
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.services.simulator.MockDataProvider
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS-w320dp-h640dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SimulatorPreferenceRecoveryDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private var animationScale = 1f

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.windowFocusChanged(true)
        animationScale = Settings.Global.getFloat(
            host.get().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        )
        Settings.Global.putFloat(host.get().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After fun closeHost() {
        Settings.Global.putFloat(host.get().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, animationScale)
        host.windowFocusChanged(false)
        host.pause().stop().destroy()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun localizedChoicesRemainDistinctAndSurviveRestorationAtDoubleFontScale() = runTest {
        val current = MockDataProvider.simulatorDevice(Instant.EPOCH).copy(
            latitude = 37.12345671, longitude = -122.12345671, defaultFloodScopeName = null,
        )
        val restored = current.copy(
            id = UUID.randomUUID(), isActive = false,
            latitude = 37.12345672, longitude = -122.12345672, defaultFloodScopeName = "",
        )
        val conflict = SimulatorDevicePreferenceConflict(current, restored)
        val recovery = SimulatorPreferenceRecovery(load = { conflict }, resolve = { _, _, _ -> })
        val attempt = async(start = CoroutineStart.UNDISPATCHED) {
            recovery.seed(current) { throw PersistenceStoreException(PersistenceStoreError.InvalidData) }
        }
        val language = mutableStateOf("en")
        val restoration = StateRestorationTester(object : ComposeContentTestRule, ComposeTestRule by compose {
            override fun setContent(composable: @Composable () -> Unit) {
                host.get().setContent(content = composable)
            }
        })
        try {
            restoration.setContent {
                val tag = language.value
                val configuration = remember(tag) {
                    Configuration(host.get().resources.configuration).apply {
                        setLocales(LocaleList.forLanguageTags(tag))
                    }
                }
                val localized = remember(configuration) { host.get().createConfigurationContext(configuration) }
                CompositionLocalProvider(
                    LocalConfiguration provides configuration,
                    LocalContext provides localized,
                    LocalResources provides localized.resources,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
                ) {
                    MeshCoreTheme(motionScale = 0f) { SimulatorPreferenceRecoveryDialog(recovery) }
                }
            }
            compose.onNodeWithTag("simulator-preference-recovered").performScrollTo().performClick()
            compose.onNodeWithTag("simulator-preference-apply").assertIsEnabled().assertHeightIsAtLeast(48.dp)
            restoration.emulateSavedInstanceStateRestore()
            compose.waitForIdle()
            compose.onNodeWithTag("simulator-preference-recovered").performScrollTo()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
                .assertHeightIsAtLeast(48.dp)

            for (tag in AppLanguages.supported) {
                compose.runOnIdle { language.value = tag }
                compose.waitForIdle()
                val resources = host.get().createConfigurationContext(
                    Configuration(host.get().resources.configuration).apply {
                        setLocales(LocaleList.forLanguageTags(tag))
                    },
                ).resources
                assertTrue(resources.getString(R.string.simulator_preference_recovery_device_details).isNotBlank())
                compose.onNodeWithTag("simulator-preference-apply").assertIsDisplayed().assertIsEnabled()
                compose.onNodeWithTag("simulator-preference-cancel").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                fun displayed(tag: String): String = compose.onNodeWithTag(tag).performScrollTo()
                    .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
                assertNotEquals(
                    displayed("simulator-preference-current-latitude"),
                    displayed("simulator-preference-recovered-latitude"),
                    "$tag must distinguish the saved coordinates",
                )
                assertNotEquals(
                    displayed("simulator-preference-current-flood-scope"),
                    displayed("simulator-preference-recovered-flood-scope"),
                    "$tag must distinguish null from empty",
                )
            }
            compose.onNodeWithTag("simulator-preference-cancel").performClick()
            runCurrent()
            assertTrue(attempt.isCancelled)
            assertEquals(null, recovery.prompt.value)
        } finally {
            recovery.close()
            attempt.cancel()
        }
    }
}
