// AndroidOnly: WP-312 Test fixture for mounted remote-node maps.
package com.meshcoreone.android.feature.remotenodes.ui

import com.meshcoreone.android.core.maps.MapColorPreference
import com.meshcoreone.android.core.maps.MapFilterHost
import com.meshcoreone.android.core.maps.MapFilterState
import com.meshcoreone.android.core.maps.MapPreferenceStore
import com.meshcoreone.android.core.maps.MapPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal fun remoteMapPreferencesForTest(host: MapFilterHost): MapPreferenceStore =
    object : MapPreferenceStore {
        private val state = MutableStateFlow(
            MapPreferences(MapFilterState.seed(host), true, true, MapColorPreference.SYSTEM),
        )

        override fun observe(host: MapFilterHost): Flow<MapPreferences> = state
        override suspend fun setFilter(host: MapFilterHost, filter: MapFilterState) {
            state.update { it.copy(filter = filter) }
        }
        override suspend fun updateFilter(host: MapFilterHost, transform: (MapFilterState) -> MapFilterState) {
            state.update { it.copy(filter = transform(it.filter)) }
        }
        override suspend fun setClustering(enabled: Boolean) {
            state.update { it.copy(clustering = enabled) }
        }
        override suspend fun setLabels(enabled: Boolean) {
            state.update { it.copy(labels = enabled) }
        }
    }
