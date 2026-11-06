// PortedFrom: MC1Services/Sources/MC1Services/Services/ReactionService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.CapturedChannelReactionTarget
import com.meshcoreone.android.core.contracts.domain.ChannelReactionOccurrence
import com.meshcoreone.android.core.contracts.domain.ChannelReactionRejection
import com.meshcoreone.android.core.contracts.domain.ChannelReactionUnprovenReason
import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.contracts.domain.ReactionPersisting
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.services.rendering.MessageCandidate
import com.meshcoreone.android.core.services.rendering.MessageLRUCache
import com.meshcoreone.android.core.services.rendering.SwiftText
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A reaction waiting for its target message to be indexed. */
data class PendingReaction(
    val parsed: ParsedReaction,
    val channelIndex: UByte,
    val senderNodeName: String,
    val rawText: String,
    val radioId: RadioId,
    val receivedAt: Instant,
    internal val queueKeyOrder: Long? = null,
    internal val queueEntryOrder: Long? = null,
    val boundTarget: CapturedChannelReactionTarget? = null,
    val mutationUncertain: Boolean = false,
    val originalAuthorityUnavailable: Boolean = false,
    val unboundOriginalMessageID: UUID? = null,
    val reactionOccurrence: ChannelReactionOccurrence? = null,
)

enum class ParkedChannelReactionFormat { MCO_V1, MCO_V3 }

sealed interface ParkedChannelReactionFailure {
    data object Stale : ParkedChannelReactionFailure
    data class Rejected(val reason: ChannelReactionRejection) : ParkedChannelReactionFailure
    data class Unknown(val cause: Exception) : ParkedChannelReactionFailure
    data class Unproven(val reason: ChannelReactionUnprovenReason) : ParkedChannelReactionFailure
}

/** Original non-native wire selection retained for diagnosis, never fed into native matching/retry. */
data class ParkedChannelReaction(
    val captured: CapturedChannelReactionTarget,
    val reaction: ReactionDTO,
    val format: ParkedChannelReactionFormat,
    val failure: ParkedChannelReactionFailure,
    val reactionOccurrence: ChannelReactionOccurrence,
)

sealed interface ChannelReactionParkResult {
    data object Retained : ChannelReactionParkResult
    data class Overflow(val unretained: ParkedChannelReaction) : ChannelReactionParkResult
}

enum class UnselectedChannelReactionFormat { NATIVE, MCO_V1, MCO_V3 }

/** Failed original selection input: diagnostic custody only, never automatic matching or redrive. */
data class UnselectedChannelReaction(
    val rawText: String,
    val channelIndex: UByte,
    val radioId: RadioId,
    val senderNodeName: String,
    val localNodeName: String?,
    val receivedAt: Instant,
    val format: UnselectedChannelReactionFormat,
    val failure: Exception,
    val reactionOccurrence: ChannelReactionOccurrence,
    val originalUnboundMessageId: UUID? = null,
)

sealed interface UnselectedChannelReactionParkResult {
    data object Retained : UnselectedChannelReactionParkResult
    data class Overflow(val unretained: UnselectedChannelReaction) : UnselectedChannelReactionParkResult
}

sealed interface NativeChannelReactionFailure {
    data object Stale : NativeChannelReactionFailure
    data class Rejected(val reason: ChannelReactionRejection) : NativeChannelReactionFailure
    data class Unknown(val cause: Exception) : NativeChannelReactionFailure
    data object CapacityExceeded : NativeChannelReactionFailure
    data class Unbound(val messageID: UUID) : NativeChannelReactionFailure
    data class AuthorityUnavailable(val cause: Exception) : NativeChannelReactionFailure
    data class Unproven(val reason: ChannelReactionUnprovenReason) : NativeChannelReactionFailure
}

data class ParkedNativeChannelReaction(
    val pending: PendingReaction,
    val failure: NativeChannelReactionFailure,
)

