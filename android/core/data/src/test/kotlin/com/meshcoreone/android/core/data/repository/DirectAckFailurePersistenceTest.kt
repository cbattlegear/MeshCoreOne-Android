// AndroidOnly: WP-202 Real Room controls for direct-ACK failure-write commit uncertainty.
package com.meshcoreone.android.core.data.repository

import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import com.meshcoreone.android.core.services.messaging.MessageService
import com.meshcoreone.android.core.services.messaging.MessageServiceConfig
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class DirectAckFailurePersistenceTest {
    private val scheduler = TestCoroutineScheduler()
    private val process = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val radio = RadioId(UUID.fromString("AAAAAAAA-1111-2222-3333-444444444444"))
    private val self = Bytes(ByteArray(32) { 1 })
    private val peer = Bytes(ByteArray(32) { 0x22 })
    private val code = Bytes.fromHex("01234567")
    private val base = Instant.parse("2026-10-05T12:00:00Z")
    private val clock = object : SessionClock {
        override val now: Duration get() = scheduler.currentTime.milliseconds
        override val wallClock: Clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = base.plusMillis(scheduler.currentTime)
        }
        override suspend fun sleepFor(duration: Duration) { delay(duration) }
    }
    private lateinit var db: MeshCoreDatabase
    private lateinit var store: RoomPersistenceStore
    private lateinit var contact: ContactDTO
    private val generations = mutableListOf<RadioGeneration>()
    private var failedCommitObserver: (() -> Unit)? = null

    @Before fun open() {
        val immediate = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MeshCoreDatabase::class.java,
        ).openHelperFactory(object : SupportSQLiteOpenHelper.Factory {
            override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
                val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
                fun observe(database: SupportSQLiteDatabase): SupportSQLiteDatabase =
                    object : SupportSQLiteDatabase by database {
                        override fun endTransaction() {
                            database.endTransaction()
                            if (!database.inTransaction() && failedCommitObserver != null) {
                                val failed = database.query("SELECT statusRawValue FROM messages").use { cursor ->
                                    cursor.moveToFirst() && cursor.getLong(0) == MessageStatus.FAILED.rawValue
                                }
                                if (failed) {
                                    val observer = requireNotNull(failedCommitObserver)
                                    failedCommitObserver = null
                                    observer()
                                }
                            }
                        }
                    }
                return object : SupportSQLiteOpenHelper by helper {
                    override val writableDatabase: SupportSQLiteDatabase get() = observe(helper.writableDatabase)
                    override val readableDatabase: SupportSQLiteDatabase get() = observe(helper.readableDatabase)
                }
            }
        }).allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        store = RoomPersistenceStore(db, process, clock.wallClock)
        contact = ContactDTO(radioId = radio, publicKey = peer, name = "Peer", lastHeardTimestamp = 0u)
    }

    @After fun close() {
        try {
            runTest(scheduler) {
                try {
                    for (generation in generations.asReversed()) generation.stop()
                } finally { store.close() }
            }
        } finally { process.cancel(); db.close() }
    }

    private inner class RadioGeneration(number: Long, persistence: PersistenceStoreProtocol = store) {
        val scope = CoroutineScope(SupervisorJob(process.coroutineContext[Job]) + StandardTestDispatcher(scheduler))
        val token = SessionToken(
            ProcessEpoch(UUID.fromString("CCCCCCCC-1111-2222-3333-444444444444")), Generation(number), radio,
        )
        val signals = Signals(token)
        val firmware = Firmware()
        val session = MeshCoreSession(firmware, SessionConfiguration(), clock, scope.coroutineContext)
        val diagnostics = mutableListOf<MessagingDiagnostic>()
        val service = MessageService(
            token, self, session, persistence, signals, scope,
            MessageServiceConfig(ackGiveUpWindow = 1.0), clock,
            reporter = MessagingIssueReporter { diagnostics += it },
        )
        val statuses = mutableListOf<MessageStatusEvent>()
        var statusFailure: MessageServiceException? = null
        private val subscription = service.statusEvents()
        private val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                subscription.events.collect {
                    assertEquals(token, it.token)
                    statuses += it.event
                }
            } catch (failure: MessageServiceException) { statusFailure = failure }
        }
        init { generations += this }

        suspend fun start() { session.start(); service.startEventMonitoring() }
        suspend fun stop(): TeardownReport {
            try { return service.close() }
            finally {
                try { session.stop() }
                finally { subscription.close(); collector.cancelAndJoin(); scope.cancel() }
            }
        }
    }

    private inner class FailedWriter : PersistenceStoreProtocol by store {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val failure = PersistenceStoreException(PersistenceStoreError.SaveFailed("controlled FAILED writer"))
        var commit = true
        var throws = true
        var failedWrites = 0
        var ackWrites = 0
        var committed: Boolean? = null
        val cancellation = CancellationException("controlled caller cancellation at actual Room commit")
        var cancelAtRoomCommit = false
        var cancelAfterSuccessfulReturn = false
        var cancelledAfterCommit = false
        var holdSuccessfulReturn = false
        val successfulReturnEntered = CompletableDeferred<Unit>()
        val releaseSuccessfulReturn = CompletableDeferred<Unit>()

        override suspend fun updateMessageStatusUnlessDelivered(key: EntityKey, status: MessageStatus): Boolean {
            if (status != MessageStatus.FAILED) return store.updateMessageStatusUnlessDelivered(key, status)
            failedWrites++
            check(failedWrites == 1) { "Unexpected second FAILED writer" }
            entered.complete(Unit)
            release.await()
            if (cancelAtRoomCommit) {
                val caller = requireNotNull(currentCoroutineContext()[Job])
                failedCommitObserver = {
                    cancelledAfterCommit = true
                    caller.cancel(cancellation)
                }
            }
            val changed = if (commit) store.updateMessageStatusUnlessDelivered(key, status) else false
            if (commit) committed = changed
            if (holdSuccessfulReturn) {
                check(changed) { "Post-commit barrier requires a successful actual Room FAILED return" }
                successfulReturnEntered.complete(Unit)
                releaseSuccessfulReturn.await()
            }
            if (cancelAfterSuccessfulReturn) {
                check(changed) { "Caller cancellation requires a successful actual Room FAILED return" }
                requireNotNull(currentCoroutineContext()[Job]).cancel(cancellation)
            }
            if (throws) throw failure
            return changed
        }

        override suspend fun updateMessageAck(key: EntityKey, ackCode: UInt, status: MessageStatus, roundTripTime: UInt?) {
            if (status == MessageStatus.DELIVERED) ackWrites++
            store.updateMessageAck(key, ackCode, status, roundTripTime)
        }
    }

    private suspend fun TestScope.sent(generation: RadioGeneration): MessageDTO {
        store.saveDevice(DeviceDTO(radioId = radio, publicKey = self, nodeName = "Local"))
        store.saveContact(contact)
        generation.start()
        val message = generation.service.sendDirectMessage("direct ACK", contact)
        assertEquals(MessageStatus.SENT, message.status)
        assertEquals(code.readUInt32LE(0), message.ackCode)
        assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(1, generation.service.pendingAckCount)
        runCurrent()
        generation.statuses.clear()
        return message
    }

    private suspend fun TestScope.registerAckWhileWriterIsHeld(generation: RadioGeneration, writer: FailedWriter) {
        assertTrue(writer.entered.isCompleted)
        assertFalse(writer.release.isCompleted)
        generation.firmware.pushAck()
        runCurrent()
        assertEquals(1, generation.service.pendingAckCount)
        assertEquals(0, writer.ackWrites, "DELIVERED ACK persistence must wait for the real service mutation lock")
        // A registered ACK is excluded from a second failure sweep before that sweep can take the held lock.
        val sweep = async { generation.service.failAllPendingMessages() }
        runCurrent()
        assertTrue(sweep.isCompleted, "The raw ACK must already have marked the live pending entry delivered")
        sweep.await()
        assertEquals(1, writer.failedWrites)
    }

    private suspend fun TestScope.assertUncertain(generation: RadioGeneration, writer: FailedWriter, message: MessageDTO) {
        val key = EntityKey(radio, message.id)
        runCurrent()
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(key))
        assertEquals(1, generation.service.pendingAckCount, "Uncertain FAILED is not a delivered readback")
        assertTrue(generation.statuses.isEmpty(), "Uncertain persistence must not publish delivery or terminal failure")
        assertTrue(generation.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any { it.cause === writer.failure })
        generation.service.checkExpiredAcks()
        runCurrent()
        assertEquals(1, generation.service.pendingAckCount, "A later reconciliation must retain the same uncertainty")
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(key))
        val report = generation.stop()
        assertFalse(report.isComplete)
        assertTrue(report.issues.any { it.cause === writer.failure }, "Teardown must report the original writer cause")
        assertEquals(0, generation.service.pendingAckCount)
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(key))
    }

    @Test fun failedWriterCommitsThenThrowsAfterMatchingRawAckRetainsUncertainty() = runTest(scheduler) {
        val writer = FailedWriter()
        val generation = RadioGeneration(1, writer)
        val message = sent(generation)
        val failure = async { runCatching { generation.service.failAllPendingMessages() } }
        runCurrent()
        assertEquals(MessageStatus.SENT, store.fetchMessage(EntityKey(radio, message.id))?.status)
        registerAckWhileWriterIsHeld(generation, writer)
        writer.release.complete(Unit)
        assertSame(writer.failure, failure.await().exceptionOrNull())
        assertEquals(true, writer.committed, "The actual Room writer must return after committing FAILED")
        assertUncertain(generation, writer, message)
    }

    @Test fun expiredAckWriterCommitsThenThrowsAfterMatchingRawAckRetainsUncertainty() = runTest(scheduler) {
        val writer = FailedWriter()
        val generation = RadioGeneration(2, writer)
        val message = sent(generation)
        advanceTimeBy(1_001)
        val expiry = async { generation.service.checkExpiredAcks() }
        runCurrent()
        registerAckWhileWriterIsHeld(generation, writer)
        writer.release.complete(Unit)
        expiry.await()
        assertEquals(true, writer.committed)
        assertUncertain(generation, writer, message)
    }

    @Test fun throwBeforeRoomCommitStillAllowsTheRegisteredAckToDeliver() = runTest(scheduler) {
        val writer = FailedWriter().apply { commit = false }
        val generation = RadioGeneration(3, writer)
        val message = sent(generation)
        val failure = async { runCatching { generation.service.failAllPendingMessages() } }
        runCurrent()
        registerAckWhileWriterIsHeld(generation, writer)
        writer.release.complete(Unit)
        assertSame(writer.failure, failure.await().exceptionOrNull())
        runCurrent()
        assertNull(writer.committed)
        val delivered = assertNotNull(store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(MessageStatus.DELIVERED, delivered.status)
        assertEquals(code.readUInt32LE(0), delivered.ackCode)
        assertEquals(123u, delivered.roundTripTime)
        assertEquals(0, generation.service.pendingAckCount)
        assertEquals(listOf<MessageStatusEvent>(MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 123u)), generation.statuses)
        assertTrue(generation.stop().isComplete)
    }

    @Test fun successfulRoomFailureReturnAllowsItsInFlightAckToDeliver() = runTest(scheduler) {
        val writer = FailedWriter().apply { throws = false }
        val generation = RadioGeneration(4, writer)
        val message = sent(generation)
        val failure = async { generation.service.failAllPendingMessages() }
        runCurrent()
        registerAckWhileWriterIsHeld(generation, writer)
        writer.release.complete(Unit)
        failure.await()
        runCurrent()
        assertEquals(true, writer.committed)
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertEquals(0, generation.service.pendingAckCount)
        assertEquals(listOf<MessageStatusEvent>(MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 123u)), generation.statuses)
        assertTrue(generation.stop().isComplete)
    }

    @Test fun successfulRoomFailAllReturnThenCallerCancellationStillCommitsTheRecordedAck() = runTest(scheduler) {
        successfulFailureReturnThenCancellation(expiry = false)
    }

    @Test fun successfulRoomExpiryReturnThenCallerCancellationStillCommitsTheRecordedAck() = runTest(scheduler) {
        successfulFailureReturnThenCancellation(expiry = true)
    }

    private suspend fun TestScope.successfulFailureReturnThenCancellation(expiry: Boolean) {
        val writer = FailedWriter().apply {
            throws = false
            cancelAfterSuccessfulReturn = true
        }
        val generation = RadioGeneration(if (expiry) 16 else 15, writer)
        val message = sent(generation)
        if (expiry) advanceTimeBy(1_001)
        val work = async {
            if (expiry) generation.service.checkExpiredAcks() else generation.service.failAllPendingMessages()
        }
        runCurrent()
        assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
        registerAckWhileWriterIsHeld(generation, writer)
        writer.release.complete(Unit)
        val thrown = assertFailsWith<CancellationException> { work.await() }
        assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === writer.cancellation })
        runCurrent()
        assertTrue(work.isCancelled)
        assertEquals(true, writer.committed, "Actual Room must return true before cancelling the service caller")
        assertFalse(writer.cancelledAfterCommit, "This schedule must not cancel inside the repository commit boundary")
        assertNull(failedCommitObserver)
        assertEquals(1, writer.failedWrites)
        val delivered = assertNotNull(store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(message.copy(status = MessageStatus.DELIVERED, roundTripTime = 123u), delivered)
        assertEquals(code.readUInt32LE(0), delivered.ackCode)
        assertEquals(0, generation.service.pendingAckCount)
        assertEquals(1, writer.ackWrites)
        assertEquals(1, generation.firmware.directSends)
        assertEquals(listOf<MessageStatusEvent>(
            MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 123u),
        ), generation.statuses)
        assertTrue(generation.service.isEventMonitoringActive, "Caller cancellation must not cancel the live ACK monitor")
        assertNull(generation.statusFailure)
        assertTrue(generation.diagnostics.none { it is MessagingDiagnostic.Failure })
        generation.firmware.pushAck()
        runCurrent()
        assertEquals(delivered, store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(1, generation.statuses.size, "Duplicate raw ACK must not publish delivery twice")
        assertEquals(1, writer.ackWrites)
        assertEquals(1, generation.firmware.directSends)
        assertTrue(generation.service.isEventMonitoringActive)
        assertTrue(generation.stop().isComplete)
    }

    @Test fun independentlyTerminalFailedRowCannotBeResurrectedByAnInFlightAck() = runTest(scheduler) {
        val writer = FailedWriter().apply { throws = false }
        val generation = RadioGeneration(5, writer)
        val message = sent(generation)
        val key = EntityKey(radio, message.id)
        store.updateMessageStatus(key, MessageStatus.FAILED)
        val failure = async { generation.service.failAllPendingMessages() }
        runCurrent()
        registerAckWhileWriterIsHeld(generation, writer)
        writer.release.complete(Unit)
        failure.await()
        runCurrent()
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(key))
        assertEquals(0, generation.service.pendingAckCount)
        assertTrue(generation.statuses.isEmpty())
        assertTrue(generation.stop().isComplete)
    }

    @Test fun genuinelyLateAckAfterCompletedFailureRetirementCannotResurrectRoomRow() = runTest(scheduler) {
        val generation = RadioGeneration(6)
        val message = sent(generation)
        generation.service.failAllPendingMessages()
        runCurrent()
        assertEquals(0, generation.service.pendingAckCount)
        assertEquals(listOf<MessageStatusEvent>(MessageStatusEvent.Failed(message.id)), generation.statuses)
        generation.firmware.pushAck()
        runCurrent()
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(0, generation.service.pendingAckCount)
        assertEquals(listOf<MessageStatusEvent>(MessageStatusEvent.Failed(message.id)), generation.statuses)
        assertTrue(generation.diagnostics.any { it is MessagingDiagnostic.UnmatchedAcknowledgement })
        assertTrue(generation.stop().isComplete)
    }

    @Test fun competingFailedWriterCannotBeMistakenForTheThrowingWritersCommit() = runTest(scheduler) {
        val writer = FailedWriter().apply { commit = false }
        val generation = RadioGeneration(7, writer)
        val message = sent(generation)
        val failure = async { runCatching { generation.service.failAllPendingMessages() } }
        runCurrent()
        assertTrue(writer.entered.isCompleted)
        store.updateMessageStatus(EntityKey(radio, message.id), MessageStatus.FAILED)
        registerAckWhileWriterIsHeld(generation, writer)
        writer.release.complete(Unit)
        assertSame(writer.failure, failure.await().exceptionOrNull())
        assertNull(writer.committed, "This writer did not commit the competing FAILED transition")
        assertUncertain(generation, writer, message)
    }

    @Test fun competingFailedWriterCannotBeMistakenForASuccessfulSameValueWrite() = runTest(scheduler) {
        successfulSameValueWriteOverCompetingFailure(cancelCaller = false)
    }

    @Test fun competingFailedWriterCannotBeMistakenForASuccessfulSameValueWriteAfterCallerCancellation() = runTest(scheduler) {
        successfulSameValueWriteOverCompetingFailure(cancelCaller = true)
    }

    private suspend fun TestScope.successfulSameValueWriteOverCompetingFailure(cancelCaller: Boolean) {
        val writer = FailedWriter().apply {
            throws = false
            cancelAfterSuccessfulReturn = cancelCaller
        }
        val generation = RadioGeneration(if (cancelCaller) 18 else 17, writer)
        val message = sent(generation)
        val key = EntityKey(radio, message.id)
        val failure = async { generation.service.failAllPendingMessages() }
        runCurrent()
        assertTrue(writer.entered.isCompleted)
        assertEquals(message, store.fetchMessage(key), "The service must begin its failure attempt from SENT")
        store.updateMessageStatus(key, MessageStatus.FAILED)
        val independentlyFailed = message.copy(status = MessageStatus.FAILED)
        assertEquals(independentlyFailed, store.fetchMessage(key))
        registerAckWhileWriterIsHeld(generation, writer)
        writer.release.complete(Unit)
        if (cancelCaller) {
            val thrown = assertFailsWith<CancellationException> { failure.await() }
            assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === writer.cancellation })
            assertTrue(failure.isCancelled)
        } else {
            failure.await()
        }
        runCurrent()
        assertEquals(true, writer.committed, "The existing public Room writer returns true for this same-value write")
        assertEquals(1, writer.failedWrites)
        assertEquals(independentlyFailed, store.fetchMessage(key), "A successful same-value write is not ownership of the competing FAILED transition")
        val persisted = assertNotNull(store.fetchMessage(key))
        assertEquals(independentlyFailed.ackCode, persisted.ackCode)
        assertEquals(independentlyFailed.roundTripTime, persisted.roundTripTime)
        assertTrue(generation.statuses.isEmpty())
        assertEquals(1, generation.firmware.directSends)
        assertTrue(generation.service.isEventMonitoringActive)
        assertNull(generation.statusFailure)
        generation.stop()
        assertEquals(independentlyFailed, store.fetchMessage(key), "Teardown must not resurrect the competing terminal failure")
        val afterTeardown = assertNotNull(store.fetchMessage(key))
        assertEquals(independentlyFailed.ackCode, afterTeardown.ackCode)
        assertEquals(independentlyFailed.roundTripTime, afterTeardown.roundTripTime)
        assertTrue(generation.statuses.isEmpty())
        assertEquals(1, generation.firmware.directSends)
    }

    @Test fun directDaoSameValueWriteAfterOwnCommitInvalidatesAckRestoration() = runTest(scheduler) {
        competingMutationAfterOwnCommit(19) { key ->
            assertEquals(1, db.messages().setStatus(key.radioId.value, key.id, MessageStatus.FAILED.rawValue))
        }
    }

    @Test fun directDaoUpsertAfterOwnCommitInvalidatesAckRestoration() = runTest(scheduler) {
        competingMutationAfterOwnCommit(20) { key ->
            db.messages().upsert(assertNotNull(db.messages().byId(key.radioId.value, key.id)))
        }
    }

    @Test fun directDaoDeleteAndRecreateAfterOwnCommitInvalidatesAckRestoration() = runTest(scheduler) {
        competingMutationAfterOwnCommit(21) { key ->
            db.withTransaction {
                val row = assertNotNull(db.messages().byId(key.radioId.value, key.id))
                assertEquals(1, db.messages().delete(key.radioId.value, key.id))
                db.messages().insert(row)
            }
        }
    }

    @Test fun distinctRepositoryContextWriteAfterOwnCommitInvalidatesAckRestoration() = runTest(scheduler) {
        competingMutationAfterOwnCommit(22) { key ->
            val other = RoomPersistenceStore(db, process, clock.wallClock)
            try { other.updateMessageStatus(key, MessageStatus.FAILED) }
            finally { other.close() }
        }
    }

    @Test fun rawSqlSameValueWriteAfterOwnCommitInvalidatesAckRestoration() = runTest(scheduler) {
        competingMutationAfterOwnCommit(23) { key ->
            val statement = db.openHelper.writableDatabase.compileStatement(
                "UPDATE messages SET statusRawValue = ? WHERE radioId = ? AND id = ?",
            )
            try {
                statement.bindLong(1, MessageStatus.FAILED.rawValue)
                statement.bindString(2, key.radioId.canonicalString)
                statement.bindString(3, key.id.canonicalString())
                assertEquals(1, statement.executeUpdateDelete())
            } finally { statement.close() }
        }
    }

    private suspend fun TestScope.competingMutationAfterOwnCommit(
        number: Long,
        mutate: suspend (EntityKey) -> Unit,
    ) {
        val writer = FailedWriter().apply {
            throws = false
            holdSuccessfulReturn = true
        }
        val generation = RadioGeneration(number, writer)
        val message = sent(generation)
        val key = EntityKey(radio, message.id)
        val work = async { generation.service.failAllPendingMessages() }
        runCurrent()
        registerAckWhileWriterIsHeld(generation, writer)
        writer.release.complete(Unit)
        runCurrent()
        assertTrue(writer.successfulReturnEntered.isCompleted)
        assertEquals(true, writer.committed)
        val failed = message.copy(status = MessageStatus.FAILED)
        assertEquals(failed, store.fetchMessage(key), "Own FAILED commit must precede the competing mutation")
        mutate(key)
        assertEquals(failed, store.fetchMessage(key), "The competing mutation deliberately preserves every DTO field")
        writer.releaseSuccessfulReturn.complete(Unit)
        work.await()
        runCurrent()
        assertEquals(failed, store.fetchMessage(key), "Competing mutation must invalidate restoration even when the DTO is identical")
        assertTrue(generation.statuses.isEmpty())
        assertEquals(1, generation.firmware.directSends)
        assertTrue(generation.service.isEventMonitoringActive)
        assertNull(generation.statusFailure)
        generation.stop()
        assertEquals(failed, store.fetchMessage(key), "Teardown must not reuse authority invalidated by the competing mutation")
        assertTrue(generation.statuses.isEmpty())
        assertEquals(1, generation.firmware.directSends)
    }

    @Test fun competingDeliveredWriterRemainsDeliveredWhenFailedWriterThrows() = runTest(scheduler) {
        val writer = FailedWriter()
        val generation = RadioGeneration(8, writer)
        val message = sent(generation)
        val failure = async { runCatching { generation.service.failAllPendingMessages() } }
        runCurrent()
        assertTrue(writer.entered.isCompleted)
        store.updateMessageAck(EntityKey(radio, message.id), code.readUInt32LE(0), MessageStatus.DELIVERED, 123u)
        registerAckWhileWriterIsHeld(generation, writer)
        writer.release.complete(Unit)
        assertSame(writer.failure, failure.await().exceptionOrNull())
        runCurrent()
        assertEquals(false, writer.committed, "Room must reject FAILED over the competing DELIVERED row")
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertEquals(0, generation.service.pendingAckCount)
        assertTrue(generation.stop().isComplete)
    }

    @Test fun replacementSessionCannotBorrowTheOldInstancesUncertaintyOrPendingAck() = runTest(scheduler) {
        val writer = FailedWriter()
        val old = RadioGeneration(9, writer)
        val message = sent(old)
        val failure = async { runCatching { old.service.failAllPendingMessages() } }
        runCurrent()
        registerAckWhileWriterIsHeld(old, writer)
        writer.release.complete(Unit)
        assertSame(writer.failure, failure.await().exceptionOrNull())
        assertUncertain(old, writer, message)
        val replacement = RadioGeneration(10)
        old.signals.replace(replacement.token)
        replacement.start()
        replacement.firmware.pushAck()
        runCurrent()
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(0, replacement.service.pendingAckCount)
        assertTrue(replacement.statuses.isEmpty())
        assertTrue(replacement.diagnostics.any { it is MessagingDiagnostic.UnmatchedAcknowledgement })
        assertTrue(replacement.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().none { it.cause === writer.failure })
        assertTrue(replacement.stop().isComplete)
    }

    @Test fun replacementWhileFailedWriterAndRecordedAckAreInFlightCannotMutateSuccessorDelivery() = runTest(scheduler) {
        val writer = FailedWriter()
        val old = RadioGeneration(11, writer)
        val message = sent(old)
        val failure = async { runCatching { old.service.failAllPendingMessages() } }
        runCurrent()
        registerAckWhileWriterIsHeld(old, writer)
        val replacement = RadioGeneration(12)
        old.signals.replace(replacement.token)
        replacement.start()
        assertFalse(writer.release.isCompleted)
        assertEquals(MessageStatus.SENT, store.fetchMessage(EntityKey(radio, message.id))?.status)
        writer.release.complete(Unit)
        assertSame(writer.failure, failure.await().exceptionOrNull())
        runCurrent()
        assertEquals(true, writer.committed)
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, message.id)))
        assertTrue(old.statuses.isEmpty())
        assertEquals(0, writer.ackWrites, "Superseded live reconciliation must not persist the old ACK")
        val report = old.stop()
        assertTrue(report.issues.any { it.cause === writer.failure })
        assertEquals(0, old.service.pendingAckCount)
        assertEquals(0, writer.ackWrites, "Old teardown must not turn uncertainty into write authority")
        replacement.firmware.pushAck()
        runCurrent()
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(0, replacement.service.pendingAckCount)
        assertTrue(replacement.statuses.isEmpty())
        assertNull(replacement.statusFailure)
        assertTrue(replacement.diagnostics.any { it is MessagingDiagnostic.UnmatchedAcknowledgement })
        assertTrue(replacement.stop().isComplete)
    }

    @Test fun actualRoomCommitThenCallerCancellationRetainsAckWithoutCancellingLiveMonitor() = runTest(scheduler) {
        val writer = FailedWriter().apply { cancelAtRoomCommit = true }
        val generation = RadioGeneration(13, writer)
        val message = sent(generation)
        val work = async { generation.service.failAllPendingMessages() }
        runCurrent()
        registerAckWhileWriterIsHeld(generation, writer)
        writer.release.complete(Unit)
        val thrown = assertFailsWith<CancellationException> { work.await() }
        assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === writer.cancellation },
            "Coroutine stack recovery must preserve the original caller cancellation in its cause chain")
        runCurrent()
        assertTrue(writer.cancelledAfterCommit, "Caller cancellation must originate after actual SQLite endTransaction")
        assertNull(failedCommitObserver)
        assertNull(writer.committed, "The repository's cancelled return must not become a successful write receipt")
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(1, generation.service.pendingAckCount)
        assertTrue(generation.service.isEventMonitoringActive, "Uncertainty must not cancel the fresh live ACK monitor")
        assertTrue(generation.statuses.isEmpty())
        assertNull(generation.statusFailure)
        assertTrue(generation.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
            it.cause === writer.cancellation
        })
        generation.service.checkExpiredAcks()
        runCurrent()
        assertEquals(1, generation.service.pendingAckCount)
        assertTrue(generation.service.isEventMonitoringActive)
        val nextCode = Bytes.fromHex("89ABCDEF")
        generation.firmware.acceptedCode = nextCode
        val next = generation.service.sendDirectMessage("independent ACK after caller cancellation", contact)
        runCurrent()
        generation.statuses.clear()
        generation.firmware.pushAck(nextCode)
        runCurrent()
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, next.id))?.status)
        assertEquals(listOf<MessageStatusEvent>(MessageStatusEvent.StatusResolved(next.id, MessageStatus.DELIVERED, 123u)),
            generation.statuses)
        assertEquals(1, generation.service.pendingAckCount)
        val report = generation.stop()
        assertTrue(report.issues.any { it.cause === writer.cancellation })
        assertEquals(0, generation.service.pendingAckCount)
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, message.id)))
        val replacement = RadioGeneration(14)
        generation.signals.replace(replacement.token)
        replacement.start()
        replacement.firmware.pushAck(code)
        runCurrent()
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, message.id)),
            "The original teardown diagnostic is not a durable writer or ACK receipt for the successor")
        assertEquals(0, replacement.service.pendingAckCount)
        assertTrue(replacement.statuses.isEmpty())
        assertTrue(replacement.diagnostics.any { it is MessagingDiagnostic.UnmatchedAcknowledgement })
        assertTrue(replacement.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().none {
            it.cause === writer.cancellation
        })
        assertTrue(replacement.stop().isComplete)
    }

    private class Signals(token: SessionToken) : ConnectionSignals {
        private val value: MutableStateFlow<ConnectionSnapshot> = MutableStateFlow(snapshot(token))
        override val snapshot: MutableStateFlow<ConnectionSnapshot> get() = value
        fun replace(token: SessionToken) { value.value = snapshot(token) }
        override suspend fun subscribeTransitions(): ConnectionSubscription = object : ConnectionSubscription {
            override val initial = value.value
            override val transitions: Flow<ConnectionSnapshot> = flow { awaitCancellation() }
            override fun close() = Unit
        }
        companion object {
            fun snapshot(token: SessionToken): ConnectionSnapshot = ConnectionSnapshot(
                DeviceConnectionState.CONNECTED, ConnectionState.Connected, null,
                ConnectionIntent.WantsConnection(), token, null,
            )
        }
    }

    private inner class Firmware : MeshTransport {
        private val mock = MockTransport()
        var acceptedCode: Bytes = code
        var directSends = 0
            private set
        override suspend fun isConnected() = mock.isConnected()
        override suspend fun connect() = mock.connect()
        override suspend fun disconnect() = mock.disconnect()
        override suspend fun receivedData() = mock.receivedData()
        override suspend fun supportsWriteWithoutResponse() = false
        override suspend fun supportsPipelinedReads() = false
        override suspend fun send(data: Bytes) {
            mock.send(data)
            when (CommandCode.fromRawValue(data[0])) {
                CommandCode.APP_START -> mock.simulateReceive(
                    ByteWriter().appendUInt8(ResponseCode.SELF_INFO.rawValue)
                        .appendUInt8(1u).appendUInt8(22u).appendUInt8(22u).append(self)
                        .appendInt32LE(0).appendInt32LE(0).appendUInt8(0u).appendUInt8(0u).appendUInt8(0u)
                        .appendUInt32LE(915_000u).appendUInt32LE(125_000u).appendUInt8(7u).appendUInt8(5u)
                        .append(Bytes.utf8("Test")).toBytes(),
                )
                CommandCode.SEND_MESSAGE -> {
                    directSends++
                    mock.simulateReceive(
                        ByteWriter().appendUInt8(ResponseCode.MESSAGE_SENT.rawValue).appendUInt8(0u)
                            .append(acceptedCode).appendUInt32LE(1u).toBytes(),
                    )
                }
                CommandCode.GET_CONTACT_BY_KEY -> mock.simulateError(2u)
                else -> throw AssertionError("Unexpected direct-ACK test command: ${data[0]}")
            }
        }
        suspend fun pushAck(ack: Bytes = code) {
            mock.simulateReceive(
                ByteWriter().appendUInt8(ResponseCode.ACK.rawValue).append(ack).appendUInt32LE(123u).toBytes(),
            )
        }
    }
}
