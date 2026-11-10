// AndroidOnly: WP-201 Original-occupant history and explicit native display disposition.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.SnapshotMap
import java.time.Instant

enum class ChannelHistoryConflict {
    UNKNOWN_AUTHORITY, STALE_OCCUPANT, UNKNOWN_MESSAGE_ORIGIN, UNBOUND_HISTORY,
    MESSAGE_NOT_FOUND, MESSAGE_DESTINATION_MISMATCH, ORIGIN_MISMATCH,
}

class ChannelHistoryRejected(
    val reason: ChannelHistoryConflict,
    val scope: ChannelHistoryScope? = null,
    val messageKey: EntityKey? = null,
) : Exception(reason.name)

/**
 * Strict history reads reject any unbound row rather than returning silently partial history.
 * Active display reads keep source-compatible unbound incoming rows, without stamping an origin,
 * while unbound outgoing drafts remain exclusively in ChannelDraftRecoveryPersisting.
 * A read scope is not a wire permit; dispatch still needs its independently owned atomic fence.
 */
interface ChannelHistoryPersisting {
    /**
     * Insert caller-stable intent once while the row exists; matching replay returns the current
     * stored row, including producer timestamps and subsequently updated metadata. Absence after
     * explicit deletion is not proof that an earlier invocation never committed.
     */
    suspend fun createOrReconcileChannelMessage(
        creation: ChannelMessageCreation, timestamp: UInt, createdAt: Instant,
    ): MessageDTO
    // Both arguments carry the initiating proof. This never fills null proof from the current slot.
    suspend fun saveChannelMessage(dto: MessageDTO, origin: ChannelHistoryScope)
    suspend fun validateChannelMessageOrigin(message: EntityKey): ChannelHistoryScope
    suspend fun fetchChannelTimeline(origin: ChannelHistoryScope, limit: Long = 50, offset: Long = 0): SnapshotList<MessageDTO>
    suspend fun fetchChannelTimelineWindow(origin: ChannelHistoryScope, anchorSortDate: Instant?, floorLimit: Long): MessageWindow
    suspend fun countUnseenChannelTimelineFailures(origin: ChannelHistoryScope): Long
    suspend fun fetchChannelTimelineMessage(origin: ChannelHistoryScope, message: EntityKey): MessageDTO?
    suspend fun markChannelTimelineFailuresSeen(origin: ChannelHistoryScope)
    suspend fun fetchChannelHistory(origin: ChannelHistoryScope, limit: Long = 50, offset: Long = 0): SnapshotList<MessageDTO>
    suspend fun fetchChannelHistoryWindow(origin: ChannelHistoryScope, anchorSortDate: Instant?, floorLimit: Long): MessageWindow
    suspend fun countChannelHistory(origin: ChannelHistoryScope): Long
    suspend fun fetchLastChannelHistory(
        origins: SnapshotList<ChannelHistoryScope>, limit: Long,
    ): SnapshotMap<ChannelHistoryScope, SnapshotList<MessageDTO>>
    suspend fun countUnseenChannelHistoryFailures(origin: ChannelHistoryScope): Long
    suspend fun fetchChannelHistoryCandidates(
        origin: ChannelHistoryScope, timestampWindow: ClosedRange<UInt>, limit: Long,
    ): SnapshotList<MessageDTO>
    suspend fun findChannelHistoryMessageForReaction(
        origin: ChannelHistoryScope, parsedReaction: ParsedReaction, localNodeName: String?,
        timestampWindow: ClosedRange<UInt>, limit: Long,
    ): MessageDTO?
}
