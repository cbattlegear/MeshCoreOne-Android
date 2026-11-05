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
import com.meshcoreone.android.app.navigation.AppSettingsContent
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
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h900dp")
class DeviceSelectionRegistrationTest : RoomProcessTest() {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private suspend fun TestScope.withSettings(
        persistence: PersistenceStoreProtocol = store,
        systemLinks: SystemLinkProbe = NoSystemLinks,
        test: suspend (ContainerHarness, AppSettingsFeature) -> Unit,
    ) {
        val h = ContainerHarness(this, store, persistenceOverride = persistence, systemLinks = systemLinks)
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
                    AppSettingsContent(feature, navigation.activeStack.last().destination, h.container.navigation)
                    SystemPairingSetupHost(h.appState)
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
