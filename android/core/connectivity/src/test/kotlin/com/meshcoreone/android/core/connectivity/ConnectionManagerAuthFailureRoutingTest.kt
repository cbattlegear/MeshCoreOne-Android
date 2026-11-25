// PortedFrom: MC1Services/Tests/MC1ServicesTests/ConnectionManagerAuthFailureRoutingTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.connectivity

import com.meshcoreone.android.core.ble.BleError
import com.meshcoreone.android.core.ble.BleTransportException
import com.meshcoreone.android.core.connectivity.support.FakeRadio
import com.meshcoreone.android.core.connectivity.support.OriginalCase
import com.meshcoreone.android.core.connectivity.support.RuntimeHarness
import com.meshcoreone.android.core.connectivity.support.runtimeScenario
import com.meshcoreone.android.core.connectivity.support.settle
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.runtime.ConnectionError
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.runtime.RuntimeDiagnostic
import com.meshcoreone.android.core.runtime.RuntimeSyncResult
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Auth-failure surfacing: a core:ble `AuthenticationFailed` is classified by the WP-206 platform
 * projection and surfaced by the runtime exactly once per failure episode.
 */
class ConnectionManagerAuthFailureRoutingTest {
    private val authFailure get() = BleTransportException(BleError.AuthenticationFailed, status = 5)

    /** A connected generation that stays SYNCING, so READY promotion cannot reset the latch. */
    private suspend fun RuntimeHarness.syncingGeneration(id: UUID): UUID {
        configureServices = { it.syncResult = RuntimeSyncResult.Failed(IllegalStateException("sync")) }
        if (companion.accessory(id) == null) register(id)
        manager.connect(target(id))
        settle()
        assertEquals(DeviceConnectionState.SYNCING, manager.connectionState)
        return id
    }

    /** Fires the link's terminal callback again (watchdog retries failing the same way). */
    private suspend fun RuntimeHarness.repeatLoss(failure: Throwable?) {
        checkNotNull(links.last().callbacks).onDisconnected(failure)
        settle()
    }

    @Test @OriginalCase("ConnectionManagerAuthFailureRoutingTests::connection loss with authenticationFailed fires the callback once per episode()")
    fun `connection loss with authenticationFailed fires the callback once per episode`() = runtimeScenario {
        val id = connectReady()
        loseLink(authFailure)
        assertEquals(listOf(id), authFailures)
        repeatLoss(authFailure)
        assertEquals(listOf(id), authFailures)
    }

    @Test @OriginalCase("ConnectionManagerAuthFailureRoutingTests::connection loss with a non-auth error does not fire the callback()")
    fun `connection loss with a non-auth error does not fire the callback`() = runtimeScenario {
        connectReady()
        loseLink(BleTransportException(BleError.ConnectionFailed("link supervision timeout")))
        assertTrue(authFailures.isEmpty())
    }

    @Test @OriginalCase("ConnectionManagerAuthFailureRoutingTests::connection loss with no error does not fire the callback()")
    fun `connection loss with no error does not fire the callback`() = runtimeScenario {
        connectReady()
        loseLink(null)
        assertTrue(authFailures.isEmpty())
    }

    @Test @OriginalCase("ConnectionManagerAuthFailureRoutingTests::explicit disconnect clears the latch so a new failure episode re-alerts()")
    fun `explicit disconnect clears the latch so a new failure episode re-alerts`() = runtimeScenario {
        val id = syncingGeneration(UUID.randomUUID())
        loseLink(authFailure)
        manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
        syncingGeneration(id)
        loseLink(authFailure)
        assertEquals(listOf(id, id), authFailures)
    }

    @Test @OriginalCase("ConnectionManagerAuthFailureRoutingTests::ready promotion clears the latch so a new failure episode re-alerts()")
    fun `ready promotion clears the latch so a new failure episode re-alerts`() = runtimeScenario {
        val id = syncingGeneration(UUID.randomUUID())
        loseLink(authFailure)
        assertEquals(listOf(id), authFailures)
        configureServices = {}
        manager.connect(target(id))
        settle()
        assertEquals(DeviceConnectionState.READY, manager.connectionState)
        loseLink(authFailure)
        assertEquals(listOf(id, id), authFailures)
    }

    @Test @OriginalCase("ConnectionManagerAuthFailureRoutingTests::opportunistic reconnect surfaces authenticationFailed thrown by connect()", "native-equivalent")
    fun `opportunistic reconnect surfaces authenticationFailed thrown by connect`() = runtimeScenario {
        val id = connectThenLose()
        // Registry inactive (scan-fallback shape): connect reaches the transport, which fails auth.
        companion.isSessionActive = false
        val failure = authFailure
        createRadio = { FakeRadio().also { it.connectFailure = failure } }
        manager.checkBLEConnectionHealth()
        val reported = diagnostics.filterIsInstance<RuntimeDiagnostic.Failure>()
            .single { it.operation == "health.reconnect" }
        assertSame(failure, assertIs<BleTransportException>(reported.cause))
        assertEquals(listOf(id), authFailures)
        assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
        assertTrue(manager.connectionIntent.wantsConnection)
    }

    @Test @OriginalCase("ConnectionManagerAuthFailureRoutingTests::opportunistic reconnect stays silent for non-auth connect failures()", "native-equivalent")
    fun `opportunistic reconnect stays silent for non-auth connect failures`() = runtimeScenario {
        connectThenLose()
        companion.setPairedAccessories(emptyList())
        manager.checkBLEConnectionHealth()
        val reported = diagnostics.filterIsInstance<RuntimeDiagnostic.Failure>()
            .single { it.operation == "health.reconnect" }
        assertIs<ConnectionError.DeviceNotFound>(reported.cause)
        assertTrue(authFailures.isEmpty())
        assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
        assertTrue(manager.connectionIntent.wantsConnection)
    }

    @Test @OriginalCase("ConnectionManagerAuthFailureRoutingTests::launch auto-reconnect throwing authenticationFailed surfaces recovery without the watchdog()")
    fun `launch auto-reconnect throwing authenticationFailed surfaces recovery without the watchdog`() = runtimeScenario {
        val id = UUID.randomUUID()
        last.persist(id, com.meshcoreone.android.core.model.RadioId(UUID.randomUUID()), "Radio")
        companion.isSessionActive = false
        createRadio = { FakeRadio().also { it.connectFailure = authFailure } }
        manager.activate()
        settle()
        assertEquals(listOf(id), authFailures, "Surfaced from the launch path, not a watchdog tick")
        assertTrue(manager.isReconnectionWatchdogRunning)
        manager.stopReconnectionWatchdog()
    }
}
