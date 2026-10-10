// AndroidOnly: WP-313 Process-owned catalog and generation-bound service inputs for the native management route.
package com.meshcoreone.android.feature.remotenodes.dependencies

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import kotlinx.coroutines.flow.Flow

data class RemoteNodesCatalog(
    val contacts: List<ContactDTO>,
    val discoveredNodes: List<DiscoveredNodeDTO>,
    val sessions: List<RemoteNodeSessionDTO>,
)

data class RemoteNodesConnection(val radioId: RadioId?, val generation: String?, val ready: Boolean)

data class RemoteRadioOptions(val bandwidthsKHz: List<Double>, val spreadingFactors: List<Long>, val codingRates: List<Long>)

interface RemoteNodesUiDependencies {
    val updates: Flow<RemoteNodesConnection>
    val radioOptions: RemoteRadioOptions
    suspend fun catalog(): RemoteNodesCatalog
    fun services(): RemoteNodesFeatureDependencies?
}
