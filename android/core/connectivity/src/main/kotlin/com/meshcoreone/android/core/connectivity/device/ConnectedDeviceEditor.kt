// PortedFrom: MC1Services/Sources/MC1Services/Connection/ConnectionManager+Pairing.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.connectivity.device

import com.meshcoreone.android.core.connectivity.ConnectivityDiagnostics
import com.meshcoreone.android.core.connectivity.ConnectivityError
import com.meshcoreone.android.core.contracts.domain.ConnectedDeviceEdit
import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.DevicePersisting
import com.meshcoreone.android.core.contracts.domain.DeviceWriteConflict
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

/** Radio preset catalog lookup (WP-211 owns the catalog); returns ids whose RF matches. */
fun interface RadioPresetMatcher {
    fun matchingPresetIds(frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte, codingRate: UByte): Set<String>
}

data class RemoveUnfavoritedResult(val removed: Int, val total: Int)

/**
 * Connected-device edits and bulk node maintenance (ConnectionManager+Pairing "Device Updates"
 * and "Node Management"). Edits share a coroutine mutex through persistence, then publish only their
 * intended fields. Failed/conflicting writes never replace live state.
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
        expectedToken: SessionToken,
        transform: (DeviceDTO) -> DeviceDTO,
    ): Boolean = sessionEdits.withLock { persistEdit(operation, expectedToken, transform) }

    private suspend fun persistEdit(
        operation: String,
        expectedToken: SessionToken?,
        transform: (DeviceDTO) -> DeviceDTO,
    ): Boolean {
        val original = if (expectedToken == null) access.connectedDevice else access.currentDevice(expectedToken)
        original ?: return false
        fun current(): Boolean {
            val live = if (expectedToken == null) access.connectedDevice else access.currentDevice(expectedToken)
            return live != null && live.id == original.id && live.radioId == original.radioId
        }
        try {
            var published = false
            devices.editDevice(original.id, ::current, withAuthority = { write ->
                access.withDeviceWriteAuthority(expectedToken, write)
            }, onCommitted = { saved ->
                var updated: DeviceDTO? = null
                val edit = access.transformConnectedDevice(expectedToken) { live ->
                    saved.preservingChanges(original, live).also { updated = it }
                }
                if (edit != null) {
                    val device = checkNotNull(updated)
                    if (edit.previousKnownRegions != device.knownRegions) {
                        onKnownRegionsChanged(edit, device.knownRegions.toList())
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
        access.transformConnectedDevice(expectedToken) { live ->
            if (live.id != device.id || live.radioId != device.radioId) live
            else live.copy(ocvPreset = device.ocvPreset, customOCVArrayString = device.customOCVArrayString)
        }
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

    fun savePreRepeatSettings(): Job? = edit("preRepeat") { it.savingPreRepeatSettings() }

    fun clearPreRepeatSettings(): Job? = edit("clearPreRepeat") { it.clearingPreRepeatSettings() }

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
        (if (token == null) access.connectedDevice else access.currentDevice(token)) ?: return null
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            report("removeKnownRegion") {
                changeKnownRegion(region, true, token, clearRadio = true)
            }
        }
    }

    suspend fun addKnownRegion(region: String, expectedToken: SessionToken): Boolean =
        changeKnownRegion(region, false, expectedToken)

    /** The Settings consumer has already verified the default-scope radio write, if required. */
    suspend fun persistKnownRegionRemoval(region: String, expectedToken: SessionToken): Boolean =
        changeKnownRegion(region, true, expectedToken)

    private suspend fun changeKnownRegion(
        region: String,
        remove: Boolean,
        expectedToken: SessionToken?,
        clearRadio: Boolean = false,
    ): Boolean = sessionEdits.withLock {
        val original = if (expectedToken == null) access.connectedDevice else access.currentDevice(expectedToken)
        original ?: return@withLock false
        fun DeviceDTO.removesSelectedScope(): Boolean {
            val selected = defaultFloodScopeName?.utf8Prefix(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES)
                ?: return false
            return remove && selected == region.utf8Prefix(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES) &&
                knownRegions.none {
                    it != region && it.utf8Prefix(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES) == selected
                }
        }
        if (clearRadio && original.removesSelectedScope()) clearDefaultFloodScope()
        persistEdit(if (remove) "removeKnownRegion" else "addKnownRegion", expectedToken) { authoritative ->
            authoritative.copy(
                knownRegions = if (remove) authoritative.knownRegions.filterNot { it == region }.snapshot()
                else (authoritative.knownRegions + region).distinct().snapshot(),
                defaultFloodScopeName = authoritative.defaultFloodScopeName.takeUnless { authoritative.removesSelectedScope() },
            )
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
                sessionEdits.withLock {
                    if (access.connectedDevice?.id == device.id) persistEdit(operation, token, transform)
                }
            }
        }
    }

    private suspend inline fun report(operation: String, action: () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { diagnostics.report("device.$operation", failure) }
    }
}
