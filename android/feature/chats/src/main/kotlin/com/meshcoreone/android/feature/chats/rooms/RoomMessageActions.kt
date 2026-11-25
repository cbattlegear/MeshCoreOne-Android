// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomMessageActionAvailability.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomMessageAction.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.rooms

import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import com.meshcoreone.android.feature.chats.actions.MessageAction

data class RoomMessageActionAvailability(
    val canReply: Boolean,
    val canSendDirectMessage: Boolean,
    val canSendAgain: Boolean,
) {
    val actions: List<MessageAction> get() = buildList {
        if (canReply) add(MessageAction.Reply)
        if (canSendDirectMessage) add(MessageAction.SendDirectMessage)
        add(MessageAction.Copy)
        if (canSendAgain) add(MessageAction.SendAgain)
    }

    companion object {
        fun forMessage(message: RoomMessageDTO, session: RemoteNodeSessionDTO) =
            RoomMessageActionAvailability(
                canReply = !message.isFromSelf && session.canPost,
                canSendDirectMessage = !message.isFromSelf && message.authorName != null,
                canSendAgain = message.isFromSelf,
            )
    }
}
