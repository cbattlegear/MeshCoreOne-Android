// AndroidOnly: WP-317 TS-31 mounted Settings entry/actions over the production Room/process/connection adapters.
package com.meshcoreone.android.app.platform

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.app.container.*
import com.meshcoreone.android.app.container.onboarding.SystemPairingSetupHost
import com.meshcoreone.android.app.container.onboarding.OnboardingPlatform
import com.meshcoreone.android.app.container.onboarding.WriteBehindOnboardingFlags
import com.meshcoreone.android.app.container.onboarding.FakePermissionFacts
import com.meshcoreone.android.app.container.onboarding.RequestedPermissions
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionKind
import com.meshcoreone.android.core.connectivity.permissions.ConnectivityPermission
import com.meshcoreone.android.core.connectivity.pairing.BluetoothScanPairingService
import com.meshcoreone.android.core.connectivity.pairing.DevicePairingService
import com.meshcoreone.android.core.connectivity.ble.BleScanCoordinator
import com.meshcoreone.android.core.connectivity.ble.BleScanGateway
import com.meshcoreone.android.core.connectivity.ble.DiscoveredDevice
import com.meshcoreone.android.app.navigation.AppSettingsContent
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.app.state.PairingFailureKind
import com.meshcoreone.android.core.ble.BleError
import com.meshcoreone.android.core.ble.BleTransportException
import com.meshcoreone.android.core.connectivity.pairing.DevicePairingError
import com.meshcoreone.android.core.connectivity.ble.SystemLinkProbe
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.DeviceIdentity
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.simulator.DemoModeManager
import com.meshcoreone.android.core.services.simulator.InMemoryDemoModeDefaults
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.Shadows.shadowOf

@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h900dp")
class DeviceSelectionRegistrationTest : RoomProcessTest() {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private companion object {
        val demoDefaults = InMemoryDemoModeDefaults()
    }

    private suspend fun TestScope.withSettings(
        persistence: PersistenceStoreProtocol = store,
        systemLinks: SystemLinkProbe = NoSystemLinks,
        permissionFacts: FakePermissionFacts = FakePermissionFacts(
            sdk = android.os.Build.VERSION.SDK_INT, granted = ConnectivityPermission.entries.toSet(),
        ),
        pairingService: DevicePairingService? = null,
        scans: BleScanCoordinator = newScans(),
        openSettings: () -> Unit = {},
        requests: RequestedPermissions = RequestedPermissions(),
        test: suspend (ContainerHarness, AppSettingsFeature) -> Unit,
    ) {
        DemoModeManager.installStandardDefaults(demoDefaults)
        val platform = OnboardingPlatform(
            WriteBehindOnboardingFlags(emptyMap(), backgroundScope, { _, _ -> }),
            permissionFacts, requests = requests, scans = scans, openAppSettings = openSettings, sdkInt = permissionFacts.sdk,
        )
        val h = ContainerHarness(
            this, store, persistenceOverride = persistence, systemLinks = systemLinks,
            pairingService = pairingService, scans = scans, onboardingPlatform = platform,
        )
        val storage = MeshCoreStorage.get(compose.activity)
        try {
            val feature = AppSettingsFeature.create(h.container, storage)
            test(h, feature)
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            h.container.close()
            storage.close()
        }
    }

    private fun mount(h: ContainerHarness, feature: AppSettingsFeature) {
        compose.runOnUiThread {
            h.container.navigation.selectTab(AppTab.SETTINGS)
            compose.activity.setContent {
                val navigation by h.container.navigation.state.collectAsState()
                MeshCoreTheme(motionScale = 0f) {
                    if (navigation.selectedTab == AppTab.SETTINGS) {
                        AppSettingsContent(feature, navigation.activeStack.last().destination, h.container.navigation)
                    }
                    SystemPairingSetupHost(h.appState)
                }
            }
        }
    }

    private fun mountNavigationShell(h: ContainerHarness, feature: AppSettingsFeature) {
        compose.runOnUiThread {
            h.container.navigation.selectTab(AppTab.SETTINGS)
            compose.activity.setContent {
                MeshCoreTheme(motionScale = 0f) {
                    NativeNavigationShell(h.container.navigation, settings = feature)
                }
            }
        }
    }

    private suspend fun saved(h: ContainerHarness): DeviceDTO {
        h.manager.connect(h.target())
        h.settle()
        h.assertReady()
        val device = requireNotNull(h.manager.connectedDevice)
        h.pairing.registered[device.id] = device.nodeName
        h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
        h.settle()
        return device
    }

    private fun open(h: ContainerHarness) {
        compose.onNodeWithText(compose.activity.getString(S.deviceConnect)).performScrollTo().performClick()
        compose.waitForIdle()
        h.settle()
        selectionTitle().assertIsDisplayed()
        assertEquals(AppTab.SETTINGS, h.container.navigation.state.value.selectedTab)
    }

    private fun selectionTitle() =
        compose.onNode(hasText(compose.activity.getString(S.deviceSelectionTitle)) and !hasClickAction())

    private fun openManualWifi(h: ContainerHarness) {
        open(h)
        compose.onNodeWithText(compose.activity.getString(S.deviceSelectionConnectViaWifi)).performClick()
        compose.onNode(hasSetTextAction() and hasText(compose.activity.getString(O.wifiConnectionIpAddressPlaceholder)))
            .performScrollTo().performTextReplacement("manual-radio.local")
        compose.onNode(hasSetTextAction() and hasText(compose.activity.getString(O.wifiConnectionPortPlaceholder)))
            .performScrollTo().performTextReplacement("6217")
        compose.onNode(hasText(compose.activity.getString(O.wifiConnectionConnect)) and hasClickAction())
            .performScrollTo().performClick()
    }

    private fun permissionResult(
        facts: FakePermissionFacts, granted: Boolean,
        permissions: Set<ConnectivityPermission> = setOf(ConnectivityPermission.ACCESS_LOCAL_NETWORK),
    ) {
        val request = requireNotNull(shadowOf(compose.activity).lastRequestedPermission)
        assertArrayEquals(permissions.map { it.manifestName }.sorted().toTypedArray(), request.requestedPermissions)
        if (granted) facts.granted = facts.granted + permissions
        compose.runOnUiThread {
            compose.activity.onRequestPermissionsResult(
                request.requestCode, request.requestedPermissions,
                IntArray(request.requestedPermissions.size) {
                    if (granted) android.content.pm.PackageManager.PERMISSION_GRANTED else android.content.pm.PackageManager.PERMISSION_DENIED
                },
            )
        }
        compose.waitForIdle()
    }

    private val bluetoothPermissions = setOf(ConnectivityPermission.BLUETOOTH_SCAN, ConnectivityPermission.BLUETOOTH_CONNECT)

