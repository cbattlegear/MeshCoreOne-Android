// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/DevicePersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/ContactPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/ChannelPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Devices.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Contacts.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Channels.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.session.ChannelWriteCancellationDisposition
import com.meshcoreone.android.core.protocol.session.ChannelWriteDispatch
import com.meshcoreone.android.core.protocol.session.RadioWritePermit
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A process-owned optimistic write ticket; device writers invalidate it inside their transaction. */
data class DeviceWriteSnapshot(
    val device: DeviceDTO,
    val revision: Long,
    val authorityRevision: Long = revision,
    val isCurrent: () -> Boolean = { true },
    val withAuthority: suspend (suspend () -> Boolean) -> Boolean = { it() },
    /** Post-commit, under [withAuthority]; must not suspend or re-enter persistence. */
    val onCommitted: (DeviceDTO) -> Unit = {},
    /** Actual post-commit revision receipt; invoked before [onCommitted], even if that callback throws. */
    val onCommittedSnapshot: (DeviceWriteSnapshot) -> Unit = {},
)

/** One preparation/SET attempt; only a newly created, definitely unissued snapshot can be aborted. */
class PreRepeatSettingsAttempt(val token: SessionToken?) {
    private var preparationClaimed = false
    private var commitRecorded = false
    private var created: DeviceWriteSnapshot? = null
    private var dispatchEntered = false
    private var radioWriteCompleted = false
    private var abortClaimed = false
    private val dispatch = ChannelWriteDispatch()

    @Synchronized fun claimPreparation() {
        check(!preparationClaimed) { "A Repeat preparation is single-use" }
        preparationClaimed = true
    }

    @Synchronized fun recordCommitted(snapshot: DeviceWriteSnapshot, created: Boolean) {
        check(preparationClaimed && !commitRecorded && !abortClaimed)
        check(snapshot.device.radioId == token?.radioId)
        commitRecorded = true
        if (created) this.created = snapshot
    }

    @Synchronized fun requireCommitted() {
        check(commitRecorded) { "Repeat preparation requires a post-commit revision receipt" }
    }

    @Synchronized fun startRadioDispatch(): ChannelWriteDispatch {
        check(commitRecorded && !abortClaimed && !dispatchEntered)
        dispatchEntered = true
        return dispatch
    }

    @Synchronized fun takeAbortSnapshot(): DeviceWriteSnapshot? {
        if (abortClaimed) return null
        abortClaimed = true
        if (dispatchEntered &&
            dispatch.cancellationDisposition != ChannelWriteCancellationDisposition.DEFINITELY_UNSENT
        ) return null
        return created
    }

    @Synchronized fun recordRadioWriteCompleted() {
        check(dispatchEntered)
        radioWriteCompleted = true
    }

    @Synchronized fun restorationMayHaveBeenUsed(): Boolean =
        radioWriteCompleted || (abortClaimed && dispatchEntered &&
            dispatch.cancellationDisposition == ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN)
}

class RepeatSnapshotReconciliationRequired :
    IllegalStateException("A cancelled Repeat preparation requires an explicit snapshot clear before reuse")

class RepeatSnapshotClearRequired :
    IllegalStateException("A pending Repeat snapshot clear must be persisted before reuse")

fun interface RepeatSnapshotRestoreAuthority {
    /** Release after the transport send settles, not after its protocol response arrives. */
    suspend fun withWrite(write: suspend (permit: RadioWritePermit) -> Unit)
    /** Confirmed RF and Repeat-off readback, before any persistence/publication suspension. */
    fun onRestoreVerified() = Unit
}

