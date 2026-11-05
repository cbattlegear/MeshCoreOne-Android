// PortedFrom: MC1Services/Sources/MC1Services/Connection/ConnectionManager+Pairing.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.connectivity.device

import com.meshcoreone.android.core.connectivity.ConnectivityDiagnostics
import com.meshcoreone.android.core.connectivity.ConnectivityError
import com.meshcoreone.android.core.contracts.domain.ConnectedDeviceEdit
import com.meshcoreone.android.core.contracts.domain.ConnectedDeviceWriteView
import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.DevicePersisting
import com.meshcoreone.android.core.contracts.domain.DeviceEditPersisting
import com.meshcoreone.android.core.contracts.domain.DeviceWriteConflict
import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.PreRepeatSettingsAttempt
import com.meshcoreone.android.core.contracts.domain.RepeatSnapshotRestoreAuthority
import com.meshcoreone.android.core.contracts.domain.KnownRegionRemovalOutcome
import com.meshcoreone.android.core.contracts.domain.editDevice
import com.meshcoreone.android.core.contracts.domain.preservingChanges
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.VContactIdentity
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.bytes.utf8Prefix
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.core.protocol.session.RadioWritePermit
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The live connected-device value. Accepted replacements return their connection, sequence and
 * previous regions atomically; rejected replacements return null.
 */
interface ConnectedDeviceAccess {
    val connectedDevice: DeviceDTO?
    /** Read and transform share the runtime's short publication monitor; never a suspending lock. */
    val ocvPublicationRevision: Long
    fun captureDeviceWriteView(expectedToken: SessionToken?): ConnectedDeviceWriteView?
    fun publishOCVSettings(device: DeviceDTO, expectedToken: SessionToken): ConnectedDeviceEdit?
    val connectedDeviceToken: SessionToken? get() = null
    fun currentDevice(expectedToken: SessionToken): DeviceDTO? =
        throw UnsupportedOperationException("Session-bound connected-device reads are not supported")
    suspend fun withDeviceWriteAuthority(expectedToken: SessionToken?, write: suspend () -> Boolean): Boolean = write()
    fun transformConnectedDevice(
        expectedToken: SessionToken?,
        transform: (DeviceDTO) -> DeviceDTO,
    ): ConnectedDeviceEdit? {
        val current = if (expectedToken == null) connectedDevice else currentDevice(expectedToken)
        val updated = transform(current ?: return null)
        return if (expectedToken == null) replaceConnectedDevice(updated) else replaceConnectedDevice(updated, expectedToken)
    }
    fun replaceConnectedDevice(device: DeviceDTO): ConnectedDeviceEdit?
    fun replaceConnectedDevice(device: DeviceDTO, expectedToken: SessionToken): ConnectedDeviceEdit? =
        throw UnsupportedOperationException("Session-bound connected-device replacement is not supported")
}

/** The current session's contact service operations used by bulk node removal. */
interface ContactRemovalPort {
    suspend fun removeContact(radioId: RadioId, publicKey: Bytes)
    suspend fun removeLocalContact(contactId: UUID, publicKey: Bytes)
}

/** The radio has no such contact; the caller cleans it up locally instead. */
class ContactNotFoundException : Exception("Contact not found on device")

class DeviceEditPersistenceConflict : IllegalStateException("Connected device changed before the edit was persisted")

private class RegionScopeReconciliationRequired : IllegalStateException()

/** Radio preset catalog lookup (WP-211 owns the catalog); returns ids whose RF matches. */
fun interface RadioPresetMatcher {
    fun matchingPresetIds(frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte, codingRate: UByte): Set<String>
}

data class RemoveUnfavoritedResult(val removed: Int, val total: Int)

/**
 * Connected-device edits and bulk node maintenance (ConnectionManager+Pairing "Device Updates"
 * and "Node Management"). Edits share a coroutine mutex through persistence, then publish only their
 * intended fields. Failed/conflicting writes never replace live state. Radio changes belong to
 * the session's verified Settings operation; this editor only persists their local representation.
 */
