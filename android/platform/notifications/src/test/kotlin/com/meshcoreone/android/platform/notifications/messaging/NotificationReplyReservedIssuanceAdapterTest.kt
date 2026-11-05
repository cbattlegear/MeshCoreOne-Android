// AndroidOnly: WP-401 Real bounded reply reservation before PendingIntent exposure; no runtime activation.
package com.meshcoreone.android.platform.notifications.messaging

import android.app.Application
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.Failure
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.IssuanceResult
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.IssuedRecord
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.StageResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class NotificationReplyReservedIssuanceAdapterTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val file = File(context.cacheDir, "reserved-producer-${UUID.randomUUID()}.db")
    private val limits = NotificationReplyLimits(1, 10_000, 10_000)
    private val issuance = NotificationReplyStagingStore.IssuanceLimits(10, 10_000, 100_000)
    private val operation = NotificationReplyEnrollmentStore.EnrollmentOperation(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
    )
    private val action = NotificationReplyStagingStore.IssuedAction(
        UUID.randomUUID(), NotificationReplyPost(1, UUID.randomUUID(), "REPLY"), NotificationId("logical"),
        NotificationPayload.DirectMessage(EntityKey(RadioId(UUID(0, 1)), UUID(0, 2)), UUID(0, 3)),
    )
    private val raw = " \n\u0000\uD800e\u0301 "
    private lateinit var receipt: NotificationReplyEnrollmentStore.CreationReceipt
    private val enrollment get() = NotificationReplyEnrollmentStore(
        file, limits, 100_000, issuance, NotificationReplyLimits(1, 10_000, 10_000),
    )
    private val store get() = enrollment.boundJournal(receipt)

    @After
    fun cleanup() { SQLiteDatabase.deleteDatabase(file) }

    @Test
    fun exactRetryKeepsRealTokenAndQuotaWhileFullJournalCannotExposeAnotherAction() = runTest {
        provision()
        val adapter = NotificationReplyIssuanceAdapter(context, operation.storeId, store)
        val first = adapter.prepareReserved(action, raw.length) as NotificationReplyPreparation.Prepared
        val retry = adapter.prepareReserved(action, raw.length) as NotificationReplyPreparation.Prepared
        assertEquals(first.issuance, retry.issuance)
        assertEquals(raw.length, first.issuance.maxReplyCodeUnits)
        assertEquals(first.pendingIntent, retry.pendingIntent)
        val another = action.copy(token = UUID.randomUUID(), post = action.post.copy(postId = UUID.randomUUID()))
        assertEquals(NotificationReplyPreparation.NotIssued(Failure.RECORD_CAPACITY),
            adapter.prepareReserved(another, raw.length))
        assertNoToken(another)
        val input = deliver(adapter, first.pendingIntent, raw)
        val staged = store.admitIssued(input.storeId, input.token, input.exactText) as StageResult.Staged
        assertEquals(StageResult.AlreadyStaged(staged.identity, staged.outgoingMessageId),
            store.admitIssued(input.storeId, input.token, input.exactText))
        first.pendingIntent.cancel()
        assertEquals(NotificationReplyPreparation.NotIssued(Failure.REPLY_RESERVATION_CONSUMED),
            adapter.prepareReserved(action, raw.length))
        assertNoToken(action)
        val retained = (store.read(operation.storeId) as NotificationReplyStagingStore.ReadResult.Inventory).entries.single()
        assertEquals(raw, retained.exactText)
    }

    @Test
    fun conflictingBoundAndRegistryOnlyTokensNeverUpgradeOrDowngrade() = runTest {
        provision()
        val adapter = NotificationReplyIssuanceAdapter(context, operation.storeId, store)
        val legacy = adapter.prepare(action) as NotificationReplyPreparation.Prepared
        assertNull(legacy.issuance.maxReplyCodeUnits)
        assertEquals(NotificationReplyPreparation.NotIssued(Failure.ISSUANCE_CONFLICT),
            adapter.prepareReserved(action, raw.length))
        val bounded = action.copy(token = UUID.randomUUID(), post = action.post.copy(postId = UUID.randomUUID()))
        assertTrue(adapter.prepareReserved(bounded, raw.length) is NotificationReplyPreparation.Prepared)
        assertEquals(NotificationReplyPreparation.NotIssued(Failure.ISSUANCE_CONFLICT),
            adapter.prepareReserved(bounded, raw.length + 1))
        assertEquals(NotificationReplyPreparation.NotIssued(Failure.ISSUANCE_CONFLICT), adapter.prepare(bounded))
        assertEquals(NotificationReplyPreparation.NotIssued(Failure.ISSUANCE_CONFLICT),
            adapter.prepareReserved(bounded.copy(logicalNotificationId = NotificationId("other")), raw.length))
    }

    @Test
    fun oversizedRawInputIsNotTruncatedAndDoesNotConsumeReservation() = runTest {
        provision()
        val adapter = NotificationReplyIssuanceAdapter(context, operation.storeId, store)
        val prepared = adapter.prepareReserved(action, raw.length) as NotificationReplyPreparation.Prepared
        val oversized = deliver(adapter, prepared.pendingIntent, raw + "\uD800")
        assertEquals(raw + "\uD800", oversized.exactText)
        assertEquals(StageResult.Rejected(Failure.REPLY_TEXT_BOUND_EXCEEDED),
            store.admitIssued(oversized.storeId, oversized.token, oversized.exactText))
        assertTrue(adapter.prepareReserved(action, raw.length) is NotificationReplyPreparation.Prepared)
        val valid = deliver(adapter, prepared.pendingIntent, raw)
        assertTrue(store.admitIssued(valid.storeId, valid.token, valid.exactText) is StageResult.Staged)
    }

    @Test
    fun everyRejectedAndIndeterminateResultExposesNoActionAndNeverMintsReplacement() = runTest {
        for (failure in Failure.entries) {
            var calls = 0
            val adapter = injected { expected, proposal, bound ->
                calls++
                assertEquals(operation.storeId, expected)
                assertEquals(action, proposal)
                assertEquals(raw.length, bound)
                IssuanceResult.Rejected(failure)
            }
            assertEquals(NotificationReplyPreparation.NotIssued(failure), adapter.prepareReserved(action, raw.length))
            assertEquals(1, calls)
            assertNoToken(action)
        }
        var calls = 0
        val ambiguous = injected { _, proposal, _ -> calls++; IssuanceResult.Indeterminate(proposal.token) }
        repeat(2) {
            assertEquals(NotificationReplyPreparation.Indeterminate(action.token), ambiguous.prepareReserved(action, raw.length))
            assertNoToken(action)
        }
        assertEquals(2, calls)
    }

    @Test
    fun exactReceiptValidationRejectsNullWrongBoundStoreTokenPostAndPayloadBeforeTokenCreation() = runTest {
        val record = IssuedRecord(operation.storeId, 1, action, raw.length)
        for (wrong in listOf(
            record.copy(maxReplyCodeUnits = null), record.copy(maxReplyCodeUnits = raw.length + 1),
            record.copy(storeId = UUID.randomUUID()), record.copy(action = action.copy(token = UUID.randomUUID())),
            record.copy(action = action.copy(post = action.post.copy(action = "MARK_READ"))),
            record.copy(action = action.copy(payload = NotificationPayload.LowBattery(1))),
        )) {
            for (already in listOf(false, true)) {
                val adapter = injected { _, _, _ ->
                    if (already) IssuanceResult.AlreadyReserved(wrong) else IssuanceResult.Reserved(wrong)
                }
                assertEquals(NotificationReplyPreparation.NotIssued(Failure.CORRUPT_STORAGE),
                    adapter.prepareReserved(action, raw.length))
                assertNoToken(action)
            }
        }
    }

    @Test
    fun awaitingCommitAndCancellationCannotExposePendingIntent() = runTest {
        val gate = CompletableDeferred<IssuanceResult>()
        val adapter = injected { _, _, _ -> gate.await() }
        val preparing = async { adapter.prepareReserved(action, raw.length) }
        runCurrent()
        assertFalse(preparing.isCompleted)
        assertNoToken(action)
        preparing.cancel()
        preparing.join()
        assertNoToken(action)
        val cancelled = injected { _, _, _ -> throw CancellationException("Commit may be uncertain") }
        try {
            cancelled.prepareReserved(action, raw.length)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertNoToken(action)
        }
    }

    @Test
    fun realLostReservationReturnReconcilesSameRecordAndNoReplacementToken() = runTest {
        provision()
        val faulting = NotificationReplyStagingStore(file, limits, expectedEnrollment = receipt,
            afterIssuanceCommit = { throw SQLiteException("lost commit return") })
        val adapter = NotificationReplyIssuanceAdapter(context, operation.storeId, faulting)
        val result = adapter.prepareReserved(action, raw.length) as NotificationReplyPreparation.Prepared
        val retry = NotificationReplyIssuanceAdapter(context, operation.storeId, store)
            .prepareReserved(action, raw.length) as NotificationReplyPreparation.Prepared
        assertEquals(result.issuance, retry.issuance)
        assertEquals(result.pendingIntent, retry.pendingIntent)
        assertEquals(action.token, result.issuance.action.token)
    }

    @Test
    fun realPostCommitStorageLossIsIndeterminateAndNeverCreatesAnActionOrMissingStore() = runTest {
        provision()
        val faulting = NotificationReplyStagingStore(file, limits, expectedEnrollment = receipt,
            afterIssuanceCommit = {
                SQLiteDatabase.deleteDatabase(file)
                throw SQLiteException("lost storage after commit")
            })
        val adapter = NotificationReplyIssuanceAdapter(context, operation.storeId, faulting)
        assertEquals(NotificationReplyPreparation.Indeterminate(action.token), adapter.prepareReserved(action, raw.length))
        assertNoToken(action)
        assertFalse(file.exists())
        assertEquals(NotificationReplyPreparation.NotIssued(Failure.MISSING_STORAGE),
            adapter.prepareReserved(action, raw.length))
        assertNoToken(action)
        assertFalse(file.exists())
    }

    @Test
    fun invalidBoundsStaleStoreAndMissingStorageNeverExposeAnAction() = runTest {
        val invalid = injected { _, _, _ -> error("Invalid bounds must not reach storage") }
        for (bound in listOf(0, -1, Int.MAX_VALUE)) {
            assertEquals(NotificationReplyPreparation.NotIssued(Failure.INVALID_REPLY_TEXT_BOUND),
                invalid.prepareReserved(action, bound))
            assertNoToken(action)
        }
        provision()
        val stale = NotificationReplyIssuanceAdapter(context, UUID.randomUUID(), store)
        assertEquals(NotificationReplyPreparation.NotIssued(Failure.IDENTITY_MISMATCH),
            stale.prepareReserved(action, raw.length))
        assertNoToken(action)
        cleanup()
        assertEquals(NotificationReplyPreparation.NotIssued(Failure.MISSING_STORAGE),
            NotificationReplyIssuanceAdapter(context, operation.storeId, store).prepareReserved(action, raw.length))
        assertNoToken(action)
        assertFalse(file.exists())
    }

    private fun injected(reserve: suspend (UUID, NotificationReplyStagingStore.IssuedAction, Int) -> IssuanceResult) =
        NotificationReplyIssuanceAdapter(context, operation.storeId, reserve) { _, _ ->
            error("Bounded preparation must not call registry-only registration")
        }

    private fun assertNoToken(proposal: NotificationReplyStagingStore.IssuedAction) {
        val identity = Intent(context, UnactivatedIssuedReplyReceiver::class.java)
            .setAction(NotificationReplyIssuanceAdapter.ACTION)
            .setData(android.net.Uri.parse(
                "meshcore-notification://issued-reply/v1/${operation.storeId}/${proposal.token}",
            ))
        assertNull(PendingIntent.getBroadcast(context, 0, identity, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE))
    }

    private fun deliver(
        adapter: NotificationReplyIssuanceAdapter, pending: PendingIntent, text: String,
    ): NotificationReplyDeliveryParsing.ReadyForAdmission {
        val fillIn = Intent()
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(NotificationActionReceiver.REMOTE_INPUT_KEY).build()),
            fillIn, Bundle().apply { putCharSequence(NotificationActionReceiver.REMOTE_INPUT_KEY, text) },
        )
        pending.send(context, 0, fillIn)
        return adapter.parseDelivery(shadowOf(context).broadcastIntents.last()) as
            NotificationReplyDeliveryParsing.ReadyForAdmission
    }

    private suspend fun provision() {
        // Test-created witness is NOT production installation/authentication evidence.
        val preparing = (enrollment.prepare(operation) as NotificationReplyEnrollmentStore.EnrollmentResult.Preparing).receipt
        receipt = (enrollment.bind(preparing, NotificationReplyEnrollmentStore.WitnessAcceptance(
            preparing.operation, preparing.creationNonce, UUID.randomUUID(),
        )) as NotificationReplyEnrollmentStore.EnrollmentResult.Bound).receipt
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready,
            store.initializeReplyReservations(operation.storeId))
    }
}
