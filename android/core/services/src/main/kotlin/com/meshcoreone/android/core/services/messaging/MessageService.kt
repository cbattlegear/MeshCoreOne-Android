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
    private val channelDispatchLease: ChannelDispatchFence.Lease? = null,
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
    private val failedAckRestorations = mutableMapOf<UUID, PendingAck>()
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
    internal fun installPendingAck(tracking: PendingAck) { synchronized(lock) { pendingAcks[tracking.messageID] = tracking } }
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
            publicKey = publicKey)
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

    private suspend fun reconcileAcknowledgementLocked(messageID: UUID, cleanup: Boolean) {
        val tracking = pendingAck(messageID) ?: return
        val ack = tracking.acknowledgement ?: return
        if (!cleanup) checkIdentity("commitAcknowledgement")
        val row = dataStore.fetchMessage(key(messageID))
        if (!cleanup) ownership.check("commitAcknowledgement.read")
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
            if (!cleanup) ownership.check("commitAcknowledgement.contact")
            if (contact != null && contact.publicKey != tracking.publicKey) {
                val failure = MessageServiceException(MessageServiceError.ContactNotFound)
                remember("ack.$messageID.identity", failure)
                retireTerminalAck(tracking)
                throw failure
            }
        }
        val restoresOwnFailure = synchronized(lock) {
            failedAckRestorations[messageID]?.receipt === tracking.receipt
        }
        if (row.status == MessageStatus.FAILED && restoresOwnFailure) {
            dataStore.updateMessageStatusUnlessDelivered(key(messageID), MessageStatus.SENT)
            if (!cleanup) ownership.check("commitAcknowledgement.restoreForAck")
        }
        dataStore.updateMessageAck(key(messageID), ack.code.ackCodeUInt32, MessageStatus.DELIVERED, ack.tripTime)
        if (!cleanup) ownership.check("commitAcknowledgement.saved")
        val committed = dataStore.fetchMessage(key(messageID))
        if (!cleanup) ownership.check("commitAcknowledgement.refetched")
        if (committed?.status == MessageStatus.DELIVERED) {
            try {
                dataStore.updateContactLastMessage(key(tracking.contactID), clock.wallClock.instant())
                recovered("ackContact.$messageID")
            } catch (failure: PersistenceStoreException) { remember("ackContact.$messageID", failure) }
            if (!cleanup && ownership.isCurrent) events.yield(MessageStatusEvent.StatusResolved(messageID, MessageStatus.DELIVERED, ack.tripTime))
        }
        synchronized(lock) {
            if (pendingAcks[messageID]?.receipt === tracking.receipt) pendingAcks.remove(messageID)
            if (failedAckRestorations[messageID]?.receipt === tracking.receipt) failedAckRestorations.remove(messageID)
        }
        recovered("ack.$messageID")
        recovered("ackDelivery")
        recovered("failPending.$messageID")
        if (committed?.status == MessageStatus.DELIVERED) recovered("failMessage.$messageID")
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
                remember("ackExpiry.${snapshot.messageID}", failure)
            }
        }
        recovered("ackExpiry")
    }

    private fun retireTerminalAck(tracking: PendingAck) {
        synchronized(lock) {
            if (pendingAcks[tracking.messageID]?.receipt === tracking.receipt) pendingAcks.remove(tracking.messageID)
            if (failedAckRestorations[tracking.messageID]?.receipt === tracking.receipt) {
                failedAckRestorations.remove(tracking.messageID)
            }
        }
    }

    private suspend fun expireOrReconcile(snapshot: PendingAck, now: Instant) {
        if (snapshot.acknowledgement != null) { reconcileAcknowledgement(snapshot.messageID); return }
        var acknowledgementArrived = false
        mutation(snapshot.messageID).withLock {
            val current = pendingAck(snapshot.messageID) ?: return@withLock
            if (current.isDelivered || Duration.between(current.sentAt, now).toNanos().toDouble() / 1e9 <=
                maxOf(config.ackGiveUpWindow, current.timeout)) return@withLock
            val wasFailed = dataStore.fetchMessage(key(current.messageID))?.status == MessageStatus.FAILED
            ownership.check("expireAcknowledgement")
            val changed = dataStore.updateMessageStatusUnlessDelivered(key(current.messageID), MessageStatus.FAILED)
            ownership.check("expireAcknowledgement.saved")
            acknowledgementArrived = settleFailedPendingAck(
                current.messageID, changed, "expireAcknowledgement", notify = true,
                retireDirectClaim = true, restoreOnAck = !wasFailed,
                checkOwnership = { operation -> ownership.check(operation) },
            )
        }
        if (acknowledgementArrived) reconcileAcknowledgement(snapshot.messageID)
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
                    val changed = dataStore.updateMessageStatusUnlessDelivered(key(id), MessageStatus.FAILED)
                    ownership.check("failAllPendingMessages.saved")
                    acknowledgementArrived = settleFailedPendingAck(
                        id, changed, "failAllPendingMessages", notify = true,
                        retireDirectClaim = true, restoreOnAck = !wasFailed,
                        checkOwnership = { operation -> ownership.check(operation) },
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
        operation: String,
        notify: Boolean,
        retireDirectClaim: Boolean,
        restoreOnAck: Boolean,
        checkOwnership: suspend (String) -> Unit,
    ): Boolean {
        val acknowledgementArrived = synchronized(lock) {
            val tracking = pendingAcks[messageID]
            if (tracking?.acknowledgement != null) {
                if (changed && restoreOnAck) failedAckRestorations[messageID] = tracking
                true
            }
            else {
                pendingAcks.remove(messageID)
                if (retireDirectClaim) {
                    activeDirectClaims[messageID]?.let {
                        if (it.acknowledgement == null) it.retiredWithoutAcknowledgement = true
                    }
                }
                false
            }
        }
        if (acknowledgementArrived) {
            if (changed && restoreOnAck) {
                dataStore.updateMessageStatusUnlessDelivered(key(messageID), MessageStatus.SENT)
                checkOwnership("$operation.restoreForAck")
            }
        } else {
            if (notify && changed) broadcastFailed(messageID)
            recovered("failPending.$messageID")
        }
        return acknowledgementArrived
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
                                val changed = dataStore.updateMessageStatusUnlessDelivered(key(messageID), MessageStatus.FAILED)
                                ownership.checkPersistenceCompletion("failMessage.saved")
                                settleFailedPendingAck(
                                    messageID, changed, "failMessage", notify, retireDirectClaim = false,
                                    restoreOnAck = !wasFailed,
                                    checkOwnership = { operation -> ownership.checkPersistenceCompletion(operation) },
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
                    val predicted = AckCodeBuilder.expectedAck(message.timestamp, 0u, text, localPublicKey)
                    val tracking = trackPendingAck(message.id, contact.id, predicted, checkInterval, contact.publicKey)
                    val info = withPoolBackoff(3u) {
                        dmOperations.withLock {
                            checkIdentity("sendDirectMessage.wire")
                            inlineWire { dispatch ->
                                val timestamp = Instant.ofEpochSecond(message.timestamp.toLong())
                                if (dispatch == null) session.sendMessage(contact.publicKey, text, timestamp)
                                else session.sendMessage(contact.publicKey, text, timestamp, dispatch = dispatch)
                            }
                        }
                    }
                    Triple(info, tracking, predicted)
                }
                ownership.check("sendDirectMessage.accepted")
                if (pendingAck(message.id)?.receipt === tracking.receipt) {
                    mergeSentInfo(message.id, contact.id, predicted, info, 0u, null)
                }
                postDMSent(message.id, contact.id, info)
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
            val claim = newDirectClaim(UUID.randomUUID(), message, contact, false).also { it.timestamp = message.timestamp }
            try {
                sendBoundary(message.id, true) {
                    val info = retryLoop(claim, contact, message.timestamp, timeout.takeIf { it > 0 })
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
    ): MessageDTO = queuedDM(messageID, contact, preserveTimestamp, isResend, claimID)

    internal fun releaseDirectClaim(claimID: UUID) {
        synchronized(lock) {
            directClaims.remove(claimID)?.let { claim ->
                if (activeDirectClaims[claim.messageID] === claim) activeDirectClaims.remove(claim.messageID)
            }
        }
    }

    private fun newDirectClaim(claimID: UUID, row: MessageDTO, contact: ContactDTO, resend: Boolean): DirectSendClaim =
        synchronized(lock) {
            directClaims[claimID]?.also {
                if (it.messageID != row.id || it.contactID != contact.id || it.publicKey != contact.publicKey || it.isResend != resend) {
                    throw MessageServiceException(MessageServiceError.SendFailed("Pending-send claim identity changed"))
                }
            } ?: DirectSendClaim(claimID, row.id, contact.id, contact.publicKey, row.text, resend).also {
                directClaims[claimID] = it
                activeDirectClaims[row.id] = it
            }
        }

    private suspend fun queuedDM(id: UUID, contact: ContactDTO, preserveTimestamp: Boolean, isResend: Boolean, claimID: UUID): MessageDTO {
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
                        return@owned row
                    }
                    val claim = newDirectClaim(claimID, row, contact, isResend)
                    val wireTimestamp = synchronized(lock) { claim.timestamp } ?: (
                        if (preserveTimestamp) row.timestamp else timestamp().also {
                            dataStore.updateMessageTimestamp(key(id), it)
                            ownership.check("queuedDirectMessage.timestamp")
                        }
                    ).also { synchronized(lock) { claim.timestamp = it } }
                    sendBoundary(id, false) {
                        val info = if (synchronized(lock) { claim.wireFinished }) {
                            synchronized(lock) { claim.lastSentInfo }
                        } else retryLoop(claim, contact, wireTimestamp, null).also {
                            synchronized(lock) { claim.lastSentInfo = it; claim.wireFinished = true }
                        }
                        if (isResend && info != null && !synchronized(lock) { claim.sendCountCommitted }) {
                            try {
                                dataStore.incrementMessageSendCount(key(id))
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
        var attempts = 0L
        var floods = 0L
        var flood = false
        while (attempts < config.maxAttempts && (!flood || floods < config.maxFloodAttempts)) {
            checkIdentity("retryDirectMessage")
            completedDirect(claim)?.let { return it }
            if (unacknowledgedRetirement(claim)) return null
            if (attempts > 0) {
                dataStore.updateMessageRetryStatus(key(id), MessageStatus.RETRYING, attempts - 1, config.maxAttempts - 1)
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
            }
            completedDirect(claim)?.let { return it }
            if (unacknowledgedRetirement(claim)) return null
            val attempt = attempts.toUByte()
            val predicted = AckCodeBuilder.expectedAck(stamp, attempt, text, localPublicKey)
            val tracking = trackPendingAck(id, contact.id, predicted, maxOf(timeout ?: config.minTimeout, checkInterval), contact.publicKey)
            val mailbox = Channel<MeshEvent.Acknowledgement>(Channel.UNLIMITED)
            val stream = session.events(EventFilter.anyAcknowledgement)
            val collector = ownership.scope.async(start = CoroutineStart.UNDISPATCHED) {
                stream.collect { if (it is MeshEvent.Acknowledgement) mailbox.send(it) }
            }
            try {
                val info = withPoolBackoff(3u) {
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
                synchronized(lock) { claim.lastSentInfo = info }
                ownership.check("retryDirectMessage.accepted")
                completedDirect(claim)?.let { return it }
                if (unacknowledgedRetirement(claim)) return null
                val ackTimeout = timeout ?: maxOf(config.minTimeout, info.suggestedTimeoutMs.toDouble() / 1000.0 * 1.2)
                mergeSentInfo(id, contact.id, predicted, info, attempt, timeout)
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
        }
        return null
    }

    private fun mergeSentInfo(
        id: UUID, contactID: UUID, predicted: Bytes, info: MessageSentInfo, attempt: UByte, timeout: Double?,
    ) {
        val seconds = timeout ?: maxOf(config.minTimeout, info.suggestedTimeoutMs.toDouble() / 1000.0 * 1.2)
        if (info.expectedAck != predicted) {
            ownership.reporter.report(MessagingDiagnostic.AckCodeMismatch(token, id, attempt))
            trackPendingAck(id, contactID, info.expectedAck, seconds)
        } else synchronized(lock) {
            pendingAcks[id]?.let { pendingAcks[id] = it.copy(sentAt = clock.wallClock.instant(), timeout = seconds) }
        }
    }

    internal suspend fun finalizeSend(
        messageID: UUID, contactID: UUID, publicKey: Bytes, sentInfo: MessageSentInfo?, initialPathLength: UByte,
    ): MessageDTO {
        ownership.check("finalizeSend")
        val tracking = pendingAck(messageID)
        if (tracking?.acknowledgement != null) reconcileAcknowledgement(messageID)
        else mutation(messageID).withLock {
            ownership.check("finalizeSend.transition")
            val current = pendingAck(messageID)
            if (current == null || current.isDelivered) synchronized(lock) { pendingAcks.remove(messageID) }
            else if (sentInfo != null) {
                dataStore.updateMessageAck(key(messageID), sentInfo.expectedAck.ackCodeUInt32, MessageStatus.DELIVERED)
                ownership.check("finalizeSend.delivered")
                dataStore.updateContactLastMessage(key(contactID), clock.wallClock.instant())
                ownership.check("finalizeSend.contact")
                synchronized(lock) { pendingAcks.remove(messageID) }
                if (dataStore.fetchMessage(key(messageID))?.status == MessageStatus.DELIVERED) {
                    ownership.check("finalizeSend.refetched")
                    events.yield(MessageStatusEvent.StatusResolved(messageID, MessageStatus.DELIVERED, null))
                }
            } else {
                val changed = dataStore.clearRetryingToSent(key(messageID))
                ownership.check("finalizeSend.sent")
                if (changed) events.yield(MessageStatusEvent.StatusResolved(messageID, MessageStatus.SENT, null))
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
                dataStore.updateMessageAck(key(id), info.expectedAck.ackCodeUInt32, MessageStatus.SENT)
                ownership.checkPersistenceCompletion("dmSent.saved")
                dataStore.updateContactLastMessage(key(contactID), clock.wallClock.instant())
                ownership.checkPersistenceCompletion("dmSent.contact")
                val row = dataStore.fetchMessage(key(id))
                ownership.checkPersistenceCompletion("dmSent.refetched")
                if (ownership.isCurrent && row?.status == MessageStatus.SENT) {
                    events.yield(MessageStatusEvent.StatusResolved(id, MessageStatus.SENT, null))
                }
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
            synchronized(lock) {
                pendingAcks.values.forEach { it.receipt.cancel() }
                pendingAcks.clear()
                failedAckRestorations.clear()
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
