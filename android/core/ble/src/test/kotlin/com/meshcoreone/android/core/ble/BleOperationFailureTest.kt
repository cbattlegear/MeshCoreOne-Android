// PortedFrom: MC1Services/Tests/MC1ServicesTests/Transport/BLEStateMachineRestorationAndTeardownTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Transport/BLEStateMachineDisconnectionMappingTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.meshcoreone.android.core.ble

import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BleOperationFailureTest(private val kind: GattOperationKind) {
    private suspend fun start(fixture: BleFixture) {
        if (kind in setOf(GattOperationKind.Write, GattOperationKind.Rssi)) fixture.transport.connect()
        fixture.pause = kind
    }

    private suspend fun runOperation(fixture: BleFixture) {
        if (kind == GattOperationKind.Rssi) fixture.transport.readRssi()
        else if (kind == GattOperationKind.Write) fixture.transport.send(Bytes.of(0x16, 0x03))
        else fixture.transport.connect()
    }

    @Test fun `cancellation closes the active operation once and joins its timer`() = runTest {
        val fixture = BleFixture()
        start(fixture)
        val operation = async { runOperation(fixture) }
        runCurrent()
        assertEquals(kind, fixture.pending?.kind)
        operation.cancelAndJoin()
        assertTrue(operation.isCancelled)
        assertEquals(1, fixture.connection.closeCalls.values.size)
        assertEquals(0, fixture.clock.sleeperCount)
        assertFalse(fixture.transport.isConnected())
        fixture.complete()
        assertFalse(fixture.transport.isConnected())
        assertEquals(1, fixture.connection.closeCalls.values.size)
    }

    @Test fun `late callback after timeout cannot satisfy a replacement generation`() = runTest {
        val fixture = BleFixture()
        start(fixture)
        val operation = async { assertFailsWith<BleTransportException> { runOperation(fixture) } }
        runCurrent()
        val old = fixture.connection
        val late = requireNotNull(fixture.pending)
        fixture.clock.advanceBy(when (kind) {
            GattOperationKind.Connect -> 10.seconds
            GattOperationKind.Write, GattOperationKind.Rssi -> 5.seconds
            else -> 40.seconds
        })
        runCurrent()
        operation.await()
        fixture.pause = null
        fixture.transport.connect()
        val newGeneration = fixture.transport.diagnostics.value.generation
        fixture.complete(old, late)
        assertTrue(fixture.transport.isConnected())
        assertEquals(newGeneration, fixture.transport.diagnostics.value.generation)
        assertEquals(1, old.closeCalls.values.size)
        assertTrue(fixture.transport.diagnostics.value.rejectedCallbacks > 0)
        fixture.transport.disconnect()
    }

    @Test fun `wrong generation operation and reply kind cannot complete the current waiter`() = runTest {
        val fixture = BleFixture()
        start(fixture)
        val operation = async { runOperation(fixture) }
        runCurrent()
        val pending = requireNotNull(fixture.pending)
        val wrongReply = if (kind == GattOperationKind.Write) GattReply.Subscribed else GattReply.Written
        fixture.connection.reply(pending, wrongReply)
        fixture.connection.reply(pending, wrongReply, pending.key.copy(generation = pending.key.generation - 1))
        fixture.connection.reply(pending, wrongReply, pending.key.copy(sequence = pending.key.sequence + 1))
        runCurrent()
        assertFalse(operation.isCompleted)
        assertEquals(3L, fixture.transport.diagnostics.value.rejectedCallbacks)
        fixture.pause = null
        fixture.complete()
        runCurrent()
        operation.await()
        assertTrue(fixture.transport.isConnected())
        fixture.transport.disconnect()
    }

    @Test fun `power off during each operation terminates it with explicit recovery`() = runTest {
        val fixture = BleFixture()
        start(fixture)
        val result = async { assertFailsWith<BleTransportException> { runOperation(fixture) } }
        runCurrent()
        fixture.connection.events.onUnavailable(fixture.connection, BluetoothAvailability.PoweredOff)
        runCurrent()
        val failure = result.await()
        assertEquals(BleError.BluetoothPoweredOff, failure.error)
        assertEquals(BleRecovery.EnableBluetooth, failure.recovery)
        assertEquals(1, fixture.connection.closeCalls.values.size)
        assertEquals(0, fixture.clock.sleeperCount)
    }

    @Test fun `permission revocation during each operation is not a timeout or fake success`() = runTest {
        val fixture = BleFixture()
        start(fixture)
        val result = async { assertFailsWith<BleTransportException> { runOperation(fixture) } }
        runCurrent()
        fixture.connection.events.onUnavailable(fixture.connection, BluetoothAvailability.Unauthorized)
        runCurrent()
        assertEquals(BleError.BluetoothUnauthorized, result.await().error)
        assertEquals(1, fixture.connection.closeCalls.values.size)
        assertEquals(0, fixture.clock.sleeperCount)
    }

    @Test fun `disconnect and duplicate terminal callbacks cannot close twice`() = runTest {
        val fixture = BleFixture()
        start(fixture)
        val result = async { assertFailsWith<BleTransportException> { runOperation(fixture) } }
        runCurrent()
        fixture.transport.disconnect()
        fixture.connection.events.onDisconnected(fixture.connection, 133)
        fixture.connection.events.onUnavailable(fixture.connection, BluetoothAvailability.PoweredOff)
        runCurrent()
        assertEquals(BleError.NotConnected, result.await().error)
        assertEquals(1, fixture.connection.closeCalls.values.size)
        assertEquals(0, fixture.clock.sleeperCount)
    }

    @Test fun `matching immediate callbacks register before triggering and leave no timer`() = runTest {
        val fixture = BleFixture()
        if (kind == GattOperationKind.Rssi) {
            fixture.transport.connect()
            fixture.transport.readRssi()
        } else if (kind == GattOperationKind.Write) {
            fixture.transport.connect()
            fixture.transport.send(Bytes.of(0x16, 0x03))
        } else fixture.transport.connect()
        assertTrue(fixture.connection.operations.values.any { it.kind == kind })
        assertEquals(0, fixture.clock.sleeperCount)
        fixture.clock.advanceBy(40_001.milliseconds)
        runCurrent()
        assertTrue(fixture.transport.isConnected())
        fixture.transport.disconnect()
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun operations(): List<Array<Any>> = GattOperationKind.entries.map { arrayOf<Any>(it) }
    }
}
