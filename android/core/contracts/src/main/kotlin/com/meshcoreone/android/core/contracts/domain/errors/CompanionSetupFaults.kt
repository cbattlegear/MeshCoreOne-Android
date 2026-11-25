// PortedFrom: MC1Services/Sources/MC1Services/Services/AccessorySetupKitService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native declined-removal outcome retains its existing distinction.
package com.meshcoreone.android.core.contracts.domain.errors

sealed interface CompanionSetupFault : SourceServiceFault {
    data object SessionNotActive : CompanionSetupFault
    data object SessionInvalidated : CompanionSetupFault
    data object PickerDismissed : CompanionSetupFault
    data object PickerRestricted : CompanionSetupFault
    data object PickerAlreadyActive : CompanionSetupFault
    data class PairingFailed(val reason: String) : CompanionSetupFault
    data object NoBluetoothIdentifier : CompanionSetupFault
    data object DiscoveryTimeout : CompanionSetupFault
    data object ConnectionFailed : CompanionSetupFault
    data object UserCancelled : CompanionSetupFault
}
