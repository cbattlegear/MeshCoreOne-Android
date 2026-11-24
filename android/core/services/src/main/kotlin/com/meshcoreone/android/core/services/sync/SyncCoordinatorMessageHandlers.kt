// PortedFrom: MC1Services/Sources/MC1Services/Sync/SyncCoordinator+MessageHandlers.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.TextRulePersisting
import com.meshcoreone.android.core.contracts.domain.TextRuleAdmission
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
import com.meshcoreone.android.core.services.remote.IncomingRoomText
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
            guardHandler("contact message", propagateFailures = dependencies.dataStore is TextRulePersisting) {
                handleIncomingMessage(IncomingMessageKind.Direct(message, contact), context, dependencies, radioId, selfNodeName)
            }
        }
    }
    contain("setChannelMessageHandler", Unit) {
        polling.setChannelMessageHandler(beforeLookup = { message, context ->
            val text = parseChannelMessage(message.text)
            val custody = dependencies.incomingChannelReactions
            val parsed = if (custody != null && dependencies.codecs.meshCoreOpenParser.parse(text.messageText) == null &&
                dependencies.codecs.meshCoreOpenParser.parseV1(text.messageText) == null
            ) dependencies.reactionService.tryProcessAsReaction(text.messageText) else null
            if (parsed != null && custody != null) {
                custody.retain(ReceivedChannelReaction(message, null, SyncIncomingChannelReaction(
                    parsed, message.channelIndex, text.senderNodeName ?: "Unknown", text.messageText, radioId,
                    clock.now(), selfNodeName, context,
                )))
                suspend { recoverReceivedChannelReactions(dependencies, radioId) }
            } else null
        }) { message, channel, context ->
            guardHandler("channel message", propagateFailures = dependencies.dataStore is TextRulePersisting) {
                handleIncomingMessage(IncomingMessageKind.Channel(message, channel), context, dependencies, radioId, selfNodeName)
            }
        }
    }
    contain("setSignedMessageHandler", Unit) {
        polling.setSignedMessageHandler { message, _ ->
            guardHandler("signed message", propagateFailures = dependencies.dataStore is TextRulePersisting) {
                handleIncomingSignedMessage(message, dependencies, radioId)
            }
        }
    }
    contain("setCLIMessageHandler", Unit) {
        polling.setCLIMessageHandler { message, contact ->
            guardHandler("CLI message") { handleIncomingCLIMessage(message, contact, dependencies) }
        }
    }
    log(DebugLogLevel.INFO, "Message handlers wired successfully")
}

/** Native admission failures must reach the poller's retained-delivery queue. */
private suspend inline fun SyncCoordinator.guardHandler(
    label: String, propagateFailures: Boolean = false, block: () -> Unit,
) {
    if (propagateFailures) block() else contain("$label handler", Unit, block)
}

/** Re-enters only prerequisite reads; journal handoff removes work before any reaction mutation. */
suspend fun SyncCoordinator.recoverReceivedChannelReactions(dependencies: SyncDependencies, radioId: RadioId) {
    val custody = dependencies.incomingChannelReactions ?: return
    repeat(100) {
        val receive = custody.claim(radioId) ?: return
        var remains = false
        try {
            guardHandler("retained channel receive") {
                handleIncomingMessage(
                    IncomingMessageKind.Channel(receive.message, receive.channel), receive.reaction.deliveryContext,
                    dependencies, radioId, receive.reaction.localNodeName, receive,
                )
            }
        } finally {
            // A cancellation before the journal write is unattempted; an uncertain write stays quarantined.
            remains = custody.release(receive)
        }
        if (remains) return
    }
}

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

internal class PendingDirectTextReceive(
    val message: MessageDTO,
    val contact: ContactDTO?,
    val wireText: String,
    val wireTextType: UByte,
    val isReaction: Boolean,
    val receivedAt: Instant,
    val isViewingConversation: Boolean,
    val timestamp: UInt,
    val hasSelfMention: Boolean,
) {
    var admission: TextRuleAdmission? = null
    var indexed = false
    var contactsNotified = false
}

