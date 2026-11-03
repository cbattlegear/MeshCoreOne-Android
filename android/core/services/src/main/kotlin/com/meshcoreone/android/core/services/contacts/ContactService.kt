// PortedFrom: MC1Services/Sources/MC1Services/Services/ContactService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ContactPersisting
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
import com.meshcoreone.android.core.protocol.session.ContactSessionOps
import com.meshcoreone.android.core.protocol.session.ContactWriteDispatch
import com.meshcoreone.android.core.protocol.session.ContactWriteDispatchOutcome
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
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
     */
    override suspend fun syncContacts(radioId: RadioId, since: Instant?): ContactSyncResult =
        ContactWriteRecovery.withSyncFence(preferences, radioId) {
            mapSession {
                val recoveryEpoch = ContactWriteRecovery.snapshot(preferences, radioId)
                val effectiveSince = if (recoveryEpoch != null) null else since
                val fetchResult = session.getContactsReportingTotal(effectiveSince)
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
                val fullSnapshotApplied = if (effectiveSince == null) {
                    pruneOrphans(radioId, devicePublicKeys, fetchResult.reportedTotal)
                } else false

                if (recoveryEpoch != null) {
                    if (!fullSnapshotApplied || devicePublicKeys.size.toLong() != fetchResult.reportedTotal) {
                        throw ContactServiceError.SyncInterrupted()
                    }
                    ContactWriteRecovery.reconciled(preferences, radioId, recoveryEpoch)
                }
                ContactSyncResult(
                    contactsReceived = receivedCount,
                    lastSyncTimestamp = lastTimestamp,
                    isIncremental = effectiveSince != null,
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
        val dispatch = ContactWriteDispatch(
            beforeDispatchAction = { handle -> ContactWriteRecovery.beforeDispatch(preferences, radioId, handle) },
            onExchangeFinishedAction = { handle ->
                ContactWriteRecovery.finishWrite(preferences, radioId, handle) { failure ->
                    requireContactWriteReconciliation(radioId, failure, handle)
                }
            },
        )
        ContactWriteRecovery.registerWrite(preferences, radioId, dispatch)
        try {
            session.addContact(contact.toMeshContact(), dispatch) {
                withContext(NonCancellable) {
                    try {
                        contacts.saveContactPreservingNewer(radioId, contact, previousFrame)
                            ?: throw ContactWriteCompletionFailed(radioId, contact.publicKey)
                        syncCoordinator?.notifyContactsChanged()
                        ContactWriteRecovery.completeWrite(preferences, radioId, dispatch)
                    } catch (cancelled: CancellationException) {
                        requireContactWriteReconciliation(radioId, cancelled, dispatch)
                        throw cancelled
                    } catch (error: ContactWriteCompletionFailed) {
                        requireContactWriteReconciliation(radioId, error, dispatch)
                        throw error
                    } catch (error: Exception) {
                        requireContactWriteReconciliation(radioId, error, dispatch)
                        throw ContactWriteCompletionFailed(radioId, contact.publicKey, error)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            when (dispatch.outcome) {
                ContactWriteDispatchOutcome.DEFINITELY_UNSENT ->
                    ContactWriteRecovery.abandonDefinitelyUnsent(preferences, radioId, dispatch)
                ContactWriteDispatchOutcome.ACCEPTED -> {
                    if (ContactWriteRecovery.isWriteTracked(preferences, radioId, dispatch)) {
                        requireContactWriteReconciliation(radioId, cancelled, dispatch)
                    }
                }
                else -> requireContactWriteReconciliation(radioId, cancelled, dispatch)
            }
            throw cancelled
        } catch (blocked: ContactWriteAdmissionBlocked) {
            ContactWriteRecovery.abandonWrite(preferences, radioId, dispatch)
            throw blocked
        } catch (error: MeshCoreException) {
            if (error !is MeshCoreException.DeviceError && error !is MeshCoreException.InvalidInput) {
                requireContactWriteReconciliation(radioId, error, dispatch)
            } else {
                ContactWriteRecovery.abandonWrite(preferences, radioId, dispatch)
            }
            if (error.isDeviceError(ErrorCode.TABLE_FULL)) throw ContactServiceError.ContactTableFull()
            throw ContactServiceError.SessionError(error)
        } catch (error: ContactWriteCompletionFailed) {
            throw error
        } catch (error: Exception) {
            requireContactWriteReconciliation(radioId, error, dispatch)
            throw error
        }
    }

    /** Survives replacement of this service through the injected process-persistent preferences. */
    fun needsContactWriteReconciliation(radioId: RadioId): Boolean =
        ContactWriteRecovery.snapshot(preferences, radioId) != null

    private fun requireContactWriteReconciliation(
        radioId: RadioId,
        failure: Exception,
        dispatch: ContactWriteDispatch? = null,
    ) {
        try {
            ContactWriteRecovery.require(preferences, radioId, dispatch)
        } catch (recordingFailure: Exception) {
            failure.addSuppressed(recordingFailure)
            logger.log(ContactLogLevel.WARNING, "Contact write recovery marker could not be persisted")
        }
        logger.log(ContactLogLevel.WARNING, "Contact write requires a complete radio contact sync")
    }

    /**
     * Remove a contact from the device.
     *
     * ZephCore V-contact remove is disabled: no `CMD_REMOVE` (which would turn `v.contact` off), no
     * local wipe, and no `NodeDeleted` storage-full clear.
     */
    suspend fun removeContact(radioId: RadioId, publicKey: Bytes) {
        if (isProtectedVContact(radioId, publicKey)) {
            logger.log(ContactLogLevel.INFO, "removeContact ignored for ZephCore V-contact (remove disabled)")
            return
        }

        try {
            session.removeContact(publicKey)

            // Remove from local database
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
        } catch (error: MeshCoreException) {
            if (error.isDeviceError(ErrorCode.NOT_FOUND)) throw ContactServiceError.ContactNotFound()
            throw ContactServiceError.SessionError(error)
        }
    }

    /**
     * Remove a contact's local data and run the full cleanup chain without contacting the device.
     * Use when the device reports the contact doesn't exist but local data remains.
     * ZephCore V-contact remove is disabled (same policy as [removeContact]).
     */
    suspend fun removeLocalContact(contact: EntityKey, publicKey: Bytes) {
        val stored = contactTryOptional { contacts.fetchContact(contact) }
        if (stored != null && isProtectedVContact(stored.radioId, publicKey)) {
            logger.log(ContactLogLevel.INFO, "removeLocalContact ignored for ZephCore V-contact (remove disabled)")
            return
        }

        contacts.deleteContact(contact)
        cleanupCoordinator?.handleCleanup(contact, ContactCleanupReason.DELETED, publicKey)
        eventBroadcaster.yield(ContactServiceEvent.NodeDeleted)
        syncCoordinator?.notifyContactsChanged()
    }

    /** Whether [publicKey] is the ZephCore V-contact for this radio (remove/prune protected). */
    private suspend fun isProtectedVContact(radioId: RadioId, publicKey: Bytes): Boolean {
        val selfPublicKey = contactTryOptional { devices.fetchDevice(radioId) }?.publicKey ?: return false
        return VContactIdentity.isVContact(publicKey, selfPublicKey)
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

        // null => leave unchanged; empty/whitespace => clear; otherwise trim and set.
        val resolvedNickname = if (nickname != null) {
            nickname.trimSwiftWhitespacesAndNewlines().ifEmpty { null }
        } else {
            existing.nickname
        }

        // Check blocking state transitions
        val isBeingBlocked = isBlocked == true && !existing.isBlocked
        val isBeingUnblocked = isBlocked == false && existing.isBlocked

        contacts.saveContact(
            existing.rebuiltThroughContactModel().copy(
                nickname = resolvedNickname,
                isBlocked = isBlocked ?: existing.isBlocked,
                isFavorite = isFavorite ?: existing.isFavorite,
                unreadCount = if (isBeingBlocked) 0 else existing.unreadCount,
            ),
        )

        // Trigger cleanup for blocking state changes
        if (isBeingBlocked) {
            cleanupCoordinator?.handleCleanup(contact, ContactCleanupReason.BLOCKED, existing.publicKey)
        } else if (isBeingUnblocked) {
            cleanupCoordinator?.handleCleanup(contact, ContactCleanupReason.UNBLOCKED, existing.publicKey)
        }

        syncCoordinator?.notifyContactsChanged()
    }

    /** Updates OCV settings for a contact. */
    suspend fun updateContactOCVSettings(contact: EntityKey, preset: String, customArray: String?) {
        val existing = contacts.fetchContact(contact) ?: throw ContactServiceError.ContactNotFound()
        contacts.saveContact(
            existing.rebuiltThroughContactModel().copy(ocvPreset = preset, customOCVArrayString = customArray),
        )
    }

    /** Updates a contact's locally stored profile picture; `null` removes it. */
    suspend fun updateContactAvatar(contact: EntityKey, imageData: Bytes?) {
        val existing = contacts.fetchContact(contact) ?: throw ContactServiceError.ContactNotFound()
        contacts.saveContact(existing.withAvatar(imageData))
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
        contacts.saveContact(existing.rebuiltThroughContactModel().copy(flags = newFlags, isFavorite = isFavorite))
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
        contacts.saveContact(existing.rebuiltThroughContactModel().copy(flags = newFlags))
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
 * Swift rebuilds these DTOs through the SwiftData `Contact` model, whose `lastHeardTimestamp` is a
 * non-optional `UInt32`: a `nil` value is persisted back as `0`.
 */
private fun ContactDTO.rebuiltThroughContactModel(): ContactDTO = copy(lastHeardTimestamp = lastHeardTimestamp ?: 0u)

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
