// AndroidOnly: WP-214 Persist summary mutations and exact operation evidence under one Room commit.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.CommittedReactionSummary
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.ReactionSummaryMutation
import com.meshcoreone.android.core.contracts.domain.ReactionSummaryReceipting
import com.meshcoreone.android.core.database.MessageEntity
import com.meshcoreone.android.core.database.DirectAckStorage
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
            if (existing != null && existing != receipt) {
                val intent = database.outgoingReactionIntents().forMessage(receipt.radioId, receipt.operationID)
                if (intent == null || existing.messageID != receipt.messageID ||
                    existing.targetIncarnation != receipt.targetIncarnation
                ) invalidData("Reaction-summary operation identity conflict")
            }
            val target = currentTarget(receipt)
                ?: throw PersistenceStoreException(PersistenceStoreError.ReactionTargetChanged)
            if (existing != null) return@write
            updateSummaryPreservingDirectAuthority(target, receipt.summary)
            database.reactionSummaryReceipts().insert(receipt)
            save()
        }

        internal suspend fun RepositoryTransaction.updateSummaryPreservingDirectAuthority(target: MessageEntity, summary: String?) {
            if (target.reactionSummary == summary) return
            if (target.channelIndex != null) {
                database.messages().upsert(target.copy(reactionSummary = summary))
                return
            }
            val dao = database.directAcks()
            DirectAckStorage.verify(database.openHelper.writableDatabase)
            val identity = dao.incarnation(target.radioId, target.id, "message")
                ?: invalidData("Reaction summary target identity is missing")
            val leases = dao.leases(target.radioId).filter { it.active }.map { it.requestId }.toSet()
            val claims = dao.currentMessageClaims(target.radioId, target.id).filter {
                it.leaseId in leases && it.incarnation == identity.incarnation && it.revision == identity.revision &&
                    it.snapshot == target
            }
            val deliveries = claims.mapNotNull { dao.delivery(target.radioId, it.requestId) }
                .filter { it.snapshot == target }.associateBy { it.claimId }
            val intents = dao.currentMessageIntents(target.radioId, target.id).filter {
                it.leaseId in leases && it.incarnation == identity.incarnation && it.revision == identity.revision &&
                    it.message == target
            }
            val completions = dao.currentMessageCompletions(target.radioId, target.id).filter {
                it.leaseId in leases && it.messageIncarnation == identity.incarnation &&
                    it.messageRevision == identity.revision && it.message == target
            }
            val next = target.copy(reactionSummary = summary)
            database.messages().upsert(next)
            val nextRevision = Math.addExact(identity.revision, 1)
            if (database.messages().byId(target.radioId, target.id) != next ||
                dao.restoreSummaryRevision(target.radioId, target.id, identity.incarnation, identity.revision, nextRevision) != 1
            ) invalidData("Reaction summary changed alongside another message mutation")
            val activeLeases = dao.leases(target.radioId).filter { it.active }.map { it.requestId }.toSet()
            for (claim in claims) {
                val pendingCurrent = claim.pendingId == null ||
                    dao.incarnation(target.radioId, claim.pendingId, "pending")?.incarnation == claim.pendingIncarnation ||
                    dao.operations(target.radioId, claim.requestId).any { it.kind == "PENDING" }
                if (claim.leaseId in activeLeases && pendingCurrent) {
                    dao.claim(claim.copy(snapshot = next))
                    deliveries[claim.requestId]?.let { dao.delivery(it.copy(snapshot = next)) }
                }
            }
            for (intent in intents) {
                val pending = intent.pendingId
                if (intent.leaseId in activeLeases && (pending == null ||
                        (dao.incarnation(target.radioId, pending, "pending")?.let {
                            it.incarnation == intent.pendingIncarnation && it.revision == intent.pendingRevision
                        } == true && database.pendingSends().byId(target.radioId, pending) == intent.pending))
                ) dao.intent(intent.copy(message = next))
            }
            for (completion in completions) {
                if (completion.leaseId in activeLeases &&
                    dao.incarnation(target.radioId, completion.pendingId, "pending")?.incarnation == completion.pendingIncarnation &&
                    database.pendingSends().byId(target.radioId, completion.pendingId) == completion.pending
                ) dao.pendingCompletion(completion.copy(message = next))
            }
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
        val intent = database.outgoingReactionIntents().forMessage(receipt.radioId, receipt.operationID)
        if (intent != null && (intent.targetID != receipt.messageID ||
                !outgoingReactionTargetIsCurrent(receipt.radioId, receipt.operationID))
        ) return null
        val index = target.channelIndex
        if (index == null && intent == null) return null
        if (index != null && target.channelOccupantID != null &&
            database.channelOccupants().forIndex(target.radioId, index) != target.channelOccupantID
        ) return null
        val incarnation = database.directAcks().incarnation(target.radioId, target.id, "message")?.incarnation
        return target.takeIf { incarnation == receipt.targetIncarnation }
    }
}