internal class PendingChannelTextReceive(
    val message: MessageDTO,
    val channel: ChannelDTO?,
    val wireText: String,
    val wireTextType: UByte,
    val receivedAt: Instant,
    val isViewingConversation: Boolean,
    val timestamp: UInt,
    val hasSelfMention: Boolean,
) {
    var admission: TextRuleAdmission? = null
    var observedIncarnation = false
    var incarnation: String? = null
    var harvested = false
    val publication = ChannelPublicationProgress()
}

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
    retained: ReceivedChannelReaction? = null,
) {
    val rules = dependencies.dataStore as? TextRulePersisting
    if (rules != null) {
        when (kind) {
            is IncomingMessageKind.Direct -> {
                val pending = locked { pendingDirectReceives[kind.message] }
                if (pending != null) {
                    resumeDirectTextReceive(kind.message, pending, rules, dependencies)
                    return
                }
            }
            is IncomingMessageKind.Channel -> {
                val pending = locked { pendingChannelReceives[kind.message] }
                if (pending != null) {
                    resumeChannelTextReceive(kind.message, pending, rules, dependencies)
                    return
                }
            }
        }
    }
    val capturedKind = if (rules != null && retained != null && kind is IncomingMessageKind.Channel && kind.channel == null) {
        kind.copy(channel = dependencies.dataStore.fetchChannel(radioId, kind.message.channelIndex))
    } else kind
    val resolvedKind = resolveDirectContactIfNeeded(capturedKind, dependencies, radioId)
    val wire = wireFields(resolvedKind)
    val codecs = dependencies.codecs
    val timestamp = wire.senderTimestamp.uint32Seconds()

    val receiveTime = retained?.reaction?.receivedAt ?: clock.now()
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
        requireReadSuccess = retained != null,
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

    if ((rules == null || resolvedKind is IncomingMessageKind.Channel) && recordArrivalAndSkipDuplicate(
            resolvedKind, deduplicationKey, radioId, rxResult, wire.snr, receiveTime, dependencies,
            retained?.let { { checkNotNull(dependencies.incomingChannelReactions).complete(it) } },
        )
    ) {
        return
    }

    if (resolvedKind is IncomingMessageKind.Channel && isBlockedSender(wire.senderNodeName)) {
        retained?.let { checkNotNull(dependencies.incomingChannelReactions).complete(it) }
        return
    }
    val isReaction = (resolvedKind !is IncomingMessageKind.Direct || resolvedKind.contact != null) &&
        (codecs.meshCoreOpenParser.parse(wire.text) != null ||
            codecs.meshCoreOpenParser.parseV1(wire.text) != null ||
            when (resolvedKind) {
                is IncomingMessageKind.Direct -> codecs.reactionParser.parseDM(wire.text) != null
                is IncomingMessageKind.Channel -> dependencies.reactionService.tryProcessAsReaction(wire.text) != null
            })
    val capturedContact = (resolvedKind as? IncomingMessageKind.Direct)?.contact
    val isViewingConversation = if (rules == null || isReaction) false else when (resolvedKind) {
        is IncomingMessageKind.Direct -> capturedContact != null &&
            contain("activeContactID", null) { dependencies.notificationService.activeContactID() } == capturedContact.id
        is IncomingMessageKind.Channel -> {
            val channel = resolvedKind.channel
            val active = if (channel != null) {
                contain("activeChannelOrigin", null) { dependencies.notificationService.activeChannelOrigin() }
            } else null
            active != null && channel != null && active.radioId == channel.radioId &&
                active.channelIndex == channel.index && active.expectedOccupantID == channel.occupantID
        }
    }
    if (rules != null && resolvedKind is IncomingMessageKind.Direct) {
        val pending = PendingDirectTextReceive(
            messageDTO, capturedContact, resolvedKind.message.text, wire.textTypeRaw, isReaction,
            clock.now(), isViewingConversation, timestamp, hasSelfMention,
        )
        locked { check(pendingDirectReceives.put(resolvedKind.message, pending) == null) }
        resumeDirectTextReceive(resolvedKind.message, pending, rules, dependencies)
        return
    }
    if (rules != null && resolvedKind is IncomingMessageKind.Channel && !isReaction && retained == null) {
        val pending = PendingChannelTextReceive(
            messageDTO, resolvedKind.channel, resolvedKind.message.text, wire.textTypeRaw,
            clock.now(), isViewingConversation, timestamp, hasSelfMention,
        )
        locked { check(pendingChannelReceives.put(resolvedKind.message, pending) == null) }
        resumeChannelTextReceive(resolvedKind.message, pending, rules, dependencies)
        return
    }
    val admission = rules?.admitTextMessage(
        messageDTO, capturedContact,
        (resolvedKind as? IncomingMessageKind.Channel)?.channel,
        when (resolvedKind) {
            is IncomingMessageKind.Direct -> resolvedKind.message.text
            is IncomingMessageKind.Channel -> resolvedKind.message.text
        }, wire.textTypeRaw, isReaction, clock.now(), isViewingConversation,
    )
    if (admission == TextRuleAdmission.DISCARDED || admission == TextRuleAdmission.REPLAY) {
        retained?.let { checkNotNull(dependencies.incomingChannelReactions).complete(it) }
        return
    }

    val admittedSaved = admission == TextRuleAdmission.SAVED || admission == TextRuleAdmission.SAVED_BLOCKED ||
        admission == TextRuleAdmission.SAVED_CHANNEL_STALE
    var directContact = if (rules != null && capturedContact != null) {
        rules.currentDirectTextContact(messageDTO, capturedContact, admittedSaved) ?: return
    } else capturedContact

    // Stamp lastHeard before the reaction early return so bodies and reactions both count.
    if (rules == null && directContact != null) {
        try {
            if (dependencies.dataStore.touchContactHeard(radioId, directContact.publicKey, clock.now())) {
                notifyContactsChanged()
            }
        } catch (failure: Exception) {
            failure.rethrowIfCallerCancelled()
            log(DebugLogLevel.ERROR, "lastHeard stamp failed for inbound DM: ${failure.syncDescription()}")
        }
    }

    when (resolvedKind) {
        is IncomingMessageKind.Direct -> {
            val contact = directContact
            if (contact != null && handleDMReaction(wire.text, contact, radioId, dependencies)) return
        }
        is IncomingMessageKind.Channel -> {
            if (isBlockedSender(wire.senderNodeName)) {
                retained?.let { checkNotNull(dependencies.incomingChannelReactions).complete(it) }
                return
            }
            if (handleChannelReaction(
                    wire.text, resolvedKind.message.channelIndex, wire.senderNodeName, selfNodeName, receiveTime,
                    radioId, dependencies, context, retained,
                )
            ) return
        }
    }

    try {
        val indexedMessage = if (admittedSaved) {
            if (resolvedKind is IncomingMessageKind.Channel) {
                checkNotNull(dependencies.dataStore.fetchChannelReactionMatch(EntityKey(radioId, messageDTO.id)))
            } else messageDTO
        } else if (resolvedKind is IncomingMessageKind.Channel) {
            dependencies.dataStore.saveReceivedChannelMessage(messageDTO)
        } else {
            dependencies.dataStore.saveMessage(messageDTO)
            messageDTO
        }
        val savedDTO = if (resolvedKind is IncomingMessageKind.Channel) {
            harvestAndRefreshChannelMessage(messageDTO, radioId, dependencies)
        } else messageDTO

        when (resolvedKind) {
            is IncomingMessageKind.Direct -> {
                directContact = indexAndNotifyDirectMessage(
                    savedDTO, directContact, wire.text, timestamp, hasSelfMention, dependencies, radioId,
                    rules.takeIf { admittedSaved }, admission != TextRuleAdmission.SAVED_BLOCKED,
                )
                if (capturedContact != null && directContact == null) return
            }
            is IncomingMessageKind.Channel -> indexAndNotifyChannelMessage(
                savedDTO.copy(channelReactionIncarnation = indexedMessage.channelReactionIncarnation),
                resolvedKind.channel, resolvedKind.message.channelIndex, wire.senderNodeName, wire.text,
                timestamp, hasSelfMention, dependencies, radioId, admittedSaved,
                admission != TextRuleAdmission.SAVED_CHANNEL_STALE,
            )
        }

        notifyConversationsChanged()
        if (resolvedKind is IncomingMessageKind.Direct && directContact != null) {
            dataEventBroadcaster.yield(SyncDataEvent.DirectMessageReceived(savedDTO, directContact))
        }
    } catch (failure: Exception) {
        failure.rethrowIfCallerCancelled()
        val noun = if (resolvedKind is IncomingMessageKind.Direct) "contact" else "channel"
        log(DebugLogLevel.ERROR, "Failed to save $noun message: ${failure.syncDescription()}")
    }
}

