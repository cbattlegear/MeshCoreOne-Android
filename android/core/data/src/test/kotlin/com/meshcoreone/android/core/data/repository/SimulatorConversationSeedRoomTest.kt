// AndroidOnly: WP-202 Real Room controls for atomic new and preserved existing simulator conversations.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.data.backup.AppBackupEnvelope
import com.meshcoreone.android.core.data.backup.AppBackupService
import com.meshcoreone.android.core.data.backup.BackupModelKind
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

class SimulatorConversationSeedRoomTest : RepositoryTest() {
    @Test fun eagerChannelReadbackPreservesOnlyPreviouslyReconciledAuthority() = runTest {
        val fixture = fixture(channel = true)
        val channel = assertIs<SimulatorConversationParent.Channel>(fixture.parent).channel
        val created = assertIs<SimulatorConversationSeedResult.Created>(seed(fixture))
        assertNull(assertIs<SimulatorConversationParent.Channel>(created.parent).channel.occupantID)
        assertTrue(assertNotNull(db.channelOccupants().stateForIndex(channel.radioId.value, channel.index.toLong())).unresolved)
        assertNull(store.fetchChannelOccupantSnapshot(channel.radioId, channel.index))
        store.saveChannel(channel.radioId, ChannelInfo(channel.index, channel.name, channel.secret))
        val current = assertNotNull(store.fetchChannel(channel.radioId, channel.index))
        assertNotNull(current.occupantID)
        val before = rawRows()
        val preserved = assertIs<SimulatorConversationSeedResult.PreservedExisting>(seed(fixture))
        assertEquals(current, assertIs<SimulatorConversationParent.Channel>(preserved.parent).channel)
        assertEquals(before, rawRows())
        assertTrue(db.pendingSends().forRadio(channel.radioId.value).isEmpty())
    }

    private fun fixture(channel: Boolean, radio: RadioId = RADIO_A): SimulatorConversationSeed {
        val parent = if (channel) {
            SimulatorConversationParent.Channel(ChannelDTO(
                id = UUID.fromString("00000000-0000-0000-0000-000000000101"),
                radioId = radio, index = 7u, name = "Fixture", secret = Bytes(ByteArray(16) { 0x55 }),
                isEnabled = true, lastMessageDate = AT, unreadCount = 3, unreadMentionCount = 1,
            ))
        } else {
            SimulatorConversationParent.Direct(contact(radio).copy(unreadCount = 3, lastMessageDate = AT))
        }
        val first = message(
            radio, UUID.fromString("00000000-0000-0000-0000-000000000201"),
            contactID = if (channel) null else CONTACT_A, channelIndex = if (channel) 7u else null,
            direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
        ).copy(
            isRead = false, containsSelfMention = true, mentionSeen = false, heardRepeats = 1,
            linkPreviewURL = "https://example.test", linkPreviewTitle = "Offline",
            linkPreviewImageData = Bytes.of(1, 2), linkPreviewIconData = Bytes.of(3), linkPreviewFetched = true,
            reactionSummary = "fixture:1",
        )
        val second = first.copy(
            id = UUID.fromString("00000000-0000-0000-0000-000000000202"), text = "second",
            heardRepeats = 0, reactionSummary = null, createdAt = AT.minusSeconds(30), sortDate = AT.minusSeconds(30),
        )
        return SimulatorConversationSeed(
            parent, SnapshotList.of(first, second),
            SnapshotList.of(ReactionDTO(
                id = UUID.fromString("00000000-0000-0000-0000-000000000301"),
                messageID = first.id, emoji = "fixture", senderName = "Peer", messageHash = "00000000",
                rawText = "fixture", receivedAt = AT, radioId = radio,
                contactID = first.contactID, channelIndex = first.channelIndex,
            )),
            SnapshotList.of(MessageRepeatDTO(
                id = UUID.fromString("00000000-0000-0000-0000-000000000401"),
                messageID = first.id, receivedAt = AT.plusSeconds(5), pathNodes = Bytes.of(7), pathLength = 1u,
            )),
        )
    }

    private fun repositoryContext() =
        RoomRepositoryContext(db, clock, owner, RepositoryIssueReporter { _, failure -> issues += failure })

    private suspend fun RepositoryTransaction.executeSeed(
        seed: SimulatorConversationSeed, repository: RoomRepositoryContext,
    ): SimulatorConversationSeedResult = seedSimulatorConversation(
        seed, ChannelRepository(repository, RoomRepository(repository)),
    )

    private suspend fun seed(fixture: SimulatorConversationSeed): SimulatorConversationSeedResult {
        val repository = repositoryContext()
        return try {
            repository.write("seedSimulatorConversation") { executeSeed(fixture, repository) }
        } finally {
            repository.close()
        }
    }

    private suspend fun assertSeedReadbackFailure(fixture: SimulatorConversationSeed) {
        val failure = assertFailsWith<PersistenceStoreException> { seed(fixture) }
        assertIs<PersistenceStoreError.SaveFailed>(failure.error)
        assertEquals(
            "Simulator conversation insert did not persist the expected rows",
            assertIs<IllegalStateException>(failure.cause).message,
        )
    }

    private data class Rows(
        val contacts: Set<ContactEntity>, val channels: Set<ChannelEntity>, val messages: Set<MessageEntity>,
        val reactions: Set<ReactionEntity>, val repeats: Set<MessageRepeatEntity>, val pending: Set<PendingSendEntity>,
    )

    private suspend fun rows(): Rows {
        val radios = db.messages().backupRadioIds()
        val messages = radios.flatMap { radio ->
            db.messages().backupPageWithoutPreviewBlobs(radio, -1, 0).map {
                assertNotNull(db.messages().byId(radio, it.id))
            }
        }
        return Rows(
            db.contacts().backupAll().toSet(), db.channels().backupAll().toSet(), messages.toSet(),
            db.reactions().backupAll().toSet(), db.repeats().backupAll().toSet(),
            listOf(RADIO_A, RADIO_B).flatMap { db.pendingSends().forRadio(it.value) }.toSet(),
        )
    }

