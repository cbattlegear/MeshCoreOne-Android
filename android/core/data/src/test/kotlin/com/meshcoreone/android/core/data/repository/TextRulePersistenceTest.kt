// AndroidOnly: WP-309 Issue #729 native Room assertions for newer upstream TextRulesPreference/Ownership contracts.
package com.meshcoreone.android.core.data.repository

import androidx.room.Room
import androidx.room.RoomDatabase
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.DirectAckStorage
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.TextRulePreferenceEntity
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
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

    @Test fun zeroWireTimestampSurvivesOutOfRangeClockForDirectAndChannelMessages() = runTest {
        store.saveDevice(device())
        store.saveContact(contact())
        store.saveChannel(RADIO_A, ChannelInfo(0u, "Public", key().prefix(16)))
        val peer = assertNotNull(store.fetchContact(entity()))
        val channel = assertNotNull(store.fetchChannel(RADIO_A, 0u))
        for ((index, date) in listOf(Instant.ofEpochSecond(-1), Instant.ofEpochSecond(4_294_967_296)).withIndex()) {
            val direct = incoming("direct $index", 0u).copy(createdAt = date, sortDate = date)
            assertEquals(TextRuleAdmission.SAVED,
                store.admitTextMessage(direct, peer, null, direct.text, 0u, false, date, false))
            assertEquals(0u, assertNotNull(store.fetchMessage(entity(id = direct.id))).timestamp)
            store.saveMessage(direct.copy(isRead = true))
            assertEquals(0u, assertNotNull(store.fetchMessage(entity(id = direct.id))).timestamp)

            val channelMessage = message(contactID = null, channelIndex = 0u, text = "channel $index",
                timestamp = 0u, createdAt = date, direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED)
                .copy(sortDate = date)
            assertEquals(TextRuleAdmission.SAVED,
                store.admitTextMessage(channelMessage, null, channel, "Peer: channel $index", 0u, false, date, false))
            assertEquals(0u, assertNotNull(store.fetchMessage(entity(id = channelMessage.id))).timestamp)
            val updated = store.saveReceivedChannelMessage(channelMessage.copy(isRead = true))
            assertEquals(0u, updated.timestamp)
            assertTrue(updated.isRead)
            assertEquals(0u, assertNotNull(store.fetchMessage(entity(id = channelMessage.id))).timestamp)
        }
        assertEquals(2, issues.size)
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

    @Test fun nonStringPhrasesAreRetainedAndReportedWhileMessagesRemainUnfiltered() = runTest {
        directRules()
        val committed = assertNotNull(db.textRules().preference())
        for ((index, invalid) in listOf("null", "true", "123", "[]", "{}").withIndex()) {
            val reportsBefore = issues.size
            val payload = committed.payload.replace("\"phrase\":\"filter\"", "\"phrase\":$invalid")
            assertNotEquals(committed.payload, payload)
            db.textRules().preference(committed.copy(payload = payload))
            assertFailsWith<PersistenceStoreException> { store.textRulesPreference() }
            assertTrue(store.textRuleLoadFailed.value)
            assertFailsWith<PersistenceStoreException> { store.updateTextRules { TextRulesPreference() } }
            val row = incoming("filter null true 123", (index + 1).toUInt())
            assertEquals(TextRuleAdmission.SAVED, admit(row))
            assertEquals(row.text, assertNotNull(store.fetchMessage(entity(id = row.id))).text)
            assertEquals(payload, db.textRules().preference()?.payload)
            assertTrue(issues.size > reportsBefore)
        }
        db.textRules().preference(committed)
        assertEquals(TextRuleJson.preference(committed.payload), store.textRulesPreference())
        assertFalse(store.textRuleLoadFailed.value)
        store.updateTextRules { it.copy(rules = listOf(TextRule(phrase = "123"))) }
        assertEquals(TextRuleAdmission.DISCARDED, admit(incoming("blocked 123", 50u)))
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

    @Test fun committedDirectValidationRejectsReplacementAndChangedReceiveFields() = runTest {
        store.saveDevice(device())
        store.saveContact(contact())
        val snapshot = assertNotNull(store.fetchContact(entity()))
        val row = incoming("original", 10u)
        var committedIncarnation: String? = null
        assertEquals(TextRuleAdmission.SAVED,
            store.admitTextMessage(row, snapshot, null, row.text, 0u, false, row.createdAt, false) { _, incarnation ->
                committedIncarnation = incarnation
            })
        val incarnation = assertNotNull(committedIncarnation)
        assertNotNull(store.currentDirectTextContact(row, snapshot, true, incarnation))

        store.saveMessage(row.copy(isRead = true, reactionSummary = "updated"))
        assertNotNull(store.currentDirectTextContact(row, snapshot, true, incarnation))
        store.saveMessage(row.copy(text = "changed", senderKeyPrefix = key(0x55).prefix(6)))
        assertNull(store.currentDirectTextContact(row, snapshot, true, incarnation))

        store.saveMessage(row)
        assertNotNull(store.currentDirectTextContact(row, snapshot, true, incarnation))
        store.deleteMessage(entity(id = row.id))
        store.saveMessage(row)
        assertNull(store.currentDirectTextContact(row, snapshot, true, incarnation))

        val orphan = incoming("orphan", 11u).copy(contactID = null)
        var orphanIncarnation: String? = null
        assertEquals(TextRuleAdmission.SAVED,
            store.admitTextMessage(orphan, null, null, orphan.text, 0u, false, orphan.createdAt, false) { _, value ->
                orphanIncarnation = value
            })
        assertTrue(store.isCurrentDirectTextMessage(orphan, orphanIncarnation))
        store.deleteMessage(entity(id = orphan.id))
        store.saveMessage(orphan)
        assertFalse(store.isCurrentDirectTextMessage(orphan, orphanIncarnation))
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

    @Test fun importedIdentityRetiresOnlyAnAbandonedTextRulePartition() = runTest {
        store.updateTextRules {
            it.copy(rules = listOf(TextRule(phrase = "filter")),
                selections = it.selections + (TextRuleKind.DIRECT_MESSAGE to TextRuleSelection(TextRuleSelectionMode.ALL)))
        }
        for (retry in listOf(false, true)) for (stillOwned in listOf(false, true)) {
            val sourceRadio = RadioId(UUID.randomUUID())
            val targetRadio = RadioId(UUID.randomUUID())
            val source = device(sourceRadio).copy(publicKey = key(0x10), isActive = true,
                connectionMethods = SnapshotList.of(ConnectionMethod.Bluetooth(UUID.randomUUID())))
            val ghost = device(targetRadio).copy(publicKey = key(0x20), isActive = false,
                connectionMethods = SnapshotList.empty())
            store.saveDevice(source)
            store.saveDevice(ghost)
            if (stillOwned) store.saveDevice(device(sourceRadio).copy(publicKey = key(0x30)))
            val sourceContact = contact(sourceRadio, UUID.randomUUID(), key(0x41))
            val targetContact = contact(targetRadio, UUID.randomUUID(), key(0x42))
            store.saveContact(sourceContact)
            store.saveContact(targetContact)
            val sourceSelection = store.textRuleConversations().single { it.key.radioId == sourceRadio }
            val targetSelection = store.textRuleConversations().single { it.key.radioId == targetRadio }
            store.setTextRuleOverride(sourceSelection.key, TextRuleOverride.ON)
            store.setTextRuleOverride(targetSelection.key, TextRuleOverride.ON)
            val sourceRow = assertNotNull(db.textRules().conversation(sourceRadio.value, "contact", sourceContact.id))
            val targetRow = assertNotNull(db.textRules().conversation(targetRadio.value, "contact", targetContact.id))
            for (contact in listOf(sourceContact, targetContact)) {
                val row = message(radio = contact.radioId, contactID = contact.id, text = "filter this",
                    timestamp = 1u, direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED)
                    .copy(senderKeyPrefix = contact.publicKey.prefix(6))
                assertEquals(TextRuleAdmission.DISCARDED, admit(row))
            }
            val before = store.recentlyBlockedTexts().filter { it.identity.radioId in setOf(sourceRadio, targetRadio) }
            assertEquals(2, before.size)

            val adopted = if (retry) {
                store.retryImportedIdentity(source.id, sourceRadio, source.publicKey, { true },
                    writeIdentity = { ghost.publicKey })
            } else {
                store.reconcileImportedIdentity(source.id, sourceRadio, source.publicKey, ghost.publicKey, { true }) {}
            }
            assertEquals(targetRadio, assertNotNull(adopted).radioId)
            assertNull(store.fetchDevice(ghost.id))
            val sourceSnapshot = assertNotNull(store.fetchContact(EntityKey(sourceRadio, sourceContact.id)))
            assertEquals(if (stillOwned) sourceRow.identity else null, sourceSnapshot.receiveConversationID)
            assertEquals(if (stillOwned) sourceRow else null,
                db.textRules().conversation(sourceRadio.value, "contact", sourceContact.id))
            assertEquals(targetRow, db.textRules().conversation(targetRadio.value, "contact", targetContact.id))
            assertEquals(before.filter { stillOwned || it.identity.radioId == targetRadio },
                store.recentlyBlockedTexts().filter { it.identity.radioId in setOf(sourceRadio, targetRadio) })
        }
    }

    @Test fun orphanSnapshotsDoNotRecreateRetiredContactOrRoomIdentities() = runTest {
        val device = device()
        val room = session().copy(isConnected = true)
        val roomKey = entity(id = room.id)
        store.saveDevice(device)
        store.saveContact(contact().copy(lastMessageDate = AT, isBlocked = true))
        store.saveRemoteNodeSessionDTO(room)
        val originalContact = assertNotNull(store.fetchContact(entity()))
        val originalRoom = assertNotNull(store.fetchRemoteNodeSession(roomKey))
        assertNotNull(originalContact.receiveConversationID)
        assertNotNull(originalRoom.receiveConversationID)

        store.deleteDevice(device.id)
        val orphanContact = assertNotNull(store.fetchContact(entity()))
        val orphanRoom = assertNotNull(store.fetchRemoteNodeSession(roomKey))
        assertNull(orphanContact.receiveConversationID)
        assertNull(orphanRoom.receiveConversationID)
        assertNull(store.fetchContacts(RADIO_A).single().receiveConversationID)
        assertNull(store.fetchConversations(RADIO_A).single().receiveConversationID)
        assertNull(store.fetchBlockedContacts(RADIO_A).single().receiveConversationID)
        assertNull(store.fetchRemoteNodeSessions(RADIO_A).single().receiveConversationID)
        assertNull(store.fetchConnectedRemoteNodeSessions(RADIO_A).single().receiveConversationID)
        assertNull(db.textRules().conversation(RADIO_A.value, "contact", CONTACT_A))
        assertNull(db.textRules().conversation(RADIO_A.value, "room", room.id))

        store.saveDevice(device)
        val restoredContact = assertNotNull(store.fetchContact(entity()))
        val restoredRoom = assertNotNull(store.fetchRemoteNodeSession(roomKey))
        assertNotEquals(originalContact.receiveConversationID, restoredContact.receiveConversationID)
        assertNotEquals(originalRoom.receiveConversationID, restoredRoom.receiveConversationID)
        val direct = incoming("orphaned contact", 5u)
        assertEquals(TextRuleAdmission.DISCARDED,
            store.admitTextMessage(direct, orphanContact, null, direct.text, 0u, false, AT, false))
        val roomMessage = RoomMessageDTO(sessionID = room.id, authorKeyPrefix = key().prefix(4),
            text = "orphaned room", timestamp = 6u, createdAt = AT)
        assertEquals(TextRuleRoomAdmission(false, false), store.admitRoomText(roomMessage, orphanRoom))
        assertNull(store.currentDirectTextContact(direct, orphanContact, false, null))
        assertNull(store.currentRoomTextSession(orphanRoom, null))
        assertTrue(store.fetchMessages(entity()).isEmpty())
        assertTrue(store.fetchRoomMessages(roomKey).isEmpty())
    }

    @Test fun conversationSnapshotsAndInventoryUseBoundedIdentityQueriesAndPreserveUnchangedRows() = runTest {
        val statements = mutableListOf<String>()
        val deviceStatements = mutableListOf<String>()
        val immediate = Executor { it.run() }
        val database = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .addCallback(DirectAckStorage.callback)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate)
            .setQueryCallback(RoomDatabase.QueryCallback { sql, _ ->
                if (sql.contains("text_rule_conversations", ignoreCase = true)) statements += sql
                if (sql.contains("devices", ignoreCase = true)) deviceStatements += sql
            }, immediate).build()
        val measured = RoomPersistenceStore(database, owner, clock)
        try {
            measured.saveDevice(device())
            val contacts = (0 until 500).map {
                contact(id = UUID.randomUUID(), name = "Peer $it").copy(lastMessageDate = AT, isBlocked = true)
            }
            val rooms = (0 until 64).map { session(id = UUID.randomUUID()).copy(name = "Room $it", isConnected = true) }
            contacts.forEach { measured.saveContact(it) }
            rooms.forEach { measured.saveRemoteNodeSessionDTO(it) }
            repeat(3) { measured.saveChannel(RADIO_A, ChannelInfo(it.toUByte(), "Channel $it", key().prefix(16))) }
            val originalContacts = measured.fetchContacts(RADIO_A).associate { it.id to it.receiveConversationID }
            val originalRooms = measured.fetchRemoteNodeSessions(RADIO_A).associate { it.id to it.receiveConversationID }
            assertTrue(originalContacts.values.all { it != null })
            assertTrue(originalRooms.values.all { it != null })
            val initial = measured.textRuleConversations()
            val selected = initial.single { it.key.conversationID == originalContacts.getValue(contacts[2].id) }
            measured.setTextRuleOverride(selected.key, TextRuleOverride.ON)

            suspend fun checkRead(block: suspend () -> Unit) {
                statements.clear()
                block()
                assertEquals(1, statements.count { it.trimStart().startsWith("SELECT", ignoreCase = true) }, statements.toString())
                assertTrue(statements.none { it.trimStart().startsWith("INSERT", ignoreCase = true) }, statements.toString())
            }
            checkRead { assertEquals(originalContacts, measured.fetchContacts(RADIO_A).associate { it.id to it.receiveConversationID }) }
            checkRead { assertEquals(originalContacts, measured.fetchConversations(RADIO_A).associate { it.id to it.receiveConversationID }) }
            checkRead {
                deviceStatements.clear()
                assertEquals(originalContacts, measured.fetchBlockedContacts(RADIO_A).associate { it.id to it.receiveConversationID })
                assertEquals(1, deviceStatements.count { it.trimStart().startsWith("SELECT", ignoreCase = true) }, deviceStatements.toString())
            }
            checkRead { assertEquals(originalRooms, measured.fetchRemoteNodeSessions(RADIO_A).associate { it.id to it.receiveConversationID }) }
            checkRead { assertEquals(originalRooms, measured.fetchConnectedRemoteNodeSessions(RADIO_A).associate { it.id to it.receiveConversationID }) }
            checkRead {
                val inventory = measured.textRuleConversations()
                assertEquals(initial.map { it.key }.toSet(), inventory.map { it.key }.toSet())
                assertEquals(TextRuleOverride.ON, inventory.single { it.key == selected.key }.override)
            }

            val changed = assertNotNull(database.contacts().byId(RADIO_A.value, contacts[0].id))
            database.contacts().upsert(changed.copy(publicKey = key(0x55)))
            database.textRules().deleteConversation(RADIO_A.value, "contact", contacts[1].id)
            statements.clear()
            val refreshed = measured.fetchContacts(RADIO_A).associate { it.id to it.receiveConversationID }
            assertEquals(1, statements.count { it.trimStart().startsWith("SELECT", ignoreCase = true) }, statements.toString())
            assertEquals(2, statements.count { it.trimStart().startsWith("INSERT", ignoreCase = true) }, statements.toString())
            assertNotEquals(originalContacts[contacts[0].id], refreshed[contacts[0].id])
            assertNotEquals(originalContacts[contacts[1].id], refreshed[contacts[1].id])
            assertEquals(originalContacts.filterKeys { it !in setOf(contacts[0].id, contacts[1].id) },
                refreshed.filterKeys { it !in setOf(contacts[0].id, contacts[1].id) })
            assertEquals(TextRuleOverride.ON, measured.textRuleConversations().single { it.key == selected.key }.override)
        } finally {
            measured.close()
            database.close()
        }
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

    @Test fun roomArchiveUsesDisplayHintsWithoutTrustingThemForSenderRules() = runTest {
        store.saveDevice(device())
        store.saveDevice(device(RADIO_B).copy(publicKey = key(0x66)))
        val initial = session()
        store.saveRemoteNodeSessionDTO(initial)
        val room = assertNotNull(store.fetchRemoteNodeSession(entity(id = initial.id)))
        val prefix = key().prefix(4)
        fun post(stamp: UInt) = RoomMessageDTO(
            sessionID = room.id, createdAt = AT, timestamp = stamp,
            authorName = "stale captured name", authorKeyPrefix = prefix, text = "filtered body $stamp",
        )
        suspend fun setRule(phrase: String, field: TextRuleField) {
            store.updateTextRules { it.copy(
                rules = listOf(TextRule(phrase = phrase, field = field)),
                selections = it.selections + (TextRuleKind.ROOM to TextRuleSelection(TextRuleSelectionMode.ALL)),
            ) }
        }
        suspend fun archivedSender(stamp: UInt): String? =
            store.recentlyBlockedTexts().single { it.identity.senderTimestamp == stamp }.sender

        setRule("filtered body", TextRuleField.MESSAGE)
        assertFalse(store.admitRoomText(post(1u), room).saved)
        assertNull(archivedSender(1u))

        store.saveContact(contact(RADIO_B, id = UUID.randomUUID(), name = "Foreign hint"))
        setRule("Foreign hint", TextRuleField.SENDER)
        assertTrue(store.admitRoomText(post(2u), room).saved)
        setRule("filtered body", TextRuleField.MESSAGE)
        assertFalse(store.admitRoomText(post(3u), room).saved)
        assertEquals("Foreign hint", archivedSender(3u))

        store.saveContact(contact(id = UUID.randomUUID(), name = "Local author"))
        setRule("Local author", TextRuleField.SENDER)
        assertFalse(store.admitRoomText(post(4u), room).saved)
        assertEquals("Local author", archivedSender(4u))

        store.saveContact(contact(id = UUID.randomUUID(), publicKey = prefix + key(0x33).prefix(28), name = "Other local"))
        setRule("Local author", TextRuleField.SENDER)
        assertTrue(store.admitRoomText(post(5u), room).saved)
        val displayHint = assertNotNull(store.findContactNameByKeyPrefix(prefix))
        setRule("filtered body", TextRuleField.MESSAGE)
        assertFalse(store.admitRoomText(post(6u), room).saved)
        assertEquals(displayHint, archivedSender(6u))
    }

    @Test fun roomArchiveSurvivesIdentityRotationUntilItsOwnRoomIsDeleted() = runTest {
        store.saveDevice(device())
        store.saveDevice(device(RADIO_B).copy(publicKey = key(0x66)))
        val sharedID = UUID.randomUUID()
        val first = session(id = sharedID)
        val second = session(RADIO_B, id = sharedID, publicKey = key(0x33))
        store.saveRemoteNodeSessionDTO(first)
        store.saveRemoteNodeSessionDTO(second)
        store.updateTextRules { it.copy(
            rules = listOf(TextRule(phrase = "blocked")),
            selections = it.selections + (TextRuleKind.ROOM to TextRuleSelection(TextRuleSelectionMode.ALL)),
        ) }
        val firstSnapshot = assertNotNull(store.fetchRemoteNodeSession(entity(id = sharedID)))
        val secondSnapshot = assertNotNull(store.fetchRemoteNodeSession(entity(RADIO_B, sharedID)))
        fun post(stamp: UInt) = RoomMessageDTO(
            sessionID = sharedID, createdAt = AT, timestamp = stamp,
            authorKeyPrefix = key(0x77).prefix(4), text = "blocked $stamp",
        )
        assertFalse(store.admitRoomText(post(1u), firstSnapshot).saved)
        assertFalse(store.admitRoomText(post(2u), secondSnapshot).saved)
        val originalEntries = store.recentlyBlockedTexts()

        store.saveRemoteNodeSessionDTO(first.copy(publicKey = key(0x55)))
        val replacement = assertNotNull(store.fetchRemoteNodeSession(entity(id = sharedID)))
        assertNotEquals(firstSnapshot.receiveConversationID, replacement.receiveConversationID)
        assertEquals(originalEntries, store.recentlyBlockedTexts())
        assertFalse(store.admitRoomText(post(3u), replacement).saved)
        assertEquals(3, store.recentlyBlockedTexts().size)

        db.sessions().delete(RADIO_A.value, sharedID)
        val remaining = store.recentlyBlockedTexts()
        assertEquals(1, remaining.size)
        assertEquals(RADIO_B, remaining.single().identity.radioId)
        assertEquals(2u, remaining.single().identity.senderTimestamp)
        store.deleteRemoteNodeSession(entity(RADIO_B, sharedID))
        assertTrue(store.recentlyBlockedTexts().isEmpty())
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
