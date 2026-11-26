// PortedFrom: MC1/State/ConnectionUIState.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: Failure classification over the Android link/BLE failure types the runtime produces.
package com.meshcoreone.android.app.state

import com.meshcoreone.android.core.ble.BleError
import com.meshcoreone.android.core.ble.BleTransportException
import com.meshcoreone.android.core.runtime.LinkFailure

/**
 * Variant of the pairing-failure alert. Determines whether the recovery action is destructive (authentication:
 * the bond must be removed) or non-destructive (transient: keep the bond and retry).
 */
enum class PairingFailureKind {
    /** Authentication failed: the bond is bad and recovery removes it and re-pairs. */
    AUTHENTICATION,

    /** A fresh pairing attempt was rejected, typically a wrong PIN. */
    PIN_REJECTED,

    /** Transient failure: the bond is good and recovery prefers a plain retry. */
    TRANSIENT,
}

/** Classifies connection failures through their cause chain (runtime wraps link failures). */
object ConnectionFailures {
    private const val MAX_CAUSE_DEPTH = 8

    private fun chain(failure: Throwable): List<Throwable> =
        generateSequence(failure) { it.cause }.take(MAX_CAUSE_DEPTH).toList()

    fun isAuthenticationFailure(failure: Throwable): Boolean = chain(failure).any {
        it is LinkFailure.AuthenticationFailed || (it is BleTransportException && it.error == BleError.AuthenticationFailed)
    }

    fun isDeviceConnectedToOtherApp(failure: Throwable): Boolean = chain(failure).any {
        it is LinkFailure.DeviceConnectedToOtherApp ||
            (it is BleTransportException && it.error == BleError.DeviceConnectedToOtherApp)
    }
}
