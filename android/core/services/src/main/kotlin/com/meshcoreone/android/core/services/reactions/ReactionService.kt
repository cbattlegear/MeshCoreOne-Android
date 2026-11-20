// PortedFrom: MC1Services/Sources/MC1Services/Services/ReactionService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.contracts.domain.ReactionPersisting
import com.meshcoreone.android.core.contracts.domain.CommittedReactionSummary
import com.meshcoreone.android.core.contracts.domain.ReactionSummaryMutation
import com.meshcoreone.android.core.contracts.domain.ReactionSummaryReceipting
import com.meshcoreone.android.core.contracts.domain.ClassifiedReactionWork
import com.meshcoreone.android.core.contracts.domain.ReactionMutationPhase
import com.meshcoreone.android.core.contracts.domain.ReactionQueueConflictException
import com.meshcoreone.android.core.contracts.domain.ReactionQueuePersisting
import com.meshcoreone.android.core.contracts.domain.ReactionQueueSnapshot
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.services.rendering.MessageCandidate
import com.meshcoreone.android.core.services.sync.SyncChannelReactionTarget
import com.meshcoreone.android.core.services.sync.REACTION_LOOKUP_LIMIT
import com.meshcoreone.android.core.services.sync.reactionTimestampWindow
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
    internal val targetEntity: EntityKey? = null,
    val localNodeName: String? = null,
    val deliveryContext: DeliveryContext? = null,
    internal val occurrenceId: UUID = UUID.randomUUID(),
    val expectedTargetIncarnation: String? = null,
    val verifyMutationOutcomes: Boolean = false,
    internal val uncertainMutation: UncertainReactionMutation? = null,
)

sealed interface UncertainReactionMutation {
    /** A crash during target selection cannot establish which incarnation was selected. */
    data object Binding : UncertainReactionMutation
    data object Save : UncertainReactionMutation
    data object Summary : UncertainReactionMutation
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
    internal val notificationPending: Boolean = false,
    internal val targetEntity: EntityKey? = null,
    internal val occurrenceId: UUID = UUID.randomUUID(),
)

/** Result of persisting a reaction. */
data class ReactionPersistResult(
    val messageID: UUID, val summary: String, val shouldNotify: Boolean = true, val occurrenceId: UUID? = null,
    internal val targetChanged: Boolean = false,
    val radioId: RadioId? = null,
)

sealed interface ReactionReplayResult {
    data class Completed(val persisted: List<ReactionPersistResult>) : ReactionReplayResult
    data class Retained(val persisted: List<ReactionPersistResult>) : ReactionReplayResult
    data object Failed : ReactionReplayResult

    fun completedOrNull(): List<ReactionPersistResult>? = when (this) {
        is Completed -> persisted
        is Retained, Failed -> null
    }
}

/**
 * Shared bounded reaction queues. Legacy misses remain session-owned. Classified native work uses the
 * optional journal, including in-flight phases; a durable removal fences completion and shared eviction.
 */
class PendingReactionCustody(private val journal: ReactionQueuePersisting? = null) {
    private val lock = Any()
    private val channelMutex = Mutex()
    private val logger = Logger.getLogger("PendingReactionCustody")
    private data class ChannelKey(
        val radioId: RadioId,
        val channelIndex: UByte,
        val targetSender: String,
        val messageHash: String,
    )
    private data class DirectKey(val radioId: RadioId, val contactID: UUID, val messageHash: String)
    private data class Owned<out T>(val value: T, val sessionOwner: UUID?)

    private data class ChannelImage(
        val queue: PendingQueue<ChannelKey, Owned<PendingReaction>> = PendingQueue.empty(),
        val inFlight: Map<EntityKey, PendingReaction> = emptyMap(),
        val recovered: Set<EntityKey> = emptySet(),
        val unknownBindings: Set<EntityKey> = emptySet(),
    )
    private var image = ChannelImage()
    private var loaded = false
    private var version: UUID? = null
    private var unconfirmed: Pair<ChannelImage, ReactionQueueSnapshot>? = null
    private var direct = PendingQueue.empty<DirectKey, Owned<PendingDMReaction>>()

    internal val isDurable: Boolean get() = journal != null
    private val PendingReaction.key: EntityKey get() = EntityKey(radioId, occurrenceId)
    private fun PendingReaction.channelKey() = ChannelKey(
        radioId, channelIndex, SwiftText.canonical(parsed.targetSender), parsed.messageHash,
    )

    internal suspend fun queue(pending: PendingReaction, sessionOwner: UUID) =
        enqueue(pending, sessionOwner, admission = true)
    internal fun queue(pending: PendingDMReaction, sessionOwner: UUID) = enqueue(pending, sessionOwner)

    internal suspend fun requeue(pending: PendingReaction) = enqueue(pending, null, admission = false)
    internal fun requeue(pending: PendingDMReaction) = enqueue(pending, null)

    private fun ChannelImage.queued(): List<PendingReaction> =
        queue.takeMatchingEntries({ true }, { true }) { owned, keyOrder, entryOrder ->
            owned.value.copy(queueKeyOrder = keyOrder, queueEntryOrder = entryOrder)
        }.second

