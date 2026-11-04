// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/MessagePersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/HeardRepeatPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/ReactionPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Messages.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+FailedSends.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID

internal class MessageRepository(
    private val context: RoomRepositoryContext,
    pending: PendingSendRepository,
) : MessagePersisting, PendingSendPersisting by pending, HeardRepeatPersisting, ReactionPersisting, FailedSendPersisting {
    override suspend fun createOrReconcilePendingDirectMessage(
        intent: PendingDirectMessageIntent, timestamp: UInt, createdAt: Instant,
    ): MessageDTO = context.write("createOrReconcilePendingDirectMessage") {
        val rows = database.messages().forIdentity(intent.messageID)
        if (rows.isNotEmpty()) {
            val row = rows.singleOrNull()
                ?: invalidData("Direct-message identity is occupied on multiple radios")
            if (row.radioId != intent.radioId.value || row.contactID != intent.contactID ||
                row.channelIndex != null || row.directionRawValue != MessageDirection.OUTGOING.rawValue ||
                row.text != intent.text || row.textTypeRawValue != intent.textType.rawValue.toLong() ||
                row.replyToID != intent.replyToID
            ) invalidData("Direct-message identity conflicts with the stored intent")
            return@write row.toDTO()
        }
        val row = MessageDTO(
            id = intent.messageID, radioId = intent.radioId, contactID = intent.contactID,
            text = intent.text, textType = intent.textType, replyToID = intent.replyToID,
            timestamp = timestamp, createdAt = createdAt,
        ).toEntityPreservingTimestamp()
        database.messages().insert(row)
        save()
        database.messages().byId(intent.radioId.value, intent.messageID)?.toDTO()
            ?: invalidData("Inserted direct message is missing")
    }

    override suspend fun isDuplicateMessage(deduplicationKey: String, radioId: RadioId): Boolean =
        context.read("isDuplicateMessage") { database.messages().forDeduplicationKey(radioId.value, deduplicationKey).isNotEmpty() }

    override suspend fun fetchMessage(deduplicationKey: String, radioId: RadioId): MessageDTO? =
        context.read("fetchMessageByDeduplicationKey") {
            database.messages().forDeduplicationKey(radioId.value, deduplicationKey).firstOrNull()?.toDTO()
        }

    override suspend fun saveMessage(dto: MessageDTO) = context.write("saveMessage") {
        database.messages().upsert(dto.toEntity())
        save()
    }

    override suspend fun fetchMessage(key: EntityKey): MessageDTO? = context.read("fetchMessage") {
        database.messages().byId(key.radioId.value, key.id)?.toDTO()
    }

    override suspend fun fetchMessages(contact: EntityKey, limit: Long, offset: Long): SnapshotList<MessageDTO> =
        context.read("fetchContactMessages") {
            queryBounds(limit, offset)
            displayMessages(database.messages().newestForContact(contact.radioId.value, contact.id, limit, offset))
        }

    override suspend fun fetchMessages(radioId: RadioId, channelIndex: UByte, limit: Long, offset: Long): SnapshotList<MessageDTO> =
        context.read("fetchChannelMessages") {
            queryBounds(limit, offset)
            displayMessages(database.messages().newestForChannel(radioId.value, channelIndex.toLong(), limit, offset))
        }

    override suspend fun newestUnreadIncomingMessage(contact: EntityKey): MessageDTO? =
        context.read("newestUnreadIncomingMessage") {
            database.messages().newestUnreadIncoming(contact.radioId.value, contact.id)?.toDTO()
        }

    override suspend fun fetchMessageWindow(contact: EntityKey, anchorSortDate: Instant?, floorLimit: Long): MessageWindow =
        context.read("fetchContactMessageWindow") {
            queryBounds(floorLimit)
            val atAnchor = anchorSortDate?.let {
                database.messages().countContactAtOrAfter(contact.radioId.value, contact.id, it.epochSecond, it.nano)
            } ?: 0
            val limit = maxOf(floorLimit, atAnchor)
            window(database.messages().newestForContact(contact.radioId.value, contact.id, checkedIncrement(limit)), limit)
        }

    override suspend fun fetchMessageWindow(
        radioId: RadioId, channelIndex: UByte, anchorSortDate: Instant?, floorLimit: Long,
    ): MessageWindow = context.read("fetchChannelMessageWindow") {
        queryBounds(floorLimit)
        val atAnchor = anchorSortDate?.let {
            database.messages().countChannelAtOrAfter(radioId.value, channelIndex.toLong(), it.epochSecond, it.nano)
        } ?: 0
        val limit = maxOf(floorLimit, atAnchor)
        window(database.messages().newestForChannel(radioId.value, channelIndex.toLong(), checkedIncrement(limit)), limit)
    }

    private fun displayMessages(newestFirst: List<MessageEntity>): SnapshotList<MessageDTO> =
        MessageDTO.reorderSameSenderClusters(newestFirst.asReversed().map { it.toDTO() })

    private fun window(rows: List<MessageEntity>, limit: Long): MessageWindow {
        val hasMore = rows.size.toLong() > limit
        return MessageWindow(displayMessages(if (hasMore) rows.dropLast(1) else rows), hasMore)
    }

    override suspend fun fetchLastMessages(
        contacts: SnapshotList<EntityKey>, limit: Long,
    ): SnapshotMap<EntityKey, SnapshotList<MessageDTO>> = context.read("fetchLastMessages") {
        queryBounds(limit)
        val result = LinkedHashMap<EntityKey, SnapshotList<MessageDTO>>()
        for (contact in contacts) {
            result[contact] = displayMessages(database.messages().newestForContact(contact.radioId.value, contact.id, limit))
        }
        result.snapshotMap()
    }

    override suspend fun fetchLastChannelMessages(
        channels: SnapshotList<ChannelQuery>, limit: Long,
    ): SnapshotMap<EntityKey, SnapshotList<MessageDTO>> = context.read("fetchLastChannelMessages") {
        queryBounds(limit)
        val result = LinkedHashMap<EntityKey, SnapshotList<MessageDTO>>()
        for (channel in channels) {
            result[EntityKey(channel.radioId, channel.id)] =
                displayMessages(database.messages().newestForChannel(channel.radioId.value, channel.channelIndex.toLong(), limit))
        }
        result.snapshotMap()
    }

    override suspend fun fetchChannelMessageCandidates(
        radioId: RadioId, channelIndex: UByte, timestampWindow: ClosedRange<UInt>, limit: Long,
    ): SnapshotList<MessageDTO> = context.read("fetchChannelMessageCandidates") {
        queryBounds(limit)
        database.messages().channelCandidates(
            radioId.value, channelIndex.toLong(), timestampWindow.start.toLong(), timestampWindow.endInclusive.toLong(), limit,
        ).map { it.toDTO() }.snapshot()
    }

    override suspend fun fetchDMMessageCandidates(
        contact: EntityKey, timestampWindow: ClosedRange<UInt>, limit: Long,
    ): SnapshotList<MessageDTO> = context.read("fetchDMMessageCandidates") {
        queryBounds(limit)
        database.messages().directCandidates(
            contact.radioId.value, contact.id, timestampWindow.start.toLong(), timestampWindow.endInclusive.toLong(), limit,
        ).map { it.toDTO() }.snapshot()
    }

    override suspend fun findChannelMessageForReaction(
        radioId: RadioId, channelIndex: UByte, parsedReaction: ParsedReaction, localNodeName: String?,
        timestampWindow: ClosedRange<UInt>, limit: Long,
    ): MessageDTO? {
        val candidates = fetchChannelMessageCandidates(radioId, channelIndex, timestampWindow, limit)
        return candidates.firstOrNull { candidate ->
            val senderMatches = if (candidate.isOutgoing) localNodeName != null && localNodeName == parsedReaction.targetSender
                else candidate.senderNodeName == parsedReaction.targetSender
            senderMatches && RepositoryReactionPolicy.messageHash(candidate.text, candidate.reactionTimestamp) == parsedReaction.messageHash
        }
    }

    override suspend fun findDMMessageForReaction(
        contact: EntityKey, messageHash: String, timestampWindow: ClosedRange<UInt>, limit: Long,
    ): MessageDTO? = fetchDMMessageCandidates(contact, timestampWindow, limit).firstOrNull {
        !RepositoryReactionPolicy.isDirectReaction(it.text) &&
            RepositoryReactionPolicy.messageHash(it.text, it.reactionTimestamp) == messageHash
    }

    override suspend fun updateMessageStatus(key: EntityKey, status: MessageStatus) =
        context.write("updateMessageStatus") {
            if (database.messages().setStatus(key.radioId.value, key.id, status.rawValue) > 0) save()
        }

    override suspend fun updateMessageStatusUnlessDelivered(key: EntityKey, status: MessageStatus): Boolean =
        context.write("updateMessageStatusUnlessDelivered") {
            val changed = database.messages().setStatusUnlessDelivered(key.radioId.value, key.id, status.rawValue) > 0
            if (changed) save()
            changed
        }

    override suspend fun clearRetryingToSent(key: EntityKey): Boolean = context.write("clearRetryingToSent") {
        val changed = database.messages().clearRetryingToSent(key.radioId.value, key.id) > 0
        if (changed) save()
        changed
    }

    override suspend fun hasOutgoingSentDM(radioId: RadioId, ackCode: UInt): Boolean = context.read("hasOutgoingSentDM") {
        database.messages().hasOutgoingSentDM(radioId.value, ackCode.toLong())
    }

    override suspend fun updateMessageAck(key: EntityKey, ackCode: UInt, status: MessageStatus, roundTripTime: UInt?) =
        context.write("updateMessageAck") {
            if (database.messages().setAck(key.radioId.value, key.id, ackCode.toLong(), status.rawValue, roundTripTime?.toLong()) > 0) save()
        }

    override suspend fun updateMessageRetryStatus(
        key: EntityKey, status: MessageStatus, retryAttempt: Long, maxRetryAttempts: Long,
    ) = context.write("updateMessageRetryStatus") {
        if (database.messages().setRetryStatus(key.radioId.value, key.id, status.rawValue, retryAttempt, maxRetryAttempts) > 0) save()
    }

    override suspend fun updateMessageTimestamp(key: EntityKey, timestamp: UInt) =
        mutateMessage("updateMessageTimestamp", key) { it.copy(timestamp = timestamp.toLong()) }
    override suspend fun updateMessageHeardRepeats(key: EntityKey, heardRepeats: Long) =
        mutateMessage("updateMessageHeardRepeats", key) { it.copy(heardRepeats = heardRepeats) }
    override suspend fun markMessageAsRead(key: EntityKey) =
        mutateMessage("markMessageAsRead", key) { it.copy(isRead = true) }

    override suspend fun updateMessageLinkPreview(
        key: EntityKey, url: String?, title: String?, imageData: Bytes?, iconData: Bytes?, fetched: Boolean,
    ) = mutateMessage("updateMessageLinkPreview", key) {
        it.copy(linkPreviewURL = url, linkPreviewTitle = title, linkPreviewImageData = imageData,
            linkPreviewIconData = iconData, linkPreviewFetched = fetched)
    }

    override suspend fun deleteMessage(key: EntityKey) = context.write("deleteMessage") {
        database.pendingSends().deleteForMessage(key.radioId.value, key.id)
        if (database.messages().byId(key.radioId.value, key.id) != null) {
            database.reactions().deleteForMessage(key.radioId.value, key.id)
            database.messages().delete(key.radioId.value, key.id)
        }
        save()
    }

    override suspend fun countPendingMessages(radioId: RadioId): Long = context.read("countPendingMessages") {
        database.messages().countPending(radioId.value)
    }

    override suspend fun findSentChannelMessage(
        radioId: RadioId, channelIndex: UByte, timestamp: UInt, text: String,
    ): MessageDTO? = context.read("findSentChannelMessage") {
        database.messages().sentChannelMessage(radioId.value, channelIndex.toLong(), timestamp.toLong(), text)?.toDTO()
    }

    override suspend fun saveMessageRepeat(radioId: RadioId, dto: MessageRepeatDTO) = context.write("saveMessageRepeat") {
        if (database.messages().byId(radioId.value, dto.messageID) == null) {
            throw PersistenceStoreException(PersistenceStoreError.MessageNotFound)
        }
        database.repeats().upsert(dto.toEntity(radioId, dto.messageID))
        save()
    }

    override suspend fun fetchMessageRepeats(message: EntityKey): SnapshotList<MessageRepeatDTO> =
        context.read("fetchMessageRepeats") {
            database.repeats().forMessage(message.radioId.value, message.id).map { it.toDTO() }.snapshot()
        }

    override suspend fun deleteMessageRepeats(message: EntityKey) = context.write("deleteMessageRepeats") {
        database.repeats().deleteForMessage(message.radioId.value, message.id)
        save()
    }

    override suspend fun messageRepeatExists(rxLogEntry: EntityKey): Boolean = context.read("messageRepeatExists") {
        database.repeats().existsForRxLog(rxLogEntry.radioId.value, rxLogEntry.id)
    }

    override suspend fun incrementMessageHeardRepeats(key: EntityKey): Long = context.write("incrementMessageHeardRepeats") {
        val row = database.messages().byId(key.radioId.value, key.id) ?: return@write 0
        val next = checkedIncrement(row.heardRepeats)
        database.messages().upsert(row.copy(heardRepeats = next))
        save()
        next
    }

    override suspend fun adoptIncomingPathIfUnknown(key: EntityKey, pathNodes: Bytes, pathLength: UByte): Boolean =
        context.write("adoptIncomingPathIfUnknown") {
            val changed = database.messages().adoptPathIfUnknown(key.radioId.value, key.id, pathNodes, pathLength.toLong()) > 0
            if (changed) save()
            changed
        }

    override suspend fun incrementMessageSendCount(key: EntityKey): Long = context.write("incrementMessageSendCount") {
        val row = database.messages().byId(key.radioId.value, key.id) ?: return@write 0
        val next = checkedIncrement(row.sendCount)
        database.messages().upsert(row.copy(sendCount = next))
        save()
        next
    }

    override suspend fun fetchReactions(message: EntityKey, limit: Long): SnapshotList<ReactionDTO> =
        context.read("fetchReactions") {
            queryBounds(limit)
            database.reactions().forMessage(message.radioId.value, message.id, limit).map { it.toDTO() }.snapshot()
        }

    override suspend fun saveReaction(dto: ReactionDTO) = context.write("saveReaction") {
        database.reactions().upsert(dto.toEntity())
        save()
    }

    override suspend fun reactionExists(message: EntityKey, senderName: String, emoji: String): Boolean =
        context.read("reactionExists") { database.reactions().exists(message.radioId.value, message.id, senderName, emoji) }

    override suspend fun updateMessageReactionSummary(message: EntityKey, summary: String?) =
        mutateMessage("updateMessageReactionSummary", message) { it.copy(reactionSummary = summary) }

    override suspend fun deleteReactionsForMessage(message: EntityKey) = context.write("deleteReactionsForMessage") {
        database.reactions().deleteForMessage(message.radioId.value, message.id)
        save()
    }

    override suspend fun fetchFailedSendConversationKeys(radioId: RadioId): FailedSendConversationKeys =
        context.read("fetchFailedSendConversationKeys") {
            FailedSendConversationKeys(
                database.messages().failedContactIDs(radioId.value).snapshotSet(),
                database.messages().failedChannelIDs(radioId.value).snapshotSet(),
                database.roomMessages().failedSessionIDs(radioId.value).snapshotSet(),
            )
        }

    override suspend fun markFailedSendsSeen(contact: EntityKey) = context.write("markContactFailedSendsSeen") {
        if (database.messages().markContactFailuresSeen(contact.radioId.value, contact.id) > 0) save()
    }

    override suspend fun markFailedSendsSeen(radioId: RadioId, channelIndex: UByte) =
        context.write("markChannelFailedSendsSeen") {
            if (database.messages().markChannelFailuresSeen(radioId.value, channelIndex.toLong()) > 0) save()
        }

    override suspend fun markRoomFailedSendsSeen(session: EntityKey) = context.write("markRoomFailedSendsSeen") {
        if (database.roomMessages().markFailuresSeen(session.radioId.value, session.id) > 0) save()
    }

    private suspend fun mutateMessage(
        operation: String, key: EntityKey, mutation: (MessageEntity) -> MessageEntity,
    ) = context.write(operation) {
        val row = database.messages().byId(key.radioId.value, key.id) ?: return@write
        database.messages().upsert(mutation(row))
        save()
    }
}
