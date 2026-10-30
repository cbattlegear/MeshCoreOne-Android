// AndroidOnly: WP-303 Pairing, Wi-Fi and demo adapter outcome mapping against fakes.
package com.meshcoreone.android.app.container.onboarding

import com.meshcoreone.android.app.state.ConnectionUiSnapshot
import com.meshcoreone.android.core.ble.BleError
import com.meshcoreone.android.core.ble.BleTransportException
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.services.simulator.DemoModeDefaults
import com.meshcoreone.android.core.services.simulator.DemoModeManager
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.onboarding.OnboardingConnectOutcome as Outcome
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingPairingAdapterTest {
    private val device = UUID.fromString("00000000-0000-0000-0000-0000000000a1")

    private fun kotlinx.coroutines.test.TestScope.pairing(host: FakeConnectionHost) =
        AppOnboardingPairingPort(host, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), scanFallback = null)

    @Test fun `successful pairing reports connected and refreshes the accessory count`() = runTest {
        val host = FakeConnectionHost()
        host.onStartPairing = { host.state.value = DeviceConnectionState.READY; host.registeredAccessoryCount = 1 }
        val port = pairing(host)
        assertEquals(Outcome.Connected, port.pairNewDevice())
        assertEquals(1, port.pairedAccessoryCount.value)
        assertTrue(port.connection.value.isReady)
    }

    @Test fun `dismissed picker is cancelled and a running flow is ignored`() = runTest {
        val host = FakeConnectionHost()
        val port = pairing(host)
        assertEquals(Outcome.Cancelled, port.pairNewDevice())
        host.isPairingActive = true
        assertEquals(Outcome.Ignored, port.pairNewDevice())
    }

    @Test fun `pairing failure carries the app state message and is consumed`() = runTest {
        val host = FakeConnectionHost()
        val message = UiText.Verbatim("PIN rejected")
        host.onStartPairing = {
            host.ui.value = ConnectionUiSnapshot(showingConnectionFailedAlert = true, connectionFailedMessage = message)
        }
        val outcome = pairing(host).pairNewDevice()
        assertEquals(Outcome.Failed(message), outcome)
        assertFalse(host.ui.value.showingConnectionFailedAlert)
    }

    @Test fun `other-app holder maps to a retryable failure with the device id`() = runTest {
        val host = FakeConnectionHost()
        host.onStartPairing = { host.ui.value = ConnectionUiSnapshot(otherAppWarningDeviceId = device) }
        val outcome = pairing(host).pairNewDevice() as Outcome.Failed
        assertEquals(device, outcome.retryDeviceId)
        assertEquals(UiText.Resource(AppLocalizableStrings.alertCouldNotConnectOtherAppMessage), outcome.message)
    }

    @Test fun `retry maps success, other-app failure and generic failure, never swallowing cancellation`() = runTest {
        val host = FakeConnectionHost()
        val port = pairing(host)
        assertEquals(Outcome.Connected, port.retryConnection(device))
        host.onRetry = { throw BleTransportException(BleError.DeviceConnectedToOtherApp) }
        assertEquals(device, (port.retryConnection(device) as Outcome.Failed).retryDeviceId)
        host.onRetry = { throw IllegalStateException("link down") }
        assertEquals(Outcome.Failed(UiText.Verbatim("link down")), port.retryConnection(device))
        host.onRetry = { throw CancellationException("stop") }
        try { port.retryConnection(device); fail("cancellation must propagate") } catch (expected: CancellationException) { }
    }

    @Test fun `clearing stale pairings resets the count`() = runTest {
        val host = FakeConnectionHost().apply { registeredAccessoryCount = 2 }
        val port = pairing(host)
        assertEquals(2, port.pairedAccessoryCount.value)
        port.clearStalePairings()
        assertEquals(0, port.pairedAccessoryCount.value)
        assertEquals(1, host.cleared)
    }

    @Test fun `wifi validates the port and maps connect failures`() = runTest {
        val host = FakeConnectionHost()
        val wifi = AppOnboardingWiFiPort(host)
        assertTrue(wifi.connect("10.0.0.5", 0) is Outcome.Failed)
        assertTrue(wifi.connect("10.0.0.5", 70_000) is Outcome.Failed)
        assertEquals(Outcome.Connected, wifi.connect("10.0.0.5", 5000))
        assertEquals("10.0.0.5" to 5000.toUShort(), host.lastWiFi)
        host.onWiFi = { _, _ -> throw java.io.IOException("refused") }
        assertEquals(Outcome.Failed(UiText.Verbatim("refused")), wifi.connect("10.0.0.5", 5000))
    }

    @Test fun `demo unlock enables demo mode and simulator connection reports failures and success`() = runTest {
        val store = HashMap<String, Boolean>()
        val manager = DemoModeManager(object : DemoModeDefaults {
            override fun bool(forKey: String) = store[forKey] ?: false
            override suspend fun setAll(values: Map<String, Boolean>) { store.putAll(values) }
        })
        val port = AppOnboardingDemoPort(manager, CoroutineScope(UnconfinedTestDispatcher(testScheduler))) {
            throw IllegalStateException("simulator unavailable")
        }
        assertFalse(port.isEnabled.value)
        port.unlockAndAwait()
        assertTrue(port.isEnabled.value)
        assertEquals(Outcome.Failed(UiText.Verbatim("simulator unavailable")), port.connectDemo())
        var connected = false
        val bound = AppOnboardingDemoPort(manager, CoroutineScope(UnconfinedTestDispatcher(testScheduler))) { connected = true }
        assertEquals(Outcome.Connected, bound.connectDemo())
        assertTrue(connected)
    }
}