sealed interface NativeChannelReactionParkResult {
    data object Retained : NativeChannelReactionParkResult
    data class Overflow(val unretained: ParkedNativeChannelReaction) : NativeChannelReactionParkResult
}

sealed interface ChannelReactionUnretainedWork {
    data class Selected(val work: ParkedChannelReaction) : ChannelReactionUnretainedWork
    data class Native(val work: SnapshotList<ParkedNativeChannelReaction>) : ChannelReactionUnretainedWork {
        init { require(work.isNotEmpty() && work.size <= ReactionService.MAX_PENDING_REACTIONS) }
    }
    data class Unselected(val work: UnselectedChannelReaction) : ChannelReactionUnretainedWork
}

class ChannelReactionRetentionException(
    val work: ChannelReactionUnretainedWork,
    cause: Exception,
) : Exception("Original channel reaction work exceeded retention capacity; no redrive is permitted", cause)

class ChannelReactionRetentionCancellation(
    val work: ChannelReactionUnretainedWork,
    original: CancellationException,
) : CancellationException("Original channel reaction cancellation exceeded retention capacity; no redrive is permitted") {
    init { initCause(original) }
}

/** A DM reaction waiting for its target message to be indexed. */
data class PendingDMReaction(
    val parsed: ParsedDMReaction,
    val contactID: UUID,
    val senderName: String,
    val rawText: String,
    val radioId: RadioId,
    val receivedAt: Instant,
    internal val queueKeyOrder: Long? = null,
    internal val queueEntryOrder: Long? = null,
)

/** Result of persisting a reaction. */
data class ReactionPersistResult(val messageID: UUID, val summary: String)

/**
 * Service for handling emoji reactions on channel and DM messages: hash-indexed target lookup through the
 * shared [MessageLRUCache] and a session-lifetime queue of reactions whose target has not arrived yet.
 *
 * Swift isolates this in an actor. Here the pending queues are immutable values swapped under one lock that
 * is never held across a suspension; [MessageLRUCache] has its own lock. A separate mutex serializes
 * complete persistence/summary operations across suspension. The process factory shares that mutex
 * across connection generations; queues and caches remain session-owned. Nothing is scheduled, so no coroutine
 * scope is needed; [clock] stamps `receivedAt` on queued reactions.
 */
