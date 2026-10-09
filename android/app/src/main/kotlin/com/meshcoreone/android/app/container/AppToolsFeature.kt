// PortedFrom: MC1/Views/Tools/TracePath/TracePathView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/NodeDiscovery/NodeDiscoveryView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.services.contacts.AdvertisementEvent
import com.meshcoreone.android.core.services.contacts.ContactServiceError
import com.meshcoreone.android.feature.tools.ToolsFeatureDependencies
import com.meshcoreone.android.feature.tools.discovery.AddContactFailure
import com.meshcoreone.android.feature.tools.discovery.NodeDiscoveryContactAdder
import com.meshcoreone.android.feature.tools.discovery.NodeDiscoveryDirectory
import com.meshcoreone.android.feature.tools.discovery.NodeDiscoveryFeatureDependencies
import com.meshcoreone.android.feature.tools.discovery.NodeDiscoverySession
import com.meshcoreone.android.feature.tools.trace.RecentHopsStorage
import com.meshcoreone.android.feature.tools.trace.TraceDiagnostics
import com.meshcoreone.android.feature.tools.trace.TraceNodeDirectory
import com.meshcoreone.android.feature.tools.trace.TracePathFeatureDependencies
import com.meshcoreone.android.feature.tools.trace.TraceResponse
import com.meshcoreone.android.feature.tools.trace.TraceSender
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.flow.mapNotNull

fun AppContainer.createToolsFeatureDependencies(recentHops: RecentHopsStorage): ToolsFeatureDependencies {
    val diagnostics = TraceDiagnostics { operation, error ->
        Logger.getLogger("com.meshcoreone.android.tools").log(Level.WARNING, operation, error)
    }
    val directory = object : TraceNodeDirectory, NodeDiscoveryDirectory {
        override suspend fun fetchContacts(radioId: com.meshcoreone.android.core.model.RadioId) =
            sessions.current?.dataStore?.fetchContacts(radioId)?.toList().orEmpty()

        override suspend fun fetchDiscoveredNodes(radioId: com.meshcoreone.android.core.model.RadioId) =
            sessions.current?.dataStore?.fetchDiscoveredNodes(radioId)?.toList().orEmpty()
    }
    val trace = object : TracePathFeatureDependencies {
        override fun connectedDevice() = appState.connectedDevice
        override fun bestAvailableLocation(): com.meshcoreone.android.core.model.Coordinate? = null
        override fun nodeDirectory() = directory
        override fun savedPaths() = sessions.current?.dataStore
        override fun traceSender(): TraceSender? = (sessions.current as? RadioSessionContainer)?.let { graph ->
            TraceSender(graph::sendToolTrace)
        }
        override fun traceResponses() = sessions.current?.advertisementService?.events()?.mapNotNull { event ->
            (event as? AdvertisementEvent.TraceResponse)?.let { TraceResponse(it.traceInfo, it.radioId) }
        }
    }
    val discovery = object : NodeDiscoveryFeatureDependencies {
        override fun session(): NodeDiscoverySession? = (sessions.current as? RadioSessionContainer)?.let { graph ->
            object : NodeDiscoverySession {
                override suspend fun sendNodeDiscoverRequest(filter: UByte, prefixOnly: Boolean) =
                    graph.sendToolDiscovery(filter, prefixOnly)
                override fun events() = graph.toolEvents()
            }
        }
        override fun directory() = directory
        override fun radioId() = appState.connectedDevice?.radioId
        override fun contactAdder(): NodeDiscoveryContactAdder? = sessions.current?.contactService?.let { service ->
            NodeDiscoveryContactAdder(service::addOrUpdateContact)
        }
        override fun maxContacts() = appState.connectedDevice?.maxContacts
        override fun classifyAddFailure(error: Exception) =
            if (error is ContactServiceError.ContactTableFull) AddContactFailure.CONTACT_TABLE_FULL
            else AddContactFailure.OTHER
    }
    return ToolsFeatureDependencies(
        trace,
        discovery,
        recentHops,
        diagnostics,
        { appState.connectedDevice?.radioId },
        appState.servicesVersion,
    )
}
