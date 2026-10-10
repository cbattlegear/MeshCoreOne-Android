// AndroidOnly: WP-311 Neutral navigation callbacks keep the feature independent of the app shell.
package com.meshcoreone.android.feature.nodes

import com.meshcoreone.android.core.model.ContactDTO

interface NodesNavigation {
    fun openContact(contact: ContactDTO)
    fun openDiscovery()
    fun openChat(contact: ContactDTO)
    fun openMap(latitude: Double, longitude: Double)
    fun openRemoteNode(contact: ContactDTO, action: RemoteNodeAction)
    fun back()

    companion object {
        val NONE = object : NodesNavigation {
            override fun openContact(contact: ContactDTO) = Unit
            override fun openDiscovery() = Unit
            override fun openChat(contact: ContactDTO) = Unit
            override fun openMap(latitude: Double, longitude: Double) = Unit
            override fun openRemoteNode(contact: ContactDTO, action: RemoteNodeAction) = Unit
            override fun back() = Unit
        }

    }
}

enum class RemoteNodeAction { MANAGEMENT, TELEMETRY, SAVED_HISTORY, JOIN_ROOM }

sealed interface NodesDestination {
    data object List : NodesDestination
    data object Discovery : NodesDestination
    data class Detail(val contact: ContactDTO) : NodesDestination
}
