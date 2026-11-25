// PortedFrom: MC1Services/Sources/MC1Services/Sync/SyncCoordinator+HandlerHelpers.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Sync/SyncCoordinator+MessageHandlers.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.TextRulePersisting
import com.meshcoreone.android.core.contracts.notifications.ChannelNotificationTarget
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.MessageDTO
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
    requireReadSuccess: Boolean = false,
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
        if (requireReadSuccess) throw failure
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
    admittedRules: TextRulePersisting?,
    notificationEligible: Boolean,
    messageIncarnation: String? = null,
    progress: MessagePublicationProgress? = null,
): ContactDTO? {
    val beforeIndex = if (contact != null && admittedRules != null) {
        admittedRules.currentDirectTextContact(
            messageDTO, contact, messageSaved = true, messageIncarnation = messageIncarnation,
        ) ?: return null
    } else contact
    if (beforeIndex != null && progress?.indexed != true) {
        val persisted = contain("indexDMMessage", null) {
            dependencies.reactionService.persistIndexedDMMessage(
                messageDTO.id, radioId, beforeIndex.id, messageText, timestamp, dependencies.dataStore,
            ) { result ->
                publishReactionPersisted(result)
            }
        }
        if (persisted == null) log(DebugLogLevel.ERROR, "Failed to persist pending DM reactions for message ${messageDTO.id}; retained for retry")
        persisted.orEmpty().filter { it.shouldNotify }.forEach { result ->
            log(DebugLogLevel.DEBUG, "Processed pending DM reaction for message ${result.messageID}")
        }
        if (progress != null) progress.indexed = true
    }
    val current = if (beforeIndex != null && admittedRules != null) {
        admittedRules.currentDirectTextContact(
            messageDTO, beforeIndex, messageSaved = true, messageIncarnation = messageIncarnation,
        ) ?: return null
    } else beforeIndex
    if (current != null && admittedRules == null) {
        dependencies.dataStore.updateContactLastMessage(EntityKey(radioId, current.id), clock.now())
    }
    // Only increment unread, post notification and update badge for non-blocked contacts.
    if (current != null && !current.isBlocked && notificationEligible) {
        updateDMUnreadsAndNotify(
            messageDTO, current.id, current, messageText, hasSelfMention, dependencies, radioId,
            updateUnread = admittedRules == null, progress = progress,
        )
    }
    return current
}

internal class MessagePublicationProgress {
    var indexed = false
    var notificationHandled = false
    var badgeHandled = false
}

