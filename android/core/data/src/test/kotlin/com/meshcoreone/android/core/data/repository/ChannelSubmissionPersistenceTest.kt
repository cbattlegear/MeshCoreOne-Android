// AndroidOnly: WP-202 Real Room retained submission replay, conflict, cleanup, cancellation and restart controls.
package com.meshcoreone.android.core.data.repository

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test

class ChannelSubmissionPersistenceTest : RepositoryTest() {
    private fun observingBeginFactory(onBeginAttempt: () -> Unit): SupportSQLiteOpenHelper.Factory {
        val factory = FrameworkSQLiteOpenHelperFactory()
        return SupportSQLiteOpenHelper.Factory { configuration ->
            val helper = factory.create(configuration)
            Proxy.newProxyInstance(
                SupportSQLiteOpenHelper::class.java.classLoader,
                arrayOf(SupportSQLiteOpenHelper::class.java),
            ) { _, method, args ->
                val value = invokeDelegate(method, helper, args)
                if (method.name == "getWritableDatabase" || method.name == "getReadableDatabase") {
                    observeBegin(value as SupportSQLiteDatabase, onBeginAttempt)
                } else value
            } as SupportSQLiteOpenHelper
        }
    }

    private fun observeBegin(
        database: SupportSQLiteDatabase,
        onBeginAttempt: () -> Unit,
    ): SupportSQLiteDatabase = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, args ->
        val sql = args?.firstOrNull() as? String
        if (method.name.startsWith("beginTransaction") ||
            (method.name == "execSQL" && sql?.trimStart()?.startsWith("BEGIN", ignoreCase = true) == true)
        ) onBeginAttempt()
        invokeDelegate(method, database, args)
    } as SupportSQLiteDatabase

    private fun invokeDelegate(
        method: java.lang.reflect.Method,
        delegate: Any,
        args: Array<out Any?>?,
    ): Any? = try {
        method.invoke(delegate, *(args ?: emptyArray()))
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }

    private fun row(radio: RadioId = RADIO_A) =
        message(radio = radio, contactID = null, channelIndex = 5u, text = "retained original\n", status = MessageStatus.PENDING)
            .copy(channelOccupantID = UUID.randomUUID())

    private fun submission(row: MessageDTO, id: UUID = UUID.randomUUID(), resend: Boolean = false) =
        ChannelSubmission(id, ChannelHistoryScope(row.radioId, 5u, assertNotNull(row.channelOccupantID)),
            ChannelMessageEnvelope(row.id, 5u, resend, row.text, row.timestamp, "original node", row.channelOccupantID))

    private suspend fun reject(reason: ChannelSubmissionConflict, action: suspend () -> Unit) {
        val failure = assertFailsWith<PersistenceStoreException> { action() }
        assertEquals(reason, assertIs<ChannelSubmissionRejected>(failure.cause).reason)
    }

    @Test fun exactReplayKeepsFirstSequenceTimeAndPayloadWithoutBumpingOrSamplingCurrentSlot() = runTest {
        val message = row()
        store.saveMessage(message)
        val input = submission(message)
        val first = store.admitChannelSubmission(input, AT.plusNanos(123))
        assertEquals(input, first.submission)
        assertEquals(ChannelSubmissionPhase.ADMITTED, first.phase)
        assertEquals(QueuedChannelDispatchState.READY, first.pending?.channelDispatchState)
        assertEquals(first, store.admitChannelSubmission(input, AT.plusSeconds(100)))
        assertEquals(first, assertIs<ChannelSubmissionReconciliation.Known>(store.reconcileChannelSubmission(input)).evidence)
        assertEquals(1, store.fetchPendingSends(RADIO_A).size)
        assertNull(store.fetchChannel(RADIO_A, 5u))
    }

    @Test fun sameStableIdRejectsChangedRadioMessageOriginTextTimestampNodeAndResendBeforeWrites() = runTest {
        val message = row()
        store.saveMessage(message)
        val input = submission(message)
        val first = store.admitChannelSubmission(input, AT)
        val otherOrigin = input.origin.copy(expectedOccupantID = UUID.randomUUID())
        for (changed in listOf(
            input.copy(origin = input.origin.copy(radioId = RADIO_B)),
            input.copy(envelope = input.envelope.copy(messageID = UUID.randomUUID())),
            input.copy(origin = otherOrigin, envelope = input.envelope.copy(channelOccupantID = otherOrigin.expectedOccupantID)),
            input.copy(origin = input.origin.copy(channelIndex = 6u), envelope = input.envelope.copy(channelIndex = 6u)),
            input.copy(envelope = input.envelope.copy(messageText = "changed")),
            input.copy(envelope = input.envelope.copy(messageTimestamp = 1u)),
            input.copy(envelope = input.envelope.copy(localNodeName = null)),
            input.copy(envelope = input.envelope.copy(isResend = true)),
        )) {
            reject(ChannelSubmissionConflict.IDENTITY_CONFLICT) { store.admitChannelSubmission(changed, AT) }
            reject(ChannelSubmissionConflict.IDENTITY_CONFLICT) { store.reconcileChannelSubmission(changed) }
        }
        assertEquals(first, store.admitChannelSubmission(input, AT))
    }

    @Test fun missingProofChangedSavedIntentAndLegacySameIdCannotManufactureAdmission() = runTest {
        val message = row()
        val input = submission(message)
        reject(ChannelSubmissionConflict.MESSAGE_MISMATCH) { store.admitChannelSubmission(input, AT) }
        for (changed in listOf(
            message.copy(channelOccupantID = null), message.copy(text = "different"),
            message.copy(direction = MessageDirection.INCOMING), message.copy(timestamp = 1u),
        )) {
            store.saveMessage(changed)
            reject(ChannelSubmissionConflict.MESSAGE_MISMATCH) { store.admitChannelSubmission(input, AT) }
        }
        store.saveMessage(message)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(input.envelope, RADIO_A, id = input.pendingID, enqueuedAt = AT))
        reject(ChannelSubmissionConflict.UNKNOWN_LEGACY_SUBMISSION) { store.admitChannelSubmission(input, AT) }
        assertEquals(ChannelSubmissionReconciliation.Unknown(ChannelSubmissionUnknown.ABSENT), store.reconcileChannelSubmission(input))
        assertNull(store.fetchPendingSends(RADIO_A).single().channelDispatchState)
    }

    @Test fun originalWriterNotYetEnteredRemainsUnknownThenCommitsAndConcurrentReplaysInsertOnce() = runTest(scheduler) {
        val message = row()
        store.saveMessage(message)
        val input = submission(message)
        val original = async { store.admitChannelSubmission(input, AT) }
        assertEquals(ChannelSubmissionReconciliation.Unknown(ChannelSubmissionUnknown.ABSENT), store.reconcileChannelSubmission(input))
        runCurrent()
        val committed = original.await()
        val second = newStore()
        try {
            val replays = listOf(
                async { store.admitChannelSubmission(input, AT.plusSeconds(1)) },
                async { second.admitChannelSubmission(input, AT.plusSeconds(2)) },
            ).awaitAll()
            assertEquals(listOf(committed, committed), replays)
            assertEquals(1, store.fetchPendingSends(RADIO_A).size)
        } finally { second.close() }
    }

    @Test fun pendingInsertFailureRollsBackEarlierReceiptAndNeverProvesNotCommitted() = runTest {
        val message = row()
        store.saveMessage(message)
        val input = submission(message)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_submission_pending BEFORE INSERT ON pending_sends BEGIN SELECT RAISE(ABORT, 'controlled submission failure'); END")
        try {
            assertFailsWith<PersistenceStoreException> { store.admitChannelSubmission(input, AT) }
            assertNull(db.channelSubmissions().byId(input.pendingID))
            assertTrue(store.fetchPendingSends(RADIO_A).isEmpty())
            assertEquals(ChannelSubmissionReconciliation.Unknown(ChannelSubmissionUnknown.ABSENT), store.reconcileChannelSubmission(input))
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_submission_pending") }
        assertEquals(input, store.admitChannelSubmission(input, AT).submission)
    }

    @Test fun admittedReceiptDoesNotRearmDispatchingUncertainOrAcceptedPendingState() = runTest {
        for (outcome in listOf(null, QueuedChannelAttemptOutcome.UNCERTAIN, QueuedChannelAttemptOutcome.ACCEPTED)) {
            val message = row()
            store.saveMessage(message)
            val input = submission(message)
            store.admitChannelSubmission(input, AT)
            val key = QueuedChannelAttemptKey(RADIO_A, input.pendingID, message.id, UUID.randomUUID())
            store.beginQueuedChannelAttempt(key, input.origin.expectedOccupantID, 2u)
            if (outcome != null) store.settleQueuedChannelAttempt(key, outcome)
            val before = store.fetchPendingSendsForMessage(EntityKey(RADIO_A, message.id)).single()
            val replay = store.admitChannelSubmission(input, AT)
            assertEquals(ChannelSubmissionPhase.ADMITTED, replay.phase)
            assertEquals(before, replay.pending)
            assertEquals(key, replay.attempts.single().key)
            assertEquals(
                replay,
                assertIs<ChannelSubmissionReconciliation.Known>(store.reconcileChannelSubmission(input)).evidence,
            )
        }
    }

    @Test fun acceptedAndDefinitelyUnsentTerminalEvidenceSurviveCleanupAndDiskReopenWithoutReinsertion() = runTest {
        for (accepted in listOf(true, false)) {
            val name = "submission-${UUID.randomUUID()}.db"
            var disk = MeshCoreDatabase.open(context, name)
            var persisted = RoomPersistenceStore(disk, owner, clock)
            val message = row()
            val input = submission(message, resend = true)
            val key = QueuedChannelAttemptKey(RADIO_A, input.pendingID, message.id, UUID.randomUUID())
            try {
                persisted.saveMessage(message)
                persisted.admitChannelSubmission(input, AT)
                persisted.beginQueuedChannelAttempt(key, input.origin.expectedOccupantID, 42u)
                persisted.settleQueuedChannelAttempt(key, if (accepted) QueuedChannelAttemptOutcome.ACCEPTED else QueuedChannelAttemptOutcome.DEFINITELY_UNSENT)
                if (accepted) persisted.completeAcceptedQueuedChannelAttempt(key)
                else persisted.retireDefinitelyUnsentQueuedChannelAttempt(key)
                persisted.close()
                disk.close()
                disk = MeshCoreDatabase.open(context, name)
                persisted = RoomPersistenceStore(disk, owner, clock)
                val evidence = persisted.admitChannelSubmission(input, AT.plusSeconds(50))
                assertEquals(input, evidence.submission)
                assertEquals(if (accepted) ChannelSubmissionPhase.ACCEPTED_COMPLETED else ChannelSubmissionPhase.RETIRED, evidence.phase)
                assertNull(evidence.pending)
                assertEquals(key, evidence.attempts.single().key)
                assertEquals(accepted, evidence.attempts.single().isCompleted)
                assertEquals(
                    evidence,
                    assertIs<ChannelSubmissionReconciliation.Known>(persisted.reconcileChannelSubmission(input)).evidence,
                )
                assertTrue(persisted.fetchPendingSends(RADIO_A).isEmpty())
            } finally {
                persisted.close()
                disk.close()
                context.deleteDatabase(name)
            }
        }
    }

    @Test fun lostAdmissionReturnRetainsExactReceiptAndReplayDoesNotInsertAnotherRow() = runTest {
        var cancelledOperation: Job? = null
        val immediate = Executor { it.run() }
        val cancelling = Executor { command ->
            command.run()
            cancelledOperation?.cancel(CancellationException("controlled admission return"))
        }
        val database = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(cancelling).build()
        val persisted = RoomPersistenceStore(database, owner, clock)
        try {
            val message = row()
            val input = submission(message)
            persisted.saveMessage(message)
            var returned = false
            val original = launch(start = CoroutineStart.UNDISPATCHED) {
                cancelledOperation = currentCoroutineContext()[Job]
                persisted.admitChannelSubmission(input, AT)
                returned = true
            }
            original.join()
            cancelledOperation = null
            assertTrue(original.isCancelled)
            assertFalse(returned)
            val evidence = assertIs<ChannelSubmissionReconciliation.Known>(persisted.reconcileChannelSubmission(input)).evidence
            assertEquals(evidence, persisted.admitChannelSubmission(input, AT.plusSeconds(1)))
            assertEquals(1, persisted.fetchPendingSends(RADIO_A).size)
        } finally {
            cancelledOperation = null
            persisted.close()
            database.close()
        }
    }

    @Test fun genericInsertionCannotOverwriteOrResurrectAReceiptAndExplicitDeletionRedactsIntent() = runTest {
        val message = row()
        store.saveMessage(message)
        val input = submission(message)
        val evidence = store.admitChannelSubmission(input, AT)
        reject(ChannelSubmissionConflict.IDENTITY_CONFLICT) { store.insertPendingSendAssigningSequence(assertNotNull(evidence.pending)) }
        store.deleteMessage(EntityKey(RADIO_A, message.id))
        val tombstone = assertNotNull(db.channelSubmissions().byId(input.pendingID))
        assertNull(tombstone.messageText)
        assertNull(tombstone.localNodeName)
        assertEquals(ChannelSubmissionReconciliation.Unknown(ChannelSubmissionUnknown.REDACTED), store.reconcileChannelSubmission(input))
        reject(ChannelSubmissionConflict.REDACTED_SUBMISSION) { store.admitChannelSubmission(input, AT) }
        reject(ChannelSubmissionConflict.IDENTITY_CONFLICT) { store.upsertPendingSend(assertNotNull(evidence.pending)) }
        assertTrue(store.fetchPendingSends(RADIO_A).isEmpty())
    }

    @Test fun deviceDeletionRedactsOrphanReceiptsAndKeepsForeignRadioIntent() = runTest {
        val device = device()
        store.saveDevice(device)
        val message = row()
        val foreign = row(RADIO_B)
        store.saveMessage(message)
        store.saveMessage(foreign)
        val input = submission(message)
        val foreignInput = submission(foreign)
        store.admitChannelSubmission(input, AT)
        val foreignEvidence = store.admitChannelSubmission(foreignInput, AT)
        store.deleteDeviceData(device.id)
        assertEquals(ChannelSubmissionReconciliation.Unknown(ChannelSubmissionUnknown.REDACTED), store.reconcileChannelSubmission(input))
        assertEquals(foreignEvidence, store.admitChannelSubmission(foreignInput, AT))
    }

    @Test fun conflictingConcurrentWritersAcrossContextsCommitOnlyOneFullIntent() = runTest(scheduler) {
        val message = row()
        store.saveMessage(message)
        val original = submission(message)
        val changed = original.copy(envelope = original.envelope.copy(localNodeName = "another node"))
        val second = newStore()
        try {
            suspend fun write(owner: RoomPersistenceStore, input: ChannelSubmission): ChannelSubmissionEvidence? =
                try { owner.admitChannelSubmission(input, AT) }
                catch (failure: PersistenceStoreException) {
                    assertEquals(ChannelSubmissionConflict.IDENTITY_CONFLICT,
                        assertIs<ChannelSubmissionRejected>(failure.cause).reason)
                    null
                }
            val results = listOf(async { write(store, original) }, async { write(second, changed) }).awaitAll()
            assertEquals(1, results.count { it == null })
            val committed = results.filterNotNull().single()
            assertEquals(committed, assertIs<ChannelSubmissionReconciliation.Known>(
                second.reconcileChannelSubmission(committed.submission)).evidence)
            assertEquals(1, store.fetchPendingSends(RADIO_A).size)
        } finally { second.close() }
    }

    @Test fun exactReplayContendsWithAnOpenRoomWriterAndReturnsItsFirstAdmission() = runTest {
        verifyContendedAdmission(conflicting = false, changedFirst = false)
    }

    @Test fun conflictingIntentContendsWithAnOpenRoomWriterWithoutReplacingEitherWinner() = runTest {
        verifyContendedAdmission(conflicting = true, changedFirst = false)
        verifyContendedAdmission(conflicting = true, changedFirst = true)
    }

    // These waits bound real Room workers, not runTest's virtual clock.
    private suspend fun verifyContendedAdmission(conflicting: Boolean, changedFirst: Boolean) = withContext(Dispatchers.IO) {
        val name = "submission-contention-${UUID.randomUUID()}.db"
        val firstRead = CountDownLatch(1)
        val secondBegin = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val held = AtomicBoolean(false)
        val heldInTransaction = AtomicBoolean(false)
        val firstQueryExecutor = Executors.newSingleThreadExecutor()
        val firstTransactionExecutor = Executors.newSingleThreadExecutor()
        val secondQueryExecutor = Executors.newSingleThreadExecutor()
        val immediate = Executor { it.run() }
        val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var firstDatabase: MeshCoreDatabase
        firstDatabase = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .setQueryExecutor(firstQueryExecutor).setTransactionExecutor(firstTransactionExecutor)
            .setQueryCallback({ sql, _ ->
                if (armed.get() && sql.startsWith("SELECT", ignoreCase = true) &&
                    sql.contains("channel_submissions") && held.compareAndSet(false, true)) {
                    heldInTransaction.set(firstDatabase.inTransaction())
                    firstRead.countDown()
                    check(releaseFirst.await(30, TimeUnit.SECONDS)) { "Controlled Room writer was not released" }
                }
            }, immediate).build()
        val secondCommandStarted = CountDownLatch(1)
        val secondTransactionWorker = Executors.newSingleThreadExecutor()
        val secondTransactionExecutor = Executor { command ->
            secondTransactionWorker.execute {
                if (armed.get()) secondCommandStarted.countDown()
                command.run()
            }
        }
        val secondDatabase = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .setQueryExecutor(secondQueryExecutor)
            .setTransactionExecutor(secondTransactionExecutor)
            .openHelperFactory(observingBeginFactory {
                if (armed.get()) secondBegin.countDown()
            }).build()
        val firstStore = RoomPersistenceStore(
            firstDatabase, lifetime, clock, channelIdentityMutation = ChannelIdentityMutex(),
        )
        val secondStore = RoomPersistenceStore(
            secondDatabase, lifetime, clock, channelIdentityMutation = ChannelIdentityMutex(),
        )
        try {
            val message = row()
            firstStore.saveMessage(message)
            // Open both handles before arming; callbacks must observe admission, not database setup.
            secondStore.fetchPendingSends(RADIO_A)
            val original = submission(message)
            val changed = original.copy(envelope = original.envelope.copy(localNodeName = "competing node"))
            val winner = if (changedFirst) changed else original
            val contender = if (conflicting) {
                if (changedFirst) original else changed
            } else original
            armed.set(true)
            val first = lifetime.async { firstStore.admitChannelSubmission(winner, AT) }
            val reachedRead = withContext(Dispatchers.IO) { firstRead.await(10, TimeUnit.SECONDS) }
            assertTrue(reachedRead, "First Room transaction must reach its retained-receipt SQL read")
            assertTrue(heldInTransaction.get(), "The held read must be inside the actual Room transaction")
            val second = lifetime.async {
                try { secondStore.admitChannelSubmission(contender, AT.plusSeconds(1)) }
                catch (failure: PersistenceStoreException) {
                    assertTrue(conflicting, "Exact replay must not reject")
                    assertEquals(ChannelSubmissionConflict.IDENTITY_CONFLICT,
                        assertIs<ChannelSubmissionRejected>(failure.cause).reason)
                    null
                }
            }
            val reachedTransactionExecutor = withContext(Dispatchers.IO) {
                secondCommandStarted.await(10, TimeUnit.SECONDS)
            }
            assertTrue(reachedTransactionExecutor, "Second Room transaction command must start while the first writer is held")
            val reachedBegin = withContext(Dispatchers.IO) { secondBegin.await(10, TimeUnit.SECONDS) }
            assertTrue(reachedBegin, "Second Room handle must reach a transaction begin attempt while the first writer is held")
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted, "Contender must not finish while the first write transaction is open")
            releaseFirst.countDown()
            val committed = withTimeout(20_000) { first.await() }
            val replay = withTimeout(20_000) { second.await() }
            assertEquals(winner, committed.submission)
            if (conflicting) assertNull(replay) else assertEquals(committed, replay)
            assertEquals(committed, assertIs<ChannelSubmissionReconciliation.Known>(
                secondStore.reconcileChannelSubmission(winner)).evidence)
            assertEquals(1, secondStore.fetchPendingSends(RADIO_A).size)
        } finally {
            armed.set(false)
            releaseFirst.countDown()
            val job = assertNotNull(lifetime.coroutineContext[Job])
            job.cancel()
            withTimeout(20_000) { job.join() }
            firstStore.close()
            secondStore.close()
            firstDatabase.close()
            secondDatabase.close()
            firstQueryExecutor.shutdown()
            firstTransactionExecutor.shutdown()
            secondQueryExecutor.shutdown()
            secondTransactionWorker.shutdown()
            assertTrue(withContext(Dispatchers.IO) { firstQueryExecutor.awaitTermination(10, TimeUnit.SECONDS) })
            assertTrue(withContext(Dispatchers.IO) { firstTransactionExecutor.awaitTermination(10, TimeUnit.SECONDS) })
            assertTrue(withContext(Dispatchers.IO) { secondQueryExecutor.awaitTermination(10, TimeUnit.SECONDS) })
            assertTrue(withContext(Dispatchers.IO) { secondTransactionWorker.awaitTermination(10, TimeUnit.SECONDS) })
            context.deleteDatabase(name)
        }
    }

    @Test fun receiptCompletionUpdateFailureRollsBackCleanupAndPreservesAcceptedEvidence() = runTest {
        val message = row()
        store.saveMessage(message)
        val input = submission(message)
        store.admitChannelSubmission(input, AT)
        val key = QueuedChannelAttemptKey(RADIO_A, input.pendingID, message.id, UUID.randomUUID())
        store.beginQueuedChannelAttempt(key, input.origin.expectedOccupantID, 42u)
        store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.ACCEPTED)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_submission_finish BEFORE UPDATE OF phaseRawValue ON channel_submissions BEGIN SELECT RAISE(ABORT, 'controlled completion failure'); END")
        try {
            assertFailsWith<PersistenceStoreException> { store.completeAcceptedQueuedChannelAttempt(key) }
            val evidence = store.admitChannelSubmission(input, AT)
            assertEquals(ChannelSubmissionPhase.ADMITTED, evidence.phase)
            assertEquals(QueuedChannelDispatchState.ACCEPTED, evidence.pending?.channelDispatchState)
            assertFalse(evidence.attempts.single().isCompleted)
            assertEquals(message, store.fetchMessage(EntityKey(RADIO_A, message.id)))
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_submission_finish") }
        store.completeAcceptedQueuedChannelAttempt(key)
        assertEquals(ChannelSubmissionPhase.ACCEPTED_COMPLETED, store.admitChannelSubmission(input, AT).phase)
    }

    @Test fun foreignLegacyIdentityAndDmOverwriteCannotBecomeAChannelAdmission() = runTest {
        val message = row()
        store.saveMessage(message)
        val input = submission(message)
        val foreignLegacy = PendingSendDTO.fromEnvelope(
            DirectMessageEnvelope(UUID.randomUUID(), CONTACT_A), RADIO_B, id = input.pendingID, enqueuedAt = AT)
        store.upsertPendingSend(foreignLegacy)
        reject(ChannelSubmissionConflict.UNKNOWN_LEGACY_SUBMISSION) { store.admitChannelSubmission(input, AT) }
        store.deletePendingSend(EntityKey(RADIO_B, input.pendingID))
        store.admitChannelSubmission(input, AT)
        reject(ChannelSubmissionConflict.IDENTITY_CONFLICT) { store.upsertPendingSend(foreignLegacy) }
        assertEquals(input.envelope, store.fetchPendingSends(RADIO_A).single().channelMessageEnvelope())
    }
}
