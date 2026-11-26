// PortedFrom: MC1Services/Sources/MC1Services/Services/ContactCleanupHandling.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.protocol.bytes.Bytes

/**
 * Cross-service side effects of a contact lifecycle change (block, unblock, delete).
 * [ContactService] invokes this after its own database writes; [ContactCleanupCoordinator] is the
 * production implementation.
 */
interface ContactCleanupHandling {
    /**
     * Runs the cleanup chain for one contact. Never throws except `CancellationException`.
     * @param contact The affected contact's radio-scoped local key.
     * @param reason Which lifecycle change triggered the cleanup.
     * @param publicKey The contact's public key, used to locate any associated remote node session.
     */
    suspend fun handleCleanup(contact: EntityKey, reason: ContactCleanupReason, publicKey: Bytes)
}
