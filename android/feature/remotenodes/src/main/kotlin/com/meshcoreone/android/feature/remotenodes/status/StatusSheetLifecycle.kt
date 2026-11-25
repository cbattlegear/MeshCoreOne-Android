// PortedFrom: MC1/Views/RemoteNodes/Repeaters/RepeaterStatusView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomStatusView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/RemoteNodes/NodeTelemetryView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.status

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeHistoryStore
import kotlin.coroutines.cancellation.CancellationException

/** Contacts and discovered nodes the repeater sheet loads for neighbour naming. */
data class NeighborSources(val contacts: List<ContactDTO>, val discoveredNodes: List<DiscoveredNodeDTO>)

/** The `.task` / `.onDisappear` sequences of the standalone status sheets. */
object StatusSheetLifecycle {
    /**
     * Repeater sheet `.task`: register handlers, then (when a radio is connected) load the OCV curve and
     * the neighbour-naming sources; each fetch failure yields an empty list (Swift `try?`).
     */
    suspend fun startRepeater(
        holder: RepeaterStatusStateHolder,
        session: RemoteNodeSessionDTO,
        connectedRadioId: RadioId?,
        store: RemoteNodeHistoryStore?,
    ): NeighborSources {
        holder.registerHandlers()
        val radioId = connectedRadioId ?: return NeighborSources(emptyList(), emptyList())
        holder.helper.loadOCVSettings(session.publicKey, radioId)
        if (store == null) return NeighborSources(emptyList(), emptyList())
        return NeighborSources(
            contacts = attempt { store.fetchContacts(radioId) }.orEmpty(),
            discoveredNodes = attempt { store.fetchDiscoveredNodes(radioId) }.orEmpty(),
        )
    }

    /**
     * Swift `refreshRouteContact`: the node's latest contact (its learned route), or [current] when the
     * store is unavailable, the fetch fails or no contact exists.
     */
    suspend fun refreshRouteContact(
        session: RemoteNodeSessionDTO,
        store: RemoteNodeHistoryStore?,
        current: ContactDTO?,
    ): ContactDTO? {
        store ?: return current
        return attempt { store.fetchContact(session.radioId, session.publicKey) } ?: current
    }

    /** Repeater sheet dismiss: stop discovery, then clear every handler slot. */
    fun dismissRepeater(holder: RepeaterStatusStateHolder) {
        holder.stopDiscovery()
        holder.cleanup()
    }

    /** Room sheet `.task`: register handlers, then load the OCV curve when a radio is connected. */
    suspend fun startRoom(holder: RoomStatusStateHolder, session: RemoteNodeSessionDTO, connectedRadioId: RadioId?) {
        holder.registerHandlers()
        connectedRadioId?.let { holder.helper.loadOCVSettings(session.publicKey, it) }
    }

    fun dismissRoom(holder: RoomStatusStateHolder) = holder.cleanup()

    /** Chat-node telemetry sheet `.task`: open the telemetry section, then load the OCV curve. */
    suspend fun startTelemetry(holder: NodeTelemetryStateHolder, contact: ContactDTO, connectedRadioId: RadioId?) {
        holder.helper.setTelemetryExpanded(true)
        connectedRadioId?.let { holder.helper.loadOCVSettings(contact.publicKey, it) }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }
}
