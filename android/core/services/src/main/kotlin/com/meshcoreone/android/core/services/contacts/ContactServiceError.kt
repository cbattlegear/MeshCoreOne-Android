// PortedFrom: MC1Services/Sources/MC1Services/Services/ContactService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.errors.ContactServiceFault
import com.meshcoreone.android.core.contracts.domain.errors.SourceServiceFaultCarrier
import com.meshcoreone.android.core.protocol.config.MeshCoreException

/**
 * Errors surfaced by [ContactService]. Mirrors the Swift `ContactServiceError` enum; each case is a
 * distinct exception class so a fresh stack trace is captured per throw, and [errorDescription]
 * carries the Swift `LocalizedError.errorDescription` text verbatim.
 */
sealed class ContactServiceError(
    val errorDescription: String,
    cause: Throwable? = null,
) : Exception(errorDescription, cause), SourceServiceFaultCarrier {
    class NotConnected : ContactServiceError("Not connected to radio")
    class SendFailed : ContactServiceError("Failed to send message")
    class InvalidResponse : ContactServiceError("Invalid response from device")
    class SyncInterrupted : ContactServiceError("Sync was interrupted")
    class ContactNotFound : ContactServiceError("Contact not found on device")
    class ContactTableFull : ContactServiceError("Device node list is full")
    class ShareContactUnavailable :
        ContactServiceError("Unable to share node. The node's advertisement may be missing or too old.")

    /** Wraps a session-level failure; the description is the session error's own message. */
    class SessionError(val error: MeshCoreException) : ContactServiceError(error.message.orEmpty(), error)

    /** Exhaustive projection onto the neutral payload; the session cause is carried as the same instance. */
    override val sourceServiceFault: ContactServiceFault
        get() = when (this) {
            is NotConnected -> ContactServiceFault.NotConnected
            is SendFailed -> ContactServiceFault.SendFailed
            is InvalidResponse -> ContactServiceFault.InvalidResponse
            is SyncInterrupted -> ContactServiceFault.SyncInterrupted
            is ContactNotFound -> ContactServiceFault.ContactNotFound
            is ContactTableFull -> ContactServiceFault.ContactTableFull
            is ShareContactUnavailable -> ContactServiceFault.ShareContactUnavailable
            is SessionError -> ContactServiceFault.SessionError(error)
        }

    override fun toString(): String = when (this) {
        is SessionError -> "ContactServiceError.sessionError(${error.javaClass.simpleName})"
        else -> "ContactServiceError.${javaClass.simpleName.replaceFirstChar(Char::lowercaseChar)}"
    }
}

/** Reason for contact cleanup (deletion or blocking). */
enum class ContactCleanupReason {
    DELETED,
    BLOCKED,
    UNBLOCKED,
}
