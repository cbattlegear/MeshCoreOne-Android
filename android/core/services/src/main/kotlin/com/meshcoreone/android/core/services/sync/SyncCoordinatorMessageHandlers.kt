// PortedFrom: MC1Services/Sources/MC1Services/Sync/SyncCoordinator+MessageHandlers.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TextType
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelMessage
import com.meshcoreone.android.core.protocol.event.ContactMessage
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** Discriminates the two text-message ingestion paths, with the resolved conversation for each. */
internal sealed interface IncomingMessageKind {
    data class Direct(val message: ContactMessage, val contact: ContactDTO?) : IncomingMessageKind
    data class Channel(val message: ChannelMessage, val channel: ChannelDTO?) : IncomingMessageKind

    /** Log noun distinguishing the two paths in shared diagnostics. */
    val logLabel: String get() = if (this is Direct) "direct" else "channel"
}

// MARK: - Message handler wiring

/** Wires the contact, channel, signed and CLI handlers on the message polling service. */
suspend fun SyncCoordinator.wireMessageHandlers(dependencies: SyncDependencies, radioId: RadioId) {
    log(DebugLogLevel.INFO, "Wiring message handlers for device ${radioId.canonicalString}")
    refreshBlockedContactsCache(radioId, dependencies.dataStore)

    // Cache the device node name for self-mention detection (Swift `try?`).
    val device = try {
        dependencies.dataStore.fetchDevice(radioId)
    } catch (failure: Exception) {
        failure.rethrowIfCallerCancelled()
        null
    }
    val selfNodeName = device?.nodeName ?: ""
    val polling = dependencies.messagePollingService

    contain("setContactMessageHandler", Unit) {
        polling.setContactMessageHandler { message, contact, context ->
            guardHandler("contact message") {
                handleIncomingMessage(IncomingMessageKind.Direct(message, contact), context, dependencies, radioId, selfNodeName)
            }
        }
    }
    contain("setChannelMessageHandler", Unit) {
        polling.setChannelMessageHandler { message, channel, context ->
            guardHandler("channel message") {
                handleIncomingMessage(IncomingMessageKind.Channel(message, channel), context, dependencies, radioId, selfNodeName)
            }
        }
    }
    contain("setSignedMessageHandler", Unit) {
        polling.setSignedMessageHandler { message, _ ->
            guardHandler("signed message") { handleIncomingSignedMessage(message, dependencies, radioId) }
        }
    }
    contain("setCLIMessageHandler", Unit) {
        polling.setCLIMessageHandler { message, contact ->
            guardHandler("CLI message") { handleIncomingCLIMessage(message, contact, dependencies) }
        }
    }
    log(DebugLogLevel.INFO, "Message handlers wired successfully")
}

/** Swift handlers cannot throw; a Kotlin collaborator throw is logged instead of reaching the poller. */
private suspend inline fun SyncCoordinator.guardHandler(label: String, block: () -> Unit) =
    contain("$label handler", Unit, block)

// MARK: - Incoming message pipeline

/**
 * When a DM arrives with no local contact, try to create the row from a pending 0x80 key before
 * save/notify, so unread, notification and Chats-list updates take the live path exactly once.
 */
private suspend fun SyncCoordinator.resolveDirectContactIfNeeded(
    kind: IncomingMessageKind,
    dependencies: SyncDependencies,
    radioId: RadioId,
): IncomingMessageKind {
    if (kind !is IncomingMessageKind.Direct || kind.contact != null) return kind
    val prefix = kind.message.senderPublicKeyPrefix
    if (prefix.isEmpty) return kind
    val materialized = contain("materializeContactForPendingAdvert", null) {
        dependencies.advertisementService.materializeContactForPendingAdvert(prefix, radioId)
    } ?: return kind
    return IncomingMessageKind.Direct(kind.message, materialized)
}

/** Per-kind wire fields; channel text embeds the sender as a "NodeName: text" prefix. */
private class WireFields(
    val text: String,
    val senderNodeName: String?,
    val senderTimestamp: Instant,
    val textTypeRaw: UByte,
    val snr: Double?,
    val reportedPathLength: UByte,
    val contactID: UUID?,
    val channelIndex: UByte?,
    val senderKeyPrefix: Bytes?,
)

