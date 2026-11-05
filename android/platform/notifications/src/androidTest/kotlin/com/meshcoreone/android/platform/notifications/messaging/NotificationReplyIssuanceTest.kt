// AndroidOnly: WP-401 Real SQLite issued-token durability, immutable routing and atomic raw reply admission.
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
class NotificationReplyIssuanceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val file = File(context.cacheDir, "reply-issuance-${UUID.randomUUID()}.db")
    private val limits = NotificationReplyLimits(2, 10_000, 20_000)
    private val policy = NotificationReplyStagingStore.IssuanceLimits(3, 10_000, 30_000)
    private val store get() = NotificationReplyStagingStore(file, limits)
    private val action = NotificationReplyStagingStore.IssuedAction(
        UUID.randomUUID(), NotificationReplyPost(1, UUID.randomUUID(), "REPLY"),
        NotificationId("logical \u0000\uD800 id"),
        NotificationPayload.DirectMessage(EntityKey(RadioId(UUID(0, 1)), UUID(0, 2)), UUID(0, 3)),
    )
    private val raw = " \t\u0000e\u0301\uD800\uDC00\uD800 \r\n "

    @After
    fun cleanup() = removeDatabase(file)

    @Test
    fun missingStorageNeverBootstrapsFromRegisterInitializeOrAdmission() = runTest {
        val expected = UUID.randomUUID()
        assertEquals(NotificationReplyStagingStore.InitializeResult.Failed(
            NotificationReplyStagingStore.Failure.MISSING_STORAGE), store.initializeIssuance(expected, policy))
        assertEquals(rejected(NotificationReplyStagingStore.Failure.MISSING_STORAGE),
            store.registerIssued(expected, action))
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.MISSING_STORAGE),
            store.admitIssued(expected, action.token, raw))
        assertFalse(file.exists())
    }

    @Test
    fun explicitBoundMigrationPreservesLegacyRecordsAndCustodyAcrossReopen() = runTest {
        val expected = provisionFixture(initializeRegistry = false)
        val first = store.admit(expected, action.post, action.payload, raw) as
            NotificationReplyStagingStore.StageResult.Staged
        val claim = store.claim(first.identity, UUID.randomUUID(), UUID.randomUUID()) as
            NotificationReplyStagingStore.CustodyResult.NewlyClaimed
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeIssuance(expected, policy))
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeIssuance(expected, policy))
        val recovered = inventory(expected).single() as NotificationReplyStagingStore.RecoveryEntry.StagedReply
        assertEquals(first.identity, recovered.identity)
        assertEquals(raw, recovered.exactText)
        assertEquals(claim.receipt, recovered.custody)
        assertEquals(null, recovered.issuedToken)
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeLifecycle(30_000))
        assertEquals(NotificationReplyStagingStore.InstallationResult.Ready(expected, 3, 1),
            store.inspectInstallation(expected))
    }

    @Test
    fun schemaOneRequiresExplicitCustodyPolicyAndInvalidOrChangedIssuancePolicyFails() = runTest {
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeNew())
        val expected = (store.inspectInstallation(null) as
            NotificationReplyStagingStore.InstallationResult.UnboundExisting).observedStoreId
        assertEquals(NotificationReplyStagingStore.InitializeResult.Failed(
            NotificationReplyStagingStore.Failure.LIFECYCLE_NOT_INITIALIZED),
            store.initializeIssuance(expected, policy))
        assertEquals(NotificationReplyStagingStore.InitializeResult.Failed(
            NotificationReplyStagingStore.Failure.INVALID_ISSUANCE_LIMITS),
            store.initializeIssuance(expected, policy.copy(maxRecords = 0)))
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeLifecycle(30_000))
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeIssuance(expected, policy))
        assertEquals(NotificationReplyStagingStore.InitializeResult.Failed(
            NotificationReplyStagingStore.Failure.LIMITS_MISMATCH),
            store.initializeIssuance(expected, policy.copy(maxRecords = 4)))
        assertTrue(inventory(expected).isEmpty())
    }

    @Test
    fun wrongInstallationAndUninitializedRegistryRejectWithoutAdoptingOrWriting() = runTest {
        val expected = provisionFixture(initializeRegistry = false)
        assertEquals(rejected(NotificationReplyStagingStore.Failure.ISSUANCE_NOT_INITIALIZED),
            store.registerIssued(expected, action))
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.ISSUANCE_NOT_INITIALIZED),
            store.admitIssued(expected, action.token, raw))
        val other = UUID.randomUUID()
        assertEquals(NotificationReplyStagingStore.InitializeResult.Failed(
            NotificationReplyStagingStore.Failure.IDENTITY_MISMATCH), store.initializeIssuance(other, policy))
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeIssuance(expected, policy))
        assertEquals(rejected(NotificationReplyStagingStore.Failure.IDENTITY_MISMATCH),
            store.registerIssued(other, action))
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.IDENTITY_MISMATCH),
            store.admitIssued(other, action.token, raw))
        assertTrue(inventory(expected).isEmpty())
    }

    @Test
    fun durableDuplicateProposalReturnsExactRecordAndNeverUsesInboundReplacementPayload() = runTest {
        val expected = provisionFixture()
        val reserved = store.registerIssued(expected, action) as NotificationReplyStagingStore.IssuanceResult.Reserved
        assertEquals(action, reserved.record.action)
        assertEquals(expected, reserved.record.storeId)
        assertEquals(NotificationReplyStagingStore.IssuanceResult.AlreadyReserved(reserved.record),
            store.registerIssued(expected, action))
        val staged = store.admitIssued(expected, action.token, raw) as NotificationReplyStagingStore.StageResult.Staged
        assertEquals(NotificationReplyStagingStore.StageResult.AlreadyStaged(staged.identity, staged.outgoingMessageId),
            store.admitIssued(expected, action.token, raw))
        val recovered = inventory(expected).single() as NotificationReplyStagingStore.RecoveryEntry.StagedReply
        assertEquals(action.payload, recovered.payload)
        assertEquals(action.post, recovered.post)
        assertEquals(action.token, recovered.issuedToken)
        assertEquals(raw, recovered.exactText)
        assertEquals(NotificationReplyStagingStore.Custody.UNCLAIMED, recovered.custody?.state)
        assertEquals(NotificationReplyStagingStore.TargetAuthority.UNPROVED, recovered.targetAuthority)
    }

    @Test
    fun conflictingTokenProposalsCannotReplaceOriginalPostLogicalIdOrTypedRoute() = runTest {
        val expected = provisionFixture()
        val original = store.registerIssued(expected, action) as NotificationReplyStagingStore.IssuanceResult.Reserved
        val alternatives = listOf(
            action.copy(post = action.post.copy(postId = UUID.randomUUID())),
            action.copy(logicalNotificationId = NotificationId("replacement")),
            action.copy(payload = NotificationPayload.ChannelMessage(RadioId(UUID(0, 9)), 255u, UUID(0, 10))),
        )
        alternatives.forEach {
            assertEquals(rejected(NotificationReplyStagingStore.Failure.ISSUANCE_CONFLICT),
                store.registerIssued(expected, it))
        }
        assertEquals(NotificationReplyStagingStore.IssuanceResult.AlreadyReserved(original.record),
            store.registerIssued(expected, action))
        assertTrue(store.admitIssued(expected, action.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
        assertEquals(action.payload,
            (inventory(expected).single() as NotificationReplyStagingStore.RecoveryEntry.StagedReply).payload)
    }

    @Test
    fun sameIssuedTokenDifferentExactTextRetainsUnboundConflictWithoutNewOutgoingId() = runTest {
        val expected = provisionFixture()
        store.registerIssued(expected, action)
        val first = store.admitIssued(expected, action.token, raw) as NotificationReplyStagingStore.StageResult.Staged
        val conflict = store.admitIssued(expected, action.token, "$raw ") as
            NotificationReplyStagingStore.StageResult.ConflictRetained
        assertEquals(conflict.copy(alreadyRetained = true), store.admitIssued(expected, action.token, "$raw "))
        val recovered = inventory(expected)
        assertEquals(listOf(raw, "$raw "), recovered.map { it.exactText })
        assertEquals(first.identity, recovered.first().identity)
        assertTrue(recovered.last() is NotificationReplyStagingStore.RecoveryEntry.UnboundConflict)
        assertEquals(NotificationReplyStagingStore.Custody.UNBOUND,
            (recovered.last() as NotificationReplyStagingStore.RecoveryEntry.UnboundConflict).custody?.state)
    }

    @Test
    fun distinctIssuancesOfSamePostDoNotAliasOneAnotherOrLegacyStaging() = runTest {
        val expected = provisionFixture()
        val another = action.copy(token = UUID.randomUUID())
        store.registerIssued(expected, action)
        store.registerIssued(expected, another)
        val first = store.admitIssued(expected, action.token, raw) as NotificationReplyStagingStore.StageResult.Staged
        val second = store.admitIssued(expected, another.token, raw) as NotificationReplyStagingStore.StageResult.Staged
        assertNotEquals(first.identity, second.identity)
        assertNotEquals(first.outgoingMessageId, second.outgoingMessageId)
        assertEquals(setOf(action.token, another.token), inventory(expected).map { it.issuedToken }.toSet())
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.RECORD_CAPACITY),
            store.admit(expected, action.post, action.payload, raw))
    }

    @Test
    fun unknownTokenOrInvalidProposalDoesNotRetainTextOrInferIssuance() = runTest {
        val expected = provisionFixture()
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.ISSUED_TOKEN_NOT_FOUND),
            store.admitIssued(expected, action.token, raw))
        assertEquals(rejected(NotificationReplyStagingStore.Failure.INVALID_IDENTITY),
            store.registerIssued(expected, action.copy(post = action.post.copy(version = 2))))
        assertEquals(rejected(NotificationReplyStagingStore.Failure.INVALID_IDENTITY),
            store.registerIssued(expected, action.copy(post = action.post.copy(action = "OPEN"))))
        assertEquals(rejected(NotificationReplyStagingStore.Failure.INVALID_IDENTITY),
            store.registerIssued(expected, action.copy(logicalNotificationId = NotificationId(""))))
        store.registerIssued(expected, action)
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.INVALID_TEXT),
            store.admitIssued(expected, action.token, ""))
        assertTrue(inventory(expected).isEmpty())
    }

    @Test
    fun registryCountCapacityRejectsWithoutEvictionWhileDuplicateRemainsIdempotent() = runTest {
        val expected = provisionFixture(policy.copy(maxRecords = 1))
        val reserved = store.registerIssued(expected, action) as NotificationReplyStagingStore.IssuanceResult.Reserved
        assertEquals(rejected(NotificationReplyStagingStore.Failure.ISSUANCE_RECORD_CAPACITY),
            store.registerIssued(expected, action.copy(token = UUID.randomUUID())))
        assertEquals(NotificationReplyStagingStore.IssuanceResult.AlreadyReserved(reserved.record),
            store.registerIssued(expected, action))
        assertTrue(store.admitIssued(expected, action.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
    }

    @Test
    fun exactRegistryByteBoundaryAndTotalBudgetAreEnforcedOnRealSerializedContents() = runTest {
        var expected = provisionFixture()
        assertTrue(store.registerIssued(expected, action) is NotificationReplyStagingStore.IssuanceResult.Reserved)
        val measured = database { db ->
            db.rawQuery("SELECT content_bytes FROM reply_issued_actions", null).use {
                assertTrue(it.moveToFirst())
                it.getLong(0)
            }
        }
        cleanup()
        expected = provisionFixture(policy.copy(maxRecordBytes = measured, maxTotalRecordBytes = measured))
        assertTrue(store.registerIssued(expected, action) is NotificationReplyStagingStore.IssuanceResult.Reserved)
        assertEquals(rejected(NotificationReplyStagingStore.Failure.ISSUANCE_PRIVATE_DATA_CAPACITY),
            store.registerIssued(expected, action.copy(token = UUID.randomUUID())))
        cleanup()
        expected = provisionFixture(policy.copy(maxRecordBytes = measured - 1, maxTotalRecordBytes = measured * 2))
        assertEquals(rejected(NotificationReplyStagingStore.Failure.ISSUANCE_PRIVATE_DATA_CAPACITY),
            store.registerIssued(expected, action))
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.ISSUED_TOKEN_NOT_FOUND),
            store.admitIssued(expected, action.token, raw))
    }

    @Test
    fun registryReservationDoesNotPretendToReserveFutureReplyOrCustodyCapacity() = runTest {
        val expected = provisionFixture()
        store.registerIssued(expected, action)
        assertTrue(store.admitIssued(expected, action.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
        assertTrue(store.admitIssued(expected, action.token, "$raw ") is
            NotificationReplyStagingStore.StageResult.ConflictRetained)
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.RECORD_CAPACITY),
            store.admitIssued(expected, action.token, "$raw  "))
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.PRIVATE_DATA_CAPACITY),
            store.admitIssued(expected, action.token, "x".repeat(10_000)))
        assertEquals(listOf(raw, "$raw "), inventory(expected).map { it.exactText })
    }

    @Test
    fun concurrentConflictingRegistrationAndRawAdmissionUseOneImmutableWinningProposal() = runTest {
        val expected = provisionFixture()
        val alternative = action.copy(payload =
            NotificationPayload.ChannelMessage(RadioId(UUID(0, 7)), 0u, UUID(0, 8)))
        val start = CountDownLatch(1)
        val work = (0 until 8).map { index ->
            async(Dispatchers.Default) {
                start.await()
                store.registerIssued(expected, if (index % 2 == 0) action else alternative)
            }
        }
        start.countDown()
        val results = work.awaitAll()
        val winner = results.filterIsInstance<NotificationReplyStagingStore.IssuanceResult.Reserved>().single().record
        assertTrue(results.all {
            it is NotificationReplyStagingStore.IssuanceResult.Reserved ||
                it == NotificationReplyStagingStore.IssuanceResult.AlreadyReserved(winner) ||
                it == rejected(NotificationReplyStagingStore.Failure.ISSUANCE_CONFLICT)
        })
        val admit = (0 until 8).map {
            async(Dispatchers.Default) { store.admitIssued(expected, action.token, raw) }
        }.awaitAll()
        assertEquals(1, admit.count { it is NotificationReplyStagingStore.StageResult.Staged })
        assertTrue(admit.all { it is NotificationReplyStagingStore.StageResult.Staged ||
            it is NotificationReplyStagingStore.StageResult.AlreadyStaged })
        assertEquals(winner.action.payload,
            (inventory(expected).single() as NotificationReplyStagingStore.RecoveryEntry.StagedReply).payload)
    }

    @Test
    fun insufficientCustodyBudgetRejectsIssuedReplyWithoutFalseRetention() = runTest {
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeNew())
        val expected = (store.inspectInstallation(null) as
            NotificationReplyStagingStore.InstallationResult.UnboundExisting).observedStoreId
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeLifecycle(1))
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeIssuance(expected, policy))
        val reserved = store.registerIssued(expected, action) as NotificationReplyStagingStore.IssuanceResult.Reserved
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.LIFECYCLE_CAPACITY),
            store.admitIssued(expected, action.token, raw))
        assertTrue(inventory(expected).isEmpty())
        assertEquals(NotificationReplyStagingStore.IssuanceResult.AlreadyReserved(reserved.record),
            store.registerIssued(expected, action))
    }

    @Test
    fun concurrentDistinctReservationsCannotOversubscribeCountBudget() = runTest {
        val expected = provisionFixture(policy.copy(maxRecords = 1))
        val work = (0 until 8).map {
            async(Dispatchers.Default) { store.registerIssued(expected, action.copy(token = UUID.randomUUID())) }
        }.awaitAll()
        assertEquals(1, work.count { it is NotificationReplyStagingStore.IssuanceResult.Reserved })
        assertEquals(7, work.count { it == rejected(NotificationReplyStagingStore.Failure.ISSUANCE_RECORD_CAPACITY) })
    }

    @Test
    fun ignoredRegistryInsertCannotReturnDurableReservation() = runTest {
        val expected = provisionFixture()
        database { it.execSQL(
            "CREATE TRIGGER ignore_issue BEFORE INSERT ON reply_issued_actions BEGIN SELECT RAISE(IGNORE); END",
        ) }
        assertEquals(rejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.registerIssued(expected, action))
        database { it.execSQL("DROP TRIGGER ignore_issue") }
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.ISSUED_TOKEN_NOT_FOUND),
            store.admitIssued(expected, action.token, raw))
        assertTrue(store.registerIssued(expected, action) is NotificationReplyStagingStore.IssuanceResult.Reserved)
    }

    @Test
    fun ignoredRegistryAccountingCannotReturnDurableReservation() = runTest {
        val expected = provisionFixture()
        database { it.execSQL(
            "CREATE TRIGGER ignore_count BEFORE UPDATE ON reply_issuance_policy BEGIN SELECT RAISE(IGNORE); END",
        ) }
        assertEquals(rejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.registerIssued(expected, action))
        database { it.execSQL("DROP TRIGGER ignore_count") }
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.ISSUED_TOKEN_NOT_FOUND),
            store.admitIssued(expected, action.token, raw))
    }

    @Test
    fun stageFailureRollsBackReplyButKeepsOriginalIssuanceAvailableForExactRetry() = runTest {
        val expected = provisionFixture()
        store.registerIssued(expected, action)
        database { it.execSQL(
            "CREATE TRIGGER abort_reply BEFORE INSERT ON reply_records BEGIN SELECT RAISE(ABORT,'test'); END",
        ) }
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.STORAGE_UNAVAILABLE),
            store.admitIssued(expected, action.token, raw))
        assertTrue(inventory(expected).isEmpty())
        database { it.execSQL("DROP TRIGGER abort_reply") }
        assertTrue(store.admitIssued(expected, action.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
    }

    @Test
    fun lostReservationReturnReconcilesOnlyExactCommittedProposalInSameInstallation() = runTest {
        val expected = provisionFixture()
        val faulting = NotificationReplyStagingStore(file, limits, afterIssuanceCommit = {
            throw SQLiteException("lost return")
        })
        val result = faulting.registerIssued(expected, action) as
            NotificationReplyStagingStore.IssuanceResult.AlreadyReserved
        assertEquals(action, result.record.action)
        assertEquals(expected, result.record.storeId)
        assertTrue(store.admitIssued(expected, action.token, raw) is NotificationReplyStagingStore.StageResult.Staged)
    }

    @Test
    fun missingStorageAfterReservationCommitIsIndeterminateAndNeverRecreated() = runTest {
        val expected = provisionFixture()
        val faulting = NotificationReplyStagingStore(file, limits, afterIssuanceCommit = {
            cleanup()
            throw SQLiteException("lost return")
        })
        assertEquals(NotificationReplyStagingStore.IssuanceResult.Indeterminate(action.token),
            faulting.registerIssued(expected, action))
        assertFalse(file.exists())
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.MISSING_STORAGE),
            store.admitIssued(expected, action.token, raw))
    }

    @Test
    fun replacementInstallationWithSameTokenAndProposalCannotReconcileOrAdmitOldIdentity() = runTest {
        val expected = provisionFixture()
        val replacement = File(context.cacheDir, "reply-replacement-${UUID.randomUUID()}.db")
        try {
            val other = NotificationReplyStagingStore(replacement, limits)
            assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, other.initializeNew())
            val otherId = (other.inspectInstallation(null) as
                NotificationReplyStagingStore.InstallationResult.UnboundExisting).observedStoreId
            assertNotEquals(expected, otherId)
            other.initializeLifecycle(30_000)
            other.initializeIssuance(otherId, policy)
            assertTrue(other.registerIssued(otherId, action) is NotificationReplyStagingStore.IssuanceResult.Reserved)
            val faulting = NotificationReplyStagingStore(file, limits, afterIssuanceCommit = {
                cleanup()
                replacement.copyTo(file)
                throw SQLiteException("lost return")
            })
            assertEquals(NotificationReplyStagingStore.IssuanceResult.Indeterminate(action.token),
                faulting.registerIssued(expected, action))
            assertEquals(stageRejected(NotificationReplyStagingStore.Failure.IDENTITY_MISMATCH),
                store.admitIssued(expected, action.token, raw))
            assertTrue(inventory(otherId).isEmpty())
        } finally {
            removeDatabase(replacement)
        }
    }

    @Test
    fun lostIssuedReplyReturnReconcilesStableReceiptAndExactRawText() = runTest {
        val expected = provisionFixture()
        store.registerIssued(expected, action)
        val faulting = NotificationReplyStagingStore(file, limits, afterAdmissionCommit = {
            throw SQLiteException("lost reply return")
        })
        val result = faulting.admitIssued(expected, action.token, raw) as
            NotificationReplyStagingStore.StageResult.AlreadyStaged
        assertEquals(result.identity, inventory(expected).single().identity)
        assertEquals(raw, inventory(expected).single().exactText)
        assertEquals(result, store.admitIssued(expected, action.token, raw))
    }

    @Test
    fun corruptRegistryRowsOrPolicyFailClosedInsteadOfTrustingInboundOrDroppingEntries() = runTest {
        var expected = provisionFixture()
        store.registerIssued(expected, action)
        database { it.execSQL("UPDATE reply_issued_actions SET logical_id=X'0000'") }
        assertEquals(stageRejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.admitIssued(expected, action.token, raw))
        assertEquals(NotificationReplyStagingStore.ReadResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.read(expected))
        cleanup()
        expected = provisionFixture()
        database { it.execSQL("UPDATE reply_issuance_policy SET max_records=4294967299") }
        assertEquals(rejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.registerIssued(expected, action))
    }

    @Test
    fun unavailableAndForeignSchemaStorageCannotBecomeAnIssuanceReservation() = runTest {
        assertTrue(file.mkdir())
        val unavailable = store.registerIssued(UUID.randomUUID(), action)
        assertTrue(unavailable is NotificationReplyStagingStore.IssuanceResult.Rejected)
        assertEquals(NotificationReplyStagingStore.Failure.STORAGE_UNAVAILABLE,
            (unavailable as NotificationReplyStagingStore.IssuanceResult.Rejected).reason)
        cleanup()
        val expected = provisionFixture()
        database { it.execSQL("CREATE TABLE sqliteX_foreign(value TEXT)") }
        assertEquals(rejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.registerIssued(expected, action))
    }

    private suspend fun provisionFixture(
        selected: NotificationReplyStagingStore.IssuanceLimits = policy,
        initializeRegistry: Boolean = true,
    ): UUID {
        // Fixture setup only: the app has no equivalent trusted first-provisioning witness.
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeNew())
        val expected = (store.inspectInstallation(null) as
            NotificationReplyStagingStore.InstallationResult.UnboundExisting).observedStoreId
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeLifecycle(30_000))
        if (initializeRegistry) {
            assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeIssuance(expected, selected))
        }
        return expected
    }

    private suspend fun inventory(expected: UUID) =
        (store.read(expected) as NotificationReplyStagingStore.ReadResult.Inventory).entries

    private fun rejected(reason: NotificationReplyStagingStore.Failure) =
        NotificationReplyStagingStore.IssuanceResult.Rejected(reason)

    private fun stageRejected(reason: NotificationReplyStagingStore.Failure) =
        NotificationReplyStagingStore.StageResult.Rejected(reason)

    private fun <T> database(block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use(block)

    private fun removeDatabase(target: File) {
        listOf(target, File("${target.path}-journal"), File("${target.path}-wal"), File("${target.path}-shm"))
            .filter(File::exists).forEach { check(it.delete()) }
    }
}
