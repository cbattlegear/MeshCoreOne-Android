// PortedFrom: MC1Services/Sources/MC1Services/Services/ChatSendQueueService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.session.SystemSessionClock
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChatSendQueueService(
    override val token: SessionToken,
    private val dataStore: PersistenceStoreProtocol,
    private val messageService: MessageService,
    private val channelQuery: MessagingChannelQuery,
    private val reactionIndexer: OutgoingChannelReactionIndexer,
    private val signals: ConnectionSignals,
    scope: CoroutineScope,
    val config: ChatSendQueueConfig = ChatSendQueueConfig(),
    private val clock: SessionClock = SystemSessionClock(),
    reporter: MessagingIssueReporter = LoggingMessagingIssueReporter,
    private val newID: () -> UUID = UUID::randomUUID,
    progress: ChatSendQueueProgress = ChatSendQueueProgress(),
    private val onQueryAccounting: ((ChannelQueryAccountingEvent) -> Unit)? = null,
) : ChatSendQueuePort {
    init { require(messageService.token == token) { "Queues must share their message service generation" } }
    private val ownership = MessagingOwnership(token, scope, signals, reporter)
    private val persistenceJob = SupervisorJob()
    private val persistenceScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + persistenceJob)
    private val lock = Any()
    private val observation = Mutex()
    private val triggers = BLETransportOpenedSignal()
    private val queued = mutableSetOf<UUID>()
    private val failures = linkedMapOf<String, Throwable>()
    private val channelProgress = progress.attach(token)
    private data class QueryAccounting(
        val messageID: UUID,
        val attempt: Attempt,
        val completion: CompletableDeferred<Unit>,
        var failure: QueryFailure? = null,
        var parkPersisted: Boolean = false,
        var reported: Boolean = false,
    )
    private data class QueryFailure(val count: Long, val cause: Throwable)
    private var queryAccounting: QueryAccounting? = null
    private var recoveryPrepared = false
    private val completedDMs = mutableSetOf<UUID>()
    private class ParkedSendCancellation : CancellationException("Parked send envelope")
    private class DirectEntry(
        val pendingID: UUID,
        val envelope: DirectMessageEnvelope,
        var predecessor: DirectEntry? = null,
    ) {
        val successorMessages = mutableSetOf<UUID>()
        @Volatile var uncertainCancellation = false
        var claimID: UUID = UUID.randomUUID()
        var selection: DirectQueueSelection? = null
        var selectedPending: PendingSendDTO? = null
        var bumpRequestID: UUID = UUID.randomUUID()
        var preparationRequestID: UUID = UUID.randomUUID()
        var missingRequestID: UUID = UUID.randomUUID()
        var terminalRequestID: UUID = UUID.randomUUID()
        var remapRequestID: UUID = UUID.randomUUID()
    }
    private val directEntries = mutableMapOf<UUID, DirectEntry>()
    private data class DirectAttempt(val pending: Attempt, val claimID: UUID)
    private val directAttempts = mutableMapOf<UUID, DirectAttempt>()
    private data class ChannelEntry(val pendingID: UUID, val envelope: ChannelMessageEnvelope, var countedAttempt: Attempt? = null)
    private val channelEntries = mutableMapOf<UUID, ChannelEntry>()
    private val uncertainChannelCounts = mutableSetOf<UUID>()
    private val indexedChannels = mutableSetOf<UUID>()
    private val persistenceSubmissions = mutableSetOf<Deferred<Result<Unit>>>()
    private var hydrated = false
    private var hydration: Deferred<Unit>? = null
    private var connectionTask: Deferred<Unit>? = null
    private var subscription: ConnectionSubscription? = null
    private var teardown: CompletableDeferred<TeardownReport>? = null
    private val dmQueue = SendQueue(ownership.scope, ::sendDM, ::terminalDM, ::drained)
    private val channelQueue = SendQueue(ownership.scope, ::sendChannel, ::terminalChannel, ::drained)
    // Test-only preemption point after dequeue; production composition leaves this unset.
    internal var beforeInitialReadinessCheck: (() -> Unit)? = null
    val queuedEnvelopeCount: Int get() = synchronized(lock) { queued.size }
    val isObservingConnection: Boolean get() = synchronized(lock) { connectionTask?.isActive == true }
    private fun key(id: UUID) = EntityKey(token.radioId, id)
    private fun record(operation: String, failure: Throwable, retain: Boolean = true) {
        if (retain) synchronized(lock) { failures[operation] = failure }
        ownership.failure(operation, failure)
    }
    private fun recovered(operation: String) { synchronized(lock) { failures.remove(operation) } }
    private suspend fun checkQueueLifetime(operation: String) {
        try { ownership.checkLifetime(operation) }
        catch (failure: MessageServiceException) {
            if (failure.error == MessageServiceError.NotConnected) throw ChatSendQueueServiceException(ChatSendQueueServiceError.NotConnected)
            throw failure
        }
    }

    suspend fun observeConnectionState(): Unit = observation.withLock {
        checkQueueLifetime("observeSendQueue")
        val prior = synchronized(lock) {
            subscription?.close()
            subscription = null
            connectionTask.also { connectionTask = null }
        }
        prior?.cancelAndJoin()
        val current = signals.subscribeTransitions()
        synchronized(lock) { subscription = current }
        var previousReady = current.initial.token == token && current.initial.state.canDrainSendQueue
        if (previousReady) triggers.fire()
        val task = ownership.scope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                current.transitions.collect { snapshot ->
                    val ready = snapshot.token == token && snapshot.state.canDrainSendQueue
                    if (!previousReady && ready) triggers.fire()
                    previousReady = ready
                    if (snapshot.token != null && snapshot.token != token) {
                        ownership.reporter.report(MessagingDiagnostic.StaleResult(token, "queueConnection"))
                        dmQueue.stopAfterCurrent()
                        channelQueue.stopAfterCurrent()
                        ownership.invalidate()
                        channelProgress.retire()
                        cancelQueryAccounting()
                        triggers.finish()
                        dmQueue.cancelDrain()
                        channelQueue.cancelDrain()
                    }
                }
            } finally { current.close() }
        }
        synchronized(lock) { connectionTask = task }
        task.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException) record("queueConnection", cause)
        }
        Unit
    }

    fun transportDidOpen() { if (ownership.isReady) triggers.fire() }

    override suspend fun enqueueDM(envelope: DirectMessageEnvelope) {
        val pending = PendingSendDTO.fromEnvelope(envelope, token.radioId, newID(), clock.wallClock.instant())
        submitPersistence(pending) { scheduleDirect(DirectEntry(pending.id, envelope)) }
    }
    override suspend fun enqueueChannel(envelope: ChannelMessageEnvelope) {
        val pending = PendingSendDTO.fromEnvelope(envelope, token.radioId, newID(), clock.wallClock.instant())
        submitPersistence(pending) { scheduleChannel(ChannelEntry(pending.id, envelope)) }
    }
    private fun scheduleChannel(entry: ChannelEntry) = synchronized(lock) {
        if (channelEntries.putIfAbsent(entry.pendingID, entry) == null) {
            queued.add(entry.envelope.messageID)
            channelQueue.enqueue(entry)
        }
    }
    private fun scheduleDirect(entry: DirectEntry) = synchronized(lock) {
        val prior = directEntries.putIfAbsent(entry.pendingID, entry)
        if (prior == null) {
            queued.add(entry.envelope.messageID)
            dmQueue.enqueue(entry)
        } else {
            prior.successorMessages += entry.envelope.messageID
            dmQueue.enqueue(DirectEntry(entry.pendingID, entry.envelope, prior))
        }
    }
    override suspend fun signalDMEnqueued(envelope: DirectMessageEnvelope) {
        checkQueueLifetime("signalDMEnqueued")
        val rows = try {
            dataStore.fetchPendingSendsForMessage(key(envelope.messageID))
        } catch (failure: PersistenceStoreException) {
            throw ChatSendQueueServiceException(ChatSendQueueServiceError.PersistFailed(failure))
        }
        checkQueueLifetime("signalDMEnqueued.read")
        val matching = rows.filter { it.directMessageEnvelope() == envelope }
        if (matching.isEmpty()) {
            throw ChatSendQueueServiceException(ChatSendQueueServiceError.PersistFailed(
                PersistenceStoreException(PersistenceStoreError.InvalidData),
            ))
        }
        matching.forEach { scheduleDirect(DirectEntry(it.id, envelope)) }
    }

    private suspend fun submitPersistence(dto: PendingSendDTO, schedule: () -> Unit) {
        checkQueueLifetime("persistPendingSend")
        val task = synchronized(lock) {
            if (teardown != null || ownership.closing) throw ChatSendQueueServiceException(ChatSendQueueServiceError.NotConnected)
            persistenceScope.async<Result<Unit>>(start = CoroutineStart.LAZY) {
                try {
                    dataStore.insertPendingSendAssigningSequence(dto)
                    synchronized(lock) { if (!ownership.closing) schedule() }
                    Result.success(Unit)
                } catch (failure: PersistenceStoreException) {
                    val typed = ChatSendQueueServiceException(ChatSendQueueServiceError.PersistFailed(failure))
                    record("persistPendingSend.${dto.id}", typed)
                    Result.failure(typed)
                }
            }.also { work ->
                persistenceSubmissions += work
                work.invokeOnCompletion { cause ->
                    if (cause != null) record("persistPendingSend.${dto.id}", cause)
                    synchronized(lock) { persistenceSubmissions.remove(work) }
                }
                work.start()
            }
        }
        // Accepted durable submissions outlive observer cancellation, but are joined before generation shutdown returns.
        task.await().getOrThrow()
    }

    suspend fun hydrate() {
        checkQueueLifetime("hydrateSendQueue")
        if (!isObservingConnection) observeConnectionState()
        val task = synchronized(lock) {
            if (hydrated) return
            hydration ?: ownership.scope.async(start = CoroutineStart.LAZY) {
                val rows = dataStore.fetchPendingSends(token.radioId)
                checkQueueLifetime("hydrateSendQueue.read")
                var malformed: PersistenceStoreException? = null
                for (row in rows) {
                    checkQueueLifetime("hydrateSendQueue.enqueue")
                    try {
                        if (row.radioId != token.radioId) throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                        when (row.kind) {
                            PendingSendKind.DM -> {
                                val envelope = row.directMessageEnvelope()
                                    ?: throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                                scheduleDirect(DirectEntry(row.id, envelope))
                            }
                            PendingSendKind.CHANNEL -> {
                                val envelope = row.channelMessageEnvelope()
                                    ?: throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                                scheduleChannel(ChannelEntry(row.id, envelope))
                            }
                        }
                        recovered("hydrate.${row.id}")
                    } catch (failure: PersistenceStoreException) {
                        record("hydrate.${row.id}", failure)
                        val first = malformed
                        if (first == null) malformed = failure else first.addSuppressed(failure)
                    }
                }
                malformed?.let { throw it }
                channelProgress.reconcile(rows)
                synchronized(lock) { hydrated = true }
                recovered("hydrate")
            }.also { hydration = it; it.start() }
        }
        try { task.await() }
        catch (failure: PersistenceStoreException) {
            record("hydrate", failure)
            synchronized(lock) { if (hydration === task) hydration = null }
            throw ChatSendQueueServiceException(ChatSendQueueServiceError.PersistFailed(failure))
        }
    }

    private suspend fun awaitReady() {
        beforeInitialReadinessCheck?.invoke()
        checkQueueReadinessLifetime("queueReadiness")
        while (!ownership.isReady) {
            clock.messagingDeadline(config.transportWaitTimeout) { triggers.wait() }
            checkQueueReadinessLifetime("queueReadiness.woke")
        }
    }
    private suspend fun checkQueueReadinessLifetime(operation: String) {
        try { ownership.checkLifetime(operation) }
        catch (failure: MessageServiceException) {
            if (failure.error == MessageServiceError.NotConnected && ownership.closing) {
                throw CancellationException("Send queue generation retired").also { it.initCause(failure) }
            }
            throw failure
        }
    }
    private suspend fun park(id: UUID, failure: Throwable, afterVersion: Long? = null): Nothing {
        record("park.$id", failure, retain = failure is PersistenceStoreException ||
            !isTransientDirectMessageError(failure) && !isTransientChannelMessageError(failure))
        reportQueryAccounting(id, ChannelQueryAccountingOutcome.PARKED)
        finishQueryAccounting(id)
        clock.messagingDeadline(config.transportWaitTimeout) {
            if (afterVersion == null) triggers.wait() else triggers.waitAfter(afterVersion)
        }
        // Retirement racing a selected wake must not send this persisted envelope to terminal handling.
        throw ParkedSendCancellation()
    }
    private suspend fun remapPending(id: UUID) = withContext(NonCancellable) {
        ownership.checkPersistenceCompletion("remapPending")
        val changed = dataStore.updateMessageStatusUnlessDelivered(key(id), MessageStatus.PENDING)
        ownership.checkPersistenceCompletion("remapPending.saved")
        changed
    }
    private fun directMutationReceipt(result: DirectQueueMutationResult): DirectQueueMutationReceipt = when (result) {
        is DirectQueueMutationResult.Applied -> result.receipt
        is DirectQueueMutationResult.AlreadyApplied -> result.receipt
        is DirectQueueMutationResult.Rejected -> {
            if (result.reason == DirectAckRejection.UNRESOLVED_OUTCOME) {
                throw UnresolvedDirectQueueException(result.reason.name)
            }
            throw PersistenceStoreException(PersistenceStoreError.SaveFailed("Queued direct authority: ${result.reason}"))
        }
    }

    private suspend fun remapDirect(entry: DirectEntry): Boolean = withContext(NonCancellable) {
        val id = entry.envelope.messageID
        ownership.checkPersistenceCompletion("remapDirect")
        messageService.retireUnbegunDirectIntent(entry.claimID, id)
        val selection = entry.selection ?: throw PersistenceStoreException(PersistenceStoreError.InvalidData)
        val receipt = directMutationReceipt(messageService.mutateDirectQueueSelection(
            selection, entry.remapRequestID, DirectQueueMutation.ReturnToPending,
        ))
        ownership.checkPersistenceCompletion("remapDirect.saved")
        receipt.status == MessageStatus.PENDING
    }

    private suspend fun parkBeforeDirectSend(entry: DirectEntry, failure: PersistenceStoreException, afterVersion: Long): Nothing {
        val id = entry.envelope.messageID
        try {
            if (remapDirect(entry)) {
                entry.selection = null
                entry.selectedPending = null
                entry.bumpRequestID = UUID.randomUUID()
            }
        }
        catch (storage: PersistenceStoreException) { record("remap.$id", storage) }
        catch (stale: MessageServiceException) { record("remap.$id", stale) }
        park(id, failure, afterVersion)
    }

    private suspend fun selectDirect(entry: DirectEntry): DirectQueueCaptureResult.Captured? {
        val id = entry.envelope.messageID
        return when (val result = messageService.captureDirectQueueSelection(id, entry.pendingID)) {
            is DirectQueueCaptureResult.Captured -> {
                if (result.pending.directMessageEnvelope() != entry.envelope) {
                    handoffDefinitelyUnsent(id, completeDirect(entry))
                    null
                } else {
                    entry.selection = result.selection
                    entry.selectedPending = result.pending
                    result
                }
            }
            is DirectQueueCaptureResult.Rejected -> {
                if (result.reason == DirectAckRejection.UNRESOLVED_OUTCOME) {
                    throw UnresolvedDirectQueueException(result.reason.name)
                }
                if (result.reason !in listOf(DirectAckRejection.MISSING, DirectAckRejection.INVALIDATED)) {
                    throw PersistenceStoreException(PersistenceStoreError.SaveFailed("Queued direct capture: ${result.reason}"))
                }
                handoffDefinitelyUnsent(id, completeDirect(entry))
                null
            }
        }
    }

    private suspend fun activateDirectSuccessor(entry: DirectEntry): Boolean {
        val predecessor = entry.predecessor ?: return true
        val existing = synchronized(lock) { directEntries[entry.pendingID] }
        if (existing != null && existing !== predecessor) return false
        val selected = when (val result = messageService.captureDirectQueueSelection(
            entry.envelope.messageID, entry.pendingID,
        )) {
            is DirectQueueCaptureResult.Captured -> result
            is DirectQueueCaptureResult.Rejected -> {
                if (result.reason == DirectAckRejection.UNRESOLVED_OUTCOME) {
                    record("directSuccessorRetained.${entry.pendingID}", UnresolvedDirectQueueException(result.reason.name))
                } else if (result.reason !in listOf(DirectAckRejection.MISSING, DirectAckRejection.INVALIDATED)) {
                    record("directHandoff.${entry.pendingID}",
                        PersistenceStoreException(PersistenceStoreError.SaveFailed("Queued direct capture: ${result.reason}")))
                }
                return false
            }
        }
        if (selected.pending.directMessageEnvelope() != entry.envelope) {
            rescheduleDirect(entry.envelope.messageID)
            return false
        }
        predecessor.selection?.let { prior ->
            when (val comparison = messageService.compareDirectQueueSelections(prior, selected.selection)) {
                DirectQueueIncarnationComparison.Same -> return false
                DirectQueueIncarnationComparison.Different -> Unit
                is DirectQueueIncarnationComparison.Rejected ->
                    throw PersistenceStoreException(PersistenceStoreError.SaveFailed(
                        "Queued direct comparison: ${comparison.reason}",
                    ))
            }
        }
        if (existing != null) messageService.releaseDirectClaim(existing.claimID)
        val installed = synchronized(lock) {
            val current = directEntries[entry.pendingID]
            if (current != null && current !== predecessor) false
            else {
                if (current === predecessor && directAttempts[entry.pendingID]?.claimID == predecessor.claimID) {
                    directAttempts.remove(entry.pendingID)
                    completedDMs.remove(entry.pendingID)
                }
                directEntries[entry.pendingID] = entry
                queued.add(entry.envelope.messageID)
                entry.selection = selected.selection
                entry.selectedPending = selected.pending
                entry.predecessor = null
                true
            }
        }
        return installed
    }

    private suspend fun pruneReplacedDirectEntries(entry: DirectEntry) {
        val messageID = entry.envelope.messageID
        val live = dataStore.fetchPendingSendsForMessage(key(messageID)).map { it.id }.toSet()
        val predecessors = synchronized(lock) {
            directEntries.values.filter {
                it !== entry && it.envelope.messageID == messageID && it.pendingID !in live
            }
        }
        for (old in predecessors) {
            messageService.releaseDirectClaim(old.claimID)
            synchronized(lock) {
                if (directEntries[old.pendingID] === old) {
                    directEntries.remove(old.pendingID)
                    if (directAttempts[old.pendingID]?.claimID == old.claimID) directAttempts.remove(old.pendingID)
                    completedDMs.remove(old.pendingID)
                }
            }
        }
    }

    private suspend fun sendDM(entry: DirectEntry) {
        val envelope = entry.envelope
        val id = envelope.messageID
        if (entry.predecessor != null) {
            try { if (!activateDirectSuccessor(entry)) return }
            catch (failure: PersistenceStoreException) { record("directHandoff.${entry.pendingID}", failure); return }
        } else if (synchronized(lock) { directEntries[entry.pendingID] !== entry }) return
        if (entry.uncertainCancellation) return
        awaitReady()
        checkSendAdmission()
        val signalVersion = triggers.version
        var completingPending = false
        var definitelyUndispatched = false
        try {
            val retained = synchronized(lock) { directAttempts[entry.pendingID] }
            val attempt = if (synchronized(lock) { entry.pendingID in completedDMs }) {
                retained ?: throw PersistenceStoreException(PersistenceStoreError.InvalidData)
            } else {
                val selection = if (retained == null) {
                    val handle = entry.selection
                    val pending = entry.selectedPending
                    if (handle != null && pending != null) DirectQueueCaptureResult.Captured(handle, pending)
                    else selectDirect(entry) ?: return
                } else null
                if (selection != null) {
                    messageService.detachRetiredTrackingForQueuedSuccessor(id, entry.claimID)
                    pruneReplacedDirectEntries(entry)
                }
                val contact = try {
                    dataStore.fetchContact(key(envelope.contactID)).also { ownership.check("dm.contact") }
                } catch (failure: PersistenceStoreException) { park(id, failure, signalVersion) }
                if (contact == null) {
                    if (retained != null) {
                        throw PersistenceStoreException(PersistenceStoreError.SaveFailed(
                            "Contact disappeared while a direct send outcome is retained",
                        ))
                    }
                    val captured = entry.selection ?: throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                    val result = messageService.mutateDirectQueueSelection(
                        captured, entry.missingRequestID, DirectQueueMutation.MissingContact,
                    )
                    if (result is DirectQueueMutationResult.Rejected) {
                        when (result.reason) {
                            DirectAckRejection.MISSING, DirectAckRejection.INVALIDATED -> {
                                handoffDefinitelyUnsent(id, completeDirect(entry))
                                return
                            }
                            DirectAckRejection.UNRESOLVED_OUTCOME ->
                                throw UnresolvedDirectQueueException(result.reason.name)
                            DirectAckRejection.NOT_ELIGIBLE -> {
                                if (dataStore.fetchContact(key(envelope.contactID)) != null) {
                                    handoffDefinitelyUnsent(id, completeDirect(entry))
                                    return
                                }
                                park(id, PersistenceStoreException(PersistenceStoreError.SaveFailed(
                                    "Queued direct cleanup has a competing send",
                                )), signalVersion)
                            }
                            else -> Unit
                        }
                    }
                    directMutationReceipt(result)
                    ownership.check("dm.deletedContact")
                    handoffDefinitelyUnsent(id, completeDirect(entry))
                    return
                }
                val current = retained ?: run {
                    val bump = try {
                        messageService.bumpDirectQueueSelection(
                            requireNotNull(selection).selection, entry.bumpRequestID,
                        ).also { ownership.check("dm.bump") }
                    } catch (failure: PersistenceStoreException) {
                        if (failure.error == PersistenceStoreError.InvalidData) {
                            record("directRetained.$id", failure)
                            return
                        }
                        parkBeforeDirectSend(entry, failure, signalVersion)
                    }
                    val receipt = when (bump) {
                        is DirectQueueBumpResult.Applied -> bump.receipt
                        is DirectQueueBumpResult.AlreadyApplied -> bump.receipt
                        is DirectQueueBumpResult.Rejected -> {
                            if (bump.reason == DirectAckRejection.UNRESOLVED_OUTCOME) {
                                throw UnresolvedDirectQueueException(bump.reason.name)
                            }
                            if (bump.reason !in listOf(DirectAckRejection.MISSING, DirectAckRejection.INVALIDATED)) {
                                throw PersistenceStoreException(PersistenceStoreError.SaveFailed("Queued direct bump: ${bump.reason}"))
                            }
                            handoffDefinitelyUnsent(id, completeDirect(entry))
                            return
                        }
                    }
                    if (receipt.pending != requireNotNull(selection).pending.copy(attemptCount = receipt.attemptCount)) {
                        throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                    }
                    entry.selection = receipt.selection
                    entry.selectedPending = receipt.pending
                    DirectAttempt(Attempt(receipt.attemptCount, entry.pendingID), entry.claimID).also {
                        synchronized(lock) { directAttempts[entry.pendingID] = it }
                    }
                }
                checkSendAdmission()
                messageService.sendPendingClaim(
                    current.claimID, id, contact, current.pending.preserveTimestamp, envelope.isResend,
                    pendingSendID = current.pending.pendingID,
                    selection = entry.selection ?: throw PersistenceStoreException(PersistenceStoreError.InvalidData),
                    preparationRequestID = entry.preparationRequestID,
                    onSelectionPrepared = { entry.selection = it },
                )
                ownership.check("dm.completed")
                synchronized(lock) { completedDMs += entry.pendingID }
                current
            }
            ownership.check("dm.sent")
            definitelyUndispatched = messageService.definitelyUndispatchedDirectClaim(
                attempt.claimID, id, attempt.pending.pendingID,
            )
            completingPending = true
            messageService.completeDirectPending(attempt.claimID, id, attempt.pending.pendingID)
            ownership.check("dm.pendingDeleted")
            triggers.clear()
            val successors = completeDirect(entry)
            if (definitelyUndispatched) handoffDefinitelyUnsent(id, successors)
            else retainSignaledSuccessors(entry, successors)
        } catch (cancelled: CancellationException) {
            if (cancelled !is ParkedSendCancellation &&
                synchronized(lock) { directAttempts[entry.pendingID] != null }
            ) {
                val registration = try {
                    withContext(NonCancellable) {
                        messageService.directClaimRegistration(entry.claimID, id).also {
                            when (it) {
                                DirectClaimRegistration.NONE -> {
                                    messageService.retireUnbegunDirectIntent(entry.claimID, id)
                                    retireDirectAttempt(entry)
                                }
                                DirectClaimRegistration.DEFINITELY_UNSENT -> retireDirectAttempt(entry, requireUnbegun = false)
                                DirectClaimRegistration.REGISTERED ->
                                    messageService.restoreInterruptedDirectRetry(entry.claimID, id)
                            }
                        }
                    }
                } catch (failure: Exception) {
                    cancelled.addSuppressed(failure)
                    DirectClaimRegistration.REGISTERED
                }
                if (registration == DirectClaimRegistration.REGISTERED) {
                    entry.uncertainCancellation = true
                    val retained = UnresolvedDirectQueueException("cancellation after a registered direct attempt")
                    retained.addSuppressed(cancelled)
                    record("directUncertain.${entry.pendingID}", retained)
                }
            }
            throw cancelled
        }
        catch (unresolved: UnresolvedDirectQueueException) {
            record("directUncertain.${entry.pendingID}", unresolved)
        }
        catch (stale: StaleDirectQueueSelectionException) {
            if (completingPending) {
                record("directCompletionStale.${entry.pendingID}", stale)
                val successors = completeDirect(entry, clearProgress = false)
                if (definitelyUndispatched && successors.isNotEmpty()) handoffDefinitelyUnsent(id, successors)
                else retainSignaledSuccessors(entry, successors)
            } else {
                handoffDefinitelyUnsent(id, completeDirect(entry))
            }
        }
        catch (failure: PersistenceStoreException) { park(id, failure, signalVersion) }
        catch (failure: MessageServiceException) {
            if (!isTransientDirectMessageError(failure)) throw failure
            handleTransientDirect(entry, failure, signalVersion)
        } catch (failure: MeshCoreException) {
            if (!isTransientDirectMessageError(failure)) throw failure
            handleTransientDirect(entry, failure, signalVersion)
        }
    }

    private suspend fun handleTransientDirect(entry: DirectEntry, failure: Exception, signalVersion: Long) {
        try {
            remapDirect(entry)
            retireDirectAttempt(entry)
        } catch (unresolved: UnresolvedDirectQueueException) {
            unresolved.addSuppressed(failure)
            record("directUncertain.${entry.pendingID}", unresolved)
            return
        } catch (storage: PersistenceStoreException) {
            storage.addSuppressed(failure)
            record("directIntentRetained.${entry.claimID}", storage)
            return
        }
        park(entry.envelope.messageID, failure, signalVersion)
    }

    private suspend fun rescheduleDirect(messageID: UUID) {
        val rows = dataStore.fetchPendingSendsForMessage(key(messageID))
        checkQueueLifetime("rescheduleDirect.read")
        rows.filter { it.kind == PendingSendKind.DM }.forEach { row ->
            val envelope = row.directMessageEnvelope() ?: throw PersistenceStoreException(PersistenceStoreError.InvalidData)
            scheduleDirect(DirectEntry(row.id, envelope))
        }
    }

    private suspend fun handoffDefinitelyUnsent(messageID: UUID, successors: Set<UUID>) {
        for (id in successors + messageID) {
            try { rescheduleDirect(id) }
            catch (failure: PersistenceStoreException) { record("directHandoff.$id", failure) }
        }
    }

    private suspend fun retainSignaledSuccessors(entry: DirectEntry, successors: Set<UUID>) {
        for (id in successors) {
            try {
                val rows = dataStore.fetchPendingSendsForMessage(key(id))
                checkQueueLifetime("retainDirectSuccessor.read")
                if (rows.any { it.id == entry.pendingID }) {
                    record("directSuccessorRetained.${entry.pendingID}",
                        UnresolvedDirectQueueException("a successor was signaled while the prior wire outcome was retained"))
                }
            } catch (failure: PersistenceStoreException) { record("directHandoff.$id", failure) }
        }
    }

    private suspend fun retireDirectAttempt(entry: DirectEntry, requireUnbegun: Boolean = true) {
        val claim = synchronized(lock) { directAttempts[entry.pendingID]?.claimID }
        if (claim != null) messageService.releaseDirectClaim(claim, requireUnbegun = requireUnbegun)
        synchronized(lock) {
            completedDMs.remove(entry.pendingID)
            directAttempts.remove(entry.pendingID)
        }
        entry.claimID = UUID.randomUUID()
        entry.selection = null
        entry.selectedPending = null
        entry.bumpRequestID = UUID.randomUUID()
        entry.preparationRequestID = UUID.randomUUID()
        entry.missingRequestID = UUID.randomUUID()
        entry.terminalRequestID = UUID.randomUUID()
        entry.remapRequestID = UUID.randomUUID()
    }

    private suspend fun sendChannel(entry: ChannelEntry) {
        try { sendChannelAttempt(entry) }
        catch (cancelled: CancellationException) {
            finishQueryAccounting(entry.envelope.messageID, cancelled)
            throw cancelled
        } catch (stale: MessageServiceException) {
            if (ownership.isCurrent || stale.error != MessageServiceError.NotConnected) throw stale
            val cancelled = CancellationException("Channel queue generation retired").also { it.initCause(stale) }
            finishQueryAccounting(entry.envelope.messageID, cancelled)
            throw cancelled
        }
    }

    private suspend fun sendChannelAttempt(entry: ChannelEntry) {
        val envelope = entry.envelope
        val id = envelope.messageID
        val pending = try {
            val submissions = dataStore.fetchPendingSendsForMessage(key(id))
            checkQueueLifetime("channel.submission")
            if (submissions.any { it.id != entry.pendingID }) {
                retainChannel(entry, rejectedChannel(entry, QueuedChannelAttemptConflict.RETAINED_SUBMISSION))
                return
            }
            submissions.singleOrNull { it.id == entry.pendingID }
        } catch (failure: PersistenceStoreException) { retryChannelRead(entry, failure); return }
        if (pending == null) { completeChannel(entry); return }
        if (pending.kind != PendingSendKind.CHANNEL || pending.channelMessageEnvelope() != envelope) {
            retainChannel(entry, rejectedChannel(entry, QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION))
            return
        }
        when (pending.channelDispatchState) {
            QueuedChannelDispatchState.ACCEPTED -> {
                val attemptID = pending.channelAttemptID
                if (attemptID == null) {
                    retainChannel(entry, rejectedChannel(entry, QueuedChannelAttemptConflict.NOT_ACCEPTED))
                    return
                }
                finishAcceptedChannel(entry, QueuedChannelAttemptKey(token.radioId, entry.pendingID, id, attemptID))
                return
            }
            QueuedChannelDispatchState.READY -> if (pending.channelAttemptID != null || pending.channelOccupantID == null) {
                retainChannel(entry, rejectedChannel(entry, QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION))
                return
            }
            null, QueuedChannelDispatchState.DISPATCHING, QueuedChannelDispatchState.UNCERTAIN -> {
                retainChannel(entry, rejectedChannel(entry, QueuedChannelAttemptConflict.SUBMISSION_NOT_READY))
                return
            }
        }
        awaitReady()
        checkSendAdmission()
        val attempt = readyChannelAttempt(entry) ?: return
        checkSendAdmission()
        try {
            val accepted = messageService.sendQueuedChannel(entry.pendingID, envelope, attempt.preserveTimestamp)
            finishAcceptedChannel(entry, accepted.key)
        } catch (cancelled: CancellationException) { entry.countedAttempt = null; throw cancelled }
        catch (retained: QueuedChannelRetainedException) { entry.countedAttempt = null; retainChannel(entry, retained) }
        catch (failure: PersistenceStoreException) { retryChannelRead(entry, failure) }
        catch (failure: MessageServiceException) { entry.countedAttempt = null; channelFailure(envelope, attempt, failure) }
        catch (failure: MeshCoreException) { entry.countedAttempt = null; channelFailure(envelope, attempt, failure) }
    }

    private fun rejectedChannel(entry: ChannelEntry, reason: QueuedChannelAttemptConflict) =
        PersistenceStoreException(PersistenceStoreError.InvalidData,
            QueuedChannelAttemptRejected(EntityKey(token.radioId, entry.pendingID), reason))

    private fun retainChannel(entry: ChannelEntry, failure: Throwable) {
        record("channelRetained.${entry.pendingID}", failure)
    }

    private suspend fun retryChannelRead(entry: ChannelEntry, failure: PersistenceStoreException) {
        if (failure.error == PersistenceStoreError.InvalidData) retainChannel(entry, failure)
        else park(entry.envelope.messageID, failure)
    }

    private suspend fun readyChannelAttempt(entry: ChannelEntry): Attempt? {
        entry.countedAttempt?.let { return it }
        if (synchronized(lock) { entry.pendingID in uncertainChannelCounts }) return null
        try {
            if (dataStore.fetchPendingSendsForMessage(key(entry.envelope.messageID)).any { it.id != entry.pendingID }) {
                retainChannel(entry, rejectedChannel(entry, QueuedChannelAttemptConflict.RETAINED_SUBMISSION))
                return null
            }
        } catch (failure: PersistenceStoreException) { retryChannelRead(entry, failure); return null }
        checkSendAdmission()
        val readiness = dataStore as? QueuedChannelReadinessPersisting
        if (readiness == null) {
            retainChannel(entry, UnsupportedOperationException("Exact READY-only pending-row attempt counting is not wired"))
            return null
        }
        val count = try {
            readiness.incrementReadyQueuedChannelAttemptCount(key(entry.pendingID), entry.envelope.messageID)
        } catch (cancelled: CancellationException) {
            synchronized(lock) { uncertainChannelCounts += entry.pendingID }
            retainChannel(entry, cancelled)
            throw cancelled
        } catch (failure: PersistenceStoreException) {
            synchronized(lock) { uncertainChannelCounts += entry.pendingID }
            try {
                val row = dataStore.fetchPendingSendsForMessage(key(entry.envelope.messageID)).singleOrNull { it.id == entry.pendingID }
                record("channelCountReadback.${entry.pendingID}.${row?.attemptCount}.${row?.channelDispatchState}", failure, retain = false)
            }
            catch (readFailure: PersistenceStoreException) {
                if (readFailure !== failure) failure.addSuppressed(readFailure)
            }
            retainChannel(entry, failure)
            return null
        }
        ownership.check("channel.bump")
        return Attempt(count, entry.pendingID).also { entry.countedAttempt = it }
    }

    private suspend fun finishAcceptedChannel(entry: ChannelEntry, attemptKey: QueuedChannelAttemptKey) {
        val envelope = entry.envelope
        val id = envelope.messageID
        try {
            val accepted = dataStore.fetchQueuedChannelAttempt(attemptKey)
            if (accepted == null || accepted.phase != QueuedChannelAttemptPhase.ACCEPTED ||
                accepted.channelOccupantID != envelope.channelOccupantID
            ) {
                retainChannel(entry, rejectedChannel(entry, QueuedChannelAttemptConflict.NOT_ACCEPTED))
                return
            }
            envelope.localNodeName?.let {
                if (!synchronized(lock) { entry.pendingID in indexedChannels }) {
                    try {
                        reactionIndexer.indexMessage(id, envelope.channelIndex, it, envelope.messageText, accepted.wireTimestamp)
                        ownership.check("channel.indexed")
                        synchronized(lock) { indexedChannels += entry.pendingID }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { record("index.$id", failure); park(id, failure) }
                }
            }
            messageService.completeQueuedChannel(attemptKey)
            ownership.check("channel.completed")
            triggers.clear()
            completeChannel(entry)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: PersistenceStoreException) { retryChannelRead(entry, failure) }
        catch (retained: QueuedChannelRetainedException) { retainChannel(entry, retained) }
        catch (failure: MessageServiceException) { retainChannel(entry, failure) }
    }

    private suspend fun channelFailure(envelope: ChannelMessageEnvelope, attempt: Attempt, failure: Exception): Nothing {
        val id = envelope.messageID
        if (!isTransientChannelMessageError(failure)) throw failure
        if (isChannelMessageNotFound(failure) && attempt.postBumpCount >= config.disambiguateAfterAttempts) {
            val accounting = synchronized(lock) {
                if (!ownership.isCurrent || recoveryPrepared) throw CancellationException("Channel-query admission closed")
                check(queryAccounting == null) { "Channel queries must be serialized" }
                QueryAccounting(id, attempt, CompletableDeferred()).also { queryAccounting = it }
            }
            val found = try { channelQuery.fetchChannel(envelope.channelIndex) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (queryFailure: Exception) {
                // The source query contract counts every thrown error; none becomes a nil channel.
                val count = channelProgress.increment(id, accounting.attempt.pendingID)
                synchronized(lock) { accounting.failure = QueryFailure(count, queryFailure) }
                record("channelQuery.$id", queryFailure, retain = queryFailure is PersistenceStoreException)
                if (count >= config.maxConsecutiveFetchChannelFailures) throw queryFailure
                try {
                    if (remapPending(id)) synchronized(lock) {
                        accounting.parkPersisted = true
                    }
                }
                catch (storage: PersistenceStoreException) { record("remap.$id", storage) }
                park(id, queryFailure)
            }
            ownership.check("channel.disambiguated")
            channelProgress.clear(id)
            if (found == null) throw failure
        }
        try { remapPending(id) }
        catch (storage: PersistenceStoreException) { record("remap.$id", storage) }
        park(id, failure)
    }

    private suspend fun terminalDM(failure: Exception, entry: DirectEntry) = terminal(failure, entry)
    private suspend fun terminalChannel(failure: Exception, entry: ChannelEntry) {
        val envelope = entry.envelope
        record("terminal.${envelope.messageID}", failure, retain = false)
        var completed = false
        var retirementKey: QueuedChannelAttemptKey? = null
        try {
            val pending = dataStore.fetchPendingSendsForMessage(key(envelope.messageID))
                .singleOrNull { it.id == entry.pendingID }
            val attempts = dataStore.fetchQueuedChannelAttempts(EntityKey(token.radioId, entry.pendingID))
            val latest = attempts.lastOrNull()
            if (pending?.channelDispatchState != QueuedChannelDispatchState.READY ||
                pending.channelAttemptID != null ||
                latest?.phase != QueuedChannelAttemptPhase.DEFINITELY_UNSENT
            ) {
                retainChannel(entry, failure)
                return
            }
            val terminal = dataStore as? QueuedChannelTerminalPersisting
            if (terminal == null) {
                retainChannel(entry, UnsupportedOperationException("Atomic queued channel retirement is not wired"))
                return
            }
            ownership.check("terminalChannel")
            retirementKey = latest.key
            val receipt = terminal.retireDefinitelyUnsentQueuedChannelAttempt(latest.key)
            ownership.check("terminalChannel.retired")
            if (receipt.statusChanged && receipt.status == MessageStatus.FAILED) {
                messageService.notifyQueuedChannelRetired(latest.key, receipt)
                reportQueryAccounting(envelope.messageID, ChannelQueryAccountingOutcome.TERMINAL_DELETED)
            }
            completeChannel(entry)
            completed = true
        } catch (storage: PersistenceStoreException) {
            retirementKey?.let { exactKey ->
                try {
                    val row = dataStore.fetchPendingSendsForMessage(key(envelope.messageID)).singleOrNull { it.id == entry.pendingID }
                    val attempt = dataStore.fetchQueuedChannelAttempt(exactKey)
                    record("channelRetirementReadback.${entry.pendingID}.${row?.channelDispatchState}.${attempt?.phase}",
                        storage, retain = false)
                } catch (readFailure: PersistenceStoreException) {
                    if (readFailure !== storage) storage.addSuppressed(readFailure)
                }
            }
            record("terminalPersistence.${envelope.messageID}", storage)
            retainChannel(entry, storage)
            completed = true
        } catch (stale: MessageServiceException) {
            if (ownership.isCurrent || stale.error != MessageServiceError.NotConnected) throw stale
            retainChannel(entry, stale)
        } finally {
            finishQueryAccounting(envelope.messageID,
                if (completed) null else CancellationException("Channel-query terminal completion interrupted"))
        }
    }

    /**
     * Seal new sends/queries, then observe the admitted query through park or terminal persistence.
     * Recovery must await this before withdrawing this token or cancelling/retiring its services.
     * Cancelling an observer does not cancel the query; manual teardown invalidates the observation.
     * Admission stays sealed for this generation even if an observer is cancelled.
     */
    suspend fun awaitChannelQueryAccounting() {
        ownership.check("awaitChannelQueryAccounting")
        val pending = synchronized(lock) {
            recoveryPrepared = true
            queryAccounting
        }
        dmQueue.stopAfterCurrent()
        channelQueue.stopAfterCurrent()
        pending?.completion?.await()
        ownership.check("awaitChannelQueryAccounting.completed")
    }

    private fun checkSendAdmission(): Unit = synchronized(lock) {
        if (recoveryPrepared || !ownership.isCurrent) throw CancellationException("Send admission closed")
    }

    private fun finishQueryAccounting(id: UUID, cancelled: CancellationException? = null) {
        val pending = synchronized(lock) {
            queryAccounting?.takeIf { it.messageID == id }?.also { queryAccounting = null }
        } ?: return
        if (cancelled == null) pending.completion.complete(Unit) else pending.completion.cancel(cancelled)
    }

    private fun cancelQueryAccounting() {
        val pending = synchronized(lock) { queryAccounting.also { queryAccounting = null } } ?: return
        pending.completion.cancel(CancellationException("Channel-query generation retired"))
    }

    private fun reportQueryAccounting(id: UUID, outcome: ChannelQueryAccountingOutcome) {
        val observer = onQueryAccounting ?: return
        val event = synchronized(lock) {
            val pending = queryAccounting?.takeIf { it.messageID == id && !it.reported } ?: return
            val failure = pending.failure ?: return
            if (outcome == ChannelQueryAccountingOutcome.PARKED && !pending.parkPersisted) return
            if (!ownership.isCurrent) return
            pending.reported = true
            ChannelQueryAccountingEvent(token, id, pending.attempt.pendingID, failure.count,
                config.maxConsecutiveFetchChannelFailures, outcome, failure.cause)
        }
        // Like MessagingIssueReporter, this synchronous observer must not throw or perform lifecycle work.
        observer(event)
    }

    private suspend fun terminal(failure: Exception, entry: DirectEntry) {
        if (synchronized(lock) { directEntries[entry.pendingID] !== entry }) return
        val id = entry.envelope.messageID
        record("terminal.$id", failure, retain = false)
        var deleted = false
        var failedPersisted = false
        var definitelyUndispatched = false
        try {
            ownership.check("terminalSend")
            if (messageService.directClaimRegistration(entry.claimID, id) != DirectClaimRegistration.NONE) {
                val retained = UnresolvedDirectQueueException("terminal failure after a registered direct attempt")
                retained.addSuppressed(failure)
                record("directUncertain.${entry.pendingID}", retained)
                return
            }
            val selection = entry.selection ?: throw PersistenceStoreException(PersistenceStoreError.InvalidData)
            val receipt = directMutationReceipt(messageService.mutateDirectQueueSelection(
                selection, entry.terminalRequestID, DirectQueueMutation.DefinitelyUnsentFailure(entry.claimID),
            ))
            failedPersisted = receipt.statusChanged && receipt.status == MessageStatus.FAILED
            definitelyUndispatched = true
            ownership.check("terminalSend.deleted")
            deleted = true
            if (failedPersisted) messageService.notifyMessageFailed(id)
        } catch (storage: PersistenceStoreException) { record("terminalPersistence.$id", storage) }
        catch (unresolved: UnresolvedDirectQueueException) { record("terminalPersistence.$id", unresolved) }
        val successors = completeDirect(entry, clearProgress = deleted)
        if (deleted && definitelyUndispatched) handoffDefinitelyUnsent(id, successors)
        else retainSignaledSuccessors(entry, successors)
        if (deleted && failedPersisted) reportQueryAccounting(id, ChannelQueryAccountingOutcome.TERMINAL_DELETED)
    }
    private suspend fun drained(failure: Exception?) {
        if (failure != null) ownership.failure("sendQueueDrain", failure)
    }
    private fun completeChannel(entry: ChannelEntry) {
        synchronized(lock) {
            val id = entry.envelope.messageID
            val last = channelEntries.values.none { it.pendingID != entry.pendingID && it.envelope.messageID == id }
            if (last) channelProgress.clear(id)
            channelEntries.remove(entry.pendingID)
            uncertainChannelCounts.remove(entry.pendingID)
            indexedChannels.remove(entry.pendingID)
            failures.remove("channelRetained.${entry.pendingID}")
            if (last && directEntries.values.none { it.envelope.messageID == id }) {
                queued.remove(id)
                failures.remove("park.$id")
                failures.remove("remap.$id")
                failures.remove("channelQuery.$id")
                failures.remove("index.$id")
            }
        }
    }
    private suspend fun completeDirect(entry: DirectEntry, clearProgress: Boolean = true): Set<UUID> {
        val id = entry.envelope.messageID
        synchronized(lock) {
            if (directEntries[entry.pendingID] !== entry) return emptySet()
            if (directAttempts[entry.pendingID]?.claimID == entry.claimID) directAttempts.remove(entry.pendingID)
        }
        try { messageService.releaseDirectClaim(entry.claimID) }
        catch (failure: PersistenceStoreException) { record("directIntentRetained.${entry.claimID}", failure) }
        return synchronized(lock) {
            if (directEntries[entry.pendingID] !== entry) return@synchronized emptySet()
            directEntries.remove(entry.pendingID)
            completedDMs.remove(entry.pendingID)
            if (directEntries.values.none { it.envelope.messageID == id } &&
                channelEntries.values.none { it.envelope.messageID == id }) {
                if (clearProgress) channelProgress.clear(id)
                queued.remove(id)
                failures.remove("park.$id")
                failures.remove("remap.$id")
                failures.remove("channelQuery.$id")
                failures.remove("index.$id")
            }
            entry.successorMessages.toSet()
        }
    }
    suspend fun awaitDrainCompletion() { dmQueue.awaitDrainCompletion(); channelQueue.awaitDrainCompletion() }

    suspend fun shutdown(): TeardownReport {
        dmQueue.stopAfterCurrent()
        channelQueue.stopAfterCurrent()
        val (receipt, claimed) = synchronized(lock) {
            teardown?.let { it to false } ?: CompletableDeferred<TeardownReport>().also {
                teardown = it
                ownership.invalidate()
                channelProgress.retire()
            }.let { it to true }
        }
        if (!claimed) return withContext(NonCancellable) { receipt.await() }
        cancelQueryAccounting()
        return withContext(NonCancellable) {
            val accepted = synchronized(lock) { persistenceSubmissions.toList() }
            for (work in accepted) {
                work.start()
                try { work.await().getOrThrow() }
                catch (failure: Exception) { record("acceptedPersistenceCompletion", failure) }
            }
            triggers.finish()
            val pendingHydration = synchronized(lock) { hydration }
            pendingHydration?.cancelAndJoin()
            synchronized(lock) { subscription?.close(); subscription = null; connectionTask }?.cancelAndJoin()
            dmQueue.shutdown()?.let { record("dmQueueCallback", it) }
            channelQueue.shutdown()?.let { record("channelQueueCallback", it) }
            for (entry in synchronized(lock) { directEntries.values.toList() }) {
                val id = entry.envelope.messageID
                if (synchronized(lock) { channelEntries.values.any { it.envelope.messageID == id } }) continue
                try {
                    ownership.checkPersistenceCompletion("shutdownPending")
                    val row = dataStore.fetchMessage(key(id))
                    if (row?.status == MessageStatus.RETRYING || row?.status == MessageStatus.FAILED) remapDirect(entry)
                } catch (storage: PersistenceStoreException) { record("shutdownPending.$id", storage) }
                catch (unresolved: UnresolvedDirectQueueException) { record("shutdownPending.$id", unresolved) }
                catch (stale: MessageServiceException) { record("shutdownPending.$id", stale) }
            }
            ownership.job.cancelAndJoin()
            persistenceJob.cancelAndJoin()
            val report = TeardownReport(synchronized(lock) {
                failures.values.map { TeardownIssue(LifecycleStage.STOP_SERVICES, it) }.snapshot()
            })
            receipt.complete(report)
            report
        }
    }

    data class Attempt(val postBumpCount: Long, val pendingID: UUID) { val preserveTimestamp: Boolean get() = postBumpCount > 1 }
    companion object {
        fun isTransientDirectMessageError(failure: Throwable): Boolean = isTransientError(failure, 3u)
        fun isTransientChannelMessageError(failure: Throwable): Boolean = isTransientError(failure, 2u)
        fun isTransientError(failure: Throwable, deviceCode: UByte): Boolean = when (failure) {
            is MessageServiceException -> when (val error = failure.error) {
                is MessageServiceError.SessionError -> isTransientError(error.underlying, deviceCode)
                MessageServiceError.NotConnected -> true
                else -> false
            }
            is ChatSendQueueServiceException -> when (val error = failure.error) {
                is ChatSendQueueServiceError.PersistFailed -> isTransientError(error.underlying, deviceCode)
                ChatSendQueueServiceError.NotConnected -> true
            }
            is MeshCoreException.Timeout, is MeshCoreException.NotConnected, is MeshCoreException.ConnectionLost,
            is MeshCoreException.BluetoothPoweredOff, is MeshCoreException.SessionNotStarted -> true
            is MeshCoreException.DeviceError -> failure.code == deviceCode
            else -> false
        }
        fun isChannelMessageNotFound(failure: Throwable): Boolean = when (failure) {
            is MeshCoreException.DeviceError -> failure.code == 2.toUByte()
            is MessageServiceException -> (failure.error as? MessageServiceError.SessionError)?.underlying?.let(::isChannelMessageNotFound) == true
            else -> false
        }
    }
}