    private fun rawRows(radio: RadioId? = null): Map<String, List<List<String?>>> =
        listOf("contacts", "channels", "messages", "reactions", "message_repeats", "pending_sends").associateWith { table ->
            val partition = radio?.let { " WHERE radioId = '${it.value.canonicalString()}' COLLATE NOCASE" }.orEmpty()
            db.openHelper.writableDatabase.query("SELECT * FROM `$table`$partition ORDER BY radioId, id").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                        when (cursor.getType(column)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList().toString()
                            else -> cursor.getString(column)
                        }
                    })
                }
            }
        }

    private fun alphabeticFixture(channel: Boolean, radio: RadioId = RADIO_A): SimulatorConversationSeed {
        val original = fixture(channel, radio)
        val contactID = UUID.fromString("abcdefab-1111-2222-3333-abcdefabcdef")
        val messageID = UUID.fromString("cdefabcd-1111-2222-3333-abcdefabcdef")
        val parent = when (val value = original.parent) {
            is SimulatorConversationParent.Direct -> SimulatorConversationParent.Direct(value.contact.copy(id = contactID))
            is SimulatorConversationParent.Channel -> SimulatorConversationParent.Channel(
                value.channel.copy(id = UUID.fromString("abcdefab-7777-2222-3333-abcdefabcdef")),
            )
        }
        return original.copy(
            parent = parent,
            messages = original.messages.mapIndexed { index, message -> message.copy(
                id = if (index == 0) messageID else message.id, contactID = if (channel) null else contactID,
            ) }.snapshot(),
            reactions = original.reactions.map { it.copy(
                id = UUID.fromString("abcdefab-4444-2222-3333-abcdefabcdef"), messageID = messageID,
                contactID = if (channel) null else contactID,
            ) }.snapshot(),
            repeats = original.repeats.map { it.copy(
                id = UUID.fromString("bcdefabc-5555-2222-3333-abcdefabcdef"), messageID = messageID,
            ) }.snapshot(),
        )
    }

    private suspend fun saveParent(parent: SimulatorConversationParent) {
        when (parent) {
            is SimulatorConversationParent.Direct -> store.saveContact(parent.contact)
            is SimulatorConversationParent.Channel -> store.saveChannel(parent.channel)
        }
    }

    private fun later(fixture: SimulatorConversationSeed) = fixture.copy(
        messages = fixture.messages.map {
            it.copy(timestamp = it.timestamp + 3600u, createdAt = it.createdAt.plusSeconds(3600),
                sortDate = it.sortDate.plusSeconds(3600))
        }.snapshot(),
        reactions = fixture.reactions.map { it.copy(receivedAt = it.receivedAt.plusSeconds(3600)) }.snapshot(),
        repeats = fixture.repeats.map { it.copy(receivedAt = it.receivedAt.plusSeconds(3600)) }.snapshot(),
    )

    private fun forbidWrites(tables: List<String>) {
        for (table in tables) for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER forbid_seed_${table}_$operation BEFORE $operation ON $table " +
                    "BEGIN SELECT RAISE(ABORT, 'unexpected preserved conversation mutation'); END",
            )
        }
    }

    private fun allowWrites(tables: List<String>) {
        for (table in tables) for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER forbid_seed_${table}_$operation")
        }
    }

    private fun cloneRowAfterInsert(
        sourceTable: String, triggerTable: String, replacements: Map<String, String>,
        sourcePredicate: String = "1", condition: String = "1",
    ) {
        val sql = db.openHelper.writableDatabase
        val columns = sql.query("PRAGMA table_info(`$sourceTable`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
        assertTrue(columns.isNotEmpty())
        val names = columns.joinToString { "`$it`" }
        val values = columns.joinToString { replacements[it] ?: "`$it`" }
        sql.execSQL(
            "CREATE TRIGGER late_seed_child AFTER INSERT ON `$triggerTable` WHEN $condition BEGIN " +
                "INSERT INTO `$sourceTable` ($names) SELECT $values FROM `$sourceTable` " +
                "WHERE $sourcePredicate LIMIT 1; END",
        )
    }

    @Test fun absentParentsAndCompleteChildrenCommitWithActualPreviewReadbackWithoutNormalizingCounts() = runTest {
        for (channel in listOf(false, true)) {
            val fixture = fixture(channel, if (channel) RADIO_B else RADIO_A)
            val created = assertIs<SimulatorConversationSeedResult.Created>(seed(fixture))
            assertEquals(fixture.parent, created.parent)
            assertEquals(fixture.messages, created.messages)
            assertEquals(fixture.reactions.toSet(), db.reactions().backupAll()
                .filter { it.radioId == fixture.messages.first().radioId.value }.map { it.toDTO() }.toSet())
            assertEquals(fixture.repeats.toSet(), db.repeats().backupAll()
                .filter { it.radioId == fixture.messages.first().radioId.value }.map { it.toDTO() }.toSet())
            assertEquals(2, created.messages.size)
            when (val parent = created.parent) {
                is SimulatorConversationParent.Direct -> assertEquals(3L, parent.contact.unreadCount)
                is SimulatorConversationParent.Channel -> assertEquals(3L, parent.channel.unreadCount)
            }
        }
    }

    @Test fun compatibleEmptyAndLegacyPartialParentsPreserveAbsenceWithoutReconstructionOrDml() = runTest {
        for (channel in listOf(false, true)) {
            val fixture = fixture(channel, if (channel) RADIO_B else RADIO_A)
            saveParent(fixture.parent)
            if (channel) store.saveMessage(fixture.messages.last().copy(isRead = true, mentionSeen = true))
            db.pendingSends().insert(pending(
                radio = fixture.messages.first().radioId, messageID = fixture.messages.first().id,
            ).copy(kind = if (channel) PendingSendKind.CHANNEL else PendingSendKind.DM,
                contactID = if (channel) null else CONTACT_A, channelIndex = if (channel) 7u else null).toEntity())
        }
        val before = rows()
        forbidWrites(listOf("contacts", "channels", "messages", "reactions", "message_repeats"))
        for (channel in listOf(false, true)) {
            val fixture = fixture(channel, if (channel) RADIO_B else RADIO_A)
            val result = assertIs<SimulatorConversationSeedResult.PreservedExisting>(seed(later(fixture)))
            assertEquals(fixture.parent, result.parent)
        }
        assertEquals(before, rows())
    }

    @Test fun freshNullLastHeardFixtureReturnsActualStoredZeroProjection() = runTest {
        val original = fixture(false)
        val contact = assertIs<SimulatorConversationParent.Direct>(original.parent).contact.copy(lastHeardTimestamp = null)
        val fixture = original.copy(parent = SimulatorConversationParent.Direct(contact))
        val created = assertIs<SimulatorConversationSeedResult.Created>(seed(fixture))
        val expected = contact.copy(lastHeardTimestamp = 0u)
        assertEquals(SimulatorConversationParent.Direct(expected), created.parent)
        assertEquals(expected.toEntity(), db.contacts().byId(RADIO_A.value, contact.id))
        assertEquals(fixture.messages, created.messages)
        assertEquals(created.parent, assertIs<SimulatorConversationSeedResult.PreservedExisting>(seed(fixture)).parent)
    }

    @Test fun restoredChannelWithRemintedIdPreservesActualParentAndAllMissingFixtures() = runTest {
        val fixture = fixture(true)
        val restored = assertIs<SimulatorConversationParent.Channel>(fixture.parent).channel.copy(
            id = UUID.randomUUID(), name = "Restored", unreadCount = 0, lastMessageDate = AT.minusSeconds(100),
        )
        store.saveChannel(restored)
        val before = rows()
        val result = assertIs<SimulatorConversationSeedResult.PreservedExisting>(seed(later(fixture)))
        assertEquals(SimulatorConversationParent.Channel(restored), result.parent)
        assertEquals(before, rows())
    }

    @Test fun actualBackupRestoreImportsAlternateContactIdentityAndReseedPreservesThatConversation() = runTest {
        val fixture = fixture(false)
        val incomingParent = assertIs<SimulatorConversationParent.Direct>(fixture.parent).contact.copy(
            id = UUID.randomUUID(), unreadCount = 0, nickname = "Restored user", lastMessageDate = AT.minusSeconds(500),
        )
        val incomingMessage = fixture.messages.first().copy(
            id = UUID.randomUUID(), contactID = incomingParent.id, isRead = true, mentionSeen = true,
        )
        val service = AppBackupService(MeshCoreStorage.get(context).backupPreferences, "1.0", "1", clock)
        val envelope = AppBackupEnvelope(
            exportDate = AT, appVersion = "1.0", appBuild = "1",
            devices = SnapshotList.of(device()), contacts = SnapshotList.of(incomingParent),
            messages = SnapshotList.of(incomingMessage),
        ).withActualManifest()
        val imported = service.importBackup(envelope, store)
        assertEquals(1L, imported.counts.getValue(BackupModelKind.CONTACTS).inserted)
        assertEquals(1L, imported.counts.getValue(BackupModelKind.MESSAGES).inserted)
        val actual = db.contacts().forPublicKey(RADIO_A.value, incomingParent.publicKey).single()
        assertNotEquals(CONTACT_A, actual.id)
        assertEquals(actual.id, assertNotNull(db.messages().byId(RADIO_A.value, incomingMessage.id)).contactID)
        val before = rows()
        val result = assertIs<SimulatorConversationSeedResult.PreservedExisting>(seed(later(fixture)))
        assertEquals(SimulatorConversationParent.Direct(actual.toDTO()), result.parent)
        assertEquals(before, rows())
        assertNull(db.messages().byId(RADIO_A.value, fixture.messages.first().id))
    }

    @Test fun actualBackupRestoreCanLeaveContactOrphanMessagesAndNewParentSeedMustNotAdoptThem() = runTest {
        val fixture = fixture(false)
        val orphan = fixture.messages.first().copy(id = UUID.randomUUID(), text = "Restored orphan", isRead = true)
        val service = AppBackupService(MeshCoreStorage.get(context).backupPreferences, "1.0", "1", clock)
        val envelope = AppBackupEnvelope(
            exportDate = AT, appVersion = "1.0", appBuild = "1",
            devices = SnapshotList.of(device()), messages = SnapshotList.of(orphan),
        ).withActualManifest()
        val imported = service.importBackup(envelope, store)
        assertEquals(1L, imported.counts.getValue(BackupModelKind.MESSAGES).inserted)
        assertNull(db.contacts().byId(RADIO_A.value, CONTACT_A))
        assertEquals(CONTACT_A, assertNotNull(db.messages().byId(RADIO_A.value, orphan.id)).contactID)
        val before = rows()
        val devices = db.devices().all()
        assertEquals(PersistenceStoreError.InvalidData,
            assertFailsWith<PersistenceStoreException> { seed(fixture) }.error)
        assertEquals(before, rows())
        assertEquals(devices, db.devices().all())
    }

    @Test fun deletedFixedMessageAndDeletedChildrenStayAbsentOnReseed() = runTest {
        val fixture = fixture(false)
        seed(fixture)
        store.deleteMessage(EntityKey(RADIO_A, fixture.messages.first().id))
        val before = rows()
        assertNull(db.messages().byId(RADIO_A.value, fixture.messages.first().id))
        assertTrue(db.repeats().backupAll().isEmpty())
        assertIs<SimulatorConversationSeedResult.PreservedExisting>(seed(later(fixture)))
        assertEquals(before, rows())
    }

    @Test fun editedReadMentionsPreviewsReactionSummaryAndRepeatTimesSurviveTwoClockReseed() = runTest {
        val fixture = fixture(true)
        seed(fixture)
        val first = fixture.messages.first()
        val changed = first.copy(
            isRead = true, mentionSeen = true, reactionSummary = "user:2", linkPreviewTitle = "User preview",
            linkPreviewImageData = Bytes.of(99), linkPreviewFetched = false,
        )
        db.messages().upsert(changed.toEntity().copy(
            linkPreviewImageData = changed.linkPreviewImageData, linkPreviewFetched = changed.linkPreviewFetched,
        ))
        store.saveReaction(fixture.reactions.single().copy(id = UUID.randomUUID(), senderName = "User"))
        store.saveMessageRepeat(RADIO_A, fixture.repeats.single().copy(receivedAt = AT.minusSeconds(10)))
        val before = rows()
        forbidWrites(listOf("channels", "messages", "reactions", "message_repeats"))
        assertIs<SimulatorConversationSeedResult.PreservedExisting>(seed(later(fixture)))
        assertEquals(before, rows())
    }

    @Test fun absentSidecarsOfAnExistingMessageAreNotRecreated() = runTest {
        val fixture = fixture(false)
        seed(fixture)
        val message = EntityKey(RADIO_A, fixture.messages.first().id)
        store.deleteMessageRepeats(message)
        store.deleteReactionsForMessage(message)
        store.updateMessageReactionSummary(message, null)
        store.updateMessageLinkPreview(message, null, null, null, null, false)
        val before = rows()
        assertIs<SimulatorConversationSeedResult.PreservedExisting>(seed(later(fixture)))
        assertEquals(before, rows())
    }

    @Test fun newParentMessageIdCollisionRefusesBeforeAnyMutation() = runTest {
        val fixture = fixture(false)
        store.saveMessage(fixture.messages.first().copy(contactID = UUID.randomUUID(), text = "Other conversation"))
        val before = rows()
        forbidWrites(listOf("contacts", "messages", "reactions", "message_repeats"))
        assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException> { seed(fixture) }.error)
        assertEquals(before, rows())
    }

    @Test fun newParentSidecarIdAndNullableOrphanCollisionsRefuseBeforeMutation() = runTest {
        for (kind in listOf("reaction-id", "reaction-orphan", "reaction-parent", "repeat-id", "repeat-orphan", "pending", "pending-parent")) {
            val fixture = fixture(false)
            when (kind) {
                "reaction-id" -> db.reactions().insert(fixture.reactions.single().copy(
                    messageID = UUID.randomUUID(), contactID = UUID.randomUUID()).toEntity())
                "reaction-orphan" -> db.reactions().insert(fixture.reactions.single().copy(
                    id = UUID.randomUUID(), contactID = UUID.randomUUID()).toEntity())
                "reaction-parent" -> db.reactions().insert(fixture.reactions.single().copy(
                    id = UUID.randomUUID(), messageID = UUID.randomUUID()).toEntity())
                "repeat-id" -> db.repeats().insert(
                    fixture.repeats.single().copy(messageID = UUID.randomUUID()).toEntity(RADIO_A, null))
                "repeat-orphan" -> db.repeats().insert(
                    fixture.repeats.single().copy(id = UUID.randomUUID()).toEntity(RADIO_A, null))
                "pending" -> db.pendingSends().insert(pending(messageID = fixture.messages.first().id).toEntity())
                "pending-parent" -> db.pendingSends().insert(pending(messageID = UUID.randomUUID()).toEntity())
            }
            val before = rows()
            val tables = listOf("contacts", "messages", "reactions", "message_repeats", "pending_sends")
            forbidWrites(tables)
            assertEquals(PersistenceStoreError.InvalidData,
                assertFailsWith<PersistenceStoreException> { seed(fixture) }.error, kind)
            assertEquals(before, rows(), kind)
            allowWrites(tables)
            db.reactions().clearRadio(RADIO_A.value)
            db.repeats().clearRadio(RADIO_A.value)
            db.pendingSends().clearRadio(RADIO_A.value)
        }
    }

    @Test fun absentParentWithOrphanConversationOrCanonicalKeyCollisionIsNotReconstructed() = runTest {
        val fixture = fixture(true)
        val orphan = fixture.messages.first().copy(id = UUID.randomUUID())
        store.saveMessage(orphan)
        var before = rows()
        val tables = listOf("channels", "messages", "reactions", "message_repeats")
        forbidWrites(tables)
        assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException> { seed(fixture) }.error)
        assertEquals(before, rows())
        allowWrites(tables)
        store.deleteMessage(EntityKey(RADIO_A, orphan.id))
        store.saveMessage(orphan.copy(channelIndex = 8u, deduplicationKey = "same-key"))
        before = rows()
        val keyed = fixture.copy(messages = SnapshotList.of(
            fixture.messages.first().copy(deduplicationKey = "same-key"), fixture.messages.last()))
        forbidWrites(tables)
        assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException> { seed(keyed) }.error)
        assertEquals(before, rows())
    }

    @Test fun lateSidecarFailureRollsBackParentAndMessagesAndAllowsCompleteRetry() = runTest {
        for (channel in listOf(false, true)) {
            val fixture = fixture(channel, if (channel) RADIO_B else RADIO_A)
            val before = rows()
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_seed_repeat BEFORE INSERT ON message_repeats " +
                    "BEGIN SELECT RAISE(ABORT, 'repeat failure after parent and message insert'); END",
            )
            assertIs<PersistenceStoreError.SaveFailed>(assertFailsWith<PersistenceStoreException> { seed(fixture) }.error)
            assertEquals(before, rows())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_seed_repeat")
            assertIs<SimulatorConversationSeedResult.Created>(seed(fixture))
        }
    }

    @Test fun ignoredAndAlteredMessageInsertsFailReadbackAndRollBackTheParent() = runTest {
        for (action in listOf("ignore", "alter")) {
            val fixture = fixture(false).copy(reactions = SnapshotList.empty(), repeats = SnapshotList.empty())
            val before = rows()
            val body = if (action == "ignore") "SELECT RAISE(IGNORE);" else
                "UPDATE messages SET text = 'altered' WHERE radioId = NEW.radioId AND id = NEW.id;"
            val timing = if (action == "ignore") "BEFORE" else "AFTER"
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER alter_seed_message $timing INSERT ON messages BEGIN $body END",
            )
            assertIs<PersistenceStoreError.SaveFailed>(assertFailsWith<PersistenceStoreException> { seed(fixture) }.error)
            assertEquals(before, rows())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER alter_seed_message")
            assertIs<SimulatorConversationSeedResult.Created>(seed(fixture))
            store.deleteMessage(EntityKey(RADIO_A, fixture.messages.first().id))
            store.deleteMessage(EntityKey(RADIO_A, fixture.messages.last().id))
            db.contacts().delete(RADIO_A.value, CONTACT_A)
        }
    }

    @Test fun actualCallerCancellationBeforeCommitRollsBackAndNextAttemptCanCreate() = runBlocking<Unit> {
        val fixture = fixture(false)
        val before = rows()
        val staged = CompletableDeferred<Unit>()
        val repository = repositoryContext()
        try {
            coroutineScope {
                val work = async(Dispatchers.Default) {
                    repository.write("seedSimulatorConversation") {
                        assertIs<SimulatorConversationSeedResult.Created>(executeSeed(fixture, repository))
                        staged.complete(Unit)
                        awaitCancellation()
                    }
                }
                withTimeout(10_000) { staged.await() }
                work.cancelAndJoin()
                assertTrue(work.isCancelled)
            }
            assertEquals(before, rows())
            assertIs<SimulatorConversationSeedResult.Created>(seed(fixture))
        } finally {
            repository.close()
        }
    }

    @Test fun equalFixtureIdsOnOtherRadioDoNotCollideOrChangeForeignRows() = runTest {
        val foreign = fixture(false, RADIO_B)
        seed(foreign)
        db.pendingSends().insert(pending(radio = RADIO_B, messageID = foreign.messages.first().id).toEntity())
        val before = rows()
        val local = fixture(false)
        assertIs<SimulatorConversationSeedResult.Created>(seed(local))
        assertEquals(before.contacts, rows().contacts.filter { it.radioId == RADIO_B.value }.toSet())
        assertEquals(before.messages, rows().messages.filter { it.radioId == RADIO_B.value }.toSet())
        assertEquals(before.reactions, rows().reactions.filter { it.radioId == RADIO_B.value }.toSet())
        assertEquals(before.repeats, rows().repeats.filter { it.radioId == RADIO_B.value }.toSet())
        assertEquals(before.pending, rows().pending.filter { it.radioId == RADIO_B.value }.toSet())
    }

    @Test fun orphanPendingConversationReferencesBlockOnlyNewParentsOnTheSameRadio() = runTest {
        for (channel in listOf(false, true)) {
            val fixture = fixture(channel, if (channel) RADIO_B else RADIO_A)
            val radio = fixture.messages.first().radioId
            db.pendingSends().insert(pending(radio = radio, messageID = UUID.randomUUID()).copy(
                kind = if (channel) PendingSendKind.CHANNEL else PendingSendKind.DM,
                contactID = if (channel) null else CONTACT_A, channelIndex = if (channel) 7u else null,
            ).toEntity())
            val before = rows()
            val tables = listOf("contacts", "channels", "messages", "reactions", "message_repeats", "pending_sends")
            forbidWrites(tables)
            assertEquals(PersistenceStoreError.InvalidData,
                assertFailsWith<PersistenceStoreException> { seed(fixture) }.error)
            assertEquals(before, rows())
            allowWrites(tables)
            db.pendingSends().clearRadio(radio.value)
            assertIs<SimulatorConversationSeedResult.Created>(seed(fixture))
        }
    }

    @Test fun targetedSidecarQueriesKeepIdentityAssociationRadioAndNullableParentScopesDistinct() = runTest {
        val fixture = fixture(false)
        val reaction = fixture.reactions.single()
        val repeat = fixture.repeats.single()
        db.reactions().insert(reaction.toEntity())
        db.repeats().insert(repeat.toEntity(RADIO_A, null))
        db.pendingSends().insert(pending(messageID = fixture.messages.first().id).toEntity())
        assertEquals(reaction.toEntity(), db.reactions().byId(RADIO_A.value, reaction.id))
        assertNull(db.reactions().byId(RADIO_B.value, reaction.id))
        assertEquals(repeat.toEntity(RADIO_A, null), db.repeats().byId(RADIO_A.value, repeat.id))
        assertNull(db.repeats().byId(RADIO_B.value, repeat.id))
        assertTrue(db.repeats().hasForMessage(RADIO_A.value, repeat.messageID))
        assertFalse(db.repeats().hasForMessage(RADIO_B.value, repeat.messageID))
        assertTrue(db.pendingSends().hasForMessage(RADIO_A.value, repeat.messageID))
        assertFalse(db.pendingSends().hasForMessage(RADIO_B.value, repeat.messageID))
        assertTrue(db.reactions().hasForConversation(RADIO_A.value, CONTACT_A, null))
        assertTrue(db.pendingSends().hasForConversation(RADIO_A.value, CONTACT_A, null))
        assertFalse(db.reactions().hasForConversation(RADIO_B.value, CONTACT_A, null))
        assertFalse(db.pendingSends().hasForConversation(RADIO_B.value, CONTACT_A, null))
        assertFalse(db.reactions().hasForConversation(RADIO_A.value, null, 7))
        assertFalse(db.pendingSends().hasForConversation(RADIO_A.value, null, 7))
        assertFalse(db.reactions().hasForConversation(RADIO_A.value, null, null))
        assertFalse(db.pendingSends().hasForConversation(RADIO_A.value, null, null))
    }

    @Test fun rawCaseChildIdentitiesAndAssociationsRefuseOnlyTheSameRadioWithoutDml() = runTest {
        val tables = listOf("message_repeats", "reactions", "pending_sends", "messages", "contacts", "channels")
        for (channel in listOf(false, true)) for (foreignRadio in listOf(false, true)) for (mixed in listOf(false, true)) {
            for (kind in listOf("message-id", "reaction-id", "repeat-id", "reaction-message",
                "repeat-message", "pending-message", "message-parent", "reaction-parent", "pending-parent", "radio-id")) {
                val localRadio = RadioId(UUID.fromString("abcdefab-6666-2222-3333-abcdefabcdef"))
                val fixture = alphabeticFixture(channel, localRadio)
                val radio = if (foreignRadio) RADIO_B else localRadio
                val first = fixture.messages.first()
                val contactID = first.contactID
                val otherParent = UUID.randomUUID()
                val table = when (kind) {
                    "message-id", "message-parent", "radio-id" -> "messages"
                    "reaction-id", "reaction-message", "reaction-parent" -> "reactions"
                    "repeat-id", "repeat-message" -> "message_repeats"
                    else -> "pending_sends"
                }
                when (table) {
                    "messages" -> db.messages().insert(first.copy(
                        radioId = radio, id = if (kind == "message-parent") UUID.randomUUID() else first.id,
                        contactID = if (kind == "message-parent") contactID else otherParent,
                        channelIndex = if (kind == "message-parent") first.channelIndex else null,
                    ).toEntity())
                    "reactions" -> db.reactions().insert(fixture.reactions.single().copy(
                        radioId = radio, id = if (kind == "reaction-id") fixture.reactions.single().id else UUID.randomUUID(),
                        messageID = if (kind == "reaction-message") first.id else UUID.randomUUID(),
                        contactID = if (kind == "reaction-parent") contactID else otherParent,
                        channelIndex = if (kind == "reaction-parent") first.channelIndex else null,
                    ).toEntity())
                    "message_repeats" -> db.repeats().insert(fixture.repeats.single().copy(
                        id = if (kind == "repeat-id") fixture.repeats.single().id else UUID.randomUUID(),
                        messageID = if (kind == "repeat-message") first.id else UUID.randomUUID(),
                    ).toEntity(radio, null))
                    else -> db.pendingSends().insert(pending(
                        radio, if (kind == "pending-message") first.id else UUID.randomUUID(),
                    ).copy(
                        kind = if (channel && kind == "pending-parent") PendingSendKind.CHANNEL else PendingSendKind.DM,
                        contactID = if (kind == "pending-parent") contactID else otherParent,
                        channelIndex = if (kind == "pending-parent") first.channelIndex else null,
                    ).toEntity())
                }
                val column = when {
                    kind == "radio-id" -> "radioId"
                    kind.endsWith("-id") -> "id"
                    kind.endsWith("-message") -> "messageID"
                    channel -> "radioId"
                    else -> "contactID"
                }
                val caseValue = if (mixed) "upper(substr(`$column`, 1, 8)) || lower(substr(`$column`, 9))"
                    else "lower(`$column`)"
                db.openHelper.writableDatabase.execSQL("UPDATE `$table` SET `$column` = $caseValue")
                val before = rawRows()
                if (foreignRadio) {
                    assertIs<SimulatorConversationSeedResult.Created>(seed(fixture))
                    assertEquals(before, rawRows(radio), "$channel/$kind")
                } else {
                    forbidWrites(tables)
                    assertEquals(PersistenceStoreError.InvalidData,
                        assertFailsWith<PersistenceStoreException> { seed(fixture) }.error, "$channel/$kind")
                    assertEquals(before, rawRows(), "$channel/$kind")
                    allowWrites(tables)
                }
                for (name in tables) db.openHelper.writableDatabase.execSQL("DELETE FROM `$name`")
            }
        }
    }

    @Test fun lateRawCaseIdentityTwinsFailReadbackRatherThanCommitLogicalDuplicates() = runTest {
        for (table in listOf("messages", "reactions", "message_repeats")) {
            val fixture = alphabeticFixture(false)
            val id = when (table) {
                "messages" -> fixture.messages.first().id
                "reactions" -> fixture.reactions.single().id
                else -> fixture.repeats.single().id
            }
            val before = rawRows()
            val unrelated = "'${UUID.randomUUID().canonicalString()}'"
            val associations = when (table) {
                "messages" -> mapOf("contactID" to unrelated, "channelIndex" to "NULL")
                "reactions" -> mapOf("contactID" to unrelated, "channelIndex" to "NULL", "messageID" to unrelated)
                else -> mapOf("messageID" to unrelated, "parentMessageID" to "NULL")
            }
            cloneRowAfterInsert(
                table, table, associations + ("id" to "lower(NEW.id)"),
                "id = NEW.id AND radioId = NEW.radioId", "NEW.id = '${id.canonicalString()}'",
            )
            assertSeedReadbackFailure(fixture)
            assertEquals(before, rawRows())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER late_seed_child")
            assertIs<SimulatorConversationSeedResult.Created>(seed(fixture))
            for (name in listOf("message_repeats", "reactions", "messages", "contacts")) {
                db.openHelper.writableDatabase.execSQL("DELETE FROM `$name`")
            }
        }
    }

    @Test fun existingParentPreservesRawCaseOrphanSidecarsWithoutInspectingThem() = runTest {
        for (channel in listOf(false, true)) {
            val fixture = alphabeticFixture(channel)
            saveParent(fixture.parent)
            db.reactions().insert(fixture.reactions.single().toEntity())
            db.repeats().insert(fixture.repeats.single().toEntity(RADIO_A, null))
            db.pendingSends().insert(pending(messageID = fixture.messages.first().id).toEntity())
            for (table in listOf("reactions", "message_repeats", "pending_sends")) {
                db.openHelper.writableDatabase.execSQL("UPDATE `$table` SET messageID = lower(messageID)")
            }
            val before = rawRows()
            val tables = listOf("contacts", "channels", "messages", "reactions", "message_repeats", "pending_sends")
            forbidWrites(tables)
            assertIs<SimulatorConversationSeedResult.PreservedExisting>(seed(fixture))
            assertEquals(before, rawRows())
            allowWrites(tables)
            for (table in listOf("message_repeats", "reactions", "pending_sends", "messages", "contacts", "channels")) {
                db.openHelper.writableDatabase.execSQL("DELETE FROM `$table`")
            }
        }
    }

    @Test fun lateRawCaseAssociationChangesFailCanonicalAndLogicalCohortReadbacks() = runTest {
        for ((table, column) in listOf(
            "messages" to "contactID", "reactions" to "contactID",
            "reactions" to "messageID", "message_repeats" to "messageID",
        )) {
            val fixture = alphabeticFixture(false)
            val before = rawRows()
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER raw_case_association AFTER INSERT ON `$table` BEGIN " +
                    "UPDATE `$table` SET `$column` = lower(NEW.`$column`) " +
                    "WHERE radioId = NEW.radioId AND id = NEW.id; END",
            )
            assertSeedReadbackFailure(fixture)
            assertEquals(before, rawRows())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER raw_case_association")
            assertIs<SimulatorConversationSeedResult.Created>(seed(fixture))
            for (name in listOf("message_repeats", "reactions", "messages", "contacts")) {
                db.openHelper.writableDatabase.execSQL("DELETE FROM `$name`")
            }
        }
    }

    @Test fun lateChildParentIdentityAndCohortTwinsRollBackOnlyWithinTheLogicalRadio() = runTest {
        val radio = RadioId(UUID.fromString("abcdefab-6666-2222-3333-abcdefabcdef"))
        for (channel in listOf(false, true)) for (identity in listOf(false, true)) {
            for (foreignRadio in listOf(false, true)) {
                val fixture = alphabeticFixture(channel, radio)
                val table = if (channel) "channels" else "contacts"
                val parentID = when (val parent = fixture.parent) {
                    is SimulatorConversationParent.Direct -> parent.contact.id
                    is SimulatorConversationParent.Channel -> parent.channel.id
                }
                assertNotEquals(parentID.canonicalString(), parentID.toString())
                assertNotEquals(radio.value.canonicalString(), radio.value.toString())
                val alternateID = UUID.randomUUID().canonicalString()
                val replacements = mutableMapOf(
                    "id" to if (identity) "lower(id)" else "'$alternateID'",
                    "radioId" to if (foreignRadio) "'${RADIO_B.value.canonicalString()}'"
                        else if (identity) "radioId" else "upper(substr(radioId, 1, 8)) || lower(substr(radioId, 9))",
                )
                if (identity) {
                    if (channel) replacements["index"] = "9"
                    else replacements["publicKey"] = "X'${"66".repeat(32)}'"
                }
                val before = rawRows()
                cloneRowAfterInsert(
                    table, "messages", replacements,
                    "id = '${parentID.canonicalString()}' AND radioId = NEW.radioId",
                    "NEW.id = '${fixture.messages.first().id.canonicalString()}'",
                )
                if (foreignRadio) {
                    assertIs<SimulatorConversationSeedResult.Created>(seed(fixture))
                    assertEquals(1, rawRows(RADIO_B).getValue(table).size)
                    assertEquals(1, rawRows(radio).getValue(table).size)
                } else {
                    assertSeedReadbackFailure(fixture)
                    assertEquals(before, rawRows())
                }
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER late_seed_child")
                if (!foreignRadio) assertIs<SimulatorConversationSeedResult.Created>(seed(fixture))
                for (name in listOf("message_repeats", "reactions", "pending_sends", "messages", "contacts", "channels")) {
                    db.openHelper.writableDatabase.execSQL("DELETE FROM `$name`")
                }
            }
        }
    }

    @Test fun incompatibleContactIdentityAndChannelSecretRefuseWithoutChangingExistingConversations() = runTest {
        for (channel in listOf(false, true)) {
            val fixture = fixture(channel, if (channel) RADIO_B else RADIO_A)
            val incompatible = when (val parent = fixture.parent) {
                is SimulatorConversationParent.Direct ->
                    SimulatorConversationParent.Direct(parent.contact.copy(publicKey = key(0x66)))
                is SimulatorConversationParent.Channel ->
                    SimulatorConversationParent.Channel(parent.channel.copy(secret = Bytes(ByteArray(16) { 0x66 })))
            }
            saveParent(incompatible)
            val before = rows()
            assertEquals(PersistenceStoreError.InvalidData,
                assertFailsWith<PersistenceStoreException> { seed(fixture) }.error)
            assertEquals(before, rows())
        }
    }

    @Test fun existingContactKeepsClearedCountersAndOnlyAdvancesApprovedLastModified() = runTest {
        val fixture = fixture(false)
        val original = assertIs<SimulatorConversationParent.Direct>(fixture.parent).contact.copy(
            unreadCount = 0, lastMessageDate = AT.minusSeconds(700), lastModified = 10u,
            nickname = "User", avatarImageData = Bytes.of(42),
        )
        store.saveContact(original)
        val expected = original.copy(lastModified = 20u)
        val request = fixture.copy(parent = SimulatorConversationParent.Direct(original.copy(
            unreadCount = 3, lastMessageDate = AT, lastModified = 20u, nickname = null, avatarImageData = null)))
        val before = rows()
        val result = assertIs<SimulatorConversationSeedResult.PreservedExisting>(seed(request))
        assertEquals(SimulatorConversationParent.Direct(expected), result.parent)
        assertEquals(before.copy(contacts = setOf(expected.toEntity())), rows())
        assertTrue(rows().messages.isEmpty())
    }

    @Test fun malformedStoredContactFreshnessFailsWithoutRepairingItOrSeedingChildren() = runTest {
        val fixture = fixture(false)
        store.saveContact(assertIs<SimulatorConversationParent.Direct>(fixture.parent).contact)
        for (invalid in listOf(-1L, 4_294_967_296L)) {
            db.openHelper.writableDatabase.execSQL("UPDATE contacts SET lastModified = ?", arrayOf(invalid))
            val before = rows()
            val error = assertFailsWith<PersistenceStoreException> { seed(fixture) }
            assertIs<PersistenceStoreError.SaveFailed>(error.error)
            assertEquals(before, rows())
        }
    }

    @Test fun ignoredAndAlteredSidecarsFailExactReadbackAndRollBackAllRows() = runTest {
        for (table in listOf("reactions", "message_repeats")) for (ignore in listOf(false, true)) {
            val fixture = fixture(false)
            val before = rows()
            val assignment = if (table == "reactions") "senderName = 'altered'" else "rssi = -123"
            val body = if (ignore) "SELECT RAISE(IGNORE);" else
                "UPDATE $table SET $assignment WHERE radioId = NEW.radioId AND id = NEW.id;"
            val timing = if (ignore) "BEFORE" else "AFTER"
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER alter_seed_sidecar $timing INSERT ON $table BEGIN $body END")
            assertIs<PersistenceStoreError.SaveFailed>(assertFailsWith<PersistenceStoreException> { seed(fixture) }.error)
            assertEquals(before, rows())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER alter_seed_sidecar")
        }
        assertIs<SimulatorConversationSeedResult.Created>(seed(fixture(false)))
    }

    @Test fun childInsertThatAltersTheNewParentFailsFinalReadbackAndRollsBackTheWholeUnit() = runTest {
        for (channel in listOf(false, true)) {
            val fixture = fixture(channel, if (channel) RADIO_B else RADIO_A)
            val before = rows()
            val table = if (channel) "channels" else "contacts"
            val parentPredicate = if (channel) "`index` = NEW.channelIndex" else "id = NEW.contactID"
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER alter_seed_parent AFTER INSERT ON messages BEGIN " +
                    "UPDATE $table SET unreadCount = 99 WHERE radioId = NEW.radioId AND $parentPredicate; END",
            )
            assertIs<PersistenceStoreError.SaveFailed>(assertFailsWith<PersistenceStoreException> { seed(fixture) }.error)
            assertEquals(before, rows())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER alter_seed_parent")
            assertIs<SimulatorConversationSeedResult.Created>(seed(fixture))
        }
    }

    @Test fun emptyBundleRejectsMessageAttachedByParentInsertAndRetriesWithoutAdoptingIt() = runTest {
        for (channel in listOf(false, true)) {
            val fixture = fixture(channel, if (channel) RADIO_B else RADIO_A).copy(
                messages = SnapshotList.empty(), reactions = SnapshotList.empty(), repeats = SnapshotList.empty(),
            )
            val radio = if (channel) RADIO_B else RADIO_A
            db.messages().insert(message(radio, contactID = UUID.randomUUID(), text = "template").toEntity())
            val before = rows()
            cloneRowAfterInsert(
                "messages", if (channel) "channels" else "contacts",
                mapOf("id" to "NEW.id", "contactID" to if (channel) "NULL" else "NEW.id",
                    "channelIndex" to if (channel) "NEW.`index`" else "NULL"),
                "text = 'template' AND radioId = NEW.radioId",
            )
            assertSeedReadbackFailure(fixture)
            assertEquals(before, rows())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER late_seed_child")
            assertTrue(assertIs<SimulatorConversationSeedResult.Created>(seed(fixture)).messages.isEmpty())
        }
    }

    @Test fun lateDifferentParentDeduplicationCollisionRollsBackAndAllowsRetry() = runTest {
        val original = fixture(false)
        val fixture = original.copy(messages = SnapshotList.of(
            original.messages.first().copy(deduplicationKey = "fixture-key"), original.messages.last(),
        ))
        db.messages().insert(message(contactID = UUID.randomUUID(), text = "template").toEntity())
        val before = rows()
        cloneRowAfterInsert(
            "messages", "messages",
            mapOf("id" to "NEW.contactID", "deduplicationKey" to "NEW.deduplicationKey"),
            "text = 'template'", "NEW.id = '${fixture.messages.first().id.canonicalString()}'",
        )
        assertSeedReadbackFailure(fixture)
        assertEquals(before, rows())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER late_seed_child")
        assertIs<SimulatorConversationSeedResult.Created>(seed(fixture))
    }

    @Test fun lateParentReactionWithUnrelatedMessageFailsWholeConversationReadback() = runTest {
        val fixture = fixture(false)
        db.reactions().insert(fixture.reactions.single().copy(
            id = UUID.randomUUID(), messageID = UUID.randomUUID(), contactID = UUID.randomUUID(),
        ).toEntity())
        val before = rows()
        cloneRowAfterInsert("reactions", "contacts", mapOf("id" to "NEW.id", "contactID" to "NEW.id"))
        assertSeedReadbackFailure(fixture)
        assertEquals(before, rows())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER late_seed_child")
        assertIs<SimulatorConversationSeedResult.Created>(seed(fixture))
    }

    @Test fun noncanonicalSidecarIdentityTextFailsCanonicalReadbackAndRollsBack() = runTest {
        for (table in listOf("reactions", "message_repeats")) {
            val original = fixture(false)
            val alphabeticID = UUID.fromString("abcdefab-cdef-abcd-efab-cdefabcdefab")
            val fixture = if (table == "reactions") original.copy(
                reactions = original.reactions.map { it.copy(id = alphabeticID) }.snapshot(),
            ) else original.copy(repeats = original.repeats.map { it.copy(id = alphabeticID) }.snapshot())
            val before = rows()
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER sidecar_raw_case AFTER INSERT ON $table BEGIN " +
                    "UPDATE $table SET id = lower(NEW.id) WHERE radioId = NEW.radioId AND id = NEW.id; END",
            )
            assertSeedReadbackFailure(fixture)
            assertEquals(before, rows())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER sidecar_raw_case")
            assertIs<SimulatorConversationSeedResult.Created>(seed(fixture))
            db.messages().clearRadio(RADIO_A.value)
            db.reactions().clearRadio(RADIO_A.value)
            db.contacts().clearRadio(RADIO_A.value)
        }
    }

    @Test fun latePendingMessageAndParentReferencesRollBackAndAllowRetry() = runTest {
        for (channel in listOf(false, true)) for (byMessage in listOf(false, true)) {
            val radio = if (channel) RADIO_B else RADIO_A
            val fixture = fixture(channel, radio)
            db.pendingSends().insert(pending(radio).copy(contactID = UUID.randomUUID()).toEntity())
            val before = rows()
            val replacement = if (byMessage) mapOf("messageID" to "NEW.id") else mapOf(
                "contactID" to if (channel) "NULL" else "NEW.id",
                "channelIndex" to if (channel) "NEW.`index`" else "NULL",
                "kindRawValue" to if (channel) "1" else "0",
            )
            cloneRowAfterInsert(
                "pending_sends", if (byMessage) "messages" else if (channel) "channels" else "contacts",
                replacement + ("id" to "NEW.radioId"), condition = if (byMessage) "NEW.text != 'second'" else "1",
            )
            assertSeedReadbackFailure(fixture)
            assertEquals(before, rows())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER late_seed_child")
            assertIs<SimulatorConversationSeedResult.Created>(seed(fixture))
            db.messages().clearRadio(radio.value)
            db.reactions().clearRadio(radio.value)
            db.pendingSends().clearRadio(radio.value)
            db.contacts().clearRadio(radio.value)
            db.channels().clearRadio(radio.value)
        }
    }

    @Test fun malformedNewConversationReferencesAndDuplicateIdsRejectBeforeParentInsert() = runTest {
        val fixture = fixture(false)
        val cases = listOf(
            fixture.copy(messages = SnapshotList.of(fixture.messages.first(), fixture.messages.first())),
            fixture.copy(messages = fixture.messages.map { it.copy(radioId = RADIO_B) }.snapshot()),
            fixture.copy(messages = fixture.messages.map { it.copy(contactID = UUID.randomUUID()) }.snapshot()),
            fixture.copy(reactions = SnapshotList.of(fixture.reactions.single(), fixture.reactions.single())),
            fixture.copy(reactions = fixture.reactions.map { it.copy(messageID = UUID.randomUUID()) }.snapshot()),
            fixture.copy(reactions = fixture.reactions.map { it.copy(radioId = RADIO_B) }.snapshot()),
            fixture.copy(reactions = fixture.reactions.map { it.copy(contactID = UUID.randomUUID()) }.snapshot()),
            fixture.copy(repeats = SnapshotList.of(fixture.repeats.single(), fixture.repeats.single())),
            fixture.copy(repeats = fixture.repeats.map { it.copy(messageID = UUID.randomUUID()) }.snapshot()),
        )
        val before = rows()
        forbidWrites(listOf("contacts", "messages", "reactions", "message_repeats"))
        for (request in cases) {
            assertEquals(PersistenceStoreError.InvalidData,
                assertFailsWith<PersistenceStoreException> { seed(request) }.error)
            assertEquals(before, rows())
        }
    }

    @Test fun committedConcurrentUserEditIsPreservedByASeparateRepositoryContext() = runBlocking<Unit> {
        val fixture = fixture(true)
        seed(fixture)
        val editing = repositoryContext()
        val entered = CompletableDeferred<Unit>()
        val reseeding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val changed = assertNotNull(db.messages().byId(RADIO_A.value, fixture.messages.first().id))
            .copy(isRead = true, mentionSeen = true, reactionSummary = "user")
        try {
            coroutineScope {
                val edit = async(Dispatchers.Default) {
                    editing.write("userEdit") {
                        database.messages().upsert(changed)
                        entered.complete(Unit)
                        release.await()
                        save()
                    }
                }
                withTimeout(10_000) { entered.await() }
                val reseed = async(Dispatchers.Default) {
                    reseeding.complete(Unit)
                    seed(later(fixture))
                }
                withTimeout(10_000) { reseeding.await() }
                release.complete(Unit)
                withTimeout(10_000) {
                    edit.await()
                    assertIs<SimulatorConversationSeedResult.PreservedExisting>(reseed.await())
                }
            }
            assertEquals(changed, db.messages().byId(RADIO_A.value, changed.id))
        } finally {
            release.complete(Unit)
            editing.close()
        }
    }

    @Test fun concurrentFirstSeedsFromSeparateContextsCreateOnceAndPreserveTheCommittedUnit() = runBlocking<Unit> {
        val fixture = fixture(true)
        val start = CompletableDeferred<Unit>()
        val results = coroutineScope {
            val one = async(Dispatchers.Default) { start.await(); seed(fixture) }
            val two = async(Dispatchers.Default) { start.await(); seed(later(fixture)) }
            start.complete(Unit)
            withTimeout(10_000) { listOf(one.await(), two.await()) }
        }
        val created = results.filterIsInstance<SimulatorConversationSeedResult.Created>().single()
        val preserved = results.filterIsInstance<SimulatorConversationSeedResult.PreservedExisting>().single()
        assertEquals(created.parent, preserved.parent)
        assertEquals(created.messages.toSet(), rows().messages.map { it.toDTO() }.toSet())
        assertEquals(1, rows().channels.size)
        assertEquals(1, rows().reactions.size)
        assertEquals(1, rows().repeats.size)
    }
}
