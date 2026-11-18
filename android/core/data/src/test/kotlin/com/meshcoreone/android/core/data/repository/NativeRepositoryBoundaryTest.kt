// AndroidOnly: WP-202 Actual Room cancellation, concurrency, observation, scoped parent and reopen boundaries.
package com.meshcoreone.android.core.data.repository

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NativeRepositoryBoundaryTest : RepositoryTest() {
    @Test fun declaredUmbrellaActuallyImplementsEveryPersistenceRole() {
        val roles = listOf(
            DevicePersisting::class.java, ContactPersisting::class.java, ChannelPersisting::class.java,
            MessagePersisting::class.java, HeardRepeatPersisting::class.java, ReactionPersisting::class.java,
            TracePathPersisting::class.java, DebugLogPersisting::class.java, LinkPreviewPersisting::class.java,
            RxLogPersisting::class.java, RoomPersisting::class.java, DiscoveredNodePersisting::class.java,
            NodeSnapshotPersisting::class.java, PendingSendPersisting::class.java, FailedSendPersisting::class.java,
            MetadataPersisting::class.java, PersistenceStoreProtocol::class.java,
        )
        for (role in roles) assertTrue(role.isInstance(store), role.simpleName)
    }

    @Test fun existingAndRestoredRadioIdentityIsNeverReplacedByAPeripheralOrHash() = runTest {
        val restored = RadioId(UUID.fromString("01234567-89AB-CDEF-0123-456789ABCDEF"))
        val d = device(restored).copy(publicKey = Bytes(ByteArray(32)))
        store.saveDevice(d)
        store.saveContact(restored, frame())
        store.saveDevice(d.copy(nodeName = "Updated"))
        assertEquals(restored, assertNotNull(store.fetchDevice(d.id)).radioId)
        assertEquals(restored, store.fetchContacts(restored).single().radioId)
        assertNotEquals(restored.value, DeviceIdentity.deriveUUID(d.publicKey))
    }

    @Test fun cancellationInsideARealRepositoryTransactionRollsBackEveryStagedRow() = runTest {
        val a = store.saveContact(RADIO_A, frame(1)).id
        val b = store.saveContact(RADIO_A, frame(2)).id
        val ma = message(contactID = a)
        val mb = message(contactID = b)
        store.saveMessage(ma)
        store.saveMessage(mb)
        var calls = 0
        assertFailsWith<CancellationException> {
            store.deleteContacts(RADIO_A, listOf(key(1), key(2)).snapshotSet()) {
                calls++
                if (calls == 4) throw CancellationException("Controlled cancellation after the first staged cascade")
                SnapshotSet.empty()
            }
        }
        assertNotNull(store.fetchContact(entity(id = a)))
        assertNotNull(store.fetchContact(entity(id = b)))
        assertNotNull(store.fetchMessage(entity(id = ma.id)))
        assertNotNull(store.fetchMessage(entity(id = mb.id)))
    }

    @Test fun cancellationAtTheRoomCommitReturnCannotResurrectDeletedDiagnosticRows() = runTest {
        var operationToCancel: kotlinx.coroutines.Job? = null
        val immediate = Executor { it.run() }
        val cancellingCommitExecutor = Executor { command ->
            command.run()
            operationToCancel?.cancel(CancellationException("Controlled post-commit return cancellation"))
        }
        val database = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(cancellingCommitExecutor).build()
        val repository = RoomRepositoryContext(database, clock, owner, RepositoryIssueReporter { _, failure -> issues += failure })
        try {
            val entry = rx()
            repository.saveRx(entry.toEntity())
            assertNull(database.rxLogs().byId(RADIO_A.value, entry.id))
            val operation = launch(start = CoroutineStart.UNDISPATCHED) {
                operationToCancel = currentCoroutineContext()[kotlinx.coroutines.Job]
                repository.write("controlledCommittedRxDeletion") {
                    database.rxLogs().clearRadio(RADIO_A.value)
                    pendingRx.clear()
                    save()
                }
            }
            operation.join()
            operationToCancel = null
            assertTrue(operation.isCancelled)
            assertEquals(0L, database.rxLogs().count(RADIO_A.value))
            repository.flushRx()
            assertEquals(0L, database.rxLogs().count(RADIO_A.value))
        } finally {
            operationToCancel = null
            repository.close()
            database.close()
        }
    }

    @Test fun anExternalTransactionCannotMakeRepositoryBufferCompletionLookCommitted() = runTest {
        val entry = rx()
        store.saveRxLogEntry(entry)
        val failure = assertFailsWith<PersistenceStoreException> {
            db.withTransaction { store.saveContact(contact()) }
        }
        assertEquals(PersistenceStoreError.InvalidData, failure.error)
        assertNull(db.contacts().byId(RADIO_A.value, CONTACT_A))
        assertNull(db.rxLogs().byId(RADIO_A.value, entry.id))
        store.flushPendingRxLogEntries()
        assertNotNull(db.rxLogs().byId(RADIO_A.value, entry.id))
    }

    @Test fun concurrentCountersAndSnapshotCapturesPreserveAllUpdates() = runTest {
        store.saveContact(contact())
        (1..20).map { async { store.incrementUnreadCount(entity()) } }.awaitAll()
        assertEquals(20L, assertNotNull(store.fetchContact(entity())).unreadCount)
        val ids = (1..10).map {
            async {
                store.recordNodeStatusSnapshot(key(), NodeStatusMetrics(uptimeSeconds = 1u, lastRSSI = -127),
                    SnapshotList.empty(), SnapshotList.empty(), NodeLocationFix(1.0, 2.0, 3.0))
            }
        }.awaitAll()
        assertEquals(1, ids.toSet().size)
        val rows = store.fetchNodeStatusSnapshots(key(), null)
        assertEquals(1, rows.size)
        assertEquals((-127).toShort(), rows.single().lastRSSI)
        assertEquals(SnapshotList.empty(), rows.single().neighborSnapshots)
        assertEquals(1.0, rows.single().latitude)
    }

    @Test fun conditionalDeliveryWinsAndOnlyTheFirstNonNullPathCanBeAdopted() = runTest {
        val m = message(status = MessageStatus.PENDING)
        store.saveMessage(m)
        val k = entity(id = m.id)
        listOf(async { store.updateMessageAck(k, UInt.MAX_VALUE, MessageStatus.DELIVERED, 0x8000_0000u) },
            async { store.updateMessageRetryStatus(k, MessageStatus.RETRYING, 3, 5) }).awaitAll()
        assertEquals(MessageStatus.DELIVERED, assertNotNull(store.fetchMessage(k)).status)
        assertEquals(UInt.MAX_VALUE, assertNotNull(store.fetchMessage(k)).ackCode)
        assertEquals(0x8000_0000u, assertNotNull(store.fetchMessage(k)).roundTripTime)
        assertFalse(store.updateMessageStatusUnlessDelivered(k, MessageStatus.FAILED))
        assertTrue(store.adoptIncomingPathIfUnknown(k, Bytes.EMPTY, 255u))
        assertFalse(store.adoptIncomingPathIfUnknown(k, Bytes.of(0x80), 1u))
        assertEquals(Bytes.EMPTY, assertNotNull(store.fetchMessage(k)).pathNodes)
    }

    @Test fun observationIsPartitionedAndCompletesWhenTheRepositoryCloses() = runTest {
        val seen = mutableListOf<List<String>>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            store.observeContacts(RADIO_A).collect { seen += it.map { c -> c.name } }
        }
        val ownedEmission = async(start = CoroutineStart.UNDISPATCHED) {
            store.observeContacts(RADIO_A).first { values -> values.any { it.name == "Owned" } }
        }
        assertTrue(store.observeContacts(RADIO_A).first().isEmpty())
        store.saveContact(contact(RADIO_B, name = "Other"))
        store.saveContact(contact(name = "Owned"))
        assertEquals(listOf("Owned"), ownedEmission.await().map { it.name })
        assertFalse(seen.flatten().contains("Other"))
        store.close()
        observer.join()
        assertTrue(observer.isCompleted)
        assertFailsWith<PersistenceStoreException> { store.fetchContacts(RADIO_A) }
        assertNotNull(db.contacts().byId(RADIO_A.value, CONTACT_A))
    }

    @Test fun sourceParentCascadesDoNotDeleteUnlinkedOrOtherRadioRepeats() = runTest {
        val m = message()
        store.saveMessage(m)
        store.saveMessage(m.copy(radioId = RADIO_B))
        val linked = MessageRepeatDTO(messageID = m.id, receivedAt = AT, pathNodes = Bytes.of(0x80))
        store.saveMessageRepeat(RADIO_A, linked)
        store.saveMessageRepeat(RADIO_B, linked)
        val unlinked = linked.copy(id = UUID.randomUUID())
        db.repeats().insert(unlinked.toEntity(RADIO_A, null))
        store.deleteMessage(entity(id = m.id))
        assertEquals(listOf(unlinked), store.fetchMessageRepeats(entity(id = m.id)))
        assertEquals(listOf(linked), store.fetchMessageRepeats(entity(RADIO_B, m.id)))
        val missing = MessageRepeatDTO(messageID = UUID.randomUUID(), receivedAt = AT, pathNodes = Bytes.EMPTY)
        val failure = assertFailsWith<PersistenceStoreException> { store.saveMessageRepeat(RADIO_A, missing) }
        assertEquals(PersistenceStoreError.MessageNotFound, failure.error)
    }

    @Test fun processResetIncludesOrphanSessionsAndBlockedSenderDeleteRemovesOnlyOneDuplicate() = runTest {
        store.saveDevice(device())
        val a = session().copy(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        val orphan = session(RADIO_B).copy(isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE)
        assertNull(store.fetchDevice(RADIO_B))
        store.saveRemoteNodeSessionDTO(a)
        store.saveRemoteNodeSessionDTO(orphan)
        store.resetAllRemoteNodeSessionConnections()
        assertFalse(assertNotNull(store.fetchRemoteNodeSession(entity(id = a.id))).isConnected)
        assertFalse(assertNotNull(store.fetchRemoteNodeSession(entity(RADIO_B, orphan.id))).isConnected)
        assertEquals(RoomPermissionLevel.ADMIN, assertNotNull(store.fetchRemoteNodeSession(entity(id = a.id))).permissionLevel)
        assertEquals(RoomPermissionLevel.READ_WRITE,
            assertNotNull(store.fetchRemoteNodeSession(entity(RADIO_B, orphan.id))).permissionLevel)
        val first = BlockedChannelSenderDTO(
            UUID.fromString("00000000-0000-0000-0000-00000000000A"), "Duplicate", RADIO_A, AT)
        val second = first.copy(id = UUID.fromString("00000000-0000-0000-0000-00000000000B"), dateBlocked = AT.plusNanos(1))
        val otherCase = first.copy(id = UUID.fromString("00000000-0000-0000-0000-00000000000C"), name = "DUPLICATE")
        db.blockedSenders().insert(first.toEntity())
        db.blockedSenders().insert(second.toEntity())
        db.blockedSenders().insert(first.copy(radioId = RADIO_B).toEntity())
        db.blockedSenders().insert(otherCase.toEntity())
        store.deleteBlockedChannelSender(RADIO_A, "Duplicate")
        assertEquals(setOf(second.id, otherCase.id), store.fetchBlockedChannelSenders(RADIO_A).map { it.id }.toSet())
        assertEquals(listOf(first.id), store.fetchBlockedChannelSenders(RADIO_B).map { it.id })
        assertEquals(AT.plusNanos(1), store.fetchBlockedChannelSenders(RADIO_A).first().dateBlocked)
    }

    @Test fun directCorrelationAndRedecryptionRetainTheReviewedSourceOrdering() = runTest {
        val old = rx(channelIndex = null, payload = PayloadType.TEXT_MESSAGE, receivedAt = AT).copy(pathNodes = Bytes.of(0x1A))
        val newer = old.copy(id = UUID.randomUUID(), receivedAt = AT.plusNanos(1), pathNodes = Bytes.of(0x2B))
        val channelAttributed = old.copy(id = UUID.randomUUID(), receivedAt = AT.plusNanos(2), channelIndex = 7u)
        for (row in listOf(old, newer, channelAttributed)) store.saveRxLogEntry(row)
        assertEquals(newer.id, assertNotNull(store.findRxLogEntry(RADIO_A, null, 42u)).id)
        assertEquals(listOf(old.id, newer.id, channelAttributed.id),
            store.fetchRecentEntriesByDecryptStatus(RADIO_A, DecryptStatus.SUCCESS, AT).map { it.id })
    }

    @Test fun timestampZeroTruncatesWholeUnixFractionsAndPartialUpdatesPreserveTheStoredValue() = runTest {
        val at = Instant.ofEpochSecond(-1, 500_000_000)
        val m = message(timestamp = 0u, createdAt = at)
        store.saveMessage(m)
        val stored = assertNotNull(store.fetchMessage(entity(id = m.id)))
        assertEquals(0u, stored.timestamp)
        assertEquals(at, stored.createdAt)
        store.updateMessageTimestamp(entity(id = m.id), 0u)
        store.markMentionSeen(entity(id = m.id))
        assertEquals(0u, assertNotNull(store.fetchMessage(entity(id = m.id))).timestamp)
        assertEquals(at, assertNotNull(store.fetchMessage(entity(id = m.id))).createdAt)
    }

    @Test fun sequenceOverflowRollsBackTheRetryReplacementInsteadOfPersistingARealNumber() = runTest {
        val m = message(status = MessageStatus.FAILED)
        store.saveMessage(m)
        val previous = pending(messageID = m.id, sequence = 1)
        val ceiling = pending(sequence = Long.MAX_VALUE)
        store.upsertPendingSend(previous)
        store.upsertPendingSend(ceiling)
        assertFailsWith<PersistenceStoreException> { store.replacePendingSendForRetry(m.id, pending(messageID = m.id)) }
        assertEquals(MessageStatus.FAILED, assertNotNull(store.fetchMessage(entity(id = m.id))).status)
        assertEquals(listOf(previous, ceiling), store.fetchPendingSends(RADIO_A))
    }

    @Test fun futureUnsupportedSchemaDoesNotDestructivelyReplaceAnExistingFile() = runTest {
        val name = "upgrade.db"
        val immediate = Executor { it.run() }
        fun open(): MeshCoreDatabase = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries()
            .setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        var file = open()
        try {
            file.devices().upsert(device().toEntity())
            assertEquals(MeshCoreDatabase.SCHEMA_VERSION, file.openHelper.readableDatabase.version)
            file.openHelper.writableDatabase.execSQL("PRAGMA user_version = 99")
            file.close()
            file = open()
            assertFailsWith<IllegalStateException> { file.devices().count() }
            file.close()
            val raw = android.database.sqlite.SQLiteDatabase.openDatabase(
                context.getDatabasePath(name).absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            )
            raw.use { database ->
                assertEquals(99, database.version)
                database.rawQuery("SELECT COUNT(*) FROM devices", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(1, it.getInt(0))
                }
            }
        } finally {
            file.close()
            context.deleteDatabase(name)
        }
    }
}