    private suspend fun enqueue(pending: PendingReaction, sessionOwner: UUID?, admission: Boolean) {
        val evicted = transition { old ->
            val queued = old.queued()
            val previous = old.inFlight[pending.key] ?: queued.firstOrNull { it.key == pending.key }
            if (!admission && pending.verifyMutationOutcomes && journal != null && previous == null) {
                logger.warning("Completed or evicted native reaction was not re-admitted")
                return@transition old to emptyList<PendingReaction>()
            }
            val value = pending.copy(
                queueKeyOrder = previous?.queueKeyOrder ?: pending.queueKeyOrder,
                queueEntryOrder = previous?.queueEntryOrder ?: pending.queueEntryOrder,
                targetEntity = pending.targetEntity ?: previous?.targetEntity,
                expectedTargetIncarnation = pending.expectedTargetIncarnation ?: previous?.expectedTargetIncarnation,
                uncertainMutation = pending.uncertainMutation ?: previous?.uncertainMutation,
                notificationPending = pending.notificationPending || previous?.notificationPending == true,
            )
            val queue = old.queue.removeMatchingValues { it.value.key == pending.key }.enqueue(
                value.channelKey(),
                Owned(value, sessionOwner.takeUnless { value.verifyMutationOutcomes && journal != null }),
                ReactionService.MAX_PENDING_REACTIONS, value.queueKeyOrder, value.queueEntryOrder,
            )
            val next = old.copy(
                queue = queue, inFlight = old.inFlight - pending.key, recovered = old.recovered - pending.key,
            )
            val remaining = next.queued().mapTo(hashSetOf()) { it.key }
            next to (queued + value).filter { it.key !in remaining }.distinctBy { it.key }
        }
        if (evicted.isNotEmpty()) logger.warning(
            "Channel reaction capacity evicted ${evicted.size} occurrence(s); limit=${ReactionService.MAX_PENDING_REACTIONS}",
        )
    }

    private fun enqueue(pending: PendingDMReaction, sessionOwner: UUID?) = synchronized(lock) {
        val key = DirectKey(pending.radioId, pending.contactID, pending.parsed.messageHash)
        direct = direct.enqueue(
            key, Owned(pending, sessionOwner), ReactionService.MAX_PENDING_REACTIONS,
            pending.queueKeyOrder, pending.queueEntryOrder,
        )
    }

    internal suspend fun takeChannel(
        radioId: RadioId?,
        channelIndex: UByte,
        targetSender: String,
        messageHash: String,
        messageID: UUID,
        sessionOwner: UUID,
        includeProcess: Boolean,
        targetIncarnation: String? = null,
    ): List<PendingReaction> = transition { old ->
        fun matches(pending: PendingReaction): Boolean =
            (radioId == null || pending.radioId == radioId) && pending.channelIndex == channelIndex &&
                if (pending.targetEntity != null) {
                    pending.targetEntity == EntityKey(pending.radioId, messageID)
                } else {
                    SwiftText.canonical(pending.parsed.targetSender) == SwiftText.canonical(targetSender) &&
                        pending.parsed.messageHash == messageHash
                }
        fun bind(pending: PendingReaction): PendingReaction = if (pending.key in old.unknownBindings) pending else pending.copy(
            targetEntity = pending.targetEntity ?: EntityKey(pending.radioId, messageID),
            expectedTargetIncarnation = if (pending.targetEntity == null) {
                pending.expectedTargetIncarnation ?: targetIncarnation
            } else pending.expectedTargetIncarnation,
            uncertainMutation = pending.uncertainMutation.takeUnless { it == UncertainReactionMutation.Binding },
        )
        val (remaining, matched) = old.queue.takeMatchingEntries(
            { key ->
                (radioId == null || key.radioId == radioId) &&
                    key.channelIndex == channelIndex
            },
            { owned ->
                (includeProcess || owned.sessionOwner == sessionOwner) && matches(owned.value)
            },
        ) { owned, keyOrder, entryOrder ->
            bind(owned.value.copy(queueKeyOrder = keyOrder, queueEntryOrder = entryOrder))
        }
        val recovered = if (includeProcess) {
            old.inFlight.values.filter { it.key in old.recovered && matches(it) }
        } else emptyList()
        val all = (matched + recovered).sortedWith(compareBy<PendingReaction> { it.queueKeyOrder }.thenBy { it.queueEntryOrder })
        old.copy(
            queue = remaining,
            inFlight = old.inFlight + all.filter { it.verifyMutationOutcomes }.associateBy { it.key },
            recovered = old.recovered - all.map { it.key }.toSet(),
        ) to all
    }

    internal fun takeDirect(
        radioId: RadioId?,
        contactID: UUID,
        messageHash: String,
        messageID: UUID,
        sessionOwner: UUID,
        includeProcess: Boolean,
    ): List<PendingDMReaction> = synchronized(lock) {
        val (remaining, matched) = direct.takeMatchingEntries(
            { key ->
                (radioId == null || key.radioId == radioId) &&
                    key.contactID == contactID && key.messageHash == messageHash
            },
            { owned ->
                val pending = owned.value
                (includeProcess || owned.sessionOwner == sessionOwner) &&
                    (pending.targetEntity == null || pending.targetEntity == EntityKey(pending.radioId, messageID))
            },
        ) { owned, keyOrder, entryOrder ->
            val pending = owned.value.copy(queueKeyOrder = keyOrder, queueEntryOrder = entryOrder)
            pending.copy(targetEntity = pending.targetEntity ?: EntityKey(pending.radioId, messageID))
        }
        direct = remaining
        matched
    }

    internal suspend fun clearSession(sessionOwner: UUID) {
        transition { old -> old.copy(queue = old.queue.removeMatchingValues { it.sessionOwner == sessionOwner }) to Unit }
        synchronized(lock) { direct = direct.removeMatchingValues { it.sessionOwner == sessionOwner } }
    }

    internal suspend fun takeRetainedNativeChannel(radioId: RadioId): List<PendingReaction> = transition { old ->
        val (remaining, retained) = old.queue.takeMatchingEntries(
            { it.radioId == radioId },
            { it.sessionOwner == null && it.value.verifyMutationOutcomes },
        ) { owned, keyOrder, entryOrder ->
            owned.value.copy(queueKeyOrder = keyOrder, queueEntryOrder = entryOrder)
        }
        val all = (retained + old.inFlight.values.filter { it.radioId == radioId })
            .sortedWith(compareBy<PendingReaction> { it.queueKeyOrder }.thenBy { it.queueEntryOrder })
        old.copy(
            queue = remaining, inFlight = old.inFlight + all.associateBy { it.key },
            recovered = old.recovered - all.map { it.key }.toSet(),
        ) to all
    }

