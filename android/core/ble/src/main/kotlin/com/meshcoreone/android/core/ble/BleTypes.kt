// PortedFrom: MC1Services/Sources/MC1Services/Transport/BLEPhase.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Transport/BLEPhaseKind.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Transport/BLELinkDiagnostics.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Transport/BLEServiceUUID.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Transport/BluetoothAvailability.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Transport/DiscoveredDevice.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.ble

import java.util.UUID
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object NusUuid {
    val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val TX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    val RX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
}

enum class BlePhase(val sourceName: String) {
    Idle("idle"),
    WaitingForBluetooth("waitingForBluetooth"),
    Connecting("connecting"),
    DiscoveringServices("discoveringServices"),
    DiscoveringCharacteristics("discoveringCharacteristics"),
    SubscribingToNotifications("subscribingToNotifications"),
    DiscoveryComplete("discoveryComplete"),
    Connected("connected"),
    AutoReconnecting("autoReconnecting"),
    RestoringState("restoringState"),
    Disconnecting("disconnecting"),
    NegotiatingMtu("negotiatingMtu");

    val isActive: Boolean get() = this != Idle
    val isDiscoveryChain: Boolean
        get() = this in setOf(
            DiscoveringServices, DiscoveringCharacteristics,
            NegotiatingMtu, SubscribingToNotifications,
        )
}

enum class BluetoothAvailability { Ready, PoweredOff, Unauthorized, Unavailable }
enum class BondState { None, Bonding, Bonded }
enum class BleConnectMode { Initial, Reconnect }
enum class RejectedCallback { Connection, Operation, Attribute, Kind, Duplicate, Closed }

class BleDeviceHandle(address: String) {
    val address: String = address.uppercase(java.util.Locale.ROOT)

    init {
        if (!ADDRESS.matches(this.address)) throw BleTransportException(BleError.DeviceNotFound)
    }

    override fun equals(other: Any?): Boolean =
        other is BleDeviceHandle && address == other.address

    override fun hashCode(): Int = address.hashCode()
    override fun toString(): String = "BleDeviceHandle(redacted)"

    companion object {
        private val ADDRESS = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")
    }
}

data class DiscoveredBleDevice(val handle: BleDeviceHandle, val name: String?, val rssiDbm: Int)

data class BondRefresh(
    val radioId: UUID,
    val generation: Long,
    val verificationEpoch: Long,
    val verifiedAt: Instant,
)

data class BleTimeouts(
    val connection: Duration = 10.seconds,
    val discovery: Duration = 40.seconds,
    val reconnectDiscovery: Duration = 15.seconds,
    val write: Duration = 5.seconds,
) {
    init {
        require(listOf(connection, discovery, reconnectDiscovery, write).all { it.isFinite() && it.isPositive() }) {
            "BLE deadlines must be finite and positive"
        }
    }
}

data class BleConfiguration(
    val timeouts: BleTimeouts = BleTimeouts(),
    val requestedMtu: Int = 517,
    val minimumMtu: Int = 23,
    val requireBond: Boolean = false,
    val writePacing: Duration = Duration.ZERO,
) {
    init {
        require(requestedMtu in 23..517 && minimumMtu in 23..requestedMtu) { "Invalid ATT MTU configuration" }
        require(writePacing.isFinite() && !writePacing.isNegative()) { "Invalid BLE write pacing" }
    }
}

data class FirmwareFrameCapabilities(
    val maximumCommandBytes: Int,
    val writeWithoutResponse: Boolean = false,
    val pipelinedReads: Boolean = false,
    val evidence: String,
) {
    init {
        require(maximumCommandBytes in 1..512) { "Firmware command capacity must fit one ATT attribute" }
        require(!pipelinedReads || writeWithoutResponse) { "Pipelining requires verified write-command support" }
        require(evidence.isNotBlank()) { "Firmware capability evidence is required" }
    }
}

data class BleLinkDiagnostics(
    val availability: BluetoothAvailability,
    val phase: BlePhase,
    val generation: Long,
    val handle: BleDeviceHandle?,
    val bond: BondState?,
    val actualMtu: Int?,
    val maximumCommandBytes: Int?,
    val firmwareVerified: Boolean,
    val writeWithoutResponse: Boolean,
    val pipelinedReads: Boolean,
    val rejectedCallbacks: Long,
    val lastRejectedCallback: RejectedCallback?,
    val issue: BleError?,
)
