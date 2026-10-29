// PortedFrom: MC1/Views/Onboarding/DeviceScanView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.onboarding

import com.meshcoreone.android.core.ui.UiText
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PairSheet { NONE, TROUBLESHOOTING, WIFI, NO_DEVICE }
/** What the pair step needs from the user before pairing can start. */
enum class PairingBlocker { NONE, BLUETOOTH_PERMISSION, BLUETOOTH_PERMISSION_DENIED, BLUETOOTH_OFF }

data class DeviceScanState(
    val sheet: PairSheet = PairSheet.NONE,
    val didInitiatePairing: Boolean = false,
    val otherAppDeviceId: UUID? = null,
    val failure: UiText? = null,
    val showDemoAlert: Boolean = false,
    val blocker: PairingBlocker = PairingBlocker.NONE,
    val localBusy: Boolean = false,
    val successTick: Int = 0,
    val failureTick: Int = 0,
)

enum class PairPrimaryAction { CONNECT_DEMO, RETRY_CONNECTION, ADD_DEVICE }

/** Pair step: CDM/direct pairing, demo mode, other-app retry and sheet routing. */
class DeviceScanCoordinator(
    private val scope: CoroutineScope,
    private val pairing: OnboardingPairingPort,
    private val demo: OnboardingDemoPort,
    private val permissions: OnboardingPermissionPort,
    private val onConnected: () -> Unit,
) {
    private val mutable = MutableStateFlow(DeviceScanState())
    val state: StateFlow<DeviceScanState> = mutable.asStateFlow()
    private var job: Job? = null
    private var titleTaps = 0

    fun isBusy(connection: OnboardingConnectionSnapshot): Boolean = connection.isBusy || mutable.value.localBusy

    fun primaryAction(demoEnabled: Boolean, otherAppDeviceId: UUID?): PairPrimaryAction = when {
        demoEnabled -> PairPrimaryAction.CONNECT_DEMO
        otherAppDeviceId != null -> PairPrimaryAction.RETRY_CONNECTION
        else -> PairPrimaryAction.ADD_DEVICE
    }

    fun showSheet(sheet: PairSheet) = mutable.update { it.copy(sheet = sheet) }
    fun dismissSheet() = showSheet(PairSheet.NONE)
    fun dismissFailure() = mutable.update { it.copy(failure = null) }
    fun dismissDemoAlert() = mutable.update { it.copy(showDemoAlert = false) }

    /** Three taps on the title unlock demo mode (App Store reviewer path, preserved from the original). */
    fun onTitleTap() {
        titleTaps += 1
        if (titleTaps < TITLE_TAPS_TO_UNLOCK) return
        titleTaps = 0
        demo.unlock()
        mutable.update { it.copy(showDemoAlert = true, successTick = it.successTick + 1) }
    }

    /** Re-evaluates the Bluetooth blocker; the UI calls this after each permission result. */
    fun blockerFor(snapshot: OnboardingPermissionSnapshot): PairingBlocker = when {
        snapshot.bluetooth == PermissionStatus.DENIED -> PairingBlocker.BLUETOOTH_PERMISSION_DENIED
        snapshot.bluetooth == PermissionStatus.NOT_DETERMINED -> PairingBlocker.BLUETOOTH_PERMISSION
        !snapshot.bluetoothAdapterEnabled -> PairingBlocker.BLUETOOTH_OFF
        else -> PairingBlocker.NONE
    }

    /**
     * Starts pairing. Returns the blocker when a just-in-time permission or adapter state must be
     * resolved first (the UI then requests the permission and calls this again).
     */
    fun startPairing(): PairingBlocker {
        val blocker = blockerFor(permissions.snapshot.value)
        mutable.update { it.copy(blocker = blocker) }
        if (blocker != PairingBlocker.NONE) return blocker
        run(initiated = true) { pairing.pairNewDevice() }
        return PairingBlocker.NONE
    }

    fun retryConnection(deviceId: UUID) = run(initiated = false) { pairing.retryConnection(deviceId) }
    fun connectDemo() = run(initiated = true) { demo.connectDemo() }

    private fun run(initiated: Boolean, action: suspend () -> OnboardingConnectOutcome) {
        if (mutable.value.localBusy) return
        mutable.update { it.copy(localBusy = true, didInitiatePairing = it.didInitiatePairing || initiated, failure = null) }
        job = scope.launch {
            val outcome = try {
                action()
            } catch (cancel: CancellationException) {
                mutable.update { it.copy(localBusy = false) }
                throw cancel
            } catch (failure: Exception) {
                OnboardingConnectOutcome.Failed(UiText.Verbatim(failure.message.orEmpty()))
            }
            apply(outcome)
        }
    }

    private fun apply(outcome: OnboardingConnectOutcome) {
        when (outcome) {
            OnboardingConnectOutcome.Connected -> {
                mutable.update { it.copy(localBusy = false, otherAppDeviceId = null, successTick = it.successTick + 1) }
                onConnected()
            }
            OnboardingConnectOutcome.Cancelled, OnboardingConnectOutcome.Ignored ->
                mutable.update { it.copy(localBusy = false) }
            is OnboardingConnectOutcome.Failed -> mutable.update {
                it.copy(
                    localBusy = false, failure = outcome.message.takeUnless { outcome.presentedByConnectionUi },
                    otherAppDeviceId = outcome.retryDeviceId,
                    failureTick = it.failureTick + 1,
                )
            }
        }
    }

    /** Continue after an already-connected radio (resume path). */
    fun continueConnected() = onConnected()

    private companion object { const val TITLE_TAPS_TO_UNLOCK = 3 }
}
