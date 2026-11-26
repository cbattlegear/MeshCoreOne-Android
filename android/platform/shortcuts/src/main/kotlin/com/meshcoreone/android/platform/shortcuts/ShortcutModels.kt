// PortedFrom: MC1/Intents/AdvertReach.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Intents/MessageRecipient.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Intents/SendRoute.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Intents/SendOutcome.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Intents/IntentError.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.platform.shortcuts

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.config.MeshCoreException

/** Raw values are the stable identifiers persisted in shortcut intents. */
enum class AdvertReach(val rawValue: String) {
    ZERO_HOP("zeroHop"),
    FLOOD("flood");

    val sendsFlood: Boolean get() = this == FLOOD

    companion object {
        fun fromRawValue(rawValue: String?): AdvertReach? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

sealed interface ShortcutRecipient {
    val radioId: RadioId
    data class Contact(val dto: ContactDTO) : ShortcutRecipient { override val radioId get() = dto.radioId }
    data class Channel(val dto: ChannelDTO) : ShortcutRecipient { override val radioId get() = dto.radioId }
}

enum class SendRoute { HEADLESS_QUEUE, QUEUE_AFTER_SYNC, FOREGROUND_ESCALATE, NOT_CONNECTED }

enum class SendOutcome { QUEUED, MUST_FOREGROUND }

/** Presentation categories retain the session payload rather than the wrapper's diagnostic message. */
sealed class ShortcutError(val name: String) {
    data object NOT_CONNECTED : ShortcutError("NOT_CONNECTED")
    data object INVALID_RECIPIENT : ShortcutError("INVALID_RECIPIENT")
    data object MESSAGE_TOO_LONG : ShortcutError("MESSAGE_TOO_LONG")
    data object SEND_FAILED : ShortcutError("SEND_FAILED")
    data object ADVERT_FAILED : ShortcutError("ADVERT_FAILED")
    data object DENIED : ShortcutError("DENIED")
    data class SessionError(val underlying: MeshCoreException) : ShortcutError("SESSION_ERROR")
}

class ShortcutException(
    val error: ShortcutError,
    cause: Throwable? = (error as? ShortcutError.SessionError)?.underlying,
) : Exception(error.name, cause)