    private suspend fun TestScope.bluetoothPermissionControl(grant: Boolean) {
        val facts = FakePermissionFacts(sdk = 31)
        val service = BluetoothScanPairingService()
        var starts = 0
        var stops = 0
        var openedSettings = 0
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) {
                assertTrue(facts.granted.containsAll(bluetoothPermissions))
                assertTrue(facts.bluetoothEnabled)
                starts++
            }
            override fun stopScan() { stops++ }
        })
        withSettings(permissionFacts = facts, pairingService = service, scans = scans, openSettings = { openedSettings++ }) { h, feature ->
            mount(h, feature)
            open(h)
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionScanBluetooth)).performClick()
            compose.waitForIdle()
            h.settle()
            assertEquals(0, starts)
            assertEquals(0, stops)
            assertFalse(service.isPresenting.value)
            assertTrue(h.links.links.isEmpty())
            permissionResult(facts, grant, bluetoothPermissions)
            if (!grant) {
                h.settle()
                assertEquals(0, starts)
                assertFalse(service.isPresenting.value)
                compose.onNodeWithText(compose.activity.getString(O.deviceScannerBluetoothUnauthorizedTitle)).assertIsDisplayed()
                compose.onNodeWithText(compose.activity.getString(O.permissionsOpenSettings)).performClick()
                assertEquals(1, openedSettings)
                facts.granted = bluetoothPermissions
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            }
            h.eventually("Bluetooth grant starts exactly one fallback scan") { service.isPresenting.value && starts == 1 }
            compose.onNodeWithText(compose.activity.getString(O.deviceScannerTitle)).assertIsDisplayed()
            assertNull(feature.selectionAction.value)
            compose.onNodeWithText(compose.activity.getString(L.commonCancel)).performClick()
            compose.waitForIdle()
            h.eventually("permitted scan cancellation completes pairing") {
                !service.isPresenting.value && !h.container.connectionPort.isPairingFlowActive && stops == 1
            }
            assertEquals(1, starts)
            assertTrue(h.links.links.isEmpty())
        }
    }

    @Test fun fallbackScanRequestsMissingBluetoothPermissionsBeforeStartingTheScanner() = runTest {
        bluetoothPermissionControl(grant = true)
    }

    @Test fun fallbackScanDenialDoesNotScanAndSettingsRecoveryConsumesTheActionOnce() = runTest {
        bluetoothPermissionControl(grant = false)
    }

    @Test fun fallbackScanPreviouslyDeniedBluetoothOffersRecoveryWithoutRequestingOrScanningAgain() = runTest {
        val facts = FakePermissionFacts(sdk = 31)
        val requests = RequestedPermissions().also { it.mark(OnboardingPermissionKind.BLUETOOTH) }
        val service = BluetoothScanPairingService()
        var starts = 0
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) { starts++ }
            override fun stopScan() = Unit
        })
        withSettings(permissionFacts = facts, requests = requests, pairingService = service, scans = scans) { h, feature ->
            mount(h, feature)
            val beforeRequest = shadowOf(compose.activity).lastRequestedPermission
            open(h)
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionScanBluetooth)).performClick()
            compose.waitForIdle()
            h.settle()
            compose.onNodeWithText(compose.activity.getString(O.deviceScannerBluetoothUnauthorizedTitle)).assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(O.permissionsOpenSettings)).assertIsDisplayed()
            assertSame(beforeRequest, shadowOf(compose.activity).lastRequestedPermission)
            assertEquals(0, starts)
            assertFalse(service.isPresenting.value)
            compose.onNodeWithText(compose.activity.getString(L.commonCancel)).performClick()
            assertNull(feature.selectionAction.value)
            assertFalse(h.container.connectionPort.isPairingFlowActive)
            assertTrue(h.links.links.isEmpty())
        }
    }

    @Test fun fallbackScanWithBluetoothOffWaitsForSettingsAndRefreshesTheAdapterOnResume() = runTest {
        val facts = FakePermissionFacts(sdk = 31, granted = bluetoothPermissions, bluetoothEnabled = false)
        val service = BluetoothScanPairingService()
        var starts = 0
        var openedSettings = 0
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) {
                assertTrue(facts.bluetoothEnabled)
                starts++
            }
            override fun stopScan() = Unit
        })
        withSettings(permissionFacts = facts, pairingService = service, scans = scans, openSettings = { openedSettings++ }) { h, feature ->
            mount(h, feature)
            val beforeRequest = shadowOf(compose.activity).lastRequestedPermission
            open(h)
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionScanBluetooth)).performClick()
            compose.waitForIdle()
            h.settle()
            compose.onNodeWithText(compose.activity.getString(O.deviceScannerBluetoothOffTitle)).assertIsDisplayed()
            assertSame(beforeRequest, shadowOf(compose.activity).lastRequestedPermission)
            assertEquals(0, starts)
            assertFalse(service.isPresenting.value)
            compose.onNodeWithText(compose.activity.getString(O.permissionsOpenSettings)).performClick()
            assertEquals(1, openedSettings)
            facts.bluetoothEnabled = true
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            h.eventually("adapter recovery starts one scan") { service.isPresenting.value && starts == 1 }
            compose.onNodeWithText(compose.activity.getString(L.commonCancel)).performClick()
            compose.waitForIdle()
            h.eventually("adapter recovery scan cancelled") { !service.isPresenting.value && !h.container.connectionPort.isPairingFlowActive }
            assertTrue(h.links.links.isEmpty())
        }
    }

    @Test fun fallbackScanRevokedPermissionStopsScanningUntilResumeRecoveryWithoutStartingAnotherPairing() = runTest {
        val facts = FakePermissionFacts(sdk = 31, granted = bluetoothPermissions)
        val service = BluetoothScanPairingService()
        var starts = 0
        var stops = 0
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) {
                assertTrue(facts.granted.containsAll(bluetoothPermissions))
                starts++
            }
            override fun stopScan() { stops++ }
        })
        withSettings(permissionFacts = facts, pairingService = service, scans = scans) { h, feature ->
            mount(h, feature)
            open(h)
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionScanBluetooth)).performClick()
            compose.waitForIdle()
            h.eventually("initial authorized scan") { starts == 1 }
            facts.granted = emptySet()
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitForIdle()
            h.eventually("revocation releases scan collector") { stops == 1 }
            assertTrue(service.isPresenting.value)
            permissionResult(facts, false, bluetoothPermissions)
            assertEquals(1, starts)
            compose.onNodeWithText(compose.activity.getString(O.permissionsOpenSettings)).performClick()
            facts.granted = bluetoothPermissions
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            h.eventually("restored permission resumes the existing picker") { starts == 2 }
            assertEquals(1, stops)
            assertTrue(service.isPresenting.value)
            compose.onNodeWithText(compose.activity.getString(L.commonCancel)).performClick()
            compose.waitForIdle()
            h.eventually("resumed picker terminates") { !service.isPresenting.value && !h.container.connectionPort.isPairingFlowActive && stops == 2 }
            assertTrue(h.links.links.isEmpty())
        }
    }

    @Test fun fallbackScanNavigationAwayDisposesTheHostAndCancelsTheSuspendedPairing() = runTest {
        val service = BluetoothScanPairingService()
        var starts = 0
        var stops = 0
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) { starts++ }
            override fun stopScan() { stops++ }
        })
        withSettings(pairingService = service, scans = scans) { h, feature ->
            mount(h, feature)
            open(h)
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionScanBluetooth)).performClick()
            compose.waitForIdle()
            h.eventually("picker visible before tab transition") { service.isPresenting.value && starts == 1 }
            compose.runOnUiThread { h.container.navigation.selectTab(AppTab.CHATS) }
            compose.waitForIdle()
            compose.onNodeWithText(compose.activity.getString(O.deviceScannerTitle)).assertDoesNotExist()
            h.eventually("navigation cancels invisible pairing") {
                !service.isPresenting.value && !h.container.connectionPort.isPairingFlowActive && stops == 1
            }
            compose.runOnUiThread { h.container.navigation.selectTab(AppTab.SETTINGS) }
            compose.waitForIdle()
            h.settle()
            assertEquals(1, starts)
            compose.onNodeWithText(compose.activity.getString(O.deviceScannerTitle)).assertDoesNotExist()
            assertTrue(h.links.links.isEmpty())
            assertFalse(h.appState.connectionUI.showingConnectionFailedAlert)
        }
    }

    @Test fun fallbackScanNavigationAwayBeforeQueuedDiscoveryCannotLaunchAnInvisiblePicker() = runTest {
        val service = BluetoothScanPairingService()
        var starts = 0
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) { starts++ }
            override fun stopScan() = Unit
        })
        withSettings(pairingService = service, scans = scans) { h, feature ->
            mount(h, feature)
            open(h)
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionScanBluetooth)).performClick()
            compose.waitForIdle()
            assertNull(feature.selectionAction.value)
            assertFalse(service.isPresenting.value)
            assertEquals(0, starts)
            compose.runOnUiThread { h.container.navigation.selectTab(AppTab.CHATS) }
            compose.waitForIdle()
            h.settle()
            assertFalse(service.isPresenting.value)
            assertFalse(h.container.connectionPort.isPairingFlowActive)
            assertEquals(0, starts)
            compose.runOnUiThread { h.container.navigation.selectTab(AppTab.SETTINGS) }
            compose.waitForIdle()
            h.settle()
            compose.onNodeWithText(compose.activity.getString(O.deviceScannerTitle)).assertDoesNotExist()
            assertEquals(0, starts)
            assertTrue(h.links.links.isEmpty())
        }
    }

    private suspend fun TestScope.reopenedFallbackCleanupControl(cancelWhileWaiting: Boolean) {
        val service = BluetoothScanPairingService()
        var starts = 0
        var stops = 0
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) { starts++ }
            override fun stopScan() { stops++ }
        })
        withSettings(pairingService = service, scans = scans) { h, feature ->
            try {
                mount(h, feature)
                open(h)
                compose.onNodeWithText(compose.activity.getString(S.deviceSelectionScanBluetooth)).performClick()
                compose.waitForIdle()
                h.eventually("first scan awaiting discovery") { service.isPresenting.value && starts == 1 }
                val oldJob = requireNotNull(feature.selectionScanJob)
                val cleanupChild = backgroundScope.launch(oldJob) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleanupEntered.complete(Unit)
                            releaseCleanup.await()
                        }
                    }
                }
                h.settle()
                assertTrue(cleanupChild.isActive)
                compose.runOnUiThread { h.container.navigation.selectTab(AppTab.CHATS) }
                compose.waitForIdle()
                h.eventually("old job cancellation held in actual child cleanup") {
                    cleanupEntered.isCompleted && oldJob.isCancelled && !oldJob.isCompleted && !service.isPresenting.value && stops == 1
                }
                compose.runOnUiThread { h.container.navigation.selectTab(AppTab.SETTINGS) }
                compose.waitForIdle()
                open(h)
                compose.onNodeWithText(compose.activity.getString(S.deviceSelectionScanBluetooth)).performClick()
                compose.waitForIdle()
                h.settle()
                val nextJob = requireNotNull(feature.selectionScanJob)
                assertNotSame(oldJob, nextJob)
                assertTrue(nextJob.isActive)
                assertFalse(oldJob.isCompleted)
                assertEquals(1, starts)
                assertFalse(service.isPresenting.value)
                if (cancelWhileWaiting) {
                    compose.runOnUiThread { h.container.navigation.selectTab(AppTab.CHATS) }
                    compose.waitForIdle()
                    releaseCleanup.complete(Unit)
                    h.eventually("navigation cancels the queued replacement before old cleanup ends") {
                        oldJob.isCompleted && nextJob.isCompleted && !service.isPresenting.value &&
                            !h.container.connectionPort.isPairingFlowActive
                    }
                    assertEquals(1, starts)
                    assertEquals(1, stops)
                    assertTrue(h.links.links.isEmpty())
                    return@withSettings
                }
                releaseCleanup.complete(Unit)
                h.eventually("new scan starts only after old job finishes") {
                    oldJob.isCompleted && nextJob.isActive && service.isPresenting.value && starts == 2
                }
                compose.onNodeWithText(compose.activity.getString(O.deviceScannerTitle)).assertIsDisplayed()
                compose.runOnUiThread { h.container.navigation.selectTab(AppTab.CHATS) }
                compose.waitForIdle()
                h.eventually("new scan retains cancellation after old cleanup") {
                    nextJob.isCompleted && !service.isPresenting.value &&
                        !h.container.connectionPort.isPairingFlowActive && stops == 2
                }
                assertTrue(h.links.links.isEmpty())
                assertFalse(h.appState.connectionUI.showingConnectionFailedAlert)
            } finally {
                releaseCleanup.complete(Unit)
            }
        }
    }

    @Test fun reopenedFallbackScanWaitsForOldCancellationCleanupThenRetainsItsOwnNavigationCancellation() = runTest {
        reopenedFallbackCleanupControl(cancelWhileWaiting = false)
    }

    @Test fun leavingSettingsWhileReplacementWaitsForOldCleanupCancelsThePendingScanGeneration() = runTest {
        reopenedFallbackCleanupControl(cancelWhileWaiting = true)
    }

    @Test fun selectedFallbackRadioContinuesConnectingAfterNavigationRemovesThePickerHost() = runTest {
        val service = BluetoothScanPairingService()
        val id = UUID.randomUUID()
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) {
                onDevice(DiscoveredDevice(id, syntheticAddress(id), "Chosen radio", -40))
            }
            override fun stopScan() = Unit
        })
        withSettings(pairingService = service, scans = scans) { h, feature ->
            val gate = CompletableDeferred<Unit>()
            h.links.configureRadio = { it.connectGate = gate }
            mount(h, feature)
            open(h)
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionScanBluetooth)).performClick()
            compose.waitForIdle()
            h.eventually("chosen radio rendered") { compose.onAllNodesWithText("Chosen radio").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("Chosen radio").performClick()
            h.eventually("chosen radio connection entered") { h.links.links.size == 1 }
            assertFalse(service.isPresenting.value)
            compose.runOnUiThread { h.container.navigation.selectTab(AppTab.CHATS) }
            compose.waitForIdle()
            gate.complete(Unit)
            h.eventually("chosen radio remains process-owned after navigation") {
                h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY &&
                    !h.container.connectionPort.isPairingFlowActive
            }
            assertEquals(id, h.manager.connectedDevice?.id)
            assertEquals(id, (h.links.links.single().target as ConnectionTarget.Bluetooth).deviceId)
            assertFalse(h.appState.connectionUI.showingConnectionFailedAlert)
        }
    }

    @Test fun fallbackScanActivityRecreationReattachesWithoutCancellingThePairingOrDuplicatingThePicker() = runTest {
        val service = BluetoothScanPairingService()
        var starts = 0
        var stops = 0
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) { starts++ }
            override fun stopScan() { stops++ }
        })
        withSettings(pairingService = service, scans = scans) { h, feature ->
            mount(h, feature)
            open(h)
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionScanBluetooth)).performClick()
            compose.waitForIdle()
            h.eventually("picker pending before recreation") { service.isPresenting.value && starts == 1 }
            compose.activityRule.scenario.recreate()
            mount(h, feature)
            h.eventually("new Activity owns the sole scan collector") { starts == 2 && stops == 1 }
            assertTrue(service.isPresenting.value)
            assertTrue(h.container.connectionPort.isPairingFlowActive)
            compose.onAllNodesWithText(compose.activity.getString(O.deviceScannerTitle)).assertCountEquals(1)
            compose.onNodeWithText(compose.activity.getString(L.commonCancel)).performClick()
            compose.waitForIdle()
            h.eventually("recreated picker cancels original pairing") {
                !service.isPresenting.value && !h.container.connectionPort.isPairingFlowActive && stops == 2
            }
            assertTrue(h.links.links.isEmpty())
        }
    }

    @Test fun fallbackScanPendingPermissionReattachesToTheRecreatedActivityAndConsumesTheGrantOnce() = runTest {
        val facts = FakePermissionFacts(sdk = 31)
        val service = BluetoothScanPairingService()
        var starts = 0
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) {
                assertTrue(facts.granted.containsAll(bluetoothPermissions))
                starts++
            }
            override fun stopScan() = Unit
        })
        withSettings(permissionFacts = facts, pairingService = service, scans = scans) { h, feature ->
            mount(h, feature)
            open(h)
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionScanBluetooth)).performClick()
            compose.waitForIdle()
            val request = requireNotNull(shadowOf(compose.activity).lastRequestedPermission)
            assertArrayEquals(bluetoothPermissions.map { it.manifestName }.sorted().toTypedArray(), request.requestedPermissions)
            assertEquals(0, starts)
            compose.activityRule.scenario.recreate()
            mount(h, feature)
            compose.waitForIdle()
            assertEquals(OnboardingPermissionKind.BLUETOOTH, feature.selectionRequestedPermission.value)
            assertNull(shadowOf(compose.activity).lastRequestedPermission)
            assertFalse(service.isPresenting.value)
            assertEquals(0, starts)
            facts.granted = bluetoothPermissions
            compose.runOnUiThread {
                compose.activity.onRequestPermissionsResult(
                    request.requestCode, request.requestedPermissions,
                    IntArray(request.requestedPermissions.size) { android.content.pm.PackageManager.PERMISSION_GRANTED },
                )
            }
            compose.waitForIdle()
            h.eventually("recreated permission result starts one pairing") { service.isPresenting.value && starts == 1 }
            assertNull(feature.selectionRequestedPermission.value)
            assertNull(feature.selectionAction.value)
            compose.onAllNodesWithText(compose.activity.getString(O.deviceScannerTitle)).assertCountEquals(1)
            compose.onNodeWithText(compose.activity.getString(L.commonCancel)).performClick()
            compose.waitForIdle()
            h.eventually("recreated permission flow terminates") { !service.isPresenting.value && !h.container.connectionPort.isPairingFlowActive }
            assertEquals(1, starts)
            assertTrue(h.links.links.isEmpty())
        }
    }

    @Test fun fallbackScanMountsActualPickerAndCancellationTerminatesThePairingAttempt() = runTest {
        val service = BluetoothScanPairingService()
        var starts = 0
        var stops = 0
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) { starts++ }
            override fun stopScan() { stops++ }
        })
        withSettings(pairingService = service, scans = scans) { h, feature ->
            mount(h, feature)
            open(h)
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionScanBluetooth)).performClick()
            compose.waitForIdle()
            h.eventually("fallback picker requested") { service.isPresenting.value }
            compose.onNodeWithText(compose.activity.getString(O.deviceScannerTitle)).assertIsDisplayed()
            h.eventually("fallback scan started") { starts == 1 }
            selectionTitle().assertDoesNotExist()
            compose.onNodeWithText(compose.activity.getString(L.commonCancel)).performClick()
            compose.waitForIdle()
            h.eventually("fallback cancelled and scan released") {
                !service.isPresenting.value && !h.container.connectionPort.isPairingFlowActive && stops == 1
            }
            assertTrue(h.links.links.isEmpty())
            assertFalse(h.appState.connectionUI.showingConnectionFailedAlert)
        }
    }

    @Test fun fallbackScanSelectionConnectsTheAdvertisedIdentityThroughTheRealPairingService() = runTest {
        val service = BluetoothScanPairingService()
        val id = UUID.randomUUID()
        var stopped = false
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) {
                onDevice(DiscoveredDevice(id, syntheticAddress(id), "Fallback radio", -40))
            }
            override fun stopScan() { stopped = true }
        })
        withSettings(pairingService = service, scans = scans) { h, feature ->
            mount(h, feature)
            open(h)
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionScanBluetooth)).performClick()
            compose.waitForIdle()
            h.eventually("fallback discovery rendered") {
                compose.onAllNodesWithText("Fallback radio").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText("Fallback radio").performClick()
            h.eventually("fallback selected radio ready") {
                h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
            }
            assertEquals(id, (h.links.links.single().target as ConnectionTarget.Bluetooth).deviceId)
            assertEquals(id, h.manager.connectedDevice?.id)
            assertFalse(service.isPresenting.value)
            compose.waitForIdle()
            h.eventually("fallback scan released after selection") { stopped && !h.container.connectionPort.isPairingFlowActive }
            assertEquals(AppTab.SETTINGS, h.container.navigation.state.value.selectedTab)
        }
    }

    @Test fun pendingWifiFailureReattachesToTheRecreatedActivityWithoutRepeatingTheConnection() = runTest {
        withSettings { h, feature ->
            val gate = CompletableDeferred<Unit>()
            h.links.configureRadio = { it.connectGate = gate; it.connectFailure = IllegalStateException("Wi-Fi test failure") }
            mount(h, feature)
            openManualWifi(h)
            h.eventually("manual attempt entered before recreation") { h.links.links.size == 1 }
            compose.activityRule.scenario.recreate()
            mount(h, feature)
            compose.onNodeWithText(compose.activity.getString(O.wifiConnectionTitle)).assertIsDisplayed()
            compose.onNode(hasSetTextAction() and hasText("manual-radio.local")).assertExists()
            compose.onNodeWithText(compose.activity.getString(L.commonCancel)).assertIsNotEnabled()
            gate.complete(Unit)
            h.eventually("retained manual attempt failed") { feature.selectionWifi.state.value.error != null }
            compose.onNodeWithText(requireNotNull(feature.selectionWifi.state.value.error).resolve(compose.activity.resources))
                .assertIsDisplayed()
            assertEquals(1, h.links.links.size)
            assertTrue(feature.selectionWifiVisible.value)
            compose.onNodeWithText(compose.activity.getString(L.commonCancel)).assertIsEnabled().performClick()
            compose.onNodeWithText(compose.activity.getString(O.wifiConnectionTitle)).assertDoesNotExist()
        }
    }

    @Test fun pendingWifiSuccessDismissesTheRecreatedActivityFormExactlyOnce() = runTest {
        withSettings { h, feature ->
            val gate = CompletableDeferred<Unit>()
            h.links.configureRadio = { it.connectGate = gate }
            mount(h, feature)
            openManualWifi(h)
            h.eventually("manual success pending before recreation") { h.links.links.size == 1 }
            compose.activityRule.scenario.recreate()
            mount(h, feature)
            compose.onNodeWithText(compose.activity.getString(O.wifiConnectionTitle)).assertIsDisplayed()
            gate.complete(Unit)
            h.eventually("retained manual success dismissed") { !feature.selectionWifiVisible.value }
            compose.onNodeWithText(compose.activity.getString(O.wifiConnectionTitle)).assertDoesNotExist()
            assertEquals(1, h.links.links.size)
            h.assertReady()
        }
    }

    @Test fun pendingWifiVetoesSwipeScrimAndBackUntilItsInlineFailureCanBeShown() = runTest {
        withSettings { h, feature ->
            val gate = CompletableDeferred<Unit>()
            h.links.configureRadio = { it.connectGate = gate; it.connectFailure = IllegalStateException("Visible inline failure") }
            mount(h, feature)
            openManualWifi(h)
            h.eventually("manual attempt pending during dismissal") { h.links.links.size == 1 }
            compose.onNodeWithText(compose.activity.getString(O.wifiConnectionTitle)).performTouchInput {
                swipeDown(endY = 1_000f)
            }
            compose.onNodeWithText(compose.activity.getString(O.wifiConnectionTitle)).assertIsDisplayed()
            compose.onNode(isDialog()).performTouchInput { click(androidx.compose.ui.geometry.Offset(2f, 2f)) }
            compose.onNodeWithText(compose.activity.getString(O.wifiConnectionTitle)).assertIsDisplayed()
            compose.runOnUiThread { requireNotNull(ShadowDialog.getLatestDialog()).onBackPressed() }
            compose.onNodeWithText(compose.activity.getString(O.wifiConnectionTitle)).assertIsDisplayed()
            assertTrue(feature.selectionWifiVisible.value)
            assertTrue(feature.selectionWifi.state.value.isConnecting)
            gate.complete(Unit)
            h.eventually("manual failure after attempted dismissal") { feature.selectionWifi.state.value.error != null }
            compose.onNodeWithText(requireNotNull(feature.selectionWifi.state.value.error).resolve(compose.activity.resources))
                .assertIsDisplayed()
            assertEquals(1, h.links.links.size)
            compose.onNodeWithText(compose.activity.getString(L.commonCancel)).assertIsEnabled().performClick()
            compose.onNodeWithText(compose.activity.getString(O.wifiConnectionTitle)).assertDoesNotExist()
        }
    }

    private suspend fun TestScope.localNetworkPermissionControl(savedDevice: Boolean, grant: Boolean) {
        val facts = FakePermissionFacts(sdk = 37, granted = ConnectivityPermission.entries.toSet() - ConnectivityPermission.ACCESS_LOCAL_NETWORK)
        var openedSettings = 0
        withSettings(permissionFacts = facts, openSettings = { openedSettings++ }) { h, feature ->
            val device = if (savedDevice) saved(h).also {
                store.saveDevice(it.copy(connectionMethods = (it.connectionMethods + ConnectionMethod.WiFi("saved-radio.local", 6218u)).snapshot()))
            } else null
            val before = h.links.links.size
            val gate = CompletableDeferred<Unit>()
            h.links.configureRadio = { it.connectGate = gate }
            mount(h, feature)
            if (device != null) {
                open(h)
                compose.onNodeWithText(device.nodeName).performClick()
            } else openManualWifi(h)
            compose.waitForIdle()
            h.settle()
            assertEquals(before, h.links.links.size)
            assertNotNull(feature.selectionAction.value)
            permissionResult(facts, grant)
            if (!grant) {
                assertEquals(before, h.links.links.size)
                compose.onNodeWithText(compose.activity.getString(O.permissionsOpenSettings)).assertIsDisplayed().performClick()
                assertEquals(1, openedSettings)
                facts.granted = facts.granted + ConnectivityPermission.ACCESS_LOCAL_NETWORK
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            }
            h.eventually("permission grant resumes one requested Wi-Fi operation") { h.links.links.size == before + 1 }
            assertEquals(
                if (savedDevice) ConnectionTarget.WiFi("saved-radio.local", 6218u) else ConnectionTarget.WiFi("manual-radio.local", 6217u),
                h.links.links.last().target,
            )
            assertEquals(ConnectionIntent.WantsConnection(forceFullSync = true), h.manager.connectionIntent)
            assertNull(feature.selectionAction.value)
            gate.complete(Unit)
            h.eventually("permitted Wi-Fi ready") {
                h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
            }
            assertEquals(before + 1, h.links.links.size)
        }
    }

    @Test @Config(sdk = [37])
    fun savedWifiRequestsLocalNetworkPermissionAndResumesOnlyAfterGrant() = runTest {
        localNetworkPermissionControl(savedDevice = true, grant = true)
    }

    @Test @Config(sdk = [37])
    fun savedWifiDeniedLocalNetworkPermissionOffersSettingsAndResumesAfterRecovery() = runTest {
        localNetworkPermissionControl(savedDevice = true, grant = false)
    }

    @Test @Config(sdk = [37])
    fun manualWifiRequestsLocalNetworkPermissionAndResumesOnlyAfterGrant() = runTest {
        localNetworkPermissionControl(savedDevice = false, grant = true)
    }

    @Test @Config(sdk = [37])
    fun manualWifiDeniedLocalNetworkPermissionOffersSettingsAndResumesAfterRecovery() = runTest {
        localNetworkPermissionControl(savedDevice = false, grant = false)
    }

    private suspend fun TestScope.savedWifiPermissionResultAcrossNativeTabRemoval(grant: Boolean) {
        val facts = FakePermissionFacts(sdk = 37, granted = ConnectivityPermission.entries.toSet() - ConnectivityPermission.ACCESS_LOCAL_NETWORK)
        var openedSettings = 0
        withSettings(permissionFacts = facts, openSettings = { openedSettings++ }) { h, feature ->
            val device = saved(h)
            store.saveDevice(device.copy(connectionMethods = (device.connectionMethods + ConnectionMethod.WiFi("tab-radio.local", 6219u)).snapshot()))
            val before = h.links.links.size
            val gate = CompletableDeferred<Unit>()
            h.links.configureRadio = { it.connectGate = gate }
            mountNavigationShell(h, feature)
            open(h)
            compose.onNodeWithText(device.nodeName).performClick()
            compose.waitForIdle()
            assertEquals(OnboardingPermissionKind.LOCAL_NETWORK, feature.selectionRequestedPermission.value)
            compose.onNodeWithTag("tab:CHATS").performClick()
            compose.onNodeWithTag("tab:CHATS").assertIsSelected()
            permissionResult(facts, grant)
            h.settle()
            assertEquals(before, h.links.links.size)
            compose.onNodeWithTag("tab:SETTINGS").performClick()
            compose.onNodeWithTag("tab:SETTINGS").assertIsSelected()
            compose.waitForIdle()
            if (!grant) {
                compose.onNodeWithText(compose.activity.getString(O.permissionsOpenSettings)).assertIsDisplayed().performClick()
                assertEquals(1, openedSettings)
                assertEquals(before, h.links.links.size)
                facts.granted = facts.granted + ConnectivityPermission.ACCESS_LOCAL_NETWORK
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            }
            h.eventually("native saveable host consumes original Wi-Fi permission result") { h.links.links.size == before + 1 }
            assertNull(feature.selectionRequestedPermission.value)
            assertNull(feature.selectionAction.value)
            assertEquals(ConnectionTarget.WiFi("tab-radio.local", 6219u), h.links.links.last().target)
            assertEquals(ConnectionIntent.WantsConnection(forceFullSync = true), h.manager.connectionIntent)
            gate.complete(Unit)
            h.eventually("native host-restored Wi-Fi connection ready") {
                h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
            }
            assertEquals(before + 1, h.links.links.size)
        }
    }

    @Test @Config(sdk = [37])
    fun nativeSaveableSettingsHostReattachesAPendingWifiGrantReceivedWhileAnotherTabIsVisible() = runTest {
        savedWifiPermissionResultAcrossNativeTabRemoval(grant = true)
    }

    @Test @Config(sdk = [37])
    fun nativeSaveableSettingsHostReattachesAPendingWifiDenialAndPreservesSettingsRecovery() = runTest {
        savedWifiPermissionResultAcrossNativeTabRemoval(grant = false)
    }

    @Test fun nativeSaveableSettingsHostConsumesCancelledScanPermissionResultWithoutRestartingTheScan() = runTest {
        val facts = FakePermissionFacts(sdk = 31)
        val service = BluetoothScanPairingService()
        var starts = 0
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) { starts++ }
            override fun stopScan() = Unit
        })
        withSettings(permissionFacts = facts, pairingService = service, scans = scans) { h, feature ->
            mountNavigationShell(h, feature)
            open(h)
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionScanBluetooth)).performClick()
            compose.waitForIdle()
            assertEquals(OnboardingPermissionKind.BLUETOOTH, feature.selectionRequestedPermission.value)
            compose.onNodeWithTag("tab:CHATS").performClick()
            compose.waitForIdle()
            assertNull(feature.selectionAction.value)
            permissionResult(facts, true, bluetoothPermissions)
            h.settle()
            assertEquals(0, starts)
            assertFalse(service.isPresenting.value)
            compose.onNodeWithTag("tab:SETTINGS").performClick()
            compose.waitForIdle()
            h.eventually("native saveable host consumes cancelled scan request result") {
                feature.selectionRequestedPermission.value == null
            }
            assertNull(feature.selectionAction.value)
            assertFalse(service.isPresenting.value)
            assertEquals(0, starts)
            open(h)
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionScanBluetooth)).performClick()
            compose.waitForIdle()
            h.eventually("only the user's new scan action starts a picker") { service.isPresenting.value && starts == 1 }
            compose.onNodeWithText(compose.activity.getString(L.commonCancel)).performClick()
            compose.waitForIdle()
            h.eventually("new explicitly requested scan completes") {
                !service.isPresenting.value && !h.container.connectionPort.isPairingFlowActive
            }
            assertTrue(h.links.links.isEmpty())
        }
    }

    @Test fun settingsEntrySelectsTheSavedBleIdentityWithFullSyncAndForceReconnect() = runTest {
        withSettings { h, feature ->
            val device = saved(h)
            mount(h, feature)
            open(h)
            h.manager.recordConnectionFailure()
            assertFalse(h.manager.shouldAllowConnection(false))
            val linksBefore = h.links.links.size
            val gate = CompletableDeferred<Unit>()
            h.links.configureRadio = { it.connectGate = gate }
            compose.onNodeWithText(device.nodeName).performClick()
            h.eventually("selected BLE connection entered") { h.links.links.size == linksBefore + 1 }
            selectionTitle().assertDoesNotExist()
            assertEquals(device.id, (h.links.links.last().target as ConnectionTarget.Bluetooth).deviceId)
            assertEquals(ConnectionIntent.WantsConnection(forceFullSync = true), h.manager.connectionIntent)
            gate.complete(Unit)
            h.eventually("selected BLE ready") { h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY }
            assertEquals(device.id, h.manager.connectedDevice?.id)
            assertEquals(device.radioId, h.manager.connectedDevice?.radioId)
            assertEquals(device.publicKey, h.manager.connectedDevice?.publicKey)
            assertEquals(0, h.pairing.removeCalls)
        }
    }

    @Test fun explicitAdapterFlagsReachTheRuntimeWithoutChangingExistingDefaultSync() = runTest {
        withSettings { h, _ ->
            val id = UUID.randomUUID()
            h.manager.recordConnectionFailure()
            try {
                h.container.connectionPort.connect(id, forceReconnect = false)
                fail("An ordinary call must not bypass the open BLE breaker")
            } catch (expected: com.meshcoreone.android.core.runtime.ConnectionError.ConnectionFailed) {
                assertEquals("Connection blocked by circuit breaker (cooling down)", expected.reason)
            }
            assertTrue(h.links.links.isEmpty())
            val gate = CompletableDeferred<Unit>()
            h.links.configureRadio = { it.connectGate = gate }
            val connect = backgroundScope.async {
                h.container.connectionPort.connect(id, forceFullSync = true, forceReconnect = true)
            }
            h.eventually("explicit adapter bypassed the BLE breaker") { h.links.links.isNotEmpty() }
            assertEquals(id, (h.links.links.single().target as ConnectionTarget.Bluetooth).deviceId)
            assertEquals(ConnectionIntent.WantsConnection(forceFullSync = true), h.manager.connectionIntent)
            connect.cancelAndJoin()
            assertFalse(h.appState.connectionUI.showingConnectionFailedAlert)
        }
    }

    @Test @Config(sdk = [37], qualifiers = "en-rUS-w840dp-h900dp")
    fun savedDualTransportPrefersWifiEvenWhenBleIsFirst() = runTest {
        withSettings { h, feature ->
            val device = saved(h)
            val wifi = ConnectionMethod.WiFi("radio.local", 6123u)
            store.saveDevice(device.copy(connectionMethods = (device.connectionMethods + wifi).snapshot()))
            val gate = CompletableDeferred<Unit>()
            h.links.configureRadio = { it.connectGate = gate }
            mount(h, feature)
            open(h)
            compose.onNodeWithText(device.nodeName).performClick()
            h.eventually("Wi-Fi preferred over BLE") { h.links.links.last().target is ConnectionTarget.WiFi }
            assertEquals(ConnectionTarget.WiFi(wifi.host, wifi.port), h.links.links.last().target)
            assertEquals(ConnectionIntent.WantsConnection(forceFullSync = true), h.manager.connectionIntent)
            gate.complete(Unit)
            h.eventually("selected Wi-Fi ready") { h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY }
            assertEquals(DeviceIdentity.deriveUUID(device.publicKey), h.manager.connectedDevice?.id)
            assertEquals(device.radioId, h.manager.connectedDevice?.radioId)
            assertEquals(device.publicKey, h.manager.connectedDevice?.publicKey)
            assertEquals(device.radioId, store.fetchDevice(device.publicKey)?.radioId)
            assertTrue(requireNotNull(h.manager.connectedDevice).connectionMethods.containsAll(device.connectionMethods + wifi))
        }
    }

    @Test fun existingTwoArgumentAdapterCallKeepsFullSyncOff() = runTest {
        withSettings { h, _ ->
            val id = UUID.randomUUID()
            val gate = CompletableDeferred<Unit>()
            h.links.configureRadio = { it.connectGate = gate }
            val connect = backgroundScope.async { h.container.connectionPort.connect(id, forceReconnect = true) }
            h.eventually("default adapter entered") { h.links.links.isNotEmpty() }
            assertEquals(ConnectionIntent.WantsConnection(forceFullSync = false), h.manager.connectionIntent)
            connect.cancelAndJoin()
            assertFalse(h.appState.connectionUI.showingConnectionFailedAlert)
        }
    }

    @Test fun dualTransportRemainsSelectableOverWifiWhenItsBleLinkIsHeldElsewhere() = runTest {
        val externallyConnected = mutableSetOf<UUID>()
        val probe = object : SystemLinkProbe {
            override suspend fun isDeviceConnectedToSystem(deviceId: UUID) = deviceId in externallyConnected
            override suspend fun systemConnectedDeviceIds(): Set<UUID> = externallyConnected.toSet()
        }
        withSettings(systemLinks = probe) { h, feature ->
            val device = saved(h)
            val wifi = ConnectionMethod.WiFi("available-radio.local", 6123u)
            store.saveDevice(device.copy(connectionMethods = (device.connectionMethods + wifi).snapshot()))
            externallyConnected += device.id
            val loaded = feature.deviceSelection()
            assertTrue(device.id in feature.devicesConnectedElsewhere(loaded))
            val gate = CompletableDeferred<Unit>()
            h.links.configureRadio = { it.connectGate = gate }
            val linksBefore = h.links.links.size
            mount(h, feature)
            open(h)
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionConnectedElsewhere)).assertIsDisplayed()
            compose.onNodeWithText(device.nodeName).assertIsEnabled().performClick()
            h.eventually("externally held BLE still permits Wi-Fi") { h.links.links.size == linksBefore + 1 }
            assertEquals(ConnectionTarget.WiFi(wifi.host, wifi.port), h.links.links.last().target)
            assertEquals(ConnectionIntent.WantsConnection(forceFullSync = true), h.manager.connectionIntent)
            gate.complete(Unit)
            h.eventually("Wi-Fi ready despite external BLE") {
                h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
            }
            assertEquals(DeviceIdentity.deriveUUID(device.publicKey), h.manager.connectedDevice?.id)
            assertEquals(device.radioId, h.manager.connectedDevice?.radioId)
            assertEquals(device.publicKey, h.manager.connectedDevice?.publicKey)
            assertEquals(device.radioId, store.fetchDevice(device.publicKey)?.radioId)
            assertTrue(requireNotNull(h.manager.connectedDevice).connectionMethods.containsAll(device.connectionMethods + wifi))
            assertFalse(h.appState.connectionUI.showingConnectionFailedAlert)
            assertEquals(0, h.pairing.removeCalls)
        }
    }

    @Test fun bleOnlyExternallyConnectedDeviceKeepsItsWarningAndCannotConnect() = runTest {
        val externallyConnected = mutableSetOf<UUID>()
        val probe = object : SystemLinkProbe {
            override suspend fun isDeviceConnectedToSystem(deviceId: UUID) = deviceId in externallyConnected
            override suspend fun systemConnectedDeviceIds(): Set<UUID> = externallyConnected.toSet()
        }
        withSettings(systemLinks = probe) { h, feature ->
            val device = saved(h)
            assertTrue(device.connectionMethods.none { it.isWiFi })
            externallyConnected += device.id
            mount(h, feature)
            open(h)
            val linksBefore = h.links.links.size
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionConnectedElsewhere)).assertIsDisplayed()
            compose.onNodeWithText(device.nodeName).assertIsNotEnabled().performClick()
            h.settle()
            assertEquals(linksBefore, h.links.links.size)
            selectionTitle().assertIsDisplayed()
            assertFalse(h.appState.connectionUI.showingConnectionFailedAlert)
            assertEquals(0, h.pairing.removeCalls)
        }
    }

    @Test fun savedAuthenticationFailureRetainsTheSelectedIdentityForGuidedRepair() = runTest {
        withSettings { h, feature ->
            val device = saved(h)
            h.links.configureRadio = { it.connectFailure = BleTransportException(BleError.AuthenticationFailed) }
            mount(h, feature)
            open(h)
            compose.onNodeWithText(device.nodeName).performClick()
            h.eventually("saved authentication failure") { h.appState.connectionUI.showingConnectionFailedAlert }
            assertEquals(device.id, h.appState.connectionUI.failedPairingDeviceId)
            assertEquals(PairingFailureKind.AUTHENTICATION, h.appState.connectionUI.pairingFailureKind)
            assertNotNull(store.fetchDevice(device.id))
            assertEquals(0, h.pairing.removeCalls)
        }
    }

    @Test fun savedOtherAppFailureUsesTheDistinctWarningWithoutForgetting() = runTest {
        withSettings { h, feature ->
            val device = saved(h)
            h.links.configureRadio = { it.connectFailure = BleTransportException(BleError.DeviceConnectedToOtherApp) }
            mount(h, feature)
            open(h)
            compose.onNodeWithText(device.nodeName).performClick()
            h.eventually("other-app warning", virtualBudgetMillis = 3_000, virtualStepMillis = 10) {
                h.appState.connectionUI.otherAppWarningDeviceId == device.id
            }
            assertEquals(5, h.links.links.size)
            assertFalse(h.appState.connectionUI.showingConnectionFailedAlert)
            assertNull(h.appState.connectionUI.failedPairingDeviceId)
            assertNotNull(store.fetchDevice(device.id))
            assertEquals(0, h.pairing.removeCalls)
        }
    }

    @Test fun savedGenericFailureDoesNotBecomePairingFailureOrSuccess() = runTest {
        withSettings { h, feature ->
            val device = saved(h)
            h.links.configureRadio = { it.connectFailure = IllegalStateException("selected link failed") }
            mount(h, feature)
            open(h)
            compose.onNodeWithText(device.nodeName).performClick()
            h.eventually("generic saved failure", virtualBudgetMillis = 3_000, virtualStepMillis = 10) {
                h.appState.connectionUI.showingConnectionFailedAlert
            }
            assertEquals(5, h.links.links.size)
            assertNull(h.appState.connectionUI.pairingFailureKind)
            assertNull(h.appState.connectionUI.failedPairingDeviceId)
            assertNull(h.appState.connectionUI.otherAppWarningDeviceId)
            assertFalse(h.manager.connectionState.isConnected)
            assertNotNull(h.appState.connectionUI.connectionFailedMessage)
            assertNotNull(store.fetchDevice(device.id))
        }
    }

    @Test fun setupRowDismissesSelectorAndQueuesOnlyTheSelectedAccessoryWithoutRemovingIt() = runTest {
        withSettings { h, feature ->
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            h.pairing.registered[first] = "Needs setup"
            h.pairing.registered[second] = "Other accessory"
            var pickerCalls = 0
            h.pairing.discovery = { pickerCalls++; throw DevicePairingError.Cancelled() }
            mount(h, feature)
            open(h)
            compose.onNodeWithText("Needs setup").performClick()
            compose.waitForIdle()
            h.settle()
            selectionTitle().assertDoesNotExist()
            val prompt = requireNotNull(h.appState.connectionUI.pendingSystemPairingSetup)
            assertEquals(listOf(first), prompt.accessories.map { it.id })
            assertNull(h.appState.connectionUI.queuedSystemPairingSetup)
            assertFalse(h.appState.connectionUI.queuedDeviceScanAfterSelectionDismiss)
            assertTrue(h.container.connectionPort.isPairingFlowActive)
            assertEquals(0, h.pairing.removeCalls)
            assertEquals(0, pickerCalls)
            compose.onNodeWithTag("system-pairing-cancel").performClick()
            assertEquals(setOf(first, second), h.pairing.registered.keys)
            assertFalse(h.container.connectionPort.isPairingFlowActive)
        }
    }

    @Test fun scanRowRunsTheActualPickerAfterSelectionDismissalAndDoesNotNavigateToOnboarding() = runTest {
        withSettings { h, feature ->
            var pickerCalls = 0
            h.pairing.discovery = { pickerCalls++; throw DevicePairingError.Cancelled() }
            mount(h, feature)
            open(h)
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionNoPairedDevices)).assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionScanBluetooth)).performClick()
            compose.waitForIdle()
            h.eventually("scan picker completed once") { pickerCalls == 1 && !h.container.connectionPort.isPairingFlowActive }
            selectionTitle().assertDoesNotExist()
            assertFalse(h.appState.connectionUI.queuedDeviceScanAfterSelectionDismiss)
            assertNull(h.appState.connectionUI.queuedSystemPairingSetup)
            assertEquals(AppTab.SETTINGS, h.container.navigation.state.value.selectedTab)
            assertEquals(1, h.container.navigation.state.value.activeStack.size)
            assertFalse(h.appState.connectionUI.showingConnectionFailedAlert)
            assertTrue(h.links.radios.isEmpty())
        }
    }

    @Test fun cancellingSelectorDoesNotConnectOrPairAndCanReopenIt() = runTest {
        withSettings { h, feature ->
            mount(h, feature)
            open(h)
            compose.runOnUiThread { requireNotNull(ShadowDialog.getLatestDialog()).onBackPressed() }
            compose.waitForIdle()
            selectionTitle().assertDoesNotExist()
            assertFalse(h.container.connectionPort.isPairingFlowActive)
            assertTrue(h.links.radios.isEmpty())
            open(h)
        }
    }

    @Test fun wifiActionMountsTheSharedAddressFormWithoutNavigatingOrConnectingOnCancel() = runTest {
        withSettings { h, feature ->
            mount(h, feature)
            open(h)
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionConnectViaWifi)).performClick()
            compose.waitForIdle()
            selectionTitle().assertDoesNotExist()
            compose.onNodeWithText(compose.activity.getString(O.wifiConnectionTitle)).assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(L.commonCancel)).performClick()
            compose.onNodeWithText(compose.activity.getString(O.wifiConnectionTitle)).assertDoesNotExist()
            assertTrue(h.links.radios.isEmpty())
            assertEquals(AppTab.SETTINGS, h.container.navigation.state.value.selectedTab)
            assertFalse(h.container.connectionPort.isPairingFlowActive)
        }
    }

    @Test fun manualWifiFormSubmitsEnteredEndpointWithFullSyncAndDismissesOnSuccess() = runTest {
        withSettings { h, feature ->
            val gate = CompletableDeferred<Unit>()
            h.links.configureRadio = { it.connectGate = gate }
            mount(h, feature)
            open(h)
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionConnectViaWifi)).performClick()
            compose.onNode(hasSetTextAction() and hasText(compose.activity.getString(O.wifiConnectionIpAddressPlaceholder)))
                .performScrollTo().performTextReplacement("manual-radio.local")
            compose.onNode(hasSetTextAction() and hasText(compose.activity.getString(O.wifiConnectionPortPlaceholder)))
                .performScrollTo().performTextReplacement("6217")
            compose.onNode(hasText(compose.activity.getString(O.wifiConnectionConnect)) and hasClickAction())
                .performScrollTo().assertIsEnabled().performClick()
            h.eventually("manual Wi-Fi reaches the process adapter") { h.links.links.isNotEmpty() }
            assertEquals(ConnectionTarget.WiFi("manual-radio.local", 6217u), h.links.links.single().target)
            assertEquals(ConnectionIntent.WantsConnection(forceFullSync = true), h.manager.connectionIntent)
            compose.onNodeWithText(compose.activity.getString(O.wifiConnectionTitle)).assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(L.commonCancel)).assertIsNotEnabled()
            gate.complete(Unit)
            h.eventually("manual Wi-Fi ready") {
                h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
            }
            h.eventually("manual Wi-Fi completion dismisses the form") {
                compose.onAllNodesWithText(compose.activity.getString(O.wifiConnectionTitle))
                    .fetchSemanticsNodes().isEmpty()
            }
            compose.waitForIdle()
            compose.onNodeWithText(compose.activity.getString(O.wifiConnectionTitle)).assertDoesNotExist()
            selectionTitle().assertDoesNotExist()
            assertEquals(AppTab.SETTINGS, h.container.navigation.state.value.selectedTab)
            assertFalse(h.appState.connectionUI.showingConnectionFailedAlert)
            assertFalse(h.container.connectionPort.isPairingFlowActive)
            assertEquals(0, h.pairing.removeCalls)
        }
    }

    @Test fun failedSavedDeviceReadSurfacesAnErrorRatherThanAnEmptySuccessfulSelector() = runTest {
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun fetchDevices(): SnapshotList<DeviceDTO> = throw IllegalStateException("saved-device read failed")
        }
        withSettings(persistence) { h, feature ->
            mount(h, feature)
            compose.onNodeWithText(compose.activity.getString(S.deviceConnect)).performScrollTo().performClick()
            compose.waitForIdle()
            h.eventually("saved-device read failure surfaced") { feature.failure.value != null }
            compose.waitForIdle()
            assertEquals("saved-device read failed", feature.failure.value?.message)
            compose.onNodeWithText(compose.activity.getString(S.deviceSelectionNoPairedDevices)).assertDoesNotExist()
            selectionTitle().assertDoesNotExist()
            assertTrue(h.links.radios.isEmpty())
            assertFalse(h.container.connectionPort.isPairingFlowActive)
        }
    }
}
