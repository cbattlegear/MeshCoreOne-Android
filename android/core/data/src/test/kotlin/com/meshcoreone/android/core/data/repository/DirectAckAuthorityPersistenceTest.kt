// AndroidOnly: WP-202 Real Room authority, independent-writer and lifecycle controls.
package com.meshcoreone.android.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
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
class DirectAckAuthorityPersistenceTest {
    private lateinit var db: MeshCoreDatabase
    private lateinit var store: RoomPersistenceStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val token = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), RADIO_A)
    private val immediate = Executor { it.run() }
    private val reported = mutableListOf<PersistenceStoreException>()

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .addCallback(DirectAckStorage.callback).allowMainThreadQueries()
            .setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        store = newStore(db)
    }

    private fun newStore(database: MeshCoreDatabase) =
        RoomPersistenceStore(database, scope, RepositoryClock(), RepositoryIssueReporter { _, failure -> reported += failure })

    @After fun close() {
        try { runBlocking { store.close() } } finally { scope.cancel(); db.close() }
    }

    private suspend fun activate(
        target: RoomPersistenceStore = store,
        session: SessionToken = token,
        request: UUID = UUID.randomUUID(),
    ): DirectAckLease = assertIs<DirectAckActivationResult.Activated>(target.activateDirectAckSession(session, request)).lease

    private data class Claim(
        val lease: DirectAckLease,
        val request: UUID,
        val wire: UUID,
        val intent: DirectAckIntent,
        val attempt: DirectAckAttempt,
        val row: MessageDTO,
    )

    private suspend fun begin(
        row: MessageDTO = message(),
        mode: DirectAckMode = DirectAckMode.SEND,
        pendingId: UUID? = null,
        lease: DirectAckLease? = null,
        target: RoomPersistenceStore = store,
    ): Claim {
        val issued = lease ?: activate(target)
        target.saveMessage(row)
        val request = UUID.randomUUID()
        val wire = UUID.randomUUID()
        val capture = assertIs<DirectAckIntentResult.Captured>(
            target.captureDirectAckIntent(issued, entity(id = row.id), request, mode, pendingId),
        )
        assertEquals(row, capture.receipt.message)
        val result = assertIs<DirectAckBeginResult.Begun>(
            target.beginDirectAckAttempt(capture.intent, wire, 123u),
        )
        return Claim(issued, request, wire, capture.intent, result.attempt, row)
    }

    private suspend fun observed(claim: Claim, target: RoomPersistenceStore = store): DirectAckReceipt =
        assertIs<DirectAckObservation.Found>(target.inspectDirectAckAttempt(claim.lease, claim.request)).receipt

    private fun rejected(result: DirectAckMutationResult, reason: DirectAckRejection = DirectAckRejection.INVALIDATED) {
        assertEquals(reason, assertIs<DirectAckMutationResult.Rejected>(result).reason)
    }

    private suspend fun capture(
        row: MessageDTO,
        lease: DirectAckLease,
        request: UUID = UUID.randomUUID(),
        pendingId: UUID? = null,
    ) = assertIs<DirectAckIntentResult.Captured>(
        store.captureDirectAckIntent(lease, entity(id = row.id), request, DirectAckMode.SEND, pendingId),
    )

    @Test fun issuedIntentRejectsBeforeBeginTextMutationSameValueUpdateAndIdenticalDtoAba() = runTest {
        val lease = activate()
        for (mutation in listOf<suspend (MessageDTO) -> Unit>(
            { store.saveMessage(it.copy(text = "replacement wire payload")) },
            { db.messages().upsert(it.toEntity()) },
            { db.messages().delete(RADIO_A.value, it.id); db.messages().insert(it.toEntity()) },
            { db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO messages SELECT * FROM messages") },
        )) {
            val row = message()
            store.saveMessage(row)
            val request = UUID.randomUUID()
            val issued = capture(row, lease, request)
            assertEquals(row, issued.receipt.message)
            assertIs<DirectAckIntentResult.AlreadyCaptured>(
                store.captureDirectAckIntent(lease, entity(id = row.id), request, DirectAckMode.SEND, null),
            )
            mutation(row)
            val independent = store.fetchMessage(entity(id = row.id))
            assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckBeginResult.Rejected>(
                store.beginDirectAckAttempt(issued.intent, UUID.randomUUID(), 123u),
            ).reason)
            assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckIntentResult.Rejected>(
                store.captureDirectAckIntent(lease, entity(id = row.id), request, DirectAckMode.SEND, null),
            ).reason)
            assertIs<DirectAckObservation.Rejected>(store.inspectDirectAckAttempt(lease, request))
            assertEquals(independent, store.fetchMessage(entity(id = row.id)))
        }
    }

    @Test fun issuedIntentRejectsSameIdPendingReplacementAndGenericPreflightAfterCapture() = runTest {
        val lease = activate()
        for (mutation in listOf<suspend (PendingSendDTO) -> Unit>(
            { store.upsertPendingSend(it) },
            { db.pendingSends().delete(RADIO_A.value, it.id); db.pendingSends().upsert(it.toEntity()) },
            { db.openHelper.writableDatabase.execSQL("UPDATE pending_sends SET attemptCount = COALESCE(attemptCount,0) + 1") },
            { db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO pending_sends SELECT * FROM pending_sends") },
        )) {
            val row = message()
            val queued = pending(messageID = row.id)
            store.saveMessage(row)
            store.upsertPendingSend(queued)
            val request = UUID.randomUUID()
            val issued = capture(row, lease, request, queued.id)
            assertEquals(queued, issued.receipt.pending)
            mutation(queued)
            assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckBeginResult.Rejected>(
                store.beginDirectAckAttempt(issued.intent, UUID.randomUUID(), 123u),
            ).reason)
            val history = assertIs<DirectAckIntentObservation.Found>(store.inspectDirectAckIntent(lease, request)).receipt
            assertEquals(queued.id, history.pendingSendId)
            assertNull(history.pending)
            assertNotEquals(DirectAckPermission.CURRENT, history.permission)
            assertEquals(row, store.fetchMessage(entity(id = row.id)))
        }
    }

    @Test fun captureAfterOldCacheWindowIssuesActualPayloadAndPreparationMustPrecedeCapture() = runTest {
        val lease = activate()
        val earlier = message(text = "earlier unguarded wire A")
        val current = earlier.copy(text = "actual wire B", timestamp = earlier.timestamp + 1u)
        store.saveMessage(earlier)
        val cached = assertNotNull(store.fetchMessage(entity(id = earlier.id)))
        store.saveMessage(current)
        val queued = pending(messageID = earlier.id, attempt = 1)
        store.upsertPendingSend(queued)
        val issued = capture(earlier, lease, pendingId = queued.id)
        val wireInputs = assertNotNull(issued.receipt.message)
        assertEquals(current, wireInputs)
        assertNotEquals(cached.text, wireInputs.text)
        assertEquals(queued, issued.receipt.pending)
        val begun = assertIs<DirectAckBeginResult.Begun>(
            store.beginDirectAckAttempt(issued.intent, UUID.randomUUID(), 456u),
        )
        assertEquals(current, begun.receipt.message)
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(begun.attempt, 456u, 7u))
        assertEquals(current.copy(status = MessageStatus.DELIVERED, ackCode = 456u, roundTripTime = 7u),
            store.fetchMessage(entity(id = earlier.id)))
    }

    private fun assertPayloadColumnsNull(table: String, messageId: UUID, vararg prefixes: String) {
        db.openHelper.readableDatabase.query(
            "SELECT * FROM $table WHERE radioId = ? AND messageId = ?",
            arrayOf(RADIO_A.value.toString().uppercase(), messageId.toString().uppercase()),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst(), "$table tombstone must remain")
            do {
                val payloadColumns = cursor.columnNames.filter { name -> prefixes.any { prefix -> name.startsWith(prefix) } }
                assertTrue(payloadColumns.isNotEmpty(), "$table payload columns must be checked")
                for (column in payloadColumns) assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow(column)), "$table.$column")
            } while (cursor.moveToNext())
        }
    }

    @Test fun messageDeleteRedactionReplaceAndConversationDevicePathsEraseAllHistoricalPayloads() = runTest {
        val lease = activate()
        for (erase in listOf<suspend (MessageDTO, DeviceDTO) -> Unit>(
            { row, _ -> store.deleteMessage(entity(id = row.id)) },
            { row, _ -> db.messages().delete(RADIO_A.value, row.id) },
            { row, _ -> db.openHelper.writableDatabase.execSQL(
                "DELETE FROM messages WHERE radioId = ? AND id = ?",
                arrayOf(RADIO_A.value.toString().uppercase(), row.id.toString().uppercase())) },
            { row, _ -> store.deleteMessagesForContact(entity(id = assertNotNull(row.contactID))) },
            { _, device -> store.deleteDeviceData(device.id) },
            { _, device -> store.deleteDeviceAndData(device.id) },
            { row, _ -> store.saveMessage(row.copy(text = "redacted replacement")) },
            { row, _ -> store.saveMessage(row.copy(contactID = null, channelIndex = 1u)) },
            { row, _ -> db.openHelper.writableDatabase.execSQL(
                "UPDATE messages SET text = '' WHERE radioId = ? AND id = ?",
                arrayOf(RADIO_A.value.toString().uppercase(), row.id.toString().uppercase())) },
            { _, _ -> db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO messages SELECT * FROM messages") },
            { row, _ -> db.messages().delete(RADIO_A.value, row.id); db.messages().insert(row.toEntity()) },
        )) {
            val row = message(text = "must not survive in authority history").copy(
                linkPreviewImageData = key(0x71), linkPreviewIconData = key(0x72), pathNodes = key(0x73),
            )
            val queued = pending(messageID = row.id).copy(messageText = row.text)
            val device = device()
            store.saveDevice(device)
            store.upsertPendingSend(queued)
            val claim = begin(row, pendingId = queued.id, lease = lease)
            val noWireRequest = UUID.randomUUID()
            val noWire = assertIs<DirectPendingCaptureResult.Captured>(
                store.captureDirectPendingCompletion(lease, entity(id = row.id), queued.id, noWireRequest,
                    DirectPendingCompletionReason.ZeroAttempts(0)),
            )
            assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(claim.attempt, 123u, 7u))
            erase(row, device)
            val history = observed(claim)
            assertNull(history.message)
            assertNull(assertNotNull(history.delivery).message)
            assertEquals(123u, history.delivery?.ackCode)
            assertEquals(7u, history.delivery?.roundTripTime)
            assertNotEquals(DirectAckPermission.CURRENT, history.permission)
            val intentHistory = assertIs<DirectAckIntentObservation.Found>(
                store.inspectDirectAckIntent(lease, claim.request),
            ).receipt
            assertNull(intentHistory.message)
            assertNull(intentHistory.pending)
            assertEquals(queued.id, intentHistory.pendingSendId)
            val completionHistory = assertIs<DirectPendingCompletionObservation.Found>(
                store.inspectDirectPendingCompletion(lease, noWireRequest),
            ).receipt
            assertNull(completionHistory.message)
            assertNull(completionHistory.pending)
            assertEquals(queued.id, completionHistory.pendingSendId)
            assertIs<DirectPendingCompletionResult.Rejected>(
                store.completeDirectPendingWithoutDispatch(noWire.completion, UUID.randomUUID()),
            )
            assertIs<DirectAckBeginResult.Rejected>(store.beginDirectAckAttempt(claim.intent, claim.wire, 123u))
            assertIs<DirectAckIntentResult.Rejected>(
                store.captureDirectAckIntent(lease, entity(id = row.id), claim.request, DirectAckMode.SEND, queued.id),
            )
            val replay = assertIs<DirectAckMutationResult.AlreadyApplied>(store.settleDirectAck(claim.attempt, 123u, 7u))
            assertNull(replay.receipt.message)
            assertNull(replay.receipt.delivery?.message)
            assertNotEquals(DirectAckPermission.CURRENT, replay.receipt.permission)
            assertPayloadColumnsNull("direct_ack_claims", row.id, "snapshot_")
            assertPayloadColumnsNull("direct_ack_deliveries", row.id, "snapshot_")
            assertPayloadColumnsNull("direct_ack_intents", row.id, "message_", "pending_")
            assertPayloadColumnsNull("direct_pending_completions", row.id, "message_", "pending_")
        }
    }

    @Test fun ownNoDispatchCompletionErasesPendingSnapshotWithoutRecreatingItOnReplay() = runTest {
        val lease = activate()
        val row = message()
        val queued = pending(messageID = row.id).copy(messageText = "erase pending payload")
        store.saveMessage(row)
        store.upsertPendingSend(queued)
        val request = UUID.randomUUID()
        val captured = assertIs<DirectPendingCaptureResult.Captured>(
            store.captureDirectPendingCompletion(lease, entity(id = row.id), queued.id, request,
                DirectPendingCompletionReason.ZeroAttempts(0)),
        )
        val completion = UUID.randomUUID()
        val committed = assertIs<DirectPendingCompletionResult.Applied>(
            store.completeDirectPendingWithoutDispatch(captured.completion, completion),
        ).receipt
        assertEquals(row, committed.message)
        assertEquals(queued.id, committed.pendingSendId)
        assertNull(committed.pending)
        assertEquals(completion, committed.completedRequestId)
        assertNotEquals(DirectPendingCompletionPermission.CURRENT, committed.permission)
        store.upsertPendingSend(queued)
        val replay = assertIs<DirectPendingCompletionResult.AlreadyApplied>(
            store.completeDirectPendingWithoutDispatch(captured.completion, completion),
        ).receipt
        assertNull(replay.pending)
        assertEquals(listOf(queued), store.fetchPendingSendsForMessage(entity(id = row.id)))
        assertPayloadColumnsNull("direct_pending_completions", row.id, "pending_")
        store.deleteMessage(entity(id = row.id))
        val erased = assertIs<DirectPendingCompletionObservation.Found>(
            store.inspectDirectPendingCompletion(lease, request),
        ).receipt
        assertNull(erased.message)
        assertNull(erased.pending)
        assertEquals(completion, erased.completedRequestId)
    }

    @Test fun intentCommitThenCallerCancellationReplaysOnlyOriginalCaptureAndConsumedBegin() = runTest {
        val lease = activate()
        val row = message()
        store.saveMessage(row)
        val request = UUID.randomUUID()
        val caller = Job()
        assertFailsWith<CancellationException> {
            withContext(caller) {
                capture(row, lease, request)
                caller.cancel()
                ensureActive()
            }
        }
        val recovered = assertIs<DirectAckIntentResult.AlreadyCaptured>(
            store.captureDirectAckIntent(lease, entity(id = row.id), request, DirectAckMode.SEND, null),
        )
        assertEquals(row, recovered.receipt.message)
        assertEquals(DirectAckPermission.CURRENT, recovered.receipt.permission)
        val wire = UUID.randomUUID()
        assertIs<DirectAckBeginResult.Begun>(store.beginDirectAckAttempt(recovered.intent, wire, 123u))
        assertIs<DirectAckBeginResult.AlreadyBegun>(store.beginDirectAckAttempt(recovered.intent, wire, 123u))
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckIntentResult.Rejected>(
            store.captureDirectAckIntent(lease, entity(id = row.id), request, DirectAckMode.SEND, null),
        ).reason)
    }

    @Test fun intentForeignContextRestoreRollbackAndGenerationCannotRenewCapturedBindings() = runTest {
        val lease = activate()
        val row = message()
        store.saveMessage(row)
        val request = UUID.randomUUID()
        val issued = capture(row, lease, request)
        val other = newStore(db)
        try {
            assertEquals(DirectAckRejection.INVALID_HANDLE, assertIs<DirectAckBeginResult.Rejected>(
                other.beginDirectAckAttempt(issued.intent, UUID.randomUUID(), 123u),
            ).reason)
            assertFailsWith<CancellationException> {
                store.withBackupRestore { _, _ -> throw CancellationException("Controlled restore rollback") }
            }
            val retained = assertIs<DirectAckIntentResult.AlreadyCaptured>(
                store.captureDirectAckIntent(lease, entity(id = row.id), request, DirectAckMode.SEND, null),
            )
            assertEquals(DirectAckPermission.CURRENT, retained.receipt.permission)
            store.withBackupRestore { _, _ -> Unit }
            assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckBeginResult.Rejected>(
                store.beginDirectAckAttempt(issued.intent, UUID.randomUUID(), 123u),
            ).reason)
            val fresh = capture(row, lease)
            activate(other, token.copy(generation = Generation(2)))
            assertEquals(DirectAckRejection.STALE_LEASE, assertIs<DirectAckBeginResult.Rejected>(
                store.beginDirectAckAttempt(fresh.intent, UUID.randomUUID(), 123u),
            ).reason)
        } finally { other.close() }
    }

    @Test fun explicitRevokeSuccessorActivationAndProcessCloseEraseOnlyRetiredLeasePayloads() = runTest {
        var currentToken = token
        for (retire in listOf<suspend (RoomPersistenceStore, DirectAckLease) -> Unit>(
            { target, lease -> assertIs<DirectAckRevocationResult.Revoked>(target.revokeDirectAckSession(lease)) },
            { target, _ ->
                currentToken = currentToken.copy(generation = Generation(currentToken.generation.value + 1))
                activate(target, currentToken)
            },
            { target, _ -> target.close() },
        )) {
            val target = newStore(db)
            currentToken = token.copy(epoch = ProcessEpoch(UUID.randomUUID()))
            val lease = activate(target, currentToken)
            val row = message(text = "retired lease payload")
            val queued = pending(messageID = row.id)
            target.upsertPendingSend(queued)
            val claim = begin(row, pendingId = queued.id, lease = lease, target = target)
            val completion = assertIs<DirectPendingCaptureResult.Captured>(
                target.captureDirectPendingCompletion(lease, entity(id = row.id), queued.id, UUID.randomUUID(),
                    DirectPendingCompletionReason.ZeroAttempts(0)),
            )
            target.settleDirectAck(claim.attempt, 123u, 7u)
            val productRow = target.fetchMessage(entity(id = row.id))
            retire(target, lease)
            assertPayloadColumnsNull("direct_ack_claims", row.id, "snapshot_")
            assertPayloadColumnsNull("direct_ack_deliveries", row.id, "snapshot_")
            assertPayloadColumnsNull("direct_ack_intents", row.id, "message_", "pending_")
            assertPayloadColumnsNull("direct_pending_completions", row.id, "message_", "pending_")
            val persisted = assertNotNull(db.directAcks().claim(RADIO_A.value, claim.request))
            assertEquals(DirectAckPhase.DELIVERED.name, persisted.phase)
            assertFalse(persisted.current)
            val delivery = assertNotNull(db.directAcks().delivery(RADIO_A.value, claim.request))
            assertEquals(123L, delivery.ackCode)
            assertEquals(7L, delivery.roundTripTime)
            assertNull(delivery.snapshot)
            assertEquals(productRow, store.fetchMessage(entity(id = row.id)))
            assertEquals(listOf(queued), store.fetchPendingSendsForMessage(entity(id = row.id)))
            assertNotNull(db.directAcks().pendingCompletion(RADIO_A.value, completion.receipt.captureRequestId))
            target.close()
        }
    }

    @Test fun ownFailureAndMatchingAckCommitAtomicallyWithExactTupleReplay() = runTest {
        val claim = begin()
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(claim.attempt, claim.wire, 456u))
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectFailure(claim.attempt))
        val failed = assertNotNull(store.fetchMessage(entity(id = claim.row.id)))
        assertEquals(MessageStatus.FAILED, failed.status)
        assertNull(failed.roundTripTime)
        val result = assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(claim.attempt, 123u, 42u))
        val delivered = assertNotNull(store.fetchMessage(entity(id = claim.row.id)))
        assertEquals(failed.copy(status = MessageStatus.DELIVERED, ackCode = 123u, roundTripTime = 42u), delivered)
        assertEquals(delivered, assertNotNull(result.receipt.delivery).message)
        assertIs<DirectAckMutationResult.AlreadyApplied>(store.settleDirectAck(claim.attempt, 123u, 42u))
        rejected(store.settleDirectAck(claim.attempt, 123u, 43u), DirectAckRejection.CONFLICT)
    }

    @Test fun activationAndBeginReplayRecoverOnlyTheExactStillCurrentLogicalClaim() = runTest {
        val activation = UUID.randomUUID()
        val lease = activate(request = activation)
        assertIs<DirectAckActivationResult.AlreadyActivated>(store.activateDirectAckSession(token, activation))
        val claim = begin(lease = lease)
        assertIs<DirectAckBeginResult.AlreadyBegun>(
            store.beginDirectAckAttempt(claim.intent, claim.wire, 123u),
        )
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckBeginResult.Rejected>(
            store.beginDirectAckAttempt(claim.intent, claim.wire, 124u),
        ).reason)
        val next = assertIs<DirectAckIntentResult.Captured>(
            store.captureDirectAckIntent(lease, entity(id = claim.row.id), UUID.randomUUID(), DirectAckMode.SEND, null),
        )
        val successor = assertIs<DirectAckBeginResult.Begun>(
            store.beginDirectAckAttempt(next.intent, UUID.randomUUID(), 456u),
        )
        rejected(store.settleDirectAck(claim.attempt, 123u, 1u))
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckBeginResult.Rejected>(
            store.beginDirectAckAttempt(claim.intent, claim.wire, 123u),
        ).reason)
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectFailure(successor.attempt))
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(successor.attempt, 456u, 2u))
        activate(session = token.copy(generation = Generation(2)))
        assertEquals(DirectAckRejection.STALE_LEASE, assertIs<DirectAckActivationResult.Rejected>(
            store.activateDirectAckSession(token, activation),
        ).reason)
    }

    @Test fun competingFailedBeforeOwnFailureAndAfterOwnCommitRemainIndependent() = runTest {
        val lease = activate()
        for (before in listOf(true, false)) {
            val claim = begin(lease = lease)
            if (!before) assertIs<DirectAckMutationResult.Applied>(store.recordDirectFailure(claim.attempt))
            store.updateMessageStatusUnlessDelivered(entity(id = claim.row.id), MessageStatus.FAILED)
            val independent = assertNotNull(store.fetchMessage(entity(id = claim.row.id)))
            if (before) rejected(store.recordDirectFailure(claim.attempt))
            else {
                val historical = assertIs<DirectAckMutationResult.AlreadyApplied>(store.recordDirectFailure(claim.attempt))
                assertEquals(DirectAckPermission.INVALIDATED, historical.receipt.permission)
            }
            rejected(store.settleDirectAck(claim.attempt, 123u, 99u))
            assertEquals(independent, store.fetchMessage(entity(id = claim.row.id)))
            assertNull(independent.roundTripTime)
        }
    }

    @Test fun callerCancellationAfterFailureCommitDoesNotCreateOrLoseAuthority() = runTest {
        val claim = begin()
        val caller = Job()
        assertFailsWith<CancellationException> {
            withContext(caller) {
                store.recordDirectFailure(claim.attempt)
                caller.cancel()
                ensureActive()
            }
        }
        assertEquals(DirectAckPhase.FAILED, observed(claim).phase)
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(claim.attempt, 123u, 123u))
        assertEquals(MessageStatus.DELIVERED, assertNotNull(store.fetchMessage(entity(id = claim.row.id))).status)
        val other = begin(lease = claim.lease)
        val cancelled = Job()
        assertFailsWith<CancellationException> {
            withContext(cancelled) {
                store.recordDirectFailure(other.attempt)
                cancelled.cancel()
                ensureActive()
            }
        }
        db.messages().setStatus(RADIO_A.value, other.row.id, 4)
        rejected(store.settleDirectAck(other.attempt, 123u, 123u))
        val independent = assertNotNull(store.fetchMessage(entity(id = other.row.id)))
        assertEquals(MessageStatus.FAILED, independent.status)
        assertNull(independent.roundTripTime)
    }

    @Test fun failureRollbackIsAnExplicitFailureAndDoesNotInventACommittedReceipt() = runTest {
        val claim = begin()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_direct_failure BEFORE UPDATE ON messages WHEN NEW.statusRawValue = 4 " +
                "BEGIN SELECT RAISE(ABORT,'independent failure control'); END",
        )
        assertFailsWith<PersistenceStoreException> { store.recordDirectFailure(claim.attempt) }
        assertEquals(1, reported.size)
        assertEquals(DirectAckPhase.REGISTERED, observed(claim).phase)
        assertEquals(claim.row, store.fetchMessage(entity(id = claim.row.id)))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_direct_failure")
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectFailure(claim.attempt))
    }

    @Test fun cancellationDuringActualRoomFailureWriteRollsBackInsteadOfGrantingFailureAuthority() = runTest {
        var cancelOnWrite: Job? = null
        val local = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .addCallback(DirectAckStorage.callback).allowMainThreadQueries()
            .setQueryExecutor(immediate).setTransactionExecutor(immediate)
            .setQueryCallback({ sql, _ ->
                if (sql.startsWith("UPDATE messages SET failureSeen")) cancelOnWrite?.cancel()
            }, immediate).build()
        val target = newStore(local)
        try {
            val claim = begin(lease = activate(target), target = target)
            val caller = Job()
            cancelOnWrite = caller
            assertFailsWith<CancellationException> { withContext(caller) { target.recordDirectFailure(claim.attempt) } }
            cancelOnWrite = null
            assertEquals(claim.row, target.fetchMessage(entity(id = claim.row.id)))
            assertEquals(DirectAckPhase.REGISTERED, observed(claim, target).phase)
            assertEquals(DirectAckPermission.CURRENT, observed(claim, target).permission)
        } finally { cancelOnWrite = null; target.close(); local.close() }
    }

    @Test fun additionalSameValueMutationInsideTheFailureStatementCannotBeRegrantedAsOwnWrite() = runTest {
        val claim = begin()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER independently_repeat_failure AFTER UPDATE ON messages " +
                "WHEN OLD.statusRawValue != 4 AND NEW.statusRawValue = 4 BEGIN " +
                "UPDATE messages SET statusRawValue = 4 WHERE radioId = NEW.radioId AND id = NEW.id; END",
        )
        val failure = assertFailsWith<PersistenceStoreException> { store.recordDirectFailure(claim.attempt) }
        assertEquals(PersistenceStoreError.InvalidData, failure.error)
        assertEquals(claim.row, store.fetchMessage(entity(id = claim.row.id)))
        assertEquals(DirectAckPhase.REGISTERED, observed(claim).phase)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER independently_repeat_failure")
    }

    @Test fun retryNewFailureAndOldAckCannotPromoteTheNewFailure() = runTest {
        val claim = begin()
        store.recordDirectFailure(claim.attempt)
        store.replacePendingSendForRetry(claim.row.id, pending(messageID = claim.row.id))
        store.updateMessageStatusUnlessDelivered(entity(id = claim.row.id), MessageStatus.FAILED)
        val independent = assertNotNull(store.fetchMessage(entity(id = claim.row.id)))
        rejected(store.settleDirectAck(claim.attempt, 123u, 123u))
        assertEquals(independent, store.fetchMessage(entity(id = claim.row.id)))
        assertEquals(MessageStatus.FAILED, independent.status)
        assertNull(independent.roundTripTime)
    }

    @Test fun everyGenericDaoAndRawMessageMutationFencesOldAuthority() = runTest {
        val lease = activate()
        for (mutation in listOf<suspend (MessageDTO) -> Unit>(
            { db.messages().setStatus(RADIO_A.value, it.id, 4) },
            { db.messages().upsert(it.toEntity()) },
            { store.saveMessage(it) },
            { db.messages().setAck(RADIO_A.value, it.id, 123, 2, null) },
            { db.messages().setAck(RADIO_A.value, it.id, 456, 3, 7) },
            { db.messages().setRetryStatus(RADIO_A.value, it.id, 5, 1, 4) },
            { db.openHelper.writableDatabase.execSQL("UPDATE messages SET statusRawValue = statusRawValue") },
            { db.messages().delete(RADIO_A.value, it.id); db.messages().insert(it.toEntity()) },
            { db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO messages SELECT * FROM messages") },
        )) {
            val claim = begin(lease = lease)
            mutation(claim.row)
            val independent = store.fetchMessage(entity(id = claim.row.id))
            rejected(store.settleDirectAck(claim.attempt, 123u, 123u))
            assertEquals(independent, store.fetchMessage(entity(id = claim.row.id)))
        }
    }

    @Test fun additivePredictedAndReturnedCodesSurviveOwnRetryButNotGenericWrites() = runTest {
        val claim = begin()
        store.recordDirectSendAccepted(claim.attempt, claim.wire, 456u)
        val retry = UUID.randomUUID()
        store.applyDirectAckBookkeeping(claim.attempt, retry, DirectAckBookkeeping.RetryStarted(1, 4))
        val wire2 = UUID.randomUUID()
        store.registerDirectAckDispatch(claim.attempt, wire2, 789u)
        store.recordDirectSendAccepted(claim.attempt, wire2, 987u)
        val codes = observed(claim).wires.flatMap { listOfNotNull(it.predictedAckCode, it.acceptedAckCode) }.toSet()
        assertEquals(setOf(123u, 456u, 789u, 987u), codes)
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(claim.attempt, 456u, 88u))
        assertIs<DirectAckMutationResult.AlreadyDelivered>(
            store.applyDirectAckBookkeeping(claim.attempt, UUID.randomUUID(), DirectAckBookkeeping.RetryExhausted),
        )
    }

    @Test fun earlyPredictedAckAndExplicitDeliveredOrFailedResendsPreserveTerminalBookkeeping() = runTest {
        val lease = activate()
        for (initial in listOf(MessageStatus.DELIVERED, MessageStatus.FAILED)) {
            val claim = begin(row = message(status = initial), mode = DirectAckMode.RESEND, lease = lease)
            store.recordDirectSendAccepted(claim.attempt, claim.wire, 456u)
            assertEquals(initial, assertNotNull(store.fetchMessage(entity(id = claim.row.id))).status)
            assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(claim.attempt, 123u, 123u))
            val count = UUID.randomUUID()
            store.applyDirectAckBookkeeping(claim.attempt, count, DirectAckBookkeeping.ResendCountIncrement)
            assertIs<DirectAckMutationResult.AlreadyApplied>(
                store.applyDirectAckBookkeeping(claim.attempt, count, DirectAckBookkeeping.ResendCountIncrement),
            )
            val row = assertNotNull(store.fetchMessage(entity(id = claim.row.id)))
            assertEquals(MessageStatus.DELIVERED, row.status)
            assertEquals(claim.row.sendCount + 1, row.sendCount)
            assertEquals(123u, row.roundTripTime)
            db.messages().setStatus(RADIO_A.value, row.id, 4)
            rejected(store.applyDirectAckBookkeeping(claim.attempt, UUID.randomUUID(), DirectAckBookkeeping.ResendCountIncrement))
        }
        val early = begin(lease = lease)
        store.settleDirectAck(early.attempt, 123u, 7u)
        store.recordDirectSendAccepted(early.attempt, early.wire, 456u)
        assertEquals(7u, assertNotNull(store.fetchMessage(entity(id = early.row.id))).roundTripTime)
    }

    @Test fun pendingCompletionIsExactOncePreservesLateAckAndCannotDeleteAReplacement() = runTest {
        val row = message()
        val queued = pending(messageID = row.id)
        store.upsertPendingSend(queued)
        val claim = begin(row, pendingId = queued.id)
        val completion = UUID.randomUUID()
        assertIs<DirectAckMutationResult.Applied>(store.completeDirectPending(claim.attempt, queued.id, completion))
        assertTrue(store.fetchPendingSendsForMessage(entity(id = row.id)).isEmpty())
        store.upsertPendingSend(queued)
        assertIs<DirectAckMutationResult.AlreadyApplied>(store.completeDirectPending(claim.attempt, queued.id, completion))
        assertEquals(listOf(queued), store.fetchPendingSendsForMessage(entity(id = row.id)))
        rejected(store.settleDirectAck(claim.attempt, 123u, 4u))
        val row2 = message()
        val queued2 = pending(messageID = row2.id)
        store.upsertPendingSend(queued2)
        val live = begin(row2, pendingId = queued2.id, lease = claim.lease)
        store.completeDirectPending(live.attempt, queued2.id, UUID.randomUUID())
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(live.attempt, 123u, 4u))
    }

    @Test fun genericPendingDaoBumpAndRawSameValueOrReplacementInvalidateCapturedCompletion() = runTest {
        val lease = activate()
        for (mutation in listOf<suspend (PendingSendDTO) -> Unit>(
            { store.upsertPendingSend(it) },
            { db.openHelper.writableDatabase.execSQL("UPDATE pending_sends SET attemptCount = COALESCE(attemptCount,0) + 1") },
            { db.openHelper.writableDatabase.execSQL("UPDATE pending_sends SET messageText = messageText") },
            { db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO pending_sends SELECT * FROM pending_sends") },
        )) {
            val row = message()
            val queued = pending(messageID = row.id)
            store.upsertPendingSend(queued)
            val claim = begin(row, pendingId = queued.id, lease = lease)
            mutation(queued)
            val independent = store.fetchPendingSendsForMessage(entity(id = row.id))
            rejected(store.completeDirectPending(claim.attempt, queued.id, UUID.randomUUID()))
            rejected(store.settleDirectAck(claim.attempt, 123u, 4u))
            assertEquals(independent, store.fetchPendingSendsForMessage(entity(id = row.id)))
        }
    }

    @Test fun rawPendingReplacementRemappingMessageInvalidatesBothIncludingAnInlineClaim() = runTest {
        val lease = activate()
        val original = message()
        val replacement = message()
        val queued = pending(messageID = original.id)
        store.upsertPendingSend(queued)
        val inline = begin(original, lease = lease)
        val other = begin(replacement, lease = lease)
        val columns = db.openHelper.readableDatabase.query("PRAGMA table_info(pending_sends)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        val projection = columns.joinToString(",") { if (it == "messageID") "?" else "`$it`" }
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO pending_sends SELECT $projection FROM pending_sends WHERE id = ?",
            arrayOf(replacement.id.toString().uppercase(), queued.id.toString().uppercase()),
        )
        rejected(store.settleDirectAck(inline.attempt, 123u, 1u))
        rejected(store.settleDirectAck(other.attempt, 123u, 1u))
        assertEquals(original, store.fetchMessage(entity(id = original.id)))
        assertEquals(replacement, store.fetchMessage(entity(id = replacement.id)))
    }

    @Test fun nestedPendingMutationCannotBeMistakenForOwnFailureOrOwnPendingCompletion() = runTest {
        val lease = activate()
        for (completion in listOf(false, true)) {
            val row = message()
            val queued = pending(messageID = row.id)
            store.upsertPendingSend(queued)
            if (completion) store.upsertPendingSend(pending(messageID = row.id, sequence = 1))
            val before = store.fetchPendingSendsForMessage(entity(id = row.id))
            val claim = begin(row, pendingId = queued.id, lease = lease)
            val trigger = if (completion) {
                "AFTER DELETE ON pending_sends BEGIN UPDATE pending_sends SET messageText = messageText " +
                    "WHERE radioId = OLD.radioId AND messageID = OLD.messageID; END"
            } else {
                "AFTER UPDATE ON messages WHEN NEW.statusRawValue = 4 BEGIN " +
                    "UPDATE pending_sends SET messageText = messageText WHERE radioId = NEW.radioId AND messageID = NEW.id; END"
            }
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER independently_mutate_pending $trigger")
            val failure = assertFailsWith<PersistenceStoreException> {
                if (completion) store.completeDirectPending(claim.attempt, queued.id, UUID.randomUUID())
                else store.recordDirectFailure(claim.attempt)
            }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
            assertEquals(claim.row, store.fetchMessage(entity(id = row.id)))
            assertEquals(before, store.fetchPendingSendsForMessage(entity(id = row.id)))
            assertEquals(DirectAckPermission.CURRENT, observed(claim).permission)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER independently_mutate_pending")
        }
    }

    @Test fun separateContextsGenerationEpochRevokeAndRestoreFenceOldAuthority() = runTest {
        val claim = begin()
        val other = newStore(db)
        rejected(other.recordDirectFailure(claim.attempt), DirectAckRejection.INVALID_HANDLE)
        val successor = token.copy(generation = Generation(2))
        activate(other, successor)
        rejected(store.settleDirectAck(claim.attempt, 123u, 1u), DirectAckRejection.STALE_LEASE)
        assertIs<DirectAckActivationResult.Rejected>(store.activateDirectAckSession(token, UUID.randomUUID()))
        assertIs<DirectAckActivationResult.Rejected>(
            store.activateDirectAckSession(token.copy(generation = Generation(3)), UUID.randomUUID()),
        )
        val newProcess = newStore(db)
        val newToken = token.copy(epoch = ProcessEpoch(UUID.randomUUID()), generation = Generation(0))
        val newLease = activate(newProcess, newToken)
        assertIs<DirectAckActivationResult.Rejected>(other.activateDirectAckSession(successor.copy(generation = Generation(3)), UUID.randomUUID()))
        val live = begin(lease = newLease, target = newProcess)
        newProcess.withBackupRestore { _, _ -> Unit }
        rejected(newProcess.settleDirectAck(live.attempt, 123u, 1u))
        newProcess.revokeDirectAckSession(newLease)
        rejected(newProcess.settleDirectAck(live.attempt, 123u, 1u), DirectAckRejection.STALE_LEASE)
        assertIs<DirectAckActivationResult.Rejected>(newProcess.activateDirectAckSession(newToken, UUID.randomUUID()))
        other.close()
        newProcess.close()
    }

    @Test fun noDispatchCaptureValidatesReasonAndCompletionReplayCannotDeleteReplacementOrGrantAck() = runTest {
        val lease = activate()
        for (zero in listOf(false, true)) {
            val row = message(status = if (zero) MessageStatus.PENDING else MessageStatus.DELIVERED)
            val queued = pending(messageID = row.id)
            store.saveMessage(row)
            store.upsertPendingSend(queued)
            val request = UUID.randomUUID()
            val reason = if (zero) DirectPendingCompletionReason.ZeroAttempts(0)
                else DirectPendingCompletionReason.AlreadyDeliveredSend
            val captured = assertIs<DirectPendingCaptureResult.Captured>(
                store.captureDirectPendingCompletion(lease, entity(id = row.id), queued.id, request, reason),
            )
            assertEquals(row, captured.receipt.message)
            assertEquals(queued, captured.receipt.pending)
            assertIs<DirectPendingCaptureResult.AlreadyCaptured>(
                store.captureDirectPendingCompletion(lease, entity(id = row.id), queued.id, request, reason),
            )
            val completion = UUID.randomUUID()
            val caller = Job()
            assertFailsWith<CancellationException> {
                withContext(caller) {
                    assertIs<DirectPendingCompletionResult.Applied>(
                        store.completeDirectPendingWithoutDispatch(captured.completion, completion),
                    )
                    caller.cancel()
                    ensureActive()
                }
            }
            assertEquals(row, store.fetchMessage(entity(id = row.id)))
            assertTrue(store.fetchPendingSendsForMessage(entity(id = row.id)).isEmpty())
            val receipt = assertIs<DirectPendingCompletionObservation.Found>(
                store.inspectDirectPendingCompletion(lease, request),
            ).receipt
            assertEquals(completion, receipt.completedRequestId)
            assertIs<DirectAckObservation.Rejected>(store.inspectDirectAckAttempt(lease, request))
            store.upsertPendingSend(queued)
            assertIs<DirectPendingCompletionResult.AlreadyApplied>(
                store.completeDirectPendingWithoutDispatch(captured.completion, completion),
            )
            assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectPendingCompletionResult.Rejected>(
                store.completeDirectPendingWithoutDispatch(captured.completion, UUID.randomUUID()),
            ).reason)
            assertEquals(listOf(queued), store.fetchPendingSendsForMessage(entity(id = row.id)))
        }
        val row = message()
        val queued = pending(messageID = row.id)
        store.saveMessage(row)
        store.upsertPendingSend(queued)
        for (reason in listOf(
            DirectPendingCompletionReason.AlreadyDeliveredSend,
            DirectPendingCompletionReason.ZeroAttempts(1),
            DirectPendingCompletionReason.ZeroAttempts(-1),
        )) assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<DirectPendingCaptureResult.Rejected>(
            store.captureDirectPendingCompletion(lease, entity(id = row.id), queued.id, UUID.randomUUID(), reason),
        ).reason)
        val delivered = row.copy(status = MessageStatus.DELIVERED)
        store.saveMessage(delivered)
        store.upsertPendingSend(queued.copy(isResend = true))
        assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<DirectPendingCaptureResult.Rejected>(
            store.captureDirectPendingCompletion(lease, entity(id = row.id), queued.id, UUID.randomUUID(),
                DirectPendingCompletionReason.AlreadyDeliveredSend),
        ).reason)
    }

    @Test fun noDispatchCaptureCannotDeleteAfterRawDaoMessagePendingRestoreOrGenerationMutation() = runTest {
        var lease = activate()
        for (mutation in listOf<suspend (MessageDTO, PendingSendDTO) -> Unit>(
            { row, _ -> db.messages().setStatus(RADIO_A.value, row.id, row.status.rawValue) },
            { _, queued -> store.upsertPendingSend(queued) },
            { _, _ -> db.openHelper.writableDatabase.execSQL("UPDATE pending_sends SET messageText = messageText") },
            { _, _ -> db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO pending_sends SELECT * FROM pending_sends") },
            { _, _ -> store.withBackupRestore { _, _ -> Unit } },
            { _, _ -> lease = activate(session = token.copy(generation = Generation(2))) },
        )) {
            val row = message()
            val queued = pending(messageID = row.id)
            store.saveMessage(row)
            store.upsertPendingSend(queued)
            val claim = assertIs<DirectPendingCaptureResult.Captured>(
                store.captureDirectPendingCompletion(lease, entity(id = row.id), queued.id, UUID.randomUUID(),
                    DirectPendingCompletionReason.ZeroAttempts(0)),
            )
            mutation(row, queued)
            assertIs<DirectPendingCompletionResult.Rejected>(
                store.completeDirectPendingWithoutDispatch(claim.completion, UUID.randomUUID()),
            )
            assertEquals(listOf(queued), store.fetchPendingSendsForMessage(entity(id = row.id)))
        }
    }

    @Test fun noDispatchCompletionInvalidatesExistingAckAndCannotHideNestedPendingMutation() = runTest {
        val row = message()
        val queued = pending(messageID = row.id)
        store.upsertPendingSend(queued)
        val claim = begin(row, pendingId = queued.id)
        val capture = assertIs<DirectPendingCaptureResult.Captured>(
            store.captureDirectPendingCompletion(claim.lease, entity(id = row.id), queued.id, UUID.randomUUID(),
                DirectPendingCompletionReason.ZeroAttempts(0)),
        )
        store.completeDirectPendingWithoutDispatch(capture.completion, UUID.randomUUID())
        rejected(store.settleDirectAck(claim.attempt, 123u, 1u))
        store.upsertPendingSend(queued)
        store.upsertPendingSend(pending(messageID = row.id, sequence = 1))
        val before = store.fetchPendingSendsForMessage(entity(id = row.id))
        val second = assertIs<DirectPendingCaptureResult.Captured>(
            store.captureDirectPendingCompletion(claim.lease, entity(id = row.id), queued.id, UUID.randomUUID(),
                DirectPendingCompletionReason.ZeroAttempts(0)),
        )
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER independently_repeat_pending AFTER DELETE ON pending_sends BEGIN " +
                "UPDATE pending_sends SET messageText = messageText WHERE messageID = OLD.messageID; END",
        )
        assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException> {
            store.completeDirectPendingWithoutDispatch(second.completion, UUID.randomUUID())
        }.error)
        assertEquals(before, store.fetchPendingSendsForMessage(entity(id = row.id)))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER independently_repeat_pending")
    }

    @Test fun fileReopenOtherHandleAndProcessStoreCloseCannotAdoptOldHandles() = runTest {
        val name = "direct-ack-handles-${UUID.randomUUID()}.db"
        fun file(): MeshCoreDatabase = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).addCallback(DirectAckStorage.callback).build()
        try {
            val first = file()
            val original = newStore(first)
            val lease = activate(original)
            val claim = begin(lease = lease, target = original)
            val second = file()
            try {
                second.messages().setStatus(RADIO_A.value, claim.row.id, 4)
                rejected(original.settleDirectAck(claim.attempt, 123u, 123u))
                val replacement = newStore(second)
                rejected(replacement.recordDirectFailure(claim.attempt), DirectAckRejection.INVALID_HANDLE)
                replacement.close()
            } finally { second.close() }
            original.close()
            first.close()
            val reopened = file()
            try {
                val replacement = newStore(reopened)
                rejected(replacement.settleDirectAck(claim.attempt, 123u, 1u), DirectAckRejection.INVALID_HANDLE)
                val row = assertNotNull(replacement.fetchMessage(entity(id = claim.row.id)))
                assertEquals(MessageStatus.FAILED, row.status)
                assertNull(row.roundTripTime)
                replacement.close()
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
