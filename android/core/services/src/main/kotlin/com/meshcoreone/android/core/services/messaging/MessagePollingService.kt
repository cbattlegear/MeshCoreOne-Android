// PortedFrom: MC1Services/Sources/MC1Services/Services/MessagePollingService.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: WP-214 Nonactivated opt-in receive envelopes; legacy consumers retain their existing contract.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.event.*
import com.meshcoreone.android.core.protocol.session.MeshCoreSessionProtocol
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.session.SystemSessionClock
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import java.time.Duration
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class MessagePollingService(
    val token: SessionToken,
    private val session: MeshCoreSessionProtocol,
    private val dataStore: PersistenceStoreProtocol,
    signals: ConnectionSignals,
    scope: CoroutineScope,
    private val clock: SessionClock = SystemSessionClock(),
    reporter: MessagingIssueReporter = LoggingMessagingIssueReporter,
) : MessagePollingServiceProtocol, ChannelReceiveEnvelopePolling {
    private data class Delivery(
        val event: MeshEvent,
        val context: DeliveryContext,
        val channelEnvelope: ChannelReceiveEnvelope?,
        val matchesChannelOccurrence: Boolean,
        var failure: Exception? = null,
    ) {
        fun inContext(context: DeliveryContext): Delivery =
            copy(context = context, channelEnvelope = channelEnvelope?.copy(context = context))
    }
    private val ownership = MessagingOwnership(token, scope, signals, reporter)
    private val lock = Any()
    private val lifecycle = Mutex()
    private val dispatch = Mutex()
    private val pending = ArrayDeque<Delivery>()
    private val parked = ArrayDeque<Delivery>()
    private val observedDuringPoll = ArrayDeque<Delivery>()
    private val expectedEchoes = mutableMapOf<MeshEvent, Long>()
    private val expectedChannelEchoes = mutableMapOf<ChannelMessage, ArrayDeque<Delivery>>()
    private var contactHandler: (suspend (ContactMessage, ContactDTO?, DeliveryContext) -> Unit)? = null
    private var channelHandler: (suspend (ChannelMessage, ChannelDTO?, DeliveryContext) -> Unit)? = null
    private var channelEnvelopeHandler: (suspend (ChannelReceiveEnvelope, ChannelDTO?) -> Unit)? = null
    private var signedHandler: (suspend (ContactMessage, ContactDTO?) -> Unit)? = null
    private var cliHandler: (suspend (ContactMessage, ContactDTO?) -> Unit)? = null
    private var listener: Deferred<Unit>? = null
    private var polling: Deferred<Long>? = null
    private var catchUp: Deferred<Unit>? = null
    private var catchUpRequested = false
    private var pollingActive = false
    private var monitorActive = false
    private var autoFetch = false
    private var desiredAutoPaused = false
    private var autoRunning = false
    private var autoRevision = 0L
    private var handlerCount = 0L
    private var handlerFailure: Throwable? = null
    private val failures = linkedMapOf<String, Throwable>()
    private val consumerInvocations = mutableMapOf<Job, MessageHandlerInvocation>()
    private var teardown: CompletableDeferred<TeardownReport>? = null
    val isAutoFetching: Boolean get() = synchronized(lock) { autoFetch }
    val pendingHandlerCount: Long get() = synchronized(lock) { handlerCount }
    val undeliveredCount: Int get() = synchronized(lock) { pending.size + parked.size }
    val hasMessageHandlersWired: Boolean get() = synchronized(lock) {
        contactHandler != null || channelHandler != null || channelEnvelopeHandler != null ||
            signedHandler != null || cliHandler != null
    }

    private fun remember(operation: String, cause: Throwable) {
        synchronized(lock) { failures[operation] = cause }
        ownership.failure(operation, cause)
    }
    private suspend fun checkPolling(operation: String) {
        try { ownership.check(operation) }
        catch (failure: MessageServiceException) {
            if (failure.error == MessageServiceError.NotConnected) throw MessagePollingException(MessagePollingError.NotConnected, failure)
            throw failure
        }
    }

    override suspend fun setContactMessageHandler(handler: suspend (ContactMessage, ContactDTO?, DeliveryContext) -> Unit) {
        ownership.checkLifetime("setContactMessageHandler")
        synchronized(lock) { contactHandler = handler }
    }
    override suspend fun setChannelMessageHandler(handler: suspend (ChannelMessage, ChannelDTO?, DeliveryContext) -> Unit) {
        ownership.checkLifetime("setChannelMessageHandler")
        synchronized(lock) { channelHandler = handler; channelEnvelopeHandler = null }
    }
    override suspend fun setChannelReceiveEnvelopeHandler(
        handler: suspend (ChannelReceiveEnvelope, ChannelDTO?) -> Unit,
    ) {
        ownership.checkLifetime("setChannelReceiveEnvelopeHandler")
        synchronized(lock) { channelEnvelopeHandler = handler; channelHandler = null }
    }
    override suspend fun setSignedMessageHandler(handler: suspend (ContactMessage, ContactDTO?) -> Unit) {
        ownership.checkLifetime("setSignedMessageHandler")
        synchronized(lock) { signedHandler = handler }
    }
    override suspend fun setCLIMessageHandler(handler: suspend (ContactMessage, ContactDTO?) -> Unit) {
        ownership.checkLifetime("setCLIMessageHandler")
        synchronized(lock) { cliHandler = handler }
    }
    override suspend fun clearMessageHandlers() {
        synchronized(lock) {
            contactHandler = null; channelHandler = null; channelEnvelopeHandler = null
            signedHandler = null; cliHandler = null
        }
    }

    suspend fun startMessageEventMonitoring(radioId: RadioId = token.radioId): Unit = lifecycle.withLock {
        require(radioId == token.radioId) { "Polling cannot switch its radio partition" }
        checkPolling("startMessageEventMonitoring")
        if (synchronized(lock) { monitorActive }) return@withLock
        val stream = session.events(EventFilter.anyContactMessage or EventFilter.anyChannelMessage)
        synchronized(lock) { monitorActive = true }
        val task = ownership.scope.async(start = CoroutineStart.UNDISPATCHED) {
            stream.collect { event ->
                checkPolling("messageEvent")
                val deferred = synchronized(lock) {
                    val delivery = newDelivery(event, DeliveryContext.Live)
                    if (takeEcho(delivery)) true
                    else if (pollingActive) {
                        observedDuringPoll.addLast(delivery)
                        true
                    } else {
                        pending.addLast(delivery)
                        false
                    }
                }
                if (!deferred) {
                    try { deliverPending() }
                    catch (failure: MessagePollingException) { remember("messageHandler", failure) }
                    catch (failure: PersistenceStoreException) { remember("messageLookup", failure) }
                }
            }
        }
        synchronized(lock) { listener = task }
        task.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException) remember("messageMonitor", cause)
            synchronized(lock) { if (listener === task) monitorActive = false }
        }
        Unit
    }

    suspend fun stopMessageEventMonitoring() = lifecycle.withLock {
        stopAutoLocked()
        val task = synchronized(lock) { monitorActive = false; listener.also { listener = null } }
        task?.cancelAndJoin()
        synchronized(lock) { expectedEchoes.clear(); expectedChannelEchoes.clear() }
    }

    override suspend fun startAutoFetch(radioId: RadioId) {
        require(radioId == token.radioId)
        startMessageEventMonitoring(radioId)
        lifecycle.withLock {
            checkPolling("startAutoFetch")
            if (synchronized(lock) { autoFetch }) return@withLock
            synchronized(lock) { autoFetch = true; desiredAutoPaused = false; autoRevision++ }
            reconcileAutoLocked()
        }
    }
    suspend fun stopAutoFetch() = lifecycle.withLock { stopAutoLocked() }
    private suspend fun stopAutoLocked() {
        synchronized(lock) { autoFetch = false; desiredAutoPaused = false; autoRevision++; catchUpRequested = false }
        synchronized(lock) { catchUp.also { catchUp = null } }?.cancelAndJoin()
        reconcileAutoLocked()
    }
    override suspend fun pauseAutoFetch() = lifecycle.withLock {
        checkPolling("pauseAutoFetch")
        synchronized(lock) { desiredAutoPaused = true; autoRevision++ }
        reconcileAutoLocked()
    }
    override suspend fun resumeAutoFetch() = lifecycle.withLock {
        checkPolling("resumeAutoFetch")
        synchronized(lock) { desiredAutoPaused = false; autoRevision++ }
        reconcileAutoLocked()
    }

    private suspend fun reconcileAutoLocked() {
        while (true) {
            val (revision, wanted, running) = synchronized(lock) {
                Triple(autoRevision, ownership.isCurrent && autoFetch && !desiredAutoPaused && !pollingActive, autoRunning)
            }
            if (wanted == running) return
            if (wanted) session.startAutoMessageFetching() else session.stopAutoMessageFetching()
            synchronized(lock) { autoRunning = wanted }
            if (synchronized(lock) {
                revision == autoRevision && wanted == (ownership.isCurrent && autoFetch && !desiredAutoPaused && !pollingActive)
            }) return
        }
    }

    suspend fun pollMessage(): MessageResult {
        checkPolling("pollMessage")
        return try {
            session.getMessage().also { checkPolling("pollMessage.received") }
        } catch (failure: MeshCoreException) {
            throw MessagePollingException(MessagePollingError.SessionError(failure))
        }
    }

    override suspend fun pollAllMessages(): Long {
        checkPolling("pollAllMessages")
        val work = synchronized(lock) {
            if (teardown != null || ownership.closing) throw MessagePollingException(MessagePollingError.NotConnected)
            polling ?: ownership.scope.async(start = CoroutineStart.LAZY) {
                drainRadio().also { synchronized(lock) { failures.remove("pollAllMessages") } }
            }.also { task ->
                polling = task
                pollingActive = true
                task.invokeOnCompletion { cause ->
                    synchronized(lock) {
                        if (polling === task) polling = null
                    }
                    if (cause != null && cause !is CancellationException) remember("pollAllMessages", cause)
                    if (cause !is CancellationException) launchCatchUp()
                }
                task.start()
            }
        }
        // This is an observer of generation-owned work, not its cancellation owner.
        return work.await()
    }

    private fun launchCatchUp() {
        synchronized(lock) {
            if (!ownership.isCurrent || !autoFetch || desiredAutoPaused) return
            catchUpRequested = true
            if (catchUp != null) return
            val task = ownership.scope.async(start = CoroutineStart.LAZY) {
                while (synchronized(lock) {
                    ownership.isCurrent && autoFetch && !desiredAutoPaused && !pollingActive && catchUpRequested
                }) {
                    synchronized(lock) { catchUpRequested = false }
                    while (synchronized(lock) { ownership.isCurrent && autoFetch && !desiredAutoPaused && !pollingActive }) {
                        if (pollMessage() == MessageResult.NoMoreMessages) break
                    }
                }
            }
            catchUp = task
            task.invokeOnCompletion { cause ->
                val repeat = synchronized(lock) {
                    if (catchUp === task) catchUp = null
                    catchUpRequested && ownership.isCurrent && autoFetch && !desiredAutoPaused && !pollingActive
                }
                if (cause != null && cause !is CancellationException) remember("pollAllMessages.catchUp", cause)
                if (repeat) launchCatchUp()
            }
            task.start()
        }
    }

    private suspend fun drainRadio(): Long {
        val anchor = clock.wallClock.instant()
        var count = 0L
        var released = false
        var deliveryFailure: Exception? = null
        suspend fun dispatchPending() {
            val failure = attemptPendingDelivery()
            if (deliveryFailure == null) deliveryFailure = failure
        }
        try {
            lifecycle.withLock { reconcileAutoLocked() }
            synchronized(lock) { while (parked.isNotEmpty()) pending.addLast(parked.removeFirst()) }
            dispatchPending()
            while (true) {
                checkPolling("pollDrain")
                val result = pollMessage()
                val event = when (result) {
                    is MessageResult.ContactMessage -> MeshEvent.ContactMessageReceived(result.message)
                    is MessageResult.ChannelMessage -> MeshEvent.ChannelMessageReceived(result.message)
                    is MessageResult.ChannelDatagram -> continue
                    MessageResult.NoMoreMessages -> {
                        val residualFailure = finishResidualDeliveries()
                        released = true
                        (deliveryFailure ?: residualFailure)?.let { throw it }
                        return count
                    }
                }
                synchronized(lock) {
                    var delivery = newDelivery(event, DeliveryContext.InitialSync(anchor))
                    val seen = matchingIndex(observedDuringPoll, delivery)
                    if (seen >= 0) {
                        repeat(seen) { pending.addLast(observedDuringPoll.removeFirst()) }
                        val observed = observedDuringPoll.removeFirst()
                        delivery = observed.inContext(delivery.context).copy(
                            matchesChannelOccurrence = observed.matchesChannelOccurrence || delivery.matchesChannelOccurrence,
                        )
                    } else {
                        transferObserved()
                        if (monitorActive) expectEcho(delivery)
                    }
                    pending.addLast(delivery)
                }
                count = Math.incrementExact(count)
                dispatchPending()
            }
        } finally {
            if (!released) {
                synchronized(lock) {
                    transferObserved()
                    pollingActive = false
                }
            }
            withContext(NonCancellable) { lifecycle.withLock { reconcileAutoLocked() } }
        }
    }

    private fun transferObserved() {
        while (observedDuringPoll.isNotEmpty()) pending.addLast(observedDuringPoll.removeFirst())
    }

    private suspend fun finishResidualDeliveries(): Exception? {
        var failure: Exception? = null
        while (true) {
            synchronized(lock) { transferObserved() }
            val current = attemptPendingDelivery()
            if (failure == null) failure = current
            if (synchronized(lock) {
                if (observedDuringPoll.isEmpty() && pending.isEmpty()) { pollingActive = false; true } else false
            }) return failure
        }
    }

    private suspend fun attemptPendingDelivery(): Exception? =
        try { deliverPending(); null }
        catch (failure: PersistenceStoreException) { failure }
        catch (failure: MessagePollingException) {
            if (failure.error == MessagePollingError.NotConnected) throw failure
            failure
        }

    private fun newDelivery(event: MeshEvent, context: DeliveryContext): Delivery = Delivery(
        event, context,
        (event as? MeshEvent.ChannelMessageReceived)?.let {
            ChannelReceiveEnvelope(UUID.randomUUID(), token, it.message, context, ChannelReceiveOrigin.Unproved)
        },
        channelEnvelopeHandler != null,
    )

    private fun matchesEcho(first: Delivery, second: Delivery): Boolean {
        val left = first.channelEnvelope
        val right = second.channelEnvelope
        if (left != null && right != null) {
            if (left.origin != right.origin) return false
            if (first.matchesChannelOccurrence || second.matchesChannelOccurrence) {
                return left.message === right.message
            }
        }
        return first.event == second.event
    }

    private fun matchingIndex(deliveries: List<Delivery>, incoming: Delivery): Int {
        val channel = incoming.channelEnvelope
        if (channel != null) {
            // SessionCore shares the parsed message object. Prefer it across the whole buffer before
            // trying legacy equality, which could otherwise consume another outstanding occurrence.
            val exact = deliveries.indexOfFirst {
                val observed = it.channelEnvelope
                observed != null && observed.message === channel.message && observed.origin == channel.origin
            }
            if (exact >= 0) return exact
        }
        return deliveries.indexOfFirst { matchesEcho(it, incoming) }
    }

    private fun expectEcho(delivery: Delivery) {
        val envelope = delivery.channelEnvelope
        if (envelope == null) {
            expectedEchoes[delivery.event] = Math.incrementExact(expectedEchoes[delivery.event] ?: 0)
        } else {
            expectedChannelEchoes.getOrPut(envelope.message) { ArrayDeque() }.addLast(delivery)
        }
    }

    private fun takeEcho(delivery: Delivery): Boolean {
        val envelope = delivery.channelEnvelope
        if (envelope != null) {
            val echoes = expectedChannelEchoes[envelope.message] ?: return false
            val index = matchingIndex(echoes, delivery)
            if (index < 0) return false
            echoes.removeAt(index)
            if (echoes.isEmpty()) expectedChannelEchoes.remove(envelope.message)
            return true
        }
        val count = expectedEchoes[delivery.event] ?: return false
        if (count == 1L) expectedEchoes.remove(delivery.event) else expectedEchoes[delivery.event] = count - 1
        return true
    }

    private suspend fun deliverPending() = dispatch.withLock {
        var firstFailure: Exception? = null
        while (true) {
            checkPolling("dispatchMessage")
            val delivery = synchronized(lock) { pending.firstOrNull() }
            if (delivery == null) {
                firstFailure?.let { throw it }
                return@withLock
            }
            synchronized(lock) { handlerCount++ }
            try {
                when (val event = delivery.event) {
                    is MeshEvent.ContactMessageReceived -> {
                        val contact = dataStore.fetchContactByPrefix(token.radioId, event.message.senderPublicKeyPrefix)
                        checkPolling("dispatchContact.lookup")
                        val handler = synchronized(lock) {
                            when (event.message.textType.toInt()) {
                                1 -> cliHandler?.let { callback -> suspend { callback(event.message, contact) } }
                                2 -> signedHandler?.let { callback -> suspend { callback(event.message, contact) } }
                                else -> contactHandler?.let { callback -> suspend { callback(event.message, contact, delivery.context) } }
                            }
                        } ?: throw MessagePollingException(MessagePollingError.PollingFailed,
                            IllegalStateException("Contact handler is not installed"))
                        invokeConsumer(handler)
                    }
                    is MeshEvent.ChannelMessageReceived -> {
                        val channel = dataStore.fetchChannel(token.radioId, event.message.channelIndex)
                        checkPolling("dispatchChannel.lookup")
                        val handler = synchronized(lock) {
                            channelEnvelopeHandler?.let { callback ->
                                val envelope = checkNotNull(delivery.channelEnvelope)
                                suspend { callback(envelope, channel) }
                            } ?: channelHandler?.let { callback ->
                                suspend { callback(event.message, channel, delivery.context) }
                            }
                        }
                            ?: throw MessagePollingException(MessagePollingError.PollingFailed,
                                IllegalStateException("Channel handler is not installed"))
                        invokeConsumer(handler)
                    }
                    else -> throw MessagePollingException(MessagePollingError.PollingFailed)
                }
                checkPolling("dispatchMessage.completed")
                synchronized(lock) {
                    check(pending.firstOrNull() === delivery)
                    pending.removeFirst()
                    handlerFailure = null
                    failures.remove("messageHandler")
                    failures.remove("messageLookup")
                    if (parked.isEmpty()) failures.remove("messageDelivery")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val typed = if (failure is PersistenceStoreException || failure is MessagePollingException) failure
                    else MessagePollingException(MessagePollingError.PollingFailed, failure)
                if (typed is MessagePollingException && typed.error == MessagePollingError.NotConnected) throw typed
                synchronized(lock) {
                    check(pending.firstOrNull() === delivery)
                    pending.removeFirst()
                    delivery.failure = typed
                    parked.addLast(delivery)
                    handlerFailure = typed
                }
                remember("messageDelivery", typed)
                if (firstFailure == null) firstFailure = typed
            } finally { synchronized(lock) { handlerCount-- } }
        }
    }

    override suspend fun waitForPendingHandlers(timeout: Duration): Boolean {
        require(!timeout.isNegative)
        val interval = timeout.seconds.seconds + timeout.nano.nanoseconds
        require(interval.isFinite())
        val end = clock.now + interval
        while (synchronized(lock) { handlerCount > 0 || pending.isNotEmpty() || parked.isNotEmpty() }) {
            currentCoroutineContext().ensureActive()
            if (clock.now >= end) return false
            clock.sleepFor(10.milliseconds)
        }
        return true
    }

    private suspend fun invokeConsumer(handler: suspend () -> Unit) {
        val owner = checkNotNull(currentCoroutineContext()[Job]) { "A polling consumer requires an owned operation" }
        val invocation = MessageHandlerInvocation(this, owner)
        synchronized(lock) { consumerInvocations[owner] = invocation }
        try { withContext(invocation) { handler() } }
        finally { synchronized(lock) { if (consumerInvocations[owner] === invocation) consumerInvocations.remove(owner) } }
    }

    private suspend fun joinUnlessConsumerClosing(work: Job, invocation: MessageHandlerInvocation?) {
        if (invocation == null) { work.join(); return }
        coroutineScope {
            val joined = async { work.join() }
            try {
                select {
                    joined.onAwait { }
                    // An external consumer entering close cannot await the attempt currently invoking it.
                    invocation.closing.onAwait { }
                }
            } finally { joined.cancelAndJoin() }
        }
    }

    private fun closeReport(): TeardownReport = synchronized(lock) {
        val result = failures.values.map { TeardownIssue(LifecycleStage.STOP_SERVICES, it) }.toMutableList()
        for (delivery in pending + parked) result += TeardownIssue(LifecycleStage.STOP_SERVICES,
            MessagePollingException(MessagePollingError.PollingFailed, delivery.failure
                ?: handlerFailure ?: CancellationException("Generation ended with an unfinished message handler")))
        TeardownReport(result.snapshot())
    }

    suspend fun close(): TeardownReport {
        val consumer = currentCoroutineContext()[MessageHandlerInvocation]?.takeIf { it.service === this }
        consumer?.closing?.complete(Unit)
        val (receipt, claimed) = synchronized(lock) {
            teardown?.let { it to false } ?: CompletableDeferred<TeardownReport>().also {
                teardown = it
                ownership.invalidate()
            }.let { it to true }
        }
        if (!claimed) {
            if (consumer != null) return closeReport()
            return withContext(NonCancellable) { receipt.await() }
        }
        return withContext(NonCancellable) {
            val work = synchronized(lock) {
                listOfNotNull(polling, listener, catchUp).distinct().map { it to consumerInvocations[it] }
            }
            work.forEach { it.first.cancel() }
            lifecycle.withLock { stopAutoLocked() }
            for ((task, invocation) in work) joinUnlessConsumerClosing(task, invocation)
            synchronized(lock) {
                polling = null; listener = null; monitorActive = false
                expectedEchoes.clear(); expectedChannelEchoes.clear()
            }
            clearMessageHandlers()
            ownership.job.cancel()
            if (work.none { it.second?.closing?.isCompleted == true }) ownership.job.join()
            closeReport().also { receipt.complete(it) }
        }
    }
}

private class MessageHandlerInvocation(val service: MessagePollingService, val owner: Job) :
    AbstractCoroutineContextElement(Key) {
    val closing = CompletableDeferred<Unit>()
    companion object Key : CoroutineContext.Key<MessageHandlerInvocation>
}
