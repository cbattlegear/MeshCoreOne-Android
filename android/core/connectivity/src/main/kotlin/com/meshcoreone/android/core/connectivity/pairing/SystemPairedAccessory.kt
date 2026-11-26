// PortedFrom: MC1/State/SystemPairedAccessory.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/State/SystemPairingSetupPrompt.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.connectivity.pairing

import java.util.UUID

/** A companion association that has no MeshCore One `Device` row. */
data class SystemPairedAccessory(val id: UUID, val name: String)

/** Forget prompt for companion associations that have no `Device` row; identity is per prompt. */
class SystemPairingSetupPrompt(
    val accessories: List<SystemPairedAccessory>,
    val id: UUID = UUID.randomUUID(),
) {
    override fun equals(other: Any?): Boolean =
        other is SystemPairingSetupPrompt && id == other.id && accessories == other.accessories
    override fun hashCode(): Int = 31 * id.hashCode() + accessories.hashCode()
}
