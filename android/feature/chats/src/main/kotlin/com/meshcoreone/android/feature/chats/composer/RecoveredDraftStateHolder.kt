// AndroidOnly: WP-308 Explicit recovery-list selection and copy; no send or source-row mutation.
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface RecoveredDraftPort {
    suspend fun fetchPage(radioId: RadioId, limit: Int, offset: Int): List<MessageDTO>

    /** Re-read the exact source's current text; preserve the source and reject a nonempty destination. */
    suspend fun copyToDraft(source: EntityKey, destination: ComposerTarget)
}

data class RecoveredDraftState(
    val messages: List<MessageDTO> = emptyList(),
    val selected: EntityKey? = null,
    val loading: Boolean = false,
    val copying: Boolean = false,
    val hasMore: Boolean = true,
    val failure: Throwable? = null,
)

class RecoveredDraftStateHolder(
    private val radioId: RadioId,
    private val port: RecoveredDraftPort,
    private val diagnostics: ComposerDiagnostics,
) {
    private val operations = Mutex()
    private val mutableState = MutableStateFlow(RecoveredDraftState())
    val state: StateFlow<RecoveredDraftState> = mutableState.asStateFlow()
    private var offset = 0

    suspend fun refresh() = loadPage(reset = true)

    suspend fun loadMore() = loadPage(reset = false)

    private suspend fun loadPage(reset: Boolean) = operations.withLock {
        val before = mutableState.value
        if (!reset && !before.hasMore) return@withLock
        mutableState.value = before.copy(loading = true, failure = null)
        try {
            val requestedOffset = if (reset) 0 else offset
            val page = port.fetchPage(radioId, PAGE_SIZE, requestedOffset)
            check(page.size <= PAGE_SIZE) { "Recovered-draft page exceeds its requested bound" }
            check(page.all { it.radioId == radioId }) { "Recovered-draft page contains another radio" }
            val messages = ((if (reset) emptyList() else before.messages) + page)
                .distinctBy { EntityKey(it.radioId, it.id) }
            offset = Math.addExact(requestedOffset, page.size)
            val selected = mutableState.value.selected
            mutableState.value = mutableState.value.copy(
                messages = messages,
                selected = selected?.takeIf { key -> messages.any { it.id == key.id && it.radioId == key.radioId } },
                hasMore = page.size == PAGE_SIZE,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            diagnostics.report("recoveredDrafts.load", failure)
            mutableState.value = mutableState.value.copy(failure = failure)
        } finally {
            mutableState.value = mutableState.value.copy(loading = false)
        }
    }

    fun select(source: EntityKey?) {
        val current = mutableState.value
        if (current.copying) return
        require(source == null || current.messages.any { it.radioId == source.radioId && it.id == source.id }) {
            "Recovered draft must be selected from the displayed radio-scoped list"
        }
        mutableState.value = current.copy(selected = source, failure = null)
    }

    suspend fun copySelectedTo(destination: ComposerTarget): Boolean = operations.withLock {
        val source = mutableState.value.selected ?: return@withLock false
        mutableState.value = mutableState.value.copy(copying = true, failure = null)
        try {
            port.copyToDraft(source, destination)
            mutableState.value = mutableState.value.copy(selected = null)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            diagnostics.report("recoveredDrafts.copy", failure)
            mutableState.value = mutableState.value.copy(failure = failure)
            false
        } finally {
            mutableState.value = mutableState.value.copy(copying = false)
        }
    }

    companion object {
        const val PAGE_SIZE = 40
    }
}
