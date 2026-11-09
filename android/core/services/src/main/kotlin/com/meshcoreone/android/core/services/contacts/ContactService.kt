// PortedFrom: MC1Services/Sources/MC1Services/Services/ContactService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.ContactAvatarUpdateResult
import com.meshcoreone.android.core.contracts.domain.ContactFavoriteUpdateResult
import com.meshcoreone.android.core.contracts.domain.ContactBlockTransition
import com.meshcoreone.android.core.contracts.domain.ContactLocalUpdate
import com.meshcoreone.android.core.contracts.domain.ContactLocalUpdateResult
import com.meshcoreone.android.core.contracts.domain.ContactServiceProtocol
import com.meshcoreone.android.core.contracts.domain.ContactSyncResult
import com.meshcoreone.android.core.contracts.domain.DevicePersisting
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.VContactIdentity
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.ErrorCode
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.session.ChannelWriteCancellationDisposition
import com.meshcoreone.android.core.protocol.session.ChannelWriteDispatch
import com.meshcoreone.android.core.protocol.session.ContactSessionOps
import com.meshcoreone.android.core.protocol.session.ContactWriteDispatch
import com.meshcoreone.android.core.protocol.session.ContactWriteDispatchOutcome
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Service for managing mesh network contacts: discovery, sync, add/update/remove operations.
 *
 * The Swift type is an actor with no mutable state of its own (actor reentrancy means it never
 * serialised work across suspension points), so this class needs no confinement: the event
 * broadcaster is lock-confined and every other member is an immutable injected collaborator. One
 * instance lives for one connection; the owning container calls [finishEvents] on teardown.
 *
 * @param devices Device persistence role (self public key for ZephCore V-contact protection).
 * @param contacts Contact persistence role.
 * @param syncCoordinator UI refresh and manual-sync claim seam; `null` when not wired.
 * @param cleanupCoordinator Cross-service cleanup chain run on delete/block/unblock; `null` when not wired.
 * @param preferences Stand-in for `UserDefaults.standard` (favorites migration flag).
 * @param clock Wall clock for `lastModified` stamps written by [resetPath] and [setPath].
 */
