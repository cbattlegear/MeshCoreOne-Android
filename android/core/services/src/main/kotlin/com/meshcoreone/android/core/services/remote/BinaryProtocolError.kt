// PortedFrom: MC1Services/Sources/MC1Services/Services/BinaryProtocolService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.errors.BinaryProtocolFault
import com.meshcoreone.android.core.contracts.domain.errors.SourceServiceFaultCarrier
import com.meshcoreone.android.core.protocol.config.MeshCoreException

/**
 * Binary-protocol failures. `message` carries the Swift `errorDescription` text verbatim; [SessionError]
 * passes the wrapped mesh error's description through, like Swift's `e.localizedDescription`.
 * Each case is a class (not an object) so every throw gets its own stack trace.
 */
sealed class BinaryProtocolError(message: String, cause: Throwable? = null) :
    Exception(message, cause), SourceServiceFaultCarrier {
    class NotConnected : BinaryProtocolError("Not connected to device.")
    class SendFailed : BinaryProtocolError("Failed to send request.")
    class Timeout : BinaryProtocolError("Request timed out.")
    class InvalidResponse : BinaryProtocolError("Invalid response from device.")
    class SessionError(val error: MeshCoreException) : BinaryProtocolError(error.message ?: error.toString(), error)

    val errorDescription: String get() = message ?: ""

    /** Exhaustive projection onto the neutral payload; the session cause is carried as the same instance. */
    override val sourceServiceFault: BinaryProtocolFault
        get() = when (this) {
            is NotConnected -> BinaryProtocolFault.NotConnected
            is SendFailed -> BinaryProtocolFault.SendFailed
            is Timeout -> BinaryProtocolFault.Timeout
            is InvalidResponse -> BinaryProtocolFault.InvalidResponse
            is SessionError -> BinaryProtocolFault.SessionError(error)
        }

    override fun toString(): String = when (this) {
        is SessionError -> "BinaryProtocolError.sessionError(${error.javaClass.simpleName})"
        else -> "BinaryProtocolError.${javaClass.simpleName.replaceFirstChar(Char::lowercaseChar)}"
    }
}