/** Process-store custody shared by all editors and delegating persistence wrappers; not durable across restart. */
class RepeatSnapshotAuthority {
    private data class Identity(val id: UUID, val radioId: RadioId, val publicKey: Bytes)
    private data class Pending(
        val receipt: DeviceWriteSnapshot, val attempt: PreRepeatSettingsAttempt,
        val identityVersion: Long, val snapshotVersion: Long,
        var abortRequested: Boolean = false,
    )
    private data class ClearRequest(val device: DeviceDTO, val token: SessionToken, val identityVersion: Long, val snapshotVersion: Long)
    private data class CommittedClear(val identityVersion: Long, val snapshotVersion: Long)
    private val pending = mutableMapOf<Identity, Pending>()
    private val clearRequests = mutableMapOf<Identity, ClearRequest>()
    private val committedClears = mutableMapOf<Identity, CommittedClear>()
    private val admissions = mutableMapOf<UUID, Mutex>()
    private val identityVersions = mutableMapOf<UUID, Long>()
    private val snapshotVersions = mutableMapOf<UUID, Long>()
    private fun DeviceDTO.identity() = Identity(id, radioId, publicKey)

    private fun DeviceDTO.sameSnapshot(other: DeviceDTO): Boolean =
        preRepeatFrequency == other.preRepeatFrequency && preRepeatBandwidth == other.preRepeatBandwidth &&
            preRepeatSpreadingFactor == other.preRepeatSpreadingFactor && preRepeatCodingRate == other.preRepeatCodingRate

    @Synchronized fun recordRestoreVerified(device: DeviceDTO, token: SessionToken, identityVersion: Long, snapshotVersion: Long) {
        if (device.sameSnapshot(device.clearingPreRepeatSettings())) return
        val cleared = committedClears[device.identity()]
        if (cleared != null && cleared.identityVersion == identityVersion &&
            (identityVersions[device.id] ?: 0L) == identityVersion &&
            cleared.snapshotVersion > snapshotVersion &&
            (snapshotVersions[device.id] ?: 0L) == cleared.snapshotVersion
        ) return
        clearRequests.putIfAbsent(device.identity(), ClearRequest(
            device, token, identityVersion, snapshotVersion,
        ))
    }

    @Synchronized fun snapshotVersion(deviceId: UUID): Long = snapshotVersions[deviceId] ?: 0L

    @Synchronized fun hasClearRequest(device: DeviceDTO): Boolean = clearRequests.containsKey(device.identity())

    @Synchronized fun requireNoClearRequest(device: DeviceDTO) {
        if (hasClearRequest(device)) throw RepeatSnapshotClearRequired()
    }

    @Synchronized fun reconcilingClearRequest(device: DeviceDTO): DeviceDTO {
        val request = clearRequests[device.identity()] ?: return device
        if (device.clientRepeat || device.identity() != request.device.identity() ||
            device.radioId != request.token.radioId || !device.sameSnapshot(request.device) ||
            identityVersion(device.id) != request.identityVersion ||
            (snapshotVersions[device.id] ?: 0L) != request.snapshotVersion
        ) throw RepeatSnapshotClearRequired()
        return device.clearingPreRepeatSettings()
    }

    suspend fun <T> withAdmission(deviceId: UUID, action: suspend () -> T): T {
        val admission = synchronized(this) { admissions.getOrPut(deviceId) { Mutex() } }
        return admission.withLock { action() }
    }

    suspend fun <T> tryWithAdmission(deviceId: UUID, ifBusy: () -> T, action: suspend () -> T): T {
        val admission = synchronized(this) { admissions.getOrPut(deviceId) { Mutex() } }
        if (!admission.tryLock()) return ifBusy()
        return try { action() } finally { admission.unlock() }
    }

    suspend fun <T> withAdmissions(deviceIds: Collection<UUID>, action: suspend () -> T): T {
        val ordered = deviceIds.distinct().sorted()
        suspend fun acquire(index: Int): T =
            if (index == ordered.size) action()
            else withAdmission(ordered[index]) { acquire(index + 1) }
        return acquire(0)
    }

    suspend fun <T> withDispatchAdmission(deviceId: UUID, action: suspend (permit: RadioWritePermit) -> T): T {
        val admission = synchronized(this) { admissions.getOrPut(deviceId) { Mutex() } }
        admission.lock()
        val permit = RadioWritePermit { admission.unlock() }
        return try { action(permit) } finally { permit.releaseIfUnclaimed() }
    }

