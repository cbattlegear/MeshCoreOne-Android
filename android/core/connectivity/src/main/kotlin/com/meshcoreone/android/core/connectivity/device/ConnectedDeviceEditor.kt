// PortedFrom: MC1Services/Sources/MC1Services/Connection/ConnectionManager+Pairing.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.connectivity.device

import com.meshcoreone.android.core.connectivity.ConnectivityDiagnostics
import com.meshcoreone.android.core.connectivity.ConnectivityError
import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.DevicePersisting
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * The live connected-device value. Accepted replacements return their originating connection
 * token atomically; rejected replacements return null.
 */
interface ConnectedDeviceAccess {
    val connectedDevice: DeviceDTO?
    fun replaceConnectedDevice(device: DeviceDTO): SessionToken?
}

/** The current session's contact service operations used by bulk node removal. */
interface ContactRemovalPort {
    suspend fun removeContact(radioId: RadioId, publicKey: Bytes)
    suspend fun removeLocalContact(contactId: UUID, publicKey: Bytes)
}

/** The radio has no such contact; the caller cleans it up locally instead. */
class ContactNotFoundException : Exception("Contact not found on device")

/** Radio preset catalog lookup (WP-211 owns the catalog); returns ids whose RF matches. */
fun interface RadioPresetMatcher {
    fun matchingPresetIds(frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte, codingRate: UByte): Set<String>
}

data class RemoveUnfavoritedResult(val removed: Int, val total: Int)

/**
 * Connected-device edits and bulk node maintenance (ConnectionManager+Pairing "Device Updates"
 * and "Node Management"). Each edit replaces the live value immediately, then persists it on the
 * injected scope; persistence failures are reported, never swallowed.
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
    private val onKnownRegionsChanged: (SessionToken, List<String>) -> Unit = { _, _ -> },
    private val clearDefaultFloodScope: suspend () -> Unit = {},
) {
    val connectedDevice: DeviceDTO? get() = access.connectedDevice

    fun updateDevice(info: SelfInfo, appliedRadioPresetID: String?): Job? {
        val device = access.connectedDevice ?: return null
        var updated = device.updating(info)
        if (appliedRadioPresetID != null) {
            updated = updated.copy(appliedRadioPresetID = appliedRadioPresetID)
        } else if (!device.clientRepeat) {
            // Drop a stale catalog id only when live RF no longer matches it; Repeat Mode keeps it.
            val matches = presets.matchingPresetIds(updated.frequency, updated.bandwidth, updated.spreadingFactor, updated.codingRate)
            val current = updated.appliedRadioPresetID
            if (current != null && current !in matches) updated = updated.copy(appliedRadioPresetID = null)
        }
        return replaceAndSave(updated, "updateDevice")
    }

    fun updateDevice(device: DeviceDTO) {
        val previous = access.connectedDevice
        val token = access.replaceConnectedDevice(device) ?: return
        if (previous?.radioId == device.radioId && previous.knownRegions != device.knownRegions) {
            onKnownRegionsChanged(token, device.knownRegions.toList())
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
        val device = access.connectedDevice ?: return null
        if (region in device.knownRegions) return null
        val updated = device.copy(knownRegions = (device.knownRegions + region).snapshot())
        updateDevice(updated)
        return scope.launch {
            report("addKnownRegion") { devices.addDeviceKnownRegion(updated.radioId, region) }
        }
    }

    fun removeKnownRegion(region: String): Job? {
        val device = access.connectedDevice ?: return null
        val wasDefault = device.defaultFloodScopeName == region
        val updated = device.copy(
            knownRegions = device.knownRegions.filterNot { it == region }.snapshot(),
            defaultFloodScopeName = if (wasDefault) null else device.defaultFloodScopeName,
        )
        updateDevice(updated)
        return scope.launch {
            report("removeKnownRegion") { devices.removeDeviceKnownRegion(updated.radioId, region) }
            if (wasDefault) report("clearDefaultFloodScope") { clearDefaultFloodScope() }
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

    private inline fun edit(operation: String, transform: (DeviceDTO) -> DeviceDTO): Job? {
        val device = access.connectedDevice ?: return null
        return replaceAndSave(transform(device), operation)
    }

    private fun replaceAndSave(updated: DeviceDTO, operation: String): Job {
        access.replaceConnectedDevice(updated)
        return scope.launch { report(operation) { devices.saveDevice(updated) } }
    }

    private suspend inline fun report(operation: String, action: () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { diagnostics.report("device.$operation", failure) }
    }
}
