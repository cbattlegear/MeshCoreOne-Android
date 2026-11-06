// PortedFrom: MC1/State/BatteryMonitor.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.state

import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.OCVPreset
import com.meshcoreone.android.core.protocol.model.BatteryInfo
import com.meshcoreone.android.core.runtime.DeadlineClock
import com.meshcoreone.android.core.runtime.SystemRuntimeClock
import com.meshcoreone.android.core.ui.isBatteryPresent
import com.meshcoreone.android.core.ui.percentage
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The slice of the per-connection service graph battery monitoring reads (settings + notifications). */
interface BatteryServices {
    suspend fun getBattery(): BatteryInfo
    suspend fun postLowBatteryNotification(deviceName: String, batteryPercentage: Long)
}

internal class RetiredBatterySource : CancellationException("Battery source has retired")

/**
 * Device battery polling, threshold checks and low-battery notifications. One bootstrap job and one refresh
 * loop at most: starting again replaces (cancels) the previous job, so a reconnect cannot leave a duplicate
 * monitor polling the radio.
 */
class BatteryMonitor(
    private val scope: CoroutineScope,
    private val clock: DeadlineClock = SystemRuntimeClock(),
    private val now: () -> Instant = Instant::now,
) {
    private val lock = Any()
    private val battery = MutableStateFlow<BatteryInfo?>(null)
    private var bootstrapJob: Job? = null
    private var refreshJob: Job? = null
    private var notifiedThresholds: Set<Long> = emptySet()
    private var lastSuccessfulFetch: Instant? = null

    /** Current device battery info (null if not fetched or disconnected). */
    var deviceBattery: BatteryInfo?
        get() = battery.value
        set(value) { battery.value = value }
    val deviceBatteryFlow: StateFlow<BatteryInfo?> = battery.asStateFlow()

    /** Called when battery info is updated (Live Activity analog). */
    @Volatile var onBatteryChanged: ((BatteryInfo) -> Unit)? = null
    /** AppState binds this non-suspending phase to the same monitor as consumer replacement. */
    internal var withCurrentServices: (BatteryServices, () -> Unit) -> Unit = { _, update -> update() }
    internal var afterBatteryReadForTesting: (suspend (BatteryServices) -> Unit)? = null

    /** Whether a refresh loop job is active (test visibility for duplicate-monitor checks). */
    val isRefreshLoopActive: Boolean get() = synchronized(lock) { refreshJob?.isActive == true }
    val isBootstrapActive: Boolean get() = synchronized(lock) { bootstrapJob?.isActive == true }

    fun activeBatteryOcvArray(device: DeviceDTO?): List<Long> =
        device?.activeOCVArray ?: OCVPreset.LI_ION.ocvArray

    /** Fetch device battery level on demand. */
    suspend fun fetchDeviceBattery(services: BatteryServices?, device: DeviceDTO?) {
        val source = services ?: return
        val context = currentCoroutineContext()
        try {
            val info = source.getBattery()
            publishBattery(source, info, context, recordFetch = true)
            checkBatteryThresholds(device, source, info)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            publishBattery(source, null, context)
        }
    }

    /** Fetch only when enough time has passed since the last success (packet-reception piggyback). */
    suspend fun fetchBatteryIfOverdue(services: BatteryServices?, device: DeviceDTO?) {
        val last = synchronized(lock) { lastSuccessfulFetch }
        if (last != null && Duration.between(last, now()).seconds < BACKGROUND_BATTERY_INTERVAL_SECONDS) return
        fetchDeviceBattery(services, device)
    }

    /** Starts monitoring for a newly connected device; bootstrap is deferred so connect is never blocked. */
    fun start(services: BatteryServices, device: DeviceDTO?): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val context = currentCoroutineContext()
            try {
                val info = services.getBattery()
                publishBattery(services, info, context, recordFetch = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                publishBattery(services, null, context)
            }
            if (!isActive) return@launch
            initializeBatteryThresholds(device, services)
            startRefreshLoop(services, device)
        }
        var installed = false
        var previous: Job? = null
        try {
            withCurrentServices(services) {
                previous = synchronized(lock) { bootstrapJob.also { bootstrapJob = job } }
                installed = true
            }
        } finally {
            if (!installed) job.cancel()
        }
        previous?.cancel()
        job.start()
        return job
    }

    /** Stops monitoring (disconnect or background). */
    fun stop() {
        val cancelled = synchronized(lock) {
            listOfNotNull(bootstrapJob, refreshJob).also {
                bootstrapJob = null
                refreshJob = null
            }
        }
        cancelled.forEach(Job::cancel)
    }

    /** Clears notification thresholds for a fresh connection. */
    fun clearThresholds() = synchronized(lock) { notifiedThresholds = emptySet() }

    /** Posts a single notification when thresholds were crossed while the app was backgrounded. */
    suspend fun checkMissedBatteryThreshold(device: DeviceDTO?, services: BatteryServices?) {
        val target = device ?: return
        val source = services ?: return
        val context = currentCoroutineContext()
        val info = try {
            source.getBattery().also { publishBattery(source, it, context) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            afterBatteryReadForTesting?.invoke(source)
            return
        }
        if (!info.isBatteryPresent) return
        val percentage = info.percentage(target.activeOCVArray).toLong()
        var missed = emptyList<Long>()
        withCurrentServices(source) {
            context.ensureActive()
            missed = synchronized(lock) {
                val crossed = WARNING_THRESHOLDS.filter { percentage <= it && it !in notifiedThresholds }
                notifiedThresholds = notifiedThresholds + crossed
                crossed
            }
        }
        if (missed.isEmpty()) return
        source.postLowBatteryNotification(target.nodeName, percentage)
    }

    /** Restarts the refresh loop (for example returning to foreground), replacing any running loop. */
    fun startRefreshLoop(services: BatteryServices, device: DeviceDTO?) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            while (isActive) {
                clock.sleep(REFRESH_INTERVAL)
                fetchDeviceBattery(services, device)
            }
        }
        var installed = false
        var previous: Job? = null
        try {
            withCurrentServices(services) {
                previous = synchronized(lock) { refreshJob.also { refreshJob = job } }
                installed = true
            }
        } finally {
            if (!installed) job.cancel()
        }
        previous?.cancel()
        job.start()
    }

    private fun notifyChanged(info: BatteryInfo) {
        try {
            onBatteryChanged?.invoke(info)
        } catch (failure: Exception) {
            // The Swift callback cannot throw; a throwing observer must not end polling.
        }
    }

    private suspend fun publishBattery(
        source: BatteryServices, info: BatteryInfo?, context: CoroutineContext, recordFetch: Boolean = false,
    ) {
        afterBatteryReadForTesting?.invoke(source)
        withCurrentServices(source) {
            context.ensureActive()
            if (recordFetch) synchronized(lock) { lastSuccessfulFetch = now() }
            deviceBattery = info
            if (info != null) {
                withCurrentServices(source) {
                    context.ensureActive()
                    notifyChanged(info)
                }
            }
        }
    }

    private suspend fun initializeBatteryThresholds(device: DeviceDTO?, services: BatteryServices) {
        val context = currentCoroutineContext()
        var notification: Pair<String, Long>? = null
        withCurrentServices(services) {
            context.ensureActive()
            val info = deviceBattery
            var crossed = emptyList<Long>()
            if (info != null && device != null && info.isBatteryPresent) {
                val percentage = info.percentage(device.activeOCVArray).toLong()
                crossed = WARNING_THRESHOLDS.filter { percentage <= it }
                if (crossed.isNotEmpty()) notification = device.nodeName to percentage
            }
            synchronized(lock) { notifiedThresholds = crossed.toSet() }
        }
        notification?.let { (name, percentage) -> services.postLowBatteryNotification(name, percentage) }
    }

    private suspend fun checkBatteryThresholds(device: DeviceDTO?, services: BatteryServices, info: BatteryInfo) {
        val target = device ?: return
        if (!info.isBatteryPresent) return
        val context = currentCoroutineContext()
        val percentage = info.percentage(target.activeOCVArray).toLong()
        for (threshold in WARNING_THRESHOLDS) {
            var crossedDown = false
            withCurrentServices(services) {
                context.ensureActive()
                crossedDown = synchronized(lock) {
                    when {
                        percentage <= threshold && threshold !in notifiedThresholds -> {
                            notifiedThresholds = notifiedThresholds + threshold
                            true
                        }
                        percentage > threshold && threshold in notifiedThresholds -> {
                            notifiedThresholds = notifiedThresholds - threshold
                            false
                        }
                        else -> false
                    }
                }
            }
            // Swift breaks only after posting; a recovered threshold keeps scanning the lower ones.
            if (crossedDown) {
                services.postLowBatteryNotification(target.nodeName, percentage)
                break
            }
        }
    }

    companion object {
        val WARNING_THRESHOLDS: List<Long> = listOf(20L, 10L, 5L)
        const val BACKGROUND_BATTERY_INTERVAL_SECONDS: Long = 900
        val REFRESH_INTERVAL = 120.seconds
    }
}
