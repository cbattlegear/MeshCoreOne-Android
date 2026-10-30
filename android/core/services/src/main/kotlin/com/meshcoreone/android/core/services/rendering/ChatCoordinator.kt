// PortedFrom: MC1Services/Sources/MC1Services/Services/ChatCoordinator.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/ChatCoordinator+Mutations.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/ChatCoordinator+Reload.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/ChatCoordinator+Rebuild.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.MessagePersisting
import com.meshcoreone.android.core.contracts.domain.MessageWindow
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.ConversationKey
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.indexByID
import com.meshcoreone.android.core.model.snapshot
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Role a bound timeline writer plays. [INTERACTIVE] (the live conversation UI) always binds and revokes
 * any prior writer; [PRIME] (speculative warm) binds only while no live interactive owner exists.
 */
enum class ChatWriterRole { INTERACTIVE, PRIME }

data class ChatTimelineSnapshot(
    val messages: SnapshotList<MessageDTO>,
    val renderState: ChatRenderState,
    val revision: ULong,
)

/**
 * Per-(radio, conversation) source of truth for chat timeline state, shared by every view model showing
 * the same conversation and resolved through [ChatCoordinatorRegistry].
 *
 * Concurrency: Swift confines this class to the main actor. Here all state lives behind one lock, no
 * lock is held across a suspension point, and hooks/callbacks run outside the lock. Asynchronous work
 * (coalesced reloads, hard resets, rebuilds) is launched on the injected [scope], which plays the main
 * actor and must use a dispatching dispatcher (never an immediate/unconfined one) so a launched job,
 * like a Swift `Task`, never runs before the scheduling call returns. The pure builder loop runs on
 * [buildDispatcher] (the Swift `@concurrent` hop). Launched jobs hold the coordinator strongly until they
 * finish or [cancelInFlight] cancels them (Swift captures `self` weakly).
 */
