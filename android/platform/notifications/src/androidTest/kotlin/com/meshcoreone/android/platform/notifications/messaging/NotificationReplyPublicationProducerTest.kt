// AndroidOnly: WP-401 One-shot notification publication controls; live reply intake remains unactivated.
package com.meshcoreone.android.platform.notifications.messaging

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationActionDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationCategory
import com.meshcoreone.android.core.contracts.notifications.NotificationCategoryDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyPublicationResult.AttemptFailed
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyPublicationResult.AttemptHeld
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyPublicationResult.AttemptReturned
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyPublicationResult.NotAttempted
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.IssuedAction
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.PublicationAttempt
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], shadows = [NotificationReplyPublicationProducerTest.CountingNotificationManager::class])
class NotificationReplyPublicationProducerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val file = File(context.cacheDir, "reply-publication-producer-${UUID.randomUUID()}.db")
    private val replyLimits = NotificationReplyLimits(2, 10_000, 20_000)
    private val issuanceLimits = NotificationReplyStagingStore.IssuanceLimits(4, 10_000, 40_000)
    private val operation = NotificationReplyEnrollmentStore.EnrollmentOperation(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
    )
    private val payload = NotificationPayload.DirectMessage(
        EntityKey(RadioId(UUID(0, 1)), UUID(0, 2)), UUID(0, 3),
    )
    private val request = NotificationRequest(
        NotificationId("logical-message"),
        NotificationCategory.DIRECT_MESSAGE,
        "Contact",
        "Incoming message",
        false,
        null,
        null,
        payload,
    )
    private val proposal = IssuedAction(
        UUID.randomUUID(),
        NotificationReplyPost(1, UUID.randomUUID(), "REPLY"),
        request.id,
        payload,
    )
    private val attempt = PublicationAttempt(UUID.randomUUID(), UUID.randomUUID())
    private lateinit var receipt: NotificationReplyEnrollmentStore.CreationReceipt
    private lateinit var delivery: AndroidMessagingNotificationDelivery
    private val enrollment get() = NotificationReplyEnrollmentStore(
        file, replyLimits, 30_000, issuanceLimits, replyLimits,
    )
    private val store get() = enrollment.boundJournal(receipt)

    @After
    fun cleanup() {
        NotificationActionDispatcher.resetForTesting()
        CountingNotificationManager.calls = 0
        CountingNotificationManager.failWithSecurityException = false
        CountingNotificationManager.notifyEntered = null
        CountingNotificationManager.allowNotifyReturn = null
        CountingNotificationManager.deferAfterNotify = false
        if (file.exists()) SQLiteDatabase.deleteDatabase(file)
    }

    @Test
    fun firstClaimBuildsAndPostsBoundReplyIntentAndConsumedReplayNeverNotifiesAgain() = runTest {
        setupDelivery(backgroundScope)
        val first = delivery.publishIssuedReplyOnce(
            request, operation.storeId, proposal, 80, attempt, store,
        ) as AttemptReturned
        assertEquals(attempt, first.issuance.publicationAttempt)
        assertEquals(1, CountingNotificationManager.calls)

        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
            .single().notification
        assertEquals(2, notification.actions.size)

        val reply = notification.actions[0]
        assertFalse(reply.actionIntent.isImmutable)
        assertEquals(1, reply.remoteInputs.size)
        val saved = shadowOf(reply.actionIntent).savedIntent
        assertEquals(UnactivatedIssuedReplyReceiver::class.java.name, saved.component?.className)
        assertEquals(NotificationReplyIssuanceAdapter.ACTION, saved.action)
        assertEquals(
            Uri.parse("meshcore-notification://issued-reply/v1/${operation.storeId}/${proposal.token}"),
            saved.data,
        )
        assertEquals(operation.storeId.toString(), saved.getStringExtra("mc1.notification.issued-reply.store"))
        assertEquals(proposal.token.toString(), saved.getStringExtra("mc1.notification.issued-reply.token"))
        assertFalse(saved.hasExtra(NotificationActionReceiver.EXTRA_ACTION))

        val markRead = notification.actions[1]
        assertTrue(markRead.actionIntent.isImmutable)
        assertEquals(NotificationActionReceiver::class.java.name,
            shadowOf(markRead.actionIntent).savedIntent.component?.className)

        val retained = store.admitIssued(operation.storeId, proposal.token, "reply")
        assertTrue(retained is NotificationReplyStagingStore.StageResult.Staged)
        assertEquals(
            AttemptHeld(first.issuance),
            delivery.publishIssuedReplyOnce(request, operation.storeId, proposal, 80, attempt, store),
        )
        assertEquals(1, CountingNotificationManager.calls)
    }

    @Test
    fun visiblePendingIntentConsumesQuotaDuringPostIpcAndCompetingPublicationRemainsHeld() = runTest {
        setupDelivery(backgroundScope)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        CountingNotificationManager.notifyEntered = entered
        CountingNotificationManager.allowNotifyReturn = release
        CountingNotificationManager.deferAfterNotify = true

        val publishing = async(Dispatchers.IO) {
            delivery.publishIssuedReplyOnce(request, operation.storeId, proposal, 80, attempt, store)
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val visible = context.getSystemService(NotificationManager::class.java).activeNotifications
                .single().notification
            val reply = visible.actions.first()
            val fillIn = Intent()
            RemoteInput.addResultsToIntent(
                reply.remoteInputs,
                fillIn,
                Bundle().apply { putCharSequence(NotificationActionReceiver.REMOTE_INPUT_KEY, "reply") },
            )
            reply.actionIntent.send(context, 0, fillIn)
            val delivered = shadowOf(context).broadcastIntents.last()
            val parsed = NotificationReplyIssuanceAdapter(context, operation.storeId, store)
                .parseDelivery(delivered) as NotificationReplyDeliveryParsing.ReadyForAdmission
            assertEquals(proposal.token, parsed.token)
            assertTrue(store.admitIssued(parsed.storeId, parsed.token, parsed.exactText) is
                NotificationReplyStagingStore.StageResult.Staged)

            val competing = delivery.publishIssuedReplyOnce(
                request, operation.storeId, proposal, 80, attempt, store,
            ) as AttemptHeld
            assertEquals(proposal.token, competing.issuance.action.token)
            assertEquals(1, CountingNotificationManager.calls)
        } finally {
            release.countDown()
        }
        assertTrue(publishing.await() is AttemptReturned)
    }

    @Test
    @Config(sdk = [37], shadows = [CountingNotificationManager::class])
    fun notificationPermissionAndInvalidRequestDoNotConsumePublicationAttempt() = runTest {
        setupDelivery(backgroundScope, grantPermission = false)
        assertEquals(
            NotAttempted(NotificationReplyPublicationResult.Reason.NOTIFICATION_PERMISSION_DENIED),
            delivery.publishIssuedReplyOnce(request, operation.storeId, proposal, 80, attempt, store),
        )
        assertEquals(0L, issuedCount())

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(
            NotAttempted(NotificationReplyPublicationResult.Reason.INVALID_REQUEST),
            delivery.publishIssuedReplyOnce(
                request.copy(payload = NotificationPayload.LowBattery(10)),
                operation.storeId,
                proposal,
                80,
                attempt,
                store,
            ),
        )
        assertEquals(0L, issuedCount())
        assertEquals(0, CountingNotificationManager.calls)
    }

    @Test
    fun notifyFailureIsTerminalAndTheSameAttemptCannotRetry() = runTest {
        setupDelivery(backgroundScope)
        CountingNotificationManager.failWithSecurityException = true
        val first = delivery.publishIssuedReplyOnce(
            request, operation.storeId, proposal, 80, attempt, store,
        ) as AttemptFailed
        assertEquals(NotificationReplyPublicationResult.PlatformFailure.SECURITY_DENIED, first.reason)
        assertEquals(1L, issuedCount())
        assertEquals(1, CountingNotificationManager.calls)

        CountingNotificationManager.failWithSecurityException = false
        assertEquals(
            AttemptHeld(first.issuance),
            delivery.publishIssuedReplyOnce(request, operation.storeId, proposal, 80, attempt, store),
        )
        assertEquals(1, CountingNotificationManager.calls)
    }

    @Test
    fun cancellationAfterDurableClaimPropagatesAndRestartObservationCannotNotify() = runTest {
        setupDelivery(backgroundScope)
        val faultingStore = NotificationReplyStagingStore(
            file, replyLimits, receipt, afterIssuanceCommit = {
                throw CancellationException("cancel after durable claim")
            },
        )
        try {
            delivery.publishIssuedReplyOnce(
                request, operation.storeId, proposal, 80, attempt, faultingStore,
            )
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(1L, issuedCount())
        }
        assertEquals(0, CountingNotificationManager.calls)
        val observed = store.reservePublicationAttempt(operation.storeId, proposal, 80, attempt)
            as NotificationReplyStagingStore.PublicationResult.Observed
        assertEquals(
            AttemptHeld(observed.record),
            delivery.publishIssuedReplyOnce(request, operation.storeId, proposal, 80, attempt, store),
        )
        assertEquals(0, CountingNotificationManager.calls)
    }

    private suspend fun setupDelivery(scope: CoroutineScope, grantPermission: Boolean = true) {
        CountingNotificationManager.calls = 0
        CountingNotificationManager.failWithSecurityException = false
        if (grantPermission) shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        delivery = AndroidMessagingNotificationDelivery(context, scope) { true }
        val preparing = (enrollment.prepare(operation) as
            NotificationReplyEnrollmentStore.EnrollmentResult.Preparing).receipt
        receipt = (enrollment.bind(preparing, NotificationReplyEnrollmentStore.WitnessAcceptance(
            preparing.operation, preparing.creationNonce, UUID.randomUUID(),
        )) as NotificationReplyEnrollmentStore.EnrollmentResult.Bound).receipt
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready,
            store.initializeReplyReservations(operation.storeId))
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready,
            store.initializePublicationAttempts(operation.storeId))
        delivery.registerCategories(SnapshotList.of(
            NotificationCategoryDefinition(
                NotificationCategory.DIRECT_MESSAGE,
                SnapshotList.of(
                    NotificationActionDefinition(NotificationAction.REPLY, "Reply", "Send", "Message"),
                    NotificationActionDefinition(NotificationAction.MARK_READ, "Read", null, null),
                ),
            ),
        ))
    }

    private fun issuedCount(): Long =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM reply_issued_actions", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getLong(0)
            }
        }

    @Implements(NotificationManager::class)
    class CountingNotificationManager : ShadowNotificationManager() {
        @Implementation
        override fun notify(tag: String?, id: Int, notification: Notification) {
            calls++
            if (failWithSecurityException) throw SecurityException("injected post denial")
            super.notify(tag, id, notification)
            if (deferAfterNotify) {
                notifyEntered?.countDown()
                check(allowNotifyReturn?.await(10, TimeUnit.SECONDS) == true)
            }
        }

        companion object {
            var calls = 0
            var failWithSecurityException = false
            var notifyEntered: CountDownLatch? = null
            var allowNotifyReturn: CountDownLatch? = null
            var deferAfterNotify = false
        }
    }
}
