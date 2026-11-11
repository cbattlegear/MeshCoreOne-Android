// PortedFrom: MC1Services/Sources/MC1Services/Services/ReactionService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.contracts.domain.ReactionPersisting
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
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
    internal val notificationPending: Boolean = false,
)

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
    internal val notificationPending: Boolean = false,
)

/** Result of persisting a reaction. */
data class ReactionPersistResult(val messageID: UUID, val summary: String, val shouldNotify: Boolean = true)

/**
 * Shared bounded reaction queues. Unmatched entries are session-owned and cleared on teardown; unfinished
 * matched entries are requeued as process-owned so an accepted-send retry can finish them after reconnect.
 */
class PendingReactionCustody {
    private val lock = Any()
    private data class ChannelKey(
        val radioId: RadioId,
        val channelIndex: UByte,
        val targetSender: String,
        val messageHash: String,
    )
    private data class DirectKey(val radioId: RadioId, val contactID: UUID, val messageHash: String)
    private data class Owned<out T>(val value: T, val sessionOwner: UUID?)

    private var channel = PendingQueue.empty<ChannelKey, Owned<PendingReaction>>()
    private var direct = PendingQueue.empty<DirectKey, Owned<PendingDMReaction>>()

    internal fun queue(pending: PendingReaction, sessionOwner: UUID) = enqueue(pending, sessionOwner)
    internal fun queue(pending: PendingDMReaction, sessionOwner: UUID) = enqueue(pending, sessionOwner)

    internal fun requeue(pending: PendingReaction) = enqueue(pending, null)
    internal fun requeue(pending: PendingDMReaction) = enqueue(pending, null)

    private fun enqueue(pending: PendingReaction, sessionOwner: UUID?) = synchronized(lock) {
        val key = ChannelKey(
            pending.radioId,
            pending.channelIndex,
            SwiftText.canonical(pending.parsed.targetSender),
            pending.parsed.messageHash,
        )
        channel = channel.enqueue(
            key, Owned(pending, sessionOwner), ReactionService.MAX_PENDING_REACTIONS,
            pending.queueKeyOrder, pending.queueEntryOrder,
        )
    }

    private fun enqueue(pending: PendingDMReaction, sessionOwner: UUID?) = synchronized(lock) {
        val key = DirectKey(pending.radioId, pending.contactID, pending.parsed.messageHash)
        direct = direct.enqueue(
            key, Owned(pending, sessionOwner), ReactionService.MAX_PENDING_REACTIONS,
            pending.queueKeyOrder, pending.queueEntryOrder,
        )
    }

    internal fun takeChannel(
        radioId: RadioId?,
        channelIndex: UByte,
        targetSender: String,
        messageHash: String,
        sessionOwner: UUID,
        includeProcess: Boolean,
    ): List<PendingReaction> = synchronized(lock) {
        val (remaining, matched) = channel.takeMatchingEntries(
            { key ->
                (radioId == null || key.radioId == radioId) &&
                    key.channelIndex == channelIndex &&
                    key.targetSender == SwiftText.canonical(targetSender) && key.messageHash == messageHash
            },
            { value -> includeProcess || value.sessionOwner == sessionOwner },
        ) { owned, keyOrder, entryOrder ->
            owned.value.copy(queueKeyOrder = keyOrder, queueEntryOrder = entryOrder)
        }
        channel = remaining
        matched
    }

    internal fun takeDirect(
        radioId: RadioId?,
        contactID: UUID,
        messageHash: String,
        sessionOwner: UUID,
        includeProcess: Boolean,
    ): List<PendingDMReaction> = synchronized(lock) {
        val (remaining, matched) = direct.takeMatchingEntries(
            { key ->
                (radioId == null || key.radioId == radioId) &&
                    key.contactID == contactID && key.messageHash == messageHash
            },
            { value -> includeProcess || value.sessionOwner == sessionOwner },
        ) { owned, keyOrder, entryOrder ->
            owned.value.copy(queueKeyOrder = keyOrder, queueEntryOrder = entryOrder)
        }
        direct = remaining
        matched
    }

    internal fun clearSession(sessionOwner: UUID) = synchronized(lock) {
        channel = channel.removeMatchingValues { it.sessionOwner == sessionOwner }
        direct = direct.removeMatchingValues { it.sessionOwner == sessionOwner }
    }

    internal val counts: Pair<Int, Int> get() = synchronized(lock) { channel.count to direct.count }
}

/**
 * Service for handling emoji reactions on channel and DM messages: hash-indexed target lookup through the
 * shared [MessageLRUCache] and a session-lifetime queue of reactions whose target has not arrived yet.
 *
 * Swift isolates this in an actor. Here the pending queues are immutable values swapped under one lock that
 * is never held across a suspension; [MessageLRUCache] has its own lock. A separate mutex serializes
 * complete persistence/summary operations across suspension. The process factory shares that mutex and
 * [PendingReactionCustody] across connection generations; caches and unmatched queues remain session-owned.
 * Nothing is scheduled, so no coroutine scope is needed; [clock] stamps `receivedAt` on queued reactions.
 */
