// PortedFrom: MC1Services/Sources/MC1Services/Services/AccessorySetupKitDiscoveryCriteria.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/AccessorySetupKitLogFormatter.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.connectivity.pairing

import com.meshcoreone.android.core.ble.NusUuid
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration

/**
 * Companion association discovery criteria. The CDM chooser filters on the Nordic UART service
 * only; "filtered discovery" means the chooser lists each match by its advertised BLE name,
 * which CompanionDeviceManager does natively for BluetoothLeDeviceFilter matches.
 */
object CompanionDiscoveryCriteria {
    const val usesFilteredDiscovery: Boolean = true
    val bluetoothServiceUUID: String = NusUuid.SERVICE.toString().uppercase(Locale.ROOT)
    val bluetoothServiceUuid: UUID = NusUuid.SERVICE

    /** Static fallback label; localized presentation belongs to the UI host. */
    const val DEFAULT_ACCESSORY_NAME: String = "MeshCore Device"
}

object CompanionLogFormatter {
    fun selectionMessage(accessoryName: String, deviceId: UUID?, elapsed: Duration?): String =
        "[CDM] Selected accessory '$accessoryName' (id: ${deviceId?.toString()?.uppercase(Locale.ROOT) ?: "none"}) " +
            "after ${durationSummary(elapsed)}"

    fun dismissalMessage(outcome: String, pairedCount: Int, elapsed: Duration?, filteredDiscovery: Boolean): String =
        "[CDM] Picker dismissed after ${durationSummary(elapsed)} (outcome: $outcome, " +
            "filteredDiscovery: $filteredDiscovery, pairedCount: $pairedCount)"

    private fun durationSummary(elapsed: Duration?): String {
        if (elapsed == null) return "unknown"
        return String.format(Locale.ROOT, "%.1fs", elapsed.inWholeMilliseconds / 1000.0)
    }
}
