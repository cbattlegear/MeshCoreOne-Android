// PortedFrom: MC1Services/Sources/MC1Services/Sync/SyncCoordinator+HandlerHelpers.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Sync/SyncCoordinator+MessageHandlers.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.RouteType
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/** Path data correlated from an RX log entry for an incoming message. */
internal data class RxLogLookupResult(
    val pathNodes: Bytes?,
    val pathLength: UByte,
    val packetHash: String?,
    val routeType: RouteType?,
    val regionScope: String?,
    val regionScopeMatches: SnapshotList<String>,
)

private fun RxLogEntryDTO.lookupResult() =
    RxLogLookupResult(pathNodes, pathLength, packetHash, routeType, regionScope, regionScopeMatches)

/** Lookback window for the DM sender-prefix fallback correlation. */
private val SENDER_PREFIX_LOOKBACK = 30.seconds

/**
 * Looks up path data from an RX log entry to correlate with an incoming message. Channel rows join by
 * deduplication key after decrypt; DMs use timestamp, then a sender-prefix fallback.
 */
internal suspend fun SyncCoordinator.lookupRxLogEntry(
    dependencies: SyncDependencies,
    radioId: RadioId,
    channelIndex: UByte?,
    senderTimestamp: UInt,
    senderPublicKeyPrefix: Bytes?,
    defaultPathLength: UByte,
    channelDeduplicationKey: String?,
): RxLogLookupResult {
    val store = dependencies.dataStore
    if (channelIndex != null) log(DebugLogLevel.DEBUG, "Looking up RxLogEntry for channel $channelIndex with senderTimestamp: $senderTimestamp")
    try {
        if (channelIndex != null) {
            if (channelDeduplicationKey != null) {
                val raw = store.fetchRxLogEntries(radioId, channelIndex, senderTimestamp)
                val decoded = contain("decodedEntries", emptyList()) { dependencies.rxLogService.decodedEntries(raw) }
                val match = dependencies.codecs.channelRXCorrelation.matching(decoded, channelDeduplicationKey).firstOrNull()
                if (match != null) {
                    log(DebugLogLevel.INFO, "Correlated channel message to RxLogEntry: pathLength=${match.pathLength}, pathNodes=${match.pathNodes.size} bytes")
                    return match.lookupResult()
                }
            }
            log(DebugLogLevel.WARNING, "No RxLogEntry found for channel $channelIndex, senderTimestamp: $senderTimestamp")
        } else {
            val entry = store.findRxLogEntry(radioId, null, senderTimestamp)
            if (entry != null) {
                log(DebugLogLevel.DEBUG, "Correlated incoming direct message to RxLogEntry, pathLength: ${entry.pathLength}")
                return entry.lookupResult()
            }
            val prefixByte = senderPublicKeyPrefix?.firstOrNull()
            if (prefixByte != null) {
                // Timestamp lookup failed (decryption may not have extracted it yet): match the sender
                // prefix byte in the raw payload within a recent window.
                val receivedSince = clock.now().minusNanos(SENDER_PREFIX_LOOKBACK.inWholeNanoseconds)
                val fallback = store.findRxLogEntryBySenderPrefix(radioId, prefixByte, receivedSince)
                if (fallback != null) {
                    log(DebugLogLevel.DEBUG, "Correlated DM to RxLogEntry via sender prefix fallback, pathLength: ${fallback.pathLength}")
                    return fallback.lookupResult()
                }
                log(DebugLogLevel.DEBUG, "No RxLogEntry found for direct message (primary + fallback), senderTimestamp: $senderTimestamp")
            } else {
                log(DebugLogLevel.DEBUG, "No RxLogEntry found for direct message, senderTimestamp: $senderTimestamp")
            }
        }
    } catch (failure: Exception) {
        failure.rethrowIfCallerCancelled()
        val noun = if (channelIndex != null) "channel" else "direct"
        log(DebugLogLevel.ERROR, "Failed to lookup RxLogEntry for $noun message: ${failure.syncDescription()}")
    }
    return RxLogLookupResult(null, defaultPathLength, null, null, null, SnapshotList.empty())
}

/** Post-save side effects for a direct message: reaction indexing, contact bookkeeping, unreads. */
internal suspend fun SyncCoordinator.indexAndNotifyDirectMessage(
    messageDTO: MessageDTO,
    contact: ContactDTO?,
    messageText: String,
    timestamp: UInt,
    hasSelfMention: Boolean,
    dependencies: SyncDependencies,
    radioId: RadioId,
) {
    if (contact != null) {
        val pending = contain("indexDMMessage", emptyList()) {
            dependencies.reactionService.indexDMMessage(messageDTO.id, contact.id, messageText, timestamp)
        }
        for (match in pending) {
            val reaction = ReactionDTO(
                messageID = messageDTO.id, emoji = match.parsed.emoji, senderName = match.senderName,
                messageHash = match.parsed.messageHash, rawText = match.rawText, contactID = contact.id, radioId = radioId,
            )
            if (persistReactionIfNew(reaction, dependencies)) log(DebugLogLevel.DEBUG, "Processed pending DM reaction ${match.parsed.emoji}")
        }
        dependencies.dataStore.updateContactLastMessage(EntityKey(radioId, contact.id), clock.now())
    }
    // Only increment unread, post notification and update badge for non-blocked contacts.
    if (contact != null && !contact.isBlocked) {
        updateDMUnreadsAndNotify(messageDTO, contact.id, contact, messageText, hasSelfMention, dependencies, radioId)
    }
}

