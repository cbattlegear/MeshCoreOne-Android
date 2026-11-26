// PortedFrom: MC1Services/Tests/MC1ServicesTests/Mocks/MockBLEStateMachine.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Mocks/MockMeshTransport.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/Helpers/MockMeshTransport.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.ble

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.testing.RecordedCalls
import com.meshcoreone.android.core.testing.TestClock
import com.meshcoreone.android.core.testing.UnscriptedCallException
import java.util.UUID
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

internal class FakeGattFacade : GattFacade {
    override val handle = BleDeviceHandle("02:00:00:00:00:01")
    var available = BluetoothAvailability.Ready
    val connections = mutableListOf<FakeGattConnection>()
    var onCreate: (FakeGattConnection) -> Unit = { throw UnscriptedCallException(connections.size) }
    override fun availability(): BluetoothAvailability = available

    override fun create(events: GattEvents): GattConnection =
        FakeGattConnection(events).also {
            connections.add(it)
            onCreate(it)
        }
}

internal class FakeGattConnection(val events: GattEvents) : GattConnection {
    val operations = RecordedCalls<GattOperation>()
    val closeCalls = RecordedCalls<Unit>()
    var closeReceipt = CompletableDeferred(Unit)
    var onClose: (() -> Unit)? = null
    var onSubmit: (GattOperation) -> Unit = { throw UnscriptedCallException(operations.values.size) }

    override fun submit(operation: GattOperation) {
        operations.record(operation)
        onSubmit(operation)
    }

    override fun close(): Deferred<Unit> {
        closeCalls.record(Unit)
        onClose?.invoke()
        return closeReceipt
    }

    fun reply(operation: GattOperation, reply: GattReply, key: GattOperationKey = operation.key) {
        events.onReply(this, key, reply)
    }

    fun fail(operation: GattOperation, failure: BleTransportException) {
        events.onFailure(this, operation.key, failure)
    }

    fun notify(characteristic: GattCharacteristic, data: Bytes) {
        events.onNotification(this, characteristic, data)
    }
}

internal class BleFixture(configuration: BleConfiguration = BleConfiguration()) {
    val clock = TestClock()
    val facade = FakeGattFacade()
    val cccd = GattDescriptor(UUID.fromString("00002902-0000-1000-8000-00805F9B34FB"))
    val tx = GattCharacteristic(
        UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
        setOf(GattProperty.Write, GattProperty.WriteWithoutResponse), emptyList(),
    )
    val rx = GattCharacteristic(
        UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"), setOf(GattProperty.Notify), listOf(cccd),
    )
    var services = listOf(GattService(UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"), listOf(tx, rx)))
    var mtu = 517
    var bond = BondState.Bonded
    var rssi = -50
    var rssiStatus = 0
    var wallNow: Instant = Instant.ofEpochSecond(1_791_091_200L)
    var pause: GattOperationKind? = null
    var pending: GattOperation? = null
    val transport = BleTransport(facade, configuration, object : BleClock {
        override val now: Duration get() = clock.now
        override suspend fun sleepUntil(deadline: Duration) = clock.sleepUntil(deadline)
    }, object : Clock() {
        override fun instant(): Instant = wallNow
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(wallNow, zone)
    })
    val connection: FakeGattConnection get() = facade.connections.last()

    init {
        facade.onCreate = { connection ->
            connection.onSubmit = { operation ->
                if (operation.kind == pause) pending = operation else complete(connection, operation)
            }
        }
    }

    fun complete(connection: FakeGattConnection = this.connection, operation: GattOperation = requireNotNull(pending)) {
        val reply = when (operation) {
            is GattOperation.Connect -> GattReply.Connected(bond)
            is GattOperation.DiscoverServices -> GattReply.Services(services)
            is GattOperation.Mtu -> GattReply.Mtu(mtu)
            is GattOperation.Subscribe -> GattReply.Subscribed
            is GattOperation.Write -> GattReply.Written
            is GattOperation.Rssi -> GattReply.Rssi(rssi, rssiStatus)
        }
        connection.reply(operation, reply)
    }

    fun verifyFirmware(maximum: Int = 512, commands: Boolean = false, pipelining: Boolean = false) {
        transport.updateFirmwareCapabilities(
            transport.diagnostics.value.generation,
            FirmwareFrameCapabilities(maximum, commands, pipelining, "independent test peer capability"),
        )
    }
}
