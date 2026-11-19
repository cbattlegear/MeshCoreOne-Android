// PortedFrom: MC1Services/Sources/MC1Services/Models/Channel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/ChannelFloodScope.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.time.Instant
import java.util.UUID

sealed interface ChannelFloodScope {
    data object Inherit : ChannelFloodScope
    data object AllRegions : ChannelFloodScope
    data class Region(val name: String) : ChannelFloodScope
}

data class ChannelFloodScopeStorage(val modeRawValue: String, val regionName: String?) {
    val scope: ChannelFloodScope
        get() = when (modeRawValue) {
            "allRegions" -> ChannelFloodScope.AllRegions
            "specific" -> if (regionName.isNullOrEmpty()) ChannelFloodScope.Inherit else ChannelFloodScope.Region(regionName)
            else -> ChannelFloodScope.Inherit
        }

    companion object {
        fun decompose(scope: ChannelFloodScope): ChannelFloodScopeStorage = when (scope) {
            ChannelFloodScope.Inherit -> ChannelFloodScopeStorage("inherit", null)
            ChannelFloodScope.AllRegions -> ChannelFloodScopeStorage("allRegions", null)
            is ChannelFloodScope.Region -> ChannelFloodScopeStorage("specific", scope.name)
        }
        fun legacy(modeRawValue: String?, regionName: String?): ChannelFloodScopeStorage =
            ChannelFloodScopeStorage(modeRawValue ?: if (regionName != null) "specific" else "inherit", regionName)
    }
}

data class ChannelDTO(
    val id: UUID = UUID.randomUUID(),
    val radioId: RadioId,
    val index: UByte,
    val name: String,
    val secret: Bytes = Bytes(ByteArray(16)),
    val isEnabled: Boolean = true,
    val lastMessageDate: Instant? = null,
    val unreadCount: Long = 0,
    val unreadMentionCount: Long = 0,
    val notificationLevel: NotificationLevel = NotificationLevel.ALL,
    val isFavorite: Boolean = false,
    val floodScopeModeRawValue: String = "inherit",
    val regionScope: String? = null,
    // Android-only lookup witness; never inferred from the reusable row ID or backup data.
    val occupantID: UUID? = null,
    // Display/removal provenance only; it survives witness withdrawal and never authorizes an operation.
    val displayIncarnationID: UUID? = null,
) {
    val floodScope: ChannelFloodScope get() = ChannelFloodScopeStorage(floodScopeModeRawValue, regionScope).scope
    val isMuted: Boolean get() = notificationLevel == NotificationLevel.MUTED
    val isPublicChannel: Boolean get() = index == 0.toUByte()
    val hasSecret: Boolean get() = secret.any { it != 0.toUByte() }
    val isEncryptedChannel: Boolean get() = !isPublicChannel && !name.startsWith("#")

    fun withFloodScope(scope: ChannelFloodScope): ChannelDTO {
        val storage = ChannelFloodScopeStorage.decompose(scope)
        return copy(floodScopeModeRawValue = storage.modeRawValue, regionScope = storage.regionName)
    }

    fun withNotificationLevel(level: NotificationLevel): ChannelDTO =
        copy(notificationLevel = level).withFloodScope(floodScope)

    fun withFavorite(favorite: Boolean): ChannelDTO = copy(isFavorite = favorite).withFloodScope(floodScope)
    fun updating(info: ChannelInfo): ChannelDTO = copy(
        name = info.name, secret = info.secret,
        occupantID = occupantID.takeIf { info.index == index && info.secret == secret },
        displayIncarnationID = displayIncarnationID.takeIf { info.index == index && info.secret == secret },
    )
    fun toChannelInfo(): ChannelInfo = ChannelInfo(index, name, secret)

    companion object {
        fun fromInfo(radioId: RadioId, info: ChannelInfo, id: UUID = UUID.randomUUID()): ChannelDTO =
            ChannelDTO(id, radioId, info.index, info.name, info.secret)

        fun fromLegacyFields(
            id: UUID, radioId: RadioId, index: UByte, name: String, secret: Bytes, isEnabled: Boolean,
            lastMessageDate: Instant?, unreadCount: Long, unreadMentionCount: Long?, notificationLevel: Long?,
            isFavorite: Boolean?, modeRawValue: String?, regionScope: String?,
        ): ChannelDTO {
            val level = if (notificationLevel == null) NotificationLevel.ALL
            else NotificationLevel.fromRawValue(notificationLevel)
                ?: throw InvalidRawEnumException("NotificationLevel", notificationLevel)
            val storage = ChannelFloodScopeStorage.legacy(modeRawValue, regionScope)
            return ChannelDTO(
                id, radioId, index, name, secret, isEnabled, lastMessageDate, unreadCount,
                unreadMentionCount ?: 0, level, isFavorite ?: false, storage.modeRawValue, storage.regionName,
            )
        }
    }
}
