// PortedFrom: MC1Services/Sources/MC1Services/Sync/SyncCoordinator+ReactionHandlers.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** Candidate/target lookups are capped like Swift's `limit: 200`. */
private const val REACTION_LOOKUP_LIMIT = 200L

/** Swift `try?`: null on failure, cancellation rethrown. */
private suspend inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (failure: Exception) {
    failure.rethrowIfCallerCancelled()
    null
}

/**
 * Persists a reaction if it does not already exist, broadcasting the updated summary on success.
 * A failed existence check counts as "not present" (Swift `try?` then `exists != true`).
 *
 * @return true only when persistence completed and the reaction was notification-eligible
 */
internal suspend fun SyncCoordinator.persistReactionIfNew(reaction: ReactionDTO, dependencies: SyncDependencies): Boolean {
    val key = EntityKey(reaction.radioId, reaction.messageID)
    val exists = attempt { dependencies.dataStore.reactionExists(key, reaction.senderName, reaction.emoji) }
    if (exists == true) return false
    val result = contain("persistReactionAndUpdateSummary", null) {
        dependencies.reactionService.persistReactionAndUpdateSummary(reaction, dependencies.dataStore) {
            publishReactionPersisted(it)
        }
    }
    if (result == null) return false
    return result.shouldNotify
}

/** Symmetric reaction-matching window around [anchor]; Swift traps on overflow, Kotlin saturates. */
internal fun reactionTimestampWindow(anchor: UInt): ClosedRange<UInt> {
    val window = SyncCoordinator.REACTION_TIMESTAMP_WINDOW_SECONDS
    val start = if (anchor > window) anchor - window else 0u
    val end = if (anchor > UInt.MAX_VALUE - window) UInt.MAX_VALUE else anchor + window
    return start..end
}

private fun reactionTimestampWindow(at: Instant): ClosedRange<UInt> = reactionTimestampWindow(at.uint32Seconds())

/**
 * Handles an incoming DM reaction (meshcore-open v3, then v1, then native format).
 *
 * @return true when the message was consumed as a reaction (the caller returns early)
 */
internal suspend fun SyncCoordinator.handleDMReaction(
    text: String,
    contact: ContactDTO,
    radioId: RadioId,
    dependencies: SyncDependencies,
): Boolean {
    val codecs = dependencies.codecs
    codecs.meshCoreOpenParser.parse(text)?.let { return handleMCODMReaction(it, text, contact, radioId, dependencies) }
    codecs.meshCoreOpenParser.parseV1(text)?.let { return handleMCOV1DMReaction(it, text, contact, radioId, dependencies) }
    val parsed = codecs.reactionParser.parseDM(text) ?: return false
    val reactions = dependencies.reactionService

    fun reaction(messageID: UUID) = ReactionDTO(
        messageID = messageID, emoji = parsed.emoji, senderName = contact.displayName, messageHash = parsed.messageHash,
        rawText = text, contactID = contact.id, radioId = radioId,
    )

    // Cache first.
    val cachedTarget = contain("findDMTargetMessage", null) { reactions.findDMTargetMessage(parsed.messageHash, contact.id) }
    if (cachedTarget != null) {
        if (persistReactionIfNew(reaction(cachedTarget), dependencies)) {
            log(DebugLogLevel.DEBUG, "Saved DM reaction ${parsed.emoji} to message $cachedTarget")
        }
        return true
    }

    // Persistence fallback.
    val window = reactionTimestampWindow(clock.now())
    val stored = attempt {
        dependencies.dataStore.findDMMessageForReaction(EntityKey(radioId, contact.id), parsed.messageHash, window, REACTION_LOOKUP_LIMIT)
    }
    if (stored != null) {
        if (persistReactionIfNew(reaction(stored.id), dependencies)) {
            log(DebugLogLevel.DEBUG, "Saved DM reaction ${parsed.emoji} to message ${stored.id} (from DB)")
        }
        return true
    }

    // Queue as pending until the target arrives.
    contain("queuePendingDMReaction", Unit) {
        reactions.queuePendingDMReaction(parsed, contact.id, contact.displayName, text, radioId)
    }
    log(DebugLogLevel.DEBUG, "Queued pending DM reaction ${parsed.emoji}")
    return true
}

/**
 * Handles an incoming channel reaction (meshcore-open v3, then v1, then native format).
 *
 * @return true when the message was consumed as a reaction
 */