    @Synchronized fun observeIdentityChange(before: DeviceDTO, after: DeviceDTO) {
        if (!before.sameSnapshot(after)) {
            snapshotVersions[before.id] = Math.addExact(snapshotVersions[before.id] ?: 0L, 1L)
        }
        if (before.identity() != after.identity()) {
            invalidateIdentity(before.id)
            if (before.id != after.id) invalidateIdentity(after.id)
            pending.remove(before.identity())?.let { pending[after.identity()] = it }
            clearRequests.remove(before.identity())?.let { clearRequests[after.identity()] = it }
        }
    }

    @Synchronized fun identityVersion(deviceId: UUID): Long = identityVersions[deviceId] ?: 0L

    @Synchronized fun invalidateIdentity(deviceId: UUID) {
        identityVersions[deviceId] = Math.addExact(identityVersion(deviceId), 1L)
    }

    @Synchronized fun recordCreated(receipt: DeviceWriteSnapshot, attempt: PreRepeatSettingsAttempt) {
        pending[receipt.device.identity()] = Pending(
            receipt, attempt, identityVersion(receipt.device.id), snapshotVersion(receipt.device.id),
        )
    }

    fun requestUnsentAbort(attempt: PreRepeatSettingsAttempt): DeviceWriteSnapshot? {
        val receipt = attempt.takeAbortSnapshot() ?: return null
        synchronized(this) {
            val created = pending[receipt.device.identity()] ?: throw DeviceWriteConflict()
            if (created.attempt !== attempt || created.receipt !== receipt) throw DeviceWriteConflict()
            created.abortRequested = true
        }
        return receipt
    }

    @Synchronized fun hasUnsentAbortRequest(device: DeviceDTO): Boolean =
        pending[device.identity()]?.abortRequested == true

    @Synchronized fun reconcilingUnsentAbortRequest(device: DeviceDTO, token: SessionToken): DeviceDTO {
        val created = pending[device.identity()] ?: return device
        if (!created.abortRequested) return device
        if (created.attempt.token != token) throw RepeatSnapshotReconciliationRequired()
        return try {
            abortingUnsentSnapshot(device, created.receipt, created.attempt)
        } catch (_: DeviceWriteConflict) {
            throw RepeatSnapshotReconciliationRequired()
        }
    }

    /** Retain the proven disposition until a lifecycle-bound clear actually commits. */
    @Synchronized fun abortingUnsentSnapshot(
        device: DeviceDTO, receipt: DeviceWriteSnapshot, attempt: PreRepeatSettingsAttempt,
    ): DeviceDTO {
        val created = pending[receipt.device.identity()] ?: throw DeviceWriteConflict()
        if (!created.abortRequested || created.attempt !== attempt || created.receipt !== receipt ||
            attempt.token?.radioId != device.radioId || device.identity() != receipt.device.identity() ||
            device.clientRepeat || !device.sameSnapshot(receipt.device) ||
            identityVersion(device.id) != created.identityVersion || snapshotVersion(device.id) != created.snapshotVersion
        ) throw DeviceWriteConflict()
        return device.clearingPreRepeatSettings()
    }

    fun requireUsable(device: DeviceDTO) {
        val identity = device.identity()
        val entry = synchronized(this) { pending[identity] } ?: return
        // Never acquire the attempt monitor while holding the registry monitor.
        if (!entry.attempt.restorationMayHaveBeenUsed()) throw RepeatSnapshotReconciliationRequired()
        synchronized(this) { if (pending[identity] === entry) pending.remove(identity) }
    }

    /** Only an actual committed clear resolves an unissued/ambiguous creation, not DTO or revision inequality. */
    @Synchronized fun observeCommittedClear(device: DeviceDTO) {
        if (device.preRepeatFrequency == null && device.preRepeatBandwidth == null &&
            device.preRepeatSpreadingFactor == null && device.preRepeatCodingRate == null) {
            committedClears[device.identity()] = CommittedClear(
                identityVersion(device.id), snapshotVersions[device.id] ?: 0L,
            )
            pending.remove(device.identity())
            clearRequests.remove(device.identity())
        } else committedClears.remove(device.identity())
    }
}

