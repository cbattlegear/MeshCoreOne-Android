// PortedFrom: MC1Services/Sources/MC1Services/Errors/ConnectionError.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.errors.ConnectionFault
import com.meshcoreone.android.core.contracts.domain.errors.SourceServiceFaultCarrier

sealed class ConnectionError(message: String, cause: Throwable? = null) :
    Exception(message, cause), SourceServiceFaultCarrier {
    class ConnectionFailed(val reason: String, cause: Throwable? = null) : ConnectionError("Connection failed: $reason", cause)
    class DeviceNotFound : ConnectionError("Device not found")
    class NotConnected : ConnectionError("Not connected to device")
    class InitializationFailed(val reason: String, cause: Throwable? = null) :
        ConnectionError("Device initialization failed: $reason", cause)
    class UnsupportedCapability(val capability: String) : ConnectionError("Unsupported connection capability: $capability")
    class ForeignPhysicalOwner : ConnectionError("Physical connection is owned by another runtime")
    class RetainedPhysicalLink : ConnectionError("A retained physical link cannot become a fresh protocol generation")
    class InvalidIdentity(message: String = "Radio public key must contain exactly 32 bytes") : ConnectionError(message)
    class FactoryOwnershipViolation : ConnectionError("Factory must return its registered generation-owned service handle")

    override val sourceServiceFault: ConnectionFault
        get() = when (this) {
            is ConnectionFailed -> ConnectionFault.ConnectionFailed(reason)
            is DeviceNotFound -> ConnectionFault.DeviceNotFound
            is NotConnected -> ConnectionFault.NotConnected
            is InitializationFailed -> ConnectionFault.InitializationFailed(reason)
            is UnsupportedCapability -> ConnectionFault.UnsupportedCapability(capability)
            is ForeignPhysicalOwner -> ConnectionFault.ForeignPhysicalOwner
            is RetainedPhysicalLink -> ConnectionFault.RetainedPhysicalLink
            is InvalidIdentity -> ConnectionFault.InvalidIdentity
            is FactoryOwnershipViolation -> ConnectionFault.FactoryOwnershipViolation
        }
}
