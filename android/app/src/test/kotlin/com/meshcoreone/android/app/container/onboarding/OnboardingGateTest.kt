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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        flowCoordinator: com.meshcoreone.android.feature.onboarding.OnboardingFlowCoordinator? = null,
        onPublication: ((
            com.meshcoreone.android.feature.onboarding.OnboardingFlowCoordinator,
            com.meshcoreone.android.feature.onboarding.OnboardingCompletionPublication,
            commit: (() -> Boolean) -> Boolean,
        ) -> Boolean)? = null,
        onReceipt: ((com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt) -> Boolean)? = null,
    ) = host.get().setContent {
        MeshCoreTheme {
            OnboardingFlow(
                dependencies, onCompleted = {}, onCompletionReceipt = onReceipt,
                onCompletionPublication = onPublication,
                flowCoordinator = flowCoordinator,
            )
        }
    }

    private fun awaitPublicationDelivery(deliveries: kotlinx.coroutines.channels.Channel<Unit>) {
        compose.waitUntil(5_000) { deliveries.tryReceive().isSuccess }
        compose.waitForIdle()
    }

    private fun awaitEnabled(node: SemanticsNodeInteraction) {
        compose.waitUntil(5_000) {
            !node.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }
        node.assertIsEnabled()
    }

    @Test fun `completion publication guard rejects equal receipt replacement and survives recreation`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val deliveries = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val publications = mutableListOf<com.meshcoreone.android.feature.onboarding.OnboardingCompletionPublication>()
        val committers = mutableListOf<((() -> Boolean) -> Boolean)>()
        val callbackFlows = mutableListOf<com.meshcoreone.android.feature.onboarding.OnboardingFlowCoordinator>()
        var navigationCommits = 0
        try {
            host.setup().visible()
            val flags = WriteBehindOnboardingFlags(emptyMap(), processScope, { _, _ -> })
            val dependencies = onboarding(flags, flags.hasCompleted, processScope)
            val flow = dependencies.retainedFlowCoordinator()
            val receipt = com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt.WithoutDevice
            val onPublication: (
                com.meshcoreone.android.feature.onboarding.OnboardingFlowCoordinator,
                com.meshcoreone.android.feature.onboarding.OnboardingCompletionPublication,
                ((() -> Boolean) -> Boolean),
            ) -> Boolean = { callbackFlow, publication, commit ->
                callbackFlows += callbackFlow
                publications += publication
                committers += commit
                deliveries.trySend(Unit)
                false
            }
            showFlow(dependencies, flowCoordinator = flow, onPublication = onPublication)

            flow.onboarding.completeOnboardingWithReceipt(receipt, {}) { it.trySeal() }
            awaitPublicationDelivery(deliveries)
            val first = publications.single()
            assertEquals(receipt, first.receipt)

            host.recreate()
            showFlow(dependencies, flowCoordinator = flow, onPublication = onPublication)
            awaitPublicationDelivery(deliveries)
            assertSame(first, publications[1])
            assertEquals(first.revision, publications[1].revision)
            assertSame(flow, callbackFlows[0])
            assertSame(flow, callbackFlows[1])
            flow.onboarding.resetOnboarding()
            flow.onboarding.completeOnboardingWithReceipt(receipt, {}) { it.trySeal() }
            awaitPublicationDelivery(deliveries)
            val replacement = publications.last()
            assertEquals(first.receipt, replacement.receipt)
            assertTrue(replacement.revision > first.revision)
            assertNotSame(first, replacement)
            assertFalse(committers.first().invoke { navigationCommits++; true })
            assertEquals(0, navigationCommits)

            val commitEntered = java.util.concurrent.CountDownLatch(1)
            val releaseCommit = java.util.concurrent.CountDownLatch(1)
            val resetStarted = java.util.concurrent.CountDownLatch(1)
            val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val commit = executor.submit<Boolean> {
                    committers.last().invoke {
                        commitEntered.countDown()
                        check(releaseCommit.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        navigationCommits++
                        true
                    }
                }
                assertTrue(commitEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val reset = executor.submit<Boolean> {
                    resetStarted.countDown()
                    runBlocking { flow.onboarding.resetOnboarding() }
                    true
                }
                assertTrue(resetStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertFalse(reset.isDone)
                releaseCommit.countDown()
                assertTrue(commit.get(5, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(reset.get(5, java.util.concurrent.TimeUnit.SECONDS))
            } finally {
                releaseCommit.countDown()
                executor.shutdownNow()
            }
            assertFalse(flow.onboarding.commitCompletion(replacement) { navigationCommits++; true })
            assertEquals(1, navigationCommits)

            dependencies.onFlowCompletionHandled(flow)
            val replacementFlow = dependencies.retainedFlowCoordinator()
            assertNotSame(flow, replacementFlow)
            showFlow(dependencies, flowCoordinator = replacementFlow, onPublication = onPublication)
            replacementFlow.onboarding.completeOnboardingWithReceipt(receipt, {}) { it.trySeal() }
            awaitPublicationDelivery(deliveries)
            assertSame(replacementFlow, callbackFlows.last())
        } finally {
            deliveries.close()
            processScope.cancel()
        }
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
                        onFinished: (Result<Unit>) -> Unit,
                    ) {
                        flags.persistBoolean(key, value, requireCurrent, seal, attempt)
                        published.complete(Unit)
                        release.await()
                        onFinished(Result.success(Unit))
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

    @Test fun `mounted no-device retry reconciles the original unknown decision without another write`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            host.setup().visible()
            var committedId: String? = null
            var readbackAvailable = false
            val writes = mutableListOf<String>()
            val reads = mutableListOf<String>()
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), processScope, { _, _ -> error("Use the original finalizer") },
                persistCompletion = { id ->
                    writes += id
                    committedId = id
                    throw java.io.IOException("lost acknowledgement")
                },
                reconcileCompletion = { id ->
                    reads += id
                    if (!readbackAvailable) throw java.io.IOException("readback unavailable")
                    committedId == id
                },
            )
            val dependencies = onboarding(flags, flags.hasCompleted, processScope)
            val original = dependencies.retainedFlowCoordinator()
            original.onboarding.replacePath(listOf(OnboardingStep.PAIR))
            original.openPairingSheet(PairSheet.NO_DEVICE)
            val delivered = mutableListOf<com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt>()
            showFlow(dependencies) { delivered += it; true }
            val context = host.get()
            compose.onNodeWithText(context.getString(
                com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings.noDeviceSheetConfirm,
            )).performClick()
            compose.waitForIdle()
            assertTrue(original.hasCompletionError.value)
            assertTrue(delivered.isEmpty())
            assertFalse(flags.hasCompleted.value)
            compose.runOnIdle { readbackAvailable = true }
            compose.onNodeWithText(context.getString(
                com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonTryAgain,
            )).performClick()
            compose.waitForIdle()
            assertEquals(1, writes.size)
            assertEquals(listOf(writes.single(), writes.single()), reads)
            assertTrue(flags.hasCompleted.value)
            assertEquals(
                listOf(com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt.WithoutDevice), delivered,
            )
        } finally { processScope.cancel() }
    }

    @Test fun `mounted no-device action refreshes when cancelled completion settles unknown`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val finalizerEntered = CompletableDeferred<String>()
        val releaseFinalizer = CompletableDeferred<Unit>()
        try {
            host.setup().visible()
            var committedId: String? = null
            var readbackAvailable = false
            val reads = mutableListOf<String>()
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), processScope, { _, _ -> error("Use the original finalizer") },
                persistCompletion = { id ->
                    finalizerEntered.complete(id)
                    releaseFinalizer.await()
                    committedId = id
                    throw java.io.IOException("lost acknowledgement")
                },
                reconcileCompletion = { id ->
                    reads += id
                    if (!readbackAvailable) throw java.io.IOException("readback unavailable")
                    committedId == id
                },
            )
            val dependencies = onboarding(flags, flags.hasCompleted, processScope)
            val flow = dependencies.retainedFlowCoordinator()
            flow.onboarding.replacePath(listOf(OnboardingStep.PAIR))
            flow.openPairingSheet(PairSheet.NO_DEVICE)
            val delivered = mutableListOf<com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt>()
            showFlow(dependencies) { delivered += it; true }
            val confirm = compose.onNodeWithText(host.get().getString(
                com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings.noDeviceSheetConfirm,
            ))
            compose.waitForIdle()

            val pending = async(start = CoroutineStart.UNDISPATCHED) { flow.onboarding.completeOnboarding() }
            val attemptId = finalizerEntered.await()
            pending.cancelAndJoin()
            compose.waitForIdle()
            assertTrue(flow.onboarding.unsettledCompletionFlow.value)
            confirm.assertIsNotEnabled()
            assertTrue(reads.isEmpty())

            releaseFinalizer.complete(Unit)
            flow.onboarding.reconciliationReadyFlow.first { it }
            compose.waitForIdle()
            assertTrue(flow.onboarding.canReconcileCompletion())
            assertTrue(flow.canCompleteWithoutDevice())
            awaitEnabled(confirm)

            compose.runOnIdle { readbackAvailable = true }
            confirm.performClick()
            compose.waitForIdle()
            assertEquals(listOf(attemptId, attemptId), reads)
            assertEquals(
                listOf(com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt.WithoutDevice),
                delivered,
            )
        } finally {
            releaseFinalizer.complete(Unit)
            processScope.cancel()
        }
    }

    @Test fun `mounted preset action refreshes when cancelled completion is known uncommitted`() = runBlocking<Unit> {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val finalizerEntered = CompletableDeferred<String>()
        val releaseFinalizer = CompletableDeferred<Unit>()
        try {
            host.setup().visible()
            val reads = mutableListOf<String>()
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), processScope, { _, _ -> error("Use the original finalizer") },
                persistCompletion = { id ->
                    finalizerEntered.complete(id)
                    releaseFinalizer.await()
                    throw java.io.IOException("finalizer did not commit")
                },
                reconcileCompletion = { id -> reads += id; false },
            )
            val dependencies = onboarding(flags, flags.hasCompleted, processScope)
            val flow = dependencies.retainedFlowCoordinator()
            flow.onboarding.replacePath(listOf(OnboardingStep.PRESET))
            val preset = flow.preset.visiblePresets().first()
            flow.preset.select(preset.id)
            showFlow(dependencies)
            val apply = compose.onNodeWithText(
                com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings.presetUse(
                    host.get().resources, preset.name,
                ),
            )
            compose.waitForIdle()
            apply.assertIsEnabled()

            val pending = async(start = CoroutineStart.UNDISPATCHED) { flow.onboarding.completeOnboarding() }
            val attemptId = finalizerEntered.await()
            pending.cancelAndJoin()
            compose.waitForIdle()
            assertTrue(flow.onboarding.unsettledCompletionFlow.value)
            apply.assertIsNotEnabled()

            releaseFinalizer.complete(Unit)
            flow.onboarding.unsettledCompletionFlow.first { !it }
            compose.waitForIdle()
            assertEquals(listOf(attemptId), reads)
            assertFalse(flags.hasCompleted.value)
            assertTrue(flow.onboarding.canStartCompletion())
            assertTrue(flow.preset.canApply(dependencies.presets.canApply.value, flow.preset.state.value))
            awaitEnabled(apply)
        } finally {
            releaseFinalizer.complete(Unit)
            processScope.cancel()
        }
    }

    @Test fun `mounted preset retry reads the original sealed receipt even after its radio is replaced`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            host.setup().visible()
            val writes = mutableListOf<String>()
            val reads = mutableListOf<String>()
            var readbackAvailable = false
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), processScope, { _, _ -> error("Use the original finalizer") },
                persistCompletion = { id -> writes += id; throw java.io.IOException("lost acknowledgement") },
                reconcileCompletion = { id ->
                    reads += id
                    if (!readbackAvailable) throw java.io.IOException("readback unavailable")
                    true
                },
            )
            val first = OnboardingPresetTarget(java.util.UUID.randomUUID(), 1, java.util.UUID.randomUUID())
            var target = first
            var applies = 0
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
            flow.onboarding.replacePath(listOf(OnboardingStep.PRESET))
            val id = flow.preset.visiblePresets().first().id
            val delivered = mutableListOf<com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt>()
            showFlow(dependencies) { delivered += it; true }
            compose.runOnIdle { flow.preset.apply(id) }
            compose.waitForIdle()
            assertTrue(flow.preset.state.value.completionNeedsRetry)
            compose.runOnIdle {
                target = first.copy(generation = 2, radioId = java.util.UUID.randomUUID())
                presets.canApply.value = false
                readbackAvailable = true
            }
            compose.onNode(hasText(host.get().getString(
                com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonTryAgain,
            )) and hasAnyAncestor(isDialog())).performClick()
            compose.waitForIdle()
            assertEquals(1, applies)
            assertEquals(1, writes.size)
            assertEquals(listOf(writes.single(), writes.single()), reads)
            assertEquals(
                listOf(com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt.Preset(id, first)),
                delivered,
            )
            assertTrue(flags.hasCompleted.value)
        } finally { processScope.cancel() }
    }

    @Test fun `dismissed preset error keeps mounted no-radio no-selection original readback available`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            host.setup().visible()
            var readbackAvailable = false
            var applies = 0
            val writes = mutableListOf<String>()
            val reads = mutableListOf<String>()
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), processScope, { _, _ -> error("Use the original finalizer") },
                persistCompletion = { id -> writes += id; throw java.io.IOException("lost acknowledgement") },
                reconcileCompletion = { id ->
                    reads += id
                    if (!readbackAvailable) throw java.io.IOException("readback unavailable")
                    true
                },
            )
            val first = OnboardingPresetTarget(java.util.UUID.randomUUID(), 1, java.util.UUID.randomUUID())
            var target: OnboardingPresetTarget? = first
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
            flow.onboarding.replacePath(listOf(OnboardingStep.PRESET))
            val id = flow.preset.visiblePresets().first().id
            val delivered = mutableListOf<com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt>()
            showFlow(dependencies) { delivered += it; true }
            compose.runOnIdle { flow.preset.apply(id) }
            compose.waitForIdle()
            assertEquals(null, flow.preset.state.value.selectedId)
            assertTrue(flow.preset.state.value.error != null)
            compose.runOnIdle {
                target = null
                presets.canApply.value = false
                org.robolectric.shadows.ShadowDialog.getLatestDialog().onBackPressed()
            }
            compose.waitForIdle()
            assertEquals(null, flow.preset.state.value.error)
            assertFalse(flow.back())
            assertTrue(flow.onboarding.canReconcileCompletion())
            compose.runOnIdle { readbackAvailable = true }
            compose.onNodeWithText(host.get().getString(
                com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonTryAgain,
            )).assertIsEnabled().performClick()
            compose.waitForIdle()
            assertEquals(1, applies)
            assertEquals(1, writes.size)
            assertEquals(listOf(writes.single(), writes.single()), reads)
            assertEquals(
                listOf(com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt.Preset(id, first)),
                delivered,
            )
        } finally { processScope.cancel() }
    }

    @Test fun `retiring an owner during admitted reset retains custody until the actual false write settles`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        try {
            val entered = CompletableDeferred<Unit>()
            val writes = mutableListOf<Boolean>()
            var durable = false
            val flags = WriteBehindOnboardingFlags(emptyMap(), processScope, { _, value ->
                writes += value
                durable = value
            })
            val delayed = object : OnboardingFlagStore by flags {
                override suspend fun persistBoolean(key: String, value: Boolean) {
                    if (!value) {
                        entered.complete(Unit)
                        release.await()
                    }
                    flags.persistBoolean(key, value)
                }
            }
            val dependencies = onboarding(delayed, flags.hasCompleted, processScope)
            val old = dependencies.retainedFlowCoordinator()
            val resetting = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { old.onboarding.resetOnboarding() }
            }
            entered.await()
            dependencies.onFlowCompletionHandled(old)
            assertTrue(old.onboarding.hasUnsettledCompletion)
            assertSame(old, dependencies.retainedFlowCoordinator())
            assertFalse(dependencies.ownsFlowCoordinator(old))
            resetting.cancel()
            assertTrue(old.onboarding.hasUnsettledCompletion)
            release.complete(Unit)
            resetting.join()
            assertFalse(old.onboarding.hasUnsettledCompletion)
            val fresh = dependencies.retainedFlowCoordinator()
            assertNotSame(old, fresh)
            fresh.onboarding.completeOnboarding()
            assertEquals(listOf(false, true), writes)
            assertTrue(durable)
            assertTrue(flags.hasCompleted.value)
            assertTrue(fresh.onboarding.hasCompletedOnboarding)
            assertEquals(null, old.onboarding.completionReceipt)
        } finally { release.complete(Unit); processScope.cancel() }
    }

    @Test fun `acknowledged retired reset settles the matching previously unknown sealed attempt`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        try {
            val entered = CompletableDeferred<Unit>()
            val writes = mutableListOf<Boolean>()
            var first = true
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), processScope, { _, value -> writes += value },
                persistCompletion = {
                    if (first) { first = false; throw java.io.IOException("unknown commit") }
                    writes += true
                },
                reconcileCompletion = { throw java.io.IOException("readback unavailable") },
            )
            val delayed = object : OnboardingFlagStore by flags {
                override suspend fun persistBoolean(key: String, value: Boolean) {
                    entered.complete(Unit)
                    release.await()
                    flags.persistBoolean(key, value)
                }
            }
            val dependencies = onboarding(delayed, flags.hasCompleted, processScope)
            val old = dependencies.retainedFlowCoordinator()
            val initial = runCatching { old.onboarding.completeOnboarding() }
            assertTrue(initial.exceptionOrNull() is OnboardingCompletionOutcomeUnknownException)
            assertTrue(old.onboarding.hasUnsettledCompletion)
            val resetting = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { old.onboarding.resetOnboarding() }
            }
            entered.await()
            dependencies.onFlowCompletionHandled(old)
            assertSame(old, dependencies.retainedFlowCoordinator())
            release.complete(Unit)
            assertTrue(resetting.await().isFailure)
            assertFalse(old.onboarding.hasUnsettledCompletion)
            val fresh = dependencies.retainedFlowCoordinator()
            assertNotSame(old, fresh)
            fresh.onboarding.completeOnboarding()
            assertEquals(listOf(false, true), writes)
            assertTrue(fresh.onboarding.hasCompletedOnboarding)
            assertEquals(null, old.onboarding.completionReceipt)
        } finally { release.complete(Unit); processScope.cancel() }
    }

    @Test fun `mounted recovery observes late unknown outcome after its sealed child was cancelled`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        try {
            host.setup().visible()
            val entered = CompletableDeferred<Unit>()
            var child: kotlinx.coroutines.Job? = null
            var readbackAvailable = false
            var applies = 0
            val writes = mutableListOf<String>()
            val reads = mutableListOf<String>()
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), processScope, { _, _ -> error("Use original finalizer") },
                persistCompletion = { id ->
                    writes += id
                    entered.complete(Unit)
                    release.await()
                    throw java.io.IOException("lost acknowledgement")
                },
                reconcileCompletion = { id ->
                    reads += id
                    if (!readbackAvailable) throw java.io.IOException("readback unavailable")
                    true
                },
            )
            val capturing = object : OnboardingFlagStore by flags {
                override suspend fun persistBoolean(
                    key: String, value: Boolean, requireCurrent: () -> Unit,
                    seal: (com.meshcoreone.android.core.contracts.domain.OnboardingCompletionAttempt) -> Boolean,
                    attempt: com.meshcoreone.android.core.contracts.domain.OnboardingCompletionAttempt,
                    onFinished: (Result<Unit>) -> Unit,
                ) {
                    child = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                    flags.persistBoolean(key, value, requireCurrent, seal, attempt, onFinished)
                }
            }
            val target = OnboardingPresetTarget(java.util.UUID.randomUUID(), 1, java.util.UUID.randomUUID())
            val presets = object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(true)
                override fun currentTarget() = target
                override suspend fun apply(presetId: String): OnboardingPresetOutcome {
                    applies++
                    return OnboardingPresetOutcome.Applied
                }
            }
            val dependencies = onboarding(capturing, flags.hasCompleted, processScope, presets = presets)
            val flow = dependencies.retainedFlowCoordinator()
            flow.onboarding.replacePath(listOf(OnboardingStep.PRESET))
            val id = flow.preset.visiblePresets().first().id
            val delivered = mutableListOf<com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt>()
            showFlow(dependencies) { delivered += it; true }
            compose.runOnIdle { flow.preset.apply(id) }
            entered.await()
            checkNotNull(child).cancel()
            checkNotNull(child).join()
            compose.runOnIdle { presets.canApply.value = false }
            compose.waitForIdle()
            assertFalse(flow.preset.state.value.isApplying)
            assertFalse(flow.onboarding.reconciliationReadyFlow.value)
            release.complete(Unit)
            flow.onboarding.reconciliationReadyFlow.first { it }
            compose.waitForIdle()
            compose.runOnIdle { readbackAvailable = true }
            compose.onNodeWithText(host.get().getString(
                com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonTryAgain,
            )).assertIsEnabled().performClick()
            compose.waitForIdle()
            assertEquals(1, applies)
            assertEquals(1, writes.size)
            assertEquals(listOf(writes.single(), writes.single()), reads)
            assertEquals(
                listOf(com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt.Preset(id, target)),
                delivered,
            )
        } finally { release.complete(Unit); processScope.cancel() }
    }

    @Test fun `still-mounted explicitly retired owner is replaced after known-uncommitted finalization`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        try {
            host.setup().visible()
            val entered = CompletableDeferred<Unit>()
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), processScope, { _, _ -> },
                persistCompletion = {
                    entered.complete(Unit)
                    release.await()
                    throw java.io.IOException("not committed")
                },
                reconcileCompletion = { false },
            )
            val dependencies = onboarding(flags, flags.hasCompleted, processScope)
            val old = dependencies.retainedFlowCoordinator()
            showFlow(dependencies)
            val completing = async { runCatching { old.onboarding.completeOnboarding() } }
            entered.await()
            dependencies.onFlowCompletionHandled(old)
            release.complete(Unit)
            assertTrue(completing.await().isFailure)
            compose.waitForIdle()
            compose.onNodeWithText(host.get().getString(
                com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings.welcomeGetStarted,
            )).performClick()
            compose.waitForIdle()
            val fresh = dependencies.retainedFlowCoordinator()
            assertNotSame(old, fresh)
            assertEquals(listOf(OnboardingStep.PERMISSIONS), fresh.onboarding.onboardingPath)
            assertEquals(emptyList<OnboardingStep>(), old.onboarding.onboardingPath)
            assertTrue(fresh.onboarding.canStartCompletion())
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

    @Test fun `retiring a sealed first-run owner fences replacement until durable snapshot publication`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        try {
            host.setup().visible()
            val finalizing = CompletableDeferred<Unit>()
            var durable = false
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), processScope, { _, value -> durable = value },
                persistCompletion = { finalizing.complete(Unit); release.await(); durable = true },
            )
            lateinit var dependencies: AppOnboarding
            var callbackReplacement: com.meshcoreone.android.feature.onboarding.OnboardingFlowCoordinator? = null
            val observed = object : OnboardingFlagStore by flags {
                override suspend fun persistBoolean(
                    key: String, value: Boolean, requireCurrent: () -> Unit,
                    seal: (com.meshcoreone.android.core.contracts.domain.OnboardingCompletionAttempt) -> Boolean,
                    attempt: com.meshcoreone.android.core.contracts.domain.OnboardingCompletionAttempt,
                    onFinished: (Result<Unit>) -> Unit,
                ) = flags.persistBoolean(key, value, requireCurrent, seal, attempt) { result ->
                    onFinished(result)
                    callbackReplacement = dependencies.retainedFlowCoordinator()
                }
            }
            dependencies = onboarding(observed, flags.hasCompleted, processScope)
            val old = dependencies.retainedFlowCoordinator()
            val completion = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { old.onboarding.completeOnboarding() }
            }
            finalizing.await()
            dependencies.onFlowCompletionHandled(old)
            assertFalse(dependencies.ownsFlowCoordinator(old))
            assertSame(old, dependencies.retainedFlowCoordinator())
            assertFalse(old.onboarding.canStartCompletion())
            assertFalse(flags.hasCompleted.value)
            release.complete(Unit)
            assertTrue(completion.await().isFailure)
            assertTrue(durable)
            val fresh = dependencies.retainedFlowCoordinator()
            assertSame(callbackReplacement, fresh)
            assertNotSame(old, fresh)
            assertTrue(dependencies.ownsFlowCoordinator(fresh))
            assertTrue(fresh.onboarding.hasCompletedOnboarding)
            assertEquals(null, old.onboarding.completionReceipt)
            assertFalse(dependencies.wasCompletedAtLoad)
            assertFalse(fresh.onboarding.canStartCompletion())
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

    @Test fun `reset fences a held radio apply without clearing a newer apply or publishing its receipt`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val oldRelease = CompletableDeferred<Unit>()
        val freshRelease = CompletableDeferred<Unit>()
        try {
            host.setup().visible()
            var applies = 0
            var completions = 0
            val durable = mutableListOf<Boolean>()
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), processScope, { _, value -> durable += value },
                persistCompletion = { completions++; durable += true },
            )
            val presets = object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(true)
                override suspend fun apply(presetId: String): OnboardingPresetOutcome {
                    when (++applies) {
                        1 -> oldRelease.await()
                        2 -> freshRelease.await()
                    }
                    return OnboardingPresetOutcome.Applied
                }
            }
            val dependencies = onboarding(flags, flags.hasCompleted, processScope, presets = presets)
            val flow = dependencies.retainedFlowCoordinator()
            val id = flow.preset.visiblePresets().first().id
            flow.preset.apply(id)
            assertTrue(flow.preset.state.value.isApplying)
            flow.onboarding.resetOnboarding()
            assertEquals(listOf(false), durable)
            assertFalse(flow.preset.state.value.isApplying)
            flow.preset.select(id)
            flow.preset.apply()
            assertEquals(2, applies)
            assertTrue(flow.preset.state.value.isApplying)
            oldRelease.complete(Unit)
            assertTrue(flow.preset.state.value.isApplying)
            assertEquals(0, completions)
            assertEquals(null, flow.onboarding.completionReceipt)
            assertFalse(flags.hasCompleted.value)
            freshRelease.complete(Unit)
            assertEquals(1, completions)
            assertTrue(flow.preset.state.value.isCompleted)
            assertEquals(1, flow.preset.state.value.commitTick)
            assertEquals(
                com.meshcoreone.android.feature.onboarding.OnboardingCompletionReceipt.Preset(id, null),
                flow.onboarding.completionReceipt,
            )
            assertEquals(listOf(false, true), durable)
        } finally { oldRelease.complete(Unit); freshRelease.complete(Unit); processScope.cancel() }
    }

    @Test fun `acknowledged explicit reset unlocks the retained completed preset for a genuine new apply`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val resetRelease = CompletableDeferred<Unit>()
        try {
            host.setup().visible()
            var applies = 0
            var completions = 0
            val flags = WriteBehindOnboardingFlags(
                emptyMap(), processScope, { _, value -> if (!value) resetRelease.await() },
                persistCompletion = { completions++ },
            )
            val presets = object : OnboardingPresetPort {
                override val canApply = MutableStateFlow(true)
                override suspend fun apply(presetId: String): OnboardingPresetOutcome {
                    applies++
                    return OnboardingPresetOutcome.Applied
                }
            }
            val flow = onboarding(flags, flags.hasCompleted, processScope, presets = presets).retainedFlowCoordinator()
            val id = flow.preset.visiblePresets().first().id
            flow.preset.apply(id)
            val original = flow.onboarding.completionReceipt
            assertTrue(flow.preset.state.value.isCompleted)
            val resetting = processScope.async { flow.onboarding.resetOnboarding() }
            assertFalse(resetting.isCompleted)
            flow.preset.apply(id)
            assertEquals(1, applies)
            assertEquals(1, completions)
            resetRelease.complete(Unit)
            resetting.await()
            assertFalse(flow.preset.state.value.isCompleted)
            assertEquals(null, flow.preset.state.value.selectedId)
            assertFalse(flow.preset.state.value.completionNeedsRetry)
            flow.preset.select(id)
            assertTrue(flow.preset.canApply(true, flow.preset.state.value))
            flow.preset.apply()
            assertEquals(2, applies)
            assertEquals(2, completions)
            assertTrue(flow.preset.state.value.isCompleted)
            assertTrue(flow.onboarding.hasCompletedOnboarding)
            assertNotSame(original, flow.onboarding.completionReceipt)
        } finally { resetRelease.complete(Unit); processScope.cancel() }
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

            nextRerunFlow.getStarted()
            val revision = checkNotNull(rerun.flowCoordinatorRevision).value
            rerun.onFlowCoordinatorEntryRemoved(nextRerunFlow)
            assertFalse(rerun.ownsFlowCoordinator(nextRerunFlow))
            assertFalse(nextRerunFlow.onboarding.canStartCompletion())
            val afterRemoval = checkNotNull(rerun.retainedFlowCoordinator())
            assertNotSame(nextRerunFlow, afterRemoval)
            assertTrue(afterRemoval.onboarding.onboardingPath.isEmpty())
            assertEquals(revision + 1, checkNotNull(rerun.flowCoordinatorRevision).value)

            rerun.onFlowCoordinatorEntryRemoved(nextRerunFlow)
            assertSame(afterRemoval, rerun.retainedFlowCoordinator())
            assertEquals(revision + 1, checkNotNull(rerun.flowCoordinatorRevision).value)
        } finally {
            processScope.cancel()
        }
    }

    @Test fun `Settings rerun entry removal replaces flow while sealed completion settles`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sealed = CompletableDeferred<Unit>()
        val releaseSeal = CountDownLatch(1)
        val presetTarget = OnboardingPresetTarget(
            java.util.UUID.randomUUID(), 1, java.util.UUID.randomUUID(),
        )
        val presets = object : OnboardingPresetPort {
            override val canApply = MutableStateFlow(true)
            override fun currentTarget() = presetTarget
            override fun sealCompletion(
                completionTarget: OnboardingPresetTarget?,
                attempt: com.meshcoreone.android.core.contracts.domain.OnboardingCompletionAttempt,
            ): Boolean {
                if (completionTarget != presetTarget || !attempt.trySeal()) return false
                sealed.complete(Unit)
                check(releaseSeal.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release sealed completion" }
                return true
            }
            override suspend fun apply(presetId: String) = OnboardingPresetOutcome.Applied
        }
        try {
            host.setup().visible()
            val baseFlags = flags(completed = true)
            val base = onboarding(baseFlags, baseFlags.hasCompleted, processScope, presets = presets)
            val rerun = base.forRerun()
            val oldFlow = checkNotNull(rerun.retainedFlowCoordinator())
            oldFlow.onboarding.replacePath(listOf(OnboardingStep.PRESET))
            val presetId = oldFlow.preset.visiblePresets().first().id
            oldFlow.preset.select(presetId)

            val applying = async(Dispatchers.Default) { oldFlow.preset.apply(presetId) }
            withTimeout(5_000) { sealed.await() }

            val revision = checkNotNull(rerun.flowCoordinatorRevision).value
            rerun.onFlowCoordinatorEntryRemoved(oldFlow)
            assertFalse(rerun.ownsFlowCoordinator(oldFlow))
            assertTrue(oldFlow.onboarding.hasUnsettledCompletion)

            val replacement = checkNotNull(rerun.retainedFlowCoordinator())
            assertNotSame(oldFlow, replacement)
            assertFalse(replacement.onboarding.hasCompletedOnboarding)
            assertEquals(revision + 1, checkNotNull(rerun.flowCoordinatorRevision).value)
            showFlow(rerun)
            compose.waitForIdle()
            assertSame(replacement, rerun.retainedFlowCoordinator())

            releaseSeal.countDown()
            withTimeout(5_000) {
                oldFlow.onboarding.unsettledCompletionFlow.first { !it }
            }
            applying.await()
            assertFalse(oldFlow.onboarding.hasCompletedOnboarding)
            assertFalse(replacement.onboarding.hasCompletedOnboarding)
            rerun.onFlowCoordinatorEntryRemoved(oldFlow)
            assertSame(replacement, rerun.retainedFlowCoordinator())
        } finally {
            releaseSeal.countDown()
            processScope.cancel()
        }
    }

    @Test fun `overlapping Settings entries keep delayed completion and retirement isolated`() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sealed = CompletableDeferred<Unit>()
        val releaseSeal = CountDownLatch(1)
        val target = OnboardingPresetTarget(java.util.UUID.randomUUID(), 1, java.util.UUID.randomUUID())
        val presets = object : OnboardingPresetPort {
            override val canApply = MutableStateFlow(true)
            override fun currentTarget() = target
            override fun sealCompletion(
                completionTarget: OnboardingPresetTarget?,
                attempt: com.meshcoreone.android.core.contracts.domain.OnboardingCompletionAttempt,
            ): Boolean {
                if (completionTarget != target || !attempt.trySeal()) return false
                sealed.complete(Unit)
                check(releaseSeal.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release sealed completion" }
                return true
            }
            override suspend fun apply(presetId: String) = OnboardingPresetOutcome.Applied
        }
        var showFirst by mutableStateOf(true)
        var showSecond by mutableStateOf(false)
        val publicationFlowsA = mutableListOf<com.meshcoreone.android.feature.onboarding.OnboardingFlowCoordinator>()
        val publicationFlowsB = mutableListOf<com.meshcoreone.android.feature.onboarding.OnboardingFlowCoordinator>()
        val publicationDeliveredA = CompletableDeferred<Unit>()
        try {
            host.setup().visible()
            val baseFlags = flags(completed = true)
            val base = onboarding(baseFlags, baseFlags.hasCompleted, processScope, presets = presets)
            val ownerA = base.createRerunOwner()
            val ownerB = base.createRerunOwner()
            assertNotSame(ownerA, ownerB)
            val flowA = checkNotNull(ownerA.retainedFlowCoordinator())
            val flowB = checkNotNull(ownerB.retainedFlowCoordinator())
            assertNotSame(flowA, flowB)

            flowA.onboarding.replacePath(listOf(OnboardingStep.PRESET))
            val presetId = flowA.preset.visiblePresets().first().id
            flowA.preset.select(presetId)

            fun mountEntries() {
                host.get().setContent {
                    MeshCoreTheme {
                        Box {
                            if (showFirst) {
                                OnboardingFlow(
                                    ownerA,
                                    onCompleted = {},
                                    modifier = Modifier.testTag("settings-entry-a"),
                                    onCompletionPublication = { callbackFlow, _, commit ->
                                        publicationFlowsA += callbackFlow
                                        publicationDeliveredA.complete(Unit)
                                        commit { false }
                                    },
                                    flowCoordinator = flowA,
                                )
                            }
                            if (showSecond) {
                                OnboardingFlow(
                                    ownerB,
                                    onCompleted = {},
                                    modifier = Modifier.testTag("settings-entry-b"),
                                    onCompletionPublication = { callbackFlow, _, commit ->
                                        publicationFlowsB += callbackFlow
                                        commit { false }
                                    },
                                    flowCoordinator = flowB,
                                )
                            }
                        }
                    }
                }
            }

            mountEntries()
            compose.waitForIdle()
            val applying = async(Dispatchers.Default) { flowA.preset.apply(presetId) }
            withTimeout(5_000) { sealed.await() }

            compose.runOnIdle { showSecond = true }
            compose.waitForIdle()
            compose.onNodeWithTag("settings-entry-a").assertIsDisplayed()
            compose.onNodeWithTag("settings-entry-b").assertIsDisplayed()
            assertTrue(publicationFlowsA.isEmpty())
            assertTrue(publicationFlowsB.isEmpty())
            host.recreate()
            mountEntries()
            compose.waitForIdle()
            compose.onNodeWithTag("settings-entry-a").assertIsDisplayed()
            compose.onNodeWithTag("settings-entry-b").assertIsDisplayed()
            assertSame(flowA, ownerA.retainedFlowCoordinator())
            assertSame(flowB, ownerB.retainedFlowCoordinator())

            releaseSeal.countDown()
            compose.waitUntil(5_000) { publicationDeliveredA.isCompleted }
            compose.waitForIdle()
            applying.await()

            assertEquals(listOf(flowA), publicationFlowsA)
            assertTrue(publicationFlowsB.isEmpty())
            assertTrue(ownerA.ownsFlowCoordinator(flowA))
            assertTrue(ownerB.ownsFlowCoordinator(flowB))
            assertFalse(flowB.onboarding.hasCompletedOnboarding)

            compose.runOnIdle { showFirst = false }
            compose.waitForIdle()
            ownerA.onFlowCoordinatorEntryRemoved(flowA)
            assertFalse(ownerA.ownsFlowCoordinator(flowA))
            assertTrue(ownerB.ownsFlowCoordinator(flowB))
            assertSame(flowB, ownerB.retainedFlowCoordinator())
            assertFalse(flowB.onboarding.hasCompletedOnboarding)
        } finally {
            releaseSeal.countDown()
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