    internal suspend fun acknowledgeNotification(reaction: ReactionDTO) {
        transition { old -> old.copy(queue = old.queue.updateMatchingValues(
            matchesValue = { owned ->
                val pending = owned.value
                pending.notificationPending && pending.radioId == reaction.radioId &&
                    pending.targetEntity == EntityKey(reaction.radioId, reaction.messageID) &&
                    pending.expectedTargetIncarnation == reaction.expectedTargetIncarnation &&
                    pending.channelIndex == reaction.channelIndex && pending.senderNodeName == reaction.senderName &&
                    pending.parsed.emoji == reaction.emoji && pending.parsed.messageHash == reaction.messageHash
            },
            transform = { owned -> owned.copy(value = owned.value.copy(notificationPending = false)) },
        )) to Unit }
        synchronized(lock) { direct = direct.updateMatchingValues(
            matchesValue = { owned ->
                val pending = owned.value
                pending.notificationPending && pending.radioId == reaction.radioId &&
                    pending.targetEntity == EntityKey(reaction.radioId, reaction.messageID) &&
                    pending.contactID == reaction.contactID && pending.senderName == reaction.senderName &&
                    pending.parsed.emoji == reaction.emoji && pending.parsed.messageHash == reaction.messageHash
            },
            transform = { owned -> owned.copy(value = owned.value.copy(notificationPending = false)) },
        ) }
    }

    internal suspend fun checkpoint(pending: PendingReaction): Boolean {
        if (!pending.verifyMutationOutcomes || journal == null) return true
        return transition { old ->
            val prior = old.inFlight[pending.key] ?: return@transition old to false
            check(prior.targetEntity == null || (prior.targetEntity == pending.targetEntity &&
                prior.expectedTargetIncarnation == pending.expectedTargetIncarnation))
            old.copy(inFlight = old.inFlight + (pending.key to pending.copy(
                queueKeyOrder = prior.queueKeyOrder, queueEntryOrder = prior.queueEntryOrder,
            ))) to true
        }
    }

    internal suspend fun finishLookup(pending: PendingReaction) {
        transition { old ->
            old.copy(queue = old.queue.updateMatchingValues(
                { it.value.key == pending.key && it.value.uncertainMutation == UncertainReactionMutation.Binding &&
                    it.value.key !in old.unknownBindings },
                { it.copy(value = it.value.copy(uncertainMutation = null)) },
            )) to Unit
        }
    }

    internal suspend fun complete(pending: PendingReaction) {
        if (!pending.verifyMutationOutcomes) return
        transition { old -> old.copy(
            queue = old.queue.removeMatchingValues { it.value.key == pending.key },
            inFlight = old.inFlight - pending.key, recovered = old.recovered - pending.key,
        ) to Unit }
    }

    private fun ChannelImage.records() = (
        queued().filter { it.verifyMutationOutcomes }.map { it.record(queued = true) } +
            inFlight.values.map { it.record(queued = false) }
        ).sortedWith(compareBy<ClassifiedReactionWork> { it.entryOrder }
            .thenBy { it.radioId.value.toString() }.thenBy { it.occurrenceID.toString() }).snapshot()

    private fun PendingReaction.record(queued: Boolean) = ClassifiedReactionWork(
        occurrenceId, radioId, parsed, channelIndex, senderNodeName, rawText, receivedAt, localNodeName, deliveryContext,
        checkNotNull(queueKeyOrder), checkNotNull(queueEntryOrder), queued, targetEntity?.id,
        expectedTargetIncarnation, notificationPending,
        when (uncertainMutation) {
            null -> null
            UncertainReactionMutation.Save -> ReactionMutationPhase.SAVE
            UncertainReactionMutation.Summary -> ReactionMutationPhase.SUMMARY
            UncertainReactionMutation.Binding -> ReactionMutationPhase.BINDING
        },
    )

    private suspend fun synchronizeJournal() {
        val persistence = journal ?: return
        if (loaded && unconfirmed == null) return
        val snapshot = persistence.fetchReactionQueue()
        val pending = unconfirmed
        if (pending != null) {
            when {
                snapshot == pending.second -> {
                    synchronized(lock) { image = pending.first }
                    version = snapshot.version
                }
                snapshot.version != version || snapshot.entries != image.records() -> throw ReactionQueueConflictException()
            }
            unconfirmed = null
            return
        }
        var queue = PendingQueue.empty<ChannelKey, Owned<PendingReaction>>()
        val inFlight = linkedMapOf<EntityKey, PendingReaction>()
        for (row in snapshot.entries) {
            val value = PendingReaction(
                row.parsed, row.channelIndex, row.senderName, row.rawText, row.radioId, row.receivedAt,
                row.keyOrder, row.entryOrder, row.notificationPending,
                row.targetID?.let { EntityKey(row.radioId, it) }, row.localNodeName, row.deliveryContext,
                row.occurrenceID, row.targetIncarnation, verifyMutationOutcomes = true,
                uncertainMutation = when (row.mutation) {
                    null -> null
                    ReactionMutationPhase.SAVE -> UncertainReactionMutation.Save
                    ReactionMutationPhase.SUMMARY -> UncertainReactionMutation.Summary
                    ReactionMutationPhase.BINDING -> UncertainReactionMutation.Binding
                },
            )
            queue = queue.enqueue(value.channelKey(), Owned(value, null), Int.MAX_VALUE, row.keyOrder, row.entryOrder)
            if (!row.queued) inFlight[value.key] = value
        }
        queue = queue.removeMatchingValues { it.value.key in inFlight }
        val unknownBindings = snapshot.entries.filter { it.mutation == ReactionMutationPhase.BINDING }
            .mapTo(hashSetOf()) { EntityKey(it.radioId, it.occurrenceID) }
        synchronized(lock) { image = ChannelImage(queue, inFlight, inFlight.keys.toSet(), unknownBindings) }
        version = snapshot.version
        loaded = true
    }

