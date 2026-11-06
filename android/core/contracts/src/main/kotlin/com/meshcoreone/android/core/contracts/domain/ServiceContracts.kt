// PortedFrom: MC1Services/Sources/MC1Services/Protocols/AppStateProvider.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/ChannelServiceProtocol.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/ContactServiceProtocol.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/MessagePollingServiceProtocol.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/ChannelService.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/ContactService.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/ServiceContainer.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: WP-214 Additive nonactivated receive envelopes; existing polling consumers are unchanged.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.event.ChannelMessage
import com.meshcoreone.android.core.protocol.event.ContactMessage
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

interface AppStateProvider { suspend fun isInForeground(): Boolean }

data class ContactSyncResult(val contactsReceived: Long, val lastSyncTimestamp: UInt, val isIncremental: Boolean)

sealed interface ChannelSyncErrorType {
    data object Timeout : ChannelSyncErrorType
    data object SendTimeout : ChannelSyncErrorType
    data object TransportError : ChannelSyncErrorType
    data object CircuitBreaker : ChannelSyncErrorType
    data class DeviceError(val code: UByte) : ChannelSyncErrorType
    data object DatabaseError : ChannelSyncErrorType
    data object Unknown : ChannelSyncErrorType
}

data class ChannelSyncError(val index: UByte, val errorType: ChannelSyncErrorType, val description: String) {
    val isRetryable: Boolean get() = errorType == ChannelSyncErrorType.Timeout || errorType == ChannelSyncErrorType.SendTimeout
    val countsTowardCircuitBreaker: Boolean get() = isRetryable || errorType == ChannelSyncErrorType.TransportError
}

data class ChannelSyncResult(val channelsSynced: Long, val errors: SnapshotList<ChannelSyncError> = SnapshotList.empty()) {
    val isComplete: Boolean get() = errors.isEmpty()
    val requestTimeoutCount: Long get() = errors.count { it.errorType == ChannelSyncErrorType.Timeout }.toLong()
    val sendTimeoutCount: Long get() = errors.count { it.errorType == ChannelSyncErrorType.SendTimeout }.toLong()
    val circuitBreakerAborted: Boolean get() = errors.any { it.errorType == ChannelSyncErrorType.CircuitBreaker }
    val retryableIndices: SnapshotList<UByte> get() = errors.filter { it.isRetryable }.map { it.index }.snapshot()
}

interface ChannelServiceProtocol {
    suspend fun syncChannels(radioId: RadioId, maxChannels: UByte, usePipelinedRead: Boolean = false): ChannelSyncResult
    suspend fun retryFailedChannels(radioId: RadioId, indices: SnapshotList<UByte>): ChannelSyncResult
}

interface ContactServiceProtocol {
    suspend fun syncContacts(radioId: RadioId, since: Instant?): ContactSyncResult
}

interface MessagePollingServiceProtocol {
    suspend fun pollAllMessages(): Long
    suspend fun waitForPendingHandlers(timeout: Duration): Boolean
    suspend fun startAutoFetch(radioId: RadioId)
    suspend fun pauseAutoFetch()
    suspend fun resumeAutoFetch()
    suspend fun setContactMessageHandler(handler: suspend (ContactMessage, ContactDTO?, DeliveryContext) -> Unit)
    suspend fun setChannelMessageHandler(handler: suspend (ChannelMessage, ChannelDTO?, DeliveryContext) -> Unit)
    suspend fun setSignedMessageHandler(handler: suspend (ContactMessage, ContactDTO?) -> Unit)
    suspend fun setCLIMessageHandler(handler: suspend (ContactMessage, ContactDTO?) -> Unit)
    suspend fun clearMessageHandlers()
}

sealed interface ChannelReceiveOrigin {
    data object Unproved : ChannelReceiveOrigin

    /**
     * Reserved for a separately admitted authoritative receive producer. There is deliberately no
     * constructor or factory available to callers: a current slot, key or UUID cannot establish origin.
     */
    class Proved private constructor(
        val session: SessionToken,
        val message: ChannelMessage,
        val occupantID: UUID,
    ) : ChannelReceiveOrigin
}

/**
 * [occurrenceID] identifies an in-process delivery, not a radio packet or channel occupant.
 * The same occurrence survives buffering/retry; distinct equal wire messages remain distinct.
 */
data class ChannelReceiveEnvelope(
    val occurrenceID: UUID,
    val session: SessionToken,
    val message: ChannelMessage,
    val context: DeliveryContext,
    val origin: ChannelReceiveOrigin,
) {
    init {
        if (origin is ChannelReceiveOrigin.Proved) {
            require(origin.session == session && origin.message === message) {
                "Receive proof belongs to a different session or parsed message occurrence"
            }
        }
    }
}

/**
 * An original channel save whose commit outcome is unknown. The occurrence is not authority to
 * retry the save or select a reaction target; a custody owner must preserve it without redrive.
 */
data class ChannelMessageIngressUnknown(
    val envelope: ChannelReceiveEnvelope,
    val messageDTO: MessageDTO,
    val cause: Exception,
) {
    init {
        require(messageDTO.radioId == envelope.session.radioId)
        require(messageDTO.channelIndex == envelope.message.channelIndex)
        require(messageDTO.contactID == null && messageDTO.direction == MessageDirection.INCOMING)
    }
}

class ChannelMessageIngressUnknownException(
    val work: ChannelMessageIngressUnknown,
) : Exception("Original channel message save outcome is unknown; redrive is not permitted", work.cause)

class ChannelMessageIngressUnknownCancellation(
    val work: ChannelMessageIngressUnknown,
    original: CancellationException,
) : CancellationException("Original channel message save was cancelled with an unknown outcome; redrive is not permitted") {
    init {
        require(work.cause === original)
        initCause(original)
    }
}

/** Separate opt-in contract; existing MessagePollingServiceProtocol consumers are unchanged. */
interface ChannelReceiveEnvelopePolling {
    /**
     * Replaces the channel consumer. Install before receiving to opt into occurrence-aware echo pairing;
     * changing consumers cannot recover events already coalesced by legacy matching.
     * [currentChannel] is lookup metadata, never receive-origin proof.
     */
    suspend fun setChannelReceiveEnvelopeHandler(
        handler: suspend (envelope: ChannelReceiveEnvelope, currentChannel: ChannelDTO?) -> Unit,
    )
}
