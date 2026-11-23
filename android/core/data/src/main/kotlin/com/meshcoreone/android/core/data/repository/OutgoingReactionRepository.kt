// AndroidOnly: WP-216 Atomic outgoing reaction admission and captured target identity.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.OutgoingReactionIntentEntity
import com.meshcoreone.android.core.database.toDTO
import com.meshcoreone.android.core.database.toEntityPreservingTimestamp
import com.meshcoreone.android.core.model.*
import java.text.Normalizer

internal class OutgoingReactionRepository(
    private val context: RoomRepositoryContext,
    private val pending: PendingSendRepository,
) : OutgoingReactionPersisting {
    override suspend fun admitOutgoingReaction(admission: OutgoingReactionAdmission): OutgoingReactionAdmissionResult =
        context.write("admitOutgoingReaction") {
            val target = admission.target
            val outgoing = admission.outgoing
            val queued = admission.pending
            val reaction = admission.reaction
            val incarnation = target.channelReactionIncarnation
                ?: throw PersistenceStoreException(PersistenceStoreError.ReactionTargetChanged)
            if (target.radioId != outgoing.radioId || target.radioId != queued.radioId ||
                target.radioId != reaction.radioId || target.id != reaction.messageID ||
                outgoing.id != queued.messageID || outgoing.text != reaction.rawText ||
                outgoing.direction != MessageDirection.OUTGOING || outgoing.status != MessageStatus.PENDING ||
                outgoing.contactID != target.contactID || outgoing.channelIndex != target.channelIndex ||
                reaction.contactID != target.contactID || reaction.channelIndex != target.channelIndex ||
                reaction.expectedTargetIncarnation != incarnation || reaction.emoji.isEmpty() ||
                reaction.senderName.isEmpty() || queued.isResend
            ) invalidData("Outgoing reaction inputs do not describe the same conversation")
            val current = database.messages().byId(target.radioId.value, target.id)?.toDTO()
                ?: throw PersistenceStoreException(PersistenceStoreError.ReactionTargetChanged)
            if (database.directAcks().incarnation(target.radioId.value, target.id, "message")?.incarnation != incarnation ||
                current.contactID != target.contactID || current.channelIndex != target.channelIndex ||
                current.text != target.text || current.reactionTimestamp != target.reactionTimestamp ||
                current.direction != target.direction || current.senderNodeName != target.senderNodeName
            ) throw PersistenceStoreException(PersistenceStoreError.ReactionTargetChanged)
            if (target.channelIndex != null) {
                val envelope = queued.channelMessageEnvelope()
                val channel = admission.channel
                val currentChannel = database.channels().forIndex(target.radioId.value, target.channelIndex!!.toLong()).singleOrNull()
                if (admission.contactPublicKey != null || queued.kind != PendingSendKind.CHANNEL ||
                    channel == null || channel.radioId != target.radioId || channel.index != target.channelIndex ||
                    currentChannel == null || currentChannel.id != channel.id || currentChannel.secret != channel.secret ||
                    envelope == null || envelope.messageID != outgoing.id || envelope.channelIndex != target.channelIndex ||
                    envelope.messageText != outgoing.text || envelope.messageTimestamp != outgoing.timestamp ||
                    envelope.localNodeName != reaction.senderName ||
                    queued.channelOccupantID == null || queued.channelOccupantID != outgoing.channelOccupantID ||
                    (current.channelOccupantID != null && current.channelOccupantID != outgoing.channelOccupantID) ||
                    database.channelOccupants().forIndex(target.radioId.value, target.channelIndex!!.toLong()) != outgoing.channelOccupantID
                ) throw PersistenceStoreException(PersistenceStoreError.ReactionTargetChanged)
            } else {
                val contact = target.contactID ?: invalidData("Direct reaction has no contact")
                if (admission.channel != null || queued.kind != PendingSendKind.DM || queued.contactID != contact ||
                    admission.contactPublicKey == null ||
                    database.contacts().byId(target.radioId.value, contact)?.publicKey != admission.contactPublicKey
                ) throw PersistenceStoreException(PersistenceStoreError.ReactionTargetChanged)
            }
            val dao = database.outgoingReactionIntents()
            val senderKey = Normalizer.normalize(reaction.senderName, Normalizer.Form.NFC)
            val emojiKey = Normalizer.normalize(reaction.emoji, Normalizer.Form.NFC)
            val prior = dao.forTarget(target.radioId.value, target.id, incarnation, senderKey, emojiKey)
            if (prior != null) {
                if (prior.completed) return@write OutgoingReactionAdmissionResult.Completed
                val row = database.pendingSends().byId(target.radioId.value, prior.pendingID)
                    ?: throw PersistenceStoreException(PersistenceStoreError.SaveFailed("Outgoing reaction outcome is unresolved"))
                return@write OutgoingReactionAdmissionResult.Pending(row.toDTO())
            }
            if (database.reactions().forMessage(target.radioId.value, target.id, -1).any {
                    Normalizer.normalize(it.senderName, Normalizer.Form.NFC) == senderKey &&
                        Normalizer.normalize(it.emoji, Normalizer.Form.NFC) == emojiKey &&
                        (it.targetIncarnation == null || it.targetIncarnation == incarnation)
                }
            ) {
                return@write OutgoingReactionAdmissionResult.Completed
            }
            if (database.messages().forIdentity(outgoing.id).isNotEmpty()) invalidData("Outgoing reaction identity is occupied")
            database.messages().insert(outgoing.toEntityPreservingTimestamp())
            with(pending) { insertPendingSendWithSequence(queued) }
            val messageIncarnation = database.directAcks().incarnation(target.radioId.value, outgoing.id, "message")?.incarnation
                ?: invalidData("Outgoing reaction message has no incarnation")
            val pendingIncarnation = database.directAcks().incarnation(target.radioId.value, queued.id, "pending")?.incarnation
                ?: invalidData("Outgoing reaction queue row has no incarnation")
            dao.insert(OutgoingReactionIntentEntity(
                target.radioId.value, outgoing.id, queued.id, messageIncarnation, pendingIncarnation, target.id, incarnation, reaction.senderName,
                reaction.emoji, senderKey, emojiKey, reaction.messageHash, reaction.rawText, target.channelIndex?.toLong(), target.contactID,
                admission.contactPublicKey,
            ))
            save()
            val stored = database.pendingSends().byId(target.radioId.value, queued.id)
                ?: invalidData("Outgoing reaction queue row is missing")
            OutgoingReactionAdmissionResult.Pending(stored.toDTO())
        }

    override suspend fun isOutgoingReaction(message: EntityKey): Boolean = context.read("isOutgoingReaction") {
        database.outgoingReactionIntents().forMessage(message.radioId.value, message.id) != null
    }

    override suspend fun fetchOutgoingReaction(message: EntityKey): ReactionDTO? = context.read("fetchOutgoingReaction") {
        val intent = database.outgoingReactionIntents().forMessage(message.radioId.value, message.id) ?: return@read null
        requireAccepted(intent)
        val outgoing = database.messages().byId(message.radioId.value, message.id)
            ?: invalidData("Outgoing reaction message is missing")
        ReactionDTO(
            id = intent.messageID, messageID = intent.targetID, emoji = intent.emoji,
            senderName = intent.senderName, messageHash = intent.messageHash, rawText = intent.rawText,
            receivedAt = outgoing.createdAt.toInstant(), channelIndex = intent.channelIndex?.toUByte(),
            contactID = intent.contactID, radioId = message.radioId, expectedTargetIncarnation = intent.targetIncarnation,
        )
    }

    override suspend fun completeOutgoingReaction(message: EntityKey) = context.write("completeOutgoingReaction") {
        val intent = database.outgoingReactionIntents().forMessage(message.radioId.value, message.id)
            ?: invalidData("Outgoing reaction intent is missing")
        requireAccepted(intent)
        if (database.outgoingReactionIntents().complete(message.radioId.value, message.id) != 1) {
            invalidData("Outgoing reaction intent is missing")
        }
        save()
    }

    private suspend fun RepositoryTransaction.requireAccepted(intent: OutgoingReactionIntentEntity) {
        if (database.directAcks().incarnation(intent.radioId, intent.messageID, "message")?.incarnation != intent.messageIncarnation ||
            database.directAcks().incarnation(intent.radioId, intent.pendingID, "pending")?.incarnation != intent.pendingIncarnation
        ) invalidData("Outgoing reaction message or queue identity changed")
        val accepted = if (intent.channelIndex != null) {
            database.queuedChannelAttempts().forPending(intent.radioId, intent.pendingID).any {
                it.messageID == intent.messageID && it.messageIncarnation == intent.messageIncarnation &&
                    it.phaseRawValue == QueuedChannelAttemptPhase.ACCEPTED.rawValue
            }
        } else {
            database.outgoingReactionIntents().hasAcceptedDirect(
                intent.radioId, intent.messageID, intent.pendingID, intent.messageIncarnation, intent.pendingIncarnation,
            )
        }
        if (!accepted) throw PersistenceStoreException(PersistenceStoreError.SaveFailed("Outgoing reaction acceptance is not known"))
    }
}

