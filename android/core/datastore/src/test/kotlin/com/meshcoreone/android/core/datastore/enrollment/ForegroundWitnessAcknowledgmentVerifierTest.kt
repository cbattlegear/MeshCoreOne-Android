// AndroidOnly: WP-204 Native SQLite, single-use acknowledgment and uncertainty regressions.
package com.meshcoreone.android.core.datastore.enrollment

import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import com.meshcoreone.android.core.datastore.StorageFailure
import com.meshcoreone.android.core.datastore.StorageIssueReporter
import com.meshcoreone.android.core.datastore.StorageOperation
import com.meshcoreone.android.core.datastore.StorageProblem

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ForegroundWitnessAcknowledgmentVerifierTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun noExplicitActionOrMissingEvidenceNeverVerifies() = runBlocking {
        val f = fixture()
        assertEquals(ForegroundAcknowledgmentResult.Required, f.verifier.verify(f.request))
        val presentation = f.verifier.present(f.request)
        assertEquals(ForegroundAcknowledgmentResult.Required, f.verifier.verify(f.request))
        assertFalse(f.file.exists())
        assertEquals(ForegroundAcknowledgmentStaging.Staged, f.verifier.recordExplicitAction(presentation))
        assertEquals(ForegroundAcknowledgmentResult.NewlyVerified(f.request), f.verifier.verify(f.request))
    }

    @Test
    fun completeWarningAndOperationBindingRejectsEveryRequestFieldChange() = runBlocking {
        val f = fixture()
        val presentation = f.verifier.present(f.request)
        assertEquals(f.warning, presentation.warning)
        assertEquals(ForegroundAcknowledgmentStaging.Staged, f.verifier.recordExplicitAction(presentation))
        val changed = listOf(
            f.request.copy(operation = f.request.operation.copy(operationId = UUID.randomUUID())),
            f.request.copy(operation = f.request.operation.copy(storeId = UUID.randomUUID())),
            f.request.copy(operation = f.request.operation.copy(creationNonce = UUID.randomUUID())),
            f.request.copy(acknowledgmentId = UUID.randomUUID()),
            f.request.copy(expectedCurrent = f.record()),
        )
        changed.forEach { request ->
            val result = f.verifier.verify(request)
            assertTrue(
                result == ForegroundAcknowledgmentResult.Rejected ||
                    request.acknowledgmentId != f.request.acknowledgmentId &&
                    result == ForegroundAcknowledgmentResult.Required,
            )
        }
        assertEquals(ForegroundAcknowledgmentResult.NewlyVerified(f.request), f.verifier.verify(f.request))
    }

    @Test
    fun changedWarningVersionOrRenderedCopyCannotReuseStagedAction() = runBlocking {
        val f = fixture()
        val presentation = f.verifier.present(f.request)
        assertEquals(ForegroundAcknowledgmentStaging.Staged, f.verifier.recordExplicitAction(presentation))
        for (warning in listOf(
            ForegroundAcknowledgmentWarning.fromRenderedCopy(
                f.warning.id,
                f.warning.version + 1,
                "A versioned changed loss warning.",
            ),
            ForegroundAcknowledgmentWarning.fromRenderedCopy(
                f.warning.id,
                f.warning.version,
                "A different rendered loss warning.",
            ),
        )) {
            val replacement = f.verifier(warning = warning, processId = UUID.randomUUID())
            val newPresentation = replacement.present(f.request)
            assertEquals(
                ForegroundAcknowledgmentStaging.Rejected,
                replacement.recordExplicitAction(newPresentation),
            )
            assertEquals(ForegroundAcknowledgmentResult.Rejected, replacement.verify(f.request))
        }
        assertEquals(ForegroundAcknowledgmentResult.NewlyVerified(f.request), f.verifier.verify(f.request))
    }

    @Test
    fun warningDescriptorHashesTheExactRenderedUtf8Copy() {
        val copy = "Loss warning: résumé and 🛰"
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(copy.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertEquals(
            expected,
            ForegroundAcknowledgmentWarning.fromRenderedCopy("notice", 1, copy).renderedCopySha256,
        )
    }

    @Test
    fun lifecycleInvalidationBeforeAndAfterStagingBlocksConsumption() = runBlocking {
        val f = fixture()
        val beforeAction = f.verifier.present(f.request)
        f.verifier.invalidate(beforeAction)
        assertEquals(ForegroundAcknowledgmentStaging.Rejected, f.verifier.recordExplicitAction(beforeAction))
        assertEquals(ForegroundAcknowledgmentResult.Required, f.verifier.verify(f.request))

        val request = f.request.copy(acknowledgmentId = UUID.randomUUID())
        val afterAction = f.verifier.present(request)
        assertEquals(ForegroundAcknowledgmentStaging.Staged, f.verifier.recordExplicitAction(afterAction))
        f.verifier.invalidate(afterAction)
        assertEquals(ForegroundAcknowledgmentResult.Rejected, f.verifier.verify(request))
        assertEquals(
            ForegroundAcknowledgmentResult.Rejected,
            f.verifier(processId = UUID.randomUUID()).verify(request),
        )
    }

    @Test
    fun synchronousRetirementDuringStagingPreventsAnActionGrant() = runBlocking {
        val f = fixture()
        val presentation = f.verifier.present(f.request)
        val gate = SynchronizationGate()
        f.synchronizationGate = gate
        val staging = async(Dispatchers.IO) { f.verifier.recordExplicitAction(presentation) }
        assertTrue(gate.entered.await(10, TimeUnit.SECONDS))

        f.verifier.retire(presentation)
        gate.release.countDown()

        assertEquals(ForegroundAcknowledgmentStaging.Rejected, staging.await())
        assertEquals(ForegroundAcknowledgmentResult.Rejected, f.verifier.verify(f.request))
        assertEquals(
            ForegroundAcknowledgmentResult.Rejected,
            f.verifier(processId = UUID.randomUUID()).verify(f.request),
        )
    }

    @Test
    fun retirementBeforeLedgerAllocationTerminatesLateStagingWithoutWitnessAuthorization() =
        assertRetiredAllocation(ForegroundWitnessAcknowledgmentVerifier.WritePoint.KEY_CREATED)

    @Test
    fun retirementDuringOwnedEmptyAllocationDefersWithoutReportingCorruption() =
        assertRetiredAllocation(ForegroundWitnessAcknowledgmentVerifier.WritePoint.ALLOCATED)

    private fun assertRetiredAllocation(point: ForegroundWitnessAcknowledgmentVerifier.WritePoint) = runBlocking {
        val f = fixture()
        val presentation = f.verifier.present(f.request)
        val gate = WriteGate(point)
        f.writeGate = gate
        val staging = async(Dispatchers.IO) { f.verifier.recordExplicitAction(presentation) }
        try {
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            assertEquals(point == ForegroundWitnessAcknowledgmentVerifier.WritePoint.ALLOCATED, f.file.exists())
            f.verifier.retire(presentation)
            f.verifier.invalidate(presentation)
            assertEquals(point == ForegroundWitnessAcknowledgmentVerifier.WritePoint.ALLOCATED, f.file.exists())
            assertTrue(f.failures.isEmpty())
        } finally {
            gate.release.countDown()
        }

        assertEquals(ForegroundAcknowledgmentStaging.Rejected, staging.await())
        assertTrue(f.failures.isEmpty())
        f.database { db ->
            db.rawQuery("SELECT payload FROM acknowledgment_history ORDER BY sequence", null).use { rows ->
                for (status in listOf("STAGED", "INVALIDATED")) {
                    assertTrue(rows.moveToNext())
                    DataInputStream(ByteArrayInputStream(rows.getBlob(0))).use { input ->
                        assertEquals("MeshCoreOne.notification-ack.history.v1", input.readUTF())
                        input.readLong()
                        repeat(2) { input.readFully(ByteArray(input.readInt())) }
                        assertEquals(presentation.presentationId, input.readUuid())
                        assertEquals(presentation.processId, input.readUuid())
                        assertEquals(status, input.readUTF())
                        assertEquals(32, input.available())
                    }
                }
                assertFalse(rows.moveToNext())
            }
        }
        assertEquals(ForegroundAcknowledgmentResult.Rejected, f.verifier.verify(f.request))
        val reopened = f.verifier(processId = UUID.randomUUID())
        assertEquals(ForegroundAcknowledgmentResult.Rejected, reopened.verify(f.request))
        val witnessFile = File(f.root, "witness.db")
        val authentication = TestWitnessAuthentication()
        val witness = NotificationEnrollmentWitness(
            witnessFile,
            authentication,
            reopened,
            StorageIssueReporter { _ -> },
            synchronize = f::synchronize,
        )
        assertEquals(
            WitnessMutation.NotRetained(WitnessProblem.ACKNOWLEDGMENT_MISMATCH),
            witness.authorize(f.request),
        )
        assertFalse(witnessFile.exists())
        assertEquals(0, authentication.created)
    }

    @Test
    fun abandonedEmptyLedgerStillReportsCorruptionWithoutAnAllocatingWriter() = runBlocking {
        val f = fixture()
        val presentation = f.verifier.present(f.request)
        f.failAt = ForegroundWitnessAcknowledgmentVerifier.WritePoint.ALLOCATED
        assertEquals(ForegroundAcknowledgmentStaging.Indeterminate, f.verifier.recordExplicitAction(presentation))
        assertTrue(f.file.isFile)
        assertTrue(f.failures.isNotEmpty())
        f.failAt = null
        f.failures.clear()
        f.verifier.invalidate(presentation)
        assertEquals(StorageProblem.CorruptSecretState, f.failures.single().problem)
        assertEquals(
            ForegroundAcknowledgmentResult.Indeterminate,
            f.verifier(processId = UUID.randomUUID()).verify(f.request),
        )
        assertEquals(1, f.authentication.creates)
    }

    @Test
    fun retiredStagingCleanupFailureIsIndeterminateAndCannotAuthorize() = runBlocking {
        val f = fixture()
        val presentation = f.verifier.present(f.request)
        val gate = SynchronizationGate()
        f.synchronizationGate = gate
        val staging = async(Dispatchers.IO) { f.verifier.recordExplicitAction(presentation) }
        try {
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            f.verifier.retire(presentation)
            f.failAt = ForegroundWitnessAcknowledgmentVerifier.WritePoint.ROW_WRITTEN
        } finally {
            gate.release.countDown()
        }
        assertEquals(ForegroundAcknowledgmentStaging.Indeterminate, staging.await())
        assertTrue(f.failures.isNotEmpty())
        f.failAt = null
        assertEquals(ForegroundAcknowledgmentResult.Rejected, f.verifier.verify(f.request))
        assertEquals(
            ForegroundAcknowledgmentResult.Rejected,
            f.verifier(processId = UUID.randomUUID()).verify(f.request),
        )
    }

    @Test
    fun retirementBeforeActionAdmissionDoesNotCreateAcknowledgmentStorage() = runBlocking {
        val f = fixture()
        val presentation = f.verifier.present(f.request)
        val gate = AdmissionGate(ForegroundWitnessAcknowledgmentVerifier.AdmissionPoint.EXPLICIT_ACTION)
        f.admissionGate = gate
        val staging = async(Dispatchers.IO) { f.verifier.recordExplicitAction(presentation) }
        assertTrue(gate.entered.await(10, TimeUnit.SECONDS))

        f.verifier.retire(presentation)
        gate.release.countDown()

        assertEquals(ForegroundAcknowledgmentStaging.Rejected, staging.await())
        assertFalse(f.file.exists())
        assertEquals(
            ForegroundAcknowledgmentResult.Required,
            f.verifier(processId = UUID.randomUUID()).verify(f.request),
        )
    }

    @Test
    fun retirementBeforeConsumptionAdmissionLeavesSQLiteStaged() = runBlocking {
        val f = fixture()
        val presentation = f.verifier.present(f.request)
        assertEquals(ForegroundAcknowledgmentStaging.Staged, f.verifier.recordExplicitAction(presentation))
        val gate = AdmissionGate(ForegroundWitnessAcknowledgmentVerifier.AdmissionPoint.WITNESS_CONSUMPTION)
        f.admissionGate = gate
        val verification = async(Dispatchers.IO) { f.verifier.verify(f.request) }
        assertTrue(gate.entered.await(10, TimeUnit.SECONDS))

        f.verifier.retire(presentation)
        gate.release.countDown()

        assertEquals(ForegroundAcknowledgmentResult.Rejected, verification.await())
        assertEquals(
            ForegroundAcknowledgmentResult.Rejected,
            f.verifier(processId = UUID.randomUUID()).verify(f.request),
        )
    }

    @Test
    fun synchronousRetirementDuringConsumptionNeverReturnsFreshAuthorization() = runBlocking {
        val f = fixture()
        val presentation = f.verifier.present(f.request)
        assertEquals(ForegroundAcknowledgmentStaging.Staged, f.verifier.recordExplicitAction(presentation))
        val gate = SynchronizationGate()
        f.synchronizationGate = gate
        val verification = async(Dispatchers.IO) { f.verifier.verify(f.request) }
        assertTrue(gate.entered.await(10, TimeUnit.SECONDS))

        f.verifier.retire(presentation)
        gate.release.countDown()

        assertEquals(ForegroundAcknowledgmentResult.Indeterminate, verification.await())
        assertEquals(
            ForegroundAcknowledgmentResult.AlreadyConsumed,
            f.verifier(processId = UUID.randomUUID()).verify(f.request),
        )
    }

    @Test
    fun processRestartRejectsPendingEvidenceButPreservesConsumedReplay() = runBlocking {
        val f = fixture()
        val pending = f.verifier.present(f.request)
        assertEquals(ForegroundAcknowledgmentStaging.Staged, f.verifier.recordExplicitAction(pending))
        val afterRestart = f.verifier(processId = UUID.randomUUID())
        assertEquals(ForegroundAcknowledgmentResult.Rejected, afterRestart.verify(f.request))

        val consumedRequest = f.request.copy(
            operation = WitnessOperation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            acknowledgmentId = UUID.randomUUID(),
        )
        val active = f.verifier.present(consumedRequest)
        assertEquals(ForegroundAcknowledgmentStaging.Staged, f.verifier.recordExplicitAction(active))
        assertEquals(ForegroundAcknowledgmentResult.NewlyVerified(consumedRequest), f.verifier.verify(consumedRequest))
        assertEquals(ForegroundAcknowledgmentResult.AlreadyConsumed, afterRestart.verify(consumedRequest))
    }

    @Test
    fun concurrentVerificationReturnsExactlyOneFreshGrant() = runBlocking {
        val f = fixture()
        val presentation = f.verifier.present(f.request)
        assertEquals(ForegroundAcknowledgmentStaging.Staged, f.verifier.recordExplicitAction(presentation))
        val results = (0 until 12).map {
            async(Dispatchers.IO) { f.verifier.verify(f.request) }
        }.awaitAll()
        assertEquals(1, results.count { it == ForegroundAcknowledgmentResult.NewlyVerified(f.request) })
        assertTrue(results.all {
            it == ForegroundAcknowledgmentResult.NewlyVerified(f.request) ||
                it == ForegroundAcknowledgmentResult.AlreadyConsumed ||
                it == ForegroundAcknowledgmentResult.Indeterminate
        })
    }

    @Test
    fun sameOperationStoreOrNonceCannotBeAcknowledgedAgainWithANewId() = runBlocking {
        val f = fixture()
        val first = f.verifier.present(f.request)
        assertEquals(ForegroundAcknowledgmentStaging.Staged, f.verifier.recordExplicitAction(first))
        val reused = f.verifier.present(f.request.copy(acknowledgmentId = UUID.randomUUID()))
        assertEquals(ForegroundAcknowledgmentStaging.Rejected, f.verifier.recordExplicitAction(reused))
        assertEquals(ForegroundAcknowledgmentResult.NewlyVerified(f.request), f.verifier.verify(f.request))
    }

    @Test
    fun lostCommitAcknowledgmentIsIndeterminateAndReplayIsNeverFresh() = runBlocking {
        val f = fixture()
        val presentation = f.verifier.present(f.request)
        assertEquals(ForegroundAcknowledgmentStaging.Staged, f.verifier.recordExplicitAction(presentation))
        f.failAt = ForegroundWitnessAcknowledgmentVerifier.WritePoint.COMMITTED
        assertEquals(ForegroundAcknowledgmentResult.Indeterminate, f.verifier.verify(f.request))
        f.failAt = null
        assertEquals(ForegroundAcknowledgmentResult.AlreadyConsumed, f.verifier.verify(f.request))
    }

    @Test
    fun stagedWriteUncertaintyNeverFeedsWitnessAuthorization() = runBlocking {
        val f = fixture()
        val presentation = f.verifier.present(f.request)
        f.failAt = ForegroundWitnessAcknowledgmentVerifier.WritePoint.COMMITTED
        assertEquals(ForegroundAcknowledgmentStaging.Indeterminate, f.verifier.recordExplicitAction(presentation))
        f.failAt = null
        assertTrue(
            f.verifier.verify(f.request) in setOf(
                ForegroundAcknowledgmentResult.Required,
                ForegroundAcknowledgmentResult.Rejected,
            ),
        )
        assertTrue(f.file.isFile)
        assertEquals(1, f.authentication.creates)
    }

    @Test
    fun lostStagingAndAuthenticatorCannotRetryTheSameExplicitActionAsFirstInstall() = runBlocking {
        val f = fixture()
        val presentation = f.verifier.present(f.request)
        assertEquals(ForegroundAcknowledgmentStaging.Staged, f.verifier.recordExplicitAction(presentation))
        assertTrue(f.file.delete())
        f.authentication.key = null

        assertEquals(
            ForegroundAcknowledgmentStaging.Indeterminate,
            f.verifier.recordExplicitAction(presentation),
        )
        assertEquals(ForegroundAcknowledgmentResult.Indeterminate, f.verifier.verify(f.request))
        assertFalse(f.file.exists())
        assertEquals(1, f.authentication.creates)
    }

    @Test
    fun corruptLedgerAndMissingIndependentKeyFailClosedWithoutRegeneration() = runBlocking {
        for (removeKey in listOf(false, true)) {
            val f = fixture()
            val presentation = f.verifier.present(f.request)
            assertEquals(ForegroundAcknowledgmentStaging.Staged, f.verifier.recordExplicitAction(presentation))
            if (removeKey) f.authentication.key = null
            else f.database { it.execSQL("UPDATE acknowledgment_head SET sequence=99") }
            assertEquals(ForegroundAcknowledgmentResult.Indeterminate, f.verifier.verify(f.request))
            assertEquals(1, f.authentication.creates)
        }
    }

    @Test
    fun stagedConsumedAndInvalidatedHistoryRoundTripsExactCodecAndReopens() = runBlocking {
        val f = fixture()
        val consumed = f.verifier.present(f.request)
        assertEquals(ForegroundAcknowledgmentStaging.Staged, f.verifier.recordExplicitAction(consumed))
        assertEquals(
            ForegroundAcknowledgmentResult.Rejected,
            f.verifier(processId = UUID.randomUUID()).verify(f.request),
        )
        assertEquals(ForegroundAcknowledgmentResult.NewlyVerified(f.request), f.verifier.verify(f.request))

        val invalidatedRequest = f.request.copy(
            operation = WitnessOperation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            acknowledgmentId = UUID.randomUUID(),
        )
        val invalidated = f.verifier.present(invalidatedRequest)
        assertEquals(ForegroundAcknowledgmentStaging.Staged, f.verifier.recordExplicitAction(invalidated))
        f.verifier.invalidate(invalidated)
        val reopened = f.verifier(processId = UUID.randomUUID())
        assertEquals(ForegroundAcknowledgmentResult.AlreadyConsumed, reopened.verify(f.request))
        assertEquals(ForegroundAcknowledgmentResult.Rejected, reopened.verify(invalidatedRequest))

        f.database { db ->
            db.rawQuery("SELECT sequence,payload,mac FROM acknowledgment_history ORDER BY sequence", null).use { rows ->
                val expected = listOf(
                    f.request to "STAGED", f.request to "CONSUMED",
                    invalidatedRequest to "STAGED", invalidatedRequest to "INVALIDATED",
                )
                var previousMac = ByteArray(32)
                for ((index, entry) in expected.withIndex()) {
                    assertTrue(rows.moveToNext())
                    assertEquals(index + 1L, rows.getLong(0))
                    DataInputStream(ByteArrayInputStream(rows.getBlob(1))).use { input ->
                        assertEquals("MeshCoreOne.notification-ack.history.v1", input.readUTF())
                        assertEquals(index + 1L, input.readLong())
                        val requestBytes = ByteArray(input.readInt()).also { input.readFully(it) }
                        DataInputStream(ByteArrayInputStream(requestBytes)).use { request ->
                            assertEquals("MeshCoreOne.notification-ack.request.v1", request.readUTF())
                            assertEquals(entry.first.operation.operationId, request.readUuid())
                            assertEquals(entry.first.operation.storeId, request.readUuid())
                            assertEquals(entry.first.operation.creationNonce, request.readUuid())
                            assertEquals(entry.first.acknowledgmentId, request.readUuid())
                            assertFalse(request.readBoolean())
                            assertEquals(0, request.available())
                        }
                        val warningBytes = ByteArray(input.readInt()).also { input.readFully(it) }
                        DataInputStream(ByteArrayInputStream(warningBytes)).use { warning ->
                            assertEquals("MeshCoreOne.notification-ack.warning.v1", warning.readUTF())
                            assertEquals(f.warning.id, warning.readUTF())
                            assertEquals(f.warning.version, warning.readInt())
                            assertEquals(f.warning.renderedCopySha256, warning.readUTF())
                            assertEquals(0, warning.available())
                        }
                        val presentation = if (index < 2) consumed else invalidated
                        assertEquals(presentation.presentationId, input.readUuid())
                        assertEquals(presentation.processId, input.readUuid())
                        assertEquals(entry.second, input.readUTF())
                        assertEquals(32, input.available())
                        val rawPreviousMac = ByteArray(32).also { input.readFully(it) }
                        assertTrue(previousMac.contentEquals(rawPreviousMac))
                        assertEquals(0, input.available())
                    }
                    previousMac = rows.getBlob(2)
                }
                assertFalse(rows.moveToNext())
            }
        }
    }

    @Test
    fun foreignNullableHistoryAndHeadBlobsFailClosedAsTypedCorruption() = runBlocking {
        for ((table, column) in listOf(
            "acknowledgment_history" to "payload",
            "acknowledgment_history" to "mac",
            "acknowledgment_head" to "mac",
        )) {
            val f = fixture()
            val presentation = f.verifier.present(f.request)
            assertEquals(ForegroundAcknowledgmentStaging.Staged, f.verifier.recordExplicitAction(presentation))
            f.database { db ->
                if (table == "acknowledgment_history") {
                    db.execSQL("CREATE TABLE altered(sequence INTEGER PRIMARY KEY, payload BLOB, mac BLOB)")
                    db.execSQL(
                        "INSERT INTO altered SELECT sequence," +
                            "${if (column == "payload") "NULL" else "payload"}," +
                            "${if (column == "mac") "NULL" else "mac"} FROM acknowledgment_history",
                    )
                } else {
                    db.execSQL(
                        "CREATE TABLE altered(singleton INTEGER PRIMARY KEY CHECK(singleton=1), " +
                            "sequence INTEGER NOT NULL, mac BLOB)",
                    )
                    db.execSQL("INSERT INTO altered SELECT singleton,sequence,NULL FROM acknowledgment_head")
                }
                db.execSQL("DROP TABLE $table")
                db.execSQL("ALTER TABLE altered RENAME TO $table")
                db.rawQuery("PRAGMA quick_check", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("ok", it.getString(0))
                }
            }
            assertEquals(ForegroundAcknowledgmentResult.Indeterminate, f.verifier.verify(f.request))
            assertEquals(StorageProblem.CorruptSecretState, f.failures.last().problem)
            assertEquals(
                ForegroundAcknowledgmentResult.Indeterminate,
                f.verifier(processId = UUID.randomUUID()).verify(f.request),
            )
            f.database { db ->
                db.rawQuery("SELECT COUNT(*) FROM acknowledgment_history", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(1, it.getInt(0))
                }
            }
            assertEquals(1, f.authentication.creates)
        }
    }

    @Test
    fun unavailablePlatformKeyStoreLookupIsATypedReadFailure() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.os.UserManager::class.java))
            .setUserUnlocked(true)
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java))
            .setIsDeviceLocked(false)
        for ((failure, problem) in listOf(
            java.security.KeyStoreException("unavailable") to StorageProblem.ProviderFailure,
            java.security.ProviderException("unavailable") to StorageProblem.ProviderFailure,
            IOException("unavailable") to StorageProblem.IoFailure,
        )) {
            val authentication = AndroidAcknowledgmentAuthentication(context) { throw failure }
            val reported = assertFailsWith<StorageFailure> { authentication.hasKey() }
            assertEquals(problem, reported.problem)
            assertEquals(StorageOperation.READ, reported.operation)
        }
    }

    @Test
    fun consumedAcknowledgmentSurvivesIndependentWitnessDatabaseAndKeyLoss() = runBlocking {
        val f = fixture()
        val witnessFile = File(f.root, "witness.db")
        val witnessAuthentication = TestWitnessAuthentication()
        val witness = NotificationEnrollmentWitness(
            witnessFile,
            witnessAuthentication,
            f.verifier,
            StorageIssueReporter { _ -> },
            synchronize = f::synchronize,
        )
        val presentation = f.verifier.present(f.request)
        assertEquals(ForegroundAcknowledgmentStaging.Staged, f.verifier.recordExplicitAction(presentation))
        assertIs<WitnessMutation.Stored>(witness.authorize(f.request))
        assertTrue(witnessFile.isFile)
        assertEquals(1, witnessAuthentication.created)

        assertTrue(witnessFile.delete())
        witnessAuthentication.keys.clear()
        assertEquals(ForegroundAcknowledgmentResult.AlreadyConsumed, f.verifier.verify(f.request))
        assertEquals(
            WitnessMutation.NotRetained(WitnessProblem.ACKNOWLEDGMENT_REQUIRED),
            witness.authorize(f.request),
        )
        assertFalse(witnessFile.exists())
        assertEquals(1, witnessAuthentication.created)
    }

    @Test
    fun verifierNeverRecreatesLedgerWhenItsExistingKeyOrArchiveIsLost() = runBlocking {
        val f = fixture()
        val presentation = f.verifier.present(f.request)
        assertEquals(ForegroundAcknowledgmentStaging.Staged, f.verifier.recordExplicitAction(presentation))
        assertTrue(f.file.delete())
        assertEquals(ForegroundAcknowledgmentResult.Indeterminate, f.verifier.verify(f.request))
        assertFalse(f.file.exists())
        assertEquals(1, f.authentication.creates)
    }

    private fun fixture() = Fixture(temporary.newFolder())

    private fun DataInputStream.readUuid() = UUID(readLong(), readLong())

    private inner class Fixture(val root: File) {
        val file = File(root, "ack.db")
        val operation = WitnessOperation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val request = WitnessAuthorizationRequest(operation, UUID.randomUUID(), null)
        val warning = ForegroundAcknowledgmentWarning.fromRenderedCopy(
            id = "notification-enrollment-loss",
            version = 1,
            renderedCopy = "Acknowledging this loss does not recover previous notification data.",
        )
        val authentication = TestAcknowledgmentAuthentication()
        var failAt: ForegroundWitnessAcknowledgmentVerifier.WritePoint? = null
        @Volatile var synchronizationGate: SynchronizationGate? = null
        @Volatile var admissionGate: AdmissionGate? = null
        @Volatile var writeGate: WriteGate? = null
        val failures = mutableListOf<StorageFailure>()
        val verifier = verifier()

        fun verifier(
            warning: ForegroundAcknowledgmentWarning = this.warning,
            processId: UUID = UUID.randomUUID(),
        ) = ForegroundWitnessAcknowledgmentVerifier(
            file,
            warning,
            authentication,
            StorageIssueReporter { failures.add(it) },
            processId = processId,
            fault = {
                writeGate?.takeIf { gate -> gate.point == it }?.let { gate ->
                    gate.entered.countDown()
                    if (!gate.release.await(10, TimeUnit.SECONDS)) {
                        throw IOException("timed out waiting for acknowledgment write release")
                    }
                    writeGate = null
                }
                if (it == failAt) throw IOException("injected acknowledgment write uncertainty")
            },
            synchronize = ::synchronize,
            beforeAdmission = ::beforeAdmission,
        )

        fun beforeAdmission(point: ForegroundWitnessAcknowledgmentVerifier.AdmissionPoint) {
            val gate = admissionGate?.takeIf { it.point == point } ?: return
            gate.entered.countDown()
            if (!gate.release.await(10, TimeUnit.SECONDS)) {
                throw IOException("timed out waiting for acknowledgment admission release")
            }
            admissionGate = null
        }

        fun synchronize(file: File) {
            synchronizationGate?.let { gate ->
                gate.entered.countDown()
                if (!gate.release.await(10, TimeUnit.SECONDS)) {
                    throw IOException("timed out waiting for acknowledgment synchronization release")
                }
                synchronizationGate = null
            }
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { it.force(true) }
            FileChannel.open(file.parentFile.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }

        fun database(action: (SQLiteDatabase) -> Unit) =
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use(action)

        fun record() = EnrollmentWitnessRecord(operation, UUID.randomUUID(), 1, WitnessPhase.AUTHORIZED, null)
    }

    private class SynchronizationGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    private class WriteGate(val point: ForegroundWitnessAcknowledgmentVerifier.WritePoint) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    private class AdmissionGate(
        val point: ForegroundWitnessAcknowledgmentVerifier.AdmissionPoint,
    ) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    private class TestAcknowledgmentAuthentication : AcknowledgmentAuthentication {
        var key: SecretKey? = null
        var creates = 0

        override fun requireAccessible() = Unit
        override fun hasKey(): Boolean = key != null
        override fun createKey() {
            check(key == null)
            key = freshKey()
            creates++
        }

        override fun authenticate(bytes: ByteArray): ByteArray {
            val current = key ?: throw StorageFailure(StorageProblem.FormerKeyMissing, StorageOperation.READ)
            return Mac.getInstance("HmacSHA256").run {
                init(current)
                doFinal(bytes)
            }
        }

        private fun freshKey(): SecretKey =
            KeyGenerator.getInstance("HmacSHA256").apply { init(256) }.generateKey()
    }

    private class TestWitnessAuthentication : WitnessAuthentication {
        val keys = mutableMapOf<UUID, SecretKey>()
        var created = 0

        override fun requireAccessible() = Unit
        override fun createNew(witnessId: UUID) {
            check(witnessId !in keys)
            keys[witnessId] = KeyGenerator.getInstance("HmacSHA256").apply { init(256) }.generateKey()
            created++
        }

        override fun authenticate(witnessId: UUID, bytes: ByteArray): ByteArray {
            val key = keys[witnessId]
                ?: throw StorageFailure(StorageProblem.FormerKeyMissing, StorageOperation.READ)
            return Mac.getInstance("HmacSHA256").run {
                init(key)
                doFinal(bytes)
            }
        }
    }
}