class ChatCoordinator internal constructor(
    val conversationID: ChatConversationID,
    internal val dataStore: MessagePersisting,
    private val scope: CoroutineScope,
    private val buildDispatcher: CoroutineDispatcher,
    private val hiddenOutgoingReaction: HiddenOutgoingReactionPredicate = HiddenOutgoingReactionPredicate.SourceWireFormat,
) {
    internal val logger: Logger = Logger.getLogger("ChatCoordinator")
    private val lock = Any()

    private val messagesState = MutableStateFlow<SnapshotList<MessageDTO>>(SnapshotList.empty())
    private val renderStateFlowBacking = MutableStateFlow(ChatRenderState.EMPTY)
    private var messagesByIDValue: Map<UUID, MessageDTO> = emptyMap()
    private var renderStateIDValue: ULong = 0UL
    private var pendingReloadIDsValue: Set<UUID> = emptySet()
    private var reloadInFlightValue = false
    private var hardResetInFlightValue = false
    private var buildItemsTaskValue: Job? = null
    private var coalescedReloadTaskValue: Job? = null
    private var hardResetTaskValue: Job? = null
    private var windowOperationTail: CompletableDeferred<Unit>? = null
    private var renderItemRebuilderValue: ((UUID) -> Unit)? = null
    private var renderStateInvalidatedValue: (() -> Unit)? = null
    private var writerGenerationValue: ULong = 0UL
    private var writerOwner: WeakReference<Any>? = null
    private var writerRole: ChatWriterRole = ChatWriterRole.PRIME

    /** Canonical loaded messages, oldest first. Mutated only through this class. */
    val messages: SnapshotList<MessageDTO> get() = messagesState.value

    /** Observation surface for [messages] (the Swift `@Observable` property). */
    val messagesFlow: StateFlow<SnapshotList<MessageDTO>> = messagesState.asStateFlow()

    /** O(1) lookup keyed by message id; every append/update guard reads this, never [renderState]. */
    val messagesByID: Map<UUID, MessageDTO> get() = synchronized(lock) { messagesByIDValue }

    /** Immutable timeline snapshot rendered by the chat list. */
    val renderState: ChatRenderState get() = renderStateFlowBacking.value

    /** Observation surface for [renderState] (the Swift `@Observable` property). */
    val renderStateFlow: StateFlow<ChatRenderState> = renderStateFlowBacking.asStateFlow()

    /**
     * Monotonic (wrapping) counter bumped on every mutation of [messages] and every non-build render-state
     * assignment. An off-thread build captures it and its result is discarded if it advanced meanwhile.
     */
    val renderStateID: ULong get() = synchronized(lock) { renderStateIDValue }

    /** One coherent read for consumers assembling DTO and render projections across threads. */
    fun snapshot(): ChatTimelineSnapshot = synchronized(lock) {
        ChatTimelineSnapshot(messages, renderState, renderStateIDValue)
    }

    /** In-flight off-thread batch build, cancelled before each new [rebuildItems]. */
    val buildItemsTask: Job? get() = synchronized(lock) { buildItemsTaskValue }

    /** In-flight coalesced-reload drain. */
    val coalescedReloadTask: Job? get() = synchronized(lock) { coalescedReloadTaskValue }

    /** In-flight hard-reset refetch. */
    val hardResetTask: Job? get() = synchronized(lock) { hardResetTaskValue }

    /** Ids accumulated since the last load cycle; the next coalesced load drains them atomically. */
    internal val pendingReloadIDs: Set<UUID> get() = synchronized(lock) { pendingReloadIDsValue }

    /** Whether a coalesced load cycle is in flight. */
    internal val reloadInFlight: Boolean get() = synchronized(lock) { reloadInFlightValue }

    /** Gates new reload drains while a hard reset is mid-flight. */
    internal val hardResetInFlight: Boolean get() = synchronized(lock) { hardResetInFlightValue }

    /**
     * Per-id render-item rebuild hook run after [applyReloadedIDs] refreshes a DTO. Installed only by
     * [bindWriter] so hook ownership and write ownership are one atomic act.
     */
    var renderItemRebuilder: ((UUID) -> Unit)?
        get() = synchronized(lock) { renderItemRebuilderValue }
        internal set(value) = synchronized(lock) { renderItemRebuilderValue = value }

    /**
     * Fires when [renderState] no longer reflects [messages] and the bound view model must reassemble
     * inputs: a stale rebuild was rejected, or a hard reset replaced the timeline.
     */
    var renderStateInvalidated: (() -> Unit)?
        get() = synchronized(lock) { renderStateInvalidatedValue }
        internal set(value) = synchronized(lock) { renderStateInvalidatedValue = value }

    /** Generation stamp of the most recent [bindWriter]; a writer with an older stamp is stale. */
    internal val writerGeneration: ULong get() = synchronized(lock) { writerGenerationValue }

    /**
     * Runs [block] under the coordinator lock only while [generation] is still the bound writer's, so a
     * writer's staleness check and the write it guards are one step (Swift does both on the main actor).
     * Returns null when the writer is stale. The lock is reentrant, so [block] may call the mutations.
     */
    internal fun <T> ifWriterGeneration(generation: ULong, block: () -> T): T? =
        synchronized(lock) { if (generation != writerGenerationValue) null else block() }

    /** Swift's view-model callbacks cannot throw; a throwing Kotlin callback is logged, never propagated. */
    private fun runCallback(name: String, callback: () -> Unit) {
        try {
            callback()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logger.severe("$name callback failed: $error")
        }
    }

    /** Debug seam (Swift `#if DEBUG`): when set, populate throws this after the entry spinner clear. */
    @Volatile
    var testPopulateFetchError: Throwable? = null

    /** Debug seam: awaited in populate after the window fetch so a test can cancel before commit. */
    @Volatile
    var testPopulateAfterFetchHook: (suspend () -> Unit)? = null

    /** Debug seam: awaited in [hardReset] after the window fetch so a test can cancel before `replaceAll`. */
    @Volatile
    internal var hardResetAfterFetchHook: (suspend () -> Unit)? = null

    // region Writer ownership

    /**
     * Claims write access and installs the rebuild hooks. [ChatWriterRole.INTERACTIVE] always succeeds and
     * makes every previously minted writer stale; [ChatWriterRole.PRIME] returns null while a live
     * interactive owner holds the slot. The owner is held weakly. Reads stay unrestricted.
     */
    fun bindWriter(
        owner: Any,
        role: ChatWriterRole,
        renderItemRebuilder: ((UUID) -> Unit)? = null,
        renderStateInvalidated: (() -> Unit)? = null,
    ): ChatTimelineWriter? {
        val generation = synchronized(lock) {
            if (role == ChatWriterRole.PRIME && writerRole == ChatWriterRole.INTERACTIVE && writerOwner?.get() != null) {
                null
            } else {
                writerGenerationValue += 1UL
                renderStateIDValue += 1UL
                settleReleasedWindowLocked()
                buildItemsTaskValue?.cancel()
                buildItemsTaskValue = null
                writerOwner = WeakReference(owner)
                writerRole = role
                renderItemRebuilderValue = renderItemRebuilder
                renderStateInvalidatedValue = renderStateInvalidated
                writerGenerationValue
            }
        }
        if (generation == null) {
            logger.info("bindWriter: prime bind denied; interactive owner active for $conversationID")
            return null
        }
        return ChatTimelineWriter(this, generation, role)
    }

    /**
     * Vacates the writer slot if [owner] still holds it, clearing the hooks with it. The identity check
     * keeps a stale view's teardown from evicting a successor. Release also revokes the writer:
     * a cancelled screen's last suspended operation must not commit after teardown.
     */
    fun releaseWriter(owner: Any) {
        synchronized(lock) {
            if (writerOwner?.get() !== owner) return
            writerGenerationValue += 1UL
            renderStateIDValue += 1UL
            settleReleasedWindowLocked()
            buildItemsTaskValue?.cancel()
            buildItemsTaskValue = null
            writerOwner = null
            writerRole = ChatWriterRole.PRIME
            renderItemRebuilderValue = null
            renderStateInvalidatedValue = null
        }
    }

    private fun settleReleasedWindowLocked() {
        val state = renderState
        val phase = if (state.phase == ChatRenderState.LoadPhase.LOADING) ChatRenderState.LoadPhase.UNINITIALIZED else state.phase
        if (state.isLoadingOlder || state.phase != phase) {
            renderStateFlowBacking.value = state.with(isLoadingOlder = false, phase = phase)
        }
    }

    // endregion

    // region Window lane

    /**
     * Runs [operation] after every prior window operation finished, in the caller's coroutine so
     * cancellation and errors propagate; populate, loadOlder and hardReset never interleave. Like the
     * Swift lane, waiting for the predecessor is not interruptible, so successors keep FIFO order behind
     * a cancelled waiter. Unlike Swift (which starts the operation and relies on its own cancellation
     * check), a caller cancelled while waiting throws instead of starting [operation].
     */
    suspend fun <T> performWindowOperation(operation: suspend () -> T): T {
        val turn = CompletableDeferred<Unit>()
        val prior = synchronized(lock) { windowOperationTail.also { windowOperationTail = turn } }
        try {
            if (prior != null) withContext(NonCancellable) { prior.await() }
            currentCoroutineContext().ensureActive()
            return operation()
        } finally {
            turn.complete(Unit)
        }
    }

    // endregion

    // region Mutations (internal: app code mutates only through ChatTimelineWriter)

    /** Replaces the canonical list, rebuilds the lookup, settles the phase to LOADED, bumps the id. */
    internal fun replaceAll(newMessages: List<MessageDTO>) = synchronized(lock) {
        setMessagesLocked(newMessages.snapshot(), newMessages.associateBy { it.id })
        if (renderState.phase != ChatRenderState.LoadPhase.LOADED) {
            renderStateFlowBacking.value = renderState.with(phase = ChatRenderState.LoadPhase.LOADED)
        }
        renderStateIDValue += 1UL
    }

    /** UNINITIALIZED → LOADING only; a no-op once loading or loaded. */
    internal fun beginLoading() = synchronized(lock) {
        if (renderState.phase != ChatRenderState.LoadPhase.UNINITIALIZED) return@synchronized
        renderStateFlowBacking.value = renderState.with(phase = ChatRenderState.LoadPhase.LOADING)
        renderStateIDValue += 1UL
    }

    /** Forces LOADED (error and early-return paths); idempotent. */
    internal fun markLoaded() = synchronized(lock) {
        if (renderState.phase == ChatRenderState.LoadPhase.LOADED) return@synchronized
        renderStateFlowBacking.value = renderState.with(phase = ChatRenderState.LoadPhase.LOADED)
        renderStateIDValue += 1UL
    }

    /** Inserts an older page at the head, skipping ids already present. */
    internal fun prepend(older: List<MessageDTO>) = synchronized(lock) {
        val filtered = older.filter { it.id !in messagesByIDValue }
        if (filtered.isEmpty()) return@synchronized
        setMessagesLocked((filtered + messages).snapshot(), messagesByIDValue + filtered.associateBy { it.id })
        renderStateIDValue += 1UL
    }

    /** Appends one message unless its id is already known. Returns whether it was appended. */
    internal fun append(message: MessageDTO): Boolean = synchronized(lock) {
        if (message.id in messagesByIDValue) return@synchronized false
        setMessagesLocked((messages + message).snapshot(), messagesByIDValue + (message.id to message))
        renderStateIDValue += 1UL
        true
    }

    /** Replaces the first message with [messageID] by `transform(it)`; a no-op when absent. */
    internal fun update(messageID: UUID, transform: (MessageDTO) -> MessageDTO) = synchronized(lock) {
        val index = messages.indexOfFirst { it.id == messageID }
        if (index < 0) return@synchronized
        val updated = transform(messages[index])
        setMessagesLocked(
            messages.mapIndexed { position, message -> if (position == index) updated else message }.snapshot(),
            messagesByIDValue + (messageID to updated),
        )
        renderStateIDValue += 1UL
    }

    /** Removes a message by id; a no-op when absent. */
    internal fun remove(messageID: UUID) = synchronized(lock) {
        if (messageID !in messagesByIDValue) return@synchronized
        setMessagesLocked(messages.filter { it.id != messageID }.snapshot(), messagesByIDValue - messageID)
        renderStateIDValue += 1UL
    }

    /** Reassigns the whole list after a same-sender reordering pass and rebuilds the lookup. */
    internal fun replaceMessagesPreservingByID(reordered: List<MessageDTO>) = synchronized(lock) {
        setMessagesLocked(reordered.snapshot(), reordered.associateBy { it.id })
        renderStateIDValue += 1UL
    }

    /** The single seam through which builds apply; false (no change) when [capturedID] is stale. */
    internal fun setRenderState(new: ChatRenderState, capturedID: ULong): Boolean = synchronized(lock) {
        setRenderStateLocked(new, capturedID)
    }

    /** Direct render-state transform for paths that keep messages consistent; bumps the id. */
    internal fun updateRenderState(transform: (ChatRenderState) -> ChatRenderState) = synchronized(lock) {
        renderStateFlowBacking.value = transform(renderState)
        renderStateIDValue += 1UL
    }

    /** Appends a fully built item so a new bubble shows without waiting for a full build. */
    internal fun appendRenderItem(item: MessageItem) = updateRenderState { it.appendingItem(item) }

    /** Replaces one render item; a no-op (no id bump) when absent or unchanged. */
    internal fun updateRenderItem(id: UUID, transform: (MessageItem) -> MessageItem) = synchronized(lock) {
        val newState = renderState.updatingItem(id, transform)
        if (newState == renderState) return@synchronized
        renderStateFlowBacking.value = newState
        renderStateIDValue += 1UL
    }

    /** Removes one render item; a no-op (no id bump) when absent. */
    internal fun removeRenderItem(id: UUID) = synchronized(lock) {
        val newState = renderState.removingItem(id)
        if (newState == renderState) return@synchronized
        renderStateFlowBacking.value = newState
        renderStateIDValue += 1UL
    }

    /**
     * Status-only update of the DTO and the rendered envelope/footer, without a store read. A DELIVERED
     * row never downgrades and a FAILED row never returns to PENDING unless [userInitiated] (a user retry
     * or resend); [roundTripTime] is kept when null.
     */
    internal fun applyStatusUpdate(
        messageID: UUID,
        status: MessageStatus,
        roundTripTime: UInt? = null,
        userInitiated: Boolean = false,
    ) = synchronized(lock) {
        val current = messagesByIDValue[messageID]
        if (current != null && !userInitiated) {
            if (current.status == MessageStatus.DELIVERED && status != MessageStatus.DELIVERED) return@synchronized
            if (current.status == MessageStatus.FAILED && status == MessageStatus.PENDING) return@synchronized
        }
        update(messageID) { dto -> dto.copy(status = status, roundTripTime = roundTripTime ?: dto.roundTripTime) }
        updateRenderItem(messageID) { item ->
            item.with(envelope = item.envelope.withStatus(status), footer = item.footer.with(status))
        }
    }

    private fun setMessagesLocked(list: SnapshotList<MessageDTO>, byID: Map<UUID, MessageDTO>) {
        messagesByIDValue = byID
        messagesState.value = list
    }

    private fun setRenderStateLocked(new: ChatRenderState, capturedID: ULong): Boolean {
        if (capturedID != renderStateIDValue) return false
        renderStateFlowBacking.value = new
        return true
    }

    // endregion

    // region Reload

    /**
     * Single chokepoint for ack / retry / fail / heard-repeat / reaction events: unions ids into the
     * pending set and schedules a coalesced load unless one (or a hard reset) is in flight. No event is
     * dropped because none asks whether its row is rendered.
     */
    internal fun enqueueReload(updatedMessageIDs: Set<UUID>) {
        synchronized(lock) { pendingReloadIDsValue = pendingReloadIDsValue + updatedMessageIDs }
        scheduleCoalescedReload()
    }

    /** Single-id convenience. */
    internal fun enqueueReload(messageID: UUID) = enqueueReload(setOf(messageID))

    private fun scheduleCoalescedReload() {
        val job = synchronized(lock) {
            if (reloadInFlightValue || hardResetInFlightValue) return
            reloadInFlightValue = true
            scope.launch(start = CoroutineStart.LAZY) { coalescedReload() }.also { coalescedReloadTaskValue = it }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) synchronized(lock) { if (coalescedReloadTaskValue === job) reloadInFlightValue = false }
        }
        job.start()
    }

    /**
     * Drains pending ids until empty: snapshot, clear, fetch, apply. Events landing mid-fetch are handled
     * by the next iteration; a hard reset in flight stops the drain so stale per-id writes never stomp the
     * refetched window. Clearing the in-flight flag is atomic with observing the empty buffer.
     */
    private suspend fun coalescedReload() {
        while (true) {
            val snapshot = synchronized(lock) {
                if (pendingReloadIDsValue.isEmpty() || hardResetInFlightValue) {
                    reloadInFlightValue = false
                    null
                } else {
                    pendingReloadIDsValue.also { pendingReloadIDsValue = emptySet() }
                }
            } ?: return
            applyReloadedIDs(snapshot)
        }
    }

    /**
     * Per-id fetch and in-place update. Store keys are `EntityKey(radio, id)` (Swift passes a bare id).
     * A nil fetch for an id still held in memory is an inconsistency and triggers [hardReset]; fetch
     * errors are logged and skipped. Refreshed ids go to [renderItemRebuilder] afterwards.
     */
    private suspend fun applyReloadedIDs(ids: Set<UUID>) {
        var inconsistencyDetected = false
        val refreshedIDs = ArrayList<UUID>()
        for (id in ids) {
            val fetched = try {
                dataStore.fetchMessage(EntityKey(conversationID.radioId, id))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                logger.warning("applyReloadedIDs fetch failed for $id: $error")
                continue
            }
            val missingKnownID = synchronized(lock) {
                val known = id in messagesByIDValue
                if (fetched != null && known) {
                    update(id) { fetched }
                    refreshedIDs.add(id)
                }
                fetched == null && known
            }
            if (missingKnownID) {
                inconsistencyDetected = true
                logger.warning("applyReloadedIDs: fetch returned nil for known id $id")
            }
        }
        renderItemRebuilder?.let { rebuilder -> refreshedIDs.forEach { id -> runCallback("renderItemRebuilder") { rebuilder(id) } } }
        if (inconsistencyDetected) hardReset("fetch returned nil for in-memory message")
    }

    /** Drops all state and refetches the loaded window on the window lane. */
    internal fun hardReset(reason: String) {
        logger.warning("ChatCoordinator hardReset: $reason")
        val job = synchronized(lock) {
            hardResetInFlightValue = true
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    performWindowOperation { refetchWindow() }
                } finally {
                    val active = isActive
                    val reschedule = synchronized(lock) {
                        hardResetInFlightValue = false
                        active && pendingReloadIDsValue.isNotEmpty()
                    }
                    if (reschedule) scheduleCoalescedReload()
                }
            }.also { hardResetTaskValue = it }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) synchronized(lock) { if (hardResetTaskValue === job) hardResetInFlightValue = false }
        }
        job.start()
    }

    private suspend fun refetchWindow() {
        try {
            currentCoroutineContext().ensureActive()
            val anchorSortDate = messages.minOfOrNull { it.sortDate }
            val window: MessageWindow = when (val conversation = conversationID.conversation) {
                is ConversationKey.DM -> dataStore.fetchMessageWindow(
                    EntityKey(conversationID.radioId, conversation.contactID), anchorSortDate, PAGE_SIZE.toLong(),
                )
                is ConversationKey.Channel -> dataStore.fetchMessageWindow(
                    conversationID.radioId, conversation.channelIndex, anchorSortDate, PAGE_SIZE.toLong(),
                )
            }
            hardResetAfterFetchHook?.invoke()
            currentCoroutineContext().ensureActive()
            val unfilteredCount = window.messages.size.toLong()
            replaceAll(hidingOutgoingReactions(window.messages))
            updateRenderState { it.with(hasMoreMessages = window.hasMore, totalFetchedCount = unfilteredCount) }
            renderStateInvalidated?.let { runCallback("renderStateInvalidated", it) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logger.severe("hardReset refetch failed: $error")
        }
    }

    /** Cancels in-flight maintenance jobs; called by the registry before it drops this coordinator. */
    internal fun cancelInFlight() {
        val jobs = synchronized(lock) { listOfNotNull(buildItemsTaskValue, coalescedReloadTaskValue, hardResetTaskValue) }
        jobs.forEach { it.cancel() }
    }

    private fun hidingOutgoingReactions(messages: List<MessageDTO>): List<MessageDTO> {
        val isDM = conversationID.conversation is ConversationKey.DM
        return messages.filter { !hiddenOutgoingReaction.isHiddenOutgoingReaction(it, isDM) }
    }

    // endregion

    // region Rebuild

    /**
     * Rebuilds [renderState] from inputs assembled by the view model. Bumps the id before capturing it so
     * back-to-back rebuilds never share one (last-scheduled wins), cancels the previous build, runs the
     * pure builder on [buildDispatcher], then applies on [scope] if the captured id is still current. A
     * rejected (stale) build fires [renderStateInvalidated]; a successful one runs [postApply].
     */
    internal fun rebuildItems(
        inputs: List<Pair<MessageDTO, MessageBuildInputs>>,
        envInputs: EnvInputs,
        postApply: (() -> Unit)? = null,
    ) {
        val snapshot = inputs.toList()
        val (job, previous) = synchronized(lock) {
            renderStateIDValue += 1UL
            val capturedID = renderStateIDValue
            val previous = buildItemsTaskValue
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val built = withContext(buildDispatcher) {
                    snapshot.map { (message, perMessageInputs) ->
                        ensureActive()
                        MessageFragmentBuilder.makeItem(message, perMessageInputs, envInputs)
                    }
                }
                applyRebuiltItems(built, capturedID, postApply)
            }
            buildItemsTaskValue = job
            job to previous
        }
        previous?.cancel()
        job.start()
    }

    private suspend fun applyRebuiltItems(built: List<MessageItem>, capturedID: ULong, postApply: (() -> Unit)?) {
        val self = currentCoroutineContext()[Job]
        if (self?.isActive == false) return
        val (applied, invalidated) = synchronized(lock) {
            // A build superseded by a newer rebuildItems is the Swift cancelled task: it neither applies nor
            // invalidates. Checked under the lock so a concurrent rebuild can't slip in between.
            if (buildItemsTaskValue !== self) return
            val new = renderState.with(items = built.snapshot(), itemIndexByID = built.indexByID { it.id })
            setRenderStateLocked(new, capturedID) to renderStateInvalidatedValue
        }
        if (!applied) {
            invalidated?.let { runCallback("renderStateInvalidated", it) }
            return
        }
        postApply?.let { runCallback("postApply", it) }
    }

    // endregion

    // region Test seams (Swift `#if DEBUG`)

    /** Fixture seam: seeds the timeline without minting a writer (keeps the bound hooks and capability). */
    fun replaceAllForTesting(newMessages: List<MessageDTO>) = replaceAll(newMessages)

    /** Fixture seam; see [replaceAllForTesting]. */
    fun markLoadedForTesting() = markLoaded()

    // endregion

    companion object {
        /** Messages per pagination page; also the hard-reset refetch floor. */
        const val PAGE_SIZE: Int = 50

        /** Read messages loaded above the first unread so the "New Messages" divider has context. */
        const val DIVIDER_READ_CONTEXT: Int = 12

        /** Ceiling on the first-page fetch. */
        const val MAX_INITIAL_PAGE_SIZE: Int = 200

        /** At least [PAGE_SIZE], enough unread plus read context when that fits, else [MAX_INITIAL_PAGE_SIZE]. */
        fun initialPageSize(unreadCount: Int): Int =
            minOf(maxOf(PAGE_SIZE, unreadCount + DIVIDER_READ_CONTEXT), MAX_INITIAL_PAGE_SIZE)
    }
}
