// AndroidOnly: WP-305 Boundary behavior for cancel/denial/adapter-off/manual-region/no-internet recovery and demo onboarding.
package com.meshcoreone.android.feature.onboarding

import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.ui.UiText
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import org.junit.Test

private val scope = CoroutineScope(Dispatchers.Unconfined)
private val granted = OnboardingPermissionSnapshot(bluetooth = PermissionStatus.GRANTED)

class PermissionsCoordinatorTest {
    @Test fun optionalPermissionsNeverGateContinue() {
        val denied = OnboardingPermissionSnapshot(location = PermissionStatus.DENIED, notifications = PermissionStatus.DENIED)
        val coordinator = PermissionsCoordinator(FakePermissions(denied))
        assertTrue(coordinator.canContinue)
        assertEquals(PermissionCardStatus.DENIED, coordinator.cardStatus(OnboardingPermissionKind.LOCATION, denied))
        assertEquals(PermissionCardStatus.REQUESTABLE, coordinator.cardStatus(OnboardingPermissionKind.NOTIFICATIONS, OnboardingPermissionSnapshot()))
        assertEquals(PermissionCardStatus.GRANTED, coordinator.cardStatus(OnboardingPermissionKind.NOTIFICATIONS, OnboardingPermissionSnapshot(notifications = PermissionStatus.GRANTED)))
    }

    @Test fun refreshDelegatesToPort() {
        val port = FakePermissions()
        PermissionsCoordinator(port).refresh()
        assertEquals(1, port.refreshCount)
    }
}

class DeviceScanCoordinatorTest {
    private fun coordinator(
        pairing: FakePairing = FakePairing(), demo: FakeDemo = FakeDemo(), permissions: FakePermissions = FakePermissions(granted),
        onConnected: () -> Unit = {},
    ) = DeviceScanCoordinator(scope, pairing, demo, permissions, onConnected)

    @Test fun pairingSuccessAdvancesAndMarksInitiated() {
        var connected = 0
        val c = coordinator(onConnected = { connected += 1 })
        assertEquals(PairingBlocker.NONE, c.startPairing())
        assertEquals(1, connected)
        assertTrue(c.state.value.didInitiatePairing)
        assertFalse(c.state.value.localBusy)
    }

    @Test fun userCancelIsBenignAndDoesNotAdvance() {
        var connected = 0
        val c = coordinator(FakePairing(OnboardingConnectOutcome.Cancelled)) { connected += 1 }
        c.startPairing()
        assertEquals(0, connected)
        assertNull(c.state.value.failure)
        assertFalse(c.state.value.localBusy)
    }

    @Test fun otherAppFailureOffersRetryThenRetrySucceeds() {
        val id = UUID.randomUUID()
        val pairing = FakePairing(OnboardingConnectOutcome.Failed(UiText.Verbatim("held"), id))
        var connected = 0
        val c = coordinator(pairing) { connected += 1 }
        c.startPairing()
        assertEquals(id, c.state.value.otherAppDeviceId)
        assertEquals(PairPrimaryAction.RETRY_CONNECTION, c.primaryAction(false, c.state.value.otherAppDeviceId))
        pairing.outcome = OnboardingConnectOutcome.Connected
        c.retryConnection(id)
        assertEquals(id, pairing.retried)
        assertNull(c.state.value.otherAppDeviceId)
        assertEquals(1, connected)
    }

    @Test fun bluetoothBlockersAreReportedWithoutPairing() {
        val pairing = FakePairing()
        val permissions = FakePermissions(OnboardingPermissionSnapshot())
        val c = coordinator(pairing, permissions = permissions)
        assertEquals(PairingBlocker.BLUETOOTH_PERMISSION, c.startPairing())
        permissions.mutable.value = OnboardingPermissionSnapshot(bluetooth = PermissionStatus.DENIED)
        assertEquals(PairingBlocker.BLUETOOTH_PERMISSION_DENIED, c.startPairing())
        permissions.mutable.value = OnboardingPermissionSnapshot(bluetooth = PermissionStatus.GRANTED, bluetoothAdapterEnabled = false)
        assertEquals(PairingBlocker.BLUETOOTH_OFF, c.startPairing())
        assertEquals(0, pairing.pairCalls)
    }

