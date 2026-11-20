// AndroidOnly: WP-214 Persist summary mutations and exact operation evidence under one Room commit.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.CommittedReactionSummary
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.ReactionSummaryMutation
import com.meshcoreone.android.core.contracts.domain.ReactionSummaryReceipting
import com.meshcoreone.android.core.database.MessageEntity
import com.meshcoreone.android.core.database.ReactionSummaryReceiptEntity
import com.meshcoreone.android.core.database.toDTO
import com.meshcoreone.android.core.model.RadioId

internal class ReactionSummaryRepository(private val context: RoomRepositoryContext) : ReactionSummaryReceipting {
    override suspend fun updateReactionSummary(mutation: ReactionSummaryMutation) =
        context.write("updateReactionSummary") {
            if (mutation.targetIncarnation.isEmpty()) invalidData("Missing reaction-summary target incarnation")
            val receipt = ReactionSummaryReceiptEntity(
                mutation.operation.radioId.value, mutation.operation.id, mutation.messageID,
                mutation.targetIncarnation, mutation.summary,
            )
            val existing = database.reactionSummaryReceipts().byOperation(receipt.radioId, receipt.operationID)
            if (existing != null && existing != receipt) invalidData("Reaction-summary operation identity conflict")
            val target = currentTarget(receipt)
                ?: throw PersistenceStoreException(PersistenceStoreError.ReactionTargetChanged)
            if (existing != null) return@write
            database.messages().upsert(target.copy(reactionSummary = receipt.summary))
            database.reactionSummaryReceipts().insert(receipt)
            save()
        }

    override suspend fun fetchCommittedReactionSummary(operation: EntityKey): CommittedReactionSummary? =
        context.read("fetchCommittedReactionSummary") {
            val receipt = database.reactionSummaryReceipts().byOperation(operation.radioId.value, operation.id)
                ?: return@read null
            val target = currentTarget(receipt) ?: return@read null
            CommittedReactionSummary(
                ReactionSummaryMutation(
                    EntityKey(RadioId(receipt.radioId), receipt.operationID), receipt.messageID,
                    receipt.targetIncarnation, receipt.summary,
                ),
                target.toDTO(),
            )
        }

    private suspend fun RepositoryTransaction.currentTarget(receipt: ReactionSummaryReceiptEntity): MessageEntity? {
        val target = database.messages().byId(receipt.radioId, receipt.messageID) ?: return null
        val index = target.channelIndex ?: return null
        if (target.channelOccupantID != null &&
            database.channelOccupants().forIndex(target.radioId, index) != target.channelOccupantID
        ) return null
        val incarnation = database.directAcks().incarnation(target.radioId, target.id, "message")?.incarnation
        return target.takeIf { incarnation == receipt.targetIncarnation }
    }
}
