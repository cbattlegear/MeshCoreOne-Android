// AndroidOnly: WP-308 Exact-source manual copy with a selection-time destination draft fence.
package com.meshcoreone.android.feature.chats.composer.ui

import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.feature.chats.composer.ComposerDraftStore
import com.meshcoreone.android.feature.chats.composer.ComposerTarget
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class UnboundRecoveryCopyException(val failure: Failure) : IllegalStateException(failure.name) {
    enum class Failure { SOURCE_CHANGED, DESTINATION_CHANGED }
}

/**
 * The reader must return a complete successful projection or throw; storage failure is not an empty list.
 * This operation neither retains incoming text nor sends, deletes, adopts or relabels a source record.
 */
class UnboundRecoveryCopy(
    private val readInventory: suspend () -> List<UnboundRecoveryText>,
    private val drafts: ComposerDraftStore,
) {
    class Destination internal constructor(
        internal val owner: UnboundRecoveryCopy,
        val conversationId: ChatConversationID,
        internal val revision: Long,
    )

    /** Call only for the user's explicit destination choice, not when the Copy button is pressed. */
    fun selectDestination(target: ComposerTarget): Destination = Destination(
        this, target.conversationId, drafts.revision(target.conversationId),
    )

    suspend fun copy(source: UnboundRecoveryText, destination: Destination) {
        require(destination.owner === this) { "Destination belongs to another recovery copy owner" }
        val current = readInventory().singleOrNull { it.key == source.key }
        if (current == null || current.exactText != source.exactText) {
            throw UnboundRecoveryCopyException(UnboundRecoveryCopyException.Failure.SOURCE_CHANGED)
        }
        currentCoroutineContext().ensureActive()
        if (!drafts.restoreIfUnchanged(destination.conversationId, destination.revision, current.exactText)) {
            throw UnboundRecoveryCopyException(UnboundRecoveryCopyException.Failure.DESTINATION_CHANGED)
        }
    }
}
