// PortedFrom: MC1/State/ChatTimelinePrimer.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.state

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.services.reactions.ReactionService
import com.meshcoreone.android.core.services.rendering.ChatCoordinatorRegistry
import com.meshcoreone.android.core.services.rendering.EnvInputs
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException

/**
 * Speculative warm path for a closed conversation's shared coordinator. Drives a PRIME-role timeline: claims
 * the writer, loads contacts before a channel bake, populates the first page and warms preview metadata for
 * the primed tail. Discarded when the prime completes. A prime runs no legacy preview decode and logs populate
 * failures, having no error surface of its own.
 */
class ChatTimelinePrimer(
    private val dependencies: Dependencies,
    private val timeline: PrimeTimeline,
    private val prefetcher: InlinePreviewPrefetching? = null,
    private val linkPreviewPreferences: LinkPreviewPreferences = LinkPreviewPreferences { false },
) {
    /** Provider bundle for the prime path; every provider resolves at call time so it survives reconnects. */
    class Dependencies(
        val registry: () -> ChatCoordinatorRegistry?,
        val dataStore: () -> PersistenceStoreProtocol?,
        val reactionService: (com.meshcoreone.android.core.model.RadioId) -> ReactionService?,
        /** Live connected device's node name, never a fallback; null skips outgoing channel indexing. */
        val connectedDeviceNodeName: (com.meshcoreone.android.core.model.RadioId) -> String?,
    )

    private val logger: Logger = Logger.getLogger("com.mc1.ChatTimelinePrimer")

    @Volatile private var senderTables: ChatSenderTables = ChatSenderTables.EMPTY

    /** Primes [conversation] into its registry coordinator; no-ops when a live interactive owner holds the writer. */
    suspend fun prime(conversation: ChatConversationType, envInputs: EnvInputs) {
        timeline.envInputs = envInputs
        val registry = dependencies.registry() ?: return
        val coordinator = registry.coordinator(conversation.coordinatorId)
        // The writer's rebake hooks capture the timeline weakly in Swift; a revoked writer simply no-ops here.
        val bound = timeline.bind(coordinator) { senderTables }
        if (!bound) return

        try {
            primeBound(conversation)
        } finally {
            timeline.close()
        }
    }

    private suspend fun primeBound(conversation: ChatConversationType) {
        senderTables = when (conversation) {
            // DM bubbles never show the sender row.
            is ChatConversationType.Dm -> ChatSenderTables.EMPTY
            // Contacts before bake so sender resolution sees the table.
            is ChatConversationType.Channel -> fetchSenderTables(conversation.channel.radioId)
        }

        val reactions = dependencies.reactionService(conversation.coordinatorId.radioId)?.let { service ->
            ReactionIndexing(
                service,
                when (conversation) {
                    is ChatConversationType.Dm -> ReactionIndexScope.Direct(conversation.contact)
                    is ChatConversationType.Channel ->
                        ReactionIndexScope.Channel(conversation.channel,
                            dependencies.connectedDeviceNodeName(conversation.coordinatorId.radioId))
                },
            )
        }

        when (val outcome = timeline.open(conversation, reactions, PopulateMode.REPLACE)) {
            TimelineOpenOutcome.Loaded -> prewarmRecentPreviews()
            TimelineOpenOutcome.Cancelled, TimelineOpenOutcome.Unavailable -> Unit
            is TimelineOpenOutcome.Failed ->
                logger.log(Level.SEVERE, "Prime failed for ${conversation.coordinatorId}: ${outcome.error.message}")
        }
    }

    /**
     * Swift returns empty tables when the prime is cancelled and lets the following open report `.cancelled`;
     * Kotlin never swallows its own cancellation, so a cancelled prime ends here with no further writes.
     */
    private suspend fun fetchSenderTables(radioId: com.meshcoreone.android.core.model.RadioId): ChatSenderTables {
        val store = dependencies.dataStore() ?: return ChatSenderTables.EMPTY
        return try {
            ChatSenderTables(store.fetchContacts(radioId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.log(Level.WARNING, "Failed to load contacts for channel prime: ${failure.message}")
            ChatSenderTables.EMPTY
        }
    }

    /** Warms preview metadata for the newest rows; a no-op without a prefetcher or with previews off. */
    private suspend fun prewarmRecentPreviews() {
        val warmer = prefetcher ?: return
        if (!timeline.envInputs.previewsEnabled) return
        val messages = timeline.messages
        if (messages.isEmpty()) return
        val isChannel = timeline.conversation is ChatConversationType.Channel
        val allowImageProbes = linkPreviewPreferences.shouldAutoResolve(isChannel)
        for (message in messages.takeLast(PREVIEW_WARM_TAIL_LIMIT)) {
            if (warmer.containsUrls(message.text)) warmer.prefetch(message.text, isChannel, allowImageProbes)
        }
    }

    companion object {
        /** Newest rows whose link/image URLs are warmed after a successful populate. */
        const val PREVIEW_WARM_TAIL_LIMIT = 10
    }
}