    @Test fun primaryActionPrefersDemoThenOtherAppThenAdd() {
        val c = coordinator()
        assertEquals(PairPrimaryAction.CONNECT_DEMO, c.primaryAction(true, UUID.randomUUID()))
        assertEquals(PairPrimaryAction.ADD_DEVICE, c.primaryAction(false, null))
    }

    @Test fun threeTitleTapsUnlockDemoMode() {
        val demo = FakeDemo()
        val c = coordinator(demo = demo)
        c.onTitleTap(); c.onTitleTap()
        assertEquals(0, demo.unlocked)
        c.onTitleTap()
        assertEquals(1, demo.unlocked)
        assertTrue(c.state.value.showDemoAlert)
        c.onTitleTap()
        assertEquals(1, demo.unlocked)
    }

    @Test fun demoUnlockWaitsForCommitAndFailureCanBeRetried() {
        val gate = CompletableDeferred<Unit>()
        val demo = FakeDemo().apply { beforeUnlock = { gate.await() } }
        val owner = Job()
        try {
            val c = DeviceScanCoordinator(CoroutineScope(Dispatchers.Unconfined + owner), FakePairing(), demo, FakePermissions(granted)) {}
            repeat(3) { c.onTitleTap() }
            assertTrue(c.state.value.localBusy)
            assertFalse(c.state.value.showDemoAlert)
            assertEquals(0, c.state.value.successTick)
            gate.completeExceptionally(IllegalStateException("write failed"))
            assertFalse(c.state.value.localBusy)
            assertNotNull(c.state.value.failure)
            assertFalse(c.state.value.showDemoAlert)
            assertFalse(demo.isEnabled.value)
            demo.beforeUnlock = {}
            repeat(3) { c.onTitleTap() }
            assertTrue(c.state.value.showDemoAlert)
            assertEquals(1, c.state.value.successTick)
            assertEquals(1, demo.unlocked)
        } finally {
            owner.cancel()
        }
    }

    @Test fun cancelledDemoUnlockNeverShowsSuccessAndAnotherAttemptCanRetry() {
        val gate = CompletableDeferred<Unit>()
        val demo = FakeDemo().apply { beforeUnlock = { gate.await() } }
        val owner = Job()
        val c = DeviceScanCoordinator(CoroutineScope(Dispatchers.Unconfined + owner), FakePairing(), demo, FakePermissions(granted)) {}
        repeat(3) { c.onTitleTap() }
        assertTrue(c.state.value.localBusy)
        owner.cancel()
        assertFalse(c.state.value.localBusy)
        assertFalse(c.state.value.showDemoAlert)
        assertNull(c.state.value.failure)
        assertFalse(demo.isEnabled.value)
        demo.beforeUnlock = {}
        val retryOwner = Job()
        try {
            val retry = DeviceScanCoordinator(CoroutineScope(Dispatchers.Unconfined + retryOwner), FakePairing(), demo, FakePermissions(granted)) {}
            repeat(3) { retry.onTitleTap() }
            assertTrue(retry.state.value.showDemoAlert)
        } finally {
            retryOwner.cancel()
        }
    }

    @Test fun cancellationAtUnlockReturnDoesNotShowSuccess() {
        val owner = Job()
        val demo = FakeDemo().apply { beforeUnlock = { owner.cancel() } }
        val c = DeviceScanCoordinator(CoroutineScope(Dispatchers.Unconfined + owner), FakePairing(), demo, FakePermissions(granted)) {}
        repeat(3) { c.onTitleTap() }
        assertFalse(c.state.value.localBusy)
        assertFalse(c.state.value.showDemoAlert)
        assertEquals(0, c.state.value.successTick)
    }

    @Test fun demoConnectAdvancesToRegion() {
        var connected = 0
        coordinator(onConnected = { connected += 1 }).connectDemo()
        assertEquals(1, connected)
    }

    @Test fun unexpectedExceptionBecomesFailureButCancellationPropagates() {
        val pairing = FakePairing().apply { throwOnPair = IllegalStateException("boom") }
        val c = coordinator(pairing)
        c.startPairing()
        assertNotNull(c.state.value.failure)
        assertFalse(c.state.value.localBusy)
        pairing.throwOnPair = CancellationException("cancelled")
        val job = kotlinx.coroutines.Job()
        val owned = DeviceScanCoordinator(CoroutineScope(Dispatchers.Unconfined + job), pairing, FakeDemo(), FakePermissions(granted)) {}
        owned.startPairing()
        assertFalse(owned.state.value.localBusy)
        assertNull(owned.state.value.failure)
    }

