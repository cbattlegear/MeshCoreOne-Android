// PortedFrom: MC1/Views/Map/MapFilter.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// WP-312 Process DataStore adapter and migration of the former map-private keys.
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
import com.meshcoreone.android.core.maps.MapStyle
import com.meshcoreone.android.core.maps.MapLayerAvailability
import com.meshcoreone.android.core.maps.MapLibreOpenFreeMap
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
                val appearance = MapColorPreference.fromRaw(rawAppearance)
                val rawStyle = values[AppStorageKey.mapStyleSelection]
                val style = MapStyle.entries.firstOrNull { it.name.lowercase() == rawStyle }
                    ?.takeIf { MapLibreOpenFreeMap.catalog.layers.getValue(it).availability == MapLayerAvailability.Available }
                if (style == null) {
                    Logger.getLogger("com.meshcoreone.android.MapPreferences")
                        .warning("Stored map style is unsupported; rendering standard without overwriting stored data")
                }
                emit(MapPreferences(
                    resolved.state, values[AppStorageKey.mapClusteringEnabled],
                    values[AppStorageKey.mapShowLabels], appearance, values[AppStorageKey.mapNorthLocked],
                    style ?: MapStyle.STANDARD,
                ))
            }
        }
    }.distinctUntilChanged()

    override suspend fun setFilter(host: MapFilterHost, filter: MapFilterState) {
        store.set(host.key, MapFilterPreferences.encode(filter, host))
    }

    override suspend fun updateFilter(host: MapFilterHost, transform: (MapFilterState) -> MapFilterState) {
        store.update {
            val current = MapFilterPreferences.migrate(
                snapshot.stored(host.key), snapshot.stored(AppStorageKey.showDiscoveredNodesOnMap), host,
            ).state
            this[host.key] = MapFilterPreferences.encode(transform(current), host)
        }
    }

    override suspend fun setClustering(enabled: Boolean) {
        store.set(AppStorageKey.mapClusteringEnabled, enabled)
    }

    override suspend fun setLabels(enabled: Boolean) {
        store.set(AppStorageKey.mapShowLabels, enabled)
    }

    override suspend fun setNorthLocked(enabled: Boolean) {
        store.set(AppStorageKey.mapNorthLocked, enabled)
    }

    override suspend fun setStyle(style: MapStyle) {
        require(MapLibreOpenFreeMap.catalog.layers.getValue(style).availability == MapLayerAvailability.Available) {
            "Map style is unavailable from the configured provider"
        }
        store.set(AppStorageKey.mapStyleSelection, style.name.lowercase())
    }

    private suspend fun migratePrivatePreferences() {
        val values = legacy.all
        val raw = values["filter-v1"] as? String
        val clustering = values["cluster-v1"] as? Boolean
        val labels = values["labels-v1"] as? Boolean
        val legacyKeys = listOf("filter-v1", "cluster-v1", "labels-v1")
        val malformedKeys = legacyKeys.filter { key ->
            values.containsKey(key) && when (key) {
                "filter-v1" -> values[key] !is String
                else -> values[key] !is Boolean
            }
        }
        if (malformedKeys.isNotEmpty()) {
            Logger.getLogger("com.meshcoreone.android.MapPreferences")
                .warning("Ignoring malformed private map preference keys: ${malformedKeys.joinToString()}")
        }
        if (legacyKeys.none(values::containsKey)) return
        if (raw != null || clustering != null || labels != null) {
            store.update {
                if (!snapshot.contains(AppStorageKey.mapFilterMainMap) && raw != null) {
                    val migrated = MapFilterPreferences.migrate(raw, null, MapFilterHost.MAIN_MAP).state
                    val resolved = snapshot.stored(AppStorageKey.showDiscoveredNodesOnMap)
                        ?.let { migrated.copy(showDiscovered = it) }
                        ?: migrated
                    this[AppStorageKey.mapFilterMainMap] =
                        MapFilterPreferences.encode(resolved, MapFilterHost.MAIN_MAP)
                }
                if (!snapshot.contains(AppStorageKey.mapClusteringEnabled) && clustering != null) {
                    this[AppStorageKey.mapClusteringEnabled] = clustering
                }
                if (!snapshot.contains(AppStorageKey.mapShowLabels) && labels != null) {
                    this[AppStorageKey.mapShowLabels] = labels
                }
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
