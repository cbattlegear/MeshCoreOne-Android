// PortedFrom: MC1Services/Sources/MC1Services/Sync/ConnectionManager+SyncRetry.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Connection/ConnectionManager+WiFi.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Connection/ConnectionManager.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.DevicePlatform
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TransportType
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The `ConnectionManager` state the sync retry policy reads and drives (WP-207's runtime
 * `ConnectionManager`, unreachable from this module). WP-303 implements it over the runtime: the
 * properties mirror runtime members of the same names, and [promoteToReadyAfterResync] needs a new
 * generation-checked runtime entry point because the runtime's own promotion is private.
 */
interface SyncRetryHost {
    val connectionIntent: ConnectionIntent
    val connectionState: DeviceConnectionState

    /** The live service graph; the loops fence on its identity (Swift `self.services === services`). */
    val currentServices: SyncRetryServices?
    val detectedPlatform: DevicePlatform
    val lastCleanChannelSync: Pair<RadioId, Instant>?
    val lastAttemptedChannelSync: Pair<RadioId, Instant>?

    /** Stores the platform resolved at connect (Swift `detectedPlatform = platform`). */
    fun storeDetectedPlatform(platform: DevicePlatform)

    /** Room session ids torn down for reconnect and awaiting re-authentication. */
    fun sessionsAwaitingReauth(): Set<UUID>

    /** Removes the consumed ids; ids appended during the re-auth await survive. */
    fun consumeSessionsAwaitingReauth(ids: Set<UUID>)

    /** Swift `connectionState = .ready` after a successful resync. */
    suspend fun promoteToReadyAfterResync()
    suspend fun syncDeviceTimeIfNeeded()
    suspend fun onDeviceSynced()
    fun onResyncFailed()

    /** Swift `disconnect(reason: .resyncFailed)`; the host's disconnect also cancels these loops. */
    suspend fun disconnectAfterResyncFailure()
}

/** The slice of Swift's `ServiceContainer` the retry loops use. Identity distinguishes connection cycles. */
class SyncRetryServices(
    val syncCoordinator: SyncCoordinator,
    val dependencies: SyncDependencies,
    val remoteNodeService: SyncRemoteNodeServicing,
)

/**
 * Sync retry policy over the connection lifecycle: initial sync, the resync loop, and the bounded
 * channel-only retry. Swift runs these as `@MainActor` tasks stored on `ConnectionManager`; here the
 * stored jobs are lock-confined and every loop fences on connection intent, operational state and the
 * service graph's identity, so a loop orphaned by a later reconnect never drives a successor connection.
 */
