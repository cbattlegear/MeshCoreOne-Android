// PortedFrom: MC1Services/Tests/MC1ServicesTests/ChannelFloodScopeMigrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/RadioIDMigrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/RepeaterUnreadMigrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/SortDateBackfillMigrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/SortDateResetMigrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Native-v1 boundary proposals, not executed Apple migrations or self-approved source equivalents.
package com.meshcoreone.android.core.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.RoomDatabase
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.DatabaseValueException
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InitialSchemaMigrationBoundaryTest : RepositoryTest() {
    // Independently pinned Foundation.Date facts and epoch arithmetic: WP-202/independent-vector-provenance.json.
    private val foundationDistantPast = Instant.ofEpochSecond(-62_135_769_600)

    private fun legacyChannel(index: UByte, region: String?, mode: String? = null): ChannelDTO =
        ChannelDTO.fromLegacyFields(
            UUID.randomUUID(), RADIO_A, index, "Chan$index", Bytes(ByteArray(16)), true, null, 0,
            null, null, null, mode, region,
        )

    @Test fun absentLegacyFloodModeRetainsRegionWhileExplicitInheritIsNotGuessed() = runTest {
        val absent = legacyChannel(1u, "Germany")
        store.saveChannel(absent)
        val explicit = legacyChannel(2u, "Germany", "inherit")
        store.saveChannel(explicit)
        store.warmUp()
        val decoded = assertNotNull(store.fetchChannel(RADIO_A, 1u))
        assertEquals("specific", decoded.floodScopeModeRawValue)
        assertEquals(ChannelFloodScope.Region("Germany"), decoded.floodScope)
        val raw = assertNotNull(store.fetchChannel(RADIO_A, 2u))
        assertEquals("inherit", raw.floodScopeModeRawValue)
        assertEquals("Germany", raw.regionScope)
        assertEquals(ChannelFloodScope.Inherit, raw.floodScope)
    }

    @Test fun nilAndMixedLegacyChannelFieldDefaultsRemainDistinct() = runTest {
        store.saveChannel(legacyChannel(1u, null))
        store.saveChannel(legacyChannel(2u, "Germany"))
        store.saveChannel(legacyChannel(3u, "France"))
        assertEquals(
            listOf(ChannelFloodScope.Inherit, ChannelFloodScope.Region("Germany"), ChannelFloodScope.Region("France")),
            store.fetchChannels(RADIO_A).map { it.floodScope },
        )
        assertEquals("inherit", assertNotNull(store.fetchChannel(RADIO_A, 1u)).floodScopeModeRawValue)
        assertNull(assertNotNull(store.fetchChannel(RADIO_A, 1u)).regionScope)
        assertTrue(store.fetchChannels(RADIO_A).all { it.notificationLevel == NotificationLevel.ALL })
        assertTrue(store.fetchChannels(RADIO_A).all { it.unreadMentionCount == 0L && !it.isFavorite })
    }

    @Test fun repeatedWarmupCannotRewriteUserFloodChoicesOrUnknownRawModes() = runTest {
        val dto = legacyChannel(1u, "Germany")
        store.saveChannel(dto)
        store.warmUp()
        store.warmUp()
        assertEquals(dto, store.fetchChannel(RADIO_A, 1u))
        store.setChannelFloodScope(entity(id = dto.id), ChannelFloodScope.AllRegions)
        store.warmUp()
        store.warmUp()
        val choice = assertNotNull(store.fetchChannel(RADIO_A, 1u))
        assertEquals(ChannelFloodScope.AllRegions, choice.floodScope)
        assertEquals("allRegions", choice.floodScopeModeRawValue)
        assertNull(choice.regionScope)
        val future = legacyChannel(2u, "raw region", "futureMode")
        store.saveChannel(future)
        store.warmUp()
        assertEquals("futureMode", assertNotNull(store.fetchChannel(RADIO_A, 2u)).floodScopeModeRawValue)
        assertEquals("raw region", assertNotNull(store.fetchChannel(RADIO_A, 2u)).regionScope)
    }

    @Test fun nativeV1StoresExplicitRadioIdentityAcrossEveryRadioOwnedTable() = runTest {
        val d = device()
        assertNotEquals(d.id, d.radioId.value)
        store.saveDevice(d)
        val c = store.saveContact(RADIO_A, frame()).id
        store.saveChannel(legacyChannel(1u, null))
        val m = message(contactID = c)
        store.saveMessage(m)
        store.saveMessageRepeat(RADIO_A, MessageRepeatDTO(messageID = m.id, receivedAt = AT, pathNodes = Bytes.of(0x80)))
        store.saveReaction(ReactionDTO(messageID = m.id, emoji = "\uD83D\uDC4D", senderName = "Peer",
            messageHash = "AABBCCDD", rawText = "reaction", receivedAt = AT, contactID = c, radioId = RADIO_A))
        val s = session()
        store.saveRemoteNodeSessionDTO(s)
        store.saveRoomMessage(RADIO_A, RoomMessageDTO(sessionID = s.id, authorKeyPrefix = Bytes.of(1, 2, 3, 4),
            text = "room", timestamp = 42u, createdAt = AT))
        store.createSavedTracePath(RADIO_A, "path", Bytes.of(0x80), 1, TracePathRunDTO(
            UUID.randomUUID(), AT, true, 1, SnapshotList.of(-1.5)))
        store.upsertDiscoveredNode(RADIO_A, frame(2))
        store.saveBlockedChannelSender(BlockedChannelSenderDTO(name = "Blocked", radioId = RADIO_A, dateBlocked = AT))
        store.saveRxLogEntry(rx())
        store.upsertPendingSend(pending(messageID = m.id))
        store.flushPendingRxLogEntries()
        for (table in listOf("devices", "contacts", "channels", "messages", "message_repeats", "reactions",
            "remote_node_sessions", "room_messages", "saved_trace_paths", "trace_path_runs", "discovered_nodes",
            "blocked_channel_senders", "rx_log_entries", "pending_sends")) {
            db.openHelper.readableDatabase.query("SELECT radioId FROM $table").use { rows ->
                assertTrue(rows.moveToFirst(), table)
                do { assertEquals(RADIO_A.canonicalString, rows.getString(0), table) } while (rows.moveToNext())
            }
        }
        assertEquals(MeshCoreDatabase.SCHEMA_VERSION, db.openHelper.readableDatabase.version)
        assertEquals(RADIO_A, assertNotNull(store.fetchDevice(d.id)).radioId)
    }

    @Test fun explicitOutgoingDedupKeyUsesPinnedContentBytesWithoutAHistoricalSweep() = runTest {
        val timestamp = 1_704_067_200u
        val expected = "dm-00000000-0000-0000-0000-000000000003-1704067200-41ABEB9C"
        assertEquals(expected, RepositoryDeduplicationKey.contentBased(CONTACT_A, null, null, timestamp, "Hello mesh"))
        val keyed = message(text = "Hello mesh", timestamp = timestamp).copy(deduplicationKey = expected)
        val unkeyed = keyed.copy(id = UUID.randomUUID(), deduplicationKey = null)
        store.saveMessage(keyed)
        store.saveMessage(unkeyed)
        store.warmUp()
        assertEquals(expected, assertNotNull(store.fetchMessage(entity(id = keyed.id))).deduplicationKey)
        assertNull(assertNotNull(store.fetchMessage(entity(id = unkeyed.id))).deduplicationKey)
        val letterId = UUID.fromString("a1b2c3d4-e5f6-4789-abcd-0123456789ef")
        val utf8 = "H\u00e9llo mesh \uD83C\uDF0D"
        val letterExpected = "dm-A1B2C3D4-E5F6-4789-ABCD-0123456789EF-1704067200-EB435A94"
        assertEquals(Bytes.of(0x48, 0xC3, 0xA9, 0x6C, 0x6C, 0x6F, 0x20, 0x6D, 0x65, 0x73, 0x68, 0x20, 0xF0, 0x9F, 0x8C, 0x8D),
            Bytes.utf8(utf8))
        assertEquals(letterExpected, RepositoryDeduplicationKey.contentBased(letterId, null, null, timestamp, utf8))
        val letterMessage = message(contactID = letterId, text = utf8, timestamp = timestamp).copy(deduplicationKey = letterExpected)
        store.saveMessage(letterMessage)
        assertEquals(letterExpected, assertNotNull(store.fetchMessage(entity(id = letterMessage.id))).deduplicationKey)
    }

    @Test fun incomingNilAndExistingDedupKeysArePreservedAcrossWarmup() = runTest {
        val incoming = message(direction = MessageDirection.INCOMING).copy(deduplicationKey = null)
        val existing = message().copy(deduplicationKey = "dm-existing-key-12345678")
        store.saveMessage(incoming)
        store.saveMessage(existing)
        store.warmUp()
        store.warmUp()
        assertNull(assertNotNull(store.fetchMessage(entity(id = incoming.id))).deduplicationKey)
        assertEquals("dm-existing-key-12345678", assertNotNull(store.fetchMessage(entity(id = existing.id))).deduplicationKey)
    }

    @Test fun repeatedWarmupPreservesRestoredRadioAndRegistryLookupResolvesIt() = runTest {
        val d = device().copy(publicKey = key(0x11))
        store.saveDevice(d)
        val c = store.saveContact(RADIO_A, frame()).id
        store.warmUp()
        store.warmUp()
        assertEquals(d.id, assertNotNull(store.fetchDevice(d.id)).id)
        assertEquals(RADIO_A, assertNotNull(store.fetchDevice(d.id)).radioId)
        assertEquals("00000000-0000-0000-0000-000000000001",
            assertNotNull(store.fetchDevice(d.id)).radioId.canonicalString)
        assertEquals(c, store.fetchContacts(RADIO_A).single().id)
        assertEquals(d, store.fetchDevice(RADIO_A))
        assertNull(store.fetchDevice(RadioId(d.id)))
    }

    @Test fun newRepeaterDefaultsAndBadgeFiltersPreserveChatUnread() = runTest {
        val chat = contact().copy(unreadCount = 4)
        val repeater = contact(id = UUID.randomUUID(), publicKey = key(2))
            .copy(typeRawValue = ContactType.REPEATER.rawValue)
        store.saveContact(chat)
        store.saveContact(repeater)
        assertEquals(0L, assertNotNull(store.fetchContact(entity(id = repeater.id))).unreadCount)
        assertEquals(0L, assertNotNull(store.fetchContact(entity(id = repeater.id))).unreadMentionCount)
        store.saveContact(repeater.copy(unreadCount = 42, unreadMentionCount = 3))
        store.warmUp()
        assertEquals(4L, store.getTotalUnreadCounts(RADIO_A).contacts)
        assertEquals(42L, assertNotNull(store.fetchContact(entity(id = repeater.id))).unreadCount)
        assertEquals(3L, assertNotNull(store.fetchContact(entity(id = repeater.id))).unreadMentionCount)
    }

    @Test fun repeaterSessionDefaultsAndBadgeFiltersPreserveRoomUnread() = runTest {
        val room = session().copy(unreadCount = 5)
        val repeater = session(publicKey = key(2)).copy(role = RemoteNodeRole.REPEATER)
        store.saveRemoteNodeSessionDTO(room)
        store.saveRemoteNodeSessionDTO(repeater)
        assertEquals(0L, assertNotNull(store.fetchRemoteNodeSession(entity(id = repeater.id))).unreadCount)
        store.saveRemoteNodeSessionDTO(repeater.copy(unreadCount = 99))
        store.warmUp()
        assertEquals(5L, store.getTotalUnreadCounts(RADIO_A).rooms)
        assertEquals(99L, assertNotNull(store.fetchRemoteNodeSession(entity(id = repeater.id))).unreadCount)
    }

    @Test fun repeatedWarmupDoesNotClearLaterRepeaterCounters() = runTest {
        val repeater = contact().copy(typeRawValue = ContactType.REPEATER.rawValue)
        store.saveContact(repeater)
        val historicalSeven = contact(id = UUID.randomUUID(), publicKey = key(7))
            .copy(typeRawValue = ContactType.REPEATER.rawValue, unreadCount = 7)
        store.saveContact(historicalSeven)
        store.warmUp()
        store.incrementUnreadCount(entity())
        store.warmUp()
        store.warmUp()
        assertEquals(1L, assertNotNull(store.fetchContact(entity())).unreadCount)
        assertEquals(7L, assertNotNull(store.fetchContact(entity(id = historicalSeven.id))).unreadCount)
        assertEquals(0L, store.getTotalUnreadCounts(RADIO_A).contacts)
    }

    @Test fun newMessagesStartWithCompleteArrivalSortDatesNotSchemaSentinels() = runTest {
        val dates = listOf(Instant.ofEpochSecond(-1, 500_000_000), AT.plusNanos(1), AT.plusNanos(999_999_999))
        val messages = dates.map { message(timestamp = 1u, createdAt = it) }
        for (m in messages) store.saveMessage(m)
        val sourceRows = listOf(
            message(text = "Hello", timestamp = 1_704_067_200u, createdAt = Instant.ofEpochSecond(1_704_067_200)),
            message(text = "World", timestamp = 1_704_070_800u, createdAt = Instant.ofEpochSecond(1_704_070_800)),
        )
        for (m in sourceRows) store.saveMessage(m)
        store.warmUp()
        for (m in messages + sourceRows) {
            val stored = assertNotNull(store.fetchMessage(entity(id = m.id)))
            assertEquals(m.createdAt, stored.createdAt)
            assertEquals(m.createdAt, stored.sortDate)
        }
        assertEquals(dates + listOf(Instant.ofEpochSecond(1_704_067_200), Instant.ofEpochSecond(1_704_070_800)),
            store.fetchMessages(entity()).map { it.sortDate })
        assertEquals(listOf("Hello", "World"), store.fetchMessages(entity()).takeLast(2).map { it.text })
    }

    @Test fun explicitSortDatesSurviveRepeatedWarmupAndTheSameNativeSchemaReopen() = runTest {
        val name = "sort.db"
        fun open(): MeshCoreDatabase = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        var file = open()
        var repository = RoomPersistenceStore(file, owner, clock)
        try {
            val m = message().copy(sortDate = AT.minusSeconds(3600).plusNanos(7))
            val buried = message(text = "Buried backlog", timestamp = 1_700_000_000u,
                createdAt = Instant.ofEpochSecond(1_704_067_200)).copy(sortDate = Instant.ofEpochSecond(1_700_000_000))
            val laterReskew = message(text = "Hello", timestamp = 1_704_067_200u,
                createdAt = Instant.ofEpochSecond(1_704_067_200)).copy(sortDate = foundationDistantPast)
            repository.saveMessage(m)
            repository.saveMessage(buried)
            repository.saveMessage(laterReskew)
            repository.warmUp()
            repository.warmUp()
            assertEquals(m.sortDate, assertNotNull(repository.fetchMessage(entity(id = m.id))).sortDate)
            assertEquals(Instant.ofEpochSecond(1_700_000_000),
                assertNotNull(repository.fetchMessage(entity(id = buried.id))).sortDate)
            assertEquals(foundationDistantPast, assertNotNull(repository.fetchMessage(entity(id = laterReskew.id))).sortDate)
            repository.close()
            file.close()
            file = open()
            repository = RoomPersistenceStore(file, owner, clock)
            repository.warmUp()
            val preserved = assertNotNull(repository.fetchMessage(entity(id = m.id)))
            assertEquals(m.createdAt, preserved.createdAt)
            assertEquals(m.sortDate, preserved.sortDate)
            val preservedBuried = assertNotNull(repository.fetchMessage(entity(id = buried.id)))
            assertEquals("Buried backlog", preservedBuried.text)
            assertEquals(Instant.ofEpochSecond(1_704_067_200), preservedBuried.createdAt)
            assertEquals(Instant.ofEpochSecond(1_700_000_000), preservedBuried.sortDate)
            val preservedReskew = assertNotNull(repository.fetchMessage(entity(id = laterReskew.id)))
            assertEquals("Hello", preservedReskew.text)
            assertEquals(Instant.ofEpochSecond(1_704_067_200), preservedReskew.createdAt)
            assertEquals(foundationDistantPast, preservedReskew.sortDate)
            assertEquals(MeshCoreDatabase.SCHEMA_VERSION, file.openHelper.readableDatabase.version)
        } finally {
            repository.close()
            file.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun missingOrMalformedSortColumnsFailWithoutNormalizingRealRows() = runTest {
        val m = message()
        store.saveMessage(m)
        assertFailsWith<SQLiteConstraintException> {
            db.openHelper.writableDatabase.execSQL("UPDATE messages SET sortDate_seconds = NULL")
        }
        assertEquals(m.sortDate, assertNotNull(store.fetchMessage(entity(id = m.id))).sortDate)
        db.openHelper.writableDatabase.execSQL("UPDATE messages SET sortDate_nanos = 1000000000")
        val failure = assertFailsWith<PersistenceStoreException> { store.fetchMessage(entity(id = m.id)) }
        assertEquals("Instant.nanos", assertIs<DatabaseValueException>(failure.cause).field)
        db.openHelper.readableDatabase.query("SELECT sortDate_nanos FROM messages").use {
            assertTrue(it.moveToFirst())
            assertEquals(1_000_000_000, it.getInt(0))
        }
    }
}
