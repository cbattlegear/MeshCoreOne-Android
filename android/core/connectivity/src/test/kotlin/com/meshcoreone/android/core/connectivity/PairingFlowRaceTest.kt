// PortedFrom: MC1Services/Tests/MC1ServicesTests/PairingCancellationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/PairingRaceIntegrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/PairingWhileConnectedTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.connectivity

import com.meshcoreone.android.core.connectivity.pairing.PairingCoordinator
import com.meshcoreone.android.core.connectivity.pairing.PairingConnectionPort
import com.meshcoreone.android.core.connectivity.pairing.PairingError
import com.meshcoreone.android.core.connectivity.support.OriginalCase
import com.meshcoreone.android.core.connectivity.support.RuntimeHarness
import com.meshcoreone.android.core.connectivity.support.association
import com.meshcoreone.android.core.connectivity.support.runtimeScenario
import com.meshcoreone.android.core.connectivity.support.settle
import com.meshcoreone.android.core.connectivity.support.testDevice
import com.meshcoreone.android.core.contracts.domain.ConnectionSnapshot
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Test

/** Pairing flows pinned inside the other-app wait, racing cancellation, health checks and old-link callbacks. */
class PairingFlowRaceTest {
    private class PinnedPair(val job: Job, val release: CompletableDeferred<Unit>, val result: CompletableDeferred<Result<Unit>>)

    /** Starts `pairNewDevice` and returns once it is suspended in the other-app wait. */
    private suspend fun RuntimeHarness.pinInOtherAppWait(pairing: PairingCoordinator = coordinator()): PinnedPair {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        pairing.otherAppWaitStrategy = { entered.complete(Unit); release.await(); false }
        val result = CompletableDeferred<Result<Unit>>()
        val job = scenario.scope.launch { result.complete(runCatching { pairing.pairNewDevice() }) }
        entered.await()
        return PinnedPair(job, release, result)
    }

    private fun RuntimeHarness.connectsTo(id: UUID): Int =
        links.count { (it.target as? ConnectionTarget.Bluetooth)?.deviceId == id }

    @Test fun `cancel during runtime connect drains only its pending transport and allows reentry`() = runtimeScenario {
        val id = UUID.randomUUID()
        register(id)
        companion.setPickerResult(Result.success(id))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        createRadio = {
            com.meshcoreone.android.core.connectivity.support.FakeRadio().apply {
                beforeConnect = { entered.complete(Unit); release.await() }
            }
        }
        val coordinator = coordinator().apply { otherAppWaitStrategy = { false } }
        val job = scenario.scope.launch { coordinator.pairNewDevice() }
        entered.await()
        job.cancel()
        job.join()
        assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
        assertNull(manager.connectedDevice)
        assertTrue(radios.all { !it.isConnected() })
        assertFalse(coordinator.isPairingInProgress)
        assertEquals(id, companion.lastRemovedDeviceId)
        createRadio = { com.meshcoreone.android.core.connectivity.support.FakeRadio() }
        register(id)
        coordinator.pairNewDevice()
        assertEquals(DeviceConnectionState.READY, manager.connectionState)
    }

    @Test fun `cancel after runtime ownership handoff preserves the operational radio and association`() = runtimeScenario {
        val id = UUID.randomUUID()
        register(id)
        companion.setPickerResult(Result.success(id))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val connection = object : PairingConnectionPort by port {
            override suspend fun connect(deviceId: UUID, forceFullSync: Boolean, forceReconnect: Boolean) {
                port.connect(deviceId, forceFullSync, forceReconnect)
                entered.complete(Unit)
                release.await()
            }
        }
        val coordinator = PairingCoordinator(
            connection, pairing, devices, probe, scans::stopBleScanning, clock, scenario.scope,
        ).apply { otherAppWaitStrategy = { false } }
        val job = scenario.scope.launch { coordinator.pairNewDevice() }
        entered.await()
        val token = manager.snapshot.value.token
        job.cancel()
        job.join()
        assertEquals(DeviceConnectionState.READY, manager.connectionState)
        assertEquals(token, manager.snapshot.value.token)
        assertTrue(radios.single().isConnected())
        assertEquals(0, companion.removeAccessoryCallCount)
        assertFalse(coordinator.isPairingInProgress)
    }

    @Test fun `cancelled old pairing cannot close a newer operational radio`() = runtimeScenario {
        val id = UUID.randomUUID()
        register(id)
        companion.setPickerResult(Result.success(id))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = coordinator().apply {
            otherAppWaitStrategy = {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                false
            }
        }
        val job = scenario.scope.launch { coordinator.pairNewDevice() }
        entered.await()
        job.cancel()
        val successor = connectReady()
        val token = manager.snapshot.value.token
        release.complete(Unit)
        job.join()
        assertEquals(successor, manager.connectedDevice?.id)
        assertEquals(token, manager.snapshot.value.token)
        assertEquals(DeviceConnectionState.READY, manager.connectionState)
        assertTrue(radios.last().isConnected())
        assertEquals(id, companion.lastRemovedDeviceId)
    }

    @Test fun `cancelled pairing keeps association cleanup errors on the cancellation`() = runtimeScenario {
        val id = UUID.randomUUID()
        register(id)
        companion.setPickerResult(Result.success(id))
        val failure = IllegalStateException("remove failed")
        companion.removeAccessoryError = failure
        val pinned = pinInOtherAppWait()
        val cancelled = CancellationException("user cancelled")
        pinned.job.cancel(cancelled)
        pinned.job.join()
        val observed = checkNotNull(pinned.result.await().exceptionOrNull())
        assertTrue(failure in observed.suppressedExceptions)
    }