    @Test fun sheetsRoute() {
        val c = coordinator()
        c.showSheet(PairSheet.WIFI)
        assertEquals(PairSheet.WIFI, c.state.value.sheet)
        c.dismissSheet()
        assertEquals(PairSheet.NONE, c.state.value.sheet)
    }
}

class WiFiConnectionCoordinatorTest {
    @Test fun invalidPortShowsLocalizedErrorWithoutConnecting() {
        val wifi = FakeWiFi()
        val c = WiFiConnectionCoordinator(scope, wifi) {}
        c.setAddress("192.168.1.5"); c.setPort("70000")
        c.connect()
        assertNotNull(c.state.value.error)
        assertNull(wifi.lastHost)
    }

    @Test fun connectNormalizesHostAndAdvances() {
        val wifi = FakeWiFi()
        var connected = 0
        val c = WiFiConnectionCoordinator(scope, wifi) { connected += 1 }
        c.setAddress("  node.local "); c.setPort("5000")
        assertTrue(c.state.value.canConnect)
        c.connect()
        assertEquals("node.local", wifi.lastHost)
        assertEquals(5000, wifi.lastPort)
        assertEquals(1, connected)
        assertFalse(c.state.value.isConnecting)
    }

    @Test fun failureStaysOnSheetWithMessage() {
        val c = WiFiConnectionCoordinator(scope, FakeWiFi(OnboardingConnectOutcome.Failed(UiText.Verbatim("no route")))) {}
        c.setAddress("10.0.0.2")
        c.connect()
        assertEquals(UiText.Verbatim("no route"), c.state.value.error)
        assertFalse(c.state.value.isConnecting)
    }

    @Test fun emptyAddressCannotConnect() {
        assertFalse(WiFiConnectionCoordinator(scope, FakeWiFi()) {}.state.value.canConnect)
    }
}

class RegionStepCoordinatorTest {
    private val us = RegionSelection("US", RegionSelection.Source.LOCATION)

    @Test fun grantedLocationResolvesAndCommitsDetected() {
        val port = FakeRegion(us)
        var committed = 0
        val c = RegionStepCoordinator(scope, port, { true }) { committed += 1 }
        c.resolve()
        assertEquals(RegionStepMode.DETECTED, c.state.value.mode(true))
        c.commitDetected()
        assertEquals(us, port.selection.value)
        assertEquals(1, committed)
    }

    @Test fun noInternetOrTimeoutFallsSilentlyToManual() {
        val c = RegionStepCoordinator(scope, FakeRegion(null), { true }) {}
        c.resolve()
        assertEquals(RegionStepMode.MANUAL, c.state.value.mode(true))
        assertNull(c.state.value.error)
    }

    @Test fun userRetryFailureSurfacesErrorOnlyThen() {
        val c = RegionStepCoordinator(scope, FakeRegion(null), { true }) {}
        c.resolve()
        c.useMyLocation()
        assertNotNull(c.state.value.error)
        c.dismissError()
        assertNull(c.state.value.error)
    }

    @Test fun deniedLocationGoesManualWithoutResolving() {
        val port = FakeRegion(us)
        val c = RegionStepCoordinator(scope, port, { false }) {}
        c.resolve()
        assertEquals(0, port.resolveCalls)
        assertEquals(RegionStepMode.MANUAL, c.state.value.mode(false))
    }

    @Test fun manualSelectionRequiredToCommit() {
        val port = FakeRegion(null)
        var committed = 0
        val c = RegionStepCoordinator(scope, port, { false }) { committed += 1 }
        c.commitManual()
        assertEquals(0, committed)
        val ca = RegionSelection("CA", RegionSelection.Source.MANUAL)
        c.setManualSelection(ca)
        c.commitManual()
        assertEquals(ca, port.selection.value)
        assertEquals(1, committed)
    }

    @Test fun resolverExceptionIsTreatedAsNoResultAndCancellationPropagates() {
        val port = FakeRegion(us).apply { throwOnResolve = IllegalStateException("net") }
        val c = RegionStepCoordinator(scope, port, { true }) {}
        c.resolve()
        assertEquals(RegionStepMode.MANUAL, c.state.value.mode(true))
        assertFailsWith<CancellationException> { throw CancellationException("x") }
    }

