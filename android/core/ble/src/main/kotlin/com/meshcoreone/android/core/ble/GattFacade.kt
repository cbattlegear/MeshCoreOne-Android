// PortedFrom: MC1Services/Sources/MC1Services/Transport/BLEStateMachineProtocol.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.ble

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Deferred

enum class GattProperty { Write, WriteWithoutResponse, Notify }
enum class GattOperationKind { Connect, DiscoverServices, Mtu, Subscribe, Write, Rssi }
enum class GattWriteMode { WithResponse, WithoutResponse }

data class GattOperationKey(val generation: Long, val sequence: Long)

class GattDescriptor(val uuid: UUID)

class GattCharacteristic(
    val uuid: UUID,
    properties: Set<GattProperty>,
    descriptors: List<GattDescriptor>,
) {
    val properties: Set<GattProperty> = Collections.unmodifiableSet(properties.toSet())
    val descriptors: List<GattDescriptor> = Collections.unmodifiableList(descriptors.toList())
}

class GattService(val uuid: UUID, characteristics: List<GattCharacteristic>) {
    val characteristics: List<GattCharacteristic> = Collections.unmodifiableList(characteristics.toList())
}

sealed interface GattOperation {
    val key: GattOperationKey
    val kind: GattOperationKind

    data class Connect(override val key: GattOperationKey, val requireBond: Boolean) : GattOperation {
        override val kind = GattOperationKind.Connect
    }

    data class DiscoverServices(override val key: GattOperationKey) : GattOperation {
        override val kind = GattOperationKind.DiscoverServices
    }

    data class Mtu(override val key: GattOperationKey, val requested: Int) : GattOperation {
        override val kind = GattOperationKind.Mtu
    }

    data class Subscribe(
        override val key: GattOperationKey,
        val characteristic: GattCharacteristic,
        val cccd: GattDescriptor,
    ) : GattOperation {
        override val kind = GattOperationKind.Subscribe
    }

    data class Write(
        override val key: GattOperationKey,
        val characteristic: GattCharacteristic,
        val data: Bytes,
        val mode: GattWriteMode,
    ) : GattOperation {
        override val kind = GattOperationKind.Write
    }

    data class Rssi(override val key: GattOperationKey) : GattOperation {
        override val kind = GattOperationKind.Rssi
    }
}

sealed interface GattReply {
    val kind: GattOperationKind

    data class Connected(val bond: BondState) : GattReply {
        override val kind = GattOperationKind.Connect
    }

    class Services(services: List<GattService>) : GattReply {
        val services: List<GattService> = Collections.unmodifiableList(services.toList())
        override val kind = GattOperationKind.DiscoverServices
    }

    data class Mtu(val actual: Int) : GattReply {
        override val kind = GattOperationKind.Mtu
    }

    data object Subscribed : GattReply {
        override val kind = GattOperationKind.Subscribe
    }

    data object Written : GattReply {
        override val kind = GattOperationKind.Write
    }

    data class Rssi(val dbm: Int, val status: Int) : GattReply {
        override val kind = GattOperationKind.Rssi
    }
}

interface GattEvents {
    fun onReply(connection: GattConnection, key: GattOperationKey, reply: GattReply)
    fun onFailure(connection: GattConnection, key: GattOperationKey, failure: BleTransportException)
    fun onNotification(connection: GattConnection, characteristic: GattCharacteristic, data: Bytes)
    fun onDisconnected(connection: GattConnection, status: Int)
    fun onUnavailable(connection: GattConnection, availability: BluetoothAvailability)
    fun onBondChanged(connection: GattConnection, bond: BondState)
    fun onLinkFailure(connection: GattConnection, failure: BleTransportException)
    fun onRejectedCallback(connection: GattConnection, reason: RejectedCallback)
}

interface GattConnection {
    fun submit(operation: GattOperation)

    // Marks the handle closed immediately; completion means all owned platform cleanup has run.
    fun close(): Deferred<Unit>
}

interface GattFacade {
    val handle: BleDeviceHandle
    fun availability(): BluetoothAvailability
    fun create(events: GattEvents): GattConnection
}