class ConnectedDeviceEditor(
    private val access: ConnectedDeviceAccess,
    private val devices: DevicePersisting,
    private val contacts: ContactPersisting,
    private val contactRemoval: () -> ContactRemovalPort?,
    private val presets: RadioPresetMatcher,
    private val scope: CoroutineScope,
    private val now: () -> Instant,
    private val diagnostics: ConnectivityDiagnostics = ConnectivityDiagnostics.NONE,
    private val onKnownRegionsChanged: (ConnectedDeviceEdit, List<String>) -> Unit = { _, _ -> },
    private val clearDefaultFloodScope: suspend () -> Unit = {},
) {
    private val sessionEdits = Mutex()
    private val repeatSnapshotAuthority
        get() = (devices as? DeviceEditPersisting
            ?: throw UnsupportedOperationException("Repeat snapshots require versioned device persistence")).repeatSnapshotAuthority
    val connectedDevice: DeviceDTO? get() = access.connectedDevice

    fun updateDevice(info: SelfInfo, appliedRadioPresetID: String?): Job? {
        return edit("updateDevice") { it.updatingSettings(info, appliedRadioPresetID) }
    }

    suspend fun updateDevice(info: SelfInfo, appliedRadioPresetID: String?, expectedToken: SessionToken): Boolean =
        sessionEdit("updateDevice", expectedToken) { it.updatingSettings(info, appliedRadioPresetID) }

    private fun DeviceDTO.updatingSettings(info: SelfInfo, presetId: String?): DeviceDTO {
        var updated = updating(info)
        if (presetId != null) {
            updated = updated.copy(appliedRadioPresetID = presetId)
        } else if (!clientRepeat) {
            val matches = presets.matchingPresetIds(updated.frequency, updated.bandwidth, updated.spreadingFactor, updated.codingRate)
            if (updated.appliedRadioPresetID != null && updated.appliedRadioPresetID !in matches) {
                updated = updated.copy(appliedRadioPresetID = null)
            }
        }
        return updated
    }

    suspend fun updateAutoAddConfig(config: AutoAddConfig, expectedToken: SessionToken): Boolean =
        sessionEdit("autoAddConfig", expectedToken) { it.copy(autoAddConfig = config.bitmask, autoAddMaxHops = config.maxHops) }

    suspend fun updateClientRepeat(enabled: Boolean, expectedToken: SessionToken): Boolean =
        sessionEdit("clientRepeat", expectedToken) { it.copy(clientRepeat = enabled) }

    suspend fun updateDefaultFloodScopeName(name: String?, expectedToken: SessionToken): Boolean =
        sessionEdit("defaultFloodScope", expectedToken) { it.copy(defaultFloodScopeName = name) }

    suspend fun updatePathHashMode(mode: UByte, expectedToken: SessionToken): Boolean =
        sessionEdit("pathHashMode", expectedToken) { it.copy(pathHashMode = mode) }

    private suspend fun sessionEdit(
        operation: String,
        expectedToken: SessionToken?,
        expectedSnapshot: DeviceWriteSnapshot? = null,
        onCommittedSnapshot: (DeviceWriteSnapshot) -> Unit = {},
        transform: (DeviceDTO) -> DeviceDTO,
    ): Boolean {
        var notification: (() -> Unit)? = null
        try {
            return sessionEdits.withLock {
                persistEdit(operation, expectedToken, { notification = it }, expectedSnapshot, onCommittedSnapshot, transform)
            }
        } finally {
            // A committed notice survives caller cancellation, but never runs under any write lease.
            notification?.invoke()
        }
    }

    private suspend fun persistEdit(
        operation: String,
        expectedToken: SessionToken?,
        notifyAfterUnlock: (() -> Unit) -> Unit,
        expectedSnapshot: DeviceWriteSnapshot?,
        onCommittedSnapshot: (DeviceWriteSnapshot) -> Unit,
        transform: (DeviceDTO) -> DeviceDTO,
    ): Boolean {
        val originalView = access.captureDeviceWriteView(expectedToken) ?: return false
        val original = originalView.device
        fun current(): Boolean {
            val live = if (expectedToken == null) access.connectedDevice else access.currentDevice(expectedToken)
            return live != null && live.id == original.id && live.radioId == original.radioId
        }
        try {
            var published = false
            devices.editDevice(original.id, ::current, withAuthority = { write ->
                access.withDeviceWriteAuthority(expectedToken, write)
            }, expectedSnapshot = expectedSnapshot, onCommittedSnapshot = onCommittedSnapshot, onCommitted = { saved ->
                var updated: DeviceDTO? = null
                val edit = access.transformConnectedDevice(expectedToken) { live ->
                    val merged = saved.preservingChanges(original, live)
                    // A -> B -> A is still a newer OCV publication, despite value equality.
                    (if (access.ocvPublicationRevision != originalView.ocvRevision) {
                        merged.copy(ocvPreset = live.ocvPreset, customOCVArrayString = live.customOCVArrayString)
                    } else merged).also { updated = it }
                }
                if (edit != null) {
                    val device = checkNotNull(updated)
                    if (edit.previousKnownRegions != device.knownRegions) {
                        notifyAfterUnlock { onKnownRegionsChanged(edit, device.knownRegions.toList()) }
                    }
                    published = true
                }
            }) { authoritative ->
                if (authoritative.radioId != original.radioId || authoritative.isActive != original.isActive) {
                    throw DeviceWriteConflict()
                }
                transform(authoritative)
            } ?: return false
            return published
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (reconcile: RegionScopeReconciliationRequired) {
            throw reconcile
        } catch (conflict: DeviceWriteConflict) {
            val failure = DeviceEditPersistenceConflict()
            diagnostics.report("device.$operation", failure)
            throw failure
        } catch (failure: Exception) {
            diagnostics.report("device.$operation", failure)
            throw failure
        }
    }

    fun updateOCVSettings(device: DeviceDTO, expectedToken: SessionToken) {
        access.publishOCVSettings(device, expectedToken)
    }

    fun updateDevice(device: DeviceDTO) {
        val edit = access.replaceConnectedDevice(device) ?: return
        if (edit.previousKnownRegions != device.knownRegions) {
            onKnownRegionsChanged(edit, device.knownRegions.toList())
        }
    }

    fun updateAutoAddConfig(config: AutoAddConfig): Job? = edit("autoAddConfig") {
        it.copy(autoAddConfig = config.bitmask, autoAddMaxHops = config.maxHops)
    }

    fun updateClientRepeat(enabled: Boolean): Job? = edit("clientRepeat") { it.copy(clientRepeat = enabled) }

    fun updatePathHashMode(mode: UByte): Job? = edit("pathHashMode") { it.copy(pathHashMode = mode) }

    fun updateDefaultFloodScopeName(name: String?): Job? = edit("defaultFloodScope") { it.copy(defaultFloodScopeName = name) }

    fun savePreRepeatSettings(): Job? = edit("preRepeat") { it.preservingOrSavingPreRepeatSettings() }

    suspend fun savePreRepeatSettings(expectedToken: SessionToken): Boolean {
        if (!reconcilePreRepeatSettingsClear(expectedToken)) return false
        return sessionEdit("preRepeat", expectedToken) { it.preservingOrSavingPreRepeatSettings() }
    }

    suspend fun savePreRepeatSettings(expectedToken: SessionToken, attempt: PreRepeatSettingsAttempt): Boolean {
        require(attempt.token == expectedToken)
        attempt.claimPreparation()
        if (!reconcilePreRepeatSettingsClear(expectedToken)) return false
        var created = false
        val saved = sessionEdit("prepareRepeat", expectedToken, onCommittedSnapshot = {
            attempt.recordCommitted(it, created)
            if (created) repeatSnapshotAuthority.recordCreated(it, attempt)
        }) { current ->
            created = current.preRepeatFrequency == null && current.preRepeatBandwidth == null &&
                current.preRepeatSpreadingFactor == null && current.preRepeatCodingRate == null && !current.clientRepeat
            current.preservingOrSavingPreRepeatSettings()
        }
        if (saved) attempt.requireCommitted()
        return saved
    }

    suspend fun abortPreRepeatSettings(expectedToken: SessionToken, attempt: PreRepeatSettingsAttempt): Boolean {
        require(attempt.token == expectedToken)
        val receipt = attempt.takeAbortSnapshot() ?: return true
        val saved = sessionEdit("abortRepeat", expectedToken, expectedSnapshot = receipt) { it.clearingPreRepeatSettings() }
        if (!saved) {
            val failure = DeviceEditPersistenceConflict()
            diagnostics.report("device.abortRepeat", failure)
            throw failure
        }
        return true
    }

    private suspend fun currentRepeatDevice(expectedToken: SessionToken): DeviceDTO? {
        val original = access.currentDevice(expectedToken) ?: return null
        val actual = devices.fetchDevice(original.id) ?: return null
        currentCoroutineContext().ensureActive()
        val current = access.currentDevice(expectedToken) ?: return null
        return actual.takeIf { it.id == current.id && it.radioId == current.radioId && it.publicKey == current.publicKey }
    }

    suspend fun validatePreRepeatSettings(expectedToken: SessionToken): Boolean {
        try {
            if (!reconcilePreRepeatSettingsClear(expectedToken)) return false
            val actual = currentRepeatDevice(expectedToken) ?: return false
            repeatSnapshotAuthority.requireUsable(actual)
            repeatSnapshotAuthority.requireNoClearRequest(actual)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            diagnostics.report("device.restoreRepeat", failure)
            throw failure
        }
    }

    fun preRepeatSettingsRestoreAuthority(expectedToken: SessionToken, expectedDevice: DeviceDTO): RepeatSnapshotRestoreAuthority {
        val identityVersion = repeatSnapshotAuthority.identityVersion(expectedDevice.id)
        val snapshotVersion = repeatSnapshotAuthority.snapshotVersion(expectedDevice.id)
        return object : RepeatSnapshotRestoreAuthority {
            override suspend fun withWrite(write: suspend (RadioWritePermit) -> Unit) {
                repeatSnapshotAuthority.withDispatchAdmission(expectedDevice.id) { permit ->
                    val actual = currentRepeatDevice(expectedToken) ?: throw DeviceEditPersistenceConflict()
                    repeatSnapshotAuthority.requireUsable(actual)
                    repeatSnapshotAuthority.requireNoClearRequest(actual)
                    if (repeatSnapshotAuthority.identityVersion(expectedDevice.id) != identityVersion ||
                        repeatSnapshotAuthority.snapshotVersion(expectedDevice.id) != snapshotVersion ||
                        actual.id != expectedDevice.id || actual.radioId != expectedDevice.radioId || actual.publicKey != expectedDevice.publicKey ||
                        actual.frequency != expectedDevice.frequency || actual.bandwidth != expectedDevice.bandwidth ||
                        actual.spreadingFactor != expectedDevice.spreadingFactor || actual.codingRate != expectedDevice.codingRate ||
                        actual.clientRepeat != expectedDevice.clientRepeat ||
                        actual.preRepeatFrequency != expectedDevice.preRepeatFrequency || actual.preRepeatBandwidth != expectedDevice.preRepeatBandwidth ||
                        actual.preRepeatSpreadingFactor != expectedDevice.preRepeatSpreadingFactor || actual.preRepeatCodingRate != expectedDevice.preRepeatCodingRate) {
                        throw DeviceEditPersistenceConflict()
                    }
                    write(permit)
                }
            }

            override fun onRestoreVerified() {
                val accepted = access.transformConnectedDevice(expectedToken) { current ->
                    if (current.id != expectedDevice.id || current.radioId != expectedDevice.radioId ||
                        current.publicKey != expectedDevice.publicKey) throw DeviceEditPersistenceConflict()
                    repeatSnapshotAuthority.recordRestoreVerified(expectedDevice, expectedToken, identityVersion, snapshotVersion)
                    current
                }
                if (accepted == null) throw DeviceEditPersistenceConflict()
            }
        }
    }

    private fun DeviceDTO.preservingOrSavingPreRepeatSettings(): DeviceDTO {
        repeatSnapshotAuthority.requireUsable(this)
        repeatSnapshotAuthority.requireNoClearRequest(this)
        // Partial imported snapshots retain their native per-field restore fallback.
        return if (preRepeatFrequency != null || preRepeatBandwidth != null ||
            preRepeatSpreadingFactor != null || preRepeatCodingRate != null
        ) this else savingPreRepeatSettings()
    }

    private suspend fun reconcilePreRepeatSettingsClear(expectedToken: SessionToken): Boolean {
        val device = access.currentDevice(expectedToken) ?: return false
        if (!repeatSnapshotAuthority.hasClearRequest(device)) return true
        return sessionEdit("reconcileRepeatClear", expectedToken) {
            repeatSnapshotAuthority.reconcilingClearRequest(it)
        }
    }

    fun clearPreRepeatSettings(): Job? {
        return edit("clearPreRepeat") { it.retiringPreRepeatSettings() }
    }

    suspend fun clearPreRepeatSettings(expectedToken: SessionToken): Boolean =
        sessionEdit("clearPreRepeat", expectedToken) { it.retiringPreRepeatSettings() }

    private fun DeviceDTO.retiringPreRepeatSettings(): DeviceDTO =
        if (repeatSnapshotAuthority.hasClearRequest(this))
            repeatSnapshotAuthority.reconcilingClearRequest(this)
        else clearingPreRepeatSettings()

    fun addKnownRegion(region: String): Job? {
        val token = access.connectedDeviceToken
        val device = if (token == null) access.connectedDevice else access.currentDevice(token)
        device ?: return null
        if (region in device.knownRegions) return null
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            report("addKnownRegion") { changeKnownRegion(region, false, token) }
        }
    }

    fun removeKnownRegion(region: String): Job? {
        val token = access.connectedDeviceToken
        val device = (if (token == null) access.connectedDevice else access.currentDevice(token)) ?: return null
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            report("removeKnownRegion") {
                val changed = changeKnownRegion(region, true, token)
                if (changed && device.defaultFloodScopeName != null &&
                    access.connectedDevice?.defaultFloodScopeName == null &&
                    (token == null || access.currentDevice(token) != null)) {
                    clearDefaultFloodScope()
                }
            }
        }
    }

    suspend fun addKnownRegion(region: String, expectedToken: SessionToken): Boolean =
        changeKnownRegion(region, false, expectedToken)

    /** The Settings consumer has already verified the default-scope radio write, if required. */
    suspend fun persistKnownRegionRemoval(region: String, expectedToken: SessionToken): Boolean =
        changeKnownRegion(region, true, expectedToken)

    /** Refuse a changed radio transition instead of silently clearing a still-acknowledged scope. */
    suspend fun persistKnownRegionRemoval(
        region: String,
        expectedToken: SessionToken,
        acknowledgedDefaultFloodScopeName: String?,
    ): KnownRegionRemovalOutcome = try {
        val saved = changeKnownRegion(region, true, expectedToken) { projected ->
            if (projected.defaultFloodScopeName.normalizedScope() != acknowledgedDefaultFloodScopeName.normalizedScope()) {
                throw RegionScopeReconciliationRequired()
            }
        }
        if (saved) KnownRegionRemovalOutcome.Removed else KnownRegionRemovalOutcome.Retired
    } catch (_: RegionScopeReconciliationRequired) {
        KnownRegionRemovalOutcome.NeedsScopeReconciliation
    }

    private fun String?.normalizedScope(): String? =
        this?.utf8Prefix(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES)

    private suspend fun changeKnownRegion(
        region: String,
        remove: Boolean,
        expectedToken: SessionToken?,
        validateProjected: (DeviceDTO) -> Unit = {},
    ): Boolean {
        fun DeviceDTO.removesSelectedScope(): Boolean {
            val selected = defaultFloodScopeName?.utf8Prefix(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES)
                ?: return false
            return remove && selected == region.utf8Prefix(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES) &&
                knownRegions.none {
                    it != region && it.utf8Prefix(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES) == selected
                }
        }
        return sessionEdit(if (remove) "removeKnownRegion" else "addKnownRegion", expectedToken) { authoritative ->
            authoritative.copy(
                knownRegions = if (remove) authoritative.knownRegions.filterNot { it == region }.snapshot()
                else (authoritative.knownRegions + region).distinct().snapshot(),
                defaultFloodScopeName = authoritative.defaultFloodScopeName.takeUnless { authoritative.removesSelectedScope() },
            ).also(validateProjected)
        }
    }

    suspend fun unfavoritedNodeCount(): Int {
        val radioId = access.connectedDevice?.radioId ?: throw ConnectivityError.NotConnected()
        return contacts.fetchContacts(radioId).count { !it.isFavorite }
    }

    suspend fun removeUnfavoritedNodes(): RemoveUnfavoritedResult = removeContacts({ !it.isFavorite })

    suspend fun removeStaleNodes(olderThanDays: Int): RemoveUnfavoritedResult {
        require(olderThanDays >= 0) { "Stale-node age must not be negative" }
        val cutoffSeconds = now().epochSecond - olderThanDays.toLong() * 86_400
        val cutoff = cutoffSeconds.coerceIn(0, UInt.MAX_VALUE.toLong()).toUInt()
        return removeContacts({ it.matchesStaleNodePrune(cutoff) }) { contact ->
            diagnostics.report("Auto-removed stale node [${contact.publicKeyHex.take(8)}]", null)
        }
    }

    private suspend fun removeContacts(
        predicate: (ContactDTO) -> Boolean,
        onRemove: (ContactDTO) -> Unit = {},
    ): RemoveUnfavoritedResult {
        val device = access.connectedDevice ?: throw ConnectivityError.NotConnected()
        val removal = contactRemoval() ?: throw ConnectivityError.NotConnected()
        // Never bulk-remove the ZephCore V-contact: CMD_REMOVE turns firmware v.contact off.
        val targets = contacts.fetchContacts(device.radioId).filter {
            predicate(it) && !VContactIdentity.isVContact(it.publicKey, device.publicKey)
        }
        if (targets.isEmpty()) return RemoveUnfavoritedResult(0, 0)
        var removed = 0
        for (contact in targets) {
            currentCoroutineContext().ensureActive()
            try {
                removal.removeContact(device.radioId, contact.publicKey)
                removed++
                onRemove(contact)
            } catch (missing: ContactNotFoundException) {
                try {
                    removal.removeLocalContact(contact.id, contact.publicKey)
                    removed++
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { diagnostics.report("removeLocalContact", failure) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                diagnostics.report("removeContact", failure)
                return RemoveUnfavoritedResult(removed, targets.size)
            }
        }
        return RemoveUnfavoritedResult(removed, targets.size)
    }

    private fun edit(operation: String, transform: (DeviceDTO) -> DeviceDTO): Job? {
        val token = access.connectedDeviceToken
        val device = if (token == null) access.connectedDevice else access.currentDevice(token)
        device ?: return null
        // UNDISPATCHED enqueues this call before a later awaited preset can acquire the edit mutex.
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            report(operation) {
                if (access.connectedDevice?.id == device.id) sessionEdit(operation, token, transform = transform)
            }
        }
    }

    private suspend inline fun report(operation: String, action: () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { diagnostics.report("device.$operation", failure) }
    }
}
