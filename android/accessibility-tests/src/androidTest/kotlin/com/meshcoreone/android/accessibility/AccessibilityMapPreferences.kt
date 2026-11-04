// AndroidOnly: WP-312 Test-only observable map preferences for native accessibility consumers.
package com.meshcoreone.android.accessibility

import com.meshcoreone.android.core.maps.MapColorPreference
import com.meshcoreone.android.core.maps.MapFilterHost
import com.meshcoreone.android.core.maps.MapFilterState
import com.meshcoreone.android.core.maps.MapPreferenceStore
import com.meshcoreone.android.core.maps.MapPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class AccessibilityMapPreferences : MapPreferenceStore {
    private val values = MapFilterHost.entries.associateWith { host ->
        MutableStateFlow(
            MapPreferences(
                filter = MapFilterState.seed(host),
                clustering = true,
                labels = true,
                appearance = MapColorPreference.SYSTEM,
            ),
        )
    }

    override fun observe(host: MapFilterHost): Flow<MapPreferences> = values.getValue(host)

    override suspend fun setFilter(host: MapFilterHost, filter: MapFilterState) {
        values.getValue(host).update { it.copy(filter = filter.sanitized(host)) }
    }

    override suspend fun updateFilter(host: MapFilterHost, transform: (MapFilterState) -> MapFilterState) {
        values.getValue(host).update { current ->
            current.copy(filter = transform(current.filter).sanitized(host))
        }
    }

    override suspend fun setClustering(enabled: Boolean) {
        values.values.forEach { state -> state.update { it.copy(clustering = enabled) } }
    }

    override suspend fun setLabels(enabled: Boolean) {
        values.values.forEach { state -> state.update { it.copy(labels = enabled) } }
    }
}
