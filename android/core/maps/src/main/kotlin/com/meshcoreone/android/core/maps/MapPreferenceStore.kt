// PortedFrom: MC1/Views/Map/MapFilter.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Map/MapAppearance.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.maps

import kotlinx.coroutines.flow.Flow

data class MapPreferences(
    val filter: MapFilterState,
    val clustering: Boolean,
    val labels: Boolean,
    val appearance: MapColorPreference,
    val northLocked: Boolean = false,
    val style: MapStyle = MapStyle.STANDARD,
)

interface MapPreferenceStore {
    fun observe(host: MapFilterHost): Flow<MapPreferences>
    suspend fun setFilter(host: MapFilterHost, filter: MapFilterState)
    suspend fun updateFilter(host: MapFilterHost, transform: (MapFilterState) -> MapFilterState)
    suspend fun setClustering(enabled: Boolean)
    suspend fun setLabels(enabled: Boolean)
    suspend fun setNorthLocked(enabled: Boolean)
    suspend fun setStyle(style: MapStyle)
}