private fun wireFields(kind: IncomingMessageKind): WireFields = when (kind) {
    // The firmware cannot surface a self-DM here: decrypt runs against a contact's shared secret.
    is IncomingMessageKind.Direct -> WireFields(
        kind.message.text, null, kind.message.senderTimestamp, kind.message.textType, kind.message.snr,
        kind.message.pathLength, kind.contact?.id, null, kind.message.senderPublicKeyPrefix,
    )
    is IncomingMessageKind.Channel -> {
        val parsed = parseChannelMessage(kind.message.text)
        WireFields(
            parsed.messageText, parsed.senderNodeName, kind.message.senderTimestamp, kind.message.textType,
            kind.message.snr, kind.message.pathLength, null, kind.message.channelIndex, null,
        )
    }
}

/**
 * Shared ingestion pipeline for incoming direct and channel messages: timestamp correction, RX-log path
 * correlation, dedup, reaction short-circuit, persistence, unread/notification updates, and UI refresh.
 */
internal suspend fun SyncCoordinator.handleIncomingMessage(
    kind: IncomingMessageKind,
    context: DeliveryContext,
    dependencies: SyncDependencies,
    radioId: RadioId,
    selfNodeName: String,
) {
    val resolvedKind = resolveDirectContactIfNeeded(kind, dependencies, radioId)
    val wire = wireFields(resolvedKind)
    val codecs = dependencies.codecs
    val timestamp = wire.senderTimestamp.uint32Seconds()

    val receiveTime = clock.now()
    val correction = SyncCoordinator.correctTimestampIfNeeded(timestamp, receiveTime)
    correction.rangeError?.let { failure ->
        log(DebugLogLevel.ERROR, "TimestampRangeError (${resolvedKind.logLabel} message): ${failure.message}")
    }
    if (correction.wasCorrected) {
        log(DebugLogLevel.DEBUG, "Corrected invalid ${resolvedKind.logLabel} message timestamp from $timestamp to $receiveTime")
    }
    val sortDate = SyncCoordinator.sortDate(context, receiveTime)

    // Content-based key (stable across retries); the RX packet hash differs per retry attempt.
    val deduplicationKey = codecs.deduplicationKey.contentBased(
        wire.contactID, wire.channelIndex, wire.senderNodeName, timestamp, wire.text,
    )
    val rxResult = lookupRxLogEntry(
        dependencies, radioId, wire.channelIndex, timestamp, wire.senderKeyPrefix, wire.reportedPathLength,
        if (wire.channelIndex != null) deduplicationKey else null,
    )

    val hasSelfMention = selfNodeName.isNotEmpty() &&
        (resolvedKind is IncomingMessageKind.Direct || !SyncSwiftText.equal(wire.senderNodeName, selfNodeName)) &&
        codecs.mentions.containsSelfMention(wire.text, selfNodeName)

    // Clamp an unknown wire textType to plain so a backup round-trip can always decode it.
    val textType = TextType.fromRawValue(wire.textTypeRaw) ?: run {
        log(DebugLogLevel.WARNING, "Unknown ${resolvedKind.logLabel} message textType raw=${wire.textTypeRaw}; clamping to .plain")
        TextType.PLAIN
    }

    val messageDTO = MessageDTO(
        radioId = radioId, contactID = wire.contactID, channelIndex = wire.channelIndex, text = wire.text,
        timestamp = correction.correctedTimestamp, createdAt = receiveTime, sortDate = sortDate,
        direction = MessageDirection.INCOMING,
        status = MessageStatus.DELIVERED, textType = textType,
        pathLength = rxResult.pathLength, snr = wire.snr, pathNodes = rxResult.pathNodes,
        senderKeyPrefix = wire.senderKeyPrefix, senderNodeName = wire.senderNodeName,
        deduplicationKey = deduplicationKey, containsSelfMention = hasSelfMention,
        timestampCorrected = correction.wasCorrected,
        senderTimestamp = if (correction.wasCorrected) timestamp else null,
        routeType = rxResult.routeType, regionScope = rxResult.regionScope,
        regionScopeMatches = rxResult.regionScopeMatches,
    )

    if (recordArrivalAndSkipDuplicate(resolvedKind, deduplicationKey, radioId, rxResult, wire.snr, receiveTime, dependencies)) {
        return
    }

    // Stamp lastHeard before the reaction early return so bodies and reactions both count.
    if (resolvedKind is IncomingMessageKind.Direct && resolvedKind.contact != null) {
        try {
            dependencies.dataStore.touchContactHeard(radioId, resolvedKind.contact.publicKey, clock.now())
        } catch (failure: Exception) {
            failure.rethrowIfCallerCancelled()
            log(DebugLogLevel.ERROR, "lastHeard stamp failed for inbound DM: ${failure.syncDescription()}")
        }
    }

    when (resolvedKind) {
        is IncomingMessageKind.Direct -> {
            val contact = resolvedKind.contact
            if (contact != null && handleDMReaction(wire.text, contact, radioId, dependencies)) return
        }
        is IncomingMessageKind.Channel -> {
            if (isBlockedSender(wire.senderNodeName)) return
            if (handleChannelReaction(
                    wire.text, resolvedKind.message.channelIndex, wire.senderNodeName, selfNodeName, receiveTime,
                    radioId, dependencies,
                )
            ) return
        }
    }

    try {
        dependencies.dataStore.saveMessage(messageDTO)
        val savedDTO = if (resolvedKind is IncomingMessageKind.Channel) {
            harvestAndRefreshChannelMessage(messageDTO, radioId, dependencies)
        } else messageDTO

        when (resolvedKind) {
            is IncomingMessageKind.Direct -> indexAndNotifyDirectMessage(
                savedDTO, resolvedKind.contact, wire.text, timestamp, hasSelfMention, dependencies, radioId,
            )
            is IncomingMessageKind.Channel -> indexAndNotifyChannelMessage(
                savedDTO, resolvedKind.channel, resolvedKind.message.channelIndex, wire.senderNodeName, wire.text,
                timestamp, hasSelfMention, dependencies, radioId,
            )
        }

        notifyConversationsChanged()
        if (resolvedKind is IncomingMessageKind.Direct && resolvedKind.contact != null) {
            dataEventBroadcaster.yield(SyncDataEvent.DirectMessageReceived(savedDTO, resolvedKind.contact))
        }
    } catch (failure: Exception) {
        failure.rethrowIfCallerCancelled()
        val noun = if (resolvedKind is IncomingMessageKind.Direct) "contact" else "channel"
        log(DebugLogLevel.ERROR, "Failed to save $noun message: ${failure.syncDescription()}")
    }
}

