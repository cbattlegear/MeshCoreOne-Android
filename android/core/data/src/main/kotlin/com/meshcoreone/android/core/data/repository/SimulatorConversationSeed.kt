// AndroidOnly: WP-202 Atomic simulator fixtures without replaying over a persisted conversation.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageRepeatDTO
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.SnapshotList

sealed interface SimulatorConversationParent {
    data class Direct(val contact: ContactDTO) : SimulatorConversationParent
    data class Channel(val channel: ChannelDTO) : SimulatorConversationParent
}

/** The caller supplies one complete conversation, including message-owned preview and summary fields. */
data class SimulatorConversationSeed(
    val parent: SimulatorConversationParent,
    val messages: SnapshotList<MessageDTO>,
    val reactions: SnapshotList<ReactionDTO>,
    val repeats: SnapshotList<MessageRepeatDTO>,
)

sealed interface SimulatorConversationSeedResult {
    data class Created(
        val parent: SimulatorConversationParent,
        val messages: SnapshotList<MessageDTO>,
    ) : SimulatorConversationSeedResult

    /** Existing contents, including missing fixtures, are preserved; this is not a completeness result. */
    data class PreservedExisting(val parent: SimulatorConversationParent) : SimulatorConversationSeedResult
}
