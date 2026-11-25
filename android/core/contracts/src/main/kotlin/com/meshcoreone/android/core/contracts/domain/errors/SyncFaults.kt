// PortedFrom: MC1Services/Sources/MC1Services/Sync/SyncCoordinator.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.contracts.domain.errors

sealed interface SyncFault : SourceServiceFault {
    data object NotConnected : SyncFault
    data class SyncFailed(val reason: String) : SyncFault
    data object AlreadySyncing : SyncFault
}