/**
 * True when a row already owns this content key. For channel duplicates a known distinct path is
 * recorded as an extra; a null path is unknown and is not written as one.
 */
private suspend fun SyncCoordinator.recordArrivalAndSkipDuplicate(
    kind: IncomingMessageKind,
    deduplicationKey: String,
    radioId: RadioId,
    rxResult: RxLogLookupResult,
    snr: Double?,
    receiveTime: Instant,
    dependencies: SyncDependencies,
): Boolean {
    val existing = try {
        dependencies.dataStore.fetchMessage(deduplicationKey, radioId) ?: return false
    } catch (failure: Exception) {
        failure.rethrowIfCallerCancelled()
        log(DebugLogLevel.WARNING, "Dedup check failed, proceeding with save: ${failure.syncDescription()}")
        return false
    }
    val path = rxResult.pathNodes
    if (kind is IncomingMessageKind.Channel && path != null) {
        contain("recordDistinctPathIfNeeded", null) {
            dependencies.heardRepeatsService.recordDistinctPathIfNeeded(
                existing, path, rxResult.pathLength, snr, null, receiveTime, null,
            )
        }
    }
    log(DebugLogLevel.INFO, "Skipping duplicate ${kind.logLabel} message")
    return true
}

/** Re-decrypts stamp-matched 0x88 rows and harvests extras, then refetches so the broadcast carries them. */
private suspend fun SyncCoordinator.harvestAndRefreshChannelMessage(
    messageDTO: MessageDTO,
    radioId: RadioId,
    dependencies: SyncDependencies,
): MessageDTO {
    val channelIndex = messageDTO.channelIndex ?: return messageDTO
    val stamp = messageDTO.senderTimestamp ?: messageDTO.timestamp
    try {
        val raw = dependencies.dataStore.fetchRxLogEntries(radioId, channelIndex, stamp)
        val decoded = contain("decodedEntries", emptyList()) { dependencies.rxLogService.decodedEntries(raw) }
        contain("harvestIncomingPaths", Unit) { dependencies.heardRepeatsService.harvestIncomingPaths(messageDTO, decoded) }
    } catch (failure: Exception) {
        failure.rethrowIfCallerCancelled()
        log(DebugLogLevel.ERROR, "Failed to harvest incoming channel paths: ${failure.syncDescription()}")
    }
    return dependencies.dataStore.fetchMessage(EntityKey(radioId, messageDTO.id)) ?: messageDTO
}

