// AndroidOnly: WP-309 Issue #729; newer upstream receive and archive contracts at 5e980a53e077c45a1270170612fdf5dae97792ff.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.UUID
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class TextRuleRepository(private val context: RoomRepositoryContext) : TextRulePersisting {
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

    override suspend fun updateTextRules(update: (TextRulesPreference) -> TextRulesPreference) =
        context.write("updateTextRules") {
            val next = update(preference())
            next.validate()
            database.textRules().preference(TextRulePreferenceEntity(payload = TextRuleJson.encode(next)))
            save()
        }

    private suspend fun RepositoryTransaction.conversation(
        radio: RadioId, family: String, parent: UUID, fingerprint: String,
    ): TextRuleConversationEntity {
        val dao = database.textRules()
        val existing = dao.conversations().firstOrNull {
            it.radioId == radio.value && it.family == family && it.parentID == parent
        }
        if (existing?.fingerprint == fingerprint) return existing
        val row = TextRuleConversationEntity(radio.value, family, parent, fingerprint,
            UUID.randomUUID(), TextRuleOverride.INHERIT.name)
        dao.conversation(row)
        return row
    }

    private suspend fun RepositoryTransaction.inventory(): List<TextRuleConversation> {
        val result = mutableListOf<TextRuleConversation>()
        for (device in database.devices().all().distinctBy { it.radioId }) {
            val radio = RadioId(device.radioId)
            suspend fun add(family: String, id: UUID, fingerprint: String, kind: TextRuleKind, name: String) {
                val row = conversation(radio, family, id, fingerprint)
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
    ): TextRuleAdmission = context.write("admitTextMessage") {
        val radio = message.radioId
        if (database.devices().forRadio(radio.value).isEmpty()) return@write TextRuleAdmission.DISCARDED
        val index = message.channelIndex
        val currentContact = contact?.let { database.contacts().byId(radio.value, it.id)?.toDTO() }
        if (contact != null && (currentContact == null || currentContact.publicKey != contact.publicKey)) {
            return@write TextRuleAdmission.DISCARDED
        }
        val currentChannel = channel?.let { database.channels().byId(radio.value, it.id)?.toDTO() }
        val occupant = index?.let { database.channelOccupants().forIndex(radio.value, it.toLong()) }
        if (channel != null && (currentChannel == null || !currentChannel.isEnabled ||
                currentChannel.index != index || occupant == null || occupant != channel.occupantID ||
                database.channels().forIndex(radio.value, currentChannel.index.toLong()).size != 1)) {
            return@write TextRuleAdmission.DISCARDED
        }
        val stamp = message.senderTimestamp ?: message.timestamp
        val saved = message.deduplicationKey?.let { database.messages().forDeduplicationKey(radio.value, it).firstOrNull() }
        if (saved != null) return@write TextRuleAdmission.REPLAY
        // A DM's contact UUID can change after orphan adoption; its wire identity cannot.
        if (index == null && message.senderKeyPrefix?.size == 6 &&
            database.messages().directWireCandidates(radio.value, message.senderKeyPrefix, stamp.toLong()).any {
                it.directionRawValue == MessageDirection.INCOMING.rawValue && it.channelIndex == null &&
                    it.senderKeyPrefix == message.senderKeyPrefix && it.text == wireText &&
                    it.textTypeRawValue == message.textType.rawValue.toLong() &&
                    (it.senderTimestamp ?: it.timestamp.takeUnless { _ -> it.timestampCorrected }) == stamp.toLong()
            }) return@write TextRuleAdmission.REPLAY

        val row = if (index != null) currentChannel?.let {
            conversation(radio, "channel", it.id, checkNotNull(occupant).toString())
        } else currentContact?.let { conversation(radio, "contact", it.id, it.publicKey.hexString) }
        val prefix = message.senderKeyPrefix
        val uniqueSender = if (index == null && prefix?.size == 6) {
            database.contacts().forPrefix(radio.value, prefix, 6).singleOrNull()?.toDTO()
        } else null
        val separator = wireText.indexOf(':')
        val originalSender = if (index != null && separator > 0) wireText.substring(0, separator) else uniqueSender?.displayName
        val originalBody = if (index != null && separator > 0) wireText.substring(separator + 1).removePrefix(" ") else wireText
        val identity = BlockedTextIdentity(radio, if (index != null) row?.identity else null,
            prefix, stamp, wireTextType, wireText)
        val kind = currentChannel?.let(::channelKind) ?: if (index == null) TextRuleKind.DIRECT_MESSAGE else null
        if (kind != null && suppress(identity, kind, row, originalBody, originalSender,
                currentChannel?.name ?: currentContact?.displayName, message.createdAt, isReaction,
                bypass = currentContact?.isBlocked == true, contactID = uniqueSender?.id)) {
            return@write TextRuleAdmission.DISCARDED
        }
        if (isReaction) return@write TextRuleAdmission.REACTION
        database.messages().upsert(message.copy(channelOccupantID = occupant).toEntity())
        save()
        TextRuleAdmission.SAVED
    }

    override suspend fun admitRoomText(message: RoomMessageDTO, room: RemoteNodeSessionDTO): TextRuleRoomAdmission =
        context.write("admitRoomText") {
            val stored = database.sessions().byId(room.radioId.value, room.id)
                ?: return@write TextRuleRoomAdmission(false, false)
            val current = stored.toDTO()
            if (message.sessionID != room.id || current.publicKey != room.publicKey || !current.isRoom ||
                database.devices().forRadio(room.radioId.value).isEmpty()) return@write TextRuleRoomAdmission(false, false)
            suspend fun recover(): TextRuleRoomAdmission {
                if (!stored.isConnected) {
                    database.sessions().upsert(stored.copy(isConnected = true))
                    save()
                }
                return TextRuleRoomAdmission(false, !stored.isConnected)
            }
            if (database.roomMessages().duplicate(room.radioId.value, room.id, message.deduplicationKey)) return@write recover()
            val row = conversation(room.radioId, "room", room.id, current.publicKey.hexString)
            val matchingSender = database.contacts().forPrefix(room.radioId.value, message.authorKeyPrefix,
                message.authorKeyPrefix.size).singleOrNull()?.toDTO()?.displayName
            val identity = BlockedTextIdentity(room.radioId, row.identity, message.authorKeyPrefix,
                message.timestamp, TextType.SIGNED_PLAIN.rawValue, message.text)
            if (suppress(identity, TextRuleKind.ROOM, row, message.text, matchingSender, current.name,
                    message.createdAt, bypass = message.isFromSelf)) return@write recover()
            database.roomMessages().upsert(message.toEntity(room.radioId))
            database.sessions().upsert(stored.copy(isConnected = true,
                lastSyncTimestamp = maxOf(stored.lastSyncTimestamp, message.timestamp.toLong()),
                lastMessageDate = StoredInstant.from(message.createdAt),
                unreadCount = if (message.isFromSelf) stored.unreadCount else checkedIncrement(stored.unreadCount)))
            save()
            TextRuleRoomAdmission(true, !stored.isConnected)
        }

    private fun channelKind(channel: ChannelDTO): TextRuleKind = when {
        channel.isPublicChannel -> TextRuleKind.PUBLIC_CHANNEL
        channel.name.startsWith("#") -> TextRuleKind.HASHTAG_CHANNEL
        else -> TextRuleKind.PRIVATE_CHANNEL
    }
}