internal suspend fun RepositoryTransaction.outgoingReactionTargetIsCurrent(radio: java.util.UUID, message: java.util.UUID): Boolean {
    val intent = database.outgoingReactionIntents().forMessage(radio, message) ?: return true
    if (intent.completed ||
        database.directAcks().incarnation(radio, intent.messageID, "message")?.incarnation != intent.messageIncarnation ||
        database.directAcks().incarnation(radio, intent.pendingID, "pending")?.incarnation != intent.pendingIncarnation ||
        database.directAcks().incarnation(radio, intent.targetID, "message")?.incarnation != intent.targetIncarnation
    ) return false
    val target = database.messages().byId(radio, intent.targetID) ?: return false
    val outgoing = database.messages().byId(radio, intent.messageID) ?: return false
    if (target.contactID != intent.contactID || target.channelIndex != intent.channelIndex ||
        outgoing.contactID != intent.contactID || outgoing.channelIndex != intent.channelIndex ||
        outgoing.text != intent.rawText
    ) return false
    return if (intent.channelIndex != null) {
        val occupant = outgoing.channelOccupantID ?: return false
        (target.channelOccupantID == null || target.channelOccupantID == occupant) &&
            database.channelOccupants().forIndex(radio, intent.channelIndex) == occupant
    } else {
        val contact = intent.contactID ?: return false
        intent.contactPublicKey != null && database.contacts().byId(radio, contact)?.publicKey == intent.contactPublicKey
    }
}
