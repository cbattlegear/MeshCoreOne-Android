// AndroidOnly: WP-214 Narrow ports for sync collaborators owned by unmerged WPs 209/210/212/215/216.
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.contracts.domain.ReactionPersisting
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ContactMessage
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/*
 * One port per Swift collaborator, listing exactly the members SyncCoordinator calls. Signatures mirror
 * the collaborator's Kotlin port where one exists on its branch, so WP-303's adapters are one-line
 * forwards. Every member that Swift declares non-throwing is still allowed to throw here; the
 * coordinator contains such throws (logs and continues) instead of letting them end a sync or a loop.
 */

/**
 * `NotificationService` (WP-215). Future implementer: `notifications.NotificationService`.
 * Swift exposes `isSuppressingNotifications` and the active-conversation slots as `@MainActor` vars.
 */
interface SyncNotificationServicing {
    suspend fun isSuppressingNotifications(): Boolean
    suspend fun setSuppressingNotifications(suppressing: Boolean)
    suspend fun activeContactID(): UUID?
    suspend fun activeChannelIndex(): UByte?
    suspend fun activeChannelRadioId(): RadioId?

    suspend fun postDirectMessageNotification(
        from: String, contactID: UUID, messageText: String, messageID: UUID, isMuted: Boolean,
    )

    suspend fun postChannelMessageNotification(
        channelName: String, channelIndex: UByte, radioId: RadioId, senderName: String?, messageText: String,
        messageID: UUID, notificationLevel: NotificationLevel, hasSelfMention: Boolean,
    )

    suspend fun postRoomMessageNotification(
        roomName: String, sessionID: UUID, senderName: String?, messageText: String, messageID: UUID,
        notificationLevel: NotificationLevel,
    )

    suspend fun postNewContactNotification(contactName: String, contactID: UUID, contactType: ContactType)
    suspend fun updateBadgeCount()
}

/** WP-216 `reactions.PendingReaction` without its `receivedAt` stamp, which sync never reads. */
data class SyncPendingReaction(
    val parsed: ParsedReaction,
    val channelIndex: UByte,
    val senderNodeName: String,
    val rawText: String,
    val radioId: RadioId,
)

/** WP-216 `reactions.ParsedDMReaction`. */
data class SyncParsedDMReaction(val emoji: String, val messageHash: String)

/** WP-216 `reactions.PendingDMReaction` without its `receivedAt` stamp. */
data class SyncPendingDMReaction(
    val parsed: SyncParsedDMReaction,
    val contactID: UUID,
    val senderName: String,
    val rawText: String,
    val radioId: RadioId,
)

/** WP-216 `reactions.ReactionPersistResult`. */
data class SyncReactionPersistResult(val messageID: UUID, val summary: String)

/** `ReactionService` (WP-216). Future implementer: `reactions.ReactionService`. */
interface SyncReactionServicing {
    suspend fun indexDMMessage(id: UUID, contactID: UUID, text: String, timestamp: UInt): List<SyncPendingDMReaction>
    suspend fun indexMessage(id: UUID, channelIndex: UByte, senderName: String, text: String, timestamp: UInt): List<SyncPendingReaction>
    suspend fun persistReactionAndUpdateSummary(reaction: ReactionDTO, dataStore: ReactionPersisting): SyncReactionPersistResult?
    suspend fun findDMTargetMessage(messageHash: String, contactID: UUID): UUID?
    suspend fun queuePendingDMReaction(parsed: SyncParsedDMReaction, contactID: UUID, senderName: String, rawText: String, radioId: RadioId)

    /** Swift `nonisolated func tryProcessAsReaction`; null means process the text as a regular message. */
    fun tryProcessAsReaction(text: String): ParsedReaction?
    suspend fun findTargetMessage(parsed: ParsedReaction, channelIndex: UByte): UUID?
    suspend fun queuePendingReaction(parsed: ParsedReaction, channelIndex: UByte, senderNodeName: String, rawText: String, radioId: RadioId)
}

/** `HeardRepeatsService` (WP-216). Future implementer: `reactions.HeardRepeatsService`. */
interface SyncHeardRepeatsServicing {
    suspend fun harvestIncomingPaths(message: MessageDTO, decodedCandidates: List<RxLogEntryDTO>)

    suspend fun recordDistinctPathIfNeeded(
        message: MessageDTO, pathNodes: Bytes, pathLength: UByte, snr: Double?, rssi: Long?,
        receivedAt: Instant, rxLogEntryID: UUID?,
    ): Long?
}

/** WP-209 `contacts.AdvertContactSyncOutcome`. */
enum class SyncAdvertContactSyncOutcome { SYNCED, BUSY, FAILED, NOT_READY }

/** The `AdvertisementEvent` cases sync consumes; every other WP-209 case maps to [Other]. */
sealed interface SyncDiscoveryEvent {
    data class NewContactDiscovered(val name: String, val contactID: UUID, val contactType: ContactType) : SyncDiscoveryEvent
    data class OrphanDirectMessagesAdopted(val contactIDs: List<UUID>) : SyncDiscoveryEvent
    data object Other : SyncDiscoveryEvent
}

/** `AdvertisementService` (WP-209). Future implementer: `contacts.AdvertisementService`. */
interface SyncAdvertisementServicing {
    suspend fun setSyncingContacts(isSyncing: Boolean)
    suspend fun setDeltaSyncHandler(handler: (suspend (fullRefetch: Boolean) -> SyncAdvertContactSyncOutcome)?)
    suspend fun materializeContactForPendingAdvert(prefix: Bytes, radioId: RadioId): ContactDTO?

    /** Registers the subscriber when called (not when collected), like WP-209's broadcaster. */
    fun events(): Flow<SyncDiscoveryEvent>
}

/** `RxLogService` (WP-212). Future implementer: `diagnostics.RxLogService`. */
interface SyncRxLogServicing {
    suspend fun updatePrivateKey(key: Bytes?)
    suspend fun updateContactPublicKeys(keys: Map<UByte, List<Bytes>>)
    suspend fun updateChannels(secrets: Map<UByte, Bytes>, names: Map<UByte, String>)
    suspend fun decodedEntries(entries: List<RxLogEntryDTO>): List<RxLogEntryDTO>
}

/** `RoomServerService.handleIncomingMessage` (WP-210). Future implementer: `remote.RoomServerService`. */
interface SyncRoomServerServicing {
    suspend fun handleIncomingMessage(senderPublicKeyPrefix: Bytes, timestamp: UInt, authorPrefix: Bytes, text: String): RoomMessageDTO?
}

/** `RoomAdminService.invokeCLIHandler` (WP-210). Future implementer: `remote.RoomAdminService`. */
interface SyncRoomAdminServicing {
    suspend fun invokeCLIHandler(message: ContactMessage, contact: ContactDTO)
}

/** `RepeaterAdminService.invokeCLIHandler` (WP-210). Future implementer: `remote.RepeaterAdminService`. */
interface SyncRepeaterAdminServicing {
    suspend fun invokeCLIHandler(message: ContactMessage, contact: ContactDTO)
}

/** `RemoteNodeService.handleBLEReconnection` (WP-210). Future implementer: `remote.RemoteNodeService`. */
interface SyncRemoteNodeServicing {
    suspend fun handleBLEReconnection(sessions: Set<EntityKey>)
}
