// PortedFrom: MC1Tests/AppState/ConnectionUIStateTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/AppState/StatusPillStateTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/AppState/DisconnectedPillTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/AppState/AuthenticationFailureGatingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/AppState/FreshPairingFailureRoutingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/AppState/SavedDeviceConnectFailureRoutingTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.state.ConnectionFailures
import com.meshcoreone.android.app.state.ConnectionUiState
import com.meshcoreone.android.app.state.PairingFailureKind
import com.meshcoreone.android.core.connectivity.pairing.PairingError
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings
import com.meshcoreone.android.core.runtime.LinkFailure
import com.meshcoreone.android.core.runtime.RuntimePreferenceValue
import com.meshcoreone.android.core.model.PersistenceKeys
import com.meshcoreone.android.core.services.sync.SyncPhase
import com.meshcoreone.android.core.ui.StatusPillState
import com.meshcoreone.android.core.ui.UiText
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

private val SYNC_FAILED = StatusPillState.Failed(UiText.Resource(AppLocalizableStrings.statusPillSyncFailed))
private val AUTH = LinkFailure.AuthenticationFailed()
private val TIMEOUT = LinkFailure.ConnectionTimeout()

private fun connectionFailed(deviceId: UUID, error: Throwable) =
    PairingError.ConnectionFailed(deviceId, error, ConnectionFailures.isAuthenticationFailure(error))

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.uiState(): ConnectionUiState =
    ConnectionUiState(backgroundScope, SchedulerClock { testScheduler.currentTime })

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.advance(duration: kotlin.time.Duration) {
    testScheduler.advanceTimeBy(duration)
    testScheduler.runCurrent()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionUiStateTest {
    // region StatusPillStateTests

    @OriginalCase("StatusPillStateTests::Failed state takes highest priority()")
    @Test fun failedHighestPriority() = runTest {
        val state = appStateOf(backgroundScope)
        state.connectionUI.showSyncFailedPill()
        assertEquals(SYNC_FAILED, state.statusPillState)
    }

    @OriginalCase("StatusPillStateTests::Syncing takes priority over connecting()")
    @Test fun syncingOverConnecting() = runTest {
        val port = FakeConnectionPort().apply { connectionState = DeviceConnectionState.CONNECTING }
        val state = appStateOf(backgroundScope, port)
        state.connectionUI.syncActivityStarted()
        assertEquals(StatusPillState.Syncing, state.statusPillState)
        state.connectionUI.syncActivityEnded(false)
    }

    @OriginalCase("StatusPillStateTests::Ready state shows when toast is active()")
    @Test fun readyWhenToastActive() = runTest {
        val state = appStateOf(backgroundScope)
        state.connectionUI.showReadyToastBriefly()
        assertEquals(StatusPillState.Ready, state.statusPillState)
    }

    @OriginalCase("StatusPillStateTests::Hidden when no conditions met()")
    @Test fun hiddenWhenNothingApplies() = runTest {
        assertEquals(StatusPillState.Hidden, appStateOf(backgroundScope).statusPillState)
    }

    // endregion

    // region ConnectionUIStateTests: statusPillState priority

    @OriginalCase("ConnectionUIStateTests::statusPillState is hidden by default()")
    @Test fun pillHiddenByDefault() = runTest { assertEquals(StatusPillState.Hidden, appStateOf(backgroundScope).statusPillState) }

    @OriginalCase("ConnectionUIStateTests::Failed state takes priority over syncing()")
    @Test fun failedOverSyncing() = runTest {
        val state = appStateOf(backgroundScope)
        state.connectionUI.syncActivityStarted()
        state.connectionUI.showSyncFailedPill()
        assertEquals(SYNC_FAILED, state.statusPillState)
    }

    @OriginalCase("ConnectionUIStateTests::Syncing takes priority over ready toast()")
    @Test fun syncingOverReadyToast() = runTest {
        val state = appStateOf(backgroundScope)
        state.connectionUI.showReadyToastBriefly()
        state.connectionUI.syncActivityStarted()
        assertEquals(StatusPillState.Syncing, state.statusPillState)
    }

    @OriginalCase("ConnectionUIStateTests::Ready toast takes priority over disconnected()")
    @Test fun readyOverDisconnected() = runTest {
        val state = appStateOf(backgroundScope)
        state.connectionUI.showReadyToastBriefly()
        assertEquals(StatusPillState.Ready, state.statusPillState)
    }

    @OriginalCase("ConnectionUIStateTests::Multiple sync activities keep syncing state until all end()")
    @Test fun multipleActivitiesKeepSyncing() = runTest {
        val state = appStateOf(backgroundScope)
        state.connectionUI.syncActivityStarted()
        state.connectionUI.syncActivityStarted()
        assertEquals(StatusPillState.Syncing, state.statusPillState)
        state.connectionUI.syncActivityEnded(false)
        assertEquals(StatusPillState.Syncing, state.statusPillState)
        state.connectionUI.syncActivityEnded(false)
        assertTrue(state.statusPillState != StatusPillState.Syncing)
    }

    // endregion

    // region Ready toast

    @OriginalCase("ConnectionUIStateTests::showReadyToastBriefly sets showReadyToast to true()")
    @Test fun readyToastShows() = runTest {
        val state = appStateOf(backgroundScope)
        state.connectionUI.showReadyToastBriefly()
        assertTrue(state.connectionUI.showReadyToast)
        assertEquals(StatusPillState.Ready, state.statusPillState)
    }

    @OriginalCase("ConnectionUIStateTests::hideReadyToast immediately clears toast()")
    @Test fun readyToastHides() = runTest {
        val state = appStateOf(backgroundScope)
        state.connectionUI.showReadyToastBriefly()
        state.connectionUI.hideReadyToast()
        assertFalse(state.connectionUI.showReadyToast)
        assertEquals(StatusPillState.Hidden, state.statusPillState)
    }

    @OriginalCase("ConnectionUIStateTests::showReadyToastBriefly auto-hides after delay()")
    @Test fun readyToastAutoHides() = runTest {
        val ui = uiState()
        ui.showReadyToastBriefly()
        assertTrue(ui.showReadyToast)
        advance(1_999.milliseconds)
        assertTrue(ui.showReadyToast, "still visible just before the 2 s delay")
        advance(1.milliseconds)
        assertFalse(ui.showReadyToast)
    }

    @OriginalCase("ConnectionUIStateTests::Calling showReadyToastBriefly again resets the timer()")
    @Test fun readyToastRestartsItsTimer() = runTest {
        val ui = uiState()
        ui.showReadyToastBriefly()
        advance(1_500.milliseconds)
        assertTrue(ui.showReadyToast)
        ui.showReadyToastBriefly()
        assertTrue(ui.showReadyToast)
        advance(1.seconds)
        assertTrue(ui.showReadyToast)
        advance(1.seconds)
        assertFalse(ui.showReadyToast)
    }

    // endregion

    // region Sync failed pill

    @OriginalCase("ConnectionUIStateTests::showSyncFailedPill sets visible flag()")
    @Test fun syncFailedVisible() = runTest {
        val state = appStateOf(backgroundScope)
        state.connectionUI.showSyncFailedPill()
        assertTrue(state.connectionUI.syncFailedPillVisible)
        assertEquals(SYNC_FAILED, state.statusPillState)
    }

    @OriginalCase("ConnectionUIStateTests::hideSyncFailedPill immediately clears pill()")
    @Test fun syncFailedHides() = runTest {
        val state = appStateOf(backgroundScope)
        state.connectionUI.showSyncFailedPill()
        state.connectionUI.hideSyncFailedPill()
        assertFalse(state.connectionUI.syncFailedPillVisible)
    }

    @OriginalCase("ConnectionUIStateTests::showSyncFailedPill auto-hides after delay()")
    @Test fun syncFailedAutoHides() = runTest {
        val ui = uiState()
        ui.showSyncFailedPill()
        assertTrue(ui.syncFailedPillVisible)
        advance(6_999.milliseconds)
        assertTrue(ui.syncFailedPillVisible)
        advance(1.milliseconds)
        assertFalse(ui.syncFailedPillVisible)
    }

    // endregion

    // region Disconnected pill (state level)

    @OriginalCase("ConnectionUIStateTests::disconnectedPillVisible is false by default()")
    @Test fun disconnectedPillDefaultsOff() = runTest { assertFalse(appStateOf(backgroundScope).connectionUI.disconnectedPillVisible) }

    @OriginalCase("ConnectionUIStateTests::hideDisconnectedPill clears pill immediately()")
    @Test fun hideDisconnectedPillClears() = runTest {
        val state = appStateOf(backgroundScope)
        state.connectionUI.hideDisconnectedPill()
        assertFalse(state.connectionUI.disconnectedPillVisible)
    }

    @OriginalCase("ConnectionUIStateTests::updateDisconnectedPillState without paired device stays hidden()")
    @Test fun noPairedDeviceStaysHidden() = runTest {
        val ui = uiState()
        ui.updateDisconnectedPillState(DeviceConnectionState.DISCONNECTED, null, false)
        advance(5.seconds)
        assertFalse(ui.disconnectedPillVisible)
    }

    @OriginalCase("ConnectionUIStateTests::canRunSettingsStartupReads is false when disconnected()")
    @Test fun settingsReadsBlockedWhenDisconnected() = runTest {
        assertFalse(appStateOf(backgroundScope).canRunSettingsStartupReads)
    }

    @OriginalCase("ConnectionUIStateTests::Sync activity shows syncing pill while active()")
    @Test fun syncActivityShowsSyncing() = runTest {
        val state = appStateOf(backgroundScope)
        state.connectionUI.syncActivityStarted()
        assertEquals(StatusPillState.Syncing, state.statusPillState)
        state.connectionUI.syncActivityEnded(false)
        assertTrue(state.statusPillState != StatusPillState.Syncing)
    }

    @OriginalCase("ConnectionUIStateTests::Connection alert state defaults()")
    @Test fun alertDefaults() = runTest {
        val ui = appStateOf(backgroundScope).connectionUI
        assertFalse(ui.showingConnectionFailedAlert)
        assertNull(ui.connectionFailedMessage)
        assertNull(ui.failedPairingDeviceId)
        assertNull(ui.pairingFailureKind)
        assertNull(ui.otherAppWarningDeviceId)
        assertFalse(ui.isBusy)
        assertFalse(ui.isNodeStorageFull)
    }

    // endregion

    // region presentPairingFailure / presentConnectionFailure

    @OriginalCase("ConnectionUIStateTests::presentPairingFailure(auth) sets pairingFailureKind to .authentication()")
    @Test fun authFailureKind() = runTest {
        val ui = uiState()
        val id = UUID.randomUUID()
        ui.presentPairingFailure(connectionFailed(id, AUTH))
        assertEquals(id, ui.failedPairingDeviceId)
        assertEquals(PairingFailureKind.AUTHENTICATION, ui.pairingFailureKind)
        assertNotNull(ui.connectionFailedTitle)
        assertTrue(ui.showingConnectionFailedAlert)
    }

    @OriginalCase("ConnectionUIStateTests::presentPairingFailure(transient) sets pairingFailureKind to .transient()")
    @Test fun transientFailureKind() = runTest {
        val ui = uiState()
        val id = UUID.randomUUID()
        ui.presentPairingFailure(connectionFailed(id, LinkFailure.ConnectionFailed("timeout")))
        assertEquals(id, ui.failedPairingDeviceId)
        assertEquals(PairingFailureKind.TRANSIENT, ui.pairingFailureKind)
        assertNull(ui.connectionFailedTitle)
        assertTrue(ui.showingConnectionFailedAlert)
    }

    @OriginalCase("ConnectionUIStateTests::presentConnectionFailure clears pairingFailureKind set by a prior pairing failure()")
    @Test fun connectionFailureClearsPairingKind() = runTest {
        val ui = uiState()
        ui.presentPairingFailure(connectionFailed(UUID.randomUUID(), AUTH))
        assertEquals(PairingFailureKind.AUTHENTICATION, ui.pairingFailureKind)
        ui.presentConnectionFailure(UiText.Verbatim("generic"))
        assertNull(ui.pairingFailureKind)
        assertNull(ui.connectionFailedTitle)
    }

    // endregion

    // region handleDisconnect

    private fun ConnectionUiState.disconnect(lastDevice: UUID? = null, suppress: Boolean = false) =
        handleDisconnect(DeviceConnectionState.DISCONNECTED, lastDevice, suppress)

    @OriginalCase("ConnectionUIStateTests::handleDisconnect resets syncActivityCount to zero()")
    @Test fun disconnectResetsActivityCount() = runTest {
        val ui = uiState()
        ui.syncActivityStarted(); ui.syncActivityStarted()
        assertEquals(2, ui.syncActivityCount)
        ui.disconnect()
        assertEquals(0, ui.syncActivityCount)
    }

    @OriginalCase("ConnectionUIStateTests::handleDisconnect clears currentSyncPhase()")
    @Test fun disconnectClearsPhase() = runTest {
        val ui = uiState()
        ui.currentSyncPhase = SyncPhase.CONTACTS
        ui.disconnect()
        assertNull(ui.currentSyncPhase)
    }

    @OriginalCase("ConnectionUIStateTests::handleDisconnect sets isNodeStorageFull to false()")
    @Test fun disconnectClearsStorageFull() = runTest {
        val ui = uiState()
        ui.isNodeStorageFull = true
        ui.disconnect()
        assertFalse(ui.isNodeStorageFull)
    }

    @OriginalCase("ConnectionUIStateTests::handleDisconnect hides ready toast()")
    @Test fun disconnectHidesReadyToast() = runTest {
        val ui = uiState()
        ui.showReadyToastBriefly()
        assertTrue(ui.showReadyToast)
        ui.disconnect()
        assertFalse(ui.showReadyToast)
    }

    @OriginalCase("ConnectionUIStateTests::handleDisconnect shows disconnected pill when device was paired()")
    @Test fun disconnectShowsPillForPairedDevice() = runTest {
        val ui = uiState()
        ui.disconnect(lastDevice = UUID.randomUUID())
        runCurrent()
        assertFalse(ui.disconnectedPillVisible, "the pill waits one second")
        advance(1.seconds)
        assertTrue(ui.disconnectedPillVisible)
    }

    @OriginalCase("ConnectionUIStateTests::handleDisconnect does not show disconnected pill when suppressed()")
    @Test fun disconnectSuppressedPill() = runTest {
        val ui = uiState()
        ui.disconnect(lastDevice = UUID.randomUUID(), suppress = true)
        advance(5.seconds)
        assertFalse(ui.disconnectedPillVisible)
    }

    @OriginalCase("ConnectionUIStateTests::handleDisconnect does not show disconnected pill without paired device()")
    @Test fun disconnectWithoutPairedDevice() = runTest {
        val ui = uiState()
        ui.disconnect()
        advance(5.seconds)
        assertFalse(ui.disconnectedPillVisible)
    }

    @OriginalCase("ConnectionUIStateTests::handleDisconnect resets all state in a single call()")
    @Test fun disconnectResetsEverything() = runTest {
        val ui = uiState()
        ui.syncActivityStarted(); ui.syncActivityStarted()
        ui.currentSyncPhase = SyncPhase.CHANNELS
        ui.isNodeStorageFull = true
        ui.showReadyToastBriefly()
        ui.disconnect()
        assertEquals(0, ui.syncActivityCount)
        assertNull(ui.currentSyncPhase)
        assertFalse(ui.isNodeStorageFull)
        assertFalse(ui.showReadyToast)
    }

    // endregion

    // region DisconnectedPillTests (value-level; the intent-backed cases run against the real runtime in SessionStateTest)

    @OriginalCase("DisconnectedPillTests::disconnected pill not shown when user explicitly disconnected()")
    @Test fun pillNotShownAfterUserDisconnect() = runTest {
        val ui = uiState()
        ui.updateDisconnectedPillState(DeviceConnectionState.DISCONNECTED, UUID.randomUUID(), true)
        advance(1_200.milliseconds)
        assertFalse(ui.disconnectedPillVisible)
    }

    @OriginalCase("DisconnectedPillTests::disconnected pill shown after unexpected disconnect()")
    @Test fun pillShownAfterUnexpectedDisconnect() = runTest {
        val ui = uiState()
        ui.updateDisconnectedPillState(DeviceConnectionState.DISCONNECTED, UUID.randomUUID(), false)
        advance(1_200.milliseconds)
        assertTrue(ui.disconnectedPillVisible)
    }

    @OriginalCase("DisconnectedPillTests::disconnected pill not shown when no last connected device()")
    @Test fun pillNotShownWithoutLastDevice() = runTest {
        val ui = uiState()
        ui.updateDisconnectedPillState(DeviceConnectionState.DISCONNECTED, null, false)
        advance(1_200.milliseconds)
        assertFalse(ui.disconnectedPillVisible)
    }

    @OriginalCase("DisconnectedPillTests::disconnected pill hidden when connection starts()")
    @Test fun pillHiddenWhenConnectionStarts() = runTest {
        val ui = uiState()
        ui.updateDisconnectedPillState(DeviceConnectionState.DISCONNECTED, UUID.randomUUID(), false)
        advance(1_200.milliseconds)
        assertTrue(ui.disconnectedPillVisible)
        ui.hideDisconnectedPill()
        assertFalse(ui.disconnectedPillVisible)
    }

    @OriginalCase("DisconnectedPillTests::disconnected pill delay prevents flash during brief reconnects()")
    @Test fun pillDelayPreventsFlash() = runTest {
        val ui = uiState()
        ui.updateDisconnectedPillState(DeviceConnectionState.DISCONNECTED, UUID.randomUUID(), false)
        assertFalse(ui.disconnectedPillVisible)
        advance(500.milliseconds)
        ui.hideDisconnectedPill()
        advance(1.seconds)
        assertFalse(ui.disconnectedPillVisible)
    }

    // endregion

    // region AuthenticationFailureGatingTests

    @OriginalCase("AuthenticationFailureGatingTests::an active app presents the pairing-failure alert()")
    @Test fun activeAppPresentsAlert() = runTest {
        val state = appStateOf(backgroundScope)
        state.handleAuthenticationFailure(UUID.randomUUID(), isAppActive = true)
        assertTrue(state.connectionUI.showingConnectionFailedAlert)
        assertEquals(PairingFailureKind.AUTHENTICATION, state.connectionUI.pairingFailureKind)
    }

    @OriginalCase("AuthenticationFailureGatingTests::an inactive app suppresses the pairing-failure alert()")
    @Test fun inactiveAppSuppressesAlert() = runTest {
        val state = appStateOf(backgroundScope)
        state.handleAuthenticationFailure(UUID.randomUUID(), isAppActive = false)
        assertFalse(state.connectionUI.showingConnectionFailedAlert)
        assertNull(state.connectionUI.pairingFailureKind)
    }

    @OriginalCase("AuthenticationFailureGatingTests::clearing pairing failure resets every alert field()")
    @Test fun clearingResetsEveryField() = runTest {
        val ui = uiState()
        ui.presentPairingFailure(connectionFailed(UUID.randomUUID(), AUTH))
        assertTrue(ui.showingConnectionFailedAlert)
        ui.clearPairingFailure()
        assertFalse(ui.showingConnectionFailedAlert)
        assertNull(ui.connectionFailedTitle)
        assertNull(ui.connectionFailedMessage)
        assertNull(ui.pairingFailureKind)
        assertNull(ui.failedPairingDeviceId)
    }

    // endregion

    // region FreshPairingFailureRoutingTests

    @OriginalCase("FreshPairingFailureRoutingTests::rejected PIN surfaces the PIN-rejected recovery()")
    @Test fun rejectedPin() = runTest {
        val ui = uiState()
        val id = UUID.randomUUID()
        ui.presentFreshPairingFailure(connectionFailed(id, AUTH))
        assertEquals(PairingFailureKind.PIN_REJECTED, ui.pairingFailureKind)
        assertEquals(id, ui.failedPairingDeviceId)
        assertEquals(UiText.Resource(AppLocalizableStrings.alertPairingFailedTitle), ui.connectionFailedTitle)
        assertEquals(UiText.Resource(com.meshcoreone.android.core.l10n.R.string.wp305_pairing_pin_rejected), ui.connectionFailedMessage)
        assertTrue(ui.showingConnectionFailedAlert)
        assertNull(ui.otherAppWarningDeviceId)
    }

    @OriginalCase("FreshPairingFailureRoutingTests::macOS rejected PIN names System Settings Bluetooth forget()", "platform-adaptation")
    @Test fun rejectedPinWithoutSystemRegistry() = runTest {
        val ui = uiState()
        ui.hasSystemPairingRegistry = false
        ui.presentFreshPairingFailure(connectionFailed(UUID.randomUUID(), AUTH))
        assertEquals(UiText.Resource(com.meshcoreone.android.core.l10n.R.string.wp305_pairing_pin_rejected), ui.connectionFailedMessage)
    }

    @OriginalCase("FreshPairingFailureRoutingTests::saved-device authentication failure keeps the dead-bond recovery()")
    @Test fun savedDeviceAuthKeepsDeadBondRecovery() = runTest {
        val ui = uiState()
        ui.presentSavedDeviceConnectFailure(UUID.randomUUID(), AUTH)
        assertEquals(PairingFailureKind.AUTHENTICATION, ui.pairingFailureKind)
        assertEquals(UiText.Resource(com.meshcoreone.android.core.l10n.R.string.wp305_pairing_authentication_failed), ui.connectionFailedMessage)
        assertEquals(UiText.Resource(AppLocalizableStrings.alertPairingFailedTitle), ui.connectionFailedTitle)
    }

    @OriginalCase("FreshPairingFailureRoutingTests::transient fresh-pair failure keeps the non-destructive retry()")
    @Test fun transientFreshPairKeepsRetry() = runTest {
        val ui = uiState()
        val id = UUID.randomUUID()
        ui.presentFreshPairingFailure(connectionFailed(id, TIMEOUT))
        assertEquals(PairingFailureKind.TRANSIENT, ui.pairingFailureKind)
        assertEquals(id, ui.failedPairingDeviceId)
        assertNull(ui.connectionFailedTitle)
        assertEquals(UiText.Resource(AppOnboardingStrings.deviceScanErrorConnectionFailed), ui.connectionFailedMessage)
        assertTrue(ui.showingConnectionFailedAlert)
    }

    @OriginalCase("FreshPairingFailureRoutingTests::fresh-pair other-app failure routes to the other-app warning()")
    @Test fun freshPairOtherApp() = runTest {
        val ui = uiState()
        val id = UUID.randomUUID()
        ui.presentFreshPairingFailure(PairingError.DeviceConnectedToOtherApp(id))
        assertEquals(id, ui.otherAppWarningDeviceId)
        assertNull(ui.pairingFailureKind)
        assertFalse(ui.showingConnectionFailedAlert)
    }

    // endregion

    // region SavedDeviceConnectFailureRoutingTests

    @OriginalCase("SavedDeviceConnectFailureRoutingTests::authentication failure surfaces guided re-pair recovery()")
    @Test fun savedAuthFailureGuidedRecovery() = runTest {
        val ui = uiState()
        val id = UUID.randomUUID()
        ui.presentSavedDeviceConnectFailure(id, AUTH)
        assertEquals(id, ui.failedPairingDeviceId)
        assertEquals(PairingFailureKind.AUTHENTICATION, ui.pairingFailureKind)
        assertEquals(UiText.Resource(AppLocalizableStrings.alertPairingFailedTitle), ui.connectionFailedTitle)
        assertEquals(UiText.Resource(com.meshcoreone.android.core.l10n.R.string.wp305_pairing_authentication_failed), ui.connectionFailedMessage)
        assertTrue(ui.showingConnectionFailedAlert)
        assertNull(ui.otherAppWarningDeviceId)
    }

    @OriginalCase("SavedDeviceConnectFailureRoutingTests::macOS authentication failure names System Settings Bluetooth forget()", "platform-adaptation")
    @Test fun savedAuthFailureWithoutSystemRegistry() = runTest {
        val ui = uiState()
        ui.hasSystemPairingRegistry = false
        ui.presentSavedDeviceConnectFailure(UUID.randomUUID(), AUTH)
        assertEquals(UiText.Resource(com.meshcoreone.android.core.l10n.R.string.wp305_pairing_authentication_failed), ui.connectionFailedMessage)
    }

    @OriginalCase("SavedDeviceConnectFailureRoutingTests::other-app failure routes to the other-app warning()")
    @Test fun savedOtherApp() = runTest {
        val ui = uiState()
        val id = UUID.randomUUID()
        ui.presentSavedDeviceConnectFailure(id, LinkFailure.DeviceConnectedToOtherApp())
        assertEquals(id, ui.otherAppWarningDeviceId)
        assertFalse(ui.showingConnectionFailedAlert)
        assertNull(ui.failedPairingDeviceId)
        assertNull(ui.pairingFailureKind)
    }

    @OriginalCase("SavedDeviceConnectFailureRoutingTests::non-auth failure routes to the generic connection-failed alert()")
    @Test fun savedNonAuthIsGeneric() = runTest {
        val ui = uiState()
        ui.presentSavedDeviceConnectFailure(UUID.randomUUID(), TIMEOUT)
        assertTrue(ui.showingConnectionFailedAlert)
        assertNull(ui.failedPairingDeviceId)
        assertNull(ui.pairingFailureKind)
        assertNull(ui.connectionFailedTitle)
        assertNull(ui.otherAppWarningDeviceId)
    }

    @OriginalCase("SavedDeviceConnectFailureRoutingTests::generic failure clears a stale failed-pairing device id()")
    @Test fun genericFailureClearsStaleDevice() = runTest {
        val ui = uiState()
        ui.presentPairingFailure(connectionFailed(UUID.randomUUID(), AUTH))
        assertNotNull(ui.failedPairingDeviceId)
        ui.presentSavedDeviceConnectFailure(UUID.randomUUID(), TIMEOUT)
        assertNull(ui.failedPairingDeviceId)
        assertNull(ui.pairingFailureKind)
    }

    // endregion
}
