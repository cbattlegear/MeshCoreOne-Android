// PortedFrom: MC1/Views/Chats/NewChatView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.model.ContactType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** How a contact reads in the New Chat list (the label is resolved to copy in the UI). */
enum class NewChatContactKind { FLOOD_ROUTING, DIRECT, REPEATER, NONE }

data class NewChatState(
    val contacts: List<ContactDTO> = emptyList(),
    val searchText: String = "",
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
) {
    /** Blocked contacts, repeaters and rooms are not eligible DM targets. */
    val eligibleContacts: List<ContactDTO>
        get() = contacts.filter { !it.isBlocked && it.type == ContactType.CHAT }

    fun filteredContacts(): List<ContactDTO> =
        if (searchText.isEmpty()) eligibleContacts
        else eligibleContacts.filter { ChatTextMatching.standardContains(it.displayName, searchText) }

    val showsEmptyState: Boolean get() = !isLoading && contacts.isEmpty()
}

fun ContactDTO.newChatKind(): NewChatContactKind = when (type) {
    ContactType.CHAT -> if (isFloodRouted) NewChatContactKind.FLOOD_ROUTING else NewChatContactKind.DIRECT
    ContactType.REPEATER -> NewChatContactKind.REPEATER
    ContactType.ROOM, ContactType.SENSOR, ContactType.UNKNOWN -> NewChatContactKind.NONE
}

/** Loads and filters contacts for the New Chat sheet. */
class NewChatStateHolder(private val dependencies: ChatListFeatureDependencies) {
    private val mutableState = MutableStateFlow(NewChatState())
    val state: StateFlow<NewChatState> = mutableState

    fun setSearchText(text: String) = mutableState.update { it.copy(searchText = text) }

    suspend fun load(radioId: RadioId?) {
        if (radioId == null) return
        mutableState.update { it.copy(isLoading = true, loadFailed = false) }
        try {
            val contacts = dependencies.data.fetchContacts(radioId)
            mutableState.update { it.copy(contacts = contacts, isLoading = false) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            dependencies.diagnostics.report("New chat contact load failed", failure)
            mutableState.update { it.copy(contacts = emptyList(), isLoading = false, loadFailed = true) }
        }
    }
}
