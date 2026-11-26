// PortedFrom: MC1/Views/RemoteNodes/NodeStatusRoute.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.status

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.NodeLocationFix
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText

/**
 * History drill-downs pushed from the shared status sections (Swift `NodeStatusRoute`). Value-typed so
 * each push rebuilds its destination. The location map carries only the fix and the node name; the
 * screen builds its single latest-fix pin titled [LocationMap.TITLE].
 */
sealed interface NodeStatusRoute {
    data object StatusHistory : NodeStatusRoute
    data object TelemetryHistory : NodeStatusRoute
    data class NeighborChart(val name: RemoteNodesText, val neighborPrefix: Bytes) : NodeStatusRoute
    data class LocationMap(val fix: NodeLocationFix, val name: String?) : NodeStatusRoute {
        companion object {
            /** Swift `L10n...Status.locationMapTitle`. */
            val TITLE: Int get() = AppRemoteNodesStrings.remoteNodesStatusLocationMapTitle
        }
    }
}