// MARK: - Signed (room) messages

/** Persists a signed room message, then notifies and refreshes the UI when it was new. */
private suspend fun SyncCoordinator.handleIncomingSignedMessage(
    message: ContactMessage,
    dependencies: SyncDependencies,
    radioId: RadioId,
) {
    // For signed room messages, the signature carries the 4-byte author key prefix.
    val authorPrefix = message.signature?.prefix(4)
    if (authorPrefix == null || authorPrefix.size != 4) {
        log(DebugLogLevel.WARNING, "Dropping signed message: missing or invalid author prefix")
        return
    }
    val timestamp = message.senderTimestamp.uint32Seconds()
    try {
        val saved = dependencies.roomServerService.handleIncomingMessage(
            message.senderPublicKeyPrefix, timestamp, authorPrefix, message.text,
        ) ?: return
        val session = try {
            dependencies.dataStore.fetchRemoteNodeSession(EntityKey(radioId, saved.sessionID))
        } catch (failure: Exception) {
            failure.rethrowIfCallerCancelled()
            null
        }
        val notifications = dependencies.notificationService
        contain("postRoomMessageNotification", Unit) {
            notifications.postRoomMessageNotification(
                session?.name ?: "Room", saved.sessionID, saved.authorName, saved.text, saved.id,
                session?.notificationLevel ?: NotificationLevel.ALL,
            )
        }
        contain("updateBadgeCount", Unit) { notifications.updateBadgeCount() }
        notifyConversationsChanged()
        dataEventBroadcaster.yield(SyncDataEvent.RoomMessageReceived(saved))
    } catch (failure: Exception) {
        failure.rethrowIfCallerCancelled()
        log(DebugLogLevel.ERROR, "Failed to handle room message: ${failure.syncDescription()}")
    }
}

// MARK: - CLI messages

/**
 * Routes a CLI response to the room or repeater admin service for the sending contact. An echoed wire
 * prefix is stripped first so parsers see the same reply regardless of firmware echo support.
 */
private suspend fun SyncCoordinator.handleIncomingCLIMessage(
    message: ContactMessage,
    contact: ContactDTO?,
    dependencies: SyncDependencies,
) {
    if (contact == null) {
        log(DebugLogLevel.WARNING, "Dropping CLI response: no contact found for sender")
        return
    }
    val body = dependencies.codecs.cliEcho.echoedBody(message.text)
    val routed = if (body != null) message.copy(text = body) else message
    if (contact.type == ContactType.ROOM) {
        contain("roomAdmin.invokeCLIHandler", Unit) { dependencies.roomAdminService.invokeCLIHandler(routed, contact) }
    } else {
        contain("repeaterAdmin.invokeCLIHandler", Unit) { dependencies.repeaterAdminService.invokeCLIHandler(routed, contact) }
    }
}

// MARK: - Discovery event monitoring

/**
 * Consumes the advertisement event stream to post new-contact notifications and refresh contact lists.
 * The subscription is registered before this returns; events yielded earlier (during the initial sync)
 * are deliberately not seen.
 */
