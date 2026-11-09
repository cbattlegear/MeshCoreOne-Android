// AndroidOnly: WP-401 Real SQLite loss-aware enrollment causality and no-route copy-only retention.
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class NotificationReplyEnrollmentTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val file = File(context.cacheDir, "reply-enrollment-${UUID.randomUUID()}.db")
    private val limits = NotificationReplyLimits(3, 10_000, 30_000)
    private val issuance = NotificationReplyStagingStore.IssuanceLimits(3, 10_000, 30_000)
    private val operation = NotificationReplyEnrollmentStore.EnrollmentOperation(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    private val store get() = NotificationReplyEnrollmentStore(file, limits, 30_000, issuance, limits)
    private val text = " \t\u0000e\u0301\uD800\uDC00\uD800 \r\n "
    private val proposal = NotificationReplyStagingStore.IssuedAction(
        UUID.randomUUID(), NotificationReplyPost(1, UUID.randomUUID(), "REPLY"), NotificationId("logical"),
        NotificationPayload.DirectMessage(EntityKey(RadioId(UUID(0, 1)), UUID(0, 2)), UUID(0, 3)),
    )

    @After
    fun cleanup() = removeDatabase(file)

    @Test
    fun unboundTextAndContainingResultsRedactRenderingWithoutChangingValueSemantics() = runTest {
        val bound = bindFixture(prepareFixture())
        val submission = UUID.randomUUID()
        val exact = "private-unbound-sentinel$text"
        val durable = store.retainUnbound(bound, submission, exact) as
            NotificationReplyEnrollmentStore.TextResult.Durable
        val duplicate = store.retainUnbound(bound, submission, exact) as
            NotificationReplyEnrollmentStore.TextResult.AlreadyRetained
        val retained = store.readUnbound(bound) as NotificationReplyEnrollmentStore.TextInventory.Retained
        val record = durable.record
        val same = record.copy()
        val changed = record.copy(exactText = "$exact ")

        assertEquals(exact.toCharArray().toList(), record.exactText.toCharArray().toList())
        assertEquals(record, same)
        assertEquals(record.hashCode(), same.hashCode())
        assertNotEquals(record, changed)
        assertEquals("$exact ", changed.exactText)
        assertEquals(record, duplicate.record)
        assertEquals(listOf(record), retained.records)
        assertEquals(record, retained.records.single().copy())
        assertEquals("UnboundText(<redacted>)", record.toString())
        assertEquals(record.toString(), changed.toString())
        listOf(record, same, changed, durable, duplicate, retained).forEach {
            assertFalse(it.toString().contains("private-unbound-sentinel"))
            assertFalse(it.toString().contains(exact))
        }
    }

    @Test
    fun missingReconcileAndCopyDoNotCreateOrInferAuthorizedFreshInstallation() = runTest {
        assertEquals(indeterminate(NotificationReplyEnrollmentStore.Failure.MISSING_STORAGE),
            store.reconcile(operation))
        val unknown = NotificationReplyEnrollmentStore.CreationReceipt(
            operation, 2, NotificationReplyEnrollmentStore.Phase.BOUND, operation.creationNonce, UUID.randomUUID())
        assertEquals(NotificationReplyEnrollmentStore.TextResult.NotRetained(
            NotificationReplyEnrollmentStore.Failure.MISSING_STORAGE),
            store.retainUnbound(unknown, UUID.randomUUID(), text))
        assertFalse(file.exists())
    }

    @Test
    fun predeclaredOperationCreatesPreparingReceiptAndCannotIssueUntilExactBoundWitness() = runTest {
        val preparing = prepareFixture()
        assertEquals(operation, preparing.operation)
        assertEquals(1L, preparing.revision)
        assertEquals(NotificationReplyEnrollmentStore.Phase.PREPARING, preparing.state)
        assertEquals(null, preparing.witnessId)
        assertEquals(NotificationReplyEnrollmentStore.EnrollmentResult.AlreadyPreparing(preparing),
            store.prepare(operation))
        val direct = NotificationReplyStagingStore(file, limits)
        assertEquals(NotificationReplyStagingStore.IssuanceResult.Rejected(
            NotificationReplyStagingStore.Failure.ENROLLMENT_NOT_BOUND),
            direct.registerIssued(operation.storeId, proposal))
        assertEquals(NotificationReplyEnrollmentStore.TextResult.NotRetained(
            NotificationReplyEnrollmentStore.Failure.NOT_BOUND),
            store.retainUnbound(preparing, UUID.randomUUID(), text))
        val bound = bindFixture(preparing)
        assertEquals(preparing.creationNonce, bound.creationNonce)
        assertEquals(2L, bound.revision)
        assertTrue(store.boundJournal(bound).registerIssued(operation.storeId, proposal) is
            NotificationReplyStagingStore.IssuanceResult.Reserved)
        assertEquals(NotificationReplyStagingStore.IssuanceResult.Rejected(
            NotificationReplyStagingStore.Failure.ENROLLMENT_MISMATCH),
            direct.registerIssued(operation.storeId, proposal))
    }

    @Test
    fun wrongOperationStoreNonceOrWitnessCannotAdoptReplaceOrRebindExistingGeneration() = runTest {
        val preparing = prepareFixture()
        assertEquals(notRetained(NotificationReplyEnrollmentStore.Failure.MISMATCH),
            store.prepare(operation.copy(operationId = UUID.randomUUID())))
        assertEquals(notRetained(NotificationReplyEnrollmentStore.Failure.MISMATCH),
            store.prepare(operation.copy(storeId = UUID.randomUUID())))
        assertEquals(notRetained(NotificationReplyEnrollmentStore.Failure.MISMATCH),
            store.reconcile(operation, UUID.randomUUID()))
        assertEquals(notRetained(NotificationReplyEnrollmentStore.Failure.MISMATCH),
            store.bind(preparing, acceptance(preparing).copy(creationNonce = UUID.randomUUID())))
        val witness = acceptance(preparing)
        val bound = (store.bind(preparing, witness) as NotificationReplyEnrollmentStore.EnrollmentResult.Bound).receipt
        assertEquals(NotificationReplyEnrollmentStore.EnrollmentResult.AlreadyBound(bound), store.bind(preparing, witness))
        assertEquals(notRetained(NotificationReplyEnrollmentStore.Failure.MISMATCH),
            store.bind(preparing, witness.copy(witnessId = UUID.randomUUID())))
        assertEquals(NotificationReplyEnrollmentStore.EnrollmentResult.Observed(bound),
            store.reconcile(operation, preparing.creationNonce))
    }

    @Test
    fun restartAtEachPersistedPhasePreservesExactCausalReceiptAndStableWitnessIdentity() = runTest {
        val preparing = prepareFixture()
        assertEquals(NotificationReplyEnrollmentStore.EnrollmentResult.Observed(preparing),
            store.reconcile(operation, preparing.creationNonce))
        val witness = acceptance(preparing)
        val bound = (store.bind(preparing, witness) as NotificationReplyEnrollmentStore.EnrollmentResult.Bound).receipt
        assertEquals(witness.witnessId, bound.witnessId)
        assertEquals(NotificationReplyEnrollmentStore.EnrollmentResult.Observed(bound),
            store.reconcile(operation, bound.creationNonce))
        assertEquals(NotificationReplyEnrollmentStore.EnrollmentResult.AlreadyBound(bound), store.prepare(operation))
        assertEquals(NotificationReplyEnrollmentStore.EnrollmentResult.AlreadyBound(bound), store.bind(preparing, witness))
    }

    @Test
    fun allocatedEmptyCorruptOrForeignPathIsNeverReinitializedAfterCrash() = runTest {
        assertTrue(file.createNewFile())
        assertEquals(notRetained(NotificationReplyEnrollmentStore.Failure.EXISTING_UNATTRIBUTED_STORAGE),
            store.prepare(operation))
        cleanup()
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val corrupt = file.readBytes()
        assertTrue(store.prepare(operation) is NotificationReplyEnrollmentStore.EnrollmentResult.NotRetained)
        assertTrue(corrupt.contentEquals(file.readBytes()))
        cleanup()
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.execSQL("CREATE TABLE unrelated(value TEXT)")
            it.execSQL("INSERT INTO unrelated VALUES('preserve')")
        }
        assertEquals(notRetained(NotificationReplyEnrollmentStore.Failure.EXISTING_UNATTRIBUTED_STORAGE),
            store.prepare(operation))
        database { db ->
            db.rawQuery("SELECT value FROM unrelated", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("preserve", it.getString(0))
            }
        }
    }

    @Test
    fun postPrepareCommitLostReturnReconcilesOnlySameCreationNonce() = runTest {
        val faulting = fault { if (it == NotificationReplyEnrollmentStore.CommitPoint.PREPARE) {
            throw SQLiteException("lost prepare return")
        } }
        val result = faulting.prepare(operation) as NotificationReplyEnrollmentStore.EnrollmentResult.AlreadyPreparing
        assertEquals(operation, result.receipt.operation)
        assertEquals(NotificationReplyEnrollmentStore.EnrollmentResult.Observed(result.receipt),
            store.reconcile(operation, result.receipt.creationNonce))
    }

    @Test
    fun callerDiesAfterPrepareCommitBeforeReturnAndSameOperationStoreReplacementCannotBeAdopted() = runTest {
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        var returned = false
        val interrupted = fault { if (it == NotificationReplyEnrollmentStore.CommitPoint.PREPARE) {
            committed.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        } }
        val caller = launch(Dispatchers.Default) {
            interrupted.prepare(operation)
            returned = true
        }
        try {
            assertTrue(committed.await(10, TimeUnit.SECONDS))
            caller.cancel()
        } finally {
            release.countDown()
        }
        caller.join()
        assertTrue(caller.isCancelled)
        assertFalse(returned)
        val original = store.reconcile(operation) as NotificationReplyEnrollmentStore.EnrollmentResult.Observed
        assertEquals(operation.creationNonce, original.receipt.creationNonce)
        val replacement = File(context.cacheDir, "reply-pre-return-${UUID.randomUUID()}.db")
        try {
            val differentNonce = operation.copy(creationNonce = UUID.randomUUID())
            val other = NotificationReplyEnrollmentStore(replacement, limits, 30_000, issuance, limits)
            assertTrue(other.prepare(differentNonce) is NotificationReplyEnrollmentStore.EnrollmentResult.Preparing)
            cleanup()
            replacement.copyTo(file)
            val before = file.readBytes()
            // Both APIs used to accept a null nonce here and return the replacement's receipt.
            assertEquals(notRetained(NotificationReplyEnrollmentStore.Failure.MISMATCH), store.prepare(operation))
            assertEquals(indeterminate(NotificationReplyEnrollmentStore.Failure.MISMATCH), store.reconcile(operation))
            assertEquals(notRetained(NotificationReplyEnrollmentStore.Failure.MISMATCH),
                store.reconcile(operation, differentNonce.creationNonce))
            assertTrue(before.contentEquals(file.readBytes()))
        } finally {
            removeDatabase(replacement)
        }
    }

    @Test
    fun callerDiesAfterBindCommitBeforeReturnAndExactRetainedWitnessStillReconciles() = runTest {
        val preparing = prepareFixture()
        val witness = acceptance(preparing)
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        var returned = false
        val interrupted = fault { if (it == NotificationReplyEnrollmentStore.CommitPoint.BIND) {
            committed.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        } }
        val caller = launch(Dispatchers.Default) {
            interrupted.bind(preparing, witness)
            returned = true
        }
        try {
            assertTrue(committed.await(10, TimeUnit.SECONDS))
            caller.cancel()
        } finally {
            release.countDown()
        }
        caller.join()
        assertTrue(caller.isCancelled)
        assertFalse(returned)
        val observed = store.reconcile(operation) as NotificationReplyEnrollmentStore.EnrollmentResult.Observed
        assertEquals(preparing.copy(revision = 2, state = NotificationReplyEnrollmentStore.Phase.BOUND,
            witnessId = witness.witnessId), observed.receipt)
    }

    @Test
    fun interruptionsDuringAllocationAndPrepareLeaveUnattributedPathNotFalseReceiptOrFreshRetry() = runTest {
        for (point in listOf(
            NotificationReplyEnrollmentStore.CrashPoint.ALLOCATED,
            NotificationReplyEnrollmentStore.CrashPoint.JOURNAL_CREATED,
            NotificationReplyEnrollmentStore.CrashPoint.PREPARE_WRITTEN,
        )) {
            val interrupted = NotificationReplyEnrollmentStore(file, limits, 30_000, issuance, limits, failAt = point)
            assertEquals(notRetained(NotificationReplyEnrollmentStore.Failure.STORAGE_UNAVAILABLE),
                interrupted.prepare(operation))
            assertTrue(file.exists())
            assertEquals(indeterminate(NotificationReplyEnrollmentStore.Failure.EXISTING_UNATTRIBUTED_STORAGE),
                store.reconcile(operation))
            assertEquals(notRetained(NotificationReplyEnrollmentStore.Failure.EXISTING_UNATTRIBUTED_STORAGE),
                store.prepare(operation))
            cleanup()
        }
    }

    @Test
    fun interruptionsBeforeBindAndCopyCommitRollbackExactPriorReceiptsAndText() = runTest {
        val preparing = prepareFixture()
        val bindInterrupted = NotificationReplyEnrollmentStore(
            file, limits, 30_000, issuance, limits,
            failAt = NotificationReplyEnrollmentStore.CrashPoint.BIND_WRITTEN)
        assertEquals(notRetained(NotificationReplyEnrollmentStore.Failure.STORAGE_UNAVAILABLE),
            bindInterrupted.bind(preparing, acceptance(preparing)))
        assertEquals(NotificationReplyEnrollmentStore.EnrollmentResult.Observed(preparing),
            store.reconcile(operation, preparing.creationNonce))
        val bound = bindFixture(preparing)
        val first = store.retainUnbound(bound, UUID.randomUUID(), text) as NotificationReplyEnrollmentStore.TextResult.Durable
        val copyInterrupted = NotificationReplyEnrollmentStore(
            file, limits, 30_000, issuance, limits,
            failAt = NotificationReplyEnrollmentStore.CrashPoint.TEXT_WRITTEN)
        assertEquals(NotificationReplyEnrollmentStore.TextResult.NotRetained(
            NotificationReplyEnrollmentStore.Failure.STORAGE_UNAVAILABLE),
            copyInterrupted.retainUnbound(bound, UUID.randomUUID(), "not retained"))
        assertEquals(listOf(first.record), inventory(bound))
    }

    @Test
    fun postBindCommitLostReturnReconcilesExactNonceAndWitnessWithoutNewGeneration() = runTest {
        val preparing = prepareFixture()
        val witness = acceptance(preparing)
        val faulting = fault { if (it == NotificationReplyEnrollmentStore.CommitPoint.BIND) {
            throw SQLiteException("lost bind return")
        } }
        val result = faulting.bind(preparing, witness) as NotificationReplyEnrollmentStore.EnrollmentResult.AlreadyBound
        assertEquals(witness.witnessId, result.receipt.witnessId)
        assertEquals(preparing.creationNonce, result.receipt.creationNonce)
        assertEquals(NotificationReplyEnrollmentStore.EnrollmentResult.AlreadyBound(result.receipt),
            store.bind(preparing, witness))
    }

    @Test
    fun storageLostAfterPrepareOrBindCommitIsIndeterminateAndNeverRecreatedByReconcile() = runTest {
        val afterPrepare = fault {
            cleanup()
            throw SQLiteException("lost storage")
        }
        assertEquals(indeterminate(NotificationReplyEnrollmentStore.Failure.MISSING_STORAGE),
            afterPrepare.prepare(operation))
        assertEquals(indeterminate(NotificationReplyEnrollmentStore.Failure.MISSING_STORAGE),
            store.reconcile(operation))
        assertFalse(file.exists())
        val preparing = prepareFixture()
        val afterBind = fault { if (it == NotificationReplyEnrollmentStore.CommitPoint.BIND) {
            cleanup()
            throw SQLiteException("lost storage")
        } }
        assertEquals(indeterminate(NotificationReplyEnrollmentStore.Failure.MISSING_STORAGE),
            afterBind.bind(preparing, acceptance(preparing)))
        assertEquals(indeterminate(NotificationReplyEnrollmentStore.Failure.MISSING_STORAGE),
            store.reconcile(operation, preparing.creationNonce))
        assertFalse(file.exists())
    }

    @Test
    fun restartReconciliationPreservesCorruptAndUnavailableObservationWithoutRecreatingStorage() = runTest {
        prepareFixture()
        cleanup()
        val corrupt = byteArrayOf(1, 2, 3, 4)
        file.writeBytes(corrupt)
        assertEquals(indeterminate(NotificationReplyEnrollmentStore.Failure.CORRUPT_STORAGE),
            store.reconcile(operation))
        assertTrue(corrupt.contentEquals(file.readBytes()))
        cleanup()
        assertTrue(file.mkdir())
        assertEquals(indeterminate(NotificationReplyEnrollmentStore.Failure.STORAGE_UNAVAILABLE),
            store.reconcile(operation))
        assertTrue(file.isDirectory)
        assertTrue(requireNotNull(file.list()).isEmpty())
        assertEquals(notRetained(NotificationReplyEnrollmentStore.Failure.MISMATCH),
            store.reconcile(operation, UUID.randomUUID()))
        assertEquals(notRetained(NotificationReplyEnrollmentStore.Failure.INVALID_LIMITS),
            NotificationReplyEnrollmentStore(file, limits, 0, issuance, limits).reconcile(operation))
        assertTrue(file.isDirectory)
    }

    @Test
    fun replacementSameOperationAndStoreButDifferentNonceCannotSatisfyLostPrepareReturn() = runTest {
        val replacement = File(context.cacheDir, "reply-enrollment-replace-${UUID.randomUUID()}.db")
        try {
            val other = NotificationReplyEnrollmentStore(replacement, limits, 30_000, issuance, limits)
            val otherOperation = operation.copy(creationNonce = UUID.randomUUID())
            val otherReceipt = (other.prepare(otherOperation) as NotificationReplyEnrollmentStore.EnrollmentResult.Preparing).receipt
            val faulting = fault {
                cleanup()
                replacement.copyTo(file)
                throw SQLiteException("replacement")
            }
            assertEquals(indeterminate(NotificationReplyEnrollmentStore.Failure.MISMATCH),
                faulting.prepare(operation))
            assertEquals(NotificationReplyEnrollmentStore.EnrollmentResult.Observed(otherReceipt),
                store.reconcile(otherOperation))
            assertEquals(indeterminate(NotificationReplyEnrollmentStore.Failure.MISMATCH),
                store.reconcile(operation))
            assertEquals(notRetained(NotificationReplyEnrollmentStore.Failure.MISMATCH),
                store.reconcile(operation, UUID.randomUUID()))
        } finally {
            removeDatabase(replacement)
        }
    }

    @Test
    fun restoredPreparingSnapshotCannotBeUsedWithBoundReceiptAndStorageDoesNotClaimAntirollback() = runTest {
        val preparing = prepareFixture()
        val snapshot = File(context.cacheDir, "reply-enrollment-snapshot-${UUID.randomUUID()}.db")
        try {
            file.copyTo(snapshot)
            val bound = bindFixture(preparing)
            cleanup()
            snapshot.copyTo(file)
            assertEquals(NotificationReplyEnrollmentStore.EnrollmentResult.Observed(preparing),
                store.reconcile(operation, preparing.creationNonce))
            assertEquals(NotificationReplyStagingStore.IssuanceResult.Rejected(
                NotificationReplyStagingStore.Failure.ENROLLMENT_NOT_BOUND),
                store.boundJournal(bound).registerIssued(operation.storeId, proposal))
            assertEquals(NotificationReplyEnrollmentStore.TextResult.NotRetained(
                NotificationReplyEnrollmentStore.Failure.NOT_BOUND),
                store.retainUnbound(bound, UUID.randomUUID(), text))
            // A joint rollback to matching BOUND witness+DB needs external antirollback policy;
            // these local receipts intentionally make no such claim.
        } finally {
            removeDatabase(snapshot)
        }
    }

    @Test
    fun jointLocalLossRemainsMissingAndExternalOwnerMustNotInventFreshnessOrReplacementIds() = runTest {
        val bound = bindFixture(prepareFixture())
        cleanup()
        assertEquals(indeterminate(NotificationReplyEnrollmentStore.Failure.MISSING_STORAGE),
            store.reconcile(operation, bound.creationNonce))
        assertEquals(NotificationReplyStagingStore.StageResult.Rejected(
            NotificationReplyStagingStore.Failure.MISSING_STORAGE),
            store.boundJournal(bound).admitIssued(operation.storeId, proposal.token, text))
        assertFalse(file.exists())
    }

    @Test
    fun concurrentSameOperationCreationConvergesAndDifferentOperationNeverRebinds() = runTest {
        val results = (0 until 8).map { async(Dispatchers.Default) { store.prepare(operation) } }.awaitAll()
        val first = results.filterIsInstance<NotificationReplyEnrollmentStore.EnrollmentResult.Preparing>().single().receipt
        assertEquals(NotificationReplyEnrollmentStore.EnrollmentResult.Observed(first),
            store.reconcile(operation, first.creationNonce))
        assertTrue(results.all {
            it is NotificationReplyEnrollmentStore.EnrollmentResult.Preparing ||
                it == NotificationReplyEnrollmentStore.EnrollmentResult.AlreadyPreparing(first) ||
                it is NotificationReplyEnrollmentStore.EnrollmentResult.NotRetained
        })
        assertEquals(notRetained(NotificationReplyEnrollmentStore.Failure.MISMATCH),
            store.prepare(operation.copy(operationId = UUID.randomUUID())))
    }

    @Test
    fun concurrentWitnessAcceptanceHasExactlyOneWinnerAndCannotOverwriteReceipt() = runTest {
        val preparing = prepareFixture()
        val witnesses = (0 until 8).map { acceptance(preparing) }
        val results = witnesses.map { witness ->
            async(Dispatchers.Default) { store.bind(preparing, witness) }
        }.awaitAll()
        val winner = results.filterIsInstance<NotificationReplyEnrollmentStore.EnrollmentResult.Bound>().single().receipt
        assertEquals(7, results.count { it == notRetained(NotificationReplyEnrollmentStore.Failure.MISMATCH) })
        assertEquals(NotificationReplyEnrollmentStore.EnrollmentResult.Observed(winner),
            store.reconcile(operation, winner.creationNonce))
    }

    @Test
    fun ignoredBindWriteCannotReportBoundAndExactPreparingReceiptRemainsRecoverable() = runTest {
        val preparing = prepareFixture()
        database { it.execSQL(
            "CREATE TRIGGER ignore_bound BEFORE UPDATE ON reply_enrollment BEGIN SELECT RAISE(IGNORE); END") }
        assertEquals(notRetained(NotificationReplyEnrollmentStore.Failure.CORRUPT_STORAGE),
            store.bind(preparing, acceptance(preparing)))
        database { it.execSQL("DROP TRIGGER ignore_bound") }
        assertEquals(NotificationReplyEnrollmentStore.EnrollmentResult.Observed(preparing),
            store.reconcile(operation, preparing.creationNonce))
    }

    @Test
    fun exactUnboundUtf16DuplicatesAndConflictsRetainOnlyCopyIdentitiesAcrossReopen() = runTest {
        val bound = bindFixture(prepareFixture())
        val submission = UUID.randomUUID()
        val first = store.retainUnbound(bound, submission, text) as NotificationReplyEnrollmentStore.TextResult.Durable
        assertEquals(NotificationReplyEnrollmentStore.TextResult.AlreadyRetained(first.record),
            store.retainUnbound(bound, submission, text))
        val changed = store.retainUnbound(bound, submission, "$text ") as
            NotificationReplyEnrollmentStore.TextResult.Durable
        assertNotEquals(first.record.recordId, changed.record.recordId)
        assertEquals(listOf(text, "$text "), inventory(bound).map { it.exactText })
        assertEquals(listOf(submission, submission), inventory(bound).map { it.submissionId })
        assertEquals(listOf(1L, 2L), inventory(bound).map { it.sequence })
        assertEquals(NotificationReplyEnrollmentStore.TextResult.AlreadyRetained(changed.record),
            store.retainUnbound(bound, submission, "$text "))
        assertTrue(store.boundJournal(bound).read(operation.storeId) is NotificationReplyStagingStore.ReadResult.Inventory)
        assertTrue((store.boundJournal(bound).read(operation.storeId) as
            NotificationReplyStagingStore.ReadResult.Inventory).entries.isEmpty())
    }

    @Test
    fun unknownIssuedTokenNeverStagesButCanBeExplicitlyRetainedThroughNoRouteCopySurface() = runTest {
        val bound = bindFixture(prepareFixture())
        assertEquals(NotificationReplyStagingStore.StageResult.Rejected(
            NotificationReplyStagingStore.Failure.ISSUED_TOKEN_NOT_FOUND),
            store.boundJournal(bound).admitIssued(operation.storeId, proposal.token, text))
        assertTrue(inventory(bound).isEmpty())
        assertTrue(store.retainUnbound(bound, UUID.randomUUID(), text) is NotificationReplyEnrollmentStore.TextResult.Durable)
        assertEquals(text, inventory(bound).single().exactText)
    }

    @Test
    fun copyCapacityRejectsWithoutEvictionAndExactDuplicateRemainsRetainedWhenFull() = runTest {
        val bound = bindFixture(prepareFixture())
        val retained = (0 until 3).map {
            (store.retainUnbound(bound, UUID.randomUUID(), "$text$it") as
                NotificationReplyEnrollmentStore.TextResult.Durable).record
        }
        assertEquals(NotificationReplyEnrollmentStore.TextResult.NotRetained(
            NotificationReplyEnrollmentStore.Failure.RECORD_CAPACITY),
            store.retainUnbound(bound, UUID.randomUUID(), "not retained"))
        assertEquals(retained, inventory(bound))
        assertEquals(NotificationReplyEnrollmentStore.TextResult.AlreadyRetained(retained.first()),
            store.retainUnbound(bound, retained.first().submissionId, retained.first().exactText))
        assertEquals(NotificationReplyEnrollmentStore.TextResult.NotRetained(
            NotificationReplyEnrollmentStore.Failure.PRIVATE_DATA_CAPACITY),
            store.retainUnbound(bound, UUID.randomUUID(), "x".repeat(10_000)))
    }

    @Test
    fun realSerializedCopyPerRecordAndAggregateByteBoundaryIsExact() = runTest {
        var bound = bindFixture(prepareFixture())
        store.retainUnbound(bound, UUID.randomUUID(), text)
        val measured = database { db ->
            db.rawQuery("SELECT content_bytes FROM reply_unbound_text", null).use {
                assertTrue(it.moveToFirst())
                it.getLong(0)
            }
        }
        cleanup()
        val exact = NotificationReplyEnrollmentStore(
            file, limits, 30_000, issuance, NotificationReplyLimits(3, measured, measured))
        val preparing = (exact.prepare(operation) as NotificationReplyEnrollmentStore.EnrollmentResult.Preparing).receipt
        bound = (exact.bind(preparing, acceptance(preparing)) as NotificationReplyEnrollmentStore.EnrollmentResult.Bound).receipt
        assertTrue(exact.retainUnbound(bound, UUID.randomUUID(), text) is
            NotificationReplyEnrollmentStore.TextResult.Durable)
        assertEquals(NotificationReplyEnrollmentStore.TextResult.NotRetained(
            NotificationReplyEnrollmentStore.Failure.PRIVATE_DATA_CAPACITY),
            exact.retainUnbound(bound, UUID.randomUUID(), text))
        cleanup()
        val tooSmall = NotificationReplyEnrollmentStore(
            file, limits, 30_000, issuance, NotificationReplyLimits(3, measured - 1, measured * 2))
        val smallPreparing = (tooSmall.prepare(operation) as
            NotificationReplyEnrollmentStore.EnrollmentResult.Preparing).receipt
        val smallBound = (tooSmall.bind(smallPreparing, acceptance(smallPreparing)) as
            NotificationReplyEnrollmentStore.EnrollmentResult.Bound).receipt
        assertEquals(NotificationReplyEnrollmentStore.TextResult.NotRetained(
            NotificationReplyEnrollmentStore.Failure.PRIVATE_DATA_CAPACITY),
            tooSmall.retainUnbound(smallBound, UUID.randomUUID(), text))
    }

    @Test
    fun concurrentCopyDuplicatesConflictsAndCapacityAreSerializedByActualDatabase() = runTest {
        val bound = bindFixture(prepareFixture())
        val submission = UUID.randomUUID()
        val results = (0 until 8).map { i ->
            async(Dispatchers.Default) { store.retainUnbound(bound, submission, if (i % 2 == 0) text else "$text ") }
        }.awaitAll()
        assertEquals(2, results.count { it is NotificationReplyEnrollmentStore.TextResult.Durable })
        assertEquals(setOf(text, "$text "), inventory(bound).map { it.exactText }.toSet())
        assertTrue(results.all { it is NotificationReplyEnrollmentStore.TextResult.Durable ||
            it is NotificationReplyEnrollmentStore.TextResult.AlreadyRetained })
        val additions = (0 until 8).map {
            async(Dispatchers.Default) { store.retainUnbound(bound, UUID.randomUUID(), "third") }
        }.awaitAll()
        assertEquals(1, additions.count { it is NotificationReplyEnrollmentStore.TextResult.Durable })
        assertEquals(3, inventory(bound).size)
    }

    @Test
    fun ignoredCopyInsertOrAccountingCannotMasqueradeAsRetention() = runTest {
        val bound = bindFixture(prepareFixture())
        for (table in listOf("reply_unbound_text", "reply_unbound_policy")) {
            val operation = if (table == "reply_unbound_text") "INSERT" else "UPDATE"
            database { it.execSQL("CREATE TRIGGER ignore_copy BEFORE $operation ON $table BEGIN SELECT RAISE(IGNORE); END") }
            assertEquals(NotificationReplyEnrollmentStore.TextResult.NotRetained(
                NotificationReplyEnrollmentStore.Failure.CORRUPT_STORAGE),
                store.retainUnbound(bound, UUID.randomUUID(), text))
            database { it.execSQL("DROP TRIGGER ignore_copy") }
            assertTrue(inventory(bound).isEmpty())
        }
    }

    @Test
    fun abortedCopyWriteDoesNotRetainTextAndPriorInputsSurvive() = runTest {
        val bound = bindFixture(prepareFixture())
        val first = store.retainUnbound(bound, UUID.randomUUID(), text) as NotificationReplyEnrollmentStore.TextResult.Durable
        database { it.execSQL(
            "CREATE TRIGGER abort_copy BEFORE INSERT ON reply_unbound_text BEGIN SELECT RAISE(ABORT,'test'); END") }
        assertEquals(NotificationReplyEnrollmentStore.TextResult.NotRetained(
            NotificationReplyEnrollmentStore.Failure.STORAGE_UNAVAILABLE),
            store.retainUnbound(bound, UUID.randomUUID(), "not retained"))
        assertEquals(listOf(first.record), inventory(bound))
    }

    @Test
    fun lostCopyReturnReconcilesStableBytesButMissingStorageIsIndeterminate() = runTest {
        val bound = bindFixture(prepareFixture())
        val submission = UUID.randomUUID()
        val faulting = fault { if (it == NotificationReplyEnrollmentStore.CommitPoint.UNBOUND_TEXT) {
            throw SQLiteException("lost copy return")
        } }
        val retained = faulting.retainUnbound(bound, submission, text) as
            NotificationReplyEnrollmentStore.TextResult.AlreadyRetained
        assertEquals(retained.record, inventory(bound).single())
        val missing = fault { if (it == NotificationReplyEnrollmentStore.CommitPoint.UNBOUND_TEXT) {
            cleanup()
            throw SQLiteException("lost copy storage")
        } }
        assertTrue(missing.retainUnbound(bound, UUID.randomUUID(), "uncertain") is
            NotificationReplyEnrollmentStore.TextResult.Indeterminate)
        assertFalse(file.exists())
    }

    @Test
    fun changedReceiptAndReplacedStoreCannotSatisfyCopyOrBoundJournalHandle() = runTest {
        val bound = bindFixture(prepareFixture())
        val staleHandle = store.boundJournal(bound)
        cleanup()
        val otherOperation = operation.copy(creationNonce = UUID.randomUUID())
        val otherPreparing = (store.prepare(otherOperation) as
            NotificationReplyEnrollmentStore.EnrollmentResult.Preparing).receipt
        val otherBound = bindFixture(otherPreparing)
        assertNotEquals(bound.creationNonce, otherBound.creationNonce)
        assertEquals(NotificationReplyEnrollmentStore.TextResult.NotRetained(
            NotificationReplyEnrollmentStore.Failure.MISMATCH),
            store.retainUnbound(bound, UUID.randomUUID(), text))
        assertEquals(NotificationReplyStagingStore.IssuanceResult.Rejected(
            NotificationReplyStagingStore.Failure.ENROLLMENT_MISMATCH),
            staleHandle.registerIssued(operation.storeId, proposal))
        assertTrue(inventory(otherBound).isEmpty())
    }

    @Test
    fun replacementWithIdenticalCopySubmissionCannotReconcileLostReturnAcrossCreationNonces() = runTest {
        val bound = bindFixture(prepareFixture())
        val submission = UUID.randomUUID()
        val replacement = File(context.cacheDir, "reply-copy-replace-${UUID.randomUUID()}.db")
        try {
            val other = NotificationReplyEnrollmentStore(replacement, limits, 30_000, issuance, limits)
            val otherOperation = operation.copy(creationNonce = UUID.randomUUID())
            val preparing = (other.prepare(otherOperation) as NotificationReplyEnrollmentStore.EnrollmentResult.Preparing).receipt
            val otherBound = (other.bind(preparing, acceptance(preparing)) as
                NotificationReplyEnrollmentStore.EnrollmentResult.Bound).receipt
            assertNotEquals(bound.creationNonce, otherBound.creationNonce)
            assertTrue(other.retainUnbound(otherBound, submission, text) is
                NotificationReplyEnrollmentStore.TextResult.Durable)
            val faulting = fault { if (it == NotificationReplyEnrollmentStore.CommitPoint.UNBOUND_TEXT) {
                cleanup()
                replacement.copyTo(file)
                throw SQLiteException("copy replacement")
            } }
            assertTrue(faulting.retainUnbound(bound, submission, text) is
                NotificationReplyEnrollmentStore.TextResult.Indeterminate)
            assertEquals(NotificationReplyEnrollmentStore.TextInventory.Failed(
                NotificationReplyEnrollmentStore.Failure.MISMATCH), store.readUnbound(bound))
        } finally {
            removeDatabase(replacement)
        }
    }

    @Test
    fun oldJournalIsUntouchedAndOnlyNoRouteCopyProjectionIsNeededForSurvivingText() = runTest {
        val oldFile = File(context.cacheDir, "reply-old-${UUID.randomUUID()}.db")
        try {
            val old = NotificationReplyStagingStore(oldFile, limits)
            assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, old.initializeNew())
            val staged = old.stage(proposal.post, proposal.payload, text) as NotificationReplyStagingStore.StageResult.Staged
            val before = oldFile.readBytes()
            val bound = bindFixture(prepareFixture())
            val copies = old.readCopyOnly() as NotificationReplyStagingStore.CopyInventory.Retained
            assertEquals(listOf(NotificationReplyStagingStore.CopyText(
                staged.identity.storeId, staged.identity.recordId, staged.identity.sequence, text)), copies.records)
            assertTrue(before.contentEquals(oldFile.readBytes()))
            assertTrue(store.retainUnbound(bound, UUID.randomUUID(), copies.records.single().exactText) is
                NotificationReplyEnrollmentStore.TextResult.Durable)
            assertEquals(NotificationReplyStagingStore.StageResult.Rejected(
                NotificationReplyStagingStore.Failure.ISSUED_TOKEN_NOT_FOUND),
                store.boundJournal(bound).admitIssued(operation.storeId, proposal.token, text))
            assertTrue(before.contentEquals(oldFile.readBytes()))
        } finally {
            removeDatabase(oldFile)
        }
    }

    @Test
    fun invalidBudgetsUnavailablePathsAndTamperedEnrollmentAreExplicitFailures() = runTest {
        val invalid = NotificationReplyEnrollmentStore(file, limits, 0, issuance, limits)
        assertEquals(notRetained(NotificationReplyEnrollmentStore.Failure.INVALID_LIMITS), invalid.prepare(operation))
        assertFalse(file.exists())
        assertTrue(file.mkdir())
        assertTrue(store.prepare(operation) is NotificationReplyEnrollmentStore.EnrollmentResult.NotRetained)
        cleanup()
        val preparing = prepareFixture()
        database { it.execSQL("UPDATE reply_enrollment SET nonce=?", arrayOf(UUID.randomUUID().toString())) }
        assertEquals(indeterminate(NotificationReplyEnrollmentStore.Failure.CORRUPT_STORAGE),
            store.reconcile(operation, preparing.creationNonce))
    }

    @Test
    fun malformedCopyBytesCountsOrBudgetsNeverYieldPartialInventory() = runTest {
        var bound = bindFixture(prepareFixture())
        store.retainUnbound(bound, UUID.randomUUID(), text)
        database { it.execSQL("UPDATE reply_unbound_text SET exact_text=X'00'") }
        assertEquals(NotificationReplyEnrollmentStore.TextInventory.Failed(
            NotificationReplyEnrollmentStore.Failure.CORRUPT_STORAGE), store.readUnbound(bound))
        cleanup()
        bound = bindFixture(prepareFixture())
        database { it.execSQL("UPDATE reply_unbound_policy SET max_records=4294967299") }
        assertEquals(NotificationReplyEnrollmentStore.TextInventory.Failed(
            NotificationReplyEnrollmentStore.Failure.CORRUPT_STORAGE), store.readUnbound(bound))
    }

    private suspend fun prepareFixture() =
        (store.prepare(operation) as NotificationReplyEnrollmentStore.EnrollmentResult.Preparing).receipt

    private suspend fun bindFixture(preparing: NotificationReplyEnrollmentStore.CreationReceipt) =
        (store.bind(preparing, acceptance(preparing)) as NotificationReplyEnrollmentStore.EnrollmentResult.Bound).receipt

    private fun acceptance(preparing: NotificationReplyEnrollmentStore.CreationReceipt) =
        NotificationReplyEnrollmentStore.WitnessAcceptance(preparing.operation, preparing.creationNonce, UUID.randomUUID())

    private suspend fun inventory(bound: NotificationReplyEnrollmentStore.CreationReceipt) =
        (store.readUnbound(bound) as NotificationReplyEnrollmentStore.TextInventory.Retained).records

    private fun fault(observer: (NotificationReplyEnrollmentStore.CommitPoint) -> Unit) =
        NotificationReplyEnrollmentStore(file, limits, 30_000, issuance, limits, afterCommit = observer)

    private fun notRetained(reason: NotificationReplyEnrollmentStore.Failure) =
        NotificationReplyEnrollmentStore.EnrollmentResult.NotRetained(reason)

    private fun indeterminate(reason: NotificationReplyEnrollmentStore.Failure) =
        NotificationReplyEnrollmentStore.EnrollmentResult.Indeterminate(operation, reason)

    private fun <T> database(block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use(block)

    private fun removeDatabase(target: File) {
        listOf(target, File("${target.path}-journal"), File("${target.path}-wal"), File("${target.path}-shm"))
            .filter(File::exists).forEach { check(it.delete()) }
    }
}
