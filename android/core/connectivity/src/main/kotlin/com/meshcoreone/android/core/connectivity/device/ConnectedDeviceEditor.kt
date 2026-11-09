// PortedFrom: MC1Services/Sources/MC1Services/Connection/ConnectionManager+Pairing.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.connectivity.device

import com.meshcoreone.android.core.connectivity.ConnectivityDiagnostics
import com.meshcoreone.android.core.connectivity.ConnectivityError
import com.meshcoreone.android.core.contracts.domain.ConnectedDeviceEdit
import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.DeviceSessionPersisting
import com.meshcoreone.android.core.contracts.domain.DeviceWriteConflict
import com.meshcoreone.android.core.contracts.domain.editDevice
import com.meshcoreone.android.core.contracts.domain.preservingChanges
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.VContactIdentity
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
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
    /** Manager-backed access must fail closed if its atomic generation capture is unavailable. */
    val requiresGenerationDeviceWriteLease: Boolean get() = false
    fun captureDeviceWrite(): Pair<SessionToken, DeviceDTO>? = null
    suspend fun <T : Any> withDeviceWrite(
        token: SessionToken,
        deviceId: UUID,
        block: suspend () -> T,
    ): T? = null
    /** The returned predicate remains valid for this admitted write until its drain is released. */
    suspend fun <T : Any> withDeviceWriteLease(
        token: SessionToken,
        deviceId: UUID,
        block: suspend (isLeaseCurrent: () -> Boolean) -> T,
    ): T? = null
    fun currentDevice(expectedToken: SessionToken): DeviceDTO? =
        throw UnsupportedOperationException("Session-bound connected-device reads are not supported")
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
    private val devices: DeviceSessionPersisting,
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
            val current = updated.appliedRadioPresetID
            if (current != null && current !in matches) updated = updated.copy(appliedRadioPresetID = null)
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

    suspend fun awaitDeviceEdits(expectedToken: SessionToken): Boolean {
        if (!access.requiresGenerationDeviceWriteLease) {
            return sessionEdits.withLock { access.currentDevice(expectedToken) != null }
        }
        val (token, device) = access.captureDeviceWrite() ?: return false
        if (token != expectedToken) return false
        return sessionEdits.withLock {
            access.withDeviceWrite(token, device.id) {
                val current = access.currentDevice(token)
                current != null && current.id == device.id && current.radioId == device.radioId
            } ?: false
        }
    }

    private suspend fun sessionEdit(
        operation: String,
        expectedToken: SessionToken,
        transform: (DeviceDTO) -> DeviceDTO,
    ): Boolean {
        if (!access.requiresGenerationDeviceWriteLease) {
            return sessionEdits.withLock { persistEdit(operation, expectedToken, transform) }
        }
        val (token, device) = access.captureDeviceWrite() ?: return false
        if (token != expectedToken) return false
        return sessionEdits.withLock {
            access.withDeviceWriteLease(token, device.id) { isLeaseCurrent ->
                persistEdit(operation, token, transform, device, isLeaseCurrent)
            } ?: false
        }
    }

    private suspend fun persistEdit(
        operation: String,
        expectedToken: SessionToken?,
        transform: (DeviceDTO) -> DeviceDTO,
        capturedDevice: DeviceDTO? = null,
        isLeaseCurrent: (() -> Boolean)? = null,
    ): Boolean {
        val original = if (isLeaseCurrent != null && expectedToken != null) {
            if (!isLeaseCurrent()) return false
            capturedDevice ?: return false
        } else if (capturedDevice != null && expectedToken != null) {
            val current = access.currentDevice(expectedToken) ?: return false
            if (current.id != capturedDevice.id || current.radioId != capturedDevice.radioId) return false
            current
        } else {
            capturedDevice ?: if (expectedToken == null) access.connectedDevice else access.currentDevice(expectedToken)
        }
        original ?: return false
        fun current(): Boolean {
            val live = if (expectedToken == null) access.connectedDevice else access.currentDevice(expectedToken)
            return live != null && live.id == original.id && live.radioId == original.radioId
        }
        try {
            val saved = devices.editDevice(original.id, isLeaseCurrent ?: ::current) { authoritative ->
                if (authoritative.radioId != original.radioId || authoritative.isActive != original.isActive) {
                    throw DeviceWriteConflict()
                }
                transform(authoritative)
            } ?: return false
            currentCoroutineContext().ensureActive()
            var published: DeviceDTO? = null
            val edit = access.transformConnectedDevice(expectedToken) { live ->
                // A callback may have published unrelated fields while Room was suspended.
                saved.preservingChanges(original, live).also { published = it }
            } ?: return false
            val updated = checkNotNull(published)
            if (edit.previousKnownRegions != updated.knownRegions) {
                onKnownRegionsChanged(edit, updated.knownRegions.toList())
            }
            return true
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

    suspend fun savePreRepeatSettings(deviceId: UUID): Boolean {
        if (!access.requiresGenerationDeviceWriteLease) {
            return sessionEdits.withLock {
                val device = access.connectedDevice?.takeIf { it.id == deviceId } ?: return@withLock false
                persistEdit("preRepeat", access.connectedDeviceToken, { it.savingPreRepeatSettings() }, device)
            }
        }
        val (token, device) = access.captureDeviceWrite() ?: return false
        if (device.id != deviceId) return false
        return sessionEdits.withLock {
            access.withDeviceWriteLease(token, device.id) { isLeaseCurrent ->
                if (!isLeaseCurrent()) return@withDeviceWriteLease false
                persistEdit("preRepeat", token, { it.savingPreRepeatSettings() }, device, isLeaseCurrent)
            } ?: false
        }
    }

    fun clearPreRepeatSettings(): Job? = edit("clearPreRepeat") { it.clearingPreRepeatSettings() }

    fun addKnownRegion(region: String): Job? {
        if (access.requiresGenerationDeviceWriteLease) {
            val (token, device) = access.captureDeviceWrite() ?: return null
            if (region in device.knownRegions) return null
            return scope.launch(start = CoroutineStart.UNDISPATCHED) {
                report("addKnownRegion") {
                    changeKnownRegion(region, false, token, capturedDevice = device)
                }
            }
        }
        val device = access.connectedDevice ?: return null
        if (region in device.knownRegions) return null
        val token = access.connectedDeviceToken
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            report("addKnownRegion") { changeKnownRegion(region, false, token) }
        }
    }

    fun removeKnownRegion(region: String): Job? {
        if (access.requiresGenerationDeviceWriteLease) {
            val (token, device) = access.captureDeviceWrite() ?: return null
            return scope.launch(start = CoroutineStart.UNDISPATCHED) {
                report("removeKnownRegion") {
                    changeKnownRegion(region, true, token, clearRadio = true, capturedDevice = device)
                }
            }
        }
        access.connectedDevice ?: return null
        val token = access.connectedDeviceToken
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            report("removeKnownRegion") {
                changeKnownRegion(region, true, token, clearRadio = true)
            }
        }
    }

    suspend fun addKnownRegion(region: String, expectedToken: SessionToken): Boolean =
        changeKnownRegionWithExpectedToken(region, false, expectedToken)

    /** The Settings consumer has already verified the default-scope radio write, if required. */
    suspend fun persistKnownRegionRemoval(region: String, expectedToken: SessionToken): Boolean =
        changeKnownRegionWithExpectedToken(region, true, expectedToken)

    private suspend fun changeKnownRegionWithExpectedToken(
        region: String,
        remove: Boolean,
        expectedToken: SessionToken,
    ): Boolean {
        if (!access.requiresGenerationDeviceWriteLease) {
            return sessionEdits.withLock {
                changeKnownRegionLocked(region, remove, expectedToken, null, false)
            }
        }
        val (token, device) = access.captureDeviceWrite() ?: return false
        if (token != expectedToken) return false
        return sessionEdits.withLock {
            access.withDeviceWriteLease(token, device.id) { isLeaseCurrent ->
                changeKnownRegionLocked(region, remove, token, device, false, isLeaseCurrent)
            } ?: false
        }
    }

    private suspend fun changeKnownRegion(
        region: String,
        remove: Boolean,
        expectedToken: SessionToken?,
        clearRadio: Boolean = false,
        capturedDevice: DeviceDTO? = null,
    ): Boolean = sessionEdits.withLock {
        if (access.requiresGenerationDeviceWriteLease) {
            val token = expectedToken ?: return@withLock false
            val device = capturedDevice ?: return@withLock false
            return@withLock access.withDeviceWriteLease(token, device.id) { isLeaseCurrent ->
                changeKnownRegionLocked(region, remove, token, device, clearRadio, isLeaseCurrent)
            } ?: false
        }
        changeKnownRegionLocked(region, remove, expectedToken, capturedDevice, clearRadio)
    }

    private suspend fun changeKnownRegionLocked(
        region: String,
        remove: Boolean,
        expectedToken: SessionToken?,
        capturedDevice: DeviceDTO?,
        clearRadio: Boolean,
        isLeaseCurrent: (() -> Boolean)? = null,
    ): Boolean {
        val original = if (isLeaseCurrent != null && expectedToken != null) {
            if (!isLeaseCurrent()) return false
            capturedDevice ?: return false
        } else if (capturedDevice != null && expectedToken != null) {
            val current = access.currentDevice(expectedToken) ?: return false
            if (current.id != capturedDevice.id || current.radioId != capturedDevice.radioId) return false
            current
        } else {
            capturedDevice ?: if (expectedToken == null) access.connectedDevice else access.currentDevice(expectedToken)
        }
        original ?: return false
        val isCurrent = {
            val live = if (expectedToken == null) access.connectedDevice else access.currentDevice(expectedToken)
            live != null && live.id == original.id && live.radioId == original.radioId
        }
        val persistenceGuard = isLeaseCurrent ?: isCurrent
        val persisted = if (remove) {
            devices.removeDeviceKnownRegionIfCurrent(original.id, original.radioId, region, persistenceGuard)
        } else {
            devices.addDeviceKnownRegionIfCurrent(original.id, original.radioId, region, persistenceGuard)
        }
        if (!persisted || !isCurrent()) return false
        if (clearRadio && original.defaultFloodScopeName == region) clearDefaultFloodScope()
        if (!isCurrent()) return false
        var published: DeviceDTO? = null
        val edit = access.transformConnectedDevice(expectedToken) { live ->
            if (live.id != original.id || live.radioId != original.radioId) live
            else live.copy(
                knownRegions = if (remove) live.knownRegions.filterNot { it == region }.snapshot()
                else (live.knownRegions + region).distinct().snapshot(),
                defaultFloodScopeName = if (remove && live.defaultFloodScopeName == region) null else live.defaultFloodScopeName,
            ).also { published = it }
        } ?: return false
        val updated = published ?: return false
        if (edit.previousKnownRegions != updated.knownRegions) onKnownRegionsChanged(edit, updated.knownRegions.toList())
        return true
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
        if (access.requiresGenerationDeviceWriteLease) {
            val (token, device) = access.captureDeviceWrite() ?: return null
            return scope.launch(start = CoroutineStart.UNDISPATCHED) {
                report(operation) {
                    sessionEdits.withLock {
                        access.withDeviceWriteLease(token, device.id) { isLeaseCurrent ->
                            persistEdit(operation, token, transform, device, isLeaseCurrent)
                        }
                    }
                }
            }
        }
        val device = access.connectedDevice ?: return null
        val token = access.connectedDeviceToken
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
