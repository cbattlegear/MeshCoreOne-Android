// PortedFrom: MC1Services/Sources/MC1Services/Services/ChatCoordinatorRegistry.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.contracts.domain.MessagePersisting
import com.meshcoreone.android.core.model.ChatConversationID
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Owns [ChatCoordinator]s keyed by [ChatConversationID]; outlives connections, so every consumer of one
 * conversation shares one coordinator. Bounded by an LRU policy (default [DEFAULT_CAPACITY]); evicted
 * coordinators have their in-flight work cancelled. [clear] empties entries and later lookups mint fresh
 * ones. A plain lookup table (not observed); entries are confined behind a lock.
 *
 * [scope] is the main-actor stand-in every coordinator launches on (dispatching, never immediate);
 * [buildDispatcher] runs the pure builder off that scope.
 */
class ChatCoordinatorRegistry(
    private val dataStore: MessagePersisting,
    private val scope: CoroutineScope,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val buildDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val hiddenOutgoingReaction: HiddenOutgoingReactionPredicate = HiddenOutgoingReactionPredicate.SourceWireFormat,
) {
    private val lock = Any()
    private data class Entry(val coordinator: ChatCoordinator, val channelOccupantId: UUID?)
    private var entries: List<Entry> = emptyList()

    init {
        require(capacity >= 0) { "ChatCoordinatorRegistry capacity must be non-negative, was $capacity" }
    }

    /**
     * The coordinator for [id], created on first request. Repeat reads promote the entry to most recently
     * used; creating one past capacity evicts (and cancels) the least recently used.
     */
    fun coordinator(id: ChatConversationID, channelOccupantId: UUID? = null): ChatCoordinator {
        val evicted: List<ChatCoordinator>
        val result = synchronized(lock) {
            val existing = entries.firstOrNull { it.coordinator.conversationID == id && it.channelOccupantId == channelOccupantId }
            if (existing != null) {
                entries = entries.filter { it !== existing } + existing
                return existing.coordinator
            }
            val created = ChatCoordinator(id, dataStore, scope, buildDispatcher, hiddenOutgoingReaction)
            val grown = entries + Entry(created, channelOccupantId)
            val overflow = maxOf(0, grown.size - capacity)
            evicted = grown.take(overflow).map(Entry::coordinator)
            entries = grown.drop(overflow)
            created
        }
        evicted.forEach(ChatCoordinator::cancelInFlight)
        return result
    }

    /**
     * The coordinator already tracked for [id], or null. A pure lookup: neither creates an entry nor
     * promotes LRU order, so a navigation-time prefetch can check warmth without polluting the cache.
     */
    fun existingCoordinator(id: ChatConversationID, channelOccupantId: UUID? = null): ChatCoordinator? =
        synchronized(lock) {
            entries.firstOrNull { it.coordinator.conversationID == id && it.channelOccupantId == channelOccupantId }?.coordinator
        }

    fun hasWarmCoordinator(id: ChatConversationID): Boolean = synchronized(lock) {
        entries.any { it.coordinator.conversationID == id && it.coordinator.renderState.phase == ChatRenderState.LoadPhase.LOADED }
    }

    /** Evicts the coordinator for [id], cancelling its in-flight work. No-op when absent. */
    fun remove(id: ChatConversationID) {
        val removed = synchronized(lock) {
            val matches = entries.filter { it.coordinator.conversationID == id }
            entries = entries - matches.toSet()
            matches
        }
        removed.forEach { it.coordinator.cancelInFlight() }
    }

    /** Cancels in-flight work and drops every entry; the registry stays usable. */
    fun clear() {
        val dropped = synchronized(lock) { entries.also { entries = emptyList() } }
        dropped.forEach { it.coordinator.cancelInFlight() }
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 16
    }
}
