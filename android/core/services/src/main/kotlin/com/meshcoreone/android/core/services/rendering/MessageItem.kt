// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/MessageItem.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.model.MessageEnvelope
import com.meshcoreone.android.core.model.SnapshotList
import java.util.UUID

/** The concrete envelope WP-213 supplies for the generic core-model [MessageEnvelope]. */
typealias MessageItemEnvelope = MessageEnvelope<NodeNameResolution, IncomingAvatarIdentity>

/**
 * One row in the chat timeline as the bubble sees it.
 *
 * Equality invariant: bubbles skip re-rendering when the item is equal, which is safe only because every
 * render-affecting input (preview state, reactions, inline images, footer status, grouping flags) is
 * encoded here. Moving any of those out of [MessageItem] would silently produce stale renders.
 */
data class MessageItem(
    val id: UUID,
    val envelope: MessageItemEnvelope,
    val content: SnapshotList<MessageFragment>,
    val footer: MessageFooter,
    val grouping: GroupingFlags,
    val shouldRequestPreviewFetch: Boolean,
) {
    /**
     * Message-scoped identity for the bubble's preview-fetch task: the message id while a fetch is
     * wanted, null otherwise. A bare `true` flag would not produce an edge when a reused cell moves
     * between two fetch-wanting messages.
     */
    val previewFetchTaskID: UUID? get() = if (shouldRequestPreviewFetch) id else null

    /** The first text fragment's Translation chrome (null when that fragment carries none). */
    val translation: MessageTranslationChrome?
        get() = content.firstOrNull { it is MessageFragment.Text }?.let { (it as MessageFragment.Text).payload.translation }

    /** Returns a new item with the supplied envelope, footer and/or grouping overridden. */
    fun with(
        envelope: MessageItemEnvelope? = null,
        footer: MessageFooter? = null,
        grouping: GroupingFlags? = null,
    ): MessageItem = copy(
        envelope = envelope ?: this.envelope,
        footer = footer ?: this.footer,
        grouping = grouping ?: this.grouping,
    )
}
