// PortedFrom: MC1Services/Sources/MC1Services/Errors/RemoteNodeError.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.errors.RemoteNodeFault
import com.meshcoreone.android.core.contracts.domain.errors.SourceServiceFaultCarrier
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.protocol.config.MeshCoreException

/**
 * Remote-node failures. `message` carries the Swift `errorDescription` text verbatim.
 * Each case is a class (not an object) so every throw gets its own stack trace.
 */
sealed class RemoteNodeError(message: String, cause: Throwable? = null) :
    Exception(message, cause), SourceServiceFaultCarrier {
    class NotConnected : RemoteNodeError("Not connected to mesh device")
    class LoginFailed(val reason: String) : RemoteNodeError("Login failed: $reason")
    class SendFailed(val reason: String) : RemoteNodeError("Failed to send: $reason")
    class InvalidResponse : RemoteNodeError("Invalid response from remote node")
    class PermissionDenied : RemoteNodeError("Permission denied")
    class Timeout : RemoteNodeError("Request timed out")
    class SessionNotFound : RemoteNodeError("Remote node session not found")
    class PasswordNotFound : RemoteNodeError("Password not found in keychain")

    /** Keep-alive requires a direct path. */
    class FloodRouted : RemoteNodeError("Keep-alive requires direct routing path")
    class PathDiscoveryFailed : RemoteNodeError("Failed to establish direct path")
    class ContactNotFound : RemoteNodeError("Contact not found in database")

    /** The radio's contact table is full; a missing node cannot be auto-added. */
    class RadioContactsFull : RemoteNodeError("Radio contact list is full")

    /** Login cancelled because of a duplicate attempt or shutdown. */
    class Cancelled : RemoteNodeError("Login cancelled")
    class SessionError(val error: MeshCoreException) : RemoteNodeError(error.message ?: error.toString(), error)
    class InvalidSensorSetting : RemoteNodeError("Invalid sensor setting")
    class SensorSettingRejected : RemoteNodeError("Sensor rejected the setting")
    class SensorOutcomeUnknown : RemoteNodeError("Sensor setting outcome unknown; reload before retrying")

    val errorDescription: String get() = message ?: ""

    val isRetryable: Boolean
        get() = this is Timeout || this is NotConnected || this is FloodRouted

    /** Exhaustive projection onto the neutral payload; raw reasons and the session cause are carried as-is. */
    override val sourceServiceFault: RemoteNodeFault
        get() = when (this) {
            is NotConnected -> RemoteNodeFault.NotConnected
            is LoginFailed -> RemoteNodeFault.LoginFailed(reason)
            is SendFailed -> RemoteNodeFault.SendFailed(reason)
            is InvalidResponse -> RemoteNodeFault.InvalidResponse
            is PermissionDenied -> RemoteNodeFault.PermissionDenied
            is Timeout -> RemoteNodeFault.Timeout
            is SessionNotFound -> RemoteNodeFault.SessionNotFound
            is PasswordNotFound -> RemoteNodeFault.PasswordNotFound
            is FloodRouted -> RemoteNodeFault.FloodRouted
            is PathDiscoveryFailed -> RemoteNodeFault.PathDiscoveryFailed
            is ContactNotFound -> RemoteNodeFault.ContactNotFound
            is RadioContactsFull -> RemoteNodeFault.RadioContactsFull
            is Cancelled -> RemoteNodeFault.Cancelled
            is SessionError -> RemoteNodeFault.SessionError(error)
            is InvalidSensorSetting -> RemoteNodeFault.InvalidSensorSetting
            is SensorSettingRejected -> RemoteNodeFault.SensorSettingRejected
            is SensorOutcomeUnknown -> RemoteNodeFault.SensorOutcomeUnknown
        }

    override fun toString(): String = when (this) {
        is LoginFailed -> "RemoteNodeError.loginFailed(\"$reason\")"
        is SendFailed -> "RemoteNodeError.sendFailed(\"$reason\")"
        is SessionError -> "RemoteNodeError.sessionError(${error.javaClass.simpleName})"
        else -> "RemoteNodeError.${javaClass.simpleName.replaceFirstChar(Char::lowercaseChar)}"
    }

    internal companion object {
        fun invalidPublicKeyLength(actual: Int): LoginFailed = LoginFailed(
            "Invalid public key length: expected ${ProtocolLimits.PUBLIC_KEY_SIZE} bytes, got $actual",
        )
    }
}