    private suspend fun <T> transition(change: (ChannelImage) -> Pair<ChannelImage, T>): T = channelMutex.withLock {
        synchronizeJournal()
        val (next, result) = change(image)
        if (journal != null && next.records() != image.records()) {
            val snapshot = ReactionQueueSnapshot(UUID.randomUUID(), next.records())
            unconfirmed = next to snapshot
            try {
                journal.replaceReactionQueue(version, snapshot)
            } catch (failure: Exception) {
                if (failure !is CancellationException) logger.log(Level.SEVERE, "Durable reaction queue transition failed", failure)
                throw failure
            }
            version = snapshot.version
            unconfirmed = null
        }
        synchronized(lock) { image = next }
        result
    }

    internal val counts: Pair<Int, Int> get() = synchronized(lock) { image.queue.count to direct.count }
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
    suspend fun indexMessage(
        id: UUID,
        channelIndex: UByte,
        senderName: String,
        text: String,
        timestamp: UInt,
        radioId: RadioId? = null,
        includeCustody: Boolean = false,
        targetIncarnation: String? = null,
    ): List<PendingReaction> {
        messageCache.index(id, channelIndex, senderName, text, timestamp, targetIncarnation)
        val hash = ReactionParser.generateMessageHash(text, timestamp)
        return takePendingChannel(id, channelIndex, senderName, hash, radioId, includeCustody, targetIncarnation)
    }

