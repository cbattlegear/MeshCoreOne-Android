// PortedFrom: MC1/Views/Components/RSSIScanTracker.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: caller-owned scan handles, never manufactured persistent radio UUIDs.
package com.meshcoreone.android.core.ui

import com.meshcoreone.android.core.model.SnapshotMap
import com.meshcoreone.android.core.model.snapshotMap
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ScanDiscovery<Id : Any>(val id: Id, val name: String?, val rssi: Long)
data class ScannedSignal<Id : Any>(
    val discovery: ScanDiscovery<Id>,
    val tier: RSSITuning.SignalTier,
    val lastSeen: Instant,
)

class RSSIScanTracker<Id : Any>(private val clock: Clock) {
    private val mutex = Mutex()
    private val consumption = Mutex()
    private val mutableDevices = MutableStateFlow(emptyMap<Id, ScannedSignal<Id>>().snapshotMap())
    val devices: StateFlow<SnapshotMap<Id, ScannedSignal<Id>>> = mutableDevices.asStateFlow()

    fun signalTier(id: Id): RSSITuning.SignalTier? = devices.value[id]?.tier
    fun isAdvertising(id: Id): Boolean = devices.value.containsKey(id)

    suspend fun ingest(discovery: ScanDiscovery<Id>) {
        if (!RSSITuning.isUsable(discovery.rssi)) return
        mutex.withLock {
            val previous = devices.value[discovery.id]
            val smoothed = RSSITuning.smooth(discovery.rssi, previous?.discovery?.rssi)
            val next = devices.value.toMutableMap()
            next[discovery.id] = ScannedSignal(
                discovery.copy(name = discovery.name ?: previous?.discovery?.name, rssi = smoothed),
                RSSITuning.tier(previous?.tier, smoothed), clock.instant(),
            )
            mutableDevices.value = next.snapshotMap()
        }
    }

    suspend fun expireStale(asOf: Instant = clock.instant()) {
        mutex.withLock {
            val cutoff = asOf.minus(RSSITuning.staleWindow)
            mutableDevices.value = devices.value.filterValues { it.lastSeen >= cutoff }.snapshotMap()
        }
    }

    suspend fun consume(discoveries: Flow<ScanDiscovery<Id>>) = consumption.withLock {
        coroutineScope {
            val expiry = launch {
                while (true) {
                    delay(RSSITuning.expiryTick.toMillis())
                    expireStale()
                }
            }
            try {
                discoveries.collect(::ingest)
            } finally {
                expiry.cancelAndJoin()
            }
        }
    }
}