class DeviceWriteConflict : IllegalStateException("Device write authority changed")

/** Captured atomically with the live OCV publication revision, including equal-valued publications. */
data class ConnectedDeviceWriteView(val device: DeviceDTO, val ocvRevision: Long)

/** A verified radio scope must still match the projected persisted default when removing a region. */
enum class KnownRegionRemovalOutcome { Removed, Retired, NeedsScopeReconciliation }

/** Overlay only changes published since [before], retaining newly committed Room fields otherwise. */
fun DeviceDTO.preservingChanges(before: DeviceDTO, live: DeviceDTO): DeviceDTO {
    require(id == before.id && id == live.id && radioId == before.radioId && radioId == live.radioId)
    fun <T> changed(old: T, current: T, saved: T): T = if (old != current) current else saved
    return copy(
        publicKey = changed(before.publicKey, live.publicKey, publicKey),
        nodeName = changed(before.nodeName, live.nodeName, nodeName),
        firmwareVersion = changed(before.firmwareVersion, live.firmwareVersion, firmwareVersion),
        firmwareVersionString = changed(before.firmwareVersionString, live.firmwareVersionString, firmwareVersionString),
        manufacturerName = changed(before.manufacturerName, live.manufacturerName, manufacturerName),
        buildDate = changed(before.buildDate, live.buildDate, buildDate),
        maxContacts = changed(before.maxContacts, live.maxContacts, maxContacts),
        maxChannels = changed(before.maxChannels, live.maxChannels, maxChannels),
        frequency = changed(before.frequency, live.frequency, frequency),
        bandwidth = changed(before.bandwidth, live.bandwidth, bandwidth),
        spreadingFactor = changed(before.spreadingFactor, live.spreadingFactor, spreadingFactor),
        codingRate = changed(before.codingRate, live.codingRate, codingRate),
        txPower = changed(before.txPower, live.txPower, txPower),
        maxTxPower = changed(before.maxTxPower, live.maxTxPower, maxTxPower),
        latitude = changed(before.latitude, live.latitude, latitude),
        longitude = changed(before.longitude, live.longitude, longitude),
        blePin = changed(before.blePin, live.blePin, blePin),
        clientRepeat = changed(before.clientRepeat, live.clientRepeat, clientRepeat),
        pathHashMode = changed(before.pathHashMode, live.pathHashMode, pathHashMode),
        defaultFloodScopeName = changed(before.defaultFloodScopeName, live.defaultFloodScopeName, defaultFloodScopeName),
        preRepeatFrequency = changed(before.preRepeatFrequency, live.preRepeatFrequency, preRepeatFrequency),
        preRepeatBandwidth = changed(before.preRepeatBandwidth, live.preRepeatBandwidth, preRepeatBandwidth),
        preRepeatSpreadingFactor = changed(before.preRepeatSpreadingFactor, live.preRepeatSpreadingFactor, preRepeatSpreadingFactor),
        preRepeatCodingRate = changed(before.preRepeatCodingRate, live.preRepeatCodingRate, preRepeatCodingRate),
        manualAddContacts = changed(before.manualAddContacts, live.manualAddContacts, manualAddContacts),
        autoAddConfig = changed(before.autoAddConfig, live.autoAddConfig, autoAddConfig),
        autoAddMaxHops = changed(before.autoAddMaxHops, live.autoAddMaxHops, autoAddMaxHops),
        multiAcks = changed(before.multiAcks, live.multiAcks, multiAcks),
        telemetryModeBase = changed(before.telemetryModeBase, live.telemetryModeBase, telemetryModeBase),
        telemetryModeLoc = changed(before.telemetryModeLoc, live.telemetryModeLoc, telemetryModeLoc),
        telemetryModeEnv = changed(before.telemetryModeEnv, live.telemetryModeEnv, telemetryModeEnv),
        advertLocationPolicy = changed(before.advertLocationPolicy, live.advertLocationPolicy, advertLocationPolicy),
        lastConnected = changed(before.lastConnected, live.lastConnected, lastConnected),
        lastContactSync = changed(before.lastContactSync, live.lastContactSync, lastContactSync),
        isActive = changed(before.isActive, live.isActive, isActive),
        ocvPreset = changed(before.ocvPreset, live.ocvPreset, ocvPreset),
        appliedRadioPresetID = changed(before.appliedRadioPresetID, live.appliedRadioPresetID, appliedRadioPresetID),
        customOCVArrayString = changed(before.customOCVArrayString, live.customOCVArrayString, customOCVArrayString),
        connectionMethods = changed(before.connectionMethods, live.connectionMethods, connectionMethods),
        knownRegions = changed(before.knownRegions, live.knownRegions, knownRegions),
    )
}

