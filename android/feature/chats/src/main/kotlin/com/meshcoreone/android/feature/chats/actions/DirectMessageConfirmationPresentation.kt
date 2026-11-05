// AndroidOnly: WP-309 A public FAILED value is not a physical no-send certificate for direct messages.
package com.meshcoreone.android.feature.chats.actions

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus

/** Conservative wording only; does not change persisted status, retry availability or send policy. */
fun requiresUnconfirmedDirectFailureLabel(message: MessageDTO): Boolean =
    message.direction == MessageDirection.OUTGOING &&
        (!message.isChannelMessage || message.contactID != null) && message.status == MessageStatus.FAILED
