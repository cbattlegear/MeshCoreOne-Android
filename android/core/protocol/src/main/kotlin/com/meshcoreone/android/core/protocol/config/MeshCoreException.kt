// PortedFrom: MeshCore/Sources/MeshCore/Session/SessionConfiguration.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.config

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.ErrorCode

sealed class MeshCoreException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    open val deviceErrorCode: ErrorCode? get() = null

    class Timeout : MeshCoreException("Mesh operation timed out")
    class DeviceError(val code: UByte) : MeshCoreException("Device returned error code ${code.toInt()}") {
        override val deviceErrorCode: ErrorCode? get() = ErrorCode.fromRawValue(code)
    }
    class ParseError(val reason: String) : MeshCoreException("Could not parse device response")
    class NotConnected : MeshCoreException("Transport is not connected")
    class CommandFailed(val command: CommandCode, val reason: String) : MeshCoreException("Device command failed")
    class InvalidResponse(val expected: String, val got: String) : MeshCoreException("Device response did not match the request")
    class ContactNotFound(val publicKeyPrefix: Bytes) : MeshCoreException("Contact was not found")
    class DataTooLarge(val maxSize: Long, val actualSize: Long) :
        MeshCoreException("Payload is too large: maximum=$maxSize, actual=$actualSize")
    class SigningFailed(val reason: String) : MeshCoreException("Signing failed")
    class InvalidInput(val reason: String) : MeshCoreException("Invalid mesh input")
    class Unknown(val reason: String) : MeshCoreException("Unknown mesh failure")
    class BluetoothUnavailable : MeshCoreException("Bluetooth is unavailable")
    class BluetoothUnauthorized : MeshCoreException("Bluetooth permission is missing")
    class BluetoothPoweredOff : MeshCoreException("Bluetooth is powered off")
    class ConnectionLost(cause: Throwable? = null) : MeshCoreException("Connection was lost", cause)
    class SessionNotStarted : MeshCoreException("Session has not been started")
    class FeatureDisabled : MeshCoreException("The device disabled this feature")
}
