// PortedFrom: MC1Services/Sources/MC1Services/Protocols/NotificationStringProvider.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/State/NavigationCoordinator.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Typed platform/navigation projections, not notification, translation or navigation implementations.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

sealed interface Destination {
    data class Tab(val tab: AppTab) : Destination
    data class Feature(val feature: FeatureId) : Destination
    data class DirectChat(val contact: EntityKey, val scrollToMessageID: UUID? = null) : Destination
    data class ChannelChat(val radioId: RadioId, val channelIndex: UByte, val scrollToMessageID: UUID? = null) : Destination
    data class Room(val session: EntityKey) : Destination
    data class RoomAuthentication(val session: EntityKey) : Destination
    data class Discovery(val radioId: RadioId) : Destination
    data class NodeDetail(val contact: EntityKey) : Destination
    data class MapFocus(val coordinate: Coordinate) : Destination
    data class ConfirmContactLink(val radioId: RadioId, val frame: ContactFrame) : Destination
    data class ConfirmChannelLink(val radioId: RadioId, val name: String, val secret: Bytes) : Destination
    data class ConfirmHashtag(val radioId: RadioId, val name: String) : Destination
}

interface NotificationStringProvider {
    fun discoveryNotificationTitle(type: ContactType): String
    val replyActionTitle: String
    val sendButtonTitle: String
    val messagePlaceholder: String
    val markAsReadActionTitle: String
    val lowBatteryTitle: String
    fun lowBatteryBody(deviceName: String, percentage: Long): String
    val quickReplyFailedTitle: String
    fun quickReplyFailedBody(conversationName: String): String
    val unknownContactName: String
    fun defaultChannelName(index: Long): String
    fun reactionNotificationBody(emoji: String, messagePreview: String): String
}

@JvmInline value class NotificationId(val value: String)
data class NotificationCommand(
    val id: NotificationId, val radioId: RadioId, val destination: Destination, val messageID: UUID?,
    val title: String, val body: String, val soundEnabled: Boolean, val badgeEnabled: Boolean,
    val sessionToken: SessionToken?,
)
sealed interface NotificationPostResult {
    data object Posted : NotificationPostResult
    data object PermissionDenied : NotificationPostResult
    data class Unsupported(val capability: Capability) : NotificationPostResult
}
interface NotificationPort {
    suspend fun post(command: NotificationCommand): NotificationPostResult
    suspend fun cancel(id: NotificationId)
}
interface NotificationPreferencesPort {
    val preferences: StateFlow<NotificationPreferences>
    suspend fun update(preferences: NotificationPreferences)
}

data class LanguagePair(val source: String, val target: String)
data class DownloadConsent(val grantedAt: Instant, val allowMetered: Boolean)
sealed interface TranslationIssue {
    data object EngineUnavailable : TranslationIssue
    data class UnsupportedLanguage(val pair: LanguagePair) : TranslationIssue
    data class ModelMissing(val pair: LanguagePair) : TranslationIssue
    data class DownloadFailed(val cause: Throwable) : TranslationIssue
    data class TranslationFailed(val cause: Throwable) : TranslationIssue
    data class ModelStorageFailed(val cause: Throwable) : TranslationIssue
}
sealed interface ModelAvailability {
    data object Unavailable : ModelAvailability
    data class UnsupportedLanguage(val pair: LanguagePair) : ModelAvailability
    data object DownloadRequired : ModelAvailability
    data class Downloading(val receivedBytes: Long, val totalBytes: Long?) : ModelAvailability
    data object Ready : ModelAvailability
    data class Failed(val issue: TranslationIssue) : ModelAvailability
}
interface TranslationPort {
    fun availability(pair: LanguagePair): StateFlow<ModelAvailability>
    suspend fun download(pair: LanguagePair, consent: DownloadConsent)
    suspend fun deleteModel(pair: LanguagePair)
    suspend fun translate(text: String, pair: LanguagePair): String
}
