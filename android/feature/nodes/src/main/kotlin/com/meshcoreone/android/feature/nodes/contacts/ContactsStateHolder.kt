// PortedFrom: MC1/Views/Contacts/ContactsViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.nodes.contacts

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.nodes.deps.CommandTimeoutException
import com.meshcoreone.android.feature.nodes.deps.EventSubscription
import com.meshcoreone.android.feature.nodes.deps.NodesContactFailure
import com.meshcoreone.android.feature.nodes.deps.NodesConnectionSnapshot
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import com.meshcoreone.android.feature.nodes.deps.RadioCommandTimeout
import com.meshcoreone.android.feature.nodes.deps.SyncProgress
import com.meshcoreone.android.feature.nodes.deps.withCommandTimeout
import com.meshcoreone.android.feature.nodes.deps.ensureContactConnection
import com.meshcoreone.android.feature.nodes.model.NodeSegment
import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Observable state of the nodes list. */
data class ContactsState(
    val contacts: List<ContactDTO> = emptyList(),
    /** Inbound advert hop count per public key from the volatile discovered-node table. */
    val inboundHopByKey: Map<Bytes, Long> = emptyMap(),
    val isLoading: Boolean = false,
    /** Whether data has been loaded at least once (prevents an empty-state flash). */
    val hasLoadedOnce: Boolean = false,
    val isSyncing: Boolean = false,
    val syncProgress: SyncProgress? = null,
    val errorMessage: NodesMessage? = null,
    /** Graph/radio owner for Sync failures, including readiness errors before an operation starts. */
    val errorConnection: NodesConnectionSnapshot? = null,
    val togglingFavoriteId: UUID? = null,
    /** Rows hidden after a confirmed delete, held until a reload sees the row gone. */
    val pendingRemovalIds: Set<UUID> = emptySet(),
    /** Rows with a delete command in flight (spinner). */
    val deletingIds: Set<UUID> = emptySet(),
) {
    /** True when any loaded contact is a favorite; drives the initial Nodes segment. */
    val hasFavorites: Boolean get() = contacts.any { it.isFavorite }

    /** True while a delete for this row is either confirmed-but-unreloaded or in flight. */
    fun isDeletePending(id: UUID): Boolean = id in pendingRemovalIds || id in deletingIds
}

/**
 * Contact management for the nodes list (`ContactsViewModel`). Main-confined like the Swift
 * `@MainActor` model: call it from one dispatcher; [scope] hosts the sync-progress listener.
 */