/** Post-save side effects for a channel message: reaction indexing, channel bookkeeping, unreads. */
internal suspend fun SyncCoordinator.indexAndNotifyChannelMessage(
    messageDTO: MessageDTO,
    channel: ChannelDTO?,
    channelIndex: UByte,
    senderNodeName: String?,
    messageText: String,
    timestamp: UInt,
    hasSelfMention: Boolean,
    dependencies: SyncDependencies,
    radioId: RadioId,
) {
    // Index with the original timestamp so pending reactions can match.
    if (senderNodeName != null) {
        val pending = contain("indexMessage", emptyList()) {
            dependencies.reactionService.indexMessage(messageDTO.id, channelIndex, senderNodeName, messageText, timestamp)
        }
        for (match in pending) {
            val reaction = ReactionDTO(
                messageID = messageDTO.id, emoji = match.parsed.emoji, senderName = match.senderNodeName,
                messageHash = match.parsed.messageHash, rawText = match.rawText, channelIndex = match.channelIndex,
                radioId = match.radioId,
            )
            persistReactionIfNew(reaction, dependencies)
        }
    }
    if (channel != null) dependencies.dataStore.updateChannelLastMessage(EntityKey(radioId, channel.id), clock.now())
    if (!isBlockedSender(senderNodeName)) {
        updateChannelUnreadsAndNotify(
            messageDTO, channel, channelIndex, senderNodeName, messageText, timestamp, hasSelfMention, radioId, dependencies,
        )
    }
}

/** Increments unread counts and posts a notification for a direct message. */
internal suspend fun SyncCoordinator.updateDMUnreadsAndNotify(
    messageDTO: MessageDTO,
    contactID: UUID,
    contact: ContactDTO?,
    messageText: String,
    hasSelfMention: Boolean,
    dependencies: SyncDependencies,
    radioId: RadioId,
) {
    val notifications = dependencies.notificationService
    // Only increment unread when the user is not viewing this contact's chat.
    val isViewingContact = contain("activeContactID", null) { notifications.activeContactID() } == contactID
    if (!isViewingContact) {
        val key = EntityKey(radioId, contactID)
        dependencies.dataStore.incrementUnreadCount(key)
        if (hasSelfMention) dependencies.dataStore.incrementUnreadMentionCount(key)
    }
    contain("postDirectMessageNotification", Unit) {
        notifications.postDirectMessageNotification(
            contact?.displayName ?: "Unknown", contactID, messageText, messageDTO.id, contact?.isMuted ?: false,
        )
    }
    contain("updateBadgeCount", Unit) { notifications.updateBadgeCount() }
}

/** Increments unread counts, posts a notification, and broadcasts a channel message. */
internal suspend fun SyncCoordinator.updateChannelUnreadsAndNotify(
    messageDTO: MessageDTO,
    channel: ChannelDTO?,
    channelIndex: UByte,
    senderNodeName: String?,
    messageText: String,
    timestamp: UInt,
    hasSelfMention: Boolean,
    radioId: RadioId,
    dependencies: SyncDependencies,
) {
    val notifications = dependencies.notificationService
    if (channel != null) {
        val activeIndex = contain("activeChannelIndex", null) { notifications.activeChannelIndex() }
        val activeRadioId = contain("activeChannelRadioId", null) { notifications.activeChannelRadioId() }
        val isViewingChannel = activeIndex == channel.index && activeRadioId == channel.radioId
        if (!isViewingChannel) {
            val key = EntityKey(radioId, channel.id)
            dependencies.dataStore.incrementChannelUnreadCount(key)
            if (hasSelfMention) dependencies.dataStore.incrementChannelUnreadMentionCount(key)
        }
    }
    if (shouldPostChannelNotification(channel)) {
        contain("postChannelMessageNotification", Unit) {
            notifications.postChannelMessageNotification(
                channel?.name ?: "Channel $channelIndex", channelIndex, radioId, senderNodeName, messageText,
                messageDTO.id, channel?.notificationLevel ?: NotificationLevel.ALL, hasSelfMention,
            )
        }
    } else {
        recordUnresolvedChannel(channelIndex, radioId, timestamp)
    }
    contain("updateBadgeCount", Unit) { notifications.updateBadgeCount() }
    dataEventBroadcaster.yield(SyncDataEvent.ChannelMessageReceived(messageDTO, channelIndex))
}

/** Logs suppressed notifications for unresolved slots, with a rate-limited summary. */
private fun SyncCoordinator.recordUnresolvedChannel(channelIndex: UByte, radioId: RadioId, senderTimestamp: UInt) {
    log(
        DebugLogLevel.WARNING,
        "Suppressing notification for unresolved channel $channelIndex on device ${radioId.canonicalString}, " +
            "senderTimestamp: $senderTimestamp — no local channel for this slot",
    )
    val now = clock.now()
    val summary = locked {
        val isNewIndex = channelIndex !in unresolvedChannelIndices
        unresolvedChannelIndices = unresolvedChannelIndices + channelIndex
        val last = lastUnresolvedChannelSummaryAt
        val shouldEmit = isNewIndex || last == null ||
            now.secondsSinceEpoch() - last.secondsSinceEpoch() >= SyncCoordinator.UNRESOLVED_CHANNEL_SUMMARY_INTERVAL_SECONDS
        if (!shouldEmit) null else {
            lastUnresolvedChannelSummaryAt = now
            unresolvedChannelIndices.sorted()
        }
    } ?: return
    log(DebugLogLevel.WARNING, "Unresolved channel summary: total=${summary.size}, indices=$summary")
}

/** Unresolved channel indices recorded this connection (test visibility). */
internal val SyncCoordinator.unresolvedChannelIndexSnapshot: Set<UByte> get() = locked { unresolvedChannelIndices }
