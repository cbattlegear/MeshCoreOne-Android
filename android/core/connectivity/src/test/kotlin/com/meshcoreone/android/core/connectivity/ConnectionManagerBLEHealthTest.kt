// PortedFrom: MC1Services/Tests/MC1ServicesTests/ConnectionManagerBLEHealthTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.connectivity

import com.meshcoreone.android.core.ble.BlePhase
import com.meshcoreone.android.core.connectivity.support.OriginalCase
import com.meshcoreone.android.core.connectivity.support.RuntimeHarness
import com.meshcoreone.android.core.connectivity.support.runtimeScenario
import com.meshcoreone.android.core.connectivity.support.settle
import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.runtime.ConnectionError
import com.meshcoreone.android.core.runtime.RuntimeDiagnostic
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Test

/**
 * The runtime's health ladder (WP-207 `checkBLEConnectionHealth`) driven through the WP-206
 * platform projection: link facts come from the production BleLinkInspector, registry facts from
 * the companion pairing service. `setTestState` seeding is replaced by real runtime transitions.
 */
class ConnectionManagerBLEHealthTest {
    /** Connected READY, then the device is dropped from the registry so a reconnect fails typed. */
    private suspend fun RuntimeHarness.readyThenUnregistered(): UUID {
        val id = connectReady()
        companion.setPairedAccessories(emptyList())
        return id
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::returns early when transport type is WiFi()")
    fun `returns early when transport type is WiFi`() = runtimeScenario {
        manager.connect(ConnectionTarget.WiFi("192.168.4.1", 5000u))
        settle()
        assertEquals(DeviceConnectionState.READY, manager.connectionState)
        last.persist(UUID.randomUUID(), com.meshcoreone.android.core.model.RadioId(UUID.randomUUID()), "Other")
        val linksBefore = links.size
        manager.checkBLEConnectionHealth()
        assertEquals(DeviceConnectionState.READY, manager.connectionState)
        assertEquals(linksBefore, links.size)
        assertTrue(stub.systemConnectedCalls.isEmpty())
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::returns early when shouldBeConnected is false()", "native-equivalent")
    fun `returns early when shouldBeConnected is false`() = runtimeScenario {
        // Runtime forbids an operational generation without connection intent; a fresh process with
        // a persisted last radio and no intent is the reachable equivalent.
        last.persist(UUID.randomUUID(), com.meshcoreone.android.core.model.RadioId(UUID.randomUUID()), "Radio")
        assertEquals(ConnectionIntent.None, manager.connectionIntent)
        manager.checkBLEConnectionHealth()
        assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
        assertTrue(links.isEmpty())
        assertTrue(stub.systemConnectedCalls.isEmpty())
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::returns early when no lastConnectedDeviceID()")
    fun `returns early when no lastConnectedDeviceID`() = runtimeScenario {
        val id = connectReady()
        last.clear(id)
        assertNull(lastConnectedDeviceId())
        stub.systemConnectedCalls.clear()
        manager.checkBLEConnectionHealth()
        assertEquals(DeviceConnectionState.READY, manager.connectionState)
        assertTrue(stub.systemConnectedCalls.isEmpty())
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::returns early when BLE is connected and app stack is healthy()")
    fun `returns early when BLE is connected and app stack is healthy`() = runtimeScenario {
        val id = connectReady()
        stub.connected = true; stub.connectedDeviceId = id
        val linksBefore = links.size
        manager.checkBLEConnectionHealth()
        settle()
        assertEquals(DeviceConnectionState.READY, manager.connectionState)
        assertEquals(linksBefore, links.size, "No rebuild generation")
        assertEquals(1, services.size)
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::skips reconnect during iOS auto-reconnect()", "platform-adaptation")
    fun `skips reconnect during an owner auto-reconnect`() = runtimeScenario {
        connectThenLose()
        stub.autoReconnecting = true
        manager.setConnectionState(DeviceConnectionState.CONNECTING)
        val linksBefore = links.size
        manager.checkBLEConnectionHealth()
        assertEquals(DeviceConnectionState.CONNECTING, manager.connectionState)
        assertEquals(linksBefore, links.size)
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::skips foreground reconnect while pairing is in progress()")
    fun `skips foreground reconnect while pairing is in progress`() = runtimeScenario {
        connectThenLose()
        manager.setPairingActivity(pairingInProgress = true, pairingFlowActive = false)
        val linksBefore = links.size
        manager.checkBLEConnectionHealth()
        assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
        assertEquals(linksBefore, links.size)
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::skips foreground reconnect while pairing flow is active()")
    fun `skips foreground reconnect while pairing flow is active`() = runtimeScenario {
        connectThenLose()
        manager.setPairingActivity(pairingInProgress = false, pairingFlowActive = true)
        stub.systemConnectedCalls.clear()
        manager.checkBLEConnectionHealth()
        assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
        assertTrue(stub.systemConnectedCalls.isEmpty())
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::skips reconnect while session rebuild is in progress()")
    fun `skips reconnect while session rebuild is in progress`() = runtimeScenario {
        val id = connectThenLose()
        // Hold a real health-driven session rebuild inside the radio connect.
        val gate = CompletableDeferred<Unit>()
        createRadio = { com.meshcoreone.android.core.connectivity.support.FakeRadio().also { it.beforeConnect = { gate.await() } } }
        stub.connected = true; stub.connectedDeviceId = id
        val first = scenario.scope.launch { manager.checkBLEConnectionHealth() }
        assertTrue(scenario.awaitCondition { manager.activeReconnectDeviceId == id })
        stub.connected = false; stub.connectedDeviceId = null
        val linksBefore = links.size
        manager.checkBLEConnectionHealth()
        assertEquals(linksBefore, links.size, "Second health check must not start another attempt")
        assertEquals(id, manager.activeReconnectDeviceId)
        gate.complete(Unit)
        first.join()
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::BLE connected with missing session and services rebuilds app stack()")
    fun `BLE connected with missing session and services rebuilds app stack`() = runtimeScenario {
        val id = connectThenLose()
        stub.connected = true; stub.connectedDeviceId = id
        val linksBefore = links.size
        manager.checkBLEConnectionHealth()
        settle()
        assertEquals(linksBefore + 1, links.size, "Exactly one rebuild generation")
        assertEquals(id, (links.last().target as ConnectionTarget.Bluetooth).deviceId)
        assertEquals(DeviceConnectionState.READY, manager.connectionState)
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::BLE connected ready state restarts missing event monitoring()", "native-equivalent")
    fun `BLE connected ready state restarts missing event monitoring`() = runtimeScenario {
        val id = connectReady()
        stub.connected = true; stub.connectedDeviceId = id
        val before = services.last().ensureListenersCalls
        manager.checkBLEConnectionHealth()
        // Listener re-arm is the RuntimeServices.ensureListeners contract (event monitor + auto-fetch).
        assertEquals(before + 1, services.last().ensureListenersCalls)
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::BLE connected with healthy listeners does not rebuild()")
    fun `BLE connected with healthy listeners does not rebuild`() = runtimeScenario {
        val id = connectReady()
        stub.connected = true; stub.connectedDeviceId = id
        val linksBefore = links.size
        manager.checkBLEConnectionHealth()
        manager.checkBLEConnectionHealth()
        settle()
        assertEquals(linksBefore, links.size)
        assertEquals(1, services.size)
        assertEquals(DeviceConnectionState.READY, manager.connectionState)
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::BLE connected skips duplicate recovery while session rebuild is active()")
    fun `BLE connected skips duplicate recovery while session rebuild is active`() = runtimeScenario {
        val id = connectThenLose()
        val gate = CompletableDeferred<Unit>()
        createRadio = { com.meshcoreone.android.core.connectivity.support.FakeRadio().also { it.beforeConnect = { gate.await() } } }
        stub.connected = true; stub.connectedDeviceId = id
        val first = scenario.scope.launch { manager.checkBLEConnectionHealth() }
        assertTrue(scenario.awaitCondition { manager.activeReconnectDeviceId == id })
        val linksBefore = links.size
        manager.checkBLEConnectionHealth()
        assertEquals(linksBefore, links.size)
        assertEquals(id, manager.activeReconnectDeviceId)
        gate.complete(Unit)
        first.join()
        settle()
        assertEquals(DeviceConnectionState.READY, manager.connectionState)
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::health check does not attempt adoption while BLE restoration is already in progress()")
    fun `health check does not attempt adoption while BLE restoration is already in progress`() = runtimeScenario {
        connectThenLose()
        stub.phase = BlePhase.RestoringState
        stub.systemConnected = { true }
        stub.adoptionSucceeds = true
        manager.checkBLEConnectionHealth()
        assertTrue(adoptions.isEmpty())
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::health check skips reconnection when Bluetooth is powered off()")
    fun `health check skips reconnection when Bluetooth is powered off`() = runtimeScenario {
        connectThenLose()
        stub.poweredOff = true
        val linksBefore = links.size
        manager.checkBLEConnectionHealth()
        assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
        assertEquals(linksBefore, links.size)
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::detects stale state when connectionState is .ready but BLE disconnected()")
    fun `detects stale state when connectionState is ready but BLE disconnected`() = runtimeScenario {
        readyThenUnregistered()
        assertEquals(DeviceConnectionState.READY, manager.connectionState)
        val lossesBefore = lossCount
        manager.checkBLEConnectionHealth()
        val reported = diagnostics.filterIsInstance<RuntimeDiagnostic.Failure>()
            .single { it.operation == "health.reconnect" }
        assertIs<ConnectionError.DeviceNotFound>(reported.cause)
        assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
        assertEquals(lossesBefore + 1, lossCount)
        assertTrue(manager.connectionIntent.wantsConnection)
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::detects stale state when connectionState is .connected but BLE disconnected()")
    fun `detects stale state when connectionState is connected but BLE disconnected`() = runtimeScenario {
        readyThenUnregistered()
        // A sync-in-progress generation reports CONNECTED transport before promotion.
        manager.setConnectionState(DeviceConnectionState.CONNECTED)
        assertEquals(DeviceConnectionState.CONNECTED, manager.connectionState)
        manager.checkBLEConnectionHealth()
        assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::detects stale state when connectionState is .syncing but BLE disconnected()")
    fun `detects stale state when connectionState is syncing but BLE disconnected`() = runtimeScenario {
        configureServices = { it.syncResult = com.meshcoreone.android.core.runtime.RuntimeSyncResult.Failed(IllegalStateException("sync")) }
        val id = UUID.randomUUID()
        register(id)
        manager.connect(target(id))
        settle()
        assertEquals(DeviceConnectionState.SYNCING, manager.connectionState)
        companion.setPairedAccessories(emptyList())
        manager.checkBLEConnectionHealth()
        assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::does not trigger cleanup when already disconnected()")
    fun `does not trigger cleanup when already disconnected`() = runtimeScenario {
        connectThenLose()
        companion.setPairedAccessories(emptyList())
        val lossesBefore = lossCount
        manager.checkBLEConnectionHealth()
        assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
        assertEquals(lossesBefore, lossCount, "No second connection-loss cleanup")
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::calls onConnectionLost when stale state detected()")
    fun `calls onConnectionLost when stale state detected`() = runtimeScenario {
        readyThenUnregistered()
        val lossesBefore = lossCount
        manager.checkBLEConnectionHealth()
        assertEquals(lossesBefore + 1, lossCount)
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::preserves wantsConnection intent after resyncFailed disconnect()")
    fun `preserves wantsConnection intent after resyncFailed disconnect`() = runtimeScenario {
        readyThenUnregistered()
        manager.disconnect(RuntimeDisconnectReason.RESYNC_FAILED)
        assertTrue(manager.connectionIntent.wantsConnection)
        manager.checkBLEConnectionHealth()
        assertTrue(manager.connectionIntent.wantsConnection)
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::appDidEnterBackground forwards to state machine()", "platform-adaptation")
    fun `appDidEnterBackground forwards to the platform`() = runtimeScenario {
        manager.appDidEnterBackground()
        assertEquals(listOf(false), foregroundCalls)
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::appDidBecomeActive forwards to state machine and triggers health check()", "platform-adaptation")
    fun `appDidBecomeActive forwards to the platform and triggers health check`() = runtimeScenario {
        manager.appDidBecomeActive()
        assertEquals(listOf(true), foregroundCalls)
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::appDidEnterBackground stops running watchdog()")
    fun `appDidEnterBackground stops running watchdog`() = runtimeScenario {
        val id = connectThenLose()
        last.clear(id)
        manager.stopReconnectionWatchdog()
        manager.appDidBecomeActive()
        assertTrue(manager.isReconnectionWatchdogRunning)
        manager.appDidEnterBackground()
        assertFalse(manager.isReconnectionWatchdogRunning)
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::appDidBecomeActive re-arms watchdog when disconnected and wants connection()")
    fun `appDidBecomeActive re-arms watchdog when disconnected and wants connection`() = runtimeScenario {
        val id = connectThenLose()
        last.clear(id)
        manager.stopReconnectionWatchdog()
        assertFalse(manager.isReconnectionWatchdogRunning)
        manager.appDidBecomeActive()
        assertTrue(manager.isReconnectionWatchdogRunning)
        manager.appDidEnterBackground()
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::appDidBecomeActive does not arm watchdog when user does not want connection()")
    fun `appDidBecomeActive does not arm watchdog when user does not want connection`() = runtimeScenario {
        assertEquals(ConnectionIntent.None, manager.connectionIntent)
        manager.appDidBecomeActive()
        assertFalse(manager.isReconnectionWatchdogRunning)
    }

    @Test @OriginalCase("ConnectionManagerBLEHealthTests::appDidBecomeActive does not arm watchdog during auto-reconnect()")
    fun `appDidBecomeActive does not arm watchdog during auto-reconnect`() = runtimeScenario {
        val id = connectThenLose()
        last.clear(id)
        manager.stopReconnectionWatchdog()
        stub.autoReconnecting = true
        manager.appDidBecomeActive()
        assertFalse(manager.isReconnectionWatchdogRunning)
    }
}
