// PortedFrom: MC1/Views/Map/MapFilter.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Map/MapAppearance.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.maps

import kotlinx.coroutines.flow.Flow

data class MapPreferences(
    val filter: MapFilterState,
    val clustering: Boolean,
    val labels: Boolean,
    val appearance: MapColorPreference,
)

interface MapPreferenceStore {
    fun observe(host: MapFilterHost): Flow<MapPreferences>
    suspend fun setFilter(host: MapFilterHost, filter: MapFilterState)
    suspend fun updateFilter(host: MapFilterHost, transform: (MapFilterState) -> MapFilterState)
    suspend fun setClustering(enabled: Boolean)
    suspend fun setLabels(enabled: Boolean)
}