class SyncRetryController(
    private val host: SyncRetryHost,
    private val scope: CoroutineScope,
    private val clock: SyncClock = SyncClock.SYSTEM,
    private val logSink: SyncLogSink = SyncLogSink.NONE,
) {
    private val lock = Any()
    private var resyncJob: Job? = null
    private var attemptCount = 0
    private var channelRetryJob: Job? = null

    /** The running resync loop (Swift `resyncTask`). */
    val resyncTask: Job? get() = synchronized(lock) { resyncJob }

    /** Attempts made by the current resync loop (Swift `resyncAttemptCount`). */
    val resyncAttemptCount: Int get() = synchronized(lock) { attemptCount }

    /** The running channel-only retry (Swift `channelRetryTask`). */
    val channelRetryTask: Job? get() = synchronized(lock) { channelRetryJob }

    private fun log(level: DebugLogLevel, message: String) = logSink.emit(level, SyncLogSink.RETRY_CATEGORY, message)

    private suspend inline fun <T> contain(label: String, fallback: T, block: () -> T): T =
        containing(logSink, SyncLogSink.RETRY_CATEGORY, label, fallback, block)

    // MARK: - Cancellation helpers

    /**
     * Cancels any resync loop in progress. The cancelled loop closes its activity bracket with
     * `succeeded = false` on its way out.
     */
    fun cancelResyncLoop() {
        val job = synchronized(lock) {
            attemptCount = 0
            resyncJob.also { resyncJob = null }
        }
        job?.cancel()
    }

    fun cancelChannelRetry() {
        synchronized(lock) { channelRetryJob.also { channelRetryJob = null } }?.cancel()
    }

    /** Swift `shouldPauseWiFiHeartbeatProbe`: no heartbeat probe while syncing or retrying. */
    val shouldPauseWiFiHeartbeatProbe: Boolean
        get() = host.connectionState == DeviceConnectionState.SYNCING || resyncTask != null || channelRetryTask != null

    /** Swift `detectAndStorePlatform`: WiFi implies an ESP32-class radio when the model is unrecognized. */
    fun detectAndStorePlatform(model: String, transportType: TransportType) {
        val platform = resolveSyncPlatform(model, transportType)
        host.storeDetectedPlatform(platform)
        if (transportType == TransportType.WIFI) log(DebugLogLevel.INFO, "[WiFi] Platform detected: $model -> ${platform.syncName}")
    }

    private fun isCurrent(services: SyncRetryServices): Boolean =
        host.connectionIntent.wantsConnection && host.connectionState.isOperational && host.currentServices === services

    // MARK: - Initial sync

    /**
     * Performs the initial sync and starts the resync loop on failure. Returns true when the sync produced
     * usable contacts. Swift's catch-all also absorbs cancellation; Kotlin rethrows it.
     */
    suspend fun performInitialSync(
        radioId: RadioId,
        services: SyncRetryServices,
        transportType: TransportType = TransportType.BLUETOOTH,
        context: String = "",
        forceFullSync: Boolean = false,
    ): Boolean {
        val config = currentChannelSyncConfig(radioId, transportType)
        val prefix = if (context.isEmpty()) "" else "$context: "
        try {
            val result = services.syncCoordinator.onConnectionEstablished(
                radioId, services.dependencies, forceFullSync, config, host.detectedPlatform.syncName,
            )
            if (result.channelRetryIndices.isNotEmpty()) scheduleChannelOnlyRetry(radioId, services, result.channelRetryIndices)
            if (result.isConnectionUsable) return true
            if (!host.connectionIntent.wantsConnection) return false
            log(DebugLogLevel.WARNING, "${prefix}Initial sync did not produce usable contacts, starting resync loop")
        } catch (failure: Exception) {
            failure.rethrowIfCallerCancelled()
            // Don't start resync if the user disconnected while sync was in progress.
            if (!host.connectionIntent.wantsConnection) return false
            log(DebugLogLevel.WARNING, "${prefix}Initial sync failed, starting resync loop: ${failure.syncDescription()}")
        }
        startResyncLoop(radioId, services, transportType, forceFullSync)
        return false
    }

    // MARK: - Resync loop

    /**
     * Retries the sync every [RESYNC_INTERVAL]; after [MAX_RESYNC_ATTEMPTS] failures reports
     * `onResyncFailed` and disconnects. Holds an outer activity bracket so the "Syncing" pill stays
     * visible across retries; every exit closes it exactly once.
     */
    fun startResyncLoop(
        radioId: RadioId,
        services: SyncRetryServices,
        transportType: TransportType = TransportType.BLUETOOTH,
        forceFullSync: Boolean = false,
    ) {
        val job = scope.launch(start = CoroutineStart.LAZY) { runResyncLoop(radioId, services, transportType, forceFullSync) }
        val previous = synchronized(lock) {
            attemptCount = 0
            resyncJob.also { resyncJob = job }
        }
        previous?.cancel()
        job.start()
    }

    private suspend fun runResyncLoop(
        radioId: RadioId,
        services: SyncRetryServices,
        transportType: TransportType,
        forceFullSync: Boolean,
    ) {
        val self = currentCoroutineContext().job
        val coordinator = services.syncCoordinator
        var bracketOpen = false
        var attempt = 0
        try {
            // Opened inside the job so the stored job is never observed missing during this await. The
            // open completes even if the loop is cancelled meanwhile, so the finally always balances it.
            withContext(NonCancellable) {
                coordinator.beginResyncActivity()
                bracketOpen = true
            }
            while (currentCoroutineContext().isActive) {
                clock.sleep(RESYNC_INTERVAL)
                // Fence on services identity: a loop orphaned by a reconnect must neither resync nor
                // disconnect against a container the manager has since replaced.
                if (!isCurrent(services)) break

                attempt += 1
                synchronized(lock) { if (resyncJob === self) attemptCount = attempt }
                log(DebugLogLevel.INFO, "Resync attempt $attempt/$MAX_RESYNC_ATTEMPTS")
                val config = currentChannelSyncConfig(radioId, transportType)
                val success = contain("performResync", false) {
                    coordinator.performResync(radioId, services.dependencies, forceFullSync, config, host.detectedPlatform.syncName)
                }

                if (success) {
                    log(DebugLogLevel.INFO, "Resync succeeded")
                    synchronized(lock) { if (resyncJob === self) attemptCount = 0 }
                    if (!currentCoroutineContext().isActive || !isCurrent(services)) break
                    // Promote before the post-sync hooks: room re-auth is multi-second work that isn't sync.
                    // The bracket close and promotion always complete together; the hooks stay cancellable.
                    withContext(NonCancellable) {
                        bracketOpen = false
                        coordinator.endResyncActivity(true)
                        contain("promoteToReadyAfterResync", Unit) { host.promoteToReadyAfterResync() }
                    }
                    runResyncSuccessHooks(radioId, services)
                    break
                }

                if (attempt >= MAX_RESYNC_ATTEMPTS) {
                    log(DebugLogLevel.WARNING, "Resync failed $MAX_RESYNC_ATTEMPTS times, disconnecting")
                    // The host's disconnect cancels this very loop; the teardown must still complete.
                    withContext(NonCancellable) {
                        bracketOpen = false
                        coordinator.endResyncActivity(false)
                        contain("onResyncFailed", Unit) { host.onResyncFailed() }
                        contain("disconnect(resyncFailed)", Unit) { host.disconnectAfterResyncFailure() }
                    }
                    break
                }
            }
        } catch (failure: Exception) {
            failure.rethrowIfCallerCancelled()
            log(DebugLogLevel.ERROR, "Resync loop failed: ${failure.syncDescription()}")
        } finally {
            // Catch-all for cancellation, guard exits and failures.
            if (bracketOpen) withContext(NonCancellable) { coordinator.endResyncActivity(false) }
            // Only the loop that still owns the slot clears it; a replacement loop is never destroyed.
            synchronized(lock) { if (resyncJob === self) resyncJob = null }
        }
    }

    /** Swift's post-promotion hooks, fenced on cancellation and the live graph between steps. */
    private suspend fun runResyncSuccessHooks(radioId: RadioId, services: SyncRetryServices) {
        contain("syncDeviceTimeIfNeeded", Unit) { host.syncDeviceTimeIfNeeded() }
        if (!currentCoroutineContext().isActive || !isCurrent(services)) return

        // Re-authenticate room sessions before onDeviceSynced to avoid radio contention.
        val sessionIDs = contain("sessionsAwaitingReauth", emptySet()) { host.sessionsAwaitingReauth() }
        if (sessionIDs.isNotEmpty()) {
            contain("handleBLEReconnection", Unit) {
                services.remoteNodeService.handleBLEReconnection(sessionIDs.map { EntityKey(radioId, it) }.toSet())
            }
        }
        if (!currentCoroutineContext().isActive || !isCurrent(services)) return

        // Only clear consumed ids after confirming the loop is still valid.
        contain("consumeSessionsAwaitingReauth", Unit) { host.consumeSessionsAwaitingReauth(sessionIDs) }
        contain("onDeviceSynced", Unit) { host.onDeviceSynced() }
    }

    // MARK: - Channel-only retry

    /**
     * Schedules a bounded channel-only retry after a partial channel phase: up to
     * [MAX_CHANNEL_RETRY_ATTEMPTS] attempts with 2 s then 4 s backoff, retrying only still-retryable slots.
     */
    fun scheduleChannelOnlyRetry(radioId: RadioId, services: SyncRetryServices, indices: List<UByte>) {
        val initialIndices = indices.toSet().sorted()
        if (initialIndices.isEmpty()) return
        val job = scope.launch(start = CoroutineStart.LAZY) { runChannelRetry(radioId, services, initialIndices) }
        val previous = synchronized(lock) { channelRetryJob.also { channelRetryJob = job } }
        previous?.cancel()
        job.start()
    }

    private suspend fun runChannelRetry(radioId: RadioId, services: SyncRetryServices, initialIndices: List<UByte>) {
        val self = currentCoroutineContext().job
        var pending = initialIndices
        try {
            for (attempt in 1..MAX_CHANNEL_RETRY_ATTEMPTS) {
                clock.sleep(channelRetryDelay(attempt))
                if (!isCurrent(services)) break
                log(DebugLogLevel.INFO, "Channel-only retry $attempt/$MAX_CHANNEL_RETRY_ATTEMPTS for ${pending.size} channel(s)")
                val result = contain("retryChannels", null) {
                    services.syncCoordinator.retryChannels(radioId, services.dependencies.channelService, pending)
                } ?: break
                if (result.isComplete) {
                    log(DebugLogLevel.INFO, "Channel-only retry recovered all pending channels")
                    pending = emptyList()
                    break
                }
                pending = result.retryableIndices
                if (pending.isEmpty()) {
                    log(DebugLogLevel.WARNING, "Channel-only retry stopped with non-retryable channel errors")
                    break
                }
            }
            if (pending.isNotEmpty()) {
                log(DebugLogLevel.WARNING, "Channel-only retry exhausted with ${pending.size} retryable channel(s) still pending")
            }
        } catch (failure: Exception) {
            failure.rethrowIfCallerCancelled()
            log(DebugLogLevel.ERROR, "Channel-only retry failed: ${failure.syncDescription()}")
        } finally {
            synchronized(lock) { if (channelRetryJob === self) channelRetryJob = null }
        }
    }

    // MARK: - Channel sync configuration

    /**
     * Builds a channel sync config for the current device and transport. The pipelined read is the policy
     * half of a two-gate design: nRF52 over BLE and ESP32 over WiFi; ESP32 over BLE stays serial.
     */
    fun currentChannelSyncConfig(radioId: RadioId, transportType: TransportType): ChannelSyncConfig {
        val platform = host.detectedPlatform
        val usePipelinedChannelRead = (platform == DevicePlatform.NRF52 && transportType == TransportType.BLUETOOTH) ||
            (platform == DevicePlatform.ESP32 && transportType == TransportType.WIFI)
        val clean = host.lastCleanChannelSync
        val attempted = host.lastAttemptedChannelSync
        return platform.channelSyncConfig(
            lastCleanChannelSync = clean?.takeIf { it.first == radioId }?.second,
            lastAttemptedChannelSync = attempted?.takeIf { it.first == radioId }?.second,
            usePipelinedChannelRead = usePipelinedChannelRead,
        )
    }

    companion object {
        /** Maximum resync attempts before giving up. */
        const val MAX_RESYNC_ATTEMPTS = 3

        /** Interval between resync attempts. */
        val RESYNC_INTERVAL: Duration = 2.seconds

        const val MAX_CHANNEL_RETRY_ATTEMPTS = 2
        val CHANNEL_RETRY_INITIAL_DELAY: Duration = 2.seconds

        /** Swift `max(channelRetryInitialDelay, .seconds(2 << (attempt - 1)))`: 2 s, then 4 s. */
        fun channelRetryDelay(attempt: Int): Duration = maxOf(CHANNEL_RETRY_INITIAL_DELAY, (2 shl (attempt - 1)).seconds)
    }
}

/** Resolves the platform at connect: an unrecognized model over WiFi is ESP32-class. */
fun resolveSyncPlatform(model: String, transportType: TransportType): DevicePlatform {
    val platform = DevicePlatform.detect(model)
    return if (transportType == TransportType.WIFI && platform == DevicePlatform.UNKNOWN) DevicePlatform.ESP32 else platform
}

/** Swift `"\(detectedPlatform)"` for instrumentation logging. */
val DevicePlatform.syncName: String
    get() = when (this) {
        DevicePlatform.ESP32 -> "esp32"
        DevicePlatform.NRF52 -> "nrf52"
        DevicePlatform.UNKNOWN -> "unknown"
    }
