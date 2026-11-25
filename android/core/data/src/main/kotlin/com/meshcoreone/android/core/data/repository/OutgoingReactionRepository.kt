// AndroidOnly: WP-216 Atomic outgoing reaction admission and captured target identity.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.OutgoingReactionIntentEntity
import com.meshcoreone.android.core.database.PendingSendEntity
import com.meshcoreone.android.core.database.toDTO
import com.meshcoreone.android.core.database.toEntityPreservingTimestamp
import com.meshcoreone.android.core.model.*
import java.nio.ByteBuffer
import java.security.MessageDigest
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
            val fingerprint = targetFingerprint(target)
            var removedStale: OutgoingReactionIntentEntity? = null
            for (old in dao.forTargetMessage(target.radioId.value, target.id)) {
                if (old.targetIncarnation == incarnation && old.targetFingerprint != fingerprint) {
                    if (removeMatchingOccurrence(old)) removedStale = old
                }
            }
            removedStale?.let { reconcileSurvivingTarget(it) }
            val prior = dao.forTarget(target.radioId.value, target.id, incarnation, fingerprint, senderKey, emojiKey)
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
            database.messages().insert(outgoing.copy(
                reactionTargetMessageID = target.id, reactionSenderName = reaction.senderName,
            ).toEntityPreservingTimestamp())
            with(pending) { insertPendingSendWithSequence(queued) }
            val messageIncarnation = database.directAcks().incarnation(target.radioId.value, outgoing.id, "message")?.incarnation
                ?: invalidData("Outgoing reaction message has no incarnation")
            val pendingIncarnation = database.directAcks().incarnation(target.radioId.value, queued.id, "pending")?.incarnation
                ?: invalidData("Outgoing reaction queue row has no incarnation")
            dao.insert(OutgoingReactionIntentEntity(
                target.radioId.value, outgoing.id, queued.id, messageIncarnation, pendingIncarnation, target.id, incarnation, fingerprint, reaction.senderName,
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

    override suspend fun requireCurrentOutgoingReactionTarget(message: EntityKey) =
        context.read("requireCurrentOutgoingReactionTarget") {
            if (!outgoingReactionTargetIsCurrent(message.radioId.value, message.id)) {
                throw PersistenceStoreException(PersistenceStoreError.ReactionTargetChanged)
            }
        }

    override suspend fun isOutgoingReactionAcceptanceKnown(message: EntityKey): Boolean =
        context.read("isOutgoingReactionAcceptanceKnown") {
            val intent = database.outgoingReactionIntents().forMessage(message.radioId.value, message.id)
                ?: invalidData("Outgoing reaction intent is missing")
            intent.completed || isAccepted(intent)
        }

    override suspend fun fetchOutgoingReaction(message: EntityKey): ReactionDTO? = context.write("fetchOutgoingReaction") {
        val intent = database.outgoingReactionIntents().forMessage(message.radioId.value, message.id) ?: return@write null
        if (intent.completed) return@write null
        requireAccepted(intent)
        if (!outgoingReactionTargetIsCurrent(intent.radioId, intent.messageID)) {
            finishObsolete(intent)
            return@write null
        }
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
        if (intent.completed) return@write
        requireAccepted(intent)
        if (!outgoingReactionTargetIsCurrent(intent.radioId, intent.messageID)) {
            finishObsolete(intent)
            return@write
        }
        if (database.outgoingReactionIntents().complete(message.radioId.value, message.id) != 1) {
            invalidData("Outgoing reaction intent is missing")
        }
        save()
    }

    private suspend fun RepositoryTransaction.finishObsolete(intent: OutgoingReactionIntentEntity) {
        if (removeMatchingOccurrence(intent)) {
            reconcileSurvivingTarget(intent)
        }
        if (database.outgoingReactionIntents().complete(intent.radioId, intent.messageID) != 1) {
            invalidData("Outgoing reaction intent is missing")
        }
        save()
    }

    private suspend fun RepositoryTransaction.removeMatchingOccurrence(intent: OutgoingReactionIntentEntity): Boolean {
        val reaction = database.reactions().byId(intent.radioId, intent.messageID)
        if (reaction != null && reaction.messageID == intent.targetID &&
            reaction.targetIncarnation == intent.targetIncarnation && reaction.rawText == intent.rawText &&
            reaction.senderName == intent.senderName && reaction.emoji == intent.emoji &&
            reaction.contactID == intent.contactID && reaction.channelIndex == intent.channelIndex
        ) {
            return database.reactions().deleteOccurrence(intent.radioId, intent.messageID, intent.targetID) == 1
        }
        return false
    }

    private suspend fun RepositoryTransaction.reconcileSurvivingTarget(intent: OutgoingReactionIntentEntity) {
        val radio = intent.radioId
        val targetID = intent.targetID
        if (database.directAcks().incarnation(radio, targetID, "message")?.incarnation != intent.targetIncarnation) return
        val target = database.messages().byId(radio, targetID) ?: return
        if (target.contactID != intent.contactID || target.channelIndex != intent.channelIndex) return
        val index = intent.channelIndex
        if (index != null) {
            val occupant = database.messages().byId(radio, intent.messageID)?.channelOccupantID ?: return
            if (database.channelOccupants().forIndex(radio, index) != occupant ||
                (target.channelOccupantID != null && target.channelOccupantID != occupant)
            ) return
        } else {
            val contact = intent.contactID ?: return
            if (intent.contactPublicKey == null || database.contacts().byId(radio, contact)?.publicKey != intent.contactPublicKey) return
        }
        if (target.reactionSummary == null) return
        val summary = buildReactionSummary(database.reactions().forMessage(radio, targetID, 100).map { it.toDTO() })
        with(ReactionSummaryRepository(context)) { updateSummaryPreservingDirectAuthority(target, summary) }
    }

    override suspend fun retireInvalidOutgoingReaction(message: EntityKey, pendingId: java.util.UUID): Boolean =
        context.write("retireInvalidOutgoingReaction") {
            val radio = message.radioId.value
            val intents = database.outgoingReactionIntents()
            val intent = intents.forMessage(radio, message.id) ?: return@write false
            if (intent.completed || intent.pendingID != pendingId ||
                database.directAcks().incarnation(radio, intent.messageID, "message")?.incarnation != intent.messageIncarnation ||
                database.directAcks().incarnation(radio, pendingId, "pending")?.incarnation != intent.pendingIncarnation ||
                outgoingReactionTargetIsCurrent(radio, message.id)
            ) return@write false
            val rows = database.pendingSends().forMessage(radio, message.id)
            val row = rows.singleOrNull()?.takeIf { it.id == pendingId } ?: return@write false
            val outgoing = database.messages().byId(radio, message.id) ?: return@write false
            val wireMatches = if (intent.targetFingerprint.isEmpty()) {
                textFingerprint(outgoing.text) == intent.rawText
            } else outgoing.text == intent.rawText
            if (!wireMatches || outgoing.directionRawValue != MessageDirection.OUTGOING.rawValue ||
                outgoing.contactID != intent.contactID || outgoing.channelIndex != intent.channelIndex ||
                row.messageID != message.id || row.contactID != intent.contactID ||
                row.channelIndex != intent.channelIndex ||
                (outgoing.statusRawValue !in setOf(
                    MessageStatus.PENDING.rawValue, MessageStatus.SENDING.rawValue, MessageStatus.RETRYING.rawValue,
                ) && !(row.isResend && outgoing.statusRawValue == MessageStatus.FAILED.rawValue)) ||
                database.channelSubmissions().byId(pendingId) != null
            ) return@write false
            val attempts = database.queuedChannelAttempts().forMessage(radio, message.id)
            if (row.kindRawValue == PendingSendKind.CHANNEL.rawValue) {
                if (row.channelDispatchStateRawValue != QueuedChannelDispatchState.READY.rawValue ||
                    row.channelAttemptID != null || row.messageText != outgoing.text ||
                    row.messageTimestamp != outgoing.timestamp ||
                    (intent.targetFingerprint.isNotEmpty() && row.localNodeName != intent.senderName) ||
                    row.channelOccupantID == null || row.channelOccupantID != outgoing.channelOccupantID ||
                    attempts.any {
                        it.phaseRawValue != QueuedChannelAttemptPhase.DEFINITELY_UNSENT.rawValue ||
                            it.isCompleted || (!row.isResend && it.pendingID != pendingId) ||
                            it.messageIncarnation != intent.messageIncarnation
                    }
                ) return@write false
            } else if (row.kindRawValue == PendingSendKind.DM.rawValue) {
                if (attempts.isNotEmpty() ||
                    intents.hasPossiblyDispatchedDirect(radio, message.id)
                ) return@write false
            } else return@write false
            redactOutgoingReactionTarget(radio, message.id)
            val wireFingerprint = if (intent.targetFingerprint.isEmpty()) intent.rawText else textFingerprint(intent.rawText)
            intents.redactIntent(radio, message.id, wireFingerprint)
            database.channelSubmissions().redactMessage(radio, message.id)
            database.pendingSends().delete(radio, pendingId)
            database.reactions().deleteForMessage(radio, message.id)
            if (database.messages().setStatus(radio, message.id, MessageStatus.FAILED.rawValue) != 1) {
                invalidData("Outgoing reaction message is missing")
            }
            save()
            true
        }

    private suspend fun RepositoryTransaction.requireAccepted(intent: OutgoingReactionIntentEntity) {
        if (!isAccepted(intent)) {
            throw PersistenceStoreException(PersistenceStoreError.SaveFailed("Outgoing reaction acceptance is not known"))
        }
    }

    private suspend fun RepositoryTransaction.isAccepted(intent: OutgoingReactionIntentEntity): Boolean {
        if (database.directAcks().incarnation(intent.radioId, intent.messageID, "message")?.incarnation != intent.messageIncarnation ||
            database.directAcks().incarnation(intent.radioId, intent.pendingID, "pending")?.incarnation != intent.pendingIncarnation
        ) invalidData("Outgoing reaction message or queue identity changed")
        return if (intent.channelIndex != null) {
            database.queuedChannelAttempts().forPending(intent.radioId, intent.pendingID).any {
                it.messageID == intent.messageID && it.messageIncarnation == intent.messageIncarnation &&
                    it.phaseRawValue == QueuedChannelAttemptPhase.ACCEPTED.rawValue
            }
        } else {
            database.outgoingReactionIntents().hasAcceptedDirect(
                intent.radioId, intent.messageID, intent.pendingID, intent.messageIncarnation, intent.pendingIncarnation,
            )
        }
    }
}

internal suspend fun RepositoryTransaction.redactOutgoingReactionTarget(radio: java.util.UUID, target: java.util.UUID) {
    val dao = database.outgoingReactionIntents()
    for (intent in dao.forTargetMessage(radio, target)) {
        // After redaction rawText holds only a wire fingerprint, needed to fence later cleanup.
        val fingerprint = if (intent.targetFingerprint.isEmpty()) intent.rawText else textFingerprint(intent.rawText)
        dao.redactIntent(radio, intent.messageID, fingerprint)
    }
}

internal suspend fun RepositoryTransaction.captureOutgoingReactionPending(row: PendingSendEntity): OutgoingReactionIntentEntity? {
    val intent = database.outgoingReactionIntents().forMessage(row.radioId, row.messageID) ?: return null
    if (intent.pendingID != row.id ||
        database.directAcks().incarnation(row.radioId, row.id, "pending")?.incarnation != intent.pendingIncarnation
    ) invalidData("Outgoing reaction pending identity changed")
    return intent
}

internal suspend fun RepositoryTransaction.renewOutgoingReactionPending(intent: OutgoingReactionIntentEntity?) {
    if (intent == null) return
    val next = database.directAcks().incarnation(intent.radioId, intent.pendingID, "pending")?.incarnation
        ?: invalidData("Outgoing reaction pending identity disappeared")
    if (next == intent.pendingIncarnation || database.outgoingReactionIntents().renewPendingIncarnation(
            intent.radioId, intent.messageID, intent.pendingID, intent.pendingIncarnation, next,
        ) != 1
    ) invalidData("Outgoing reaction pending identity did not advance")
}

private fun textFingerprint(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal suspend fun RepositoryTransaction.outgoingReactionTargetIsCurrent(radio: java.util.UUID, message: java.util.UUID): Boolean {
    val intent = database.outgoingReactionIntents().forMessage(radio, message) ?: return true
    return database.directAcks().incarnation(radio, intent.pendingID, "pending")?.incarnation == intent.pendingIncarnation &&
        outgoingReactionTargetMatches(intent)
}

internal suspend fun RepositoryTransaction.outgoingReactionTargetMatches(intent: OutgoingReactionIntentEntity): Boolean {
    val radio = intent.radioId
    if (intent.completed ||
        database.directAcks().incarnation(radio, intent.messageID, "message")?.incarnation != intent.messageIncarnation ||
        database.directAcks().incarnation(radio, intent.targetID, "message")?.incarnation != intent.targetIncarnation
    ) return false
    val target = database.messages().byId(radio, intent.targetID) ?: return false
    val outgoing = database.messages().byId(radio, intent.messageID) ?: return false
    if (target.contactID != intent.contactID || target.channelIndex != intent.channelIndex ||
        targetFingerprint(target.toDTO()) != intent.targetFingerprint ||
        outgoing.contactID != intent.contactID || outgoing.channelIndex != intent.channelIndex ||
        outgoing.text != intent.rawText
    ) return false
    val channelIndex = intent.channelIndex
    return if (channelIndex != null) {
        val occupant = outgoing.channelOccupantID ?: return false
        (target.channelOccupantID == null || target.channelOccupantID == occupant) &&
            database.channelOccupants().forIndex(radio, channelIndex) == occupant
    } else {
        val contact = intent.contactID ?: return false
        intent.contactPublicKey != null && database.contacts().byId(radio, contact)?.publicKey == intent.contactPublicKey
    }
}

private fun targetFingerprint(target: MessageDTO): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: String?) {
        val bytes = value?.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes?.size ?: -1).array())
        if (bytes != null) digest.update(bytes)
    }
    add(target.text)
    add(target.reactionTimestamp.toString())
    add(target.direction.rawValue.toString())
    add(target.senderNodeName)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
