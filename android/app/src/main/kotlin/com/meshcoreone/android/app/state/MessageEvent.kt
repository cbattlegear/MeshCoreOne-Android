// PortedFrom: MC1/State/MessageEvent.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.state

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RoomMessageDTO
import java.util.UUID

/**
 * Events emitted by mesh subsystems and consumed by chat / room views via [MessageEventStream]. Each case is
 * sourced from a concrete service event stream consumed by [MessageEventDispatcher]; consumers should switch
 * exhaustively (no `else`) so a new case becomes a compile error rather than a silent skip.
 */
sealed interface MessageEvent {
    data class DirectMessageReceived(val message: MessageDTO, val contact: ContactDTO) : MessageEvent
    data class ChannelMessageReceived(val message: MessageDTO, val channelIndex: UByte) : MessageEvent
    data class ChannelHistoryDeleted(val radioId: RadioId) : MessageEvent
    data class RoomMessageReceived(val message: RoomMessageDTO, val sessionID: UUID) : MessageEvent

    /**
     * A message's status resolved to sent or delivered for an original (non-resend) send. [roundTripTime] is
     * supplied only when firmware reports it; the dispatcher writes the DB row before firing.
     */
    data class MessageStatusResolved(
        val messageID: UUID,
        val status: MessageStatus,
        val roundTripTime: UInt? = null,
    ) : MessageEvent

    /** Fired after a channel-message resend completes; consumers must re-fetch every affected field. */
    data class MessageResent(val messageID: UUID) : MessageEvent
    data class MessageFailed(val messageID: UUID) : MessageEvent
    data class MessageRetrying(val messageID: UUID, val attempt: Long, val maxAttempts: Long) : MessageEvent
    data class HeardRepeatRecorded(val messageID: UUID, val count: Long) : MessageEvent
    data class ReactionReceived(val messageID: UUID, val summary: String) : MessageEvent

    /** Region reprocess rewrote dual region fields on these Message rows. */
    data class MessagesRegionUpdated(val messageIDs: List<UUID>) : MessageEvent
    data class RoutingChanged(val contactID: UUID, val isFlood: Boolean) : MessageEvent
    data class RoomMessageStatusUpdated(val messageID: UUID) : MessageEvent
    data class RoomMessageFailed(val messageID: UUID) : MessageEvent
}