/**
 * Rebase an intended-field edit on the matching persisted value, never on a previously read live DTO.
 * Full replacements/activation invalidate authority; targeted concurrent edits may be retried.
 */
suspend fun DevicePersisting.editDevice(
    id: UUID,
    isCurrent: () -> Boolean = { true },
    onSaveFailure: (PersistenceStoreException) -> Nothing = { throw it },
    onCommitted: (DeviceDTO) -> Unit = {},
    withAuthority: suspend (suspend () -> Boolean) -> Boolean = { it() },
    expectedSnapshot: DeviceWriteSnapshot? = null,
    onCommittedSnapshot: (DeviceWriteSnapshot) -> Unit = {},
    transform: (DeviceDTO) -> DeviceDTO,
): DeviceDTO? {
    val persistence = this as? DeviceEditPersisting
        ?: throw UnsupportedOperationException("Device edits require versioned device persistence")
    var authority: Long? = null
    repeat(16) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return null
        val snapshot = persistence.fetchDeviceWriteSnapshot(id) ?: return null
        if (!isCurrent()) return null
        if (expectedSnapshot != null && (snapshot.revision != expectedSnapshot.revision ||
                snapshot.authorityRevision != expectedSnapshot.authorityRevision ||
                snapshot.device != expectedSnapshot.device)) return null
        if (authority != null && snapshot.authorityRevision != authority) throw DeviceWriteConflict()
        authority = snapshot.authorityRevision
        val updated = transform(snapshot.device)
        require(updated.id == snapshot.device.id && updated.radioId == snapshot.device.radioId)
        require(updated.isActive == snapshot.device.isActive && updated.lastConnected == snapshot.device.lastConnected)
        val caller = currentCoroutineContext()
        var commitObserved = false
        var callbackFailure: Throwable? = null
        val guarded = snapshot.copy(withAuthority = withAuthority, onCommitted = { saved ->
            commitObserved = true
            try {
                onCommitted(saved)
            } catch (failure: Throwable) {
                if (callbackFailure == null) callbackFailure = failure
                throw failure
            }
        }, onCommittedSnapshot = { committed ->
            commitObserved = true
            try {
                onCommittedSnapshot(committed)
            } catch (failure: Throwable) {
                if (callbackFailure == null) callbackFailure = failure
                throw failure
            }
        }, isCurrent = {
            caller.ensureActive()
            isCurrent()
        })
        val committed = try {
            persistence.saveDeviceIfUnchanged(updated, guarded)
        } catch (failure: Throwable) {
            // A dispatcher boundary may recover/copy CancellationException; retain the actual callback object.
            callbackFailure?.let { throw it }
            if (failure is PersistenceStoreException && !commitObserved) onSaveFailure(failure)
            throw failure
        }
        caller.ensureActive()
        if (committed) return updated
    }
    throw DeviceWriteConflict()
}

interface DeviceEditPersisting {
    val repeatSnapshotAuthority: RepeatSnapshotAuthority
        get() = throw UnsupportedOperationException("Repeat snapshot custody requires a process-owned authority")
    /** Initialization only: atomically create an inactive row, never replace an existing device. */
    suspend fun insertDeviceIfAbsent(dto: DeviceDTO): Boolean
    /** Evaluate connection ownership inside the serialized activation transaction, not before waiting. */
    suspend fun activateDeviceIfCurrent(id: UUID, isCurrent: () -> Boolean): Boolean
    suspend fun fetchDeviceWriteSnapshot(id: UUID): DeviceWriteSnapshot?
    /** Publish [DeviceWriteSnapshot.onCommitted] exactly once after commit, never on a rejected write. */
    suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean
}

