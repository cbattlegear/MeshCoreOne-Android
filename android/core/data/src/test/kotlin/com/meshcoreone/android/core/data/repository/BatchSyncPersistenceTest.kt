// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreBatchSyncTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BatchSyncPersistenceTest : RepositoryTest() {
    private fun info(index: UByte, name: String = "Slot$index", secret: Int = index.toInt()): ChannelInfo =
        ChannelInfo(index, name, Bytes(ByteArray(16) { secret.toByte() }))
    private suspend fun slot(index: UByte, count: Int = 3): UUID {
        val id = store.saveChannel(RADIO_A, info(index))
        repeat(count) {
            val m = message(contactID = null, channelIndex = index, text = "msg $it")
            store.saveMessage(m)
            store.saveReaction(ReactionDTO(messageID = m.id, emoji = "\uD83D\uDC4D", senderName = "peer",
                messageHash = "h$it", rawText = "reaction", receivedAt = AT, channelIndex = index, radioId = RADIO_A))
            store.incrementChannelUnreadCount(entity(id = id))
        }
        return id
    }

    @Test @OriginalCase("PersistenceStoreBatchSyncTests::batchSaveChannels inserts new and updates existing in one pass()")
    fun batchUpsertsExistingAndNewSlots() = runTest {
        store.saveChannel(RADIO_A, info(1u, "Old", 0x11))
        val rows = store.batchSaveChannels(RADIO_A, SnapshotList.of(info(1u, "New", 0x99), info(2u, "Two", 0x22)),
            SnapshotList.empty(), 8u)
        assertEquals(listOf(1.toUByte(), 2.toUByte()), rows.map { it.index })
        assertEquals("New", rows[0].name)
        assertEquals(Bytes(ByteArray(16) { 0x99.toByte() }), rows[0].secret)
        assertEquals("Two", rows[1].name)
    }

    @Test @OriginalCase("PersistenceStoreBatchSyncTests::batchSaveChannels deletes stale rows at unconfigured indices()")
    @OriginalCase("PersistenceStoreBatchSyncTests::batchSaveChannels prunes orphans beyond device capacity()")
    @OriginalCase("PersistenceStoreBatchSyncTests::batchSaveChannels leaves circuit-breaker-skipped slots untouched()")
    fun unconfiguredAndCapacityPrunesDoNotDeleteSkippedSlots() = runTest {
        for (i in listOf(1u, 2u, 3u, 10u)) store.saveChannel(RADIO_A, info(i.toUByte()))
        val rows = store.batchSaveChannels(RADIO_A, SnapshotList.of(info(1u, "OneUpdated")),
            SnapshotList.of(2u), 8u)
        assertEquals(listOf(1.toUByte(), 3.toUByte()), rows.map { it.index })
        assertEquals("OneUpdated", rows.first().name)
        assertEquals("Slot3", rows.last().name)
        assertNull(store.fetchChannel(RADIO_A, 2u))
        assertNull(store.fetchChannel(RADIO_A, 10u))
    }

    @Test @OriginalCase("PersistenceStoreBatchSyncTests::batchSaveChannels handles non-contiguous configured indices()")
    @OriginalCase("PersistenceStoreBatchSyncTests::batchSaveChannels on empty store with no work returns empty()")
    fun emptyAndNonContiguousPassesHaveExactSortedResults() = runTest {
        assertTrue(store.batchSaveChannels(RADIO_A, SnapshotList.empty(), SnapshotList.empty(), 8u).isEmpty())
        val rows = store.batchSaveChannels(RADIO_A, SnapshotList.of(info(0u, "Public"), info(2u, "Two"), info(7u, "Seven")),
            SnapshotList.of(1u, 3u, 4u, 5u, 6u), 8u)
        assertEquals(listOf(0.toUByte(), 2.toUByte(), 7.toUByte()), rows.map { it.index })
        assertEquals("Seven", rows.last().name)
    }

    @Test @OriginalCase("PersistenceStoreBatchSyncTests::batchSaveChannels scopes to radioID()")
    fun batchCannotPruneAnotherRadio() = runTest {
        store.saveChannel(RADIO_B, info(10u, "OtherRadio"))
        store.batchSaveChannels(RADIO_A, SnapshotList.of(info(1u, "Mine")), SnapshotList.of(10u), 8u)
        assertEquals("OtherRadio", assertNotNull(store.fetchChannel(RADIO_B, 10u)).name)
    }

    @Test @OriginalCase("PersistenceStoreBatchSyncTests::deleteChannel removes the slot's messages and reactions, sparing other slots()")
    fun slotRemovalCascadesHistoryWithoutTouchingAnotherSlot() = runTest {
        val doomed = slot(3u)
        slot(5u, 1)
        val ids = store.fetchMessages(RADIO_A, 3u).map { it.id }
        store.deleteChannel(entity(id = doomed))
        assertNull(store.fetchChannel(RADIO_A, 3u))
        assertTrue(store.fetchMessages(RADIO_A, 3u).isEmpty())
        for (id in ids) assertTrue(store.fetchReactions(entity(id = id)).isEmpty())
        assertEquals(1, store.fetchMessages(RADIO_A, 5u).size)
    }

    @Test @OriginalCase("PersistenceStoreBatchSyncTests::saveChannel with a new secret wipes the slot and resets derived counters()")
    @OriginalCase("PersistenceStoreBatchSyncTests::saveChannel with the same secret keeps history on rename()")
    fun secretNotNameDefinesTheSlotOccupant() = runTest {
        slot(3u)
        val original = assertNotNull(store.fetchChannel(RADIO_A, 3u))
        store.saveChannel(RADIO_A, info(3u, "Renamed"))
        assertEquals(3L, assertNotNull(store.fetchChannel(RADIO_A, 3u)).unreadCount)
        assertEquals(3, store.fetchMessages(RADIO_A, 3u).size)
        store.saveChannel(RADIO_A, info(3u, "Replacement", 0x99))
        val replaced = assertNotNull(store.fetchChannel(RADIO_A, 3u))
        assertNotEquals(original.id, replaced.id)
        assertEquals("Replacement", replaced.name)
        assertEquals(0L, replaced.unreadCount)
        assertEquals(0L, replaced.unreadMentionCount)
        assertNull(replaced.lastMessageDate)
        assertTrue(store.fetchMessages(RADIO_A, 3u).isEmpty())
    }

    @Test @OriginalCase("PersistenceStoreBatchSyncTests::batchSaveChannels wipes secret-changed, unconfigured, and pruned slots and keeps the rest()")
    fun batchWipesAllConfirmedRemovedOccupantsIncludingOrphanSlotHistory() = runTest {
        for (i in listOf(1u, 2u, 3u, 10u)) slot(i.toUByte())
        store.saveMessage(message(contactID = null, channelIndex = 4u, text = "leftover"))
        store.batchSaveChannels(RADIO_A, SnapshotList.of(info(1u, "Renamed"), info(2u, "Taken over", 0x99)),
            SnapshotList.of(3u, 4u), 8u)
        assertEquals(3, store.fetchMessages(RADIO_A, 1u).size)
        for (i in listOf(2u, 3u, 4u, 10u)) assertTrue(store.fetchMessages(RADIO_A, i.toUByte()).isEmpty())
        assertEquals(0L, assertNotNull(store.fetchChannel(RADIO_A, 2u)).unreadCount)
    }

    @Test @OriginalCase("PersistenceStoreBatchSyncTests::batchSaveChannels failed save restores the wiped messages with the rows()")
    fun actualSQLiteFailureRollsBackTheEntireSyncPass() = runTest {
        slot(2u)
        slot(3u)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_channel_insert BEFORE INSERT ON channels BEGIN SELECT RAISE(ABORT, 'source save failure'); END",
        )
        assertFailsWith<PersistenceStoreException> {
            store.batchSaveChannels(RADIO_A, SnapshotList.of(info(2u, "Taken over", 0x99)), SnapshotList.of(3u), 8u)
        }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_channel_insert")
        assertEquals(info(2u).secret, assertNotNull(store.fetchChannel(RADIO_A, 2u)).secret)
        assertNotNull(store.fetchChannel(RADIO_A, 3u))
        assertEquals(3, store.fetchMessages(RADIO_A, 2u).size)
        assertEquals(3, store.fetchMessages(RADIO_A, 3u).size)
    }

    @Test @OriginalCase("PersistenceStoreBatchSyncTests::batchSaveContacts inserts all and returns count()")
    @OriginalCase("PersistenceStoreBatchSyncTests::batchSaveContacts with empty frames returns zero()")
    fun contactBatchPersistsEveryFrameAndEmptyBatchIsEmpty() = runTest {
        assertEquals(0L, store.batchSaveContacts(RADIO_A, SnapshotList.empty()))
        assertTrue(store.fetchContacts(RADIO_A).isEmpty())
        assertEquals(3L, store.batchSaveContacts(RADIO_A,
            SnapshotList.of(frame(1, "Alice"), frame(2, "Bob"), frame(3, "Carol"))))
        assertEquals(setOf("Alice", "Bob", "Carol"), store.fetchContacts(RADIO_A).map { it.name }.toSet())
    }

    @Test @OriginalCase("PersistenceStoreBatchSyncTests::batchSaveContacts updates existing rows and preserves favorite bit()")
    @OriginalCase("PersistenceStoreBatchSyncTests::batchSaveContacts preserves existing avatarImageData()")
    fun frameBatchKeepsFavoriteAndLocalAvatar() = runTest {
        val id = store.saveContact(RADIO_A, frame(1, "Original", 1u)).id
        val before = assertNotNull(store.fetchContact(entity(id = id)))
        val jpeg = Bytes(ByteArray(32) { 0xAB.toByte() })
        store.saveContact(before.copy(avatarImageData = jpeg))
        assertEquals(1L, store.batchSaveContacts(RADIO_A, SnapshotList.of(frame(1, "Renamed", 0u))))
        val after = assertNotNull(store.fetchContact(entity(id = id)))
        assertEquals("Renamed", after.name)
        assertTrue(after.isFavorite)
        assertEquals(1.toUByte(), after.flags)
        assertEquals(jpeg, after.avatarImageData)
    }
}
