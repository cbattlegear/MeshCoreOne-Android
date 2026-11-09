// AndroidOnly: WP-401 Real SQLite installation binding and truthful atomic intake without bootstrap authority.
package com.meshcoreone.android.platform.notifications.messaging

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
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
class NotificationReplyAdmissionTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val file = File(context.cacheDir, "reply-admission-${UUID.randomUUID()}.db")
    private val limits = NotificationReplyLimits(3, 10_000, 30_000)
    private val store get() = NotificationReplyStagingStore(file, limits)
    private val post = NotificationReplyPost(1, UUID.randomUUID(), "REPLY")
    private val payload = NotificationPayload.DirectMessage(
        EntityKey(RadioId(UUID(0, 1)), UUID(0, 2)), UUID(0, 3),
    )
    private val raw = " \t\u0000e\u0301\uD800\uDC00\uD800 \r\n "

    @After
    fun cleanup() {
        listOf(file, File("${file.path}-journal"), File("${file.path}-wal"), File("${file.path}-shm"))
            .filter(File::exists).forEach { check(it.delete()) }
    }

    @Test
    fun missingDatabaseAndMissingExpectationNeverProveFreshInstall() = runTest {
        assertEquals(NotificationReplyStagingStore.InstallationResult.UnprovisionedOrLost,
            store.inspectInstallation(null))
        val expected = UUID.randomUUID()
        assertEquals(NotificationReplyStagingStore.InstallationResult.MissingInitializedStorage(expected),
            store.inspectInstallation(expected))
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.MISSING_STORAGE),
            store.admit(expected, post, payload, raw),
        )
        assertEquals(
            NotificationReplyStagingStore.ReadResult.Failed(NotificationReplyStagingStore.Failure.MISSING_STORAGE),
            store.read(expected),
        )
        assertFalse(file.exists())
    }

    @Test
    fun validDatabaseWithoutExternalExpectationIsUnboundNotAutomaticallyAdopted() = runTest {
        val expected = provisionFixture()
        assertEquals(NotificationReplyStagingStore.InstallationResult.UnboundExisting(expected),
            store.inspectInstallation(null))
        assertEquals(NotificationReplyStagingStore.InstallationResult.Ready(expected, 1, 0),
            store.inspectInstallation(expected))
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeLifecycle(30_000))
        assertEquals(NotificationReplyStagingStore.InstallationResult.Ready(expected, 2, 0),
            store.inspectInstallation(expected))
        val other = UUID.randomUUID()
        assertEquals(NotificationReplyStagingStore.InstallationResult.IdentityMismatch(other, expected),
            store.inspectInstallation(other))
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.IDENTITY_MISMATCH),
            store.admit(other, post, payload, raw),
        )
        assertEquals(
            NotificationReplyStagingStore.ReadResult.Failed(NotificationReplyStagingStore.Failure.IDENTITY_MISMATCH),
            store.read(other),
        )
        assertTrue(inventory(expected).isEmpty())
    }

    @Test
    fun boundDuplicateAndConflictingAdmissionSurviveReopenWithExactTextAndStableIds() = runTest {
        val expected = provisionFixture()
        val first = store.admit(expected, post, payload, raw) as NotificationReplyStagingStore.StageResult.Staged
        val duplicate = store.admit(expected, post, payload, raw) as
            NotificationReplyStagingStore.StageResult.AlreadyStaged
        assertEquals(first.identity, duplicate.identity)
        assertEquals(first.outgoingMessageId, duplicate.outgoingMessageId)
        val conflict = store.admit(expected, post, payload, "$raw ") as
            NotificationReplyStagingStore.StageResult.ConflictRetained
        val changedRoute = NotificationPayload.ChannelMessage(RadioId(UUID(0, 5)), 255u, UUID(0, 6))
        val routeConflict = store.admit(expected, post, changedRoute, raw) as
            NotificationReplyStagingStore.StageResult.ConflictRetained
        assertEquals(conflict.copy(alreadyRetained = true), store.admit(expected, post, payload, "$raw "))
        assertEquals(routeConflict.copy(alreadyRetained = true), store.admit(expected, post, changedRoute, raw))
        val entries = inventory(expected)
        assertEquals(listOf(raw, "$raw ", raw), entries.map { it.exactText })
        assertEquals(first.identity, entries.first().identity)
        assertTrue(entries.drop(1).all { it is NotificationReplyStagingStore.RecoveryEntry.UnboundConflict })
        assertTrue(entries.all { it.targetAuthority == NotificationReplyStagingStore.TargetAuthority.UNPROVED })
    }

    @Test
    fun capacityExhaustionIsNotRetainedAndDoesNotEvictOrRetirePriorInputs() = runTest {
        val expected = provisionFixture()
        val ids = (1..3).map {
            (store.admit(expected, post.copy(postId = UUID.randomUUID()), payload, "$raw$it") as
                NotificationReplyStagingStore.StageResult.Staged).identity
        }
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.RECORD_CAPACITY),
            store.admit(expected, post, payload, "not retained"),
        )
        assertEquals(ids, inventory(expected).map { it.identity })
        assertEquals(listOf("${raw}1", "${raw}2", "${raw}3"), inventory(expected).map { it.exactText })
        assertEquals(NotificationReplyStagingStore.InstallationResult.Ready(expected, 1, 3),
            store.inspectInstallation(expected))
    }

    @Test
    fun concurrentAdmissionIsOnePrimaryOneUnboundConflictAndNoCapacityOrIdentityBypass() = runTest {
        val expected = provisionFixture()
        val start = CountDownLatch(1)
        val work = (0 until 8).map { index ->
            async(Dispatchers.Default) {
                start.await()
                store.admit(expected, post, payload, if (index % 2 == 0) raw else "$raw ")
            }
        }
        start.countDown()
        val results = work.awaitAll()
        assertEquals(1, results.count { it is NotificationReplyStagingStore.StageResult.Staged })
        assertEquals(2, inventory(expected).size)
        assertEquals(setOf(raw, "$raw "), inventory(expected).map { it.exactText }.toSet())
        assertTrue(results.all {
            it is NotificationReplyStagingStore.StageResult.Staged ||
                it is NotificationReplyStagingStore.StageResult.AlreadyStaged ||
                it is NotificationReplyStagingStore.StageResult.ConflictRetained
        })
    }

    @Test
    fun validReplacementJournalCannotBeAdoptedEvenAfterInspectionOfOldStore() = runTest {
        val expected = provisionFixture()
        store.admit(expected, post, payload, raw)
        assertEquals(NotificationReplyStagingStore.InstallationResult.Ready(expected, 1, 1),
            store.inspectInstallation(expected))
        cleanup()
        val replacement = provisionFixture()
        assertNotEquals(expected, replacement)
        assertEquals(NotificationReplyStagingStore.InstallationResult.IdentityMismatch(expected, replacement),
            store.inspectInstallation(expected))
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.IDENTITY_MISMATCH),
            store.admit(expected, post, payload, raw),
        )
        assertTrue(inventory(replacement).isEmpty())
    }

    @Test
    fun lossAfterKnownInitializationRemainsMissingNotARequestToBootstrap() = runTest {
        val expected = provisionFixture()
        store.admit(expected, post, payload, raw)
        cleanup()
        assertEquals(NotificationReplyStagingStore.InstallationResult.MissingInitializedStorage(expected),
            store.inspectInstallation(expected))
        assertEquals(NotificationReplyStagingStore.InstallationResult.UnprovisionedOrLost,
            store.inspectInstallation(null))
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.MISSING_STORAGE),
            store.admit(expected, post, payload, raw),
        )
        assertFalse(file.exists())
    }

    @Test
    fun corruptOrEmptyExistingStorageIsPreservedNotTreatedAsFreshOrEmptySuccess() = runTest {
        val expected = UUID.randomUUID()
        val bytes = "not sqlite private state".toByteArray()
        file.writeBytes(bytes)
        assertEquals(
            NotificationReplyStagingStore.InstallationResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.inspectInstallation(expected),
        )
        assertEquals(bytes.toList(), file.readBytes().toList())
        cleanup()
        SQLiteDatabase.openOrCreateDatabase(file, null).close()
        assertEquals(
            NotificationReplyStagingStore.InstallationResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.inspectInstallation(null),
        )
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.admit(expected, post, payload, raw),
        )
    }

    @Test
    fun failedAdmissionTransactionIsNotRetainedAndPreservesPreviousBytes() = runTest {
        val expected = provisionFixture()
        val first = store.admit(expected, post, payload, raw) as NotificationReplyStagingStore.StageResult.Staged
        exec(
            """CREATE TRIGGER reject_intake BEFORE UPDATE ON reply_metadata
               BEGIN SELECT RAISE(ABORT, 'metadata failure'); END""",
        )
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.STORAGE_UNAVAILABLE),
            store.admit(expected, post, payload, "not retained"),
        )
        assertEquals(listOf(first.identity), inventory(expected).map { it.identity })
        assertEquals(listOf(raw), inventory(expected).map { it.exactText })
        exec("DROP TRIGGER reject_intake")
        val retry = store.admit(expected, post, payload, "not retained") as
            NotificationReplyStagingStore.StageResult.ConflictRetained
        assertEquals(2L, retry.identity.sequence)
    }

    @Test
    fun committedAdmissionLostReturnReconcilesExactRetainedRecordWithoutAnotherSendIdentity() = runTest {
        val expected = provisionFixture()
        val faulting = NotificationReplyStagingStore(
            file, limits, afterAdmissionCommit = { throw SQLiteException("lost return") },
        )
        val result = faulting.admit(expected, post, payload, raw) as
            NotificationReplyStagingStore.StageResult.AlreadyStaged
        val duplicate = store.admit(expected, post, payload, raw) as
            NotificationReplyStagingStore.StageResult.AlreadyStaged
        assertEquals(result.identity, duplicate.identity)
        assertEquals(result.outgoingMessageId, duplicate.outgoingMessageId)
        assertEquals(listOf(raw), inventory(expected).map { it.exactText })
    }

    @Test
    fun missingStoreAfterCommitIsIndeterminateAndNeverCreatesStorageDuringReconciliation() = runTest {
        val expected = provisionFixture()
        val faulting = NotificationReplyStagingStore(file, limits, afterAdmissionCommit = {
            cleanup()
            throw SQLiteException("lost committed store")
        })
        val result = faulting.admit(expected, post, payload, raw)
        assertTrue(result is NotificationReplyStagingStore.StageResult.Indeterminate)
        assertEquals(NotificationReplyStagingStore.InstallationResult.MissingInitializedStorage(expected),
            store.inspectInstallation(expected))
        assertFalse(file.exists())
    }

    @Test
    fun replacementAfterCommitCannotReconcileAsRetainedInAnotherInstallation() = runTest {
        val expected = provisionFixture()
        val replacementFile = File(context.cacheDir, "replacement-admission-${UUID.randomUUID()}.db")
        try {
            val replacement = NotificationReplyStagingStore(replacementFile, limits)
            assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, replacement.initializeNew())
            val replacementId = (replacement.inspectInstallation(null) as
                NotificationReplyStagingStore.InstallationResult.UnboundExisting).observedStoreId
            assertTrue(replacement.admit(replacementId, post, payload, raw) is
                NotificationReplyStagingStore.StageResult.Staged)
            val faulting = NotificationReplyStagingStore(file, limits, afterAdmissionCommit = {
                cleanup()
                replacementFile.copyTo(file)
                throw SQLiteException("lost original return")
            })
            assertTrue(faulting.admit(expected, post, payload, raw) is
                NotificationReplyStagingStore.StageResult.Indeterminate)
            assertNotEquals(expected, replacementId)
            assertEquals(NotificationReplyStagingStore.InstallationResult.IdentityMismatch(expected, replacementId),
                store.inspectInstallation(expected))
            assertEquals(
                NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.IDENTITY_MISMATCH),
                store.admit(expected, post, payload, raw),
            )
            assertEquals(listOf(raw), inventory(replacementId).map { it.exactText })
        } finally {
            listOf(replacementFile, File("${replacementFile.path}-journal"),
                File("${replacementFile.path}-wal"), File("${replacementFile.path}-shm"))
                .filter(File::exists).forEach { check(it.delete()) }
        }
    }

    @Test
    fun unavailableDirectoryAndWrongLimitsAreExplicitNotRetainedFailures() = runTest {
        val expected = provisionFixture()
        val wrongLimits = NotificationReplyStagingStore(file, limits.copy(maxRecords = 4))
        assertEquals(
            NotificationReplyStagingStore.InstallationResult.Failed(NotificationReplyStagingStore.Failure.LIMITS_MISMATCH),
            wrongLimits.inspectInstallation(expected),
        )
        cleanup()
        check(file.mkdir())
        assertEquals(
            NotificationReplyStagingStore.InstallationResult.Failed(NotificationReplyStagingStore.Failure.STORAGE_UNAVAILABLE),
            store.inspectInstallation(expected),
        )
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.STORAGE_UNAVAILABLE),
            store.admit(expected, post, payload, raw),
        )
    }

    private suspend fun provisionFixture(): UUID {
        // Explicit test setup, NOT evidence that production can infer a fresh installation.
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeNew())
        return (store.inspectInstallation(null) as NotificationReplyStagingStore.InstallationResult.UnboundExisting)
            .observedStoreId
    }

    private suspend fun inventory(expected: UUID) =
        (store.read(expected) as NotificationReplyStagingStore.ReadResult.Inventory).entries

    private fun exec(sql: String) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(sql) }
    }
}
