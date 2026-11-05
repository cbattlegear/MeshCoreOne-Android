// AndroidOnly: WP-202 Real Room receipts and replay must not materialize internal resolution history.
package com.meshcoreone.android.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DirectAckReceiptJournalQueryTest {
    @Test fun sameClaimInternalHistoryIsExcludedInSqlWhileReceiptReplayAndPendingSemanticsRemainExact() =
        runTest(timeout = 180.seconds) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val queries = Collections.synchronizedList(mutableListOf<String>())
            val immediate = Executor { it.run() }
            val db = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
                .addCallback(DirectAckStorage.callback).allowMainThreadQueries()
                .setQueryExecutor(immediate).setTransactionExecutor(immediate)
                .setQueryCallback({ sql, _ ->
                    val normalized = sql.replace(Regex("\\s+"), " ").trim()
                    if (normalized.startsWith("SELECT", ignoreCase = true) &&
                        normalized.contains("FROM direct_ack_operations")
                    ) queries += normalized
                }, immediate).build()
            val store = RoomPersistenceStore(db, scope, RepositoryClock())
            try {
                val token = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), RADIO_A)
                val lease = assertIs<DirectAckActivationResult.Activated>(
                    store.activateDirectAckSession(token, UUID.randomUUID()),
                ).lease
                val row = message()
                val queued = pending(messageID = row.id)
                store.saveMessage(row)
                store.upsertPendingSend(queued)
                val claim = UUID.randomUUID()
                val intent = assertIs<DirectAckIntentResult.Captured>(store.captureDirectAckIntent(
                    lease, EntityKey(RADIO_A, row.id), claim, DirectAckMode.SEND, queued.id,
                )).intent
                val wire = UUID.randomUUID()
                val attempt = assertIs<DirectAckBeginResult.Begun>(
                    store.beginDirectAckAttemptRequiringEmissionTracking(intent, wire, 123u),
                ).attempt
                val marked = assertIs<DirectAckEmissionMarkResult.Marked>(
                    store.markDirectAckWireMayHaveWritten(attempt, wire, UUID.randomUUID()),
                )
                val resolution = UUID.randomUUID()
                assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
                    marked.handle, resolution, DirectAckEmissionResolution.Accepted(123u),
                ))
                val firstResolution = assertNotNull(db.directAcks().operation(RADIO_A.value, claim, resolution))
                db.withTransaction {
                    repeat(4_100) {
                        db.directAcks().operation(firstResolution.copy(requestId = UUID.randomUUID()))
                    }
                }
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM direct_ack_operations").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(4_103, it.getInt(0))
                }
                withUnchangedDirectAckState(db, EntityKey(RADIO_A, row.id), claim) {
                    val refused = assertIs<DirectAckRadioSettlementResult.Nonsettling>(
                        store.settleDirectAckExcludingRadioHistory(attempt, 123u, 42u))
                    assertEquals(setOf(DirectAckRadioNonsettlingReason.PREAUTHORITY_HISTORY_UNKNOWN),
                        refused.reasons.toSet())
                }
                queries.clear()
                val change = DirectAckBookkeeping.RetryStarted(1, 4)
                val bookkeeping = UUID.randomUUID()
                assertIs<DirectAckMutationResult.Applied>(store.applyDirectAckBookkeeping(attempt, bookkeeping, change))
                val completion = UUID.randomUUID()
                assertIs<DirectAckMutationResult.Applied>(store.completeDirectPending(attempt, queued.id, completion))
                val receipt = assertIs<DirectAckObservation.Found>(store.inspectDirectAckAttempt(lease, claim)).receipt
                assertEquals(listOf(DirectAckBookkeepingReceipt(bookkeeping, change)), receipt.bookkeeping.toList())
                assertEquals(listOf(DirectAckPendingReceipt(completion, queued.id)), receipt.pendingCompletions.toList())
                assertEquals(listOf(DirectAckWireReceipt(wire, 123u, 123u)), receipt.wires.toList())
                assertEquals(2, db.directAcks().receiptOperations(RADIO_A.value, claim).size)
                assertTrue(db.directAcks().hasPendingCompletion(RADIO_A.value, claim))
                val unchangedRow = store.fetchMessage(EntityKey(RADIO_A, row.id))
                assertEquals(receipt, assertIs<DirectAckMutationResult.AlreadyApplied>(
                    store.applyDirectAckBookkeeping(attempt, bookkeeping, change),
                ).receipt)
                assertEquals(receipt, assertIs<DirectAckMutationResult.AlreadyApplied>(
                    store.completeDirectPending(attempt, queued.id, completion),
                ).receipt)
                assertIs<DirectAckEmissionMutationResult.AlreadyApplied>(store.resolveDirectAckWireEmission(
                    marked.handle, resolution, DirectAckEmissionResolution.Accepted(123u),
                ))
                assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckMutationResult.Rejected>(
                    store.applyDirectAckBookkeeping(attempt, resolution, change),
                ).reason)
                assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckMutationResult.Rejected>(
                    store.completeDirectPending(attempt, queued.id, UUID.randomUUID()),
                ).reason)
                assertEquals(unchangedRow, store.fetchMessage(EntityKey(RADIO_A, row.id)))
                assertTrue(store.fetchPendingSendsForMessage(EntityKey(RADIO_A, row.id)).isEmpty())
                assertEquals(firstResolution, db.directAcks().operation(RADIO_A.value, claim, resolution))
                val delivery = assertIs<DirectAckMutationResult.Applied>(
                    store.settleDirectAck(attempt, 123u, 42u)).receipt
                assertEquals(receipt.bookkeeping, delivery.bookkeeping)
                assertEquals(receipt.pendingCompletions, delivery.pendingCompletions)
                assertEquals(MessageStatus.DELIVERED, delivery.delivery?.message?.status)

                val captured = synchronized(queries) { queries.toList() }
                assertTrue(captured.any { it.contains("substr(kind,1,20)") })
                assertTrue(captured.any { it.contains("AND requestId = ?") })
                assertTrue(captured.any { it.startsWith("SELECT EXISTS(") && it.contains("kind = 'PENDING'") })
                assertTrue(captured.isNotEmpty())
                for (sql in captured) {
                    assertTrue(
                        sql.contains("substr(kind,1,20)") ||
                            sql.contains("AND requestId = ?") ||
                            sql.startsWith("SELECT EXISTS(") ||
                            sql.contains("kind = 'RADIO_DELIVERY' LIMIT 2"),
                        "Unexpected unfiltered operation-journal query: $sql",
                    )
                }
            } finally { store.close(); scope.cancel(); db.close() }
        }
}
