// AndroidOnly: WP-313 Process-owned catalog and generation-bound service inputs for the native management route.
package com.meshcoreone.android.feature.remotenodes.dependencies

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class RemoteNodesCatalog(
    val contacts: List<ContactDTO>,
    val discoveredNodes: List<DiscoveredNodeDTO>,
    val sessions: List<RemoteNodeSessionDTO>,
)

data class RemoteNodesConnection(val radioId: RadioId?, val generation: String?, val ready: Boolean)

data class RemoteRadioOptions(val bandwidthsKHz: List<Double>, val spreadingFactors: List<Long>, val codingRates: List<Long>)

interface RemoteNodesUiDependencies {
    val mapPreferences: com.meshcoreone.android.core.maps.MapPreferenceStore? get() = null
    val bestAvailableLocation: com.meshcoreone.android.core.model.Coordinate? get() = null
    /** Foreground phone fixes and current-radio fallback; snapshot-only adapters retain their existing value. */
    val locations: Flow<com.meshcoreone.android.core.model.Coordinate?>
        get() = flow { emit(bestAvailableLocation) }
    val updates: Flow<RemoteNodesConnection>
    val radioOptions: RemoteRadioOptions
    suspend fun catalog(): RemoteNodesCatalog
    fun services(): RemoteNodesFeatureDependencies?
}
