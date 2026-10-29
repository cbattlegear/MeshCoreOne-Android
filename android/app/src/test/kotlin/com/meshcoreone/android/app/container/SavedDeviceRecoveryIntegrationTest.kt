// PortedFrom: MC1Tests/AppState/SavedDeviceConnectFailureRoutingTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.container.onboarding.AppOnboardingPairingPort
import com.meshcoreone.android.app.container.onboarding.AppStateOnboardingHost
import com.meshcoreone.android.app.state.PairingFailureKind
import com.meshcoreone.android.core.ble.BleError
import com.meshcoreone.android.core.ble.BleTransportException
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.onboarding.OnboardingConnectOutcome
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedDeviceRecoveryIntegrationTest : RoomProcessTest() {
    @Test fun actualSavedRetryRoutesAuthenticationToTheAppRecoveryDialog() = runTest {
        withSavedDevice { h, id ->
            h.links.configureRadio = { it.connectFailure = BleTransportException(BleError.AuthenticationFailed) }
            val outcome = assertIs<OnboardingConnectOutcome.Failed>(pairing(h).retryConnection(id))
            val ui = h.appState.connectionUI.state.value
            assertTrue(outcome.presentedByConnectionUi)
            assertNull(outcome.retryDeviceId)
            assertTrue(ui.showingConnectionFailedAlert)
            assertEquals(id, ui.failedPairingDeviceId)
            assertEquals(PairingFailureKind.AUTHENTICATION, ui.pairingFailureKind)
            assertEquals(0, h.pairing.removeCalls)
            assertNotNull(store.fetchDevice(id))
            assertEquals(0, h.sessions.outstanding)
        }
    }

    @Test fun actualGenericRetryClearsStalePairingFieldsAndHasNoDestructiveRecovery() = runTest {
        withSavedDevice { h, id ->
            h.appState.handleAuthenticationFailure(id, isAppActive = true)
            h.links.configureRadio = { it.connectFailure = IllegalStateException("link down") }
            val outcome = assertIs<OnboardingConnectOutcome.Failed>(pairing(h).retryConnection(id))
            assertFalse(outcome.presentedByConnectionUi)
            assertNull(outcome.retryDeviceId)
            val ui = h.appState.connectionUI.state.value
            assertNull(ui.failedPairingDeviceId)
            assertNull(ui.pairingFailureKind)
            assertNull(ui.connectionFailedTitle)
            assertFalse(ui.showingConnectionFailedAlert)
            assertEquals(0, h.pairing.removeCalls)
            assertNotNull(store.fetchDevice(id))
        }
    }

    @Test fun actualOtherAppFailureKeepsItsDistinctRetryWarning() = runTest {
        withSavedDevice { h, id ->
            h.links.configureRadio = { it.connectFailure = BleTransportException(BleError.DeviceConnectedToOtherApp) }
            val outcome = assertIs<OnboardingConnectOutcome.Failed>(pairing(h).retryConnection(id))
            assertEquals(id, outcome.retryDeviceId)
            assertEquals(UiText.Resource(AppLocalizableStrings.alertCouldNotConnectOtherAppMessage), outcome.message)
            assertFalse(outcome.presentedByConnectionUi)
            assertNull(h.appState.connectionUI.failedPairingDeviceId)
            assertNull(h.appState.connectionUI.pairingFailureKind)
            assertEquals(0, h.pairing.removeCalls)
        }
    }

    @Test fun cancelledSavedRetryDoesNotPresentFailureOrDeleteTheDevice() = runTest {
        withSavedDevice { h, id ->
            h.links.configureRadio = { it.connectGate = CompletableDeferred() }
            val retry = backgroundScope.async { pairing(h).retryConnection(id) }
            h.eventually("saved retry reaches its suspended transport", virtualBudgetMillis = 0) {
                h.links.radios.last().connectGate != null &&
                    h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.CONNECTING
            }
            retry.cancelAndJoin()
            assertTrue(retry.isCancelled)
            assertFalse(h.appState.connectionUI.showingConnectionFailedAlert)
            assertNull(h.appState.connectionUI.failedPairingDeviceId)
            assertNotNull(store.fetchDevice(id))
            assertEquals(0, h.pairing.removeCalls)
        }
    }

    @Test fun confirmedRemovalFailureIsPresentedWithoutPretendingThatRepairSucceeded() = runTest {
        withSavedDevice { h, id ->
            h.pairing.registered[id] = "Saved radio"
            h.pairing.removeFailure = IllegalStateException("association removal failed")
            h.appState.handleAuthenticationFailure(id, isAppActive = true)
            assertNotNull(h.appState.removeFailedPairingAndRetry()).join()
            assertTrue(h.appState.connectionUI.showingConnectionFailedAlert)
            assertEquals(UiText.Verbatim("association removal failed"), h.appState.connectionUI.connectionFailedMessage)
            assertTrue(id in h.pairing.registered)
            assertFalse(h.appState.connectionUI.shouldShowPickerOnForeground)
        }
    }

    @Test fun failedGhostDemotionAfterRegistryRemovalDoesNotAdvanceTheRecoveryPicker() = runTest {
        var rejectDemotion = false
        val persistence = object : com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol by store {
            override suspend fun demoteDeviceToGhost(id: UUID) {
                if (rejectDemotion) throw IllegalStateException("ghost demotion failed")
                store.demoteDeviceToGhost(id)
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val device = assertNotNull(h.manager.connectedDevice)
            h.pairing.registered[device.id] = "Saved radio"
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            rejectDemotion = true
            h.appState.handleAuthenticationFailure(device.id, isAppActive = true)
            assertNotNull(h.appState.removeFailedPairingAndRetry()).join()
            assertTrue(h.appState.connectionUI.showingConnectionFailedAlert)
            assertEquals(UiText.Verbatim("ghost demotion failed"), h.appState.connectionUI.connectionFailedMessage)
            assertFalse(h.appState.connectionUI.shouldShowPickerOnForeground)
            assertFalse(device.id in h.pairing.registered)
            assertEquals(device.connectionMethods, assertNotNull(store.fetchDevice(device.id)).connectionMethods)
        } finally { h.container.close() }
    }

    @Test fun confirmedCompanionRemovalStartsTheActualPickerWithoutAnotherForegroundEdge() = runTest {
        withSavedDevice { h, id ->
            h.pairing.registered[id] = "Saved radio"
            h.container.foreground.activityStarted()
            var pickerCalls = 0
            h.pairing.discovery = {
                pickerCalls++
                throw com.meshcoreone.android.core.connectivity.pairing.DevicePairingError.Cancelled()
            }
            h.appState.handleAuthenticationFailure(id, isAppActive = true)
            assertNotNull(h.appState.removeFailedPairingAndRetry()).join()
            assertEquals(1, pickerCalls)
            assertFalse(h.appState.connectionUI.shouldShowPickerOnForeground)
            assertFalse(id in h.pairing.registered)
        }
    }

    @Test fun backgroundCompanionRemovalDefersTheActualPickerUntilForeground() = runTest {
        withSavedDevice { h, id ->
            h.pairing.registered[id] = "Saved radio"
            var pickerCalls = 0
            h.pairing.discovery = {
                pickerCalls++
                throw com.meshcoreone.android.core.connectivity.pairing.DevicePairingError.Cancelled()
            }
            h.appState.handleAuthenticationFailure(id, isAppActive = true)
            assertNotNull(h.appState.removeFailedPairingAndRetry()).join()
            assertEquals(0, pickerCalls)
            assertTrue(h.appState.connectionUI.shouldShowPickerOnForeground)
            h.container.foreground.activityStarted()
            h.appState.handleBecameActive()
            h.eventually("deferred companion picker") { pickerCalls == 1 }
            assertFalse(h.appState.connectionUI.shouldShowPickerOnForeground)
        }
    }

    @Test fun teardownFailurePreventsAssociationRemovalDemotionAndPickerAdvancement() = runTest {
        assertIncompleteTeardownStopsRepair(throws = true)
    }

    @Test fun silentlyRetainedPhysicalLinkPreventsAssociationRemovalDemotionAndPickerAdvancement() = runTest {
        assertIncompleteTeardownStopsRepair(throws = false)
    }

    private suspend fun TestScope.assertIncompleteTeardownStopsRepair(throws: Boolean) {
        val h = ContainerHarness(this, store)
        var radio: FirmwareRadio? = null
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val device = assertNotNull(h.manager.connectedDevice)
            radio = h.links.radios.single()
            if (throws) radio.disconnectFailure = IllegalStateException("transport teardown failed")
            else radio.retainConnectionOnDisconnect = true
            h.pairing.registered[device.id] = "Saved radio"
            h.container.foreground.activityStarted()
            var pickerCalls = 0
            h.pairing.discovery = {
                pickerCalls++
                throw com.meshcoreone.android.core.connectivity.pairing.DevicePairingError.Cancelled()
            }
            h.appState.handleAuthenticationFailure(device.id, isAppActive = true)
            assertNotNull(h.appState.removeFailedPairingAndRetry()).join()
            assertTrue(h.appState.connectionUI.showingConnectionFailedAlert)
            assertFalse(h.appState.connectionUI.shouldShowPickerOnForeground)
            assertEquals(0, pickerCalls)
            assertEquals(0, h.pairing.removeCalls)
            assertTrue(device.id in h.pairing.registered)
            assertEquals(device.connectionMethods, assertNotNull(store.fetchDevice(device.id)).connectionMethods)
            assertTrue(radio.connected)
            h.appState.handleAuthenticationFailure(device.id, isAppActive = true)
            assertNotNull(h.appState.removeFailedPairingAndRetry()).join()
            assertTrue(h.appState.connectionUI.showingConnectionFailedAlert)
            assertEquals(0, h.pairing.removeCalls)
            assertEquals(0, pickerCalls)
        } finally {
            radio?.let {
                it.disconnectFailure = null
                it.retainConnectionOnDisconnect = false
                it.disconnect()
            }
            h.container.close()
        }
    }

    private fun TestScope.pairing(h: ContainerHarness) =
        AppOnboardingPairingPort(AppStateOnboardingHost(h.container), backgroundScope, scanFallback = null)

    private suspend fun TestScope.withSavedDevice(block: suspend TestScope.(ContainerHarness, UUID) -> Unit) {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val id = assertNotNull(h.manager.connectedDevice).id
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            h.settle()
            block(h, id)
        } finally {
            h.container.close()
        }
    }
}
