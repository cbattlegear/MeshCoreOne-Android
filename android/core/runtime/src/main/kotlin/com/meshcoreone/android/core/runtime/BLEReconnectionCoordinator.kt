// PortedFrom: MC1Services/Sources/MC1Services/Connection/BLEReconnectionCoordinator.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface BLEReconnectionDelegate {
    val connectionIntent: ConnectionIntent
    val connectionState: DeviceConnectionState
    suspend fun setConnectionState(state: DeviceConnectionState)
    suspend fun clearConnectedDevice()
    suspend fun teardownSessionForReconnect()
    suspend fun rebuildSession(deviceId: UUID)
    suspend fun disconnectTransport()
    suspend fun notifyAutoReconnectStarted()
    suspend fun notifyConnectionLost()
    suspend fun handleReconnectionFailure()
    suspend fun isTransportAutoReconnecting(): Boolean
}

class BLEReconnectionCoordinator(
    private val delegate: BLEReconnectionDelegate,
    private val scope: CoroutineScope,
    private val clock: RuntimeClock,
    private val reporter: RuntimeIssueReporter,
    private val uiTimeout: Duration = 15.seconds,
    private val maximumWindow: Duration = 60.seconds,
) {
    init {
        require(uiTimeout.isFinite() && uiTimeout.isPositive())
        require(maximumWindow.isFinite() && maximumWindow.isPositive())
    }
    private class Cycle(val deviceId: UUID, val generation: Long, var started: Duration) {
        val teardown = CompletableDeferred<Unit>()
        var uiTimedOut = false
    }
    private val lock = Any()
    private var cycle: Cycle? = null
    private var generation = 0L
    private var rebuilding: Long? = null
    private var timeout: Job? = null
    private var timerRevision = 0L
    private val ownedTimers = mutableSetOf<Job>()
    val reconnectingDeviceId: UUID? get() = synchronized(lock) { cycle?.deviceId }
    val reconnectGeneration: Long get() = synchronized(lock) { generation }
    val rebuildInFlight: Boolean get() = synchronized(lock) { rebuilding != null }

    suspend fun handleEnteringAutoReconnect(deviceId: UUID) {
        if (!delegate.connectionIntent.wantsConnection) {
            delegate.disconnectTransport()
            return
        }
        val claim = synchronized(lock) {
            generation = Math.incrementExact(generation)
            Cycle(deviceId, generation, clock.elapsed).also { cycle = it }
        }
        delegate.setConnectionState(DeviceConnectionState.CONNECTING)
        try {
            delegate.notifyAutoReconnectStarted()
            if (!owns(claim)) return
            delegate.teardownSessionForReconnect()
            if (owns(claim)) armTimeout(claim)
        } finally {
            claim.teardown.complete(Unit)
        }
    }

    suspend fun handleReconnectionComplete(deviceId: UUID) {
        if (!delegate.connectionIntent.wantsConnection) {
            cancelTimeout()
            clearReconnectingDevice()
            delegate.disconnectTransport()
            return
        }
        val claim = synchronized(lock) { cycle?.takeIf { it.deviceId == deviceId } } ?: return
        val state = delegate.connectionState
        if (state != DeviceConnectionState.CONNECTING && state != DeviceConnectionState.DISCONNECTED) {
            if (!rebuildInFlight) clearIfOwned(claim)
            return
        }
        val expected = synchronized(lock) {
            if (cycle !== claim || rebuilding == generation) return
            generation = Math.incrementExact(generation)
            rebuilding = generation
            generation
        }
        cancelTimeout()
        delegate.setConnectionState(DeviceConnectionState.CONNECTING)
        try {
            claim.teardown.await()
            if (!current(claim, expected)) return
            try {
                delegate.rebuildSession(deviceId)
                if (current(claim, expected)) clearIfOwned(claim)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reporter.report(RuntimeDiagnostic.Failure("reconnect.rebuild", failure))
                clock.sleep(2.seconds)
                if (!current(claim, expected)) return
                if (!delegate.connectionIntent.wantsConnection) {
                    delegate.handleReconnectionFailure()
                    if (current(claim, expected)) clearIfOwned(claim)
                    return
                }
                try {
                    delegate.rebuildSession(deviceId)
                    if (current(claim, expected)) clearIfOwned(claim)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (retryFailure: Exception) {
                    reporter.report(RuntimeDiagnostic.Failure("reconnect.retry", retryFailure))
                    if (current(claim, expected)) {
                        delegate.handleReconnectionFailure()
                        if (current(claim, expected)) clearIfOwned(claim)
                    }
                }
            }
        } finally {
            synchronized(lock) { if (rebuilding == expected) rebuilding = null }
        }
    }

    fun restartTimeout(deviceId: UUID) {
        val claim = synchronized(lock) {
            val existing = cycle
            if (existing?.deviceId == deviceId) {
                existing.also { it.started = clock.elapsed; it.uiTimedOut = false }
            } else {
                generation = Math.incrementExact(generation)
                Cycle(deviceId, generation, clock.elapsed).also {
                    it.teardown.complete(Unit)
                    cycle = it
                }
            }
        }
        armTimeout(claim)
    }

    fun cancelTimeout() {
        synchronized(lock) { timerRevision++; timeout.also { timeout = null } }?.cancel()
    }

    fun clearReconnectingDevice() {
        cancelTimeout()
        synchronized(lock) {
            if (cycle != null) {
                generation = Math.incrementExact(generation)
                cycle = null
            }
        }
    }

    suspend fun close() {
        clearReconnectingDevice()
        val timers = synchronized(lock) { ownedTimers.toList() }
        withContext(NonCancellable) { timers.forEach { it.cancelAndJoin() } }
    }

    private fun armTimeout(claim: Cycle) {
        cancelTimeout()
        val expectedTimer = synchronized(lock) { timerRevision }
        val task = scope.launch(start = CoroutineStart.LAZY) {
            clock.sleep(uiTimeout)
            val firingJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            synchronized(lock) { if (timeout === firingJob) timeout = null }
            handleUITimeout(claim, expectedTimer)
        }
        synchronized(lock) {
            if (cycle !== claim) { task.cancel(); return }
            timeout = task
            ownedTimers += task
        }
        task.invokeOnCompletion { synchronized(lock) { ownedTimers.remove(task); if (timeout === task) timeout = null } }
        task.start()
    }

    private suspend fun handleUITimeout(claim: Cycle, expectedTimer: Long) {
        if (synchronized(lock) { timerRevision != expectedTimer }) return
        if (!owns(claim) || delegate.connectionState != DeviceConnectionState.CONNECTING) return
        val elapsed = clock.elapsed - claim.started
        val autoReconnecting = delegate.isTransportAutoReconnecting()
        if (synchronized(lock) { timerRevision != expectedTimer }) return
        if (!owns(claim) || delegate.connectionState != DeviceConnectionState.CONNECTING || rebuildInFlight) return
        if (autoReconnecting && elapsed < maximumWindow) {
            armTimeout(claim)
            return
        }
        if (autoReconnecting) synchronized(lock) { if (cycle === claim) claim.uiTimedOut = true }
        else clearIfOwned(claim, invalidate = true)
        delegate.setConnectionState(DeviceConnectionState.DISCONNECTED)
        delegate.clearConnectedDevice()
        delegate.notifyConnectionLost()
    }

    private fun owns(claim: Cycle): Boolean = synchronized(lock) { cycle === claim }
    private fun current(claim: Cycle, expected: Long): Boolean =
        synchronized(lock) { cycle === claim && generation == expected }
    private fun clearIfOwned(claim: Cycle, invalidate: Boolean = false) {
        synchronized(lock) {
            if (cycle === claim) {
                cycle = null
                if (invalidate) generation = Math.incrementExact(generation)
            }
        }
    }
}
