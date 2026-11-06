// AndroidOnly: WP-303 In-app scan picker port over BluetoothScanPairingService and the BLE scan coordinator.
package com.meshcoreone.android.app.container.onboarding

import com.meshcoreone.android.core.connectivity.ble.BleScanCoordinator
import com.meshcoreone.android.core.connectivity.pairing.BluetoothEndpoint
import com.meshcoreone.android.core.connectivity.pairing.BluetoothScanPairingService
import com.meshcoreone.android.core.connectivity.pairing.DeviceEndpointIdentity
import com.meshcoreone.android.core.connectivity.pairing.KnownEndpointStore
import com.meshcoreone.android.core.ui.RSSIScanTracker
import com.meshcoreone.android.core.ui.ScanDiscovery
import com.meshcoreone.android.feature.onboarding.OnboardingScanDiscovery
import com.meshcoreone.android.feature.onboarding.OnboardingScanFallbackPort
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Presented only when companion association is unsupported ([BluetoothScanPairingService] is the pairing service).
 * Collectors in one pairing attempt share a scan; the final collector leaving stops it.
 */
class ScanFallbackPort(
    private val service: BluetoothScanPairingService,
    private val scans: BleScanCoordinator,
    private val knownEndpoints: KnownEndpointStore,
    private val scope: CoroutineScope,
    private val isBondedAddress: (String) -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
) : OnboardingScanFallbackPort {
    override val isRequested: StateFlow<Boolean> get() = service.isPresenting
    private val selectionLock = Any()
    private var activeAttempt: DiscoveryAttempt? = null
    private var nextAttemptId = 0L
    private val endpointWriteLock = Mutex()

    internal fun beginDiscovery(): Long {
        val (generation, previousJobs) = synchronized(selectionLock) {
            val previousJobs = activeAttempt?.let(::retireAttempt)
            val attempt = DiscoveryAttempt(++nextAttemptId)
            activeAttempt = attempt
            attempt.generation to previousJobs
        }
        cancel(previousJobs)
        return generation
    }

    internal fun endDiscovery(generation: Long) {
        val jobs = synchronized(selectionLock) {
            val attempt = activeAttempt?.takeIf { it.generation == generation } ?: return
            retireAttempt(attempt)
        }
        cancel(jobs)
    }

    override fun discoveries(): Flow<List<OnboardingScanDiscovery>> = channelFlow {
        val attempt = synchronized(selectionLock) {
            activeAttempt?.takeIf { it.active } ?: DiscoveryAttempt(++nextAttemptId).also { activeAttempt = it }
        }
        val scanJob = synchronized(selectionLock) {
            if (activeAttempt !== attempt || !attempt.active) null
            else {
                attempt.scanCollectors++
                attempt.scanJob ?: scope.launch(start = CoroutineStart.LAZY) {
                    scan(attempt, attempt.scanResult)
                }.also {
                    attempt.scanJob = it
                    it.start()
                }
            }
        } ?: return@channelFlow
        val publishJob = launch {
            attempt.discoveries.collect { send(it) }
        }
        try {
            val failure = attempt.scanResult.await()
            if (failure != null) {
                val shouldReport = synchronized(selectionLock) {
                    if (attempt.failureReported) false
                    else {
                        attempt.failureReported = true
                        true
                    }
                }
                if (shouldReport) throw failure
            }
            scanJob.join()
        } finally {
            publishJob.cancel()
            val stopJob = synchronized(selectionLock) {
                attempt.scanCollectors--
                if (attempt.scanCollectors == 0) {
                    attempt.scanJob.also {
                        attempt.scanJob = null
                        attempt.scanResult = CompletableDeferred()
                        attempt.failureReported = false
                        attempt.discoveries.value = emptyList()
                    }
                } else {
                    null
                }
            }
            stopJob?.cancel()
        }
    }

    private suspend fun scan(attempt: DiscoveryAttempt, result: CompletableDeferred<Exception?>) {
        val tracker = RSSIScanTracker<UUID>(clock)
        val publishJob = scope.launch {
            tracker.devices.collect { devices ->
                attempt.discoveries.value =
                    devices.values.map { OnboardingScanDiscovery(it.discovery.id, it.discovery.name, it.tier) }
            }
        }
        try {
            tracker.consume(scans.startBleScanning().map { device ->
                val endpoint = BluetoothEndpoint(device.id, DeviceEndpointIdentity.canonicalAddress(device.address), null)
                if (isActive(attempt)) attempt.endpoints[device.id] = endpoint
                ScanDiscovery(device.id, device.name, device.rssi.toLong())
            })
            result.complete(null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            result.complete(failure)
        } finally {
            publishJob.cancel()
        }
    }

    override fun select(id: UUID) {
        synchronized(selectionLock) {
            val attempt = activeAttempt?.takeIf { it.active } ?: return
            if (attempt.selectionJob?.isActive == true) return
            val endpoint = attempt.endpoints[id]
            if (endpoint == null) {
                resolveFailure(attempt, IllegalStateException("Selected BLE discovery no longer has an endpoint"))
                return
            }
            val job = scope.launch(start = CoroutineStart.LAZY) { retainAndSelect(attempt, id, endpoint) }
            attempt.selectionJob = job
            job.start()
        }
    }

    override fun cancel() {
        val jobs = synchronized(selectionLock) { activeAttempt?.let(::retireAttempt) }
        cancel(jobs)
        service.cancel()
    }

    private suspend fun retainAndSelect(attempt: DiscoveryAttempt, id: UUID, endpoint: BluetoothEndpoint) {
        val job = currentCoroutineContext()[Job] ?: return
        try {
            val stability = DeviceEndpointIdentity.stability(endpoint.address, bonded = isBondedAddress(endpoint.address))
            endpointWriteLock.withLock {
                job.ensureActive()
                if (!isActive(attempt)) return

                val previous = knownEndpoints.endpoint(id)
                val previousStability = previous?.let {
                    DeviceEndpointIdentity.stability(it.address, bonded = isBondedAddress(it.address))
                }
                var writeStarted = false
                try {
                    writeStarted = true
                    knownEndpoints.remember(endpoint, stability)
                    if (!job.isActive || !isActive(attempt)) {
                        withContext(NonCancellable) {
                            rollbackEndpoint(id, endpoint, previous, previousStability)
                        }
                        if (!job.isActive) job.ensureActive()
                        return
                    }
                    val selected = synchronized(selectionLock) {
                        if (activeAttempt !== attempt || !attempt.active || !job.isActive) {
                            false
                        } else {
                            attempt.active = false
                            attempt.selectionJob = null
                            attempt.scanJob?.cancel()
                            activeAttempt = null
                            attempt.endpoints.remove(id)
                            service.select(id)
                            true
                        }
                    }
                    if (!selected) {
                        rollbackEndpoint(id, endpoint, previous, previousStability)
                        return
                    }
                } catch (cancelled: CancellationException) {
                    if (writeStarted) rollbackWithoutReplacingFailure(id, endpoint, previous, previousStability, cancelled)
                    throw cancelled
                } catch (failure: Exception) {
                    if (writeStarted) rollbackWithoutReplacingFailure(id, endpoint, previous, previousStability, failure)
                    throw failure
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val resolved = synchronized(selectionLock) {
                if (activeAttempt !== attempt || !attempt.active || !job.isActive) {
                    false
                } else {
                    attempt.active = false
                    attempt.selectionJob = null
                    activeAttempt = null
                    service.fail(failure)
                    true
                }
            }
            if (!resolved) logger.log(Level.WARNING, "Discarded failure from an ended scan discovery", failure)
        } finally {
            synchronized(selectionLock) {
                if (attempt.selectionJob === job) attempt.selectionJob = null
            }
        }
    }

    private fun isActive(attempt: DiscoveryAttempt): Boolean =
        synchronized(selectionLock) { activeAttempt === attempt && attempt.active }

    private fun retireAttempt(attempt: DiscoveryAttempt): AttemptJobs {
        attempt.active = false
        attempt.failureReported = true
        attempt.discoveries.value = emptyList()
        attempt.endpoints.clear()
        val jobs = AttemptJobs(attempt.selectionJob, attempt.scanJob)
        attempt.selectionJob = null
        attempt.scanJob = null
        if (activeAttempt === attempt) activeAttempt = null
        attempt.scanResult.complete(null)
        return jobs
    }

    private fun cancel(jobs: AttemptJobs?) {
        jobs?.selection?.cancel()
        jobs?.scan?.cancel()
    }

    private fun resolveFailure(attempt: DiscoveryAttempt, failure: Throwable) {
        if (activeAttempt !== attempt || !attempt.active) return
        attempt.active = false
        activeAttempt = null
        service.fail(failure)
    }

    private suspend fun rollbackEndpoint(
        id: UUID,
        attempted: BluetoothEndpoint,
        previous: BluetoothEndpoint?,
        previousStability: com.meshcoreone.android.core.connectivity.pairing.EndpointStability?,
    ) {
        if (knownEndpoints.endpoint(id) != attempted) return
        if (previous == null) knownEndpoints.forget(id)
        else knownEndpoints.remember(previous, requireNotNull(previousStability))
    }

    private suspend fun rollbackWithoutReplacingFailure(
        id: UUID,
        attempted: BluetoothEndpoint,
        previous: BluetoothEndpoint?,
        previousStability: com.meshcoreone.android.core.connectivity.pairing.EndpointStability?,
        failure: Exception,
    ) {
        try {
            withContext(NonCancellable) { rollbackEndpoint(id, attempted, previous, previousStability) }
        } catch (rollbackFailure: Exception) {
            failure.addSuppressed(rollbackFailure)
        }
    }

    private data class AttemptJobs(val selection: Job?, val scan: Job?)

    private class DiscoveryAttempt(
        val generation: Long,
        var active: Boolean = true,
        var selectionJob: Job? = null,
        var scanJob: Job? = null,
        var scanCollectors: Int = 0,
        var scanResult: CompletableDeferred<Exception?> = CompletableDeferred(),
        var failureReported: Boolean = false,
        val discoveries: MutableStateFlow<List<OnboardingScanDiscovery>> = MutableStateFlow(emptyList()),
        val endpoints: ConcurrentHashMap<UUID, BluetoothEndpoint> = ConcurrentHashMap(),
    )

    private companion object {
        val logger: Logger = Logger.getLogger(ScanFallbackPort::class.java.name)
    }
}