class ReactionService internal constructor(
    private val messageCache: MessageLRUCache,
    private val clock: Clock,
    private val persistenceMutex: Mutex = Mutex(),
) {
    /** A service with its own message cache; [clock] also stamps the cache's `indexedAt`. */
    constructor(clock: Clock = Clock.systemUTC(), persistenceMutex: Mutex = Mutex()) :
        this(MessageLRUCache(clock = clock), clock, persistenceMutex)

    private val logger: Logger = Logger.getLogger("ReactionService")
    private val lock = Any()

    // Pending reactions queues: no TTL, session lifetime. Guarded by [lock].
    private var pendingReactions = PendingQueue.empty<PendingReactionKey, PendingReaction>()
    private var pendingDMReactions = PendingQueue.empty<PendingDMReactionKey, PendingDMReaction>()

    private var parkedChannelReactions = PendingQueue.empty<Unit, ParkedChannelReaction>()
    private var unselectedChannelReactions = PendingQueue.empty<Unit, UnselectedChannelReaction>()
    private data class NativeEntry(
        val order: Long,
        val pending: PendingReaction,
        val parked: ParkedNativeChannelReaction? = null,
    ) {
        val failure: NativeChannelReactionFailure? get() = parked?.failure
    }
    private var nativeChannelReactions: List<NativeEntry> = emptyList()
    private var nextNativeOrder = 0L

    /** [targetSender] is canonical (NFC) because Swift hashes `String` keys by canonical equivalence. */
    private data class PendingReactionKey(val channelIndex: UByte, val targetSender: String, val messageHash: String) {
        companion object {
            fun of(channelIndex: UByte, targetSender: String, messageHash: String) =
                PendingReactionKey(channelIndex, SwiftText.canonical(targetSender), messageHash)
        }
    }

    private data class PendingDMReactionKey(val contactID: UUID, val messageHash: String)

    /** Indexes a message for reaction matching and returns any pending reactions that now match. */
    fun indexMessage(id: UUID, channelIndex: UByte, senderName: String, text: String, timestamp: UInt): List<PendingReaction> {
        messageCache.index(messageID = id, channelIndex = channelIndex, senderName = senderName, text = text, timestamp = timestamp)
        // Check pending queue for matching reactions.
        val key = PendingReactionKey.of(channelIndex, senderName, ReactionParser.generateMessageHash(text, timestamp))
        val matched = synchronized(lock) {
            val (remaining, taken) = pendingReactions.take(key) { value, keyOrder, entryOrder ->
                value.copy(queueKeyOrder = keyOrder, queueEntryOrder = entryOrder)
            }
            pendingReactions = remaining
            taken
        } ?: return emptyList()
        logger.fine { "Matched ${matched.size} pending reaction(s) to message $id" }
        return matched
    }

    private fun appendNativeEntry(pending: PendingReaction, parked: ParkedNativeChannelReaction? = null) {
        val order = nextNativeOrder
        nextNativeOrder = Math.incrementExact(nextNativeOrder)
        nativeChannelReactions = nativeChannelReactions + NativeEntry(order, pending, parked)
    }

    fun parkNativeChannelReaction(work: ParkedNativeChannelReaction): NativeChannelReactionParkResult {
        val result = synchronized(lock) {
            if (nativeChannelReactions.size >= MAX_PENDING_REACTIONS) NativeChannelReactionParkResult.Overflow(work)
            else {
                appendNativeEntry(work.pending, work)
                NativeChannelReactionParkResult.Retained
            }
        }
        if (result is NativeChannelReactionParkResult.Overflow) {
            logger.severe("Protected native reaction retention is full; exact original work returned as typed overflow")
        } else {
            logger.warning("Original native reaction retained without redrive authority: ${work.failure::class.simpleName}")
        }
        return result
    }

    fun retainedNativeChannelReactionFailures(radioId: RadioId): List<ParkedNativeChannelReaction> =
        synchronized(lock) {
            nativeChannelReactions.mapNotNull { entry -> entry.parked?.takeIf { entry.pending.radioId == radioId } }
        }

    fun parkChannelReaction(work: ParkedChannelReaction): ChannelReactionParkResult {
        require(work.reaction.radioId == work.captured.message.radioId)
        require(work.reaction.messageID == work.captured.message.id)
        require(work.reaction.channelIndex == work.captured.message.channelIndex)
        require(work.reaction.channelIndex != null && work.reaction.contactID == null)
        val result = synchronized(lock) {
            if (parkedChannelReactions.count >= MAX_PENDING_REACTIONS) ChannelReactionParkResult.Overflow(work)
            else {
                parkedChannelReactions = parkedChannelReactions.enqueue(Unit, work, MAX_PENDING_REACTIONS)
                ChannelReactionParkResult.Retained
            }
        }
        if (result is ChannelReactionParkResult.Overflow) {
            logger.severe("Channel reaction failure queue is full; original work returned as typed unretained overflow")
        } else {
            logger.warning("Original ${work.format} channel reaction retained without replay authority: ${work.failure::class.simpleName}")
        }
        return result
    }

    fun retainedChannelReactionFailures(radioId: RadioId): List<ParkedChannelReaction> =
        synchronized(lock) { parkedChannelReactions.valuesInOrder().filter { it.reaction.radioId == radioId } }

    fun parkUnselectedChannelReaction(work: UnselectedChannelReaction): UnselectedChannelReactionParkResult {
        val result = synchronized(lock) {
            if (unselectedChannelReactions.count >= MAX_PENDING_REACTIONS) {
                UnselectedChannelReactionParkResult.Overflow(work)
            } else {
                unselectedChannelReactions = unselectedChannelReactions.enqueue(Unit, work, MAX_PENDING_REACTIONS)
                UnselectedChannelReactionParkResult.Retained
            }
        }
        if (result is UnselectedChannelReactionParkResult.Overflow) {
            logger.severe("Unselected channel reaction failure queue is full; exact input returned as typed unretained overflow")
        } else {
            logger.warning("Original ${work.format} channel reaction selection failed; input retained without redrive authority")
        }
        return result
    }

    fun retainedUnselectedChannelReactionFailures(radioId: RadioId): List<UnselectedChannelReaction> =
        synchronized(lock) { unselectedChannelReactions.valuesInOrder().filter { it.radioId == radioId } }

    /** Builds channel reaction wire text: `@[sender]emoji` then newline and hash. */
    fun buildReactionText(emoji: String, targetSender: String, targetText: String, targetTimestamp: UInt): String =
        "@[$targetSender]$emoji\n${ReactionParser.generateMessageHash(targetText, targetTimestamp)}"

    /** Builds DM reaction wire format (shorter, no sender). */
    fun buildDMReactionText(emoji: String, targetText: String, targetTimestamp: UInt): String =
        ReactionParser.buildDMReactionText(emoji, targetText, targetTimestamp)

    /** Finds the target message ID for a parsed reaction: the most recently indexed hash match. */
    fun findTargetMessage(parsed: ParsedReaction, channelIndex: UByte): UUID? =
        messageCache.lookup(channelIndex = channelIndex, senderName = parsed.targetSender, messageHash = parsed.messageHash)
            .mostRecentlyIndexed()?.messageID

    /** Attempts to parse incoming text as a channel reaction; null means process it as a regular message. */
    internal fun tryProcessAsReaction(text: String): ParsedReaction? = ReactionParser.parse(text)

    /** Queues a reaction that couldn't find its target message. */
    fun queuePendingReaction(
        parsed: ParsedReaction,
        channelIndex: UByte,
        senderNodeName: String,
        rawText: String,
        radioId: RadioId,
    ) {
        val key = PendingReactionKey.of(channelIndex, parsed.targetSender, parsed.messageHash)
        val pending = PendingReaction(parsed, channelIndex, senderNodeName, rawText, radioId, clock.instant())
        synchronized(lock) { pendingReactions = pendingReactions.enqueue(key, pending, MAX_PENDING_REACTIONS) }
        logger.fine { "Queued pending reaction ${parsed.emoji} for ${parsed.targetSender}" }
    }

    /** Returns an unfinished history replay to the bounded queue without replacing its arrival metadata. */
    fun requeuePendingReaction(pending: PendingReaction) {
        val key = PendingReactionKey.of(pending.channelIndex, pending.parsed.targetSender, pending.parsed.messageHash)
        synchronized(lock) {
            pendingReactions = pendingReactions.enqueue(
                key, pending, MAX_PENDING_REACTIONS, pending.queueKeyOrder, pending.queueEntryOrder,
            )
        }
    }

    /** Clears all pending reactions (call on disconnect). */
    internal fun clearPendingReactions() {
        val cleared = synchronized(lock) {
            val count = pendingReactions.count + pendingDMReactions.count
            pendingReactions = pendingReactions.cleared()
            pendingDMReactions = pendingDMReactions.cleared()
            count
        }
        if (cleared > 0) logger.fine { "Cleared $cleared pending reaction(s)" }
    }

    // MARK: - Persistence

    /**
     * Persists a reaction unless the store already has its sender/emoji, then rebuilds the summary from the
     * newest 100 reactions (the store's default fetch limit). Store failures are logged and yield null;
     * cancellation propagates.
     */
    suspend fun persistReactionAndUpdateSummary(
        reaction: ReactionDTO,
        dataStore: ReactionPersisting,
    ): ReactionPersistResult? = persistenceMutex.withLock {
        val message = EntityKey(reaction.radioId, reaction.messageID)
        val exists = try {
            dataStore.reactionExists(message, reaction.senderName, reaction.emoji)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.log(Level.SEVERE, "Failed to check reaction for message ${reaction.messageID}: ${error.message}", error)
            return@withLock null
        }
        try {
            if (!exists) dataStore.saveReaction(reaction)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.log(Level.SEVERE, "Failed to save reaction for message ${reaction.messageID}: ${error.message}", error)
            return@withLock null
        }
        reconcileSummaryLocked(message, dataStore)
    }

    /** Rebuilds a committed reaction's summary without saving or replacing the reaction row again. */
    suspend fun reconcileSummary(message: EntityKey, dataStore: ReactionPersisting): ReactionPersistResult? =
        persistenceMutex.withLock { reconcileSummaryLocked(message, dataStore) }

    private suspend fun reconcileSummaryLocked(message: EntityKey, dataStore: ReactionPersisting): ReactionPersistResult? {
        val reactions = try {
            dataStore.fetchReactions(message)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.log(Level.SEVERE, "Failed to fetch reactions for message ${message.id}: ${error.message}", error)
            return null
        }

        val summary = ReactionParser.buildSummary(reactions)
        try {
            dataStore.updateMessageReactionSummary(message, summary)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.log(Level.SEVERE, "Failed to update reaction summary for message ${message.id}: ${error.message}", error)
            return null
        }
        return ReactionPersistResult(message.id, summary)
    }

    // MARK: - DM Reactions

    /** Indexes a DM message for reaction matching and returns any pending reactions that now match. */
    fun indexDMMessage(id: UUID, contactID: UUID, text: String, timestamp: UInt): List<PendingDMReaction> {
        messageCache.indexDM(messageID = id, contactID = contactID, text = text, timestamp = timestamp)
        val key = PendingDMReactionKey(contactID, ReactionParser.generateMessageHash(text, timestamp))
        val matched = synchronized(lock) {
            val (remaining, taken) = pendingDMReactions.take(key) { value, keyOrder, entryOrder ->
                value.copy(queueKeyOrder = keyOrder, queueEntryOrder = entryOrder)
            }
            pendingDMReactions = remaining
            taken
        } ?: return emptyList()
        logger.fine { "Matched ${matched.size} pending DM reaction(s) to message $id" }
        return matched
    }

    /** Finds the target DM message ID by hash and contact: the most recently indexed match. */
    fun findDMTargetMessage(messageHash: String, contactID: UUID): UUID? =
        messageCache.lookupDM(contactID = contactID, messageHash = messageHash).mostRecentlyIndexed()?.messageID

    /** Queues a DM reaction that couldn't find its target message. */
    fun queuePendingDMReaction(
        parsed: ParsedDMReaction,
        contactID: UUID,
        senderName: String,
        rawText: String,
        radioId: RadioId,
    ) {
        val key = PendingDMReactionKey(contactID, parsed.messageHash)
        val pending = PendingDMReaction(parsed, contactID, senderName, rawText, radioId, clock.instant())
        synchronized(lock) { pendingDMReactions = pendingDMReactions.enqueue(key, pending, MAX_PENDING_REACTIONS) }
        logger.fine { "Queued pending DM reaction ${parsed.emoji}" }
    }

    /** Returns an unfinished history replay to the bounded queue without replacing its arrival metadata. */
    fun requeuePendingDMReaction(pending: PendingDMReaction) {
        val key = PendingDMReactionKey(pending.contactID, pending.parsed.messageHash)
        synchronized(lock) {
            pendingDMReactions = pendingDMReactions.enqueue(
                key, pending, MAX_PENDING_REACTIONS, pending.queueKeyOrder, pending.queueEntryOrder,
            )
        }
    }

    /** Test-visible total of queued reactions (channel, DM). */
    internal val pendingCounts: Pair<Int, Int> get() = synchronized(lock) { pendingReactions.count to pendingDMReactions.count }

    internal companion object {
        const val MAX_PENDING_REACTIONS: Int = 100

        /**
         * The candidate with the latest `indexedAt`, keeping the first equal date like Swift's `max(by:)`.
         */
        private fun List<MessageCandidate>.mostRecentlyIndexed(): MessageCandidate? =
            fold(null as MessageCandidate?) { best, candidate -> if (best == null || candidate.indexedAt > best.indexedAt) candidate else best }
    }
}

