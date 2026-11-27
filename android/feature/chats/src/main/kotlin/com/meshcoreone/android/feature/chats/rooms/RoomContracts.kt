// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomConversationView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomConversationViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Room/RoomAuthenticationSheet.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.rooms

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

fun interface RoomSessionKeepAlive {
    fun acquire(session: RemoteNodeSessionDTO): AutoCloseable?
}

interface RoomConversationDataSource {
    suspend fun fetchMessages(session: RemoteNodeSessionDTO): List<RoomMessageDTO>
    suspend fun refreshSession(session: RemoteNodeSessionDTO): RemoteNodeSessionDTO?
    suspend fun contactFor(session: RemoteNodeSessionDTO): ContactDTO?
    suspend fun markAsRead(session: RemoteNodeSessionDTO)
    suspend fun markFailedSendsSeen(session: RemoteNodeSessionDTO)
}

/** What a room login is waiting for, so the sheet can say more than "busy". */
sealed interface RoomLoginProgress {
    /** The radio accepted the login; the node has up to [timeoutSeconds] to answer. */
    data class AwaitingReply(val timeoutSeconds: Int) : RoomLoginProgress

    /** The known route produced no reply; the login is being repeated over flood routing. */
    data object RetryingViaFlood : RoomLoginProgress

    /** The node accepted the password and the room's history is being synced. */
    data object SyncingHistory : RoomLoginProgress
}

interface RoomConversationService {
    suspend fun join(contact: ContactDTO, password: String?, rememberPassword: Boolean): RemoteNodeSessionDTO

    /** [join] that reports [RoomLoginProgress]; implementations without progress fall back to the plain join. */
    suspend fun join(
        contact: ContactDTO,
        password: String?,
        rememberPassword: Boolean,
        onProgress: (RoomLoginProgress) -> Unit,
    ): RemoteNodeSessionDTO = join(contact, password, rememberPassword)

    /** The remembered password for [contact], to prefill the sheet; null when none is stored or it cannot be read. */
    suspend fun savedPassword(contact: ContactDTO): String? = null

    /** Forgets the remembered password after a login that unchecked "Remember Password". */
    suspend fun forgetPassword(contact: ContactDTO) = Unit
    suspend fun reconnect(session: RemoteNodeSessionDTO): RemoteNodeSessionDTO
    suspend fun post(session: RemoteNodeSessionDTO, text: String): RoomMessageDTO
    suspend fun retry(session: RemoteNodeSessionDTO, messageId: UUID): RoomMessageDTO
}

sealed interface RoomConversationEvent {
    val sessionId: UUID

    data class MessageReceived(
        override val sessionId: UUID,
        val message: RoomMessageDTO,
    ) : RoomConversationEvent

    data class MessageChanged(
        override val sessionId: UUID,
        val messageId: UUID,
    ) : RoomConversationEvent

    data class MessageFailed(
        override val sessionId: UUID,
        val messageId: UUID,
    ) : RoomConversationEvent

    data class ConnectionRecovered(
        override val sessionId: UUID,
        val session: RemoteNodeSessionDTO,
    ) : RoomConversationEvent
}

fun interface RoomConversationDiagnostics {
    fun report(operation: String, failure: Throwable)
}

interface RoomConversationDependencies {
    val data: RoomConversationDataSource
    val service: RoomConversationService
    val events: Flow<RoomConversationEvent>
    val keepAlive: Flow<RoomSessionKeepAlive?> get() = flowOf(null)
    val diagnostics: RoomConversationDiagnostics get() = RoomConversationDiagnostics { _, _ -> }
    suspend fun removeDeliveredNotifications(session: RemoteNodeSessionDTO) = Unit
    suspend fun updateBadgeCount() = Unit
    fun conversationsChanged() = Unit
}