private suspend fun SyncCoordinator.resumeDirectTextReceive(
    wireMessage: ContactMessage,
    pending: PendingDirectTextReceive,
    rules: TextRulePersisting,
    dependencies: SyncDependencies,
) {
    fun complete() {
        locked { check(pendingDirectReceives.remove(wireMessage) === pending) }
    }
    val admission = locked { pending.admission } ?: rules.admitTextMessage(
        pending.message, pending.contact, null, pending.wireText, pending.wireTextType, pending.isReaction,
        pending.receivedAt, pending.isViewingConversation,
    ) { result -> locked { pending.admission = result } }
    if (admission == TextRuleAdmission.DISCARDED || admission == TextRuleAdmission.REPLAY) {
        complete()
        return
    }
    val saved = admission == TextRuleAdmission.SAVED || admission == TextRuleAdmission.SAVED_BLOCKED ||
        admission == TextRuleAdmission.SAVED_CHANNEL_STALE
    val captured = pending.contact
    val contact = if (captured != null) {
        rules.currentDirectTextContact(pending.message, captured, saved) ?: run { complete(); return }
    } else null
    if (contact != null && !pending.contactsNotified) {
        notifyContactsChanged()
        pending.contactsNotified = true
    }
    if (contact != null && handleDMReaction(pending.wireText, contact, pending.message.radioId, dependencies)) {
        complete()
        return
    }
    if (!saved) dependencies.dataStore.saveMessage(pending.message)
    val current = indexAndNotifyDirectMessage(
        pending.message, contact, pending.wireText, pending.timestamp, pending.hasSelfMention,
        dependencies, pending.message.radioId, rules.takeIf { saved },
        admission != TextRuleAdmission.SAVED_BLOCKED, indexed = locked { pending.indexed },
        onIndexed = { locked { pending.indexed = true } },
    )
    if (captured != null && current == null) {
        complete()
        return
    }
    notifyConversationsChanged()
    if (current != null) dataEventBroadcaster.yield(SyncDataEvent.DirectMessageReceived(pending.message, current))
    complete()
}

