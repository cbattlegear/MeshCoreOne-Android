// AndroidOnly: WP-401 Real SQLite bounded first-reply reservations; no disk or OS text-survival promise.
package com.meshcoreone.android.platform.notifications.messaging

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.model.RadioId
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class NotificationReplyCapacityTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val file = File(context.cacheDir, "reply-capacity-${UUID.randomUUID()}.db")
    private var limits = NotificationReplyLimits(2, 10_000, 20_000)
    private var custodyBudget = 30_000L
    private val issuance = NotificationReplyStagingStore.IssuanceLimits(10, 10_000, 100_000)
    private val copyLimits = NotificationReplyLimits(2, 10_000, 20_000)
    private val operation = NotificationReplyEnrollmentStore.EnrollmentOperation(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    private lateinit var receipt: NotificationReplyEnrollmentStore.CreationReceipt
    private val enrollment get() = NotificationReplyEnrollmentStore(file, limits, custodyBudget, issuance, copyLimits)
    private val journal get() = enrollment.boundJournal(receipt)
    private val action = NotificationReplyStagingStore.IssuedAction(
        UUID.randomUUID(), NotificationReplyPost(1, UUID.randomUUID(), "REPLY"), NotificationId("logical \u0000\uD800"),
        NotificationPayload.DirectMessage(EntityKey(RadioId(UUID(0, 1)), UUID(0, 2)), UUID(0, 3)),
    )
    private val raw = " \t\u0000e\u0301\uD800\uDC00\uD800 \r\n "

    @After
    fun cleanup() {
        SQLiteDatabase.deleteDatabase(file)
        assertFalse(file.exists())
    }

    @Test
    fun explicitBoundUpgradePreservesOldRecordsCopyTextAndExactEnrollmentReceipt() = runTest {
        provision(enable = false)
        val old = journal.registerIssued(operation.storeId, action) as NotificationReplyStagingStore.IssuanceResult.Reserved
        val staged = journal.admitIssued(operation.storeId, action.token, raw)
        val copy = enrollment.retainUnbound(receipt, UUID.randomUUID(), raw)
        assertEquals(ready, journal.initializeReplyReservations(operation.storeId))
        assertEquals(ready, journal.initializeReplyReservations(operation.storeId))
        assertEquals(5L, scalar("PRAGMA user_version"))
        assertEquals(NotificationReplyEnrollmentStore.EnrollmentResult.Observed(receipt), enrollment.reconcile(operation))
        assertEquals(NotificationReplyStagingStore.IssuanceResult.AlreadyReserved(old.record),
            journal.registerIssued(operation.storeId, action))
        assertTrue(staged is NotificationReplyStagingStore.StageResult.Staged)
        assertEquals(raw, inventory().single().exactText)
        assertEquals((copy as NotificationReplyEnrollmentStore.TextResult.Durable).record,
            (enrollment.readUnbound(receipt) as NotificationReplyEnrollmentStore.TextInventory.Retained).records.single())
        assertEquals(listOf(raw, raw),
            (journal.readCopyOnly() as NotificationReplyStagingStore.CopyInventory.Retained).records.map { it.exactText })
    }

    @Test
    fun missingPreparingOrUnboundHandleCannotEnableReservationsOrCreateStorage() = runTest {
        val direct = NotificationReplyStagingStore(file, limits)
        assertEquals(NotificationReplyStagingStore.InitializeResult.Failed(
            NotificationReplyStagingStore.Failure.MISSING_STORAGE),
            direct.initializeReplyReservations(operation.storeId))
        assertFalse(file.exists())
        val preparing = (enrollment.prepare(operation) as
            NotificationReplyEnrollmentStore.EnrollmentResult.Preparing).receipt
        assertEquals(NotificationReplyStagingStore.InitializeResult.Failed(
            NotificationReplyStagingStore.Failure.ENROLLMENT_NOT_BOUND),
            enrollment.boundJournal(preparing).initializeReplyReservations(operation.storeId))
        receipt = bind(preparing)
        assertEquals(NotificationReplyStagingStore.InitializeResult.Failed(
            NotificationReplyStagingStore.Failure.ENROLLMENT_MISMATCH),
            direct.initializeReplyReservations(operation.storeId))
        assertEquals(4L, scalar("PRAGMA user_version"))
    }

    @Test
    fun explicitOptInAndPositiveCallerTextBoundAreRequiredWithoutDefaults() = runTest {
        provision(enable = false)
        assertEquals(rejected(NotificationReplyStagingStore.Failure.REPLY_RESERVATIONS_NOT_INITIALIZED),
            reserve())
        assertEquals(ready, journal.initializeReplyReservations(operation.storeId))
        listOf(0, -1, Int.MAX_VALUE).forEach {
            assertEquals(rejected(NotificationReplyStagingStore.Failure.INVALID_REPLY_TEXT_BOUND), reserve(bound = it))
        }
        assertEquals(0L, scalar("SELECT COUNT(*) FROM reply_issued_actions"))
    }

    @Test
    fun alreadyFullJournalRejectsBeforeRetainingOrExposingAnotherIssuedAction() = runTest {
        limits = limits.copy(maxRecords = 1)
        provision()
        assertTrue(journal.stage(action.post, action.payload, raw) is NotificationReplyStagingStore.StageResult.Staged)
        assertEquals(rejected(NotificationReplyStagingStore.Failure.RECORD_CAPACITY), reserve())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM reply_issued_actions"))
        assertEquals(raw, inventory().single().exactText)
    }

    @Test
    fun perRecordAndTotalPrivateByteLimitsRejectReservationWithoutRegistryWrite() = runTest {
        limits = NotificationReplyLimits(2, 1_000, 1_000)
        provision()
        assertEquals(rejected(NotificationReplyStagingStore.Failure.PRIVATE_DATA_CAPACITY), reserve(bound = 500))
        assertTrue(reserve(bound = 200) is NotificationReplyStagingStore.IssuanceResult.Reserved)
        assertEquals(rejected(NotificationReplyStagingStore.Failure.PRIVATE_DATA_CAPACITY),
            reserve(otherAction(), 200))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM reply_issued_actions"))
    }

    @Test
    fun initialCustodyCapacityIsReservedBeforeActionExposure() = runTest {
        custodyBudget = 1
        provision()
        assertEquals(rejected(NotificationReplyStagingStore.Failure.LIFECYCLE_CAPACITY), reserve())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM reply_issued_actions"))
        assertTrue(inventory().isEmpty())
    }

    @Test
    fun pendingReservationPreventsLegacyAndUnreservedActionsFromSpendingItsRow() = runTest {
        limits = limits.copy(maxRecords = 1)
        provision()
        val reserved = reserve() as NotificationReplyStagingStore.IssuanceResult.Reserved
        val old = otherAction()
        assertTrue(journal.registerIssued(operation.storeId, old) is NotificationReplyStagingStore.IssuanceResult.Reserved)
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.RECORD_CAPACITY),
            journal.admitIssued(operation.storeId, old.token, raw))
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.RECORD_CAPACITY),
            journal.stage(old.post, old.payload, raw))
        assertTrue(journal.admitIssued(operation.storeId, action.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
        assertEquals(raw, inventory().single().exactText)
        assertEquals(raw.length, reserved.record.maxReplyCodeUnits)
    }

    @Test
    fun pendingReservationProtectsExactSerializedBytesAtTheOneByteBoundary() = runTest {
        provision()
        assertTrue(reserve() is NotificationReplyStagingStore.IssuanceResult.Reserved)
        assertTrue(journal.admitIssued(operation.storeId, action.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
        val rowBytes = scalar("SELECT content_bytes FROM reply_records")
        cleanup()
        limits = NotificationReplyLimits(2, rowBytes, rowBytes * 2 - 1)
        provision()
        assertTrue(reserve() is NotificationReplyStagingStore.IssuanceResult.Reserved)
        val old = otherAction()
        assertTrue(journal.registerIssued(operation.storeId, old) is NotificationReplyStagingStore.IssuanceResult.Reserved)
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.PRIVATE_DATA_CAPACITY),
            journal.admitIssued(operation.storeId, old.token, raw))
        assertTrue(journal.admitIssued(operation.storeId, action.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
        assertEquals(rowBytes, scalar("SELECT content_bytes FROM reply_records"))
    }

    @Test
    fun custodyGrowthCannotSpendAnotherTokensReservedInitialReceipt() = runTest {
        provision()
        val old = otherAction()
        journal.registerIssued(operation.storeId, old)
        journal.admitIssued(operation.storeId, old.token, raw)
        val initialBytes = initialCustodyBytes()
        cleanup()
        custodyBudget = initialBytes * 2
        provision()
        journal.registerIssued(operation.storeId, old)
        val first = journal.admitIssued(operation.storeId, old.token, raw) as NotificationReplyStagingStore.StageResult.Staged
        assertTrue(reserve() is NotificationReplyStagingStore.IssuanceResult.Reserved)
        assertEquals(NotificationReplyStagingStore.CustodyResult.Failed(
            NotificationReplyStagingStore.Failure.LIFECYCLE_CAPACITY),
            journal.claim(first.identity, UUID.randomUUID(), UUID.randomUUID()))
        assertTrue(journal.admitIssued(operation.storeId, action.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
        assertEquals(2, inventory().size)
    }

    @Test
    fun distinctOutstandingReservationsEachRetainTheirOwnExactInitialReplyAfterRestart() = runTest {
        provision()
        val other = otherAction()
        val first = reserve() as NotificationReplyStagingStore.IssuanceResult.Reserved
        assertTrue(reserve(other) is NotificationReplyStagingStore.IssuanceResult.Reserved)
        assertEquals(NotificationReplyStagingStore.IssuanceResult.AlreadyReserved(first.record), reserve())
        assertTrue(journal.admitIssued(operation.storeId, other.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
        assertTrue(journal.admitIssued(operation.storeId, action.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
        assertEquals(listOf(raw, raw), inventory().map { it.exactText })
    }

    @Test
    fun exactUtf16BoundPreservesNulSurrogatesCombiningFormsAndWhitespaceWithoutTrimming() = runTest {
        limits = limits.copy(maxRecords = 1)
        provision()
        reserve()
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.REPLY_TEXT_BOUND_EXCEEDED),
            journal.admitIssued(operation.storeId, action.token, raw + "\uD800"))
        assertTrue(inventory().isEmpty())
        assertTrue(journal.admitIssued(operation.storeId, action.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
        assertEquals(raw.toCharArray().toList(), inventory().single().exactText.toCharArray().toList())
    }

    @Test
    fun blankButNonemptyReplyIsExactAndEmptyReplyDoesNotConsumeReservation() = runTest {
        provision()
        reserve(bound = 3)
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.INVALID_TEXT),
            journal.admitIssued(operation.storeId, action.token, ""))
        assertTrue(reserve(bound = 3) is NotificationReplyStagingStore.IssuanceResult.AlreadyReserved)
        assertTrue(journal.admitIssued(operation.storeId, action.token, " \t\n") is NotificationReplyStagingStore.StageResult.Staged)
        assertEquals(" \t\n", inventory().single().exactText)
    }

    @Test
    fun duplicateAtFullCapacityIsIdempotentButConsumedReservationNeverReexposesAction() = runTest {
        limits = limits.copy(maxRecords = 1)
        provision()
        reserve()
        val first = journal.admitIssued(operation.storeId, action.token, raw) as NotificationReplyStagingStore.StageResult.Staged
        assertEquals(NotificationReplyStagingStore.StageResult.AlreadyStaged(first.identity, first.outgoingMessageId),
            journal.admitIssued(operation.storeId, action.token, raw))
        assertEquals(rejected(NotificationReplyStagingStore.Failure.REPLY_RESERVATION_CONSUMED), reserve())
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.RECORD_CAPACITY),
            journal.admitIssued(operation.storeId, action.token, "changed"))
        assertEquals(raw, inventory().single().exactText)
    }

    @Test
    fun changedSubmissionUsesOnlySpareUnreservedSpaceAndRemainsUnboundCopyNotNewSend() = runTest {
        limits = limits.copy(maxRecords = 3)
        provision()
        val pending = otherAction()
        reserve()
        reserve(pending)
        journal.admitIssued(operation.storeId, action.token, raw)
        val changed = journal.admitIssued(operation.storeId, action.token, "changed") as
            NotificationReplyStagingStore.StageResult.ConflictRetained
        assertFalse(changed.alreadyRetained)
        assertEquals(changed.copy(alreadyRetained = true),
            journal.admitIssued(operation.storeId, action.token, "changed"))
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.RECORD_CAPACITY),
            journal.admitIssued(operation.storeId, action.token, "third"))
        assertTrue(journal.admitIssued(operation.storeId, pending.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
        val conflict = inventory().filterIsInstance<NotificationReplyStagingStore.RecoveryEntry.UnboundConflict>().single()
        assertEquals("changed", conflict.exactText)
        assertEquals(NotificationReplyStagingStore.TargetAuthority.UNPROVED, conflict.targetAuthority)
    }

    @Test
    fun oldRegistryOnlyTokenCannotBeUpgradedOrBoundedTokenDowngradedOrRebound() = runTest {
        provision()
        journal.registerIssued(operation.storeId, action)
        assertEquals(rejected(NotificationReplyStagingStore.Failure.ISSUANCE_CONFLICT), reserve())
        val bounded = otherAction()
        reserve(bounded)
        assertEquals(rejected(NotificationReplyStagingStore.Failure.ISSUANCE_CONFLICT),
            journal.registerIssued(operation.storeId, bounded))
        assertEquals(rejected(NotificationReplyStagingStore.Failure.ISSUANCE_CONFLICT),
            reserve(bounded, raw.length + 1))
        assertEquals(rejected(NotificationReplyStagingStore.Failure.ISSUANCE_CONFLICT),
            reserve(bounded.copy(payload =
                NotificationPayload.ChannelMessage(RadioId(UUID(0, 9)), 1u, UUID(0, 10)))))
    }

    @Test
    fun unknownOldOrForeignTokensNeverSpendReservationOrAcquireARouteFromCopyRetention() = runTest {
        limits = limits.copy(maxRecords = 1)
        provision()
        reserve()
        val unknown = UUID.randomUUID()
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.ISSUED_TOKEN_NOT_FOUND),
            journal.admitIssued(operation.storeId, unknown, raw))
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.IDENTITY_MISMATCH),
            journal.admitIssued(UUID.randomUUID(), action.token, raw))
        assertTrue(enrollment.retainUnbound(receipt, unknown, raw) is NotificationReplyEnrollmentStore.TextResult.Durable)
        assertTrue(journal.admitIssued(operation.storeId, action.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
        assertEquals(listOf(raw, raw),
            (journal.readCopyOnly() as NotificationReplyStagingStore.CopyInventory.Retained).records.map { it.exactText })
        assertEquals(1L, scalar("SELECT COUNT(*) FROM reply_records"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM reply_unbound_text"))
    }

    @Test
    fun concurrentReservationsCannotOversubscribeTheLastRowAcrossIndependentHandles() = runTest {
        limits = limits.copy(maxRecords = 1)
        provision()
        val gate = CountDownLatch(1)
        val proposals = List(8) { otherAction() }
        val tasks = proposals.map { proposal -> async(Dispatchers.IO) {
            assertTrue(gate.await(10, TimeUnit.SECONDS))
            reserve(proposal)
        } }
        gate.countDown()
        val results = tasks.awaitAll()
        assertEquals(1, results.count { it is NotificationReplyStagingStore.IssuanceResult.Reserved })
        assertEquals(7, results.count { it == rejected(NotificationReplyStagingStore.Failure.RECORD_CAPACITY) })
        val winner = (results.single { it is NotificationReplyStagingStore.IssuanceResult.Reserved } as
            NotificationReplyStagingStore.IssuanceResult.Reserved).record.action
        assertTrue(journal.admitIssued(operation.storeId, winner.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
    }

    @Test
    fun concurrentSameTokenBoundAndExactSubmissionReconcileOneReservationAndOneRow() = runTest {
        limits = limits.copy(maxRecords = 1)
        provision()
        val reservations = List(8) { async(Dispatchers.IO) { reserve() } }.awaitAll()
        assertEquals(1, reservations.count { it is NotificationReplyStagingStore.IssuanceResult.Reserved })
        assertEquals(7, reservations.count { it is NotificationReplyStagingStore.IssuanceResult.AlreadyReserved })
        val replies = List(8) { async(Dispatchers.IO) {
            journal.admitIssued(operation.storeId, action.token, raw)
        } }.awaitAll()
        assertEquals(1, replies.count { it is NotificationReplyStagingStore.StageResult.Staged })
        assertEquals(7, replies.count { it is NotificationReplyStagingStore.StageResult.AlreadyStaged })
        assertEquals(raw, inventory().single().exactText)
    }

    @Test
    fun concurrentLegacyWritersCannotStealReservedSlotWhileInitialReplyCommits() = runTest {
        limits = limits.copy(maxRecords = 1)
        provision()
        reserve()
        val gate = CountDownLatch(1)
        val legacy = List(8) { async(Dispatchers.IO) {
            assertTrue(gate.await(10, TimeUnit.SECONDS))
            val other = otherAction()
            journal.stage(other.post, other.payload, raw)
        } }
        val initial = async(Dispatchers.IO) {
            assertTrue(gate.await(10, TimeUnit.SECONDS))
            journal.admitIssued(operation.storeId, action.token, raw)
        }
        gate.countDown()
        assertTrue(initial.await() is NotificationReplyStagingStore.StageResult.Staged)
        assertTrue(legacy.awaitAll().all {
            it == stageRejected(NotificationReplyStagingStore.Failure.RECORD_CAPACITY)
        })
        assertEquals(action.token, inventory().single().issuedToken)
    }

    @Test
    fun concurrentDifferentBoundsForOneTokenCannotReplaceFirstImmutableReservation() = runTest {
        provision()
        val gate = CountDownLatch(1)
        val tasks = listOf(raw.length, raw.length + 1).map { bound -> async(Dispatchers.IO) {
            assertTrue(gate.await(10, TimeUnit.SECONDS))
            reserve(bound = bound)
        } }
        gate.countDown()
        val results = tasks.awaitAll()
        assertEquals(1, results.count { it is NotificationReplyStagingStore.IssuanceResult.Reserved })
        assertEquals(1, results.count { it == rejected(NotificationReplyStagingStore.Failure.ISSUANCE_CONFLICT) })
        val record = (results.single { it is NotificationReplyStagingStore.IssuanceResult.Reserved } as
            NotificationReplyStagingStore.IssuanceResult.Reserved).record
        assertEquals(NotificationReplyStagingStore.IssuanceResult.AlreadyReserved(record),
            reserve(bound = requireNotNull(record.maxReplyCodeUnits)))
    }

    @Test
    fun ignoredReservationRowAndAccountingWritesRollbackWithoutFalseReservedSuccess() = runTest {
        provision()
        database { it.execSQL(
            "CREATE TRIGGER ignore_issue BEFORE INSERT ON reply_issued_actions BEGIN SELECT RAISE(IGNORE); END") }
        assertEquals(rejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE), reserve())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM reply_issued_actions"))
        database {
            it.execSQL("DROP TRIGGER ignore_issue")
            it.execSQL("CREATE TRIGGER ignore_count BEFORE UPDATE ON reply_issuance_policy BEGIN SELECT RAISE(IGNORE); END")
        }
        assertEquals(rejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE), reserve())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM reply_issued_actions"))
    }

    @Test
    fun actualAbortedReplyWriteIsNotRetainedAndDoesNotConsumeReservedQuota() = runTest {
        provision()
        val reservation = reserve() as NotificationReplyStagingStore.IssuanceResult.Reserved
        database { it.execSQL(
            "CREATE TRIGGER reject_reply BEFORE INSERT ON reply_records BEGIN SELECT RAISE(ABORT,'write refused'); END") }
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.STORAGE_UNAVAILABLE),
            journal.admitIssued(operation.storeId, action.token, raw))
        assertTrue(inventory().isEmpty())
        assertEquals(NotificationReplyStagingStore.IssuanceResult.AlreadyReserved(reservation.record), reserve())
        database { it.execSQL("DROP TRIGGER reject_reply") }
        assertTrue(journal.admitIssued(operation.storeId, action.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
    }

    @Test
    fun ignoredCustodyWriteRollsBackReplyAndKeepsReservationAvailable() = runTest {
        provision()
        reserve()
        database { it.execSQL(
            "CREATE TRIGGER ignore_custody BEFORE INSERT ON reply_custody BEGIN SELECT RAISE(IGNORE); END") }
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            journal.admitIssued(operation.storeId, action.token, raw))
        assertTrue(inventory().isEmpty())
        assertTrue(reserve() is NotificationReplyStagingStore.IssuanceResult.AlreadyReserved)
    }

    @Test
    fun lostCommittedReservationReturnReconcilesExactTokenBoundAndGenerationOnly() = runTest {
        provision()
        val faulting = NotificationReplyStagingStore(file, limits, receipt, afterIssuanceCommit = {
            throw SQLiteException("lost return")
        })
        val observed = faulting.reserveIssuedReply(operation.storeId, action, raw.length) as
            NotificationReplyStagingStore.IssuanceResult.AlreadyReserved
        assertEquals(raw.length, observed.record.maxReplyCodeUnits)
        assertEquals(NotificationReplyStagingStore.IssuanceResult.AlreadyReserved(observed.record), reserve())
        assertEquals(rejected(NotificationReplyStagingStore.Failure.ISSUANCE_CONFLICT), reserve(bound = raw.length + 1))
    }

    @Test
    fun callerDeathAfterCommitBeforeReturnLeavesRestartedReservationAndAdmissionIdempotent() = runTest {
        limits = limits.copy(maxRecords = 1)
        provision()
        val issueJob = launch(Dispatchers.IO) {
            NotificationReplyStagingStore(file, limits, receipt, afterIssuanceCommit = {
                throw CancellationException("caller interrupted after commit")
            }).reserveIssuedReply(operation.storeId, action, raw.length)
        }
        issueJob.join()
        assertTrue(issueJob.isCancelled)
        assertTrue(reserve() is NotificationReplyStagingStore.IssuanceResult.AlreadyReserved)
        val replyJob = launch(Dispatchers.IO) {
            NotificationReplyStagingStore(file, limits, receipt, afterAdmissionCommit = {
                throw CancellationException("caller interrupted after commit")
            }).admitIssued(operation.storeId, action.token, raw)
        }
        replyJob.join()
        assertTrue(replyJob.isCancelled)
        assertTrue(journal.admitIssued(operation.storeId, action.token, raw) is
            NotificationReplyStagingStore.StageResult.AlreadyStaged)
        assertEquals(rejected(NotificationReplyStagingStore.Failure.REPLY_RESERVATION_CONSUMED), reserve())
        assertEquals(raw, inventory().single().exactText)
    }

    @Test
    fun postCommitStorageLossReturnsIndeterminateAndNeverRecreatesLostTextOrReservation() = runTest {
        provision()
        val faulting = NotificationReplyStagingStore(file, limits, receipt, afterIssuanceCommit = {
            SQLiteDatabase.deleteDatabase(file)
            throw SQLiteException("storage lost")
        })
        assertEquals(NotificationReplyStagingStore.IssuanceResult.Indeterminate(action.token),
            faulting.reserveIssuedReply(operation.storeId, action, raw.length))
        assertFalse(file.exists())
        assertEquals(rejected(NotificationReplyStagingStore.Failure.MISSING_STORAGE), reserve())
        assertFalse(file.exists())
        provision()
        reserve()
        val replyFault = NotificationReplyStagingStore(file, limits, receipt, afterAdmissionCommit = {
            SQLiteDatabase.deleteDatabase(file)
            throw SQLiteException("storage lost")
        })
        assertTrue(replyFault.admitIssued(operation.storeId, action.token, raw) is
            NotificationReplyStagingStore.StageResult.Indeterminate)
        assertFalse(file.exists())
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.MISSING_STORAGE),
            journal.admitIssued(operation.storeId, action.token, raw))
    }

    @Test
    fun sameOperationStoreReplacementWithDifferentNonceCannotAdoptOutstandingCapacity() = runTest {
        provision()
        reserve()
        val retainedReceipt = receipt
        val held = journal
        cleanup()
        val replacement = operation.copy(creationNonce = UUID.randomUUID())
        val preparing = (enrollment.prepare(replacement) as
            NotificationReplyEnrollmentStore.EnrollmentResult.Preparing).receipt
        receipt = bind(preparing)
        assertEquals(ready, journal.initializeReplyReservations(operation.storeId))
        reserve()
        assertEquals(rejected(NotificationReplyStagingStore.Failure.ENROLLMENT_MISMATCH),
            held.reserveIssuedReply(operation.storeId, action, raw.length))
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.ENROLLMENT_MISMATCH),
            held.admitIssued(operation.storeId, action.token, raw))
        assertEquals(NotificationReplyEnrollmentStore.EnrollmentResult.Indeterminate(
            retainedReceipt.operation, NotificationReplyEnrollmentStore.Failure.MISMATCH),
            enrollment.reconcile(retainedReceipt.operation))
    }

    @Test
    fun lostReservationReturnCannotReconcileIdenticalTokenAndBoundInReplacementNonce() = runTest {
        provision()
        val replacementFile = File(context.cacheDir, "reply-capacity-replacement-${UUID.randomUUID()}.db")
        try {
            val replacement = NotificationReplyEnrollmentStore(
                replacementFile, limits, custodyBudget, issuance, copyLimits)
            val operation2 = operation.copy(creationNonce = UUID.randomUUID())
            val preparing = (replacement.prepare(operation2) as
                NotificationReplyEnrollmentStore.EnrollmentResult.Preparing).receipt
            val bound = (replacement.bind(preparing, NotificationReplyEnrollmentStore.WitnessAcceptance(
                operation2, operation2.creationNonce, UUID.randomUUID())) as
                NotificationReplyEnrollmentStore.EnrollmentResult.Bound).receipt
            val replacementJournal = replacement.boundJournal(bound)
            assertEquals(ready, replacementJournal.initializeReplyReservations(operation.storeId))
            assertTrue(replacementJournal.reserveIssuedReply(operation.storeId, action, raw.length) is
                NotificationReplyStagingStore.IssuanceResult.Reserved)
            val faulting = NotificationReplyStagingStore(file, limits, receipt, afterIssuanceCommit = {
                SQLiteDatabase.deleteDatabase(file)
                replacementFile.copyTo(file)
                throw SQLiteException("lost return after replacement")
            })
            assertEquals(NotificationReplyStagingStore.IssuanceResult.Indeterminate(action.token),
                faulting.reserveIssuedReply(operation.storeId, action, raw.length))
            assertEquals(rejected(NotificationReplyStagingStore.Failure.ENROLLMENT_MISMATCH), reserve())
        } finally {
            SQLiteDatabase.deleteDatabase(replacementFile)
            assertFalse(replacementFile.exists())
        }
    }

    @Test
    fun corruptReservationBoundOrEnrollmentIdentityFailsClosedWithoutPartialInventory() = runTest {
        provision()
        reserve()
        database { it.execSQL("UPDATE reply_issued_actions SET max_reply_code_units=max_reply_code_units+1") }
        assertEquals(rejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE), reserve())
        assertEquals(NotificationReplyStagingStore.ReadResult.Failed(
            NotificationReplyStagingStore.Failure.CORRUPT_STORAGE), journal.read(operation.storeId))
        assertEquals(NotificationReplyStagingStore.CopyInventory.Failed(
            NotificationReplyStagingStore.Failure.CORRUPT_STORAGE), journal.readCopyOnly())
    }

    private suspend fun provision(enable: Boolean = true) {
        val preparing = (enrollment.prepare(operation) as
            NotificationReplyEnrollmentStore.EnrollmentResult.Preparing).receipt
        receipt = bind(preparing)
        if (enable) assertEquals(ready, journal.initializeReplyReservations(operation.storeId))
    }

    private suspend fun bind(preparing: NotificationReplyEnrollmentStore.CreationReceipt) =
        (enrollment.bind(preparing, NotificationReplyEnrollmentStore.WitnessAcceptance(
            preparing.operation, preparing.creationNonce, UUID.randomUUID())) as
            NotificationReplyEnrollmentStore.EnrollmentResult.Bound).receipt

    private suspend fun reserve(
        proposal: NotificationReplyStagingStore.IssuedAction = action,
        bound: Int = raw.length,
    ) = journal.reserveIssuedReply(operation.storeId, proposal, bound)

    private fun otherAction() = action.copy(token = UUID.randomUUID(),
        post = action.post.copy(postId = UUID.randomUUID()))

    private suspend fun inventory() =
        (journal.read(operation.storeId) as NotificationReplyStagingStore.ReadResult.Inventory).entries

    private fun scalar(sql: String): Long = database { db -> db.rawQuery(sql, null).use {
        assertTrue(it.moveToFirst())
        it.getLong(0)
    } }

    private fun initialCustodyBytes(): Long = database { db ->
        db.rawQuery("SELECT record_id,revision,state,claim_id,owner_id,uncertainty,digest FROM reply_custody", null).use {
            assertTrue(it.moveToFirst())
            val serialized = "${operation.storeId}|${it.getString(0)}|1|${it.getLong(1)}|${it.getString(2)}|" +
                "${it.getString(3)}|${it.getString(4)}|${it.getString(5)}"
            serialized.length.toLong() * Char.SIZE_BYTES + it.getBlob(6).size
        }
    }

    private fun <T> database(block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use(block)

    private fun rejected(reason: NotificationReplyStagingStore.Failure) =
        NotificationReplyStagingStore.IssuanceResult.Rejected(reason)

    private fun stageRejected(reason: NotificationReplyStagingStore.Failure) =
        NotificationReplyStagingStore.StageResult.Rejected(reason)

    private val ready get() = NotificationReplyStagingStore.InitializeResult.Ready
}
