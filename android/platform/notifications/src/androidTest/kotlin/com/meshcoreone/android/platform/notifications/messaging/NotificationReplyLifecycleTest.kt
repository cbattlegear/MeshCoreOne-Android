// AndroidOnly: WP-401 Real SQLite custody fencing, migration, crash-shaped loss and ambiguous outcomes.
package com.meshcoreone.android.platform.notifications.messaging

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.model.RadioId
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class NotificationReplyLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private val file by lazy { File(temporary.root, "reply-custody-${UUID.randomUUID()}.db") }
    private val limits = NotificationReplyLimits(20, 20_000, 100_000)
    private val store get() = NotificationReplyStagingStore(file, limits)
    private val post = NotificationReplyPost(1, UUID.randomUUID(), "REPLY")
    private val payload = NotificationPayload.DirectMessage(
        EntityKey(RadioId(UUID(0, 1)), UUID(0, 2)), UUID(0, 3),
    )
    private val text = " \t\u0000raw\uD800\n "
    private lateinit var identity: NotificationReplyStagingStore.RecordIdentity
    private val claimId = UUID.randomUUID()
    private val ownerId = UUID.randomUUID()

    @Before
    fun initialize() = runTest {
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeNew())
        identity = (store.stage(post, payload, text) as NotificationReplyStagingStore.StageResult.Staged).identity
    }

    @After
    fun cleanup() {
        listOf(file, File("${file.path}-journal"), File("${file.path}-wal"), File("${file.path}-shm"))
            .filter(File::exists).forEach { check(it.delete()) }
    }

    @Test
    fun v1RequiresExplicitAtomicMigrationAndPreservesOriginalBytesAndConflicts() = runTest {
        val conflict = store.stage(post, payload, "conflict") as
            NotificationReplyStagingStore.StageResult.ConflictRetained
        assertEquals(
            NotificationReplyStagingStore.CustodyResult.Failed(
                NotificationReplyStagingStore.Failure.LIFECYCLE_NOT_INITIALIZED,
            ),
            store.claim(identity, claimId, ownerId),
        )
        val before = primary()
        assertEquals(null, before.custody)
        enable()
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeLifecycle(100_000))
        val after = primary()
        assertEquals(before.identity, after.identity)
        assertEquals(before.outgoingMessageId, after.outgoingMessageId)
        assertEquals(text.toCharArray().toList(), after.exactText.toCharArray().toList())
        assertEquals(payload, after.payload)
        assertEquals(NotificationReplyStagingStore.Custody.UNCLAIMED, after.custody?.state)
        val unbound = inventory().last() as NotificationReplyStagingStore.RecoveryEntry.UnboundConflict
        assertEquals(conflict.identity, unbound.identity)
        assertEquals("conflict", unbound.exactText)
        assertEquals(NotificationReplyStagingStore.Custody.UNBOUND, unbound.custody?.state)
        assertTrue(store.claim(conflict.identity, UUID.randomUUID(), ownerId) is
            NotificationReplyStagingStore.CustodyResult.Fenced)
    }

    @Test
    fun independentConcurrentOwnersHaveExactlyOneFreshClaimAndNeverTakeOver() = runTest {
        enable()
        val start = CountDownLatch(1)
        val work = (0 until 8).map {
            async(Dispatchers.Default) {
                start.await()
                store.claim(identity, UUID.randomUUID(), UUID.randomUUID())
            }
        }
        start.countDown()
        val results = work.awaitAll()
        assertEquals(1, results.count { it is NotificationReplyStagingStore.CustodyResult.NewlyClaimed })
        assertEquals(7, results.count { it is NotificationReplyStagingStore.CustodyResult.Fenced })
        val receipt = requireNotNull(primary().custody)
        assertEquals(1L, receipt.revision)
        assertEquals(NotificationReplyStagingStore.Custody.CLAIMED_POSSIBLE_SEND, receipt.state)
        assertEquals(NotificationReplyStagingStore.TargetAuthority.UNPROVED, primary().targetAuthority)
    }

    @Test
    fun exactConcurrentRedeliveryOnlyOneNewClaimOthersObserveNoDispatchPermission() = runTest {
        enable()
        val start = CountDownLatch(1)
        val work = (0 until 8).map {
            async(Dispatchers.Default) {
                start.await()
                store.claim(identity, claimId, ownerId)
            }
        }
        start.countDown()
        val results = work.awaitAll()
        assertEquals(1, results.count { it is NotificationReplyStagingStore.CustodyResult.NewlyClaimed })
        assertEquals(7, results.count { it is NotificationReplyStagingStore.CustodyResult.Observed })
        assertEquals(NotificationReplyStagingStore.CustodyResult.Observed(primary().custody!!),
            store.claim(identity, claimId, ownerId))
    }

    @Test
    fun lostOwnerAndReopenRemainPossibleSendUntilConservativeSettlementNeverResend() = runTest {
        enable()
        val claimed = claim()
        assertEquals(claimed, primary().custody)
        assertEquals(NotificationReplyStagingStore.CustodyResult.Observed(claimed),
            store.claim(identity, claimId, ownerId))
        val settled = store.settleIndeterminate(claimed, NotificationReplyStagingStore.Uncertainty.RECOVERY)
            as NotificationReplyStagingStore.CustodyResult.SettledIndeterminate
        assertEquals(2L, settled.receipt.revision)
        assertEquals(NotificationReplyStagingStore.Custody.INDETERMINATE, settled.receipt.state)
        assertEquals(settled.receipt, primary().custody)
        assertEquals(NotificationReplyStagingStore.CustodyResult.Observed(settled.receipt),
            store.settleIndeterminate(claimed, NotificationReplyStagingStore.Uncertainty.RECOVERY))
        assertEquals(NotificationReplyStagingStore.CustodyResult.Observed(settled.receipt),
            store.claim(identity, claimId, ownerId))
        assertTrue(store.claim(identity, UUID.randomUUID(), UUID.randomUUID()) is
            NotificationReplyStagingStore.CustodyResult.Fenced)
        val replay = store.stage(post, payload, text) as NotificationReplyStagingStore.StageResult.AlreadyStaged
        assertEquals(identity, replay.identity)
        assertEquals(text, primary().exactText)
        assertEquals(settled.receipt, primary().custody)
    }

    @Test
    fun wrongStoreSequenceClaimOwnerAndRevisionCannotSettleOrClaimAnotherRecord() = runTest {
        enable()
        assertEquals(
            NotificationReplyStagingStore.CustodyResult.Failed(NotificationReplyStagingStore.Failure.IDENTITY_MISMATCH),
            store.claim(identity.copy(storeId = UUID.randomUUID()), claimId, ownerId),
        )
        assertEquals(
            NotificationReplyStagingStore.CustodyResult.Failed(NotificationReplyStagingStore.Failure.IDENTITY_MISMATCH),
            store.claim(identity.copy(sequence = identity.sequence + 1), claimId, ownerId),
        )
        val receipt = claim()
        listOf(receipt.copy(revision = 0), receipt.copy(revision = Long.MAX_VALUE),
            receipt.copy(claimId = UUID.randomUUID()), receipt.copy(ownerId = UUID.randomUUID())).forEach {
            assertEquals(
                NotificationReplyStagingStore.CustodyResult.Fenced(receipt),
                store.settleIndeterminate(it, NotificationReplyStagingStore.Uncertainty.OUTCOME_UNKNOWN),
            )
        }
        assertEquals(receipt, primary().custody)
    }

    @Test
    fun competingUncertaintySettlementsAreExactRevisionBoundAndCannotOverwriteFirstReason() = runTest {
        enable()
        val claimed = claim()
        val start = CountDownLatch(1)
        val work = NotificationReplyStagingStore.Uncertainty.entries.map { reason ->
            async(Dispatchers.Default) {
                start.await()
                store.settleIndeterminate(claimed, reason)
            }
        }
        start.countDown()
        val results = work.awaitAll()
        assertEquals(1, results.count { it is NotificationReplyStagingStore.CustodyResult.SettledIndeterminate })
        assertEquals(3, results.count { it is NotificationReplyStagingStore.CustodyResult.Fenced })
        assertEquals(2L, primary().custody?.revision)
    }

    @Test
    fun reusedClaimIdentifierCannotBecomeAnotherLogicalAction() = runTest {
        enable()
        claim()
        val second = store.stage(post.copy(postId = UUID.randomUUID()), payload, text) as
            NotificationReplyStagingStore.StageResult.Staged
        assertEquals(
            NotificationReplyStagingStore.CustodyResult.Failed(NotificationReplyStagingStore.Failure.IDENTITY_MISMATCH),
            store.claim(second.identity, claimId, ownerId),
        )
        assertEquals(NotificationReplyStagingStore.Custody.UNCLAIMED,
            (inventory().last() as NotificationReplyStagingStore.RecoveryEntry.StagedReply).custody?.state)
    }

    @Test
    fun abortedAndIgnoredClaimUpdatesNeverReturnFreshClaimAndRollbackExactly() = runTest {
        enable()
        val original = primary().custody
        listOf("ABORT, 'injected'" to NotificationReplyStagingStore.Failure.STORAGE_UNAVAILABLE,
            "IGNORE" to NotificationReplyStagingStore.Failure.CORRUPT_STORAGE).forEach { (raise, reason) ->
            exec("CREATE TRIGGER reject_claim BEFORE UPDATE ON reply_custody BEGIN SELECT RAISE($raise); END")
            assertEquals(NotificationReplyStagingStore.CustodyResult.Failed(reason),
                store.claim(identity, claimId, ownerId))
            assertEquals(original, primary().custody)
            exec("DROP TRIGGER reject_claim")
        }
        assertEquals(NotificationReplyStagingStore.Custody.CLAIMED_POSSIBLE_SEND, claim().state)
    }

    @Test
    fun failedIndeterminateSettlementRetainsClaimAndIgnoredWriteDoesNotSucceed() = runTest {
        enable()
        val claimed = claim()
        listOf("ABORT, 'injected'" to NotificationReplyStagingStore.Failure.STORAGE_UNAVAILABLE,
            "IGNORE" to NotificationReplyStagingStore.Failure.CORRUPT_STORAGE).forEach { (raise, reason) ->
            exec("CREATE TRIGGER reject_settle BEFORE UPDATE ON reply_custody BEGIN SELECT RAISE($raise); END")
            assertEquals(NotificationReplyStagingStore.CustodyResult.Failed(reason),
                store.settleIndeterminate(claimed, NotificationReplyStagingStore.Uncertainty.OWNER_REPORTED))
            assertEquals(claimed, primary().custody)
            exec("DROP TRIGGER reject_settle")
        }
        assertTrue(store.settleIndeterminate(claimed, NotificationReplyStagingStore.Uncertainty.OWNER_REPORTED) is
            NotificationReplyStagingStore.CustodyResult.SettledIndeterminate)
    }

    @Test
    fun committedClaimWithLostReturnIsIndeterminateAndExactRetryOnlyObserves() = runTest {
        enable()
        val faulting = NotificationReplyStagingStore(file, limits) { throw SQLiteException("lost return") }
        assertEquals(NotificationReplyStagingStore.CustodyResult.Indeterminate(identity),
            faulting.claim(identity, claimId, ownerId))
        val receipt = primary().custody!!
        assertEquals(NotificationReplyStagingStore.Custody.CLAIMED_POSSIBLE_SEND, receipt.state)
        assertEquals(NotificationReplyStagingStore.CustodyResult.Observed(receipt),
            store.claim(identity, claimId, ownerId))
        assertTrue(store.claim(identity, UUID.randomUUID(), ownerId) is NotificationReplyStagingStore.CustodyResult.Fenced)
    }

    @Test
    fun committedSettlementWithLostReturnRemainsIndeterminateAcrossReopen() = runTest {
        enable()
        val claimed = claim()
        val faulting = NotificationReplyStagingStore(file, limits) { throw SQLiteException("lost return") }
        assertEquals(NotificationReplyStagingStore.CustodyResult.Indeterminate(identity),
            faulting.settleIndeterminate(claimed, NotificationReplyStagingStore.Uncertainty.CANCELLED))
        val receipt = primary().custody!!
        assertEquals(NotificationReplyStagingStore.Custody.INDETERMINATE, receipt.state)
        assertEquals(NotificationReplyStagingStore.CustodyResult.Observed(receipt),
            store.settleIndeterminate(claimed, NotificationReplyStagingStore.Uncertainty.CANCELLED))
    }

    @Test
    fun cancellationAfterRealCommitCannotReturnFreshClaimOrResetCustody() = runTest {
        enable()
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val faulting = NotificationReplyStagingStore(file, limits) {
            committed.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        var returned = false
        val job = launch(Dispatchers.Default) {
            faulting.claim(identity, claimId, ownerId)
            returned = true
        }
        try {
            assertTrue(committed.await(10, TimeUnit.SECONDS))
            job.cancel()
        } finally {
            release.countDown()
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertEquals(NotificationReplyStagingStore.Custody.CLAIMED_POSSIBLE_SEND, primary().custody?.state)
        assertTrue(store.claim(identity, claimId, ownerId) is NotificationReplyStagingStore.CustodyResult.Observed)
    }

    @Test
    fun insufficientMigrationBudgetRollsBackSchemaAndDoesNotEvictText() = runTest {
        assertEquals(
            NotificationReplyStagingStore.InitializeResult.Failed(NotificationReplyStagingStore.Failure.LIFECYCLE_CAPACITY),
            store.initializeLifecycle(1),
        )
        assertEquals(1L, scalar("PRAGMA user_version"))
        assertEquals(0L, scalar("SELECT count(*) FROM sqlite_master WHERE name='reply_custody'"))
        assertEquals(text, primary().exactText)
        assertEquals(null, primary().custody)
        enable()
    }

    @Test
    fun receiptBudgetRejectsGrowthWithoutMutatingIntakeAndReplaysStillWork() = runTest {
        // The serialized format accounting is explicit: compute the initial receipt's actual bytes.
        val initial = NotificationReplyStagingStore.CustodyReceipt(
            identity, 0, NotificationReplyStagingStore.Custody.UNCLAIMED, null, null, null,
        )
        val encoded = "${identity.storeId}|${identity.recordId}|${identity.sequence}|" +
            "${initial.revision}|${initial.state.name}|null|null|null"
        val exactBudget = encoded.length * Char.SIZE_BYTES + 32L
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeLifecycle(exactBudget))
        assertEquals(
            NotificationReplyStagingStore.CustodyResult.Failed(NotificationReplyStagingStore.Failure.LIFECYCLE_CAPACITY),
            store.claim(identity, claimId, ownerId),
        )
        assertEquals(NotificationReplyStagingStore.Custody.UNCLAIMED, primary().custody?.state)
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.LIFECYCLE_CAPACITY),
            store.stage(post, payload, "not retained conflict"),
        )
        assertEquals(1, inventory().size)
        assertTrue(store.stage(post, payload, text) is NotificationReplyStagingStore.StageResult.AlreadyStaged)
    }

    @Test
    fun custodyMissingTamperedOrNarrowedNeverBecomesUnclaimed() = runTest {
        enable()
        val claimed = claim()
        listOf("UPDATE reply_custody SET revision=4294967297",
            "UPDATE reply_custody SET owner_id='${UUID.randomUUID()}'",
            "UPDATE reply_custody_policy SET max_bytes=100001",
            "DELETE FROM reply_custody").forEach { sql ->
            exec(sql)
            assertEquals(
                NotificationReplyStagingStore.ReadResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
                store.read(),
            )
            if (sql.startsWith("DELETE")) return@forEach
            when {
                sql.contains("revision=") -> exec("UPDATE reply_custody SET revision=${claimed.revision}")
                sql.contains("owner_id=") -> exec("UPDATE reply_custody SET owner_id='${claimed.ownerId}'")
                else -> exec("UPDATE reply_custody_policy SET max_bytes=100000")
            }
            assertEquals(claimed, primary().custody)
        }
        assertEquals(
            NotificationReplyStagingStore.CustodyResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.claim(identity, claimId, ownerId),
        )
    }

    @Test
    fun missingStorageNeverRecreatesClaimsAndMismatchedPolicyFails() = runTest {
        enable()
        claim()
        assertEquals(
            NotificationReplyStagingStore.InitializeResult.Failed(NotificationReplyStagingStore.Failure.LIMITS_MISMATCH),
            store.initializeLifecycle(100_001),
        )
        cleanup()
        assertEquals(
            NotificationReplyStagingStore.CustodyResult.Failed(NotificationReplyStagingStore.Failure.MISSING_STORAGE),
            store.claim(identity, claimId, ownerId),
        )
        assertFalse(file.exists())
    }

    private suspend fun enable() {
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeLifecycle(100_000))
    }

    private suspend fun claim(): NotificationReplyStagingStore.CustodyReceipt =
        (store.claim(identity, claimId, ownerId) as NotificationReplyStagingStore.CustodyResult.NewlyClaimed).receipt

    private suspend fun inventory() = (store.read() as NotificationReplyStagingStore.ReadResult.Inventory).entries
    private suspend fun primary() = inventory().first() as NotificationReplyStagingStore.RecoveryEntry.StagedReply

    private fun exec(sql: String) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(sql) }
    }

    private fun scalar(sql: String): Long =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.compileStatement(sql).use { it.simpleQueryForLong() }
        }
}
