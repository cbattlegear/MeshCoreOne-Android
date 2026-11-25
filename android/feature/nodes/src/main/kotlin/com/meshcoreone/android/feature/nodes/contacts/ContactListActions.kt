// PortedFrom: MC1/Views/Contacts/ContactListActions.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Contacts/ContactsSidebarContent.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Contacts/ContactRoute.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.nodes.contacts

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import com.meshcoreone.android.feature.nodes.model.NodeSegment
import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import java.time.Instant
import java.util.UUID

data class ContactListSnapshot(
    val contacts: List<ContactDTO>,
    val timestampsById: Map<UUID, UInt?>,
)

/**
 * Layout-independent nodes-list actions shared by the compact stack and the split layout
 * (`ContactListActions` and the sidebar's load/refresh/selection rules).
 */
class ContactListActions(
    private val holder: ContactsStateHolder,
    private val dependencies: NodesFeatureDependencies,
) {
    private val session get() = dependencies.session

    /** Keeps row dates with the ordering, including when a lazy row is composed later. */
    fun filteredContacts(
        searchText: String,
        segment: NodeSegment,
        sortOrder: NodeSortOrder,
        userLocation: Coordinate?,
        referenceTime: Instant = dependencies.clock.wallNow,
    ): ContactListSnapshot {
        val contacts = holder.filteredContacts(
            searchText, segment, NodeSortOrder.effective(sortOrder, userLocation != null), userLocation, referenceTime,
        )
        return ContactListSnapshot(contacts, contacts.associate { it.id to it.nodeListTimestamp(referenceTime) })
    }

    val searchPrompt: NodesMessage
        get() {
            val count = holder.state.value.contacts.size
            return if (count > 0) NodesMessage.res(R.string.l10n_app_contacts_contacts_list_searchpromptwithcount, count)
            else NodesMessage.res(R.string.l10n_app_contacts_contacts_list_searchprompt)
        }

    suspend fun loadContacts() {
        val radioId = session.currentRadioId() ?: return
        holder.loadContacts(radioId)
    }

    /** Returns true when a sync ran (the source toggles its success haptic then). */
    suspend fun syncContacts(): Boolean {
        val radioId = session.currentRadioId() ?: return false
        holder.syncContacts(radioId)
        return true
    }

    /** Pull-to-refresh: offline shows the cannot-refresh alert instead of syncing. */
    suspend fun refreshNodes(): RefreshOutcome =
        if (session.connectionState() != DeviceConnectionState.READY) RefreshOutcome.ShowOfflineAlert
        else RefreshOutcome.Synced(syncContacts())

    fun announceOfflineStateIfNeeded() {
        if (session.connectionState() != DeviceConnectionState.DISCONNECTED || session.currentRadioId() == null) return
        dependencies.announcer.announce(NodesMessage.res(R.string.l10n_app_contacts_contacts_list_offlineannouncement))
    }

    sealed interface RefreshOutcome {
        data object ShowOfflineAlert : RefreshOutcome
        data class Synced(val ran: Boolean) : RefreshOutcome
    }

    companion object {
        /**
         * On the first successful load, land on Favorites when any exist, but only from the default
         * segment and only on the false-to-true transition.
         */
        fun segmentAfterLoad(selected: NodeSegment, loaded: Boolean, hasFavorites: Boolean): NodeSegment =
            if (loaded && selected == NodeSegment.CONTACTS && hasFavorites) NodeSegment.FAVORITES else selected

        /** Prefer the freshest loaded row for a pushed detail; fall back to the carried payload. */
        fun detailContact(route: ContactRoute.Detail, loaded: List<ContactDTO>): ContactDTO =
            loaded.firstOrNull { it.id == route.contact.id } ?: route.contact
    }
}

/**
 * Push destinations for the nodes stacks. Equality uses the contact's stable id, so path identity
 * survives row updates while the payload still builds the destination before the list loads.
 */
sealed interface ContactRoute {
    class Detail(val contact: ContactDTO) : ContactRoute {
        override fun equals(other: Any?): Boolean = other is Detail && other.contact.id == contact.id
        override fun hashCode(): Int = 31 * KIND_DETAIL + contact.id.hashCode()
    }

    data object BlockedContacts : ContactRoute

    /** Telemetry history push carried by the detail screen itself. */
    data class TelemetryHistory(val publicKey: Bytes, val radioId: RadioId, val showNeighbors: Boolean = true)

    private companion object {
        const val KIND_DETAIL = 0
    }
}
