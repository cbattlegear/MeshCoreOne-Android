// PortedFrom: MC1Services/Sources/MC1Services/Services/MessageStatusEvent.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/MessageService+SendDM.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/MessageService+SendChannel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/ChatSendQueueService.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: WP-208 narrow injected consumer roles; graph assembly remains WP-303.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.ChannelMessageEnvelope
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DirectMessageEnvelope
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TextType
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.util.UUID

sealed interface MessageStatusEvent {
    data class StatusResolved(val messageID: UUID, val status: MessageStatus, val roundTripTime: UInt?) : MessageStatusEvent
    data class Resent(val messageID: UUID) : MessageStatusEvent
    data class Retrying(val messageID: UUID, val attempt: Long, val maxAttempts: Long) : MessageStatusEvent
    data class RoutingChanged(val contactID: UUID, val isFlood: Boolean) : MessageStatusEvent
    data class Failed(val messageID: UUID) : MessageStatusEvent
}

data class ChannelSendReceipt(val id: UUID, val timestamp: UInt)

interface MessagingSendPort {
    val token: SessionToken
    fun statusEvents(): SessionEventSubscription<MessageStatusEvent>
    suspend fun createPendingMessage(
        text: String, contact: ContactDTO, textType: TextType = TextType.PLAIN, replyToID: UUID? = null,
    ): MessageDTO
    suspend fun sendDirectMessage(
        text: String, contact: ContactDTO, textType: TextType = TextType.PLAIN, replyToID: UUID? = null,
    ): MessageDTO
    suspend fun sendMessageWithRetry(
        text: String, contact: ContactDTO, textType: TextType = TextType.PLAIN, replyToID: UUID? = null,
        timeout: Double = 0.0, onMessageCreated: (suspend (MessageDTO) -> Unit)? = null,
    ): MessageDTO
    suspend fun sendPendingDirectMessage(messageID: UUID, contact: ContactDTO, preserveTimestamp: Boolean = false): MessageDTO
    suspend fun resendDirectMessage(messageID: UUID, contact: ContactDTO, preserveTimestamp: Boolean = false): MessageDTO
    suspend fun createPendingChannelMessage(
        text: String, channelIndex: UByte, radioId: RadioId, textType: TextType = TextType.PLAIN,
    ): MessageDTO
    suspend fun sendChannelMessage(
        text: String, channelIndex: UByte, radioId: RadioId, textType: TextType = TextType.PLAIN,
    ): ChannelSendReceipt
    suspend fun sendPendingChannelMessage(messageID: UUID)
    suspend fun resendChannelMessage(messageID: UUID, preserveTimestamp: Boolean = false): UInt
}

interface ChatSendQueuePort {
    val token: SessionToken
    suspend fun enqueueDM(envelope: DirectMessageEnvelope)
    suspend fun enqueueChannel(envelope: ChannelMessageEnvelope)
    suspend fun signalDMEnqueued(envelope: DirectMessageEnvelope)
}

fun interface MessagingChannelQuery {
    suspend fun fetchChannel(index: UByte): ChannelInfo?
}

fun interface OutgoingChannelReactionIndexer {
    suspend fun indexMessage(id: UUID, channelIndex: UByte, senderName: String, text: String, timestamp: UInt)
}

sealed interface MessagingDiagnostic {
    data class Failure(val token: SessionToken, val operation: String, val cause: Throwable) : MessagingDiagnostic
    data class StaleResult(val token: SessionToken, val operation: String) : MessagingDiagnostic
    data class AckCodeMismatch(val token: SessionToken, val messageID: UUID, val attempt: UByte) : MessagingDiagnostic
    data class AckCodeCollision(val token: SessionToken, val messageID: UUID, val otherMessageID: UUID) : MessagingDiagnostic
    data class UnmatchedAcknowledgement(val token: SessionToken, val orphanSentRow: Boolean) : MessagingDiagnostic
}

fun interface MessagingIssueReporter {
    fun report(diagnostic: MessagingDiagnostic)
}