@Suppress("TooManyFunctions")
class ContactService(
    private val session: ContactSessionOps,
    private val devices: DevicePersisting,
    private val contacts: ContactPersisting,
    private val syncCoordinator: ContactSyncCoordinating?,
    private val cleanupCoordinator: ContactCleanupHandling?,
    private val preferences: ContactPreferenceFlags,
    private val clock: Clock = Clock.systemUTC(),
    private val logger: ContactServiceLogger = ContactServiceLogger.NONE,
) : ContactServiceProtocol {
    /** Multicast broadcaster for sync progress and node-deletion events. */
    private val eventBroadcaster = ContactEventBroadcaster()

    // region Events

    /**
     * Returns a fresh subscription to contact service events. Registration is synchronous, so
     * events yielded after this call returns are never dropped.
     */
    fun events(): ContactEventSubscription = eventBroadcaster.subscribe()

    /** Ends every `events()` subscriber's collection; called by the container on teardown. */
    fun finishEvents() = eventBroadcaster.finish()

    // endregion

    // region Configuration

    /** Whether a sync coordinator was injected at construction. */
    internal val hasSyncCoordinatorWired: Boolean get() = syncCoordinator != null

    /** Whether a cleanup coordinator was injected at construction. */
    internal val hasCleanupCoordinatorWired: Boolean get() = cleanupCoordinator != null

    // endregion

    // region Contact Sync

    /** Full sync (`since == nil`). */
    suspend fun syncContacts(radioId: RadioId): ContactSyncResult = syncContacts(radioId, null)

    /**
     * Sync contacts from the device.
     * @param since Optional date for incremental sync (only contacts modified after this time);
     *   `null` is a full sync that may prune local contacts the device no longer has.
     * Marked write recovery requires a guarded reconciliation contract; an ordinary fetch cannot
     * authorize persistence, pruning or marker clearing for that state.
     */
    override suspend fun syncContacts(radioId: RadioId, since: Instant?): ContactSyncResult =
        ContactWriteRecovery.withSyncFence(preferences, radioId) {
            mapSession {
                if (ContactWriteRecovery.snapshot(preferences, radioId) != null) {
                    throw ContactServiceError.SyncInterrupted()
                }
                val fetchResult = session.getContactsReportingTotal(since)
                val meshContacts = fetchResult.contacts
                val total = meshContacts.size.toLong()

                eventBroadcaster.yield(ContactServiceEvent.SyncProgress(received = 0, total = total))

                // Build set of public keys from device for cleanup
                val devicePublicKeys = meshContacts.mapTo(LinkedHashSet()) { it.publicKey }

                // Persist every received contact in a single transaction.
                val frames = meshContacts.map { it.toContactFrame() }.snapshot()
                val receivedCount = contacts.batchSaveContacts(radioId, frames)

                val lastTimestamp = meshContacts.maxOfOrNull { it.lastModified.toUInt32Seconds() } ?: 0u

                eventBroadcaster.yield(ContactServiceEvent.SyncProgress(received = receivedCount, total = total))

                // On full sync, remove local contacts that no longer exist on device.
                if (since == null) {
                    pruneOrphans(radioId, devicePublicKeys, fetchResult.reportedTotal)
                }
                ContactSyncResult(
                    contactsReceived = receivedCount,
                    lastSyncTimestamp = lastTimestamp,
                    isIncremental = since != null,
                )
            }
        }

    /**
     * Removes local contacts that a full fetch proves are gone from the device.
     *
     * Prunes only on a complete snapshot: the received key count must meet the `contactsStart`
     * total. A `null` [reportedTotal] means no header, so completeness is unprovable and the prune
     * skips. Without a usable self public key the V-contact cannot be identified, so it skips too.
     */
    private suspend fun pruneOrphans(radioId: RadioId, devicePublicKeys: Set<Bytes>, reportedTotal: Long?): Boolean {
        if (reportedTotal == null) {
            logger.log(
                ContactLogLevel.NOTICE,
                "Full sync prune skipped: device sent no contact total (missing contactsStart header)",
            )
            return false
        }
        if (devicePublicKeys.size < reportedTotal) {
            logger.log(
                ContactLogLevel.NOTICE,
                "Full sync prune skipped: received ${devicePublicKeys.size} of $reportedTotal reported device contacts (incomplete snapshot)",
            )
            return false
        }
        val selfPublicKey = contactTryOptional { devices.fetchDevice(radioId) }?.publicKey
        if (selfPublicKey == null || selfPublicKey.size != ProtocolLimits.PUBLIC_KEY_SIZE) {
            logger.log(
                ContactLogLevel.NOTICE,
                "Full sync prune skipped: self public key unavailable or invalid, cannot exclude the V-contact",
            )
            return false
        }

        val localContacts = contacts.fetchContacts(radioId)
        val orphans = localContacts.filter { contact ->
            contact.publicKey !in devicePublicKeys &&
                !VContactIdentity.isVContact(contact.publicKey, selfPublicKey)
        }
        if (orphans.isNotEmpty()) {
            logger.log(
                ContactLogLevel.NOTICE,
                "Full sync prune: ${orphans.size} local contact(s) not found on device (device has ${devicePublicKeys.size}, local has ${localContacts.size})",
            )
        }
        for (localContact in orphans) {
            val keyPrefix = localContact.publicKey.prefix(4).hexString
            logger.log(
                ContactLogLevel.NOTICE,
                "Full sync prune: deleting '${localContact.name}' [$keyPrefix…] (favorite=${localContact.isFavorite}, type=${localContact.typeRawValue}, lastModified=${localContact.lastModified})",
            )
            val key = EntityKey(localContact.radioId, localContact.id)
            contacts.deleteContact(key)
            cleanupCoordinator?.handleCleanup(key, ContactCleanupReason.DELETED, localContact.publicKey)
        }
        return true
    }

    /**
     * Full contact sync for a user-initiated refresh.
     *
     * Atomically waits out an advert-driven delta sync and claims the manual refresh flag on the
     * coordinator, so an advert delta cannot interleave its `SyncProgress` events with this one.
     * Callers still call `AdvertisementService.setSyncingContacts(true)` first so no new delta
     * starts before this claim runs.
     */
    suspend fun syncContactsForRefresh(radioId: RadioId): ContactSyncResult {
        try {
            syncCoordinator?.claimManualContactSync()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timedOut: Exception) {
            // Wait timed out while an advert delta still held the claim. Do not proceed (races
            // progress) and do not silent-skip: surface so the spinner stops.
            throw ContactServiceError.SyncInterrupted()
        }
        try {
            return syncContacts(radioId)
        } finally {
            // Cleared on success and failure alike. Swift's `await` on this actor hop is not a
            // cancellation point, so the clear must not be skippable by cancellation here either.
            withContext(NonCancellable) { syncCoordinator?.setManualContactSyncActive(false) }
        }
    }

    // endregion

    // region Get / Add / Remove

    /** Get a specific contact by public key from the local database. */
    suspend fun getContact(radioId: RadioId, publicKey: Bytes): ContactDTO? =
        contacts.fetchContact(radioId, publicKey)

    /**
     * The sent exchange owns accepted-write persistence even if this caller retires. Cancellation
     * still propagates to the UI; a potentially written or incompletely accounted write requires
     * a full sync of this radio, never a claimed rollback.
     */
    suspend fun addOrUpdateContact(radioId: RadioId, contact: ContactFrame) {
        val previousFrame = contacts.fetchContact(radioId, contact.publicKey)?.toContactFrame()
        writeContact(radioId, contact, previousFrame)
    }

    /** A link import must not replace a contact that another accepted write already persisted. */
    suspend fun addContactIfAbsent(radioId: RadioId, contact: ContactFrame) {
        writeContact(radioId, contact, previousFrame = null) {
            val existing = contacts.fetchContact(radioId, contact.publicKey)
            check(existing == null || existing.radioId == radioId && existing.publicKey == contact.publicKey) {
                "Contact lookup returned a mismatched identity"
            }
            existing == null
        }
    }

    private suspend fun writeContact(
        radioId: RadioId,
        contact: ContactFrame,
        previousFrame: ContactFrame?,
        shouldDispatch: (suspend () -> Boolean)? = null,
    ) {
        val dispatch = ContactWriteDispatch(
            beforeDispatchAction = { handle -> ContactWriteRecovery.beforeDispatch(preferences, radioId, handle) },
            onOutcomeChangedAction = { handle ->
                ContactWriteRecovery.outcomeChanged(preferences, radioId, handle)
            },
            onExchangeFinishedAction = { handle ->
                ContactWriteRecovery.finishWrite(preferences, radioId, handle) { failure ->
                    requireContactReconciliation(radioId, failure, handle)
                }
            },
        )
        ContactWriteRecovery.registerWrite(preferences, radioId, dispatch)
        try {
            val onCommitted: suspend () -> Unit = {
                withContext(NonCancellable) {
                    try {
                        contacts.saveContactPreservingNewer(radioId, contact, previousFrame)
                            ?: throw ContactWriteCompletionFailed(radioId, contact.publicKey)
                        syncCoordinator?.notifyContactsChanged()
                        ContactWriteRecovery.completeWrite(preferences, radioId, dispatch)
                    } catch (cancelled: CancellationException) {
                        requireContactReconciliation(radioId, cancelled, dispatch)
                        throw cancelled
                    } catch (error: ContactWriteCompletionFailed) {
                        requireContactReconciliation(radioId, error, dispatch)
                        throw error
                    } catch (error: Exception) {
                        requireContactReconciliation(radioId, error, dispatch)
                        throw ContactWriteCompletionFailed(radioId, contact.publicKey, error)
                    }
                }
            }
            if (shouldDispatch == null) {
                session.addContact(contact.toMeshContact(), dispatch, onCommitted)
            } else {
                session.addContactIf(contact.toMeshContact(), dispatch, shouldDispatch, onCommitted)
            }
        } catch (cancelled: CancellationException) {
            when (dispatch.outcome) {
                ContactWriteDispatchOutcome.DEFINITELY_UNSENT ->
                    ContactWriteRecovery.abandonDefinitelyUnsent(preferences, radioId, dispatch)
                ContactWriteDispatchOutcome.ACCEPTED -> {
                    if (ContactWriteRecovery.isWriteTracked(preferences, radioId, dispatch)) {
                        requireContactReconciliation(radioId, cancelled, dispatch)
                    }
                }
                else -> requireContactReconciliation(radioId, cancelled, dispatch)
            }
            throw cancelled
        } catch (blocked: ContactWriteAdmissionBlocked) {
            ContactWriteRecovery.abandonWrite(preferences, radioId, dispatch)
            throw blocked
        } catch (error: MeshCoreException) {
            if (dispatch.definitelyUnsent) {
                ContactWriteRecovery.abandonDefinitelyUnsent(preferences, radioId, dispatch)
            } else if (error !is MeshCoreException.DeviceError && error !is MeshCoreException.InvalidInput) {
                requireContactReconciliation(radioId, error, dispatch)
            } else {
                ContactWriteRecovery.abandonWrite(preferences, radioId, dispatch)
            }
            if (error.isDeviceError(ErrorCode.TABLE_FULL)) throw ContactServiceError.ContactTableFull()
            throw ContactServiceError.SessionError(error)
        } catch (error: ContactWriteCompletionFailed) {
            throw error
        } catch (error: Exception) {
            requireContactReconciliation(radioId, error, dispatch)
            throw error
        }
    }

    /** Survives replacement of this service through the injected process-persistent preferences. */
    fun needsContactWriteReconciliation(radioId: RadioId): Boolean =
        ContactWriteRecovery.snapshot(preferences, radioId) != null

    /**
     * Read-only presentation state. Does not retry marker writes, change recovery epochs, fetch,
     * clear uncertainty, or authorize a new contact write. Preference read failures propagate.
     */
    fun contactWriteRecoveryState(radioId: RadioId): ContactWriteRecoveryState =
        ContactWriteRecovery.observe(preferences, radioId)

    /**
     * Emits an atomic initial snapshot followed by effective changes for this process preference
     * role and radio. Collection does not retry marker writes or access the network.
     */
    fun observeContactWriteRecoveryState(radioId: RadioId): Flow<ContactWriteRecoveryState> =
        ContactWriteRecovery.updates(preferences, radioId)

    private fun requireContactReconciliation(
        radioId: RadioId,
        failure: Exception,
        dispatch: ContactWriteDispatch? = null,
    ) {
        try {
            if (dispatch?.definitelyUnsent == true) {
                ContactWriteRecovery.abandonDefinitelyUnsent(preferences, radioId, dispatch)
                if (!ContactWriteRecovery.retryExistingRecovery(preferences, radioId)) return
            } else {
                ContactWriteRecovery.require(preferences, radioId, dispatch)
            }
        } catch (recordingFailure: Exception) {
            if (recordingFailure !== failure) failure.addSuppressed(recordingFailure)
            logger.log(ContactLogLevel.WARNING, "Contact recovery marker could not be persisted")
        }
        logger.log(ContactLogLevel.WARNING, "Contact state requires a complete radio contact sync")
    }

    /**
     * Remove a contact from the device.
     *
     * The per-radio mutation fence spans the device command and local cleanup so a contact snapshot
     * cannot apply between the two sides. Cancellation while waiting for the fence sends no command;
     * an uncertain command result or a local failure after acknowledgement requires reconciliation.
     *
     * ZephCore V-contact remove is disabled: no `CMD_REMOVE` (which would turn `v.contact` off), no
     * local wipe, and no `NodeDeleted` storage-full clear.
     * Identity lookup failures propagate; missing or invalid keys interrupt before any mutation.
     */
    suspend fun removeContact(radioId: RadioId, publicKey: Bytes) {
        if (isProtectedVContact(radioId, publicKey)) {
            logger.log(ContactLogLevel.INFO, "removeContact ignored for ZephCore V-contact (remove disabled)")
            return
        }

        ContactWriteRecovery.withContactMutationFence(
            preferences,
            radioId,
            shouldMutate = {
                if (isProtectedVContact(radioId, publicKey)) {
                    logger.log(ContactLogLevel.INFO, "removeContact ignored for ZephCore V-contact (remove disabled)")
                    false
                } else true
            },
        ) {
            val dispatch = ChannelWriteDispatch()
            try {
                session.removeContact(publicKey, dispatch)
            } catch (cancelled: CancellationException) {
                if (dispatch.cancellationDisposition != ChannelWriteCancellationDisposition.DEFINITELY_UNSENT) {
                    requireContactReconciliation(radioId, cancelled)
                }
                throw cancelled
            } catch (error: MeshCoreException) {
                if (dispatch.cancellationDisposition != ChannelWriteCancellationDisposition.DEFINITELY_UNSENT &&
                    error !is MeshCoreException.DeviceError && error !is MeshCoreException.InvalidInput) {
                    requireContactReconciliation(radioId, error)
                }
                if (error.isDeviceError(ErrorCode.NOT_FOUND)) throw ContactServiceError.ContactNotFound()
                throw ContactServiceError.SessionError(error)
            } catch (error: Exception) {
                if (dispatch.cancellationDisposition != ChannelWriteCancellationDisposition.DEFINITELY_UNSENT) {
                    requireContactReconciliation(radioId, error)
                }
                throw error
            }

            try {
                // Remove from local database only after the radio confirms the command.
                val contact = contacts.fetchContact(radioId, publicKey)
                if (contact != null) {
                    val key = EntityKey(contact.radioId, contact.id)
                    contacts.deleteContact(key)

                    // Trigger cleanup (notifications, badge, session)
                    cleanupCoordinator?.handleCleanup(key, ContactCleanupReason.DELETED, publicKey)
                }

                // Notify that a node was deleted (for clearing storage full flag)
                eventBroadcaster.yield(ContactServiceEvent.NodeDeleted)

                // Notify UI to refresh contacts list
                syncCoordinator?.notifyContactsChanged()
            } catch (cancelled: CancellationException) {
                requireContactReconciliation(radioId, cancelled)
                throw cancelled
            } catch (error: Exception) {
                requireContactReconciliation(radioId, error)
                throw error
            }
        }
    }

    /**
     * Remove a contact's local data and run the full cleanup chain without contacting the device.
     * Use when the device reports the contact doesn't exist but local data remains. The per-radio
     * mutation fence orders this deletion with a fetched contact snapshot.
     * ZephCore V-contact remove is disabled (same policy as [removeContact]).
     * The stored row must exist and match both the scoped ID and supplied key at each admission check.
     */
    suspend fun removeLocalContact(contact: EntityKey, publicKey: Bytes) {
        if (isProtectedLocalContact(contact, publicKey)) {
            logger.log(ContactLogLevel.INFO, "removeLocalContact ignored for ZephCore V-contact (remove disabled)")
            return
        }

        ContactWriteRecovery.withContactMutationFence(
            preferences,
            contact.radioId,
            shouldMutate = {
                if (isProtectedLocalContact(contact, publicKey)) {
                    logger.log(ContactLogLevel.INFO, "removeLocalContact ignored for ZephCore V-contact (remove disabled)")
                    false
                } else true
            },
        ) {
            contacts.deleteContact(contact)
            cleanupCoordinator?.handleCleanup(contact, ContactCleanupReason.DELETED, publicKey)
            eventBroadcaster.yield(ContactServiceEvent.NodeDeleted)
            syncCoordinator?.notifyContactsChanged()
        }
    }

    private suspend fun isProtectedLocalContact(contact: EntityKey, publicKey: Bytes): Boolean {
        val stored = contacts.fetchContact(contact) ?: throw ContactServiceError.ContactNotFound()
        if (stored.id != contact.id || stored.radioId != contact.radioId || stored.publicKey != publicKey) {
            logger.log(ContactLogLevel.WARNING, "Contact removal blocked: stored contact identity changed")
            throw ContactServiceError.SyncInterrupted()
        }
        return isProtectedVContact(stored.radioId, stored.publicKey)
    }

    /** Whether [publicKey] is the ZephCore V-contact for this radio (remove/prune protected). */
    private suspend fun isProtectedVContact(radioId: RadioId, publicKey: Bytes): Boolean {
        val device = devices.fetchDevice(radioId)
        if (device == null || device.radioId != radioId ||
            device.publicKey.size != ProtocolLimits.PUBLIC_KEY_SIZE || publicKey.size != ProtocolLimits.PUBLIC_KEY_SIZE
        ) {
            logger.log(ContactLogLevel.WARNING, "Contact removal blocked: self or contact public key unavailable or invalid")
            throw ContactServiceError.SyncInterrupted()
        }
        return VContactIdentity.isVContact(publicKey, device.publicKey)
    }

    /**
     * Clears all messages for a direct conversation without deleting the contact. Preserves
     * `lastMessageDate` so the now-empty conversation stays listed, clears both unread counters and
     * notifies observers so no stale badge or preview survives.
     */
    suspend fun clearContactMessages(contact: EntityKey) {
        contacts.deleteMessagesForContact(contact)
        contacts.clearUnreadCount(contact)
        contacts.clearUnreadMentionCount(contact)
        syncCoordinator?.notifyConversationsChanged()
    }

    // endregion

    // region Paths

    /** Reset the path for a contact (force rediscovery) and mark it flood-routed locally. */
    suspend fun resetPath(radioId: RadioId, publicKey: Bytes) {
        try {
            session.resetPath(publicKey)

            // Update local contact to show flood routing
            val contact = contacts.fetchContact(radioId, publicKey)
            if (contact != null) {
                contacts.saveContact(radioId, contact.floodedContactFrame(asOf = nowSeconds()))
            }
        } catch (error: MeshCoreException) {
            if (error.isDeviceError(ErrorCode.NOT_FOUND)) throw ContactServiceError.ContactNotFound()
            throw ContactServiceError.SessionError(error)
        }
    }

    /** Send a path discovery request; returns the firmware's sent info with its estimated timeout. */
    @Suppress("UnusedParameter")
    suspend fun sendPathDiscovery(radioId: RadioId, publicKey: Bytes): MessageSentInfo = try {
        session.sendPathDiscovery(publicKey)
    } catch (error: MeshCoreException) {
        if (error.isDeviceError(ErrorCode.NOT_FOUND)) throw ContactServiceError.ContactNotFound()
        throw ContactServiceError.SessionError(error)
    }

    /**
     * Set a specific path for a contact.
     * @param pathLength Encoded path length byte (0xFF for flood, 0 for direct, >0 for routed).
     */
    suspend fun setPath(radioId: RadioId, publicKey: Bytes, path: Bytes, pathLength: UByte) {
        // Get current contact to preserve other fields
        val existing = contacts.fetchContact(radioId, publicKey) ?: throw ContactServiceError.ContactNotFound()

        // The Swift frame is built from `type:` alone, so an unmodeled raw type byte is normalised.
        val updatedFrame = ContactFrame(
            publicKey = existing.publicKey,
            type = existing.type,
            flags = existing.flags,
            outPathLength = pathLength,
            outPath = path,
            name = existing.name,
            lastAdvertTimestamp = existing.lastAdvertTimestamp,
            latitude = existing.latitude,
            longitude = existing.longitude,
            lastModified = nowSeconds(),
        )

        // Send update to device
        addOrUpdateContact(radioId, updatedFrame)
    }

    // endregion

    // region Share / Export / Import

    /** Share a contact via zero-hop broadcast. */
    suspend fun shareContact(publicKey: Bytes) {
        try {
            session.shareContact(publicKey)
        } catch (error: MeshCoreException) {
            if (error.isDeviceError(ErrorCode.TABLE_FULL)) throw ContactServiceError.ShareContactUnavailable()
            if (error.isDeviceError(ErrorCode.NOT_FOUND)) throw ContactServiceError.ContactNotFound()
            throw ContactServiceError.SessionError(error)
        }
    }

    /** Export a contact to a shareable URI (legacy firmware call); `null` exports self. */
    @Deprecated("Use exportContactURI(name, publicKey, type) instead")
    suspend fun exportContact(publicKey: Bytes? = null): String = try {
        session.exportContact(publicKey)
    } catch (error: MeshCoreException) {
        throw ContactServiceError.SessionError(error)
    }

    /** Import a contact from card data. */
    suspend fun importContact(cardData: Bytes) {
        try {
            session.importContact(cardData)
        } catch (error: MeshCoreException) {
            throw ContactServiceError.SessionError(error)
        }
    }

    // endregion

    // region Local Database Operations

    /** Get all contacts for a device from the local database. */
    suspend fun getContacts(radioId: RadioId): SnapshotList<ContactDTO> = contacts.fetchContacts(radioId)

    /** Get conversations (contacts with messages) from the local database. */
    suspend fun getConversations(radioId: RadioId): SnapshotList<ContactDTO> = contacts.fetchConversations(radioId)

    /** Get a contact by its radio-scoped key from the local database. */
    suspend fun getContactByID(contact: EntityKey): ContactDTO? = contacts.fetchContact(contact)

    /**
     * Update local contact preferences (nickname, blocked, favorite).
     * [nickname]: `null` leaves the existing nickname unchanged; an empty or whitespace-only string
     * clears it. A non-empty value is trimmed and stored.
     */
    suspend fun updateContactPreferences(
        contact: EntityKey,
        nickname: String? = null,
        isBlocked: Boolean? = null,
        isFavorite: Boolean? = null,
    ) {
        val existing = contacts.fetchContact(contact) ?: throw ContactServiceError.ContactNotFound()

        val result = applyLocalUpdate(
            contact, existing.publicKey,
            ContactLocalUpdate.Preferences(nickname?.trimSwiftWhitespacesAndNewlines(), isBlocked, isFavorite),
        )

        when (result.blockTransition) {
            ContactBlockTransition.BLOCKED ->
                cleanupCoordinator?.handleCleanup(contact, ContactCleanupReason.BLOCKED, existing.publicKey)
            ContactBlockTransition.UNBLOCKED ->
                cleanupCoordinator?.handleCleanup(contact, ContactCleanupReason.UNBLOCKED, existing.publicKey)
            ContactBlockTransition.NONE -> Unit
        }

        syncCoordinator?.notifyContactsChanged()
    }

    /** Updates OCV settings for a contact. */
    suspend fun updateContactOCVSettings(contact: EntityKey, preset: String, customArray: String?) {
        val existing = contacts.fetchContact(contact) ?: throw ContactServiceError.ContactNotFound()
        applyLocalUpdate(contact, existing.publicKey, ContactLocalUpdate.OCVSettings(preset, customArray))
    }

    /** Updates a contact's locally stored profile picture; `null` removes it. */
    suspend fun updateContactAvatar(contact: EntityKey, imageData: Bytes?) {
        val existing = contacts.fetchContact(contact) ?: throw ContactServiceError.ContactNotFound()
        updateContactAvatar(contact, existing.publicKey, imageData)
    }

    /** Uses the identity captured before an asynchronous chooser without substituting a newer contact. */
    suspend fun updateContactAvatar(contact: EntityKey, expectedPublicKey: Bytes, imageData: Bytes?) {
        when (contacts.updateContactAvatar(contact, expectedPublicKey, imageData)) {
            ContactAvatarUpdateResult.UPDATED -> Unit
            ContactAvatarUpdateResult.CONTACT_NOT_FOUND,
            ContactAvatarUpdateResult.IDENTITY_MISMATCH -> throw ContactServiceError.ContactNotFound()
        }
        syncCoordinator?.notifyContactsChanged()
    }

    // endregion

    // region Device Flags

    /**
     * Sets a contact's favorite status on the device (flags bit 0), waits for confirmation, then
     * updates local storage.
     */
    suspend fun setContactFavorite(contact: EntityKey, isFavorite: Boolean) {
        val existing = contacts.fetchContact(contact) ?: throw ContactServiceError.ContactNotFound()
        val newFlags = existing.flags.withBits(FAVORITE_BIT, isFavorite)
        pushFlags(existing, newFlags)

        // Device confirmed - update local storage
        when (contacts.updateContactFavorite(contact, existing.publicKey, isFavorite)) {
            ContactFavoriteUpdateResult.UPDATED -> Unit
            ContactFavoriteUpdateResult.CONTACT_NOT_FOUND,
            ContactFavoriteUpdateResult.IDENTITY_MISMATCH -> throw ContactServiceError.ContactNotFound()
        }
    }

    /**
     * Sets telemetry permission flags (bits 1-3: base/location/environment) on a contact's device
     * record, preserving bit 0 (favourite).
     */
    suspend fun setTelemetryPermissions(contact: EntityKey, granted: Boolean) {
        val existing = contacts.fetchContact(contact) ?: throw ContactServiceError.ContactNotFound()
        val newFlags = existing.flags.withBits(TELEMETRY_BITS, granted)
        pushFlags(existing, newFlags)

        // Device confirmed - update local storage
        applyLocalUpdate(contact, existing.publicKey, ContactLocalUpdate.TelemetryPermissions(granted))
    }

    private suspend fun applyLocalUpdate(
        contact: EntityKey, expectedPublicKey: Bytes, update: ContactLocalUpdate,
    ): ContactLocalUpdateResult.Updated =
        when (val result = contacts.updateContactLocalFields(contact, expectedPublicKey, update)) {
            is ContactLocalUpdateResult.Updated -> result
            ContactLocalUpdateResult.ContactNotFound,
            ContactLocalUpdateResult.IdentityMismatch -> throw ContactServiceError.ContactNotFound()
        }

    /** Push [newFlags] for [existing] to the device and wait for confirmation. */
    private suspend fun pushFlags(existing: ContactDTO, newFlags: UByte) {
        // Built from `type:` alone, as in Swift, so typeRawValue is the modeled type's raw value.
        val meshContact = MeshContact(
            id = existing.publicKey.uppercaseHexString(),
            publicKey = existing.publicKey,
            type = ContactType.fromRawValue(existing.typeRawValue) ?: ContactType.CHAT,
            flags = ContactFlags(existing.flags),
            outPathLength = existing.outPathLength,
            outPath = existing.outPath,
            advertisedName = existing.name,
            lastAdvertisement = Instant.ofEpochSecond(existing.lastAdvertTimestamp.toLong()),
            latitude = existing.latitude,
            longitude = existing.longitude,
            lastModified = Instant.ofEpochSecond(existing.lastModified.toLong()),
        )
        try {
            session.changeContactFlags(meshContact, ContactFlags(newFlags))
        } catch (error: MeshCoreException) {
            throw ContactServiceError.SessionError(error)
        }
    }

    // endregion

    // region Favorites Migration

    /**
     * Migrates existing app favorites to device flags (one-time operation). Any contact that is a
     * favorite in the app but not on the device is pushed; the completion flag is set only when
     * every push succeeds, so a partial run retries on the next connect.
     * @return Number of contacts migrated to the device.
     */
    suspend fun migrateAppFavoritesToDevice(radioId: RadioId): Long {
        // Check if already migrated
        if (preferences.bool(FAVORITES_MIGRATION_KEY)) return 0

        logger.log(ContactLogLevel.INFO, "Starting favorites migration to device")

        // Find contacts that are favorite in app but not on device
        val toMigrate = contacts.fetchContacts(radioId).filter { it.isFavorite && (it.flags and FAVORITE_BIT) == ZERO }

        if (toMigrate.isEmpty()) {
            logger.log(ContactLogLevel.INFO, "No favorites to migrate, marking complete")
            preferences.set(FAVORITES_MIGRATION_KEY, true)
            return 0
        }

        logger.log(ContactLogLevel.INFO, "Migrating ${toMigrate.size} favorites to device")

        var migratedCount = 0L
        for (contact in toMigrate) {
            try {
                setContactFavorite(EntityKey(contact.radioId, contact.id), isFavorite = true)
                migratedCount += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Continue with other contacts, will retry on next connect
                logger.log(ContactLogLevel.WARNING, "Failed to migrate favorite for ${contact.name}: $failure")
            }
        }

        // Only mark complete if all were migrated
        if (migratedCount == toMigrate.size.toLong()) {
            preferences.set(FAVORITES_MIGRATION_KEY, true)
            logger.log(ContactLogLevel.INFO, "Favorites migration complete: $migratedCount contacts")
        } else {
            logger.log(ContactLogLevel.WARNING, "Partial migration: $migratedCount/${toMigrate.size}, will retry")
        }
        return migratedCount
    }

    // endregion

    private fun nowSeconds(): UInt = clock.instant().toUInt32Seconds()

    /** Maps a session failure inside [block] to [ContactServiceError.SessionError]. */
    private inline fun <T> mapSession(block: () -> T): T = try {
        block()
    } catch (error: MeshCoreException) {
        throw ContactServiceError.SessionError(error)
    }

    companion object {
        /** UserDefaults key recording the one-time favorites migration. */
        const val FAVORITES_MIGRATION_KEY: String = "hasMigratedContactFavorites"

        private val ZERO: UByte = 0u
        private val FAVORITE_BIT: UByte = 0x01u
        private val TELEMETRY_BITS: UByte = 0x0Eu
        private const val NAME_KEY = "name"
        private const val PUBLIC_KEY_KEY = "public_key"
        private const val TYPE_KEY = "type"

        /** Whether a contact has telemetry permission flags set (bits 1-3). */
        fun hasTelemetryPermissions(flags: UByte): Boolean = (flags and TELEMETRY_BITS) != ZERO

        /**
         * Build a shareable contact URI:
         * `meshcore://contact/add?name=...&public_key=...&type=...`. The name is query-item encoded
         * so `&`/`=` in it cannot inject `public_key`/`type`.
         */
        fun exportContactURI(name: String, publicKey: Bytes, type: ContactType): String = ContactUriBuilder.build(
            listOf(
                NAME_KEY to name,
                PUBLIC_KEY_KEY to publicKey.uppercaseHexString(),
                TYPE_KEY to type.rawValue.toString(),
            ),
        )
    }
}

private fun MeshCoreException.isDeviceError(code: ErrorCode): Boolean =
    this is MeshCoreException.DeviceError && this.code == code.rawValue

private fun UByte.withBits(mask: UByte, set: Boolean): UByte = if (set) this or mask else this and mask.inv()

/**
 * Foundation `trimmingCharacters(in: .whitespacesAndNewlines)`: Unicode `Zs`, `Zl`, `Zp`, TAB,
 * U+000A-U+000D and U+0085. Unlike Kotlin `trim()`, U+001C-U+001F are not trimmed.
 */
private fun String.trimSwiftWhitespacesAndNewlines(): String = trim { character ->
    character == '\t' || character in '\n'..'\r' || character == '\u0085' ||
        Character.getType(character).toByte().let {
            it == Character.SPACE_SEPARATOR || it == Character.LINE_SEPARATOR || it == Character.PARAGRAPH_SEPARATOR
        }
}
