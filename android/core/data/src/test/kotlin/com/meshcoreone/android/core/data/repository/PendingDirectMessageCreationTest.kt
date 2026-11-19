// AndroidOnly: WP-202 real Room caller-ID creation/replay, isolation and process-store lifecycle regressions.
package com.meshcoreone.android.core.data.repository

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    @Test fun zeroTimestampIsIndependentOfCreationTimeAndLegacyMappingStillRejectsInvalidDerivedTime() = runTest {
        for (createdAt in listOf(
            Instant.ofEpochSecond(-1),
            Instant.ofEpochSecond(-2, 500_000_000),
            Instant.ofEpochSecond(UInt.MAX_VALUE.toLong() + 1),
            Instant.ofEpochSecond(UInt.MAX_VALUE.toLong() + 1, 999_999_999),
        )) {
            val intent = intent()
            val actual = store.createOrReconcilePendingDirectMessage(intent, 0u, createdAt)
            assertEquals(0u, actual.timestamp)
            assertEquals(createdAt, actual.createdAt)
            assertEquals(createdAt, actual.sortDate)
            val row = assertNotNull(db.messages().byId(RADIO_A.value, intent.messageID))
            assertEquals(0L, row.timestamp)
            assertEquals(actual, row.toDTO())
            assertEquals(actual, store.createOrReconcilePendingDirectMessage(intent, 99u, AT))
            val failure = assertFailsWith<PersistenceStoreException> { store.saveMessage(actual.copy(id = UUID.randomUUID())) }
            assertIs<PersistenceStoreError.SaveFailed>(failure.error)
            assertEquals(listOf(row), db.messages().forIdentity(intent.messageID))
        }
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
        verifyIndependentRoomConnections()
    }

    private suspend fun verifyIndependentRoomConnections() = withContext(Dispatchers.IO) {
        val name = "pending-direct-${UUID.randomUUID()}.db"
        val firstRead = CountDownLatch(1)
        val secondBegin = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val held = AtomicBoolean(false)
        val firstReadInTransaction = AtomicBoolean(false)
        val workers = List(4) { Executors.newSingleThreadExecutor() }
        lateinit var first: MeshCoreDatabase
        first = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .setQueryExecutor(workers[0]).setTransactionExecutor(workers[1])
            .setQueryCallback({ sql, _ ->
                if (armed.get() && sql.startsWith("SELECT", ignoreCase = true) &&
                    sql.contains("messages") && held.compareAndSet(false, true)
                ) {
                    firstReadInTransaction.set(first.inTransaction())
                    firstRead.countDown()
                    check(releaseFirst.await(30, TimeUnit.SECONDS)) { "Controlled DM writer was not released" }
                }
            }, Executor { it.run() }).build()
        val second = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .setQueryExecutor(workers[2]).setTransactionExecutor(workers[3])
            .openHelperFactory(observingBeginFactory { if (armed.get()) secondBegin.countDown() })
            .build()
        var firstStore = RoomPersistenceStore(first, owner, clock)
        val secondStore = RoomPersistenceStore(second, owner, clock)
        val intent = intent()
        try {
            first.openHelper.writableDatabase
            second.openHelper.writableDatabase
            armed.set(true)
            val committed = coroutineScope {
                val original = async { firstStore.createOrReconcilePendingDirectMessage(intent, 42u, AT) }
                try {
                    assertTrue(firstRead.await(10, TimeUnit.SECONDS), "Original writer did not reach its identity read")
                    assertTrue(firstReadInTransaction.get(), "Identity read must be held inside the Room transaction")
                    val contender = async {
                        assertFailsWith<PersistenceStoreException> {
                            secondStore.createOrReconcilePendingDirectMessage(intent.copy(radioId = RADIO_B), 42u, AT)
                        }
                    }
                    assertTrue(secondBegin.await(10, TimeUnit.SECONDS), "Independent handle did not reach SQLite BEGIN")
                    assertFalse(original.isCompleted, "Original must still hold the write transaction")
                    assertFalse(contender.isCompleted, "Contender must wait for the original transaction")
                    releaseFirst.countDown()
                    val saved = withTimeout(20_000) { original.await() }
                    val rejected = withTimeout(20_000) { contender.await() }
                    assertEquals(
                        PersistenceStoreError.InvalidData, rejected.error,
                        "Conflicting radio must reject by intent, not a storage error (${rejected.cause?.javaClass?.simpleName})",
                    )
                    saved
                } finally {
                    releaseFirst.countDown()
                }
            }
            armed.set(false)
            assertEquals(listOf(committed), second.messages().forIdentity(intent.messageID).map { it.toDTO() })
            assertEquals(committed, secondStore.createOrReconcilePendingDirectMessage(intent, 99u, AT.plusSeconds(10)))
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
            assertEquals(MeshCoreDatabase.SCHEMA_VERSION, first.openHelper.readableDatabase.version)
        } finally {
            armed.set(false)
            releaseFirst.countDown()
            secondStore.close()
            second.close()
            firstStore.close()
            first.close()
            workers.forEach { it.shutdown() }
            workers.forEach { assertTrue(it.awaitTermination(10, TimeUnit.SECONDS), "Room worker did not finish") }
            context.deleteDatabase(name)
        }
    }

    private fun observingBeginFactory(onBegin: () -> Unit): SupportSQLiteOpenHelper.Factory =
        SupportSQLiteOpenHelper.Factory { configuration ->
            val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
            Proxy.newProxyInstance(
                SupportSQLiteOpenHelper::class.java.classLoader, arrayOf(SupportSQLiteOpenHelper::class.java),
            ) { _, method, args ->
                val result = invokeDelegate(method, helper, args)
                if (method.name == "getWritableDatabase" || method.name == "getReadableDatabase") {
                    val database = result as SupportSQLiteDatabase
                    Proxy.newProxyInstance(
                        SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java),
                    ) { _, operation, parameters ->
                        val sql = parameters?.firstOrNull() as? String
                        if (operation.name.startsWith("beginTransaction") ||
                            (operation.name == "execSQL" && sql?.trimStart()?.startsWith("BEGIN", ignoreCase = true) == true)
                        ) onBegin()
                        invokeDelegate(operation, database, parameters)
                    } as SupportSQLiteDatabase
                } else result
            } as SupportSQLiteOpenHelper
        }

    private fun invokeDelegate(method: java.lang.reflect.Method, delegate: Any, args: Array<out Any?>?): Any? =
        try {
            method.invoke(delegate, *(args ?: emptyArray()))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
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
        val requested = CompletableDeferred<Unit>()
        val creation = async(Dispatchers.IO) {
            requested.complete(Unit)
            store.createOrReconcilePendingDirectMessage(intent, 42u, AT)
        }
        try {
            requested.await()
            creation.cancel()
        } finally {
            // Room must service the occupied transaction executor before the queued cancellation can join.
            release.complete(Unit)
            blocker.await()
        }
        creation.join()
        assertTrue(creation.isCancelled)
        assertFailsWith<CancellationException> { creation.await() }
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