fun SyncCoordinator.startDiscoveryEventMonitoring(dependencies: SyncDependencies, radioId: RadioId) {
    log(DebugLogLevel.INFO, "Starting discovery event monitoring for device ${radioId.canonicalString}")
    val events = try {
        dependencies.advertisementService.events()
    } catch (failure: Exception) {
        log(DebugLogLevel.ERROR, "Advertisement events subscription failed: ${failure.syncDescription()}")
        locked { discoveryEventsJob.also { discoveryEventsJob = null } }?.cancel()
        return
    }
    val monitor = scope.launch(start = CoroutineStart.LAZY) {
        try {
            events.collect { event -> contain("discovery event", Unit) { handleDiscoveryEvent(event, dependencies, radioId) } }
        } catch (failure: Exception) {
            failure.rethrowIfCallerCancelled()
            log(DebugLogLevel.ERROR, "Discovery event stream failed: ${failure.syncDescription()}")
        }
    }
    val previous = locked { discoveryEventsJob.also { discoveryEventsJob = monitor } }
    previous?.cancel()
    monitor.start()
}

private suspend fun SyncCoordinator.handleDiscoveryEvent(event: SyncDiscoveryEvent, dependencies: SyncDependencies, radioId: RadioId) {
    val notifications = dependencies.notificationService
    when (event) {
        is SyncDiscoveryEvent.NewContactDiscovered -> {
            logSink.emit(DebugLogLevel.DEBUG, "discover-trace", "B4 relay newContactDiscovered ${event.contactID} -> notifyContactsChanged")
            contain("postNewContactNotification", Unit) {
                notifications.postNewContactNotification(event.name, event.contactID, event.contactType)
            }
            notifyContactsChanged()
        }
        is SyncDiscoveryEvent.OrphanDirectMessagesAdopted -> {
            // These DMs arrived before their sender existed, so no banner fired at receipt. Post once per
            // contact now; the final badge refresh also covers muted contacts.
            for (contactID in event.contactIDs) postAdoptedDirectMessage(contactID, dependencies, radioId)
            contain("updateBadgeCount", Unit) { notifications.updateBadgeCount() }
        }
        SyncDiscoveryEvent.Other -> Unit
    }
}

private suspend fun SyncCoordinator.postAdoptedDirectMessage(contactID: UUID, dependencies: SyncDependencies, radioId: RadioId) {
    try {
        val key = EntityKey(radioId, contactID)
        val contact = dependencies.dataStore.fetchContact(key) ?: return
        if (contact.isBlocked) return
        val message = dependencies.dataStore.newestUnreadIncomingMessage(key) ?: return
        dependencies.notificationService.postDirectMessageNotification(
            contact.displayName, contactID, message.text, message.id, contact.isMuted,
        )
    } catch (failure: Exception) {
        failure.rethrowIfCallerCancelled()
        log(DebugLogLevel.ERROR, "Adopted DM notification failed for $contactID: ${failure.syncDescription()}")
    }
}

/** Cancels the discovery event task so it releases the service references it captures. */
fun SyncCoordinator.cancelDiscoveryEventMonitoring() {
    locked { discoveryEventsJob.also { discoveryEventsJob = null } }?.cancel()
}

/** Whether a discovery monitor is running (test visibility). */
internal val SyncCoordinator.isDiscoveryMonitoring: Boolean get() = locked { discoveryEventsJob?.isActive == true }

// MARK: - Static helpers

/**
 * Splits "NodeName: text" at the first `:` Character. Swift's `split` omits empty pieces, so a leading or
 * trailing colon yields no sender; both parts are trimmed with Foundation's `.whitespaces`.
 */
fun parseChannelMessage(text: String): ParsedChannelText {
    val parts = SyncSwiftText.split(text, ":", maxSplits = 1)
    if (parts.size > 1) {
        return ParsedChannelText(SyncSwiftText.trimmingWhitespaces(parts[0]), SyncSwiftText.trimmingWhitespaces(parts[1]))
    }
    return ParsedChannelText(null, text)
}

/**
 * A channel message only warrants a notification when it resolves to a known local channel: firmware can
 * attribute zero-key group traffic to an unconfigured slot, which has no openable chat.
 */
fun shouldPostChannelNotification(resolvedChannel: ChannelDTO?): Boolean = resolvedChannel != null
