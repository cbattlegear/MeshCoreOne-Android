// PortedFrom: MC1Services/Tests/MC1ServicesTests/Mocks/MockAccessorySetupKitService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/Helpers/MockAccessorySetupKitService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/Helpers/ASAccessory+Testing.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.connectivity.support

import com.meshcoreone.android.core.connectivity.pairing.CompanionAssociation
import com.meshcoreone.android.core.connectivity.pairing.CompanionSetupDelegate
import com.meshcoreone.android.core.connectivity.pairing.CompanionSetupError
import com.meshcoreone.android.core.connectivity.pairing.CompanionSetupServicing
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred

/** Test fixture for an association with a given endpoint id (`ASAccessory(bluetoothIdentifier:displayName:)`). */
internal fun association(id: UUID, name: String, associationId: Int = id.hashCode() and 0x7fffffff): CompanionAssociation =
    CompanionAssociation(associationId, id, syntheticAddress(id), name)

internal fun syntheticAddress(id: UUID): String {
    val bits = id.leastSignificantBits
    return (0 until 6).joinToString(":") { index ->
        val octet = ((bits ushr (8 * (5 - index))) and 0xff).toInt()
        // Force a static-random first octet so the synthetic address is never rotating.
        "%02X".format(if (index == 0) octet or 0xC0 else octet)
    }
}

/**
 * In-memory association picker. `showPicker` returns the configured result immediately or after
 * the test releases [pickerGate]; associations are listed only after `activateSession`.
 */
internal class MockCompanionSetupService : CompanionSetupServicing {
    private var stored = listOf<CompanionAssociation>()
    private var activated = false

    override val pairedAccessories: List<CompanionAssociation> get() = if (activated) stored else emptyList()
    override var isSessionActive: Boolean = true
    override var delegate: CompanionSetupDelegate? = null

    var removeAccessoryCallCount = 0; private set
    var lastRemovedDeviceId: UUID? = null; private set
    var renameAccessoryCallCount = 0; private set
    var activateSessionCallCount = 0; private set
    var showPickerCallCount = 0; private set
    var invalidateSessionCallCount = 0; private set

    private var pickerResult: Result<UUID> = Result.failure(CompanionSetupError.SessionNotActive())
    var pickerGate: CompletableDeferred<Unit>? = null
    var pickerEntered: CompletableDeferred<Unit>? = null
    var removeAccessoryError: Exception? = null

    fun setPickerResult(result: Result<UUID>) { pickerResult = result }
    fun setPairedAccessories(accessories: List<CompanionAssociation>) { stored = accessories }
    val storedAccessories: List<CompanionAssociation> get() = stored

    override suspend fun activateSession() { activateSessionCallCount++; activated = true }

    override suspend fun showPicker(): UUID {
        showPickerCallCount++
        pickerEntered?.complete(Unit)
        pickerGate?.await()
        return pickerResult.getOrThrow()
    }

    override suspend fun removeAccessory(accessory: CompanionAssociation) {
        removeAccessoryCallCount++
        lastRemovedDeviceId = accessory.deviceId
        removeAccessoryError?.let { throw it }
        stored = stored.filterNot { it.deviceId == accessory.deviceId }
    }

    override suspend fun renameAccessory(accessory: CompanionAssociation) { renameAccessoryCallCount++ }

    override fun accessory(deviceId: UUID): CompanionAssociation? = stored.firstOrNull { it.deviceId == deviceId }

    override fun invalidateSession() { invalidateSessionCallCount++; activated = false }
}
