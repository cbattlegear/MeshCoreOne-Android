// AndroidOnly: WP-303 First-run gate: onboarding until the flag is set, then the shell and the Chats root.
// AndroidOnly: WP-305 Verify app-owned flow-session lifetimes across Activity recreation and Settings reruns.
package com.meshcoreone.android.app.container.onboarding

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import com.meshcoreone.android.app.content.LocationPermissionResultReporting
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.services.simulator.DemoModeDefaults
import com.meshcoreone.android.core.services.simulator.DemoModeManager
import com.meshcoreone.android.feature.onboarding.OnboardingFlagStore
import com.meshcoreone.android.feature.onboarding.InMemoryOnboardingFlagStore
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionKind
import com.meshcoreone.android.feature.onboarding.OnboardingPresetOutcome
import com.meshcoreone.android.feature.onboarding.OnboardingPresetPort
import com.meshcoreone.android.feature.onboarding.OnboardingPresetTarget
import com.meshcoreone.android.feature.onboarding.OnboardingStep
import com.meshcoreone.android.feature.onboarding.OnboardingState
import com.meshcoreone.android.feature.onboarding.PermissionStatus
import com.meshcoreone.android.feature.onboarding.ui.OnboardingFlow
import com.meshcoreone.android.feature.onboarding.PairSheet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
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
        locationReporter: LocationPermissionResultReporting? = null,
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
            settingsOpener = {}, locationReporter = locationReporter, sdkInt = 34, flowScope = scope, hasCompleted = hasCompleted,
            pairingState = pairingState,
        )
    }

    private fun flags(completed: Boolean) = WriteBehindOnboardingFlags(
        mapOf(OnboardingState.KEY_HAS_COMPLETED to completed), CoroutineScope(Dispatchers.Unconfined), { _, _ -> },
    )

    private fun showGate(coordinator: NavigationCoordinator, bound: AppOnboarding?) = host.setup().visible().get().setContent {
        MeshCoreTheme { OnboardingGate(coordinator, bound) { Box(Modifier.testTag("shell")) { Text("shell") } } }
    }

    private fun showFlow(
        dependencies: com.meshcoreone.android.feature.onboarding.OnboardingFeatureDependencies,
        onReceipt: ((com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt) -> Boolean)? = null,
    ) = host.get().setContent {
        MeshCoreTheme { OnboardingFlow(dependencies, onCompleted = {}, onCompletionReceipt = onReceipt) }
    }

    @Test fun `raw flag before producer acknowledgement survives recreation without becoming startup completion`() =
        runBlocking {
            val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val release = CompletableDeferred<Unit>()
            try {
                host.setup().visible()
                val published = CompletableDeferred<Unit>()
                val flags = WriteBehindOnboardingFlags(emptyMap(), processScope, persist = { _, _ -> })
                val delayed = object : OnboardingFlagStore by flags {
                    override suspend fun persistBoolean(
                        key: String, value: Boolean, requireCurrent: () -> Unit,
                        seal: (com.meshcoreone.android.core.contracts.domain.OnboardingCompletionAttempt) -> Boolean,
                        attempt: com.meshcoreone.android.core.contracts.domain.OnboardingCompletionAttempt,
                    ) {
                        flags.persistBoolean(key, value, requireCurrent, seal, attempt)
                        published.complete(Unit)
                        release.await()
                    }
                }
                val dependencies = onboarding(delayed, flags.hasCompleted, processScope)
                val original = dependencies.retainedFlowCoordinator()
                val completing = async(start = CoroutineStart.UNDISPATCHED) { original.onboarding.completeOnboarding() }
                published.await()
                assertTrue(flags.hasCompleted.value)
                assertFalse(dependencies.wasCompletedAtLoad)
                assertEquals(null, original.onboarding.completionReceipt)
                val delivered = mutableListOf<com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt>()
                showFlow(dependencies) { delivered += it; true }
                compose.waitForIdle()
                assertTrue(delivered.isEmpty())
                host.recreate()
                showFlow(dependencies) { delivered += it; true }
                compose.waitForIdle()
                assertSame(original, dependencies.retainedFlowCoordinator())
                assertFalse(dependencies.wasCompletedAtLoad)
                assertTrue(delivered.isEmpty())
                release.complete(Unit)
                completing.await()
                compose.waitForIdle()
                assertEquals(
                    listOf(com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt.WithoutDevice),
                    delivered,
                )
            } finally { release.complete(Unit); processScope.cancel() }
        }

    @Test fun `held and failed reset revoke delivery until explicit reset recovery`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        try {
            host.setup().visible()
            val resetting = CompletableDeferred<Unit>()
            var failReset = true
            var durable = false
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), processScope, { _, value ->
                    if (!value && failReset) {
                        resetting.complete(Unit)
                        release.await()
                        throw java.io.IOException("reset failed")
                    }
                    durable = value
                },
            )
            val dependencies = onboarding(flags, flags.hasCompleted, processScope)
            val flow = dependencies.retainedFlowCoordinator()
            flow.onboarding.completeOnboarding()
            var deliveryKey by mutableStateOf(0)
            var accept = false
            val delivered = mutableListOf<com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt>()
            host.get().setContent {
                MeshCoreTheme {
                    OnboardingFlow(
                        dependencies, onCompleted = {},
                        onCompletionReceipt = { delivered += it; accept },
                        completionDeliveryKey = deliveryKey,
                    )
                }
            }
            compose.waitForIdle()
            assertEquals(1, delivered.size)
            val reset = async { runCatching { flow.onboarding.resetOnboarding() } }
            resetting.await()
            compose.runOnIdle { accept = true; deliveryKey++ }
            compose.waitForIdle()
            assertEquals(1, delivered.size)
            release.complete(Unit)
            assertEquals("reset failed", reset.await().exceptionOrNull()?.message)
            compose.runOnIdle { deliveryKey++ }
            compose.waitForIdle()
            assertEquals(1, delivered.size)
            assertTrue(durable)
            failReset = false
            flow.onboarding.resetOnboarding()
            compose.waitForIdle()
            assertFalse(durable)
            flow.onboarding.completeOnboarding()
            compose.waitForIdle()
            assertEquals(2, delivered.size)
            assertTrue(durable)
        } finally { release.complete(Unit); processScope.cancel() }
    }

    @Test fun `first-run explicit retirement rejects held write and reacquires a fresh exact-owned flow`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        try {
            host.setup().visible()
            val staged = CompletableDeferred<Unit>()
            var stages = 0
            var durable = false
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), processScope, { _, value -> durable = value },
                stageCompletion = { if (++stages == 1) { staged.complete(Unit); release.await() } },
            )
            val dependencies = onboarding(flags, flags.hasCompleted, processScope)
            val old = dependencies.retainedFlowCoordinator()
            val completing = async { runCatching { old.onboarding.completeOnboarding() } }
            staged.await()
            dependencies.onFlowCompletionHandled(old)
            val fresh = dependencies.retainedFlowCoordinator()
            assertNotSame(old, fresh)
            assertTrue(dependencies.ownsFlowCoordinator(fresh))
            assertFalse(dependencies.ownsFlowCoordinator(old))
            dependencies.onFlowCompletionHandled(old)
            assertTrue(dependencies.ownsFlowCoordinator(fresh))
            release.complete(Unit)
            assertTrue(completing.await().isFailure)
            assertFalse(durable)
            fresh.onboarding.completeOnboarding()
            assertTrue(durable)
            assertTrue(fresh.onboarding.hasCompletedOnboarding)
            assertFalse(old.onboarding.hasCompletedOnboarding)
        } finally { release.complete(Unit); processScope.cancel() }
    }

    @Test fun `delivery retry cannot observe a second unsealed preset receipt from a completed flow`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releaseSecondStage = CompletableDeferred<Unit>()
        try {
            host.setup().visible()
            var stages = 0
            var applies = 0
            var durable = false
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), processScope, { _, value -> durable = value },
                stageCompletion = {
                    if (++stages == 2) {
                        releaseSecondStage.await()
                        throw java.io.IOException("second attempt rejected before seal")
                    }
                },
            )
            val first = OnboardingPresetTarget(java.util.UUID.randomUUID(), 1, java.util.UUID.randomUUID())
            var target = first
            val presets = object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(true)
                override fun currentTarget() = target
                override suspend fun apply(presetId: String): OnboardingPresetOutcome {
                    applies++
                    return OnboardingPresetOutcome.Applied
                }
            }
            val dependencies = onboarding(flags, flags.hasCompleted, processScope, presets = presets)
            val flow = dependencies.retainedFlowCoordinator()
            val id = flow.preset.visiblePresets().first().id
            flow.preset.apply(id)
            val original = checkNotNull(flow.onboarding.completionReceipt)
            assertTrue(durable)
            var deliveryKey by mutableStateOf(0)
            var accept = false
            val delivered = mutableListOf<com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt>()
            host.get().setContent {
                MeshCoreTheme {
                    OnboardingFlow(
                        dependencies, onCompleted = { error("Use the receipt") },
                        onCompletionReceipt = { delivered += it; accept },
                        completionDeliveryKey = deliveryKey,
                    )
                }
            }
            compose.waitForIdle()
            target = first.copy(generation = 2)
            flow.preset.apply(id)
            compose.runOnIdle { deliveryKey++ }
            compose.waitForIdle()
            assertEquals(2, delivered.size)
            delivered.forEach { assertSame(original, it) }
            assertEquals(1, applies)
            assertEquals(1, stages)
            assertTrue(flow.preset.state.value.isCompleted)
            assertFalse(flow.preset.canApply(true, flow.preset.state.value))
            releaseSecondStage.complete(Unit)
            compose.runOnIdle { accept = true; deliveryKey++ }
            compose.waitForIdle()
            assertEquals(3, delivered.size)
            delivered.forEach { assertSame(original, it) }
            assertTrue(durable)
        } finally { releaseSecondStage.complete(Unit); processScope.cancel() }
    }

    @Test fun `retiring a flow before its radio apply resumes blocks later completion creation`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        try {
            host.setup().visible()
            val presets = object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(true)
                override suspend fun apply(presetId: String): OnboardingPresetOutcome {
                    release.await()
                    return OnboardingPresetOutcome.Applied
                }
            }
            val dependencies = onboarding(
                InMemoryOnboardingFlagStore(), MutableStateFlow(false), processScope, presets = presets,
            ).forRerun()
            val old = checkNotNull(dependencies.retainedFlowCoordinator())
            old.preset.apply(old.preset.visiblePresets().first().id)
            assertTrue(old.preset.state.value.isApplying)
            dependencies.onFlowCompletionHandled(old)
            val replacement = checkNotNull(dependencies.retainedFlowCoordinator())
            release.complete(Unit)
            assertFalse(old.onboarding.hasCompletedOnboarding)
            assertEquals(null, old.onboarding.completionReceipt)
            assertEquals(0, old.preset.state.value.commitTick)
            assertFalse(old.preset.state.value.isApplying)
            assertTrue(dependencies.ownsFlowCoordinator(replacement))
            assertFalse(replacement.onboarding.hasCompletedOnboarding)
        } finally { release.complete(Unit); processScope.cancel() }
    }

    @Test fun `equal no-device receipts never let old flow settlement retire its replacement`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            host.setup().visible()
            val dependencies = onboarding(
                InMemoryOnboardingFlagStore(), MutableStateFlow(false), processScope,
            ).forRerun()
            val old = checkNotNull(dependencies.retainedFlowCoordinator())
            old.onboarding.completeOnboarding()
            val oldReceipt = checkNotNull(old.onboarding.completionReceipt)
            dependencies.onFlowCompletionHandled(old)
            val replacement = checkNotNull(dependencies.retainedFlowCoordinator())
            replacement.onboarding.completeOnboarding()
            assertSame(oldReceipt, replacement.onboarding.completionReceipt)
            assertNotSame(old, replacement)
            dependencies.onFlowCompletionHandled(old)
            assertTrue(dependencies.ownsFlowCoordinator(replacement))
            val delivered = mutableListOf<com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt>()
            showFlow(dependencies) { delivered += it; true }
            compose.waitForIdle()
            assertEquals(listOf(oldReceipt), delivered)
            assertFalse(dependencies.ownsFlowCoordinator(replacement))
        } finally { processScope.cancel() }
    }

    @Test fun `same mounted rejected receipt retries on explicit delivery key and fresh attempt obtains a fresh owned flow`() =
        runBlocking {
            val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                host.setup().visible()
                val dependencies = onboarding(
                    InMemoryOnboardingFlagStore(), MutableStateFlow(false), processScope,
                ).forRerun()
                val flow = checkNotNull(dependencies.retainedFlowCoordinator())
                flow.onboarding.completeOnboarding()
                val original = checkNotNull(flow.onboarding.completionReceipt)
                var deliveryKey by mutableStateOf(0)
                var attemptKey by mutableStateOf(0)
                var occupiedError = true
                val delivered = mutableListOf<com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt>()
                host.get().setContent {
                    MeshCoreTheme {
                        OnboardingFlow(
                            dependencies, onCompleted = { error("Do not use the Boolean callback") },
                            onCompletionReceipt = { delivered += it; !occupiedError },
                            attemptKey = attemptKey,
                            completionDeliveryKey = deliveryKey,
                        )
                    }
                }
                compose.waitForIdle()
                assertEquals(1, delivered.size)
                assertTrue(dependencies.ownsFlowCoordinator(flow))
                compose.runOnIdle { occupiedError = false; deliveryKey++ }
                compose.waitForIdle()
                assertEquals(2, delivered.size)
                assertSame(original, delivered[0])
                assertSame(original, delivered[1])
                assertFalse(dependencies.ownsFlowCoordinator(flow))
                compose.runOnIdle { attemptKey++ }
                compose.waitForIdle()
                val fresh = checkNotNull(dependencies.retainedFlowCoordinator())
                assertNotSame(flow, fresh)
                assertFalse(fresh.onboarding.hasCompletedOnboarding)
                assertEquals(2, delivered.size)
            } finally { processScope.cancel() }
        }

    @Test fun `rejected delayed preset receipt survives Activity recreation without relabeling and retires only on acceptance`() =
        runBlocking {
            val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                host.setup().visible()
                val original = OnboardingPresetTarget(java.util.UUID.randomUUID(), 1, java.util.UUID.randomUUID())
                var target = original
                val presets = object : OnboardingPresetPort {
                    override val canApply = MutableStateFlow(true)
                    override fun currentTarget() = target
                    override suspend fun apply(presetId: String) = OnboardingPresetOutcome.Applied
                }
                val dependencies = onboarding(
                    InMemoryOnboardingFlagStore(), MutableStateFlow(false), processScope, presets = presets,
                ).forRerun()
                val flow = checkNotNull(dependencies.retainedFlowCoordinator())
                val presetId = flow.preset.visiblePresets().first().id
                flow.preset.apply(presetId)
                assertTrue(flow.onboarding.hasCompletedOnboarding)
                val originalReceipt = checkNotNull(flow.onboarding.completionReceipt)
                target = original.copy(generation = 2, radioId = java.util.UUID.randomUUID())
                val delivered = mutableListOf<com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt>()
                showFlow(dependencies) { delivered += it; false }
                compose.waitForIdle()
                assertEquals(listOf(originalReceipt), delivered)
                assertTrue(dependencies.ownsFlowCoordinator(flow))
                host.recreate()
                showFlow(dependencies) { delivered += it; true }
                compose.waitForIdle()
                assertEquals(2, delivered.size)
                assertSame(originalReceipt, delivered[0])
                assertSame(originalReceipt, delivered[1])
                assertEquals(
                    com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt.Preset(presetId, original),
                    delivered[1],
                )
                assertFalse(dependencies.ownsFlowCoordinator(flow))
                assertFalse(checkNotNull(dependencies.retainedFlowCoordinator()).onboarding.hasCompletedOnboarding)
                compose.waitForIdle()
                assertEquals(2, delivered.size)
            } finally { processScope.cancel() }
        }

    private fun showPermissionRequester(
        dependencies: AppOnboarding,
        registry: ActivityResultRegistry,
        onResult: () -> Unit,
    ) = host.get().setContent {
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            val requestPermission = dependencies.rememberPermissionRequester(onResult)
            Button(
                modifier = Modifier.testTag("request-location-permission"),
                onClick = { requestPermission(OnboardingPermissionKind.LOCATION) },
            ) { Text("Request location") }
        }
    }

    private class DeferredActivityResultRegistry : ActivityResultRegistry() {
        var launchedRequestCode: Int? = null
            private set

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launchedRequestCode = requestCode
        }
    }

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
        val bound = onboarding(flags(completed = true))
        assertTrue(bound.wasCompletedAtLoad)
        showGate(coordinator, bound)
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

    @Test fun `pending denied permission result is restored after Activity recreation`() {
        val reportedLocationResults = mutableListOf<Boolean>()
        val flags = flags(completed = false)
        val dependencies = onboarding(
            flags,
            flags.hasCompleted,
            CoroutineScope(Dispatchers.Unconfined),
            locationReporter = object : LocationPermissionResultReporting {
                override fun reportPermissionResult(granted: Boolean) { reportedLocationResults += granted }
            },
        )
        val firstRegistry = DeferredActivityResultRegistry()
        host.setup().visible()
        showPermissionRequester(dependencies, firstRegistry) {}
        compose.onNodeWithTag("request-location-permission").performClick()
        compose.waitForIdle()
        val requestCode = checkNotNull(firstRegistry.launchedRequestCode)

        host.recreate()
        compose.waitForIdle()
        compose.runOnUiThread {
            assertTrue(firstRegistry.dispatchResult(
                requestCode,
                Activity.RESULT_OK,
                Intent()
                    .putExtra(
                        "androidx.activity.result.contract.extra.PERMISSIONS",
                        arrayOf(OnboardingPermissionMapping.COARSE_LOCATION),
                    )
                    .putExtra(
                        "androidx.activity.result.contract.extra.PERMISSION_GRANT_RESULTS",
                        intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED),
                    ),
            ))
        }

        val registryState = Bundle()
        compose.runOnUiThread { firstRegistry.onSaveInstanceState(registryState) }
        val restoredRegistry = DeferredActivityResultRegistry()
        compose.runOnUiThread { restoredRegistry.onRestoreInstanceState(registryState) }
        showPermissionRequester(dependencies, restoredRegistry) {}
        compose.waitForIdle()

        assertEquals(listOf(false), reportedLocationResults)
        assertEquals(PermissionStatus.DENIED, dependencies.permissions.snapshot.value.location)
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
