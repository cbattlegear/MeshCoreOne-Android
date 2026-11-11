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
import com.meshcoreone.android.core.services.contacts.ChannelDispatchFailure
import com.meshcoreone.android.core.services.contacts.ChannelDispatchFence
import java.time.Duration
import java.time.Instant
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

internal class QueuedChannelRetainedException(val pendingID: UUID, cause: Throwable) :
    Exception("Queued channel submission retained without automatic dispatch", cause)

internal class StaleDirectQueueSelectionException(val reason: DirectAckRejection) :
    Exception("Queued direct selection is no longer current: $reason")

internal class UnresolvedDirectQueueException(detail: String) :
    Exception("Queued direct outcome is unresolved: $detail")

internal enum class DirectClaimRegistration { NONE, REGISTERED, DEFINITELY_UNSENT }

private sealed interface ChannelPrewireResult {
    data class Accepted(val attempt: QueuedChannelAttempt) : ChannelPrewireResult
    data class Rejected(val failure: PersistenceStoreException) : ChannelPrewireResult
    data class ReadFailed(val failure: PersistenceStoreException) : ChannelPrewireResult
    data class CancelledUnsent(val cancellation: CancellationException) : ChannelPrewireResult
    data class CancelledAccepted(val cancellation: CancellationException) : ChannelPrewireResult
}

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
    private val channelDispatchLease: ChannelDispatchFence.Lease? = null,
    private val directQueueAuthority: DirectQueueAuthority? = directAckAuthority as? DirectQueueAuthority,
    private val directMessageCreationAuthority: DirectMessageCreationAuthority? =
        directAckAuthority as? DirectMessageCreationAuthority,
) : MessagingSendPort {
    init {
        require(localPublicKey.size == ProtocolLimits.PUBLIC_KEY_SIZE)
        channelDispatchLease?.let {
            require(it.token == token && it.connection === session) { "Channel dispatch requires the actual connection lease" }
        }
    }
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
    private class DirectAuthorityState(
        val messageID: UUID,
        val requestID: UUID,
        val attempt: DirectAckAttempt,
        firstWireID: UUID,
        val registrationEpoch: Long,
        val correlateResponses: Boolean,
    ) {
        val wires = mutableMapOf(0 to firstWireID)
        val operations = mutableMapOf<String, UUID>()
        val predictedCodes = mutableMapOf<UUID, Bytes>()
        val acceptedCodes = mutableMapOf<UUID, Bytes>()
        val confirmedCodes = mutableSetOf<Bytes>()
        val bufferedAcknowledgements = mutableListOf<MeshAcknowledgement>()
        var definitelyUnsentWire: UUID? = null
        var acknowledgementBufferOverflow = false
    }
    private data class DirectWireObservation(
        val claimID: UUID,
        val predicted: Bytes,
        var returned: Bytes? = null,
    )
    private val directAuthorities = mutableMapOf<UUID, DirectAuthorityState>()
    private val directRegistrationEpochs = mutableMapOf<UUID, Long>()
    private val directWireObservations = mutableMapOf<UUID, MutableMap<UUID, DirectWireObservation>>()
    private val directIntentMessages = mutableSetOf<UUID>()
    private val directRegistrationIDs = mutableMapOf<UUID, UUID>()
    private class NoDispatchCompletion(
        val messageID: UUID,
        val pendingID: UUID,
        val reason: DirectPendingCompletionReason,
        val captureID: UUID = UUID.randomUUID(),
        val completionID: UUID = UUID.randomUUID(),
        var handle: DirectPendingCompletion? = null,
        var selection: DirectQueueSelection? = null,
        var statusPublished: Boolean = false,
    )
    private val noDispatchCompletions = mutableMapOf<UUID, NoDispatchCompletion>()
    private data class QueuePreparation(val timestamp: UInt?, val resolveToSent: Boolean)
    private val queuePreparations = mutableMapOf<UUID, QueuePreparation>()
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
    private val failedNotifications = mutableSetOf<UUID>()
    private val retiredChannelNotifications = mutableSetOf<QueuedChannelAttemptKey>()
    private val events = MessagingEvents<MessageStatusEvent>(token)
    private var listener: Deferred<Unit>? = null
    private var expiry: Deferred<Unit>? = null
    private var teardown: CompletableDeferred<TeardownReport>? = null
    private var checkInterval = 5.0

    val pendingAckCount: Int get() = synchronized(lock) { pendingAcks.size }
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

    private fun queueAuthority(): DirectQueueAuthority =
        directQueueAuthority ?: throw authorityFailure("queued direct capability unavailable")

    private fun creationAuthority(): DirectMessageCreationAuthority =
        directMessageCreationAuthority ?: throw authorityFailure("inline direct creation capability unavailable")

    internal suspend fun captureDirectQueueSelection(id: UUID, pendingID: UUID): DirectQueueCaptureResult =
        queueAuthority().captureDirectQueueSelection(directLease(), key(id), pendingID)

    internal fun compareDirectQueueSelections(
        first: DirectQueueSelection, second: DirectQueueSelection,
    ): DirectQueueIncarnationComparison = queueAuthority().compareDirectQueueSelections(first, second)

    internal suspend fun bumpDirectQueueSelection(
        selection: DirectQueueSelection, requestID: UUID,
    ): DirectQueueBumpResult = queueAuthority().bumpDirectQueueSelection(selection, requestID)

    internal suspend fun mutateDirectQueueSelection(
        selection: DirectQueueSelection, requestID: UUID, mutation: DirectQueueMutation,
    ): DirectQueueMutationResult = queueAuthority().mutateDirectQueueSelection(selection, requestID, mutation)

    internal suspend fun retireUnbegunDirectIntent(claimID: UUID, messageID: UUID) {
        if (retireDirectIntent(directLease(), messageID, claimID) == DirectAckIntentRetirement.AttemptBegun) {
            throw UnresolvedDirectQueueException("registered direct attempt has no safe retry disposition")
        }
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

    private suspend fun retireDirectIntent(
        lease: DirectAckLease, messageID: UUID, requestID: UUID,
    ): DirectAckIntentRetirement {
        val result = try { authority().retireDirectAckIntent(lease, requestID) }
        catch (failure: PersistenceStoreException) {
            try { authority().retireDirectAckIntent(lease, requestID) }
            catch (cancelled: CancellationException) {
                cancelled.addSuppressed(failure)
                throw cancelled
            }
            catch (retry: Exception) {
                failure.addSuppressed(retry)
                throw failure
            }
        }
        if (result is DirectAckIntentRetirement.Rejected) throw authorityFailure("intent retirement: ${result.reason}")
        if (result != DirectAckIntentRetirement.AttemptBegun && ownership.isCurrent &&
            pendingAck(messageID)?.acknowledgement != null) {
            try { bookkeeping("ackAfterIntent.$messageID") { reconcileAcknowledgement(messageID) } }
            catch (failure: MessageServiceException) { remember("ackAfterIntent.$messageID", failure) }
        }
        return result
    }

    private suspend fun retireIntentAfterFailure(
        lease: DirectAckLease?, messageID: UUID, requestID: UUID, failure: Exception,
    ): DirectAckIntentRetirement? = try {
        withContext(NonCancellable) { retireDirectIntent(lease ?: directLease(), messageID, requestID) }
    } catch (cleanup: Exception) {
        remember("directIntentRetained.$requestID", cleanup)
        failure.addSuppressed(cleanup)
        null
    }

    private suspend fun prepareDirect(
        id: UUID, requestID: UUID, contact: ContactDTO, mode: DirectAckMode, pendingSendID: UUID?,
        isResend: Boolean = false, selection: DirectQueueSelection? = null,
        creation: DirectMessageCreation? = null, retryCreationMessage: MessageDTO? = null,
    ): PreparedDirect {
        synchronized(lock) { directIntentMessages += id }
        require(selection == null || creation == null)
        require(retryCreationMessage == null || creation != null)
        val lease = directLease()
        val result = try {
            when {
                creation != null && retryCreationMessage != null ->
                    creationAuthority().captureCreatedDirectRetryIntent(creation, mode)
                creation != null -> creationAuthority().captureCreatedDirectIntent(creation)
                selection != null -> queueAuthority().captureDirectQueueIntent(selection, requestID, mode)
                else -> authority().captureDirectAckIntent(lease, key(id), requestID, mode, pendingSendID)
            }
        } catch (failure: Exception) {
            retireIntentAfterFailure(lease, id, requestID, failure)
            throw failure
        }
        val (intent, receipt) = when (result) {
            is DirectAckIntentResult.Captured -> result.intent to result.receipt
            is DirectAckIntentResult.AlreadyCaptured -> result.intent to result.receipt
            is DirectAckIntentResult.Rejected -> {
                val failure = when {
                    selection != null && result.reason == DirectAckRejection.UNRESOLVED_OUTCOME ->
                        UnresolvedDirectQueueException(result.reason.name)
                    selection != null && result.reason in listOf(DirectAckRejection.MISSING, DirectAckRejection.INVALIDATED) ->
                        StaleDirectQueueSelectionException(result.reason)
                    else -> authorityFailure(result.reason.name)
                }
                retireIntentAfterFailure(lease, id, requestID, failure)
                throw failure
            }
        }
        return try {
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
            if (retryCreationMessage != null &&
                (message.text != retryCreationMessage.text || message.timestamp != retryCreationMessage.timestamp)) {
                throw authorityFailure("retry creation payload changed")
            }
            val selected = dataStore.fetchContact(key(requireNotNull(message.contactID)))
                ?: throw MessageServiceException(MessageServiceError.ContactNotFound)
            ownership.check("directIntent.contact")
            if (selected.publicKey != contact.publicKey) throw MessageServiceException(MessageServiceError.ContactNotFound)
            validate(message.text, selected)
            PreparedDirect(intent, message, selected)
        } catch (failure: Exception) {
            retireIntentAfterFailure(lease, id, requestID, failure)
            throw failure
        }
    }

    private suspend fun directLease(): DirectAckLease = authorityActivation.withLock {
        authorityLease ?: run {
            authorityActivationStarted = true
            when (val result = authority().activateDirectAckSession(token, activationRequestID)) {
            is DirectAckActivationResult.Activated -> result.lease
            is DirectAckActivationResult.AlreadyActivated -> result.lease
            is DirectAckActivationResult.Rejected -> throw authorityFailure(result.reason.name)
            }
        }.also { authorityLease = it }
    }

    private fun directAuthority(id: UUID, requestID: UUID? = null): DirectAuthorityState = synchronized(lock) {
        val state = directAuthorities[id] ?: throw authorityFailure("unregistered message")
        if (requestID != null &&
            (state.requestID != requestID || directRegistrationEpochs[id] != state.registrationEpoch)
        ) throw authorityFailure("direct registration superseded")
        state
    }

    private fun requireCurrentDirectAuthority(state: DirectAuthorityState) = synchronized(lock) {
        if (directAuthorities[state.messageID] !== state ||
            directRegistrationEpochs[state.messageID] != state.registrationEpoch
        ) throw authorityFailure("direct registration superseded")
    }

    private suspend fun registerDirectDispatch(
        id: UUID, requestID: UUID, predicted: Bytes, wireIndex: Int,
        intent: DirectAckIntent,
        correlateResponses: Boolean = true,
    ): Pair<DirectAuthorityState, UUID> {
        val prior = synchronized(lock) { directAuthorities[id] }
        if (prior?.requestID == requestID) {
            requireCurrentDirectAuthority(prior)
            val wireID = synchronized(lock) {
                prior.wires.getOrPut(wireIndex, UUID::randomUUID).also {
                    directWireObservations.getOrPut(id, ::mutableMapOf).putIfAbsent(it, DirectWireObservation(requestID, predicted))
                }
            }
            requireReceipt(authority().registerDirectAckDispatch(prior.attempt, wireID, predicted.ackCodeUInt32))
            synchronized(lock) {
                requireCurrentDirectAuthority(prior)
                prior.predictedCodes[wireID] = predicted
            }
            return prior to wireID
        }
        val (wireID, epoch) = synchronized(lock) {
            val nextEpoch = Math.incrementExact(directRegistrationEpochs[id] ?: 0L)
            directRegistrationEpochs[id] = nextEpoch
            val wire = directRegistrationIDs.getOrPut(requestID, UUID::randomUUID).also {
                directWireObservations.getOrPut(id, ::mutableMapOf).putIfAbsent(it, DirectWireObservation(requestID, predicted))
            }
            wire to nextEpoch
        }
        fun restorePriorEpoch(retired: DirectAckIntentRetirement?) {
            if (retired != null && retired != DirectAckIntentRetirement.AttemptBegun) synchronized(lock) {
                if (directRegistrationEpochs[id] == epoch && directAuthorities[id] === prior) {
                    if (prior == null) directRegistrationEpochs.remove(id)
                    else directRegistrationEpochs[id] = prior.registrationEpoch
                }
            }
        }
        val result = try {
            authority().beginDirectAckAttempt(intent, wireID, predicted.ackCodeUInt32)
        } catch (failure: Exception) {
            restorePriorEpoch(retireIntentAfterFailure(null, id, requestID, failure))
            throw failure
        }
        val (attempt, receipt) = when (result) {
            is DirectAckBeginResult.Begun -> result.attempt to result.receipt
            is DirectAckBeginResult.AlreadyBegun -> result.attempt to result.receipt
            is DirectAckBeginResult.Rejected -> {
                val failure = authorityFailure(result.reason.name)
                restorePriorEpoch(retireIntentAfterFailure(null, id, requestID, failure))
                throw failure
            }
        }
        if (receipt.permission != DirectAckPermission.CURRENT) throw authorityFailure(receipt.permission.name)
        val state = DirectAuthorityState(id, requestID, attempt, wireID, epoch, correlateResponses)
        synchronized(lock) {
            if (directRegistrationEpochs[id] != epoch) throw authorityFailure("direct registration superseded")
            state.predictedCodes[wireID] = predicted
            directAuthorities[id] = state
            pendingAcks.remove(id)?.receipt?.cancel()
        }
        return state to wireID
    }

    private suspend fun recordDirectAcceptance(state: DirectAuthorityState, wireID: UUID, info: MessageSentInfo) {
        synchronized(lock) { directWireObservations[state.messageID]?.get(wireID)?.returned = info.expectedAck }
        requireCurrentDirectAuthority(state)
        val receipt = requireReceipt(authority().recordDirectSendAccepted(state.attempt, wireID, info.expectedAck.ackCodeUInt32))
        if (receipt.wires.none { it.requestId == wireID && it.acceptedAckCode == info.expectedAck.ackCodeUInt32 }) {
            throw authorityFailure("accepted wire binding missing")
        }
        synchronized(lock) {
            state.acceptedCodes[wireID] = info.expectedAck
            state.confirmedCodes += info.expectedAck
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
        requireCurrentDirectAuthority(state)
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
        val (state, superseded) = synchronized(lock) {
            val current = directAuthorities[messageID]
            current?.takeIf { it.requestID == claimID } to
                (current != null && current.requestID != claimID && claimID !in noDispatchCompletions)
        }
        if (state == null) {
            if (superseded) throw StaleDirectQueueSelectionException(DirectAckRejection.INVALIDATED)
            completeWithoutDispatch(claimID, messageID, pendingSendID)
            return
        }
        val requestID = synchronized(lock) { state.operations.getOrPut("pendingCompletion", UUID::randomUUID) }
        val result = authority().completeDirectPending(state.attempt, pendingSendID, requestID)
        if (result is DirectAckMutationResult.Rejected) rejectStaleQueueCompletion(result.reason)
        val receipt = mutationReceipt(result)
        when (receipt.permission) {
            DirectAckPermission.INVALIDATED -> throw StaleDirectQueueSelectionException(DirectAckRejection.INVALIDATED)
            DirectAckPermission.MISSING -> throw StaleDirectQueueSelectionException(DirectAckRejection.MISSING)
            DirectAckPermission.STALE_LEASE -> throw authorityFailure("pending completion has stale lease")
            DirectAckPermission.CURRENT -> Unit
        }
        if (receipt.pendingCompletions.none { it.requestId == requestID && it.pendingSendId == pendingSendID }) {
            throw authorityFailure("pending completion receipt missing")
        }
        ownership.check("completeDirectPending.saved")
    }

    private fun rejectStaleQueueCompletion(reason: DirectAckRejection): Nothing {
        if (reason == DirectAckRejection.UNRESOLVED_OUTCOME) throw UnresolvedDirectQueueException(reason.name)
        if (reason == DirectAckRejection.MISSING || reason == DirectAckRejection.INVALIDATED) {
            throw StaleDirectQueueSelectionException(reason)
        }
        throw authorityFailure(reason.name)
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
            val result = state.selection?.let {
                queueAuthority().captureDirectQueueCompletion(it, state.captureID, state.reason)
            } ?: producer.captureDirectPendingCompletion(
                directLease(), key(messageID), pendingSendID, state.captureID, state.reason,
            )
            val (captured, receipt) = when (result) {
                is DirectPendingCaptureResult.Captured -> result.completion to result.receipt
                is DirectPendingCaptureResult.AlreadyCaptured -> result.completion to result.receipt
                is DirectPendingCaptureResult.Rejected -> rejectStaleQueueCompletion(result.reason)
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
            is DirectPendingCompletionResult.Rejected -> rejectStaleQueueCompletion(result.reason)
        }
        if (receipt.captureRequestId != state.captureID || receipt.completedRequestId != state.completionID) {
            throw authorityFailure("no-dispatch completion receipt missing")
        }
        ownership.check("completeDirectPending.saved")
    }

    private fun rememberNoDispatch(
        claimID: UUID, messageID: UUID, pendingID: UUID?, reason: DirectPendingCompletionReason,
    ): NoDispatchCompletion? {
        if (pendingID == null) return null
        return synchronized(lock) {
            val state = noDispatchCompletions.getOrPut(claimID) { NoDispatchCompletion(messageID, pendingID, reason) }
            if (state.messageID != messageID || state.pendingID != pendingID || state.reason != reason) {
                throw authorityFailure("no-dispatch decision changed")
            }
            state
        }
    }

    internal fun definitelyUndispatchedDirectClaim(claimID: UUID, messageID: UUID, pendingID: UUID): Boolean =
        synchronized(lock) {
            noDispatchCompletions[claimID]?.let { it.messageID == messageID && it.pendingID == pendingID } == true
        }

    internal suspend fun directClaimRegistration(claimID: UUID, messageID: UUID): DirectClaimRegistration =
        when (val observation = authority().inspectDirectAckAttempt(directLease(), claimID)) {
            is DirectAckObservation.Found -> {
                if (observation.receipt.key != key(messageID) || observation.receipt.requestId != claimID) {
                    throw authorityFailure("registered claim identity mismatch")
                }
                val unsent = observation.receipt.definitelyUnsentWireRequestId
                if (unsent == null) DirectClaimRegistration.REGISTERED
                else {
                    val state = synchronized(lock) {
                        directAuthorities[messageID]?.takeIf { it.requestID == claimID }
                    }
                    retireBufferedAcknowledgements(messageID, state?.attempt)
                    val tracking = synchronized(lock) {
                        directWireObservations[messageID]?.remove(unsent)
                        directRegistrationIDs.remove(claimID)
                        if (state != null && directAuthorities[messageID] === state) {
                            directAuthorities.remove(messageID)
                            pendingAcks[messageID]?.takeIf { it.authority === state.attempt }?.also {
                                pendingAcks.remove(messageID)
                            }
                        } else null
                    }
                    tracking?.receipt?.cancel(CancellationException("Direct wire was definitely not dispatched"))
                    DirectClaimRegistration.DEFINITELY_UNSENT
                }
            }
            is DirectAckObservation.Rejected -> {
                if (observation.reason != DirectAckRejection.MISSING) {
                    throw authorityFailure("registered claim inspection: ${observation.reason}")
                }
                DirectClaimRegistration.NONE
            }
        }

    internal suspend fun restoreInterruptedDirectRetry(claimID: UUID, messageID: UUID) {
        ownership.checkPersistenceCompletion("interruptedDirectRetry")
        val state = directAuthority(messageID, claimID)
        val observation = authority().inspectDirectAckAttempt(directLease(), claimID)
        val receipt = (observation as? DirectAckObservation.Found)?.receipt
            ?: throw authorityFailure("interrupted direct claim missing")
        if (receipt.permission != DirectAckPermission.CURRENT) {
            throw authorityFailure("interrupted direct claim is ${receipt.permission}")
        }
        if (receipt.message?.status == MessageStatus.RETRYING) {
            directBookkeeping(state, "interruptedRetry", DirectAckBookkeeping.ReturnToPending)
        }
        ownership.checkPersistenceCompletion("interruptedDirectRetry.saved")
    }

    internal suspend fun detachRetiredTrackingForQueuedSuccessor(messageID: UUID, claimID: UUID) {
        val state = synchronized(lock) {
            directAuthorities[messageID]?.takeIf { it.requestID != claimID }
        } ?: return
        when (val observation = authority().inspectDirectAckAttempt(directLease(), state.requestID)) {
            is DirectAckObservation.Found -> {
                if (observation.receipt.key != key(messageID) || observation.receipt.requestId != state.requestID) {
                    throw authorityFailure("predecessor claim identity mismatch")
                }
                if (observation.receipt.permission == DirectAckPermission.CURRENT) {
                    throw UnresolvedDirectQueueException("predecessor direct claim remains current")
                }
            }
            is DirectAckObservation.Rejected -> {
                if (observation.reason != DirectAckRejection.MISSING) {
                    throw authorityFailure("predecessor claim inspection: ${observation.reason}")
                }
            }
        }
        retireBufferedAcknowledgements(messageID, state.attempt)
        val tracking = synchronized(lock) {
            if (directAuthorities[messageID] !== state) null
            else {
                directAuthorities.remove(messageID)
                pendingAcks[messageID]?.takeIf { it.authority === state.attempt }?.also {
                    pendingAcks.remove(messageID)
                }
            }
        }
        tracking?.receipt?.cancel(CancellationException("Direct claim superseded by a queued successor"))
    }

    private suspend fun retireDefinitelyUnsentWire(
        state: DirectAuthorityState, wireID: UUID, cause: Exception, queued: Boolean = true,
    ): Boolean = withContext(NonCancellable) {
        val requestID = synchronized(lock) {
            state.operations.getOrPut("definitelyUnsent.$wireID", UUID::randomUUID)
        }
        try {
            suspend fun record(): DirectAckMutationResult = if (queued) {
                authority().retireDefinitelyUnsentQueuedAttempt(state.attempt, wireID, requestID)
            } else {
                authority().recordDefinitelyUnsentInlineWire(state.attempt, wireID, requestID)
            }
            val result = try {
                record()
            } catch (first: PersistenceStoreException) {
                try { record() }
                catch (second: PersistenceStoreException) {
                    if (first !== second) first.addSuppressed(second)
                    throw first
                }
            }
            val receipt = mutationReceipt(result)
            if (receipt.key != key(state.messageID) || receipt.requestId != state.requestID ||
                receipt.definitelyUnsentWireRequestId != wireID ||
                (receipt.permission == DirectAckPermission.CURRENT) == queued
            ) throw authorityFailure("invalid definitely-unsent retirement receipt")
            if (!queued) {
                synchronized(lock) {
                    state.definitelyUnsentWire = wireID
                    if (directWireObservations[state.messageID]?.get(wireID)?.claimID == state.requestID) {
                        directWireObservations[state.messageID]?.remove(wireID)
                    }
                }
                retireBufferedAcknowledgements(state.messageID, state.attempt)
            }
            true
        } catch (failure: PersistenceStoreException) {
            if (cause !== failure) cause.addSuppressed(failure)
            remember("directUnsentRetirement.${state.requestID}", failure)
            false
        }
    }

    private suspend fun captureQueuedNoDispatch(
        selection: DirectQueueSelection, state: NoDispatchCompletion,
    ): MessageDTO {
        state.selection = selection
        val result = queueAuthority().captureDirectQueueCompletion(selection, state.captureID, state.reason)
        val (handle, receipt) = when (result) {
            is DirectPendingCaptureResult.Captured -> result.completion to result.receipt
            is DirectPendingCaptureResult.AlreadyCaptured -> result.completion to result.receipt
            is DirectPendingCaptureResult.Rejected -> rejectStaleQueueCompletion(result.reason)
        }
        val message = receipt.message
        if (receipt.permission != DirectPendingCompletionPermission.CURRENT || receipt.key != key(state.messageID) ||
            message == null || receipt.pendingSendId != state.pendingID || receipt.pending?.id != state.pendingID ||
            receipt.reason != state.reason) {
            throw authorityFailure("queued no-dispatch capture is not current or matching")
        }
        state.handle = handle
        return message
    }

    private suspend fun prepareQueuedSelection(
        selection: DirectQueueSelection, requestID: UUID, claimID: UUID, preserveTimestamp: Boolean,
        resolveToSent: Boolean,
    ): DirectQueuePreparationReceipt {
        val preparation = synchronized(lock) {
            queuePreparations.getOrPut(claimID) {
                QueuePreparation(if (preserveTimestamp) null else timestamp(), resolveToSent)
            }
        }
        return when (val result = queueAuthority().prepareDirectQueueSelection(
            selection, requestID, preparation.timestamp, preparation.resolveToSent,
        )) {
            is DirectQueuePreparationResult.Applied -> result.receipt
            is DirectQueuePreparationResult.AlreadyApplied -> result.receipt
            is DirectQueuePreparationResult.Rejected -> rejectStaleQueueCompletion(result.reason)
        }
    }

    private suspend fun zeroAttemptStatusTarget(id: UUID): PendingAck? {
        if (pendingAck(id)?.acknowledgement != null) {
            reconcileAcknowledgement(id)
            return null
        }
        return mutation(id).withLock {
            ownership.check("finalizeSend.transition")
            val current = pendingAck(id)
            when {
                current?.acknowledgement != null -> {
                    reconcileAcknowledgementLocked(id, cleanup = false)
                    null
                }
                current == null || current.isDelivered -> {
                    synchronized(lock) {
                        if (current != null && pendingAcks[id] === current) pendingAcks.remove(id)
                    }
                    null
                }
                else -> current
            }
        }
    }

    private suspend fun zeroAttemptNeedsStatusResolution(id: UUID): Boolean = zeroAttemptStatusTarget(id) != null

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
        expectedAttempt: DirectAckAttempt? = null,
    ): PendingAck = synchronized(lock) {
        require(timeout.isFinite() && timeout >= 0)
        if (expectedAttempt != null) {
            val state = directAuthorities[messageID]
            if (state == null || state.attempt !== expectedAttempt ||
                directRegistrationEpochs[messageID] != state.registrationEpoch
            ) {
                throw authorityFailure("direct acknowledgement registration superseded")
            }
        }
        pendingAcks.values.firstOrNull { it.messageID != messageID && ackCode in it.ackCodes }?.let {
            ownership.reporter.report(MessagingDiagnostic.AckCodeCollision(token, messageID, it.messageID))
            throw MessageServiceException(MessageServiceError.SendFailed("Ambiguous acknowledgement identity"))
        }
        val prior = pendingAcks[messageID]
        if (expectedAttempt != null && prior != null && prior.authority !== expectedAttempt) {
            throw authorityFailure("direct acknowledgement tracking superseded")
        }
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
            fun ambiguous(state: DirectAuthorityState): Boolean =
                directWireObservations[state.messageID]?.values?.any {
                    it.claimID != state.requestID && (it.predicted == code || it.returned == code)
                } == true
            fun knownForCurrentWire(state: DirectAuthorityState): Boolean = code in state.confirmedCodes
            val found = pendingAcks.values.firstOrNull {
                code in it.ackCodes && !it.isDelivered &&
                    (expectedAttempt == null || it.authority === expectedAttempt) &&
                    directAuthorities[it.messageID]?.takeIf { state -> state.attempt === it.authority }
                        ?.let { state -> !state.correlateResponses || !ambiguous(state) && knownForCurrentWire(state) } != false
            }
            directAuthorities.forEach { (id, state) ->
                if (found == null && (expectedAttempt == null || state.attempt === expectedAttempt) &&
                    state.correlateResponses && pendingAcks[id]?.authority === state.attempt &&
                    !ambiguous(state) && code !in state.confirmedCodes &&
                    state.predictedCodes.keys.any { it != state.definitelyUnsentWire } &&
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
            retireTerminalAck(tracking, failure)
            throw failure
        }
        if (row.contactID != tracking.contactID || row.radioId != token.radioId) {
            val failure = PersistenceStoreException(PersistenceStoreError.InvalidData)
            remember("ack.$messageID", failure)
            retireTerminalAck(tracking, failure)
            throw failure
        }
        if (tracking.publicKey != null) {
            val contact = dataStore.fetchContact(key(tracking.contactID))
            checkOwnership("commitAcknowledgement.contact")
            if (contact != null && contact.publicKey != tracking.publicKey) {
                val failure = MessageServiceException(MessageServiceError.ContactNotFound)
                remember("ack.$messageID.identity", failure)
                retireTerminalAck(tracking, failure)
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
            authority().settleDirectAck(
                attempt ?: throw authorityFailure("unregistered acknowledgement"),
                ack.code.ackCodeUInt32, ack.tripTime,
            )
        } catch (failure: PersistenceStoreException) {
            if (uncertainWrite != null) {
                remember("ackUncertain.$messageID", uncertainWrite.cause)
                remember("ack.$messageID", uncertainWrite.cause)
                throw uncertainWrite.recoveryFailure
            }
            throw failure
        }
        if (result is DirectAckMutationResult.Rejected && result.reason == DirectAckRejection.AMBIGUOUS_ACK) {
            synchronized(lock) {
                val current = pendingAcks[messageID]
                if (current != null && current.receipt === tracking.receipt && current.acknowledgement == ack) {
                    pendingAcks[messageID] = current.copy(isDelivered = false, acknowledgement = null)
                }
            }
            ownership.failure("ambiguousAck.$messageID", authorityFailure("ACK matches another registered claim"))
            return
        }
        if (result is DirectAckMutationResult.Rejected && result.reason in retiredAuthorityReasons) {
            if (uncertainWrite != null && row.status != MessageStatus.DELIVERED) {
                remember("ackUncertain.$messageID", uncertainWrite.cause)
                throw uncertainWrite.recoveryFailure
            }
            ownership.reporter.report(MessagingDiagnostic.StaleResult(token, "ackAuthority.${result.reason}"))
            retireTerminalAck(tracking, authorityFailure("ACK settlement rejected: ${result.reason}"))
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
            retireTerminalAck(tracking, authorityFailure("ACK settlement is ${receipt.permission}"))
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
            val state = directAuthorities[messageID]
            if (pendingAcks[messageID]?.receipt === tracking.receipt &&
                state != null && state.attempt === tracking.authority) {
                activeDirectClaims[messageID]?.takeIf { it.id == state.requestID }?.acknowledgement = ack
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
    }

    suspend fun checkExpiredAcks() {
        ownership.check("checkExpiredAcks")
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

    private fun retireTerminalAck(tracking: PendingAck, failure: Exception? = null) {
        val retired = synchronized(lock) {
            val current = pendingAcks[tracking.messageID]?.receipt === tracking.receipt
            if (current) {
                pendingAcks.remove(tracking.messageID)
                val state = directAuthorities[tracking.messageID]
                if (failure != null && state != null && state.attempt === tracking.authority) {
                    activeDirectClaims[tracking.messageID]?.takeIf { it.id == state.requestID }?.authorityFailure = failure
                }
            }
            if (uncertainFailureWrites[tracking.messageID]?.tracking?.receipt === tracking.receipt) {
                uncertainFailureWrites.remove(tracking.messageID)
            }
            current
        }
        if (retired && failure != null) tracking.receipt.completeExceptionally(failure)
        retireBufferedAcknowledgements(tracking.messageID, tracking.authority)
    }

    private suspend fun expireOrReconcile(snapshot: PendingAck, now: Instant) {
        val failureOperation = "ackExpiry.${snapshot.messageID}"
        if (snapshot.acknowledgement != null) {
            reconcileAcknowledgement(snapshot.messageID, failureOperation = failureOperation)
            return
        }
        var acknowledgementArrived = false
        mutation(snapshot.messageID).withLock {
            try {
                val current = pendingAck(snapshot.messageID) ?: return@withLock
                if (current.isDelivered || Duration.between(current.sentAt, now).toNanos().toDouble() / 1e9 <=
                    maxOf(config.ackGiveUpWindow, current.timeout)) return@withLock
                val wasFailed = dataStore.fetchMessage(key(current.messageID))?.status == MessageStatus.FAILED
                ownership.check("expireAcknowledgement")
                val changed = writeFailedStatus(current.messageID, wasFailed)
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
                    val changed = writeFailedStatus(id, wasFailed)
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
            if (notify && changed) broadcastFailed(messageID)
            recovered("failPending.$messageID")
            recovered("ackUncertain.$messageID")
        }
        return acknowledgementArrived
    }

    private suspend fun writeFailedStatus(
        messageID: UUID, wasFailed: Boolean, expectedAttempt: DirectAckAttempt? = null,
    ): Boolean {
        val tracking = pendingAck(messageID)?.takeIf { expectedAttempt == null || it.authority === expectedAttempt }
        try {
            val attempt = expectedAttempt ?: tracking?.authority ?: synchronized(lock) {
                if (tracking == null) directAuthorities[messageID]?.attempt else null
            }
            if (attempt == null && tracking == null) {
                if (synchronized(lock) { messageID in directIntentMessages }) {
                    throw authorityFailure("dispatch intent has no failure grant")
                }
                return dataStore.updateMessageStatusUnlessDelivered(key(messageID), MessageStatus.FAILED)
            }
            if (attempt == null && wasFailed) return false
            if (attempt == null && dataStore.fetchMessage(key(messageID))?.status == MessageStatus.DELIVERED) return false
            val result = authority().recordDirectFailure(
                attempt ?: throw authorityFailure("unregistered failure"),
            )
            if (result is DirectAckMutationResult.Rejected && result.reason in retiredAuthorityReasons) {
                ownership.reporter.report(MessagingDiagnostic.StaleResult(token, "failureAuthority.${result.reason}"))
                tracking?.let { retireTerminalAck(it) }
                return false
            }
            val receipt = requireReceipt(result)
            return receipt.message?.status == MessageStatus.FAILED && !wasFailed
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

    private fun broadcastFailed(id: UUID) {
        if (ownership.isCurrent && synchronized(lock) { failedNotifications.add(id) }) events.yield(MessageStatusEvent.Failed(id))
    }
    suspend fun notifyMessageFailed(messageID: UUID) {
        ownership.check("notifyMessageFailed")
        val row = dataStore.fetchMessage(key(messageID))
        ownership.check("notifyMessageFailed.read")
        if (row?.status == MessageStatus.FAILED) broadcastFailed(messageID)
    }

    internal suspend fun notifyQueuedChannelRetired(attemptKey: QueuedChannelAttemptKey, receipt: QueuedChannelRetirement) {
        ownership.check("notifyQueuedChannelRetired")
        require(attemptKey.radioId == token.radioId) { "A retirement receipt belongs to its initiating radio" }
        if (receipt.statusChanged && receipt.status == MessageStatus.FAILED &&
            synchronized(lock) { retiredChannelNotifications.add(attemptKey) }
        ) events.yield(MessageStatusEvent.Failed(attemptKey.messageID))
    }

    internal suspend fun failMessageAndRethrow(
        failure: Exception,
        messageID: UUID,
        notify: Boolean = false,
        failDefinitelyUnsentInline: Boolean = false,
        failureAttempt: (() -> DirectAckAttempt?)? = null,
        failureRequestID: UUID? = null,
        failureBeforeDispatch: (suspend () -> DirectMessageCreationFailureReceipt)? = null,
        retainRegisteredQueuedOutcome: Boolean = false,
    ): Nothing {
        // Complete only the already-persisted send's failure before teardown returns, never a successor's work.
        val notificationReceipt = currentCoroutineContext()[NotificationActionReceipt]
        withContext(NonCancellable) {
            mutation(messageID).withLock {
                try {
                    val expectedAttempt = failureAttempt?.invoke()
                    val superseded = failureAttempt != null && synchronized(lock) {
                        val state = directAuthorities[messageID]
                        if (expectedAttempt != null) {
                            state == null || state.attempt !== expectedAttempt ||
                                directRegistrationEpochs[messageID] != state.registrationEpoch
                        } else state?.let { it.requestID != failureRequestID } == true
                    }
                    if (superseded) {
                        ownership.reporter.report(MessagingDiagnostic.StaleResult(token, "inlineFailureAuthority"))
                        return@withLock
                    }
                    if (retainRegisteredQueuedOutcome && expectedAttempt != null) return@withLock
                    if (expectedAttempt != null) {
                        val uncertain = synchronized(lock) {
                            val state = directAuthorities[messageID]
                            state?.takeIf {
                                it.attempt === expectedAttempt &&
                                    it.wires.values.any { wire -> wire != it.definitelyUnsentWire }
                            }
                        }
                        if (uncertain != null) {
                            notificationReceipt?.retainUncertain()
                            if (notificationReceipt == null) remember("directUncertain.$messageID", failure)
                            restoreInterruptedDirectRetry(uncertain.requestID, messageID)
                            return@withLock
                        }
                    }
                    val failUnsentInlineRow = notificationReceipt == null || failDefinitelyUnsentInline
                    if (notificationReceipt == null || notificationReceipt.state == NotificationActionReceipt.State.READY) {
                        if (failUnsentInlineRow) {
                            if (failureAttempt != null && expectedAttempt == null) {
                                ownership.checkPersistenceCompletion("failMessage.creation")
                                val receipt = failureBeforeDispatch?.invoke()
                                    ?: throw authorityFailure("own dispatch intent has no observed failure grant")
                                ownership.checkPersistenceCompletion("failMessage.creation.saved")
                                if (notify && receipt.statusChanged && receipt.status == MessageStatus.FAILED) {
                                    broadcastFailed(messageID)
                                }
                                return@withLock
                            }
                            if (pendingAck(messageID)?.acknowledgement == null) {
                                val wasFailed = dataStore.fetchMessage(key(messageID))?.status == MessageStatus.FAILED
                                ownership.checkPersistenceCompletion("failMessage")
                                val changed = writeFailedStatus(messageID, wasFailed, expectedAttempt)
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
        failureBeforeDispatch: (suspend () -> DirectMessageCreationFailureReceipt)? = null,
        retainRegisteredQueuedOutcome: Boolean = false,
        block: suspend () -> T,
    ): T =
        try { block() }
        catch (cancelled: CancellationException) { failMessageAndRethrow(cancelled, id, notify, failDefinitelyUnsentInline, failureAttempt, failureRequestID, failureBeforeDispatch, retainRegisteredQueuedOutcome) }
        catch (failure: MeshCoreException) { failMessageAndRethrow(failure, id, notify, failDefinitelyUnsentInline, failureAttempt, failureRequestID, failureBeforeDispatch, retainRegisteredQueuedOutcome) }
        catch (failure: MeshTransportError) { failMessageAndRethrow(failure, id, notify, failDefinitelyUnsentInline, failureAttempt, failureRequestID, failureBeforeDispatch, retainRegisteredQueuedOutcome) }
        catch (failure: MessageServiceException) { failMessageAndRethrow(failure, id, notify, failDefinitelyUnsentInline, failureAttempt, failureRequestID, failureBeforeDispatch, retainRegisteredQueuedOutcome) }
        catch (failure: PersistenceStoreException) { failMessageAndRethrow(failure, id, notify, failDefinitelyUnsentInline, failureAttempt, failureRequestID, failureBeforeDispatch, retainRegisteredQueuedOutcome) }

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
    private suspend fun savePendingCreation(
        message: MessageDTO,
        save: suspend () -> MessageDTO = { dataStore.saveMessage(message); message },
    ): MessageDTO {
        val receipt = currentCoroutineContext()[PendingMessageCreation]
        receipt?.writing(message)
        val saved = try {
            save()
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
        if (saved.id != message.id || saved.radioId != message.radioId ||
            saved.channelIndex != message.channelIndex || saved.channelOccupantID != message.channelOccupantID
        ) {
            receipt?.settled(PendingMessageCreation.State.UNKNOWN)
            val failure = PersistenceStoreException(PersistenceStoreError.InvalidData)
            remember("pendingCreation.${message.id}.identity", failure)
            throw failure
        }
        if (receipt?.committed(saved) == false) {
            val failure = PersistenceStoreException(PersistenceStoreError.InvalidData)
            remember("pendingCreation.${message.id}.identity", failure)
            throw failure
        }
        return saved
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
        state: DirectAuthorityState,
        wireID: UUID,
        block: suspend (MessageWriteDispatch) -> T,
    ): T {
        val receipt = currentCoroutineContext()[NotificationActionReceipt]
        val dispatch = MessageWriteDispatch()
        val ownsOutcome = receipt?.state == NotificationActionReceipt.State.READY
        if (ownsOutcome) receipt.wireStarted()
        try {
            val result = block(dispatch)
            if (ownsOutcome) receipt.accepted()
            return result
        } catch (cancelled: CancellationException) {
            val unsent = dispatch.cancellationDisposition == ChannelWriteCancellationDisposition.DEFINITELY_UNSENT &&
                retireDefinitelyUnsentWire(state, wireID, cancelled, queued = false)
            if (ownsOutcome) {
                if (unsent) receipt.wireNotDispatched() else receipt.uncertain()
            }
            throw cancelled
        } catch (failure: Exception) {
            if (ownsOutcome) receipt.uncertain()
            throw failure
        }
    }

    override suspend fun sendDirectMessage(text: String, contact: ContactDTO, textType: TextType, replyToID: UUID?): MessageDTO =
        withFreshNotificationAction("sendDirectMessage") {
            owned("sendDirectMessage") {
                validate(text, contact)
                val message = outgoing(text, contact, textType, replyToID)
                val requestID = UUID.randomUUID()
                val creationRequestID = UUID.randomUUID()
                val creationFailureID = UUID.randomUUID()
                var creationLease: DirectAckLease? = null
                var creation: DirectMessageCreation? = null
                var registered: Pair<DirectAuthorityState, UUID>? = null
                val (info, tracking, predicted) = sendBoundary(
                    message.id, true, failDefinitelyUnsentInline = true,
                    failureAttempt = { registered?.first?.attempt },
                    failureRequestID = requestID,
                    failureBeforeDispatch = {
                        val producer = creationAuthority()
                        val handle = creation ?: when (val recovered = producer.recoverDirectMessageCreation(
                            creationLease ?: throw authorityFailure("inline creation was not started"),
                            key(message.id), creationRequestID, requestID,
                        )) {
                            is DirectMessageCreationResult.Created -> recovered.creation
                            is DirectMessageCreationResult.AlreadyCreated -> recovered.creation
                            is DirectMessageCreationResult.Rejected -> throw authorityFailure("inline creation: ${recovered.reason}")
                        }
                        when (val failed = producer.failDirectMessageCreation(handle, creationFailureID)) {
                            is DirectMessageCreationFailureResult.Applied -> failed.receipt
                            is DirectMessageCreationFailureResult.AlreadyApplied -> failed.receipt.copy(statusChanged = false)
                            is DirectMessageCreationFailureResult.Rejected -> throw authorityFailure("inline failure: ${failed.reason}")
                        }
                    },
                ) {
                    val lease = directLease().also { creationLease = it }
                    creation = when (val result = creationAuthority().createDirectMessage(
                        lease, message, creationRequestID, requestID,
                    )) {
                        is DirectMessageCreationResult.Created -> result.creation
                        is DirectMessageCreationResult.AlreadyCreated -> result.creation
                        is DirectMessageCreationResult.Rejected -> throw authorityFailure("inline creation: ${result.reason}")
                    }
                    ownership.check("sendDirectMessage.saved")
                    checkIdentity("sendDirectMessage")
                    val prepared = prepareDirect(
                        message.id, requestID, contact, DirectAckMode.SEND, null, creation = requireNotNull(creation),
                    )
                    val captured = prepared.message
                    val selected = prepared.contact
                    val predicted = AckCodeBuilder.expectedAck(captured.timestamp, 0u, captured.text, localPublicKey)
                    val registration = registerDirectDispatch(message.id, requestID, predicted, 0, prepared.intent,
                        correlateResponses = true)
                    registered = registration
                    val tracking = trackPendingAck(
                        message.id, selected.id, predicted, checkInterval, selected.publicKey, registration.first.attempt,
                    )
                    var transportInvoked = false
                    val info = try {
                        withPoolBackoff(3u, retryOnDeviceError = false) {
                            dmOperations.withLock {
                                checkIdentity("sendDirectMessage.wire")
                                requireCurrentDirectAuthority(registration.first)
                                transportInvoked = true
                                notificationWire(registration.first, registration.second) { dispatch ->
                                    val timestamp = Instant.ofEpochSecond(captured.timestamp.toLong())
                                    session.sendMessage(selected.publicKey, captured.text, timestamp, dispatch = dispatch)
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        if (!transportInvoked) {
                            retireDefinitelyUnsentWire(registration.first, registration.second, cancelled, queued = false)
                        }
                        throw cancelled
                    } catch (failure: Exception) {
                        if (!transportInvoked) {
                            retireDefinitelyUnsentWire(registration.first, registration.second, failure, queued = false)
                        }
                        throw failure
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

    override suspend fun sendMessageWithRetry(
        text: String, contact: ContactDTO, textType: TextType, replyToID: UUID?, timeout: Double,
        onMessageCreated: (suspend (MessageDTO) -> Unit)?,
    ): MessageDTO {
        require(timeout.isFinite() && timeout >= 0)
        validate(text, contact)
        val requestID = UUID.randomUUID()
        val creationRequestID = UUID.randomUUID()
        val creationFailureID = UUID.randomUUID()
        val (message, creation) = owned("saveMessageWithRetry", pendingOperations) {
            val row = outgoing(text, contact, textType, replyToID)
            val created = when (val result = creationAuthority().createDirectMessage(
                directLease(), row, creationRequestID, requestID,
            )) {
                is DirectMessageCreationResult.Created -> result.creation
                is DirectMessageCreationResult.AlreadyCreated -> result.creation
                is DirectMessageCreationResult.Rejected -> throw authorityFailure("retry creation: ${result.reason}")
            }
            ownership.check("sendMessageWithRetry.saved")
            row to created
        }
        // A consumer may send again or close the generation; it owns neither the wire lease nor this service's job.
        onMessageCreated?.invoke(message)
        ownership.check("sendMessageWithRetry.created")
        return owned("sendMessageWithRetry") {
            suspend fun failCreation(): DirectMessageCreationFailureReceipt =
                when (val result = creationAuthority().failCreatedDirectRetryMessage(creation, creationFailureID)) {
                    is DirectMessageCreationFailureResult.Applied -> result.receipt
                    is DirectMessageCreationFailureResult.AlreadyApplied -> result.receipt.copy(statusChanged = false)
                    is DirectMessageCreationFailureResult.Rejected -> throw authorityFailure("retry failure: ${result.reason}")
                }
            val current = fetchSaved(message.id)
            if (current.contactID != contact.id) throw MessageServiceException(MessageServiceError.ContactNotFound)
            if (config.maxAttempts == 0L) {
                return@owned sendBoundary(
                    message.id, true, failureAttempt = { null }, failureRequestID = requestID,
                    failureBeforeDispatch = { failCreation() },
                ) {
                    val target = zeroAttemptStatusTarget(message.id)
                    val resolutionID = UUID.randomUUID()
                    suspend fun resolve(): DirectMessageCreationResolutionReceipt {
                        val result = try {
                            creationAuthority().resolveCreatedDirectWithoutDispatch(
                                creation, resolutionID, target != null, target?.authority,
                            )
                        } catch (failure: PersistenceStoreException) {
                            try {
                                creationAuthority().resolveCreatedDirectWithoutDispatch(
                                    creation, resolutionID, target != null, target?.authority,
                                )
                            } catch (retry: PersistenceStoreException) {
                                failure.addSuppressed(retry)
                                throw failure
                            }
                        }
                        return when (result) {
                            is DirectMessageCreationResolutionResult.Applied -> result.receipt
                            is DirectMessageCreationResolutionResult.AlreadyApplied -> result.receipt
                            is DirectMessageCreationResolutionResult.Rejected ->
                                throw authorityFailure("retry zero-attempt resolution: ${result.reason}")
                        }
                    }
                    if (resolve().statusChanged) {
                        events.yield(MessageStatusEvent.StatusResolved(message.id, MessageStatus.SENT, null))
                    }
                    checkRouting(message.id, contact.id, contact.publicKey, contact.outPathLength)
                    resolve().message
                }
            }
            val claim = newDirectClaim(
                requestID, message, contact, false, initialStatus = current.status, creation = creation,
            )
            releaseDirectClaimOnExit(claim.id) {
                sendBoundary(
                    message.id, true,
                    failureAttempt = {
                        synchronized(lock) { directAuthorities[message.id]?.takeIf { it.requestID == claim.id }?.attempt }
                    },
                    failureRequestID = claim.id,
                    failureBeforeDispatch = { failCreation() },
                ) {
                    val info = retryLoop(claim, claim.contact, requireNotNull(claim.timestamp), timeout.takeIf { it > 0 })
                    finalizeSend(message.id, contact.id, contact.publicKey, info, contact.outPathLength, claimID = claim.id)
                }
            }
        }
    }

    override suspend fun sendPendingDirectMessage(messageID: UUID, contact: ContactDTO, preserveTimestamp: Boolean): MessageDTO =
        ephemeralQueuedDM(messageID, contact, preserveTimestamp, false)
    override suspend fun resendDirectMessage(messageID: UUID, contact: ContactDTO, preserveTimestamp: Boolean): MessageDTO =
        ephemeralQueuedDM(messageID, contact, preserveTimestamp, true)

    private suspend fun ephemeralQueuedDM(id: UUID, contact: ContactDTO, preserveTimestamp: Boolean, isResend: Boolean): MessageDTO {
        val claimID = UUID.randomUUID()
        return releaseDirectClaimOnExit(claimID) { queuedDM(id, contact, preserveTimestamp, isResend, claimID) }
    }

    internal suspend fun sendPendingClaim(
        claimID: UUID, messageID: UUID, contact: ContactDTO, preserveTimestamp: Boolean, isResend: Boolean,
        pendingSendID: UUID, selection: DirectQueueSelection, preparationRequestID: UUID,
        onSelectionPrepared: (DirectQueueSelection) -> Unit,
    ): MessageDTO = queuedDM(
        messageID, contact, preserveTimestamp, isResend, claimID, pendingSendID,
        selection, preparationRequestID, onSelectionPrepared,
    )

    private suspend fun <T> releaseDirectClaimOnExit(claimID: UUID, block: suspend () -> T): T {
        var original: Exception? = null
        try { return block() }
        catch (failure: Exception) {
            original = failure
            throw failure
        } finally {
            try { withContext(NonCancellable) { releaseDirectClaim(claimID) } }
            catch (cleanup: Exception) {
                remember("directIntentRetained.$claimID", cleanup)
                if (original == null) throw cleanup
                original.addSuppressed(cleanup)
            }
        }
    }

    internal suspend fun releaseDirectClaim(claimID: UUID, requireUnbegun: Boolean = false) {
        val claim = synchronized(lock) { directClaims[claimID] }
        if (claim != null &&
            retireDirectIntent(directLease(), claim.messageID, claimID) == DirectAckIntentRetirement.AttemptBegun &&
            requireUnbegun
        ) throw UnresolvedDirectQueueException("registered attempt cannot be released for replay")
        synchronized(lock) {
            noDispatchCompletions.remove(claimID)
            queuePreparations.remove(claimID)
            if (claim != null && directClaims[claimID] === claim) {
                directClaims.remove(claimID)
                if (activeDirectClaims[claim.messageID] === claim) activeDirectClaims.remove(claim.messageID)
            }
        }
    }

    private suspend fun newDirectClaim(
        claimID: UUID, row: MessageDTO, contact: ContactDTO, resend: Boolean, pendingSendID: UUID? = null,
        initialStatus: MessageStatus = row.status, selection: DirectQueueSelection? = null,
        creation: DirectMessageCreation? = null,
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
        val prepared = prepareDirect(
            row.id, claimID, contact, mode, pendingSendID, resend, selection, creation,
            retryCreationMessage = row.takeIf { creation != null },
        )
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
        selection: DirectQueueSelection? = null, preparationRequestID: UUID? = null,
        onSelectionPrepared: (DirectQueueSelection) -> Unit = {},
    ): MessageDTO {
        synchronized(lock) {
            if (!inFlightRetries.add(id)) throw MessageServiceException(MessageServiceError.SendFailed("Retry already in progress"))
            failedNotifications.remove(id)
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
                        val state = rememberNoDispatch(claimID, id, pendingSendID, DirectPendingCompletionReason.AlreadyDeliveredSend)
                        return@owned if (selection != null && state != null) captureQueuedNoDispatch(selection, state) else row
                    }
                    if (config.maxAttempts == 0L) {
                        if (selection != null) {
                            val state = requireNotNull(rememberNoDispatch(
                                claimID, id, pendingSendID, DirectPendingCompletionReason.ZeroAttempts(0L),
                            ))
                            val resolveToSent = zeroAttemptNeedsStatusResolution(id)
                            val prepared = prepareQueuedSelection(
                                selection, requireNotNull(preparationRequestID), claimID, preserveTimestamp, resolveToSent,
                            )
                            onSelectionPrepared(prepared.selection)
                            if (prepared.statusResolved && !state.statusPublished) {
                                events.yield(MessageStatusEvent.StatusResolved(id, MessageStatus.SENT, null))
                                state.statusPublished = true
                            }
                            captureQueuedNoDispatch(prepared.selection, state)
                            checkRouting(id, contact.id, contact.publicKey, contact.outPathLength)
                            return@owned fetchSaved(id)
                        }
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
                    var preparedSelection = selection
                    if (retained == null && selection != null) {
                        val prepared = prepareQueuedSelection(
                            selection, requireNotNull(preparationRequestID), claimID, preserveTimestamp, resolveToSent = false,
                        )
                        preparedSelection = prepared.selection
                        onSelectionPrepared(prepared.selection)
                    } else if (retained == null && !preserveTimestamp) {
                        timestamp().also {
                            dataStore.updateMessageTimestamp(key(id), it)
                            ownership.check("queuedDirectMessage.timestamp")
                        }
                    }
                    val claim = newDirectClaim(claimID, row, contact, isResend, pendingSendID, selection = preparedSelection)
                    val wireTimestamp = requireNotNull(claim.timestamp)
                    sendBoundary(
                        id, false,
                        failureAttempt = {
                            synchronized(lock) { directAuthorities[id]?.takeIf { it.requestID == claimID }?.attempt }
                        },
                        failureRequestID = claimID,
                        retainRegisteredQueuedOutcome = pendingSendID != null,
                    ) {
                        val info = if (synchronized(lock) { claim.wireFinished }) {
                            synchronized(lock) { claim.lastSentInfo }
                        } else retryLoop(claim, claim.contact, wireTimestamp, null).also {
                            synchronized(lock) { claim.lastSentInfo = it; claim.wireFinished = true }
                        }
                        if (isResend && info != null && !synchronized(lock) { claim.sendCountCommitted }) {
                            try {
                                directBookkeeping(directAuthority(id, claim.id), "resendCount", DirectAckBookkeeping.ResendCountIncrement)
                                synchronized(lock) { claim.sendCountCommitted = true }
                                ownership.check("queuedDirectMessage.sendCount")
                                recovered("dmSendCount.$id")
                            } catch (failure: PersistenceStoreException) {
                                remember("dmSendCount.$id", failure)
                                throw failure
                            }
                        }
                        val result = finalizeSend(id, contact.id, contact.publicKey, info, contact.outPathLength, claimID = claim.id)
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
        claim.authorityFailure?.let { throw it }
        if (claim.acknowledgement != null) claim.lastSentInfo else null
    }
    private fun unacknowledgedRetirement(claim: DirectSendClaim): Boolean = synchronized(lock) {
        claim.acknowledgement == null && claim.retiredWithoutAcknowledgement
    }

    private fun pendingAckForClaim(claim: DirectSendClaim): PendingAck? = synchronized(lock) {
        val state = directAuthorities[claim.messageID]?.takeIf { it.requestID == claim.id } ?: return@synchronized null
        pendingAcks[claim.messageID]?.takeIf { it.authority === state.attempt }
    }

    private suspend fun retryLoop(claim: DirectSendClaim, contact: ContactDTO, stamp: UInt, timeout: Double?): MessageSentInfo? {
        val id = claim.messageID
        val text = claim.text
        var attempts = synchronized(lock) { claim.nextWireIndex }
        var floods = synchronized(lock) { claim.floodAttempts }
        var flood = synchronized(lock) { claim.usingFlood }
        while (attempts < config.maxAttempts && (!flood || floods < config.maxFloodAttempts)) {
            checkIdentity("retryDirectMessage")
            pendingAckForClaim(claim)?.takeIf { it.acknowledgement != null }?.let {
                reconcileAcknowledgement(id, expectedAttempt = it.authority)
            }
            synchronized(lock) { claim.acceptedWire }?.let {
                recordDirectAcceptance(directAuthority(id, claim.id), it.requestID, it.info)
            }
            completedDirect(claim)?.let { return it }
            if (unacknowledgedRetirement(claim)) return null
            if (attempts > 0) {
                directBookkeeping(
                    directAuthority(id, claim.id), "retry.$attempts",
                    DirectAckBookkeeping.RetryStarted(attempts - 1, config.maxAttempts - 1),
                )
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
            val tracking = trackPendingAck(
                id, contact.id, predicted, maxOf(timeout ?: config.minTimeout, checkInterval), contact.publicKey,
                authorityState.attempt,
            )
            val mailbox = Channel<MeshEvent.Acknowledgement>(Channel.UNLIMITED)
            val stream = session.events(EventFilter.anyAcknowledgement)
            val collector = ownership.scope.async(start = CoroutineStart.UNDISPATCHED) {
                stream.collect { if (it is MeshEvent.Acknowledgement) mailbox.send(it) }
            }
            try {
                var transportInvoked = false
                val info = try {
                    synchronized(lock) { claim.acceptedWire }?.info ?:
                        withPoolBackoff(3u, retryOnDeviceError = false) {
                        dmOperations.withLock {
                            checkIdentity("retryDirectMessage.wire")
                            requireCurrentDirectAuthority(authorityState)
                            pendingAckForClaim(claim)?.takeIf { it.acknowledgement != null }?.let {
                                reconcileAcknowledgement(id, expectedAttempt = it.authority)
                            }
                            if (unacknowledgedRetirement(claim)) null
                            else completedDirect(claim) ?: if (claim.pendingSendID != null) {
                                val dispatch = MessageWriteDispatch()
                                transportInvoked = true
                                try {
                                    session.sendMessage(
                                        contact.publicKey.prefix(6), text, Instant.ofEpochSecond(stamp.toLong()), attempt, dispatch,
                                    )
                                } catch (cancelled: CancellationException) {
                                    if (dispatch.cancellationDisposition == ChannelWriteCancellationDisposition.DEFINITELY_UNSENT) {
                                        retireDefinitelyUnsentWire(authorityState, wireID, cancelled)
                                    }
                                    throw cancelled
                                }
                            } else notificationWire(authorityState, wireID) { dispatch ->
                                transportInvoked = true
                                val timestamp = Instant.ofEpochSecond(stamp.toLong())
                                session.sendMessage(contact.publicKey.prefix(6), text, timestamp, attempt, dispatch)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    if (!transportInvoked) {
                        retireDefinitelyUnsentWire(authorityState, wireID, cancelled, queued = claim.pendingSendID != null)
                    }
                    throw cancelled
                } catch (failure: Exception) {
                    if (!transportInvoked) {
                        retireDefinitelyUnsentWire(authorityState, wireID, failure, queued = claim.pendingSendID != null)
                    }
                    throw failure
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
                            // The exact receipt remains correlated after its map snapshot is replaced or retired.
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
        noDispatch: Boolean = false, claimID: UUID? = null,
    ): MessageDTO {
        ownership.check("finalizeSend")
        val state = synchronized(lock) {
            directAuthorities[messageID]?.also {
                if (claimID != null) directAuthority(messageID, claimID)
            }
        }
        val tracking = pendingAck(messageID)?.takeIf { claimID == null || it.authority === state?.attempt }
        if (tracking?.acknowledgement != null) reconcileAcknowledgement(messageID, expectedAttempt = state?.attempt)
        else mutation(messageID).withLock {
            ownership.check("finalizeSend.transition")
            if (claimID != null) {
                if (state != null) requireCurrentDirectAuthority(state)
                else if (synchronized(lock) { directAuthorities[messageID] } != null) {
                    throw authorityFailure("direct finalization superseded")
                }
            }
            val current = pendingAck(messageID)?.takeIf { claimID == null || it.authority === state?.attempt }
            if (current?.acknowledgement != null) {
                reconcileAcknowledgementLocked(messageID, cleanup = false, expectedAttempt = state?.attempt)
            } else if (current == null || current.isDelivered) synchronized(lock) {
                if (current != null && pendingAcks[messageID] === current) pendingAcks.remove(messageID)
            }
            else if (sentInfo != null) {
                throw authorityFailure("send completion has no observed acknowledgement")
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
                val receipt = directBookkeeping(
                    directAuthority(messageID, claimID), "retryExhausted", DirectAckBookkeeping.RetryExhausted,
                )
                ownership.check("finalizeSend.sent")
                if (receipt.message?.status == MessageStatus.SENT) {
                    events.yield(MessageStatusEvent.StatusResolved(messageID, MessageStatus.SENT, null))
                }
            }
        }
        checkRouting(messageID, contactID, publicKey, initialPathLength)
        if (claimID != null && state != null) {
            requireCurrentDirectAuthority(state)
            val observation = authority().inspectDirectAckAttempt(directLease(), claimID)
            val receipt = (observation as? DirectAckObservation.Found)?.receipt
                ?: throw authorityFailure("direct finalization receipt missing")
            val message = receipt.message
            if (receipt.permission != DirectAckPermission.CURRENT || message == null) {
                throw authorityFailure("direct finalization is ${receipt.permission}")
            }
            requireCurrentDirectAuthority(state)
            return message
        }
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
            saveOutgoingChannel(text, channelIndex, radioId, textType).also {
                ownership.check("createPendingChannel.saved")
            }
        }

    suspend fun createPendingChannelMessage(
        text: String, channel: ChannelDTO, textType: TextType = TextType.PLAIN,
    ): MessageDTO = owned("createPendingDisplayedChannel", pendingOperations) {
        fun reject(reason: ChannelHistoryConflict): Nothing = throw PersistenceStoreException(
            PersistenceStoreError.InvalidData, ChannelHistoryRejected(reason),
        )
        val occupant = channel.occupantID ?: reject(ChannelHistoryConflict.UNKNOWN_AUTHORITY)
        if (channel.radioId != token.radioId) reject(ChannelHistoryConflict.STALE_OCCUPANT)
        dataStore.withChannelOperation { store, _ ->
            val current = store.fetchChannelOccupantSnapshot(channel.radioId, channel.index)
                ?: reject(ChannelHistoryConflict.UNKNOWN_AUTHORITY)
            if (current.occupantID != occupant || current.channel.id != channel.id ||
                current.channel.secret != channel.secret
            ) reject(ChannelHistoryConflict.STALE_OCCUPANT)
            saveOutgoingChannel(text, channel.index, channel.radioId, textType,
                ChannelHistoryScope(channel.radioId, channel.index, occupant)).also {
                ownership.check("createPendingDisplayedChannel.saved")
            }
        }
    }

    private suspend fun saveOutgoingChannel(
        text: String, channelIndex: UByte, radioId: RadioId, textType: TextType,
        capturedOrigin: ChannelHistoryScope? = null,
    ): MessageDTO {
        val dto = outgoingChannel(text, channelIndex, radioId, textType)
        val origin = capturedOrigin ?: ChannelHistoryScope.from(
            dataStore.fetchChannelOccupantSnapshot(radioId, channelIndex)
                ?: throw MessageServiceException(MessageServiceError.ChannelNotFound),
        )
        ownership.check("createPendingChannel.origin")
        if (origin.radioId != radioId || origin.channelIndex != channelIndex) {
            throw PersistenceStoreException(PersistenceStoreError.InvalidData)
        }
        val history = dataStore as? ChannelHistoryPersisting
            ?: throw PersistenceStoreException(PersistenceStoreError.InvalidData,
                UnsupportedOperationException("Origin-aware channel creation is not wired"))
        val intended = dto.copy(channelOccupantID = origin.expectedOccupantID)
        val saved = savePendingCreation(intended) {
            history.createOrReconcileChannelMessage(
                ChannelMessageCreation(dto.id, origin, dto.text, dto.textType),
                dto.timestamp, dto.createdAt,
            )
        }
        return saved
    }

    override suspend fun sendChannelMessage(text: String, channelIndex: UByte, radioId: RadioId, textType: TextType): ChannelSendReceipt =
        withFreshNotificationAction("sendChannelMessage") {
            owned("sendChannelMessage", channelOperations) {
                val dto = saveOutgoingChannel(text, channelIndex, radioId, textType)
                sendBoundary(dto.id, true, failDefinitelyUnsentInline = true) {
                    ownership.check("sendChannelMessage.persisted")
                }
                val stamp = inlineChannel(dto, isResend = false, preserveTimestamp = true, notifyFailure = true)
                ChannelSendReceipt(dto.id, stamp)
            }
        }

    override suspend fun sendPendingChannelMessage(messageID: UUID) { queuedChannel(messageID, false, true) }
    override suspend fun resendChannelMessage(messageID: UUID, preserveTimestamp: Boolean): UInt =
        queuedChannel(messageID, true, preserveTimestamp)

    internal suspend fun sendQueuedChannel(
        pendingID: UUID, envelope: ChannelMessageEnvelope, preserveTimestamp: Boolean,
    ): QueuedChannelAttempt = owned("sendQueuedChannel") {
        dispatchChannelAttempt(pendingID, envelope, preserveTimestamp)
    }

    private suspend fun dispatchChannelAttempt(
        pendingID: UUID,
        envelope: ChannelMessageEnvelope,
        preserveTimestamp: Boolean,
        newSubmission: PendingSendDTO? = null,
        trackNotification: Boolean = false,
        journalStarted: () -> Unit = {},
        retireUnsent: (suspend (QueuedChannelAttemptKey) -> Unit)? = null,
    ): QueuedChannelAttempt {
        val occupantID = envelope.channelOccupantID
        val submissions = dataStore.fetchPendingSendsForMessage(key(envelope.messageID))
        if (submissions.any { it.id != pendingID }) {
            throw retainedChannel(pendingID, PersistenceStoreException(PersistenceStoreError.InvalidData,
                QueuedChannelAttemptRejected(EntityKey(token.radioId, pendingID),
                    QueuedChannelAttemptConflict.RETAINED_SUBMISSION)))
        }
        val pending = submissions.singleOrNull { it.id == pendingID }
        if (newSubmission == null && (pending == null || pending.channelMessageEnvelope() != envelope ||
            pending.channelDispatchState != QueuedChannelDispatchState.READY ||
            pending.channelAttemptID != null || occupantID == null
        )) {
            throw retainedChannel(pendingID, PersistenceStoreException(PersistenceStoreError.InvalidData,
                QueuedChannelAttemptRejected(EntityKey(token.radioId, pendingID),
                    QueuedChannelAttemptConflict.SUBMISSION_NOT_READY)))
        }
        val lease = channelDispatchLease
            ?: throw retainedChannel(pendingID, ChannelDispatchFailure.RetiredSession())
        val row = dataStore.fetchMessage(key(envelope.messageID))
        if (row == null || occupantID == null || row.direction != MessageDirection.OUTGOING || row.contactID != null ||
            row.channelIndex != envelope.channelIndex || row.channelOccupantID != occupantID ||
            row.text != envelope.messageText
        ) {
            throw retainedChannel(pendingID, PersistenceStoreException(PersistenceStoreError.InvalidData,
                QueuedChannelAttemptRejected(EntityKey(token.radioId, pendingID),
                    QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)))
        }
        val inlineAdmission = if (newSubmission == null) null else {
            dataStore as? InlineChannelAttemptPersisting
                ?: throw retainedChannel(pendingID, PersistenceStoreException(PersistenceStoreError.InvalidData,
                    UnsupportedOperationException("Atomic inline channel admission is not wired")))
        }
        val stamp = if (!envelope.isResend || preserveTimestamp) row.timestamp else timestamp()
        val notification = if (trackNotification) currentCoroutineContext()[NotificationActionReceipt] else null
        val ownsOutcome = notification?.state == NotificationActionReceipt.State.READY
        val wireContext = if (trackNotification) currentCoroutineContext() else null
        synchronized(lock) { failedNotifications.remove(envelope.messageID) }
        var retainedFailure: QueuedChannelRetainedException? = null
        var beginFailure: PersistenceStoreException? = null
        fun retain(failure: Throwable) = QueuedChannelRetainedException(pendingID, failure).also { retainedFailure = it }
        fun reject(failure: PersistenceStoreException) = ChannelPrewireResult.Rejected(failure).also { retain(failure) }
        return try {
            val attemptKey = QueuedChannelAttemptKey(token.radioId, pendingID, envelope.messageID, newID())
            val result = lease.owner.dispatch(lease, envelope.channelIndex, occupantID,
                dataStore::fetchChannelOccupantID) {
                checkIdentity("sendQueuedChannel.admitted")
                // Observational guard only; storage must atomically exclude racing siblings at begin.
                val siblings = try {
                    dataStore.fetchPendingSendsForMessage(key(envelope.messageID))
                } catch (failure: PersistenceStoreException) {
                    return@dispatch ChannelPrewireResult.ReadFailed(failure)
                }
                if (siblings.any { it.id != pendingID }) {
                    return@dispatch reject(PersistenceStoreException(PersistenceStoreError.InvalidData,
                        QueuedChannelAttemptRejected(EntityKey(token.radioId, pendingID),
                            QueuedChannelAttemptConflict.RETAINED_SUBMISSION)))
                }
                journalStarted()
                if (ownsOutcome) notification?.wireStarted()
                try {
                    // Fresh inline intent and its attempt commit together, never as restart-ready work.
                    if (newSubmission != null) {
                        requireNotNull(inlineAdmission).beginInlineChannelAttempt(newSubmission, attemptKey, occupantID, stamp)
                    } else {
                        dataStore.beginQueuedChannelAttempt(attemptKey, occupantID, stamp)
                    }
                } catch (failure: PersistenceStoreException) {
                    // A lost begin response may have committed DISPATCHING. Never infer READY or send.
                    if (ownsOutcome) notification?.uncertain()
                    beginFailure = failure
                    try { dataStore.fetchQueuedChannelAttempt(attemptKey) }
                    catch (readFailure: PersistenceStoreException) {
                        if (readFailure !== failure) failure.addSuppressed(readFailure)
                    }
                    return@dispatch reject(failure)
                }
                val dispatch = MessageWriteDispatch()
                var wireEntered = false
                var wireAccepted = false
                suspend fun wire() {
                    wireEntered = true
                    session.sendChannelMessage(envelope.channelIndex, envelope.messageText,
                        Instant.ofEpochSecond(stamp.toLong()), dispatch)
                    wireAccepted = true
                    if (ownsOutcome) notification?.accepted()
                }
                try {
                    // Restore inline cancellation only for the protocol task, not its durable settlement.
                    if (wireContext == null) wire() else withContext(wireContext) { wire() }
                } catch (cancelled: CancellationException) {
                    val unsent = !wireEntered ||
                        dispatch.cancellationDisposition == ChannelWriteCancellationDisposition.DEFINITELY_UNSENT
                    try {
                        if (unsent && !wireAccepted && retireUnsent != null) {
                            retireUnsent(attemptKey)
                        } else {
                            settleQueuedChannel(attemptKey, when {
                                wireAccepted -> QueuedChannelAttemptOutcome.ACCEPTED
                                unsent -> QueuedChannelAttemptOutcome.DEFINITELY_UNSENT
                                else -> QueuedChannelAttemptOutcome.UNCERTAIN
                            })
                        }
                    } catch (settlement: Exception) {
                        if (ownsOutcome) notification?.uncertain()
                        val retained = if (settlement is QueuedChannelRetainedException) settlement else retain(settlement)
                        retainedFailure = retained
                        cancelled.addSuppressed(retained)
                        if (wireAccepted) return@dispatch ChannelPrewireResult.CancelledAccepted(cancelled)
                        throw cancelled
                    }
                    if (wireAccepted) return@dispatch ChannelPrewireResult.CancelledAccepted(cancelled)
                    if (ownsOutcome) {
                        if (unsent) notification?.wireNotDispatched() else notification?.uncertain()
                    }
                    // Leave the fence normally only after exact settlement, not on raw ERROR.
                    if (unsent) return@dispatch ChannelPrewireResult.CancelledUnsent(cancelled)
                    throw cancelled
                } catch (failure: Exception) {
                    if (ownsOutcome) notification?.uncertain()
                    try { settleQueuedChannel(attemptKey, QueuedChannelAttemptOutcome.UNCERTAIN) }
                    catch (settlement: PersistenceStoreException) {
                        throw retain(settlement).also {
                            if (settlement !== failure) it.addSuppressed(failure)
                        }
                    }
                    throw retain(failure)
                }
                // Inside the admitted operation: cancellation on dispatch return cannot erase acceptance.
                try {
                    ChannelPrewireResult.Accepted(settleQueuedChannel(attemptKey, QueuedChannelAttemptOutcome.ACCEPTED))
                } catch (failure: PersistenceStoreException) {
                    reject(failure)
                }
            }
            when (result) {
                is ChannelPrewireResult.Accepted -> result.attempt
                is ChannelPrewireResult.Rejected -> throw requireNotNull(retainedFailure)
                is ChannelPrewireResult.ReadFailed -> throw result.failure
                is ChannelPrewireResult.CancelledUnsent -> throw result.cancellation
                is ChannelPrewireResult.CancelledAccepted -> throw result.cancellation
            }
        } catch (failure: QueuedChannelRetainedException) {
            retainedFailure = failure
            throw failure
        } catch (failure: ChannelDispatchFailure) { throw retain(failure) }
        finally {
            // Prompt cancellation on fence return must not discard the captured persistence failure.
            retainedFailure?.let { retained ->
                beginFailure?.let { failure ->
                    try { remember("channelBegin.$pendingID", failure) }
                    catch (reporterFailure: Exception) {
                        if (reporterFailure !== retained && reporterFailure !== failure) retained.addSuppressed(reporterFailure)
                    }
                }
                recordRetainedChannel(retained)
            }
        }
    }

    private fun retainedChannel(pendingID: UUID, failure: Throwable): QueuedChannelRetainedException =
        recordRetainedChannel(QueuedChannelRetainedException(pendingID, failure))

    private fun recordRetainedChannel(failure: QueuedChannelRetainedException): QueuedChannelRetainedException =
        failure.also {
            try { remember("channelRetained.${it.pendingID}", it) }
            catch (reporterFailure: Exception) {
                if (reporterFailure !== it && reporterFailure !== it.cause) it.addSuppressed(reporterFailure)
            }
        }

    private suspend fun settleQueuedChannel(
        attemptKey: QueuedChannelAttemptKey, outcome: QueuedChannelAttemptOutcome,
    ): QueuedChannelAttempt = try {
        dataStore.settleQueuedChannelAttempt(attemptKey, outcome)
    } catch (failure: PersistenceStoreException) {
        val readback = try { dataStore.fetchQueuedChannelAttempt(attemptKey) }
        catch (readFailure: PersistenceStoreException) {
            if (readFailure !== failure) failure.addSuppressed(readFailure)
            null
        }
        if (readback?.phase == outcome.phase) readback
        else throw failure
    }

    internal suspend fun completeQueuedChannel(attemptKey: QueuedChannelAttemptKey) =
        owned("completeQueuedChannel", channelOperations) {
            completeChannelAttempt(attemptKey)
        }

    private suspend fun completeChannelAttempt(attemptKey: QueuedChannelAttemptKey) {
        val accepted = dataStore.fetchQueuedChannelAttempt(attemptKey)
        if (attemptKey.radioId != token.radioId || accepted?.phase != QueuedChannelAttemptPhase.ACCEPTED) {
            throw retainedChannel(attemptKey.pendingID, PersistenceStoreException(PersistenceStoreError.InvalidData,
                QueuedChannelAttemptRejected(EntityKey(attemptKey.radioId, attemptKey.pendingID),
                    QueuedChannelAttemptConflict.NOT_ACCEPTED)))
        }
        if (accepted.isCompleted) return
        withContext(NonCancellable) {
            try { dataStore.completeAcceptedQueuedChannelAttempt(attemptKey) }
            catch (failure: PersistenceStoreException) {
                val readback = try { dataStore.fetchQueuedChannelAttempt(attemptKey) }
                catch (readFailure: PersistenceStoreException) {
                    if (readFailure !== failure) failure.addSuppressed(readFailure)
                    null
                }
                if (readback?.phase != QueuedChannelAttemptPhase.ACCEPTED || !readback.isCompleted) {
                    throw retainedChannel(attemptKey.pendingID, failure)
                }
            }
        }
        ownership.check("completeQueuedChannel.saved")
        recovered("channelRetained.${attemptKey.pendingID}")
        if (accepted.isResend) events.yield(MessageStatusEvent.Resent(attemptKey.messageID))
        else {
            if (dataStore.fetchMessage(key(attemptKey.messageID))?.status != MessageStatus.DELIVERED) {
                events.yield(MessageStatusEvent.StatusResolved(attemptKey.messageID, MessageStatus.SENT, null))
            }
            bookkeeping("channelDate.${attemptKey.messageID}") {
                dataStore.applyChannelMessageMetadataIfCurrentOccupant(
                    ChannelHistoryScope(attemptKey.radioId, accepted.channelIndex, accepted.channelOccupantID),
                    clock.wallClock.instant(), incrementUnreadCount = false,
                )
            }
        }
    }

    private suspend fun queuedChannel(id: UUID, isResend: Boolean, preserveTimestamp: Boolean): UInt =
        owned("queuedChannel", channelOperations) {
            val row = sendBoundary(id, false) {
                val row = fetchSaved(id)
                if (row.channelIndex == null) throw MessageServiceException(MessageServiceError.SendFailed("Not a channel message"))
                row
            }
            inlineChannel(row, isResend, preserveTimestamp, notifyFailure = false)
        }

    private suspend fun inlineChannel(
        row: MessageDTO, isResend: Boolean, preserveTimestamp: Boolean, notifyFailure: Boolean,
    ): UInt {
        val submissions = dataStore.fetchPendingSendsForMessage(key(row.id))
        val pending = submissions.singleOrNull()
        val envelope = ChannelMessageEnvelope(row.id, requireNotNull(row.channelIndex), isResend,
            row.text, row.timestamp, pending?.localNodeName, row.channelOccupantID)
        val pendingID = pending?.id ?: newID()
        if (row.radioId != token.radioId || row.contactID != null || row.direction != MessageDirection.OUTGOING ||
            row.channelOccupantID == null) {
            throw retainedChannel(pendingID, PersistenceStoreException(PersistenceStoreError.InvalidData,
                QueuedChannelAttemptRejected(key(pendingID), QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)))
        }
        if (submissions.size > 1 || pending != null && pending.channelMessageEnvelope() != envelope) {
            throw retainedChannel(pendingID, PersistenceStoreException(PersistenceStoreError.InvalidData,
                QueuedChannelAttemptRejected(key(pendingID), QueuedChannelAttemptConflict.RETAINED_SUBMISSION)))
        }
        if (pending?.channelDispatchState == QueuedChannelDispatchState.ACCEPTED) {
            val attemptID = pending.channelAttemptID
                ?: throw retainedChannel(pendingID, PersistenceStoreException(PersistenceStoreError.InvalidData))
            val attemptKey = QueuedChannelAttemptKey(token.radioId, pendingID, row.id, attemptID)
            val accepted = dataStore.fetchQueuedChannelAttempt(attemptKey)
                ?: throw retainedChannel(pendingID, PersistenceStoreException(PersistenceStoreError.InvalidData))
            ownership.check("inlineChannel.recoverAccepted")
            completeChannelAttempt(attemptKey)
            return accepted.wireTimestamp
        }
        if (pending != null && (pending.channelDispatchState != QueuedChannelDispatchState.READY ||
                pending.channelAttemptID != null)) {
            throw retainedChannel(pendingID, PersistenceStoreException(PersistenceStoreError.InvalidData,
                QueuedChannelAttemptRejected(key(pendingID), QueuedChannelAttemptConflict.SUBMISSION_NOT_READY)))
        }
        val terminal = dataStore as? QueuedChannelTerminalPersisting
            ?: throw PersistenceStoreException(PersistenceStoreError.InvalidData,
                UnsupportedOperationException("Inline channel cancellation retirement is not wired"))
        var journalEntered = false
        var retirement: Pair<QueuedChannelAttemptKey, QueuedChannelRetirement>? = null
        val accepted = try {
            dispatchChannelAttempt(pendingID, envelope, preserveTimestamp,
                newSubmission = if (pending == null) PendingSendDTO.fromEnvelope(
                    envelope, token.radioId, pendingID, clock.wallClock.instant(),
                ) else null,
                trackNotification = true,
                journalStarted = { journalEntered = true },
                retireUnsent = { attemptKey ->
                    retirement = attemptKey to terminal.settleAndRetireDefinitelyUnsentQueuedChannelAttempt(attemptKey)
                })
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (notifyFailure && ownership.isCurrent) retirement?.let { (attemptKey, receipt) ->
                    notifyQueuedChannelRetired(attemptKey, receipt)
                }
            }
            if (!journalEntered && pending == null) {
                failMessageAndRethrow(cancelled, row.id, notifyFailure, failDefinitelyUnsentInline = true)
            }
            throw cancelled
        } catch (failure: QueuedChannelRetainedException) {
            if (!journalEntered && pending == null) {
                failMessageAndRethrow(failure, row.id, notifyFailure, failDefinitelyUnsentInline = true)
            }
            throw failure
        } catch (failure: MessageServiceException) {
            if (!journalEntered && pending == null) {
                failMessageAndRethrow(failure, row.id, notifyFailure, failDefinitelyUnsentInline = true)
            }
            throw failure
        }
        ownership.check("inlineChannel.accepted")
        completeChannelAttempt(accepted.key)
        return accepted.wireTimestamp
    }

    private suspend fun <T> bookkeeping(operation: String, block: suspend () -> T) {
        try { block(); recovered(operation) }
        catch (failure: PersistenceStoreException) { remember(operation, failure) }
    }

    private suspend fun <T> withPoolBackoff(
        code: UByte, retryOnDeviceError: Boolean = true, block: suspend () -> T,
    ): T {
        var attempt = 0L
        while (true) {
            try { return block() }
            catch (failure: MeshCoreException.DeviceError) {
                if (!retryOnDeviceError || failure.code != code || attempt >= config.poolBackoff.attemptCap) throw failure
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
                    when (val result = authority().revokeDirectAckSession(directLease())) {
                        DirectAckRevocationResult.Revoked, DirectAckRevocationResult.AlreadyRevoked -> Unit
                        is DirectAckRevocationResult.Rejected -> throw authorityFailure(result.reason.name)
                    }
                } catch (failure: PersistenceStoreException) { remember("directAckRevoke", failure) }
            }
            synchronized(lock) {
                pendingAcks.values.forEach { it.receipt.cancel() }
                pendingAcks.clear()
                directAuthorities.clear()
                directRegistrationEpochs.clear()
                directWireObservations.clear()
                directRegistrationIDs.clear()
                noDispatchCompletions.clear()
                queuePreparations.clear()
                uncertainFailureWrites.clear()
                directClaims.clear()
                activeDirectClaims.clear()
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