suspend fun DevicePersisting.activateDeviceIfCurrent(id: UUID, isCurrent: () -> Boolean): Boolean =
    (this as? DeviceEditPersisting
        ?: throw UnsupportedOperationException("Connection activation requires versioned device persistence"))
        .activateDeviceIfCurrent(id, isCurrent)

interface DevicePersisting {
    suspend fun fetchDevice(id: UUID): DeviceDTO?
    suspend fun fetchDevice(radioId: RadioId): DeviceDTO?
    suspend fun fetchDevice(publicKey: Bytes): DeviceDTO?
    suspend fun fetchDevices(): SnapshotList<DeviceDTO>
    suspend fun fetchActiveDevice(): DeviceDTO?
    suspend fun saveDevice(dto: DeviceDTO)
    suspend fun setActiveDevice(id: UUID)
    suspend fun updateDeviceLastContactSync(radioId: RadioId, timestamp: UInt)
    suspend fun addDeviceKnownRegion(radioId: RadioId, region: String)
    suspend fun removeDeviceKnownRegion(radioId: RadioId, region: String)
    suspend fun deleteDeviceData(id: UUID)
    suspend fun deleteDevice(id: UUID)
    suspend fun demoteDeviceToGhost(id: UUID)
    suspend fun deleteDeviceAndData(id: UUID)
    suspend fun reconcileGhostIdentity(currentDeviceID: UUID, newPublicKey: Bytes): RadioId?
}

interface ContactPersisting {
    suspend fun fetchContacts(radioId: RadioId): SnapshotList<ContactDTO>
    suspend fun fetchConversations(radioId: RadioId): SnapshotList<ContactDTO>
    suspend fun fetchContact(key: EntityKey): ContactDTO?
    suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO?
    suspend fun fetchContactByPrefix(radioId: RadioId, publicKeyPrefix: Bytes): ContactDTO?
    suspend fun fetchContactPublicKeysByPrefix(radioId: RadioId): SnapshotMap<UByte, SnapshotList<Bytes>>
    suspend fun findContactNameByKeyPrefix(prefix: Bytes): String?
    suspend fun findContactByPublicKey(publicKey: Bytes): ContactDTO?
    suspend fun saveContact(radioId: RadioId, frame: ContactFrame): ContactSaveResult
    /**
     * Atomic accepted-write upsert preserving newer timestamps and equal-timestamp frames changed
     * since [expectedFrame] was read. Returns null when a newer frame was retained rather than
     * applying this one. Local preferences/activity are retained even when applying.
     */
    suspend fun saveContactPreservingNewer(
        radioId: RadioId, frame: ContactFrame, expectedFrame: ContactFrame? = null,
    ): ContactSaveResult?
    suspend fun saveContact(dto: ContactDTO)
    /** Atomically changes only the avatar of an existing matching identity; null clears it. Never inserts. */
    suspend fun updateContactAvatar(
        key: EntityKey, expectedPublicKey: Bytes, imageData: Bytes?,
    ): ContactAvatarUpdateResult
    /** Changes only the favorite preference and flag bit on an existing matching identity. */
    suspend fun updateContactFavorite(
        key: EntityKey, expectedPublicKey: Bytes, isFavorite: Boolean,
    ): ContactFavoriteUpdateResult
    /** Applies only the selected local fields to an existing identity; reports the committed block transition. */
    suspend fun updateContactLocalFields(
        key: EntityKey, expectedPublicKey: Bytes, update: ContactLocalUpdate,
    ): ContactLocalUpdateResult
    suspend fun batchSaveContacts(radioId: RadioId, frames: SnapshotList<ContactFrame>): Long
    suspend fun deleteContact(key: EntityKey)
    suspend fun deleteContacts(
        radioId: RadioId, publicKeys: SnapshotSet<Bytes>, skippingPublicKeys: () -> SnapshotSet<Bytes> = { SnapshotSet.empty() },
    ): SnapshotList<UUID>
    suspend fun deleteContactIfUnreferenced(key: EntityKey)
    suspend fun adoptOrphanedDirectMessages(radioId: RadioId, contacts: SnapshotList<ContactIdentity>): SnapshotMap<UUID, Long>
    suspend fun touchContactHeard(radioId: RadioId, publicKey: Bytes, date: Instant): Boolean
    suspend fun updateContactLastMessage(key: EntityKey, date: Instant?)
    suspend fun recomputeContactLastMessageDate(key: EntityKey): Instant?
    suspend fun incrementUnreadCount(key: EntityKey)
    suspend fun clearUnreadCount(key: EntityKey)
    suspend fun markMentionSeen(key: EntityKey)
    suspend fun incrementUnreadMentionCount(key: EntityKey)
    suspend fun decrementUnreadMentionCount(key: EntityKey)
    suspend fun clearUnreadMentionCount(key: EntityKey)
    suspend fun fetchUnseenMentionIDs(key: EntityKey): SnapshotList<UUID>
    suspend fun setContactMuted(key: EntityKey, isMuted: Boolean)
    suspend fun deleteMessagesForContact(key: EntityKey)
    suspend fun deleteChannelMessages(senderName: String, radioId: RadioId)
    suspend fun fetchBlockedContacts(radioId: RadioId): SnapshotList<ContactDTO>
    suspend fun saveBlockedChannelSender(dto: BlockedChannelSenderDTO)
    suspend fun deleteBlockedChannelSender(radioId: RadioId, name: String)
    suspend fun fetchBlockedChannelSenders(radioId: RadioId): SnapshotList<BlockedChannelSenderDTO>
    suspend fun fetchContactPublicKeys(radioId: RadioId): SnapshotSet<Bytes>
}

