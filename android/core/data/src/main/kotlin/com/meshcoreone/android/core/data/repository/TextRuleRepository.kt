// AndroidOnly: WP-309 Issue #729; newer upstream receive and archive contracts at 5e980a53e077c45a1270170612fdf5dae97792ff.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class TextRuleRepository(
    private val context: RoomRepositoryContext,
    private val contacts: ContactRepository,
) : TextRulePersisting {
    override val textRuleChanges = context.database.textRules().observeArchive().map { Unit }
    private val loadFailed = MutableStateFlow(false)
    override val textRuleLoadFailed = loadFailed.asStateFlow()

    override suspend fun textRulesPreference(): TextRulesPreference = context.read("textRulesPreference") { preference() }

    private suspend fun RepositoryTransaction.preference(): TextRulesPreference {
        val raw = database.textRules().preference()?.payload ?: return TextRulesPreference().also { loadFailed.value = false }
        try {
            return TextRuleJson.preference(raw).also { loadFailed.value = false }
        } catch (failure: IllegalArgumentException) {
            loadFailed.value = true
            throw PersistenceStoreException(PersistenceStoreError.FetchFailed("Invalid text-rule preferences"), failure)
        } catch (failure: NoSuchElementException) {
            loadFailed.value = true
            throw PersistenceStoreException(PersistenceStoreError.FetchFailed("Incomplete text-rule preferences"), failure)
        }
    }

    private suspend fun RepositoryTransaction.receivePreference(): TextRulesPreference? = try {
        preference()
    } catch (failure: PersistenceStoreException) {
        // Upstream keeps receiving without rules when preference bytes cannot be decoded; never overwrite those bytes.
        if (!loadFailed.value) throw failure
        context.reporter.report("textRulesPreference", failure)
        null
    }

    private suspend fun RepositoryTransaction.touchReceiveContactHeard(row: ContactEntity, date: Instant): ContactEntity {
        if (date.truncatedUnixUIntOrNull() != null) return contacts.touchHeardInTransaction(this, row, date)
        context.reporter.report(
            "touchContactHeard",
            PersistenceStoreException(PersistenceStoreError.InvalidData,
                IllegalArgumentException("Phone-clock seconds do not fit UInt32")),
        )
        return row
    }

    override suspend fun updateTextRules(update: (TextRulesPreference) -> TextRulesPreference) =
        context.write("updateTextRules") {
            val next = update(preference())
            next.validate()
            database.textRules().preference(TextRulePreferenceEntity(payload = TextRuleJson.encode(next)))
            save()
        }

    private suspend fun RepositoryTransaction.inventory(): List<TextRuleConversation> {
        val result = mutableListOf<TextRuleConversation>()
        for (device in database.devices().all().distinctBy { it.radioId }) {
            val radio = RadioId(device.radioId)
            suspend fun add(family: String, id: UUID, fingerprint: String, kind: TextRuleKind, name: String) {
                val row = textRuleConversation(radio, family, id, fingerprint)
                result += TextRuleConversation(TextRuleKey(radio, row.identity), kind, name,
                    device.nodeName, TextRuleOverride.valueOf(row.override))
            }
            val channels = database.channels().forRadio(radio.value)
            for (row in channels) {
                if (!row.isEnabled || channels.count { it.index == row.index } != 1) continue
                val occupant = database.channelOccupants().forIndex(radio.value, row.index) ?: continue
                val dto = row.toDTO()
                add("channel", row.id, occupant.toString(), channelKind(dto), dto.name)
            }
            for (row in database.contacts().forRadio(radio.value)) if (row.toDTO().type == ContactType.CHAT) {
                add("contact", row.id, row.publicKey.hexString, TextRuleKind.DIRECT_MESSAGE, row.toDTO().displayName)
            }
            for (row in database.sessions().forRadio(radio.value)) if (row.toDTO().isRoom) {
                add("room", row.id, row.publicKey.hexString, TextRuleKind.ROOM, row.name)
            }
        }
        return result.sortedWith(compareBy<TextRuleConversation> { it.name }.thenBy { it.radioName })
    }

    override suspend fun textRuleConversations(): List<TextRuleConversation> =
        context.write("textRuleConversations") { inventory().also { save() } }

    override suspend fun setTextRuleOverride(key: TextRuleKey, value: TextRuleOverride) =
        context.write("setTextRuleOverride") {
            if (inventory().none { it.key == key }) invalidData("Text-rule conversation no longer exists")
            val row = database.textRules().conversations().single { it.radioId == key.radioId.value && it.identity == key.conversationID }
            database.textRules().conversation(row.copy(override = value.name))
            save()
        }

    override suspend fun recentlyBlockedTexts(): List<RecentlyBlockedText> =
        context.read("recentlyBlockedTexts") {
            database.textRules().archive().map { TextRuleJson.archived(it.payload).copy(contactID = it.contactID) }
        }

    private suspend fun RepositoryTransaction.suppress(
        identity: BlockedTextIdentity, kind: TextRuleKind, row: TextRuleConversationEntity?,
        body: String, sender: String?, name: String?, arrived: java.time.Instant,
        reaction: Boolean = false, bypass: Boolean = false, contactID: UUID? = null,
    ): Boolean {
        val dao = database.textRules()
        if (dao.archive().any { TextRuleJson.archived(it.payload).identity == identity }) return true
        if (bypass) return false
        val preference = receivePreference() ?: return false
        if (!preference.isEnabled(kind, row?.let { TextRuleKey(identity.radioId, it.identity) },
                row?.let { TextRuleOverride.valueOf(it.override) } ?: TextRuleOverride.INHERIT)) return false
        val hit = preference.firstMatch(if (reaction) null else body, sender) ?: return false
        if (!reaction) {
            val entry = RecentlyBlockedText(identity = identity, arrivedAt = arrived, body = body, sender = sender,
                conversationName = name, radioName = database.devices().forRadio(identity.radioId.value).firstOrNull()?.nodeName,
                hit = hit, contactID = contactID)
            dao.archive(RecentlyBlockedTextEntity(entry.id, identity.radioId.value, identity.conversationID,
                contactID, identity.senderPrefix, arrived.epochSecond, arrived.nano, TextRuleJson.encode(entry)))
            dao.archive().drop(100).forEach { dao.deleteArchive(it.id) }
            save()
        }
        return true
    }

    override suspend fun admitTextMessage(
        message: MessageDTO, contact: ContactDTO?, channel: ChannelDTO?,
        wireText: String, wireTextType: UByte, isReaction: Boolean,
        receivedAt: Instant, isViewingConversation: Boolean,
        onCommitted: (TextRuleAdmission) -> Unit,
    ): TextRuleAdmission = context.write("admitTextMessage") {
        fun committed(value: TextRuleAdmission): TextRuleAdmission = value.also {
            afterCommit { onCommitted(value) }
        }
        val radio = message.radioId
        if (database.devices().forRadio(radio.value).isEmpty()) return@write committed(TextRuleAdmission.DISCARDED)
        val index = message.channelIndex
        val currentContactRow = contact?.let { database.contacts().byId(radio.value, it.id) }
        val currentContact = currentContactRow?.toDTO()
        if (contact != null && contact.receiveConversationID == null) {
            invalidData("Incoming contact snapshot has no conversation incarnation")
        }
        val contactConversation = contact?.let { database.textRules().conversation(radio.value, "contact", it.id) }
        if (contact != null && (currentContact == null || currentContact.publicKey != contact.publicKey ||
                contact.radioId != radio || message.contactID != contact.id || index != null ||
                contactConversation?.identity != contact.receiveConversationID)) {
            return@write committed(TextRuleAdmission.DISCARDED)
        }
        val currentChannelRow = channel?.let { database.channels().byId(radio.value, it.id) }
        val currentChannel = currentChannelRow?.toDTO()
        val occupant = index?.let { database.channelOccupants().forIndex(radio.value, it.toLong()) }
        if (channel != null && (currentChannel == null || !currentChannel.isEnabled ||
                currentChannel.index != index ||
                (occupant != null && channel.occupantID != null && occupant != channel.occupantID) ||
                database.channels().forIndex(radio.value, currentChannel.index.toLong()).size != 1)) {
            return@write committed(TextRuleAdmission.DISCARDED)
        }
        val stamp = message.senderTimestamp ?: message.timestamp
        if (index != null && message.deduplicationKey?.let {
                database.messages().forDeduplicationKey(radio.value, it).isNotEmpty()
            } == true) return@write committed(TextRuleAdmission.REPLAY)
        // A DM's contact UUID can change after orphan adoption; its wire identity cannot.
        val prefix = message.senderKeyPrefix
        if (index == null && prefix?.size == 6 &&
            database.messages().directWireCandidates(radio.value, prefix, stamp.toLong()).any {
                it.directionRawValue == MessageDirection.INCOMING.rawValue && it.channelIndex == null &&
                    it.senderKeyPrefix == prefix && it.text == wireText &&
                    it.textTypeRawValue == message.textType.rawValue.toLong() &&
                    (it.senderTimestamp ?: it.timestamp.takeUnless { _ -> it.timestampCorrected }) == stamp.toLong()
            }) return@write committed(TextRuleAdmission.REPLAY)

        val staleChannel = channel != null && (channel.radioId != radio ||
            currentChannelRow?.secret != channel.secret || occupant != channel.occupantID)
        val row = when {
            index != null && !staleChannel && occupant != null && occupant == channel?.occupantID -> currentChannel?.let {
                textRuleConversation(radio, "channel", it.id, occupant.toString())
            }
            index == null -> contactConversation
            else -> null
        }
        val uniqueSender = if (index == null && prefix?.size == 6) {
            database.contacts().forPrefix(radio.value, prefix, 6).singleOrNull()?.toDTO()
        } else null
        val (originalSender, originalBody) = if (index != null) originalChannelFields(wireText)
            else uniqueSender?.displayName to wireText
        val identity = BlockedTextIdentity(radio, if (index != null) row?.identity else null,
            prefix, stamp, wireTextType, wireText)
        val kind = if (row != null && index != null) currentChannel?.let(::channelKind)
            else if (index == null) TextRuleKind.DIRECT_MESSAGE else null
        if (kind != null && suppress(identity, kind, row, originalBody, originalSender,
                currentChannel?.name ?: currentContact?.displayName, message.createdAt, isReaction,
                bypass = currentContact?.isBlocked == true, contactID = uniqueSender?.id)) {
            return@write committed(TextRuleAdmission.DISCARDED)
        }
        if (isReaction) {
            if (currentContactRow != null) {
                touchReceiveContactHeard(currentContactRow, receivedAt)
                save()
            }
            return@write committed(TextRuleAdmission.REACTION)
        }
        database.messages().upsert(message.toEntity())
        if (index == null && currentContactRow != null) {
            val heard = touchReceiveContactHeard(currentContactRow, receivedAt)
            val incrementUnread = !heard.isBlocked && !isViewingConversation
            database.contacts().upsert(heard.copy(
                lastMessageDate = StoredInstant.from(receivedAt),
                unreadCount = if (incrementUnread) checkedIncrement(heard.unreadCount) else heard.unreadCount,
                unreadMentionCount = if (incrementUnread && message.containsSelfMention) {
                    checkedIncrement(heard.unreadMentionCount)
                } else heard.unreadMentionCount,
            ))
        } else if (index != null && currentChannelRow != null && !staleChannel) {
            database.channels().upsert(currentChannelRow.copy(
                lastMessageDate = StoredInstant.from(receivedAt),
                unreadCount = if (!isViewingConversation) checkedIncrement(currentChannelRow.unreadCount)
                    else currentChannelRow.unreadCount,
                unreadMentionCount = if (!isViewingConversation && message.containsSelfMention) {
                    checkedIncrement(currentChannelRow.unreadMentionCount)
                } else currentChannelRow.unreadMentionCount,
            ))
        }
        save()
        committed(when {
            index == null && currentContact?.isBlocked == true -> TextRuleAdmission.SAVED_BLOCKED
            index != null && staleChannel -> TextRuleAdmission.SAVED_CHANNEL_STALE
            else -> TextRuleAdmission.SAVED
        })
    }

    override suspend fun currentDirectTextContact(
        message: MessageDTO, contact: ContactDTO, messageSaved: Boolean,
    ): ContactDTO? = context.read("currentDirectTextContact") {
        if (contact.receiveConversationID == null) invalidData("Incoming contact snapshot has no conversation incarnation")
        if (contact.radioId != message.radioId || message.contactID != contact.id || message.channelIndex != null) {
            return@read null
        }
        val row = database.contacts().byId(message.radioId.value, contact.id) ?: return@read null
        val conversation = database.textRules().conversation(message.radioId.value, "contact", contact.id)
        if (row.publicKey != contact.publicKey || conversation?.identity != contact.receiveConversationID) return@read null
        if (messageSaved) {
            val stored = database.messages().byId(message.radioId.value, message.id) ?: return@read null
            if (stored.contactID != contact.id || stored.channelIndex != null ||
                stored.directionRawValue != MessageDirection.INCOMING.rawValue) return@read null
        }
        row.toDTO().copy(receiveConversationID = contact.receiveConversationID)
    }

    override suspend fun admitRoomText(
        message: RoomMessageDTO, room: RemoteNodeSessionDTO, onCommitted: (TextRuleRoomAdmission) -> Unit,
    ): TextRuleRoomAdmission =
        context.write("admitRoomText") {
            fun committed(value: TextRuleRoomAdmission): TextRuleRoomAdmission = value.also {
                afterCommit { onCommitted(value) }
            }
            if (room.receiveConversationID == null) invalidData("Incoming room snapshot has no conversation incarnation")
            val stored = database.sessions().byId(room.radioId.value, room.id)
                ?: return@write committed(TextRuleRoomAdmission(false, false))
            val current = stored.toDTO()
            val row = database.textRules().conversation(room.radioId.value, "room", room.id)
            if (message.sessionID != room.id || current.publicKey != room.publicKey || !current.isRoom ||
                row == null || row.identity != room.receiveConversationID ||
                database.devices().forRadio(room.radioId.value).isEmpty()) return@write committed(TextRuleRoomAdmission(false, false))
            suspend fun recover(): TextRuleRoomAdmission {
                if (!stored.isConnected) {
                    database.sessions().upsert(stored.copy(isConnected = true))
                    save()
                }
                return TextRuleRoomAdmission(false, !stored.isConnected)
            }
            if (database.roomMessages().duplicateExact(
                    room.radioId.value, room.id, message.deduplicationKey, message.timestamp.toLong(),
                    message.authorKeyPrefix, message.text,
                ))                 return@write committed(recover())
            val matchingSender = database.contacts().forPrefix(room.radioId.value, message.authorKeyPrefix,
                message.authorKeyPrefix.size).singleOrNull()?.toDTO()?.displayName
            val identity = BlockedTextIdentity(room.radioId, row.identity, message.authorKeyPrefix,
                message.timestamp, TextType.SIGNED_PLAIN.rawValue, message.text)
            if (suppress(identity, TextRuleKind.ROOM, row, message.text, matchingSender, current.name,
                    message.createdAt, bypass = message.isFromSelf)) return@write committed(recover())
            database.roomMessages().upsert(message.toEntity(room.radioId))
            database.sessions().upsert(stored.copy(isConnected = true,
                lastSyncTimestamp = maxOf(stored.lastSyncTimestamp, message.timestamp.toLong()),
                lastMessageDate = StoredInstant.from(message.createdAt),
                unreadCount = if (message.isFromSelf) stored.unreadCount else checkedIncrement(stored.unreadCount)))
            save()
            committed(TextRuleRoomAdmission(true, !stored.isConnected))
        }

    override suspend fun currentRoomTextSession(room: RemoteNodeSessionDTO, message: RoomMessageDTO?): RemoteNodeSessionDTO? =
        context.read("currentRoomTextSession") {
            if (room.receiveConversationID == null) invalidData("Incoming room snapshot has no conversation incarnation")
            val stored = database.sessions().byId(room.radioId.value, room.id) ?: return@read null
            val current = stored.toDTO()
            val conversation = database.textRules().conversation(room.radioId.value, "room", room.id)
            if (!current.isRoom || current.publicKey != room.publicKey ||
                conversation?.identity != room.receiveConversationID ||
                database.devices().forRadio(room.radioId.value).isEmpty()) return@read null
            if (message != null) {
                if (message.sessionID != room.id) return@read null
                val saved = database.roomMessages().byId(room.radioId.value, message.id) ?: return@read null
                if (saved.sessionID != room.id || saved.authorKeyPrefix != message.authorKeyPrefix ||
                    saved.timestamp != message.timestamp.toLong() || saved.text != message.text ||
                    saved.deduplicationKey != message.deduplicationKey) return@read null
            }
            current.copy(receiveConversationID = room.receiveConversationID)
        }

    private fun channelKind(channel: ChannelDTO): TextRuleKind = when {
        channel.isPublicChannel -> TextRuleKind.PUBLIC_CHANNEL
        channel.name.startsWith("#") -> TextRuleKind.HASHTAG_CHANNEL
        else -> TextRuleKind.PRIVATE_CHANNEL
    }

    private fun originalChannelFields(text: String): Pair<String?, String> {
        val characters = Pattern.compile("\\X").matcher(text)
        while (characters.find()) {
            if (characters.group() != ":") continue
            if (characters.start() == 0) return null to text
            val sender = text.substring(0, characters.start())
            var bodyStart = characters.end()
            if (characters.find() && characters.group() == " ") bodyStart = characters.end()
            return sender to text.substring(bodyStart)
        }
        return null to text
    }
}

internal suspend fun RepositoryTransaction.textRuleConversation(
    radio: RadioId, family: String, parent: UUID, fingerprint: String,
): TextRuleConversationEntity {
    val dao = database.textRules()
    val existing = dao.conversation(radio.value, family, parent)
    if (existing?.fingerprint == fingerprint) return existing
    val row = TextRuleConversationEntity(radio.value, family, parent, fingerprint,
        UUID.randomUUID(), TextRuleOverride.INHERIT.name)
    dao.conversation(row)
    return row
}
