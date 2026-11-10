// AndroidOnly: WP-305 Verify Settings onboarding cannot be unmounted while preset work is in flight.
// AndroidOnly: WP-302 Verify mounted navigation preserves entry and session authority.
package com.meshcoreone.android.app.navigation

import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.services.simulator.DemoModeDefaults
import com.meshcoreone.android.core.services.simulator.DemoModeManager
import com.meshcoreone.android.feature.onboarding.InMemoryOnboardingFlagStore
import com.meshcoreone.android.feature.onboarding.OnboardingFeatureDependencies
import com.meshcoreone.android.feature.onboarding.OnboardingFlagStore
import com.meshcoreone.android.feature.onboarding.OnboardingFlowCoordinator
import com.meshcoreone.android.feature.onboarding.OnboardingPresetOption
import com.meshcoreone.android.feature.onboarding.OnboardingPresetOutcome
import com.meshcoreone.android.feature.onboarding.OnboardingPresetPort
import com.meshcoreone.android.feature.onboarding.OnboardingPresetTarget
import com.meshcoreone.android.feature.onboarding.OnboardingStep
import com.meshcoreone.android.feature.onboarding.OnboardingState
import com.meshcoreone.android.feature.onboarding.ui.OnboardingFlow
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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
    private val presetTarget = OnboardingPresetTarget(
        java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
        1,
        java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"),
    )

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

    private fun mountShell(
        navigation: NavigationCoordinator,
        onboarding: OnboardingFeatureDependencies,
        sessionCapture: (NavigationState) -> (MountedOnboardingProducer) -> Boolean = { { true } },
    ) {
        navigation.installOnboardingSessionCapture(sessionCapture)
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

    private fun assertRerunStillMounted(
        navigation: NavigationCoordinator,
        tab: AppTab = AppTab.SETTINGS,
    ) {
        assertEquals(tab, navigation.state.value.selectedTab)
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
                override fun currentTarget() = presetTarget
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

            assertRerunStillMounted(navigation)
            compose.onNodeWithTag("navigation-back").assertIsNotEnabled()

            releaseApply.complete(Unit)
            flow.preset.state.first { it.error != null }
            compose.onNodeWithText("held radio apply failure").assertIsDisplayed()
            compose.onNodeWithTag("navigation-back").assertIsEnabled()
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
                override fun currentTarget() = presetTarget
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

            assertRerunStillMounted(navigation)
            compose.onNodeWithTag("tab:MAP").assertIsNotEnabled()

            releaseCompletion.complete(Unit)
            flow.preset.state.first { it.completionNeedsRetry }
            val completionFailure = ApplicationProvider.getApplicationContext<Context>().getString(S.alertRetryUnableToSave)
            compose.onAllNodesWithText(completionFailure).assertCountEquals(1)
            compose.onNodeWithText(completionFailure).assertIsDisplayed()
            compose.onNodeWithTag("tab:MAP").assertIsEnabled()
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
            compose.onNodeWithTag("open-radio-setup").assertIsEnabled()
            compose.onNodeWithText(applyLabel).performClick()
            applyStarted.await()

            compose.runOnUiThread { host.get().onBackPressedDispatcher.onBackPressed() }
            assertRerunStillMounted(navigation)
            assertEquals(OnboardingStep.PRESET, flow.currentStep)

            releaseApply.complete(Unit)
            flow.preset.state.first { it.error != null }
            compose.onNodeWithText("held back apply failure").assertIsDisplayed()
            Unit
        } finally {
            processScope.cancel()
        }
    }

    @Test fun `Nodes toolbar actions cannot cover a busy rerun and work after it settles`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val applyStarted = CompletableDeferred<Unit>()
            val releaseApply = CompletableDeferred<Unit>()
            val presets = object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(true)
                override suspend fun apply(presetId: String): OnboardingPresetOutcome {
                    applyStarted.complete(Unit)
                    releaseApply.await()
                    return OnboardingPresetOutcome.Failed(
                        com.meshcoreone.android.core.ui.UiText.Verbatim("held nodes apply failure"),
                    )
                }
            }
            val dependencies = appOnboarding(processScope, presets).forRerun()
            val flow = checkNotNull(dependencies.retainedFlowCoordinator())
            val option = flow.preset.visiblePresets().first()
            val navigation = NavigationCoordinator().also { it.selectTab(AppTab.NODES) }
            flow.onboarding.replacePath(listOf(OnboardingStep.PRESET))
            mountShell(navigation, dependencies)
            compose.onNodeWithTag("open-radio-setup").performClick()
            compose.onNodeWithText(option.name).performClick()
            compose.onNodeWithText(
                O.presetUse(
                    ApplicationProvider.getApplicationContext<Context>().resources,
                    option.name,
                ),
            ).performClick()
            applyStarted.await()

            val stackSize = navigation.state.value.activeStack.size
            compose.onNodeWithTag("open-remote-nodes").assertIsNotEnabled()
            compose.onNodeWithTag("open-radio-setup").assertIsNotEnabled()
            assertRerunStillMounted(navigation, AppTab.NODES)
            assertEquals(stackSize, navigation.state.value.activeStack.size)

            releaseApply.complete(Unit)
            flow.preset.state.first { it.error != null }
            compose.onNodeWithText("held nodes apply failure").assertIsDisplayed()
            compose.onNodeWithTag("open-remote-nodes").assertIsEnabled()
            compose.onNodeWithTag("open-radio-setup").assertIsEnabled()
            compose.onNodeWithText(ApplicationProvider.getApplicationContext<Context>().getString(L.commonOk))
                .performClick()

            compose.onNodeWithTag("open-remote-nodes").performClick()
            assertEquals(
                NavigationDestination.Auxiliary(com.meshcoreone.android.core.contracts.FeatureId.REMOTE_NODES),
                navigation.state.value.activeStack.last().destination,
            )
            compose.onNodeWithTag("open-radio-setup").assertIsEnabled().performClick()
            assertEquals(
                NavigationDestination.Auxiliary(com.meshcoreone.android.core.contracts.FeatureId.ONBOARDING),
                navigation.state.value.activeStack.last().destination,
            )
            Unit
        } finally {
            processScope.cancel()
        }
    }

    @Test fun `successful reruns retire flow collection while process scope remains alive`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val processJob = checkNotNull(processScope.coroutineContext[Job])
            val base = appOnboarding(
                processScope,
                object : OnboardingPresetPort {
                    override val canApply = MutableStateFlow(true)
                    override fun currentTarget() = presetTarget
                    override suspend fun apply(presetId: String) = OnboardingPresetOutcome.Applied
                },
            )
            val baselineJobs = processJob.children.toSet()
            val dependencies = base.forRerun()
            val navigation = NavigationCoordinator().also { it.selectTab(AppTab.SETTINGS) }
            var previousFlow: OnboardingFlowCoordinator? = null

            repeat(2) {
                val flow = checkNotNull(dependencies.retainedFlowCoordinator())
                previousFlow?.let { assertNotSame(it, flow) }
                previousFlow = flow
                val option = flow.preset.visiblePresets().first()
                val applyLabel = enterSettingsRerun(navigation, dependencies, flow, option)
                compose.onNodeWithText(applyLabel).performClick()
                compose.waitUntil { navigation.state.value.selectedTab == AppTab.CHATS }
                assertTrue(processJob.isActive)
                compose.waitUntil { processJob.children.toSet() == baselineJobs }
                assertTrue(baselineJobs.all { it.isActive })
                navigation.selectTab(AppTab.SETTINGS)
            }
        } finally {
            processScope.cancel()
        }
    }

    @Test fun `rapid rerun replacement rebinds busy guards to the current flow`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val applyStarted = CompletableDeferred<Unit>()
            val releaseApply = CompletableDeferred<Unit>()
            var applies = 0
            val presets = object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(true)
                override fun currentTarget() = presetTarget
                override suspend fun apply(presetId: String): OnboardingPresetOutcome {
                    applies += 1
                    applyStarted.complete(Unit)
                    releaseApply.await()
                    return OnboardingPresetOutcome.Applied
                }
            }
            val dependencies = appOnboarding(processScope, presets).forRerun()
            val flowA = checkNotNull(dependencies.retainedFlowCoordinator())
            flowA.onboarding.replacePath(listOf(OnboardingStep.PRESET))
            val navigation = NavigationCoordinator().also { it.selectTab(AppTab.SETTINGS) }
            mountShell(navigation, dependencies)
            compose.onNodeWithTag("open-radio-setup").performClick()
            compose.waitForIdle()
            val firstEntryId = navigation.state.value.activeStack.last().id
            assertEquals(
                NavigationDestination.Auxiliary(FeatureId.ONBOARDING),
                navigation.state.value.activeStack.last().destination,
            )

            compose.runOnUiThread {
                dependencies.onFlowCompletionHandled(flowA)
                check(navigation.back())
                navigation.navigate(FeatureRoute(FeatureId.ONBOARDING))
            }
            val secondEntry = navigation.state.value.activeStack.last()
            assertEquals(NavigationDestination.Auxiliary(FeatureId.ONBOARDING), secondEntry.destination)
            assertFalse(firstEntryId == secondEntry.id)
            val flowB = checkNotNull(dependencies.retainedFlowCoordinator())
            assertNotSame(flowA, flowB)
            assertFalse(dependencies.ownsFlowCoordinator(flowA))
            flowB.onboarding.replacePath(listOf(OnboardingStep.PRESET))
            val option = flowB.preset.visiblePresets().first()
            compose.waitForIdle()

            compose.onNodeWithText(option.name).performClick()
            val applyLabel = O.presetUse(
                ApplicationProvider.getApplicationContext<Context>().resources,
                option.name,
            )
            compose.onNodeWithText(applyLabel).performClick()
            applyStarted.await()

            compose.onNodeWithTag("tab:MAP").assertIsNotEnabled()
            compose.onNodeWithTag("navigation-back").assertIsNotEnabled()
            compose.onNodeWithTag("open-radio-setup").assertIsNotEnabled()
            compose.runOnUiThread { host.get().onBackPressedDispatcher.onBackPressed() }
            assertEquals(secondEntry.id, navigation.state.value.activeStack.last().id)
            assertEquals(AppTab.SETTINGS, navigation.state.value.selectedTab)
            assertTrue(dependencies.ownsFlowCoordinator(flowB))

            releaseApply.complete(Unit)
            compose.waitUntil { navigation.state.value.selectedTab == AppTab.CHATS }
            assertEquals(1, applies)
            compose.onNodeWithTag("tab:MAP").assertIsEnabled().performClick()
            assertEquals(AppTab.MAP, navigation.state.value.selectedTab)
        } finally {
            processScope.cancel()
        }
    }

    @Test fun `stale completed flow cannot deliver completion after rerun replacement`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val dependencies = appOnboarding(
                processScope,
                object : OnboardingPresetPort {
                    override val canApply = MutableStateFlow(true)
                    override fun currentTarget() = presetTarget
                    override suspend fun apply(presetId: String) = OnboardingPresetOutcome.Applied
                },
            ).forRerun()
            val flowA = checkNotNull(dependencies.retainedFlowCoordinator())
            var completionCallbacks = 0
            host.get().setContent {
                MeshCoreTheme(motionScale = 0f) {
                    OnboardingFlow(dependencies, onCompleted = { completionCallbacks += 1 })
                }
            }
            compose.waitForIdle()

            var flowB: OnboardingFlowCoordinator? = null
            compose.runOnUiThread {
                runBlocking { flowA.onboarding.completeOnboarding() }
                dependencies.onFlowCompletionHandled(flowA)
                flowB = checkNotNull(dependencies.retainedFlowCoordinator())
            }
            compose.waitForIdle()

            assertNotSame(flowA, flowB)
            assertFalse(dependencies.ownsFlowCoordinator(flowA))
            assertEquals(0, completionCallbacks)
            assertTrue(dependencies.ownsFlowCoordinator(checkNotNull(flowB)))
        } finally {
            processScope.cancel()
        }
    }

    @Test fun `completed rerun blocks pre-frame exit until host retires it then setup opens fresh`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val dependencies = appOnboarding(
                processScope,
                object : OnboardingPresetPort {
                    override val canApply = MutableStateFlow(true)
                    override fun currentTarget() = presetTarget
                    override suspend fun apply(presetId: String) = OnboardingPresetOutcome.Applied
                },
            ).forRerun()
            val flowA = checkNotNull(dependencies.retainedFlowCoordinator())
            flowA.onboarding.replacePath(listOf(OnboardingStep.PAIR))
            val navigation = NavigationCoordinator().also { it.selectTab(AppTab.SETTINGS) }
            mountShell(navigation, dependencies)
            compose.onNodeWithTag("open-radio-setup").performClick()
            compose.waitForIdle()
            val firstEntryId = navigation.state.value.activeStack.last().id
            val up = checkNotNull(
                compose.onNodeWithTag("navigation-back").assertIsEnabled()
                    .fetchSemanticsNode().config[SemanticsActions.OnClick].action,
            )
            val openSetup = checkNotNull(
                compose.onNodeWithTag("open-radio-setup").assertIsEnabled()
                    .fetchSemanticsNode().config[SemanticsActions.OnClick].action,
            )
            val selectMap = checkNotNull(
                compose.onNodeWithTag("tab:MAP").assertIsEnabled()
                    .fetchSemanticsNode().config[SemanticsActions.OnClick].action,
            )

            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                flowA.confirmNoDevice()
                assertTrue(flowA.onboarding.hasCompletedOnboarding)
                assertFalse(flowA.isCompletingWithoutDevice.value)
                assertTrue(dependencies.ownsFlowCoordinator(flowA))
                assertSame(flowA, dependencies.retainedFlowCoordinator())

                // Invoke the mounted callbacks without a frame/effect or explicit completion handling.
                assertTrue(up())
                assertTrue(openSetup())
                assertTrue(selectMap())
                host.get().onBackPressedDispatcher.onBackPressed()
                assertRerunStillMounted(navigation)
                assertEquals(firstEntryId, navigation.state.value.activeStack.last().id)
                assertEquals(OnboardingStep.PAIR, flowA.currentStep)
                assertSame(flowA, dependencies.retainedFlowCoordinator())
                assertFalse(flowA.canLeaveCurrentFlow())
            }
            compose.mainClock.autoAdvance = true
            compose.waitUntil { navigation.state.value.selectedTab == AppTab.CHATS }
            assertFalse(dependencies.ownsFlowCoordinator(flowA))

            compose.onNodeWithTag("tab:SETTINGS").assertIsEnabled().performClick()
            compose.onNodeWithTag("open-radio-setup").assertIsEnabled().performClick()
            val secondEntryId = navigation.state.value.activeStack.last().id
            val flowB = checkNotNull(dependencies.retainedFlowCoordinator())
            assertFalse(firstEntryId == secondEntryId)
            assertNotSame(flowA, flowB)
            assertFalse(flowB.onboarding.hasCompletedOnboarding)
            assertTrue(flowB.canLeaveCurrentFlow())
            compose.onNodeWithText(
                ApplicationProvider.getApplicationContext<Context>().getString(O.welcomeGetStarted),
            ).assertIsDisplayed().performClick()
            assertRerunStillMounted(navigation)
            assertEquals(secondEntryId, navigation.state.value.activeStack.last().id)
            assertEquals(OnboardingStep.PERMISSIONS, flowB.currentStep)
            compose.onNodeWithTag("navigation-back").assertIsEnabled().performClick()
            assertEquals(AppTab.SETTINGS, navigation.state.value.selectedTab)
            assertEquals(NavigationDestination.Root(AppTab.SETTINGS), navigation.state.value.activeStack.last().destination)
        } finally {
            compose.mainClock.autoAdvance = true
            processScope.cancel()
        }
    }

    @Test fun `off-tab onboarding completion cannot pop the selected tab entry`() {
        val navigation = NavigationCoordinator().also {
            it.installOnboardingSessionCapture { { true } }
            it.navigateToDiscovery()
            it.selectTab(AppTab.SETTINGS)
            it.navigate(FeatureRoute(FeatureId.ONBOARDING))
        }
        val presented = navigation.publishedNavigation.value
        val completion = navigation.captureMountedOnboarding(presented, presented.value.activeStack.last().id)
        navigation.selectTab(AppTab.NODES)
        val selectedStack = navigation.state.value.activeStack

        assertFalse(navigation.completeMountedOnboarding(completion, MountedOnboardingProducer.NoDevice))

        assertEquals(AppTab.NODES, navigation.state.value.selectedTab)
        assertEquals(selectedStack, navigation.state.value.activeStack)
    }

    @Test fun `returning to an idle onboarding entry preserves its flow and accepts a fresh completion`() {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val dependencies = appOnboarding(processScope, object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(false)
                override suspend fun apply(presetId: String): OnboardingPresetOutcome =
                    error("This flow must not apply a preset")
            })
            val navigation = NavigationCoordinator().also { it.selectTab(AppTab.SETTINGS) }
            mountShell(navigation, dependencies)
            compose.onNodeWithTag("open-radio-setup").performClick()
            val retention = navigation.onboardingRetention(dependencies)
            compose.waitUntil(5_000) { retention.mountedEntries.value.size == 1 }
            val entry = retention.mountedEntries.value.values.single()
            val originalWitness = entry.witness.get()
            compose.runOnUiThread { entry.flow.onboarding.replacePath(listOf(OnboardingStep.PERMISSIONS)) }

            compose.onNodeWithTag("tab:NODES").assertIsEnabled().performClick()
            compose.onNodeWithTag("tab:SETTINGS").assertIsEnabled().performClick()
            compose.waitUntil(5_000) { navigation.state.value.activeStack.last().id == entry.identity.entryId }
            compose.waitUntil(5_000) { entry.witness.get() !== originalWitness }
            compose.waitForIdle()
            assertSame(entry, retention.mountedEntries.value[entry.identity])
            assertEquals(OnboardingStep.PERMISSIONS, entry.flow.currentStep)
            assertFalse(navigation.completeMountedOnboarding(originalWitness, MountedOnboardingProducer.NoDevice))

            compose.runOnUiThread { entry.flow.confirmNoDevice() }
            compose.waitUntil(5_000) { navigation.state.value.selectedTab == AppTab.CHATS }
            compose.waitUntil(5_000) { !entry.owner.ownsFlowCoordinator(entry.flow) }
        } finally {
            processScope.cancel()
        }
    }

    @Test fun `reactivated presentation keeps original authority and retirement`() {
        for (retireOwner in listOf(false, true)) {
            val navigation = NavigationCoordinator().also {
                it.installOnboardingSessionCapture { { true } }
                it.selectTab(AppTab.SETTINGS)
                it.navigate(FeatureRoute(FeatureId.ONBOARDING))
            }
            val original = navigation.publishedNavigation.value
            val entryId = original.value.activeStack.last().id
            val first = checkNotNull(navigation.captureMountedOnboarding(original, entryId))
            navigation.selectTab(AppTab.NODES)
            navigation.selectTab(AppTab.SETTINGS)
            val reactivated = navigation.publishedNavigation.value
            val next = checkNotNull(navigation.prepareMountedOnboardingPresentation(first, reactivated))
            assertSame(original, next.initiatingPresentation)
            assertFalse(navigation.completeMountedOnboarding(first, MountedOnboardingProducer.NoDevice))
            if (retireOwner) navigation.retireMountedOnboarding(first)
            else navigation.retireMountedOnboardingPresentation(first)
            assertEquals(
                !retireOwner,
                navigation.completeMountedOnboarding(next, MountedOnboardingProducer.NoDevice),
            )
        }
    }

    @Test fun `reactivated entry accepts its authorized first session binding`() {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val token = SessionToken(ProcessEpoch(UUID(0, 81)), Generation(1), RadioId(UUID(0, 82)))
            val target = OnboardingPresetTarget(token.epoch.value, token.generation.value, token.radioId.value)
            val current = AtomicReference<OnboardingPresetTarget?>(null)
            val presets = object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(false)
                override fun currentTarget(): OnboardingPresetTarget? = current.get()
                override suspend fun apply(presetId: String): OnboardingPresetOutcome =
                    error("This flow must not apply a preset")
            }
            val dependencies = appOnboarding(processScope, presets)
            val navigation = NavigationCoordinator().also { it.selectTab(AppTab.SETTINGS) }
            val capture: (NavigationState) -> (MountedOnboardingProducer) -> Boolean = { _ ->
                val origin = current.get()
                val valid: (MountedOnboardingProducer) -> Boolean = { producer ->
                    origin == null && current.get() == target &&
                        producer == MountedOnboardingProducer.NoDeviceAtSession(token)
                }
                valid
            }
            mountShell(navigation, dependencies, capture)
            compose.onNodeWithTag("open-radio-setup").performClick()
            val retention = navigation.onboardingRetention(dependencies)
            compose.waitUntil(5_000) { retention.mountedEntries.value.size == 1 }
            val entry = retention.mountedEntries.value.values.single()
            val original = entry.witness.get()

            compose.onNodeWithTag("tab:NODES").assertIsEnabled().performClick()
            current.set(target)
            assertTrue(navigation.bindNotificationSession(token.radioId, null, token))
            compose.onNodeWithTag("tab:SETTINGS").assertIsEnabled().performClick()
            compose.waitUntil(5_000) { entry.witness.get() !== original }
            assertSame(original.initiatingPresentation, entry.witness.get().initiatingPresentation)
            compose.runOnUiThread { entry.flow.confirmNoDevice() }
            compose.waitUntil(5_000) { navigation.state.value.selectedTab == AppTab.CHATS }
            assertEquals(token, navigation.currentState.boundSessionToken)
        } finally {
            processScope.cancel()
        }
    }

    @Test fun `reactivated entry rejects a replacement graph before navigation binding`() {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val dependencies = appOnboarding(processScope, object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(false)
                override suspend fun apply(presetId: String): OnboardingPresetOutcome =
                    error("This flow must not apply a preset")
            })
            val navigation = NavigationCoordinator().also { it.selectTab(AppTab.SETTINGS) }
            val currentGraph = AtomicReference(Any())
            val capture: (NavigationState) -> (MountedOnboardingProducer) -> Boolean = { _ ->
                val origin = currentGraph.get()
                val valid: (MountedOnboardingProducer) -> Boolean = { currentGraph.get() === origin }
                valid
            }
            mountShell(navigation, dependencies, capture)
            compose.onNodeWithTag("open-radio-setup").performClick()
            val retention = navigation.onboardingRetention(dependencies)
            compose.waitUntil(5_000) { retention.mountedEntries.value.size == 1 }
            val entry = retention.mountedEntries.value.values.single()
            val original = entry.witness.get()

            compose.onNodeWithTag("tab:NODES").assertIsEnabled().performClick()
            currentGraph.set(Any())
            compose.onNodeWithTag("tab:SETTINGS").assertIsEnabled().performClick()
            compose.waitUntil(5_000) { entry.witness.get() !== original }
            assertFalse(navigation.completeMountedOnboarding(entry.witness.get(), MountedOnboardingProducer.NoDevice))
            compose.runOnUiThread { entry.flow.confirmNoDevice() }
            compose.waitUntil(5_000) { entry.recovery.value.failed }
            compose.onNodeWithTag("onboarding-completion-recovery").assertIsDisplayed()
            assertEquals(AppTab.SETTINGS, navigation.currentState.selectedTab)
            assertEquals(entry.identity.entryId, navigation.currentState.activeStack.last().id)
        } finally {
            processScope.cancel()
        }
    }

    @Test fun `reactivated entry rejects a replacement session after binding`() {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val radioId = RadioId(UUID(0, 83))
            val epoch = ProcessEpoch(UUID(0, 84))
            val originalToken = SessionToken(epoch, Generation(1), radioId)
            val replacementToken = SessionToken(epoch, Generation(2), radioId)
            val originalTarget = OnboardingPresetTarget(epoch.value, 1, radioId.value)
            val replacementTarget = OnboardingPresetTarget(epoch.value, 2, radioId.value)
            val current = AtomicReference(originalTarget)
            val presets = object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(false)
                override fun currentTarget() = current.get()
                override suspend fun apply(presetId: String): OnboardingPresetOutcome =
                    error("This flow must not apply a preset")
            }
            val dependencies = appOnboarding(processScope, presets)
            val navigation = NavigationCoordinator().also {
                it.selectTab(AppTab.SETTINGS)
                assertTrue(it.bindNotificationSession(radioId, null, originalToken))
            }
            val capture: (NavigationState) -> (MountedOnboardingProducer) -> Boolean = { _ ->
                val origin = current.get()
                val valid: (MountedOnboardingProducer) -> Boolean = { current.get() == origin }
                valid
            }
            mountShell(navigation, dependencies, capture)
            compose.onNodeWithTag("open-radio-setup").performClick()
            val retention = navigation.onboardingRetention(dependencies)
            compose.waitUntil(5_000) { retention.mountedEntries.value.size == 1 }
            val entry = retention.mountedEntries.value.values.single()
            val original = entry.witness.get()

            compose.onNodeWithTag("tab:NODES").assertIsEnabled().performClick()
            current.set(replacementTarget)
            assertTrue(navigation.bindNotificationSession(radioId, null, replacementToken))
            compose.onNodeWithTag("tab:SETTINGS").assertIsEnabled().performClick()
            compose.waitUntil(5_000) { entry.witness.get() !== original }
            assertSame(original.initiatingPresentation, entry.witness.get().initiatingPresentation)
            compose.runOnUiThread { entry.flow.confirmNoDevice() }
            compose.waitUntil(5_000) { entry.recovery.value.failed }
            compose.onNodeWithTag("onboarding-completion-recovery").assertIsDisplayed()
            assertEquals(AppTab.SETTINGS, navigation.currentState.selectedTab)
            assertEquals(entry.identity.entryId, navigation.currentState.activeStack.last().id)
            assertEquals(replacementToken, navigation.currentState.boundSessionToken)
        } finally {
            processScope.cancel()
        }
    }

    @Test fun `mounted restart rejects replacement during reset and accepts a new explicit attempt`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val completionStarted = CompletableDeferred<Unit>()
        val releaseCompletion = CompletableDeferred<Unit>()
        val resetStarted = CompletableDeferred<Unit>()
        val releaseReset = CompletableDeferred<Unit>()
        try {
            val completed = AtomicBoolean()
            val writes = AtomicInteger()
            val resets = AtomicInteger()
            val flags = object : OnboardingFlagStore {
                override fun getBoolean(key: String) = completed.get()
                override fun setBoolean(key: String, value: Boolean) { completed.set(value) }
                override suspend fun persistBoolean(key: String, value: Boolean) {
                    if (value && writes.incrementAndGet() == 1) {
                        completionStarted.complete(Unit)
                        releaseCompletion.await()
                    }
                    if (!value && resets.incrementAndGet() == 1) {
                        resetStarted.complete(Unit)
                        releaseReset.await()
                    }
                    completed.set(value)
                }
            }
            val radioId = RadioId(UUID(0, 131))
            val epoch = ProcessEpoch(UUID(0, 132))
            val first = SessionToken(epoch, Generation(1), radioId)
            val second = first.copy(generation = Generation(2))
            val third = first.copy(generation = Generation(3))
            fun target(token: SessionToken) =
                OnboardingPresetTarget(token.epoch.value, token.generation.value, token.radioId.value)
            val current = AtomicReference(target(first))
            val presets = object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(false)
                override fun currentTarget() = current.get()
                override suspend fun apply(presetId: String): OnboardingPresetOutcome =
                    error("This flow must not apply a preset")
            }
            val dependencies = TestRerunDependencies(appOnboarding(processScope, presets).forRerun(), flags, processScope)
            val navigation = NavigationCoordinator().also {
                it.selectTab(AppTab.SETTINGS)
                assertTrue(it.bindNotificationSession(radioId, null, first))
            }
            val capture: (NavigationState) -> (MountedOnboardingProducer) -> Boolean = { _ ->
                val origin = current.get()
                val valid: (MountedOnboardingProducer) -> Boolean = { producer ->
                    current.get() == origin && when (producer) {
                        MountedOnboardingProducer.NoDevice -> true
                        is MountedOnboardingProducer.NoDeviceAtSession -> target(producer.token) == origin
                        is MountedOnboardingProducer.Preset -> target(producer.token) == origin
                    }
                }
                valid
            }
            mountShell(navigation, dependencies, capture)
            compose.onNodeWithTag("open-radio-setup").performClick()
            val retention = navigation.onboardingRetention(dependencies)
            compose.waitUntil(5_000) { retention.mountedEntries.value.size == 1 }
            val entry = retention.mountedEntries.value.values.single()
            val originalWitness = entry.witness.get()

            compose.runOnUiThread { entry.flow.confirmNoDevice() }
            withTimeout(5_000) { completionStarted.await() }
            compose.runOnUiThread {
                current.set(target(second))
                assertTrue(navigation.bindNotificationSession(radioId, null, second))
            }
            releaseCompletion.complete(Unit)
            compose.waitUntil(5_000) { entry.recovery.value.failed }
            compose.onNodeWithTag("onboarding-completion-restart").performClick()
            withTimeout(5_000) { resetStarted.await() }
            compose.runOnUiThread {
                current.set(target(third))
                assertTrue(navigation.bindNotificationSession(radioId, null, third))
            }
            releaseReset.complete(Unit)
            compose.waitUntil(5_000) { entry.recovery.value.failed && !entry.recovery.value.restarting }
            assertSame(originalWitness, entry.witness.get())
            assertRerunStillMounted(navigation)
            assertFalse(completed.get())

            compose.onNodeWithTag("onboarding-completion-restart").performClick()
            compose.waitUntil(5_000) { !entry.recovery.value.failed && !entry.recovery.value.restarting }
            compose.runOnUiThread { entry.flow.confirmNoDevice() }
            compose.waitUntil(5_000) { navigation.state.value.selectedTab == AppTab.CHATS }
            assertTrue(completed.get())
            assertEquals(2, resets.get())
            assertEquals(third, navigation.currentState.boundSessionToken)
        } finally {
            releaseCompletion.complete(Unit)
            releaseReset.complete(Unit)
            processScope.cancel()
        }
    }

    @Test fun `in-flight completion is not rebound after a programmatic tab round trip`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val applyStarted = CompletableDeferred<Unit>()
        val releaseApply = CompletableDeferred<Unit>()
        try {
            val token = SessionToken(ProcessEpoch(UUID(0, 91)), Generation(1), RadioId(UUID(0, 92)))
            val target = OnboardingPresetTarget(token.epoch.value, token.generation.value, token.radioId.value)
            val presets = object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(true)
                override fun currentTarget() = target
                override suspend fun apply(presetId: String): OnboardingPresetOutcome {
                    applyStarted.complete(Unit)
                    releaseApply.await()
                    return OnboardingPresetOutcome.Applied
                }
            }
            val dependencies = appOnboarding(processScope, presets)
            val navigation = NavigationCoordinator().also {
                it.selectTab(AppTab.SETTINGS)
                assertTrue(it.bindNotificationSession(token.radioId, null, token))
            }
            mountShell(navigation, dependencies)
            compose.onNodeWithTag("open-radio-setup").performClick()
            val retention = navigation.onboardingRetention(dependencies)
            compose.waitUntil(5_000) { retention.mountedEntries.value.size == 1 }
            val entry = retention.mountedEntries.value.values.single()
            val original = entry.witness.get()
            val presetId = entry.flow.preset.visiblePresets().first().id
            compose.runOnUiThread {
                entry.flow.onboarding.replacePath(listOf(OnboardingStep.PRESET))
                entry.flow.preset.select(presetId)
                entry.flow.preset.apply()
            }
            withTimeout(5_000) { applyStarted.await() }
            compose.runOnUiThread {
                navigation.selectTab(AppTab.NODES)
                navigation.selectTab(AppTab.SETTINGS)
            }
            compose.waitForIdle()
            assertSame(original, entry.witness.get())
            releaseApply.complete(Unit)
            compose.waitUntil(5_000) { entry.recovery.value.failed }
            compose.onNodeWithTag("onboarding-completion-recovery").assertIsDisplayed()
            assertSame(original, entry.witness.get())
            assertEquals(AppTab.SETTINGS, navigation.currentState.selectedTab)
            assertEquals(entry.identity.entryId, navigation.currentState.activeStack.last().id)
        } finally {
            releaseApply.complete(Unit)
            processScope.cancel()
        }
    }

    @Test fun `failed in-flight action can refresh before a new completion`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val applyStarted = CompletableDeferred<Unit>()
        val releaseApply = CompletableDeferred<Unit>()
        try {
            val token = SessionToken(ProcessEpoch(UUID(0, 93)), Generation(1), RadioId(UUID(0, 94)))
            val target = OnboardingPresetTarget(token.epoch.value, token.generation.value, token.radioId.value)
            val presets = object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(true)
                override fun currentTarget() = target
                override suspend fun apply(presetId: String): OnboardingPresetOutcome {
                    applyStarted.complete(Unit)
                    releaseApply.await()
                    return OnboardingPresetOutcome.NotConnected
                }
            }
            val dependencies = appOnboarding(processScope, presets)
            val navigation = NavigationCoordinator().also {
                it.selectTab(AppTab.SETTINGS)
                assertTrue(it.bindNotificationSession(token.radioId, null, token))
            }
            mountShell(navigation, dependencies)
            compose.onNodeWithTag("open-radio-setup").performClick()
            val retention = navigation.onboardingRetention(dependencies)
            compose.waitUntil(5_000) { retention.mountedEntries.value.size == 1 }
            val entry = retention.mountedEntries.value.values.single()
            val original = entry.witness.get()
            val presetId = entry.flow.preset.visiblePresets().first().id
            compose.runOnUiThread {
                entry.flow.onboarding.replacePath(listOf(OnboardingStep.PRESET))
                entry.flow.preset.select(presetId)
                entry.flow.preset.apply()
            }
            withTimeout(5_000) { applyStarted.await() }
            compose.runOnUiThread {
                navigation.selectTab(AppTab.NODES)
                navigation.selectTab(AppTab.SETTINGS)
            }
            compose.waitForIdle()
            assertSame(original, entry.witness.get())
            releaseApply.complete(Unit)
            compose.waitUntil(5_000) { entry.witness.get() !== original }
            compose.runOnUiThread {
                entry.flow.onboarding.replacePath(listOf(OnboardingStep.PAIR))
                entry.flow.confirmNoDevice()
            }
            compose.waitUntil(5_000) { navigation.state.value.selectedTab == AppTab.CHATS }
            assertEquals(token, navigation.currentState.boundSessionToken)
        } finally {
            releaseApply.complete(Unit)
            processScope.cancel()
        }
    }

    @Test fun `rerun recreation retains its entry while retired work cannot complete a successor`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val applyStarted = CompletableDeferred<Unit>()
        val releaseApply = CompletableDeferred<Unit>()
        val applyExited = CompletableDeferred<Unit>()
        try {
            val token = SessionToken(ProcessEpoch(UUID(0, 31)), Generation(1), RadioId(UUID(0, 32)))
            val target = OnboardingPresetTarget(token.epoch.value, token.generation.value, token.radioId.value)
            var applies = 0
            val presets = object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(true)
                override fun currentTarget() = target
                override suspend fun apply(presetId: String): OnboardingPresetOutcome {
                    applies++
                    applyStarted.complete(Unit)
                    try {
                        return withContext(NonCancellable) {
                            releaseApply.await()
                            OnboardingPresetOutcome.Applied
                        }
                    } finally {
                        applyExited.complete(Unit)
                    }
                }
            }
            val dependencies = appOnboarding(processScope, presets)
            val navigation = NavigationCoordinator().also {
                it.selectTab(AppTab.SETTINGS)
                assertTrue(it.bindNotificationSession(token.radioId, null, token))
            }
            mountShell(navigation, dependencies)
            compose.onNodeWithTag("open-radio-setup").performClick()
            val retention = navigation.onboardingRetention(dependencies)
            compose.waitUntil(5_000) { retention.mountedEntries.value.size == 1 }
            val first = retention.mountedEntries.value.values.single()
            val oldWitness = first.witness.get()
            val presetId = first.flow.preset.visiblePresets().first().id
            compose.runOnUiThread {
                first.flow.onboarding.replacePath(listOf(OnboardingStep.PRESET))
                first.flow.preset.select(presetId)
                first.flow.preset.apply()
            }
            withTimeout(5_000) { applyStarted.await() }

            host.recreate()
            mountShell(navigation, dependencies)
            compose.waitForIdle()
            assertSame(first, retention.mountedEntries.value[first.identity])
            assertSame(oldWitness, first.witness.get())
            assertTrue(first.flow.preset.state.value.isApplying)

            compose.runOnUiThread {
                assertTrue(navigation.back())
                navigation.navigate(FeatureRoute(FeatureId.ONBOARDING))
            }
            compose.waitUntil(5_000) {
                !first.owner.ownsFlowCoordinator(first.flow) &&
                    retention.mountedEntries.value.values.any { it.identity != first.identity }
            }
            val successor = retention.mountedEntries.value.values.single()
            assertNotSame(first.owner, successor.owner)
            assertNotSame(first.flow, successor.flow)
            assertFalse(navigation.completeMountedOnboarding(oldWitness, MountedOnboardingProducer.Preset(token)))
            releaseApply.complete(Unit)
            withTimeout(5_000) { applyExited.await() }
            withTimeout(5_000) { first.flow.preset.state.first { !it.isApplying } }
            compose.waitForIdle()
            assertRerunStillMounted(navigation)
            assertEquals(successor.identity.entryId, navigation.currentState.activeStack.last().id)
            assertFalse(successor.flow.onboarding.hasCompletedOnboarding)
            assertEquals(1, applies)

            compose.runOnUiThread { successor.flow.confirmNoDevice() }
            compose.waitUntil(5_000) { navigation.state.value.selectedTab == AppTab.CHATS }
            assertEquals(token, navigation.currentState.boundSessionToken)
        } finally {
            releaseApply.complete(Unit)
            processScope.cancel()
        }
    }

    @Test fun `generic fallback creates a new flow after Activity scope recreation`() {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val base = appOnboarding(processScope, object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(false)
                override suspend fun apply(presetId: String): OnboardingPresetOutcome =
                    error("A fallback flow must not apply a preset")
            })
            val dependencies = object : OnboardingFeatureDependencies by base.forRerun() {
                override fun retainedFlowCoordinator(): OnboardingFlowCoordinator? = null
                override fun ownsFlowCoordinator(coordinator: OnboardingFlowCoordinator): Boolean = true
            }
            val navigation = NavigationCoordinator().also { it.selectTab(AppTab.SETTINGS) }
            mountShell(navigation, dependencies)
            compose.onNodeWithTag("open-radio-setup").performClick()
            val retention = navigation.onboardingRetention(dependencies)
            compose.waitUntil(5_000) { retention.mountedEntries.value.size == 1 }
            val first = retention.mountedEntries.value.values.single()

            host.recreate()
            mountShell(navigation, dependencies)
            compose.waitUntil(5_000) { retention.mountedEntries.value.values.singleOrNull()?.let { it !== first } == true }
            val replacement = retention.mountedEntries.value.values.single()
            assertEquals(first.identity, replacement.identity)
            assertNotSame(first.flow, replacement.flow)
            assertFalse(first.flow.onboarding.canStartCompletion())
            assertTrue(replacement.flow.onboarding.canStartCompletion())
        } finally {
            processScope.cancel()
        }
    }

    @Test fun `generic owner cannot be reused until entry retirement finishes`() {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val retiring = CountDownLatch(1)
        val releaseRetirement = CountDownLatch(1)
        try {
            val base = appOnboarding(processScope, object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(false)
                override suspend fun apply(presetId: String): OnboardingPresetOutcome =
                    error("This flow must not apply a preset")
            })
            val dependencies = object : OnboardingFeatureDependencies by base.forRerun() {
                @Volatile private var current = OnboardingFlowCoordinator(processScope, this).also {
                    it.restoreStartingPath()
                }
                override fun retainedFlowCoordinator(): OnboardingFlowCoordinator = current
                override fun ownsFlowCoordinator(coordinator: OnboardingFlowCoordinator) = current === coordinator
                override fun onFlowCoordinatorEntryRemoved(coordinator: OnboardingFlowCoordinator) {
                    retiring.countDown()
                    check(releaseRetirement.await(5, TimeUnit.SECONDS))
                    coordinator.onboarding.retireCompletion()
                    current = OnboardingFlowCoordinator(processScope, this).also { it.restoreStartingPath() }
                }
            }
            val navigation = NavigationCoordinator().also { it.selectTab(AppTab.SETTINGS) }
            mountShell(navigation, dependencies)
            compose.onNodeWithTag("open-radio-setup").performClick()
            val retention = navigation.onboardingRetention(dependencies)
            compose.waitUntil(5_000) { retention.mountedEntries.value.size == 1 }
            val first = retention.mountedEntries.value.values.single()
            compose.runOnUiThread { assertTrue(navigation.back()) }
            compose.waitUntil(5_000) { retiring.count == 0L }
            compose.runOnUiThread { navigation.navigate(FeatureRoute(FeatureId.ONBOARDING)) }
            compose.waitForIdle()
            assertTrue(retention.mountedEntries.value.isEmpty())
            assertFalse(navigation.completeMountedOnboarding(first.witness.get(), MountedOnboardingProducer.NoDevice))

            releaseRetirement.countDown()
            compose.waitUntil(5_000) { retention.mountedEntries.value.size == 1 }
            val successor = retention.mountedEntries.value.values.single()
            assertNotSame(first.flow, successor.flow)
            assertTrue(successor.flow.onboarding.canStartCompletion())
        } finally {
            releaseRetirement.countDown()
            processScope.cancel()
        }
    }

    @Test fun `failed owner retirement is visible and cannot reuse the old flow`() {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val retiring = CountDownLatch(1)
        try {
            val base = appOnboarding(processScope, object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(false)
                override suspend fun apply(presetId: String): OnboardingPresetOutcome =
                    error("This flow must not apply a preset")
            })
            val dependencies = object : OnboardingFeatureDependencies by base.forRerun() {
                val current = OnboardingFlowCoordinator(processScope, this).also { it.restoreStartingPath() }
                override fun retainedFlowCoordinator(): OnboardingFlowCoordinator = current
                override fun ownsFlowCoordinator(coordinator: OnboardingFlowCoordinator) = current === coordinator
                override fun onFlowCoordinatorEntryRemoved(coordinator: OnboardingFlowCoordinator) {
                    retiring.countDown()
                    throw IllegalStateException("onboarding owner did not retire")
                }
            }
            val navigation = NavigationCoordinator().also { it.selectTab(AppTab.SETTINGS) }
            mountShell(navigation, dependencies)
            compose.onNodeWithTag("open-radio-setup").performClick()
            val retention = navigation.onboardingRetention(dependencies)
            compose.waitUntil(5_000) { retention.mountedEntries.value.size == 1 }
            val first = retention.mountedEntries.value.values.single()
            compose.runOnUiThread { assertTrue(navigation.back()) }
            compose.waitUntil(5_000) { retiring.count == 0L }
            compose.runOnUiThread { navigation.navigate(FeatureRoute(FeatureId.ONBOARDING)) }
            compose.waitUntil(5_000) { retention.unavailableEntries.value.isNotEmpty() }
            assertTrue(retention.mountedEntries.value.isEmpty())
            assertFalse(navigation.completeMountedOnboarding(first.witness.get(), MountedOnboardingProducer.NoDevice))
            compose.onNodeWithText(host.get().getString(L.commonErrorFailedToLoad)).assertIsDisplayed()
            compose.onNodeWithTag("navigation-back").assertIsEnabled().performClick()
            assertEquals(NavigationDestination.Root(AppTab.SETTINGS), navigation.state.value.activeStack.last().destination)
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
        override fun ownsFlowCoordinator(coordinator: OnboardingFlowCoordinator): Boolean = flow === coordinator
    }
}
