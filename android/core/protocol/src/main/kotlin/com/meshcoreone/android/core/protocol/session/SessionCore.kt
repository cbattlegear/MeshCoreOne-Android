// PortedFrom: MeshCore/Sources/MeshCore/Session/MeshCoreSession.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Session/MeshCoreSession+EventWaiting.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.*
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.core.protocol.parser.PacketParser
import com.meshcoreone.android.core.protocol.parser.Parsers
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow

internal sealed interface ResponseDisposition<out T> {
    data class Success<T>(val value: T) : ResponseDisposition<T>
    data class Failure(val error: Exception) : ResponseDisposition<Nothing>
    data object Ignore : ResponseDisposition<Nothing>
}

internal const val ARBITRARY_RESPONSE_FAMILY = "*"

internal class PendingEvents(val filter: (MeshEvent) -> Boolean) {
    val id: UUID = UUID.randomUUID()
    val channel = Channel<MeshEvent>(Channel.UNLIMITED)
}

internal class SessionGeneration(
    val number: Long,
    context: CoroutineContext,
    orphanFailure: (Throwable) -> Unit,
) {
    val job = SupervisorJob(context[Job])
    val scope = CoroutineScope(context + job)
    val dispatcher = EventDispatcher()
    val serializer = RequestResponseSerializer(scope, orphanFailure)
    val contacts = ContactManager()
    val pending = linkedMapOf<UUID, PendingEvents>()
    val unresolvedReplies = mutableSetOf<String>()
    val retiredBinaryTags = mutableSetOf<Bytes>()
    val retiredAckCodes = mutableSetOf<Bytes>()
    val disconnectClaim = AtomicBoolean(false)
    var failure: MeshCoreException.ConnectionLost? = null
    var ended = false
    var ownsTransport = false
    var running = false
    var ready = false
    var receive: Deferred<Unit>? = null
    var receiveStream: Flow<Bytes>? = null
    var startup: Deferred<Unit>? = null
    var teardown: Deferred<Unit>? = null
    var selfInfo: SelfInfo? = null
    var deviceTime: Instant? = null
    var capabilities: DeviceCapabilities? = null
    var contactProgress: ContactStreamProgress? = null
    var poll: Deferred<MessageResult>? = null
    var autoFetching = false
    var autoRequested = false
    var autoSubscription: EventSubscription? = null
    var autoListener: Deferred<Unit>? = null
    var autoDrain: Deferred<Unit>? = null
    var contactRefreshRequested = false
    var contactRefresh: Deferred<Unit>? = null
}

