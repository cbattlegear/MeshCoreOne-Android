// AndroidOnly: WP-309 Issue #729 native Room assertions for newer upstream TextRulesPreference/Ownership contracts.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.TextRulePreferenceEntity
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TextRulePersistenceTest : RepositoryTest() {
    private suspend fun directRules(field: TextRuleField = TextRuleField.MESSAGE, phrase: String = "filter") {
        store.saveDevice(device())
        store.saveContact(contact())
        store.updateTextRules {
            it.copy(rules = listOf(TextRule(phrase = phrase, field = field)),
                selections = it.selections + (TextRuleKind.DIRECT_MESSAGE to TextRuleSelection(TextRuleSelectionMode.ALL)))
        }
    }
    private fun incoming(text: String = "please FILTER this", stamp: UInt = 1u) =
        message(text = text, timestamp = stamp, direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED)
            .copy(senderKeyPrefix = key().prefix(6))
    private suspend fun admit(message: MessageDTO, reaction: Boolean = false) =
        store.admitTextMessage(message, message.contactID?.let { store.fetchContact(EntityKey(message.radioId, it)) },
            null, message.text, 0u, reaction, message.createdAt, false)

    @Test fun outOfRangeReceiveClockReportsHeardFailureWithoutLosingMessagesOrReactions() = runTest {
        store.saveDevice(device())
        store.saveContact(contact())
        val snapshot = assertNotNull(store.fetchContact(entity()))
        for ((index, date) in listOf(Instant.ofEpochSecond(-1), Instant.ofEpochSecond(4_294_967_296)).withIndex()) {
            val row = incoming(text = "retained $index", stamp = (index + 10).toUInt()).copy(
                createdAt = date, sortDate = date)
            assertEquals(TextRuleAdmission.SAVED,
                store.admitTextMessage(row, snapshot, null, row.text, 0u, false, date, false))
            assertNotNull(store.fetchMessage(entity(id = row.id)))
            assertEquals(TextRuleAdmission.REACTION,
                store.admitTextMessage(row.copy(id = UUID.randomUUID(), timestamp = row.timestamp + 100u),
                    snapshot, null, "reaction $index", 0u, true, date, false))
            val current = assertNotNull(store.fetchContact(entity()))
            assertEquals(0u, current.lastHeardTimestamp)
            assertEquals(date, current.lastMessageDate)
            assertEquals((index + 1).toLong(), current.unreadCount)
        }
        assertEquals(4, issues.size)
        assertTrue(issues.all { it.error == PersistenceStoreError.InvalidData })
    }

    @Test fun archiveIsCommittedBeforeDiscardReplayRemainsSuppressedAfterRuleRemovalAndHistoryIsUntouched() = runTest {
        directRules()
        val existing = message(text = "filter is old history")
        store.saveMessage(existing)
        val row = incoming()
        assertEquals(TextRuleAdmission.DISCARDED, admit(row))
        assertNull(store.fetchMessage(entity(id = row.id)))
        val archived = store.recentlyBlockedTexts().single()
        assertEquals(row.text, archived.body)
        assertEquals(row.text, archived.identity.wireText)
        assertEquals(CONTACT_A, archived.contactID)
        assertEquals(listOf(TextRuleHighlight(7, 6)), archived.hit.bodyHighlights)
        store.updateTextRules { it.copy(rules = emptyList()) }
        assertEquals(TextRuleAdmission.DISCARDED, admit(row.copy(id = UUID.randomUUID())))
        assertEquals(1, store.recentlyBlockedTexts().size)
        assertEquals(existing, store.fetchMessage(entity(id = existing.id)))
        assertEquals(0L, store.getUnreadCount(entity()))
    }

    @Test fun failedArchiveAndPreferenceWritesRollBackAndRetryDoesNotReportFakeSuccess() = runTest {
        directRules()
        val committed = store.textRulesPreference()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_text_archive AFTER INSERT ON recently_blocked_texts BEGIN SELECT RAISE(FAIL, 'test archive failure'); END")
        val row = incoming()
        assertFailsWith<PersistenceStoreException> { admit(row) }
        assertTrue(store.recentlyBlockedTexts().isEmpty())
        assertNull(store.fetchMessage(entity(id = row.id)))
        assertTrue(issues.isNotEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_text_archive")
        assertEquals(TextRuleAdmission.DISCARDED, admit(row))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_text_preference BEFORE INSERT ON text_rule_preferences BEGIN SELECT RAISE(FAIL, 'test preference failure'); END")
        assertFailsWith<PersistenceStoreException> { store.updateTextRules { it.copy(rules = emptyList()) } }
        assertEquals(committed, store.textRulesPreference())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_text_preference")
        assertFailsWith<IllegalArgumentException> { store.updateTextRules { it.copy(rules = listOf(TextRule(phrase = " \n"))) } }
        assertEquals(committed, store.textRulesPreference())
    }

    @Test fun malformedPreferencesAreRetainedAndReportedWhileReceivingUnfilteredUntilRecovery() = runTest {
        directRules()
        val committed = db.textRules().preference()!!
        db.textRules().preference(TextRulePreferenceEntity(payload = "invalid settings"))
        assertFailsWith<PersistenceStoreException> { store.textRulesPreference() }
        assertTrue(store.textRuleLoadFailed.value)
        assertFailsWith<PersistenceStoreException> { store.updateTextRules { TextRulesPreference() } }
        val row = incoming()
        assertEquals(TextRuleAdmission.SAVED, admit(row))
        assertNotNull(store.fetchMessage(entity(id = row.id)))
        assertEquals("invalid settings", db.textRules().preference()?.payload)
        db.textRules().preference(committed)
        assertEquals(TextRuleJson.preference(committed.payload), store.textRulesPreference())
        assertFalse(store.textRuleLoadFailed.value)
    }

    @Test fun ambiguousSendersAreNotGuessedAndExistingContactBlocksDoNotBecomeTextBlocks() = runTest {
        directRules(TextRuleField.SENDER, "TestContact")
        val first = incoming()
        assertEquals(TextRuleAdmission.DISCARDED, admit(first))
        val collision = contact(id = UUID.randomUUID(),
            publicKey = key().prefix(6) + Bytes(ByteArray(26) { 0x55 }))
        store.saveContact(collision)
        val second = incoming(stamp = 2u)
        assertEquals(TextRuleAdmission.SAVED, admit(second))
        val blocked = contact().copy(isBlocked = true)
        store.saveContact(blocked)
        store.updateTextRules { it.copy(rules = listOf(TextRule(phrase = "filter"))) }
        assertEquals(TextRuleAdmission.SAVED_BLOCKED, admit(incoming(stamp = 3u)))
        assertEquals(1, store.recentlyBlockedTexts().size)
    }

    @Test fun reactionsMatchOnlySenderAndAreNotArchived() = runTest {
        directRules()
        assertEquals(TextRuleAdmission.REACTION, admit(incoming(), reaction = true))
        assertTrue(store.recentlyBlockedTexts().isEmpty())
        store.updateTextRules { it.copy(rules = listOf(TextRule(phrase = "TestContact", field = TextRuleField.SENDER))) }
        assertEquals(TextRuleAdmission.DISCARDED, admit(incoming(), reaction = true))
        assertTrue(store.recentlyBlockedTexts().isEmpty())
    }

    @Test fun directReplayUsesOriginalOrLegacyTimestampAndStillRequiresExactWireIdentity() = runTest {
        directRules()
        val corrected = incoming("corrected", 10u).copy(
            timestamp = 500u, timestampCorrected = true, senderTimestamp = 10u,
        )
        store.saveMessage(corrected)
        assertEquals(TextRuleAdmission.REPLAY, admit(incoming("corrected", 10u)))

        val legacy = incoming("legacy", 20u)
        store.saveMessage(legacy)
        assertEquals(TextRuleAdmission.REPLAY, admit(incoming("legacy", 20u)))

        store.saveMessage(incoming("unknown original", 30u).copy(timestampCorrected = true))
        assertEquals(TextRuleAdmission.SAVED, admit(incoming("unknown original", 30u)))
        store.saveMessage(incoming("sender", 40u).copy(senderKeyPrefix = key(0x55).prefix(6)))
        assertEquals(TextRuleAdmission.SAVED, admit(incoming("sender", 40u)))
        store.saveMessage(incoming("type", 50u).copy(textType = TextType.CLI_DATA))
        assertEquals(TextRuleAdmission.SAVED, admit(incoming("type", 50u)))
        store.saveMessage(incoming("different body", 60u))
        assertEquals(TextRuleAdmission.SAVED, admit(incoming("body", 60u)))
        store.saveMessage(incoming("direction", 70u).copy(direction = MessageDirection.OUTGOING))
        assertEquals(TextRuleAdmission.SAVED, admit(incoming("direction", 70u)))
    }

    @Test fun archiveIsBoundedByArrivalAndContactDeletionRetiresSelectionAndOwnedArchive() = runTest {
        directRules()
        val before = store.textRuleConversations().single()
        store.setTextRuleOverride(before.key, TextRuleOverride.ON)
        repeat(105) { index ->
            assertEquals(TextRuleAdmission.DISCARDED, admit(incoming(stamp = index.toUInt()).copy(
                createdAt = AT.plusSeconds(index.toLong()))))
        }
        assertEquals(100, store.recentlyBlockedTexts().size)
        assertEquals(104u, store.recentlyBlockedTexts().first().identity.senderTimestamp)
        assertEquals(5u, store.recentlyBlockedTexts().last().identity.senderTimestamp)
        store.deleteContactIfUnreferenced(entity())
        assertNotNull(store.fetchContact(entity()), "archive retains its bound contact")
        store.deleteContact(entity())
        assertTrue(store.recentlyBlockedTexts().isEmpty())
        store.saveContact(contact())
        val restored = store.textRuleConversations().single()
        assertNotEquals(before.key, restored.key)
        assertEquals(TextRuleOverride.INHERIT, restored.override)
        assertFailsWith<PersistenceStoreException> { store.setTextRuleOverride(before.key, TextRuleOverride.ON) }
    }

    @Test fun preferenceAndConversationIdentitySurviveFileReopen() = runTest {
        store.close(); db.close()
        val name = "text-rules-restart.db"
        db = MeshCoreDatabase.open(context, name)
        store = newStore()
        directRules()
        val conversation = store.textRuleConversations().single()
        store.setTextRuleOverride(conversation.key, TextRuleOverride.OFF)
        val preference = store.textRulesPreference()
        val row = incoming()
        assertEquals(TextRuleAdmission.SAVED, admit(row))
        store.setTextRuleOverride(conversation.key, TextRuleOverride.ON)
        assertEquals(TextRuleAdmission.DISCARDED, admit(incoming(stamp = 2u)))
        store.setTextRuleOverride(conversation.key, TextRuleOverride.OFF)
        val archive = store.recentlyBlockedTexts()
        store.close(); db.close()
        db = MeshCoreDatabase.open(context, name)
        store = newStore()
        assertEquals(preference, store.textRulesPreference())
        assertEquals(conversation.key, store.textRuleConversations().single().key)
        assertEquals(TextRuleOverride.OFF, store.textRuleConversations().single().override)
        assertEquals(row.text, store.fetchMessage(entity(id = row.id))?.text)
        assertEquals(archive, store.recentlyBlockedTexts())
    }

    @Test fun demotionPreservesArchiveOverridesAndFixedConversationSelections() = runTest {
        directRules()
        val selected = store.textRuleConversations().single()
        store.setTextRuleOverride(selected.key, TextRuleOverride.ON)
        store.updateTextRules { it.copy(selections = it.selections + (TextRuleKind.DIRECT_MESSAGE to
            TextRuleSelection(TextRuleSelectionMode.FIXED, setOf(selected.key)))) }
        assertEquals(TextRuleAdmission.DISCARDED, admit(incoming()))
        val archive = store.recentlyBlockedTexts()
        val preference = store.textRulesPreference()
        val original = assertNotNull(store.fetchDevice(key()))
        store.demoteDeviceToGhost(original.id)
        val ghost = assertNotNull(store.fetchDevice(original.publicKey))
        assertNotEquals(original.id, ghost.id)
        assertFalse(ghost.isActive)
        assertEquals(archive, store.recentlyBlockedTexts())
        assertEquals(preference, store.textRulesPreference())
        assertEquals(selected.key, store.textRuleConversations().single().key)
        assertEquals(TextRuleOverride.ON, store.textRuleConversations().single().override)
    }

    @Test fun staleContactSnapshotsCannotSaveOrFilterIntoAnIdenticallyRestoredChat() = runTest {
        directRules()
        for ((index, override) in listOf(TextRuleOverride.OFF, TextRuleOverride.ON).withIndex()) {
            val stale = assertNotNull(store.fetchContactByPrefix(RADIO_A, key().prefix(6)))
            assertNotNull(stale.receiveConversationID)
            store.deleteContact(entity())
            store.saveContact(stale)
            val restored = assertNotNull(store.fetchContact(entity()))
            assertNotEquals(stale.receiveConversationID, restored.receiveConversationID)
            val conversation = store.textRuleConversations().single()
            store.setTextRuleOverride(conversation.key, override)
            val row = incoming(stamp = (index + 1).toUInt())
            assertEquals(TextRuleAdmission.DISCARDED,
                store.admitTextMessage(row, stale, null, row.text, 0u, false, row.createdAt, false))
            assertTrue(store.fetchMessages(entity()).isEmpty())
            assertTrue(store.recentlyBlockedTexts().isEmpty())
            assertEquals(restored, store.fetchContact(entity()))
            assertEquals(conversation.key, store.textRuleConversations().single().key)
            val admitted = store.admitTextMessage(row, restored, null, row.text, 0u, false, row.createdAt, false)
            if (override == TextRuleOverride.OFF) {
                assertEquals(TextRuleAdmission.SAVED, admitted)
                assertEquals(listOf(row), store.fetchMessages(entity()))
            } else {
                assertEquals(TextRuleAdmission.DISCARDED, admitted)
                assertEquals(listOf(row.text), store.recentlyBlockedTexts().map { it.body })
            }
        }
    }

    @Test fun staleRoomSnapshotsCannotRecoverUpdateOrFilterIntoAnIdenticallyRestoredRoom() = runTest {
        store.saveDevice(device())
        val original = session()
        val roomKey = entity(id = original.id)
        store.saveRemoteNodeSessionDTO(original)
        store.updateTextRules { it.copy(rules = listOf(TextRule(phrase = "filter")),
            selections = it.selections + (TextRuleKind.ROOM to TextRuleSelection(TextRuleSelectionMode.ALL))) }
        for ((index, override) in listOf(TextRuleOverride.OFF, TextRuleOverride.ON).withIndex()) {
            val stale = store.fetchRemoteNodeSessions(RADIO_A).single()
            assertNotNull(stale.receiveConversationID)
            store.deleteRemoteNodeSession(roomKey)
            store.saveRemoteNodeSessionDTO(original.copy(lastSyncTimestamp = 42u, unreadCount = 3,
                lastMessageDate = AT.minusSeconds(1)))
            val restored = assertNotNull(store.fetchRemoteNodeSession(roomKey))
            assertNotEquals(stale.receiveConversationID, restored.receiveConversationID)
            val conversation = store.textRuleConversations().single()
            store.setTextRuleOverride(conversation.key, override)
            val row = RoomMessageDTO(sessionID = original.id, authorKeyPrefix = key().prefix(4),
                text = "filter", timestamp = (index + 100).toUInt(), createdAt = AT)
            assertEquals(TextRuleRoomAdmission(false, false), store.admitRoomText(row, stale))
            assertTrue(store.fetchRoomMessages(roomKey).isEmpty())
            assertTrue(store.recentlyBlockedTexts().isEmpty())
            assertEquals(restored, store.fetchRemoteNodeSession(roomKey))
            assertEquals(conversation.key, store.textRuleConversations().single().key)
            val admitted = store.admitRoomText(row, restored)
            assertTrue(admitted.recovered)
            if (override == TextRuleOverride.OFF) {
                assertTrue(admitted.saved)
                assertEquals(listOf(row), store.fetchRoomMessages(roomKey))
                val updated = assertNotNull(store.fetchRemoteNodeSession(roomKey))
                assertEquals(row.timestamp, updated.lastSyncTimestamp)
                assertEquals(4L, updated.unreadCount)
            } else {
                assertFalse(admitted.saved)
                assertEquals(listOf(row.text), store.recentlyBlockedTexts().map { it.body })
                assertEquals(restored.copy(isConnected = true), store.fetchRemoteNodeSession(roomKey))
            }
        }
    }

    @Test fun unresolvedChannelWitnessRetainsUnprovedMessagesAcrossRestartWithoutApplyingScopedRules() = runTest {
        store.close(); db.close()
        val name = "text-rules-unresolved.db"
        db = MeshCoreDatabase.open(context, name)
        store = newStore()
        store.saveDevice(device())
        store.saveChannel(RADIO_A, ChannelInfo(0u, "Public", key().prefix(16)))
        store.withChannelOperation { scoped, _ -> scoped.beginChannelMutation(RADIO_A, 0u) }
        suspend fun receive(stamp: UInt) {
            val channel = assertNotNull(store.fetchChannel(RADIO_A, 0u))
            assertNull(channel.occupantID)
            val row = message(contactID = null, channelIndex = 0u, text = "filter", timestamp = stamp,
                direction = MessageDirection.INCOMING)
            assertEquals(TextRuleAdmission.SAVED,
                store.admitTextMessage(row, null, channel, "Peer: filter", 0u, false, row.createdAt, false))
            assertNull(assertNotNull(store.fetchMessage(entity(id = row.id))).channelOccupantID)
        }
        receive(1u)
        store.updateTextRules { it.copy(rules = listOf(TextRule(phrase = "filter"))) }
        store.close(); db.close()
        db = MeshCoreDatabase.open(context, name)
        store = newStore()
        receive(2u)
        assertEquals(2, store.fetchMessages(RADIO_A, 0u).size)
        assertTrue(store.recentlyBlockedTexts().isEmpty())
    }

    @Test fun originalChannelFieldsUseStandaloneGraphemeDelimitersAndFramingSpaces() = runTest {
        store.saveDevice(device())
        store.saveChannel(RADIO_A, ChannelInfo(0u, "Public", key().prefix(16)))
        val channel = assertNotNull(store.fetchChannel(RADIO_A, 0u))
        data class Case(val wire: String, val sender: String?, val body: String, val field: TextRuleField)
        val cases = listOf(
            Case("a:\u0301b: body", "a:\u0301b", "body", TextRuleField.SENDER),
            Case("A: \u0301body", "A", " \u0301body", TextRuleField.MESSAGE),
            Case("A:  body", "A", " body", TextRuleField.MESSAGE),
            Case("a:\u0301b", null, "a:\u0301b", TextRuleField.MESSAGE),
            Case(": body", null, ": body", TextRuleField.MESSAGE),
        )
        for ((index, case) in cases.withIndex()) {
            store.updateTextRules { it.copy(rules = listOf(TextRule(
                phrase = if (case.field == TextRuleField.SENDER) requireNotNull(case.sender) else case.body,
                field = case.field, match = TextRuleMatch.WHOLE_FIELD))) }
            val row = message(contactID = null, channelIndex = 0u, text = case.body,
                timestamp = (index + 1).toUInt(), direction = MessageDirection.INCOMING)
            assertEquals(TextRuleAdmission.DISCARDED,
                store.admitTextMessage(row, null, channel, case.wire, 0u, false, row.createdAt, false), case.wire)
            val archived = store.recentlyBlockedTexts().single { it.identity.senderTimestamp == row.timestamp }
            assertEquals(case.sender, archived.sender)
            assertEquals(case.body, archived.body)
            assertEquals(case.wire, archived.identity.wireText)
        }
        assertTrue(store.fetchMessages(RADIO_A, 0u).isEmpty())
    }

    @Test fun channelDefaultsFixedRadioScopeReconnectReplacementAndDeletionRetainOnlyTheRightIdentity() = runTest {
        store.saveDevice(device())
        store.saveDevice(device(RADIO_B).copy(publicKey = key(0x66)))
        for (radio in listOf(RADIO_A, RADIO_B)) {
            store.saveChannel(radio, ChannelInfo(0u, "Public", key().prefix(16)))
            store.saveChannel(radio, ChannelInfo(1u, "#local", key().prefix(16)))
            store.saveChannel(radio, ChannelInfo(2u, "Private", key().prefix(16)))
        }
        store.updateTextRules { it.copy(rules = listOf(TextRule(phrase = "filter"))) }
        suspend fun receive(radio: RadioId, index: UByte, stamp: UInt): TextRuleAdmission {
            val channel = assertNotNull(store.fetchChannel(radio, index))
            val row = message(radio, contactID = null, channelIndex = index, text = "filter", timestamp = stamp,
                direction = MessageDirection.INCOMING)
            return store.admitTextMessage(row, null, channel, "Peer: filter", 0u, false, row.createdAt, false)
        }
        assertEquals(TextRuleAdmission.DISCARDED, receive(RADIO_A, 0u, 1u))
        assertEquals(TextRuleAdmission.DISCARDED, receive(RADIO_A, 1u, 2u))
        assertEquals(TextRuleAdmission.SAVED, receive(RADIO_A, 2u, 3u))
        val selected = store.textRuleConversations().single { it.key.radioId == RADIO_A && it.kind == TextRuleKind.HASHTAG_CHANNEL }
        store.updateTextRules { it.copy(selections = it.selections + (TextRuleKind.HASHTAG_CHANNEL to
            TextRuleSelection(TextRuleSelectionMode.FIXED, setOf(selected.key)))) }
        assertEquals(TextRuleAdmission.SAVED, receive(RADIO_B, 1u, 4u))
        store.saveChannel(RADIO_A, ChannelInfo(1u, "#renamed", key().prefix(16)))
        assertEquals(selected.key, store.textRuleConversations().single { it.name == "#renamed" }.key)
        assertEquals(TextRuleAdmission.DISCARDED, receive(RADIO_A, 1u, 5u))
        val captured = assertNotNull(store.fetchChannel(RADIO_A, 1u))
        store.saveChannel(RADIO_A, ChannelInfo(1u, "#replacement", key(0x55).prefix(16)))
        val replacement = store.textRuleConversations().single { it.name == "#replacement" }
        assertNotEquals(selected.key, replacement.key)
        assertEquals(TextRuleOverride.INHERIT, replacement.override)
        assertEquals(TextRuleAdmission.SAVED, receive(RADIO_A, 1u, 6u))
        val archivedCount = store.recentlyBlockedTexts().size
        assertEquals(TextRuleAdmission.DISCARDED, store.admitTextMessage(
            message(contactID = null, channelIndex = 1u), null, captured, "Peer: filter", 0u, false, AT, false))
        assertEquals(archivedCount, store.recentlyBlockedTexts().size)
        store.batchSaveChannels(RADIO_A, emptyList<ChannelInfo>().snapshot(), listOf<UByte>(1u).snapshot(), null)
        assertEquals(archivedCount, store.recentlyBlockedTexts().size, "deleted channels retain review history")
        store.saveChannel(RADIO_A, ChannelInfo(1u, "#restored", key().prefix(16)))
        val restored = store.textRuleConversations().single { it.name == "#restored" }
        assertNotEquals(selected.key, restored.key)
        assertNotEquals(replacement.key, restored.key)
    }

    @Test fun roomSenderResolutionIsRadioScopedAmbiguitySafeAndArchiveFailureCannotRecoverTheConnection() = runTest {
        store.saveDevice(device())
        store.saveDevice(device(RADIO_B).copy(publicKey = key(0x66)))
        val initial = session()
        store.saveRemoteNodeSessionDTO(initial)
        val room = assertNotNull(store.fetchRemoteNodeSession(entity(id = initial.id)))
        store.saveContact(contact(RADIO_B, name = "Filtered Author"))
        store.updateTextRules { it.copy(rules = listOf(TextRule(phrase = "Filtered Author", field = TextRuleField.SENDER)),
            selections = it.selections + (TextRuleKind.ROOM to TextRuleSelection(TextRuleSelectionMode.ALL))) }
        fun post(stamp: UInt) = RoomMessageDTO(sessionID = room.id, authorKeyPrefix = key().prefix(4),
            text = "original room body", timestamp = stamp, createdAt = AT)
        assertTrue(store.admitRoomText(post(1u), room).saved, "another radio is not a sender-name authority")
        store.saveContact(contact(name = "Filtered Author"))
        val before = assertNotNull(store.fetchRemoteNodeSession(entity(id = room.id))).copy(isConnected = false)
        store.saveRemoteNodeSessionDTO(before)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_room_archive BEFORE INSERT ON recently_blocked_texts BEGIN SELECT RAISE(FAIL, 'test room failure'); END")
        assertFailsWith<PersistenceStoreException> { store.admitRoomText(post(2u), room) }
        assertEquals(before, store.fetchRemoteNodeSession(entity(id = room.id)))
        assertTrue(store.recentlyBlockedTexts().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_room_archive")
        val suppressed = store.admitRoomText(post(2u), room)
        assertFalse(suppressed.saved)
        assertTrue(suppressed.recovered)
        store.saveContact(contact(id = UUID.randomUUID(), publicKey = key().prefix(4) + key(0x55).prefix(28)))
        assertTrue(store.admitRoomText(post(3u), room).saved)
        assertEquals(listOf(1u, 3u), store.fetchRoomMessages(entity(id = room.id)).map { it.timestamp })
    }

    @Test fun unboundDirectArchiveBindsOnceToAUniqueSameRadioContactAndKeepsThatOwnerThroughAmbiguity() = runTest {
        store.saveDevice(device())
        store.saveDevice(device(RADIO_B).copy(publicKey = key(0x66)))
        store.updateTextRules { it.copy(rules = listOf(TextRule(phrase = "filter")),
            selections = it.selections + (TextRuleKind.DIRECT_MESSAGE to TextRuleSelection(TextRuleSelectionMode.ALL))) }
        assertEquals(TextRuleAdmission.DISCARDED, admit(incoming().copy(contactID = null)))
        assertNull(store.recentlyBlockedTexts().single().contactID)
        store.saveContact(contact(RADIO_B))
        assertNull(store.recentlyBlockedTexts().single().contactID)
        store.saveContact(contact())
        assertEquals(CONTACT_A, store.recentlyBlockedTexts().single().contactID)
        store.saveContact(contact(id = UUID.randomUUID(), publicKey = key().prefix(6) + key(0x55).prefix(26)))
        assertEquals(CONTACT_A, store.recentlyBlockedTexts().single().contactID)
        store.deleteContactIfUnreferenced(entity())
        assertNotNull(store.fetchContact(entity()))
        store.deleteContact(entity())
        assertTrue(store.recentlyBlockedTexts().isEmpty())
    }
}
