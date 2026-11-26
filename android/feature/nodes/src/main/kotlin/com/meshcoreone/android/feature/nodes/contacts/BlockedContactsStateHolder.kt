// PortedFrom: MC1/Views/Contacts/BlockedContactsView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.nodes.contacts

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BlockedContactsState(
    val contacts: List<ContactDTO> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: NodesMessage? = null,
)

/** Blocked-contacts management list; reloads on appear and on every contacts-version change. */
class BlockedContactsStateHolder(private val dependencies: NodesFeatureDependencies) {
    private val mutableState = MutableStateFlow(BlockedContactsState())
    val state: StateFlow<BlockedContactsState> = mutableState.asStateFlow()

    suspend fun loadBlockedContacts() {
        val session = dependencies.session
        val dataStore = session.servicesDataStore() ?: return
        val radioId = session.connectedDevice()?.radioId ?: return
        mutableState.update { it.copy(isLoading = true) }
        try {
            val blocked = dataStore.fetchBlockedContacts(radioId)
            mutableState.update { it.copy(contacts = blocked) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(errorMessage = NodesMessage.Text(dependencies.messages.message(error))) }
        } finally {
            mutableState.update { it.copy(isLoading = false) }
        }
    }

    fun clearError() = mutableState.update { it.copy(errorMessage = null) }
}