internal class SessionCore(
    val transport: MeshTransport,
    val configuration: SessionConfiguration,
    val clock: SessionClock,
    private val context: CoroutineContext,
    val diagnostic: (SessionDiagnostic) -> Unit,
    private val offlinePlaceholder: Boolean,
) {
    init {
        timeoutDuration(configuration.defaultTimeout)
        timeoutDuration(configuration.binaryRequestOverallTimeout)
        configuration.binaryRequestRetransmitInterval?.let { timeoutDuration(it) }
        timeoutDuration(configuration.contactStreamInactivityTimeout)
        timeoutDuration(configuration.contactStreamHardTimeout)
        timeoutDuration(configuration.channelPipelineIdleTimeout)
        timeoutDuration(configuration.channelPipelineHardTimeout)
        timeoutDuration(configuration.channelPipelinePostDrainGrace, allowZero = true)
    }

    val lock = Any()
    private var lifecycleJob = SupervisorJob(context[Job])
    private var precedingCleanup: Deferred<Unit>? = null
    private var nextGeneration = 1L
    var active: SessionGeneration? = newGeneration()
        private set
    private var lastGeneration: SessionGeneration? = null
    private var physicalOwner: SessionGeneration? = null
    private var retainedTransport = false
    private var state: ConnectionState = ConnectionState.Disconnected
    private val stateSubscriptions = linkedMapOf<UUID, Channel<ConnectionState>>()
    private var stateStreamsEnded = false

    private fun newGeneration(): SessionGeneration {
        ensureLifecycleJob()
        val number = nextGeneration
        nextGeneration = Math.addExact(nextGeneration, 1)
        return SessionGeneration(number, context + lifecycleJob) {
            diagnostic(SessionDiagnostic.BackgroundFailure(number, "orphan-drain", it))
        }
    }

    private fun ensureLifecycleJob() {
        if (!lifecycleJob.isActive) {
            lifecycleJob = SupervisorJob(context[Job])
        }
    }

    fun generation(): SessionGeneration = synchronized(lock) { active ?: throw MeshCoreException.SessionNotStarted() }

    fun requireCurrent(generation: SessionGeneration) = synchronized(lock) {
        if (active !== generation || generation.ended) throw generation.failure ?: MeshCoreException.ConnectionLost()
    }

    fun isCurrent(generation: SessionGeneration): Boolean = synchronized(lock) { active === generation && !generation.ended }

    fun eventsTracked(filter: EventFilter? = null): EventSubscription = synchronized(lock) {
        val dispatcher = active?.dispatcher ?: EventDispatcher()
        val subscription = if (filter == null) dispatcher.subscribeTracked() else dispatcher.subscribeTracked(filter)
        if (active == null) dispatcher.finishSubscription(subscription.id)
        subscription
    }

    fun finishEvents(id: UUID) = synchronized(lock) {
        active?.dispatcher?.finishSubscription(id)
        lastGeneration?.dispatcher?.finishSubscription(id)
    }

    fun connectionStates(): Flow<ConnectionState> {
        val id = UUID.randomUUID()
        val queue = Channel<ConnectionState>(Channel.UNLIMITED)
        synchronized(lock) {
            queue.trySend(state).getOrThrow()
            if (stateStreamsEnded) queue.close() else stateSubscriptions[id] = queue
        }
        val collected = AtomicBoolean(false)
        return flow {
            check(collected.compareAndSet(false, true)) { "A connection-state subscription permits one consumer" }
            try {
                for (next in queue) emit(next)
            } finally {
                synchronized(lock) { stateSubscriptions.remove(id); queue.cancel() }
            }
        }
    }

    private fun updateState(newState: ConnectionState) {
        synchronized(lock) {
            state = newState
            stateSubscriptions.values.forEach { it.trySend(newState).getOrThrow() }
        }
    }

    suspend fun start(reconnectingAttempt: Long?, disconnectTransportOnFailure: Boolean) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        if (offlinePlaceholder) throw MeshTransportError.NotConnected
        val (generation, startup) = synchronized(lock) {
            if (retainedTransport) throw MeshCoreException.ConnectionLost(SessionCorrelationException.RetainedTransport())
            val generation = active ?: newGeneration().also { active = it; stateStreamsEnded = false }
            if (generation.ready) return
            val predecessor = precedingCleanup
            val startup = generation.startup ?: generation.scope.async(start = CoroutineStart.LAZY) {
                predecessor?.await()
                requireCurrent(generation)
                synchronized(lock) {
                    if (retainedTransport) throw MeshCoreException.ConnectionLost(SessionCorrelationException.RetainedTransport())
                }
                ensureTransportOwnership(generation)
                updateState(if (reconnectingAttempt == null) ConnectionState.Connecting
                    else ConnectionState.Reconnecting(maxOf(1, reconnectingAttempt)))
                transport.connect()
                requireCurrent(generation)
                synchronized(lock) { generation.running = true }
                updateState(ConnectionState.Connected)
                val incoming = transport.receivedData()
                synchronized(lock) {
                    requireCurrent(generation)
                    generation.receiveStream = incoming
                    generation.receive = generation.scope.async(start = CoroutineStart.LAZY) {
                        incoming.collect { data -> receive(generation, data) }
                    }.also { reader ->
                        reader.invokeOnCompletion { cause ->
                            if (isCurrent(generation)) {
                                diagnostic(SessionDiagnostic.StreamEnded(generation.number, cause))
                                val terminalState = if (cause == null) ConnectionState.Disconnected else ConnectionState.Failed(
                                    cause as? MeshTransportError ?: MeshTransportError.ConnectionFailed("session.receive ${cause.javaClass.simpleName}"),
                                )
                                endGeneration(generation, cause, terminalState, finishStateStreams = true, disconnect = false)
                            }
                        }
                        reader.start()
                    }
                }
                exchange(generation) {
                    query(PacketBuilder.appStart(configuration.clientIdentifier), "selfInfo") {
                        (it as? MeshEvent.SelfInfo)?.info
                    }
                }
                synchronized(lock) { requireCurrent(generation); generation.ready = true }
            }.also { generation.startup = it; it.start() }
            generation to startup
        }
        try {
            startup.await()
        } catch (failure: Exception) {
            // Startup is a lifecycle boundary: unwind, retain the original failure, then rethrow it.
            withContext(NonCancellable) {
                if (isCurrent(generation)) {
                    val mapped = failure as? MeshTransportError
                        ?: MeshTransportError.ConnectionFailed("session.start ${failure.javaClass.simpleName}")
                    endGeneration(generation, failure, ConnectionState.Failed(mapped), finishStateStreams = false,
                        disconnect = disconnectTransportOnFailure, retain = !disconnectTransportOnFailure).await()
                } else {
                    synchronized(lock) { generation.teardown }?.await()
                }
            }
            if (failure is CancellationException && caller.isActive) generation.failure?.let { throw it }
            throw failure
        }
    }

    suspend fun stop(disconnectTransport: Boolean) {
        val (generation, physical) = synchronized(lock) {
            (active ?: lastGeneration ?: physicalOwner) to physicalOwner
        }
        if (generation == null) return
        if (disconnectTransport && physical == null && !generation.ownsTransport && isCurrent(generation)) {
            try {
                ensureTransportOwnership(generation)
            } catch (failure: MeshCoreException.ConnectionLost) {
                val cleanup = endGeneration(generation, failure, ConnectionState.Disconnected, finishStateStreams = true, disconnect = false)
                withContext(NonCancellable) { cleanup.await() }
                throw failure
            }
        }
        val cleanup = endGeneration(generation, null, ConnectionState.Disconnected, finishStateStreams = true,
            disconnect = disconnectTransport && (physical == null || physical === generation), retain = !disconnectTransport)
        val physicalCleanup = if (disconnectTransport && physical != null && physical !== generation) {
            endGeneration(physical, null, ConnectionState.Disconnected, finishStateStreams = true, disconnect = true)
        } else null
        withContext(NonCancellable) {
            var failure: Exception? = null
            for (task in listOfNotNull(cleanup, physicalCleanup)) {
                try {
                    task.await()
                } catch (issue: Exception) {
                    if (failure == null) failure = issue else failure.addSuppressed(issue)
                }
            }
            failure?.let { throw it }
        }
    }

    private fun endGeneration(
        generation: SessionGeneration, cause: Throwable?, newState: ConnectionState, finishStateStreams: Boolean,
        disconnect: Boolean, retain: Boolean = false,
    ): Deferred<Unit> = synchronized(lock) {
            val endedHere = active === generation
            if (endedHere) {
                generation.failure = MeshCoreException.ConnectionLost(cause)
                generation.ended = true
                generation.running = false
                generation.ready = false
                active = null
                lastGeneration = generation
                generation.dispatcher.dispatch(MeshEvent.ConnectionStateChanged(newState))
                generation.pending.values.forEach { it.channel.close(generation.failure) }
                generation.pending.clear()
                generation.dispatcher.finishAllSubscriptions()
                generation.autoFetching = false
                generation.autoRequested = false
                generation.contactRefreshRequested = false
                updateState(newState)
                generation.job.cancel(CancellationException("session.generationEnded").apply { initCause(generation.failure) })
            }
            if (lastGeneration === generation && active == null && finishStateStreams) {
                if (!endedHere && !stateStreamsEnded) updateState(newState)
                stateStreamsEnded = true
                stateSubscriptions.values.forEach { it.close() }
                stateSubscriptions.clear()
            }
            val previous = generation.teardown
            if (previous != null && (!disconnect || generation.disconnectClaim.get())) return@synchronized previous
            if (retain && generation.ownsTransport) retainedTransport = true
            val cleanupParent = lifecycleJob
            // Only the bounded, explicitly retained teardown receipt outlives owning-job cancellation.
            val cleanup = CoroutineScope(context + NonCancellable).async(start = CoroutineStart.LAZY) {
                previous?.await()
                try {
                    if (disconnect && generation.ownsTransport && !generation.disconnectClaim.get()) {
                        SessionTransportOwnership.reserveClose(transport, this@SessionCore, generation.number, !transport.isConnected())
                        if (generation.disconnectClaim.compareAndSet(false, true)) transport.disconnect()
                    }
                } catch (failure: Exception) {
                    diagnostic(SessionDiagnostic.BackgroundFailure(generation.number, "disconnect", failure))
                    if (cause == null) throw failure
                    cause.addSuppressed(failure)
                } finally {
                    generation.job.cancelAndJoin()
                    if (generation.ownsTransport) {
                        val connected = transport.isConnected()
                        SessionTransportOwnership.release(transport, this@SessionCore, generation.number, retained = connected)
                        synchronized(lock) {
                            if (physicalOwner === generation) {
                                retainedTransport = connected
                                if (!connected) physicalOwner = null
                            }
                        }
                    }
                }
            }
            generation.teardown = cleanup
            precedingCleanup = cleanup
            cleanup.invokeOnCompletion {
                synchronized(lock) { if (active == null && lifecycleJob === cleanupParent) cleanupParent.complete() }
            }
            cleanup.start()
            cleanup
        }

    private fun receive(generation: SessionGeneration, data: Bytes) {
        if (!isCurrent(generation)) return
        var event = PacketParser.parse(data, clock.wallClock)
        synchronized(lock) {
            if (!isCurrent(generation)) return
            if (event is MeshEvent.StatusResponse &&
                generation.contacts.getByKeyPrefix(event.response.publicKeyPrefix)?.type == ContactType.ROOM) {
                event = Parsers.StatusResponse.parse(data.slice(1, data.size), StatusResponse.Layout.ROOM_SERVER)
            }
            generation.contacts.trackChanges(event)
            when (val received = event) {
                is MeshEvent.CurrentTime -> generation.deviceTime = received.time
                is MeshEvent.SelfInfo -> generation.selfInfo = received.info
                is MeshEvent.DeviceInfo -> generation.capabilities = received.info
                is MeshEvent.ParseFailure ->
                    diagnostic(SessionDiagnostic.ParseFailure(generation.number, data.size, received.reason))
                else -> Unit
            }
            generation.pending.values.forEach { if (it.filter(event)) it.channel.trySend(event).getOrThrow() }
            generation.dispatcher.dispatch(event)
            if (generation.contacts.isAutoUpdateEnabled && generation.contacts.needsRefresh &&
                (event is MeshEvent.Advertisement || event is MeshEvent.PathUpdate || event is MeshEvent.NewContact)) {
                requestContactRefresh(generation)
            }
        }
    }

    fun register(generation: SessionGeneration, filter: (MeshEvent) -> Boolean = { true }): PendingEvents =
        synchronized(lock) {
            requireCurrent(generation)
            PendingEvents(filter).also { generation.pending[it.id] = it }
        }

    fun unregister(generation: SessionGeneration, subscription: PendingEvents) = synchronized(lock) {
        generation.pending.remove(subscription.id)
        subscription.channel.close()
    }

    fun unresolved(generation: SessionGeneration, family: String, acceptsErrors: Boolean) = synchronized(lock) {
        generation.unresolvedReplies += family
        if (acceptsErrors) generation.unresolvedReplies += "error"
        diagnostic(SessionDiagnostic.CorrelationUncertain(generation.number, family))
    }

    fun checkCorrelation(generation: SessionGeneration, family: String, acceptsErrors: Boolean) = synchronized(lock) {
        requireCurrent(generation)
        val unresolved = when {
            ARBITRARY_RESPONSE_FAMILY in generation.unresolvedReplies -> ARBITRARY_RESPONSE_FAMILY
            family == ARBITRARY_RESPONSE_FAMILY && generation.unresolvedReplies.isNotEmpty() ->
                generation.unresolvedReplies.first()
            family in generation.unresolvedReplies -> family
            acceptsErrors && "error" in generation.unresolvedReplies -> "error"
            else -> null
        }
        if (unresolved != null) throw MeshCoreException.ConnectionLost(
            SessionCorrelationException.UnresolvedReply(generation.number, unresolved),
        )
    }

    private suspend fun ensureTransportOwnership(generation: SessionGeneration) {
        requireCurrent(generation)
        val disconnected = !transport.isConnected()
        synchronized(lock) {
            requireCurrent(generation)
            SessionTransportOwnership.acquire(transport, this, generation.number, disconnected)
            generation.ownsTransport = true
            physicalOwner = generation
        }
    }

    suspend fun <T> exchange(operation: suspend ExchangeOwner.() -> T): T = exchange(generation(), operation)

    suspend fun <T> exchange(generation: SessionGeneration, operation: suspend ExchangeOwner.() -> T): T {
        val caller = currentCoroutineContext()
        if (offlinePlaceholder) {
            caller.ensureActive()
            throw MeshTransportError.NotConnected
        }
        try {
            return generation.serializer.withOwnedSerialization {
                requireCurrent(generation)
                if (synchronized(lock) { ARBITRARY_RESPONSE_FAMILY in generation.unresolvedReplies }) {
                    checkCorrelation(generation, ARBITRARY_RESPONSE_FAMILY, acceptsErrors = false)
                }
                ensureTransportOwnership(generation)
                ExchangeOwner(this@SessionCore, generation, this).operation()
            }
        } catch (cancelled: CancellationException) {
            if (caller.isActive) generation.failure?.let { throw it }
            throw cancelled
        }
    }

    suspend fun waitForEvent(filter: (MeshEvent) -> Boolean, timeout: Double?): MeshEvent? {
        val subscription = eventsTracked(EventFilter(filter))
        val generation = synchronized(lock) { active }
        return try {
            clock.withDeadline(timeout ?: configuration.defaultTimeout) {
                subscription.stream.firstOrNull { event ->
                    generation != null && isCurrent(generation) &&
                        (event !is MeshEvent.Acknowledgement || synchronized(lock) {
                            event.code !in generation.retiredAckCodes
                        })
                }
            }
        } catch (_: MeshCoreException.Timeout) {
            null
        } finally {
            finishEvents(subscription.id)
        }
    }

    suspend fun getMessage(timeout: Double?): MessageResult {
        val generation = generation()
        val task = synchronized(lock) {
            requireCurrent(generation)
            generation.poll ?: generation.scope.async(start = CoroutineStart.LAZY) {
                exchange(generation) {
                    match(PacketBuilder.getMessage(), "message", timeout = timeout, acceptsErrors = true) { event ->
                        when (event) {
                            is MeshEvent.ContactMessageReceived -> ResponseDisposition.Success(MessageResult.ContactMessage(event.message))
                            is MeshEvent.ChannelMessageReceived -> ResponseDisposition.Success(MessageResult.ChannelMessage(event.message))
                            is MeshEvent.ChannelDataReceived -> ResponseDisposition.Success(MessageResult.ChannelDatagram(event.datagram))
                            MeshEvent.NoMoreMessages -> ResponseDisposition.Success(MessageResult.NoMoreMessages)
                            is MeshEvent.Error -> ResponseDisposition.Failure(MeshCoreException.DeviceError(event.code ?: 0u))
                            else -> ResponseDisposition.Ignore
                        }
                    }
                }
            }.also { poll ->
                generation.poll = poll
                poll.invokeOnCompletion { synchronized(lock) { if (generation.poll === poll) generation.poll = null } }
                poll.start()
            }
        }
        try {
            return task.await()
        } catch (cancelled: CancellationException) {
            if (currentCoroutineContext().isActive) generation.failure?.let { throw it }
            throw cancelled
        }
    }

    suspend fun startAutoMessageFetching() {
        val generation = generation()
        synchronized(lock) {
            requireCurrent(generation)
            if (generation.autoFetching) return
            generation.autoFetching = true
            val subscription = generation.dispatcher.subscribeTracked(EventFilter.messagesWaiting)
            generation.autoSubscription = subscription
            generation.autoListener = generation.scope.async {
                subscription.stream.collect { requestAutoDrain(generation) }
            }
        }
    }

    suspend fun stopAutoMessageFetching() {
        val generation = synchronized(lock) { active ?: lastGeneration } ?: return
        val tasks = synchronized(lock) {
            generation.autoFetching = false
            generation.autoRequested = false
            generation.autoSubscription?.let { generation.dispatcher.finishSubscription(it.id) }
            generation.autoSubscription = null
            listOfNotNull(generation.autoListener, generation.autoDrain).also {
                generation.autoListener = null
                generation.autoDrain = null
            }
        }
        tasks.forEach { it.cancel() }
        withContext(NonCancellable) { tasks.forEach { it.join() } }
    }

    private fun requestAutoDrain(generation: SessionGeneration): Unit = synchronized(lock) {
        if (!isCurrent(generation) || !generation.autoFetching) return
        generation.autoRequested = true
        if (generation.autoDrain != null) return
        val drain = generation.scope.async(start = CoroutineStart.LAZY) {
            while (synchronized(lock) { isCurrent(generation) && generation.autoFetching && generation.autoRequested }) {
                synchronized(lock) { generation.autoRequested = false }
                while (synchronized(lock) { isCurrent(generation) && generation.autoFetching }) {
                    if (getMessage(null) == MessageResult.NoMoreMessages) break
                    clock.sleepFor(kotlin.time.Duration.parse("100ms"))
                }
            }
        }
        generation.autoDrain = drain
        drain.invokeOnCompletion { cause ->
            synchronized(lock) { if (generation.autoDrain === drain) generation.autoDrain = null }
            if (cause != null && cause !is CancellationException) {
                diagnostic(SessionDiagnostic.BackgroundFailure(generation.number, "auto-message-fetch", cause))
            }
            if (synchronized(lock) { generation.autoRequested && generation.autoFetching && isCurrent(generation) }) {
                requestAutoDrain(generation)
            }
        }
        drain.start()
        Unit
    }

    private fun requestContactRefresh(generation: SessionGeneration): Unit = synchronized(lock) {
        if (!isCurrent(generation)) return
        generation.contactRefreshRequested = true
        if (generation.contactRefresh != null) return
        val refresh = generation.scope.async(start = CoroutineStart.LAZY) {
            while (synchronized(lock) { isCurrent(generation) && generation.contactRefreshRequested }) {
                synchronized(lock) { generation.contactRefreshRequested = false }
                fetchContacts(generation.contacts.contactsLastModified)
            }
        }
        generation.contactRefresh = refresh
        refresh.invokeOnCompletion { cause ->
            synchronized(lock) { if (generation.contactRefresh === refresh) generation.contactRefresh = null }
            if (cause != null && cause !is CancellationException) {
                diagnostic(SessionDiagnostic.BackgroundFailure(generation.number, "auto-contact-refresh", cause))
            }
            if (synchronized(lock) { generation.contactRefreshRequested && isCurrent(generation) }) requestContactRefresh(generation)
        }
        refresh.start()
        Unit
    }
}

