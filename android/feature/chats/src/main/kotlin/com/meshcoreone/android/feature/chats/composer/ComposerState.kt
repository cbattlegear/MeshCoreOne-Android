// AndroidOnly: WP-308 Immutable composer UI state (plain state holder, no ViewModel dependency on the locked classpath).
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.model.ContactDTO

/** Why the last send did not complete; the UI maps each to a localized message. */
enum class ComposerSendFailure { NOT_CONNECTED, MESSAGE_TOO_LONG, RECIPIENT_NOT_FOUND, OTHER }

sealed interface ComposerSendPhase {
    data object Idle : ComposerSendPhase
    data object Sending : ComposerSendPhase

    /** [messageSaved] means creation succeeded, not that the retained row is failed or definitely unsent. */
    data class Failed(val reason: ComposerSendFailure, val messageSaved: Boolean) : ComposerSendPhase
}

/** The accessibility hint of the send button, in the iOS precedence order. */
enum class SendHint { OVER_LIMIT, REQUIRES_CONNECTION, TAP_TO_SEND, TYPE_FIRST }

data class ComposerUiState(
    val draft: String = "",
    val maxBytes: Int = 0,
    val isEncrypted: Boolean = true,
    val isConnected: Boolean = false,
    val isCoolingDown: Boolean = false,
    val sendPhase: ComposerSendPhase = ComposerSendPhase.Idle,
    val mentionQuery: String? = null,
    val mentionSuggestions: List<ContactDTO> = emptyList(),
    /** Bumped to request IME focus after an insert; the UI keys a LaunchedEffect on it. */
    val focusRequest: Int = 0,
) {
    val byteCount: Int get() = ComposerLimits.byteCount(draft)
    val isOverLimit: Boolean get() = ComposerLimits.isOverLimit(byteCount, maxBytes)
    val showCounter: Boolean get() = ComposerLimits.shouldShowCounter(byteCount, maxBytes)
    val bytesOverLimit: Int get() = (byteCount - maxBytes).coerceAtLeast(0)
    val isSending: Boolean get() = sendPhase is ComposerSendPhase.Sending

    val canSend: Boolean
        get() = !isCoolingDown && !isSending && isConnected && !ComposerText.isBlank(draft) && !isOverLimit

    val sendHint: SendHint
        get() = when {
            isOverLimit -> SendHint.OVER_LIMIT
            !isConnected -> SendHint.REQUIRES_CONNECTION
            canSend -> SendHint.TAP_TO_SEND
            else -> SendHint.TYPE_FIRST
        }
}
