// AndroidOnly: WP-206 Device/preset fixtures for connected-device editor tests (frozen source preset rows for br, us-ca, ca).
package com.meshcoreone.android.core.connectivity.support

import com.meshcoreone.android.core.connectivity.device.ConnectedDeviceAccess
import com.meshcoreone.android.core.connectivity.device.RadioPresetMatcher
import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.core.runtime.ConnectionManager
import java.util.UUID
import kotlin.math.abs

internal data class PresetRow(val id: String, val frequencyMHz: Double, val bandwidthKHz: Double, val spreadingFactor: UByte, val codingRate: UByte)

/** Rows copied from the frozen `RadioPresets.all` (WP-211 owns the catalog); matching keeps its tolerances. */
internal val PRESET_ROWS = listOf(
    PresetRow("us-ca", 910.525, 62.5, 7u, 5u),
    PresetRow("ca", 910.525, 62.5, 7u, 5u),
    PresetRow("br", 923.125, 62.5, 8u, 8u),
)

internal val PRESET_MATCHER = RadioPresetMatcher { frequencyKHz, bandwidthKHz, spreadingFactor, codingRate ->
    val freqMHz = frequencyKHz.toDouble() / 1000.0
    val bwKHz = bandwidthKHz.toDouble() / 1000.0
    PRESET_ROWS.filter {
        abs(it.frequencyMHz - freqMHz) < 0.1 && abs(it.bandwidthKHz - bwKHz) < 1.0 &&
            it.spreadingFactor == spreadingFactor && it.codingRate == codingRate
    }.mapTo(linkedSetOf()) { it.id }
}

internal fun testDevice(
    id: UUID = UUID.randomUUID(),
    radioId: RadioId = RadioId(id),
    nodeName: String = "TestDevice",
    frequency: UInt = 915_000u,
    bandwidth: UInt = 250_000u,
    spreadingFactor: UByte = 10u,
    codingRate: UByte = 5u,
): DeviceDTO = DeviceDTO(id, radioId, Bytes(ByteArray(32) { 0x01 }), nodeName, frequency = frequency,
    bandwidth = bandwidth, spreadingFactor = spreadingFactor, codingRate = codingRate)

internal fun selfInfo(frequencyMHz: Double, bandwidthKHz: Double, spreadingFactor: UByte, codingRate: UByte): SelfInfo =
    SelfInfo(0u, 20, 20, Bytes(ByteArray(32) { 0x01 }), 0.0, 0.0, 2u, 0u, 0u, 0u, 2u, false,
        frequencyMHz, bandwidthKHz, spreadingFactor, codingRate, "TestNode")

internal fun selfInfo(preset: PresetRow): SelfInfo =
    selfInfo(preset.frequencyMHz, preset.bandwidthKHz, preset.spreadingFactor, preset.codingRate)

internal class InMemoryDeviceAccess(initial: DeviceDTO? = null) : ConnectedDeviceAccess {
    private val epoch = ProcessEpoch(UUID.randomUUID())
    override var connectedDevice: DeviceDTO? = initial
        private set
    override fun replaceConnectedDevice(device: DeviceDTO): SessionToken {
        connectedDevice = device
        return SessionToken(epoch, Generation(0), device.radioId)
    }
}

/** Read-through to the runtime; replacement is the missing runtime seam (coordinator note C-03). */
internal class RuntimeDeviceAccess(private val manager: ConnectionManager) : ConnectedDeviceAccess {
    override val connectedDevice: DeviceDTO? get() = manager.connectedDevice
    override fun replaceConnectedDevice(device: DeviceDTO) =
        throw UnsupportedOperationException("core:runtime exposes no connected-device replacement seam")
}
