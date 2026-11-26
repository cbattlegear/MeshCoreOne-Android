// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CascadeSourceTest : RepositoryTest() {
    private data class Seed(val device: DeviceDTO, val message: MessageDTO, val session: RemoteNodeSessionDTO)
    private suspend fun seed(): Seed {
        val d = device()
        store.saveDevice(d)
        store.saveContact(contact())
        val m = message()
        store.saveMessage(m)
        store.saveMessageRepeat(RADIO_A, MessageRepeatDTO(messageID = m.id, receivedAt = AT, pathNodes = Bytes.of(0x42)))
        store.saveChannel(RADIO_A, ChannelInfo(1u, "Private", Bytes(ByteArray(16) { 0x42 })))
        store.saveReaction(ReactionDTO(messageID = m.id, emoji = "\uD83D\uDC4D", senderName = "Reactor",
            messageHash = "AABBCCDD", rawText = "reaction", receivedAt = AT, contactID = CONTACT_A, radioId = RADIO_A))
        val s = session()
        store.saveRemoteNodeSessionDTO(s)
        store.saveRoomMessage(RADIO_A, RoomMessageDTO(sessionID = s.id, authorKeyPrefix = Bytes.of(1, 2, 3, 4),
            text = "Room message", timestamp = 42u, createdAt = AT))
        store.saveBlockedChannelSender(BlockedChannelSenderDTO(name = "Spammer", radioId = RADIO_A, dateBlocked = AT))
        store.saveRxLogEntry(rx())
        store.upsertDiscoveredNode(RADIO_A, frame(2, "Discovered"))
        return Seed(d, m, s)
    }
    private suspend fun assertPresent(seed: Seed, rxCount: Int = 1) {
        assertEquals(1, store.fetchContacts(RADIO_A).size)
        assertEquals(1, store.fetchChannels(RADIO_A).size)
        assertEquals(1, store.fetchReactions(entity(id = seed.message.id)).size)
        assertEquals(1, store.fetchMessageRepeats(entity(id = seed.message.id)).size)
        assertEquals(1, store.fetchRemoteNodeSessions(RADIO_A).size)
        assertEquals(1, store.fetchRoomMessages(entity(id = seed.session.id)).size)
        assertEquals(1, store.fetchBlockedChannelSenders(RADIO_A).size)
        assertEquals(rxCount, store.fetchRxLogEntries(RADIO_A).size)
        assertEquals(1, store.fetchDiscoveredNodes(RADIO_A).size)
    }
    private suspend fun assertGone(seed: Seed) {
        assertTrue(store.fetchContacts(RADIO_A).isEmpty())
        assertTrue(store.fetchChannels(RADIO_A).isEmpty())
        assertTrue(store.fetchReactions(entity(id = seed.message.id)).isEmpty())
        assertTrue(store.fetchMessageRepeats(entity(id = seed.message.id)).isEmpty())
        assertTrue(store.fetchRemoteNodeSessions(RADIO_A).isEmpty())
        assertTrue(store.fetchRoomMessages(entity(id = seed.session.id)).isEmpty())
        assertTrue(store.fetchBlockedChannelSenders(RADIO_A).isEmpty())
        assertTrue(store.fetchRxLogEntries(RADIO_A).isEmpty())
        assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
        assertNull(store.fetchMessage(entity(id = seed.message.id)))
    }

    @Test @OriginalCase("PersistenceStoreTests::deleteDevice removes only device record, preserves all associated data()")
    fun registryRemovalPreservesEverySeededDataFamily() = runTest {
        val seeded = seed()
        store.deleteDevice(seeded.device.id)
        assertNull(store.fetchDevice(seeded.device.id))
        assertPresent(seeded)
    }

    @Test @OriginalCase("PersistenceStoreTests::deleteDeviceData removes all associated data but not device record()")
    @OriginalCase("PersistenceStoreTests::deleteDeviceData reaps a message and its heard repeats while preserving the device()")
    @OriginalCase("PersistenceStoreTests::deleteDeviceData reaps radio-scoped orphan PendingSends and preserves the Device row()")
    fun dataWipeRetainsRegistryAndReapsMatchedAndOrphanPendingRows() = runTest {
        val seeded = seed()
        store.upsertPendingSend(pending(messageID = seeded.message.id))
        store.upsertPendingSend(pending())
        store.deleteDeviceData(seeded.device.id)
        assertNotNull(store.fetchDevice(seeded.device.id))
        assertGone(seeded)
        assertTrue(store.fetchPendingSends(RADIO_A).isEmpty())
    }

    @Test @OriginalCase("PersistenceStoreTests::deleteDeviceAndData removes device and all data atomically()")
    @OriginalCase("PersistenceStoreTests::deleteDeviceAndData does not trap when a message has heard repeats (cascade-vs-batch)()")
    fun combinedWipeDeletesBothRegistryAndLinkedRepeats() = runTest {
        val seeded = seed()
        store.saveMessageRepeat(RADIO_A, MessageRepeatDTO(messageID = seeded.message.id, receivedAt = AT, pathNodes = Bytes.of(0x43)))
        store.deleteDeviceAndData(seeded.device.id)
        assertNull(store.fetchDevice(seeded.device.id))
        assertGone(seeded)
    }

    @Test @OriginalCase("PersistenceStoreTests::deleteMessage cascades the matching PendingSend in a single transaction()")
    @OriginalCase("PersistenceStoreTests::deleteMessage reaps an orphan PendingSend even when no Message row exists()")
    @OriginalCase("PersistenceStoreTests::deletePendingSendsForMessage public API saves on return()")
    fun singleMessageAndOrphanQueueDeletionCommitBeforeReturn() = runTest {
        val m = message()
        store.saveMessage(m)
        store.upsertPendingSend(pending(messageID = m.id))
        store.deleteMessage(entity(id = m.id))
        assertNull(store.fetchMessage(entity(id = m.id)))
        assertFalse(store.hasPendingSend(entity(id = m.id)))
        val orphan = pending()
        store.upsertPendingSend(orphan)
        store.deleteMessage(entity(id = orphan.messageID))
        assertFalse(store.hasPendingSend(entity(id = orphan.messageID)))
        val explicit = pending()
        store.upsertPendingSend(explicit)
        store.deletePendingSendsForMessage(entity(id = explicit.messageID))
        assertFalse(store.hasPendingSend(entity(id = explicit.messageID)))
    }

    @Test @OriginalCase("PersistenceStoreTests::deleteMessagesForContact removes all messages for a contact()")
    @OriginalCase("PersistenceStoreTests::deleteMessagesForContact cascades PendingSends and spares unrelated contacts()")
    @OriginalCase("PersistenceStoreTests::deleteContact cascades messages, reactions, repeats, and pending sends()")
    @OriginalCase("PersistenceStoreTests::deleteContact removes local data when the contact row is already gone()")
    fun contactWipesReachValueScopedOrphansAndSpareAnotherContact() = runTest {
        store.saveContact(contact())
        val survivorId = UUID.randomUUID()
        store.saveContact(contact(id = survivorId, publicKey = key(2)))
        val rows = (0..4).map { message(text = "Message $it") }
        for (m in rows) {
            store.saveMessage(m)
            store.upsertPendingSend(pending(messageID = m.id))
            store.saveMessageRepeat(RADIO_A, MessageRepeatDTO(messageID = m.id, receivedAt = AT, pathNodes = Bytes.of(0x42)))
            store.saveReaction(ReactionDTO(messageID = m.id, emoji = "\uD83D\uDC4D", senderName = "Doomed",
                messageHash = "hash", rawText = "reaction", receivedAt = AT, contactID = CONTACT_A, radioId = RADIO_A))
        }
        val kept = message(contactID = survivorId)
        store.saveMessage(kept)
        val keptPending = pending(messageID = kept.id, sequence = 99)
        store.upsertPendingSend(keptPending)
        assertEquals(5, store.fetchMessages(entity()).size)
        store.deleteMessagesForContact(entity())
        assertTrue(store.fetchMessages(entity()).isEmpty())
        assertNotNull(store.fetchContact(entity()))
        for (m in rows) {
            assertTrue(store.fetchMessageRepeats(entity(id = m.id)).isEmpty())
            assertTrue(store.fetchReactions(entity(id = m.id)).isEmpty())
        }
        assertEquals(listOf(keptPending), store.fetchPendingSends(RADIO_A))
        assertEquals(kept.id, store.fetchMessages(entity(id = survivorId)).single().id)
        val doomed = message()
        store.saveMessage(doomed)
        store.upsertPendingSend(pending(messageID = doomed.id))
        store.deleteContact(entity())
        assertNull(store.fetchContact(entity()))
        assertTrue(store.fetchMessages(entity()).isEmpty())
        val ghostId = UUID.randomUUID()
        val orphan = message(contactID = ghostId)
        store.saveMessage(orphan)
        store.upsertPendingSend(pending(messageID = orphan.id))
        store.deleteContact(entity(id = ghostId))
        assertNull(store.fetchMessage(entity(id = orphan.id)))
        assertFalse(store.hasPendingSend(entity(id = orphan.id)))
        assertNotNull(store.fetchContact(entity(id = survivorId)))
    }

    @Test @OriginalCase("PersistenceStoreTests::deleteMessagesForChannel removes all messages for a channel()")
    @OriginalCase("PersistenceStoreTests::deleteMessagesForChannel cascades PendingSends and spares other channels()")
    @OriginalCase("PersistenceStoreTests::deleteChannelMessages(fromSender:) cascades PendingSends and spares other senders()")
    fun channelAndSenderWipesDoNotReachDMsOtherSlotsOrOtherSenders() = runTest {
        val target = (0..4).map { message(contactID = null, channelIndex = 0u, text = "Ch0 $it") }
        for (m in target) { store.saveMessage(m); store.upsertPendingSend(pending(messageID = m.id)) }
        val other = message(contactID = null, channelIndex = 1u).copy(senderNodeName = "Friend")
        store.saveMessage(other)
        store.upsertPendingSend(pending(messageID = other.id, sequence = 99))
        val dm = message()
        store.saveMessage(dm)
        store.deleteMessagesForChannel(RADIO_A, 0u)
        assertTrue(store.fetchMessages(RADIO_A, 0u).isEmpty())
        assertEquals(other.id, store.fetchMessages(RADIO_A, 1u).single().id)
        assertEquals(dm.id, store.fetchMessages(entity()).single().id)
        assertEquals(other.id, store.fetchPendingSends(RADIO_A).single().messageID)
        val spam = message(contactID = null, channelIndex = 1u).copy(senderNodeName = "Spammer")
        store.saveMessage(spam)
        store.upsertPendingSend(pending(messageID = spam.id))
        store.deleteChannelMessages("Spammer", RADIO_A)
        assertNull(store.fetchMessage(entity(id = spam.id)))
        assertEquals(other.id, store.fetchPendingSends(RADIO_A).single().messageID)
    }

    @Test @OriginalCase("PersistenceStoreTests::deleteContacts removes matching rows in one pass()")
    @OriginalCase("PersistenceStoreTests::deleteContacts skippingPublicKeys leaves those rows()")
    @OriginalCase("PersistenceStoreTests::deleteContacts skippingPublicKeys after fetch leaves those rows()")
    fun contactBatchRechecksTheReviveSetAfterFetchingAllCandidates() = runTest {
        val a = store.saveContact(RADIO_A, frame(0xA1, "A")).id
        val b = store.saveContact(RADIO_A, frame(0xA2, "B")).id
        store.saveMessage(message(contactID = a))
        var calls = 0
        val deleted = store.deleteContacts(RADIO_A, listOf(key(0xA1), key(0xA2)).snapshotSet()) {
            calls++
            if (calls <= 2) SnapshotSet.empty() else listOf(key(0xA1)).snapshotSet()
        }
        assertEquals(listOf(b), deleted)
        assertNotNull(store.fetchContact(entity(id = a)))
        assertNull(store.fetchContact(entity(id = b)))
        assertEquals(1, store.fetchMessages(entity(id = a)).size)
        val skipped = store.deleteContacts(RADIO_A, listOf(key(0xA1)).snapshotSet()) { listOf(key(0xA1)).snapshotSet() }
        assertTrue(skipped.isEmpty())
        assertNotNull(store.fetchContact(entity(id = a)))
        assertEquals(listOf(a), store.deleteContacts(RADIO_A, listOf(key(0xA1)).snapshotSet()))
        assertTrue(store.fetchMessages(entity(id = a)).isEmpty())
    }

    @Test @OriginalCase("PersistenceStoreTests::deleteContacts save throw rolls back contact and cascade()")
    @OriginalCase("PersistenceStoreTests::deleteContacts rollback does not drop unsaved RxLog inserts()")
    fun failedContactCascadeRollsBackAllRowsButNotItsPriorRxFlush() = runTest {
        val seeded = seed()
        val p = pending(messageID = seeded.message.id)
        store.upsertPendingSend(p)
        val entry = rx(id = UUID.randomUUID(), timestamp = 43u)
        store.saveRxLogEntry(entry)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_contact_delete BEFORE DELETE ON contacts BEGIN SELECT RAISE(ABORT, 'source save failure'); END",
        )
        assertFailsWith<PersistenceStoreException> { store.deleteContacts(RADIO_A, listOf(key()).snapshotSet()) }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_contact_delete")
        assertPresent(seeded, rxCount = 2)
        assertEquals(listOf(p), store.fetchPendingSendsForMessage(entity(id = seeded.message.id)))
        assertEquals(entry.id, assertNotNull(store.findRxLogEntry(RADIO_A, 1u, 43u)).id)
        assertNotNull(db.rxLogs().byId(RADIO_A.value, entry.id))
    }

    @Test @OriginalCase("PersistenceStoreTests::deleteMessagesForChannel save throw rolls back messages and cascade()")
    @OriginalCase("PersistenceStoreTests::deleteMessagesForChannel rollback does not drop unsaved RxLog inserts()")
    fun failedSlotWipeCannotFlushItsDeletesOnALaterSuccessfulSave() = runTest {
        val m = message(contactID = null, channelIndex = 0u)
        store.saveMessage(m)
        store.upsertPendingSend(pending(messageID = m.id))
        store.saveMessageRepeat(RADIO_A, MessageRepeatDTO(messageID = m.id, receivedAt = AT, pathNodes = Bytes.of(0x42)))
        store.saveReaction(ReactionDTO(messageID = m.id, emoji = "\uD83D\uDC4D", senderName = "Reactor",
            messageHash = "hash", rawText = "reaction", receivedAt = AT, channelIndex = 0u, radioId = RADIO_A))
        val entry = rx()
        store.saveRxLogEntry(entry)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_message_delete BEFORE DELETE ON messages BEGIN SELECT RAISE(ABORT, 'source save failure'); END",
        )
        assertFailsWith<PersistenceStoreException> { store.deleteMessagesForChannel(RADIO_A, 0u) }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_message_delete")
        store.saveMessage(message(contactID = null, channelIndex = 1u))
        assertEquals(m.id, store.fetchMessages(RADIO_A, 0u).single().id)
        assertEquals(1, store.fetchPendingSendsForMessage(entity(id = m.id)).size)
        assertEquals(1, store.fetchMessageRepeats(entity(id = m.id)).size)
        assertEquals(1, store.fetchReactions(entity(id = m.id)).size)
        assertNotNull(db.rxLogs().byId(RADIO_A.value, entry.id))
        assertEquals(entry.id, assertNotNull(store.findRxLogEntry(RADIO_A, 1u, 42u)).id)
    }

    @Test @OriginalCase("PersistenceStoreTests::Database warm-up()")
    @OriginalCase("PersistenceStoreTests::warmUp runs both purgeOrphanPendingSends and purgeLegacyAttemptCountRows()")
    @OriginalCase("PersistenceStoreTests::purgeLegacyAttemptCountRows deletes only legacy nil rows()")
    @OriginalCase("PersistenceStoreTests::purgeLegacyAttemptCountRows is idempotent()")
    fun warmUpRepairsOrphansAndNullableLegacyAttemptsWithoutAProcessLatch() = runTest {
        store.warmUp()
        store.saveDevice(device())
        val legacy = pending(attempt = null, sequence = 1)
        val zero = pending(attempt = 0, sequence = 2)
        val positive = pending(attempt = 3, sequence = 3)
        for (p in listOf(legacy, zero, positive)) store.upsertPendingSend(p)
        assertEquals(1L, store.purgeLegacyAttemptCountRows())
        assertEquals(0L, store.purgeLegacyAttemptCountRows())
        assertEquals(listOf(0L, 3L), store.fetchPendingSends(RADIO_A).map { it.attemptCount })
        store.upsertPendingSend(pending(attempt = null))
        store.upsertPendingSend(pending(RADIO_B, attempt = null))
        store.warmUp()
        assertEquals(listOf(zero, positive), store.fetchPendingSends(RADIO_A))
        assertTrue(store.fetchPendingSends(RADIO_B).isEmpty())
        store.upsertPendingSend(pending(RADIO_B))
        store.warmUp()
        assertTrue(store.fetchPendingSends(RADIO_B).isEmpty())
    }

    @Test @OriginalCase("PersistenceStoreTests::incrementPendingSendAttemptCount from 0 bumps to 1()")
    @OriginalCase("PersistenceStoreTests::incrementPendingSendAttemptCount returns nil when no row matches()")
    fun attemptBumpsPersistAndMissingRowsAreTerminalRatherThanErrors() = runTest {
        val p = pending()
        store.upsertPendingSend(p)
        assertEquals(1L, store.incrementPendingSendAttemptCount(entity(id = p.messageID)))
        assertEquals(1L, store.fetchPendingSends(RADIO_A).single().attemptCount)
        assertNull(store.incrementPendingSendAttemptCount(entity(id = UUID.randomUUID())))
    }
}
