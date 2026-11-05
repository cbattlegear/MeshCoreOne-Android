// AndroidOnly: WP-401 Unactivated issued-reply PendingIntent and actual journal admission host controls.
package com.meshcoreone.android.platform.notifications.messaging

import android.app.Application
import android.app.RemoteInput
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyDeliveryParsing.ParseFailure
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.Failure
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.InitializeResult
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.InstallationResult
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.IssuanceResult
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.IssuedAction
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.IssuedRecord
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.RecoveryEntry
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class NotificationReplyIssuanceAdapterTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val file = File(context.cacheDir, "reply-adapter-${UUID.randomUUID()}.db")
    private val payload = NotificationPayload.DirectMessage(
        EntityKey(RadioId(UUID(0, 1)), UUID(0, 2)), UUID(0, 3),
    )
    private val raw = "  exact reply\n" + String(charArrayOf('\uD800', '\u0000')) + "  "
    private fun proposal(token: Long = 1) = IssuedAction(
        UUID(1, token), NotificationReplyPost(1, UUID(2, token), "REPLY"),
        NotificationId("same-notification"), payload,
    )

    @After
    fun cleanup() {
        if (file.exists()) assertTrue(SQLiteDatabase.deleteDatabase(file))
    }

    @Test
    fun noTokenIsExposedBeforeExactCommittedReservationAndCancellationDoesNotRetry() = runTest {
        val storeId = UUID(0, 100)
        val gate = CompletableDeferred<IssuanceResult>()
        var calls = 0
        val adapter = NotificationReplyIssuanceAdapter(context, storeId) { expected, action ->
            assertEquals(storeId, expected)
            assertEquals(proposal(), action)
            calls++
            gate.await()
        }
        val preparing = async { adapter.prepare(proposal()) }
        runCurrent()
        assertEquals(1, calls)
        assertFalse(preparing.isCompleted)
        assertTrue(shadowOf(context).broadcastIntents.isEmpty())
        gate.complete(IssuanceResult.Reserved(IssuedRecord(storeId, 1, proposal())))
        assertTrue(preparing.await() is NotificationReplyPreparation.Prepared)

        val cancelled = NotificationReplyIssuanceAdapter(context, storeId) { _, _ ->
            calls++
            throw CancellationException("Commit outcome must be reconciled by registry")
        }
        try {
            cancelled.prepare(proposal(2))
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(2, calls)
        }
    }

    @Test
    fun rejectedAmbiguousOrMismatchedReservationsNeverReturnAPendingIntent() = runTest {
        val storeId = UUID(0, 100)
        for (failure in Failure.entries) {
            val adapter = NotificationReplyIssuanceAdapter(context, storeId) { _, _ ->
                IssuanceResult.Rejected(failure)
            }
            assertEquals(NotificationReplyPreparation.NotIssued(failure), adapter.prepare(proposal()))
        }
        var calls = 0
        val ambiguous = NotificationReplyIssuanceAdapter(context, storeId) { _, action ->
            calls++
            IssuanceResult.Indeterminate(action.token)
        }
        assertEquals(NotificationReplyPreparation.Indeterminate(proposal().token), ambiguous.prepare(proposal()))
        assertEquals(1, calls)
        for (record in listOf(
            IssuedRecord(storeId, 1, proposal(2)), IssuedRecord(UUID(0, 101), 1, proposal()),
        )) {
            val mismatched = NotificationReplyIssuanceAdapter(context, storeId) { _, _ ->
                IssuanceResult.AlreadyReserved(record)
            }
            assertEquals(NotificationReplyPreparation.NotIssued(Failure.CORRUPT_STORAGE), mismatched.prepare(proposal()))
        }
    }

    @Test
    fun actualRegistryAndMutablePendingIntentPreserveOldTokenRoutingAgainstReissueAndLegacyFillIn() = runTest {
        val (store, storeId) = provisionFixture()
        val adapter = NotificationReplyIssuanceAdapter(context, storeId, store)
        val a = adapter.prepare(proposal()) as NotificationReplyPreparation.Prepared
        val channel = NotificationPayload.ChannelMessage(payload.contact.radioId, 7u, UUID(0, 4))
        val b = adapter.prepare(proposal(2).copy(payload = channel)) as NotificationReplyPreparation.Prepared
        assertNotEquals(a.pendingIntent, b.pendingIntent) // Same requestCode and logical ID, distinct issued data.
        assertTrue(a.pendingIntent.isBroadcast)
        assertFalse(a.pendingIntent.isImmutable)
        val malicious = NotificationPayloadCodec.write(
            Intent().setAction(Intent.ACTION_VIEW).setData(Uri.parse("meshcore-notification://forged")), channel,
        ).putExtra(NotificationActionReceiver.EXTRA_ACTION, NotificationAction.REPLY.rawValue)
        addInput(malicious, raw)
        var first: StageResult.Staged? = null
        for (prepared in listOf(a, b, a)) {
            prepared.pendingIntent.send(context, 0, malicious)
            val delivered = shadowOf(context).broadcastIntents.last()
            assertEquals(NotificationReplyIssuanceAdapter.ACTION, delivered.action)
            assertNull(NotificationPayloadCodec.read(delivered)) // Injected legacy route cannot bypass new admission.
            val parsed = adapter.parseDelivery(delivered) as NotificationReplyDeliveryParsing.ReadyForAdmission
            assertEquals(NotificationReplyDeliveryParsing.ReadyForAdmission(storeId, prepared.issuance.action.token, raw), parsed)
            val admitted = store.admitIssued(parsed.storeId, parsed.token, parsed.exactText)
            if (prepared == a && first != null) {
                assertEquals(StageResult.AlreadyStaged(first.identity, first.outgoingMessageId), admitted)
            } else {
                assertTrue(admitted is StageResult.Staged)
                if (prepared == a) first = admitted as StageResult.Staged
            }
        }
        val retained = inventory(store, storeId).map { it as RecoveryEntry.StagedReply }
        assertEquals(listOf(payload, channel), retained.map { it.payload })
        assertEquals(listOf(a.issuance.action.token, b.issuance.action.token), retained.map { it.issuedToken })
        assertEquals(listOf(raw, raw), retained.map { it.exactText })
        val conflict = store.admitIssued(storeId, a.issuance.action.token, "different") as StageResult.ConflictRetained
        assertEquals(StageResult.ConflictRetained(conflict.identity, true), store.admitIssued(storeId, a.issuance.action.token, "different"))
        assertTrue(inventory(store, storeId).last() is RecoveryEntry.UnboundConflict)
    }

    @Test
    fun duplicateProposalReusesTokenButChangedSameTokenCannotOverwriteIssuedPayload() = runTest {
        val (store, storeId) = provisionFixture()
        val adapter = NotificationReplyIssuanceAdapter(context, storeId, store)
        val original = adapter.prepare(proposal()) as NotificationReplyPreparation.Prepared
        val duplicate = adapter.prepare(proposal()) as NotificationReplyPreparation.Prepared
        assertEquals(original.issuance, duplicate.issuance)
        assertEquals(original.pendingIntent, duplicate.pendingIntent)
        val channel = NotificationPayload.ChannelMessage(payload.contact.radioId, 7u, UUID(0, 4))
        for (changed in listOf(
            proposal().copy(payload = channel), proposal().copy(post = NotificationReplyPost(1, UUID(0, 999), "REPLY")),
            proposal().copy(logicalNotificationId = NotificationId("other")),
        )) {
            assertEquals(NotificationReplyPreparation.NotIssued(Failure.ISSUANCE_CONFLICT), adapter.prepare(changed))
        }
        original.pendingIntent.send(context, 0, addInput(Intent(), raw))
        val parsed = adapter.parseDelivery(shadowOf(context).broadcastIntents.last()) as NotificationReplyDeliveryParsing.ReadyForAdmission
        assertTrue(store.admitIssued(parsed.storeId, parsed.token, parsed.exactText) is StageResult.Staged)
        assertEquals(payload, (inventory(store, storeId).single() as RecoveryEntry.StagedReply).payload)
    }

    @Test
    fun missingLegacyMalformedAndStaleIdentityAreExplicitlyRejected() = runTest {
        val (store, storeId) = provisionFixture()
        val adapter = NotificationReplyIssuanceAdapter(context, storeId, store)
        val issued = adapter.prepare(proposal()) as NotificationReplyPreparation.Prepared
        val base = shadowOf(issued.pendingIntent).savedIntent
        assertEquals(rejected(ParseFailure.MISSING_INPUT), adapter.parseDelivery(base))
        assertEquals(rejected(ParseFailure.MALFORMED_IDENTITY), adapter.parseDelivery(Intent(base).setAction("legacy")))
        assertEquals(rejected(ParseFailure.MALFORMED_IDENTITY),
            adapter.parseDelivery(Intent(base).setData(Uri.parse("meshcore-notification://action/legacy/REPLY"))))
        assertEquals(rejected(ParseFailure.MALFORMED_IDENTITY),
            adapter.parseDelivery(Intent(base).setData(Uri.parse("meshcore-notification://issued-reply/v2/$storeId/${proposal().token}"))))
        assertEquals(rejected(ParseFailure.MISSING_IDENTITY),
            adapter.parseDelivery(Intent(base).apply { removeExtra("mc1.notification.issued-reply.token") }))
        assertEquals(rejected(ParseFailure.MALFORMED_IDENTITY),
            adapter.parseDelivery(Intent(base).putExtra("mc1.notification.issued-reply.token", "not-a-uuid")))
        assertEquals(rejected(ParseFailure.MALFORMED_IDENTITY),
            adapter.parseDelivery(Intent(base).putExtra("mc1.notification.type", "direct")))
        val stale = NotificationReplyIssuanceAdapter(context, UUID(0, 999), store)
        assertEquals(NotificationReplyPreparation.NotIssued(Failure.IDENTITY_MISMATCH), stale.prepare(proposal()))
        assertEquals(rejected(ParseFailure.STALE_INSTALLATION), stale.parseDelivery(base))
    }

    @Test
    fun structuralParsingDoesNotAuthenticateUnknownTokensOrNormalizeRawInput() = runTest {
        val (store, storeId) = provisionFixture()
        val adapter = NotificationReplyIssuanceAdapter(context, storeId, store)
        val issued = adapter.prepare(proposal()) as NotificationReplyPreparation.Prepared
        val base = shadowOf(issued.pendingIntent).savedIntent
        for (text in listOf("", " \n ", raw, "different")) {
            assertEquals(
                NotificationReplyDeliveryParsing.ReadyForAdmission(storeId, proposal().token, text),
                adapter.parseDelivery(addInput(Intent(base), text)),
            )
        }
        assertTrue(inventory(store, storeId).isEmpty()) // Parsing alone never reserves reply bytes or handles an action.
        val unknownToken = UUID(1, 999)
        val forged = addInput(
            Intent(base).putExtra("mc1.notification.issued-reply.token", unknownToken.toString())
                .setData(Uri.parse("meshcore-notification://issued-reply/v1/$storeId/$unknownToken")), raw,
        )
        val parsed = adapter.parseDelivery(forged) as NotificationReplyDeliveryParsing.ReadyForAdmission
        assertEquals(StageResult.Rejected(Failure.ISSUED_TOKEN_NOT_FOUND),
            store.admitIssued(parsed.storeId, parsed.token, parsed.exactText))
        assertTrue(inventory(store, storeId).isEmpty())
    }

    @Test
    fun issuanceCapacityAndFutureReplyCapacityAreSeparateAndMissingStorageNeverBootstraps() = runTest {
        val (store, storeId) = provisionFixture(maxReplies = 1, maxIssues = 2)
        val adapter = NotificationReplyIssuanceAdapter(context, storeId, store)
        val a = adapter.prepare(proposal()) as NotificationReplyPreparation.Prepared
        val b = adapter.prepare(proposal(2)) as NotificationReplyPreparation.Prepared
        assertEquals(NotificationReplyPreparation.NotIssued(Failure.ISSUANCE_RECORD_CAPACITY), adapter.prepare(proposal(3)))
        assertTrue(store.admitIssued(storeId, a.issuance.action.token, raw) is StageResult.Staged)
        assertEquals(StageResult.Rejected(Failure.RECORD_CAPACITY), store.admitIssued(storeId, b.issuance.action.token, "not retained"))
        assertEquals(listOf(raw), inventory(store, storeId).map { it.exactText })
        cleanup()
        assertEquals(NotificationReplyPreparation.NotIssued(Failure.MISSING_STORAGE), adapter.prepare(proposal()))
        assertFalse(file.exists())
    }

    @Test
    fun issuedCapabilityDoesNotPromiseFutureCustodyCapacityOrReplyRetention() = runTest {
        val (store, storeId) = provisionFixture(maxReceiptBytes = 1)
        val adapter = NotificationReplyIssuanceAdapter(context, storeId, store)
        val issued = adapter.prepare(proposal()) as NotificationReplyPreparation.Prepared
        issued.pendingIntent.send(context, 0, addInput(Intent(), raw))
        val parsed = adapter.parseDelivery(shadowOf(context).broadcastIntents.last()) as NotificationReplyDeliveryParsing.ReadyForAdmission
        assertEquals(StageResult.Rejected(Failure.LIFECYCLE_CAPACITY),
            store.admitIssued(parsed.storeId, parsed.token, parsed.exactText))
        assertTrue(inventory(store, storeId).isEmpty())
    }

    @Test
    fun invalidVersionActionAndNonMessageProposalsNeverReachRegistry() = runTest {
        val adapter = NotificationReplyIssuanceAdapter(context, UUID(0, 100)) { _, _ ->
            error("Invalid proposal must not reserve")
        }
        for (post in listOf(NotificationReplyPost(2, UUID(0, 1), "REPLY"), NotificationReplyPost(1, UUID(0, 1), "MARK_READ"))) {
            assertEquals(NotificationReplyPreparation.NotIssued(Failure.INVALID_IDENTITY), adapter.prepare(proposal().copy(post = post)))
        }
        assertEquals(NotificationReplyPreparation.NotIssued(Failure.INVALID_PAYLOAD),
            adapter.prepare(proposal().copy(payload = NotificationPayload.LowBattery(10))))
    }

    private fun addInput(intent: Intent, text: String): Intent {
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(NotificationActionReceiver.REMOTE_INPUT_KEY).build()),
            intent, Bundle().apply { putCharSequence(NotificationActionReceiver.REMOTE_INPUT_KEY, text) },
        )
        return intent
    }

    private suspend fun provisionFixture(
        maxReplies: Int = 10, maxIssues: Int = 10, maxReceiptBytes: Long = 100_000,
    ): Pair<NotificationReplyStagingStore, UUID> {
        // Explicit test-only provisioning. Observing this generated ID is NOT a production installation witness.
        val store = NotificationReplyStagingStore(file, NotificationReplyLimits(maxReplies, 10_000, 100_000))
        assertEquals(InitializeResult.Ready, store.initializeNew())
        val storeId = (store.inspectInstallation(null) as InstallationResult.UnboundExisting).observedStoreId
        assertEquals(InitializeResult.Ready, store.initializeLifecycle(maxReceiptBytes))
        assertEquals(InitializeResult.Ready,
            store.initializeIssuance(storeId, NotificationReplyStagingStore.IssuanceLimits(maxIssues, 10_000, 100_000)))
        return store to storeId
    }

    private suspend fun inventory(store: NotificationReplyStagingStore, storeId: UUID) =
        (store.read(storeId) as NotificationReplyStagingStore.ReadResult.Inventory).entries

    private fun rejected(reason: ParseFailure) = NotificationReplyDeliveryParsing.Rejected(reason)
}
