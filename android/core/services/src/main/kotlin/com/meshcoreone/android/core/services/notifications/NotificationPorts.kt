// AndroidOnly: WP-215 Narrow ports for notification collaborators owned by unmerged WPs 208/210/214 and the app layer.
package com.meshcoreone.android.core.services.notifications

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.UnreadCounts
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import java.time.Instant
import kotlin.time.Duration
import kotlinx.coroutines.delay

/*
 * One port per Swift collaborator, listing exactly the members the notification code calls. Signatures
 * mirror the collaborator's Kotlin port on its branch so WP-303's adapters are one-line forwards.
 * Members may throw even where Swift cannot; the notification code contains such throws.
 */

/**
 * `MessageService` (WP-208). Future implementer: `messaging.MessageService` (its `MessagingSendPort`
 * members of the same names; the channel receipt is discarded as Swift does with `_ =`).
 */
interface NotificationMessageSending {
    suspend fun sendDirectMessage(text: String, contact: ContactDTO): MessageDTO
    suspend fun sendChannelMessage(text: String, channelIndex: UByte, radioId: RadioId)
    suspend fun sendChannelNotificationReply(
        text: String, channel: com.meshcoreone.android.core.model.ChannelDTO, selected: MessageDTO,
    ) {
        throw UnsupportedOperationException("Scoped channel notification replies are not wired")
    }
}

/** `RoomServerService.markAsRead(sessionID:)` (WP-210). Future implementer: `remote.RoomServerService.markAsRead(session)`. */
fun interface NotificationRoomReading {
    suspend fun markAsRead(session: EntityKey)
}

/** `SyncCoordinator.notifyConversationsChanged()` (WP-214). Future implementer: `sync.SyncCoordinator`. */
fun interface NotificationConversationsNotifying {
    fun notifyConversationsChanged()
}

/**
 * The app-layer `getBadgeCount` closure installed by Swift `AppState.wireServicesIfConnected`
 * (`dataStore.getTotalUnreadCounts(radioID:)`, zeros when no radio). Wired by WP-303.
 */
fun interface NotificationUnreadCounting {
    suspend fun unreadCounts(): UnreadCounts
}

/** Wall clock and sleeping for the badge debounce and time-stamped identifiers (Swift `Date()`/`Task.sleep`). */
interface NotificationClock {
    fun now(): Instant
    suspend fun sleep(duration: Duration)
}

/** Production clock. */
object SystemNotificationClock : NotificationClock {
    override fun now(): Instant = Instant.now()
    override suspend fun sleep(duration: Duration) = delay(duration)
}