internal suspend fun SyncCoordinator.handleChannelReaction(
    text: String,
    channelIndex: UByte,
    senderNodeName: String?,
    selfNodeName: String,
    receiveTime: Instant,
    radioId: RadioId,
    dependencies: SyncDependencies,
    deliveryContext: DeliveryContext = DeliveryContext.Live,
): Boolean {
    val codecs = dependencies.codecs
    codecs.meshCoreOpenParser.parse(text)?.let {
        return handleMCOChannelReaction(it, text, channelIndex, senderNodeName, selfNodeName, receiveTime, radioId, dependencies)
    }
    codecs.meshCoreOpenParser.parseV1(text)?.let {
        return handleMCOV1ChannelReaction(it, text, channelIndex, senderNodeName, selfNodeName, radioId, dependencies)
    }
    val reactions = dependencies.reactionService
    val parsed = reactions.tryProcessAsReaction(text) ?: return false
    val senderName = senderNodeName ?: "Unknown"

    val incoming = SyncIncomingChannelReaction(
        parsed, channelIndex, senderName, text, radioId, receiveTime, selfNodeName, deliveryContext,
    )

    var stored: MessageDTO? = null
    val targetID = try {
        reactions.findTargetMessage(parsed, channelIndex) ?: run {
            stored = dependencies.dataStore.findChannelMessageForReaction(
                radioId, channelIndex, parsed, selfNodeName.ifEmpty { null },
                reactionTimestampWindow(receiveTime), REACTION_LOOKUP_LIMIT,
            )
            stored?.id
        }
    } catch (failure: Exception) {
        // Retention cannot suspend: the caller may already be cancelled by session retirement.
        reactions.retainIncomingChannelReaction(incoming)
        failure.rethrowIfCallerCancelled()
        log(DebugLogLevel.ERROR, "Channel reaction target lookup failed; retained for indexed replay: ${failure.syncDescription()}")
        return true
    }
    if (targetID == null) {
        reactions.queueIncomingChannelReaction(incoming)
        return true
    }

    val result = contain("persistIncomingChannelReaction", null) {
        reactions.persistIncomingChannelReaction(incoming, targetID, dependencies.dataStore) {
            publishReactionPersisted(it)
        }
    }
    if (result == null) {
        log(DebugLogLevel.ERROR, "Channel reaction persistence incomplete; retained for indexed replay")
        return true
    }
    stored?.let { target ->
        val targetSenderName = candidateSenderName(target, selfNodeName)
        if (targetSenderName != null) contain("persistIndexedChannelMessage", null) {
            reactions.persistIndexedChannelMessage(
                target.id, radioId, channelIndex, targetSenderName, target.text, target.reactionTimestamp,
                dependencies.dataStore,
            ) { publishReactionPersisted(it) }
        }
    }
    if (result.shouldNotify) log(DebugLogLevel.DEBUG, "Saved reaction ${parsed.emoji} to message $targetID")
    return true
}

/** The sender name a reaction hash covers: our own node name for outgoing rows. */
private fun candidateSenderName(candidate: MessageDTO, selfNodeName: String): String? =
    if (candidate.direction == MessageDirection.OUTGOING) selfNodeName.ifEmpty { null } else candidate.senderNodeName

// MARK: - meshcore-open v3 (Dart hash against DB candidates; no cache, no pending queue)

private suspend fun SyncCoordinator.handleMCODMReaction(
    reaction: SyncParsedMCOReaction,
    rawText: String,
    contact: ContactDTO,
    radioId: RadioId,
    dependencies: SyncDependencies,
): Boolean {
    val parser = dependencies.codecs.meshCoreOpenParser
    val candidates = attempt {
        dependencies.dataStore.fetchDMMessageCandidates(EntityKey(radioId, contact.id), reactionTimestampWindow(clock.now()), REACTION_LOOKUP_LIMIT)
    }
    if (candidates.isNullOrEmpty()) {
        log(DebugLogLevel.DEBUG, "MCO DM reaction ${reaction.emoji}: no candidates in window")
        return true
    }
    for (candidate in candidates) {
        if (dependencies.codecs.reactionParser.isReactionText(candidate.text, isDM = true)) continue
        if (parser.computeReactionHash(candidate.reactionTimestamp, null, candidate.text) != reaction.dartHash) continue
        val dto = ReactionDTO(
            messageID = candidate.id, emoji = reaction.emoji, senderName = contact.displayName,
            messageHash = reaction.dartHash, rawText = rawText, contactID = contact.id, radioId = radioId,
        )
        if (persistReactionIfNew(dto, dependencies)) log(DebugLogLevel.DEBUG, "Saved MCO DM reaction ${reaction.emoji} to message ${candidate.id}")
        return true
    }
    log(DebugLogLevel.DEBUG, "MCO DM reaction ${reaction.emoji}: no hash match found")
    return true
}

