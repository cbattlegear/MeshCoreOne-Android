// AndroidOnly: WP-401 Real SQLite permanent app publication attempts, not atomic Android display.
package com.meshcoreone.android.platform.notifications.messaging

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class NotificationReplyPublicationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val file by lazy { File(temporary.root, "reply-publication-${UUID.randomUUID()}.db") }
    private var limits = NotificationReplyLimits(2, 10_000, 20_000)
    private var custodyBudget = 30_000L
    private var policy = NotificationReplyStagingStore.IssuanceLimits(4, 10_000, 40_000)
    private val copies = NotificationReplyLimits(2, 10_000, 20_000)
    private val operation = NotificationReplyEnrollmentStore.EnrollmentOperation(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    private lateinit var receipt: NotificationReplyEnrollmentStore.CreationReceipt
    private val enrollment get() = NotificationReplyEnrollmentStore(file, limits, custodyBudget, policy, copies)
    private val journal get() = enrollment.boundJournal(receipt)
    private val attempt = NotificationReplyStagingStore.PublicationAttempt(UUID.randomUUID(), UUID.randomUUID())
    private val action = NotificationReplyStagingStore.IssuedAction(
        UUID.randomUUID(), NotificationReplyPost(1, UUID.randomUUID(), "REPLY"), NotificationId("logical"),
        NotificationPayload.DirectMessage(EntityKey(RadioId(UUID(0, 1)), UUID(0, 2)), UUID(0, 3)),
    )
    private val raw = " \t\u0000e\u0301\uD800\uDC00\uD800 \r\n "

    @After
    fun cleanup() {
        SQLiteDatabase.deleteDatabase(file)
        assertFalse(file.exists())
    }

    @Test
    fun explicitEmptyGenerationOptInPreservesReceiptAndIndependentCopyInventory() = runTest {
        provision(enable = false)
        val copy = enrollment.retainUnbound(receipt, UUID.randomUUID(), raw)
        assertEquals(ready, journal.initializePublicationAttempts(operation.storeId))
        assertEquals(ready, journal.initializePublicationAttempts(operation.storeId))
        assertEquals(6L, scalar("PRAGMA user_version"))
        assertEquals(NotificationReplyEnrollmentStore.EnrollmentResult.Observed(receipt), enrollment.reconcile(operation))
        assertEquals((copy as NotificationReplyEnrollmentStore.TextResult.Durable).record,
            (enrollment.readUnbound(receipt) as NotificationReplyEnrollmentStore.TextInventory.Retained).records.single())
    }

    @Test
    fun possiblePreSchemaSixIssuedTokenPreventsMigrationAndIsNeverAdoptedAsPublication() = runTest {
        provision(enable = false)
        val old = journal.registerIssued(operation.storeId, action) as NotificationReplyStagingStore.IssuanceResult.Reserved
        assertEquals(initializeFailed(NotificationReplyStagingStore.Failure.PUBLICATION_REQUIRES_EMPTY_GENERATION),
            journal.initializePublicationAttempts(operation.storeId))
        assertEquals(5L, scalar("PRAGMA user_version"))
        assertEquals(NotificationReplyStagingStore.IssuanceResult.AlreadyReserved(old.record),
            journal.registerIssued(operation.storeId, action))
        assertEquals(rejected(NotificationReplyStagingStore.Failure.PUBLICATION_NOT_INITIALIZED), claim())
        assertEquals(1L, scalar("SELECT COUNT(*) FROM reply_issued_actions"))
    }

    @Test
    fun legacyReplyWithoutIssuedTokenAlsoPreventsPublicationOptIn() = runTest {
        provision(enable = false)
        assertTrue(journal.stage(action.post, action.payload, raw) is NotificationReplyStagingStore.StageResult.Staged)
        assertEquals(initializeFailed(NotificationReplyStagingStore.Failure.PUBLICATION_REQUIRES_EMPTY_GENERATION),
            journal.initializePublicationAttempts(operation.storeId))
        assertEquals(raw, inventory().single().exactText)
    }

    @Test
    fun missingPreparingWrongGenerationAndMissingCapacityOptInNeverCreateOrAdopt() = runTest {
        val direct = NotificationReplyStagingStore(file, limits)
        assertEquals(initializeFailed(NotificationReplyStagingStore.Failure.MISSING_STORAGE),
            direct.initializePublicationAttempts(operation.storeId))
        assertFalse(file.exists())
        val preparing = (enrollment.prepare(operation) as
            NotificationReplyEnrollmentStore.EnrollmentResult.Preparing).receipt
        assertEquals(initializeFailed(NotificationReplyStagingStore.Failure.ENROLLMENT_NOT_BOUND),
            enrollment.boundJournal(preparing).initializePublicationAttempts(operation.storeId))
        receipt = bind(preparing)
        assertEquals(initializeFailed(NotificationReplyStagingStore.Failure.REPLY_RESERVATIONS_NOT_INITIALIZED),
            journal.initializePublicationAttempts(operation.storeId))
        assertEquals(ready, journal.initializeReplyReservations(operation.storeId))
        assertEquals(initializeFailed(NotificationReplyStagingStore.Failure.IDENTITY_MISMATCH),
            journal.initializePublicationAttempts(UUID.randomUUID()))
        assertEquals(initializeFailed(NotificationReplyStagingStore.Failure.ENROLLMENT_MISMATCH),
            direct.initializePublicationAttempts(operation.storeId))
    }

    @Test
    fun onlyFirstAtomicReservationAndAttemptIsNewEveryOwnerReplayIsObservation() = runTest {
        provision()
        val first = claim() as NotificationReplyStagingStore.PublicationResult.NewlyClaimed
        assertEquals(action, first.record.action)
        assertEquals(attempt, first.record.publicationAttempt)
        assertEquals(raw.length, first.record.maxReplyCodeUnits)
        assertEquals(observed(first.record), claim())
        assertEquals(observed(first.record), claim(owner = newAttempt()))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM reply_issued_actions"))
        assertTrue(inventory().isEmpty())
        // A permanent attempt does not prove that the caller ever created or posted a notification.
        assertEquals(observed(first.record), claim())
    }

    @Test
    fun schemaSixDisablesRegistryOnlyAndBothLegacyAdmissionPathsWithoutFallback() = runTest {
        provision()
        assertEquals(NotificationReplyStagingStore.IssuanceResult.Rejected(
            NotificationReplyStagingStore.Failure.PUBLICATION_REQUIRED),
            journal.registerIssued(operation.storeId, action))
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.PUBLICATION_REQUIRED),
            journal.stage(action.post, action.payload, raw))
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.PUBLICATION_REQUIRED),
            journal.admit(operation.storeId, action.post, action.payload, raw))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM reply_issued_actions"))
        assertTrue(inventory().isEmpty())
    }

    @Test
    fun heldUnclaimedReservationCannotBeConsumedOrUpgradedToPublicationAttempt() = runTest {
        provision()
        assertTrue(journal.reserveIssuedReply(operation.storeId, action, raw.length) is
            NotificationReplyStagingStore.IssuanceResult.Reserved)
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.ISSUED_TOKEN_UNCLAIMED),
            journal.admitIssued(operation.storeId, action.token, raw))
        assertEquals(rejected(NotificationReplyStagingStore.Failure.ISSUANCE_CONFLICT), claim())
        assertTrue(inventory().isEmpty())
        assertEquals(1L, scalar("SELECT COUNT(*) FROM reply_issued_actions"))
    }

    @Test
    fun permanentAttemptAllowsExactAdmissionWithoutInventingPostAcknowledgment() = runTest {
        provision()
        val first = claim() as NotificationReplyStagingStore.PublicationResult.NewlyClaimed
        val retained = journal.admitIssued(operation.storeId, action.token, raw) as
            NotificationReplyStagingStore.StageResult.Staged
        assertEquals(NotificationReplyStagingStore.StageResult.AlreadyStaged(retained.identity, retained.outgoingMessageId),
            journal.admitIssued(operation.storeId, action.token, raw))
        assertEquals(observed(first.record), claim(owner = newAttempt()))
        assertEquals(raw, inventory().single().exactText)
        assertEquals(attempt, first.record.publicationAttempt)
    }

    @Test
    fun consumedReservationCannotBeDowngradedOrReclaimedAndChangedTextRemainsCopyOnly() = runTest {
        provision()
        val first = claim() as NotificationReplyStagingStore.PublicationResult.NewlyClaimed
        journal.admitIssued(operation.storeId, action.token, raw)
        assertEquals(observed(first.record), claim())
        assertEquals(NotificationReplyStagingStore.IssuanceResult.Rejected(
            NotificationReplyStagingStore.Failure.ISSUANCE_CONFLICT),
            journal.reserveIssuedReply(operation.storeId, action, raw.length))
        assertTrue(journal.admitIssued(operation.storeId, action.token, "changed") is
            NotificationReplyStagingStore.StageResult.ConflictRetained)
        assertTrue(inventory().last() is NotificationReplyStagingStore.RecoveryEntry.UnboundConflict)
        assertEquals(observed(first.record), claim(owner = newAttempt()))
    }

    @Test
    fun changedActionBoundAndCrossTokenAttemptReuseNeverOverwriteFirstAttempt() = runTest {
        provision()
        val first = claim() as NotificationReplyStagingStore.PublicationResult.NewlyClaimed
        assertEquals(rejected(NotificationReplyStagingStore.Failure.ISSUANCE_CONFLICT), claim(bound = raw.length + 1))
        assertEquals(rejected(NotificationReplyStagingStore.Failure.ISSUANCE_CONFLICT),
            claim(proposal = action.copy(logicalNotificationId = NotificationId("replacement"))))
        assertEquals(rejected(NotificationReplyStagingStore.Failure.ISSUANCE_CONFLICT), claim(proposal = otherAction()))
        assertEquals(observed(first.record), claim())
        assertEquals(1L, scalar("SELECT COUNT(*) FROM reply_issued_actions"))
    }

    @Test
    fun quotaOrRegistryFailureRollsBackBothIssuanceAndAttemptWithoutPermission() = runTest {
        custodyBudget = 1
        provision()
        assertEquals(rejected(NotificationReplyStagingStore.Failure.LIFECYCLE_CAPACITY), claim())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM reply_issued_actions"))
        cleanup()
        custodyBudget = 30_000
        policy = policy.copy(maxRecords = 1)
        provision()
        claim()
        assertEquals(rejected(NotificationReplyStagingStore.Failure.ISSUANCE_RECORD_CAPACITY),
            claim(proposal = otherAction(), owner = newAttempt()))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM reply_issued_actions"))
    }

    @Test
    fun publicationAttemptBytesAreWithinExplicitIssuancePrivateDataPolicy() = runTest {
        policy = NotificationReplyStagingStore.IssuanceLimits(4, 500, 2_000)
        provision()
        assertEquals(rejected(NotificationReplyStagingStore.Failure.ISSUANCE_PRIVATE_DATA_CAPACITY), claim())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM reply_issued_actions"))
    }

    @Test
    fun claimedQuotaProtectsLastRowAgainstHeldAndLegacyInputs() = runTest {
        limits = limits.copy(maxRecords = 1)
        provision()
        claim()
        assertEquals(NotificationReplyStagingStore.IssuanceResult.Rejected(
            NotificationReplyStagingStore.Failure.RECORD_CAPACITY),
            journal.reserveIssuedReply(operation.storeId, otherAction(), raw.length))
        assertTrue(journal.admitIssued(operation.storeId, action.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
        assertEquals(raw, inventory().single().exactText)
    }

    @Test
    fun exactUtf16AndOversizeRejectionDoNotChangeOrConsumeAttemptIdentity() = runTest {
        provision()
        val first = claim() as NotificationReplyStagingStore.PublicationResult.NewlyClaimed
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.REPLY_TEXT_BOUND_EXCEEDED),
            journal.admitIssued(operation.storeId, action.token, raw + "\uD800"))
        assertEquals(observed(first.record), claim())
        assertTrue(journal.admitIssued(operation.storeId, action.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
        assertEquals(raw.toCharArray().toList(), inventory().single().exactText.toCharArray().toList())
    }

    @Test
    fun concurrentCompetingPublishersAcrossHandlesGetExactlyOneNewAttempt() = runTest {
        provision()
        val gate = CountDownLatch(1)
        val tasks = List(8) { async(Dispatchers.IO) {
            assertTrue(gate.await(10, TimeUnit.SECONDS))
            claim(owner = newAttempt())
        } }
        gate.countDown()
        val results = tasks.awaitAll()
        val first = results.filterIsInstance<NotificationReplyStagingStore.PublicationResult.NewlyClaimed>().single()
        assertEquals(7, results.count { it == observed(first.record) })
        assertEquals(1L, scalar("SELECT COUNT(*) FROM reply_issued_actions"))
    }

    @Test
    fun admissionRacingNewClaimEitherRejectsMissingTokenOrRetainsWithoutSecondAttempt() = runTest {
        provision()
        val gate = CountDownLatch(1)
        val publisher = async(Dispatchers.IO) {
            assertTrue(gate.await(10, TimeUnit.SECONDS))
            claim()
        }
        val receiver = async(Dispatchers.IO) {
            assertTrue(gate.await(10, TimeUnit.SECONDS))
            journal.admitIssued(operation.storeId, action.token, raw)
        }
        gate.countDown()
        val first = publisher.await() as NotificationReplyStagingStore.PublicationResult.NewlyClaimed
        val received = receiver.await()
        assertTrue(received is NotificationReplyStagingStore.StageResult.Staged ||
            received == stageRejected(NotificationReplyStagingStore.Failure.ISSUED_TOKEN_NOT_FOUND))
        assertEquals(observed(first.record), claim(owner = newAttempt()))
        val retry = journal.admitIssued(operation.storeId, action.token, raw)
        assertTrue(retry is NotificationReplyStagingStore.StageResult.Staged ||
            retry is NotificationReplyStagingStore.StageResult.AlreadyStaged)
        assertEquals(raw, inventory().single().exactText)
    }

    @Test
    fun consumptionBetweenAttemptReturnAndAppPublicationNeverGrantsCompetitor() = runTest {
        provision()
        val first = claim() as NotificationReplyStagingStore.PublicationResult.NewlyClaimed
        assertTrue(journal.admitIssued(operation.storeId, action.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
        repeat(3) { assertEquals(observed(first.record), claim(owner = newAttempt())) }
        assertEquals(raw, inventory().single().exactText)
        // The first caller could still be paused before Android IPC. Storage does not revoke or
        // order that already granted app attempt relative to Android visibility.
    }

    @Test
    fun lostCommittedReturnIsIndeterminateAndEveryRestartOnlyObservesPermanentAttempt() = runTest {
        provision()
        val faulting = NotificationReplyStagingStore(file, limits, receipt, afterIssuanceCommit = {
            throw SQLiteException("lost return after commit")
        })
        assertEquals(NotificationReplyStagingStore.PublicationResult.Indeterminate(action.token),
            faulting.reservePublicationAttempt(operation.storeId, action, raw.length, attempt))
        val observed = claim() as NotificationReplyStagingStore.PublicationResult.Observed
        assertEquals(attempt, observed.record.publicationAttempt)
        assertEquals(observed, claim(owner = newAttempt()))
        assertTrue(inventory().isEmpty())
    }

    @Test
    fun callerDeathAfterCommitBeforeReturnNeverRegrantsAndPossibleLiveInputStillAdmits() = runTest {
        provision()
        val job = launch(Dispatchers.IO) {
            NotificationReplyStagingStore(file, limits, receipt, afterIssuanceCommit = {
                throw CancellationException("caller interrupted after durable attempt")
            }).reservePublicationAttempt(operation.storeId, action, raw.length, attempt)
        }
        job.join()
        assertTrue(job.isCancelled)
        val restart = claim() as NotificationReplyStagingStore.PublicationResult.Observed
        assertEquals(attempt, restart.record.publicationAttempt)
        // No post receipt is invented; this covers admission if an original PI did escape before death.
        assertTrue(journal.admitIssued(operation.storeId, action.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
        assertEquals(restart, claim(owner = newAttempt()))
    }

    @Test
    fun ignoredAttemptRowAndAbortedRegistryWritesCannotYieldNewlyClaimed() = runTest {
        provision()
        database { it.execSQL(
            "CREATE TRIGGER ignore_issue BEFORE INSERT ON reply_issued_actions BEGIN SELECT RAISE(IGNORE); END") }
        assertEquals(rejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE), claim())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM reply_issued_actions"))
        database {
            it.execSQL("DROP TRIGGER ignore_issue")
            it.execSQL("CREATE TRIGGER reject_issue BEFORE INSERT ON reply_issued_actions BEGIN SELECT RAISE(ABORT,'refused'); END")
        }
        assertEquals(rejected(NotificationReplyStagingStore.Failure.STORAGE_UNAVAILABLE), claim())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM reply_issued_actions"))
    }

    @Test
    fun lostStoreAfterAttemptCommitIsIndeterminateAndNoOperationRecreatesIt() = runTest {
        provision()
        val faulting = NotificationReplyStagingStore(file, limits, receipt, afterIssuanceCommit = {
            SQLiteDatabase.deleteDatabase(file)
            throw SQLiteException("store lost")
        })
        assertEquals(NotificationReplyStagingStore.PublicationResult.Indeterminate(action.token),
            faulting.reservePublicationAttempt(operation.storeId, action, raw.length, attempt))
        assertEquals(rejected(NotificationReplyStagingStore.Failure.MISSING_STORAGE), claim())
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.MISSING_STORAGE),
            journal.admitIssued(operation.storeId, action.token, raw))
        assertFalse(file.exists())
    }

    @Test
    fun wrongStoreAndUnknownOldTokenCannotInheritPublicationOrRoute() = runTest {
        provision()
        assertEquals(NotificationReplyStagingStore.PublicationResult.Rejected(
            NotificationReplyStagingStore.Failure.IDENTITY_MISMATCH),
            journal.reservePublicationAttempt(UUID.randomUUID(), action, raw.length, attempt))
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.ISSUED_TOKEN_NOT_FOUND),
            journal.admitIssued(operation.storeId, UUID.randomUUID(), raw))
        assertTrue(enrollment.retainUnbound(receipt, UUID.randomUUID(), raw) is
            NotificationReplyEnrollmentStore.TextResult.Durable)
        assertTrue(inventory().isEmpty())
    }

    @Test
    fun replacedSameOperationStoreWithDifferentNonceRejectsHeldPublicationAndAdmission() = runTest {
        provision()
        claim()
        val held = journal
        cleanup()
        val replacement = operation.copy(creationNonce = UUID.randomUUID())
        val preparing = (enrollment.prepare(replacement) as
            NotificationReplyEnrollmentStore.EnrollmentResult.Preparing).receipt
        receipt = bind(preparing)
        assertEquals(ready, journal.initializeReplyReservations(operation.storeId))
        assertEquals(ready, journal.initializePublicationAttempts(operation.storeId))
        claim()
        assertEquals(rejected(NotificationReplyStagingStore.Failure.ENROLLMENT_MISMATCH),
            held.reservePublicationAttempt(operation.storeId, action, raw.length, attempt))
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.ENROLLMENT_MISMATCH),
            held.admitIssued(operation.storeId, action.token, raw))
    }

    @Test
    fun corruptAttemptOwnerOrPartialClaimNeverYieldsUsableInventoryOrNewPermission() = runTest {
        provision()
        claim()
        database { it.execSQL("UPDATE reply_issued_actions SET publication_owner_id=NULL") }
        assertEquals(rejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE), claim())
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            journal.admitIssued(operation.storeId, action.token, raw))
        assertEquals(NotificationReplyStagingStore.CopyInventory.Failed(
            NotificationReplyStagingStore.Failure.CORRUPT_STORAGE), journal.readCopyOnly())
    }

    private suspend fun provision(enable: Boolean = true) {
        val preparing = (enrollment.prepare(operation) as
            NotificationReplyEnrollmentStore.EnrollmentResult.Preparing).receipt
        receipt = bind(preparing)
        assertEquals(ready, journal.initializeReplyReservations(operation.storeId))
        if (enable) assertEquals(ready, journal.initializePublicationAttempts(operation.storeId))
    }

    private suspend fun bind(preparing: NotificationReplyEnrollmentStore.CreationReceipt) =
        (enrollment.bind(preparing, NotificationReplyEnrollmentStore.WitnessAcceptance(
            preparing.operation, preparing.creationNonce, UUID.randomUUID())) as
            NotificationReplyEnrollmentStore.EnrollmentResult.Bound).receipt

    private suspend fun claim(
        proposal: NotificationReplyStagingStore.IssuedAction = action,
        bound: Int = raw.length,
        owner: NotificationReplyStagingStore.PublicationAttempt = attempt,
    ) = journal.reservePublicationAttempt(operation.storeId, proposal, bound, owner)

    private fun newAttempt() = NotificationReplyStagingStore.PublicationAttempt(UUID.randomUUID(), UUID.randomUUID())
    private fun otherAction() = action.copy(token = UUID.randomUUID(), post = action.post.copy(postId = UUID.randomUUID()))
    private suspend fun inventory() =
        (journal.read(operation.storeId) as NotificationReplyStagingStore.ReadResult.Inventory).entries
    private fun scalar(sql: String): Long = database { db -> db.rawQuery(sql, null).use {
        assertTrue(it.moveToFirst())
        it.getLong(0)
    } }
    private fun <T> database(block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun rejected(reason: NotificationReplyStagingStore.Failure) =
        NotificationReplyStagingStore.PublicationResult.Rejected(reason)
    private fun stageRejected(reason: NotificationReplyStagingStore.Failure) =
        NotificationReplyStagingStore.StageResult.Rejected(reason)
    private fun initializeFailed(reason: NotificationReplyStagingStore.Failure) =
        NotificationReplyStagingStore.InitializeResult.Failed(reason)
    private fun observed(record: NotificationReplyStagingStore.IssuedRecord) =
        NotificationReplyStagingStore.PublicationResult.Observed(record)
    private val ready get() = NotificationReplyStagingStore.InitializeResult.Ready
}
