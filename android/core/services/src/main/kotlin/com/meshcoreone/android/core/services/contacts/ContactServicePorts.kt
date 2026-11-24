// PortedFrom: MC1Services/Sources/MC1Services/Services/ContactService.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/ContactCleanupCoordinator.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID

/*
 * Narrow seams for collaborators whose Kotlin ports (SyncCoordinator, NotificationService,
 * RemoteNodeService, the persistent logger, UserDefaults) are not on this package's base. Each
 * interface lists exactly the members the Swift contact code calls, with the Swift signatures.
 */

/** The `SyncCoordinator` members used by `ContactService` and `ContactCleanupCoordinator`. */
interface ContactSyncCoordinating {
    /**
     * Waits out an advert-driven delta sync and claims the manual refresh flag atomically.
     * Throws when the wait times out; throws `CancellationException` when the caller is cancelled.
     */
    suspend fun claimManualContactSync()

    /** Marks or clears a user-initiated contact refresh so advert delta sync returns busy. */
    suspend fun setManualContactSyncActive(active: Boolean)

    /** Notifies UI observers that the contacts list changed. */
    suspend fun notifyContactsChanged()

    /** Notifies UI observers that the conversations list changed. */
    suspend fun notifyConversationsChanged()

    /** Rebuilds the blocked-names cache for [radioId] from [dataStore]; never throws. */
    suspend fun refreshBlockedContactsCache(radioId: RadioId, dataStore: ContactPersisting)
}

/** The `NotificationService` members used by `ContactCleanupCoordinator`. */
interface ContactCleanupNotifications {
    suspend fun removeDeliveredNotifications(contactId: UUID)
    suspend fun updateBadgeCount()
}

/** The `RemoteNodeService` member used by `ContactCleanupCoordinator`. */
interface ContactCleanupRemoteSessions {
    suspend fun removeSession(id: UUID, publicKey: Bytes)
}

/** Boolean preference store standing in for `UserDefaults.standard` (favorites migration flag). */
interface ContactPreferenceFlags {
    fun bool(key: String): Boolean
    fun set(key: String, value: Boolean)
    fun trueKeys(prefix: String): Set<String> =
        throw UnsupportedOperationException("Contact preference key enumeration is unavailable")
}

/** Severity levels used by the Swift `PersistentLogger` calls in `ContactService`. */
enum class ContactLogLevel { INFO, NOTICE, WARNING }

/** Destination for `ContactService` log lines (Swift `PersistentLogger`, category "ContactService"). */
fun interface ContactServiceLogger {
    fun log(level: ContactLogLevel, message: String)

    companion object {
        /** Discards every line; production wiring injects the persistent logger. */
        val NONE: ContactServiceLogger = ContactServiceLogger { _, _ -> }
    }
}
