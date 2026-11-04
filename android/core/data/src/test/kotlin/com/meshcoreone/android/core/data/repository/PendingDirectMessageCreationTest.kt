// AndroidOnly: WP-202 real Room caller-ID creation/replay, isolation and process-store lifecycle regressions.
package com.meshcoreone.android.core.data.repository

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.meshcoreone.android.core.contracts.domain.PendingDirectMessageIntent
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.toDTO
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.TextType
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PendingDirectMessageCreationTest : RepositoryTest() {
    private fun intent() = PendingDirectMessageIntent(
        UUID.randomUUID(), RADIO_A, CONTACT_A, "  exact\r\ntext e\u0301 \uD83D\uDC69\u200D\uD83D\uDCBB  ",
        TextType.PLAIN, UUID.randomUUID(),
    )

    @Test fun insertPreservesCallerIdentityExactTextAndProducerTimeIncludingZeroTimestamp() = runTest {
        for (timestamp in listOf(0u, UInt.MAX_VALUE)) {
            val intent = intent()
            val createdAt = AT.plusNanos(123_456_789)
            val actual = store.createOrReconcilePendingDirectMessage(intent, timestamp, createdAt)
            assertEquals(intent.messageID, actual.id)
            assertEquals(intent.radioId, actual.radioId)
            assertEquals(intent.contactID, actual.contactID)
            assertEquals(intent.text, actual.text)
            assertEquals(intent.textType, actual.textType)
            assertEquals(intent.replyToID, actual.replyToID)
            assertEquals(timestamp, actual.timestamp)
            assertEquals(createdAt, actual.createdAt)
            assertEquals(createdAt, actual.sortDate)
            assertEquals(MessageDirection.OUTGOING, actual.direction)
            assertEquals(MessageStatus.PENDING, actual.status)
            assertNull(actual.channelIndex)
            assertEquals(actual, store.fetchMessage(entity(id = actual.id)))
            assertEquals(timestamp.toLong(), assertNotNull(db.messages().byId(RADIO_A.value, actual.id)).timestamp)
        }
    }

    @Test fun repeatedCreationReturnsTheActualEvolvedRowAndLeavesPendingAttemptsUntouched() = runTest {
        val intent = intent()
        val created = store.createOrReconcilePendingDirectMessage(intent, 42u, AT)
        val queued = pending(messageID = created.id, sequence = 9, attempt = 3)
        store.upsertPendingSend(queued)
        for (status in MessageStatus.entries) {
            val evolved = created.copy(
                status = status, timestamp = 99u, createdAt = AT.minusSeconds(10), sortDate = AT.plusSeconds(5),
                ackCode = UInt.MAX_VALUE, roundTripTime = 12u, retryAttempt = 4, maxRetryAttempts = 5,
                sendCount = 8, heardRepeats = 2, isRead = true, failureSeen = true,
                timestampCorrected = true, senderTimestamp = 42u, reactionSummary = "reaction",
                linkPreviewTitle = "preview", linkPreviewURL = "https://example.invalid",
            ).toEntity().copy(
                linkPreviewImageData = Bytes.of(1, 2), linkPreviewIconData = Bytes.of(3), linkPreviewFetched = true,
            )
            db.messages().upsert(evolved)
            repeat(3) {
                assertEquals(evolved.toDTO(), store.createOrReconcilePendingDirectMessage(intent, 123u, AT.plusSeconds(100)))
                assertEquals(evolved, db.messages().byId(RADIO_A.value, created.id))
                assertEquals(listOf(queued), store.fetchPendingSendsForMessage(entity(id = created.id)).toList())
            }
        }
        assertTrue(issues.isEmpty())
    }

    @Test fun conflictingImmutableIntentFailsTypedWithoutReplacingAnyRow() = runTest {
        val intent = intent()
        store.createOrReconcilePendingDirectMessage(intent, 42u, AT)
        val original = assertNotNull(db.messages().byId(RADIO_A.value, intent.messageID))
        for (conflict in listOf(
            intent.copy(radioId = RADIO_B),
            intent.copy(contactID = UUID.randomUUID()),
            intent.copy(text = intent.text.trim()),
            intent.copy(text = intent.text.replace("e\u0301", "\u00e9")),
            intent.copy(textType = TextType.SIGNED_PLAIN),
            intent.copy(replyToID = null),
            intent.copy(replyToID = UUID.randomUUID()),
        )) {
            val failure = assertFailsWith<PersistenceStoreException> {
                store.createOrReconcilePendingDirectMessage(conflict, 123u, AT.plusSeconds(10))
            }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
            assertSame(failure, issues.last())
            assertEquals(listOf(original), db.messages().forIdentity(intent.messageID))
            assertNull(db.messages().byId(RADIO_B.value, intent.messageID))
        }
    }

    @Test fun incomingChannelOrOrphanIdentityCannotBeReinterpretedAsAnOutgoingDirectMessage() = runTest {
        val intent = intent()
        val created = store.createOrReconcilePendingDirectMessage(intent, 42u, AT)
        for (occupied in listOf(
            created.copy(direction = MessageDirection.INCOMING),
            created.copy(channelIndex = 1u),
            created.copy(contactID = null),
        )) {
            val original = occupied.toEntity()
            db.messages().upsert(original)
            val failure = assertFailsWith<PersistenceStoreException> {
                store.createOrReconcilePendingDirectMessage(intent, 123u, AT.plusSeconds(10))
            }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
            assertEquals(listOf(original), db.messages().forIdentity(intent.messageID))
        }
    }

    @Test fun sameIdOnMultipleRadiosFailsClosedWithoutMutatingEitherPartition() = runTest {
        val intent = intent()
        val a = store.createOrReconcilePendingDirectMessage(intent, 42u, AT)
        val b = a.copy(radioId = RADIO_B)
        store.saveMessage(b)
        val rows = db.messages().forIdentity(a.id).toSet()
        for (radio in listOf(RADIO_A, RADIO_B)) {
            val failure = assertFailsWith<PersistenceStoreException> {
                store.createOrReconcilePendingDirectMessage(intent.copy(radioId = radio), 42u, AT)
            }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
            assertEquals(rows, db.messages().forIdentity(a.id).toSet())
        }
    }

    @Test fun concurrentCreatesAcrossRepositoryContextsReturnOneCommittedIdentity() = runTest {
        val intent = intent()
        val otherStore = newStore()
        try {
            val gate = CompletableDeferred<Unit>()
            val results = (0 until 24).map { index ->
                async(Dispatchers.IO) {
                    gate.await()
                    (if (index % 2 == 0) store else otherStore)
                        .createOrReconcilePendingDirectMessage(intent, index.toUInt(), AT.plusSeconds(index.toLong()))
                }
            }
            gate.complete(Unit)
            val actual = results.awaitAll()
            val committed = db.messages().forIdentity(intent.messageID).single().toDTO()
            assertTrue(actual.all { it == committed })
            assertEquals(intent.text, committed.text)
            assertEquals(1L, db.messages().countForContact(RADIO_A.value, CONTACT_A))
        } finally {
            otherStore.close()
        }
    }

    @Test fun independentRoomConnectionsSerializeConflictingRadioClaimsAndSurviveFileReopen() = runTest {
        val name = "pending-direct-${UUID.randomUUID()}.db"
        var first = fileDatabase(name)
        val second = fileDatabase(name)
        var firstStore = RoomPersistenceStore(first, owner, clock)
        val secondStore = RoomPersistenceStore(second, owner, clock)
        val intent = intent()
        try {
            first.openHelper.writableDatabase
            second.openHelper.writableDatabase
            val gate = CompletableDeferred<Unit>()
            val outcomes = coroutineScope {
                val work = listOf(firstStore to intent, secondStore to intent.copy(radioId = RADIO_B)).map { (repository, candidate) ->
                    async(Dispatchers.IO) {
                        gate.await()
                        try {
                            repository.createOrReconcilePendingDirectMessage(candidate, 42u, AT) to null
                        } catch (failure: PersistenceStoreException) {
                            null to failure.error
                        }
                    }
                }
                gate.complete(Unit)
                work.awaitAll()
            }
            assertEquals(1, outcomes.count { it.first != null })
            assertEquals(listOf(PersistenceStoreError.InvalidData), outcomes.mapNotNull { it.second })
            val committed = assertNotNull(outcomes.mapNotNull { it.first }.single())
            val matching = intent.copy(radioId = committed.radioId)
            val committedKey = entity(committed.radioId, committed.id)
            firstStore.updateMessageAck(committedKey, 123u, MessageStatus.DELIVERED, 7u)
            val evolved = assertNotNull(firstStore.fetchMessage(committedKey))
            secondStore.close()
            second.close()
            firstStore.close()
            first.close()
            first = fileDatabase(name)
            firstStore = RoomPersistenceStore(first, owner, clock)
            assertEquals(evolved, firstStore.createOrReconcilePendingDirectMessage(matching, 999u, AT.plusSeconds(1000)))
            assertEquals(listOf(evolved.toEntity()), first.messages().forIdentity(intent.messageID))
            assertEquals(1, first.openHelper.readableDatabase.version)
        } finally {
            secondStore.close()
            second.close()
            firstStore.close()
            first.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun reconnectingAConsumerOrReopeningTheProcessStoreDoesNotResetExistingState() = runTest {
        val intent = intent()
        val created = store.createOrReconcilePendingDirectMessage(intent, 42u, AT)
        store.updateMessageRetryStatus(entity(id = created.id), MessageStatus.RETRYING, 3, 5)
        val evolved = assertNotNull(store.fetchMessage(entity(id = created.id)))
        val reconnected = newStore()
        try {
            assertEquals(evolved, reconnected.createOrReconcilePendingDirectMessage(intent, 123u, AT.plusSeconds(10)))
        } finally {
            reconnected.close()
        }
        store.close()
        store = newStore()
        assertEquals(evolved, store.createOrReconcilePendingDirectMessage(intent, 456u, AT.plusSeconds(20)))
    }

    @Test fun failedInsertRollsBackAndReportsTheFailureWithoutCreatingAnIdentity() = runTest {
        val intent = intent()
        val buffered = rx()
        store.saveRxLogEntry(buffered)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_direct AFTER INSERT ON messages BEGIN SELECT RAISE(FAIL, 'controlled insert failure'); END",
        )
        try {
            val failure = assertFailsWith<PersistenceStoreException> {
                store.createOrReconcilePendingDirectMessage(intent, 42u, AT)
            }
            assertIs<PersistenceStoreError.SaveFailed>(failure.error)
            assertSame(failure, issues.single())
            assertTrue(db.messages().forIdentity(intent.messageID).isEmpty())
            assertNull(db.rxLogs().byId(RADIO_A.value, buffered.id))
            assertEquals(buffered.copy(decodedText = null), store.fetchRxLogEntries(RADIO_A).single())
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_direct")
        }
        assertEquals(intent.messageID, store.createOrReconcilePendingDirectMessage(intent, 42u, AT).id)
        assertNotNull(db.rxLogs().byId(RADIO_A.value, buffered.id))
    }

    @Test fun cancellationWhileWaitingForRoomDoesNotInsertOrReportAStorageFailure() = runTest {
        val intent = intent()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = async(Dispatchers.IO) {
            db.withTransaction {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        try {
            val requested = CompletableDeferred<Unit>()
            val creation = async(Dispatchers.IO) {
                requested.complete(Unit)
                store.createOrReconcilePendingDirectMessage(intent, 42u, AT)
            }
            requested.await()
            creation.cancelAndJoin()
        } finally {
            release.complete(Unit)
            blocker.await()
        }
        assertTrue(db.messages().forIdentity(intent.messageID).isEmpty())
        assertTrue(issues.isEmpty())
        assertEquals(intent.messageID, store.createOrReconcilePendingDirectMessage(intent, 42u, AT).id)
    }

    private fun fileDatabase(name: String): MeshCoreDatabase {
        val immediate = Executor { it.run() }
        return Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
    }
}