    private suspend fun takePendingChannel(
        id: UUID,
        channelIndex: UByte,
        senderName: String,
        hash: String,
        radioId: RadioId?,
        includeCustody: Boolean,
        targetIncarnation: String? = null,
    ): List<PendingReaction> {
        val retained = if (includeCustody) {
            pendingReactionCustody?.takeChannel(
                radioId, channelIndex, senderName, hash, id, sessionOwner, true, targetIncarnation,
            ).orEmpty()
        } else {
            pendingReactionCustody?.takeChannel(
                radioId, channelIndex, senderName, hash, id, sessionOwner, false, targetIncarnation,
            ).orEmpty()
        }
        val matched = synchronized(lock) {
            val (remaining, taken) = pendingReactions.takeMatchingEntries(
                matchesKey = { it.channelIndex == channelIndex },
                matchesValue = { pending ->
                    (radioId == null || pending.radioId == radioId) &&
                        if (pending.targetEntity != null) {
                            pending.targetEntity == EntityKey(pending.radioId, id)
                        } else {
                            SwiftText.canonical(pending.parsed.targetSender) == SwiftText.canonical(senderName) &&
                                pending.parsed.messageHash == hash
                        }
                },
            ) { value, keyOrder, entryOrder ->
                val pending = value.copy(queueKeyOrder = keyOrder, queueEntryOrder = entryOrder)
                pending.copy(targetEntity = pending.targetEntity ?: EntityKey(pending.radioId, id))
            }
            pendingReactions = remaining
            taken
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
        findChannelTarget(parsed, channelIndex)?.messageID

    fun findChannelTarget(parsed: ParsedReaction, channelIndex: UByte): SyncChannelReactionTarget? =
        messageCache.lookup(channelIndex = channelIndex, senderName = parsed.targetSender, messageHash = parsed.messageHash)
            .mostRecentlyIndexed()?.let { SyncChannelReactionTarget(it.messageID, it.targetIncarnation) }

    /** Attempts to parse incoming text as a channel reaction; null means process it as a regular message. */
    internal fun tryProcessAsReaction(text: String): ParsedReaction? = ReactionParser.parse(text)

    /** Queues a reaction that couldn't find its target message. */
    suspend fun queuePendingReaction(
        parsed: ParsedReaction,
        channelIndex: UByte,
        senderNodeName: String,
        rawText: String,
        radioId: RadioId,
    ) {
        val pending = PendingReaction(parsed, channelIndex, senderNodeName, rawText, radioId, clock.instant())
        queuePendingReaction(pending)
        logger.fine { "Queued pending reaction ${parsed.emoji} for ${parsed.targetSender}" }
    }

    /** Queues a proven target miss without replacing sync's original receive context. */
    suspend fun queuePendingReaction(pending: PendingReaction) {
        val custody = pendingReactionCustody
        if (custody == null) synchronized(lock) {
            val key = PendingReactionKey.of(pending.channelIndex, pending.parsed.targetSender, pending.parsed.messageHash)
            pendingReactions = pendingReactions.removeMatchingValues { it.occurrenceId == pending.occurrenceId }
                .enqueue(key, pending, MAX_PENDING_REACTIONS)
        } else custody.queue(pending, sessionOwner)
    }

    /** Returns an unfinished history replay to the bounded queue without replacing its arrival metadata. */
    suspend fun requeuePendingReaction(pending: PendingReaction) {
        val custody = pendingReactionCustody
        if (custody != null) custody.requeue(pending) else {
            val key = PendingReactionKey.of(pending.channelIndex, pending.parsed.targetSender, pending.parsed.messageHash)
            synchronized(lock) {
                pendingReactions = pendingReactions.removeMatchingValues { it.occurrenceId == pending.occurrenceId }.enqueue(
                    key, pending, MAX_PENDING_REACTIONS, pending.queueKeyOrder, pending.queueEntryOrder,
                )
            }
        }
    }

    suspend fun finishIncomingChannelLookup(pending: PendingReaction) {
        val custody = pendingReactionCustody
        if (custody != null) custody.finishLookup(pending) else synchronized(lock) {
            pendingReactions = pendingReactions.updateMatchingValues(
                { it.occurrenceId == pending.occurrenceId },
                { it.copy(uncertainMutation = null) },
            )
        }
    }

    /** Clears legacy session misses, preserving native durable and process-retained work. */
    suspend fun clearPendingReactions() {
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

    /** One bounded pass over durable classified and process-retained native channel work for this radio. */
    suspend fun recoverRetainedChannelReactions(
        radioId: RadioId,
        dataStore: PersistenceStoreProtocol,
        onPersisted: suspend (ReactionPersistResult) -> Unit,
    ): ReactionReplayResult = persistenceMutex.withLock {
        val retained = pendingReactionCustody?.takeRetainedNativeChannel(radioId).orEmpty().toMutableList()
        val matched = ArrayList<PendingReaction>(retained.size)
        val deferred = ArrayList<PendingReaction>()
        val blockedLookups = mutableSetOf<PendingReactionKey>()
        var lookupFailed = false
        var transferred = false
        try {
            for (index in retained.indices) {
                val pending = retained[index]
                val key = PendingReactionKey.of(pending.channelIndex, pending.parsed.targetSender, pending.parsed.messageHash)
                if (key in blockedLookups) {
                    deferred += pending
                    continue
                }
                if (pending.uncertainMutation == UncertainReactionMutation.Binding) {
                    deferred += pending
                    blockedLookups += key
                    logger.warning("Interrupted target selection retained without rebinding")
                    continue
                }
                if (pending.targetEntity != null) {
                    // A previously selected target must never adopt a replacement's incarnation.
                    if (pending.expectedTargetIncarnation == null) {
                        deferred += pending
                        blockedLookups += key
                    } else matched += pending
                    continue
                }
                val lookingUp = pending.copy(uncertainMutation = UncertainReactionMutation.Binding)
                pendingReactionCustody?.checkpoint(lookingUp)
                retained[index] = lookingUp
                val target = try {
                    dataStore.findChannelMessageForReaction(
                        radioId, pending.channelIndex, pending.parsed, pending.localNodeName?.ifEmpty { null },
                        reactionTimestampWindow(pending.receivedAt), REACTION_LOOKUP_LIMIT,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    lookupFailed = true
                    logger.log(Level.SEVERE, "Retained reaction target lookup failed: ${failure.message}", failure)
                    null
                }
                if (target?.channelReactionIncarnation == null) {
                    pendingReactionCustody?.checkpoint(pending)
                    retained[index] = pending
                    deferred += pending
                    blockedLookups += key
                } else {
                    check(target.radioId == radioId)
                    val bound = pending.copy(
                        targetEntity = EntityKey(radioId, target.id),
                        expectedTargetIncarnation = target.channelReactionIncarnation,
                    )
                    retained[index] = bound
                    pendingReactionCustody?.checkpoint(bound)
                    matched += bound
                }
            }
            deferred.forEach { requeuePendingReaction(it) }
            transferred = true
            val result = persistMatched(
                matched, dataStore, ::requeuePendingReaction,
                { it.notificationPending }, { it.copy(notificationPending = true) }, onPersisted,
                uncertainMutation = { it.uncertainMutation },
                recordMutation = { pending, mutation -> pending.copy(uncertainMutation = mutation) },
                summaryOperationID = { it.occurrenceId },
                checkpoint = { pendingReactionCustody?.checkpoint(it) ?: true },
                complete = { pendingReactionCustody?.complete(it) },
                keepSummaryPhase = pendingReactionCustody?.isDurable == true,
            ) { pending ->
                ReactionDTO(
                    id = pending.occurrenceId, messageID = checkNotNull(pending.targetEntity).id,
                    emoji = pending.parsed.emoji, senderName = pending.senderNodeName,
                    messageHash = pending.parsed.messageHash, rawText = pending.rawText,
                    receivedAt = pending.receivedAt, channelIndex = pending.channelIndex, radioId = pending.radioId,
                    expectedTargetIncarnation = pending.expectedTargetIncarnation,
                )
            }
            when {
                lookupFailed -> ReactionReplayResult.Failed
                deferred.isNotEmpty() && result is ReactionReplayResult.Completed -> {
                    logger.warning("Retained channel reactions still lack a proven target")
                    ReactionReplayResult.Retained(result.persisted)
                }
                else -> result
            }
        } finally {
            if (!transferred) withContext(NonCancellable) { retained.forEach { requeuePendingReaction(it) } }
        }
    }

    /** Takes an admitted native occurrence, or legacy pending work, against the originally selected target. */
    suspend fun persistIncomingChannelReaction(
        pending: PendingReaction,
        target: EntityKey,
        dataStore: ReactionPersisting,
        onPersisted: (ReactionPersistResult) -> Unit,
    ): ReactionPersistResult? {
        require(pending.radioId == target.radioId)
        val matched = pending.copy(targetEntity = target)
        var acquired = false
        try {
            return persistenceMutex.withLock {
                acquired = true
                val retained = takePendingChannel(
                    target.id, pending.channelIndex, pending.parsed.targetSender, pending.parsed.messageHash,
                    target.radioId, includeCustody = true,
                    targetIncarnation = matched.expectedTargetIncarnation,
                )
                persistMatched(
                    retained.map {
                        if (it.expectedTargetIncarnation != null || it.uncertainMutation == UncertainReactionMutation.Binding) it
                        else it.copy(expectedTargetIncarnation = matched.expectedTargetIncarnation)
                    } + listOfNotNull(matched.takeUnless { it.verifyMutationOutcomes }),
                    dataStore, ::requeuePendingReaction,
                    { it.notificationPending }, { it.copy(notificationPending = true) }, onPersisted,
                    reportTargetChanged = true,
                    focusIncarnation = matched.expectedTargetIncarnation,
                    uncertainMutation = { it.uncertainMutation },
                    recordMutation = { pending, mutation ->
                        if (pending.verifyMutationOutcomes) pending.copy(uncertainMutation = mutation) else pending
                    },
                    summaryOperationID = { pending -> pending.occurrenceId.takeIf { pending.verifyMutationOutcomes } },
                    checkpoint = { pendingReactionCustody?.checkpoint(it) ?: true },
                    complete = { pendingReactionCustody?.complete(it) },
                    keepSummaryPhase = pendingReactionCustody?.isDurable == true,
                ) { reaction ->
                    ReactionDTO(
                        id = reaction.occurrenceId,
                        messageID = target.id, emoji = reaction.parsed.emoji, senderName = reaction.senderNodeName,
                        messageHash = reaction.parsed.messageHash, rawText = reaction.rawText,
                        receivedAt = reaction.receivedAt, channelIndex = reaction.channelIndex, radioId = reaction.radioId,
                        expectedTargetIncarnation = reaction.expectedTargetIncarnation,
                    )
                }.completedOrNull()?.lastOrNull()
            }
        } catch (cancelled: CancellationException) {
            if (!acquired) withContext(NonCancellable) { requeuePendingReaction(matched) }
            throw cancelled
        }
    }

    /**
     * Persists a reaction unless the store already has its sender/emoji, then rebuilds the summary from the
     * newest 100 reactions (the store's default fetch limit). Store failures are logged and yield null;
     * cancellation propagates.
     */
    suspend fun persistReactionAndUpdateSummary(
        reaction: ReactionDTO,
        dataStore: ReactionPersisting,
        onPersisted: ((ReactionPersistResult) -> Unit)? = null,
    ): ReactionPersistResult? = persistenceMutex.withLock {
        val result = persistReactionAndUpdateSummaryLocked(reaction, dataStore)
        if (result?.shouldNotify == true && onPersisted != null) {
            onPersisted(result)
            acknowledgePendingNotification(reaction)
        }
        result
    }

    private suspend fun acknowledgePendingNotification(reaction: ReactionDTO) {
        synchronized(lock) {
            pendingReactions = pendingReactions.updateMatchingValues(
                matchesValue = { pending ->
                    pending.notificationPending && pending.radioId == reaction.radioId &&
                        pending.targetEntity == EntityKey(reaction.radioId, reaction.messageID) &&
                        pending.expectedTargetIncarnation == reaction.expectedTargetIncarnation &&
                        pending.channelIndex == reaction.channelIndex && pending.senderNodeName == reaction.senderName &&
                        pending.parsed.emoji == reaction.emoji && pending.parsed.messageHash == reaction.messageHash
                },
                transform = { it.copy(notificationPending = false) },
            )
            pendingDMReactions = pendingDMReactions.updateMatchingValues(
                matchesValue = { pending ->
                    pending.notificationPending && pending.radioId == reaction.radioId &&
                        pending.targetEntity == EntityKey(reaction.radioId, reaction.messageID) &&
                        pending.contactID == reaction.contactID && pending.senderName == reaction.senderName &&
                        pending.parsed.emoji == reaction.emoji && pending.parsed.messageHash == reaction.messageHash
                },
                transform = { it.copy(notificationPending = false) },
            )
        }
        pendingReactionCustody?.acknowledgeNotification(reaction)
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
        targetIncarnation: String? = null,
        onPersisted: suspend (ReactionPersistResult) -> Unit = {},
    ): List<ReactionPersistResult>? = replayIndexedChannelMessage(
        id, radioId, channelIndex, senderName, text, timestamp, dataStore, targetIncarnation, onPersisted,
    ).completedOrNull()

    /** Retained unknown outcomes do not prevent displaying already stored messages. */
    suspend fun replayIndexedChannelMessage(
        id: UUID,
        radioId: RadioId,
        channelIndex: UByte,
        senderName: String,
        text: String,
        timestamp: UInt,
        dataStore: ReactionPersisting,
        targetIncarnation: String? = null,
        onPersisted: suspend (ReactionPersistResult) -> Unit = {},
    ): ReactionReplayResult = persistenceMutex.withLock {
        val matches = indexMessage(
            id, channelIndex, senderName, text, timestamp, radioId, includeCustody = true,
            targetIncarnation = targetIncarnation,
        ).map {
            if (it.expectedTargetIncarnation != null || it.uncertainMutation == UncertainReactionMutation.Binding) it
            else it.copy(expectedTargetIncarnation = targetIncarnation)
        }
        if (matches.any { it.radioId != radioId }) {
            matches.forEach { requeuePendingReaction(it) }
            throw IllegalStateException("Pending channel reactions belong to another radio")
        }
        persistMatched(
            matches,
            dataStore,
            ::requeuePendingReaction,
            { it.notificationPending },
            { it.copy(notificationPending = true) },
            onPersisted,
            focusIncarnation = targetIncarnation,
            uncertainMutation = { it.uncertainMutation },
            recordMutation = { pending, mutation ->
                if (pending.verifyMutationOutcomes) pending.copy(uncertainMutation = mutation) else pending
            },
            summaryOperationID = { pending -> pending.occurrenceId.takeIf { pending.verifyMutationOutcomes } },
            checkpoint = { pendingReactionCustody?.checkpoint(it) ?: true },
            complete = { pendingReactionCustody?.complete(it) },
            keepSummaryPhase = pendingReactionCustody?.isDurable == true,
        ) { pending ->
            ReactionDTO(
                id = pending.occurrenceId,
                messageID = id,
                emoji = pending.parsed.emoji,
                senderName = pending.senderNodeName,
                messageHash = pending.parsed.messageHash,
                rawText = pending.rawText,
                receivedAt = pending.receivedAt,
                channelIndex = pending.channelIndex,
                radioId = pending.radioId,
                expectedTargetIncarnation = pending.expectedTargetIncarnation,
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
                id = pending.occurrenceId,
                messageID = id,
                emoji = pending.parsed.emoji,
                senderName = pending.senderName,
                messageHash = pending.parsed.messageHash,
                rawText = pending.rawText,
                receivedAt = pending.receivedAt,
                contactID = pending.contactID,
                radioId = pending.radioId,
            )
        }.completedOrNull()
    }

    private suspend fun persistReactionAndUpdateSummaryLocked(
        reaction: ReactionDTO,
        dataStore: ReactionPersisting,
        onSaveAttempt: suspend () -> Unit = {},
        onSaveCompleted: suspend () -> Unit = {},
        onSummaryAttempt: suspend () -> Unit = {},
        onSummaryCompleted: suspend () -> Unit = {},
        summaryOperationID: UUID? = null,
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
                onSaveCompleted()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is PersistenceStoreException && error.error == PersistenceStoreError.ReactionTargetChanged) {
                return ReactionPersistResult(message.id, "", shouldNotify = false, targetChanged = true)
            }
            logger.log(Level.SEVERE, "Failed to save reaction for message ${reaction.messageID}: ${error.message}", error)
            return null
        }
        val result = reconcileSummaryLocked(
            message, dataStore, reaction.expectedTargetIncarnation, onSummaryAttempt, onSummaryCompleted,
            summaryOperationID,
        )
        return result?.takeIf { it.targetChanged } ?: result?.copy(
            shouldNotify = !exists, occurrenceId = reaction.id, radioId = reaction.radioId,
        )
    }

    private suspend fun <T> persistMatched(
        matches: List<T>,
        dataStore: ReactionPersisting,
        requeue: suspend (T) -> Unit,
        isNotificationPending: (T) -> Boolean,
        markNotificationPending: (T) -> T,
        onPersisted: suspend (ReactionPersistResult) -> Unit,
        reportTargetChanged: Boolean = false,
        focusIncarnation: String? = null,
        uncertainMutation: (T) -> UncertainReactionMutation? = { null },
        recordMutation: (T, UncertainReactionMutation?) -> T = { value, _ -> value },
        summaryOperationID: (T) -> UUID? = { null },
        checkpoint: suspend (T) -> Boolean = { true },
        complete: suspend (T) -> Unit = {},
        keepSummaryPhase: Boolean = false,
        toDTO: (T) -> ReactionDTO,
    ): ReactionReplayResult {
        val results = ArrayList<ReactionPersistResult>(matches.size)
        val blockedIncarnations = mutableSetOf<String>()
        var blockedWithoutIncarnation = false
        var incomplete = false
        var failed = false
        var next = 0
        var retryCurrentNotification = false
        var currentMutation: UncertainReactionMutation? = null
        try {
            while (next < matches.size) {
                val pending = matches[next]
                if (!checkpoint(pending)) {
                    logger.warning("Reaction left durable custody before persistence")
                    next++
                    continue
                }
                retryCurrentNotification = isNotificationPending(pending)
                currentMutation = uncertainMutation(pending)
                if (currentMutation == UncertainReactionMutation.Binding) {
                    requeue(pending)
                    blockedWithoutIncarnation = true
                    incomplete = true
                    next++
                    continue
                }
                suspend fun checkpointMutation() {
                    val value = recordMutation(pending, currentMutation)
                    check(checkpoint(if (retryCurrentNotification) markNotificationPending(value) else value)) {
                        "Reaction left durable custody before mutation"
                    }
                }
                var retainedOnly = currentMutation != null
                val reaction = toDTO(pending)
                val operationID = summaryOperationID(pending)
                val incarnation = reaction.expectedTargetIncarnation
                if (incomplete && (blockedWithoutIncarnation || incarnation == null || incarnation in blockedIncarnations)) {
                    requeue(pending)
                    next++
                    continue
                }
                var receiptReadFailed = false
                val receipt = if (operationID != null && currentMutation != UncertainReactionMutation.Save) {
                    try {
                        (dataStore as? ReactionSummaryReceipting)?.fetchCommittedReactionSummary(
                            EntityKey(reaction.radioId, operationID),
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        logger.log(Level.SEVERE, "Failed to verify reaction summary $operationID: ${error.message}", error)
                        receiptReadFailed = true
                        retainedOnly = false
                        null
                    }
                } else null
                if (receipt != null && !receipt.matches(reaction, operationID)) {
                    logger.severe("Reaction summary receipt does not match the retained occurrence")
                    receiptReadFailed = true
                    retainedOnly = false
                }
                // Equal values and absent receipts never identify which summary update committed.
                val result = if (receiptReadFailed) {
                    null
                } else if (receipt != null) {
                    currentMutation = if (keepSummaryPhase) UncertainReactionMutation.Summary else null
                    ReactionPersistResult(
                        reaction.messageID, receipt.target.reactionSummary.orEmpty(),
                        shouldNotify = retryCurrentNotification, occurrenceId = operationID, radioId = reaction.radioId,
                    )
                } else if (currentMutation == UncertainReactionMutation.Summary) {
                    null
                } else if (currentMutation == UncertainReactionMutation.Save) {
                    val committed = try {
                        dataStore.fetchCommittedReaction(EntityKey(reaction.radioId, reaction.id))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        logger.log(Level.SEVERE, "Failed to verify reaction ${reaction.id}: ${error.message}", error)
                        retainedOnly = false
                        null
                    }
                    val expectedIncarnation = reaction.expectedTargetIncarnation
                    if (committed == null || expectedIncarnation == null ||
                        committed.targetIncarnation != expectedIncarnation ||
                        committed.reaction != reaction.copy(expectedTargetIncarnation = null)
                    ) {
                        null
                    } else {
                        currentMutation = null
                        retryCurrentNotification = true
                        reconcileSummaryLocked(
                            EntityKey(reaction.radioId, reaction.messageID), dataStore, expectedIncarnation,
                            {
                                retainedOnly = false
                                currentMutation = UncertainReactionMutation.Summary
                                checkpointMutation()
                            },
                            { if (!keepSummaryPhase) currentMutation = null },
                            operationID,
                        )?.let {
                            if (it.targetChanged) it
                            else it.copy(shouldNotify = true, occurrenceId = reaction.id, radioId = reaction.radioId)
                        }
                    }
                } else {
                    persistReactionAndUpdateSummaryLocked(
                        reaction, dataStore,
                        onSaveAttempt = {
                            retryCurrentNotification = true
                            currentMutation = UncertainReactionMutation.Save
                            checkpointMutation()
                        },
                        onSaveCompleted = { currentMutation = null; checkpointMutation() },
                        onSummaryAttempt = { currentMutation = UncertainReactionMutation.Summary; checkpointMutation() },
                        onSummaryCompleted = { if (!keepSummaryPhase) currentMutation = null },
                        summaryOperationID = operationID,
                    )
                }
                if (result == null) {
                    val retained = recordMutation(pending, currentMutation)
                    requeue(if (retryCurrentNotification) markNotificationPending(retained) else retained)
                    if (uncertainMutation(retained) != null) {
                        if (!retainedOnly) failed = true
                        if (incarnation == null) blockedWithoutIncarnation = true else blockedIncarnations += incarnation
                        incomplete = true
                        next++
                        continue
                    }
                    matches.drop(next + 1).forEach { requeue(it) }
                    return ReactionReplayResult.Failed
                }
                if (result.targetChanged) {
                    complete(pending)
                    if (reportTargetChanged) results += result
                    next++
                    retryCurrentNotification = false
                    continue
                }
                val completed = result.copy(shouldNotify = result.shouldNotify || retryCurrentNotification)
                results += completed
                retryCurrentNotification = completed.shouldNotify
                onPersisted(completed)
                complete(pending)
                next++
                retryCurrentNotification = false
            }
        } catch (cancelled: CancellationException) {
            if (next < matches.size) {
                val pending = recordMutation(matches[next], currentMutation)
                withContext(NonCancellable) {
                    requeue(if (retryCurrentNotification) markNotificationPending(pending) else pending)
                    matches.drop(next + 1).forEach { requeue(it) }
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            if (next < matches.size) {
                val pending = recordMutation(matches[next], currentMutation)
                withContext(NonCancellable) {
                    requeue(if (retryCurrentNotification) markNotificationPending(pending) else pending)
                    matches.drop(next + 1).forEach { requeue(it) }
                }
            }
            throw failure
        }
        if (failed) return ReactionReplayResult.Failed
        if (incomplete) {
            if (blockedWithoutIncarnation || focusIncarnation == null || focusIncarnation in blockedIncarnations) {
                logger.warning("Unverified reaction mutation retained without retry or acknowledgement")
                return ReactionReplayResult.Retained(results)
            }
            logger.warning("Unverified reaction mutation retained for a different target incarnation")
        }
        return ReactionReplayResult.Completed(results)
    }

    private fun CommittedReactionSummary.matches(reaction: ReactionDTO, operationID: UUID?): Boolean =
        operationID != null && mutation.operation == EntityKey(reaction.radioId, operationID) &&
            mutation.messageID == reaction.messageID && mutation.targetIncarnation == reaction.expectedTargetIncarnation &&
            target.radioId == reaction.radioId && target.id == reaction.messageID &&
            target.channelIndex == reaction.channelIndex

    /** Rebuilds a committed reaction's summary without saving or replacing the reaction row again. */
    suspend fun reconcileSummary(message: EntityKey, dataStore: ReactionPersisting): ReactionPersistResult? =
        persistenceMutex.withLock { reconcileSummaryLocked(message, dataStore) }

    private suspend fun reconcileSummaryLocked(
        message: EntityKey, dataStore: ReactionPersisting, expectedIncarnation: String? = null,
        onSummaryAttempt: suspend () -> Unit = {},
        onSummaryCompleted: suspend () -> Unit = {},
        operationID: UUID? = null,
    ): ReactionPersistResult? {
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
            onSummaryAttempt()
            val receipts = dataStore as? ReactionSummaryReceipting
            if (receipts != null && operationID != null && expectedIncarnation != null) {
                receipts.updateReactionSummary(ReactionSummaryMutation(
                    EntityKey(message.radioId, operationID), message.id, expectedIncarnation, summary,
                ))
            } else {
                dataStore.updateMessageReactionSummary(message, summary, expectedIncarnation)
            }
            onSummaryCompleted()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is PersistenceStoreException && error.error == PersistenceStoreError.ReactionTargetChanged) {
                return ReactionPersistResult(message.id, "", shouldNotify = false, targetChanged = true)
            }
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
            pendingReactionCustody?.takeDirect(radioId, contactID, hash, id, sessionOwner, true).orEmpty()
        } else {
            pendingReactionCustody?.takeDirect(radioId, contactID, hash, id, sessionOwner, false).orEmpty()
        }
        val matched = synchronized(lock) {
            val (remaining, taken) = pendingDMReactions.takeMatchingEntries(
                matchesKey = { it == key },
                matchesValue = { pending ->
                    pending.targetEntity == null || pending.targetEntity == EntityKey(pending.radioId, id)
                },
            ) { value, keyOrder, entryOrder ->
                val pending = value.copy(queueKeyOrder = keyOrder, queueEntryOrder = entryOrder)
                pending.copy(targetEntity = pending.targetEntity ?: EntityKey(pending.radioId, id))
            }
            pendingDMReactions = remaining
            taken
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

    fun updateMatchingValues(matchesValue: (V) -> Boolean, transform: (V) -> V): PendingQueue<K, V> {
        var nextEntries = entries
        for ((key, values) in entries) {
            val updated = values.map { entry ->
                if (matchesValue(entry.value)) entry.copy(value = transform(entry.value)) else entry
            }
            if (updated != values) nextEntries = nextEntries + (key to updated)
        }
        return PendingQueue(nextEntries, keyOrders, nextOrder)
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
