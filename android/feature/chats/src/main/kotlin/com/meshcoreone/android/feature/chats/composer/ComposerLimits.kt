// PortedFrom: MC1/Views/Chats/Components/ChatInputBar.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/ChatConversationInputBar.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.model.ProtocolLimits

/** UTF-8 byte budget and counter rules of the input bar. */
object ComposerLimits {
    /** The counter shows once the draft is within this many bytes of the limit. */
    const val COUNTER_THRESHOLD_BYTES = 20

    /** Re-enable delay after a send (iOS `Task.sleep(for: .seconds(1))`). */
    const val SEND_COOLDOWN_MILLIS = 1_000L

    /** Rows shown by the mention overlay (iOS `maxSuggestions`). */
    const val MAX_MENTION_SUGGESTIONS = 20

    /** Direct messages: `ProtocolLimits.maxDirectMessageLength`; channels: total minus the `name: ` prefix. */
    fun maxBytes(target: ComposerTarget, nodeNameByteCount: Int): Int = when (target) {
        is ComposerTarget.Direct -> ProtocolLimits.MAX_DIRECT_MESSAGE_LENGTH
        is ComposerTarget.Channel -> ProtocolLimits.maxChannelMessageLength(nodeNameByteCount.toLong()).toInt()
    }

    fun byteCount(text: String): Int = ComposerText.utf8Length(text)

    fun isOverLimit(byteCount: Int, maxBytes: Int): Boolean = byteCount > maxBytes

    fun shouldShowCounter(byteCount: Int, maxBytes: Int): Boolean = byteCount >= maxBytes - COUNTER_THRESHOLD_BYTES
}
