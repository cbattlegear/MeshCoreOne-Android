// AndroidOnly: WP-204 Real native SQLite persistence with test-only JCE keys; not hardware Keystore proof.
package com.meshcoreone.android.core.datastore.enrollment

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import com.meshcoreone.android.core.datastore.StorageFailure
import com.meshcoreone.android.core.datastore.StorageIssueReporter
import com.meshcoreone.android.core.datastore.StorageOperation
import com.meshcoreone.android.core.datastore.StorageProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NotificationEnrollmentWitnessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun missingStateIsUnknownAndReadOnlyNotFirstInstallProof() = runBlocking {
        val f = fixture()
        repeat(2) { assertEquals(WitnessObservation.UnknownOrLost, f.store().observe()) }
        assertFalse(f.file.exists())
        assertTrue(f.keys.keys.isEmpty())
        assertEquals(0, f.verifications)
    }

    @Test
    fun stableTrustedParentAliasCanAuthorizeAndReopenWithoutChangingIdentity() = runBlocking {
        val root = temporary.newFolder()
        val actual = File(root, "actual").apply { mkdir() }
        val noBackup = File(actual, "no_backup").apply { mkdir() }
        val alias = Files.createSymbolicLink(File(root, "trusted-alias").toPath(), actual.toPath()).toFile()
        val f = Fixture(File(alias, "no_backup/witness.db"))
        assertEquals(WitnessObservation.UnknownOrLost, f.store().observe())
        val record = f.authorize()
        assertEquals(WitnessObservation.Current(record), f.store().observe())
        assertTrue(File(noBackup, "witness.db").isFile)
        assertFalse(assertIs<WitnessMutation.Stored>(f.store().authorize(f.request)).newlyAuthorized)
        assertEquals(1, f.keys.created)
    }

    @Test
    fun parentAliasDoesNotPermitExistingOrDanglingLeafSymlinks() = runBlocking {
        for (existing in listOf(false, true)) {
            val root = temporary.newFolder()
            val actual = File(root, "actual").apply { mkdir() }
            val alias = Files.createSymbolicLink(File(root, "trusted-alias").toPath(), actual.toPath()).toFile()
            val target = File(root, "foreign.db")
            if (existing) target.writeText("must remain untouched")
            Files.createSymbolicLink(File(actual, "witness.db").toPath(), target.toPath())
            val f = Fixture(File(alias, "witness.db"))
            assertEquals(WitnessObservation.Failed(WitnessProblem.CORRUPT_STORAGE), f.store().observe())
            assertIs<WitnessMutation.NotRetained>(f.store().authorize(f.request))
            assertTrue(f.keys.keys.isEmpty())
            if (existing) assertEquals("must remain untouched", target.readText()) else assertFalse(target.exists())
        }
    }

    @Test
    fun retargetedTrustedParentIsRejectedByTheExistingWitnessInstance() = runBlocking {
        val root = temporary.newFolder()
        val actual = File(root, "actual").apply { mkdir() }
        val replacement = File(root, "replacement").apply { mkdir() }
        val alias = Files.createSymbolicLink(File(root, "trusted-alias").toPath(), actual.toPath())
        val f = Fixture(File(alias.toFile(), "witness.db"))
        val store = f.store()
        val authorized = assertIs<WitnessMutation.Stored>(store.authorize(f.request)).record
        val retained = File(actual, "witness.db").readBytes().toList()
        Files.delete(alias)
        Files.createSymbolicLink(alias, replacement.toPath())
        assertEquals(WitnessObservation.Failed(WitnessProblem.CORRUPT_STORAGE), store.observe())
        assertIs<WitnessMutation.NotRetained>(store.authorize(f.newRequest(authorized)))
        assertEquals(retained, File(actual, "witness.db").readBytes().toList())
        assertFalse(File(replacement, "witness.db").exists())
        assertEquals(1, f.keys.created)
    }

    @Test
    fun missingOrDeclinedForegroundAcknowledgmentNeverAllocatesDatabaseOrKey() = runBlocking {
        for (result in listOf(
            ForegroundAcknowledgmentResult.Required, ForegroundAcknowledgmentResult.Rejected,
            ForegroundAcknowledgmentResult.AlreadyConsumed,
        )) {
            val f = fixture()
            f.verification = { result }
            assertIs<WitnessMutation.NotRetained>(f.store().authorize(f.request))
            assertFalse(f.file.exists())
            assertTrue(f.keys.keys.isEmpty())
        }
    }

    @Test
    fun indeterminateAcknowledgmentNeverAllocatesOrCreatesANewKey() = runBlocking {
        val f = fixture()
        f.verification = { ForegroundAcknowledgmentResult.Indeterminate }
        assertEquals(WitnessMutation.Indeterminate(f.operation), f.store().authorize(f.request))
        assertFalse(f.file.exists())
        assertTrue(f.keys.keys.isEmpty())
    }

    @Test
    fun verifierSubstitutedNonceOrPriorObservationCannotAuthorize() = runBlocking {
        val f = fixture()
        f.verification = { ForegroundAcknowledgmentResult.NewlyVerified(it.copy(
            operation = it.operation.copy(creationNonce = UUID.randomUUID()),
        )) }
        assertEquals(WitnessMutation.NotRetained(WitnessProblem.ACKNOWLEDGMENT_MISMATCH), f.store().authorize(f.request))
        assertFalse(f.file.exists())
        assertTrue(f.keys.keys.isEmpty())
    }

    @Test
    fun authorizedTripleAndStableWitnessSurviveANewStoreObject() = runBlocking {
        val f = fixture()
        val first = assertIs<WitnessMutation.Stored>(f.store().authorize(f.request))
        assertTrue(first.newlyAuthorized)
        assertEquals(f.operation, first.record.operation)
        assertEquals(WitnessPhase.AUTHORIZED, first.record.phase)
        assertEquals(WitnessObservation.Current(first.record), f.store().observe())
        assertEquals(1, f.keys.created)
    }

    @Test
    fun sameOperationReplayNeverReturnsNewAuthorizationOrCallsVerifierAgain() = runBlocking {
        val f = fixture()
        val first = assertIs<WitnessMutation.Stored>(f.store().authorize(f.request))
        f.verification = { error("Replay must use retained authorization") }
        val replay = assertIs<WitnessMutation.Stored>(f.store().authorize(f.request))
        assertEquals(first.record, replay.record)
        assertFalse(replay.newlyAuthorized)
        assertEquals(1, f.verifications)
        assertEquals(1, f.keys.created)
    }

    @Test
    fun replayRequiresSameAcknowledgmentAndOriginalExpectedCurrent() = runBlocking {
        val f = fixture()
        val record = assertIs<WitnessMutation.Stored>(f.store().authorize(f.request)).record
        for (request in listOf(
            f.request.copy(acknowledgmentId = UUID.randomUUID()),
            f.request.copy(expectedCurrent = record),
        )) {
            assertEquals(WitnessMutation.NotRetained(WitnessProblem.ACKNOWLEDGMENT_MISMATCH), f.store().authorize(request))
        }
        assertEquals(WitnessObservation.Current(record), f.store().observe())
    }

    @Test
    fun acceptAndCompleteRetainExactReceiptAcrossReopen() = runBlocking {
        val f = fixture()
        val authorized = f.authorize()
        val preparing = f.preparing()
        val accepted = assertIs<WitnessMutation.Stored>(f.store().accept(authorized, preparing)).record
        assertEquals(preparing, accepted.receipt)
        val bound = preparing.copy(revision = 2, phase = JournalPhase.BOUND, witnessId = authorized.witnessId)
        val completed = assertIs<WitnessMutation.Stored>(f.store().complete(accepted, bound)).record
        assertEquals(3L, completed.revision)
        assertEquals(WitnessPhase.BOUND, completed.phase)
        assertEquals(bound, completed.receipt)
        assertEquals(WitnessObservation.Current(completed), f.store().observe())
        assertFalse(assertIs<WitnessMutation.Stored>(f.store().authorize(f.request)).newlyAuthorized)
        assertEquals(1, f.keys.created)
    }

    @Test
    fun everyPreparingIdentityAndRevisionMismatchPreservesAuthorization() = runBlocking {
        val f = fixture()
        val authorized = f.authorize()
        val receipt = f.preparing()
        val other = UUID.randomUUID()
        for (invalid in listOf(
            receipt.copy(operation = f.operation.copy(operationId = other)),
            receipt.copy(operation = f.operation.copy(storeId = other)),
            receipt.copy(operation = f.operation.copy(creationNonce = other), creationNonce = other),
            receipt.copy(creationNonce = other),
            receipt.copy(revision = 2),
            receipt.copy(witnessId = other),
            receipt.copy(phase = JournalPhase.BOUND),
        )) {
            assertEquals(WitnessMutation.NotRetained(WitnessProblem.INVALID_RECEIPT), f.store().accept(authorized, invalid))
        }
        assertEquals(WitnessObservation.Current(authorized), f.store().observe())
    }

    @Test
    fun boundMustKeepExactNonceOperationAndWitness() = runBlocking {
        val f = fixture()
        val authorized = f.authorize()
        val accepted = assertIs<WitnessMutation.Stored>(f.store().accept(authorized, f.preparing())).record
        val bound = f.preparing().copy(revision = 2, phase = JournalPhase.BOUND, witnessId = authorized.witnessId)
        val other = UUID.randomUUID()
        for (invalid in listOf(
            bound.copy(witnessId = other),
            bound.copy(creationNonce = other),
            bound.copy(operation = f.operation.copy(creationNonce = other), creationNonce = other),
            bound.copy(revision = 1),
            bound.copy(operation = f.operation.copy(storeId = other)),
        )) {
            assertEquals(WitnessMutation.NotRetained(WitnessProblem.INVALID_RECEIPT), f.store().complete(accepted, invalid))
        }
        assertEquals(WitnessObservation.Current(accepted), f.store().observe())
    }

    @Test
    fun fullExpectedRecordCASRejectsChangedWitnessAndConcurrentNewGeneration() = runBlocking {
        val f = fixture()
        val authorized = f.authorize()
        assertEquals(WitnessMutation.Stale, f.store().accept(authorized.copy(witnessId = UUID.randomUUID()), f.preparing()))
        val request = f.newRequest(authorized)
        val second = assertIs<WitnessMutation.Stored>(f.store().authorize(request)).record
        assertEquals(WitnessMutation.Stale, f.store().accept(authorized, f.preparing()))
        assertEquals(WitnessMutation.Stale, f.store().authorize(f.request))
        assertEquals(WitnessObservation.Current(second), f.store().observe())
        assertNotEquals(authorized.witnessId, second.witnessId)
        assertEquals(2, f.rowCount())
        assertEquals(2, f.keys.keys.size)
    }

    @Test
    fun twoConcurrentAuthorizationsAgainstSameExpectedRecordCannotBothWin() = runBlocking {
        val f = fixture()
        val authorized = f.authorize()
        val one = f.newRequest(authorized)
        val two = f.newRequest(authorized)
        val a = async { f.store().authorize(one) }
        val b = async { f.store().authorize(two) }
        val results = listOf(a.await(), b.await())
        assertEquals(1, results.count { it is WitnessMutation.Stored && it.newlyAuthorized })
        assertEquals(1, results.count { it == WitnessMutation.Stale })
        assertEquals(2, f.rowCount())
    }

    @Test
    fun oldGenerationsAndTheirTransitionRowsAreRetainedWithoutAnExpiryPolicy() = runBlocking {
        val f = fixture()
        val original = f.authorize()
        val accepted = assertIs<WitnessMutation.Stored>(f.store().accept(original, f.preparing())).record
        val bound = f.preparing().copy(revision = 2, phase = JournalPhase.BOUND, witnessId = original.witnessId)
        val completed = assertIs<WitnessMutation.Stored>(f.store().complete(accepted, bound)).record
        val previousBytes = f.payloads()
        val next = assertIs<WitnessMutation.Stored>(f.store().authorize(f.newRequest(completed))).record
        assertEquals(4, f.rowCount())
        assertEquals(previousBytes, f.payloads().take(3))
        assertEquals(2, f.keys.keys.size)
        assertEquals(WitnessObservation.Current(next), f.store().observe())
    }

    @Test
    fun reusedOperationStoreNonceAndAcknowledgmentAreRejectedWithoutKeyCreation() = runBlocking {
        val f = fixture()
        val authorized = f.authorize()
        val new = f.newRequest(authorized)
        for (request in listOf(
            new.copy(operation = new.operation.copy(operationId = f.operation.operationId)),
            new.copy(operation = new.operation.copy(storeId = f.operation.storeId)),
            new.copy(operation = new.operation.copy(creationNonce = f.operation.creationNonce)),
            new.copy(acknowledgmentId = f.request.acknowledgmentId),
        )) {
            assertEquals(WitnessMutation.NotRetained(WitnessProblem.INVALID_RECEIPT), f.store().authorize(request))
        }
        assertEquals(1, f.keys.created)
        assertEquals(WitnessObservation.Current(authorized), f.store().observe())
    }

    @Test
    fun missingOrReplacedAuthenticatorNeverGeneratesAReplacement() = runBlocking {
        for (replace in listOf(false, true)) {
            val f = fixture()
            val authorized = f.authorize()
            if (replace) f.keys.keys[authorized.witnessId] = f.keys.freshKey() else f.keys.keys.clear()
            val failure = assertIs<WitnessObservation.Failed>(f.store().observe())
            assertEquals(if (replace) WitnessProblem.AUTHENTICATION_FAILED else WitnessProblem.MISSING_AUTHENTICATOR, failure.problem)
            assertIs<WitnessMutation.NotRetained>(f.store().authorize(f.newRequest(authorized)))
            assertEquals(1, f.keys.created)
        }
    }

    @Test
    fun lockedStateCannotBeReadOrAuthorizedAndIsNotTreatedAsAbsence() = runBlocking {
        val f = fixture()
        val authorized = f.authorize()
        f.keys.locked = true
        assertEquals(WitnessObservation.Failed(WitnessProblem.LOCKED), f.store().observe())
        assertEquals(WitnessMutation.NotRetained(WitnessProblem.LOCKED), f.store().authorize(f.newRequest(authorized)))
        assertEquals(1, f.keys.created)
        f.keys.locked = false
        assertEquals(WitnessObservation.Current(authorized), f.store().observe())
    }

    @Test
    fun emptyPartialAndForeignDatabaseAreNeverReinitialized() = runBlocking {
        for (foreign in listOf(false, true)) {
            val f = fixture()
            assertTrue(f.file.createNewFile())
            if (foreign) SQLiteDatabase.openDatabase(f.file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("CREATE TABLE unrelated(value TEXT)")
                it.execSQL("INSERT INTO unrelated VALUES('retained')")
            }
            val before = f.file.readBytes()
            assertIs<WitnessObservation.Failed>(f.store().observe())
            assertIs<WitnessMutation.NotRetained>(f.store().authorize(f.request))
            assertEquals(before.toList(), f.file.readBytes().toList())
            assertTrue(f.keys.keys.isEmpty())
        }
    }

    @Test
    fun tamperedHistoryOrHeadIsRejectedAndNeverReset() = runBlocking {
        for (sql in listOf(
            "UPDATE witness_history SET payload=zeroblob(20)",
            "UPDATE witness_history SET mac=zeroblob(32)",
            "UPDATE witness_head SET sequence=99",
            "DELETE FROM witness_history",
            "DELETE FROM witness_head",
        )) {
            val f = fixture()
            f.authorize()
            f.database { it.execSQL(sql) }
            assertIs<WitnessObservation.Failed>(f.store().observe())
            assertIs<WitnessMutation.NotRetained>(f.store().authorize(f.request))
            assertEquals(1, f.keys.created)
        }
    }

    @Test
    fun nullableForeignDatabaseBlobsAreTypedCorruptionAndNeverReset() = runBlocking {
        for ((table, column) in listOf(
            "witness_history" to "payload",
            "witness_history" to "mac",
            "witness_head" to "mac",
        )) {
            val f = fixture()
            f.authorize()
            f.database { db ->
                when (table) {
                    "witness_history" -> {
                        db.execSQL(
                            "CREATE TABLE witness_history_nullable(sequence INTEGER PRIMARY KEY, payload BLOB, mac BLOB)",
                        )
                        db.execSQL(
                            "INSERT INTO witness_history_nullable " +
                                "SELECT sequence,${if (column == "payload") "NULL" else "payload"}," +
                                "${if (column == "mac") "NULL" else "mac"} FROM witness_history",
                        )
                        db.execSQL("DROP TABLE witness_history")
                        db.execSQL("ALTER TABLE witness_history_nullable RENAME TO witness_history")
                    }
                    "witness_head" -> {
                        db.execSQL(
                            "CREATE TABLE witness_head_nullable(singleton INTEGER PRIMARY KEY CHECK(singleton=1), sequence INTEGER NOT NULL, mac BLOB)",
                        )
                        db.execSQL(
                            "INSERT INTO witness_head_nullable SELECT singleton,sequence," +
                                "${if (column == "mac") "NULL" else "mac"} FROM witness_head",
                        )
                        db.execSQL("DROP TABLE witness_head")
                        db.execSQL("ALTER TABLE witness_head_nullable RENAME TO witness_head")
                    }
                }
                db.rawQuery("PRAGMA quick_check", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("ok", it.getString(0))
                }
            }

            assertEquals(
                WitnessObservation.Failed(WitnessProblem.CORRUPT_STORAGE),
                f.store().observe(),
            )
            assertEquals(
                WitnessMutation.NotRetained(WitnessProblem.CORRUPT_STORAGE),
                f.store().authorize(f.request),
            )
            assertEquals(1, f.keys.created)
            assertEquals(1, f.rowCount())
        }
    }

    @Test
    fun partialHistoricalLossBlocksEvenIfLatestRecordAndKeySurvive() = runBlocking {
        val f = fixture()
        val old = f.authorize()
        f.store().authorize(f.newRequest(old))
        f.database { it.execSQL("DELETE FROM witness_history WHERE sequence=1") }
        assertEquals(WitnessObservation.Failed(WitnessProblem.CORRUPT_STORAGE), f.store().observe())
        assertEquals(2, f.keys.created)
    }

    @Test
    fun missingDatabaseAfterPriorEnrollmentDoesNotReuseOrDeleteOldKeys() = runBlocking {
        val f = fixture()
        f.authorize()
        val oldKeys = f.keys.keys.toMap()
        assertTrue(f.file.delete())
        assertEquals(WitnessObservation.UnknownOrLost, f.store().observe())
        f.verification = { ForegroundAcknowledgmentResult.Required }
        assertIs<WitnessMutation.NotRetained>(f.store().authorize(f.request))
        assertEquals(oldKeys, f.keys.keys)
        f.verification = { ForegroundAcknowledgmentResult.NewlyVerified(it) }
        val fresh = assertIs<WitnessMutation.Stored>(f.store().authorize(f.newRequest(null)))
        assertTrue(fresh.newlyAuthorized)
        assertTrue(f.keys.keys.keys.containsAll(oldKeys.keys))
        assertEquals(2, f.keys.created)
    }

    @Test
    fun failureAfterCommitIsIndeterminateAndReplayCannotReturnNewlyAuthorized() = runBlocking {
        val f = fixture()
        val failed = f.store { if (it == NotificationEnrollmentWitness.WritePoint.COMMITTED) throw IOException("lost return") }
        assertEquals(WitnessMutation.Indeterminate(f.operation), failed.authorize(f.request))
        val recovered = assertIs<WitnessMutation.Stored>(f.store().authorize(f.request))
        assertFalse(recovered.newlyAuthorized)
        assertEquals(1, f.keys.created)
    }

    @Test
    fun failedFileOrDirectorySynchronizationNeverAcknowledgesFreshAuthorization() = runBlocking {
        val f = fixture()
        f.synchronize = { throw IOException("synchronization failed") }
        assertEquals(WitnessMutation.Indeterminate(f.operation), f.store().authorize(f.request))
        assertFalse(assertIs<WitnessMutation.Stored>(f.store().authorize(f.request)).newlyAuthorized)
        assertEquals(1, f.keys.created)
    }

    @Test
    fun failedInitialAllocationOrKeyCreationNeverRecreatesPartialStorage() = runBlocking {
        for (point in listOf(
            NotificationEnrollmentWitness.WritePoint.ALLOCATED,
            NotificationEnrollmentWitness.WritePoint.KEY_CREATED,
            NotificationEnrollmentWitness.WritePoint.ROW_WRITTEN,
        )) {
            val f = fixture()
            assertEquals(WitnessMutation.Indeterminate(f.operation), f.store {
                if (it == point) throw IOException("interrupted")
            }.authorize(f.request))
            assertTrue(f.file.exists())
            assertIs<WitnessObservation.Failed>(f.store().observe())
            val created = f.keys.created
            assertIs<WitnessMutation.NotRetained>(f.store().authorize(f.request))
            assertEquals(created, f.keys.created)
        }
    }

    @Test
    fun acceptRollbackAndCompleteLostReturnPreserveHonestOutcomes() = runBlocking {
        val f = fixture()
        val authorized = f.authorize()
        assertEquals(WitnessMutation.Indeterminate(f.operation), f.store {
            if (it == NotificationEnrollmentWitness.WritePoint.ROW_WRITTEN) throw IOException("rollback")
        }.accept(authorized, f.preparing()))
        assertEquals(WitnessObservation.Current(authorized), f.store().observe())
        val accepted = assertIs<WitnessMutation.Stored>(f.store().accept(authorized, f.preparing())).record
        val bound = f.preparing().copy(revision = 2, phase = JournalPhase.BOUND, witnessId = authorized.witnessId)
        assertEquals(WitnessMutation.Indeterminate(f.operation), f.store {
            if (it == NotificationEnrollmentWitness.WritePoint.COMMITTED) throw IOException("lost return")
        }.complete(accepted, bound))
        assertEquals(bound, assertIs<WitnessObservation.Current>(f.store().observe()).record.receipt)
        assertFalse(assertIs<WitnessMutation.Stored>(f.store().complete(accepted, bound)).newlyAuthorized)
    }

    @Test
    fun lateCancellationPropagatesAndRetainedAuthorizationStillCannotBeRecreated() = runBlocking {
        val f = fixture()
        var cancelled = false
        try {
            f.store {
                if (it == NotificationEnrollmentWitness.WritePoint.COMMITTED) throw CancellationException("late")
            }.authorize(f.request)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertFalse(assertIs<WitnessMutation.Stored>(f.store().authorize(f.request)).newlyAuthorized)
    }

    @Test
    fun matchingSameDeviceSnapshotRollbackIsExplicitlyNotDetectable() = runBlocking {
        val f = fixture()
        val first = f.authorize()
        val snapshot = f.file.readBytes()
        f.store().authorize(f.newRequest(first))
        f.file.writeBytes(snapshot)
        // Authentication is positive continuity, NOT a monotonic rollback detector.
        assertEquals(WitnessObservation.Current(first), f.store().observe())
    }

    private fun fixture() = Fixture(File(temporary.newFolder(), "witness.db"))

    private class Fixture(val file: File) {
        val operation = operation()
        val request = WitnessAuthorizationRequest(operation, UUID.randomUUID(), null)
        val keys = TestAuthentication()
        var verifications = 0
        var verification: (WitnessAuthorizationRequest) -> ForegroundAcknowledgmentResult = {
            ForegroundAcknowledgmentResult.NewlyVerified(it)
        }
        val failures = mutableListOf<StorageFailure>()
        // ShadowLinux uses RandomAccessFile for Os.open and cannot open directories. Exercise
        // real JVM file/directory force here, not a purported Android Os/Keystore device proof.
        var synchronize: (File) -> Unit = { file ->
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { it.force(true) }
            FileChannel.open(file.parentFile.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
        fun store(fault: ((NotificationEnrollmentWitness.WritePoint) -> Unit)? = null) =
            NotificationEnrollmentWitness(file, keys, ForegroundWitnessAcknowledgment {
                verifications++
                verification(it)
            }, StorageIssueReporter { failures.add(it) }, fault = fault, synchronize = { synchronize(it) })

        suspend fun authorize() = assertIs<WitnessMutation.Stored>(store().authorize(request)).record
        fun preparing() = WitnessCreationReceipt(operation, 1, JournalPhase.PREPARING, operation.creationNonce, null)
        fun newRequest(expected: EnrollmentWitnessRecord?) = WitnessAuthorizationRequest(operation(), UUID.randomUUID(), expected)
        fun database(action: (SQLiteDatabase) -> Unit) =
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use(action)
        fun rowCount(): Int {
            var count = 0
            database { db -> db.rawQuery("SELECT count(*) FROM witness_history", null).use {
                assertTrue(it.moveToFirst())
                count = it.getInt(0)
            } }
            return count
        }
        fun payloads(): List<List<Byte>> = buildList {
            database { db -> db.rawQuery("SELECT payload FROM witness_history ORDER BY sequence", null).use {
                while (it.moveToNext()) add(it.getBlob(0).toList())
            } }
        }
    }

    private class TestAuthentication : WitnessAuthentication {
        val keys = mutableMapOf<UUID, SecretKey>()
        var created = 0
        var locked = false
        override fun requireAccessible() {
            if (locked) throw StorageFailure(StorageProblem.DeviceLocked, StorageOperation.READ)
        }
        override fun createNew(witnessId: UUID) {
            check(witnessId !in keys)
            keys[witnessId] = freshKey()
            created++
        }
        override fun authenticate(witnessId: UUID, bytes: ByteArray): ByteArray {
            requireAccessible()
            val key = keys[witnessId] ?: throw StorageFailure(StorageProblem.FormerKeyMissing, StorageOperation.READ)
            return Mac.getInstance("HmacSHA256").run { init(key); doFinal(bytes) }
        }
        fun freshKey(): SecretKey = KeyGenerator.getInstance("HmacSHA256").apply { init(256) }.generateKey()
    }

    private companion object {
        fun operation() = WitnessOperation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    }
}
