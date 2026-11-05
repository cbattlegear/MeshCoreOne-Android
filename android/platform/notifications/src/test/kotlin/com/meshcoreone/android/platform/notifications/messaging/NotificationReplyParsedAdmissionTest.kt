// AndroidOnly: WP-215 Inactive parser/journal outcome controls; no live receiver or publication proof.
package com.meshcoreone.android.platform.notifications.messaging

import android.app.Application
import android.app.RemoteInput
import android.content.ClipData
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.Failure
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.StageResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class NotificationReplyParsedAdmissionTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val file = File(context.cacheDir, "inactive-admission-${UUID.randomUUID()}.db")
    private val limits = NotificationReplyLimits(10, 10_000, 100_000)
    private val enrollment = NotificationReplyEnrollmentStore(
        file, limits, 100_000,
        NotificationReplyStagingStore.IssuanceLimits(10, 10_000, 100_000), limits,
    )
    private val operation = NotificationReplyEnrollmentStore.EnrollmentOperation(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
    )
    private val action = NotificationReplyStagingStore.IssuedAction(
        UUID.randomUUID(), NotificationReplyPost(1, UUID.randomUUID(), "REPLY"), NotificationId("logical"),
        NotificationPayload.DirectMessage(EntityKey(RadioId(UUID(0, 1)), UUID(0, 2)), UUID(0, 3)),
    )
    private val raw = " \n\u0000\uD800e\u0301 "
    private lateinit var store: NotificationReplyStagingStore
    private lateinit var parser: NotificationReplyIssuanceAdapter

    @After
    fun cleanup() { SQLiteDatabase.deleteDatabase(file) }

    @Test
    fun exactRawInputAndDuplicateKeepTheRealJournalReceipt() = runTest {
        provision()
        val admission = NotificationReplyAdmission(parser, store)
        val first = admission.admit(input(raw)) as NotificationReplyAdmission.Result.JournalAttempt
        val staged = first.outcome as StageResult.Staged
        assertEquals(raw, first.exactText)
        assertEquals(
            NotificationReplyAdmission.Result.JournalAttempt(
                raw, StageResult.AlreadyStaged(staged.identity, staged.outgoingMessageId),
            ),
            admission.admit(input(raw)),
        )
        val inventory = store.readCopyOnly() as NotificationReplyStagingStore.CopyInventory.Retained
        assertEquals(listOf(raw), inventory.records.map { it.exactText })
    }

    @Test
    fun differentTextOnTheSameTokenRemainsAnExplicitRetainedConflict() = runTest {
        provision()
        val admission = NotificationReplyAdmission(parser, store)
        admission.admit(input(raw))
        val different = "$raw\u0000"
        val conflict = admission.admit(input(different)) as NotificationReplyAdmission.Result.JournalAttempt
        assertEquals(different, conflict.exactText)
        assertTrue(conflict.outcome is StageResult.ConflictRetained)
        val inventory = store.readCopyOnly() as NotificationReplyStagingStore.CopyInventory.Retained
        assertEquals(setOf(raw, different), inventory.records.map { it.exactText }.toSet())
        val repeated = admission.admit(input(different)) as NotificationReplyAdmission.Result.JournalAttempt
        assertTrue((repeated.outcome as StageResult.ConflictRetained).alreadyRetained)
    }

    @Test
    fun unknownTokenHasRawTextButNoRetainedOrSendSuccess() = runTest {
        provision()
        val result = NotificationReplyAdmission(parser, store).admit(input(raw, UUID.randomUUID()))
        assertEquals(
            NotificationReplyAdmission.Result.JournalAttempt(raw, StageResult.Rejected(Failure.ISSUED_TOKEN_NOT_FOUND)),
            result,
        )
        assertEquals(NotificationReplyStagingStore.CopyInventory.Retained(emptyList()), store.readCopyOnly())
    }

    @Test
    fun emptyAndOversizedInputKeepExactTextAndActualStoreRejection() = runTest {
        provision()
        val admission = NotificationReplyAdmission(parser, store)
        for ((text, reason) in listOf(
            "" to Failure.INVALID_TEXT,
            "x".repeat(5_001) to Failure.PRIVATE_DATA_CAPACITY,
        )) {
            assertEquals(
                NotificationReplyAdmission.Result.JournalAttempt(text, StageResult.Rejected(reason)),
                admission.admit(input(text)),
            )
        }
        assertEquals(NotificationReplyStagingStore.CopyInventory.Retained(emptyList()), store.readCopyOnly())
    }

    @Test
    fun malformedAndStaleIdentityNeverInvokeAdmission() = runTest {
        provision()
        var calls = 0
        val admission = NotificationReplyAdmission(parser::parseDelivery) { _, _, _ ->
            calls++
            error("Parsing rejection must not reach storage")
        }
        assertEquals(
            NotificationReplyAdmission.Result.ParsingRejected(
                NotificationReplyDeliveryParsing.ParseFailure.MALFORMED_IDENTITY,
                NotificationReplyDeliveryParsing.InputStatus.READABLE,
                NotificationReplyAdmission.Result.TransientNotRetained(raw),
            ),
            admission.admit(input(raw).setAction("legacy-action")),
        )
        assertEquals(
            NotificationReplyAdmission.Result.ParsingRejected(
                NotificationReplyDeliveryParsing.ParseFailure.STALE_INSTALLATION,
                NotificationReplyDeliveryParsing.InputStatus.READABLE,
                NotificationReplyAdmission.Result.TransientNotRetained(raw),
            ),
            admission.admit(input(raw).putExtra("mc1.notification.issued-reply.store", UUID.randomUUID().toString())),
        )
        assertEquals(0, calls)
    }

    @Test
    fun missingAndEmptyRejectedInputStayExplicitWithoutInventedRecoveryText() = runTest {
        provision()
        var calls = 0
        val admission = NotificationReplyAdmission(parser::parseDelivery) { _, _, _ ->
            calls++
            error("Rejected identity must never reach admission")
        }
        val missing = input(raw).setAction("legacy").apply { clipData = null }
        for ((intent, status) in listOf(
            missing to NotificationReplyDeliveryParsing.InputStatus.MISSING,
            input("").setAction("legacy") to NotificationReplyDeliveryParsing.InputStatus.EMPTY,
        )) {
            assertEquals(
                NotificationReplyAdmission.Result.ParsingRejected(
                    NotificationReplyDeliveryParsing.ParseFailure.MALFORMED_IDENTITY, status, null,
                ),
                admission.admit(intent),
            )
        }
        assertEquals(0, calls)
    }

    @Test
    fun unreadableParcelInputPreservesIdentityErrorWithoutStorageOrEmptyFallback() = runTest {
        provision()
        var calls = 0
        val admission = NotificationReplyAdmission(parser::parseDelivery) { _, _, _ ->
            calls++
            error("Unreadable input must never reach admission")
        }
        val hostileResults = Intent().putExtra(
            "android.remoteinput.resultsData",
            Bundle().apply {
                putParcelable(NotificationActionReceiver.REMOTE_INPUT_KEY, Intent("not-text"))
            },
        )
        val hostile = input(raw).setClipData(ClipData.newIntent("android.remoteinput.results", hostileResults))
        assertEquals(
            NotificationReplyAdmission.Result.ParsingRejected(
                NotificationReplyDeliveryParsing.ParseFailure.MALFORMED_INPUT,
                NotificationReplyDeliveryParsing.InputStatus.MALFORMED, null,
            ),
            admission.admit(hostile),
        )
        assertEquals(
            NotificationReplyAdmission.Result.ParsingRejected(
                NotificationReplyDeliveryParsing.ParseFailure.STALE_INSTALLATION,
                NotificationReplyDeliveryParsing.InputStatus.MALFORMED, null,
            ),
            admission.admit(Intent(hostile).putExtra("mc1.notification.issued-reply.store", UUID.randomUUID().toString())),
        )
        assertEquals(0, calls)
    }

    @Test
    fun outcomeFormattingNeverDisclosesVolatileReplyText() = runTest {
        provision()
        val admission = NotificationReplyAdmission(parser, store)
        val rejected = admission.admit(input(raw).setAction("legacy")) as NotificationReplyAdmission.Result.ParsingRejected
        assertEquals(raw, rejected.transientNotRetained?.exactText)
        assertFalse(rejected.toString().contains(raw))
        assertFalse(rejected.transientNotRetained.toString().contains(raw))
        val retained = admission.admit(input(raw))
        assertFalse(retained.toString().contains(raw))
    }

    @Test
    fun everyTypedStorageRejectionAndUncertainOutcomeRemainUnchanged() = runTest {
        provision()
        val outcomes = Failure.entries.map { StageResult.Rejected(it) } +
            StageResult.Indeterminate(action.post, UUID.randomUUID())
        for (outcome in outcomes) {
            var calls = 0
            val admission = NotificationReplyAdmission(parser::parseDelivery) { storeId, token, exactText ->
                calls++
                assertEquals(operation.storeId, storeId)
                assertEquals(action.token, token)
                assertEquals(raw, exactText)
                outcome
            }
            val result = admission.admit(input(raw)) as NotificationReplyAdmission.Result.JournalAttempt
            assertEquals(raw, result.exactText)
            assertSame(outcome, result.outcome)
            assertEquals(1, calls)
        }
    }

    @Test
    fun cancellationPropagatesWithoutRetryOrInventedOutcome() = runTest {
        provision()
        val cancellation = CancellationException("No journal result available")
        var calls = 0
        val admission = NotificationReplyAdmission(parser::parseDelivery) { _, _, _ ->
            calls++
            throw cancellation
        }
        try {
            admission.admit(input(raw))
            fail("Cancellation must propagate")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
        assertEquals(1, calls)
    }

    @Test
    fun missingStorageIsNotRecreatedOrReportedAsSaved() = runTest {
        provision()
        cleanup()
        assertEquals(
            NotificationReplyAdmission.Result.JournalAttempt(raw, StageResult.Rejected(Failure.MISSING_STORAGE)),
            NotificationReplyAdmission(parser, store).admit(input(raw)),
        )
        assertTrue(!file.exists())
    }

    private fun input(text: String, token: UUID = action.token): Intent =
        Intent(context, UnactivatedIssuedReplyReceiver::class.java)
            .setAction(NotificationReplyIssuanceAdapter.ACTION)
            .setData(Uri.parse("meshcore-notification://issued-reply/v1/${operation.storeId}/$token"))
            .putExtra("mc1.notification.issued-reply.store", operation.storeId.toString())
            .putExtra("mc1.notification.issued-reply.token", token.toString())
            .also { intent ->
                RemoteInput.addResultsToIntent(
                    arrayOf(RemoteInput.Builder(NotificationActionReceiver.REMOTE_INPUT_KEY).build()),
                    intent, Bundle().apply { putCharSequence(NotificationActionReceiver.REMOTE_INPUT_KEY, text) },
                )
            }

    private suspend fun provision() {
        // Fixture-created witness is not production installation or current-generation evidence.
        val preparing = (enrollment.prepare(operation) as NotificationReplyEnrollmentStore.EnrollmentResult.Preparing).receipt
        val receipt = (enrollment.bind(preparing, NotificationReplyEnrollmentStore.WitnessAcceptance(
            preparing.operation, preparing.creationNonce, UUID.randomUUID(),
        )) as NotificationReplyEnrollmentStore.EnrollmentResult.Bound).receipt
        store = enrollment.boundJournal(receipt)
        assertTrue(store.registerIssued(operation.storeId, action) is NotificationReplyStagingStore.IssuanceResult.Reserved)
        parser = NotificationReplyIssuanceAdapter(context, operation.storeId, store)
    }
}
