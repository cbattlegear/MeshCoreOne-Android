// AndroidOnly: WP-305 Verify Settings onboarding cannot be unmounted while preset work is in flight.
package com.meshcoreone.android.app.navigation

import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.onboarding.AndroidOnboardingPermissionPort
import com.meshcoreone.android.app.container.onboarding.AppOnboarding
import com.meshcoreone.android.app.container.onboarding.AppOnboardingDemoPort
import com.meshcoreone.android.app.container.onboarding.AppOnboardingPairingPort
import com.meshcoreone.android.app.container.onboarding.AppOnboardingRegionPort
import com.meshcoreone.android.app.container.onboarding.AppOnboardingWiFiPort
import com.meshcoreone.android.app.container.onboarding.FakeConnectionHost
import com.meshcoreone.android.app.container.onboarding.FakePermissionFacts
import com.meshcoreone.android.app.container.onboarding.RequestedPermissions
import com.meshcoreone.android.app.container.onboarding.ServicesRegionCatalog
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.services.simulator.DemoModeDefaults
import com.meshcoreone.android.core.services.simulator.DemoModeManager
import com.meshcoreone.android.feature.onboarding.InMemoryOnboardingFlagStore
import com.meshcoreone.android.feature.onboarding.OnboardingFeatureDependencies
import com.meshcoreone.android.feature.onboarding.OnboardingFlagStore
import com.meshcoreone.android.feature.onboarding.OnboardingFlowCoordinator
import com.meshcoreone.android.feature.onboarding.OnboardingPresetOption
import com.meshcoreone.android.feature.onboarding.OnboardingPresetOutcome
import com.meshcoreone.android.feature.onboarding.OnboardingPresetPort
import com.meshcoreone.android.feature.onboarding.OnboardingStep
import com.meshcoreone.android.feature.onboarding.OnboardingState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w800dp-h900dp")
class SettingsRerunNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: org.robolectric.android.controller.ActivityController<ComponentActivity>

    @Before fun attachHost() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    }

    @After fun closeHost() {
        host.pause().stop().destroy()
    }

    private fun appOnboarding(
        scope: CoroutineScope,
        presets: OnboardingPresetPort,
    ): AppOnboarding {
        val host = FakeConnectionHost()
        val demoFlags = HashMap<String, Boolean>()
        return AppOnboarding(
            flags = InMemoryOnboardingFlagStore(mapOf(OnboardingState.KEY_HAS_COMPLETED to true)),
            permissions = AndroidOnboardingPermissionPort(FakePermissionFacts(), RequestedPermissions(), scanFallback = false),
            pairing = AppOnboardingPairingPort(host, scope, null),
            wifi = AppOnboardingWiFiPort(host),
            demo = AppOnboardingDemoPort(DemoModeManager(object : DemoModeDefaults {
                override fun bool(forKey: String) = demoFlags[forKey] ?: false
                override suspend fun setAll(values: Map<String, Boolean>) { demoFlags.putAll(values) }
            }), scope) { error("Simulator connection was not requested by this test") },
            region = AppOnboardingRegionPort(ServicesRegionCatalog(), MutableStateFlow<RegionSelection?>(null), {}, null),
            presets = presets,
            settingsOpener = {},
            locationReporter = null,
            sdkInt = 34,
            flowScope = scope,
            hasCompleted = MutableStateFlow(true),
        )
    }

    private fun mountShell(navigation: NavigationCoordinator, onboarding: OnboardingFeatureDependencies) {
        host.get().setContent {
            MeshCoreTheme(motionScale = 0f) {
                NativeNavigationShell(navigation, onboarding = onboarding)
            }
        }
    }

    private fun enterSettingsRerun(
        navigation: NavigationCoordinator,
        dependencies: OnboardingFeatureDependencies,
        flow: OnboardingFlowCoordinator,
        option: OnboardingPresetOption,
    ): String {
        flow.onboarding.replacePath(listOf(OnboardingStep.PRESET))
        mountShell(navigation, dependencies)
        compose.onNodeWithTag("open-radio-setup").performClick()
        compose.onNodeWithText(option.name).performClick()
        return O.presetUse(
            ApplicationProvider.getApplicationContext<Context>().resources,
            option.name,
        )
    }

    private fun assertRerunStillMounted(navigation: NavigationCoordinator) {
        assertEquals(AppTab.SETTINGS, navigation.state.value.selectedTab)
        assertEquals(
            NavigationDestination.Auxiliary(com.meshcoreone.android.core.contracts.FeatureId.ONBOARDING),
            navigation.state.value.activeStack.last().destination,
        )
    }

    @Test fun `toolbar Up keeps rerun mounted for held apply failure and successful retry navigation`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val applyStarted = CompletableDeferred<Unit>()
            val releaseApply = CompletableDeferred<Unit>()
            var applies = 0
            val presets = object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(true)
                override suspend fun apply(presetId: String): OnboardingPresetOutcome {
                    applies += 1
                    if (applies == 1) {
                        applyStarted.complete(Unit)
                        releaseApply.await()
                        return OnboardingPresetOutcome.Failed(com.meshcoreone.android.core.ui.UiText.Verbatim("held radio apply failure"))
                    }
                    return OnboardingPresetOutcome.Applied
                }
            }
            val base = appOnboarding(processScope, presets)
            val dependencies = base.forRerun()
            val flow = checkNotNull(dependencies.retainedFlowCoordinator())
            val option = flow.preset.visiblePresets().first()
            val navigation = NavigationCoordinator().also { it.selectTab(AppTab.SETTINGS) }
            val applyLabel = enterSettingsRerun(navigation, dependencies, flow, option)
            compose.onNodeWithText(applyLabel).performClick()
            applyStarted.await()

            compose.onNodeWithTag("navigation-back").performClick()
            assertRerunStillMounted(navigation)

            releaseApply.complete(Unit)
            flow.preset.state.first { it.error != null }
            compose.onNodeWithText("held radio apply failure").assertIsDisplayed()
            compose.onNodeWithText(ApplicationProvider.getApplicationContext<Context>().getString(L.commonOk)).performClick()
            compose.onNodeWithText(applyLabel).performClick()
            compose.waitUntil { navigation.state.value.selectedTab == AppTab.CHATS }
            assertEquals(2, applies)

            compose.onNodeWithTag("tab:MAP").performClick()
            assertEquals(AppTab.MAP, navigation.state.value.selectedTab)
        } finally {
            processScope.cancel()
        }
    }

    @Test fun `tab selection keeps rerun mounted while completion commit is held and retry does not reapply`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val completionStarted = CompletableDeferred<Unit>()
            val releaseCompletion = CompletableDeferred<Unit>()
            var writes = 0
            val flags = object : OnboardingFlagStore {
                private var completed = false
                override fun getBoolean(key: String) = completed
                override fun setBoolean(key: String, value: Boolean) { completed = value }
                override suspend fun persistBoolean(key: String, value: Boolean) {
                    writes += 1
                    if (writes == 1) {
                        completionStarted.complete(Unit)
                        releaseCompletion.await()
                        error("held completion write failure")
                    }
                    completed = value
                }
            }
            var applies = 0
            val presets = object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(true)
                override suspend fun apply(presetId: String): OnboardingPresetOutcome {
                    applies += 1
                    return OnboardingPresetOutcome.Applied
                }
            }
            val base = appOnboarding(processScope, presets)
            val firstRun = checkNotNull(base.retainedFlowCoordinator())
            val rerunBase = base.forRerun()
            val dependencies = TestRerunDependencies(rerunBase, flags, processScope)
            val flow = checkNotNull(dependencies.retainedFlowCoordinator())
            assertNotSame(firstRun, flow)
            val option = flow.preset.visiblePresets().first()
            val navigation = NavigationCoordinator().also { it.selectTab(AppTab.SETTINGS) }
            val applyLabel = enterSettingsRerun(navigation, dependencies, flow, option)
            compose.onNodeWithText(applyLabel).performClick()
            completionStarted.await()
            assertTrue(flow.preset.state.value.isApplying)

            compose.onNodeWithTag("tab:MAP").performClick()
            assertRerunStillMounted(navigation)

            releaseCompletion.complete(Unit)
            flow.preset.state.first { it.completionNeedsRetry }
            val completionFailure = ApplicationProvider.getApplicationContext<Context>().getString(S.alertRetryUnableToSave)
            compose.onAllNodesWithText(completionFailure).assertCountEquals(1)
            compose.onNodeWithText(completionFailure).assertIsDisplayed()
            val retryLabel = ApplicationProvider.getApplicationContext<Context>().getString(L.commonTryAgain)
            compose.onNodeWithText(retryLabel).assertIsDisplayed().performClick()
            compose.waitUntil { navigation.state.value.selectedTab == AppTab.CHATS }
            assertEquals(1, applies)
            assertEquals(2, writes)
            compose.onNodeWithTag("tab:MAP").performClick()
            assertEquals(AppTab.MAP, navigation.state.value.selectedTab)
        } finally {
            processScope.cancel()
        }
    }

    @Test fun `mounted onboarding Back keeps rerun and failure visible while preset work is held`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val applyStarted = CompletableDeferred<Unit>()
            val releaseApply = CompletableDeferred<Unit>()
            val presets = object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(true)
                override suspend fun apply(presetId: String): OnboardingPresetOutcome {
                    applyStarted.complete(Unit)
                    releaseApply.await()
                    return OnboardingPresetOutcome.Failed(com.meshcoreone.android.core.ui.UiText.Verbatim("held back apply failure"))
                }
            }
            val dependencies = appOnboarding(processScope, presets).forRerun()
            val flow = checkNotNull(dependencies.retainedFlowCoordinator())
            val option = flow.preset.visiblePresets().first()
            val navigation = NavigationCoordinator().also { it.selectTab(AppTab.SETTINGS) }
            val applyLabel = enterSettingsRerun(navigation, dependencies, flow, option)
            compose.onNodeWithText(applyLabel).performClick()
            applyStarted.await()

            compose.runOnUiThread { host.get().onBackPressedDispatcher.onBackPressed() }
            assertRerunStillMounted(navigation)
            assertEquals(OnboardingStep.PRESET, flow.currentStep)

            releaseApply.complete(Unit)
            flow.preset.state.first { it.error != null }
            compose.onNodeWithText("held back apply failure").assertIsDisplayed()
        } finally {
            processScope.cancel()
        }
    }

    private class TestRerunDependencies(
        private val delegate: OnboardingFeatureDependencies,
        override val flags: OnboardingFlagStore,
        scope: CoroutineScope,
    ) : OnboardingFeatureDependencies by delegate {
        private val flow = OnboardingFlowCoordinator(scope, this).also { it.restoreStartingPath() }
        override fun retainedFlowCoordinator(): OnboardingFlowCoordinator = flow
    }
}
