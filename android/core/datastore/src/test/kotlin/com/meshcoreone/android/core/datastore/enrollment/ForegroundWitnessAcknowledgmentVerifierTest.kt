// AndroidOnly: WP-204 Native SQLite, single-use acknowledgment and uncertainty regressions.
package com.meshcoreone.android.core.datastore.enrollment

import android.database.sqlite.SQLiteDatabase
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
            fault = { if (it == failAt) throw IOException("injected acknowledgment write uncertainty") },
            synchronize = ::synchronize,
        )

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