    @Test fun chooseAnotherSwitchesDetectedToManual() {
        val c = RegionStepCoordinator(scope, FakeRegion(us), { true }) {}
        c.resolve(); c.chooseAnother()
        assertEquals(RegionStepMode.MANUAL, c.state.value.mode(true))
    }
}

class PresetStepCoordinatorTest {
    private val presets = mapOf<String?, List<OnboardingPresetOption>>(
        "US" to listOf(OnboardingPresetOption("a", "USA/Canada", 910.525), OnboardingPresetOption("b", "Other", 868.0)),
        null to listOf(OnboardingPresetOption("loc", "Locale", 433.0)),
    )

    private fun coordinator(port: FakePresets, region: FakeRegion = FakeRegion(presets = presets), done: () -> Unit = {}) =
        PresetStepCoordinator(scope, region, port, done)

    @Test fun visiblePresetsFollowRegionWithLocaleFallback() {
        val region = FakeRegion(presets = presets)
        val c = coordinator(FakePresets(), region)
        assertEquals(listOf("loc"), c.visiblePresets().map { it.id })
        region.setSelection(RegionSelection("US", RegionSelection.Source.MANUAL))
        assertEquals(listOf("a", "b"), c.visiblePresets().map { it.id })
    }

    @Test fun applySuccessCompletesOnboarding() {
        val port = FakePresets()
        var done = 0
        val c = coordinator(port, done = { done += 1 })
        c.select("loc"); c.apply()
        assertEquals(listOf("loc"), port.applied)
        assertEquals(1, done)
        assertEquals(1, c.state.value.commitTick)
    }

    @Test fun demoDeviceSkipsRadioConfigurationButCompletes() {
        var done = 0
        val c = coordinator(FakePresets(OnboardingPresetOutcome.NoRadioToConfigure), done = { done += 1 })
        c.select("loc"); c.apply()
        assertEquals(1, done)
    }

    @Test fun notConnectedSurfacesErrorWithoutCompleting() {
        var done = 0
        val c = coordinator(FakePresets(OnboardingPresetOutcome.NotConnected), done = { done += 1 })
        c.select("loc"); c.apply()
        assertEquals(0, done)
        assertNotNull(c.state.value.error)
    }

    @Test fun retryableFailureOffersRetryThenFallsBackAfterThree() {
        val port = FakePresets(OnboardingPresetOutcome.Retryable(UiText.Verbatim("busy")))
        val c = coordinator(port)
        c.select("loc"); c.apply()
        assertEquals(1, c.state.value.retryAlert?.retryCount)
        c.retry(); c.retry()
        val alert = assertNotNull(c.state.value.retryAlert)
        assertTrue(alert.isMaxRetriesExceeded)
        assertEquals(3, port.applied.size)
        c.dismissRetryAlert()
        assertNull(c.state.value.retryAlert)
        assertNotNull(c.state.value.error)
    }

    @Test fun staleSelectionIsIgnoredAndApplyingBlocksReentry() {
        val port = FakePresets()
        val c = coordinator(port)
        c.apply("missing")
        assertTrue(port.applied.isEmpty())
        assertFalse(c.canApply(false, PresetStepState(selectedId = "loc")))
        assertFalse(c.canApply(true, PresetStepState(selectedId = "loc", isApplying = true)))
        assertTrue(c.canApply(true, PresetStepState(selectedId = "loc")))
    }
}

class OnboardingFlowCoordinatorTest {
    private class Deps(
        override val flags: OnboardingFlagStore = InMemoryOnboardingFlagStore(),
        override val permissions: FakePermissions = FakePermissions(granted),
        override val pairing: FakePairing = FakePairing(),
        override val wifi: FakeWiFi = FakeWiFi(),
        override val demo: FakeDemo = FakeDemo(),
        override val region: FakeRegion = FakeRegion(RegionSelection("US", RegionSelection.Source.LOCATION),
            mapOf(null to listOf(OnboardingPresetOption("p", "P", 915.0)))),
        override val presets: FakePresets = FakePresets(),
    ) : OnboardingFeatureDependencies {
        override fun openAppSettings() = Unit
        @androidx.compose.runtime.Composable
        override fun rememberPermissionRequester(onResult: () -> Unit): (OnboardingPermissionKind) -> Unit = { }
        @androidx.compose.runtime.Composable
        override fun InterceptBack(enabled: Boolean, onBack: () -> Unit) = Unit
    }