class ReactionService internal constructor(
    private val messageCache: MessageLRUCache,
    private val clock: Clock,
    private val persistenceMutex: Mutex = Mutex(),
    private val pendingReactionCustody: PendingReactionCustody? = null,
) {
    /** A service with its own message cache; [clock] also stamps the cache's `indexedAt`. */
    constructor(
        clock: Clock = Clock.systemUTC(),
        persistenceMutex: Mutex = Mutex(),
        pendingReactionCustody: PendingReactionCustody? = null,
    ) : this(MessageLRUCache(clock = clock), clock, persistenceMutex, pendingReactionCustody)

    private val logger: Logger = Logger.getLogger("ReactionService")
    private val lock = Any()
    private val sessionOwner = UUID.randomUUID()

    // Pending reactions queues: no TTL, session lifetime. Guarded by [lock].
    private var pendingReactions = PendingQueue.empty<PendingReactionKey, PendingReaction>()
    private var pendingDMReactions = PendingQueue.empty<PendingDMReactionKey, PendingDMReaction>()

    /** [targetSender] is canonical (NFC) because Swift hashes `String` keys by canonical equivalence. */
    private data class PendingReactionKey(val channelIndex: UByte, val targetSender: String, val messageHash: String) {
        companion object {
            fun of(channelIndex: UByte, targetSender: String, messageHash: String) =
                PendingReactionKey(channelIndex, SwiftText.canonical(targetSender), messageHash)
        }
    }

    private data class PendingDMReactionKey(val contactID: UUID, val messageHash: String)

    /** Indexes a message for reaction matching and returns any pending reactions that now match. */
    fun indexMessage(
        id: UUID,
        channelIndex: UByte,
        senderName: String,
        text: String,
        timestamp: UInt,
        radioId: RadioId? = null,
        includeCustody: Boolean = false,
    ): List<PendingReaction> {
        messageCache.index(messageID = id, channelIndex = channelIndex, senderName = senderName, text = text, timestamp = timestamp)
        val hash = ReactionParser.generateMessageHash(text, timestamp)
        val key = PendingReactionKey.of(channelIndex, senderName, hash)
        val retained = if (includeCustody) {
            pendingReactionCustody?.takeChannel(radioId, channelIndex, senderName, hash, sessionOwner, true).orEmpty()
        } else {
            pendingReactionCustody?.takeChannel(radioId, channelIndex, senderName, hash, sessionOwner, false).orEmpty()
        }
        val matched = synchronized(lock) {
            val (remaining, taken) = pendingReactions.take(key) { value, keyOrder, entryOrder ->
                value.copy(queueKeyOrder = keyOrder, queueEntryOrder = entryOrder)
            }
            pendingReactions = remaining
            taken.orEmpty()
        }
        if (retained.isEmpty() && matched.isEmpty()) return emptyList()
        val allMatches = retained + matched
        logger.fine { "Matched ${allMatches.size} pending reaction(s) to message $id" }
        return allMatches
    }

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
        val custody = pendingReactionCustody
        if (custody == null) synchronized(lock) {
            pendingReactions = pendingReactions.enqueue(key, pending, MAX_PENDING_REACTIONS)
        } else custody.queue(pending, sessionOwner)
        logger.fine { "Queued pending reaction ${parsed.emoji} for ${parsed.targetSender}" }
    }

    /** Returns an unfinished history replay to the bounded queue without replacing its arrival metadata. */
    fun requeuePendingReaction(pending: PendingReaction) {
        val custody = pendingReactionCustody
        if (custody != null) custody.requeue(pending) else {
            val key = PendingReactionKey.of(pending.channelIndex, pending.parsed.targetSender, pending.parsed.messageHash)
            synchronized(lock) {
                pendingReactions = pendingReactions.enqueue(
                    key, pending, MAX_PENDING_REACTIONS, pending.queueKeyOrder, pending.queueEntryOrder,
                )
            }
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
        pendingReactionCustody?.clearSession(sessionOwner)
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
        persistReactionAndUpdateSummaryLocked(reaction, dataStore)
    }

    /**
     * Atomically takes and persists every channel reaction matching a message. The shared persistence lock spans
     * both indexing and storage so an overlapping history replay cannot hide an unfinished batch from an accepted
     * send retry.
     */
    suspend fun persistIndexedChannelMessage(
        id: UUID,
        radioId: RadioId,
        channelIndex: UByte,
        senderName: String,
        text: String,
        timestamp: UInt,
        dataStore: ReactionPersisting,
        onPersisted: suspend (ReactionPersistResult) -> Unit = {},
    ): List<ReactionPersistResult>? = persistenceMutex.withLock {
        val matches = indexMessage(id, channelIndex, senderName, text, timestamp, radioId, includeCustody = true)
        if (matches.any { it.radioId != radioId }) {
            matches.forEach(::requeuePendingReaction)
            throw IllegalStateException("Pending channel reactions belong to another radio")
        }
        persistMatched(
            matches,
            dataStore,
            ::requeuePendingReaction,
            { it.notificationPending },
            { it.copy(notificationPending = true) },
            onPersisted,
        ) { pending ->
            ReactionDTO(
                messageID = id,
                emoji = pending.parsed.emoji,
                senderName = pending.senderNodeName,
                messageHash = pending.parsed.messageHash,
                rawText = pending.rawText,
                receivedAt = pending.receivedAt,
                channelIndex = pending.channelIndex,
                radioId = pending.radioId,
            )
        }
    }

    /** Atomically takes and persists every DM reaction matching a message. */
    suspend fun persistIndexedDirectMessage(
        id: UUID,
        radioId: RadioId,
        contactID: UUID,
        text: String,
        timestamp: UInt,
        dataStore: ReactionPersisting,
        onPersisted: suspend (ReactionPersistResult) -> Unit = {},
    ): List<ReactionPersistResult>? = persistenceMutex.withLock {
        val matches = indexDMMessage(id, contactID, text, timestamp, radioId, includeCustody = true)
        if (matches.any { it.radioId != radioId }) {
            matches.forEach(::requeuePendingDMReaction)
            throw IllegalStateException("Pending direct reactions belong to another radio")
        }
        persistMatched(
            matches,
            dataStore,
            ::requeuePendingDMReaction,
            { it.notificationPending },
            { it.copy(notificationPending = true) },
            onPersisted,
        ) { pending ->
            ReactionDTO(
                messageID = id,
                emoji = pending.parsed.emoji,
                senderName = pending.senderName,
                messageHash = pending.parsed.messageHash,
                rawText = pending.rawText,
                receivedAt = pending.receivedAt,
                contactID = pending.contactID,
                radioId = pending.radioId,
            )
        }
    }

    private suspend fun persistReactionAndUpdateSummaryLocked(
        reaction: ReactionDTO,
        dataStore: ReactionPersisting,
        onSaveAttempt: () -> Unit = {},
    ): ReactionPersistResult? {
        val message = EntityKey(reaction.radioId, reaction.messageID)
        val exists = try {
            dataStore.reactionExists(message, reaction.senderName, reaction.emoji)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.log(Level.SEVERE, "Failed to check reaction for message ${reaction.messageID}: ${error.message}", error)
            return null
        }
        try {
            if (!exists) {
                onSaveAttempt()
                dataStore.saveReaction(reaction)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.log(Level.SEVERE, "Failed to save reaction for message ${reaction.messageID}: ${error.message}", error)
            return null
        }
        return reconcileSummaryLocked(message, dataStore)?.copy(shouldNotify = !exists)
    }

    private suspend fun <T> persistMatched(
        matches: List<T>,
        dataStore: ReactionPersisting,
        requeue: (T) -> Unit,
        isNotificationPending: (T) -> Boolean,
        markNotificationPending: (T) -> T,
        onPersisted: suspend (ReactionPersistResult) -> Unit,
        toDTO: (T) -> ReactionDTO,
    ): List<ReactionPersistResult>? {
        val results = ArrayList<ReactionPersistResult>(matches.size)
        var next = 0
        var retryCurrentNotification = false
        try {
            while (next < matches.size) {
                val pending = matches[next]
                retryCurrentNotification = isNotificationPending(pending)
                val result = persistReactionAndUpdateSummaryLocked(toDTO(pending), dataStore) {
                    retryCurrentNotification = true
                }
                if (result == null) {
                    requeue(if (retryCurrentNotification) markNotificationPending(pending) else pending)
                    matches.drop(next + 1).forEach(requeue)
                    return null
                }
                val completed = result.copy(shouldNotify = result.shouldNotify || retryCurrentNotification)
                results += completed
                retryCurrentNotification = completed.shouldNotify
                onPersisted(completed)
                next++
                retryCurrentNotification = false
            }
        } catch (cancelled: CancellationException) {
            if (next < matches.size) {
                val pending = matches[next]
                requeue(if (retryCurrentNotification) markNotificationPending(pending) else pending)
                matches.drop(next + 1).forEach(requeue)
            }
            throw cancelled
        } catch (failure: Exception) {
            if (next < matches.size) {
                val pending = matches[next]
                requeue(if (retryCurrentNotification) markNotificationPending(pending) else pending)
                matches.drop(next + 1).forEach(requeue)
            }
            throw failure
        }
        return results
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
    fun indexDMMessage(
        id: UUID,
        contactID: UUID,
        text: String,
        timestamp: UInt,
        radioId: RadioId? = null,
        includeCustody: Boolean = false,
    ): List<PendingDMReaction> {
        messageCache.indexDM(messageID = id, contactID = contactID, text = text, timestamp = timestamp)
        val hash = ReactionParser.generateMessageHash(text, timestamp)
        val key = PendingDMReactionKey(contactID, hash)
        val retained = if (includeCustody) {
            pendingReactionCustody?.takeDirect(radioId, contactID, hash, sessionOwner, true).orEmpty()
        } else {
            pendingReactionCustody?.takeDirect(radioId, contactID, hash, sessionOwner, false).orEmpty()
        }
        val matched = synchronized(lock) {
            val (remaining, taken) = pendingDMReactions.take(key) { value, keyOrder, entryOrder ->
                value.copy(queueKeyOrder = keyOrder, queueEntryOrder = entryOrder)
            }
            pendingDMReactions = remaining
            taken.orEmpty()
        }
        if (retained.isEmpty() && matched.isEmpty()) return emptyList()
        val allMatches = retained + matched
        logger.fine { "Matched ${allMatches.size} pending DM reaction(s) to message $id" }
        return allMatches
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
        val custody = pendingReactionCustody
        if (custody == null) synchronized(lock) {
            pendingDMReactions = pendingDMReactions.enqueue(key, pending, MAX_PENDING_REACTIONS)
        } else custody.queue(pending, sessionOwner)
        logger.fine { "Queued pending DM reaction ${parsed.emoji}" }
    }

    /** Returns an unfinished history replay to the bounded queue without replacing its arrival metadata. */
    fun requeuePendingDMReaction(pending: PendingDMReaction) {
        val custody = pendingReactionCustody
        if (custody != null) custody.requeue(pending) else {
            val key = PendingDMReactionKey(pending.contactID, pending.parsed.messageHash)
            synchronized(lock) {
                pendingDMReactions = pendingDMReactions.enqueue(
                    key, pending, MAX_PENDING_REACTIONS, pending.queueKeyOrder, pending.queueEntryOrder,
                )
            }
        }
    }

    /** Test-visible total of queued reactions (channel, DM). */
    internal val pendingCounts: Pair<Int, Int>
        get() {
            val local = synchronized(lock) { pendingReactions.count to pendingDMReactions.count }
            val retained = pendingReactionCustody?.counts ?: (0 to 0)
            return (local.first + retained.first) to (local.second + retained.second)
        }

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

    fun removeMatchingValues(matchesValue: (V) -> Boolean): PendingQueue<K, V> {
        var nextEntries = entries
        var nextKeyOrders = keyOrders
        for ((key, values) in entries) {
            val remaining = values.filterNot { matchesValue(it.value) }
            if (remaining.size != values.size) {
                if (remaining.isEmpty()) {
                    nextEntries = nextEntries - key
                    nextKeyOrders = nextKeyOrders - key
                } else {
                    nextEntries = nextEntries + (key to remaining)
                }
            }
        }
        return PendingQueue(nextEntries, nextKeyOrders, nextOrder)
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
        val (remaining, matched) = takeMatching({ it == key }, withOrder)
        return remaining to matched.ifEmpty { null }
    }

    fun takeMatching(
        matchesKey: (K) -> Boolean,
        withOrder: (V, Long, Long) -> V,
    ): Pair<PendingQueue<K, V>, List<V>> {
        return takeMatchingEntries(matchesKey, { true }, withOrder)
    }

    fun <R> takeMatchingEntries(
        matchesKey: (K) -> Boolean,
        matchesValue: (V) -> Boolean,
        withOrder: (V, Long, Long) -> R,
    ): Pair<PendingQueue<K, V>, List<R>> {
        var nextEntries = entries
        var nextKeyOrders = keyOrders
        val matched = ArrayList<Triple<Long, Long, R>>()
        for ((key, values) in entries) {
            if (!matchesKey(key)) continue
            val keyOrder = keyOrders.getValue(key)
            val taking = values.filter { matchesValue(it.value) }
            if (taking.isEmpty()) continue
            matched += taking.map { Triple(keyOrder, it.order, withOrder(it.value, keyOrder, it.order)) }
            val remaining = values.filterNot { matchesValue(it.value) }
            if (remaining.isEmpty()) {
                nextEntries = nextEntries - key
                nextKeyOrders = nextKeyOrders - key
            } else {
                nextEntries = nextEntries + (key to remaining)
            }
        }
        return PendingQueue(nextEntries, nextKeyOrders, nextOrder) to
            matched.sortedWith(compareBy<Triple<Long, Long, R>> { it.first }.thenBy { it.second }).map { it.third }
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
