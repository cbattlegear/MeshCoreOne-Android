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
    private class DirectAuthorityState(
        val requestID: UUID,
        val attempt: DirectAckAttempt,
        firstWireID: UUID,
    ) {
        val wires = mutableMapOf(0 to firstWireID)
        val operations = mutableMapOf<String, UUID>()
    }
    private val directAuthorities = mutableMapOf<UUID, DirectAuthorityState>()
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
    private val failedNotifications = mutableSetOf<UUID>()
    private val acceptedChannels = mutableMapOf<UUID, UInt>()
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

    private fun directAuthority(id: UUID): DirectAuthorityState =
        synchronized(lock) { directAuthorities[id] } ?: throw authorityFailure("unregistered message")

    private suspend fun registerDirectDispatch(
        id: UUID, requestID: UUID, predicted: Bytes, wireIndex: Int,
        intent: DirectAckIntent,
    ): Pair<DirectAuthorityState, UUID> {
        val prior = synchronized(lock) { directAuthorities[id] }
        if (prior?.requestID == requestID) {
            val wireID = synchronized(lock) { prior.wires.getOrPut(wireIndex, UUID::randomUUID) }
            requireReceipt(authority().registerDirectAckDispatch(prior.attempt, wireID, predicted.ackCodeUInt32))
            return prior to wireID
        }
        val wireID = synchronized(lock) { directRegistrationIDs.getOrPut(requestID, UUID::randomUUID) }
        val result = authority().beginDirectAckAttempt(intent, wireID, predicted.ackCodeUInt32)
        val (attempt, receipt) = when (result) {
            is DirectAckBeginResult.Begun -> result.attempt to result.receipt
            is DirectAckBeginResult.AlreadyBegun -> result.attempt to result.receipt
            is DirectAckBeginResult.Rejected -> throw authorityFailure(result.reason.name)
        }
        if (receipt.permission != DirectAckPermission.CURRENT) throw authorityFailure(receipt.permission.name)
        val state = DirectAuthorityState(requestID, attempt, wireID)
        synchronized(lock) {
            directAuthorities[id] = state
            pendingAcks.remove(id)?.receipt?.cancel()
        }
        return state to wireID
    }

    private suspend fun recordDirectAcceptance(state: DirectAuthorityState, wireID: UUID, info: MessageSentInfo) {
        val receipt = mutationReceipt(authority().recordDirectSendAccepted(state.attempt, wireID, info.expectedAck.ackCodeUInt32))
        if (receipt.wires.none { it.requestId == wireID && it.acceptedAckCode == info.expectedAck.ackCodeUInt32 }) {
            throw authorityFailure("accepted wire binding missing")
        }
    }

    private suspend fun directBookkeeping(id: UUID, operation: String, change: DirectAckBookkeeping): DirectAckReceipt {
        val state = directAuthority(id)
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
    internal suspend fun installPendingAck(tracking: PendingAck) {
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
                registerDirectDispatch(tracking.messageID, requestID, code, index, intent)
            }
        }
        synchronized(lock) {
            pendingAcks[tracking.messageID] = tracking.copy(authority = directAuthorities[tracking.messageID]?.attempt)
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

    internal suspend fun handleAcknowledgement(code: Bytes, tripTime: UInt?) {
        if (!ownership.isCurrent) {
            ownership.reporter.report(MessagingDiagnostic.StaleResult(token, "acknowledgement"))
            return
        }
        checkIdentity("acknowledgement")
        val tracking = synchronized(lock) {
            val found = pendingAcks.values.firstOrNull { code in it.ackCodes && !it.isDelivered }
            found?.copy(isDelivered = true, acknowledgement = MeshAcknowledgement(code, tripTime))?.also {
                pendingAcks[it.messageID] = it
                activeDirectClaims[it.messageID]?.acknowledgement = it.acknowledgement
                it.receipt.complete(requireNotNull(it.acknowledgement))
            }
        }
        if (tracking == null) {
            bookkeeping("unmatchedAckDiagnostic") {
                val orphanSentRow = dataStore.hasOutgoingSentDM(token.radioId, code.ackCodeUInt32)
                ownership.check("unmatchedAcknowledgement.read")
                ownership.reporter.report(MessagingDiagnostic.UnmatchedAcknowledgement(token, orphanSentRow))
            }
            return
        }
        reconcileAcknowledgement(tracking.messageID)
    }

    private suspend fun reconcileAcknowledgement(
        messageID: UUID,
        cleanup: Boolean = false,
        failureOperation: String? = null,
    ) = mutation(messageID).withLock {
        try {
            reconcileAcknowledgementLocked(messageID, cleanup)
        } catch (failure: PersistenceStoreException) {
            if (!cleanup && !ownership.isCurrent) throw failure
            if (failureOperation != null) remember(failureOperation, failure)
            throw failure
        }
    }

    private suspend fun reconcileAcknowledgementLocked(
        messageID: UUID, cleanup: Boolean, verifyRadioIdentity: Boolean = true,
    ) {
        val tracking = pendingAck(messageID) ?: return
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
        val committed = dataStore.fetchMessage(key(messageID))
        checkOwnership("commitAcknowledgement.refetched")
        if (committed?.status != MessageStatus.DELIVERED || committed.ackCode != delivery.ackCode ||
            committed.roundTripTime != delivery.roundTripTime) throw authorityFailure("delivery readback changed")
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

    private fun retireTerminalAck(tracking: PendingAck) {
        synchronized(lock) {
            if (pendingAcks[tracking.messageID]?.receipt === tracking.receipt) pendingAcks.remove(tracking.messageID)
            if (uncertainFailureWrites[tracking.messageID]?.tracking?.receipt === tracking.receipt) {
                uncertainFailureWrites.remove(tracking.messageID)
            }
        }
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
    ): Boolean {
        val acknowledgementArrived = synchronized(lock) {
            val tracking = pendingAcks[messageID]
            if (tracking?.acknowledgement != null) {
                true
            }
            else {
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
        if (!acknowledgementArrived) {
            if (notify && changed) broadcastFailed(messageID)
            recovered("failPending.$messageID")
            recovered("ackUncertain.$messageID")
        }
        return acknowledgementArrived
    }

    private suspend fun writeFailedStatus(messageID: UUID, wasFailed: Boolean): Boolean {
        val tracking = pendingAck(messageID)
        try {
            val attempt = tracking?.authority ?: synchronized(lock) {
                if (tracking == null) directAuthorities[messageID]?.attempt else null
            }
            if (attempt == null && tracking == null) {
                return dataStore.updateMessageStatusUnlessDelivered(key(messageID), MessageStatus.FAILED)
            }
            if (attempt == null && wasFailed) return false
            if (attempt == null && dataStore.fetchMessage(key(messageID))?.status == MessageStatus.DELIVERED) return false
            val result = authority().recordDirectFailure(
                attempt ?: throw authorityFailure("unregistered failure"),
            )
            if (result is DirectAckMutationResult.Rejected && result.reason in retiredAuthorityReasons) {
                ownership.reporter.report(MessagingDiagnostic.StaleResult(token, "failureAuthority.${result.reason}"))
                tracking?.let(::retireTerminalAck)
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

    internal suspend fun failMessageAndRethrow(
        failure: Exception,
        messageID: UUID,
        notify: Boolean = false,
        failDefinitelyUnsentInline: Boolean = false,
    ): Nothing {
        // Complete only the already-persisted send's failure before teardown returns, never a successor's work.
        val notificationReceipt = currentCoroutineContext()[NotificationActionReceipt]
        withContext(NonCancellable) {
            mutation(messageID).withLock {
                try {
                    val failUnsentInlineRow = notificationReceipt == null || failDefinitelyUnsentInline
                    if (notificationReceipt == null || notificationReceipt.state == NotificationActionReceipt.State.READY) {
                        if (failUnsentInlineRow) {
                            if (pendingAck(messageID)?.acknowledgement == null) {
                                val wasFailed = dataStore.fetchMessage(key(messageID))?.status == MessageStatus.FAILED
                                ownership.checkPersistenceCompletion("failMessage")
                                val changed = writeFailedStatus(messageID, wasFailed)
                                ownership.checkPersistenceCompletion("failMessage.saved")
                                settleFailedPendingAck(
                                    messageID, changed, notify, retireDirectClaim = false,
                                )
                            }
                        } else {
                            synchronized(lock) {
                                if (pendingAcks[messageID]?.acknowledgement == null) pendingAcks.remove(messageID)
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
        block: suspend () -> T,
    ): T =
        try { block() }
        catch (cancelled: CancellationException) { failMessageAndRethrow(cancelled, id, notify, failDefinitelyUnsentInline) }
        catch (failure: MeshCoreException) { failMessageAndRethrow(failure, id, notify, failDefinitelyUnsentInline) }
        catch (failure: MeshTransportError) { failMessageAndRethrow(failure, id, notify, failDefinitelyUnsentInline) }
        catch (failure: MessageServiceException) { failMessageAndRethrow(failure, id, notify, failDefinitelyUnsentInline) }
        catch (failure: PersistenceStoreException) { failMessageAndRethrow(failure, id, notify, failDefinitelyUnsentInline) }

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

    private suspend fun <T> notificationWire(block: suspend (MessageWriteDispatch?) -> T): T {
        val receipt = currentCoroutineContext()[NotificationActionReceipt]
        if (receipt == null) return block(null)
        val dispatch = MessageWriteDispatch()
        val ownsOutcome = receipt.state == NotificationActionReceipt.State.READY
        if (ownsOutcome) receipt.wireStarted()
        try {
            val result = block(dispatch)
            if (ownsOutcome) receipt.accepted()
            return result
        } catch (cancelled: CancellationException) {
            if (ownsOutcome) {
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
        notificationWire(block)

    override suspend fun sendDirectMessage(text: String, contact: ContactDTO, textType: TextType, replyToID: UUID?): MessageDTO =
        withFreshNotificationAction("sendDirectMessage") {
            owned("sendDirectMessage") {
                validate(text, contact)
                val message = outgoing(text, contact, textType, replyToID)
                val (info, tracking, predicted) = sendBoundary(message.id, true, failDefinitelyUnsentInline = true) {
                    dataStore.saveMessage(message)
                    ownership.check("sendDirectMessage.saved")
                    checkIdentity("sendDirectMessage")
                    val requestID = UUID.randomUUID()
                    val prepared = prepareDirect(message.id, requestID, contact, DirectAckMode.SEND, null)
                    val captured = prepared.message
                    val selected = prepared.contact
                    val predicted = AckCodeBuilder.expectedAck(captured.timestamp, 0u, captured.text, localPublicKey)
                    registerDirectDispatch(message.id, requestID, predicted, 0, prepared.intent)
                    val tracking = trackPendingAck(message.id, selected.id, predicted, checkInterval, selected.publicKey)
                    val info = withPoolBackoff(3u) {
                        dmOperations.withLock {
                            checkIdentity("sendDirectMessage.wire")
                            inlineWire { dispatch ->
                                val timestamp = Instant.ofEpochSecond(captured.timestamp.toLong())
                                if (dispatch == null) session.sendMessage(selected.publicKey, captured.text, timestamp)
                                else session.sendMessage(selected.publicKey, captured.text, timestamp, dispatch = dispatch)
                            }
                        }
                    }
                    mergeSentInfo(message.id, predicted, info, 0u, null)
                    Triple(info, tracking, predicted)
                }
                postDMSent(message.id, contact.id, info)
                ownership.check("sendDirectMessage.accepted")
                if (pendingAck(message.id)?.receipt === tracking.receipt) {
                    mergeSentInfo(message.id, predicted, info, 0u, null)
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
            directClaims.remove(claimID)?.let { claim ->
                if (activeDirectClaims[claim.messageID] === claim) activeDirectClaims.remove(claim.messageID)
            }
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
                        else completedDirect(claim) ?: notificationWire { dispatch ->
                            val timestamp = Instant.ofEpochSecond(stamp.toLong())
                            if (dispatch == null) {
                                session.sendMessage(contact.publicKey.prefix(6), text, timestamp, attempt)
                            } else {
                                session.sendMessage(contact.publicKey.prefix(6), text, timestamp, attempt, dispatch)
                            }
                        }
                    }
                } ?: return null
                synchronized(lock) {
                    claim.lastSentInfo = info
                    claim.acceptedWire = AcceptedDirectWire(wireID, info)
                }
                mergeSentInfo(id, predicted, info, attempt, timeout)
                recordDirectAcceptance(authorityState, wireID, info)
                ownership.check("retryDirectMessage.accepted")
                completedDirect(claim)?.let { return it }
                if (unacknowledgedRetirement(claim)) return null
                val ackTimeout = timeout ?: maxOf(config.minTimeout, info.suggestedTimeoutMs.toDouble() / 1000.0 * 1.2)
                val outcome = clock.messagingDeadline(ackTimeout) {
                    while (true) {
                        val ack = select<MeshAcknowledgement> {
                            tracking.receipt.onAwait { it }
                            mailbox.onReceive { MeshAcknowledgement(it.code, it.tripTime) }
                        }
                        ownership.check("retryDirectMessage.ack")
                        if (ack.code in (pendingAck(id)?.ackCodes ?: tracking.ackCodes)) {
                            handleAcknowledgement(ack.code, ack.tripTime)
                            return@messagingDeadline true
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
    ) {
        val seconds = timeout ?: maxOf(config.minTimeout, info.suggestedTimeoutMs.toDouble() / 1000.0 * 1.2)
        if (info.expectedAck != predicted) {
            ownership.reporter.report(MessagingDiagnostic.AckCodeMismatch(token, id, attempt))
        }
        synchronized(lock) {
            val current = pendingAcks[id] ?: return
            if (current.authority !== directAuthorities[id]?.attempt) return
            if (info.expectedAck != predicted) {
                pendingAcks.values.firstOrNull { it.messageID != id && info.expectedAck in it.ackCodes }?.let {
                    ownership.reporter.report(MessagingDiagnostic.AckCodeCollision(token, id, it.messageID))
                    throw MessageServiceException(MessageServiceError.SendFailed("Ambiguous acknowledgement identity"))
                }
            }
            pendingAcks[id] = current.copy(
                ackCodes = (current.ackCodes + listOf(info.expectedAck)).snapshotSet(),
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
                val acknowledgement = MeshAcknowledgement(sentInfo.expectedAck, null)
                synchronized(lock) {
                    pendingAcks[messageID] = current.copy(isDelivered = true, acknowledgement = acknowledgement)
                    current.receipt.complete(acknowledgement)
                    activeDirectClaims[messageID]?.acknowledgement = acknowledgement
                }
                reconcileAcknowledgementLocked(messageID, cleanup = false, verifyRadioIdentity = false)
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

    private suspend fun postDMSent(id: UUID, contactID: UUID, info: MessageSentInfo) = withContext(NonCancellable) {
        bookkeeping("dmSent.$id") {
            mutation(id).withLock {
                ownership.checkPersistenceCompletion("dmSent")
                val state = directAuthority(id)
                val wireID = synchronized(lock) { requireNotNull(state.wires[0]) }
                recordDirectAcceptance(state, wireID, info)
                ownership.checkPersistenceCompletion("dmSent.saved")
                dataStore.updateContactLastMessage(key(contactID), clock.wallClock.instant())
                ownership.checkPersistenceCompletion("dmSent.contact")
                val row = dataStore.fetchMessage(key(id))
                ownership.checkPersistenceCompletion("dmSent.refetched")
                if (ownership.isCurrent && row?.status == MessageStatus.SENT) {
                    events.yield(MessageStatusEvent.StatusResolved(id, MessageStatus.SENT, null))
                }
            }
            if (ownership.isCurrent && pendingAck(id)?.acknowledgement != null) {
                bookkeeping("ack.$id") { reconcileAcknowledgement(id) }
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
                sendBoundary(dto.id, true, failDefinitelyUnsentInline = true) {
                    dataStore.saveMessage(dto)
                    ownership.check("sendChannelMessage.persisted")
                    withPoolBackoff(2u) {
                        channelTransmission(channelIndex) {
                            checkIdentity("sendChannelMessage.wire")
                            inlineWire { dispatch ->
                                val timestamp = Instant.ofEpochSecond(dto.timestamp.toLong())
                                if (dispatch == null) session.sendChannelMessage(channelIndex, text, timestamp)
                                else session.sendChannelMessage(channelIndex, text, timestamp, dispatch = dispatch)
                            }
                        }
                    }
                }
                ownership.check("sendChannelMessage.accepted")
                bookkeeping("inlineChannel.${dto.id}") {
                    dataStore.updateMessageStatus(key(dto.id), MessageStatus.SENT)
                    ownership.check("sendChannelMessage.saved")
                    events.yield(MessageStatusEvent.StatusResolved(dto.id, MessageStatus.SENT, null))
                    updateChannelDate(channelIndex)
                }
                ChannelSendReceipt(dto.id, dto.timestamp)
            }
        }

    override suspend fun sendPendingChannelMessage(messageID: UUID) { queuedChannel(messageID, false, true) }
    override suspend fun resendChannelMessage(messageID: UUID, preserveTimestamp: Boolean): UInt =
        queuedChannel(messageID, true, preserveTimestamp)

    private suspend fun queuedChannel(id: UUID, isResend: Boolean, preserveTimestamp: Boolean): UInt =
        owned("queuedChannel", channelOperations) {
            synchronized(lock) { failedNotifications.remove(id) }
            val row = sendBoundary(id, false) {
                val row = fetchSaved(id)
                if (row.channelIndex == null) throw MessageServiceException(MessageServiceError.SendFailed("Not a channel message"))
                row
            }
            val index = requireNotNull(row.channelIndex)
            val accepted = synchronized(lock) { acceptedChannels[id] }
            val stamp = accepted ?: if (!isResend || preserveTimestamp) row.timestamp else timestamp()
            if (accepted == null) {
                sendBoundary(id, false) {
                    if (isResend && !preserveTimestamp) dataStore.updateMessageTimestamp(key(id), stamp)
                    ownership.check("queuedChannel.timestamp")
                    withPoolBackoff(2u) {
                        channelTransmission(index) {
                            checkIdentity("queuedChannel.wire")
                            session.sendChannelMessage(index, row.text, Instant.ofEpochSecond(stamp.toLong()))
                        }
                    }
                }
                ownership.check("queuedChannel.accepted")
                synchronized(lock) { acceptedChannels[id] = stamp }
            }
            try {
                dataStore.updateMessageStatus(key(id), MessageStatus.SENT)
                ownership.check("queuedChannel.saved")
                recovered("channelCommit.$id")
            } catch (failure: PersistenceStoreException) {
                remember("channelCommit.$id", failure)
                throw failure
            }
            if (isResend) {
                bookkeeping("channelResend.$id") {
                    dataStore.incrementMessageSendCount(key(id))
                    dataStore.updateMessageHeardRepeats(key(id), 0)
                    dataStore.deleteMessageRepeats(key(id))
                }
                ownership.check("queuedChannel.resent")
                events.yield(MessageStatusEvent.Resent(id))
            } else {
                events.yield(MessageStatusEvent.StatusResolved(id, MessageStatus.SENT, null))
                bookkeeping("channelDate.$id") { updateChannelDate(index) }
            }
            synchronized(lock) { acceptedChannels.remove(id) }
            stamp
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
                directRegistrationIDs.clear()
                noDispatchCompletions.clear()
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
