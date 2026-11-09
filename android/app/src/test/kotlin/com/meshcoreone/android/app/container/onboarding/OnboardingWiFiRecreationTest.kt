// AndroidOnly: WP-305 Retained Wi-Fi state across detached and reattached Activity failure delivery.
package com.meshcoreone.android.app.container.onboarding

import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.services.simulator.DemoModeDefaults
import com.meshcoreone.android.core.services.simulator.DemoModeManager
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.onboarding.InMemoryOnboardingFlagStore
import com.meshcoreone.android.feature.onboarding.OnboardingConnectOutcome
import com.meshcoreone.android.feature.onboarding.OnboardingPresetOutcome
import com.meshcoreone.android.feature.onboarding.OnboardingPresetPort
import com.meshcoreone.android.feature.onboarding.OnboardingStep
import com.meshcoreone.android.feature.onboarding.OnboardingWiFiPort
import com.meshcoreone.android.feature.onboarding.PairSheet
import com.meshcoreone.android.feature.onboarding.WiFiSheetState
import com.meshcoreone.android.feature.onboarding.ui.OnboardingFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
@Config(sdk = [31], qualifiers = "w400dp-h900dp")
class OnboardingWiFiRecreationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: org.robolectric.android.controller.ActivityController<ComponentActivity>? = null

    @Before fun reduceMotion() {
        Settings.Global.putFloat(
            ApplicationProvider.getApplicationContext<Context>().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            0f,
        )
    }

    @After fun closeHost() { destroyHost() }

    private fun destroyHost() {
        host?.pause()?.stop()?.destroy()
        host = null
    }

    private fun attach(dependencies: AppOnboarding) {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        checkNotNull(host).get().setContent {
            MeshCoreTheme(motionScale = 0f) {
                OnboardingFlow(dependencies, onCompleted = {})
            }
        }
        compose.waitForIdle()
    }

    private fun dependencies(scope: CoroutineScope, wifi: OnboardingWiFiPort): AppOnboarding {
        val connectionHost = FakeConnectionHost()
        val demoFlags = HashMap<String, Boolean>()
        return AppOnboarding(
            flags = InMemoryOnboardingFlagStore(),
            permissions = AndroidOnboardingPermissionPort(FakePermissionFacts(), RequestedPermissions(), scanFallback = false),
            pairing = AppOnboardingPairingPort(connectionHost, scope, null),
            wifi = wifi,
            demo = AppOnboardingDemoPort(DemoModeManager(object : DemoModeDefaults {
                override fun bool(forKey: String) = demoFlags[forKey] ?: false
                override suspend fun setAll(values: Map<String, Boolean>) { demoFlags.putAll(values) }
            }), scope) { error("Demo connection is not requested") },
            region = AppOnboardingRegionPort(ServicesRegionCatalog(), MutableStateFlow<RegionSelection?>(null), {}, null),
            presets = object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(true)
                override suspend fun apply(presetId: String) = OnboardingPresetOutcome.Applied
            },
            settingsOpener = {},
            locationReporter = null,
            sdkInt = 31,
            flowScope = scope,
            hasCompleted = MutableStateFlow(false),
        )
    }

    private fun label(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    @Test fun `failure delivered between Activity destruction and attachment retains fields error and retry`() {
        failureAcrossRecreation(releaseBeforeAttachment = true)
    }

    @Test fun `failure delivered after reattachment retains fields error and retry`() {
        failureAcrossRecreation(releaseBeforeAttachment = false)
    }

    private fun failureAcrossRecreation(releaseBeforeAttachment: Boolean) = runBlocking {
        val processJob = SupervisorJob()
        val processScope = CoroutineScope(processJob + Dispatchers.Unconfined)
        try {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<OnboardingConnectOutcome>()
            val calls = mutableListOf<Pair<String, Int>>()
            val dependencies = dependencies(processScope, object : OnboardingWiFiPort {
                override suspend fun connect(host: String, port: Int): OnboardingConnectOutcome {
                    calls += host to port
                    if (calls.size == 1) {
                        started.complete(Unit)
                        return release.await()
                    }
                    return OnboardingConnectOutcome.Connected
                }
            })
            val flow = dependencies.retainedFlowCoordinator()
            flow.onboarding.replacePath(listOf(OnboardingStep.PAIR))
            attach(dependencies)
            compose.onNodeWithText(label(O.deviceScanConnectViaWifi)).performClick()
            compose.runOnUiThread {
                flow.wifi.setAddress("192.0.2.17")
                flow.wifi.setPort("4321")
            }
            compose.onNodeWithText(label(O.wifiConnectionConnect)).assertIsEnabled().performClick()
            started.await()
            assertTrue(flow.wifi.state.value.isConnecting)
            destroyHost()
            assertTrue(processJob.isActive)

            val failure = OnboardingConnectOutcome.Failed(UiText.Verbatim("retained Wi-Fi failure"))
            if (releaseBeforeAttachment) {
                release.complete(failure)
                assertFalse(flow.wifi.state.value.isConnecting)
                assertEquals(failure.message, flow.wifi.state.value.error)
            }
            attach(dependencies)
            assertSame(flow, dependencies.retainedFlowCoordinator())
            if (!releaseBeforeAttachment) {
                assertTrue(flow.wifi.state.value.isConnecting)
                release.complete(failure)
            }
            compose.waitForIdle()
            assertEquals("192.0.2.17", flow.wifi.state.value.ipAddress)
            assertEquals("4321", flow.wifi.state.value.port)
            assertEquals(failure.message, flow.wifi.state.value.error)
            assertEquals(PairSheet.WIFI, flow.deviceScan.state.value.sheet)
            compose.onNodeWithText("192.0.2.17").assertIsDisplayed()
            compose.onNodeWithText("4321").assertIsDisplayed()
            compose.onNodeWithText("retained Wi-Fi failure").assertIsDisplayed()
            compose.onNodeWithText(label(O.wifiConnectionConnect)).assertIsDisplayed().assertIsEnabled().performClick()
            assertEquals(listOf("192.0.2.17" to 4321, "192.0.2.17" to 4321), calls)
            assertEquals(OnboardingStep.REGION, flow.currentStep)
            assertEquals(PairSheet.NONE, flow.deviceScan.state.value.sheet)
            assertTrue(processJob.isActive)
        } finally {
            destroyHost()
            processScope.cancel()
        }
    }

    @Test fun `intentional reopen resets settled state but not an in-flight connection`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<OnboardingConnectOutcome>()
            val dependencies = dependencies(processScope, object : OnboardingWiFiPort {
                override suspend fun connect(host: String, port: Int) = release.await()
            })
            val flow = dependencies.retainedFlowCoordinator()
            flow.onboarding.replacePath(listOf(OnboardingStep.PAIR))
            attach(dependencies)
            compose.onNodeWithText(label(O.deviceScanConnectViaWifi)).performClick()
            compose.runOnUiThread {
                flow.wifi.setAddress("192.0.2.17")
                flow.wifi.setPort("4321")
                flow.wifi.connect()
                flow.dismissPairingSheet()
            }
            compose.onNodeWithText(label(O.deviceScanConnectViaWifi)).performClick()
            assertEquals("192.0.2.17", flow.wifi.state.value.ipAddress)
            assertEquals("4321", flow.wifi.state.value.port)
            assertTrue(flow.wifi.state.value.isConnecting)
            release.complete(OnboardingConnectOutcome.Failed(UiText.Verbatim("fresh-open failure")))
            compose.waitForIdle()
            compose.onNodeWithText("fresh-open failure").assertIsDisplayed()
            compose.runOnUiThread { flow.dismissPairingSheet() }
            compose.onNodeWithText(label(O.deviceScanConnectViaWifi)).performClick()
            assertEquals(WiFiSheetState(), flow.wifi.state.value)
        } finally {
            destroyHost()
            processScope.cancel()
        }
    }

    @Test fun `connection cancellation preserves input without reporting success`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<OnboardingConnectOutcome>()
            val dependencies = dependencies(processScope, object : OnboardingWiFiPort {
                override suspend fun connect(host: String, port: Int) = release.await()
            })
            val flow = dependencies.retainedFlowCoordinator()
            flow.onboarding.replacePath(listOf(OnboardingStep.PAIR))
            attach(dependencies)
            compose.onNodeWithText(label(O.deviceScanConnectViaWifi)).performClick()
            compose.runOnUiThread {
                flow.wifi.setAddress("192.0.2.17")
                flow.wifi.setPort("4321")
                flow.wifi.connect()
            }
            assertTrue(flow.wifi.state.value.isConnecting)
            processScope.cancel()
            assertFalse(flow.wifi.state.value.isConnecting)
            assertEquals("192.0.2.17", flow.wifi.state.value.ipAddress)
            assertEquals("4321", flow.wifi.state.value.port)
            assertEquals(null, flow.wifi.state.value.error)
            assertEquals(OnboardingStep.PAIR, flow.currentStep)
        } finally {
            destroyHost()
            processScope.cancel()
        }
    }
}