interface ChannelPersisting {
    suspend fun fetchChannels(radioId: RadioId): SnapshotList<ChannelDTO>
    suspend fun fetchChannel(radioId: RadioId, index: UByte): ChannelDTO?
    suspend fun fetchChannel(key: EntityKey): ChannelDTO?
    suspend fun saveChannel(radioId: RadioId, info: ChannelInfo): UUID
    suspend fun saveChannel(dto: ChannelDTO)
    suspend fun batchSaveChannels(
        radioId: RadioId, configured: SnapshotList<ChannelInfo>, unconfiguredIndices: SnapshotList<UByte>, pruneBeyond: UByte?,
    ): SnapshotList<ChannelDTO>
    suspend fun deleteChannel(key: EntityKey)
    suspend fun deleteMessagesForChannel(radioId: RadioId, channelIndex: UByte)
    suspend fun updateChannelLastMessage(key: EntityKey, date: Instant?)
    suspend fun incrementChannelUnreadCount(key: EntityKey)
    suspend fun clearChannelUnreadCount(key: EntityKey)
    suspend fun clearChannelUnreadCount(radioId: RadioId, index: UByte)
    suspend fun setChannelMuted(key: EntityKey, isMuted: Boolean)
    suspend fun setChannelNotificationLevel(key: EntityKey, level: NotificationLevel)
    suspend fun setSessionNotificationLevel(key: EntityKey, level: NotificationLevel)
    suspend fun setChannelFavorite(key: EntityKey, isFavorite: Boolean)
    suspend fun setChannelFloodScope(key: EntityKey, floodScope: ChannelFloodScope)
    suspend fun incrementChannelUnreadMentionCount(key: EntityKey)
    suspend fun decrementChannelUnreadMentionCount(key: EntityKey)
    suspend fun clearChannelUnreadMentionCount(key: EntityKey)
    suspend fun fetchUnseenChannelMentionIDs(radioId: RadioId, channelIndex: UByte): SnapshotList<UUID>
}
