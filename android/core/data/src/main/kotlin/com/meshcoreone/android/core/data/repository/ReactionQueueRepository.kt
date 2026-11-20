// AndroidOnly: WP-214 Conditional, atomic classified-reaction queue transitions.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.ClassifiedReactionEntity
import com.meshcoreone.android.core.database.ReactionQueueStateEntity
import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.snapshot
import java.time.Instant

internal class ReactionQueueRepository(private val context: RoomRepositoryContext) : ReactionQueuePersisting {
    override suspend fun fetchReactionQueue(): ReactionQueueSnapshot = context.read("fetchReactionQueue") {
        val dao = database.classifiedReactionQueue()
        val rows = dao.entries()
        if (rows.size > ReactionQueuePersisting.MAX_RETAINED) {
            throw ReactionQueueCapacityException(ReactionQueuePersisting.MAX_RETAINED)
        }
        val version = dao.state()?.version
        if (version == null && rows.isNotEmpty()) invalidData("Reaction custody has no transition identity")
        ReactionQueueSnapshot(version, rows.map { it.work() }.snapshot())
    }

    override suspend fun replaceReactionQueue(expectedVersion: java.util.UUID?, next: ReactionQueueSnapshot) =
        context.write("replaceReactionQueue") {
            val version = next.version ?: invalidData("Missing reaction queue transition identity")
            if (next.entries.size > ReactionQueuePersisting.MAX_RETAINED) {
                throw ReactionQueueCapacityException(ReactionQueuePersisting.MAX_RETAINED)
            }
            if (next.entries.count { it.queued } > ReactionQueuePersisting.MAX_QUEUED) {
                throw ReactionQueueCapacityException(ReactionQueuePersisting.MAX_QUEUED)
            }
            next.entries.forEach {
                if (it.keyOrder < 0 || it.entryOrder < it.keyOrder ||
                    (it.targetID == null && it.targetIncarnation != null) ||
                    (it.mutation in setOf(ReactionMutationPhase.SAVE, ReactionMutationPhase.SUMMARY) &&
                        (it.targetID == null || it.targetIncarnation == null))
                ) invalidData("Invalid classified reaction custody")
            }
            val rows = next.entries.map { it.entity() }.sortedWith(
                compareBy<ClassifiedReactionEntity> { it.entryOrder }.thenBy { it.radioId.toString() }.thenBy { it.occurrenceID.toString() },
            )
            val dao = database.classifiedReactionQueue()
            val current = dao.state()?.version
            if (current == version) {
                if (dao.entries() != rows) throw ReactionQueueConflictException()
                return@write
            }
            if (current != expectedVersion) throw ReactionQueueConflictException()
            dao.clear()
            dao.insert(rows)
            dao.state(ReactionQueueStateEntity(version = version))
            save()
        }

    private fun ClassifiedReactionWork.entity(): ClassifiedReactionEntity {
        val initial = deliveryContext as? DeliveryContext.InitialSync
        return ClassifiedReactionEntity(
            radioId.value, occurrenceID, parsed.emoji, parsed.targetSender, parsed.messageHash,
            channelIndex.toInt(), senderName, rawText, receivedAt.epochSecond, receivedAt.nano, localNodeName,
            when (deliveryContext) { null -> 0; DeliveryContext.Live -> 1; is DeliveryContext.InitialSync -> 2 },
            initial?.anchor?.epochSecond, initial?.anchor?.nano,
            keyOrder, entryOrder, queued, targetID, targetIncarnation, notificationPending,
            when (mutation) {
                null -> 0
                ReactionMutationPhase.SAVE -> 1
                ReactionMutationPhase.SUMMARY -> 2
                ReactionMutationPhase.BINDING -> 3
            },
        )
    }

    private fun ClassifiedReactionEntity.work(): ClassifiedReactionWork {
        if (channelIndex !in 0..255 || receivedNanos !in 0..999_999_999 ||
            keyOrder < 0 || entryOrder < keyOrder
        ) invalidData("Invalid classified reaction values")
        val delivery = when (deliveryKind) {
            0 -> null
            1 -> DeliveryContext.Live
            2 -> {
                val seconds = anchorSeconds ?: invalidData("Missing initial-sync anchor")
                val nanos = anchorNanos?.takeIf { it in 0..999_999_999 } ?: invalidData("Invalid initial-sync anchor")
                DeliveryContext.InitialSync(Instant.ofEpochSecond(seconds, nanos.toLong()))
            }
            else -> invalidData("Unknown classified reaction delivery context")
        }
        val phase = when (mutation) {
            0 -> null
            1 -> ReactionMutationPhase.SAVE
            2 -> ReactionMutationPhase.SUMMARY
            3 -> ReactionMutationPhase.BINDING
            else -> invalidData("Unknown classified reaction mutation")
        }
        if ((targetID == null && targetIncarnation != null) ||
            (phase in setOf(ReactionMutationPhase.SAVE, ReactionMutationPhase.SUMMARY) &&
                (targetID == null || targetIncarnation == null))
        ) invalidData("Invalid classified reaction binding")
        return ClassifiedReactionWork(
            occurrenceID, RadioId(radioId), ParsedReaction(emoji, targetSender, messageHash), channelIndex.toUByte(),
            senderName, rawText, Instant.ofEpochSecond(receivedSeconds, receivedNanos.toLong()), localNodeName,
            delivery, keyOrder, entryOrder, queued, targetID, targetIncarnation, notificationPending, phase,
        )
    }
}
