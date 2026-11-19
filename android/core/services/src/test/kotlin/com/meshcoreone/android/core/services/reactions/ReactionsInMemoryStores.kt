// AndroidOnly: WP-216 in-memory HeardRepeatPersisting/ReactionPersisting fakes standing in for the Swift in-memory PersistenceStore (core:services cannot depend on core:data).
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.HeardRepeatPersisting
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.ReactionPersisting
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageRepeatDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes

/**
 * Failure injection shared by the fakes: an operation named in [failures] throws the mapped exception
 * (a store error, or a `CancellationException` to prove cancellation propagates).
 */
internal open class ReactionsFaultInjection {
    private val lock = Any()
    private var failures: Map<String, () -> Throwable> = emptyMap()
    private var calls: List<String> = emptyList()

    fun failOn(operation: String, error: () -> Throwable = { PersistenceStoreException(PersistenceStoreError.SaveFailed(operation)) }) {
        synchronized(lock) { failures = failures + (operation to error) }
    }

    fun clearFailures() = synchronized(lock) { failures = emptyMap() }

    val recordedCalls: List<String> get() = synchronized(lock) { calls }

    protected fun record(operation: String) {
        val failure = synchronized(lock) {
            calls = calls + operation
            failures[operation]
        }
        if (failure != null) throw failure()
    }
}

/**
 * In-memory [HeardRepeatPersisting] with the Swift `PersistenceStore` / Room repository semantics the service relies
 * on: sent-echo lookup by radio, channel, timestamp, outgoing direction and exact text (newest `createdAt` wins);
 * repeat saves require the parent message; repeats come back ordered by `receivedAt`; increments return the new
 * count (0 for an unknown message); path adoption only fills a null path.
 */
internal class ReactionsInMemoryHeardRepeatStore : ReactionsFaultInjection(), HeardRepeatPersisting {
    private val lock = Any()
    private var messages: Map<EntityKey, MessageDTO> = emptyMap()
    private var repeats: List<Pair<RadioId, MessageRepeatDTO>> = emptyList()

    fun saveMessage(dto: MessageDTO) = synchronized(lock) { messages = messages + (EntityKey(dto.radioId, dto.id) to dto) }

    fun fetchMessage(key: EntityKey): MessageDTO? = synchronized(lock) { messages[key] }

    override suspend fun findSentChannelMessage(radioId: RadioId, channelIndex: UByte, timestamp: UInt, text: String): MessageDTO? {
        record("findSentChannelMessage")
        return synchronized(lock) {
            messages.values.filter {
                it.radioId == radioId && it.channelIndex == channelIndex && it.timestamp == timestamp &&
                    it.direction == MessageDirection.OUTGOING && it.text == text
            }.maxByOrNull { it.createdAt }
        }
    }

    override suspend fun fetchMessage(deduplicationKey: String, radioId: RadioId): MessageDTO? {
        record("fetchMessageByDeduplicationKey")
        return synchronized(lock) { messages.values.firstOrNull { it.radioId == radioId && it.deduplicationKey == deduplicationKey } }
    }

    override suspend fun saveMessageRepeat(radioId: RadioId, dto: MessageRepeatDTO) {
        record("saveMessageRepeat")
        synchronized(lock) {
            if (EntityKey(radioId, dto.messageID) !in messages) throw PersistenceStoreException(PersistenceStoreError.MessageNotFound)
            repeats = repeats + (radioId to dto)
        }
    }

    override suspend fun fetchMessageRepeats(message: EntityKey): SnapshotList<MessageRepeatDTO> {
        record("fetchMessageRepeats")
        return synchronized(lock) {
            repeats.filter { (radio, repeat) -> radio == message.radioId && repeat.messageID == message.id }
                .map { it.second }.sortedBy { it.receivedAt }.snapshot()
        }
    }

    override suspend fun deleteMessageRepeats(message: EntityKey) {
        record("deleteMessageRepeats")
        synchronized(lock) { repeats = repeats.filterNot { (radio, repeat) -> radio == message.radioId && repeat.messageID == message.id } }
    }

    override suspend fun messageRepeatExists(rxLogEntry: EntityKey): Boolean {
        record("messageRepeatExists")
        return synchronized(lock) { repeats.any { (radio, repeat) -> radio == rxLogEntry.radioId && repeat.rxLogEntryID == rxLogEntry.id } }
    }

    override suspend fun incrementMessageHeardRepeats(key: EntityKey): Long {
        record("incrementMessageHeardRepeats")
        return synchronized(lock) {
            val message = messages[key] ?: return@synchronized 0L
            val next = message.heardRepeats + 1
            messages = messages + (key to message.copy(heardRepeats = next))
            next
        }
    }

    override suspend fun adoptIncomingPathIfUnknown(key: EntityKey, pathNodes: Bytes, pathLength: UByte): Boolean {
        record("adoptIncomingPathIfUnknown")
        return synchronized(lock) {
            val message = messages[key] ?: return@synchronized false
            if (message.pathNodes != null) return@synchronized false
            messages = messages + (key to message.copy(pathNodes = pathNodes, pathLength = pathLength))
            true
        }
    }

    override suspend fun incrementMessageSendCount(key: EntityKey): Long {
        record("incrementMessageSendCount")
        return synchronized(lock) {
            val message = messages[key] ?: return@synchronized 0L
            val next = message.sendCount + 1
            messages = messages + (key to message.copy(sendCount = next))
            next
        }
    }
}

/** In-memory [ReactionPersisting]: reactions newest first (Room orders by `receivedAt` descending), summaries by message. */
internal class ReactionsInMemoryReactionStore : ReactionsFaultInjection(), ReactionPersisting {
    private val lock = Any()
    private var reactions: List<ReactionDTO> = emptyList()
    private var summaries: Map<EntityKey, String?> = emptyMap()

    fun summary(message: EntityKey): String? = synchronized(lock) { summaries[message] }

    val hasSummaryUpdate: Boolean get() = synchronized(lock) { summaries.isNotEmpty() }

    override suspend fun fetchCommittedReaction(occurrence: EntityKey) =
        error("This test store does not resolve notification targets")

    override suspend fun fetchReactions(message: EntityKey, limit: Long): SnapshotList<ReactionDTO> {
        record("fetchReactions")
        return synchronized(lock) {
            reactions.filter { it.radioId == message.radioId && it.messageID == message.id }
                .sortedByDescending { it.receivedAt }.take(limit.toInt()).snapshot()
        }
    }

    override suspend fun saveReaction(dto: ReactionDTO) {
        record("saveReaction")
        synchronized(lock) { reactions = reactions.filterNot { it.id == dto.id } + dto }
    }

    override suspend fun reactionExists(message: EntityKey, senderName: String, emoji: String): Boolean {
        record("reactionExists")
        return synchronized(lock) {
            reactions.any { it.radioId == message.radioId && it.messageID == message.id && it.senderName == senderName && it.emoji == emoji }
        }
    }

    override suspend fun updateMessageReactionSummary(message: EntityKey, summary: String?, expectedIncarnation: String?) {
        record("updateMessageReactionSummary")
        synchronized(lock) { summaries = summaries + (message to summary) }
    }

    override suspend fun deleteReactionsForMessage(message: EntityKey) {
        record("deleteReactionsForMessage")
        synchronized(lock) { reactions = reactions.filterNot { it.radioId == message.radioId && it.messageID == message.id } }
    }
}