    @Test @OriginalCase("PairingCancellationTests::cancellation in connect(to:) phase removes accessory from ASK()")
    fun `cancellation in the connect phase removes the association`() = runtimeScenario {
        val id = UUID.randomUUID()
        companion.setPickerResult(Result.success(id))
        companion.setPairedAccessories(listOf(association(id, "test")))
        devices.saveDevice(testDevice(id))
        val pinned = pinInOtherAppWait()
        pinned.job.cancel()
        pinned.release.complete(Unit)
        pinned.job.join()
        assertEquals(1, companion.removeAccessoryCallCount)
        assertEquals(id, companion.lastRemovedDeviceId)
    }

    @Test @OriginalCase("PairingCancellationTests::cancellation before connect(to:) bails before transport.connect runs()")
    fun `cancellation before connect bails before transport connect runs`() = runtimeScenario {
        val id = UUID.randomUUID()
        companion.setPickerResult(Result.success(id))
        companion.setPairedAccessories(listOf(association(id, "test")))
        devices.saveDevice(testDevice(id))
        val pinned = pinInOtherAppWait()
        pinned.job.cancel()
        pinned.release.complete(Unit)
        pinned.job.join()
        assertTrue(radios.all { it.connects == 0 }, "A cancelled pairing must not establish a BLE link")
        assertTrue(port.connectRequests.isEmpty())
        assertEquals(1, companion.removeAccessoryCallCount, "Association cleaned up after cancellation")
    }

    @Test @OriginalCase("PairingRaceIntegrationTests::opportunistic reconnect to old device is gated while pairing is suspended in waitForOtherAppReconnection()")
    fun `opportunistic reconnect to old device is gated while pairing is suspended in the other-app wait`() = runtimeScenario {
        val old = connectThenLose()
        companion.setPickerResult(Result.success(UUID.randomUUID()))
        val before = connectsTo(old)
        val pinned = pinInOtherAppWait()
        manager.checkBLEConnectionHealth()
        assertEquals(before, connectsTo(old), "Opportunistic reconnect to the old device is deferred")
        pinned.release.complete(Unit)
        pinned.job.cancel()
        pinned.job.join()
    }

    @Test @OriginalCase("PairingWhileConnectedTests::auto-reconnect during waitForOtherAppReconnection tears down old-device session without claiming coordinator()")
    fun `auto-reconnect during the other-app wait tears down old-device session without claiming coordinator`() = runtimeScenario {
        val old = connectReady()
        companion.setPickerResult(Result.success(UUID.randomUUID()))
        val pinned = pinInOtherAppWait()
        checkNotNull(links.last().callbacks).onAutoReconnecting("supervision timeout")
        assertTrue(scenario.awaitCondition {
            manager.connectionState == DeviceConnectionState.DISCONNECTED && manager.connectedDevice == null
        })
        assertNull(manager.activeReconnectDeviceId)
        assertNull(manager.connectedDevice)
        assertEquals(0, autoCount, "The reconnect coordinator was not claimed for $old")
        pinned.release.complete(Unit)
        pinned.job.cancel()
        pinned.job.join()
    }

    @Test @OriginalCase("PairingWhileConnectedTests::auto-reconnect completion during pair-wait does not run rebuildSession()")
    fun `auto-reconnect completion during pair-wait does not run rebuildSession`() = runtimeScenario {
        val old = connectThenLose()
        companion.setPickerResult(Result.success(UUID.randomUUID()))
        val pinned = pinInOtherAppWait()
        val linksBefore = links.size
        checkNotNull(links.last().callbacks).onReconnected()
        settle()
        assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState, "Late completion must not transition state")
        assertEquals(linksBefore, links.size, "No rebuild generation for $old")
        pinned.release.complete(Unit)
        pinned.job.cancel()
        pinned.job.join()
    }

    @Test @OriginalCase("PairingWhileConnectedTests::pair-while-connected with switchDevice throw leaves state .disconnected()")
    fun `pair-while-connected with a failing switch leaves state disconnected`() = runtimeScenario {
        connectReady()
        val oldServices = services.last()
        // The new device is not in the association registry, so the runtime rejects it typed.
        companion.setPickerResult(Result.success(UUID.randomUUID()))
        val pairing = coordinator().apply { otherAppWaitStrategy = { false } }
        assertIs<PairingError>(runCatching { pairing.pairNewDevice() }.exceptionOrNull())
        settle()
        assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
        assertNull(manager.connectedDevice)
        assertEquals(1, oldServices.teardowns, "Old generation services were torn down")
    }

    @Test @OriginalCase("PairingWhileConnectedTests::failed switchDevice fires onConnectionLost so observers tear down()", "native-equivalent")
    fun `failed switch publishes a disconnected transition so observers tear down`() = runtimeScenario {
        connectReady()
        val transitions = mutableListOf<ConnectionSnapshot>()
        val subscription = manager.subscribeTransitions()
        scenario.scope.launch { subscription.transitions.collect { transitions += it } }
        companion.setPickerResult(Result.success(UUID.randomUUID()))
        val pairing = coordinator().apply { otherAppWaitStrategy = { false } }
        assertIs<PairingError>(runCatching { pairing.pairNewDevice() }.exceptionOrNull())
        settle()
        // Runtime publishes the terminal transition rather than calling onConnectionLost (coordinator note C-05).
        val terminal = transitions.last()
        assertEquals(DeviceConnectionState.DISCONNECTED, terminal.state)
        assertNull(terminal.token)
        subscription.close()
    }
}
