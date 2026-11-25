// PortedFrom: MC1Services/Sources/MC1Services/Services/ContactCleanupCoordinator.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.RoomPersisting
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs the cross-service side effects of a contact lifecycle change: channel-message deletion and
 * blocked-name cache refresh on block, notification cleanup and badge updates, and remote node
 * session removal on delete. Built by the service container and injected into [ContactService].
 *
 * Holds the collaborating services directly, never the container, so a torn-down container cannot
 * be kept alive through this coordinator. Stateless, so no confinement is needed.
 */
class ContactCleanupCoordinator(
    private val contacts: ContactPersisting,
    private val rooms: RoomPersisting,
    private val syncCoordinator: ContactSyncCoordinating,
    private val notificationService: ContactCleanupNotifications,
    private val remoteNodeService: ContactCleanupRemoteSessions,
    private val radioId: RadioId,
) : ContactCleanupHandling {
    override suspend fun handleCleanup(contact: EntityKey, reason: ContactCleanupReason, publicKey: Bytes) {
        // Refresh blocked names cache and delete channel messages on block
        if (reason == ContactCleanupReason.BLOCKED || reason == ContactCleanupReason.UNBLOCKED) {
            val stored = contactTryOptional { contacts.fetchContact(contact) }
            if (stored != null) {
                if (reason == ContactCleanupReason.BLOCKED) {
                    contactTryOptional { contacts.deleteChannelMessages(stored.name, stored.radioId) }
                }
                syncCoordinator.refreshBlockedContactsCache(stored.radioId, contacts)
                syncCoordinator.notifyConversationsChanged()
            }
        }

        // Remove delivered notifications for this contact (only on block/delete)
        if (reason == ContactCleanupReason.BLOCKED || reason == ContactCleanupReason.DELETED) {
            notificationService.removeDeliveredNotifications(contact.id)
        }

        // Update badge count
        notificationService.updateBadgeCount()

        // Clean up any associated remote node session on delete
        if (reason == ContactCleanupReason.DELETED) {
            val session = contactTryOptional { rooms.fetchRemoteNodeSessions(radioId) }?.firstOrNull { it.publicKey == publicKey }
            if (session != null) {
                contactTryOptional { remoteNodeService.removeSession(session.id, publicKey) }
            }
            syncCoordinator.notifyConversationsChanged()
        }
    }
}

/**
 * Swift `try?`: a failed step yields `null` and the chain continues. Cancellation is never
 * swallowed, unlike Swift where `try?` would also absorb a `CancellationError`.
 */
internal suspend inline fun <T> contactTryOptional(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    null
}
