// PortedFrom: MC1Services/Sources/MC1Services/Services/MessageLRUCache.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.contracts.domain.CapturedChannelReactionTarget
import com.meshcoreone.android.core.model.RadioId
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Key for channel message lookup. [senderName] is canonical (NFC), matching Swift `String` hashing. */
internal data class MessageCacheKey(
    val channelIndex: UByte,
    val senderName: String,
    val messageHash: String,
    val radioId: RadioId? = null,
)

/** Key for DM message lookup. */
internal data class DirectMessageCacheKey(val contactID: UUID, val messageHash: String)

/** Candidate message for reaction matching. */
internal data class MessageCandidate(
    val messageID: UUID,
    val text: String,
    val timestamp: UInt,
    val indexedAt: Instant,
    val channelTarget: CapturedChannelReactionTarget? = null,
    val indexOrder: Long = 0,
)

/**
 * LRU cache of recent channel and DM messages for reaction matching with collision resolution. Keys are
 * evicted least-recently-indexed first once more than [capacity] keys exist; each key keeps at most
 * [maxCandidatesPerKey] candidates (most recent). Swift isolates this in an actor; here every operation
 * runs under one lock and never suspends.
 */
internal class MessageLRUCache(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxCandidatesPerKey: Int = DEFAULT_MAX_CANDIDATES_PER_KEY,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val lock = Any()
    private var cache: Map<MessageCacheKey, List<MessageCandidate>> = emptyMap()
    private var order: List<MessageCacheKey> = emptyList()
    private var dmCache: Map<DirectMessageCacheKey, List<MessageCandidate>> = emptyMap()
    private var dmOrder: List<DirectMessageCacheKey> = emptyList()
    private var nextChannelIndexOrder: Long = 0

    /** Indexes a channel message for later lookup. */
    fun index(messageID: UUID, channelIndex: UByte, senderName: String, text: String, timestamp: UInt) {
        val key = MessageCacheKey(channelIndex, SwiftText.canonical(senderName), ReactionWireFormat.generateMessageHash(text, timestamp))
        synchronized(lock) {
            val candidate = MessageCandidate(messageID, text, timestamp, clock.instant(), indexOrder = nextChannelIndexOrder)
            nextChannelIndexOrder = Math.incrementExact(nextChannelIndexOrder)
            indexChannelCandidate(key, candidate)
        }
    }

    /** Candidates for a channel key, oldest first; empty when unknown. */
    fun lookup(channelIndex: UByte, senderName: String, messageHash: String): List<MessageCandidate> =
        synchronized(lock) { cache[MessageCacheKey(channelIndex, SwiftText.canonical(senderName), messageHash)].orEmpty() }

    /** Only an original storage-issued capture enters the protected, radio-scoped cache. */
    fun indexChannelTarget(captured: CapturedChannelReactionTarget, senderName: String) {
        val message = captured.message
        val channelIndex = requireNotNull(message.channelIndex)
        require(message.contactID == null)
        val key = MessageCacheKey(
            channelIndex, SwiftText.canonical(senderName),
            ReactionWireFormat.generateMessageHash(message.text, message.reactionTimestamp), message.radioId,
        )
        synchronized(lock) {
            val candidate = MessageCandidate(
                message.id, message.text, message.reactionTimestamp, clock.instant(), captured, nextChannelIndexOrder,
            )
            nextChannelIndexOrder = Math.incrementExact(nextChannelIndexOrder)
            indexChannelCandidate(key, candidate)
        }
    }

    fun lookupChannelTargets(
        radioId: RadioId,
        channelIndex: UByte,
        senderName: String,
        messageHash: String,
    ): List<MessageCandidate> = synchronized(lock) {
        cache[MessageCacheKey(channelIndex, SwiftText.canonical(senderName), messageHash, radioId)].orEmpty()
    }

    fun lookupChannelCandidates(
        radioId: RadioId,
        channelIndex: UByte,
        senderName: String,
        messageHash: String,
    ): List<MessageCandidate> = synchronized(lock) {
        val sender = SwiftText.canonical(senderName)
        val bound = cache[MessageCacheKey(channelIndex, sender, messageHash, radioId)].orEmpty()
        val unbound = cache[MessageCacheKey(channelIndex, sender, messageHash)].orEmpty()
        (bound + unbound).sortedByDescending { it.indexOrder }.distinctBy { it.messageID }
            .sortedBy { it.indexOrder }.takeLast(maxCandidatesPerKey)
    }

    /** A summary write advances only its exact predecessor, without creating an indexing arrival. */
    fun advanceChannelTarget(
        predecessor: CapturedChannelReactionTarget,
        successor: CapturedChannelReactionTarget,
    ): Boolean {
        require(predecessor.message.radioId == successor.message.radioId)
        require(predecessor.message.id == successor.message.id)
        require(predecessor.message.channelIndex == successor.message.channelIndex)
        synchronized(lock) {
            var advanced = false
            cache = cache.mapValues { (_, candidates) ->
                candidates.map { candidate ->
                    if (candidate.channelTarget?.target === predecessor.target) {
                        advanced = true
                        candidate.copy(channelTarget = successor)
                    } else candidate
                }
            }
            return advanced
        }
    }

    /** Never evict a newer same-key binding while retiring a stale predecessor. */
    fun evictChannelTarget(predecessor: CapturedChannelReactionTarget) {
        synchronized(lock) {
            cache = cache.mapValues { (_, candidates) ->
                candidates.filterNot { it.channelTarget?.target === predecessor.target }
            }.filterValues { it.isNotEmpty() }
            order = order.filter { group -> cache.keys.any { it.copy(radioId = null) == group } }
        }
    }

    private fun indexChannelCandidate(key: MessageCacheKey, candidate: MessageCandidate) {
        val group = key.copy(radioId = null)
        val (newOrder, evicted) = touched(order, group)
        order = newOrder
        val retained = if (evicted == null) cache else cache.filterKeys { it.copy(radioId = null) != evicted }
        val indexed = retained + (key to (retained[key].orEmpty().filter { it.messageID != candidate.messageID } + candidate))
        val selectedIds = indexed.entries.asSequence()
            .filter { it.key.copy(radioId = null) == group }
            .flatMap { it.value.asSequence() }
            .sortedByDescending { it.indexOrder }
            .distinctBy { it.messageID }
            .take(maxCandidatesPerKey)
            .map { it.messageID }
            .toHashSet()
        cache = indexed.mapValues { (candidateKey, candidates) ->
            if (candidateKey.copy(radioId = null) == group) candidates.filter { it.messageID in selectedIds }
            else candidates
        }.filterValues { it.isNotEmpty() }
    }

    /** Indexes a DM message for later lookup. */
    fun indexDM(messageID: UUID, contactID: UUID, text: String, timestamp: UInt) {
        val key = DirectMessageCacheKey(contactID, ReactionWireFormat.generateMessageHash(text, timestamp))
        val candidate = MessageCandidate(messageID, text, timestamp, clock.instant())
        synchronized(lock) {
            val (newOrder, evicted) = touched(dmOrder, key)
            dmOrder = newOrder
            dmCache = withCandidate(evicted?.let { dmCache - it } ?: dmCache, key, candidate)
        }
    }

    /** Candidates for a DM key, oldest first; empty when unknown. */
    fun lookupDM(contactID: UUID, messageHash: String): List<MessageCandidate> =
        synchronized(lock) { dmCache[DirectMessageCacheKey(contactID, messageHash)].orEmpty() }

    /** Clears both caches. */
    fun clear() {
        synchronized(lock) {
            cache = emptyMap()
            order = emptyList()
            dmCache = emptyMap()
            dmOrder = emptyList()
            nextChannelIndexOrder = 0
        }
    }

    /**
     * Moves [key] to the most-recent end, then evicts the oldest key when over capacity — before the
     * candidate is stored, exactly as Swift orders it.
     */
    private fun <K> touched(order: List<K>, key: K): Pair<List<K>, K?> {
        val moved = order.filter { it != key } + key
        return if (moved.size > capacity) moved.drop(1) to moved.first() else moved to null
    }

    /** Replaces a re-indexed message id, appends the candidate, keeps the newest [maxCandidatesPerKey]. */
    private fun <K> withCandidate(cache: Map<K, List<MessageCandidate>>, key: K, candidate: MessageCandidate): Map<K, List<MessageCandidate>> {
        val candidates = cache[key].orEmpty().filter { it.messageID != candidate.messageID } + candidate
        return cache + (key to candidates.takeLast(maxCandidatesPerKey))
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 500
        const val DEFAULT_MAX_CANDIDATES_PER_KEY: Int = 5
    }
}