private suspend fun SyncCoordinator.resumeChannelTextReceive(
    wireMessage: ChannelMessage,
    pending: PendingChannelTextReceive,
    rules: TextRulePersisting,
    dependencies: SyncDependencies,
) {
    fun complete() {
        locked { check(pendingChannelReceives.remove(wireMessage) === pending) }
    }
    val admission = locked { pending.admission } ?: rules.admitTextMessage(
        pending.message, null, pending.channel, pending.wireText, pending.wireTextType, false,
        pending.receivedAt, pending.isViewingConversation,
    ) { result -> locked { pending.admission = result } }
    if (admission == TextRuleAdmission.DISCARDED || admission == TextRuleAdmission.REPLAY) {
        complete()
        return
    }
    check(admission == TextRuleAdmission.SAVED || admission == TextRuleAdmission.SAVED_CHANNEL_STALE)
    val expected = pending.message
    val key = EntityKey(expected.radioId, expected.id)
    suspend fun currentMessage(): MessageDTO? {
        val current = dependencies.dataStore.fetchChannelReactionMatch(key) ?: return null
        if (current.radioId != expected.radioId || current.id != expected.id ||
            current.channelIndex != expected.channelIndex || current.direction != expected.direction ||
            current.text != expected.text || current.textType != expected.textType ||
            current.senderNodeName != expected.senderNodeName || current.timestamp != expected.timestamp ||
            current.senderTimestamp != expected.senderTimestamp || current.createdAt != expected.createdAt ||
            current.deduplicationKey != expected.deduplicationKey) return null
        if (!pending.observedIncarnation) {
            pending.incarnation = current.channelReactionIncarnation
            pending.observedIncarnation = true
        } else if (pending.incarnation != current.channelReactionIncarnation) {
            return null
        }
        return current
    }
    var saved = currentMessage() ?: run { complete(); return }
    if (!pending.harvested) {
        harvestAndRefreshChannelMessage(expected, expected.radioId, dependencies)
        pending.harvested = true
        saved = currentMessage() ?: run { complete(); return }
    }
    indexAndNotifyChannelMessage(
        saved, pending.channel, wireMessage.channelIndex, expected.senderNodeName, expected.text,
        pending.timestamp, pending.hasSelfMention, dependencies, expected.radioId,
        bookkeepingCommitted = true, notificationEligible = admission != TextRuleAdmission.SAVED_CHANNEL_STALE,
        progress = pending.publication,
    )
    notifyConversationsChanged()
    complete()
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
    onConfirmedDuplicate: (() -> Unit)? = null,
): Boolean {
    val existing = try {
        dependencies.dataStore.fetchMessage(deduplicationKey, radioId) ?: return false
    } catch (failure: Exception) {
        failure.rethrowIfCallerCancelled()
        if (onConfirmedDuplicate != null) throw failure
        log(DebugLogLevel.WARNING, "Dedup check failed, proceeding with save: ${failure.syncDescription()}")
        return false
    }
    onConfirmedDuplicate?.invoke()
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

internal class PendingRoomTextReceive(val result: IncomingRoomText) {
    var recoveryPublished = false
}

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
    try {
        val rules = dependencies.dataStore as? TextRulePersisting
        val pending = locked { pendingRoomReceives[message] } ?: run {
            val result = dependencies.roomServerService.handleIncomingMessage(
                message.senderPublicKeyPrefix, message.senderTimestamp.uint32Seconds(), authorPrefix, message.text,
            ) { committed ->
                if (rules != null) locked { pendingRoomReceives[message] = PendingRoomTextReceive(committed) }
            } ?: return
            if (rules == null) PendingRoomTextReceive(result) else locked {
                pendingRoomReceives[message] ?: PendingRoomTextReceive(result).also { pendingRoomReceives[message] = it }
            }
        }
        fun complete() {
            if (rules != null) locked { check(pendingRoomReceives.remove(message) === pending) }
        }
        val result = pending.result
        if (!result.admission.saved && !result.admission.recovered) {
            complete()
            return
        }
        val saved = result.message
        val session = if (rules != null) {
            rules.currentRoomTextSession(result.room, saved.takeIf { result.admission.saved })
                ?: run { complete(); return }
        } else try {
            dependencies.dataStore.fetchRemoteNodeSession(EntityKey(radioId, saved.sessionID))
        } catch (failure: Exception) {
            failure.rethrowIfCallerCancelled()
            null
        }
        if (result.admission.recovered && !pending.recoveryPublished) {
            dependencies.roomServerService.publishIncomingRecovery(result.room)
            pending.recoveryPublished = true
        }
        if (!result.admission.saved) {
            complete()
            return
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
        complete()
    } catch (failure: Exception) {
        failure.rethrowIfCallerCancelled()
        if (dependencies.dataStore is TextRulePersisting) throw failure
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
