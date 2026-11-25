// PortedFrom: MC1/Views/Chats/Reactions/MessageAction.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Reactions/MessageActionAvailability.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Components/MessagePathFormatter.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.actions

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus

sealed interface MessageAction {
    data class React(val emoji: String) : MessageAction
    data object Reply : MessageAction
    data object Copy : MessageAction
    data object Translate : MessageAction
    data object SendAgain : MessageAction
    data object SendDirectMessage : MessageAction
    data object BlockSender : MessageAction
    data object Delete : MessageAction
}

data class MessageActionAvailability(
    val canReply: Boolean,
    val canCopy: Boolean,
    val canSendAgain: Boolean,
    val canBlockSender: Boolean,
    val canSendDirectMessage: Boolean,
    val canShowRepeatDetails: Boolean,
    val canViewPath: Boolean,
    val canDelete: Boolean,
    val showsPathDetail: Boolean,
) {
    companion object {
        fun forMessage(message: MessageDTO): MessageActionAvailability {
            val hasNamedChannelSender =
                message.isChannelMessage && !message.isOutgoing && message.senderNodeName != null
            val canViewPath =
                !message.isOutgoing && message.isFloodRouted && message.pathNodes?.isEmpty == false
            val canShowRepeats = message.isOutgoing && message.heardRepeats > 0
            return MessageActionAvailability(
                canReply = !message.isOutgoing,
                canCopy = true,
                canSendAgain = message.isOutgoing,
                canBlockSender = hasNamedChannelSender,
                canSendDirectMessage = hasNamedChannelSender,
                canShowRepeatDetails = canShowRepeats,
                canViewPath = canViewPath,
                canDelete = true,
                showsPathDetail = canViewPath || canShowRepeats ||
                    (!message.isOutgoing && message.heardRepeats > 0),
            )
        }
    }
}

enum class DeliveryFeedback {
    SENDING,
    SENT,
    DELIVERED,
    FAILED,
    RETRYING;

    companion object {
        fun from(message: MessageDTO): DeliveryFeedback = when (message.status) {
            MessageStatus.PENDING, MessageStatus.SENDING -> SENDING
            MessageStatus.SENT -> if (message.isChannelMessage) SENT else SENDING
            MessageStatus.DELIVERED -> DELIVERED
            MessageStatus.FAILED -> FAILED
            MessageStatus.RETRYING -> RETRYING
        }
    }
}

object MessagePathFormatter {
    const val MAX_NODES: Int = 4

    fun format(message: MessageDTO, direct: String, flood: String): String {
        if (message.isDirectRouted) return direct
        val pathNodes = message.pathNodes
        if (pathNodes?.size == 1 && pathNodes[0].toInt() == 0xFF) return direct
        val nodes = message.pathNodesHex
        if (nodes.isEmpty()) return flood
        if (nodes.size <= MAX_NODES) return nodes.joinToString(",")
        return "${nodes.take(2).joinToString(",")}\u2026${nodes.takeLast(2).joinToString(",")}"
    }
}

data class ReactionSummaryEntry(val emoji: String, val count: Long)

fun parseReactionSummary(summary: String?): List<ReactionSummaryEntry> {
    if (summary.isNullOrEmpty()) return emptyList()
    return summary.split(',').mapNotNull { entry ->
        val separator = entry.indexOf(':')
        if (separator <= 0 || separator != entry.lastIndexOf(':')) return@mapNotNull null
        val count = entry.substring(separator + 1).toLongOrNull() ?: return@mapNotNull null
        ReactionSummaryEntry(entry.substring(0, separator), count)
    }
}

enum class MessageSignalQuality(val qualityLabel: String) {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    FAIR("Fair"),
    POOR("Weak"),
    UNKNOWN("Unknown");

    companion object {
        fun of(snr: Double?): MessageSignalQuality = when {
            snr == null -> UNKNOWN
            snr > 6.0 -> EXCELLENT
            snr > 0.0 -> GOOD
            snr > -6.0 -> FAIR
            else -> POOR
        }
    }
}
