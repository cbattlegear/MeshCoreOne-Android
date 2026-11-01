// PortedFrom: MC1/Views/Map/MapFilter.swift@db14559b39d32322b06477c6ae676112f583db50
// AndroidOnly: WP-312 Process DataStore adapter and migration of the former map-private keys.
package com.meshcoreone.android.app.container

import android.content.SharedPreferences
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.PreferenceKey
import com.meshcoreone.android.core.datastore.PreferenceStore
import com.meshcoreone.android.core.maps.MapColorPreference
import com.meshcoreone.android.core.maps.MapFilterHost
import com.meshcoreone.android.core.maps.MapFilterPreferences
import com.meshcoreone.android.core.maps.MapFilterState
import com.meshcoreone.android.core.maps.MapPreferenceStore
import com.meshcoreone.android.core.maps.MapPreferences
import java.io.IOException
import java.util.logging.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

internal class AppMapPreferences(
    private val store: PreferenceStore,
    private val legacy: SharedPreferences,
) : MapPreferenceStore {
    override fun observe(host: MapFilterHost): Flow<MapPreferences> = flow {
        migratePrivatePreferences()
        store.snapshots.collect { values ->
            val key = host.key
            val resolved = MapFilterPreferences.migrate(
                values.stored(key), values.stored(AppStorageKey.showDiscoveredNodesOnMap), host,
            )
            if (resolved.replacement != null) {
                // Resolve again inside the transaction so a concurrent restore/user edit wins.
                store.update {
                    val current = MapFilterPreferences.migrate(
                        snapshot.stored(key), snapshot.stored(AppStorageKey.showDiscoveredNodesOnMap), host,
                    )
                    current.replacement?.let { this[key] = it }
                }
            } else {
                val rawAppearance = values[AppStorageKey.mapColorSchemePreference]
                val appearance = when (rawAppearance) {
                    "system" -> MapColorPreference.SYSTEM
                    "light" -> MapColorPreference.LIGHT
                    "dark" -> MapColorPreference.DARK
                    else -> {
                        Logger.getLogger("com.meshcoreone.android.MapPreferences")
                            .warning("Unknown map appearance value; using system without rewriting stored data")
                        MapColorPreference.SYSTEM
                    }
                }
                emit(MapPreferences(
                    resolved.state, values[AppStorageKey.mapClusteringEnabled],
                    values[AppStorageKey.mapShowLabels], appearance,
                ))
            }
        }
    }.distinctUntilChanged()

    override suspend fun setFilter(host: MapFilterHost, filter: MapFilterState) {
        store.set(host.key, MapFilterPreferences.encode(filter, host))
    }

    override suspend fun setClustering(enabled: Boolean) {
        store.set(AppStorageKey.mapClusteringEnabled, enabled)
    }

    override suspend fun setLabels(enabled: Boolean) {
        store.set(AppStorageKey.mapShowLabels, enabled)
    }

    private suspend fun migratePrivatePreferences() {
        val raw = legacy.getString("filter-v1", null)
        val clustering = if (legacy.contains("cluster-v1")) legacy.getBoolean("cluster-v1", true) else null
        val labels = if (legacy.contains("labels-v1")) legacy.getBoolean("labels-v1", true) else null
        if (raw == null && clustering == null && labels == null) return
        store.update {
            if (!snapshot.contains(AppStorageKey.mapFilterMainMap) &&
                !snapshot.contains(AppStorageKey.showDiscoveredNodesOnMap) && raw != null
            ) {
                this[AppStorageKey.mapFilterMainMap] = MapFilterPreferences.migrate(
                    raw, null, MapFilterHost.MAIN_MAP,
                ).state.encode()
            }
            if (!snapshot.contains(AppStorageKey.mapClusteringEnabled) && clustering != null) {
                this[AppStorageKey.mapClusteringEnabled] = clustering
            }
            if (!snapshot.contains(AppStorageKey.mapShowLabels) && labels != null) {
                this[AppStorageKey.mapShowLabels] = labels
            }
        }
        if (!legacy.edit().remove("filter-v1").remove("cluster-v1").remove("labels-v1").commit()) {
            throw IOException("Unable to retire private map preferences after migration")
        }
    }
}

private val MapFilterHost.key: PreferenceKey.StringKey
    get() = when (this) {
        MapFilterHost.MAIN_MAP -> AppStorageKey.mapFilterMainMap
        MapFilterHost.TRACE_PATH -> AppStorageKey.mapFilterTracePath
        MapFilterHost.NEIGHBOR_SNR -> AppStorageKey.mapFilterNeighborSNR
    }