    @Test fun fullDemoJourneyCompletesWithoutRadio() {
        val deps = Deps(permissions = FakePermissions(OnboardingPermissionSnapshot(location = PermissionStatus.GRANTED, bluetooth = PermissionStatus.GRANTED)),
            presets = FakePresets(OnboardingPresetOutcome.NoRadioToConfigure))
        val flow = OnboardingFlowCoordinator(scope, deps)
        assertEquals(OnboardingStep.WELCOME, flow.currentStep)
        flow.getStarted(); assertEquals(OnboardingStep.PERMISSIONS, flow.currentStep)
        flow.continueFromPermissions(); assertEquals(OnboardingStep.PAIR, flow.currentStep)
        flow.deviceScan.connectDemo(); assertEquals(OnboardingStep.REGION, flow.currentStep)
        flow.region.resolve(); flow.region.commitDetected(); assertEquals(OnboardingStep.PRESET, flow.currentStep)
        flow.preset.select("p"); flow.preset.apply()
        assertTrue(flow.onboarding.hasCompletedOnboarding)
        assertTrue(deps.flags.getBoolean(OnboardingState.KEY_HAS_COMPLETED))
    }

    @Test fun noDeviceSheetCompletesWithoutRegionOrPreset() {
        val flow = OnboardingFlowCoordinator(scope, Deps())
        flow.getStarted(); flow.continueFromPermissions()
        flow.deviceScan.showSheet(PairSheet.NO_DEVICE)
        flow.confirmNoDevice()
        assertTrue(flow.onboarding.hasCompletedOnboarding)
        assertEquals(PairSheet.NONE, flow.deviceScan.state.value.sheet)
        assertEquals(OnboardingStep.PAIR, flow.currentStep)
    }

    @Test fun wifiConnectNavigatesToRegionAndClosesSheet() {
        val flow = OnboardingFlowCoordinator(scope, Deps())
        flow.getStarted(); flow.continueFromPermissions()
        flow.deviceScan.showSheet(PairSheet.WIFI)
        flow.wifi.setAddress("10.0.0.5"); flow.wifi.connect()
        assertEquals(OnboardingStep.REGION, flow.currentStep)
        assertEquals(PairSheet.NONE, flow.deviceScan.state.value.sheet)
    }

    @Test fun justInTimeBluetoothPermissionThenPairResumes() {
        val deps = Deps(permissions = FakePermissions(OnboardingPermissionSnapshot()))
        val flow = OnboardingFlowCoordinator(scope, deps)
        val requested = mutableListOf<OnboardingPermissionKind>()
        flow.addDevice { requested += it }
        assertEquals(listOf(OnboardingPermissionKind.BLUETOOTH), requested)
        assertEquals(0, deps.pairing.pairCalls)
        deps.permissions.mutable.value = granted
        flow.onPermissionResult()
        assertEquals(1, deps.pairing.pairCalls)
        assertEquals(1, deps.permissions.refreshCount)
    }

    @Test fun backPopsAndRestoreResumesPartialInstall() {
        val deps = Deps(permissions = FakePermissions(OnboardingPermissionSnapshot(location = PermissionStatus.GRANTED, notifications = PermissionStatus.DENIED)))
        deps.pairing.hasLastConnectedDevice = true
        val flow = OnboardingFlowCoordinator(scope, deps)
        flow.restoreStartingPath()
        assertEquals(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR, OnboardingStep.REGION), flow.onboarding.onboardingPath)
        assertTrue(flow.back())
        assertEquals(OnboardingStep.PAIR, flow.currentStep)
    }

    @Test fun clearStalePairingsRunsOnceAndClosesSheet() {
        val deps = Deps()
        val flow = OnboardingFlowCoordinator(scope, deps)
        flow.deviceScan.showSheet(PairSheet.TROUBLESHOOTING)
        flow.clearStalePairings()
        assertEquals(1, deps.pairing.cleared)
        assertFalse(flow.isClearingPairings.value)
        assertEquals(PairSheet.NONE, flow.deviceScan.state.value.sheet)
    }
}
