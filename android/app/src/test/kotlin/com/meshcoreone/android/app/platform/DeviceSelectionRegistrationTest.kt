// AndroidOnly: WP-317 TS-31 mounted Settings entry/actions over the production Room/process/connection adapters.
package com.meshcoreone.android.app.platform

import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
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
import com.meshcoreone.android.feature.onboarding.ui.OnboardingFlow
import com.meshcoreone.android.core.connectivity.permissions.ConnectivityPermission
import com.meshcoreone.android.core.connectivity.pairing.BluetoothScanPairingService
import com.meshcoreone.android.core.connectivity.pairing.BluetoothEndpoint
import com.meshcoreone.android.core.connectivity.pairing.DeviceEndpointIdentity
import com.meshcoreone.android.core.connectivity.pairing.EndpointStability
import com.meshcoreone.android.core.connectivity.pairing.DevicePairingService
import com.meshcoreone.android.core.connectivity.pairing.InMemoryKnownEndpointStore
import com.meshcoreone.android.core.connectivity.pairing.KnownEndpointStore
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
import com.meshcoreone.android.feature.settings.device.DeviceSelectionListBuilder
import com.meshcoreone.android.feature.settings.device.ui.DeviceSelectionSheet
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onStart
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

    private class PermissionDialogRequest(val requestCode: Int, val permissions: Array<String>)

    private class PermissionDialog(
        private val host: android.app.Activity,
        private val allowed: Set<ConnectivityPermission>,
    ) : ActivityCompat.PermissionCompatDelegate {
        var request: PermissionDialogRequest? = null
            private set
        var launches = 0
            private set

        override fun requestPermissions(activity: android.app.Activity, permissions: Array<String>, requestCode: Int): Boolean {
            if (activity !== host) return false
            check(permissions.isNotEmpty() && permissions.all { name -> allowed.any { it.manifestName == name } }) {
                "Unexpected permission request: ${permissions.toList()}"
            }
            check(request == null) { "A permission dialog is already awaiting its result" }
            launches++
            request = PermissionDialogRequest(requestCode, permissions.copyOf())
            return true
        }

        override fun onActivityResult(activity: android.app.Activity, requestCode: Int, resultCode: Int, data: android.content.Intent?) = false

        fun takeRequest(): PermissionDialogRequest = requireNotNull(request).also { request = null }
    }

    private class HeldKnownEndpointStore(
        private val heldDeviceId: UUID,
        private val failureAfterRelease: Exception? = null,
    ) : KnownEndpointStore {
        private val delegate = InMemoryKnownEndpointStore()
        val firstWrite = CompletableDeferred<BluetoothEndpoint>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val rolledBack = CompletableDeferred<Unit>()

        override suspend fun endpoint(deviceId: UUID): BluetoothEndpoint? = delegate.endpoint(deviceId)
        override suspend fun remember(endpoint: BluetoothEndpoint) =
            remember(endpoint, DeviceEndpointIdentity.stability(endpoint.address, bonded = false))

        override suspend fun remember(endpoint: BluetoothEndpoint, stability: EndpointStability) {
            if (endpoint.deviceId == heldDeviceId && firstWrite.complete(endpoint)) {
                withContext(NonCancellable) {
                    releaseFirstWrite.await()
                    delegate.remember(endpoint, stability)
                    failureAfterRelease?.let { throw it }
                }
            } else {
                delegate.remember(endpoint, stability)
            }
        }

        override suspend fun forget(deviceId: UUID) {
            delegate.forget(deviceId)
            if (deviceId == heldDeviceId) rolledBack.complete(Unit)
        }
    }

    private var permissionDialog: PermissionDialog? = null

    @android.annotation.SuppressLint("RestrictedApi")
    private suspend fun TestScope.withSettings(
        persistence: PersistenceStoreProtocol = store,
        systemLinks: SystemLinkProbe = NoSystemLinks,
        permissionFacts: FakePermissionFacts = FakePermissionFacts(
            sdk = android.os.Build.VERSION.SDK_INT, granted = ConnectivityPermission.entries.toSet(),
        ),
        pairingService: DevicePairingService? = null,
        scans: BleScanCoordinator = newScans(),
        knownEndpoints: KnownEndpointStore = SyntheticEndpoints(),
        openSettings: () -> Unit = {},
        requests: RequestedPermissions = RequestedPermissions(),
        controlledLocalNetworkDialog: Boolean = false,
        controlledBluetoothDialog: Boolean = false,
        test: suspend (ContainerHarness, AppSettingsFeature) -> Unit,
    ) {
        DemoModeManager.installStandardDefaults(demoDefaults)
        val androidPermissions = shadowOf(compose.activity.application)
        androidPermissions.grantPermissions(*permissionFacts.granted.map { it.manifestName }.toTypedArray())
        androidPermissions.denyPermissions(*(ConnectivityPermission.entries.toSet() - permissionFacts.granted)
            .map { it.manifestName }.toTypedArray())
        ConnectivityPermission.entries.filter { permissionFacts.sdk >= it.minimumSdk }.forEach { permission ->
            assertEquals(permission.manifestName,
                if (permission in permissionFacts.granted) android.content.pm.PackageManager.PERMISSION_GRANTED
                else android.content.pm.PackageManager.PERMISSION_DENIED,
                compose.activity.checkSelfPermission(permission.manifestName))
        }
        val platform = OnboardingPlatform(
            WriteBehindOnboardingFlags(emptyMap(), backgroundScope, { _, _ -> }),
            permissionFacts, requests = requests, scans = scans, openAppSettings = openSettings, sdkInt = permissionFacts.sdk,
        )
        val h = ContainerHarness(
            this, store, persistenceOverride = persistence, systemLinks = systemLinks,
            pairingService = pairingService, scans = scans, onboardingPlatform = platform, knownEndpoints = knownEndpoints,
        )
        val storage = MeshCoreStorage.get(compose.activity)
        val previousDelegate = ActivityCompat.getPermissionCompatDelegate()
        try {
            if (controlledLocalNetworkDialog || controlledBluetoothDialog) {
                if (controlledLocalNetworkDialog) assertEquals(37, android.os.Build.VERSION.SDK_INT)
                assertNull(previousDelegate)
                permissionDialog = PermissionDialog(compose.activity,
                    if (controlledBluetoothDialog) bluetoothPermissions else setOf(ConnectivityPermission.ACCESS_LOCAL_NETWORK))
                ActivityCompat.setPermissionCompatDelegate(permissionDialog)
            }
            val feature = AppSettingsFeature.create(h.container, storage)
            test(h, feature)
        } finally {
            if (controlledLocalNetworkDialog || controlledBluetoothDialog) {
                ActivityCompat.setPermissionCompatDelegate(previousDelegate)
                permissionDialog = null
            }
            compose.runOnUiThread { compose.activity.setContent {} }
            h.container.close()
            storage.close()
        }
    }

    private fun mount(h: ContainerHarness, feature: AppSettingsFeature, includeConnectionRecovery: Boolean = false) {
        compose.runOnUiThread {
            h.container.navigation.selectTab(AppTab.SETTINGS)
            compose.activity.setContent {
                val navigation by h.container.navigation.state.collectAsState()
                MeshCoreTheme(motionScale = 0f) {
                    if (navigation.selectedTab == AppTab.SETTINGS) {
                        AppSettingsContent(feature, navigation.activeStack.last().destination, h.container.navigation)
                    }
                    SystemPairingSetupHost(h.appState)
                    if (includeConnectionRecovery) AppConnectionRecoveryDialogs(h.container)
                }
            }
        }
    }

    private fun mountNavigationShell(h: ContainerHarness, feature: AppSettingsFeature, withOnboarding: Boolean = false) {
        compose.runOnUiThread {
            h.container.navigation.selectTab(AppTab.SETTINGS)
            compose.activity.setContent {
                MeshCoreTheme(motionScale = 0f) {
                    NativeNavigationShell(
                        h.container.navigation, settings = feature,
                        onboarding = if (withOnboarding) requireNotNull(h.container.onboarding).forRerun() else null,
                    )
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

    private fun clickSelectionRow(label: String) {
        compose.onNodeWithTag("device-selection-list").performScrollToNode(hasText(label))
        compose.onNodeWithText(label).assertIsDisplayed().performClick()
    }

    private fun permissionSettingsButton() = compose.onNode(
        hasText(compose.activity.getString(O.permissionsOpenSettings)) and hasClickAction() and hasAnyAncestor(isDialog()),
    )

    private fun assertLocalNetworkRequestCanStart(h: ContainerHarness) {
        val port = requireNotNull(h.container.onboarding).permissions
        val names = port.permissionsFor(OnboardingPermissionKind.LOCAL_NETWORK, android.os.Build.VERSION.SDK_INT)
        assertEquals(37, android.os.Build.VERSION.SDK_INT)
        assertEquals(listOf(ConnectivityPermission.ACCESS_LOCAL_NETWORK.manifestName), names)
        assertEquals(com.meshcoreone.android.feature.onboarding.PermissionStatus.NOT_DETERMINED, port.snapshot.value.localNetwork)
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            androidx.core.content.ContextCompat.checkSelfPermission(compose.activity, names.single()))
        assertNull("The real permission contract must not complete synchronously before the request",
            ActivityResultContracts.RequestMultiplePermissions().getSynchronousResult(compose.activity, names.toTypedArray()))
    }

    private fun assertLocalNetworkRequestPending(h: ContainerHarness, feature: AppSettingsFeature) {
        val request = shadowOf(compose.activity).lastRequestedPermission
        val platform = requireNotNull(h.container.onboarding)
        val context = "action=${feature.selectionAction.value}, denied=${feature.selectionPermissionDenied.value}, " +
            "snapshot=${platform.permissions.snapshot.value}, " +
            "request=${request?.requestCode}:${request?.requestedPermissions?.toList()}"
        assertEquals(context, OnboardingPermissionKind.LOCAL_NETWORK, feature.selectionRequestedPermission.value)
        permissionDialog?.let { dialog ->
            assertEquals(1, dialog.launches)
            assertArrayEquals(arrayOf(ConnectivityPermission.ACCESS_LOCAL_NETWORK.manifestName), requireNotNull(dialog.request).permissions)
        }
    }

    private fun ContainerHarness.eventuallyMounted(message: String, condition: () -> Boolean) =
        eventually(message) {
            compose.waitForIdle()
            condition()
        }

    private fun openManualWifi(h: ContainerHarness) {
        open(h)
        clickSelectionRow(compose.activity.getString(S.deviceSelectionConnectViaWifi))
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
        val request = permissionDialog?.takeRequest() ?: requireNotNull(shadowOf(compose.activity).lastRequestedPermission)
            .let { PermissionDialogRequest(it.requestCode, it.requestedPermissions) }
        assertArrayEquals(permissions.map { it.manifestName }.sorted().toTypedArray(), request.permissions)
        if (granted) {
            facts.granted = facts.granted + permissions
            shadowOf(compose.activity.application).grantPermissions(*permissions.map { it.manifestName }.toTypedArray())
        } else {
            shadowOf(compose.activity.application).denyPermissions(*permissions.map { it.manifestName }.toTypedArray())
        }
        compose.runOnUiThread {
            compose.activity.onRequestPermissionsResult(
                request.requestCode, request.permissions,
                IntArray(request.permissions.size) {
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
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
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
                permissionSettingsButton().performClick()
                assertEquals(1, openedSettings)
                facts.granted = bluetoothPermissions
                shadowOf(compose.activity.application).grantPermissions(*bluetoothPermissions.map { it.manifestName }.toTypedArray())
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            }
            h.eventuallyMounted("Bluetooth grant starts exactly one fallback scan") { service.isPresenting.value && starts == 1 }
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

    private suspend fun TestScope.fallbackScanFailureControl(
        startup: Boolean,
        direct: Boolean = false,
        onboarding: Boolean = false,
    ) {
        val service = BluetoothScanPairingService()
        val failure = IllegalStateException(if (startup) "Scanner could not start" else "Scanner callback failed")
        var failScan: ((Throwable) -> Unit)? = null
        var starts = 0
        var stops = 0
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) {
                starts++
                if (startup) throw failure
                failScan = onFailure
            }
            override fun stopScan() { stops++ }
        })
        withSettings(pairingService = service, scans = scans) { h, feature ->
            if (onboarding) {
                val dependencies = requireNotNull(h.container.onboarding).forRerun()
                compose.runOnUiThread {
                    compose.activity.setContent {
                        MeshCoreTheme(motionScale = 0f) {
                            OnboardingFlow(dependencies, {})
                            AppConnectionRecoveryDialogs(h.appState)
                        }
                    }
                }
                compose.onNodeWithText(compose.activity.getString(O.welcomeGetStarted)).assertIsDisplayed().performClick()
                compose.onNode(hasText(compose.activity.getString(O.permissionsContinue)) and hasClickAction())
                    .assertIsDisplayed().performClick()
            } else {
                mount(h, feature, includeConnectionRecovery = true)
            }
            val directJob = if (onboarding) {
                compose.onNodeWithText(compose.activity.getString(O.deviceScanAddDevice)).performScrollTo().performClick()
                null
            } else if (direct) h.appState.startDeviceScan() else {
                open(h)
                clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
                null
            }
            if (!startup) {
                h.eventuallyMounted("fallback scanner callback installed") { failScan != null }
                requireNotNull(failScan)(failure)
            }
            h.eventuallyMounted("scanner error resolves waiting pairing and reaches connection recovery") {
                if (onboarding) {
                    !h.appState.connectionUI.showingConnectionFailedAlert &&
                        compose.onAllNodesWithText(requireNotNull(failure.message)).fetchSemanticsNodes().isNotEmpty()
                } else h.appState.connectionUI.showingConnectionFailedAlert &&
                    h.appState.connectionUI.connectionFailedMessage ==
                    com.meshcoreone.android.core.ui.UiText.Verbatim(requireNotNull(failure.message))
            }
            h.eventuallyMounted("scanner error cancels pairing and scan") {
                !service.isPresenting.value && !h.container.connectionPort.isPairingFlowActive &&
                    (if (onboarding) !h.appState.connectionUI.isBusy
                    else (directJob ?: feature.selectionScanJob)?.isCompleted == true)
            }
            assertEquals(1, starts)
            assertEquals(if (startup) 0 else 1, stops)
            assertFalse(scans.isScanning)
            assertTrue(h.links.links.isEmpty())
            compose.onNodeWithText(requireNotNull(failure.message)).assertIsDisplayed()
            if (onboarding) assertFalse(h.appState.connectionUI.showingConnectionFailedAlert)
            compose.onNodeWithText(compose.activity.getString(L.commonOk)).assertIsDisplayed().performClick()
            assertNull(feature.failure.value)
            assertFalse(h.appState.connectionUI.showingConnectionFailedAlert)
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            if (onboarding) {
                compose.onNodeWithText(compose.activity.getString(O.deviceScanAddDevice)).assertIsDisplayed().assertIsEnabled()
                compose.onNodeWithText(compose.activity.getString(O.regionTitle)).assertDoesNotExist()
            }
            assertEquals(1, starts)
        }
    }

    @Test fun fallbackScannerStartupFailureCancelsPairingAndPresentsSettingsError() = runTest {
        fallbackScanFailureControl(startup = true)
    }

    @Test fun fallbackScannerCallbackFailureCancelsPairingAndPresentsSettingsError() = runTest {
        fallbackScanFailureControl(startup = false)
    }

    @Test fun publicScanStartupFailureResolvesItsPairingAndPresentsSettingsError() = runTest {
        fallbackScanFailureControl(startup = true, direct = true)
    }

    private suspend fun TestScope.onboardingScanFailureControl(startup: Boolean) {
        val resolver = compose.activity.contentResolver
        val previousScale = android.provider.Settings.Global.getFloat(
            resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        )
        try {
            android.provider.Settings.Global.putFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            fallbackScanFailureControl(startup = startup, onboarding = true)
        } finally {
            android.provider.Settings.Global.putFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, previousScale)
        }
    }

    @Test fun onboardingScannerStartupFailureResolvesPairingAndShowsOneVisibleError() = runTest {
        onboardingScanFailureControl(startup = true)
    }

    @Test fun onboardingScannerCallbackFailureResolvesPairingAndShowsOneVisibleError() = runTest {
        onboardingScanFailureControl(startup = false)
    }

    private suspend fun TestScope.savedBlePermissionControl(grant: Boolean, cancel: Boolean = false, scanAfter: Boolean = false) {
        val facts = FakePermissionFacts(sdk = 31)
        val service = BluetoothScanPairingService()
        var openedSettings = 0
        withSettings(
            permissionFacts = facts, pairingService = service, openSettings = { openedSettings++ },
            controlledBluetoothDialog = true,
        ) { h, feature ->
            val device = saved(h)
            val before = h.links.links.size
            mount(h, feature)
            open(h)
            compose.onNodeWithText(device.nodeName).performClick()
            compose.waitForIdle()
            assertEquals(OnboardingPermissionKind.BLUETOOTH, feature.selectionRequestedPermission.value)
            assertEquals(before, h.links.links.size)
            assertFalse(service.isPresenting.value)
            permissionResult(facts, grant, setOf(ConnectivityPermission.BLUETOOTH_CONNECT))
            if (!grant) {
                assertEquals(before, h.links.links.size)
                permissionSettingsButton().assertIsDisplayed()
                if (cancel) {
                    compose.onNodeWithText(compose.activity.getString(L.commonCancel)).performClick()
                    facts.granted = setOf(ConnectivityPermission.BLUETOOTH_CONNECT)
                    shadowOf(compose.activity.application).grantPermissions(ConnectivityPermission.BLUETOOTH_CONNECT.manifestName)
                    compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                    compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                    h.settle()
                    assertEquals(before, h.links.links.size)
                    assertNull(feature.selectionAction.value)
                    return@withSettings
                }
                permissionSettingsButton().performClick()
                assertEquals(1, openedSettings)
                facts.granted = setOf(ConnectivityPermission.BLUETOOTH_CONNECT)
                shadowOf(compose.activity.application).grantPermissions(ConnectivityPermission.BLUETOOTH_CONNECT.manifestName)
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            }
            h.eventually("CONNECT-only saved BLE resumes selected identity") { h.links.links.size == before + 1 }
            assertEquals(device.id, (h.links.links.last().target as ConnectionTarget.Bluetooth).deviceId)
            assertEquals(ConnectionIntent.WantsConnection(forceFullSync = true), h.manager.connectionIntent)
            assertFalse(service.isPresenting.value)
            assertFalse(ConnectivityPermission.BLUETOOTH_SCAN in facts.granted)
            h.eventually("saved BLE ready after CONNECT grant") {
                h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
            }
            assertEquals(device.id, h.manager.connectedDevice?.id)
            assertEquals(before + 1, h.links.links.size)
            if (scanAfter) {
                h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
                h.settle()
                open(h)
                clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
                compose.waitForIdle()
                h.settle()
                h.eventuallyMounted("SCAN action advances after a CONNECT-only grant") {
                    feature.selectionRequestedPermission.value != null || service.isPresenting.value || feature.failure.value != null
                }
                val context = "action=${feature.selectionAction.value}, denied=${feature.selectionPermissionDenied.value}, " +
                    "blocker=${feature.selectionScanBlocker.value}, permissions=${facts.granted}, " +
                    "requested=${shadowOf(compose.activity).lastRequestedPermission?.requestedPermissions?.toList()}"
                assertEquals(context, OnboardingPermissionKind.BLUETOOTH, feature.selectionRequestedPermission.value)
                assertFalse(feature.selectionPermissionDenied.value)
                assertFalse(service.isPresenting.value)
                assertEquals(2, requireNotNull(permissionDialog).launches)
                permissionResult(facts, true, bluetoothPermissions)
                h.eventuallyMounted("SCAN remains requestable after a CONNECT-only grant") { service.isPresenting.value }
                compose.onNodeWithText(compose.activity.getString(L.commonCancel)).performClick()
                h.eventually("scan completes after CONNECT-only permission family") { !service.isPresenting.value }
            }
        }
    }

    @Test fun savedBleRequestsOnlyConnectAndResumesWithoutScanPermission() = runTest {
        savedBlePermissionControl(grant = true)
    }

    @Test fun savedBleDeniedConnectOffersSettingsAndResumesTheSelectedIdentity() = runTest {
        savedBlePermissionControl(grant = false)
    }

    @Test fun savedBleDeniedConnectCancellationDoesNotReconnectAfterRecovery() = runTest {
        savedBlePermissionControl(grant = false, cancel = true)
    }

    @Test fun savedBleConnectGrantDoesNotTreatTheUnaskedFallbackScanAsDenied() = runTest {
        savedBlePermissionControl(grant = true, scanAfter = true)
    }

    @Test fun savedBleWithConnectGrantedDoesNotRequestMissingScanPermission() = runTest {
        val facts = FakePermissionFacts(sdk = 31, granted = setOf(ConnectivityPermission.BLUETOOTH_CONNECT))
        withSettings(permissionFacts = facts, pairingService = BluetoothScanPairingService()) { h, feature ->
            val device = saved(h)
            val before = h.links.links.size
            mount(h, feature)
            val previousRequest = shadowOf(compose.activity).lastRequestedPermission
            open(h)
            compose.onNodeWithText(device.nodeName).performClick()
            compose.waitForIdle()
            h.eventually("CONNECT-only saved BLE connects without a scan request") { h.links.links.size == before + 1 }
            assertSame(previousRequest, shadowOf(compose.activity).lastRequestedPermission)
            assertNull(feature.selectionAction.value)
            assertNull(feature.selectionRequestedPermission.value)
            assertFalse(ConnectivityPermission.BLUETOOTH_SCAN in facts.granted)
        }
    }

    private suspend fun TestScope.setupPermissionControl(grant: Boolean, cancel: Boolean = false) {
        val facts = FakePermissionFacts(sdk = 37)
        var openedSettings = 0
        withSettings(
            permissionFacts = facts, openSettings = { openedSettings++ }, controlledBluetoothDialog = true,
        ) { h, feature ->
            val selected = UUID.randomUUID()
            val other = UUID.randomUUID()
            h.pairing.registered[selected] = "Selected setup"
            h.pairing.registered[other] = "Other setup"
            mount(h, feature)
            open(h)
            clickSelectionRow("Selected setup")
            compose.waitForIdle()
            h.settle()
            assertNull(h.appState.connectionUI.pendingSystemPairingSetup)
            assertNull(h.appState.connectionUI.queuedSystemPairingSetup)
            assertEquals(0, h.pairing.removeCalls)
            assertEquals(setOf(selected, other), h.pairing.registered.keys)
            h.eventuallyMounted(
                "setup requests CONNECT before showing confirmation " +
                    "(action=${feature.selectionAction.value}, requested=${feature.selectionRequestedPermission.value}, " +
                    "denied=${feature.selectionPermissionDenied.value}, visible=${feature.selectionVisible.value})",
            ) {
                feature.selectionRequestedPermission.value == OnboardingPermissionKind.BLUETOOTH
            }
            assertEquals(1, requireNotNull(permissionDialog).launches)
            assertFalse(feature.selectionPermissionDenied.value)
            permissionResult(facts, grant, setOf(ConnectivityPermission.BLUETOOTH_CONNECT))
            if (!grant) {
                permissionSettingsButton().assertIsDisplayed()
                assertNull(h.appState.connectionUI.pendingSystemPairingSetup)
                if (cancel) {
                    compose.onNodeWithText(compose.activity.getString(L.commonCancel)).performClick()
                } else {
                    permissionSettingsButton().performClick()
                    assertEquals(1, openedSettings)
                }
                facts.granted = setOf(ConnectivityPermission.BLUETOOTH_CONNECT)
                shadowOf(compose.activity.application).grantPermissions(ConnectivityPermission.BLUETOOTH_CONNECT.manifestName)
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            }
            compose.waitForIdle()
            h.settle()
            assertEquals(0, h.pairing.removeCalls)
            assertEquals(setOf(selected, other), h.pairing.registered.keys)
            if (cancel) {
                assertNull(h.appState.connectionUI.pendingSystemPairingSetup)
                assertNull(h.appState.connectionUI.queuedSystemPairingSetup)
                assertFalse(h.container.connectionPort.isPairingFlowActive)
            } else {
                h.eventuallyMounted("selected setup prompt appears after CONNECT grant") {
                    h.appState.connectionUI.pendingSystemPairingSetup != null
                }
                assertEquals(listOf(selected), requireNotNull(h.appState.connectionUI.pendingSystemPairingSetup).accessories.map { it.id })
                compose.onNodeWithTag("system-pairing-cancel").performClick()
                assertFalse(h.container.connectionPort.isPairingFlowActive)
            }
            assertTrue(h.links.links.isEmpty())
        }
    }

    @Test @Config(sdk = [37])
    fun setupRequestsConnectBeforeQueuingAnyDestructiveConfirmation() = runTest {
        setupPermissionControl(grant = true)
    }

    @Test @Config(sdk = [37])
    fun setupDeniedConnectKeepsAssociationsAndResumesOnlySelectedSetupAfterRecovery() = runTest {
        setupPermissionControl(grant = false)
    }

    @Test @Config(sdk = [37])
    fun setupDeniedConnectCancellationKeepsAllAssociationsAfterRecovery() = runTest {
        setupPermissionControl(grant = false, cancel = true)
    }

    private suspend fun TestScope.companionConnectDenialControl(setup: Boolean) {
        val facts = FakePermissionFacts(sdk = 31)
        withSettings(permissionFacts = facts, controlledBluetoothDialog = true) { h, feature ->
            val id = if (setup) UUID.randomUUID().also { h.pairing.registered[it] = "Denied setup" }
                else saved(h).id
            val label = if (setup) "Denied setup" else requireNotNull(store.fetchDevice(id)).nodeName
            val before = h.links.links.size
            mount(h, feature)
            open(h)
            clickSelectionRow(label)
            h.eventuallyMounted("companion selection requests CONNECT") {
                feature.selectionRequestedPermission.value == OnboardingPermissionKind.BLUETOOTH
            }
            permissionResult(facts, granted = false, permissions = setOf(ConnectivityPermission.BLUETOOTH_CONNECT))
            permissionSettingsButton().assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(L.commonCancel)).performClick()
            assertEquals(
                com.meshcoreone.android.feature.onboarding.PermissionStatus.DENIED,
                requireNotNull(h.container.onboarding).permissions.snapshot.value.bluetooth,
            )
            open(h)
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
            h.eventuallyMounted("companion scan preserves CONNECT denial recovery") {
                feature.selectionAction.value == AppSettingsFeature.SelectionAction.Scan && feature.selectionPermissionDenied.value
            }
            permissionSettingsButton().assertIsDisplayed()
            assertNull(feature.selectionRequestedPermission.value)
            assertEquals(1, requireNotNull(permissionDialog).launches)
            assertEquals(before, h.links.links.size)
            assertEquals(0, h.pairing.removeCalls)
            assertNull(h.appState.connectionUI.pendingSystemPairingSetup)
            compose.onNodeWithText(compose.activity.getString(L.commonCancel)).performClick()
            assertNull(feature.selectionAction.value)
        }
    }

    @Test fun companionSavedConnectDenialRemainsDeniedForPairingInsteadOfRequestingAgain() = runTest {
        companionConnectDenialControl(setup = false)
    }

    @Test fun companionSetupConnectDenialRemainsDeniedForPairingInsteadOfRequestingAgain() = runTest {
        companionConnectDenialControl(setup = true)
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
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
            compose.waitForIdle()
            h.settle()
            compose.onNodeWithText(compose.activity.getString(O.deviceScannerBluetoothUnauthorizedTitle)).assertIsDisplayed()
            permissionSettingsButton().assertIsDisplayed()
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
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
            compose.waitForIdle()
            h.settle()
            compose.onNodeWithText(compose.activity.getString(O.deviceScannerBluetoothOffTitle)).assertIsDisplayed()
            assertSame(beforeRequest, shadowOf(compose.activity).lastRequestedPermission)
            assertEquals(0, starts)
            assertFalse(service.isPresenting.value)
            permissionSettingsButton().performClick()
            assertEquals(1, openedSettings)
            facts.bluetoothEnabled = true
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            h.eventuallyMounted("adapter recovery starts one scan") { service.isPresenting.value && starts == 1 }
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
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
            compose.waitForIdle()
            h.eventuallyMounted("initial authorized scan") { starts == 1 }
            facts.granted = emptySet()
            shadowOf(compose.activity.application).denyPermissions(*bluetoothPermissions.map { it.manifestName }.toTypedArray())
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitForIdle()
            h.eventually("revocation releases scan collector") { stops == 1 }
            assertTrue(service.isPresenting.value)
            permissionResult(facts, false, bluetoothPermissions)
            assertEquals(1, starts)
            permissionSettingsButton().performClick()
            facts.granted = bluetoothPermissions
            shadowOf(compose.activity.application).grantPermissions(*bluetoothPermissions.map { it.manifestName }.toTypedArray())
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            h.eventuallyMounted("restored permission resumes the existing picker") { starts == 2 }
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
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
            compose.waitForIdle()
            h.eventuallyMounted("picker visible before tab transition") { service.isPresenting.value && starts == 1 }
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

    @Test fun cancellingPermissionBlockedPickerDoesNotRestartAfterGrantOrResume() = runTest {
        val facts = FakePermissionFacts(sdk = 31, granted = bluetoothPermissions)
        val service = BluetoothScanPairingService()
        var starts = 0
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) { starts++ }
            override fun stopScan() = Unit
        })
        withSettings(
            permissionFacts = facts, pairingService = service, scans = scans, controlledBluetoothDialog = true,
        ) { h, feature ->
            mount(h, feature)
            open(h)
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
            h.eventuallyMounted("picker scans before permission is revoked") { starts == 1 && service.isPresenting.value }
            facts.granted = setOf(ConnectivityPermission.BLUETOOTH_CONNECT)
            shadowOf(compose.activity.application).denyPermissions(ConnectivityPermission.BLUETOOTH_SCAN.manifestName)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            h.eventuallyMounted("live discovery retains its missing permission action") {
                feature.selectionRequestedPermission.value == OnboardingPermissionKind.BLUETOOTH
            }
            compose.onNode(
                hasText(compose.activity.getString(L.commonCancel)) and
                    hasAnyAncestor(hasTestTag("settings-scan-picker")),
            ).performClick()
            h.eventuallyMounted("picker cancellation clears retained scan intent") {
                !service.isPresenting.value && feature.selectionAction.value == null &&
                    !h.container.connectionPort.isPairingFlowActive
            }
            permissionResult(facts, granted = true, permissions = bluetoothPermissions)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitForIdle()
            h.settle()
            assertNull(feature.selectionAction.value)
            assertFalse(service.isPresenting.value)
            assertEquals(1, starts)
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
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
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
                clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
                compose.waitForIdle()
                h.eventuallyMounted("first scan awaiting discovery") { service.isPresenting.value && starts == 1 }
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
                clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
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
                h.eventuallyMounted("new scan starts only after old job finishes") {
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
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
            compose.waitForIdle()
            h.eventually("chosen radio rendered") { compose.onAllNodesWithText("Chosen radio").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("Chosen radio").performClick()
            h.eventually("selection ownership is released before the pairing continuation resumes") {
                !service.isPresenting.value && !feature.ownsSelectionScan
            }
            h.eventually("chosen radio connection entered", virtualBudgetMillis = 3_000) { h.links.links.size == 1 }
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
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
            compose.waitForIdle()
            h.eventuallyMounted("picker pending before recreation") { service.isPresenting.value && starts == 1 }
            compose.activityRule.scenario.recreate()
            mount(h, feature)
            h.eventuallyMounted("new Activity owns the sole scan collector") { starts == 2 && stops == 1 }
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
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
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
            shadowOf(compose.activity.application).grantPermissions(*bluetoothPermissions.map { it.manifestName }.toTypedArray())
            compose.runOnUiThread {
                compose.activity.onRequestPermissionsResult(
                    request.requestCode, request.requestedPermissions,
                    IntArray(request.requestedPermissions.size) { android.content.pm.PackageManager.PERMISSION_GRANTED },
                )
            }
            compose.waitForIdle()
            h.eventuallyMounted("recreated permission result starts one pairing") { service.isPresenting.value && starts == 1 }
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
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
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
        val address = "4A:00:00:00:00:01"
        val id = DeviceEndpointIdentity.deviceId(address)
        val endpoints = SharedPreferencesKnownEndpoints(compose.activity)
        var stopped = false
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) {
                onDevice(DiscoveredDevice(id, address, "Fallback radio", -40))
            }
            override fun stopScan() { stopped = true }
        })
        withSettings(pairingService = service, scans = scans, knownEndpoints = endpoints) { h, feature ->
            assertNull(endpoints.endpoint(id))
            mount(h, feature)
            open(h)
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
            compose.waitForIdle()
            h.eventually("fallback discovery rendered") {
                compose.onAllNodesWithText("Fallback radio").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText("Fallback radio").performClick()
            h.eventually("fallback selected radio ready", virtualBudgetMillis = 3_000) {
                h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
            }
            assertEquals(id, (h.links.links.single().target as ConnectionTarget.Bluetooth).deviceId)
            assertEquals(id, h.manager.connectedDevice?.id)
            assertEquals(BluetoothEndpoint(id, address, null), endpoints.endpoint(id))
            assertNull(SharedPreferencesKnownEndpoints(compose.activity).endpoint(id))
            assertFalse(service.isPresenting.value)
            compose.waitForIdle()
            h.eventually("fallback scan released after selection") { stopped && !h.container.connectionPort.isPairingFlowActive }
            assertEquals(AppTab.SETTINGS, h.container.navigation.state.value.selectedTab)
        }
    }

    @Test fun leavingSettingsAfterFallbackSelectionDoesNotCancelTheSelectedConnection() = runTest {
        val service = BluetoothScanPairingService()
        val address = "4A:00:00:00:00:02"
        val id = DeviceEndpointIdentity.deviceId(address)
        val endpoints = SharedPreferencesKnownEndpoints(compose.activity)
        val connectionStarted = CompletableDeferred<UUID>()
        val releaseConnection = CompletableDeferred<Unit>()
        val systemLinks = object : SystemLinkProbe {
            override suspend fun isDeviceConnectedToSystem(deviceId: UUID): Boolean {
                connectionStarted.complete(deviceId)
                releaseConnection.await()
                return false
            }

            override suspend fun systemConnectedDeviceIds(): Set<UUID> = emptySet()
        }
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) {
                onDevice(DiscoveredDevice(id, address, "Selected radio", -40))
            }
            override fun stopScan() = Unit
        })
        withSettings(pairingService = service, scans = scans, knownEndpoints = endpoints, systemLinks = systemLinks) { h, feature ->
            mount(h, feature)
            open(h)
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
            compose.waitForIdle()
            h.eventually("selected radio discovery rendered") {
                compose.onAllNodesWithText("Selected radio").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText("Selected radio").performClick()
            h.eventually("selection commits and connection waits", virtualBudgetMillis = 1_000) {
                !service.isPresenting.value && connectionStarted.isCompleted
            }
            assertEquals(id, connectionStarted.await())
            assertTrue(feature.selectionScanJob?.isActive == true)

            compose.runOnUiThread { compose.activity.setContent {} }
            compose.waitForIdle()
            assertTrue("selected connection must outlive the Settings host", feature.selectionScanJob?.isActive == true)
            releaseConnection.complete(Unit)
            h.eventually("selected radio connects after Settings disposal", virtualBudgetMillis = 3_000) {
                h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
            }
            assertEquals(id, h.manager.connectedDevice?.id)
            assertEquals(BluetoothEndpoint(id, address, null), endpoints.endpoint(id))
        }
    }

    @Test fun fallbackDiscoverySharesOneScanAcrossConcurrentSettingsCollectors() = runTest {
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
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
            compose.waitForIdle()
            h.eventuallyMounted("first Settings collector starts fallback scan") { starts == 1 }

            val duplicateStarted = CompletableDeferred<Unit>()
            val duplicate = backgroundScope.launch {
                requireNotNull(h.container.onboarding?.pairing?.scanFallback).discoveries()
                    .onStart { duplicateStarted.complete(Unit) }
                    .collect {}
            }
            withTimeout(5_000) { duplicateStarted.await() }
            h.settle()
            assertEquals(1, starts)

            duplicate.cancelAndJoin()
            assertEquals(0, stops)
            compose.onNodeWithText(compose.activity.getString(L.commonCancel)).performClick()
            h.eventually("last Settings collector stops fallback scan") {
                !service.isPresenting.value && stops == 1
            }
        }
    }

    @Test fun cancelledDiscoveryCannotResolveOrCancelItsReplacementAfterHeldWriteFails() = runTest {
        val service = BluetoothScanPairingService()
        val firstAddress = "C2:00:00:00:00:01"
        val firstId = DeviceEndpointIdentity.deviceId(firstAddress)
        val replacementAddress = "D2:00:00:00:00:02"
        val replacementId = DeviceEndpointIdentity.deviceId(replacementAddress)
        val endpoints = HeldKnownEndpointStore(firstId, IllegalStateException("late first write failure"))
        var starts = 0
        var stops = 0
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) {
                starts++
                if (starts == 1) onDevice(DiscoveredDevice(firstId, firstAddress, "First radio", -40))
                else onDevice(DiscoveredDevice(replacementId, replacementAddress, "Replacement radio", -41))
            }
            override fun stopScan() { stops++ }
        })
        withSettings(pairingService = service, scans = scans, knownEndpoints = endpoints) { h, feature ->
            mount(h, feature)
            open(h)
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
            h.eventuallyMounted("first discovery is selectable") {
                compose.onAllNodesWithText("First radio").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText("First radio").performClick()
            h.eventually("first endpoint write is held") { endpoints.firstWrite.isCompleted }
            assertEquals(BluetoothEndpoint(firstId, firstAddress, null), endpoints.firstWrite.await())

            compose.onNodeWithText(compose.activity.getString(L.commonCancel)).performClick()
            h.eventually("first pairing and scan are cancelled") {
                !service.isPresenting.value && !h.container.connectionPort.isPairingFlowActive &&
                    stops == 1 && feature.selectionScanJob?.isCompleted == true
            }
            open(h)
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
            h.eventuallyMounted("replacement discovery is active") {
                service.isPresenting.value &&
                    compose.onAllNodesWithText("Replacement radio").fetchSemanticsNodes().size == 1
            }
            assertEquals(2, starts)

            endpoints.releaseFirstWrite.complete(Unit)
            h.eventually("late endpoint write rolled back") { endpoints.rolledBack.isCompleted }
            endpoints.rolledBack.await()
            h.settle()
            assertTrue(service.isPresenting.value)
            assertNull(endpoints.endpoint(firstId))
            assertTrue(h.links.links.isEmpty())
            compose.onNodeWithText("Replacement radio").performClick()
            h.eventually("replacement radio connects", virtualBudgetMillis = 3_000) {
                h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
            }
            assertEquals(replacementId, h.manager.connectedDevice?.id)
            assertEquals(BluetoothEndpoint(replacementId, replacementAddress, null), endpoints.endpoint(replacementId))
        }
    }

    @Test fun cancelledDiscoveryCoroutineCannotSelectOrCancelReplacementAfterHeldWrite() = runTest {
        val service = BluetoothScanPairingService()
        val firstAddress = "C2:00:00:00:00:04"
        val firstId = DeviceEndpointIdentity.deviceId(firstAddress)
        val replacementAddress = "D2:00:00:00:00:05"
        val replacementId = DeviceEndpointIdentity.deviceId(replacementAddress)
        val endpoints = HeldKnownEndpointStore(firstId)
        var starts = 0
        var stops = 0
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) {
                starts++
                if (starts == 1) onDevice(DiscoveredDevice(firstId, firstAddress, "First radio", -40))
                else onDevice(DiscoveredDevice(replacementId, replacementAddress, "Replacement radio", -41))
            }

            override fun stopScan() { stops++ }
        })
        withSettings(pairingService = service, scans = scans, knownEndpoints = endpoints) { h, feature ->
            mount(h, feature)
            open(h)
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
            h.eventuallyMounted("first discovery is selectable") {
                compose.onAllNodesWithText("First radio").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText("First radio").performClick()
            h.eventually("first endpoint write is held") { endpoints.firstWrite.isCompleted }
            assertEquals(BluetoothEndpoint(firstId, firstAddress, null), endpoints.firstWrite.await())

            val firstDiscovery = requireNotNull(feature.selectionScanJob)
            firstDiscovery.cancelAndJoin()
            h.eventually("actual discovery coroutine cancellation releases its picker and scan") {
                !service.isPresenting.value && !h.container.connectionPort.isPairingFlowActive &&
                    stops == 1 && firstDiscovery.isCompleted
            }
            assertNull(endpoints.endpoint(firstId))

            open(h)
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
            h.eventuallyMounted("replacement discovery is active") {
                service.isPresenting.value &&
                    compose.onAllNodesWithText("Replacement radio").fetchSemanticsNodes().size == 1
            }
            assertEquals(2, starts)

            endpoints.releaseFirstWrite.complete(Unit)
            h.eventually("cancelled discovery endpoint write is rolled back") { endpoints.rolledBack.isCompleted }
            endpoints.rolledBack.await()
            h.settle()
            assertTrue(service.isPresenting.value)
            assertNull(endpoints.endpoint(firstId))
            assertTrue(h.links.links.isEmpty())

            compose.onNodeWithText("Replacement radio").performClick()
            h.eventually("replacement discovery connects", virtualBudgetMillis = 3_000) {
                h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
            }
            assertEquals(replacementId, h.manager.connectedDevice?.id)
            assertNull(endpoints.endpoint(firstId))
            assertEquals(BluetoothEndpoint(replacementId, replacementAddress, null), endpoints.endpoint(replacementId))
        }
    }

    @Test fun directAppStateDiscoveryCancellationCannotSelectOrCancelReplacementAfterHeldWrite() = runTest {
        val service = BluetoothScanPairingService()
        val firstAddress = "C2:00:00:00:00:06"
        val firstId = DeviceEndpointIdentity.deviceId(firstAddress)
        val replacementAddress = "D2:00:00:00:00:07"
        val replacementId = DeviceEndpointIdentity.deviceId(replacementAddress)
        val endpoints = HeldKnownEndpointStore(firstId)
        var starts = 0
        var stops = 0
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) {
                starts++
                if (starts == 1) onDevice(DiscoveredDevice(firstId, firstAddress, "Direct first radio", -40))
                else onDevice(DiscoveredDevice(replacementId, replacementAddress, "Direct replacement radio", -41))
            }

            override fun stopScan() { stops++ }
        })
        withSettings(pairingService = service, scans = scans, knownEndpoints = endpoints) { h, _ ->
            val fallback = requireNotNull(h.container.onboarding?.scanFallbackLifecycle)
            val firstReady = CompletableDeferred<Unit>()
            val firstJob = h.appState.startDeviceScan()
            h.eventually("direct AppState discovery reaches the actual pairing service") {
                service.isPresenting.value
            }
            val firstCollector = backgroundScope.launch {
                fallback.discoveries().collect { devices ->
                    if (devices.any { it.id == firstId }) firstReady.complete(Unit)
                }
            }
            withTimeout(5_000) { firstReady.await() }
            assertEquals(1, starts)
            fallback.select(firstId)
            withTimeout(5_000) { endpoints.firstWrite.await() }

            val concurrentJob = h.appState.startDeviceScan()
            concurrentJob.join()
            assertTrue(firstJob.isActive)
            assertTrue(service.isPresenting.value)
            assertTrue(h.appState.connectionUI.isBusy)
            assertTrue(h.container.connectionPort.isPairingFlowActive)
            assertEquals(1, starts)

            firstJob.cancelAndJoin()
            firstCollector.cancelAndJoin()
            h.eventually("direct discovery cancellation retires its scan") {
                !service.isPresenting.value && stops == 1 && firstJob.isCompleted
            }
            assertNull(endpoints.endpoint(firstId))

            val replacementReady = CompletableDeferred<Unit>()
            val replacementJob = h.appState.startDeviceScan()
            h.eventually("replacement AppState discovery reaches the pairing service") {
                service.isPresenting.value
            }
            val replacementCollector = backgroundScope.launch {
                fallback.discoveries().collect { devices ->
                    if (devices.any { it.id == replacementId }) replacementReady.complete(Unit)
                }
            }
            withTimeout(5_000) { replacementReady.await() }
            assertEquals(2, starts)
            assertTrue(service.isPresenting.value)
            assertTrue(replacementJob.isActive)

            endpoints.releaseFirstWrite.complete(Unit)
            withTimeout(5_000) { endpoints.rolledBack.await() }
            assertNull(endpoints.endpoint(firstId))
            assertTrue(service.isPresenting.value)
            assertTrue(h.links.links.isEmpty())

            fallback.select(replacementId)
            h.eventually("replacement selected through direct AppState discovery connects", virtualBudgetMillis = 3_000) {
                h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY &&
                    replacementJob.isCompleted
            }
            replacementCollector.cancelAndJoin()
            assertEquals(replacementId, h.manager.connectedDevice?.id)
            assertNull(endpoints.endpoint(firstId))
            assertEquals(BluetoothEndpoint(replacementId, replacementAddress, null), endpoints.endpoint(replacementId))
            assertFalse(service.isPresenting.value)
        }
    }

    @Test fun concurrentNonFallbackPairingDoesNotClearOwningActivityState() = runTest {
        val port = FakeConnectionPort()
        val firstPickerStarted = CompletableDeferred<Unit>()
        val releaseFirstPicker = CompletableDeferred<Unit>()
        port.beforePicker = {
            if (port.pickerCalls == 1) {
                firstPickerStarted.complete(Unit)
                releaseFirstPicker.await()
            } else {
                throw DevicePairingError.AlreadyInProgress()
            }
        }
        val state = appStateOf(backgroundScope, port)
        val firstJob = state.startDeviceScan()
        runCurrent()
        firstPickerStarted.await()
        assertTrue(port.hasSystemPairingRegistry)
        assertTrue(state.connectionUI.isBusy)
        assertTrue(port.isPairingFlowActive)

        val duplicateJob = state.startDeviceScan()
        duplicateJob.join()
        assertTrue(firstJob.isActive)
        assertTrue(state.connectionUI.isBusy)
        assertTrue(port.isPairingFlowActive)
        assertEquals(1, port.pickerCalls)

        val awaitPairing = async { state.startDeviceScanAndAwait() }
        runCurrent()
        assertFalse(awaitPairing.isCompleted)
        assertTrue(state.connectionUI.isBusy)
        assertTrue(port.isPairingFlowActive)

        releaseFirstPicker.complete(Unit)
        firstJob.join()
        assertTrue(awaitPairing.await())
        assertFalse(state.connectionUI.isBusy)
        assertFalse(port.isPairingFlowActive)
        assertEquals(1, port.pickerCalls)
    }

    @Test fun cancellingQueuedScanReleasesItsStateBeforeTheNextScan() = runTest {
        val port = FakeConnectionPort()
        val state = appStateOf(backgroundScope, port)
        val queued = state.startDeviceScan()
        assertTrue(state.connectionUI.isBusy)
        assertTrue(port.isPairingFlowActive)
        queued.cancelAndJoin()
        assertEquals(0, port.pickerCalls)
        assertFalse(state.connectionUI.isBusy)
        assertFalse(port.isPairingFlowActive)

        state.startDeviceScan().join()
        assertEquals(1, port.pickerCalls)
        assertFalse(state.connectionUI.isBusy)
        assertFalse(port.isPairingFlowActive)
    }

    @Test fun oldAwaiterCleanupCannotClearNewPairingAfterHeldRetryCancellation() = runTest {
        val port = FakeConnectionPort()
        val ui = com.meshcoreone.android.app.state.ConnectionUiState(backgroundScope)
        val retrySleeping = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val replacementStarted = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        val clock = object : com.meshcoreone.android.core.runtime.DeadlineClock {
            override suspend fun sleep(duration: kotlin.time.Duration) {
                retrySleeping.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            }
        }
        port.beforePicker = {
            if (port.pickerCalls == 1) throw DevicePairingError.PickerUnavailable()
            replacementStarted.complete(Unit)
            releaseReplacement.await()
        }
        val flow = com.meshcoreone.android.app.state.PairingFlow(
            connection = port, ui = ui, scope = backgroundScope, clock = clock,
            wireServices = {}, disconnect = {},
        )
        val first = async { flow.startDeviceScanAndAwait() }
        runCurrent()
        retrySleeping.await()
        assertTrue(ui.shouldCompleteFreshPairingOnForeground)
        first.cancel()
        runCurrent()
        cleanupStarted.await()
        assertFalse(first.isCompleted)

        // The user dismisses the old deferred flow while its retry cancellation is still unwinding.
        flow.cancelSystemPairingSetup()
        val replacement = flow.startDeviceScan()
        runCurrent()
        replacementStarted.await()
        assertTrue(ui.isBusy)
        assertTrue(port.isPairingFlowActive)
        releaseCleanup.complete(Unit)
        first.join()
        assertTrue(replacement.isActive)
        assertTrue(ui.isBusy)
        assertTrue(port.isPairingFlowActive)
        assertEquals(2, port.pickerCalls)

        releaseReplacement.complete(Unit)
        replacement.join()
        assertFalse(ui.isBusy)
        assertFalse(port.isPairingFlowActive)
    }

    @Test fun disposingSettingsHostCancelsHeldFallbackSelectionAndRollsBackItsEndpoint() = runTest {
        val service = BluetoothScanPairingService()
        val address = "C2:00:00:00:00:03"
        val id = DeviceEndpointIdentity.deviceId(address)
        val endpoints = HeldKnownEndpointStore(id)
        var stops = 0
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) {
                onDevice(DiscoveredDevice(id, address, "Held radio", -40))
            }
            override fun stopScan() { stops++ }
        })
        withSettings(pairingService = service, scans = scans, knownEndpoints = endpoints) { h, feature ->
            mount(h, feature)
            open(h)
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
            h.eventuallyMounted("held discovery is selectable") {
                compose.onAllNodesWithText("Held radio").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText("Held radio").performClick()
            h.eventually("endpoint write is held") { endpoints.firstWrite.isCompleted }
            endpoints.firstWrite.await()

            compose.runOnUiThread { compose.activity.setContent {} }
            h.eventuallyMounted("host disposal cancels pairing and scan") {
                !service.isPresenting.value && !h.container.connectionPort.isPairingFlowActive &&
                    stops == 1 && feature.selectionScanJob?.isCompleted == true
            }
            endpoints.releaseFirstWrite.complete(Unit)
            h.eventually("disposed selection endpoint rolled back") { endpoints.rolledBack.isCompleted }
            endpoints.rolledBack.await()
            assertNull(endpoints.endpoint(id))
            assertTrue(h.links.links.isEmpty())
        }
    }

    @Test fun longDeviceSelectionSheetScrollsToTheLastRadioAndConnectionActions() = runTest {
        withSettings { h, _ ->
            val seed = saved(h)
            val radios = (1..30).map { index ->
                seed.copy(id = UUID.randomUUID(), nodeName = "Long-list radio $index")
            }
            var scanned = false
            var connectedViaWifi = false
            compose.activity.setContent {
                MeshCoreTheme {
                    DeviceSelectionSheet(
                        list = DeviceSelectionListBuilder.Result(radios, emptyList()),
                        connectedElsewhere = emptySet(),
                        onSelect = {},
                        onSetUp = {},
                        onScan = { scanned = true },
                        onConnectViaWifi = { connectedViaWifi = true },
                        onDismiss = {},
                    )
                }
            }
            compose.waitForIdle()
            val list = compose.onNodeWithTag("device-selection-list")
            list.performScrollToNode(hasText("Long-list radio 30"))
            compose.onNodeWithText("Long-list radio 30").assertIsDisplayed()
            val scan = compose.activity.getString(S.deviceSelectionScanBluetooth)
            list.performScrollToNode(hasText(scan))
            compose.onNodeWithText(scan).assertIsDisplayed().performClick()
            val wifi = compose.activity.getString(S.deviceSelectionConnectViaWifi)
            list.performScrollToNode(hasText(wifi))
            compose.onNodeWithText(wifi).assertIsDisplayed().performClick()
            assertTrue(scanned)
            assertTrue(connectedViaWifi)
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

    @Test @Config(sdk = [37])
    fun nativeLocalNetworkRequestCompletionWithoutGrantKeepsWifiBlocked() = runTest {
        val facts = FakePermissionFacts(sdk = 37, granted = ConnectivityPermission.entries.toSet() - ConnectivityPermission.ACCESS_LOCAL_NETWORK)
        withSettings(permissionFacts = facts) { h, feature ->
            mount(h, feature)
            assertLocalNetworkRequestCanStart(h)
            openManualWifi(h)
            compose.waitForIdle()
            h.settle()
            val request = requireNotNull(shadowOf(compose.activity).lastRequestedPermission)
            assertArrayEquals(arrayOf(ConnectivityPermission.ACCESS_LOCAL_NETWORK.manifestName), request.requestedPermissions)
            assertNull(feature.selectionRequestedPermission.value)
            assertEquals(AppSettingsFeature.SelectionAction.Manual, feature.selectionAction.value)
            assertTrue(feature.selectionPermissionDenied.value)
            assertEquals(com.meshcoreone.android.feature.onboarding.PermissionStatus.DENIED,
                requireNotNull(h.container.onboarding).permissions.snapshot.value.localNetwork)
            permissionSettingsButton().assertIsDisplayed()
            assertTrue(h.links.links.isEmpty())
        }
    }

    private suspend fun TestScope.localNetworkPermissionControl(savedDevice: Boolean, grant: Boolean) {
        val facts = FakePermissionFacts(sdk = 37, granted = ConnectivityPermission.entries.toSet() - ConnectivityPermission.ACCESS_LOCAL_NETWORK)
        var openedSettings = 0
        withSettings(permissionFacts = facts, openSettings = { openedSettings++ }, controlledLocalNetworkDialog = true) { h, feature ->
            val device = if (savedDevice) saved(h).also {
                store.saveDevice(it.copy(connectionMethods = (it.connectionMethods + ConnectionMethod.WiFi("saved-radio.local", 6218u)).snapshot()))
            } else null
            val before = h.links.links.size
            val gate = CompletableDeferred<Unit>()
            h.links.configureRadio = { it.connectGate = gate }
            mount(h, feature)
            assertLocalNetworkRequestCanStart(h)
            if (device != null) {
                open(h)
                compose.onNodeWithText(device.nodeName).performClick()
            } else openManualWifi(h)
            compose.waitForIdle()
            h.settle()
            assertEquals(before, h.links.links.size)
            assertNotNull(feature.selectionAction.value)
            assertLocalNetworkRequestPending(h, feature)
            permissionResult(facts, grant)
            assertNull(feature.selectionRequestedPermission.value)
            assertEquals(if (grant) com.meshcoreone.android.feature.onboarding.PermissionStatus.GRANTED
                else com.meshcoreone.android.feature.onboarding.PermissionStatus.DENIED,
                requireNotNull(h.container.onboarding).permissions.snapshot.value.localNetwork)
            if (!grant) {
                assertEquals(before, h.links.links.size)
                permissionSettingsButton().assertIsDisplayed().performClick()
                assertEquals(1, openedSettings)
                facts.granted = facts.granted + ConnectivityPermission.ACCESS_LOCAL_NETWORK
                shadowOf(compose.activity.application).grantPermissions(ConnectivityPermission.ACCESS_LOCAL_NETWORK.manifestName)
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
            assertEquals(1, requireNotNull(permissionDialog).launches)
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

    private suspend fun TestScope.savedWifiPermissionResultAcrossNativeTabRemoval(grant: Boolean, expanded: Boolean = false) {
        val facts = FakePermissionFacts(sdk = 37, granted = ConnectivityPermission.entries.toSet() - ConnectivityPermission.ACCESS_LOCAL_NETWORK)
        var openedSettings = 0
        withSettings(permissionFacts = facts, openSettings = { openedSettings++ }, controlledLocalNetworkDialog = true) { h, feature ->
            val device = saved(h)
            store.saveDevice(device.copy(connectionMethods = (device.connectionMethods + ConnectionMethod.WiFi("tab-radio.local", 6219u)).snapshot()))
            val before = h.links.links.size
            val gate = CompletableDeferred<Unit>()
            h.links.configureRadio = { it.connectGate = gate }
            mountNavigationShell(h, feature)
            if (expanded) {
                compose.onNode(hasText(compose.activity.getString(S.supportTitle)) and hasClickAction())
                    .performScrollTo().performClick()
                compose.onNodeWithTag("list-detail").assertIsDisplayed()
            }
            assertLocalNetworkRequestCanStart(h)
            open(h)
            compose.onNodeWithText(device.nodeName).performClick()
            compose.waitForIdle()
            assertLocalNetworkRequestPending(h, feature)
            compose.onNodeWithTag("tab:CHATS").performClick()
            compose.onNodeWithTag("tab:CHATS").assertIsSelected()
            permissionResult(facts, grant)
            h.settle()
            assertEquals(before, h.links.links.size)
            compose.onNodeWithTag("tab:SETTINGS").performClick()
            compose.onNodeWithTag("tab:SETTINGS").assertIsSelected()
            compose.waitForIdle()
            if (!grant) {
                permissionSettingsButton().assertIsDisplayed().performClick()
                assertEquals(1, openedSettings)
                assertEquals(before, h.links.links.size)
                facts.granted = facts.granted + ConnectivityPermission.ACCESS_LOCAL_NETWORK
                shadowOf(compose.activity.application).grantPermissions(ConnectivityPermission.ACCESS_LOCAL_NETWORK.manifestName)
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
            assertEquals(1, requireNotNull(permissionDialog).launches)
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

    @Test @Config(sdk = [37], qualifiers = "en-rUS-w1200dp-h900dp")
    fun expandedSettingsRootAndDetailRequestWifiPermissionOnceAndRetainTheGrantAcrossTabs() = runTest {
        savedWifiPermissionResultAcrossNativeTabRemoval(grant = true, expanded = true)
    }

    @Test @Config(sdk = [37], qualifiers = "en-rUS-w1200dp-h900dp")
    fun expandedSettingsRootAndDetailRequestWifiPermissionOnceAndRetainDenialRecovery() = runTest {
        savedWifiPermissionResultAcrossNativeTabRemoval(grant = false, expanded = true)
    }

    @Test @Config(qualifiers = "en-rUS-w1200dp-h900dp")
    fun expandedSettingsRootAndDetailShareOneScanAndDetailRemovalDoesNotCancelIt() = runTest {
        val service = BluetoothScanPairingService()
        var starts = 0
        var stops = 0
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) { starts++ }
            override fun stopScan() { stops++ }
        })
        withSettings(pairingService = service, scans = scans) { h, feature ->
            mountNavigationShell(h, feature)
            compose.onNode(hasText(compose.activity.getString(S.supportTitle)) and hasClickAction())
                .performScrollTo().performClick()
            compose.onNodeWithTag("list-detail").assertIsDisplayed()
            open(h)
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
            h.eventuallyMounted("two panes share one fallback collector") { starts == 1 && service.isPresenting.value }
            assertEquals(1, compose.onAllNodesWithText(compose.activity.getString(O.deviceScannerTitle)).fetchSemanticsNodes().size)
            compose.runOnUiThread { h.container.navigation.back() }
            compose.waitForIdle()
            h.settle()
            assertTrue(service.isPresenting.value)
            assertEquals(1, starts)
            assertEquals(0, stops)
            compose.onNodeWithText(compose.activity.getString(L.commonCancel)).performClick()
            h.eventuallyMounted("shared fallback cancellation completes") {
                stops == 1 && !service.isPresenting.value && !h.container.connectionPort.isPairingFlowActive
            }
            assertNull(feature.failure.value)
            assertTrue(h.links.links.isEmpty())
        }
    }

    @Test @Config(qualifiers = "en-rUS-w1200dp-h900dp")
    fun expandedSettingsRootAndDetailShareOnePendingWifiSheetAcrossDetailRemoval() = runTest {
        withSettings { h, feature ->
            val gate = CompletableDeferred<Unit>()
            h.links.configureRadio = { it.connectGate = gate }
            mountNavigationShell(h, feature)
            compose.onNode(hasText(compose.activity.getString(S.supportTitle)) and hasClickAction())
                .performScrollTo().performClick()
            compose.onNodeWithTag("list-detail").assertIsDisplayed()
            openManualWifi(h)
            h.eventuallyMounted("two panes share one Wi-Fi connection") { h.links.links.size == 1 }
            assertEquals(1, compose.onAllNodesWithText(compose.activity.getString(O.wifiConnectionTitle)).fetchSemanticsNodes().size)
            compose.runOnUiThread { h.container.navigation.back() }
            compose.waitForIdle()
            assertEquals(1, compose.onAllNodesWithText(compose.activity.getString(O.wifiConnectionTitle)).fetchSemanticsNodes().size)
            gate.complete(Unit)
            h.eventuallyMounted("shared Wi-Fi sheet dismisses on success") { !feature.selectionWifiVisible.value }
            compose.onNodeWithText(compose.activity.getString(O.wifiConnectionTitle)).assertDoesNotExist()
            assertEquals(1, h.links.links.size)
        }
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
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
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
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
            compose.waitForIdle()
            h.eventuallyMounted("only the user's new scan action starts a picker") { service.isPresenting.value && starts == 1 }
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

    private suspend fun TestScope.removeAndRetryScanFailureControl(switchAway: Boolean) {
        val facts = FakePermissionFacts(
            sdk = 31, granted = setOf(ConnectivityPermission.BLUETOOTH_CONNECT),
        )
        val service = BluetoothScanPairingService()
        val failure = IllegalStateException("Retry scanner callback failed")
        var failScan: ((Throwable) -> Unit)? = null
        var starts = 0
        var stops = 0
        val scans = BleScanCoordinator(object : BleScanGateway {
            override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) {
                assertTrue(facts.granted.containsAll(bluetoothPermissions))
                starts++
                failScan = onFailure
            }
            override fun stopScan() { stops++ }
        })
        withSettings(permissionFacts = facts, pairingService = service, scans = scans) { h, feature ->
            requireNotNull(h.container.onboarding).flags.setBoolean(
                com.meshcoreone.android.feature.onboarding.OnboardingState.KEY_HAS_COMPLETED, true,
            )
            val device = saved(h)
            h.links.configureRadio = { it.connectFailure = BleTransportException(BleError.AuthenticationFailed) }
            mount(h, feature, includeConnectionRecovery = true)
            open(h)
            compose.onNodeWithText(device.nodeName).performClick()
            if (switchAway) {
                compose.runOnUiThread { h.container.navigation.selectTab(AppTab.CHATS) }
                compose.waitForIdle()
                assertEquals(AppTab.CHATS, h.container.navigation.state.value.selectedTab)
            }
            h.eventually("saved authentication failure before guided retry", virtualBudgetMillis = 3_000, virtualStepMillis = 10) {
                compose.waitForIdle()
                h.appState.connectionUI.showingConnectionFailedAlert &&
                    h.appState.connectionUI.failedPairingDeviceId == device.id
            }

            compose.onNodeWithText(compose.activity.getString(L.alertConnectionFailedRemoveAndRetry)).performClick()
            assertEquals(AppTab.SETTINGS, h.container.navigation.state.value.selectedTab)
            h.eventuallyMounted("direct retry requests scan permission while pairing is active") {
                feature.selectionRequestedPermission.value == OnboardingPermissionKind.BLUETOOTH &&
                    service.isPresenting.value
            }
            assertNull(store.fetchDevice(device.id))
            assertTrue(h.container.connectionPort.isPairingFlowActive)
            assertEquals(0, starts)

            permissionResult(facts, granted = true, permissions = bluetoothPermissions)
            h.eventuallyMounted("permission-gated retry starts its scanner") { starts == 1 && failScan != null }
            requireNotNull(failScan)(failure)
            h.eventuallyMounted("retry scanner failure resolves the direct pairing and reaches Settings") {
                h.appState.connectionUI.showingConnectionFailedAlert && !service.isPresenting.value &&
                    !h.container.connectionPort.isPairingFlowActive
            }
            assertEquals(com.meshcoreone.android.core.ui.UiText.Verbatim(requireNotNull(failure.message)),
                h.appState.connectionUI.connectionFailedMessage)
            assertEquals(1, starts)
            assertEquals(1, stops)
            assertFalse(scans.isScanning)
            assertNull(store.fetchDevice(device.id))
            compose.onNodeWithText(compose.activity.getString(L.commonOk)).performClick()
            assertFalse(h.appState.connectionUI.showingConnectionFailedAlert)
            assertNull(feature.failure.value)
            compose.onAllNodes(isDialog()).assertCountEquals(0)
        }
    }

    @Test fun removeAndRetryDirectScanRequestsPermissionAndReportsScannerFailure() = runTest {
        removeAndRetryScanFailureControl(switchAway = false)
    }

    @Test fun savedFailureAfterLeavingSettingsRoutesRemoveAndRetryBackToItsPickerHost() = runTest {
        removeAndRetryScanFailureControl(switchAway = true)
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
            clickSelectionRow(compose.activity.getString(S.deviceSelectionScanBluetooth))
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
            clickSelectionRow(compose.activity.getString(S.deviceSelectionConnectViaWifi))
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
            clickSelectionRow(compose.activity.getString(S.deviceSelectionConnectViaWifi))
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