private suspend fun SyncCoordinator.handleMCOChannelReaction(
    reaction: SyncParsedMCOReaction,
    rawText: String,
    channelIndex: UByte,
    senderNodeName: String?,
    selfNodeName: String,
    receiveTime: Instant,
    radioId: RadioId,
    dependencies: SyncDependencies,
): Boolean {
    val parser = dependencies.codecs.meshCoreOpenParser
    val senderName = senderNodeName ?: "Unknown"
    val candidates = attempt {
        dependencies.dataStore.fetchChannelMessageCandidates(radioId, channelIndex, reactionTimestampWindow(receiveTime), REACTION_LOOKUP_LIMIT)
    }
    if (candidates.isNullOrEmpty()) {
        log(DebugLogLevel.DEBUG, "MCO channel reaction ${reaction.emoji}: no candidates in window")
        return true
    }
    for (candidate in candidates) {
        if (dependencies.codecs.reactionParser.isReactionText(candidate.text, isDM = false)) continue
        // For channel messages, the Dart hash includes the sender name.
        val hash = parser.computeReactionHash(candidate.reactionTimestamp, candidateSenderName(candidate, selfNodeName), candidate.text)
        if (hash != reaction.dartHash) continue
        val dto = ReactionDTO(
            messageID = candidate.id, emoji = reaction.emoji, senderName = senderName, messageHash = reaction.dartHash,
            rawText = rawText, channelIndex = channelIndex, radioId = radioId,
        )
        if (persistReactionIfNew(dto, dependencies)) log(DebugLogLevel.DEBUG, "Saved MCO channel reaction ${reaction.emoji} to message ${candidate.id}")
        return true
    }
    log(DebugLogLevel.DEBUG, "MCO channel reaction ${reaction.emoji}: no hash match found")
    return true
}

// MARK: - meshcore-open v1 (timestamp anchor + Dart text/sender hashes)

private suspend fun SyncCoordinator.handleMCOV1DMReaction(
    reaction: SyncParsedMCOReactionV1,
    rawText: String,
    contact: ContactDTO,
    radioId: RadioId,
    dependencies: SyncDependencies,
): Boolean {
    val parser = dependencies.codecs.meshCoreOpenParser
    val candidates = attempt {
        dependencies.dataStore.fetchDMMessageCandidates(
            EntityKey(radioId, contact.id), reactionTimestampWindow(reaction.timestampSeconds), REACTION_LOOKUP_LIMIT,
        )
    }
    if (candidates.isNullOrEmpty()) {
        log(DebugLogLevel.DEBUG, "MCO v1 DM reaction ${reaction.emoji}: no candidates in window")
        return true
    }
    for (candidate in candidates) {
        if (dependencies.codecs.reactionParser.isReactionText(candidate.text, isDM = true)) continue
        if (parser.dartStringHash(candidate.text) != reaction.textHash) continue
        val dto = ReactionDTO(
            messageID = candidate.id, emoji = reaction.emoji, senderName = contact.displayName,
            messageHash = reaction.messageIdHash, rawText = rawText, contactID = contact.id, radioId = radioId,
        )
        if (persistReactionIfNew(dto, dependencies)) log(DebugLogLevel.DEBUG, "Saved MCO v1 DM reaction ${reaction.emoji} to message ${candidate.id}")
        return true
    }
    log(DebugLogLevel.DEBUG, "MCO v1 DM reaction ${reaction.emoji}: no hash match found")
    return true
}

private suspend fun SyncCoordinator.handleMCOV1ChannelReaction(
    reaction: SyncParsedMCOReactionV1,
    rawText: String,
    channelIndex: UByte,
    senderNodeName: String?,
    selfNodeName: String,
    radioId: RadioId,
    dependencies: SyncDependencies,
): Boolean {
    val parser = dependencies.codecs.meshCoreOpenParser
    val senderName = senderNodeName ?: "Unknown"
    val candidates = attempt {
        dependencies.dataStore.fetchChannelMessageCandidates(
            radioId, channelIndex, reactionTimestampWindow(reaction.timestampSeconds), REACTION_LOOKUP_LIMIT,
        )
    }
    if (candidates.isNullOrEmpty()) {
        log(DebugLogLevel.DEBUG, "MCO v1 channel reaction ${reaction.emoji}: no candidates in window")
        return true
    }
    for (candidate in candidates) {
        if (dependencies.codecs.reactionParser.isReactionText(candidate.text, isDM = false)) continue
        val name = candidateSenderName(candidate, selfNodeName)
        if (name != null && parser.dartStringHash(name) != reaction.senderNameHash) continue
        if (parser.dartStringHash(candidate.text) != reaction.textHash) continue
        val dto = ReactionDTO(
            messageID = candidate.id, emoji = reaction.emoji, senderName = senderName,
            messageHash = reaction.messageIdHash, rawText = rawText, channelIndex = channelIndex, radioId = radioId,
        )
        if (persistReactionIfNew(dto, dependencies)) log(DebugLogLevel.DEBUG, "Saved MCO v1 channel reaction ${reaction.emoji} to message ${candidate.id}")
        return true
    }
    log(DebugLogLevel.DEBUG, "MCO v1 channel reaction ${reaction.emoji}: no hash match found")
    return true
}
