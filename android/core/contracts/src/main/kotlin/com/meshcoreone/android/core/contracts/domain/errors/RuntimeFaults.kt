// PortedFrom: MC1Services/Sources/MC1Services/Errors/ConnectionError.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Utilities/TimeoutUtility.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native connection ownership failures retain their existing typed distinctions.
package com.meshcoreone.android.core.contracts.domain.errors

import kotlin.time.Duration

sealed interface ConnectionFault : SourceServiceFault {
    data class ConnectionFailed(val reason: String) : ConnectionFault
    data object DeviceNotFound : ConnectionFault
    data object NotConnected : ConnectionFault
    data class InitializationFailed(val reason: String) : ConnectionFault
    data class UnsupportedCapability(val capability: String) : ConnectionFault
    data object ForeignPhysicalOwner : ConnectionFault
    data object RetainedPhysicalLink : ConnectionFault
    data object InvalidIdentity : ConnectionFault
    data object FactoryOwnershipViolation : ConnectionFault
}

data class RuntimeTimeoutFault(val operationName: String, val timeout: Duration) : SourceServiceFault
