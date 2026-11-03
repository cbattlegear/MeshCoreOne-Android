// AndroidOnly: WP-303 First-run gate: onboarding until the flag is set, then the shell and the Chats root.
// AndroidOnly: WP-305 Verify app-owned flow-session lifetimes across Activity recreation and Settings reruns.
package com.meshcoreone.android.app.container.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.services.simulator.DemoModeDefaults
import com.meshcoreone.android.core.services.simulator.DemoModeManager
import com.meshcoreone.android.feature.onboarding.OnboardingFlagStore
import com.meshcoreone.android.feature.onboarding.OnboardingPresetOutcome
import com.meshcoreone.android.feature.onboarding.OnboardingPresetPort
import com.meshcoreone.android.feature.onboarding.OnboardingPresetTarget
import com.meshcoreone.android.feature.onboarding.OnboardingStep
import com.meshcoreone.android.feature.onboarding.OnboardingState
import com.meshcoreone.android.feature.onboarding.ui.OnboardingFlow
import com.meshcoreone.android.feature.onboarding.PairSheet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w400dp-h800dp")
class OnboardingGateTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = Robolectric.buildActivity(ComponentActivity::class.java)

    /** Reduce-motion (animator scale 0) stops the welcome mesh animation, which would keep Compose from idling. */
    @Before fun reduceMotion() {
        val resolver = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        android.provider.Settings.Global.putFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After fun closeHost() { host.pause().stop().destroy() }

    private fun onboarding(
        flags: WriteBehindOnboardingFlags,
        pairingState: com.meshcoreone.android.app.state.AppState? = null,
    ): AppOnboarding {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        return onboarding(flags, flags.hasCompleted, scope, pairingState)
    }

    private fun onboarding(
        flags: OnboardingFlagStore,
        hasCompleted: StateFlow<Boolean>,
        scope: CoroutineScope,
        pairingState: com.meshcoreone.android.app.state.AppState? = null,
        presets: OnboardingPresetPort? = null,
    ): AppOnboarding {
        val host = FakeConnectionHost()
        val demoStore = HashMap<String, Boolean>()
        return AppOnboarding(
            flags = flags,
            permissions = AndroidOnboardingPermissionPort(FakePermissionFacts(), RequestedPermissions(), scanFallback = false),
            pairing = AppOnboardingPairingPort(host, scope, null),
            wifi = AppOnboardingWiFiPort(host),
            demo = AppOnboardingDemoPort(DemoModeManager(object : DemoModeDefaults {
                override fun bool(forKey: String) = demoStore[forKey] ?: false
                override suspend fun setAll(values: Map<String, Boolean>) { demoStore.putAll(values) }
            }), scope) { error("Simulator connection was not requested by this test") },
            region = AppOnboardingRegionPort(ServicesRegionCatalog(), MutableStateFlow<RegionSelection?>(null), {}, null),
            presets = presets ?: AppOnboardingPresetPort(FakeRadio(), scope),
            settingsOpener = {}, locationReporter = null, sdkInt = 34, flowScope = scope, hasCompleted = hasCompleted,
            pairingState = pairingState,
        )
    }

    private fun flags(completed: Boolean) = WriteBehindOnboardingFlags(
        mapOf(OnboardingState.KEY_HAS_COMPLETED to completed), CoroutineScope(Dispatchers.Unconfined), { _, _ -> },
    )

    private fun showGate(coordinator: NavigationCoordinator, bound: AppOnboarding?) = host.setup().visible().get().setContent {
        MeshCoreTheme { OnboardingGate(coordinator, bound) { Box(Modifier.testTag("shell")) { Text("shell") } } }
    }

    private fun showFlow(dependencies: com.meshcoreone.android.feature.onboarding.OnboardingFeatureDependencies) =
        host.get().setContent { MeshCoreTheme { OnboardingFlow(dependencies, onCompleted = {}) } }

    @Test fun `incomplete onboarding hides the shell and completion reveals it and selects chats`() {
        val flags = flags(completed = false)
        val coordinator = NavigationCoordinator().also { it.selectTab(AppTab.SETTINGS) }
        showGate(coordinator, onboarding(flags))
        compose.onNodeWithTag("shell").assertDoesNotExist()
        assertEquals(AppTab.SETTINGS, coordinator.state.value.selectedTab)

        compose.runOnUiThread { runBlocking { OnboardingState(flags).completeOnboarding() } }
        compose.waitForIdle()
        compose.onNodeWithTag("shell").assertIsDisplayed()
        assertEquals(AppTab.CHATS, coordinator.state.value.selectedTab)
    }

    @Test fun `already completed install shows the shell at once and leaves navigation alone`() {
        val coordinator = NavigationCoordinator().also { it.selectTab(AppTab.MAP) }
        showGate(coordinator, onboarding(flags(completed = true)))
        compose.onNodeWithTag("shell").assertIsDisplayed()
        assertEquals(AppTab.MAP, coordinator.state.value.selectedTab)
    }

    @Test fun `no bindings shows the shell and never a fake flow`() {
        showGate(NavigationCoordinator(), null)
        compose.onNodeWithTag("shell").assertIsDisplayed()
    }

    @Test fun `first run flow survives Activity recreation and retries completion without reapplying a preset`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            host.setup().visible()
            val completion = MutableStateFlow(false)
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            var writes = 0
            val flags = object : OnboardingFlagStore {
                override fun getBoolean(key: String) = completion.value
                override fun setBoolean(key: String, value: Boolean) { completion.value = value }
                override suspend fun persistBoolean(key: String, value: Boolean) {
                    writes += 1
                    if (writes == 1) {
                        writeStarted.complete(Unit)
                        releaseWrite.await()
                        error("preference write failed")
                    }
                    setBoolean(key, value)
                }
            }
            val target = OnboardingPresetTarget(
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"), 1,
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"),
            )
            var applies = 0
            val presets = object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(true)
                override fun currentTarget() = target
                override suspend fun apply(presetId: String): OnboardingPresetOutcome {
                    applies += 1
                    return OnboardingPresetOutcome.Applied
                }
            }
            val dependencies = onboarding(flags, completion, processScope, presets = presets)
            val beforeRecreation = dependencies.retainedFlowCoordinator()
            val flow = checkNotNull(beforeRecreation)
            val presetId = flow.preset.visiblePresets().first().id
            flow.onboarding.replacePath(listOf(OnboardingStep.PRESET))
            showFlow(dependencies)
            compose.waitForIdle()
            flow.preset.select(presetId)
            flow.preset.apply()
            writeStarted.await()

            assertTrue(flow.preset.state.value.isApplying)
            host.recreate()
            showFlow(dependencies)
            compose.waitForIdle()
            val afterRecreation = checkNotNull(dependencies.retainedFlowCoordinator())
            assertSame(flow, afterRecreation)
            assertEquals(presetId, afterRecreation.preset.state.value.selectedId)
            assertTrue(afterRecreation.preset.state.value.isApplying)

            releaseWrite.complete(Unit)
            afterRecreation.preset.state.first { it.completionNeedsRetry }
            assertFalse(completion.value)
            afterRecreation.preset.retry()

            assertTrue(completion.first { it })
            assertEquals(2, writes)
            assertEquals(1, applies)
            assertFalse(afterRecreation.preset.state.value.isApplying)
        } finally {
            processScope.cancel()
        }
    }

    @Test fun `held no-device completion failure remains retryable after Activity recreation`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            host.setup().visible()
            val completion = MutableStateFlow(false)
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            var writes = 0
            val flags = object : OnboardingFlagStore {
                override fun getBoolean(key: String) = completion.value
                override fun setBoolean(key: String, value: Boolean) { completion.value = value }
                override suspend fun persistBoolean(key: String, value: Boolean) {
                    writes += 1
                    if (writes == 1) {
                        writeStarted.complete(Unit)
                        releaseWrite.await()
                        error("preference write failed")
                    }
                    setBoolean(key, value)
                }
            }
            val dependencies = onboarding(flags, completion, processScope)
            val flow = dependencies.retainedFlowCoordinator()
            flow.getStarted()
            flow.continueFromPermissions()
            flow.deviceScan.showSheet(PairSheet.NO_DEVICE)
            showFlow(dependencies)
            compose.waitForIdle()

            flow.confirmNoDevice()
            writeStarted.await()
            assertTrue(flow.isCompletingWithoutDevice.value)
            host.recreate()
            showFlow(dependencies)
            compose.waitForIdle()
            val afterRecreation = checkNotNull(dependencies.retainedFlowCoordinator())
            assertSame(flow, afterRecreation)

            releaseWrite.complete(Unit)
            assertTrue(afterRecreation.hasCompletionError.first { it })
            assertFalse(afterRecreation.onboarding.hasCompletedOnboarding)
            afterRecreation.retryCompletion()
            assertTrue(completion.first { it })
            assertEquals(2, writes)
            assertFalse(afterRecreation.hasCompletionError.value)
        } finally {
            processScope.cancel()
        }
    }

    @Test fun `Settings rerun owns a retained session separate from first run and resets after completion`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            host.setup().visible()
            val completedFlags = flags(completed = true)
            val firstRun = onboarding(completedFlags, completedFlags.hasCompleted, processScope)
            val firstRunFlow = firstRun.retainedFlowCoordinator()
            val rerun = firstRun.forRerun()
            assertSame(rerun, firstRun.forRerun())

            val rerunFlow = checkNotNull(rerun.retainedFlowCoordinator())
            assertNotSame(firstRunFlow, rerunFlow)
            assertTrue(firstRunFlow.onboarding.hasCompletedOnboarding)
            assertFalse(rerunFlow.onboarding.hasCompletedOnboarding)
            assertSame(rerunFlow, rerun.retainedFlowCoordinator())
            assertNotSame(firstRun.flags, rerun.flags)

            showFlow(rerun)
            compose.waitForIdle()
            host.recreate()
            showFlow(rerun)
            compose.waitForIdle()
            assertSame(rerunFlow, rerun.retainedFlowCoordinator())
            rerunFlow.getStarted()
            rerunFlow.continueFromPermissions()
            rerunFlow.deviceScan.showSheet(PairSheet.NO_DEVICE)
            rerunFlow.confirmNoDevice()
            compose.waitForIdle()
            assertTrue(rerunFlow.onboarding.hasCompletedOnboarding)

            val nextRerunFlow = checkNotNull(rerun.retainedFlowCoordinator())
            assertNotSame(rerunFlow, nextRerunFlow)
            assertFalse(nextRerunFlow.onboarding.hasCompletedOnboarding)
            assertTrue(firstRunFlow.onboarding.hasCompletedOnboarding)
        } finally {
            processScope.cancel()
        }
    }

    @Test fun `consent is mounted above both onboarding and the completed shell`() {
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val state = com.meshcoreone.android.app.container.appStateOf(scope)
            state.connectionUI.pendingSystemPairingSetup =
                com.meshcoreone.android.core.connectivity.pairing.SystemPairingSetupPrompt(
                    listOf(com.meshcoreone.android.core.connectivity.pairing.SystemPairedAccessory(
                        java.util.UUID.randomUUID(), "Leftover radio",
                    )),
                )
            val flags = flags(completed = false)
            showGate(NavigationCoordinator(), onboarding(flags, state))
            compose.onNodeWithTag("system-pairing-setup").assertIsDisplayed()
            compose.onNodeWithTag("shell").assertDoesNotExist()
            compose.runOnUiThread { runBlocking { OnboardingState(flags).completeOnboarding() } }
            compose.waitForIdle()
            compose.onNodeWithTag("shell").assertExists()
            compose.onNodeWithTag("system-pairing-setup").assertIsDisplayed()
            compose.onNodeWithTag("system-pairing-cancel").performClick()
            compose.onNodeWithTag("system-pairing-setup").assertDoesNotExist()
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }
}