internal fun ChannelDTO?.matchesReceivedOccupant(expected: ChannelDTO): Boolean =
    this != null && isEnabled && id == expected.id && secret == expected.secret &&
        occupantID == expected.occupantID

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
    bookkeepingCommitted: Boolean,
    notificationEligible: Boolean,
    progress: MessagePublicationProgress? = null,
    beforePublish: suspend () -> Boolean = { true },
): Boolean {
    // Index with the original timestamp so pending reactions can match.
    if (senderNodeName != null && progress?.indexed != true) {
        val persisted = contain("indexMessage", null) {
            dependencies.reactionService.persistIndexedChannelMessage(
                messageDTO.id, radioId, channelIndex, senderNodeName, messageText, timestamp, dependencies.dataStore,
                messageDTO.channelReactionIncarnation,
            ) { result ->
                publishReactionPersisted(result)
            }
        }
        if (persisted == null) log(DebugLogLevel.ERROR, "Failed to persist pending channel reactions for message ${messageDTO.id}; retained for retry")
        if (progress != null) progress.indexed = true
    }
    if (!notificationEligible) return beforePublish()
    if (channel != null && bookkeepingCommitted) {
        val current = dependencies.dataStore.fetchChannel(radioId, channelIndex)
        if (!current.matchesReceivedOccupant(channel)) return beforePublish()
    }
    if (channel != null && !bookkeepingCommitted) {
        val store = dependencies.dataStore
        val occupant = channel.occupantID
        if (store is com.meshcoreone.android.core.contracts.domain.TextRulePersisting && occupant != null) {
            val outcome = store.applyChannelMessageMetadataIfCurrentOccupant(
                com.meshcoreone.android.core.contracts.domain.ChannelHistoryScope(radioId, channelIndex, occupant),
                clock.now(), incrementUnreadCount = false,
            )
            if (outcome != com.meshcoreone.android.core.contracts.domain.ChannelOccupantMetadataMutationOutcome.APPLIED) {
                return beforePublish()
            }
        } else {
            store.updateChannelLastMessage(EntityKey(radioId, channel.id), clock.now())
        }
    }
    if (!isBlockedSender(senderNodeName)) {
        return updateChannelUnreadsAndNotify(
            messageDTO, channel, channelIndex, senderNodeName, messageText, timestamp, hasSelfMention, radioId, dependencies,
            updateUnread = !bookkeepingCommitted, progress = progress, beforePublish = beforePublish,
        )
    }
    return beforePublish()
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
    updateUnread: Boolean,
    progress: MessagePublicationProgress? = null,
) {
    val notifications = dependencies.notificationService
    // Only increment unread when the user is not viewing this contact's chat.
    val isViewingContact = updateUnread && contain("activeContactID", null) { notifications.activeContactID() } == contactID
    if (updateUnread && !isViewingContact) {
        val key = EntityKey(radioId, contactID)
        dependencies.dataStore.incrementUnreadCount(key)
        if (hasSelfMention) dependencies.dataStore.incrementUnreadMentionCount(key)
    }
    if (progress?.notificationHandled != true) {
        contain("postDirectMessageNotification", Unit) {
            notifications.postDirectMessageNotification(
                contact?.displayName ?: "Unknown", contactID, messageText, messageDTO.id, contact?.isMuted ?: false,
            ) { if (progress != null) progress.notificationHandled = true }
        }
        if (progress != null) progress.notificationHandled = true
    }
    if (progress?.badgeHandled != true) {
        contain("updateBadgeCount", Unit) { notifications.updateBadgeCount() }
        if (progress != null) progress.badgeHandled = true
    }
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
    updateUnread: Boolean,
    progress: MessagePublicationProgress? = null,
    beforePublish: suspend () -> Boolean = { true },
): Boolean {
    val notifications = dependencies.notificationService
    if (channel != null && updateUnread) {
        val active = contain("activeChannelOrigin", null) { notifications.activeChannelOrigin() }
        val isViewingChannel = active != null && active.radioId == channel.radioId &&
            active.channelIndex == channel.index && active.expectedOccupantID == channel.occupantID
        if (!isViewingChannel) {
            val store = dependencies.dataStore
            val scoped = store as? com.meshcoreone.android.core.contracts.domain.ChannelDeliveryUnreadPersisting
            if (scoped != null) {
                scoped.incrementChannelDeliveryUnread(channel, hasSelfMention)
            } else if (channel.occupantID == null) {
                val key = EntityKey(radioId, channel.id)
                store.incrementChannelUnreadCount(key)
                if (hasSelfMention) store.incrementChannelUnreadMentionCount(key)
            }
        }
    }
    if (progress?.notificationHandled != true) {
        if (channel != null && shouldPostChannelNotification(channel)) {
            val incarnation = messageDTO.channelReactionIncarnation
            val history = dependencies.dataStore as? com.meshcoreone.android.core.contracts.domain.ChannelHistoryPersisting
            val occupant = channel.occupantID
            val scope = if (occupant != null && channel.radioId == radioId && channel.index == channelIndex) {
                com.meshcoreone.android.core.contracts.domain.ChannelHistoryScope(radioId, channelIndex, occupant)
            } else null
            val displayed = if (scope != null && history != null && incarnation != null) {
                contain("captureChannelNotificationDisplay", null) {
                    history.fetchChannelTimelineMessage(scope, EntityKey(radioId, messageDTO.id))
                        ?.takeIf { it.channelReactionIncarnation == incarnation }
                }
            } else null
            val target = if (displayed != null && scope != null) {
                ChannelNotificationTarget.Proven(radioId, channelIndex, scope.expectedOccupantID)
            } else ChannelNotificationTarget.Unproved
            contain("postChannelMessageNotification", Unit) {
                notifications.postChannelMessageNotification(
                    channel.name, channelIndex, radioId, senderNodeName, messageText,
                    messageDTO.id, channel.notificationLevel, hasSelfMention,
                    target, incarnation.takeIf { displayed != null },
                ) { if (progress != null) progress.notificationHandled = true }
            }
        } else {
            recordUnresolvedChannel(channelIndex, radioId, timestamp)
        }
        if (progress != null) progress.notificationHandled = true
    }
    if (progress?.badgeHandled != true) {
        contain("updateBadgeCount", Unit) { notifications.updateBadgeCount() }
        if (progress != null) progress.badgeHandled = true
    }
    if (!beforePublish()) return false
    dataEventBroadcaster.yield(SyncDataEvent.ChannelMessageReceived(messageDTO, channelIndex))
    return true
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