/**
 * Immutable pending-reaction queue: entries grouped by key plus first-queued key order. Swift keeps the two
 * dictionaries/arrays inline and duplicates the eviction loop for channel and DM. Ordinals additionally let a
 * failed history drain restore its original key and entry priority, independently of wall-clock timestamps.
 */
internal class PendingQueue<K, V> private constructor(
    private val entries: Map<K, List<Entry<V>>>,
    private val keyOrders: Map<K, Long>,
    private val nextOrder: Long,
) {
    private data class Entry<V>(val value: V, val order: Long)
    val count: Int get() = entries.values.sumOf { it.size }

    fun cleared(): PendingQueue<K, V> = PendingQueue(emptyMap(), emptyMap(), nextOrder)

    fun valuesInOrder(): List<V> = keyOrders.entries.sortedBy { it.value }.flatMap { (key, _) ->
        entries.getValue(key).map { it.value }
    }

    /** Appends [value] under [key] (a new key joins the end of the order), then evicts down to [maxCount]. */
    fun enqueue(
        key: K, value: V, maxCount: Int, restoredKeyOrder: Long? = null, restoredEntryOrder: Long? = null,
    ): PendingQueue<K, V> {
        require((restoredKeyOrder == null) == (restoredEntryOrder == null))
        val entryOrder = restoredEntryOrder ?: nextOrder
        val keyOrder = minOf(keyOrders[key] ?: Long.MAX_VALUE, restoredKeyOrder ?: nextOrder)
        val values = (entries[key].orEmpty() + Entry(value, entryOrder)).sortedBy { it.order }
        val queued = PendingQueue(
            entries + (key to values), keyOrders + (key to keyOrder),
            maxOf(nextOrder, Math.incrementExact(entryOrder)),
        )
        return queued.evicted(maxCount)
    }

    /** Removes every value queued under [key]; returns the remaining queue and the values (null when none). */
    fun take(key: K, withOrder: (V, Long, Long) -> V): Pair<PendingQueue<K, V>, List<V>?> {
        val matched = entries[key] ?: return this to null
        val keyOrder = keyOrders.getValue(key)
        return PendingQueue(entries - key, keyOrders - key, nextOrder) to
            matched.map { withOrder(it.value, keyOrder, it.order) }
    }

    /**
     * Swift `evictIfNeeded`: while over [maxCount], drop the oldest value of the oldest key; a key whose list
     * empties (or that has no list) leaves the order.
     */
    private fun evicted(maxCount: Int): PendingQueue<K, V> {
        var total = count
        if (total <= maxCount) return this
        var nextEntries = entries
        var nextKeyOrders = keyOrders
        while (total > maxCount && nextKeyOrders.isNotEmpty()) {
            val oldestKey = nextKeyOrders.minBy { it.value }.key
            val values = nextEntries[oldestKey]
            if (!values.isNullOrEmpty()) {
                val remaining = values.drop(1)
                total -= 1
                if (remaining.isEmpty()) {
                    nextEntries = nextEntries - oldestKey
                    nextKeyOrders = nextKeyOrders - oldestKey
                } else {
                    nextEntries = nextEntries + (oldestKey to remaining)
                }
            } else {
                nextKeyOrders = nextKeyOrders - oldestKey
            }
        }
        return PendingQueue(nextEntries, nextKeyOrders, nextOrder)
    }

    companion object {
        fun <K, V> empty(): PendingQueue<K, V> = PendingQueue(emptyMap(), emptyMap(), 0)
    }
}
