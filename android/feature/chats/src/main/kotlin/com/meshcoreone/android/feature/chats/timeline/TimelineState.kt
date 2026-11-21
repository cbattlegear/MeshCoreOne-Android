// PortedFrom: MC1/Views/Chats/Timeline/ChatTimeline.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/ChatInitialScrollPolicy.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/MessageDayDividerView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/NewMessagesDividerView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.timeline

import com.meshcoreone.android.core.model.MessageDTO
import java.text.Normalizer
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

sealed interface TimelineRow {
    val stableKey: String

    data class DayDivider(val date: LocalDate) : TimelineRow {
        override val stableKey = "day:$date"
    }

    data class UnreadDivider(val beforeMessageId: UUID) : TimelineRow {
        override val stableKey = "unread:$beforeMessageId"
    }

    data class Message(
        val message: MessageDTO,
        val startsGroup: Boolean,
        val endsGroup: Boolean,
    ) : TimelineRow {
        override val stableKey = "message:${message.radioId.canonicalString}:${message.id}"
    }
}

sealed interface InitialTimelineAnchor {
    data object Latest : InitialTimelineAnchor
    data class Message(val messageId: UUID, val targetIncarnation: String? = null) : InitialTimelineAnchor
}

data class TimelineScrollAnchor(val messageId: UUID, val offset: Int)

enum class PassiveTimelineOperation { REFRESH, LOAD_OLDER }

data class IncomingAvatarHandoff(val fromId: UUID, val toId: UUID)

/** Immutable App-owned render values; feature code does not depend on the service implementation. */
interface TimelineRenderItem {
    val id: UUID
}

data class ChatTimelineState(
    /** Always oldest to newest. Reverse layout is a presentation detail. */
    val messages: List<MessageDTO> = emptyList(),
    val rows: List<TimelineRow> = emptyList(),
    val renderItems: List<TimelineRenderItem> = emptyList(),
    val renderItemIndexByID: Map<UUID, Long> = emptyMap(),
    val isLoading: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val hasMoreMessages: Boolean = true,
    val totalFetchedCount: Int = 0,
    val initialAnchor: InitialTimelineAnchor? = null,
    val initialAnchorConsumed: Boolean = false,
    val scrollAnchor: TimelineScrollAnchor? = null,
    val isAtLatest: Boolean = true,
    val newMessageCount: Int = 0,
    val incomingAvatarHandoff: IncomingAvatarHandoff? = null,
    val draft: String = "",
    val retryingMessageIds: Set<UUID> = emptySet(),
    val loadError: Throwable? = null,
    val passiveError: Throwable? = null,
    val passiveErrorOperation: PassiveTimelineOperation = PassiveTimelineOperation.REFRESH,
    val sendError: Throwable? = null,
)

fun buildTimelineRows(
    messages: List<MessageDTO>,
    unreadAnchorId: UUID?,
    zoneId: ZoneId,
): List<TimelineRow> = buildList {
    messages.forEachIndexed { index, message ->
        val previous = messages.getOrNull(index - 1)
        val next = messages.getOrNull(index + 1)
        val date = message.senderDate.atZone(zoneId).toLocalDate()
        if (previous == null || previous.senderDate.atZone(zoneId).toLocalDate() != date) {
            add(TimelineRow.DayDivider(date))
        }
        if (message.id == unreadAnchorId) add(TimelineRow.UnreadDivider(message.id))
        add(
            TimelineRow.Message(
                message = message,
                startsGroup = previous == null || !sameMessageGroup(previous, message, zoneId),
                endsGroup = next == null || !sameMessageGroup(message, next, zoneId),
            ),
        )
    }
}

internal fun incomingAvatarClusterContinues(earlier: MessageDTO, later: MessageDTO?): Boolean {
    if (later == null || !earlier.isChannelMessage || !later.isChannelMessage ||
        earlier.isOutgoing || later.isOutgoing
    ) return false
    val earlierName = earlier.senderNodeName ?: return false
    val laterName = later.senderNodeName ?: return false
    return Duration.between(earlier.senderDate, later.senderDate).abs().seconds <= 300L &&
        Normalizer.normalize(earlierName, Normalizer.Form.NFC) ==
        Normalizer.normalize(laterName, Normalizer.Form.NFC)
}

private fun sameMessageGroup(first: MessageDTO, second: MessageDTO, zoneId: ZoneId): Boolean {
    if (first.direction != second.direction || first.isChannelMessage != second.isChannelMessage) return false
    if (first.isChannelMessage && first.senderNodeName != second.senderNodeName) return false
    if (first.senderDate.atZone(zoneId).toLocalDate() != second.senderDate.atZone(zoneId).toLocalDate()) return false
    return Duration.between(first.senderDate, second.senderDate).abs().seconds <= GROUP_WINDOW_SECONDS
}

private const val GROUP_WINDOW_SECONDS = 300L
