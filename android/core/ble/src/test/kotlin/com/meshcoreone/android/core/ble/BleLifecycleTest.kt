// PortedFrom: MC1Services/Tests/MC1ServicesTests/Transport/BLEStateMachineTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Transport/BLEStateMachineRestorationAndTeardownTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Transport/BLEStateMachineBondSuspectRecoveryTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.meshcoreone.android.core.ble

import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BleLifecycleTest {
    @Test fun `construction is idempotent activation without a physical connection`() {
        val fixture = BleFixture()
        repeat(3) { assertEquals(BlePhase.Idle, fixture.transport.diagnostics.value.phase) }
        assertTrue(fixture.facade.connections.isEmpty())
    }

    @Test fun `connect discovers MTU and subscribes in exact whole operation order`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        assertEquals(
            listOf(GattOperationKind.Connect, GattOperationKind.DiscoverServices, GattOperationKind.Mtu, GattOperationKind.Subscribe),
            fixture.connection.operations.values.map { it.kind },
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), fixture.connection.operations.values.map { it.key.sequence })
        assertTrue(fixture.connection.operations.values.all { it.key.generation == 1L })
        assertTrue(fixture.transport.isConnected())
        assertEquals(517, fixture.transport.diagnostics.value.actualMtu)
        assertEquals(20, fixture.transport.diagnostics.value.maximumCommandBytes)
        assertFalse(fixture.transport.supportsWriteWithoutResponse())
        assertFalse(fixture.transport.supportsPipelinedReads())
        assertEquals(0, fixture.clock.sleeperCount)
        fixture.transport.disconnect()
    }

    @Test fun `connecting twice retains the same handle generation and flow`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        val stream = fixture.transport.receivedData()
        fixture.transport.connect()
        assertEquals(1, fixture.facade.connections.size)
        assertEquals(1L, fixture.transport.diagnostics.value.generation)
        assertSame(stream, fixture.transport.receivedData())
        fixture.transport.disconnect()
    }

    @Test fun `connection default expires at exactly ten seconds`() = runTest {
        val fixture = BleFixture()
        fixture.pause = GattOperationKind.Connect
        val result = async { assertFailsWith<BleTransportException> { fixture.transport.connect() } }
        runCurrent()
        fixture.clock.advanceBy(9_999.milliseconds)
        runCurrent()
        assertFalse(result.isCompleted)
        fixture.clock.advanceBy(1.milliseconds)
        runCurrent()
        assertEquals(BleError.ConnectionTimeout, result.await().error)
        assertEquals(1, fixture.connection.closeCalls.values.size)
        assertEquals(0, fixture.clock.sleeperCount)
        assertFalse(fixture.transport.isConnected())
    }

    @Test fun `discovery timeout is forty seconds for the whole chain not each request`() = runTest {
        val fixture = BleFixture()
        fixture.pause = GattOperationKind.DiscoverServices
        val result = async { assertFailsWith<BleTransportException> { fixture.transport.connect() } }
        runCurrent()
        fixture.clock.advanceBy(20.seconds)
        fixture.pause = GattOperationKind.Mtu
        fixture.complete()
        runCurrent()
        assertEquals(GattOperationKind.Mtu, fixture.pending?.kind)
        fixture.clock.advanceBy(19_999.milliseconds)
        runCurrent()
        assertFalse(result.isCompleted)
        fixture.clock.advanceBy(1.milliseconds)
        runCurrent()
        val failure = result.await()
        assertEquals(BleError.ConnectionTimeout, failure.error)
        assertEquals(GattOperationKind.Mtu, failure.operation)
        assertEquals(1, fixture.connection.closeCalls.values.size)
        assertEquals(0, fixture.clock.sleeperCount)
    }

    @Test fun `explicit reconnect uses fifteen second discovery without an implicit loop`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        fixture.transport.disconnect()
        fixture.pause = GattOperationKind.Subscribe
        val result = async { assertFailsWith<BleTransportException> { fixture.transport.connect(BleConnectMode.Reconnect) } }
        runCurrent()
        fixture.clock.advanceBy(14_999.milliseconds)
        runCurrent()
        assertFalse(result.isCompleted)
        fixture.clock.advanceBy(1.milliseconds)
        runCurrent()
        assertEquals(BleError.ConnectionTimeout, result.await().error)
        assertEquals(2, fixture.facade.connections.size)
        assertEquals(2L, fixture.transport.diagnostics.value.generation)
        fixture.clock.advanceBy((24 * 60 * 60).seconds)
        runCurrent()
        assertEquals(2, fixture.facade.connections.size)
        assertEquals(1, fixture.connection.closeCalls.values.size)
    }

    @Test fun `receiver and readiness operations end on explicit idempotent disconnect`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        fixture.pause = GattOperationKind.Write
        val sender = async { assertFailsWith<BleTransportException> { fixture.transport.sendWithoutResponse(Bytes.of(1)) } }
        runCurrent()
        repeat(3) { fixture.transport.disconnect() }
        assertEquals(BleError.NotConnected, sender.await().error)
        assertEquals(1, fixture.connection.closeCalls.values.size)
        assertEquals(0, fixture.clock.sleeperCount)
    }

    @Test fun `cancellation before connect creates no GATT and propagates cancellation`() = runTest {
        val fixture = BleFixture()
        val connect = async { fixture.transport.connect() }
        connect.cancel()
        connect.cancelAndJoin()
        assertTrue(connect.isCancelled)
        assertTrue(fixture.facade.connections.isEmpty())
        assertEquals(0L, fixture.transport.diagnostics.value.generation)
    }

    @Test fun `disconnect invalidates a connect queued behind the old attempt`() = runTest {
        val fixture = BleFixture()
        fixture.pause = GattOperationKind.Connect
        val first = async { assertFailsWith<BleTransportException> { fixture.transport.connect() } }
        runCurrent()
        val queued = async { assertFailsWith<BleTransportException> { fixture.transport.connect() } }
        runCurrent()
        fixture.transport.disconnect()
        runCurrent()
        assertEquals(BleError.NotConnected, first.await().error)
        assertEquals(BleError.NotConnected, queued.await().error)
        assertEquals(1, fixture.facade.connections.size)
    }

    @Test fun `close completion is awaited before a new generation opens`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        fixture.connection.closeReceipt = CompletableDeferred()
        val old = fixture.connection
        val disconnect = async { fixture.transport.disconnect() }
        runCurrent()
        assertFalse(disconnect.isCompleted)
        val reconnect = async { fixture.transport.connect() }
        runCurrent()
        assertFalse(reconnect.isCompleted)
        assertEquals(1, fixture.facade.connections.size)
        old.closeReceipt.complete(Unit)
        runCurrent()
        disconnect.await()
        reconnect.await()
        assertEquals(2, fixture.facade.connections.size)
        assertNotEquals(old, fixture.connection)
        fixture.transport.disconnect()
    }

    @Test fun `disconnect cancellation still closes the handle then propagates`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        fixture.connection.closeReceipt = CompletableDeferred()
        val disconnect = async { fixture.transport.disconnect() }
        runCurrent()
        disconnect.cancel()
        assertFalse(disconnect.isCompleted)
        fixture.connection.closeReceipt.complete(Unit)
        runCurrent()
        assertFailsWith<CancellationException> { disconnect.await() }
        assertEquals(1, fixture.connection.closeCalls.values.size)
        assertFalse(fixture.transport.isConnected())
    }

    @Test fun `close failures are surfaced and not replaced with connection success`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        val cleanup = BleTransportException(BleError.CleanupFailed("test.close"))
        fixture.connection.closeReceipt = CompletableDeferred<Unit>().apply { completeExceptionally(cleanup) }
        assertEquals(cleanup.error, assertFailsWith<BleTransportException> { fixture.transport.disconnect() }.error)
        assertEquals(cleanup.error, fixture.transport.diagnostics.value.issue)
        assertEquals(cleanup.error, assertFailsWith<BleTransportException> { fixture.transport.connect() }.error)
        assertEquals(1, fixture.facade.connections.size)
        assertEquals(1, fixture.connection.closeCalls.values.size)
    }

    @Test fun `terminal operation failure retains a cleanup failure as suppressed metadata`() = runTest {
        val fixture = BleFixture()
        fixture.pause = GattOperationKind.Connect
        val result = async { assertFailsWith<BleTransportException> { fixture.transport.connect() } }
        runCurrent()
        fixture.connection.closeReceipt = CompletableDeferred<Unit>().apply {
            completeExceptionally(BleTransportException(BleError.CleanupFailed("test.close")))
        }
        fixture.connection.fail(requireNotNull(fixture.pending), BleTransportException(BleError.AuthenticationFailed))
        runCurrent()
        val failure = result.await()
        assertEquals(BleError.AuthenticationFailed, failure.error)
        assertTrue(failure.suppressed.any { it is BleTransportException && it.error is BleError.CleanupFailed })
        assertEquals(1, fixture.connection.closeCalls.values.size)
    }

    @Test fun `required bond cannot publish an unbonded connection`() = runTest {
        val fixture = BleFixture(BleConfiguration(requireBond = true))
        fixture.bond = BondState.None
        val failure = assertFailsWith<BleTransportException> { fixture.transport.connect() }
        assertEquals(BleError.BondRequired(BondState.None), failure.error)
        assertEquals(BleRecovery.PairInSystem, failure.recovery)
        assertEquals(listOf(GattOperationKind.Connect), fixture.connection.operations.values.map { it.kind })
        assertFalse(fixture.transport.isConnected())
    }

    @Test fun `bond loss is definitive even after a connected verified firmware session`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        fixture.verifyFirmware(commands = true, pipelining = true)
        fixture.connection.events.onBondChanged(fixture.connection, BondState.None)
        assertEquals(BleError.AuthenticationFailed, fixture.transport.diagnostics.value.issue)
        assertFalse(fixture.transport.isConnected())
        assertEquals(1, fixture.connection.closeCalls.values.size)
        assertFalse(fixture.transport.supportsWriteWithoutResponse())
        assertFalse(fixture.transport.supportsPipelinedReads())
    }

    @Test fun `bonding failure during setup cannot leak a continuation`() = runTest {
        val fixture = BleFixture()
        fixture.pause = GattOperationKind.DiscoverServices
        val result = async { assertFailsWith<BleTransportException> { fixture.transport.connect() } }
        runCurrent()
        fixture.connection.events.onBondChanged(fixture.connection, BondState.Bonding)
        fixture.connection.events.onBondChanged(fixture.connection, BondState.None)
        runCurrent()
        assertEquals(BleError.AuthenticationFailed, result.await().error)
        assertEquals(1, fixture.connection.closeCalls.values.size)
        assertEquals(0, fixture.clock.sleeperCount)
    }

    @Test fun `clean disconnect is not mislabeled authentication or other app ownership`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        fixture.connection.events.onDisconnected(fixture.connection, 0)
        assertNull(fixture.transport.diagnostics.value.issue)
        assertFalse(fixture.transport.isConnected())
        assertEquals(1, fixture.connection.closeCalls.values.size)
    }

    @Test fun `physical close never runs while the callback state monitor is held`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        var callbackCompletedInsideClose = false
        fixture.connection.onClose = {
            val callback = thread {
                fixture.connection.events.onRejectedCallback(fixture.connection, RejectedCallback.Closed)
            }
            callback.join(1_000)
            callbackCompletedInsideClose = !callback.isAlive
        }
        fixture.transport.disconnect()
        assertTrue(callbackCompletedInsideClose)
        assertEquals(1, fixture.connection.closeCalls.values.size)
    }
}