internal class ExchangeOwner(
    val core: SessionCore,
    val generation: SessionGeneration,
    val context: RequestContext,
) {
    suspend fun send(
        data: Bytes, cleanup: Boolean = false, withoutResponse: Boolean = false,
        onWriteAttempt: () -> Unit = {},
    ) {
        core.requireCurrent(generation)
        supervisorScope {
            val operation = async(start = CoroutineStart.LAZY) {
                core.requireCurrent(generation)
                context.beforeSend(cleanup)
                onWriteAttempt()
                if (withoutResponse) core.transport.sendWithoutResponse(data) else core.transport.send(data)
            }
            context.attachSend(operation, cleanup)
            operation.start()
            try { operation.await() } finally { context.detachSend(operation) }
        }
        core.requireCurrent(generation)
    }

    suspend fun <T> query(
        data: Bytes, family: String, timeout: Double? = null,
        errorMatcher: ((MeshEvent) -> Exception?)? = null,
        cleanup: Boolean = false, predicate: (MeshEvent) -> T?,
    ): T = match(data, family, timeout, errorMatcher != null, cleanup) { event ->
        val error = errorMatcher?.invoke(event)
        if (error != null) ResponseDisposition.Failure(error)
        else predicate(event)?.let { ResponseDisposition.Success(it) } ?: ResponseDisposition.Ignore
    }

    suspend fun <T> match(
        data: Bytes, family: String, timeout: Double? = null,
        acceptsErrors: Boolean = false, cleanup: Boolean = false,
        matcher: (MeshEvent) -> ResponseDisposition<T>,
    ): T {
        core.checkCorrelation(generation, family, acceptsErrors)
        val subscription = core.register(generation)
        var sent = false
        var terminalResponse = false
        try {
            return core.clock.withDeadline(timeout ?: core.configuration.defaultTimeout) {
                send(data, cleanup, onWriteAttempt = { sent = true })
                for (event in subscription.channel) {
                    when (val result = matcher(event)) {
                        is ResponseDisposition.Success -> {
                            terminalResponse = true
                            return@withDeadline result.value
                        }
                        is ResponseDisposition.Failure -> {
                            terminalResponse = true
                            throw result.error
                        }
                        ResponseDisposition.Ignore -> Unit
                    }
                }
                throw generation.failure ?: MeshCoreException.ConnectionLost()
            }
        } finally {
            if (sent && !terminalResponse && core.isCurrent(generation)) core.unresolved(generation, family, acceptsErrors = true)
            core.unregister(generation, subscription)
        }
    }

    suspend fun simple(data: Bytes, cleanup: Boolean = false) {
        query(data, "ok", errorMatcher = ::deviceError, cleanup = cleanup) {
            if (it is MeshEvent.Ok && it.value == null) Unit else null
        }
    }

    suspend fun rollbackSimple(data: Bytes) {
        val ambiguousErrors = synchronized(core.lock) { "error" in generation.unresolvedReplies }
        match(data, "ok", cleanup = true) {
            when {
                it is MeshEvent.Ok && it.value == null -> ResponseDisposition.Success(Unit)
                it is MeshEvent.Error && !ambiguousErrors ->
                    ResponseDisposition.Failure(MeshCoreException.DeviceError(it.code ?: 0u))
                it is MeshEvent.Error -> {
                    core.diagnostic(SessionDiagnostic.BackgroundFailure(
                        generation.number, "rollback-uncorrelated-error", MeshCoreException.DeviceError(it.code ?: 0u),
                    ))
                    ResponseDisposition.Ignore
                }
                else -> ResponseDisposition.Ignore
            }
        }
    }
}

internal fun deviceError(event: MeshEvent): MeshCoreException.DeviceError? =
    (event as? MeshEvent.Error)?.let { MeshCoreException.DeviceError(it.code ?: 0u) }

internal fun requireFullPublicKey(key: Bytes, operation: String) {
    if (key.size != PacketBuilder.PUBLIC_KEY_SIZE) {
        throw MeshCoreException.InvalidInput("Full ${PacketBuilder.PUBLIC_KEY_SIZE}-byte public key required for $operation")
    }
}

internal fun requirePrefix(key: Bytes, operation: String) {
    if (key.size < 6) throw MeshCoreException.InvalidInput("At least 6 public-key bytes required for $operation")
}
