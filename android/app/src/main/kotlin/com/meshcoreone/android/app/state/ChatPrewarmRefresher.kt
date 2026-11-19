// PortedFrom: MC1/State/ChatPrewarmRefresher.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.state

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.runtime.DeadlineClock
import com.meshcoreone.android.core.runtime.SystemRuntimeClock
import com.meshcoreone.android.core.services.rendering.ChatCoordinatorRegistry
import com.meshcoreone.android.core.services.rendering.ChatRenderState
import com.meshcoreone.android.core.services.rendering.EnvInputs
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Re-primes warm chat coordinators when a message arrives for a conversation that is not currently open, so a
 * reopen's first frame is already complete and bottom-anchored. Owned by app state and fed by the message event
 * dispatcher. Rooms have no coordinator and need no refresh.
 */
class ChatPrewarmRefresher(
    private val hooks: Hooks,
    private val scope: CoroutineScope,
    private val clock: DeadlineClock = SystemRuntimeClock(),
    private val debounce: Duration = DEFAULT_DEBOUNCE,
) {
    /** Identifies the conversation an event belongs to; the full conversation resolves lazily after the debounce. */
    sealed interface ConversationKind {
        val coordinatorId: ChatConversationID

        data class Dm(val contact: ContactDTO) : ConversationKind {
            override val coordinatorId: ChatConversationID get() = ChatConversationID.dm(contact.radioId, contact.id)
        }

        data class Channel(val radioId: RadioId, val channelIndex: UByte) : ConversationKind {
            override val coordinatorId: ChatConversationID get() = ChatConversationID.channel(radioId, channelIndex)
        }
    }

    class Hooks(
        /** Registry holding the warm coordinators; null while no store is available. */
        val registry: () -> ChatCoordinatorRegistry?,
        /** Dependency bundle for the priming path; null when the owner is gone. */
        val dependencies: () -> ChatTimelinePrimer.Dependencies?,
        /** Environment snapshot to bake with; null when no chat UI has rendered yet. */
        val envInputs: (ChatConversationType) -> EnvInputs?,
        /** Whether the conversation is open: the open view model appends in place and must not be raced. */
        val isConversationActive: (ConversationKind) -> Boolean,
        val channel: suspend (RadioId, UByte) -> ChannelDTO?,
        /** Re-fetched at refresh time so the bake reads the post-increment unread count. */
        val contact: suspend (RadioId, UUID) -> ContactDTO?,
        /** Builds the primer (timeline, preview warmer) for one refresh; null skips the refresh. */
        val makePrimer: (ChatTimelinePrimer.Dependencies) -> ChatTimelinePrimer?,
    )

    private val lock = Any()
    private val scheduled = mutableMapOf<ChatConversationID, Job>()

    /** One scheduled refresh per conversation; an arrival during the window rides the one already scheduled. */
    val inFlight: Set<ChatConversationID> get() = synchronized(lock) { scheduled.keys.toSet() }
    val inFlightCount: Int get() = synchronized(lock) { scheduled.size }

    /** The scheduled refresh for [id], if any (Swift `inFlight[id]`). */
    fun inFlightJob(id: ChatConversationID): Job? = synchronized(lock) { scheduled[id] }

    fun noteDirectMessage(contact: ContactDTO) = schedule(ConversationKind.Dm(contact))

    fun noteChannelMessage(radioId: RadioId, channelIndex: UByte) = schedule(ConversationKind.Channel(radioId, channelIndex))

    private fun isWarm(registry: ChatCoordinatorRegistry, id: ChatConversationID): Boolean =
        registry.existingCoordinator(id)?.renderState?.phase == ChatRenderState.LoadPhase.LOADED

    private fun schedule(kind: ConversationKind) {
        val id = kind.coordinatorId
        if (synchronized(lock) { id in scheduled }) return
        // Hooks run outside the lock: they read other components' state and must never nest under ours.
        if (hooks.isConversationActive(kind)) return
        val registry = hooks.registry() ?: return
        if (!isWarm(registry, id)) return
        synchronized(lock) {
            if (id in scheduled) return
            // LAZY so the map entry exists before the body can finish and remove it.
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    clock.sleep(debounce)
                    refresh(kind, id)
                } finally {
                    synchronized(lock) { scheduled.remove(id) }
                }
            }
            scheduled[id] = job
            job.start()
        }
    }

    /** Cancels every scheduled refresh (process shutdown or disconnect teardown). */
    fun cancelAll() {
        val jobs = synchronized(lock) { scheduled.values.toList().also { scheduled.clear() } }
        jobs.forEach(Job::cancel)
    }

    private suspend fun refresh(kind: ConversationKind, id: ChatConversationID) {
        // Re-validate after the debounce: the user may have opened the chat, the registry may have torn down on
        // disconnect, or the LRU may have evicted the entry. A cold entry needs no refresh.
        if (hooks.isConversationActive(kind)) return
        val registry = hooks.registry() ?: return
        if (!isWarm(registry, id)) return

        val conversation: ChatConversationType? = when (kind) {
            is ConversationKind.Dm ->
                (hooks.contact(kind.contact.radioId, kind.contact.id) ?: kind.contact).let(ChatConversationType::Dm)
            is ConversationKind.Channel -> hooks.channel(kind.radioId, kind.channelIndex)?.let(ChatConversationType::Channel)
        }
        val resolved = conversation ?: return
        val dependencies = hooks.dependencies() ?: return
        val envInputs = hooks.envInputs(resolved) ?: return
        // A PRIME bind is denied while an interactive owner is active; a mid-refresh open revokes the writer.
        val primer = hooks.makePrimer(dependencies) ?: return
        primer.prime(resolved, envInputs)
    }

    companion object {
        /** Coalescing window: a sync catch-up delivers a burst for one conversation; one refresh covers them all. */
        val DEFAULT_DEBOUNCE: Duration = 250.milliseconds
    }
}
