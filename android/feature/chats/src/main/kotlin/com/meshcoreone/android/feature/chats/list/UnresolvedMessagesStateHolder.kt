// AndroidOnly: WP-306 Read-only access to retained direct occurrences; no sender or reply identity.
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.contracts.domain.UnprovedDirectMessage
import com.meshcoreone.android.core.model.RadioId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class UnresolvedMessagesState(
    val radioId: RadioId?,
    val messages: List<UnprovedDirectMessage> = emptyList(),
    val isLoading: Boolean = radioId != null,
    val loadFailed: Boolean = false,
    val hasMore: Boolean = false,
)

/** One holder per selected radio. Reads never mark messages read, adopt contacts, or send. */
class UnresolvedMessagesStateHolder(
    radioId: RadioId?,
    private val data: ChatListDataSource,
    private val diagnostics: ChatListDiagnostics,
) {
    private val mutableState = MutableStateFlow(UnresolvedMessagesState(radioId))
    val state: StateFlow<UnresolvedMessagesState> = mutableState
    private val reads = Mutex()

    suspend fun refresh() = load(append = false)
    suspend fun loadMore() {
        if (state.value.hasMore && !state.value.isLoading) load(append = true)
    }

    private suspend fun load(append: Boolean) = reads.withLock {
        val before = state.value
        val radio = before.radioId ?: return@withLock
        mutableState.update { it.copy(isLoading = true, loadFailed = false) }
        try {
            val offset = if (append) before.messages.size.toLong() else 0L
            val rows = data.fetchUnprovedDirectMessages(radio, PAGE_SIZE + 1, offset)
            check(rows.size <= PAGE_SIZE + 1 && rows.all { it.radioId == radio }) {
                "Unproved direct-message read violated its radio/page contract"
            }
            val page = rows.take(PAGE_SIZE.toInt())
            mutableState.update {
                it.copy(
                    messages = (if (append) before.messages + page else page).distinctBy { row -> row.occurrenceID },
                    isLoading = false,
                    hasMore = rows.size > PAGE_SIZE,
                    loadFailed = false,
                )
            }
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(isLoading = false) }
            throw cancelled
        } catch (failure: Exception) {
            diagnostics.report("Retained unproved direct messages could not load", failure)
            mutableState.update { it.copy(isLoading = false, loadFailed = true) }
        }
    }

    companion object {
        const val PAGE_SIZE = 50L
    }
}
