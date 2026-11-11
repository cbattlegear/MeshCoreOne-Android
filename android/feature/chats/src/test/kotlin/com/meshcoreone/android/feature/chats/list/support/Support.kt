// AndroidOnly: WP-306 JVM test support: original-case binding, fixtures and a deterministic scenario runner.
package com.meshcoreone.android.feature.chats.list.support

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.list.ChatListStrings
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * Original-case binding carried in source (same mechanism as core:data, core:connectivity and
 * feature:nodes): the JUnit XML names the method; this binds it to the frozen Swift case id.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
internal annotation class OriginalCase(val value: String, val disposition: String = "source-behavior")

/** Deterministic strings so subtitle/title assertions do not depend on Android resources. */
internal object TestStrings : ChatListStrings {
    override fun channelDefaultName(index: Int) = "Channel $index"
    override fun floodRouting() = "Flood routing"
    override fun directHops(hops: Long) = "Direct • $hops hops"
    override fun headerRegion(region: String) = "Region: $region"
    override fun scopedDefault(region: String) = "$region (default)"
}

internal class Scenario(val scope: CoroutineScope)

/** Runs [block] with an eager (Unconfined) scope: fake suspend calls complete synchronously. */
internal fun scenario(block: suspend Scenario.() -> Unit) = runBlocking {
    val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    try {
        Scenario(scope).block()
    } finally {
        scope.cancel()
    }
}

internal suspend fun settle() = repeat(5) { yield() }

internal object Fixtures {
    val radio = RadioId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    val epoch: Instant = Instant.ofEpochSecond(1_704_067_200)

    fun contact(
        name: String = "Test",
        id: UUID = UUID.randomUUID(),
        radioId: RadioId = radio,
        nickname: String? = null,
        outPathLength: UByte = 0u,
        isFavorite: Boolean = false,
        isMuted: Boolean = false,
        isBlocked: Boolean = false,
        unreadCount: Long = 0,
        lastMessageDate: Instant? = null,
        typeRawValue: UByte = 1u,
        publicKey: Bytes = Bytes(ByteArray(32)),
        lastAdvertTimestamp: UInt = 0u,
        lastModified: UInt = 0u,
        lastHeardTimestamp: UInt? = null,
    ) = ContactDTO(
        id = id, radioId = radioId, publicKey = publicKey, name = name, typeRawValue = typeRawValue,
        outPathLength = outPathLength, lastAdvertTimestamp = lastAdvertTimestamp, lastModified = lastModified,
        lastHeardTimestamp = lastHeardTimestamp, nickname = nickname, isBlocked = isBlocked, isMuted = isMuted,
        isFavorite = isFavorite, lastMessageDate = lastMessageDate, unreadCount = unreadCount,
    )

    fun channel(
        name: String = "General",
        id: UUID = UUID.randomUUID(),
        radioId: RadioId = radio,
        index: UByte = 1u,
        unreadCount: Long = 0,
        notificationLevel: NotificationLevel = NotificationLevel.ALL,
        isFavorite: Boolean = false,
        floodScope: ChannelFloodScope = ChannelFloodScope.Inherit,
        lastMessageDate: Instant? = null,
        secret: Bytes = Bytes(ByteArray(16)),
    ) = ChannelDTO(
        id = id, radioId = radioId, index = index, name = name, secret = secret, unreadCount = unreadCount,
        notificationLevel = notificationLevel, isFavorite = isFavorite, lastMessageDate = lastMessageDate,
        displayIncarnationID = UUID.randomUUID(),
    ).withFloodScope(floodScope)

    fun room(
        name: String = "Room",
        id: UUID = UUID.randomUUID(),
        unreadCount: Long = 0,
        notificationLevel: NotificationLevel = NotificationLevel.ALL,
        isFavorite: Boolean = false,
        isConnected: Boolean = true,
    ) = RemoteNodeSessionDTO(
        id = id, radioId = radio, publicKey = Bytes(ByteArray(32)), name = name, role = RemoteNodeRole.ROOM_SERVER,
        isConnected = isConnected, unreadCount = unreadCount, notificationLevel = notificationLevel, isFavorite = isFavorite,
    )
}
