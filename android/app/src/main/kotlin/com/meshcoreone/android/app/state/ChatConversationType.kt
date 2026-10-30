// AndroidOnly: WP-303 Conversation identity and the timeline port the primer drives; ChatTimeline itself is WP-307.
package com.meshcoreone.android.app.state

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.services.reactions.ReactionService
import com.meshcoreone.android.core.services.rendering.ChatCoordinator
import com.meshcoreone.android.core.services.rendering.EnvInputs

/** The two conversation kinds that own a [ChatCoordinator] (rooms have none). */
sealed interface ChatConversationType {
    val coordinatorId: ChatConversationID

    data class Dm(val contact: ContactDTO) : ChatConversationType {
        override val coordinatorId: ChatConversationID get() = ChatConversationID.dm(contact.radioId, contact.id)
    }

    data class Channel(val channel: ChannelDTO) : ChatConversationType {
        override val coordinatorId: ChatConversationID get() = ChatConversationID.channel(channel.radioId, channel.index)
    }
}

/** Contacts resolved before a channel bake so sender resolution sees the table (nickname/avatar tables are WP-307). */
data class ChatSenderTables(val contacts: SnapshotList<ContactDTO>) {
    companion object { val EMPTY = ChatSenderTables(com.meshcoreone.android.core.model.SnapshotList.empty()) }
}

/** Reaction indexing inputs: the service plus the conversation scope the index is built for. */
data class ReactionIndexing(val service: ReactionService, val scope: ReactionIndexScope)

sealed interface ReactionIndexScope {
    data class Direct(val contact: ContactDTO) : ReactionIndexScope
    /** [localNodeName] is the live device's node name, never a fallback; null skips outgoing-message indexing. */
    data class Channel(val channel: ChannelDTO, val localNodeName: String?) : ReactionIndexScope
}

enum class PopulateMode { REPLACE }

sealed interface TimelineOpenOutcome {
    data object Loaded : TimelineOpenOutcome
    data object Cancelled : TimelineOpenOutcome
    data object Unavailable : TimelineOpenOutcome
    data class Failed(val error: Throwable) : TimelineOpenOutcome
}

/**
 * The prime-role `ChatTimeline` the primer drives (WP-307 supplies the production implementation).
 * [bind] claims a PRIME writer on the coordinator and returns false when an interactive owner holds it.
 */
interface PrimeTimeline : AutoCloseable {
    var envInputs: EnvInputs
    val messages: SnapshotList<MessageDTO>
    val conversation: ChatConversationType?
    fun bind(coordinator: ChatCoordinator, senderTables: () -> ChatSenderTables): Boolean
    suspend fun open(
        conversation: ChatConversationType,
        reactions: ReactionIndexing?,
        populateMode: PopulateMode,
    ): TimelineOpenOutcome
    override fun close() = Unit
}

/** Warms link-preview metadata and inline-image dimensions for primed rows (`InlineImagePrefetcher`). */
interface InlinePreviewPrefetching {
    fun containsUrls(text: String): Boolean
    suspend fun prefetch(text: String, isChannelMessage: Boolean, allowImageProbes: Boolean)
}

/** Scope preferences read by the preview prewarm (`LinkPreviewPreferences`). */
fun interface LinkPreviewPreferences {
    fun shouldAutoResolve(isChannelMessage: Boolean): Boolean
}
