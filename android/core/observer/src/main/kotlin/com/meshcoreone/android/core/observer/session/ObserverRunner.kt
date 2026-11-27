// AndroidOnly: WP-101 Prizm Mobile Observer service logic (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.session

import com.meshcoreone.android.core.observer.location.LocationFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ObserverMode { OFF, WAITING_FOR_NODE, UPLOADING }

/** What the notification and the settings screen show. */
data class ObserverStatus(
    val mode: ObserverMode = ObserverMode.OFF,
    val counters: ObserverCounters = ObserverCounters(),
    val gateText: String? = null,
)

/**
 * Keeps exactly one [ObserverSession] alive while uploading is switched on and a node link exists, and none otherwise.
 * A different link (a new connection) replaces the session. Counters are
 * per Android service run: they restart from zero when uploading is switched on, and carry across node reconnects.
 */
class ObserverRunner(
    private val scope: CoroutineScope,
    private val createSession: (ObserverNodeLink) -> ObserverSession,
) {
    private val mutex = Mutex()
    private var session: ObserverSession? = null
    private var link: ObserverNodeLink? = null
    private var mirror: Job? = null
    private var screenOn = true
    private var carried = ObserverCounters()
    private val statusState = MutableStateFlow(ObserverStatus())
    val status: StateFlow<ObserverStatus> = statusState.asStateFlow()

    /** Idempotent: call whenever the switch or the node link changes. */
    suspend fun update(enabled: Boolean, nodeLink: ObserverNodeLink?) = mutex.withLock {
        val wanted = if (enabled) nodeLink else null
        if (wanted === link && (wanted == null || session?.isActive == true)) {
            if (!enabled) statusState.value = ObserverStatus()
            else if (wanted == null) statusState.value = statusState.value.copy(mode = ObserverMode.WAITING_FOR_NODE)
            return@withLock
        }
        stopLocked()
        if (!enabled) { carried = ObserverCounters(); statusState.value = ObserverStatus(); return@withLock }
        if (wanted == null) {
            statusState.value = ObserverStatus(ObserverMode.WAITING_FOR_NODE, carried)
            return@withLock
        }
        val fresh = createSession(wanted)
        session = fresh; link = wanted
        fresh.setScreenOn(screenOn)
        fresh.start(scope)
        mirror = scope.launch { mirrorStatus(fresh) }
    }

    fun onFix(fix: LocationFix) { session?.onFix(fix) }

    fun setScreenOn(on: Boolean) { screenOn = on; session?.setScreenOn(on) }

    /** Ends the current session (offline status sent) without changing the carried counters. */
    suspend fun shutdown() = mutex.withLock { stopLocked(); carried = ObserverCounters(); statusState.value = ObserverStatus() }

    private suspend fun stopLocked() {
        mirror?.cancel(); mirror = null
        session?.let { carried = it.counters.value.let { c -> carried.plus(c) }; it.stop() }
        session = null; link = null
    }

    private suspend fun mirrorStatus(active: ObserverSession) {
        val base = carried
        kotlinx.coroutines.flow.combine(active.counters, active.gateText) { c, g -> ObserverStatus(ObserverMode.UPLOADING, base.plus(c), g) }
            .collect { statusState.value = it }
    }

    private fun ObserverCounters.plus(other: ObserverCounters) = ObserverCounters(
        heard + other.heard, uploaded + other.uploaded, metadataOnly + other.metadataOnly, other.queued, withheld + other.withheld,
    )
}
