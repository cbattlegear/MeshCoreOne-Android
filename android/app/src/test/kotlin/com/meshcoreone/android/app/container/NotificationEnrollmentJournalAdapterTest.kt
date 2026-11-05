// AndroidOnly: WP-303 Real SQLite coordinator journal identity and noncreation regression tests.
package com.meshcoreone.android.app.container

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator as Enrollment
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyLimits
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NotificationEnrollmentJournalAdapterTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val operation = Enrollment.Operation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    private val file = File(context.noBackupFilesDir, "notification-reply-generation-${operation.storeId}.db")
    private val limits = NotificationReplyLimits(3, 10_000, 30_000)
    private val journal get() = NotificationEnrollmentJournalAdapter(
        context, limits, 30_000, NotificationReplyStagingStore.IssuanceLimits(3, 10_000, 30_000), limits,
    )

    @After fun cleanup() { SQLiteDatabase.deleteDatabase(file) }

    @Test fun missingReconcileDoesNotCreateStorageOrInferFreshness() = runTest {
        assertEquals(Enrollment.JournalResult.Indeterminate(operation),
            journal.reconcile(operation, operation.creationNonce))
        assertFalse(file.exists())
    }

    @Test fun lostPreparedStorageRetainsUncertaintyWithoutAllocatingReplacement() = runTest {
        assertIs<Enrollment.JournalResult.Observed>(journal.prepare(operation))
        assertTrue(SQLiteDatabase.deleteDatabase(file))
        repeat(2) {
            assertEquals(Enrollment.JournalResult.Indeterminate(operation),
                journal.reconcile(operation, operation.creationNonce))
            assertFalse(file.exists())
        }
    }

    @Test fun corruptPreparedStorageRetainsUncertaintyAndDoesNotRepairItsBytes() = runTest {
        assertIs<Enrollment.JournalResult.Observed>(journal.prepare(operation))
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE reply_enrollment SET digest = X'00' WHERE singleton = 1")
        }
        val corrupt = file.readBytes()
        repeat(2) {
            assertEquals(Enrollment.JournalResult.Indeterminate(operation),
                journal.reconcile(operation, operation.creationNonce))
            assertTrue(corrupt.contentEquals(file.readBytes()))
        }
    }

    @Test fun replacedPreparedStorageCannotResolveTheOriginalOperationOrBeOverwritten() = runTest {
        assertIs<Enrollment.JournalResult.Observed>(journal.prepare(operation))
        assertTrue(SQLiteDatabase.deleteDatabase(file))
        val foreign = operation.copy(operationId = UUID.randomUUID(), creationNonce = UUID.randomUUID())
        val foreignReceipt = assertIs<Enrollment.JournalResult.Observed>(journal.prepare(foreign))
        repeat(2) {
            assertEquals(Enrollment.JournalResult.Indeterminate(operation),
                journal.reconcile(operation, operation.creationNonce))
            assertEquals(foreignReceipt, journal.reconcile(foreign, foreign.creationNonce))
        }
    }

    @Test fun mismatchedExpectedNonceIsInvalidInputWithoutCreatingStorage() = runTest {
        assertEquals(Enrollment.JournalResult.NotRetained(Enrollment.Failure.IDENTITY_MISMATCH),
            journal.reconcile(operation, UUID.randomUUID()))
        assertFalse(file.exists())
    }

    @Test fun exactOperationNonceWitnessAndRevisionsSurviveRealSQLiteRoundTrip() = runTest {
        val preparing = assertIs<Enrollment.JournalResult.Observed>(journal.prepare(operation)).receipt
        assertEquals(Enrollment.CreationReceipt(operation, 1, Enrollment.Phase.PREPARING,
            operation.creationNonce, null), preparing)
        val witness = UUID.randomUUID()
        val bound = assertIs<Enrollment.JournalResult.Observed>(journal.bind(preparing, witness)).receipt
        assertEquals(preparing.copy(revision = 2, phase = Enrollment.Phase.BOUND, witnessId = witness), bound)
        assertEquals(Enrollment.JournalResult.Observed(bound), journal.reconcile(operation, operation.creationNonce))
        assertEquals(Enrollment.JournalResult.Observed(bound), journal.bind(preparing, witness))
        assertEquals(Enrollment.JournalResult.NotRetained(Enrollment.Failure.IDENTITY_MISMATCH),
            journal.bind(preparing, UUID.randomUUID()))
        assertEquals(Enrollment.JournalResult.Observed(bound), journal.reconcile(operation, operation.creationNonce))
    }

    @Test fun changedNonceCannotAdoptOrReplaceRealGeneration() = runTest {
        val preparing = journal.prepare(operation)
        val replacement = operation.copy(creationNonce = UUID.randomUUID())
        assertEquals(Enrollment.JournalResult.NotRetained(Enrollment.Failure.IDENTITY_MISMATCH),
            journal.reconcile(replacement, replacement.creationNonce))
        assertEquals(Enrollment.JournalResult.NotRetained(Enrollment.Failure.IDENTITY_MISMATCH),
            journal.prepare(replacement))
        assertEquals(preparing, journal.reconcile(operation, operation.creationNonce))
    }
}