class ContactsStateHolder(
    private val dependencies: NodesFeatureDependencies,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(ContactsState())
    val state: StateFlow<ContactsState> = mutableState.asStateFlow()

    /** Bumped at the start of each load so a slower fetch cannot overwrite a newer list. */
    private var loadGeneration = 0

    /** Upserted rows held until a fetch includes the same id or public key and radio. */
    private val pendingAdmissions = LinkedHashMap<UUID, ContactDTO>()

    private class ContactSync(val connection: Any?, val radioId: RadioId) {
        var progress: EventSubscription<SyncProgress>? = null
        var listener: Job? = null

        fun stopProgress() {
            listener?.cancel()
            listener = null
            progress?.close()
            progress = null
        }
    }

    private var activeSync: ContactSync? = null

    private val session get() = dependencies.session

    /** Test/preview seam for the Swift model's publicly settable properties. */
    internal fun seed(transform: (ContactsState) -> ContactsState) = mutableState.update(transform)

    fun isDeletePending(id: UUID): Boolean = state.value.isDeletePending(id)

    /** Load contacts from the local database. */
    suspend fun loadContacts(radioId: RadioId) = loadContacts(radioId) { true }

    private suspend fun loadContacts(
        radioId: RadioId,
        errorOwner: NodesConnectionSnapshot? = null,
        canPublish: () -> Boolean,
    ) {
        val dataStore = session.offlineDataStore() ?: return
        val generation = ++loadGeneration
        val admissionsAtStart = pendingAdmissions.toMap()
        mutableState.update { it.copy(isLoading = true, errorMessage = null, errorConnection = null) }
        try {
            val fetched = dataStore.fetchContacts(radioId)
            if (generation != loadGeneration || !canPublish()) return
            val merged = mergeAdmissions(fetched, admissionsAtStart)
            val ids = merged.mapTo(HashSet()) { it.id }
            // Self-heal the mask: once a deleted row is gone from the fetch, stop masking it.
            mutableState.update { it.copy(contacts = merged, pendingRemovalIds = it.pendingRemovalIds intersect ids) }
        } catch (cancelled: CancellationException) {
            if (generation == loadGeneration && canPublish()) mutableState.update { it.copy(isLoading = false) }
            throw cancelled
        } catch (error: Exception) {
            if (generation != loadGeneration || !canPublish()) return
            mutableState.update { it.copy(errorMessage = failure(error), errorConnection = errorOwner) }
        }
        // Best-effort inbound-hop fallback: a failure here must not fail the contact load.
        val hops = try {
            dataStore.fetchDiscoveredNodes(radioId).mapNotNull { node -> node.inboundHopCount?.let { node.publicKey to it } }.toMap()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
        if (generation != loadGeneration || !canPublish()) return
        mutableState.update { it.copy(inboundHopByKey = hops, hasLoadedOnce = true, isLoading = false) }
    }

    /** Inserts or replaces by id, then public key and radio; unmasks a re-added row. */
    fun upsert(contact: ContactDTO) {
        pendingAdmissions.entries.removeAll { (id, existing) -> id == contact.id || existing.sameIdentity(contact) }
        pendingAdmissions[contact.id] = contact
        mutableState.update { current ->
            val removal = current.pendingRemovalIds - contact.id
            val byId = current.contacts.indexOfFirst { it.id == contact.id }
            val byKey = current.contacts.indexOfFirst { it.sameIdentity(contact) }
            when {
                byId >= 0 -> current.copy(contacts = current.contacts.replacing(byId, contact), pendingRemovalIds = removal)
                byKey >= 0 -> current.copy(
                    contacts = current.contacts.replacing(byKey, contact),
                    pendingRemovalIds = removal - current.contacts[byKey].id,
                )
                else -> current.copy(contacts = current.contacts + contact, pendingRemovalIds = removal)
            }
        }
    }

    private fun mergeAdmissions(fetched: List<ContactDTO>, admissionsAtStart: Map<UUID, ContactDTO>): List<ContactDTO> {
        val result = fetched.toMutableList()
        val resolved = mutableListOf<UUID>()
        for ((id, contact) in pendingAdmissions) {
            val index = result.indexOfFirst { it.id == contact.id || it.sameIdentity(contact) }
            if (index < 0) {
                result += contact
            } else if (admissionsAtStart[id] !== contact) {
                // This query started before the admission: even a matching row may be an older version.
                result[index] = contact
            } else {
                resolved += id
            }
        }
        resolved.forEach(pendingAdmissions::remove)
        return result
    }

    /** Sync contacts from the device; a re-trigger while one is in flight is a no-op. */
    suspend fun syncContacts(radioId: RadioId) {
        val connection = session.connectionIdentity()
        if (session.contactService() == null) return
        syncContacts(radioId, connection)
    }

    suspend fun syncContacts(radioId: RadioId, connection: Any?) {
        session.ensureContactConnection(connection, radioId)
        val ready = session.connectionState() == DeviceConnectionState.READY
        session.ensureContactConnection(connection, radioId)
        if (!ready) {
            mutableState.update {
                it.copy(
                    errorMessage = NodesMessage.res(R.string.l10n_app_contacts_contacts_list_connecttosync),
                    errorConnection = NodesConnectionSnapshot(radioId, connection),
                )
            }
            return
        }
        onConnectionChanged()
        if (activeSync != null) return
        val operation = ContactSync(connection, radioId)
        val contactService = session.contactService() ?: return
        val advertisementService = session.advertisementService()
        // Providers can observe different registry generations; reject a mixed capture before any side effect.
        session.ensureContactConnection(operation.connection, radioId)
        activeSync = operation
        mutableState.update { it.copy(isSyncing = true, syncProgress = null, errorMessage = null, errorConnection = null) }
        var cancellation: CancellationException? = null
        try {
            // Flag the advert pipeline first and register before sending so no progress is missed.
            advertisementService?.setSyncingContacts(true)
            ensureCurrentSync(operation)
            val subscription = contactService.syncProgressEvents()
            operation.progress = subscription
            operation.listener = scope.launch {
                subscription.events.collect { progress ->
                    if (isCurrentSync(operation)) mutableState.update { it.copy(syncProgress = progress) }
                    else onConnectionChanged()
                }
            }
            contactService.syncContactsForRefresh(radioId)
            ensureCurrentSync(operation)
            loadContacts(radioId, NodesConnectionSnapshot(radioId, operation.connection)) { isCurrentSync(operation) }
            ensureCurrentSync(operation)
            mutableState.update { it.copy(syncProgress = null) }
        } catch (cancelled: CancellationException) {
            // Interrupted refresh (tab switch, teardown) is not a failure to report.
            cancellation = cancelled
        } catch (error: Exception) {
            if (isCurrentSync(operation)) mutableState.update {
                it.copy(errorMessage = failure(error), errorConnection = NodesConnectionSnapshot(radioId, operation.connection))
            }
            else onConnectionChanged()
        } finally {
            operation.stopProgress()
            withContext(NonCancellable) {
                advertisementService?.setSyncingContacts(false)
                if (activeSync === operation) onConnectionChanged()
                if (activeSync === operation) {
                    activeSync = null
                    mutableState.update { it.copy(isSyncing = false, syncProgress = null) }
                }
            }
        }
        cancellation?.let { throw it }
    }

    /** Retire only connection-owned work; cached contacts and pending admissions remain radio-owned. */
    fun onConnectionChanged() {
        val errorOwner = state.value.errorConnection
        if (errorOwner != null &&
            (session.connectionIdentity() !== errorOwner.identity || session.currentRadioId() != errorOwner.radioId)
        ) {
            mutableState.update {
                if (it.errorConnection === errorOwner) it.copy(errorMessage = null, errorConnection = null) else it
            }
        }
        val operation = activeSync ?: return
        if (isCurrentSync(operation)) return
        activeSync = null
        operation.stopProgress()
        ++loadGeneration
        mutableState.update { it.copy(isSyncing = false, syncProgress = null, isLoading = false) }
    }

    private fun isCurrentSync(operation: ContactSync): Boolean =
        activeSync === operation && session.connectionIdentity() === operation.connection && session.currentRadioId() == operation.radioId

    private suspend fun ensureCurrentSync(operation: ContactSync) {
        session.ensureContactConnection(operation.connection, operation.radioId)
        if (activeSync !== operation) throw CancellationException("Contact sync was retired")
    }

    /** Toggle favorite on the device, then reload. */
    suspend fun toggleFavorite(contact: ContactDTO) {
        val contactService = session.contactService() ?: return
        val current = state.value.contacts.firstOrNull { it.id == contact.id } ?: return
        mutableState.update { it.copy(togglingFavoriteId = contact.id) }
        try {
            contactService.setContactFavorite(contact.key, !current.isFavorite)
            if (state.value.contacts.any { it.id == contact.id }) loadContacts(contact.radioId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(errorMessage = failure(error), errorConnection = null) }
        } finally {
            mutableState.update { it.copy(togglingFavoriteId = null) }
        }
    }

    /** Toggle blocked status, then reload. */
    suspend fun toggleBlocked(contact: ContactDTO) {
        val contactService = session.contactService() ?: return
        val current = state.value.contacts.firstOrNull { it.id == contact.id } ?: return
        reportingFailure {
            contactService.updateContactPreferences(contact.key, isBlocked = !current.isBlocked)
            loadContacts(contact.radioId)
        }
    }

    /** Update the nickname; an empty string is passed as "unchanged" exactly like the source. */
    suspend fun updateNickname(contact: ContactDTO, nickname: String?) {
        val contactService = session.contactService() ?: return
        reportingFailure {
            contactService.updateContactPreferences(contact.key, nickname = nickname?.takeUnless { it.isEmpty() })
            loadContacts(contact.radioId)
        }
    }

    /**
     * Delete a contact. Removing a node is a radio command, so the row keeps a spinner until the radio
     * acks and is then hidden once; failure or timeout leaves it in place with an error.
     */
    suspend fun deleteContact(contact: ContactDTO) {
        val contactService = session.contactService() ?: run {
            mutableState.update {
                it.copy(errorMessage = NodesMessage.res(R.string.l10n_app_contacts_contacts_viewmodel_connecttodelete), errorConnection = null)
            }
            return
        }
        if (isDeletePending(contact.id)) return
        mutableState.update { it.copy(deletingIds = it.deletingIds + contact.id) }
        try {
            withCommandTimeout(dependencies.clock, RadioCommandTimeout.delete, "removeContact") {
                contactService.removeContact(contact.radioId, contact.publicKey)
            }
            hideDeletedContact(contact)
        } catch (_: NodesContactFailure.ContactNotFound) {
            // The radio no longer knows this contact: clear the orphaned local row.
            reportingFailure {
                contactService.removeLocalContact(contact.key, contact.publicKey)
                hideDeletedContact(contact)
            }
        } catch (_: CommandTimeoutException) {
            mutableState.update {
                it.copy(errorMessage = NodesMessage.res(R.string.l10n_app_contacts_contacts_viewmodel_removetimedout), errorConnection = null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(errorMessage = failure(error), errorConnection = null) }
        } finally {
            mutableState.update { it.copy(deletingIds = it.deletingIds - contact.id) }
        }
    }

    /** Masks and removes a row whose deletion the radio confirmed, in one state transition. */
    private fun hideDeletedContact(contact: ContactDTO) {
        pendingAdmissions.remove(contact.id)
        mutableState.update {
            it.copy(pendingRemovalIds = it.pendingRemovalIds + contact.id, contacts = it.contacts.filterNot { row -> row.id == contact.id })
        }
    }

    fun clearError() = mutableState.update { it.copy(errorMessage = null, errorConnection = null) }

    /** Contacts filtered by segment (or search, which ignores the segment) and sorted. */
    fun filteredContacts(
        searchText: String,
        segment: NodeSegment,
        sortOrder: NodeSortOrder,
        userLocation: Coordinate?,
    ): List<ContactDTO> = state.value.let {
        ContactsSorting.filtered(
            it.contacts, it.pendingRemovalIds, it.inboundHopByKey, searchText, segment, sortOrder, userLocation,
            dependencies.locale(),
        )
    }

    private suspend fun reportingFailure(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(errorMessage = failure(error), errorConnection = null) }
        }
    }

    private fun failure(error: Throwable): NodesMessage = NodesMessage.Text(dependencies.messages.message(error))
}

internal val ContactDTO.key: EntityKey get() = EntityKey(radioId, id)

internal fun ContactDTO.sameIdentity(other: ContactDTO): Boolean = publicKey == other.publicKey && radioId == other.radioId

private fun <T> List<T>.replacing(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }
