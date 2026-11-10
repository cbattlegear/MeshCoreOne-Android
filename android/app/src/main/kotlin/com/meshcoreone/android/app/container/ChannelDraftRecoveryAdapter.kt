// AndroidOnly: WP-308 Bind copy-only recovered drafts to the independent storage query and exact-key read.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.ChannelDraftRecoveryPersisting
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.MessagePersisting
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.feature.chats.composer.ComposerTarget
import com.meshcoreone.android.feature.chats.composer.RecoveredDraftPort

internal class ChannelDraftRecoveryAdapter(
    private val radioId: RadioId,
    private val recovery: ChannelDraftRecoveryPersisting,
    private val messages: MessagePersisting,
    private val copyIntoSelectedDraft: suspend (MessageDTO, ComposerTarget) -> Unit,
) : RecoveredDraftPort {
    override suspend fun fetchPage(radioId: RadioId, limit: Int, offset: Int): List<MessageDTO> {
        requireRadio(radioId)
        return recovery.fetchRecoverableChannelDrafts(radioId, limit.toLong(), offset.toLong()).toList()
    }

    override suspend fun copyToDraft(source: EntityKey, destination: ComposerTarget) {
        requireRadio(source.radioId)
        val current = messages.fetchMessage(source)
            ?: throw PersistenceStoreException(PersistenceStoreError.MessageNotFound)
        if (current.radioId != source.radioId || current.id != source.id ||
            current.direction != MessageDirection.OUTGOING || current.contactID != null || current.channelIndex == null
        ) throw PersistenceStoreException(PersistenceStoreError.InvalidData)
        // The destination owner handles draft conflict/navigation custody, not queue admission.
        copyIntoSelectedDraft(current, destination)
    }

    private fun requireRadio(requested: RadioId) {
        if (requested != radioId) throw PersistenceStoreException(PersistenceStoreError.InvalidData)
    }
}
