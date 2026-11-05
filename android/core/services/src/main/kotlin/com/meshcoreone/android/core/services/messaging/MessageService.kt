// PortedFrom: MC1Services/Sources/MC1Services/Services/MessageService.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/MessageService+ACK.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/MessageService+SendDM.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/MessageService+SendChannel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/MessageService+SendHelpers.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.*
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.EventFilter
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.event.MeshTransportError
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.session.ChannelWriteCancellationDisposition
import com.meshcoreone.android.core.protocol.session.MeshCoreSessionProtocol
import com.meshcoreone.android.core.protocol.session.MessageWriteDispatch
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.session.SystemSessionClock
import java.time.Duration
import java.time.Instant
import java.util.BitSet
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MessageService(
    override val token: SessionToken,
    private val localPublicKey: Bytes,
    private val session: MeshCoreSessionProtocol,
    private val dataStore: PersistenceStoreProtocol,
    signals: ConnectionSignals,
    scope: CoroutineScope,
    val config: MessageServiceConfig = MessageServiceConfig(),
    private val clock: SessionClock = SystemSessionClock(),
    private val jitter: (ClosedFloatingPointRange<Double>) -> Double = { range ->
        range.start + Random.nextDouble() * (range.endInclusive - range.start)
    },
    reporter: MessagingIssueReporter = LoggingMessagingIssueReporter,
    private val newID: () -> UUID = UUID::randomUUID,
    private val directAckAuthority: DirectAckAuthority? = dataStore as? DirectAckAuthority,
    private val directPendingCompletionAuthority: DirectPendingCompletionAuthority? =
        dataStore as? DirectPendingCompletionAuthority,
    private val channelTransmission: suspend (UByte, suspend () -> Unit) -> Unit = { _, transmit -> transmit() },
    private val directAckRadioExclusionAuthority: DirectAckRadioExclusionAuthority? =
        directAckAuthority as? DirectAckRadioExclusionAuthority ?: dataStore as? DirectAckRadioExclusionAuthority,
    private val directAckRadioFailureAuthority: DirectAckRadioFailureAuthority? =
        directAckAuthority as? DirectAckRadioFailureAuthority ?: dataStore as? DirectAckRadioFailureAuthority,
    private val channelFailureAuthority: ChannelFailureAuthority? = dataStore as? ChannelFailureAuthority,
    private val channelSendCompletionAuthority: ChannelSendCompletionAuthority? =
        dataStore as? ChannelSendCompletionAuthority,
    private val channelQueueAdmissionAuthority: ChannelQueueAdmissionAuthority? =
        dataStore as? ChannelQueueAdmissionAuthority,
) : MessagingSendPort {
    init { require(localPublicKey.size == ProtocolLimits.PUBLIC_KEY_SIZE) }
    private val ownership = MessagingOwnership(token, scope, signals, reporter)
    private val lock = Any()
    private val dmOperations = Mutex()
    private val pendingOperations = Mutex()
    private val channelOperations = Mutex()
    private val monitoring = Mutex()
    private val mutationLocks = mutableMapOf<UUID, Mutex>()
    private val pendingAcks = linkedMapOf<UUID, PendingAck>()
    private val authorityActivation = Mutex()
    private val activationRequestID = UUID.randomUUID()
    private var authorityLease: DirectAckLease? = null
    private var authorityActivationStarted = false
    private var authorityActivatedThroughChannel = false
    private class ChannelClaim(
        val id: UUID,
        val message: MessageDTO,
        val pendingID: UUID?,
        var attempt: ChannelFailureAttempt,
        var receipt: ChannelFailureReceipt,
        val mode: ChannelSendCompletionMode,
    ) {
        var failureID = UUID.randomUUID()
        val terminalID = UUID.randomUUID()
        var remapID = UUID.randomUUID()
        val completionID = UUID.randomUUID()
        val pendingCompletionID = UUID.randomUUID()
        var acceptedTimestamp: UInt? = null
        var acceptanceNoted = false
        var completion: ChannelSendCompletion? = null
        var completionReceipt: ChannelSendCompletionReceipt? = null
        var retired = false
    }
    private val channelClaims = mutableMapOf<UUID, ChannelClaim>()
    private val channelAdmissionTimestamps = mutableMapOf<UUID, UInt>()
    private class DirectAuthorityState(
        val requestID: UUID,
        val attempt: DirectAckAttempt,
        firstWireID: UUID,
        val correlateResponses: Boolean,
    ) {
        val wires = mutableMapOf(0 to firstWireID)
        val operations = mutableMapOf<String, UUID>()
        val predictedCodes = mutableMapOf<UUID, Bytes>()
        val acceptedCodes = mutableMapOf<UUID, Bytes>()
        val acceptedEmissions = mutableMapOf<UUID, AcceptedEmission>()
        val possiblyEmittedCodes = mutableMapOf<UUID, Bytes?>()
        val confirmedCodes = mutableSetOf<Bytes>()
        val bufferedAcknowledgements = mutableListOf<MeshAcknowledgement>()
        var acknowledgementBufferOverflow = false
    }
    private class AcceptedEmission(
        val handle: DirectAckEmissionHandle?,
        val info: MessageSentInfo,
        val resolutionID: UUID = UUID.randomUUID(),
    ) {
        var resolutionCommitted = handle == null
        var acceptanceRecorded = false
    }
    private val directAuthorities = mutableMapOf<UUID, DirectAuthorityState>()
    private val inFlightDirectEmissions = mutableSetOf<DirectAuthorityState>()
    private val inlineDirectRequests = mutableSetOf<UUID>()
    // Bounded diagnostics only; the persisted radio journal decides exclusion atomically.
    private val retiredEmissionBuckets = BitSet(65536)
    private val directIntentMessages = mutableSetOf<UUID>()
    private val directRegistrationIDs = mutableMapOf<UUID, UUID>()
    private class NoDispatchCompletion(
        val messageID: UUID,
        val pendingID: UUID,
        val reason: DirectPendingCompletionReason,
        val captureID: UUID = UUID.randomUUID(),
        val completionID: UUID = UUID.randomUUID(),
        var handle: DirectPendingCompletion? = null,
    )
    private val noDispatchCompletions = mutableMapOf<UUID, NoDispatchCompletion>()
    private data class UncertainFailureWrite(val tracking: PendingAck, val cause: Exception) {
        val recoveryFailure = if (cause is PersistenceStoreException) cause else
            PersistenceStoreException(PersistenceStoreError.SaveFailed("FAILED write outcome is uncertain"), cause)
    }
    private val uncertainFailureWrites = mutableMapOf<UUID, UncertainFailureWrite>()
    private val directClaims = mutableMapOf<UUID, DirectSendClaim>()
    private val activeDirectClaims = mutableMapOf<UUID, DirectSendClaim>()
    private val inFlightRetries = mutableSetOf<UUID>()
    private val activeOperations = mutableSetOf<Deferred<*>>()
    private val retainedFailures = linkedMapOf<String, Throwable>()
    private val events = MessagingEvents<MessageStatusEvent>(token)
    private var listener: Deferred<Unit>? = null
    private var expiry: Deferred<Unit>? = null
    private var teardown: CompletableDeferred<TeardownReport>? = null
    private var checkInterval = 5.0

    val pendingAckCount: Int get() = synchronized(lock) { pendingAcks.size }
    internal val retainedDirectAuthorityCount: Int get() = synchronized(lock) { directAuthorities.size }
    internal val emissionHistoryBucketCount: Int get() = synchronized(lock) { retiredEmissionBuckets.cardinality() }
    val isAckExpiryCheckingActive: Boolean get() = synchronized(lock) { expiry?.isActive == true }
    val isEventMonitoringActive: Boolean get() = synchronized(lock) { listener?.isActive == true }
    override fun statusEvents(): SessionEventSubscription<MessageStatusEvent> = events.subscribe()
    fun finishStatusEvents(cause: Throwable? = null) = events.finish(cause)
    private fun key(id: UUID) = EntityKey(token.radioId, id)
    private fun mutation(id: UUID): Mutex = synchronized(lock) { mutationLocks.getOrPut(id) { Mutex() } }
    private fun remember(operation: String, failure: Throwable) {
        synchronized(lock) { retainedFailures[operation] = failure }
        ownership.failure(operation, failure)
    }
    private fun recovered(operation: String) { synchronized(lock) { retainedFailures.remove(operation) } }

    private fun authorityFailure(detail: String): PersistenceStoreException =
        PersistenceStoreException(PersistenceStoreError.SaveFailed("Direct ACK authority: $detail"))

    private fun authority(): DirectAckAuthority =
        directAckAuthority ?: throw authorityFailure("capability unavailable")

    private fun radioAuthority(): DirectAckRadioExclusionAuthority =
        directAckRadioExclusionAuthority ?: throw authorityFailure("radio exclusion capability unavailable")

    private fun failureAuthority(): DirectAckRadioFailureAuthority =
        directAckRadioFailureAuthority ?: throw authorityFailure("radio failure capability unavailable")

    private fun channelAuthorityFailure(detail: String) =
        PersistenceStoreException(PersistenceStoreError.SaveFailed("Channel failure authority: $detail"))

    private fun channelAuthority(): ChannelFailureAuthority =
        channelFailureAuthority ?: throw channelAuthorityFailure("capability unavailable")

    private fun channelCompletionAuthority(): ChannelSendCompletionAuthority =
        channelSendCompletionAuthority ?: throw channelAuthorityFailure("completion capability unavailable")

    private fun requireDirectDispatchAuthorities() {
        radioAuthority()
        failureAuthority()
    }

    private fun mutationReceipt(result: DirectAckMutationResult): DirectAckReceipt =
        when (result) {
            is DirectAckMutationResult.Applied -> result.receipt
            is DirectAckMutationResult.AlreadyApplied -> result.receipt
            is DirectAckMutationResult.AlreadyDelivered -> result.receipt
            is DirectAckMutationResult.Rejected -> throw authorityFailure(result.reason.name)
        }

    private fun requireReceipt(result: DirectAckMutationResult): DirectAckReceipt {
        val receipt = mutationReceipt(result)
        if (receipt.permission != DirectAckPermission.CURRENT || receipt.message == null) {
            throw authorityFailure("non-current or erased ${receipt.permission}")
        }
        return receipt
    }

    private data class PreparedDirect(
        val intent: DirectAckIntent, val message: MessageDTO, val contact: ContactDTO,
    )

    private suspend fun prepareDirect(
        id: UUID, requestID: UUID, contact: ContactDTO, mode: DirectAckMode, pendingSendID: UUID?,
        isResend: Boolean = false,
    ): PreparedDirect {
        synchronized(lock) { directIntentMessages += id }
        val result = authority().captureDirectAckIntent(directLease(), key(id), requestID, mode, pendingSendID)
        val (intent, receipt) = when (result) {
            is DirectAckIntentResult.Captured -> result.intent to result.receipt
            is DirectAckIntentResult.AlreadyCaptured -> result.intent to result.receipt
            is DirectAckIntentResult.Rejected -> throw authorityFailure(result.reason.name)
        }
        ownership.check("directIntent.captured")
        val message = receipt.message ?: throw authorityFailure("erased dispatch intent")
        val pending = receipt.pending
        if (receipt.permission != DirectAckPermission.CURRENT || receipt.key != key(id) ||
            receipt.requestId != requestID || receipt.mode != mode || receipt.pendingSendId != pendingSendID ||
            pendingSendID != null && (pending == null || pending.id != pendingSendID || pending.messageID != id)) {
            throw authorityFailure("non-current or mismatched dispatch intent")
        }
        if (pending != null && (pending.contactID != message.contactID || pending.isResend != isResend)) {
            throw authorityFailure("pending dispatch intent changed")
        }
        if (message.radioId != token.radioId || message.contactID != contact.id || contact.radioId != token.radioId) {
            throw MessageServiceException(MessageServiceError.ContactNotFound)
        }
        val selected = dataStore.fetchContact(key(requireNotNull(message.contactID)))
            ?: throw MessageServiceException(MessageServiceError.ContactNotFound)
        ownership.check("directIntent.contact")
        if (selected.publicKey != contact.publicKey) throw MessageServiceException(MessageServiceError.ContactNotFound)
        validate(message.text, selected)
        return PreparedDirect(intent, message, selected)
    }

    private suspend fun directLease(): DirectAckLease = sessionLease(channel = false)

    private suspend fun sessionLease(channel: Boolean): DirectAckLease = authorityActivation.withLock {
        authorityLease ?: run {
            authorityActivationStarted = true
            authorityActivatedThroughChannel = channel
            val result = if (channel) channelAuthority().activateChannelFailureSession(token, activationRequestID)
                else authority().activateDirectAckSession(token, activationRequestID)
            when (result) {
            is DirectAckActivationResult.Activated -> result.lease
            is DirectAckActivationResult.AlreadyActivated -> result.lease
            is DirectAckActivationResult.Rejected -> throw authorityFailure(result.reason.name)
            }
        }.also { authorityLease = it }
    }

    private fun capturedChannel(
        claimID: UUID, id: UUID, pendingID: UUID?, result: ChannelFailureCaptureResult,
        mode: ChannelSendCompletionMode = ChannelSendCompletionMode.SEND,
    ): ChannelClaim {
        val (attempt, receipt) = when (result) {
            is ChannelFailureCaptureResult.Captured -> result.attempt to result.receipt
            is ChannelFailureCaptureResult.AlreadyCaptured -> result.attempt to result.receipt
            is ChannelFailureCaptureResult.Rejected -> {
                if (result.reason == DirectAckRejection.MISSING || result.reason == DirectAckRejection.NOT_ELIGIBLE) {
                    throw MessageServiceException(MessageServiceError.SendFailed("Channel message unavailable: ${result.reason}"))
                }
                throw channelAuthorityFailure(result.reason.name)
            }
        }
        val message = receipt.message ?: throw channelAuthorityFailure("erased capture")
        val pending = receipt.pending
        if (receipt.captureRequestId != claimID || receipt.key != key(id) ||
            receipt.permission != DirectAckPermission.CURRENT || receipt.pendingSendId != pendingID ||
            message.radioId != token.radioId || message.id != id || message.channelIndex == null ||
            message.contactID != null || message.direction != MessageDirection.OUTGOING ||
            pendingID != null && (pending == null || pending.id != pendingID || pending.messageID != id ||
                pending.radioId != token.radioId || pending.kind != PendingSendKind.CHANNEL ||
                pending.channelIndex != message.channelIndex ||
                pending.isResend != (mode == ChannelSendCompletionMode.RESEND))) {
            throw channelAuthorityFailure("non-current or mismatched capture")
        }
        return ChannelClaim(claimID, message, pendingID, attempt, receipt, mode).also {
            synchronized(lock) { channelClaims[claimID] = it }
        }
    }

    internal fun channelClaimMessage(claimID: UUID): MessageDTO =
        synchronized(lock) { channelClaims[claimID]?.message }
            ?: throw channelAuthorityFailure("capture unavailable")

    internal fun releaseChannelClaim(claimID: UUID) {
        synchronized(lock) {
            channelClaims.remove(claimID)
            channelAdmissionTimestamps.remove(claimID)
        }
    }

    internal fun channelClaimAccepted(claimID: UUID): Boolean =
        synchronized(lock) { channelClaims[claimID]?.acceptedTimestamp != null }

    internal sealed interface ChannelSendOutcome {
        data class Completed(val message: MessageDTO, val timestamp: UInt, val pending: PendingSendDTO?) : ChannelSendOutcome
        data object Retired : ChannelSendOutcome
    }

    internal sealed interface ChannelQueuePreparation {
        data class Ready(val pending: PendingSendDTO, val postBumpCount: Long) : ChannelQueuePreparation
        data object Retired : ChannelQueuePreparation
    }

    private fun queueAdmissionAuthority(): ChannelQueueAdmissionAuthority =
        channelQueueAdmissionAuthority ?: throw channelAuthorityFailure("queue admission capability unavailable")

    internal suspend fun admitQueuedChannelClaim(claimID: UUID, id: UUID): ChannelQueuePreparation =
        owned("admitQueuedChannel", channelOperations) {
            channelCompletionAuthority()
            val freshTimestamp = synchronized(lock) { channelAdmissionTimestamps.getOrPut(claimID) { timestamp() } }
            val result = queueAdmissionAuthority().admitQueuedChannelSend(
                sessionLease(channel = true), claimID, key(id), freshTimestamp,
            )
            val (attempt, receipt, count) = when (result) {
                is ChannelQueueAdmissionResult.Ready -> Triple(result.attempt, result.receipt, result.postBumpCount)
                is ChannelQueueAdmissionResult.AlreadyReady -> Triple(result.attempt, result.receipt, result.postBumpCount)
                is ChannelQueueAdmissionResult.PreviouslyCompleted -> {
                    if (result.receipt.capture.key != key(id)) throw channelAuthorityFailure("foreign completed admission")
                    if (ownership.isCurrent) events.yield(MessageStatusEvent.PersistenceChanged(key(id)))
                    return@owned ChannelQueuePreparation.Retired
                }
                is ChannelQueueAdmissionResult.Uncertain -> {
                    if (result.acceptance.key != key(id)) throw channelAuthorityFailure("foreign uncertain admission")
                    ownership.failure("channelQueueUncertain.$claimID", ChannelQueueAdmissionUncertaintyException(
                        token, result.acceptance, result.reason,
                    ))
                    return@owned ChannelQueuePreparation.Retired
                }
                is ChannelQueueAdmissionResult.Rejected -> {
                    reportChannelUncertainty(id, result.reason)
                    return@owned ChannelQueuePreparation.Retired
                }
            }
            val pending = receipt.pending ?: throw channelAuthorityFailure("admission pending unavailable")
            if (count < 1 || pending.attemptCount != count) throw channelAuthorityFailure("mismatched admission count")
            val mode = if (pending.isResend) ChannelSendCompletionMode.RESEND else ChannelSendCompletionMode.SEND
            capturedChannel(claimID, id, pending.id,
                ChannelFailureCaptureResult.Captured(attempt, receipt), mode)
            ChannelQueuePreparation.Ready(pending, count)
        }

    private fun noteChannelAcceptance(claim: ChannelClaim) {
        val pendingID = claim.pendingID ?: return
        if (claim.acceptanceNoted) return
        val stamp = claim.acceptedTimestamp ?: throw channelAuthorityFailure("accepted timestamp unavailable")
        val acceptance = when (val result = queueAdmissionAuthority().noteAcceptedChannelAttempt(
            claim.attempt, claim.completionID, claim.mode, stamp,
        )) {
            is ChannelQueueAcceptanceResult.Registered -> result.acceptance
            is ChannelQueueAcceptanceResult.AlreadyRegistered -> result.acceptance
            is ChannelQueueAcceptanceResult.Rejected -> {
                val failure = channelAuthorityFailure("accepted process fence rejected: ${result.reason}")
                remember("channelAcceptance.${claim.id}", failure)
                throw failure
            }
        }
        if (acceptance.captureRequestId != claim.id || acceptance.key != key(claim.message.id) ||
            acceptance.pendingSendId != pendingID || acceptance.completionRequestId != claim.completionID ||
            acceptance.mode != claim.mode || acceptance.acceptedTimestamp != stamp) {
            val failure = channelAuthorityFailure("mismatched accepted process fence")
            remember("channelAcceptance.${claim.id}", failure)
            throw failure
        }
        claim.acceptanceNoted = true
        recovered("channelAcceptance.${claim.id}")
    }

    private fun validateChannelCompletion(claim: ChannelClaim, receipt: ChannelSendCompletionReceipt) {
        val capture = receipt.capture
        if (capture.captureRequestId != claim.id || capture.key != key(claim.message.id) ||
            capture.pendingSendId != claim.pendingID || receipt.completionRequestId != claim.completionID ||
            receipt.mode != claim.mode || receipt.acceptedTimestamp != claim.acceptedTimestamp) {
            throw channelAuthorityFailure("mismatched send completion")
        }
    }

    private fun retireAcceptedChannel(claim: ChannelClaim, rejection: DirectAckRejection? = null) {
        claim.retired = true
        ownership.failure("channelCompletionRetired.${claim.id}", ChannelSendCompletionUncertaintyException(
            token, claim.id, claim.completionID, claim.message.id, rejection,
        ))
    }

    private suspend fun completeAcceptedChannel(claim: ChannelClaim): ChannelSendOutcome {
        if (claim.retired) return ChannelSendOutcome.Retired
        noteChannelAcceptance(claim)
        ownership.checkPersistenceCompletion("channelSendCompletion")
        val stamp = claim.acceptedTimestamp ?: throw channelAuthorityFailure("transport acceptance unavailable")
        val (completion, receipt) = try {
            when (val result = channelCompletionAuthority().recordChannelSendCompleted(
                claim.attempt, claim.completionID, claim.mode, stamp,
            )) {
                is ChannelSendCompletionResult.Applied -> result.completion to result.receipt
                is ChannelSendCompletionResult.AlreadyApplied -> result.completion to result.receipt
                is ChannelSendCompletionResult.AlreadyDelivered -> {
                    retireAcceptedChannel(claim)
                    return ChannelSendOutcome.Retired
                }
                is ChannelSendCompletionResult.Rejected -> {
                    retireAcceptedChannel(claim, result.reason)
                    return ChannelSendOutcome.Retired
                }
            }
        } catch (failure: PersistenceStoreException) {
            remember("channelCommit.${claim.id}", failure)
            throw failure
        }
        validateChannelCompletion(claim, receipt)
        claim.completionReceipt = receipt
        claim.completion = completion
        recovered("channelCommit.${claim.id}")
        if (ownership.isCurrent) events.yield(MessageStatusEvent.PersistenceChanged(key(claim.message.id)))
        val message = receipt.capture.message
        if (completion == null || receipt.capture.permission != DirectAckPermission.CURRENT || message == null) {
            retireAcceptedChannel(claim)
            return ChannelSendOutcome.Retired
        }
        if (message.id != claim.message.id || message.radioId != token.radioId ||
            message.channelIndex != claim.message.channelIndex || message.contactID != null ||
            message.timestamp != stamp || message.status != MessageStatus.SENT ||
            message.direction != MessageDirection.OUTGOING) {
            throw channelAuthorityFailure("mismatched current send completion")
        }
        val pending = receipt.capture.pending
        if (claim.pendingID != null && (pending == null || pending.id != claim.pendingID ||
            pending.messageID != message.id || pending.radioId != token.radioId ||
            pending.channelIndex != message.channelIndex || pending.kind != PendingSendKind.CHANNEL ||
            pending.isResend != (claim.mode == ChannelSendCompletionMode.RESEND))) {
            throw channelAuthorityFailure("mismatched current send pending binding")
        }
        return ChannelSendOutcome.Completed(message, stamp, pending)
    }

    internal suspend fun completeQueuedChannelPending(claimID: UUID): Boolean =
        mutation(synchronized(lock) { channelClaims[claimID]?.message?.id }
            ?: throw channelAuthorityFailure("pending completion capture unavailable")).withLock {
            ownership.checkPersistenceCompletion("channelPendingCompletion")
            val claim = synchronized(lock) { channelClaims[claimID] }
                ?: throw channelAuthorityFailure("pending completion capture unavailable")
            val completion = claim.completion ?: throw channelAuthorityFailure("pending completion grant unavailable")
            val receipt = try {
                when (val result = channelCompletionAuthority().completeChannelSendPending(completion, claim.pendingCompletionID)) {
                    is ChannelSendPendingCompletionResult.Applied -> result.receipt
                    is ChannelSendPendingCompletionResult.AlreadyApplied -> result.receipt
                    is ChannelSendPendingCompletionResult.Rejected -> {
                        retireAcceptedChannel(claim, result.reason)
                        return@withLock false
                    }
                }
            } catch (failure: PersistenceStoreException) {
                remember("channelCleanup.${claim.id}", failure)
                throw failure
            }
            validateChannelCompletion(claim, receipt)
            if (receipt.pendingCompletionRequestId != claim.pendingCompletionID ||
                receipt.capture.message != null || receipt.capture.pending != null) {
                throw channelAuthorityFailure("mismatched pending completion commit")
            }
            claim.completionReceipt = receipt
            recovered("channelCleanup.${claim.id}")
            if (ownership.isCurrent) events.yield(MessageStatusEvent.PersistenceChanged(key(claim.message.id)))
            true
        }

    internal suspend fun remapQueuedChannelClaim(claimID: UUID): Boolean = withContext(NonCancellable) {
        ownership.checkPersistenceCompletion("channelRemap")
        val claim = synchronized(lock) { channelClaims[claimID] }
            ?: throw channelAuthorityFailure("remap capture unavailable")
        if (claim.acceptedTimestamp != null) throw channelAuthorityFailure("accepted capture cannot remap")
        val (attempt, receipt) = when (val result = channelAuthority().remapChannelFailureAttempt(claim.attempt, claim.remapID)) {
            is ChannelFailureRemapResult.Remapped -> result.attempt to result.receipt
            is ChannelFailureRemapResult.AlreadyRemapped -> result.attempt to result.receipt
            is ChannelFailureRemapResult.AlreadyDelivered -> return@withContext false
            is ChannelFailureRemapResult.Rejected -> {
                reportChannelUncertainty(claim.message.id, result.reason)
                return@withContext false
            }
        }
        if (receipt.captureRequestId != claim.id || receipt.key != key(claim.message.id) ||
            receipt.pendingSendId != claim.pendingID || receipt.permission != DirectAckPermission.CURRENT ||
            receipt.message?.status != MessageStatus.PENDING ||
            receipt.operations.none { it.requestId == claim.remapID && it.operation == ChannelFailureOperation.REMAPPED_TO_PENDING }) {
            throw channelAuthorityFailure("mismatched remap commit")
        }
        claim.attempt = attempt
        claim.receipt = receipt
        claim.remapID = UUID.randomUUID()
        claim.failureID = UUID.randomUUID()
        if (ownership.isCurrent) events.yield(MessageStatusEvent.PersistenceChanged(key(claim.message.id)))
        true
    }

    private fun reportChannelUncertainty(id: UUID, rejection: DirectAckRejection? = null) {
        ownership.failure("channelFailureUncertain.$id", ChannelFailureUncertaintyException(token, id, rejection))
    }

    private suspend fun failChannelClaim(claimID: UUID?, id: UUID, cleanup: ChannelFailureCleanup): Boolean {
        val claim = synchronized(lock) { channelClaims[claimID] }
        if (claim == null || claim.message.id != id) {
            reportChannelUncertainty(id)
            return false
        }
        if (claim.acceptedTimestamp != null) {
            reportChannelUncertainty(id)
            return false
        }
        ownership.checkPersistenceCompletion("channelFailure")
        val requestID = if (cleanup == ChannelFailureCleanup.KEEP_PENDING) claim.failureID else claim.terminalID
        val receipt = when (val result = channelAuthority().recordChannelFailure(claim.attempt, requestID, cleanup)) {
            is ChannelFailureResult.Applied -> result.receipt
            is ChannelFailureResult.AlreadyApplied -> result.receipt
            is ChannelFailureResult.AlreadyDelivered -> return false
            is ChannelFailureResult.Rejected -> {
                reportChannelUncertainty(id, result.reason)
                return false
            }
        }
        val operation = if (cleanup == ChannelFailureCleanup.KEEP_PENDING) ChannelFailureOperation.FAILED
            else ChannelFailureOperation.FAILED_AND_PENDING_DELETED
        if (receipt.key != key(id) || receipt.captureRequestId != claim.id ||
            receipt.pendingSendId != claim.pendingID ||
            receipt.operations.none { it.requestId == requestID && it.operation == operation }) {
            throw channelAuthorityFailure("mismatched failure commit")
        }
        claim.receipt = receipt
        recovered("channelFailure.$id")
        if (ownership.isCurrent) events.yield(MessageStatusEvent.PersistenceChanged(key(id)))
        return true
    }

    internal suspend fun failQueuedChannelClaim(claimID: UUID?, messageID: UUID): Boolean =
        mutation(messageID).withLock {
            failChannelClaim(claimID, messageID, ChannelFailureCleanup.DELETE_CAPTURED_PENDING)
        }

    private suspend fun failChannelAndRethrow(claimID: UUID, id: UUID, failure: Exception): Nothing {
        val receipt = currentCoroutineContext()[NotificationActionReceipt]
        withContext(NonCancellable) {
            if (receipt == null || receipt.state == NotificationActionReceipt.State.READY) {
                try {
                    mutation(id).withLock { failChannelClaim(claimID, id, ChannelFailureCleanup.KEEP_PENDING) }
                } catch (storage: PersistenceStoreException) {
                    remember("channelFailure.$id", storage)
                    if (storage !== failure) failure.addSuppressed(storage)
                } catch (stale: MessageServiceException) {
                    remember("channelFailure.$id", stale)
                    if (stale !== failure) failure.addSuppressed(stale)
                }
            }
        }
        if (failure is MeshCoreException) throw MessageServiceException(MessageServiceError.SessionError(failure))
        throw failure
    }

    private suspend fun <T> channelBoundary(claimID: UUID, id: UUID, block: suspend () -> T): T =
        try { block() }
        catch (failure: CancellationException) { failChannelAndRethrow(claimID, id, failure) }
        catch (failure: MeshCoreException) { failChannelAndRethrow(claimID, id, failure) }
        catch (failure: MeshTransportError) { failChannelAndRethrow(claimID, id, failure) }
        catch (failure: MessageServiceException) { failChannelAndRethrow(claimID, id, failure) }
        catch (failure: PersistenceStoreException) { failChannelAndRethrow(claimID, id, failure) }

    private fun directAuthority(id: UUID): DirectAuthorityState =
        synchronized(lock) { directAuthorities[id] } ?: throw authorityFailure("unregistered message")

    private suspend fun registerDirectDispatch(
        id: UUID, requestID: UUID, predicted: Bytes, wireIndex: Int,
        intent: DirectAckIntent,
        correlateResponses: Boolean = true,
    ): Pair<DirectAuthorityState, UUID> {
        requireDirectDispatchAuthorities()
        val prior = synchronized(lock) { directAuthorities[id] }
        if (prior?.requestID == requestID) {
            val wireID = synchronized(lock) { prior.wires.getOrPut(wireIndex, UUID::randomUUID) }
            requireReceipt(radioAuthority().registerDirectAckDispatchRequiringEmissionTracking(
                prior.attempt, wireID, predicted.ackCodeUInt32))
            synchronized(lock) { prior.predictedCodes[wireID] = predicted }
            return prior to wireID
        }
        val wireID = synchronized(lock) { directRegistrationIDs.getOrPut(requestID, UUID::randomUUID) }
        val result = radioAuthority().beginDirectAckAttemptRequiringEmissionTracking(intent, wireID, predicted.ackCodeUInt32)
        val (attempt, receipt) = when (result) {
            is DirectAckBeginResult.Begun -> result.attempt to result.receipt
            is DirectAckBeginResult.AlreadyBegun -> result.attempt to result.receipt
            is DirectAckBeginResult.Rejected -> throw authorityFailure(result.reason.name)
        }
        if (receipt.permission != DirectAckPermission.CURRENT) throw authorityFailure(receipt.permission.name)
        val state = synchronized(lock) {
            DirectAuthorityState(requestID, attempt, wireID, correlateResponses).also {
                it.predictedCodes[wireID] = predicted
                directAuthorities.put(id, it)?.let { displaced -> retainRetiredEmission(displaced) }
                pendingAcks.remove(id)?.receipt?.cancel()
            }
        }
        return state to wireID
    }

    private fun retainRetiredEmission(state: DirectAuthorityState) {
        if (state in inFlightDirectEmissions ||
            directAuthorities.values.any { it === state } || state.possiblyEmittedCodes.isEmpty()) return
        state.possiblyEmittedCodes.values.forEach { code ->
            if (code != null) retiredEmissionBuckets.set(emissionBucket(code))
        }
    }

    private fun pruneDirectAuthorities() {
        val retired = directAuthorities.filter { (id, state) ->
            id !in pendingAcks && state !in inFlightDirectEmissions &&
                state.requestID !in inlineDirectRequests &&
                directClaims.values.none { it.id == state.requestID }
        }
        retired.forEach { (id, state) ->
            directAuthorities.remove(id)
            directRegistrationIDs.remove(state.requestID)
            retainRetiredEmission(state)
        }
    }

    private suspend fun <T> withInlineDirectRequest(requestID: UUID, block: suspend () -> T): T {
        synchronized(lock) { inlineDirectRequests += requestID }
        try { return block() }
        finally {
            synchronized(lock) {
                inlineDirectRequests -= requestID
                directRegistrationIDs.remove(requestID)
                pruneDirectAuthorities()
            }
        }
    }

    private suspend fun directWire(
        state: DirectAuthorityState, wireID: UUID, dispatch: MessageWriteDispatch?,
        transmit: suspend () -> MessageSentInfo,
    ): MessageSentInfo {
        val emissionID = UUID.randomUUID()
        val prediction = synchronized(lock) { state.predictedCodes[wireID] }
            ?: throw authorityFailure("unregistered physical wire")
        val marked = try {
            radioAuthority().markDirectAckWireMayHaveWritten(state.attempt, wireID, emissionID)
        } catch (failure: PersistenceStoreException) {
            remember("ackMark.${state.requestID}.$wireID.$emissionID", failure)
            throw failure
        }
        fun checkedHandle(receipt: DirectAckEmissionReceipt, handle: DirectAckEmissionHandle): DirectAckEmissionHandle {
            val evidence = receipt.evidence
            if (receipt.emissionRequestId != emissionID || evidence.emissionRequestId != emissionID ||
                evidence.radioId != token.radioId || evidence.claimRequestId != state.requestID ||
                evidence.wireRequestId != wireID || evidence.predictedAckCode != prediction.ackCodeUInt32 ||
                evidence.outcome != DirectAckRadioWireOutcome.MayHaveWritten) {
                throw authorityFailure("physical marker tuple mismatch")
            }
            return handle
        }
        val handle = when (marked) {
            is DirectAckEmissionMarkResult.Marked -> checkedHandle(marked.receipt, marked.handle)
            is DirectAckEmissionMarkResult.AlreadyMarked -> checkedHandle(marked.receipt, marked.handle)
            is DirectAckEmissionMarkResult.CoveredBySaturation -> {
                if (marked.receipt.radioId != token.radioId) throw authorityFailure("wrong saturation radio")
                remember("ackSaturation", authorityFailure("Persisted radio history is saturated; delivery remains unproven"))
                null
            }
            is DirectAckEmissionMarkResult.Rejected -> throw authorityFailure(marked.reason.name)
        }
        checkIdentity("directWire.marked")
        synchronized(lock) {
            state.possiblyEmittedCodes[wireID] = null
            inFlightDirectEmissions += state
        }
        var transportReturned = false
        try {
            currentCoroutineContext()[NotificationActionReceipt]?.wireStarted()
            val info = transmit()
            transportReturned = true
            currentCoroutineContext()[NotificationActionReceipt]?.accepted()
            val accepted = AcceptedEmission(handle, info)
            synchronized(lock) {
                state.possiblyEmittedCodes[wireID] = info.expectedAck
                state.acceptedEmissions[wireID] = accepted
                activeDirectClaims.values.firstOrNull { it.id == state.requestID }?.let {
                    it.lastSentInfo = info
                    it.acceptedWire = AcceptedDirectWire(wireID, info)
                }
            }
            resolveDirectAcceptance(accepted)
            return info
        } catch (cancelled: CancellationException) {
            if (!transportReturned &&
                dispatch?.cancellationDisposition == ChannelWriteCancellationDisposition.DEFINITELY_UNSENT) {
                if (handle != null) {
                    try {
                        withContext(NonCancellable) {
                            val resolved = requireEmissionResolution(radioAuthority().resolveDirectAckWireEmission(
                                handle, UUID.randomUUID(), DirectAckEmissionResolution.DefinitelyUnsent,
                            ))
                            if (resolved.emissionRequestId != emissionID ||
                                resolved.evidence.outcome != DirectAckRadioWireOutcome.DefinitelyUnsent) {
                                throw authorityFailure("physical unsent binding missing")
                            }
                        }
                        synchronized(lock) { state.possiblyEmittedCodes.remove(wireID) }
                    } catch (failure: Exception) {
                        remember("ackUnsent.$emissionID", failure)
                        if (failure !== cancelled) cancelled.addSuppressed(failure)
                    }
                }
            }
            throw cancelled
        } finally {
            synchronized(lock) {
                inFlightDirectEmissions -= state
                retainRetiredEmission(state)
            }
        }
    }

    private fun requireEmissionResolution(result: DirectAckEmissionMutationResult): DirectAckEmissionReceipt =
        when (result) {
            is DirectAckEmissionMutationResult.Applied -> result.receipt
            is DirectAckEmissionMutationResult.AlreadyApplied -> result.receipt
            is DirectAckEmissionMutationResult.Conflict -> throw authorityFailure("physical emission conflict")
            is DirectAckEmissionMutationResult.Rejected -> throw authorityFailure(result.reason.name)
        }

    private suspend fun resolveDirectAcceptance(accepted: AcceptedEmission) {
        val handle = accepted.handle ?: return
        if (synchronized(lock) { accepted.resolutionCommitted }) return
        // Finish only exact wire evidence after acceptance, never renew the retired product grant.
        val receipt = withContext(NonCancellable) {
            requireEmissionResolution(radioAuthority().resolveDirectAckWireEmission(
                handle, accepted.resolutionID, DirectAckEmissionResolution.Accepted(accepted.info.expectedAck.ackCodeUInt32),
            ))
        }
        if (receipt.evidence.outcome != DirectAckRadioWireOutcome.Accepted(accepted.info.expectedAck.ackCodeUInt32)) {
            throw authorityFailure("physical accepted binding missing")
        }
        synchronized(lock) { accepted.resolutionCommitted = true }
    }

    private fun emissionBucket(code: Bytes): Int = code.ackCodeUInt32.toInt() and 0xFFFF

    private suspend fun recordDirectAcceptance(state: DirectAuthorityState, wireID: UUID, info: MessageSentInfo) {
        synchronized(lock) { state.acceptedEmissions[wireID] }?.let { resolveDirectAcceptance(it) }
        val receipt = mutationReceipt(authority().recordDirectSendAccepted(state.attempt, wireID, info.expectedAck.ackCodeUInt32))
        if (receipt.wires.none { it.requestId == wireID && it.acceptedAckCode == info.expectedAck.ackCodeUInt32 }) {
            throw authorityFailure("accepted wire binding missing")
        }
        synchronized(lock) {
            state.acceptedCodes[wireID] = info.expectedAck
            state.confirmedCodes += info.expectedAck
            state.acceptedEmissions[wireID]?.acceptanceRecorded = true
        }
    }

    private suspend fun replayConfirmedAcknowledgements(state: DirectAuthorityState) {
        var unmatched: List<MeshAcknowledgement> = emptyList()
        val ready = synchronized(lock) {
            state.bufferedAcknowledgements.filter { it.code in state.confirmedCodes }.also {
                state.bufferedAcknowledgements.removeAll(it.toSet())
                if (state.acceptedCodes.keys.containsAll(state.predictedCodes.keys)) {
                    unmatched = state.bufferedAcknowledgements.toList()
                    state.bufferedAcknowledgements.clear()
                }
            }
        }
        ready.forEach { handleAcknowledgement(it.code, it.tripTime, state.attempt) }
        unmatched.forEach { reportUnmatchedAcknowledgement(it.code) }
    }

    internal fun bufferedAcknowledgementCount(messageID: UUID): Int =
        synchronized(lock) { directAuthorities[messageID]?.bufferedAcknowledgements?.size ?: 0 }

    private fun retireBufferedAcknowledgements(messageID: UUID, expectedAttempt: DirectAckAttempt?) {
        val requestID = synchronized(lock) {
            directAuthorities[messageID]?.takeIf { it.attempt === expectedAttempt }
                ?.takeIf { it.bufferedAcknowledgements.isNotEmpty() }?.let {
                    it.bufferedAcknowledgements.clear()
                    it.requestID
                }
        }
        if (requestID != null) remember("ackBuffer.$requestID",
            authorityFailure("Uncorrelated ACK evidence retired with its terminal tracker"))
    }

    private suspend fun reportUnmatchedAcknowledgement(code: Bytes) = bookkeeping("unmatchedAckDiagnostic") {
        val orphanSentRow = dataStore.hasOutgoingSentDM(token.radioId, code.ackCodeUInt32)
        ownership.check("unmatchedAcknowledgement.read")
        ownership.reporter.report(MessagingDiagnostic.UnmatchedAcknowledgement(token, orphanSentRow))
    }

    private suspend fun directBookkeeping(id: UUID, operation: String, change: DirectAckBookkeeping): DirectAckReceipt {
        return directBookkeeping(directAuthority(id), operation, change)
    }

    private suspend fun directBookkeeping(
        state: DirectAuthorityState, operation: String, change: DirectAckBookkeeping,
    ): DirectAckReceipt {
        val requestID = synchronized(lock) { state.operations.getOrPut(operation, UUID::randomUUID) }
        val result = authority().applyDirectAckBookkeeping(state.attempt, requestID, change)
        if (result is DirectAckMutationResult.Rejected && result.reason == DirectAckRejection.NOT_ELIGIBLE &&
            change != DirectAckBookkeeping.ResendCountIncrement) {
            val observation = authority().inspectDirectAckAttempt(directLease(), state.requestID)
            if (observation is DirectAckObservation.Found &&
                observation.receipt.permission == DirectAckPermission.CURRENT &&
                observation.receipt.message?.status == MessageStatus.FAILED) {
                return observation.receipt
            }
        }
        return requireReceipt(result)
    }

    internal suspend fun completeDirectPending(claimID: UUID, messageID: UUID, pendingSendID: UUID) {
        ownership.check("completeDirectPending")
        val state = synchronized(lock) { directAuthorities[messageID]?.takeIf { it.requestID == claimID } }
        if (state == null) {
            completeWithoutDispatch(claimID, messageID, pendingSendID)
            return
        }
        val requestID = synchronized(lock) { state.operations.getOrPut("pendingCompletion", UUID::randomUUID) }
        val receipt = mutationReceipt(authority().completeDirectPending(state.attempt, pendingSendID, requestID))
        if (receipt.pendingCompletions.none { it.requestId == requestID && it.pendingSendId == pendingSendID }) {
            throw authorityFailure("pending completion receipt missing")
        }
        ownership.check("completeDirectPending.saved")
    }

    private suspend fun completeWithoutDispatch(claimID: UUID, messageID: UUID, pendingSendID: UUID) {
        val state = synchronized(lock) { noDispatchCompletions[claimID] }
            ?: throw authorityFailure("pending completion has no send or no-dispatch decision")
        if (state.messageID != messageID || state.pendingID != pendingSendID) {
            throw authorityFailure("no-dispatch completion identity mismatch")
        }
        val producer = directPendingCompletionAuthority
            ?: throw authorityFailure("no-dispatch completion capability unavailable")
        val handle = state.handle ?: run {
            val result = producer.captureDirectPendingCompletion(
                directLease(), key(messageID), pendingSendID, state.captureID, state.reason,
            )
            val (captured, receipt) = when (result) {
                is DirectPendingCaptureResult.Captured -> result.completion to result.receipt
                is DirectPendingCaptureResult.AlreadyCaptured -> result.completion to result.receipt
                is DirectPendingCaptureResult.Rejected -> throw authorityFailure(result.reason.name)
            }
            if (receipt.permission != DirectPendingCompletionPermission.CURRENT ||
                receipt.key != key(messageID) || receipt.message == null ||
                receipt.pendingSendId != pendingSendID || receipt.pending?.id != pendingSendID || receipt.reason != state.reason) {
                throw authorityFailure("no-dispatch capture is not current or matching")
            }
            captured
        }.also { state.handle = it }
        ownership.check("completeDirectPending.captured")
        val receipt = when (val result = producer.completeDirectPendingWithoutDispatch(handle, state.completionID)) {
            is DirectPendingCompletionResult.Applied -> result.receipt
            is DirectPendingCompletionResult.AlreadyApplied -> result.receipt
            is DirectPendingCompletionResult.Rejected -> throw authorityFailure(result.reason.name)
        }
        if (receipt.captureRequestId != state.captureID || receipt.completedRequestId != state.completionID) {
            throw authorityFailure("no-dispatch completion receipt missing")
        }
        ownership.check("completeDirectPending.saved")
    }

    private fun rememberNoDispatch(
        claimID: UUID, messageID: UUID, pendingID: UUID?, reason: DirectPendingCompletionReason,
    ) {
        if (pendingID == null) return
        synchronized(lock) {
            val state = noDispatchCompletions.getOrPut(claimID) { NoDispatchCompletion(messageID, pendingID, reason) }
            if (state.messageID != messageID || state.pendingID != pendingID || state.reason != reason) {
                throw authorityFailure("no-dispatch decision changed")
            }
        }
    }

    private suspend fun checkIdentity(operation: String) {
        ownership.check(operation)
        if (session.currentSelfInfo?.publicKey != localPublicKey) {
            val failure = MessageServiceException(MessageServiceError.NotConnected)
            ownership.failure("$operation.identity", failure)
            throw failure
        }
    }

    private suspend fun <T> owned(operation: String, mutex: Mutex? = null, block: suspend () -> T): T {
        val caller = currentCoroutineContext()
        val receipts = (caller[NotificationActionReceipt] ?: EmptyCoroutineContext) +
            (caller[PendingMessageCreation] ?: EmptyCoroutineContext)
        ownership.check(operation)
        val task = synchronized(lock) {
            if (ownership.closing) throw MessageServiceException(MessageServiceError.NotConnected)
            ownership.scope.async(context = receipts, start = CoroutineStart.LAZY) {
                suspend fun runOperation(): T {
                    ownership.check(operation)
                    return block()
                }
                if (mutex == null) runOperation() else mutex.withLock { runOperation() }
            }.also { activeOperations += it; it.start() }
        }
        try { return task.await() }
        catch (cancelled: CancellationException) {
            task.cancel()
            withContext(NonCancellable) { task.join() }
            throw cancelled
        } finally { synchronized(lock) { activeOperations.remove(task) } }
    }

    suspend fun startEventMonitoring(): Unit = monitoring.withLock {
        ownership.check("startAckMonitoring")
        if (isEventMonitoringActive) return@withLock
        if (directAckAuthority != null) {
            directLease()
            ownership.check("startAckMonitoring.activated")
        }
        val stream = session.events(EventFilter.anyAcknowledgement)
        val task = ownership.scope.async(start = CoroutineStart.UNDISPATCHED) {
            stream.collect { event ->
                if (!ownership.isCurrent) throw CancellationException("Obsolete ACK monitor")
                if (event is MeshEvent.Acknowledgement) {
                    try { handleAcknowledgement(event.code, event.tripTime) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        if (!ownership.isCurrent) throw failure
                        remember("ackDelivery", failure)
                    }
                }
            }
        }
        synchronized(lock) { listener = task }
        task.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException) {
                remember("ackMonitor", cause)
                events.finish(cause)
            }
        }
        Unit
    }

    suspend fun stopEventMonitoring() = monitoring.withLock {
        val task = synchronized(lock) { listener.also { listener = null } }
        task?.cancelAndJoin()
    }

    suspend fun startAckExpiryChecking(interval: Double = 5.0): Unit = monitoring.withLock {
        require(interval.isFinite() && interval > 0)
        ownership.check("startAckExpiry")
        if (directAckAuthority != null) {
            directLease()
            ownership.check("startAckExpiry.activated")
        }
        val prior = synchronized(lock) { expiry.also { expiry = null; checkInterval = interval } }
        prior?.cancelAndJoin()
        val task = ownership.scope.async {
            while (isActive) {
                clock.sleepFor(interval.seconds)
                ownership.check("ackExpiry")
                try { checkExpiredAcks() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (!ownership.isCurrent) throw failure
                    remember("ackExpiry", failure)
                }
            }
        }
        synchronized(lock) { expiry = task }
        task.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException) remember("ackExpiryTask", cause)
        }
        Unit
    }

    suspend fun stopAckExpiryChecking() = monitoring.withLock {
        val task = synchronized(lock) { expiry.also { expiry = null } }
        task?.cancelAndJoin()
    }

    internal fun pendingAck(messageID: UUID): PendingAck? = synchronized(lock) { pendingAcks[messageID] }
    internal suspend fun installPendingAck(tracking: PendingAck): PendingAck {
        val row = dataStore.fetchMessage(key(tracking.messageID))
        if (row != null && row.status !in listOf(MessageStatus.FAILED, MessageStatus.DELIVERED)) {
            val requestID = UUID.randomUUID()
            val result = authority().captureDirectAckIntent(directLease(), key(row.id), requestID, DirectAckMode.SEND, null)
            val intent = when (result) {
                is DirectAckIntentResult.Captured -> result.intent
                is DirectAckIntentResult.AlreadyCaptured -> result.intent
                is DirectAckIntentResult.Rejected -> throw authorityFailure(result.reason.name)
            }
            tracking.ackCodes.forEachIndexed { index, code ->
                registerDirectDispatch(tracking.messageID, requestID, code, index, intent, correlateResponses = false)
            }
        }
        return synchronized(lock) {
            tracking.copy(authority = directAuthorities[tracking.messageID]?.attempt).also {
                pendingAcks[tracking.messageID] = it
            }
        }
    }
    internal fun claimRetryForTest(messageID: UUID) { synchronized(lock) { inFlightRetries += messageID } }

    internal fun trackPendingAck(
        messageID: UUID, contactID: UUID, ackCode: Bytes, timeout: Double, publicKey: Bytes? = null,
    ): PendingAck = synchronized(lock) {
        require(timeout.isFinite() && timeout >= 0)
        pendingAcks.values.firstOrNull { it.messageID != messageID && ackCode in it.ackCodes }?.let {
            ownership.reporter.report(MessagingDiagnostic.AckCodeCollision(token, messageID, it.messageID))
            throw MessageServiceException(MessageServiceError.SendFailed("Ambiguous acknowledgement identity"))
        }
        val prior = pendingAcks[messageID]
        val value = prior?.copy(
            ackCodes = (prior.ackCodes + listOf(ackCode)).snapshotSet(), sentAt = clock.wallClock.instant(), timeout = timeout,
        ) ?: PendingAck(messageID, contactID, SnapshotSet(listOf(ackCode)), clock.wallClock.instant(), timeout,
            publicKey = publicKey, authority = directAuthorities[messageID]?.attempt)
        pendingAcks[messageID] = value
        value
    }

    internal suspend fun handleAcknowledgement(
        code: Bytes, tripTime: UInt?, expectedAttempt: DirectAckAttempt? = null,
    ) {
        if (!ownership.isCurrent) {
            ownership.reporter.report(MessagingDiagnostic.StaleResult(token, "acknowledgement"))
            return
        }
        checkIdentity("acknowledgement")
        var deferred = false
        val overflows = mutableListOf<UUID>()
        val tracking = synchronized(lock) {
            val found = pendingAcks.values.firstOrNull {
                code in it.ackCodes && !it.isDelivered &&
                    (expectedAttempt == null || it.authority === expectedAttempt) &&
                    directAuthorities[it.messageID]?.takeIf { state -> state.attempt === it.authority }
                        ?.let { state ->
                            !state.correlateResponses || code in state.confirmedCodes
                        } != false
            }
            directAuthorities.forEach { (id, state) ->
                if (found == null && (expectedAttempt == null || state.attempt === expectedAttempt) &&
                    state.correlateResponses && pendingAcks[id]?.authority === state.attempt &&
                    code !in state.confirmedCodes &&
                    !state.acceptedCodes.keys.containsAll(state.predictedCodes.keys)) {
                    deferred = true
                    val acknowledgement = MeshAcknowledgement(code, tripTime)
                    if (!state.acknowledgementBufferOverflow && acknowledgement !in state.bufferedAcknowledgements) {
                        if (state.bufferedAcknowledgements.size == 64) {
                            state.acknowledgementBufferOverflow = true
                            state.bufferedAcknowledgements.clear()
                            overflows += state.requestID
                        } else {
                            state.bufferedAcknowledgements += acknowledgement
                        }
                    }
                }
            }
            found?.copy(isDelivered = true, acknowledgement = MeshAcknowledgement(code, tripTime))?.also {
                pendingAcks[it.messageID] = it
            }
        }
        overflows.forEach {
            remember("ackBuffer.$it", authorityFailure("ACK correlation buffer overflow; early delivery evidence is uncertain"))
        }
        if (tracking == null) {
            if (!deferred) reportUnmatchedAcknowledgement(code)
            return
        }
        reconcileAcknowledgement(tracking.messageID, expectedAttempt = tracking.authority)
    }

    private suspend fun reconcileAcknowledgement(
        messageID: UUID,
        cleanup: Boolean = false,
        failureOperation: String? = null,
        expectedAttempt: DirectAckAttempt? = null,
    ) = mutation(messageID).withLock {
        try {
            reconcileAcknowledgementLocked(messageID, cleanup, expectedAttempt = expectedAttempt)
        } catch (failure: PersistenceStoreException) {
            if (!cleanup && !ownership.isCurrent) throw failure
            if (failureOperation != null) remember(failureOperation, failure)
            throw failure
        }
    }

    private suspend fun reconcileAcknowledgementLocked(
        messageID: UUID, cleanup: Boolean, verifyRadioIdentity: Boolean = true,
        expectedAttempt: DirectAckAttempt? = null,
    ) {
        val tracking = pendingAck(messageID) ?: return
        if (expectedAttempt != null && tracking.authority !== expectedAttempt) return
        val ack = tracking.acknowledgement ?: return
        suspend fun checkOwnership(operation: String) {
            if (cleanup) ownership.checkPersistenceCompletion(operation) else ownership.check(operation)
        }
        if (cleanup) checkOwnership("commitAcknowledgement.cleanup")
        else if (verifyRadioIdentity) checkIdentity("commitAcknowledgement")
        else ownership.check("commitAcknowledgement")
        val row = dataStore.fetchMessage(key(messageID))
        checkOwnership("commitAcknowledgement.read")
        if (row == null) {
            val failure = PersistenceStoreException(PersistenceStoreError.MessageNotFound)
            remember("ack.$messageID", failure)
            retireTerminalAck(tracking)
            throw failure
        }
        if (row.contactID != tracking.contactID || row.radioId != token.radioId) {
            val failure = PersistenceStoreException(PersistenceStoreError.InvalidData)
            remember("ack.$messageID", failure)
            retireTerminalAck(tracking)
            throw failure
        }
        if (tracking.publicKey != null) {
            val contact = dataStore.fetchContact(key(tracking.contactID))
            checkOwnership("commitAcknowledgement.contact")
            if (contact != null && contact.publicKey != tracking.publicKey) {
                val failure = MessageServiceException(MessageServiceError.ContactNotFound)
                remember("ack.$messageID.identity", failure)
                retireTerminalAck(tracking)
                throw failure
            }
        }
        val uncertainWrite = synchronized(lock) {
            uncertainFailureWrites[messageID]?.takeIf { it.tracking.receipt === tracking.receipt }
        }
        val attempt = tracking.authority
        if (attempt == null && row.status in listOf(MessageStatus.FAILED, MessageStatus.DELIVERED)) {
            retireTerminalAck(tracking)
            return
        }
        val result = try {
            when (val settled = radioAuthority().settleDirectAckExcludingRadioHistory(
                attempt ?: throw authorityFailure("unregistered acknowledgement"),
                ack.code.ackCodeUInt32, ack.tripTime,
            )) {
                is DirectAckRadioSettlementResult.Settled -> when (val applied = settled.receipt) {
                    is DirectAckRadioSettlementReceipt.Applied -> DirectAckMutationResult.Applied(applied.receipt)
                    is DirectAckRadioSettlementReceipt.AlreadyApplied -> DirectAckMutationResult.AlreadyApplied(applied.receipt)
                }
                is DirectAckRadioSettlementResult.Nonsettling -> {
                    val uncertainty = DirectAckNonsettlementException(
                        token, messageID, ack.code.ackCodeUInt32, settled.reasons,
                    )
                    synchronized(lock) {
                        pendingAcks[messageID]?.takeIf {
                            it.receipt === tracking.receipt && it.authority === attempt && it.acknowledgement == ack
                        }?.let {
                            pendingAcks[messageID] = it.copy(isDelivered = false, acknowledgement = null)
                        }
                    }
                    ownership.failure("ackAmbiguous.$messageID", uncertainty)
                    return
                }
                is DirectAckRadioSettlementResult.Rejected -> DirectAckMutationResult.Rejected(settled.reason)
            }
        } catch (failure: PersistenceStoreException) {
            if (uncertainWrite != null) {
                remember("ackUncertain.$messageID", uncertainWrite.cause)
                remember("ack.$messageID", uncertainWrite.cause)
                throw uncertainWrite.recoveryFailure
            }
            throw failure
        }
        if (result is DirectAckMutationResult.Rejected && result.reason in retiredAuthorityReasons) {
            if (uncertainWrite != null && row.status != MessageStatus.DELIVERED) {
                remember("ackUncertain.$messageID", uncertainWrite.cause)
                throw uncertainWrite.recoveryFailure
            }
            ownership.reporter.report(MessagingDiagnostic.StaleResult(token, "ackAuthority.${result.reason}"))
            retireTerminalAck(tracking)
            recovered("ack.$messageID")
            recovered("ackUncertain.$messageID")
            recovered("failPending.$messageID")
            recovered("failMessage.$messageID")
            return
        }
        val receipt = mutationReceipt(result)
        checkOwnership("commitAcknowledgement.saved")
        val delivery = receipt.delivery ?: throw authorityFailure("missing committed delivery")
        if (delivery.ackCode != ack.code.ackCodeUInt32 || delivery.roundTripTime != ack.tripTime) {
            throw authorityFailure("delivery tuple mismatch")
        }
        if (receipt.permission != DirectAckPermission.CURRENT) {
            retireTerminalAck(tracking)
            recovered("ack.$messageID")
            recovered("ackUncertain.$messageID")
            return
        }
        if (receipt.message == null || delivery.message == null) {
            throw authorityFailure("current delivery payload erased")
        }
        val committed = dataStore.fetchMessage(key(messageID))
        checkOwnership("commitAcknowledgement.refetched")
        if (committed?.status != MessageStatus.DELIVERED || committed.ackCode != delivery.ackCode ||
            committed.roundTripTime != delivery.roundTripTime) throw authorityFailure("delivery readback changed")
        synchronized(lock) {
            if (pendingAcks[messageID]?.receipt === tracking.receipt) {
                activeDirectClaims[messageID]?.takeIf { directAuthorities[messageID]?.attempt === tracking.authority }
                    ?.acknowledgement = ack
                tracking.receipt.complete(ack)
            }
        }
        if (committed?.status == MessageStatus.DELIVERED) {
            try {
                dataStore.updateContactLastMessage(key(tracking.contactID), clock.wallClock.instant())
                checkOwnership("commitAcknowledgement.contactSaved")
                recovered("ackContact.$messageID")
            } catch (failure: PersistenceStoreException) { remember("ackContact.$messageID", failure) }
            if (!cleanup && ownership.isCurrent) events.yield(MessageStatusEvent.StatusResolved(messageID, MessageStatus.DELIVERED, ack.tripTime))
        }
        synchronized(lock) {
            if (pendingAcks[messageID]?.receipt === tracking.receipt) pendingAcks.remove(messageID)
            if (uncertainFailureWrites[messageID]?.tracking?.receipt === tracking.receipt) uncertainFailureWrites.remove(messageID)
        }
        retireBufferedAcknowledgements(messageID, tracking.authority)
        recovered("ack.$messageID")
        recovered("ackDelivery")
        recovered("failPending.$messageID")
        if (committed?.status == MessageStatus.DELIVERED) {
            recovered("ackUncertain.$messageID")
            recovered("failMessage.$messageID")
            recovered("ackExpiry.$messageID")
        }
        synchronized(lock) { pruneDirectAuthorities() }
    }

    suspend fun checkExpiredAcks() {
        ownership.check("checkExpiredAcks")
        val unresolvedAcceptances = synchronized(lock) {
            directAuthorities.toList().filter { (id, state) ->
                pendingAcks[id]?.authority === state.attempt &&
                    state.acceptedEmissions.values.any { !it.acceptanceRecorded }
            }
        }
        for ((id, state) in unresolvedAcceptances) {
            try {
                val accepted = synchronized(lock) {
                    state.acceptedEmissions.toList().filter { !it.second.acceptanceRecorded }
                }
                for ((wireID, emission) in accepted) {
                    recordDirectAcceptance(state, wireID, emission.info)
                    ownership.check("ackAcceptance.resolved")
                    if (synchronized(lock) {
                        directAuthorities[id] !== state || pendingAcks[id]?.authority !== state.attempt
                    }) break
                    val wireIndex = synchronized(lock) { state.wires.entries.first { it.value == wireID }.key }
                    val predicted = synchronized(lock) { requireNotNull(state.predictedCodes[wireID]) }
                    mergeSentInfo(id, predicted, emission.info, wireIndex.toUByte(), null, state)
                }
                if (synchronized(lock) { directAuthorities[id] === state }) replayConfirmedAcknowledgements(state)
                recovered("ackAcceptance.$id")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!ownership.isCurrent) throw failure
                remember("ackAcceptance.$id", failure)
            }
        }
        val now = clock.wallClock.instant()
        val candidates = synchronized(lock) { pendingAcks.values.toList() }
        for (snapshot in candidates) {
            try {
                expireOrReconcile(snapshot, now)
                recovered("ackExpiry.${snapshot.messageID}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!ownership.isCurrent) throw failure
                if (failure !is PersistenceStoreException) remember("ackExpiry.${snapshot.messageID}", failure)
            }
        }
        recovered("ackExpiry")
    }

    private fun retireTerminalAck(tracking: PendingAck) {
        synchronized(lock) {
            if (pendingAcks[tracking.messageID]?.receipt === tracking.receipt) pendingAcks.remove(tracking.messageID)
            if (uncertainFailureWrites[tracking.messageID]?.tracking?.receipt === tracking.receipt) {
                uncertainFailureWrites.remove(tracking.messageID)
            }
        }
        retireBufferedAcknowledgements(tracking.messageID, tracking.authority)
        synchronized(lock) { pruneDirectAuthorities() }
    }

    private suspend fun expireOrReconcile(snapshot: PendingAck, now: Instant) {
        val failureOperation = "ackExpiry.${snapshot.messageID}"
        if (snapshot.acknowledgement != null) {
            reconcileAcknowledgement(snapshot.messageID, failureOperation = failureOperation)
            return
        }
        if (synchronized(lock) {
            directAuthorities[snapshot.messageID]?.takeIf { it.attempt === snapshot.authority }
                ?.acceptedEmissions?.values?.any { !it.acceptanceRecorded } == true
        }) return
        var acknowledgementArrived = false
        mutation(snapshot.messageID).withLock {
            try {
                val current = pendingAck(snapshot.messageID) ?: return@withLock
                if (current.isDelivered || Duration.between(current.sentAt, now).toNanos().toDouble() / 1e9 <=
                    maxOf(config.ackGiveUpWindow, current.timeout)) return@withLock
                val wasFailed = dataStore.fetchMessage(key(current.messageID))?.status == MessageStatus.FAILED
                ownership.check("expireAcknowledgement")
                val changed = writeFailedStatus(current.messageID, wasFailed) == FailureWrite.CHANGED
                ownership.check("expireAcknowledgement.saved")
                acknowledgementArrived = settleFailedPendingAck(
                    current.messageID, changed, notify = true, retireDirectClaim = true,
                )
            } catch (failure: PersistenceStoreException) {
                if (!ownership.isCurrent) throw failure
                remember(failureOperation, failure)
                throw failure
            }
        }
        if (acknowledgementArrived) reconcileAcknowledgement(snapshot.messageID, failureOperation = failureOperation)
    }

    suspend fun failAllPendingMessages() {
        ownership.check("failAllPendingMessages")
        val ids = synchronized(lock) { pendingAcks.values.filter { !it.isDelivered }.map { it.messageID } }
        var firstFailure: PersistenceStoreException? = null
        for (id in ids) {
            var acknowledgementArrived = false
            mutation(id).withLock {
                ownership.check("failAllPendingMessages.entry")
                try {
                    if (pendingAck(id)?.isDelivered != false) return@withLock
                    val wasFailed = dataStore.fetchMessage(key(id))?.status == MessageStatus.FAILED
                    ownership.check("failAllPendingMessages.read")
                    val changed = writeFailedStatus(id, wasFailed) == FailureWrite.CHANGED
                    ownership.check("failAllPendingMessages.saved")
                    acknowledgementArrived = settleFailedPendingAck(
                        id, changed, notify = true, retireDirectClaim = true,
                    )
                } catch (failure: PersistenceStoreException) {
                    if (!ownership.isCurrent) throw failure
                    remember("failPending.$id", failure)
                    val first = firstFailure
                    if (first == null) firstFailure = failure
                    else if (first !== failure) first.addSuppressed(failure)
                }
            }
            if (acknowledgementArrived) {
                try { reconcileAcknowledgement(id, failureOperation = "failPending.$id") }
                catch (failure: PersistenceStoreException) {
                    if (!ownership.isCurrent) throw failure
                    val first = firstFailure
                    if (first == null) firstFailure = failure
                    else if (first !== failure) first.addSuppressed(failure)
                }
            }
        }
        firstFailure?.let { throw it }
    }

    private suspend fun settleFailedPendingAck(
        messageID: UUID,
        changed: Boolean,
        notify: Boolean,
        retireDirectClaim: Boolean,
        expectedAttempt: DirectAckAttempt? = null,
    ): Boolean {
        var retiredAttempt: DirectAckAttempt? = null
        val acknowledgementArrived = synchronized(lock) {
            val tracking = pendingAcks[messageID]
            if (expectedAttempt != null && tracking?.authority !== expectedAttempt) return false
            if (tracking?.acknowledgement != null) {
                true
            }
            else {
                retiredAttempt = tracking?.authority
                pendingAcks.remove(messageID)
                uncertainFailureWrites.remove(messageID)
                if (retireDirectClaim) {
                    activeDirectClaims[messageID]?.let {
                        if (it.acknowledgement == null) it.retiredWithoutAcknowledgement = true
                    }
                }
                false
            }
        }
        if (!acknowledgementArrived) retireBufferedAcknowledgements(messageID, retiredAttempt)
        if (!acknowledgementArrived) {
            if (notify && changed) broadcastFailurePersistenceChanged(messageID)
            recovered("failPending.$messageID")
            recovered("ackUncertain.$messageID")
        }
        return acknowledgementArrived
    }

    private enum class FailureWrite { CHANGED, UNCHANGED, UNCERTAIN, RETIRED }

    internal suspend fun failQueuedDirectClaim(claimID: UUID, messageID: UUID): Boolean =
        mutation(messageID).withLock {
            ownership.check("failQueuedDirectClaim")
            val state = synchronized(lock) {
                directAuthorities[messageID]?.takeIf { it.requestID == claimID }
            } ?: throw authorityFailure("queued direct failure lacks its current dispatch grant")
            val row = dataStore.fetchMessage(key(messageID))
            ownership.check("failQueuedDirectClaim.read")
            val result = writeFailedStatus(messageID, row?.status == MessageStatus.FAILED, state.attempt)
            if (result == FailureWrite.UNCERTAIN || result == FailureWrite.RETIRED) return@withLock false
            val pendingID = synchronized(lock) {
                directClaims[claimID]?.takeIf { it.messageID == messageID }?.pendingSendID
            } ?: throw authorityFailure("queued direct failure lacks its pending incarnation")
            completeDirectPending(claimID, messageID, pendingID)
            true
        }

    private suspend fun writeFailedStatus(
        messageID: UUID, wasFailed: Boolean, expectedAttempt: DirectAckAttempt? = null,
    ): FailureWrite {
        val tracking = pendingAck(messageID)?.takeIf { expectedAttempt == null || it.authority === expectedAttempt }
        try {
            val attempt = expectedAttempt ?: tracking?.authority ?: synchronized(lock) {
                if (tracking == null) directAuthorities[messageID]?.attempt else null
            }
            if (attempt == null && tracking == null) {
                val row = dataStore.fetchMessage(key(messageID))
                ownership.checkPersistenceCompletion("failMessage.preclaim")
                if (row?.channelIndex != null && row.contactID == null &&
                    synchronized(lock) { messageID !in directIntentMessages }) {
                    reportChannelUncertainty(messageID)
                } else {
                    ownership.failure("preclaimUncertain.$messageID", DirectPreclaimFailureUncertaintyException(
                        token, messageID, DirectPreclaimFailureUncertaintyReason.UNPROVED_FAILURE,
                    ))
                }
                return FailureWrite.UNCERTAIN
            }
            if (attempt == null && wasFailed) return FailureWrite.UNCHANGED
            if (attempt == null && dataStore.fetchMessage(key(messageID))?.status == MessageStatus.DELIVERED) {
                return FailureWrite.UNCHANGED
            }
            val result = when (val guarded = failureAuthority().recordDirectFailureExcludingRadioHistory(
                attempt ?: throw authorityFailure("unregistered failure"),
            )) {
                is DirectAckRadioFailureResult.Applied -> DirectAckMutationResult.Applied(guarded.receipt)
                is DirectAckRadioFailureResult.AlreadyApplied -> DirectAckMutationResult.AlreadyApplied(guarded.receipt)
                is DirectAckRadioFailureResult.AlreadyDelivered -> DirectAckMutationResult.AlreadyDelivered(guarded.receipt)
                is DirectAckRadioFailureResult.Rejected -> DirectAckMutationResult.Rejected(guarded.reason)
                is DirectAckRadioFailureResult.Nonfailing -> {
                    val uncertainty = DirectAckFailureNonsettlementException(token, messageID, guarded.reasons)
                    ownership.failure("ackFailureUncertain.$messageID", uncertainty)
                    return FailureWrite.UNCERTAIN
                }
            }
            if (result is DirectAckMutationResult.Rejected && result.reason in retiredAuthorityReasons) {
                ownership.reporter.report(MessagingDiagnostic.StaleResult(token, "failureAuthority.${result.reason}"))
                tracking?.let(::retireTerminalAck)
                return FailureWrite.RETIRED
            }
            val receipt = requireReceipt(result)
            return if (receipt.message?.status == MessageStatus.FAILED && !wasFailed) {
                FailureWrite.CHANGED
            } else FailureWrite.UNCHANGED
        } catch (cancelled: CancellationException) {
            retainUncertainFailureWrite(messageID, tracking, wasFailed, cancelled)
            throw cancelled
        } catch (failure: PersistenceStoreException) {
            retainUncertainFailureWrite(messageID, tracking, wasFailed, failure)
            throw failure
        }
    }

    private fun retainUncertainFailureWrite(
        messageID: UUID,
        tracking: PendingAck?,
        wasFailed: Boolean,
        failure: Exception,
    ) {
        if (wasFailed || tracking == null) return
        val report = synchronized(lock) {
            val current = pendingAcks[messageID]
            if (current?.receipt !== tracking.receipt) false else {
                uncertainFailureWrites[messageID] = UncertainFailureWrite(tracking, failure)
                failure !is CancellationException || current.acknowledgement != null
            }
        }
        if (report) remember("ackUncertain.$messageID", failure)
    }

    suspend fun stopAndFailAllPending() { stopAckExpiryChecking(); failAllPendingMessages() }

    private fun broadcastFailurePersistenceChanged(id: UUID) {
        if (ownership.isCurrent) {
            events.yield(MessageStatusEvent.PersistenceChanged(key(id)))
        }
    }
    suspend fun notifyMessageFailed(messageID: UUID) {
        ownership.check("notifyMessageFailed")
        broadcastFailurePersistenceChanged(messageID)
    }

    internal suspend fun failMessageAndRethrow(
        failure: Exception,
        messageID: UUID,
        notify: Boolean = false,
        failDefinitelyUnsentInline: Boolean = false,
        failureAttempt: (() -> DirectAckAttempt?)? = null,
        failureRequestID: UUID? = null,
    ): Nothing {
        // Complete only the already-persisted send's failure before teardown returns, never a successor's work.
        val notificationReceipt = currentCoroutineContext()[NotificationActionReceipt]
        withContext(NonCancellable) {
            mutation(messageID).withLock {
                try {
                    val expectedAttempt = failureAttempt?.invoke()
                    val superseded = failureAttempt != null && synchronized(lock) {
                        if (expectedAttempt != null) directAuthorities[messageID]?.attempt !== expectedAttempt
                        else directAuthorities[messageID]?.let { it.requestID != failureRequestID } == true
                    }
                    if (superseded) {
                        ownership.reporter.report(MessagingDiagnostic.StaleResult(token, "inlineFailureAuthority"))
                        return@withLock
                    }
                    if (failureAttempt != null && expectedAttempt == null &&
                        synchronized(lock) { messageID in directIntentMessages }) {
                        throw authorityFailure("own dispatch intent has no observed failure grant")
                    }
                    val failUnsentInlineRow = notificationReceipt == null || failDefinitelyUnsentInline
                    val awaitingAcceptedEvidence = synchronized(lock) {
                        directAuthorities[messageID]?.takeIf {
                            expectedAttempt == null || it.attempt === expectedAttempt
                        }?.acceptedEmissions?.values?.any { !it.resolutionCommitted } == true
                    }
                    if (!awaitingAcceptedEvidence &&
                        (notificationReceipt == null || notificationReceipt.state == NotificationActionReceipt.State.READY)) {
                        if (failUnsentInlineRow) {
                            if (pendingAck(messageID)?.acknowledgement == null) {
                                val wasFailed = dataStore.fetchMessage(key(messageID))?.status == MessageStatus.FAILED
                                ownership.checkPersistenceCompletion("failMessage")
                                val changed = writeFailedStatus(messageID, wasFailed, expectedAttempt) == FailureWrite.CHANGED
                                ownership.checkPersistenceCompletion("failMessage.saved")
                                val acknowledgementArrived = settleFailedPendingAck(
                                    messageID, changed, notify, retireDirectClaim = false, expectedAttempt = expectedAttempt,
                                )
                                if (acknowledgementArrived) {
                                    reconcileAcknowledgementLocked(
                                        messageID, cleanup = !ownership.isCurrent, verifyRadioIdentity = false,
                                        expectedAttempt = expectedAttempt,
                                    )
                                }
                            }
                        } else {
                            synchronized(lock) {
                                val current = pendingAcks[messageID]
                                if (current?.acknowledgement == null &&
                                    (expectedAttempt == null || current?.authority === expectedAttempt)) pendingAcks.remove(messageID)
                            }
                        }
                    }
                } catch (storage: PersistenceStoreException) {
                    remember("failMessage.$messageID", storage)
                    if (storage !== failure) failure.addSuppressed(storage)
                } catch (stale: MessageServiceException) {
                    remember("failMessage.$messageID", stale)
                    if (stale !== failure) failure.addSuppressed(stale)
                }
            }
        }
        if (failure is MeshCoreException) throw MessageServiceException(MessageServiceError.SessionError(failure))
        throw failure
    }

    private suspend fun <T> sendBoundary(
        id: UUID,
        notify: Boolean,
        failDefinitelyUnsentInline: Boolean = false,
        failureAttempt: (() -> DirectAckAttempt?)? = null,
        failureRequestID: UUID? = null,
        block: suspend () -> T,
    ): T =
        try { block() }
        catch (cancelled: CancellationException) { failMessageAndRethrow(cancelled, id, notify, failDefinitelyUnsentInline, failureAttempt, failureRequestID) }
        catch (failure: MeshCoreException) { failMessageAndRethrow(failure, id, notify, failDefinitelyUnsentInline, failureAttempt, failureRequestID) }
        catch (failure: MeshTransportError) { failMessageAndRethrow(failure, id, notify, failDefinitelyUnsentInline, failureAttempt, failureRequestID) }
        catch (failure: MessageServiceException) { failMessageAndRethrow(failure, id, notify, failDefinitelyUnsentInline, failureAttempt, failureRequestID) }
        catch (failure: PersistenceStoreException) { failMessageAndRethrow(failure, id, notify, failDefinitelyUnsentInline, failureAttempt, failureRequestID) }

    private fun validate(text: String, contact: ContactDTO) {
        if (contact.radioId != token.radioId) throw MessageServiceException(MessageServiceError.ContactNotFound)
        if (contact.type == ContactType.REPEATER) throw MessageServiceException(MessageServiceError.InvalidRecipient)
        if (Bytes.utf8(text).size > ProtocolLimits.MAX_DIRECT_MESSAGE_LENGTH) throw MessageServiceException(MessageServiceError.MessageTooLong)
    }
    private fun timestamp(): UInt {
        val seconds = clock.wallClock.instant().epochSecond
        if (seconds !in 0..UInt.MAX_VALUE.toLong()) throw MeshCoreException.InvalidInput("Phone-clock timestamp exceeds UInt32")
        return seconds.toUInt()
    }
    private fun outgoing(text: String, contact: ContactDTO, type: TextType, replyToID: UUID?) = MessageDTO(
        id = newID(), radioId = token.radioId, contactID = contact.id, text = text, timestamp = timestamp(),
        createdAt = clock.wallClock.instant(), textType = type, replyToID = replyToID,
    )
    private suspend fun savePendingCreation(message: MessageDTO) {
        val receipt = currentCoroutineContext()[PendingMessageCreation]
        receipt?.writing(message)
        try {
            dataStore.saveMessage(message)
        } catch (failure: Exception) {
            // The write has settled, but a thrown return does not prove rollback. Only read this
            // operation's exact process-store key; no wire work or successor-generation mutation.
            if (receipt != null) withContext(NonCancellable) {
                try {
                    val stored = dataStore.fetchMessage(key(message.id))
                    when {
                        stored == null -> receipt.settled(PendingMessageCreation.State.UNKNOWN)
                        stored == message -> receipt.committed(stored)
                        else -> {
                            receipt.settled(PendingMessageCreation.State.UNKNOWN)
                            remember("pendingCreation.${message.id}.identity",
                                PersistenceStoreException(PersistenceStoreError.InvalidData))
                        }
                    }
                } catch (readFailure: Exception) {
                    receipt.settled(PendingMessageCreation.State.UNKNOWN)
                    remember("pendingCreation.${message.id}.read", readFailure)
                    if (failure !== readFailure) failure.addSuppressed(readFailure)
                }
            }
            throw failure
        }
        if (receipt?.committed(message) == false) {
            val failure = PersistenceStoreException(PersistenceStoreError.InvalidData)
            remember("pendingCreation.${message.id}.identity", failure)
            throw failure
        }
    }

    private suspend fun <T> withFreshNotificationAction(operation: String, block: suspend () -> T): T {
        val receipt = currentCoroutineContext()[NotificationActionReceipt] ?: return block()
        val claimed = synchronized(notificationActionLock) {
            receipt.state == NotificationActionReceipt.State.READY && notificationActionClaims.add(receipt)
        }
        if (!claimed) {
            throw MessageServiceException(
                MessageServiceError.SendFailed("$operation cannot replay or overlap a notification action"),
            )
        }
        try {
            return block()
        } finally {
            synchronized(notificationActionLock) { notificationActionClaims.remove(receipt) }
        }
    }

    override suspend fun createPendingMessage(text: String, contact: ContactDTO, textType: TextType, replyToID: UUID?): MessageDTO =
        owned("createPendingMessage", pendingOperations) {
            validate(text, contact)
            val message = outgoing(text, contact, textType, replyToID)
            savePendingCreation(message)
            ownership.check("createPendingMessage.saved")
            dataStore.updateContactLastMessage(key(contact.id), message.date)
            ownership.check("createPendingMessage.contact")
            message
        }

    private suspend fun <T> notificationWire(
        deferWireStart: Boolean = false, block: suspend (MessageWriteDispatch?) -> T,
    ): T {
        val receipt = currentCoroutineContext()[NotificationActionReceipt]
        if (receipt == null) return block(MessageWriteDispatch())
        val dispatch = MessageWriteDispatch()
        val ownsOutcome = receipt.state == NotificationActionReceipt.State.READY
        if (ownsOutcome && !deferWireStart) receipt.wireStarted()
        try {
            val result = block(dispatch)
            if (ownsOutcome) receipt.accepted()
            return result
        } catch (cancelled: CancellationException) {
            if (ownsOutcome && receipt.state == NotificationActionReceipt.State.IN_FLIGHT) {
                if (dispatch.cancellationDisposition == ChannelWriteCancellationDisposition.DEFINITELY_UNSENT) {
                    receipt.wireNotDispatched()
                } else {
                    receipt.uncertain()
                }
            }
            throw cancelled
        } catch (rejected: MeshCoreException.DeviceError) {
            if (ownsOutcome) receipt.wireRejected()
            throw rejected
        } catch (failure: Exception) {
            if (ownsOutcome) receipt.uncertain()
            throw failure
        }
    }

    private suspend fun <T> inlineWire(block: suspend (MessageWriteDispatch?) -> T): T =
        notificationWire(block = block)

    override suspend fun sendDirectMessage(text: String, contact: ContactDTO, textType: TextType, replyToID: UUID?): MessageDTO =
        withFreshNotificationAction("sendDirectMessage") {
            owned("sendDirectMessage") {
                validate(text, contact)
                requireDirectDispatchAuthorities()
                val message = outgoing(text, contact, textType, replyToID)
                val requestID = UUID.randomUUID()
                withInlineDirectRequest(requestID) {
                    var registered: Pair<DirectAuthorityState, UUID>? = null
                    val (info, tracking, predicted) = sendBoundary(
                        message.id, true, failDefinitelyUnsentInline = true,
                        failureAttempt = { registered?.first?.attempt },
                        failureRequestID = requestID,
                    ) {
                        dataStore.saveMessage(message)
                        ownership.check("sendDirectMessage.saved")
                        checkIdentity("sendDirectMessage")
                        val prepared = prepareDirect(message.id, requestID, contact, DirectAckMode.SEND, null)
                        val captured = prepared.message
                        val selected = prepared.contact
                        val predicted = AckCodeBuilder.expectedAck(captured.timestamp, 0u, captured.text, localPublicKey)
                        val registration = registerDirectDispatch(message.id, requestID, predicted, 0, prepared.intent,
                            correlateResponses = true)
                        registered = registration
                        val tracking = trackPendingAck(message.id, selected.id, predicted, checkInterval, selected.publicKey)
                        val info = withPoolBackoff(3u) {
                            dmOperations.withLock {
                                checkIdentity("sendDirectMessage.wire")
                                notificationWire(deferWireStart = true) { dispatch ->
                                    val timestamp = Instant.ofEpochSecond(captured.timestamp.toLong())
                                    directWire(registration.first, registration.second, dispatch) {
                                        if (dispatch == null) session.sendMessage(selected.publicKey, captured.text, timestamp)
                                        else session.sendMessage(selected.publicKey, captured.text, timestamp, dispatch = dispatch)
                                    }
                                }
                            }
                        }
                        mergeSentInfo(message.id, predicted, info, 0u, null, registration.first)
                        Triple(info, tracking, predicted)
                    }
                    val (state, wireID) = requireNotNull(registered)
                    postDMSent(message.id, contact.id, info, state, wireID, tracking)
                    ownership.check("sendDirectMessage.accepted")
                    if (synchronized(lock) { directAuthorities[message.id] !== state }) {
                        throw authorityFailure("inline attempt superseded")
                    }
                    if (pendingAck(message.id)?.receipt === tracking.receipt) {
                        mergeSentInfo(message.id, predicted, info, 0u, null, state)
                    }
                    fetchSaved(message.id)
                }
            }
        }

    override suspend fun sendMessageWithRetry(
        text: String, contact: ContactDTO, textType: TextType, replyToID: UUID?, timeout: Double,
        onMessageCreated: (suspend (MessageDTO) -> Unit)?,
    ): MessageDTO {
        require(timeout.isFinite() && timeout >= 0)
        validate(text, contact)
        if (config.maxAttempts > 0) requireDirectDispatchAuthorities()
        val message = owned("saveMessageWithRetry", pendingOperations) {
            outgoing(text, contact, textType, replyToID).also {
                dataStore.saveMessage(it)
                ownership.check("sendMessageWithRetry.saved")
            }
        }
        // A consumer may send again or close the generation; it owns neither the wire lease nor this service's job.
        onMessageCreated?.invoke(message)
        ownership.check("sendMessageWithRetry.created")
        return owned("sendMessageWithRetry") {
            val current = fetchSaved(message.id)
            if (current.contactID != contact.id) throw MessageServiceException(MessageServiceError.ContactNotFound)
            if (config.maxAttempts == 0L) {
                return@owned sendBoundary(message.id, true) {
                    finalizeSend(message.id, contact.id, contact.publicKey, null, contact.outPathLength, noDispatch = true)
                }
            }
            val claim = newDirectClaim(
                UUID.randomUUID(), message, contact, false, initialStatus = current.status,
            )
            try {
                sendBoundary(message.id, true) {
                    val info = retryLoop(claim, claim.contact, requireNotNull(claim.timestamp), timeout.takeIf { it > 0 })
                    finalizeSend(message.id, contact.id, contact.publicKey, info, contact.outPathLength)
                }
            } finally { releaseDirectClaim(claim.id) }
        }
    }

    override suspend fun sendPendingDirectMessage(messageID: UUID, contact: ContactDTO, preserveTimestamp: Boolean): MessageDTO =
        ephemeralQueuedDM(messageID, contact, preserveTimestamp, false)
    override suspend fun resendDirectMessage(messageID: UUID, contact: ContactDTO, preserveTimestamp: Boolean): MessageDTO =
        ephemeralQueuedDM(messageID, contact, preserveTimestamp, true)

    private suspend fun ephemeralQueuedDM(id: UUID, contact: ContactDTO, preserveTimestamp: Boolean, isResend: Boolean): MessageDTO {
        val claimID = UUID.randomUUID()
        try { return queuedDM(id, contact, preserveTimestamp, isResend, claimID) }
        finally { releaseDirectClaim(claimID) }
    }

    internal suspend fun sendPendingClaim(
        claimID: UUID, messageID: UUID, contact: ContactDTO, preserveTimestamp: Boolean, isResend: Boolean,
        pendingSendID: UUID = claimID,
    ): MessageDTO = queuedDM(messageID, contact, preserveTimestamp, isResend, claimID, pendingSendID)

    internal fun releaseDirectClaim(claimID: UUID) {
        synchronized(lock) {
            noDispatchCompletions.remove(claimID)
            directRegistrationIDs.remove(claimID)
            directClaims.remove(claimID)?.let { claim ->
                if (activeDirectClaims[claim.messageID] === claim) activeDirectClaims.remove(claim.messageID)
            }
            pruneDirectAuthorities()
        }
    }

    private suspend fun newDirectClaim(
        claimID: UUID, row: MessageDTO, contact: ContactDTO, resend: Boolean, pendingSendID: UUID? = null,
        initialStatus: MessageStatus = row.status,
    ): DirectSendClaim {
        synchronized(lock) {
            directClaims[claimID]?.let {
                if (it.messageID != row.id || it.contactID != contact.id || it.publicKey != contact.publicKey ||
                    it.isResend != resend || it.pendingSendID != pendingSendID) {
                    throw MessageServiceException(MessageServiceError.SendFailed("Pending-send claim identity changed"))
                }
                return it
            }
        }
        val mode = if (resend || initialStatus in listOf(MessageStatus.FAILED, MessageStatus.DELIVERED)) {
            DirectAckMode.RESEND
        } else DirectAckMode.SEND
        if (config.maxAttempts > 0) checkIdentity("retryDirectMessage")
        val prepared = prepareDirect(row.id, claimID, contact, mode, pendingSendID, resend)
        return DirectSendClaim(
            claimID, row.id, prepared.contact.id, prepared.contact.publicKey, prepared.message.text,
            resend, pendingSendID, mode, prepared.intent, prepared.contact,
        ).also {
            it.timestamp = prepared.message.timestamp
            synchronized(lock) {
                directClaims[claimID] = it
                activeDirectClaims[row.id] = it
            }
        }
    }

    private suspend fun queuedDM(
        id: UUID, contact: ContactDTO, preserveTimestamp: Boolean, isResend: Boolean, claimID: UUID,
        pendingSendID: UUID? = null,
    ): MessageDTO {
        synchronized(lock) {
            if (!inFlightRetries.add(id)) throw MessageServiceException(MessageServiceError.SendFailed("Retry already in progress"))
        }
        try {
            return withFreshNotificationAction("queuedDirectMessage") {
                owned("queuedDirectMessage") {
                    val row = fetchSaved(id)
                    if (row.contactID != contact.id || contact.radioId != token.radioId) {
                        throw MessageServiceException(MessageServiceError.ContactNotFound)
                    }
                    validate(row.text, contact)
                    if (!isResend && row.status == MessageStatus.DELIVERED) {
                        rememberNoDispatch(claimID, id, pendingSendID, DirectPendingCompletionReason.AlreadyDeliveredSend)
                        return@owned row
                    }
                    if (config.maxAttempts == 0L) {
                        return@owned sendBoundary(id, false) {
                            if (!preserveTimestamp) {
                                dataStore.updateMessageTimestamp(key(id), timestamp())
                                ownership.check("queuedDirectMessage.timestamp")
                            }
                            val result = finalizeSend(id, contact.id, contact.publicKey, null, contact.outPathLength, noDispatch = true)
                            rememberNoDispatch(claimID, id, pendingSendID, DirectPendingCompletionReason.ZeroAttempts(0L))
                            result
                        }
                    }
                    val retained = synchronized(lock) { directClaims[claimID] }
                    if (retained == null && !preserveTimestamp) {
                        timestamp().also {
                            dataStore.updateMessageTimestamp(key(id), it)
                            ownership.check("queuedDirectMessage.timestamp")
                        }
                    }
                    val claim = newDirectClaim(claimID, row, contact, isResend, pendingSendID)
                    val wireTimestamp = requireNotNull(claim.timestamp)
                    sendBoundary(id, false) {
                        val info = if (synchronized(lock) { claim.wireFinished }) {
                            synchronized(lock) { claim.lastSentInfo }
                        } else retryLoop(claim, claim.contact, wireTimestamp, null).also {
                            synchronized(lock) { claim.lastSentInfo = it; claim.wireFinished = true }
                        }
                        if (isResend && info != null && !synchronized(lock) { claim.sendCountCommitted }) {
                            try {
                                directBookkeeping(id, "resendCount", DirectAckBookkeeping.ResendCountIncrement)
                                synchronized(lock) { claim.sendCountCommitted = true }
                                ownership.check("queuedDirectMessage.sendCount")
                                recovered("dmSendCount.$id")
                            } catch (failure: PersistenceStoreException) {
                                remember("dmSendCount.$id", failure)
                                throw failure
                            }
                        }
                        val result = finalizeSend(id, contact.id, contact.publicKey, info, contact.outPathLength)
                        if (isResend && info != null && synchronized(lock) { !claim.resentPublished }) {
                            events.yield(MessageStatusEvent.Resent(id))
                            synchronized(lock) { claim.resentPublished = true }
                        }
                        result
                    }
                }
            }
        } finally { synchronized(lock) { inFlightRetries.remove(id) } }
    }

    private fun completedDirect(claim: DirectSendClaim): MessageSentInfo? = synchronized(lock) {
        if (claim.acknowledgement != null) claim.lastSentInfo else null
    }
    private fun unacknowledgedRetirement(claim: DirectSendClaim): Boolean = synchronized(lock) {
        claim.acknowledgement == null && claim.retiredWithoutAcknowledgement
    }

    private suspend fun retryLoop(claim: DirectSendClaim, contact: ContactDTO, stamp: UInt, timeout: Double?): MessageSentInfo? {
        val id = claim.messageID
        val text = claim.text
        var attempts = synchronized(lock) { claim.nextWireIndex }
        var floods = synchronized(lock) { claim.floodAttempts }
        var flood = synchronized(lock) { claim.usingFlood }
        while (attempts < config.maxAttempts && (!flood || floods < config.maxFloodAttempts)) {
            checkIdentity("retryDirectMessage")
            val uncommittedAck = synchronized(lock) {
                pendingAcks[id]?.takeIf {
                    it.acknowledgement != null && directAuthorities[id]?.requestID == claim.id
                }
            }
            if (uncommittedAck != null) {
                reconcileAcknowledgement(id, expectedAttempt = uncommittedAck.authority)
            }
            synchronized(lock) { claim.acceptedWire }?.let {
                recordDirectAcceptance(directAuthority(id), it.requestID, it.info)
            }
            completedDirect(claim)?.let { return it }
            if (unacknowledgedRetirement(claim)) return null
            if (attempts > 0) {
                directBookkeeping(id, "retry.$attempts", DirectAckBookkeeping.RetryStarted(attempts - 1, config.maxAttempts - 1))
                ownership.check("retryDirectMessage.status")
                completedDirect(claim)?.let { return it }
                if (unacknowledgedRetirement(claim)) return null
                events.yield(MessageStatusEvent.Retrying(id, attempts - 1, config.maxAttempts - 1))
            }
            if (attempts == config.floodAfter && !flood) {
                try {
                    session.resetPath(contact.publicKey)
                    ownership.check("retryDirectMessage.resetPath")
                    completedDirect(claim)?.let { return it }
                    if (unacknowledgedRetirement(claim)) return null
                    session.getContact(contact.publicKey)?.let {
                        ownership.check("retryDirectMessage.contact")
                        completedDirect(claim)?.let { return it }
                        if (unacknowledgedRetirement(claim)) return null
                        dataStore.saveContact(token.radioId, it.toFrame())
                        ownership.check("retryDirectMessage.contactSaved")
                        completedDirect(claim)?.let { return it }
                        if (unacknowledgedRetirement(claim)) return null
                    }
                    events.yield(MessageStatusEvent.RoutingChanged(contact.id, true))
                } catch (failure: MeshCoreException) { remember("resetPath.$id", failure) }
                catch (failure: MeshTransportError) { remember("resetPath.$id", failure) }
                catch (failure: PersistenceStoreException) { remember("resetPath.$id", failure) }
                flood = true
                synchronized(lock) { claim.usingFlood = true }
            }
            completedDirect(claim)?.let { return it }
            if (unacknowledgedRetirement(claim)) return null
            val attempt = attempts.toUByte()
            val predicted = AckCodeBuilder.expectedAck(stamp, attempt, text, localPublicKey)
            val (authorityState, wireID) = registerDirectDispatch(
                id, claim.id, predicted, attempts.toInt(),
                claim.intent,
                correlateResponses = true,
            )
            val tracking = trackPendingAck(id, contact.id, predicted, maxOf(timeout ?: config.minTimeout, checkInterval), contact.publicKey)
            val mailbox = Channel<MeshEvent.Acknowledgement>(Channel.UNLIMITED)
            val stream = session.events(EventFilter.anyAcknowledgement)
            val collector = ownership.scope.async(start = CoroutineStart.UNDISPATCHED) {
                stream.collect { if (it is MeshEvent.Acknowledgement) mailbox.send(it) }
            }
            try {
                val info = synchronized(lock) { claim.acceptedWire }?.info ?: withPoolBackoff(3u) {
                    dmOperations.withLock {
                        checkIdentity("retryDirectMessage.wire")
                        if (unacknowledgedRetirement(claim)) null
                        else completedDirect(claim) ?: notificationWire(deferWireStart = true) { dispatch ->
                            val timestamp = Instant.ofEpochSecond(stamp.toLong())
                            directWire(authorityState, wireID, dispatch) {
                                if (dispatch == null) {
                                    session.sendMessage(contact.publicKey.prefix(6), text, timestamp, attempt)
                                } else {
                                    session.sendMessage(contact.publicKey.prefix(6), text, timestamp, attempt, dispatch)
                                }
                            }
                        }
                    }
                } ?: return null
                synchronized(lock) {
                    claim.lastSentInfo = info
                    claim.acceptedWire = AcceptedDirectWire(wireID, info)
                }
                mergeSentInfo(id, predicted, info, attempt, timeout, authorityState)
                recordDirectAcceptance(authorityState, wireID, info)
                replayConfirmedAcknowledgements(authorityState)
                ownership.check("retryDirectMessage.accepted")
                completedDirect(claim)?.let { return it }
                if (unacknowledgedRetirement(claim)) return null
                val ackTimeout = timeout ?: maxOf(config.minTimeout, info.suggestedTimeoutMs.toDouble() / 1000.0 * 1.2)
                val outcome = clock.messagingDeadline(ackTimeout) {
                    while (true) {
                        val ack = select<MeshAcknowledgement?> {
                            // This exact tracker receipt is already correlated, even after its map entry retires.
                            tracking.receipt.onAwait { null }
                            mailbox.onReceive { MeshAcknowledgement(it.code, it.tripTime) }
                        }
                        ownership.check("retryDirectMessage.ack")
                        if (ack == null) return@messagingDeadline true
                        if (ack.code in (pendingAck(id)?.ackCodes ?: tracking.ackCodes)) {
                            handleAcknowledgement(ack.code, ack.tripTime)
                            if (tracking.receipt.isCompleted) return@messagingDeadline true
                        }
                    }
                }
                if (outcome is DeadlineOutcome.Value) {
                    completedDirect(claim)?.let { return it }
                }
                if (pendingAck(id)?.let {
                    it.authority === authorityState.attempt && it.acknowledgement != null
                } == true) {
                    reconcileAcknowledgement(id, expectedAttempt = authorityState.attempt)
                }
                completedDirect(claim)?.let { return it }
                if (unacknowledgedRetirement(claim)) return null
            } finally {
                collector.cancelAndJoin()
                mailbox.cancel()
            }
            attempts++
            if (flood) floods++
            synchronized(lock) {
                claim.nextWireIndex = attempts
                claim.floodAttempts = floods
                claim.acceptedWire = null
            }
        }
        return null
    }

    private fun mergeSentInfo(
        id: UUID, predicted: Bytes, info: MessageSentInfo, attempt: UByte, timeout: Double?,
        state: DirectAuthorityState,
    ) {
        val seconds = timeout ?: maxOf(config.minTimeout, info.suggestedTimeoutMs.toDouble() / 1000.0 * 1.2)
        if (info.expectedAck != predicted) {
            ownership.reporter.report(MessagingDiagnostic.AckCodeMismatch(token, id, attempt))
        }
        synchronized(lock) {
            val current = pendingAcks[id] ?: return
            if (current.authority !== state.attempt) return
            if (info.expectedAck != predicted) {
                pendingAcks.values.firstOrNull { it.messageID != id && info.expectedAck in it.ackCodes }?.let {
                    ownership.reporter.report(MessagingDiagnostic.AckCodeCollision(token, id, it.messageID))
                    throw MessageServiceException(MessageServiceError.SendFailed("Ambiguous acknowledgement identity"))
                }
            }
            pendingAcks[id] = current.copy(
                ackCodes = (state.predictedCodes.map { (wireID, code) ->
                    state.acceptedCodes[wireID] ?: if (wireID == state.wires[attempt.toInt()]) info.expectedAck else code
                } + listOf(info.expectedAck)).snapshotSet(),
                sentAt = clock.wallClock.instant(), timeout = seconds,
            )
        }
    }

    internal suspend fun finalizeSend(
        messageID: UUID, contactID: UUID, publicKey: Bytes, sentInfo: MessageSentInfo?, initialPathLength: UByte,
        noDispatch: Boolean = false,
    ): MessageDTO {
        ownership.check("finalizeSend")
        val tracking = pendingAck(messageID)
        if (tracking?.acknowledgement != null) reconcileAcknowledgement(messageID)
        else mutation(messageID).withLock {
            ownership.check("finalizeSend.transition")
            val current = pendingAck(messageID)
            if (current?.acknowledgement != null) reconcileAcknowledgementLocked(messageID, cleanup = false)
            else if (current == null || current.isDelivered) synchronized(lock) { pendingAcks.remove(messageID) }
            else if (sentInfo != null) {
                throw authorityFailure("send acceptance is not recipient acknowledgement")
            } else {
                if (noDispatch) {
                    val changed = dataStore.clearRetryingToSent(key(messageID))
                    ownership.check("finalizeSend.noDispatch")
                    if (changed) events.yield(MessageStatusEvent.StatusResolved(messageID, MessageStatus.SENT, null))
                    return@withLock
                }
                if (synchronized(lock) { directAuthorities[messageID] } == null &&
                    dataStore.fetchMessage(key(messageID))?.status in listOf(MessageStatus.FAILED, MessageStatus.DELIVERED)) {
                    return@withLock
                }
                val receipt = directBookkeeping(messageID, "retryExhausted", DirectAckBookkeeping.RetryExhausted)
                ownership.check("finalizeSend.sent")
                if (receipt.message?.status == MessageStatus.SENT) {
                    events.yield(MessageStatusEvent.StatusResolved(messageID, MessageStatus.SENT, null))
                }
            }
        }
        checkRouting(messageID, contactID, publicKey, initialPathLength)
        return fetchSaved(messageID)
    }

    private suspend fun checkRouting(id: UUID, contactID: UUID, publicKey: Bytes, initialPathLength: UByte) {
        try {
            ownership.check("checkRouting")
            val contact = session.getContact(publicKey) ?: return
            ownership.check("checkRouting.read")
            if (contact.publicKey != publicKey) throw MeshCoreException.InvalidResponse("original contact", "different public key")
            if (contact.outPathLength != initialPathLength) {
                dataStore.saveContact(token.radioId, contact.toFrame())
                ownership.check("checkRouting.saved")
                events.yield(MessageStatusEvent.RoutingChanged(contactID, contact.outPathLength == 255.toUByte()))
            }
            recovered("routing.$id")
        } catch (failure: MeshCoreException) { remember("routing.$id", failure) }
        catch (failure: MeshTransportError) { remember("routing.$id", failure) }
        catch (failure: PersistenceStoreException) { remember("routing.$id", failure) }
    }

    private suspend fun fetchSaved(id: UUID): MessageDTO {
        ownership.check("fetchSaved")
        val row = dataStore.fetchMessage(key(id))
        ownership.check("fetchSaved.read")
        return row ?: throw MessageServiceException(MessageServiceError.SendFailed("Message not found"))
    }

    private suspend fun postDMSent(
        id: UUID, contactID: UUID, info: MessageSentInfo, state: DirectAuthorityState, wireID: UUID,
        tracking: PendingAck,
    ) = withContext(NonCancellable) {
        bookkeeping("dmSent.$id") {
            mutation(id).withLock {
                ownership.checkPersistenceCompletion("dmSent")
                recordDirectAcceptance(state, wireID, info)
            }
            if (ownership.isCurrent) replayConfirmedAcknowledgements(state)
            mutation(id).withLock {
                ownership.checkPersistenceCompletion("dmSent.completion")
                directBookkeeping(state, "sendCompleted.$wireID", DirectAckBookkeeping.SendCompleted(wireID))
                ownership.checkPersistenceCompletion("dmSent.saved")
                if (synchronized(lock) { directAuthorities[id] !== state }) throw authorityFailure("inline attempt superseded")
                dataStore.updateContactLastMessage(key(contactID), clock.wallClock.instant())
                ownership.checkPersistenceCompletion("dmSent.contact")
                val row = dataStore.fetchMessage(key(id))
                ownership.checkPersistenceCompletion("dmSent.refetched")
                if (ownership.isCurrent && row?.status == MessageStatus.SENT) {
                    events.yield(MessageStatusEvent.StatusResolved(id, MessageStatus.SENT, null))
                }
            }
            if (ownership.isCurrent && pendingAck(id)?.takeIf { it.receipt === tracking.receipt }?.acknowledgement != null) {
                bookkeeping("ack.$id") { reconcileAcknowledgement(id, expectedAttempt = state.attempt) }
            }
        }
    }

    private suspend fun rejectChannelText(text: String, radioId: RadioId) {
        if (radioId != token.radioId) throw MessageServiceException(MessageServiceError.ChannelNotFound)
        val device = dataStore.fetchDevice(radioId)
        ownership.check("channelText.device")
        val nameBytes = device?.nodeName?.let { Bytes.utf8(it).size.toLong() } ?: ProtocolLimits.MAX_USABLE_NAME_BYTES.toLong()
        if (Bytes.utf8(text).size.toLong() > ProtocolLimits.maxChannelMessageLength(nameBytes)) {
            throw MessageServiceException(MessageServiceError.MessageTooLong)
        }
    }

    private suspend fun outgoingChannel(text: String, channelIndex: UByte, radioId: RadioId, textType: TextType): MessageDTO {
        ownership.check("createPendingChannel")
        rejectChannelText(text, radioId)
        return MessageDTO(newID(), radioId, channelIndex = channelIndex, text = text, timestamp = timestamp(),
            createdAt = clock.wallClock.instant(), textType = textType)
    }

    override suspend fun createPendingChannelMessage(text: String, channelIndex: UByte, radioId: RadioId, textType: TextType): MessageDTO =
        owned("createPendingChannel", pendingOperations) {
            val dto = outgoingChannel(text, channelIndex, radioId, textType)
            savePendingCreation(dto)
            ownership.check("createPendingChannel.saved")
            dto
        }

    override suspend fun sendChannelMessage(text: String, channelIndex: UByte, radioId: RadioId, textType: TextType): ChannelSendReceipt =
        withFreshNotificationAction("sendChannelMessage") {
            owned("sendChannelMessage", channelOperations) {
                val dto = outgoingChannel(text, channelIndex, radioId, textType)
                val claimID = UUID.randomUUID()
                var retainAccepted = false
                try {
                    channelCompletionAuthority()
                    lateinit var claim: ChannelClaim
                    channelBoundary(claimID, dto.id) {
                        claim = capturedChannel(claimID, dto.id, null,
                            channelAuthority().createChannelFailureAttempt(sessionLease(channel = true), claimID, dto))
                        ownership.check("sendChannelMessage.persisted")
                        withPoolBackoff(2u) {
                            channelTransmission(requireNotNull(claim.message.channelIndex)) {
                                checkIdentity("sendChannelMessage.wire")
                                inlineWire { dispatch ->
                                    val timestamp = Instant.ofEpochSecond(claim.message.timestamp.toLong())
                                    if (dispatch == null) session.sendChannelMessage(channelIndex, claim.message.text, timestamp)
                                    else session.sendChannelMessage(channelIndex, claim.message.text, timestamp, dispatch = dispatch)
                                    synchronized(lock) { claim.acceptedTimestamp = claim.message.timestamp }
                                    retainAccepted = true
                                }
                            }
                        }
                    }
                    ownership.check("sendChannelMessage.accepted")
                    when (completeAcceptedChannel(claim)) {
                        is ChannelSendOutcome.Completed -> {
                            retainAccepted = false
                            bookkeeping("channelDate.${dto.id}") { updateChannelDate(channelIndex) }
                            ChannelSendReceipt(dto.id, dto.timestamp)
                        }
                        ChannelSendOutcome.Retired -> {
                            retainAccepted = false
                            throw channelAuthorityFailure("accepted inline completion retired; original work retained")
                        }
                    }
                } finally { if (!retainAccepted) releaseChannelClaim(claimID) }
            }
        }

    override suspend fun sendPendingChannelMessage(messageID: UUID) { queuedChannel(messageID, false, true) }
    override suspend fun resendChannelMessage(messageID: UUID, preserveTimestamp: Boolean): UInt =
        queuedChannel(messageID, true, preserveTimestamp)

    private suspend fun queuedChannel(id: UUID, isResend: Boolean, preserveTimestamp: Boolean): UInt {
        val claimID = UUID.randomUUID()
        var completed = false
        try {
            return when (val result = sendPendingChannelClaim(claimID, id, null, isResend, preserveTimestamp)) {
                is ChannelSendOutcome.Completed -> result.timestamp.also { completed = true }
                ChannelSendOutcome.Retired -> {
                    completed = true
                    throw channelAuthorityFailure("accepted completion retired; original work retained")
                }
            }
        } finally { if (completed || !channelClaimAccepted(claimID)) releaseChannelClaim(claimID) }
    }

    internal suspend fun sendPendingChannelClaim(
        claimID: UUID, id: UUID, pendingID: UUID?, isResend: Boolean, preserveTimestamp: Boolean,
    ): ChannelSendOutcome =
        owned("queuedChannel", channelOperations) {
            channelCompletionAuthority()
            if (pendingID != null) queueAdmissionAuthority()
            val mode = if (isResend) ChannelSendCompletionMode.RESEND else ChannelSendCompletionMode.SEND
            if (synchronized(lock) {
                claimID !in channelClaims && channelClaims.values.any {
                    it.message.id == id && it.acceptedTimestamp != null && !it.retired
                }
            }) throw channelAuthorityFailure("accepted invocation requires its original capture and completion request")
            val claim = channelBoundary(claimID, id) {
                synchronized(lock) { channelClaims[claimID] } ?: capturedChannel(claimID, id, pendingID,
                    channelAuthority().prepareChannelFailureAttempt(sessionLease(channel = true), claimID, key(id), pendingID,
                        if (isResend && !preserveTimestamp) timestamp() else null), mode)
            }
            if (claim.message.id != id || claim.pendingID != pendingID || claim.mode != mode) {
                throw channelAuthorityFailure("changed channel invocation arguments")
            }
            val row = claim.message
            val index = requireNotNull(row.channelIndex)
            val accepted = synchronized(lock) { claim.acceptedTimestamp }
            val stamp = accepted ?: row.timestamp
            if (accepted == null) {
                channelBoundary(claimID, id) {
                    ownership.check("queuedChannel.timestamp")
                    withPoolBackoff(2u) {
                        channelTransmission(index) {
                            checkIdentity("queuedChannel.wire")
                            session.sendChannelMessage(index, row.text, Instant.ofEpochSecond(stamp.toLong()))
                            synchronized(lock) { claim.acceptedTimestamp = stamp }
                            noteChannelAcceptance(claim)
                        }
                    }
                }
                ownership.check("queuedChannel.accepted")
            }
            val outcome = completeAcceptedChannel(claim)
            if (!isResend && outcome is ChannelSendOutcome.Completed) {
                bookkeeping("channelDate.$id") { updateChannelDate(index) }
            }
            outcome
        }

    private suspend fun updateChannelDate(index: UByte) {
        dataStore.fetchChannel(token.radioId, index)?.let {
            ownership.check("channelDate.read")
            dataStore.updateChannelLastMessage(key(it.id), clock.wallClock.instant())
            ownership.check("channelDate.saved")
        }
    }

    private suspend fun <T> bookkeeping(operation: String, block: suspend () -> T) {
        try { block(); recovered(operation) }
        catch (failure: PersistenceStoreException) { remember(operation, failure) }
    }

    private suspend fun <T> withPoolBackoff(code: UByte, block: suspend () -> T): T {
        var attempt = 0L
        while (true) {
            try { return block() }
            catch (failure: MeshCoreException.DeviceError) {
                if (failure.code != code || attempt >= config.poolBackoff.attemptCap) throw failure
                val factor = jitter(config.poolBackoff.jitterRange)
                require(factor in config.poolBackoff.jitterRange) { "Jitter escaped its declared envelope" }
                val delay = config.poolBackoff.baseDelay * config.poolBackoff.exponentBase.pow(attempt.toDouble()) * factor
                require(delay.isFinite() && delay >= 0 && delay * 1000 <= Long.MAX_VALUE)
                clock.sleepFor((delay * 1000).toLong().milliseconds)
                attempt++
            }
        }
    }

    suspend fun close(): TeardownReport {
        val (receipt, claimed) = synchronized(lock) {
            teardown?.let { it to false } ?: CompletableDeferred<TeardownReport>().also {
                teardown = it
                ownership.invalidate()
            }.let { it to true }
        }
        if (!claimed) return withContext(NonCancellable) { receipt.await() }
        return withContext(NonCancellable) {
            val work = synchronized(lock) { activeOperations.toList() }
            work.forEach { it.cancel() }
            work.forEach { it.join() }
            stopEventMonitoring()
            stopAckExpiryChecking()
            val deliveries = synchronized(lock) { pendingAcks.values.filter { it.acknowledgement != null }.map { it.messageID } }
            for (id in deliveries) {
                try { reconcileAcknowledgement(id, cleanup = true) }
                catch (failure: PersistenceStoreException) { remember("ack.$id", failure) }
                catch (failure: MessageServiceException) { remember("ack.$id.identity", failure) }
            }
            if (authorityActivationStarted) {
                try {
                    val result = if (authorityActivatedThroughChannel) {
                        channelAuthority().revokeChannelFailureSession(sessionLease(channel = true))
                    } else authority().revokeDirectAckSession(directLease())
                    when (result) {
                        DirectAckRevocationResult.Revoked, DirectAckRevocationResult.AlreadyRevoked -> Unit
                        is DirectAckRevocationResult.Rejected -> throw authorityFailure(result.reason.name)
                    }
                } catch (failure: PersistenceStoreException) { remember("directAckRevoke", failure) }
            }
            synchronized(lock) {
                pendingAcks.values.forEach { it.receipt.cancel() }
                pendingAcks.clear()
                directAuthorities.clear()
                inFlightDirectEmissions.clear()
                inlineDirectRequests.clear()
                directRegistrationIDs.clear()
                noDispatchCompletions.clear()
                uncertainFailureWrites.clear()
                directClaims.clear()
                activeDirectClaims.clear()
                channelClaims.clear()
                channelAdmissionTimestamps.clear()
            }
            events.finish()
            ownership.job.cancelAndJoin()
            val report = TeardownReport(synchronized(lock) {
                retainedFailures.values.map { TeardownIssue(LifecycleStage.STOP_SERVICES, it) }.snapshot()
            })
            receipt.complete(report)
            report
        }
    }

    private companion object {
        private val retiredAuthorityReasons = setOf(
            DirectAckRejection.INVALIDATED, DirectAckRejection.STALE_LEASE, DirectAckRejection.MISSING,
        )
        // A caller receipt must have one active top-level owner across service instances.
        private val notificationActionLock = Any()
        private val notificationActionClaims = mutableSetOf<NotificationActionReceipt>()
    }
}

private fun MeshContact.toFrame(): ContactFrame = ContactFrame(
    publicKey, type, flags.rawValue, outPathLength, outPath, advertisedName,
    lastAdvertisement.contactTimestamp(), latitude, longitude, lastModified.contactTimestamp(), typeRawValue,
)

private fun Instant.contactTimestamp(): UInt {
    if (epochSecond !in 0..UInt.MAX_VALUE.toLong()) throw MeshCoreException.InvalidInput("Contact timestamp exceeds UInt32")
    return epochSecond.toUInt()
}
