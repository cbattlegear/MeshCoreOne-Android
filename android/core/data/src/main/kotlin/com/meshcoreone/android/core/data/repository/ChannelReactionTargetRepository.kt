// AndroidOnly: WP-202 Unproven-safe channel ingress and history without an ordered origin issuer.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.MessageEntity
import com.meshcoreone.android.core.database.toDTO
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.*
import java.time.Instant

internal class ChannelReactionTargetRepository(
    private val context: RoomRepositoryContext,
) : ChannelReactionTargetAuthority {
    override suspend fun saveIncomingChannelMessageWithReactionTarget(
        message: MessageDTO,
        occurrence: ChannelReactionOccurrence,
    ): ChannelReactionIngressResult = context.write("saveIncomingChannelMessageWithReactionTarget") {
        if (message.direction != MessageDirection.INCOMING ||
            message.channelIndex == null || message.contactID != null
        ) {
            return@write ChannelReactionIngressResult.Rejected(ChannelReactionRejection.NOT_ELIGIBLE)
        }
        val existing = database.messages().byId(message.radioId.value, message.id) != null
        database.messages().upsert(message.toEntity())
        val actual = checkNotNull(database.messages().byId(message.radioId.value, message.id)).toDTO()
        save()
        if (existing) {
            ChannelReactionIngressResult.Existing(EntityKey(actual.radioId, actual.id))
        } else {
            ChannelReactionIngressResult.InsertedUnproven(actual, unprovenReason(occurrence))
        }
    }

    override suspend fun findChannelReactionTarget(
        occurrence: ChannelReactionOccurrence,
        parsedReaction: ParsedReaction,
        localNodeName: String?,
        timestampWindow: ClosedRange<UInt>,
        limit: Long,
    ): ChannelReactionSelectionResult<CapturedChannelReactionTarget> =
        context.read("findChannelReactionTarget") {
            queryBounds(limit)
            ChannelReactionSelectionResult.Unproven(unprovenReason(occurrence))
        }

    override suspend fun fetchChannelReactionTargets(
        occurrence: ChannelReactionOccurrence,
        timestampWindow: ClosedRange<UInt>,
        limit: Long,
    ): ChannelReactionSelectionResult<SnapshotList<CapturedChannelReactionTarget>> =
        context.read("fetchChannelReactionTargets") {
            queryBounds(limit)
            ChannelReactionSelectionResult.Unproven(unprovenReason(occurrence))
        }

    override suspend fun fetchChannelReactionWindow(
        radioId: RadioId,
        channelIndex: UByte,
        limit: Long,
        offset: Long,
    ): SnapshotList<ChannelReactionHistoryEntry> = context.read("fetchChannelReactionWindow") {
        queryBounds(limit, offset)
        history(database.messages().newestForChannel(radioId.value, channelIndex.toLong(), limit, offset))
    }

    override suspend fun fetchChannelReactionWindow(
        radioId: RadioId,
        channelIndex: UByte,
        anchorSortDate: Instant?,
        floorLimit: Long,
    ): ChannelReactionWindow = context.read("fetchAnchoredChannelReactionWindow") {
        queryBounds(floorLimit)
        val atAnchor = anchorSortDate?.let {
            database.messages().countChannelAtOrAfter(radioId.value, channelIndex.toLong(), it.epochSecond, it.nano)
        } ?: 0
        val limit = maxOf(floorLimit, atAnchor)
        val rows = database.messages().newestForChannel(radioId.value, channelIndex.toLong(), checkedIncrement(limit))
        val hasMore = rows.size.toLong() > limit
        ChannelReactionWindow(history(if (hasMore) rows.dropLast(1) else rows), hasMore)
    }

    override suspend fun applyChannelReaction(
        target: ChannelReactionTarget,
        reaction: ReactionDTO,
        occurrence: ChannelReactionOccurrence,
    ): ChannelReactionApplyResult = context.write("applyChannelReaction") {
        ChannelReactionApplyResult.Unproven(unprovenReason(occurrence))
    }

    // There is no authenticated private Proven implementation or persisted occupant origin yet.
    // Public marker implementations cannot upgrade ingress, history, selection or apply.
    private fun unprovenReason(occurrence: ChannelReactionOccurrence): ChannelReactionUnprovenReason =
        when (occurrence) {
            is ChannelReactionOccurrence.Unproven -> occurrence.reason
            is ChannelReactionOccurrence.Proven -> ChannelReactionUnprovenReason.INVALID_PROOF
        }

    private fun history(rows: List<MessageEntity>): SnapshotList<ChannelReactionHistoryEntry> =
        MessageDTO.reorderSameSenderClusters(rows.asReversed().map { it.toDTO() }).map {
            UnprovenChannelReactionMessage(it, ChannelReactionUnprovenReason.MISSING_ORIGIN)
        }.snapshot()
}
