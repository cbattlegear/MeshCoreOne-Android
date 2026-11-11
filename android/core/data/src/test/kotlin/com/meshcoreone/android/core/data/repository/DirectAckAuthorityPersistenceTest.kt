// AndroidOnly: WP-202 Real Room authority, independent-writer and lifecycle controls.
package com.meshcoreone.android.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.data.backup.ImportResult
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DirectAckAuthorityPersistenceTest {
    private lateinit var db: MeshCoreDatabase
    private lateinit var store: RoomPersistenceStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val token = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), RADIO_A)
    private val immediate = Executor { it.run() }
    private val reported = mutableListOf<PersistenceStoreException>()

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .addCallback(DirectAckStorage.callback).allowMainThreadQueries()
            .setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        store = newStore(db)
    }

    private fun newStore(database: MeshCoreDatabase) =
        RoomPersistenceStore(database, scope, RepositoryClock(), RepositoryIssueReporter { _, failure -> reported += failure })

    @After fun close() {
        try { runBlocking { store.close() } } finally { scope.cancel(); db.close() }
    }

    private suspend fun activate(
        target: RoomPersistenceStore = store,
        session: SessionToken = token,
        request: UUID = UUID.randomUUID(),
    ): DirectAckLease = assertIs<DirectAckActivationResult.Activated>(target.activateDirectAckSession(session, request)).lease

    private data class Claim(
        val lease: DirectAckLease,
        val request: UUID,
        val wire: UUID,
        val intent: DirectAckIntent,
        val attempt: DirectAckAttempt,
        val row: MessageDTO,
    )

    private suspend fun begin(
        row: MessageDTO = message(),
        mode: DirectAckMode = DirectAckMode.SEND,
        pendingId: UUID? = null,
        lease: DirectAckLease? = null,
        target: RoomPersistenceStore = store,
    ): Claim {
        val issued = lease ?: activate(target)
        target.saveMessage(row)
        if (row.linkPreviewImageData != null || row.linkPreviewIconData != null || row.linkPreviewFetched) {
            target.updateMessageLinkPreview(
                entity(id = row.id), row.linkPreviewURL, row.linkPreviewTitle,
                row.linkPreviewImageData, row.linkPreviewIconData, row.linkPreviewFetched,
            )
        }
        val request = UUID.randomUUID()
        val wire = UUID.randomUUID()
        val capture = assertIs<DirectAckIntentResult.Captured>(
            target.captureDirectAckIntent(issued, entity(id = row.id), request, mode, pendingId),
        )
        assertEquals(row, capture.receipt.message)
        val result = assertIs<DirectAckBeginResult.Begun>(
            target.beginDirectAckAttempt(capture.intent, wire, 123u),
        )
        return Claim(issued, request, wire, capture.intent, result.attempt, row)
    }

    private suspend fun observed(claim: Claim, target: RoomPersistenceStore = store): DirectAckReceipt =
        assertIs<DirectAckObservation.Found>(target.inspectDirectAckAttempt(claim.lease, claim.request)).receipt

    private fun rejected(result: DirectAckMutationResult, reason: DirectAckRejection = DirectAckRejection.INVALIDATED) {
        assertEquals(reason, assertIs<DirectAckMutationResult.Rejected>(result).reason)
    }

    private suspend fun capture(
        row: MessageDTO,
        lease: DirectAckLease,
        request: UUID = UUID.randomUUID(),
        pendingId: UUID? = null,
    ) = assertIs<DirectAckIntentResult.Captured>(
        store.captureDirectAckIntent(lease, entity(id = row.id), request, DirectAckMode.SEND, pendingId),
    )

    @Test fun inlineCreationRecoveryAndFailureAreIdempotentWithoutRecreatingRows() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val creationID = UUID.randomUUID()
        val claimID = UUID.randomUUID()
        val key = entity(id = row.id)
        assertEquals(DirectAckRejection.MISSING, assertIs<DirectMessageCreationResult.Rejected>(
            store.recoverDirectMessageCreation(lease, key, creationID, claimID),
        ).reason)
        assertNull(store.fetchMessage(key))

        val created = assertIs<DirectMessageCreationResult.Created>(
            store.createDirectMessage(lease, row, creationID, claimID),
        ).creation
        val recovered = assertIs<DirectMessageCreationResult.AlreadyCreated>(
            store.recoverDirectMessageCreation(lease, key, creationID, claimID),
        ).creation
        assertIs<DirectMessageCreationResult.AlreadyCreated>(
            store.createDirectMessage(lease, row, creationID, claimID),
        )
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectMessageCreationResult.Rejected>(
            store.recoverDirectMessageCreation(lease, key, creationID, UUID.randomUUID()),
        ).reason)
        assertIs<DirectAckIntentResult.Captured>(store.captureCreatedDirectIntent(recovered))
        assertIs<DirectAckIntentResult.AlreadyCaptured>(store.captureCreatedDirectIntent(created))

        val failureID = UUID.randomUUID()
        val failure = assertIs<DirectMessageCreationFailureResult.Applied>(
            store.failDirectMessageCreation(created, failureID),
        ).receipt
        assertEquals(DirectMessageCreationFailureReceipt(MessageStatus.FAILED, true), failure)
        assertEquals(failure, assertIs<DirectMessageCreationFailureResult.AlreadyApplied>(
            store.failDirectMessageCreation(recovered, failureID),
        ).receipt)
        assertEquals(MessageStatus.FAILED, store.fetchMessage(key)?.status)

        store.deleteMessage(key)
        store.saveMessage(row)
        assertEquals(failure, assertIs<DirectMessageCreationFailureResult.AlreadyApplied>(
            store.failDirectMessageCreation(created, failureID),
        ).receipt)
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectMessageCreationFailureResult.Rejected>(
            store.failDirectMessageCreation(created, UUID.randomUUID()),
        ).reason)
        assertEquals(row, store.fetchMessage(key))
    }

    @Test fun inlineCreationCannotCaptureOrFailAnIdenticalReplacementOrSameValueWrite() = runTest {
        val lease = activate()
        for (replacement in listOf(false, true)) {
            val row = message(status = MessageStatus.PENDING)
            val key = entity(id = row.id)
            val created = assertIs<DirectMessageCreationResult.Created>(
                store.createDirectMessage(lease, row, UUID.randomUUID(), UUID.randomUUID()),
            ).creation
            if (replacement) store.deleteMessage(key)
            store.saveMessage(row)
            assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckIntentResult.Rejected>(
                store.captureCreatedDirectIntent(created),
            ).reason)
            assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectMessageCreationFailureResult.Rejected>(
                store.failDirectMessageCreation(created, UUID.randomUUID()),
            ).reason)
            assertEquals(row, store.fetchMessage(key))
        }
    }

    @Test fun retryCreationAcceptsSameIncarnationEditsButNotAnIdenticalReplacement() = runTest {
        val lease = activate()
        for (status in listOf(MessageStatus.PENDING, MessageStatus.RETRYING, MessageStatus.FAILED, MessageStatus.DELIVERED)) {
            val row = message(status = MessageStatus.PENDING)
            val key = entity(id = row.id)
            val created = assertIs<DirectMessageCreationResult.Created>(
                store.createDirectMessage(lease, row, UUID.randomUUID(), UUID.randomUUID()),
            ).creation
            val edited = row.copy(text = "callback edit", status = status)
            store.saveMessage(edited)
            val mode = if (status in listOf(MessageStatus.FAILED, MessageStatus.DELIVERED)) {
                DirectAckMode.RESEND
            } else DirectAckMode.SEND
            val capture = assertIs<DirectAckIntentResult.Captured>(store.captureCreatedDirectRetryIntent(created, mode))
            assertEquals(edited, capture.receipt.message)
            store.deleteMessage(key)
            store.saveMessage(edited)
            assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckIntentResult.Rejected>(
                store.captureCreatedDirectRetryIntent(created, mode),
            ).reason)
            assertEquals(edited, store.fetchMessage(key))
        }
    }

    @Test fun retryCreationFailureTargetsEditedOriginalButNotReplacementOrCompetingIntent() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val key = entity(id = row.id)
        val created = assertIs<DirectMessageCreationResult.Created>(
            store.createDirectMessage(lease, row, UUID.randomUUID(), UUID.randomUUID()),
        ).creation
        val edited = row.copy(text = "callback edit")
        store.saveMessage(edited)
        val otherIntent = assertIs<DirectAckIntentResult.Captured>(
            store.captureDirectAckIntent(lease, key, UUID.randomUUID(), DirectAckMode.SEND, null),
        )
        val failureID = UUID.randomUUID()
        assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<DirectMessageCreationFailureResult.Rejected>(
            store.failCreatedDirectRetryMessage(created, failureID),
        ).reason)
        assertEquals(DirectAckIntentRetirement.Retired, store.retireDirectAckIntent(lease, otherIntent.receipt.requestId))
        val applied = assertIs<DirectMessageCreationFailureResult.Applied>(
            store.failCreatedDirectRetryMessage(created, failureID),
        ).receipt
        assertEquals(DirectMessageCreationFailureReceipt(MessageStatus.FAILED, true), applied)
        assertEquals(edited.copy(status = MessageStatus.FAILED), store.fetchMessage(key))
        store.deleteMessage(key)
        store.saveMessage(edited)
        assertEquals(applied, assertIs<DirectMessageCreationFailureResult.AlreadyApplied>(
            store.failCreatedDirectRetryMessage(created, failureID),
        ).receipt)
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectMessageCreationFailureResult.Rejected>(
            store.failCreatedDirectRetryMessage(created, UUID.randomUUID()),
        ).reason)
        assertEquals(edited, store.fetchMessage(key))
    }

    @Test fun zeroAttemptCreationReplayCannotObserveOrMutateAnIdenticalReplacement() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val key = entity(id = row.id)
        val created = assertIs<DirectMessageCreationResult.Created>(
            store.createDirectMessage(lease, row, UUID.randomUUID(), UUID.randomUUID()),
        ).creation
        val edited = row.copy(text = "callback edit", status = MessageStatus.RETRYING)
        store.saveMessage(edited)
        val request = UUID.randomUUID()
        val applied = assertIs<DirectMessageCreationResolutionResult.Applied>(
            store.resolveCreatedDirectWithoutDispatch(created, request, resolveToSent = true, attempt = null),
        ).receipt
        assertEquals(edited.copy(status = MessageStatus.SENT), applied.message)
        assertTrue(applied.statusChanged)
        assertEquals(applied, assertIs<DirectMessageCreationResolutionResult.AlreadyApplied>(
            store.resolveCreatedDirectWithoutDispatch(created, request, resolveToSent = true, attempt = null),
        ).receipt)
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectMessageCreationResolutionResult.Rejected>(
            store.resolveCreatedDirectWithoutDispatch(created, request, resolveToSent = false, attempt = null),
        ).reason)
        store.deleteMessage(key)
        store.saveMessage(applied.message)
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectMessageCreationResolutionResult.Rejected>(
            store.resolveCreatedDirectWithoutDispatch(created, request, resolveToSent = true, attempt = null),
        ).reason)
        assertEquals(applied.message, store.fetchMessage(key))
    }

    @Test fun zeroAttemptResolutionRenewsOnlyItsObservedCurrentClaim() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val key = entity(id = row.id)
        val claimID = UUID.randomUUID()
        val created = assertIs<DirectMessageCreationResult.Created>(
            store.createDirectMessage(lease, row, UUID.randomUUID(), claimID),
        ).creation
        val intent = assertIs<DirectAckIntentResult.Captured>(
            store.captureCreatedDirectRetryIntent(created, DirectAckMode.SEND),
        ).intent
        val attempt = assertIs<DirectAckBeginResult.Begun>(
            store.beginDirectAckAttempt(intent, UUID.randomUUID(), 123u),
        ).attempt
        assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<DirectMessageCreationResolutionResult.Rejected>(
            store.resolveCreatedDirectWithoutDispatch(created, UUID.randomUUID(), true, null),
        ).reason)
        val receipt = assertIs<DirectMessageCreationResolutionResult.Applied>(
            store.resolveCreatedDirectWithoutDispatch(created, UUID.randomUUID(), true, attempt),
        ).receipt
        assertEquals(MessageStatus.SENT, receipt.message.status)
        assertTrue(receipt.statusChanged)
        val current = assertIs<DirectAckObservation.Found>(store.inspectDirectAckAttempt(lease, claimID)).receipt
        assertEquals(DirectAckPermission.CURRENT, current.permission)
        assertEquals(receipt.message, current.message)
        assertIs<DirectAckMutationResult.Applied>(store.registerDirectAckDispatch(attempt, UUID.randomUUID(), 456u))
        assertEquals(receipt.message, store.fetchMessage(key))
    }

    @Test fun inlineCreationFailureRejectsCompetingIntentAndAnActuallyRegisteredAttempt() = runTest {
        val lease = activate()
        val competing = message(status = MessageStatus.PENDING)
        val created = assertIs<DirectMessageCreationResult.Created>(
            store.createDirectMessage(lease, competing, UUID.randomUUID(), UUID.randomUUID()),
        ).creation
        assertIs<DirectAckIntentResult.Captured>(
            store.captureDirectAckIntent(lease, entity(id = competing.id), UUID.randomUUID(), DirectAckMode.SEND, null),
        )
        assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<DirectMessageCreationFailureResult.Rejected>(
            store.failDirectMessageCreation(created, UUID.randomUUID()),
        ).reason)
        assertEquals(competing, store.fetchMessage(entity(id = competing.id)))

        val registered = message(status = MessageStatus.PENDING)
        val own = assertIs<DirectMessageCreationResult.Created>(
            store.createDirectMessage(lease, registered, UUID.randomUUID(), UUID.randomUUID()),
        ).creation
        val intent = assertIs<DirectAckIntentResult.Captured>(store.captureCreatedDirectIntent(own)).intent
        assertIs<DirectAckBeginResult.Begun>(store.beginDirectAckAttempt(intent, UUID.randomUUID(), 123u))
        assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<DirectMessageCreationFailureResult.Rejected>(
            store.failDirectMessageCreation(own, UUID.randomUUID()),
        ).reason)
        assertEquals(registered, store.fetchMessage(entity(id = registered.id)))
    }

    @Test fun inlineCreationFailureRollsBackNestedIndependentMessageMutation() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val created = assertIs<DirectMessageCreationResult.Created>(
            store.createDirectMessage(lease, row, UUID.randomUUID(), UUID.randomUUID()),
        ).creation
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER independently_repeat_inline_failure AFTER UPDATE ON messages " +
                "WHEN OLD.statusRawValue != 4 AND NEW.statusRawValue = 4 BEGIN " +
                "UPDATE messages SET statusRawValue = 4 WHERE radioId = NEW.radioId AND id = NEW.id; END",
        )
        val failure = assertFailsWith<PersistenceStoreException> {
            store.failDirectMessageCreation(created, UUID.randomUUID())
        }
        assertEquals(PersistenceStoreError.InvalidData, failure.error)
        assertEquals(row, store.fetchMessage(entity(id = row.id)))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER independently_repeat_inline_failure")
        assertIs<DirectMessageCreationFailureResult.Applied>(
            store.failDirectMessageCreation(created, UUID.randomUUID()),
        )
    }

    @Test fun replacingPendingIdentityByUpdateInvalidatesTheDisplacedMessagesInlineClaim() = runTest {
        val lease = activate()
        val displaced = message()
        val replacement = message()
        val displacedPending = pending(messageID = displaced.id)
        val replacementPending = pending(messageID = replacement.id)
        store.saveMessage(displaced)
        store.saveMessage(replacement)
        store.upsertPendingSend(displacedPending)
        store.upsertPendingSend(replacementPending)
        val claim = begin(displaced, lease = lease)
        assertEquals(DirectAckPermission.CURRENT, observed(claim).permission)

        db.openHelper.writableDatabase.execSQL("PRAGMA recursive_triggers = OFF")
        db.openHelper.writableDatabase.execSQL(
            "UPDATE OR REPLACE pending_sends SET id = ? WHERE radioId = ? AND id = ?",
            arrayOf(displacedPending.id.canonicalString(), RADIO_A.value.canonicalString(), replacementPending.id.canonicalString()),
        )

        assertEquals(replacement.id, store.fetchPendingSendsForMessage(entity(id = replacement.id)).single().messageID)
        assertTrue(store.fetchPendingSendsForMessage(entity(id = displaced.id)).isEmpty())
        rejected(store.registerDirectAckDispatch(claim.attempt, UUID.randomUUID(), 456u))
        assertEquals(DirectAckPermission.INVALIDATED, observed(claim).permission)
    }

    @Test fun missingContactCleanupCommitsOnceAndCannotDeleteAReplacementOnReplay() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val original = pending(messageID = row.id)
        store.saveMessage(row)
        store.upsertPendingSend(original)
        val selected = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), original.id),
        )
        val request = UUID.randomUUID()
        val receipt = assertIs<DirectQueueMutationResult.Applied>(
            store.mutateDirectQueueSelection(selected.selection, request, DirectQueueMutation.MissingContact),
        ).receipt
        assertTrue(receipt.removed)
        assertTrue(receipt.statusChanged)
        assertEquals(MessageStatus.FAILED, receipt.status)
        assertEquals(MessageStatus.FAILED, store.fetchMessage(entity(id = row.id))?.status)
        assertTrue(store.fetchPendingSendsForMessage(entity(id = row.id)).isEmpty())

        val replacement = original.copy(id = UUID.randomUUID())
        store.saveMessage(row.copy(text = "replacement", status = MessageStatus.PENDING))
        store.upsertPendingSend(replacement)
        assertEquals(receipt, assertIs<DirectQueueMutationResult.AlreadyApplied>(
            store.mutateDirectQueueSelection(selected.selection, request, DirectQueueMutation.MissingContact),
        ).receipt)
        assertEquals(replacement, store.fetchPendingSendsForMessage(entity(id = row.id)).single())
        assertEquals(MessageStatus.PENDING, store.fetchMessage(entity(id = row.id))?.status)
    }

    @Test fun queuedCleanupRollsBackIndependentNestedPendingAndMessageWrites() = runTest {
        val lease = activate()
        for (onFailure in listOf(false, true)) {
            val row = message(status = MessageStatus.PENDING)
            val queued = pending(messageID = row.id)
            store.saveMessage(row)
            store.upsertPendingSend(queued)
            val selected = assertIs<DirectQueueCaptureResult.Captured>(
                store.captureDirectQueueSelection(lease, entity(id = row.id), queued.id),
            )
            val trigger = if (onFailure) {
                "AFTER UPDATE ON messages WHEN NEW.statusRawValue = 4 BEGIN " +
                    "UPDATE pending_sends SET attemptCount = COALESCE(attemptCount, 0) + 1 " +
                    "WHERE radioId = NEW.radioId AND messageID = NEW.id; END"
            } else {
                "AFTER DELETE ON pending_sends BEGIN " +
                    "UPDATE messages SET statusRawValue = statusRawValue " +
                    "WHERE radioId = OLD.radioId AND id = OLD.messageID; END"
            }
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER independently_mutate_queue_cleanup $trigger")
            val failure = assertFailsWith<PersistenceStoreException> {
                store.mutateDirectQueueSelection(selected.selection, UUID.randomUUID(), DirectQueueMutation.MissingContact)
            }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
            assertEquals(row, store.fetchMessage(entity(id = row.id)))
            assertEquals(listOf(queued), store.fetchPendingSendsForMessage(entity(id = row.id)))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER independently_mutate_queue_cleanup")
        }
    }

    @Test fun queueCleanupRejectsIdenticalPendingAbaAndSameValueMessageWrites() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val original = pending(messageID = row.id)
        store.saveMessage(row)
        store.upsertPendingSend(original)
        val first = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), original.id),
        )
        db.pendingSends().delete(RADIO_A.value, original.id)
        db.pendingSends().insert(original.toEntity())
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectQueueMutationResult.Rejected>(
            store.mutateDirectQueueSelection(first.selection, UUID.randomUUID(), DirectQueueMutation.MissingContact),
        ).reason)
        val second = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), original.id),
        )
        db.messages().upsert(row.toEntity())
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectQueueMutationResult.Rejected>(
            store.mutateDirectQueueSelection(second.selection, UUID.randomUUID(), DirectQueueMutation.ReturnToPending),
        ).reason)
        assertEquals(original, store.fetchPendingSendsForMessage(entity(id = row.id)).single())
        assertEquals(row, store.fetchMessage(entity(id = row.id)))
    }

    @Test fun queueAttemptBumpRejectsAnIdenticalPendingRowRecreatedAfterSelection() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val original = pending(messageID = row.id, attempt = 2)
        store.saveMessage(row)
        store.upsertPendingSend(original)
        val selected = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), original.id),
        )
        db.pendingSends().delete(RADIO_A.value, original.id)
        db.pendingSends().insert(original.toEntity())
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectQueueBumpResult.Rejected>(
            store.bumpDirectQueueSelection(selected.selection, UUID.randomUUID()),
        ).reason)
        assertEquals(original, store.fetchPendingSendsForMessage(entity(id = row.id)).single())
    }

    @Test fun queueAttemptBumpReplaysAnExactCommitWithoutIncrementingAReplacement() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val original = pending(messageID = row.id, attempt = 2)
        store.saveMessage(row)
        store.upsertPendingSend(original)
        val selected = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), original.id),
        )
        val request = UUID.randomUUID()
        val committed = assertIs<DirectQueueBumpResult.Applied>(
            store.bumpDirectQueueSelection(selected.selection, request),
        ).receipt
        assertEquals(3L, committed.attemptCount)
        val replay = assertIs<DirectQueueBumpResult.AlreadyApplied>(
            store.bumpDirectQueueSelection(selected.selection, request),
        ).receipt
        assertEquals(committed.pending, replay.pending)
        assertEquals(3L, replay.attemptCount)
        db.pendingSends().delete(RADIO_A.value, original.id)
        db.pendingSends().insert(committed.pending.toEntity())
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectQueueBumpResult.Rejected>(
            store.bumpDirectQueueSelection(selected.selection, request),
        ).reason)
        assertEquals(committed.pending, store.fetchPendingSendsForMessage(entity(id = row.id)).single())
    }

    @Test fun queueAttemptSelectionKeepsRadioAndMessageIdentitiesDistinct() = runTest {
        val lease = activate()
        val direct = pending(attempt = 2)
        val otherRadio = direct.copy(radioId = RADIO_B, attemptCount = 8)
        store.upsertPendingSend(direct)
        store.upsertPendingSend(otherRadio)
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectQueueCaptureResult.Rejected>(
            store.captureDirectQueueSelection(lease, entity(radio = RADIO_B, id = direct.messageID), direct.id),
        ).reason)
        assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<DirectQueueCaptureResult.Rejected>(
            store.captureDirectQueueSelection(lease, entity(id = UUID.randomUUID()), direct.id),
        ).reason)
        val selected = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = direct.messageID), direct.id),
        )
        assertEquals(3L, assertIs<DirectQueueBumpResult.Applied>(
            store.bumpDirectQueueSelection(selected.selection, UUID.randomUUID()),
        ).receipt.attemptCount)
        assertEquals(direct.copy(attemptCount = 3), store.fetchPendingSends(RADIO_A).single())
        assertEquals(otherRadio, store.fetchPendingSends(RADIO_B).single())
    }

    @Test fun identicalReplacementAfterBumpCannotBePreparedOrCapturedByTheOldSelection() = runTest {
        val lease = activate()
        val row = message(text = "original")
        val queued = pending(messageID = row.id, attempt = 0)
        store.saveMessage(row)
        store.upsertPendingSend(queued)
        val selected = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), queued.id),
        )
        val bumped = assertIs<DirectQueueBumpResult.Applied>(
            store.bumpDirectQueueSelection(selected.selection, UUID.randomUUID()),
        ).receipt
        store.deletePendingSend(entity(id = queued.id))
        store.upsertPendingSend(bumped.pending)

        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectQueuePreparationResult.Rejected>(
            store.prepareDirectQueueSelection(bumped.selection, UUID.randomUUID(), row.timestamp + 1u, false),
        ).reason)
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckIntentResult.Rejected>(
            store.captureDirectQueueIntent(bumped.selection, UUID.randomUUID(), DirectAckMode.SEND),
        ).reason)
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectPendingCaptureResult.Rejected>(
            store.captureDirectQueueCompletion(bumped.selection, UUID.randomUUID(), DirectPendingCompletionReason.ZeroAttempts(0)),
        ).reason)
        assertEquals(row, store.fetchMessage(entity(id = row.id)))
        assertEquals(bumped.pending, store.fetchPendingSendsForMessage(entity(id = row.id)).single())
    }

    @Test fun queuePreparationReplaysExactlyAndCapturesTheCurrentSameIncarnationMessage() = runTest {
        val lease = activate()
        val row = message(text = "original")
        val queued = pending(messageID = row.id, attempt = 0)
        store.saveMessage(row)
        store.upsertPendingSend(queued)
        val selected = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), queued.id),
        )
        val bumped = assertIs<DirectQueueBumpResult.Applied>(
            store.bumpDirectQueueSelection(selected.selection, UUID.randomUUID()),
        ).receipt
        val changed = row.copy(text = "edited before dispatch")
        store.saveMessage(changed)
        val operation = UUID.randomUUID()
        val stamp = changed.timestamp + 1u
        val prepared = assertIs<DirectQueuePreparationResult.Applied>(
            store.prepareDirectQueueSelection(bumped.selection, operation, stamp, false),
        ).receipt
        val replay = assertIs<DirectQueuePreparationResult.AlreadyApplied>(
            store.prepareDirectQueueSelection(bumped.selection, operation, stamp, false),
        ).receipt
        assertEquals(prepared.message, replay.message)
        assertEquals(changed.copy(timestamp = stamp), prepared.message)
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectQueuePreparationResult.Rejected>(
            store.prepareDirectQueueSelection(bumped.selection, operation, stamp + 1u, false),
        ).reason)

        val latest = prepared.message.copy(text = "edited after preparation", timestamp = stamp - 1u)
        store.saveMessage(latest)
        val intent = assertIs<DirectAckIntentResult.Captured>(
            store.captureDirectQueueIntent(prepared.selection, UUID.randomUUID(), DirectAckMode.SEND),
        )
        assertEquals(latest, intent.receipt.message)
        assertEquals(bumped.pending, intent.receipt.pending)
    }

    @Test fun replacementAfterQueuePreparationCannotAcquireWireOrNoDispatchAuthority() = runTest {
        val lease = activate()
        val row = message()
        val queued = pending(messageID = row.id, attempt = 0)
        store.saveMessage(row)
        store.upsertPendingSend(queued)
        val selected = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), queued.id),
        )
        val bumped = assertIs<DirectQueueBumpResult.Applied>(
            store.bumpDirectQueueSelection(selected.selection, UUID.randomUUID()),
        ).receipt
        val prepared = assertIs<DirectQueuePreparationResult.Applied>(
            store.prepareDirectQueueSelection(bumped.selection, UUID.randomUUID(), null, false),
        ).receipt
        store.deletePendingSend(entity(id = queued.id))
        store.upsertPendingSend(bumped.pending)

        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckIntentResult.Rejected>(
            store.captureDirectQueueIntent(prepared.selection, UUID.randomUUID(), DirectAckMode.SEND),
        ).reason)
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectPendingCaptureResult.Rejected>(
            store.captureDirectQueueCompletion(prepared.selection, UUID.randomUUID(), DirectPendingCompletionReason.ZeroAttempts(0)),
        ).reason)
        assertEquals(bumped.pending, store.fetchPendingSendsForMessage(entity(id = row.id)).single())
    }

    @Test fun explicitQueuedRetryGrantSurvivesOnlyItsOwnBumpAndNeverAnIdenticalReplacement() = runTest {
        val lease = activate()
        for (replacementKind in listOf("different-id", "same-id", "same-value-write", "delete-recreate", "generic")) {
            val row = message(status = MessageStatus.PENDING)
            val original = pending(messageID = row.id)
            store.saveMessage(row)
            store.upsertPendingSend(original)
            val oldSelection = assertIs<DirectQueueCaptureResult.Captured>(
                store.captureDirectQueueSelection(lease, entity(id = row.id), original.id),
            )
            val firstIntent = assertIs<DirectAckIntentResult.Captured>(
                store.captureDirectQueueIntent(oldSelection.selection, UUID.randomUUID(), DirectAckMode.SEND),
            )
            assertIs<DirectAckBeginResult.Begun>(store.beginDirectAckAttempt(firstIntent.intent, UUID.randomUUID(), 123u))
            assertEquals(DirectAckRejection.UNRESOLVED_OUTCOME, assertIs<DirectQueueCaptureResult.Rejected>(
                store.captureDirectQueueSelection(lease, entity(id = row.id), original.id),
            ).reason)
            val replacement = original.copy(
                id = if (replacementKind == "same-id") original.id else UUID.randomUUID(), isResend = true,
            )
            if (replacementKind == "generic") {
                store.deletePendingSend(entity(id = original.id))
                store.upsertPendingSend(replacement)
                assertEquals(DirectAckRejection.UNRESOLVED_OUTCOME, assertIs<DirectQueueCaptureResult.Rejected>(
                    store.captureDirectQueueSelection(lease, entity(id = row.id), replacement.id),
                ).reason)
                continue
            }
            store.replacePendingSendForRetry(row.id, replacement)
            val selected = assertIs<DirectQueueCaptureResult.Captured>(
                store.captureDirectQueueSelection(lease, entity(id = row.id), replacement.id),
            )
            assertEquals(DirectQueueIncarnationComparison.Different,
                store.compareDirectQueueSelections(oldSelection.selection, selected.selection))
            val bumpID = UUID.randomUUID()
            val bumped = assertIs<DirectQueueBumpResult.Applied>(
                store.bumpDirectQueueSelection(selected.selection, bumpID),
            ).receipt
            assertEquals(1L, bumped.attemptCount)
            assertEquals(bumped, assertIs<DirectQueueBumpResult.AlreadyApplied>(
                store.bumpDirectQueueSelection(selected.selection, bumpID),
            ).receipt)
            if (replacementKind == "same-value-write") store.upsertPendingSend(bumped.pending)
            if (replacementKind == "delete-recreate") {
                store.deletePendingSend(entity(id = replacement.id))
                store.upsertPendingSend(bumped.pending)
            }
            if (replacementKind in listOf("same-value-write", "delete-recreate")) {
                assertEquals(DirectAckRejection.UNRESOLVED_OUTCOME, assertIs<DirectQueueCaptureResult.Rejected>(
                    store.captureDirectQueueSelection(lease, entity(id = row.id), replacement.id),
                ).reason)
                continue
            }
            val successor = assertIs<DirectAckIntentResult.Captured>(
                store.captureDirectQueueIntent(bumped.selection, UUID.randomUUID(), DirectAckMode.RESEND),
            )
            assertIs<DirectAckBeginResult.Begun>(store.beginDirectAckAttempt(successor.intent, UUID.randomUUID(), 456u))
            assertEquals(DirectAckRejection.UNRESOLVED_OUTCOME, assertIs<DirectQueueCaptureResult.Rejected>(
                store.captureDirectQueueSelection(lease, entity(id = row.id), replacement.id),
            ).reason, "An uncertain successor must require its own explicit retry")
        }
    }

    @Test fun definitelyUnsentQueuedWireRetirementIsExactAndDoesNotPoisonALaterSameCode() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val queued = pending(messageID = row.id)
        store.saveMessage(row)
        store.upsertPendingSend(queued)
        val selected = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), queued.id),
        )
        val intent = assertIs<DirectAckIntentResult.Captured>(
            store.captureDirectQueueIntent(selected.selection, UUID.randomUUID(), DirectAckMode.SEND),
        )
        val wire = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val attempt = assertIs<DirectAckBeginResult.Begun>(store.beginDirectAckAttempt(intent.intent, wire, 123u)).attempt
        val request = UUID.randomUUID()
        rejected(store.retireDefinitelyUnsentQueuedAttempt(attempt, UUID.randomUUID(), request), DirectAckRejection.NOT_ELIGIBLE)
        val retired = assertIs<DirectAckMutationResult.Applied>(
            store.retireDefinitelyUnsentQueuedAttempt(attempt, wire, request),
        ).receipt
        assertEquals(DirectAckPermission.INVALIDATED, retired.permission)
        assertEquals(wire, retired.definitelyUnsentWireRequestId)
        assertEquals(retired, assertIs<DirectAckMutationResult.AlreadyApplied>(
            store.retireDefinitelyUnsentQueuedAttempt(attempt, wire, request),
        ).receipt)
        rejected(store.recordDirectSendAccepted(attempt, wire, 123u))
        val captured = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), queued.id),
        )
        assertEquals(DirectQueueIncarnationComparison.Same,
            store.compareDirectQueueSelections(selected.selection, captured.selection))
        val nextIntent = assertIs<DirectAckIntentResult.Captured>(
            store.captureDirectQueueIntent(captured.selection, UUID.randomUUID(), DirectAckMode.SEND),
        )
        val nextWire = UUID.randomUUID()
        val next = assertIs<DirectAckBeginResult.Begun>(
            store.beginDirectAckAttempt(nextIntent.intent, nextWire, 123u),
        ).attempt
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(next, nextWire, 123u))
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(next, 123u, 9u))
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(entity(id = row.id))?.status)
    }

    @Test fun definitelyUnsentRetirementCannotDiscardAcceptedMultipleWireOrInlineClaims() = runTest {
        val lease = activate()
        for (scenario in listOf("accepted", "multiple", "inline")) {
            val row = message(status = MessageStatus.PENDING)
            val queued = pending(messageID = row.id)
            if (scenario != "inline") store.upsertPendingSend(queued)
            val claim = begin(row, pendingId = queued.id.takeIf { scenario != "inline" }, lease = lease)
            if (scenario == "accepted") store.recordDirectSendAccepted(claim.attempt, claim.wire, 123u)
            if (scenario == "multiple") store.registerDirectAckDispatch(claim.attempt, UUID.randomUUID(), 456u)
            rejected(
                store.retireDefinitelyUnsentQueuedAttempt(claim.attempt, claim.wire, UUID.randomUUID()),
                DirectAckRejection.NOT_ELIGIBLE,
            )
            assertNull(observed(claim).definitelyUnsentWireRequestId)
            assertEquals(DirectAckPermission.CURRENT, observed(claim).permission)
        }
    }

    @Test fun definitelyUnsentInlineWireCannotSettleAndDoesNotPoisonALaterSameCode() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val first = begin(row, lease = lease)
        val request = UUID.randomUUID()
        rejected(
            store.recordDefinitelyUnsentInlineWire(first.attempt, UUID.randomUUID(), request),
            DirectAckRejection.NOT_ELIGIBLE,
        )
        val recorded = assertIs<DirectAckMutationResult.Applied>(
            store.recordDefinitelyUnsentInlineWire(first.attempt, first.wire, request),
        ).receipt
        assertEquals(DirectAckPermission.CURRENT, recorded.permission)
        assertEquals(first.wire, recorded.definitelyUnsentWireRequestId)
        assertEquals(recorded, assertIs<DirectAckMutationResult.AlreadyApplied>(
            store.recordDefinitelyUnsentInlineWire(first.attempt, first.wire, request),
        ).receipt)
        rejected(store.recordDirectSendAccepted(first.attempt, first.wire, 123u), DirectAckRejection.NOT_ELIGIBLE)
        rejected(store.registerDirectAckDispatch(first.attempt, UUID.randomUUID(), 123u), DirectAckRejection.NOT_ELIGIBLE)
        rejected(store.settleDirectAck(first.attempt, 123u, 9u), DirectAckRejection.NOT_ELIGIBLE)

        val nextIntent = capture(row, lease)
        val nextWire = UUID.randomUUID()
        val next = assertIs<DirectAckBeginResult.Begun>(
            store.beginDirectAckAttempt(nextIntent.intent, nextWire, 123u),
        ).attempt
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(next, nextWire, 123u))
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(next, 123u, 9u))
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(entity(id = row.id))?.status)
    }

    @Test fun inlineUnsentProofCannotRetireQueuedAcceptedOrMultipleWireClaims() = runTest {
        val lease = activate()
        for (scenario in listOf("queued", "accepted", "multiple")) {
            val row = message(status = MessageStatus.PENDING)
            val queued = pending(messageID = row.id)
            if (scenario == "queued") store.upsertPendingSend(queued)
            val claim = begin(row, pendingId = queued.id.takeIf { scenario == "queued" }, lease = lease)
            if (scenario == "accepted") store.recordDirectSendAccepted(claim.attempt, claim.wire, 123u)
            if (scenario == "multiple") store.registerDirectAckDispatch(claim.attempt, UUID.randomUUID(), 456u)
            rejected(
                store.recordDefinitelyUnsentInlineWire(claim.attempt, claim.wire, UUID.randomUUID()),
                DirectAckRejection.NOT_ELIGIBLE,
            )
            assertNull(observed(claim).definitelyUnsentWireRequestId)
            assertEquals(DirectAckPermission.CURRENT, observed(claim).permission)
        }
    }

    @Test fun knownPriorWireCollisionCannotSettleAnExplicitRetryAcrossGenerations() = runTest {
        val firstLease = activate()
        val row = message(status = MessageStatus.PENDING)
        val queued = pending(messageID = row.id)
        store.upsertPendingSend(queued)
        val first = begin(row, pendingId = queued.id, lease = firstLease)
        assertEquals(DirectAckRevocationResult.Revoked, store.revokeDirectAckSession(firstLease))
        val secondLease = activate(session = token.copy(generation = Generation(2)))
        val replacement = queued.copy(id = UUID.randomUUID(), isResend = true)
        store.replacePendingSendForRetry(row.id, replacement)
        val selected = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(secondLease, entity(id = row.id), replacement.id),
        )
        val bumped = assertIs<DirectQueueBumpResult.Applied>(
            store.bumpDirectQueueSelection(selected.selection, UUID.randomUUID()),
        ).receipt
        val intent = assertIs<DirectAckIntentResult.Captured>(
            store.captureDirectQueueIntent(bumped.selection, UUID.randomUUID(), DirectAckMode.RESEND),
        )
        val wire = UUID.randomUUID()
        val successor = assertIs<DirectAckBeginResult.Begun>(
            store.beginDirectAckAttempt(intent.intent, wire, 123u),
        ).attempt
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(successor, wire, 456u))
        rejected(store.settleDirectAck(successor, 123u, 1u), DirectAckRejection.AMBIGUOUS_ACK)
        assertNotEquals(MessageStatus.DELIVERED, store.fetchMessage(entity(id = row.id))?.status)
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(successor, 456u, 2u))
        assertEquals(2u, store.fetchMessage(entity(id = row.id))?.roundTripTime)
        assertEquals(123u, assertNotNull(db.directAcks().wires(RADIO_A.value, first.request).single().predictedCode).toUInt())
    }

    @Test fun queueCleanupRechecksRestoredContactAndRegisteredWire() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val original = pending(messageID = row.id)
        store.saveMessage(row)
        store.upsertPendingSend(original)
        val selected = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), original.id),
        )
        store.saveContact(contact())
        assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<DirectQueueMutationResult.Rejected>(
            store.mutateDirectQueueSelection(selected.selection, UUID.randomUUID(), DirectQueueMutation.MissingContact),
        ).reason)
        val request = UUID.randomUUID()
        val intent = assertIs<DirectAckIntentResult.Captured>(
            store.captureDirectAckIntent(lease, entity(id = row.id), request, DirectAckMode.SEND, original.id),
        )
        assertIs<DirectAckBeginResult.Begun>(store.beginDirectAckAttempt(intent.intent, UUID.randomUUID(), 1u))
        assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<DirectQueueMutationResult.Rejected>(
            store.mutateDirectQueueSelection(
                selected.selection, UUID.randomUUID(), DirectQueueMutation.DefinitelyUnsentFailure(request),
            ),
        ).reason)
        assertEquals(original, store.fetchPendingSendsForMessage(entity(id = row.id)).single())
        assertEquals(row, store.fetchMessage(entity(id = row.id)))
    }

    @Test fun queuedCleanupRejectsCompetingClaimsAndIntentsWithoutChangingTheirRows() = runTest {
        val lease = activate()
        val mutations = listOf(
            DirectQueueMutation.MissingContact,
            DirectQueueMutation.ReturnToPending,
            DirectQueueMutation.DefinitelyUnsentFailure(UUID.randomUUID()),
        )
        for (mutation in mutations) for (registered in listOf(false, true)) {
            val row = message(status = MessageStatus.SENT)
            val queued = pending(messageID = row.id)
            store.saveMessage(row)
            store.upsertPendingSend(queued)
            val selected = assertIs<DirectQueueCaptureResult.Captured>(
                store.captureDirectQueueSelection(lease, entity(id = row.id), queued.id),
            )
            val request = UUID.randomUUID()
            val intent = assertIs<DirectAckIntentResult.Captured>(
                store.captureDirectAckIntent(lease, entity(id = row.id), request, DirectAckMode.SEND, queued.id),
            )
            if (registered) assertIs<DirectAckBeginResult.Begun>(
                store.beginDirectAckAttempt(intent.intent, UUID.randomUUID(), 123u),
            )
            val expected = if (registered) DirectAckRejection.UNRESOLVED_OUTCOME else DirectAckRejection.NOT_ELIGIBLE
            assertEquals(expected, assertIs<DirectQueueMutationResult.Rejected>(
                store.mutateDirectQueueSelection(selected.selection, UUID.randomUUID(), mutation),
            ).reason)
            assertEquals(row, store.fetchMessage(entity(id = row.id)))
            assertEquals(listOf(queued), store.fetchPendingSendsForMessage(entity(id = row.id)))
            if (registered) {
                assertEquals(DirectAckPermission.CURRENT, assertIs<DirectAckObservation.Found>(
                    store.inspectDirectAckAttempt(lease, request),
                ).receipt.permission)
            } else {
                assertEquals(DirectAckPermission.CURRENT, assertIs<DirectAckIntentObservation.Found>(
                    store.inspectDirectAckIntent(lease, request),
                ).receipt.permission)
            }
        }
    }

    @Test fun queuedDefinitelyUnsentCleanupMayInvalidateOnlyItsOwnUnregisteredIntent() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val queued = pending(messageID = row.id)
        store.saveMessage(row)
        store.upsertPendingSend(queued)
        val selected = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), queued.id),
        )
        val request = UUID.randomUUID()
        val intent = assertIs<DirectAckIntentResult.Captured>(
            store.captureDirectAckIntent(lease, entity(id = row.id), request, DirectAckMode.SEND, queued.id),
        )
        val result = assertIs<DirectQueueMutationResult.Applied>(store.mutateDirectQueueSelection(
            selected.selection, UUID.randomUUID(), DirectQueueMutation.DefinitelyUnsentFailure(request),
        ))
        assertTrue(result.receipt.removed)
        assertEquals(MessageStatus.FAILED, store.fetchMessage(entity(id = row.id))?.status)
        assertTrue(store.fetchPendingSendsForMessage(entity(id = row.id)).isEmpty())
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckBeginResult.Rejected>(
            store.beginDirectAckAttempt(intent.intent, UUID.randomUUID(), 123u),
        ).reason)
    }

    @Test fun olderClaimCannotMutateOrDispatchWhileACompetingIntentIsCurrent() = runTest {
        val lease = activate()
        for (operation in listOf("wire", "failure", "ack", "bookkeeping", "dequeue")) {
            val row = message(status = MessageStatus.PENDING)
            val queued = pending(messageID = row.id)
            store.saveMessage(row)
            store.upsertPendingSend(queued)
            val firstRequest = UUID.randomUUID()
            val firstIntent = assertIs<DirectAckIntentResult.Captured>(
                store.captureDirectAckIntent(lease, entity(id = row.id), firstRequest, DirectAckMode.SEND, queued.id),
            )
            val firstWire = UUID.randomUUID()
            val first = assertIs<DirectAckBeginResult.Begun>(
                store.beginDirectAckAttempt(firstIntent.intent, firstWire, 123u),
            )
            val nextRequest = UUID.randomUUID()
            val next = assertIs<DirectAckIntentResult.Captured>(
                store.captureDirectAckIntent(lease, entity(id = row.id), nextRequest, DirectAckMode.SEND, queued.id),
            )
            if (operation == "wire") assertIs<DirectAckMutationResult.AlreadyApplied>(
                store.registerDirectAckDispatch(first.attempt, firstWire, 123u),
            )
            val result = when (operation) {
                "wire" -> store.registerDirectAckDispatch(first.attempt, UUID.randomUUID(), 456u)
                "failure" -> store.recordDirectFailure(first.attempt)
                "ack" -> store.settleDirectAck(first.attempt, 123u, 42u)
                "bookkeeping" -> store.applyDirectAckBookkeeping(
                    first.attempt, UUID.randomUUID(), DirectAckBookkeeping.RetryStarted(1, 3),
                )
                else -> store.completeDirectPending(first.attempt, queued.id, UUID.randomUUID())
            }
            assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<DirectAckMutationResult.Rejected>(result).reason)
            assertEquals(row, store.fetchMessage(entity(id = row.id)))
            assertEquals(listOf(queued), store.fetchPendingSendsForMessage(entity(id = row.id)))
            assertEquals(DirectAckPermission.CURRENT, assertIs<DirectAckIntentObservation.Found>(
                store.inspectDirectAckIntent(lease, nextRequest),
            ).receipt.permission)
            assertEquals(DirectAckRejection.UNRESOLVED_OUTCOME, assertIs<DirectAckBeginResult.Rejected>(
                store.beginDirectAckAttempt(next.intent, UUID.randomUUID(), 456u),
            ).reason)
            assertEquals(DirectAckIntentRetirement.Retired, store.retireDirectAckIntent(lease, nextRequest))
            assertEquals(DirectAckPermission.CURRENT, assertIs<DirectAckObservation.Found>(
                store.inspectDirectAckAttempt(lease, firstRequest),
            ).receipt.permission)
        }
    }

    @Test fun retiringAnUnbegunCompetingIntentRestoresThePriorClaimWithoutChangingRows() = runTest {
        val row = message(status = MessageStatus.PENDING)
        val first = begin(row)
        val successor = assertIs<DirectAckIntentResult.Captured>(
            store.captureDirectAckIntent(first.lease, entity(id = row.id), UUID.randomUUID(), DirectAckMode.SEND, null),
        )
        rejected(store.settleDirectAck(first.attempt, 123u, 42u), DirectAckRejection.NOT_ELIGIBLE)
        assertEquals(DirectAckIntentRetirement.Retired, store.retireDirectAckIntent(first.lease, successor.receipt.requestId))
        assertEquals(DirectAckIntentRetirement.NotCurrent, store.retireDirectAckIntent(first.lease, successor.receipt.requestId))
        assertEquals(row, store.fetchMessage(entity(id = row.id)))
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckBeginResult.Rejected>(
            store.beginDirectAckAttempt(successor.intent, UUID.randomUUID(), 456u),
        ).reason)
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(first.attempt, 123u, 42u))
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(entity(id = row.id))?.status)
    }

    @Test fun retiringAnIntentCannotRevokeAnAlreadyBegunAttempt() = runTest {
        val claim = begin(message(status = MessageStatus.PENDING))
        assertEquals(DirectAckIntentRetirement.AttemptBegun, store.retireDirectAckIntent(claim.lease, claim.request))
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(claim.attempt, 123u, 42u))
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(entity(id = claim.row.id))?.status)
    }

    @Test fun queuePreflightCannotSupersedeACompetingIntentOrUnresolvedClaim() = runTest {
        val lease = activate()
        for (begun in listOf(false, true)) {
            val row = message(status = MessageStatus.PENDING)
            val queued = pending(messageID = row.id, attempt = 2)
            store.saveMessage(row)
            store.upsertPendingSend(queued)
            val selected = assertIs<DirectQueueCaptureResult.Captured>(
                store.captureDirectQueueSelection(lease, entity(id = row.id), queued.id),
            )
            val request = UUID.randomUUID()
            val competing = assertIs<DirectAckIntentResult.Captured>(
                store.captureDirectAckIntent(lease, entity(id = row.id), request, DirectAckMode.SEND, queued.id),
            )
            if (begun) assertIs<DirectAckBeginResult.Begun>(
                store.beginDirectAckAttempt(competing.intent, UUID.randomUUID(), 123u),
            )
            else assertIs<DirectAckIntentResult.AlreadyCaptured>(
                store.captureDirectQueueIntent(selected.selection, request, DirectAckMode.SEND),
            )
            val expected = if (begun) DirectAckRejection.UNRESOLVED_OUTCOME else DirectAckRejection.NOT_ELIGIBLE
            assertEquals(expected, assertIs<DirectQueueBumpResult.Rejected>(
                store.bumpDirectQueueSelection(selected.selection, UUID.randomUUID()),
            ).reason)
            assertEquals(expected, assertIs<DirectQueuePreparationResult.Rejected>(
                store.prepareDirectQueueSelection(selected.selection, UUID.randomUUID(), row.timestamp + 1u, false),
            ).reason)
            assertEquals(expected, assertIs<DirectAckIntentResult.Rejected>(
                store.captureDirectQueueIntent(selected.selection, UUID.randomUUID(), DirectAckMode.SEND),
            ).reason)
            assertEquals(expected, assertIs<DirectPendingCaptureResult.Rejected>(
                store.captureDirectQueueCompletion(selected.selection, UUID.randomUUID(), DirectPendingCompletionReason.ZeroAttempts(0)),
            ).reason)
            assertEquals(row, store.fetchMessage(entity(id = row.id)))
            assertEquals(queued, store.fetchPendingSendsForMessage(entity(id = row.id)).single())
            if (!begun) {
                assertEquals(DirectAckIntentRetirement.Retired, store.retireDirectAckIntent(lease, request))
                assertIs<DirectQueueBumpResult.Applied>(store.bumpDirectQueueSelection(selected.selection, UUID.randomUUID()))
            }
        }
    }

    @Test fun queuedUnresolvedAttemptFencesSameMessageReplacementAcrossLeaseRetirement() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val queued = pending(messageID = row.id)
        store.upsertPendingSend(queued)
        val claim = begin(row, pendingId = queued.id, lease = lease)
        val key = entity(id = row.id)
        assertEquals(DirectAckIntentRetirement.AttemptBegun, store.retireDirectAckIntent(lease, claim.request))
        store.deletePendingSend(entity(id = queued.id))
        store.upsertPendingSend(queued)
        val next = activate(session = token.copy(generation = Generation(2)))
        assertEquals(DirectAckRejection.UNRESOLVED_OUTCOME, assertIs<DirectQueueCaptureResult.Rejected>(
            store.captureDirectQueueSelection(next, key, queued.id),
        ).reason)
        assertEquals(row, store.fetchMessage(key))
        assertEquals(queued, store.fetchPendingSendsForMessage(key).single())

        store.deleteMessage(key)
        store.saveMessage(row)
        store.upsertPendingSend(queued)
        assertIs<DirectQueueCaptureResult.Captured>(store.captureDirectQueueSelection(next, key, queued.id))
    }

    @Test fun crossMessagePendingReplacementInvalidatesDisplacedInlineIntent() = runTest {
        val lease = activate()
        val raw = db.openHelper.writableDatabase
        raw.execSQL("PRAGMA recursive_triggers = OFF")
        val columns = raw.query("PRAGMA table_info(pending_sends)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        for (update in listOf(false, true)) {
            val displacedMessage = message(status = MessageStatus.PENDING)
            val replacingMessage = message(status = MessageStatus.PENDING)
            store.saveMessage(displacedMessage)
            store.saveMessage(replacingMessage)
            val displaced = pending(messageID = displacedMessage.id)
            val retained = pending(messageID = displacedMessage.id)
            val mover = pending(messageID = replacingMessage.id)
            db.pendingSends().insert(displaced.toEntity())
            db.pendingSends().insert(retained.toEntity())
            db.pendingSends().insert(mover.toEntity())
            val inline = capture(displacedMessage, lease)
            val parameters = arrayOf(displaced.id.canonicalString(), RADIO_A.value.canonicalString(), mover.id.canonicalString())
            if (update) {
                raw.execSQL("UPDATE OR REPLACE pending_sends SET id = ? WHERE radioId = ? AND id = ?", parameters)
            } else {
                val projection = columns.joinToString(",") { if (it == "id") "?" else "`$it`" }
                raw.execSQL(
                    "INSERT OR REPLACE INTO pending_sends SELECT $projection FROM pending_sends WHERE radioId = ? AND id = ?",
                    parameters,
                )
            }

            assertEquals(0, db.directAcks().currentIntents(RADIO_A.value, displacedMessage.id))
            assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckBeginResult.Rejected>(
                store.beginDirectAckAttempt(inline.intent, UUID.randomUUID(), 123u),
            ).reason)
            assertEquals(DirectAckIntentRetirement.NotCurrent, store.retireDirectAckIntent(lease, inline.receipt.requestId))
            val selected = assertIs<DirectQueueCaptureResult.Captured>(
                store.captureDirectQueueSelection(lease, entity(id = displacedMessage.id), retained.id),
            )
            assertIs<DirectAckIntentResult.Captured>(
                store.captureDirectQueueIntent(selected.selection, UUID.randomUUID(), DirectAckMode.SEND),
            )
        }
    }

    @Test fun queuedCompletionRequiresAcceptanceOrDeliveryNotRegistration() = runTest {
        val lease = activate()
        for (deliveryFirst in listOf(false, true)) {
            val row = message(status = MessageStatus.PENDING)
            val queued = pending(messageID = row.id)
            store.upsertPendingSend(queued)
            val claim = begin(row, pendingId = queued.id, lease = lease)
            val request = UUID.randomUUID()
            rejected(store.completeDirectPending(claim.attempt, queued.id, request), DirectAckRejection.UNRESOLVED_OUTCOME)
            assertEquals(row, store.fetchMessage(entity(id = row.id)))
            assertEquals(queued, store.fetchPendingSendsForMessage(entity(id = row.id)).single())

            if (deliveryFirst) {
                assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(claim.attempt, 123u, 42u))
            } else {
                assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(claim.attempt, claim.wire, 456u))
            }
            assertIs<DirectAckMutationResult.Applied>(store.completeDirectPending(claim.attempt, queued.id, request))
            store.upsertPendingSend(queued)
            assertIs<DirectAckMutationResult.AlreadyApplied>(store.completeDirectPending(claim.attempt, queued.id, request))
            assertEquals(queued, store.fetchPendingSendsForMessage(entity(id = row.id)).single())
        }
    }

    @Test fun queuedNoDispatchCompletionWaitsForACompetingIntentAndCannotLoseItsScope() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val queued = pending(messageID = row.id)
        store.saveMessage(row)
        store.upsertPendingSend(queued)
        val selection = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), queued.id),
        ).selection
        val captureID = UUID.randomUUID()
        val reason = DirectPendingCompletionReason.ZeroAttempts(0)
        val completion = assertIs<DirectPendingCaptureResult.Captured>(
            store.captureDirectQueueCompletion(selection, captureID, reason),
        ).completion
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectPendingCaptureResult.Rejected>(
            store.captureDirectPendingCompletion(lease, entity(id = row.id), queued.id, captureID, reason),
        ).reason)
        val contender = UUID.randomUUID()
        assertIs<DirectAckIntentResult.Captured>(
            store.captureDirectAckIntent(lease, entity(id = row.id), contender, DirectAckMode.SEND, queued.id),
        )
        val completionID = UUID.randomUUID()
        assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<DirectPendingCompletionResult.Rejected>(
            store.completeDirectPendingWithoutDispatch(completion, completionID),
        ).reason)
        assertEquals(queued, store.fetchPendingSendsForMessage(entity(id = row.id)).single())
        assertEquals(DirectAckIntentRetirement.Retired, store.retireDirectAckIntent(lease, contender))
        assertIs<DirectPendingCompletionResult.Applied>(store.completeDirectPendingWithoutDispatch(completion, completionID))
        assertEquals(row, store.fetchMessage(entity(id = row.id)))
        assertTrue(store.fetchPendingSendsForMessage(entity(id = row.id)).isEmpty())
    }

    @Test fun queueCleanupCanRemoveAnOrphanOnlyWhileTheMessageRemainsAbsent() = runTest {
        val lease = activate()
        val original = pending()
        store.upsertPendingSend(original)
        val key = entity(id = original.messageID)
        val selected = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, key, original.id),
        )
        val result = assertIs<DirectQueueMutationResult.Applied>(
            store.mutateDirectQueueSelection(selected.selection, UUID.randomUUID(), DirectQueueMutation.MissingContact),
        )
        assertTrue(result.receipt.removed)
        assertNull(result.receipt.status)
        assertTrue(store.fetchPendingSendsForMessage(key).isEmpty())

        store.upsertPendingSend(original)
        val old = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, key, original.id),
        )
        val replacement = message(id = original.messageID, status = MessageStatus.PENDING)
        store.saveMessage(replacement)
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectQueueMutationResult.Rejected>(
            store.mutateDirectQueueSelection(old.selection, UUID.randomUUID(), DirectQueueMutation.MissingContact),
        ).reason)
        assertEquals(replacement, store.fetchMessage(key))
        assertEquals(original, store.fetchPendingSendsForMessage(key).single())
    }

    @Test fun transientDirectRemapCannotRewriteAReplacement() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.RETRYING)
        val original = pending(messageID = row.id)
        store.saveMessage(row)
        store.upsertPendingSend(original)
        val selected = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), original.id),
        )
        val request = UUID.randomUUID()
        assertEquals(MessageStatus.PENDING, assertIs<DirectQueueMutationResult.Applied>(
            store.mutateDirectQueueSelection(selected.selection, request, DirectQueueMutation.ReturnToPending),
        ).receipt.status)
        val replacement = row.copy(text = "new message", status = MessageStatus.FAILED)
        store.saveMessage(replacement)
        assertIs<DirectQueueMutationResult.AlreadyApplied>(
            store.mutateDirectQueueSelection(selected.selection, request, DirectQueueMutation.ReturnToPending),
        )
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectQueueMutationResult.Rejected>(
            store.mutateDirectQueueSelection(selected.selection, UUID.randomUUID(), DirectQueueMutation.ReturnToPending),
        ).reason)
        assertEquals(replacement, store.fetchMessage(entity(id = row.id)))
        assertEquals(original, store.fetchPendingSendsForMessage(entity(id = row.id)).single())
    }

    @Test fun restoreInvalidatesQueuedSelectionsEvenWhenItSkipsTheirRows() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val original = pending(messageID = row.id)
        store.saveMessage(row)
        store.upsertPendingSend(original)
        val selected = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), original.id),
        )
        store.withBackupRestore { _, _ -> ImportResult() }
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectQueueMutationResult.Rejected>(
            store.mutateDirectQueueSelection(selected.selection, UUID.randomUUID(), DirectQueueMutation.MissingContact),
        ).reason)
        assertEquals(row, store.fetchMessage(entity(id = row.id)))
        assertEquals(original, store.fetchPendingSendsForMessage(entity(id = row.id)).single())
    }

    @Test fun restorePreservesUnresolvedWiresButNotStaleHandlesOrRecreatedMessages() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val original = pending(messageID = row.id)
        store.upsertPendingSend(original)
        val claim = begin(row, pendingId = original.id, lease = lease)
        val before = assertNotNull(db.directAcks().incarnation(RADIO_A.value, row.id, "message"))

        store.withBackupRestore { _, _ -> ImportResult() }

        val after = assertNotNull(db.directAcks().incarnation(RADIO_A.value, row.id, "message"))
        assertNotEquals(before.incarnation, after.incarnation)
        rejected(store.settleDirectAck(claim.attempt, 123u, 1u))
        assertEquals(DirectAckRejection.UNRESOLVED_OUTCOME, assertIs<DirectQueueCaptureResult.Rejected>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), original.id),
        ).reason)
        assertEquals(1, db.directAcks().collidingPriorWires(
            RADIO_A.value, row.id, after.incarnation, UUID.randomUUID(), 123,
        ))

        store.deletePendingSend(entity(id = original.id))
        store.deleteMessage(entity(id = row.id))
        store.saveMessage(row)
        store.upsertPendingSend(original)
        assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), original.id),
        )
    }

    @Test fun restorePreservesOnlyTheExactPreviouslyAuthorizedRetryAndItsWireHistory() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val original = pending(messageID = row.id)
        store.upsertPendingSend(original)
        begin(row, pendingId = original.id, lease = lease)
        val retry = original.copy(id = UUID.randomUUID(), isResend = true)
        store.replacePendingSendForRetry(row.id, retry)
        val oldSelection = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), retry.id),
        )
        val beforeMessage = db.directAcks().incarnation(RADIO_A.value, row.id, "message")
        val beforePending = db.directAcks().incarnation(RADIO_A.value, retry.id, "pending")
        assertFailsWith<CancellationException> {
            store.withBackupRestore { _, _ -> throw CancellationException("Controlled restore rollback") }
        }
        assertEquals(beforeMessage, db.directAcks().incarnation(RADIO_A.value, row.id, "message"))
        assertEquals(beforePending, db.directAcks().incarnation(RADIO_A.value, retry.id, "pending"))
        assertEquals(0, db.directAcks().unresolvedQueuedClaims(RADIO_A.value, row.id, retry.id))

        store.withBackupRestore { _, _ -> ImportResult() }

        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectQueueBumpResult.Rejected>(
            store.bumpDirectQueueSelection(oldSelection.selection, UUID.randomUUID()),
        ).reason)
        assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), retry.id),
        )
        val persisted = store.fetchPendingSendsForMessage(entity(id = row.id)).single()
        store.upsertPendingSend(persisted)
        assertEquals(DirectAckRejection.UNRESOLVED_OUTCOME, assertIs<DirectQueueCaptureResult.Rejected>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), retry.id),
        ).reason)
        store.replacePendingSendForRetry(row.id, retry)
        val fresh = assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(lease, entity(id = row.id), retry.id),
        )
        val bumped = assertIs<DirectQueueBumpResult.Applied>(
            store.bumpDirectQueueSelection(fresh.selection, UUID.randomUUID()),
        ).receipt
        val intent = assertIs<DirectAckIntentResult.Captured>(
            store.captureDirectQueueIntent(bumped.selection, UUID.randomUUID(), DirectAckMode.RESEND),
        )
        val wire = UUID.randomUUID()
        val successor = assertIs<DirectAckBeginResult.Begun>(
            store.beginDirectAckAttempt(intent.intent, wire, 123u),
        ).attempt
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(successor, wire, 456u))
        rejected(store.settleDirectAck(successor, 123u, 1u), DirectAckRejection.AMBIGUOUS_ACK)
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(successor, 456u, 2u))
    }

    @Test fun transientClaimRemapRetainsOnlyItsOwnCurrentAuthority() = runTest {
        val claim = begin(message(status = MessageStatus.PENDING))
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectFailure(claim.attempt))
        val request = UUID.randomUUID()
        assertIs<DirectAckMutationResult.Applied>(
            store.applyDirectAckBookkeeping(claim.attempt, request, DirectAckBookkeeping.ReturnToPending),
        )
        assertEquals(MessageStatus.PENDING, store.fetchMessage(entity(id = claim.row.id))?.status)
        val replacement = claim.row.copy(text = "new owner", status = MessageStatus.FAILED)
        store.saveMessage(replacement)
        val replay = assertIs<DirectAckMutationResult.AlreadyApplied>(
            store.applyDirectAckBookkeeping(claim.attempt, request, DirectAckBookkeeping.ReturnToPending),
        )
        assertEquals(DirectAckPermission.INVALIDATED, replay.receipt.permission)
        rejected(store.applyDirectAckBookkeeping(claim.attempt, UUID.randomUUID(), DirectAckBookkeeping.ReturnToPending))
        assertEquals(replacement, store.fetchMessage(entity(id = claim.row.id)))
    }

    @Test fun issuedIntentRejectsBeforeBeginTextMutationSameValueUpdateAndIdenticalDtoAba() = runTest {
        val lease = activate()
        for (mutation in listOf<suspend (MessageDTO) -> Unit>(
            { store.saveMessage(it.copy(text = "replacement wire payload")) },
            { db.messages().upsert(it.toEntity()) },
            { db.messages().delete(RADIO_A.value, it.id); db.messages().insert(it.toEntity()) },
            { db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO messages SELECT * FROM messages") },
        )) {
            val row = message()
            store.saveMessage(row)
            val request = UUID.randomUUID()
            val issued = capture(row, lease, request)
            assertEquals(row, issued.receipt.message)
            assertIs<DirectAckIntentResult.AlreadyCaptured>(
                store.captureDirectAckIntent(lease, entity(id = row.id), request, DirectAckMode.SEND, null),
            )
            mutation(row)
            val independent = store.fetchMessage(entity(id = row.id))
            assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckBeginResult.Rejected>(
                store.beginDirectAckAttempt(issued.intent, UUID.randomUUID(), 123u),
            ).reason)
            assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckIntentResult.Rejected>(
                store.captureDirectAckIntent(lease, entity(id = row.id), request, DirectAckMode.SEND, null),
            ).reason)
            assertIs<DirectAckObservation.Rejected>(store.inspectDirectAckAttempt(lease, request))
            assertEquals(independent, store.fetchMessage(entity(id = row.id)))
        }
    }

    @Test fun issuedIntentRejectsSameIdPendingReplacementAndGenericPreflightAfterCapture() = runTest {
        val lease = activate()
        for (mutation in listOf<suspend (PendingSendDTO) -> Unit>(
            { store.upsertPendingSend(it) },
            { db.pendingSends().delete(RADIO_A.value, it.id); db.pendingSends().upsert(it.toEntity()) },
            { db.openHelper.writableDatabase.execSQL("UPDATE pending_sends SET attemptCount = COALESCE(attemptCount,0) + 1") },
            { db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO pending_sends SELECT * FROM pending_sends") },
        )) {
            val row = message()
            val queued = pending(messageID = row.id)
            store.saveMessage(row)
            store.upsertPendingSend(queued)
            val request = UUID.randomUUID()
            val issued = capture(row, lease, request, queued.id)
            assertEquals(queued, issued.receipt.pending)
            mutation(queued)
            assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckBeginResult.Rejected>(
                store.beginDirectAckAttempt(issued.intent, UUID.randomUUID(), 123u),
            ).reason)
            val history = assertIs<DirectAckIntentObservation.Found>(store.inspectDirectAckIntent(lease, request)).receipt
            assertEquals(queued.id, history.pendingSendId)
            assertNull(history.pending)
            assertNotEquals(DirectAckPermission.CURRENT, history.permission)
            assertEquals(row, store.fetchMessage(entity(id = row.id)))
        }
    }

    @Test fun captureAfterOldCacheWindowIssuesActualPayloadAndPreparationMustPrecedeCapture() = runTest {
        val lease = activate()
        val earlier = message(text = "earlier unguarded wire A")
        val current = earlier.copy(text = "actual wire B", timestamp = earlier.timestamp + 1u)
        store.saveMessage(earlier)
        val cached = assertNotNull(store.fetchMessage(entity(id = earlier.id)))
        store.saveMessage(current)
        val queued = pending(messageID = earlier.id, attempt = 1)
        store.upsertPendingSend(queued)
        val issued = capture(earlier, lease, pendingId = queued.id)
        val wireInputs = assertNotNull(issued.receipt.message)
        assertEquals(current, wireInputs)
        assertNotEquals(cached.text, wireInputs.text)
        assertEquals(queued, issued.receipt.pending)
        val begun = assertIs<DirectAckBeginResult.Begun>(
            store.beginDirectAckAttempt(issued.intent, UUID.randomUUID(), 456u),
        )
        assertEquals(current, begun.receipt.message)
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(begun.attempt, 456u, 7u))
        assertEquals(current.copy(status = MessageStatus.DELIVERED, ackCode = 456u, roundTripTime = 7u),
            store.fetchMessage(entity(id = earlier.id)))
    }

    private fun assertPayloadColumnsNull(table: String, messageId: UUID, vararg prefixes: String) =
        assertPayloadColumnsNullIn(db, table, messageId, *prefixes)

    private fun assertPayloadColumnsNullIn(
        database: MeshCoreDatabase, table: String, messageId: UUID, vararg prefixes: String,
    ) {
        database.openHelper.readableDatabase.query(
            "SELECT * FROM $table WHERE radioId = ? AND messageId = ?",
            arrayOf(RADIO_A.value.toString().uppercase(), messageId.toString().uppercase()),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst(), "$table tombstone must remain")
            do {
                val payloadColumns = cursor.columnNames.filter { name -> prefixes.any { prefix -> name.startsWith(prefix) } }
                assertTrue(payloadColumns.isNotEmpty(), "$table payload columns must be checked")
                for (column in payloadColumns) assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow(column)), "$table.$column")
            } while (cursor.moveToNext())
        }
    }

    @Test fun messageDeleteRedactionReplaceAndConversationDevicePathsEraseAllHistoricalPayloads() = runTest {
        val lease = activate()
        for (erase in listOf<suspend (MessageDTO, DeviceDTO) -> Unit>(
            { row, _ -> store.deleteMessage(entity(id = row.id)) },
            { row, _ -> db.messages().delete(RADIO_A.value, row.id) },
            { row, _ -> db.openHelper.writableDatabase.execSQL(
                "DELETE FROM messages WHERE radioId = ? AND id = ?",
                arrayOf(RADIO_A.value.toString().uppercase(), row.id.toString().uppercase())) },
            { row, _ -> store.deleteMessagesForContact(entity(id = assertNotNull(row.contactID))) },
            { _, device -> store.deleteDeviceData(device.id) },
            { _, device -> store.deleteDeviceAndData(device.id) },
            { row, _ -> store.saveMessage(row.copy(text = "redacted replacement")) },
            { row, _ -> store.saveMessage(row.copy(contactID = null, channelIndex = 1u)) },
            { row, _ -> db.openHelper.writableDatabase.execSQL(
                "UPDATE messages SET text = '' WHERE radioId = ? AND id = ?",
                arrayOf(RADIO_A.value.toString().uppercase(), row.id.toString().uppercase())) },
            { _, _ -> db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO messages SELECT * FROM messages") },
            { row, _ -> db.messages().delete(RADIO_A.value, row.id); db.messages().insert(row.toEntity()) },
        )) {
            val row = message(text = "must not survive in authority history").copy(
                linkPreviewImageData = key(0x71), linkPreviewIconData = key(0x72), pathNodes = key(0x73),
            )
            val queued = pending(messageID = row.id).copy(messageText = row.text)
            val device = device()
            store.saveDevice(device)
            store.upsertPendingSend(queued)
            val claim = begin(row, pendingId = queued.id, lease = lease)
            val noWireRequest = UUID.randomUUID()
            val noWire = assertIs<DirectPendingCaptureResult.Captured>(
                store.captureDirectPendingCompletion(lease, entity(id = row.id), queued.id, noWireRequest,
                    DirectPendingCompletionReason.ZeroAttempts(0)),
            )
            assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(claim.attempt, 123u, 7u))
            erase(row, device)
            val history = observed(claim)
            assertNull(history.message)
            assertNull(assertNotNull(history.delivery).message)
            assertEquals(123u, history.delivery?.ackCode)
            assertEquals(7u, history.delivery?.roundTripTime)
            assertNotEquals(DirectAckPermission.CURRENT, history.permission)
            val intentHistory = assertIs<DirectAckIntentObservation.Found>(
                store.inspectDirectAckIntent(lease, claim.request),
            ).receipt
            assertNull(intentHistory.message)
            assertNull(intentHistory.pending)
            assertEquals(queued.id, intentHistory.pendingSendId)
            val completionHistory = assertIs<DirectPendingCompletionObservation.Found>(
                store.inspectDirectPendingCompletion(lease, noWireRequest),
            ).receipt
            assertNull(completionHistory.message)
            assertNull(completionHistory.pending)
            assertEquals(queued.id, completionHistory.pendingSendId)
            assertIs<DirectPendingCompletionResult.Rejected>(
                store.completeDirectPendingWithoutDispatch(noWire.completion, UUID.randomUUID()),
            )
            assertIs<DirectAckBeginResult.Rejected>(store.beginDirectAckAttempt(claim.intent, claim.wire, 123u))
            assertIs<DirectAckIntentResult.Rejected>(
                store.captureDirectAckIntent(lease, entity(id = row.id), claim.request, DirectAckMode.SEND, queued.id),
            )
            val replay = assertIs<DirectAckMutationResult.AlreadyApplied>(store.settleDirectAck(claim.attempt, 123u, 7u))
            assertNull(replay.receipt.message)
            assertNull(replay.receipt.delivery?.message)
            assertNotEquals(DirectAckPermission.CURRENT, replay.receipt.permission)
            assertPayloadColumnsNull("direct_ack_claims", row.id, "snapshot_")
            assertPayloadColumnsNull("direct_ack_deliveries", row.id, "snapshot_")
            assertPayloadColumnsNull("direct_ack_intents", row.id, "message_", "pending_")
            assertPayloadColumnsNull("direct_pending_completions", row.id, "message_", "pending_")
        }
    }

    @Test fun ownNoDispatchCompletionErasesPendingSnapshotWithoutRecreatingItOnReplay() = runTest {
        val lease = activate()
        val row = message()
        val queued = pending(messageID = row.id).copy(messageText = "erase pending payload")
        store.saveMessage(row)
        store.upsertPendingSend(queued)
        val request = UUID.randomUUID()
        val captured = assertIs<DirectPendingCaptureResult.Captured>(
            store.captureDirectPendingCompletion(lease, entity(id = row.id), queued.id, request,
                DirectPendingCompletionReason.ZeroAttempts(0)),
        )
        val completion = UUID.randomUUID()
        val committed = assertIs<DirectPendingCompletionResult.Applied>(
            store.completeDirectPendingWithoutDispatch(captured.completion, completion),
        ).receipt
        assertEquals(row, committed.message)
        assertEquals(queued.id, committed.pendingSendId)
        assertNull(committed.pending)
        assertEquals(completion, committed.completedRequestId)
        assertNotEquals(DirectPendingCompletionPermission.CURRENT, committed.permission)
        store.upsertPendingSend(queued)
        val replay = assertIs<DirectPendingCompletionResult.AlreadyApplied>(
            store.completeDirectPendingWithoutDispatch(captured.completion, completion),
        ).receipt
        assertNull(replay.pending)
        assertEquals(listOf(queued), store.fetchPendingSendsForMessage(entity(id = row.id)))
        assertPayloadColumnsNull("direct_pending_completions", row.id, "pending_")
        store.deleteMessage(entity(id = row.id))
        val erased = assertIs<DirectPendingCompletionObservation.Found>(
            store.inspectDirectPendingCompletion(lease, request),
        ).receipt
        assertNull(erased.message)
        assertNull(erased.pending)
        assertEquals(completion, erased.completedRequestId)
    }

    @Test fun intentCommitThenCallerCancellationReplaysOnlyOriginalCaptureAndConsumedBegin() = runTest {
        val lease = activate()
        val row = message()
        store.saveMessage(row)
        val request = UUID.randomUUID()
        val caller = Job()
        assertFailsWith<CancellationException> {
            withContext(caller) {
                capture(row, lease, request)
                caller.cancel()
                ensureActive()
            }
        }
        val recovered = assertIs<DirectAckIntentResult.AlreadyCaptured>(
            store.captureDirectAckIntent(lease, entity(id = row.id), request, DirectAckMode.SEND, null),
        )
        assertEquals(row, recovered.receipt.message)
        assertEquals(DirectAckPermission.CURRENT, recovered.receipt.permission)
        val wire = UUID.randomUUID()
        assertIs<DirectAckBeginResult.Begun>(store.beginDirectAckAttempt(recovered.intent, wire, 123u))
        assertIs<DirectAckBeginResult.AlreadyBegun>(store.beginDirectAckAttempt(recovered.intent, wire, 123u))
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckIntentResult.Rejected>(
            store.captureDirectAckIntent(lease, entity(id = row.id), request, DirectAckMode.SEND, null),
        ).reason)
    }

    @Test fun intentForeignContextRestoreRollbackAndGenerationCannotRenewCapturedBindings() = runTest {
        val lease = activate()
        val row = message()
        store.saveMessage(row)
        val request = UUID.randomUUID()
        val issued = capture(row, lease, request)
        val other = newStore(db)
        try {
            assertEquals(DirectAckRejection.INVALID_HANDLE, assertIs<DirectAckBeginResult.Rejected>(
                other.beginDirectAckAttempt(issued.intent, UUID.randomUUID(), 123u),
            ).reason)
            assertFailsWith<CancellationException> {
                store.withBackupRestore { _, _ -> throw CancellationException("Controlled restore rollback") }
            }
            val retained = assertIs<DirectAckIntentResult.AlreadyCaptured>(
                store.captureDirectAckIntent(lease, entity(id = row.id), request, DirectAckMode.SEND, null),
            )
            assertEquals(DirectAckPermission.CURRENT, retained.receipt.permission)
            store.withBackupRestore { _, _ -> ImportResult() }
            assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckBeginResult.Rejected>(
                store.beginDirectAckAttempt(issued.intent, UUID.randomUUID(), 123u),
            ).reason)
            val fresh = capture(row, lease)
            activate(other, token.copy(generation = Generation(2)))
            assertEquals(DirectAckRejection.STALE_LEASE, assertIs<DirectAckBeginResult.Rejected>(
                store.beginDirectAckAttempt(fresh.intent, UUID.randomUUID(), 123u),
            ).reason)
        } finally { other.close() }
    }

    @Test fun explicitRevokeSuccessorActivationAndProcessCloseEraseOnlyRetiredLeasePayloads() = runTest {
        var currentToken = token
        for (retire in listOf<suspend (RoomPersistenceStore, DirectAckLease) -> Unit>(
            { target, lease -> assertIs<DirectAckRevocationResult.Revoked>(target.revokeDirectAckSession(lease)) },
            { target, _ ->
                currentToken = currentToken.copy(generation = Generation(currentToken.generation.value + 1))
                activate(target, currentToken)
            },
            { target, _ -> target.close() },
        )) {
            val target = newStore(db)
            currentToken = token.copy(epoch = ProcessEpoch(UUID.randomUUID()))
            val lease = activate(target, currentToken)
            val row = message(text = "retired lease payload")
            val queued = pending(messageID = row.id)
            target.upsertPendingSend(queued)
            val claim = begin(row, pendingId = queued.id, lease = lease, target = target)
            val completion = assertIs<DirectPendingCaptureResult.Captured>(
                target.captureDirectPendingCompletion(lease, entity(id = row.id), queued.id, UUID.randomUUID(),
                    DirectPendingCompletionReason.ZeroAttempts(0)),
            )
            target.settleDirectAck(claim.attempt, 123u, 7u)
            val productRow = target.fetchMessage(entity(id = row.id))
            retire(target, lease)
            assertPayloadColumnsNull("direct_ack_claims", row.id, "snapshot_")
            assertPayloadColumnsNull("direct_ack_deliveries", row.id, "snapshot_")
            assertPayloadColumnsNull("direct_ack_intents", row.id, "message_", "pending_")
            assertPayloadColumnsNull("direct_pending_completions", row.id, "message_", "pending_")
            val persisted = assertNotNull(db.directAcks().claim(RADIO_A.value, claim.request))
            assertEquals(DirectAckPhase.DELIVERED.name, persisted.phase)
            assertFalse(persisted.current)
            val delivery = assertNotNull(db.directAcks().delivery(RADIO_A.value, claim.request))
            assertEquals(123L, delivery.ackCode)
            assertEquals(7L, delivery.roundTripTime)
            assertNull(delivery.snapshot)
            assertEquals(productRow, store.fetchMessage(entity(id = row.id)))
            assertEquals(listOf(queued), store.fetchPendingSendsForMessage(entity(id = row.id)))
            assertNotNull(db.directAcks().pendingCompletion(RADIO_A.value, completion.receipt.captureRequestId))
            target.close()
        }
    }

    @Test fun ownFailureAndMatchingAckCommitAtomicallyWithExactTupleReplay() = runTest {
        val claim = begin()
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(claim.attempt, claim.wire, 456u))
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectFailure(claim.attempt))
        val failed = assertNotNull(store.fetchMessage(entity(id = claim.row.id)))
        assertEquals(MessageStatus.FAILED, failed.status)
        assertNull(failed.roundTripTime)
        val result = assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(claim.attempt, 123u, 42u))
        val delivered = assertNotNull(store.fetchMessage(entity(id = claim.row.id)))
        assertEquals(failed.copy(status = MessageStatus.DELIVERED, ackCode = 123u, roundTripTime = 42u), delivered)
        assertEquals(delivered, assertNotNull(result.receipt.delivery).message)
        assertIs<DirectAckMutationResult.AlreadyApplied>(store.settleDirectAck(claim.attempt, 123u, 42u))
        rejected(store.settleDirectAck(claim.attempt, 123u, 43u), DirectAckRejection.CONFLICT)
    }

    @Test fun activationAndBeginReplayRecoverOnlyTheExactStillCurrentLogicalClaim() = runTest {
        val activation = UUID.randomUUID()
        val lease = activate(request = activation)
        assertIs<DirectAckActivationResult.AlreadyActivated>(store.activateDirectAckSession(token, activation))
        val claim = begin(lease = lease)
        assertIs<DirectAckBeginResult.AlreadyBegun>(
            store.beginDirectAckAttempt(claim.intent, claim.wire, 123u),
        )
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckBeginResult.Rejected>(
            store.beginDirectAckAttempt(claim.intent, claim.wire, 124u),
        ).reason)
        val next = assertIs<DirectAckIntentResult.Captured>(
            store.captureDirectAckIntent(lease, entity(id = claim.row.id), UUID.randomUUID(), DirectAckMode.SEND, null),
        )
        val successor = assertIs<DirectAckBeginResult.Begun>(
            store.beginDirectAckAttempt(next.intent, UUID.randomUUID(), 456u),
        )
        rejected(store.settleDirectAck(claim.attempt, 123u, 1u))
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckBeginResult.Rejected>(
            store.beginDirectAckAttempt(claim.intent, claim.wire, 123u),
        ).reason)
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectFailure(successor.attempt))
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(successor.attempt, 456u, 2u))
        activate(session = token.copy(generation = Generation(2)))
        assertEquals(DirectAckRejection.STALE_LEASE, assertIs<DirectAckActivationResult.Rejected>(
            store.activateDirectAckSession(token, activation),
        ).reason)
    }

    @Test fun competingFailedBeforeOwnFailureAndAfterOwnCommitRemainIndependent() = runTest {
        val lease = activate()
        for (before in listOf(true, false)) {
            val claim = begin(lease = lease)
            if (!before) assertIs<DirectAckMutationResult.Applied>(store.recordDirectFailure(claim.attempt))
            store.updateMessageStatusUnlessDelivered(entity(id = claim.row.id), MessageStatus.FAILED)
            val independent = assertNotNull(store.fetchMessage(entity(id = claim.row.id)))
            if (before) rejected(store.recordDirectFailure(claim.attempt))
            else {
                val historical = assertIs<DirectAckMutationResult.AlreadyApplied>(store.recordDirectFailure(claim.attempt))
                assertEquals(DirectAckPermission.INVALIDATED, historical.receipt.permission)
            }
            rejected(store.settleDirectAck(claim.attempt, 123u, 99u))
            assertEquals(independent, store.fetchMessage(entity(id = claim.row.id)))
            assertNull(independent.roundTripTime)
        }
    }

    @Test fun callerCancellationAfterFailureCommitDoesNotCreateOrLoseAuthority() = runTest {
        val claim = begin()
        val caller = Job()
        assertFailsWith<CancellationException> {
            withContext(caller) {
                store.recordDirectFailure(claim.attempt)
                caller.cancel()
                ensureActive()
            }
        }
        assertEquals(DirectAckPhase.FAILED, observed(claim).phase)
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(claim.attempt, 123u, 123u))
        assertEquals(MessageStatus.DELIVERED, assertNotNull(store.fetchMessage(entity(id = claim.row.id))).status)
        val other = begin(lease = claim.lease)
        val cancelled = Job()
        assertFailsWith<CancellationException> {
            withContext(cancelled) {
                store.recordDirectFailure(other.attempt)
                cancelled.cancel()
                ensureActive()
            }
        }
        db.messages().setStatus(RADIO_A.value, other.row.id, 4)
        rejected(store.settleDirectAck(other.attempt, 123u, 123u))
        val independent = assertNotNull(store.fetchMessage(entity(id = other.row.id)))
        assertEquals(MessageStatus.FAILED, independent.status)
        assertNull(independent.roundTripTime)
    }

    @Test fun failureRollbackIsAnExplicitFailureAndDoesNotInventACommittedReceipt() = runTest {
        val claim = begin()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_direct_failure BEFORE UPDATE ON messages WHEN NEW.statusRawValue = 4 " +
                "BEGIN SELECT RAISE(ABORT,'independent failure control'); END",
        )
        assertFailsWith<PersistenceStoreException> { store.recordDirectFailure(claim.attempt) }
        assertEquals(1, reported.size)
        assertEquals(DirectAckPhase.REGISTERED, observed(claim).phase)
        assertEquals(claim.row, store.fetchMessage(entity(id = claim.row.id)))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_direct_failure")
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectFailure(claim.attempt))
    }

    @Test fun cancellationDuringActualRoomFailureWriteRollsBackInsteadOfGrantingFailureAuthority() = runTest {
        var cancelOnWrite: Job? = null
        val local = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .addCallback(DirectAckStorage.callback).allowMainThreadQueries()
            .setQueryExecutor(immediate).setTransactionExecutor(immediate)
            .setQueryCallback({ sql, _ ->
                if (sql.startsWith("UPDATE messages SET failureSeen")) cancelOnWrite?.cancel()
            }, immediate).build()
        val target = newStore(local)
        try {
            val claim = begin(lease = activate(target), target = target)
            val caller = Job()
            cancelOnWrite = caller
            assertFailsWith<CancellationException> { withContext(caller) { target.recordDirectFailure(claim.attempt) } }
            cancelOnWrite = null
            assertEquals(claim.row, target.fetchMessage(entity(id = claim.row.id)))
            assertEquals(DirectAckPhase.REGISTERED, observed(claim, target).phase)
            assertEquals(DirectAckPermission.CURRENT, observed(claim, target).permission)
        } finally { cancelOnWrite = null; target.close(); local.close() }
    }

    @Test fun additionalSameValueMutationInsideTheFailureStatementCannotBeRegrantedAsOwnWrite() = runTest {
        val claim = begin()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER independently_repeat_failure AFTER UPDATE ON messages " +
                "WHEN OLD.statusRawValue != 4 AND NEW.statusRawValue = 4 BEGIN " +
                "UPDATE messages SET statusRawValue = 4 WHERE radioId = NEW.radioId AND id = NEW.id; END",
        )
        val failure = assertFailsWith<PersistenceStoreException> { store.recordDirectFailure(claim.attempt) }
        assertEquals(PersistenceStoreError.InvalidData, failure.error)
        assertEquals(claim.row, store.fetchMessage(entity(id = claim.row.id)))
        assertEquals(DirectAckPhase.REGISTERED, observed(claim).phase)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER independently_repeat_failure")
    }

    @Test fun retryNewFailureAndOldAckCannotPromoteTheNewFailure() = runTest {
        val claim = begin()
        store.recordDirectFailure(claim.attempt)
        store.replacePendingSendForRetry(claim.row.id, pending(messageID = claim.row.id))
        store.updateMessageStatusUnlessDelivered(entity(id = claim.row.id), MessageStatus.FAILED)
        val independent = assertNotNull(store.fetchMessage(entity(id = claim.row.id)))
        rejected(store.settleDirectAck(claim.attempt, 123u, 123u))
        assertEquals(independent, store.fetchMessage(entity(id = claim.row.id)))
        assertEquals(MessageStatus.FAILED, independent.status)
        assertNull(independent.roundTripTime)
    }

    @Test fun everyGenericDaoAndRawMessageMutationFencesOldAuthority() = runTest {
        val lease = activate()
        for (mutation in listOf<suspend (MessageDTO) -> Unit>(
            { db.messages().setStatus(RADIO_A.value, it.id, 4) },
            { db.messages().upsert(it.toEntity()) },
            { store.saveMessage(it) },
            { db.messages().setAck(RADIO_A.value, it.id, 123, 2, null) },
            { db.messages().setAck(RADIO_A.value, it.id, 456, 3, 7) },
            { db.messages().setRetryStatus(RADIO_A.value, it.id, 5, 1, 4) },
            { db.openHelper.writableDatabase.execSQL("UPDATE messages SET statusRawValue = statusRawValue") },
            { db.messages().delete(RADIO_A.value, it.id); db.messages().insert(it.toEntity()) },
            { db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO messages SELECT * FROM messages") },
        )) {
            val claim = begin(lease = lease)
            mutation(claim.row)
            val independent = store.fetchMessage(entity(id = claim.row.id))
            rejected(store.settleDirectAck(claim.attempt, 123u, 123u))
            assertEquals(independent, store.fetchMessage(entity(id = claim.row.id)))
        }
    }

    @Test fun additivePredictedAndReturnedCodesSurviveOwnRetryButNotGenericWrites() = runTest {
        val claim = begin()
        store.recordDirectSendAccepted(claim.attempt, claim.wire, 456u)
        val retry = UUID.randomUUID()
        store.applyDirectAckBookkeeping(claim.attempt, retry, DirectAckBookkeeping.RetryStarted(1, 4))
        val wire2 = UUID.randomUUID()
        store.registerDirectAckDispatch(claim.attempt, wire2, 789u)
        store.recordDirectSendAccepted(claim.attempt, wire2, 987u)
        val codes = observed(claim).wires.flatMap { listOfNotNull(it.predictedAckCode, it.acceptedAckCode) }.toSet()
        assertEquals(setOf(123u, 456u, 789u, 987u), codes)
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(claim.attempt, 456u, 88u))
        assertIs<DirectAckMutationResult.AlreadyDelivered>(
            store.applyDirectAckBookkeeping(claim.attempt, UUID.randomUUID(), DirectAckBookkeeping.RetryExhausted),
        )
    }

    @Test fun earlyPredictedAckAndExplicitDeliveredOrFailedResendsPreserveTerminalBookkeeping() = runTest {
        val lease = activate()
        for (initial in listOf(MessageStatus.DELIVERED, MessageStatus.FAILED)) {
            val claim = begin(row = message(status = initial), mode = DirectAckMode.RESEND, lease = lease)
            store.recordDirectSendAccepted(claim.attempt, claim.wire, 456u)
            assertEquals(initial, assertNotNull(store.fetchMessage(entity(id = claim.row.id))).status)
            assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(claim.attempt, 123u, 123u))
            val count = UUID.randomUUID()
            store.applyDirectAckBookkeeping(claim.attempt, count, DirectAckBookkeeping.ResendCountIncrement)
            assertIs<DirectAckMutationResult.AlreadyApplied>(
                store.applyDirectAckBookkeeping(claim.attempt, count, DirectAckBookkeeping.ResendCountIncrement),
            )
            val row = assertNotNull(store.fetchMessage(entity(id = claim.row.id)))
            assertEquals(MessageStatus.DELIVERED, row.status)
            assertEquals(claim.row.sendCount + 1, row.sendCount)
            assertEquals(123u, row.roundTripTime)
            db.messages().setStatus(RADIO_A.value, row.id, 4)
            rejected(store.applyDirectAckBookkeeping(claim.attempt, UUID.randomUUID(), DirectAckBookkeeping.ResendCountIncrement))
        }
        val early = begin(lease = lease)
        store.settleDirectAck(early.attempt, 123u, 7u)
        store.recordDirectSendAccepted(early.attempt, early.wire, 456u)
        assertEquals(7u, assertNotNull(store.fetchMessage(entity(id = early.row.id))).roundTripTime)
    }

    @Test fun pendingCompletionIsExactOncePreservesLateAckAndCannotDeleteAReplacement() = runTest {
        val row = message()
        val queued = pending(messageID = row.id)
        store.upsertPendingSend(queued)
        val claim = begin(row, pendingId = queued.id)
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(claim.attempt, claim.wire, 123u))
        val completion = UUID.randomUUID()
        assertIs<DirectAckMutationResult.Applied>(store.completeDirectPending(claim.attempt, queued.id, completion))
        assertTrue(store.fetchPendingSendsForMessage(entity(id = row.id)).isEmpty())
        store.upsertPendingSend(queued)
        assertIs<DirectAckMutationResult.AlreadyApplied>(store.completeDirectPending(claim.attempt, queued.id, completion))
        assertEquals(listOf(queued), store.fetchPendingSendsForMessage(entity(id = row.id)))
        rejected(store.settleDirectAck(claim.attempt, 123u, 4u))
        val row2 = message()
        val queued2 = pending(messageID = row2.id)
        store.upsertPendingSend(queued2)
        val live = begin(row2, pendingId = queued2.id, lease = claim.lease)
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(live.attempt, live.wire, 123u))
        store.completeDirectPending(live.attempt, queued2.id, UUID.randomUUID())
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(live.attempt, 123u, 4u))
    }

    @Test fun genericPendingDaoBumpAndRawSameValueOrReplacementInvalidateCapturedCompletion() = runTest {
        val lease = activate()
        for (mutation in listOf<suspend (PendingSendDTO) -> Unit>(
            { store.upsertPendingSend(it) },
            { db.openHelper.writableDatabase.execSQL("UPDATE pending_sends SET attemptCount = COALESCE(attemptCount,0) + 1") },
            { db.openHelper.writableDatabase.execSQL("UPDATE pending_sends SET messageText = messageText") },
            { db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO pending_sends SELECT * FROM pending_sends") },
        )) {
            val row = message()
            val queued = pending(messageID = row.id)
            store.upsertPendingSend(queued)
            val claim = begin(row, pendingId = queued.id, lease = lease)
            assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(claim.attempt, claim.wire, 123u))
            mutation(queued)
            val independent = store.fetchPendingSendsForMessage(entity(id = row.id))
            rejected(store.completeDirectPending(claim.attempt, queued.id, UUID.randomUUID()))
            rejected(store.settleDirectAck(claim.attempt, 123u, 4u))
            assertEquals(independent, store.fetchPendingSendsForMessage(entity(id = row.id)))
        }
    }

    @Test fun rawPendingReplacementRemappingMessageInvalidatesBothIncludingAnInlineClaim() = runTest {
        val lease = activate()
        val original = message()
        val replacement = message()
        val queued = pending(messageID = original.id)
        store.upsertPendingSend(queued)
        val inline = begin(original, lease = lease)
        val other = begin(replacement, lease = lease)
        val columns = db.openHelper.readableDatabase.query("PRAGMA table_info(pending_sends)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        val projection = columns.joinToString(",") { if (it == "messageID") "?" else "`$it`" }
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO pending_sends SELECT $projection FROM pending_sends WHERE id = ?",
            arrayOf(replacement.id.toString().uppercase(), queued.id.toString().uppercase()),
        )
        rejected(store.settleDirectAck(inline.attempt, 123u, 1u))
        rejected(store.settleDirectAck(other.attempt, 123u, 1u))
        assertEquals(original, store.fetchMessage(entity(id = original.id)))
        assertEquals(replacement, store.fetchMessage(entity(id = replacement.id)))
    }

    @Test fun nestedPendingMutationCannotBeMistakenForOwnFailureOrOwnPendingCompletion() = runTest {
        val lease = activate()
        for (completion in listOf(false, true)) {
            val row = message()
            val queued = pending(messageID = row.id)
            store.upsertPendingSend(queued)
            if (completion) store.upsertPendingSend(pending(messageID = row.id, sequence = 1))
            val before = store.fetchPendingSendsForMessage(entity(id = row.id))
            val claim = begin(row, pendingId = queued.id, lease = lease)
            if (completion) {
                assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(claim.attempt, claim.wire, 123u))
            }
            val trigger = if (completion) {
                "AFTER DELETE ON pending_sends BEGIN UPDATE pending_sends SET messageText = messageText " +
                    "WHERE radioId = OLD.radioId AND messageID = OLD.messageID; END"
            } else {
                "AFTER UPDATE ON messages WHEN NEW.statusRawValue = 4 BEGIN " +
                    "UPDATE pending_sends SET messageText = messageText WHERE radioId = NEW.radioId AND messageID = NEW.id; END"
            }
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER independently_mutate_pending $trigger")
            val failure = assertFailsWith<PersistenceStoreException> {
                if (completion) store.completeDirectPending(claim.attempt, queued.id, UUID.randomUUID())
                else store.recordDirectFailure(claim.attempt)
            }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
            assertEquals(claim.row, store.fetchMessage(entity(id = row.id)))
            assertEquals(before, store.fetchPendingSendsForMessage(entity(id = row.id)))
            assertEquals(DirectAckPermission.CURRENT, observed(claim).permission)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER independently_mutate_pending")
        }
    }

    @Test fun separateContextsGenerationEpochRevokeAndRestoreFenceOldAuthority() = runTest {
        val claim = begin()
        val other = newStore(db)
        rejected(other.recordDirectFailure(claim.attempt), DirectAckRejection.INVALID_HANDLE)
        val successor = token.copy(generation = Generation(2))
        activate(other, successor)
        rejected(store.settleDirectAck(claim.attempt, 123u, 1u), DirectAckRejection.STALE_LEASE)
        assertIs<DirectAckActivationResult.Rejected>(store.activateDirectAckSession(token, UUID.randomUUID()))
        assertIs<DirectAckActivationResult.Rejected>(
            store.activateDirectAckSession(token.copy(generation = Generation(3)), UUID.randomUUID()),
        )
        val newProcess = newStore(db)
        val newToken = token.copy(epoch = ProcessEpoch(UUID.randomUUID()), generation = Generation(0))
        val newLease = activate(newProcess, newToken)
        assertIs<DirectAckActivationResult.Rejected>(other.activateDirectAckSession(successor.copy(generation = Generation(3)), UUID.randomUUID()))
        val live = begin(lease = newLease, target = newProcess)
        newProcess.withBackupRestore { _, _ -> ImportResult() }
        rejected(newProcess.settleDirectAck(live.attempt, 123u, 1u))
        newProcess.revokeDirectAckSession(newLease)
        rejected(newProcess.settleDirectAck(live.attempt, 123u, 1u), DirectAckRejection.STALE_LEASE)
        assertIs<DirectAckActivationResult.Rejected>(newProcess.activateDirectAckSession(newToken, UUID.randomUUID()))
        other.close()
        newProcess.close()
    }

    @Test fun noDispatchCaptureValidatesReasonAndCompletionReplayCannotDeleteReplacementOrGrantAck() = runTest {
        val lease = activate()
        for (zero in listOf(false, true)) {
            val row = message(status = if (zero) MessageStatus.PENDING else MessageStatus.DELIVERED)
            val queued = pending(messageID = row.id)
            store.saveMessage(row)
            store.upsertPendingSend(queued)
            val request = UUID.randomUUID()
            val reason = if (zero) DirectPendingCompletionReason.ZeroAttempts(0)
                else DirectPendingCompletionReason.AlreadyDeliveredSend
            val captured = assertIs<DirectPendingCaptureResult.Captured>(
                store.captureDirectPendingCompletion(lease, entity(id = row.id), queued.id, request, reason),
            )
            assertEquals(row, captured.receipt.message)
            assertEquals(queued, captured.receipt.pending)
            assertIs<DirectPendingCaptureResult.AlreadyCaptured>(
                store.captureDirectPendingCompletion(lease, entity(id = row.id), queued.id, request, reason),
            )
            val completion = UUID.randomUUID()
            val caller = Job()
            assertFailsWith<CancellationException> {
                withContext(caller) {
                    assertIs<DirectPendingCompletionResult.Applied>(
                        store.completeDirectPendingWithoutDispatch(captured.completion, completion),
                    )
                    caller.cancel()
                    ensureActive()
                }
            }
            assertEquals(row, store.fetchMessage(entity(id = row.id)))
            assertTrue(store.fetchPendingSendsForMessage(entity(id = row.id)).isEmpty())
            val receipt = assertIs<DirectPendingCompletionObservation.Found>(
                store.inspectDirectPendingCompletion(lease, request),
            ).receipt
            assertEquals(completion, receipt.completedRequestId)
            assertIs<DirectAckObservation.Rejected>(store.inspectDirectAckAttempt(lease, request))
            store.upsertPendingSend(queued)
            assertIs<DirectPendingCompletionResult.AlreadyApplied>(
                store.completeDirectPendingWithoutDispatch(captured.completion, completion),
            )
            assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectPendingCompletionResult.Rejected>(
                store.completeDirectPendingWithoutDispatch(captured.completion, UUID.randomUUID()),
            ).reason)
            assertEquals(listOf(queued), store.fetchPendingSendsForMessage(entity(id = row.id)))
        }
        val row = message()
        val queued = pending(messageID = row.id)
        store.saveMessage(row)
        store.upsertPendingSend(queued)
        for (reason in listOf(
            DirectPendingCompletionReason.AlreadyDeliveredSend,
            DirectPendingCompletionReason.ZeroAttempts(1),
            DirectPendingCompletionReason.ZeroAttempts(-1),
        )) assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<DirectPendingCaptureResult.Rejected>(
            store.captureDirectPendingCompletion(lease, entity(id = row.id), queued.id, UUID.randomUUID(), reason),
        ).reason)
        val delivered = row.copy(status = MessageStatus.DELIVERED)
        store.saveMessage(delivered)
        store.upsertPendingSend(queued.copy(isResend = true))
        assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<DirectPendingCaptureResult.Rejected>(
            store.captureDirectPendingCompletion(lease, entity(id = row.id), queued.id, UUID.randomUUID(),
                DirectPendingCompletionReason.AlreadyDeliveredSend),
        ).reason)
    }

    @Test fun deliveredQueuedRecoveryRequiresTheExactRecordedDeliveryAndReplaysOnlyItsDeletion() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val queued = pending(messageID = row.id)
        store.upsertPendingSend(queued)
        val claim = begin(row, pendingId = queued.id, lease = lease)
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(claim.attempt, claim.wire, 123u))
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(claim.attempt, 123u, 42u))
        val delivered = assertNotNull(store.fetchMessage(entity(id = row.id)))
        val next = activate(session = token.copy(generation = Generation(2)))
        assertEquals(DirectAckRejection.UNRESOLVED_OUTCOME, assertIs<DirectQueueCaptureResult.Rejected>(
            store.captureDirectQueueSelection(next, entity(id = row.id), queued.id),
        ).reason)

        val captureID = UUID.randomUUID()
        val reason = DirectPendingCompletionReason.DeliveredQueuedRecovery
        val captured = assertIs<DirectPendingCaptureResult.Captured>(
            store.captureDirectPendingCompletion(next, entity(id = row.id), queued.id, captureID, reason),
        )
        assertEquals(delivered, captured.receipt.message)
        assertIs<DirectPendingCaptureResult.AlreadyCaptured>(
            store.captureDirectPendingCompletion(next, entity(id = row.id), queued.id, captureID, reason),
        )
        val completionID = UUID.randomUUID()
        assertIs<DirectPendingCompletionResult.Applied>(
            store.completeDirectPendingWithoutDispatch(captured.completion, completionID),
        )
        assertTrue(store.fetchPendingSendsForMessage(entity(id = row.id)).isEmpty())
        assertEquals(delivered, store.fetchMessage(entity(id = row.id)))
        assertEquals(0, db.directAcks().unresolvedQueuedClaims(RADIO_A.value, row.id, queued.id))
        store.upsertPendingSend(queued)
        assertIs<DirectPendingCompletionResult.AlreadyApplied>(
            store.completeDirectPendingWithoutDispatch(captured.completion, completionID),
        )
        assertEquals(listOf(queued), store.fetchPendingSendsForMessage(entity(id = row.id)))
        assertIs<DirectQueueCaptureResult.Captured>(
            store.captureDirectQueueSelection(next, entity(id = row.id), queued.id),
        )
        assertIs<DirectAckObservation.Rejected>(store.inspectDirectAckAttempt(next, captureID))
    }

    @Test fun deliveredQueuedRecoverySurvivesRestoreOnlyForUnchangedMessageAndPendingIncarnations() = runTest {
        val lease = activate()
        for (mutation in listOf("none", "message-before", "pending-before", "message-during")) {
            val row = message(status = MessageStatus.PENDING)
            val queued = pending(messageID = row.id)
            store.upsertPendingSend(queued)
            val claim = begin(row, pendingId = queued.id, lease = lease)
            store.recordDirectSendAccepted(claim.attempt, claim.wire, 123u)
            store.settleDirectAck(claim.attempt, 123u, 42u)
            val delivered = assertNotNull(store.fetchMessage(entity(id = row.id)))
            if (mutation == "message-before") store.saveMessage(delivered)
            if (mutation == "pending-before") store.upsertPendingSend(queued)
            store.withBackupRestore { database, _ ->
                if (mutation == "message-during") {
                    database.messages().setStatus(RADIO_A.value, row.id, MessageStatus.DELIVERED.rawValue)
                }
                ImportResult()
            }
            val result = store.captureDirectPendingCompletion(
                lease, entity(id = row.id), queued.id, UUID.randomUUID(),
                DirectPendingCompletionReason.DeliveredQueuedRecovery,
            )
            if (mutation == "none") {
                val captured = assertIs<DirectPendingCaptureResult.Captured>(result)
                assertIs<DirectPendingCompletionResult.Applied>(
                    store.completeDirectPendingWithoutDispatch(captured.completion, UUID.randomUUID()),
                )
                assertTrue(store.fetchPendingSendsForMessage(entity(id = row.id)).isEmpty())
            } else {
                assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<DirectPendingCaptureResult.Rejected>(result).reason)
                assertEquals(listOf(queued), store.fetchPendingSendsForMessage(entity(id = row.id)))
            }
            assertEquals(delivered, store.fetchMessage(entity(id = row.id)))
        }
    }

    @Test fun deliveredQueuedRecoveryRejectsMissingProofReplacementAndCompetingIntent() = runTest {
        val lease = activate()
        val unproven = message(status = MessageStatus.DELIVERED)
        val unprovenPending = pending(messageID = unproven.id)
        store.saveMessage(unproven)
        store.upsertPendingSend(unprovenPending)
        val reason = DirectPendingCompletionReason.DeliveredQueuedRecovery
        assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<DirectPendingCaptureResult.Rejected>(
            store.captureDirectPendingCompletion(lease, entity(id = unproven.id), unprovenPending.id, UUID.randomUUID(), reason),
        ).reason)

        val replaced = message(status = MessageStatus.PENDING)
        val replacedPending = pending(messageID = replaced.id)
        store.upsertPendingSend(replacedPending)
        val replacedClaim = begin(replaced, pendingId = replacedPending.id, lease = lease)
        store.recordDirectSendAccepted(replacedClaim.attempt, replacedClaim.wire, 123u)
        store.settleDirectAck(replacedClaim.attempt, 123u, 42u)
        store.upsertPendingSend(replacedPending)
        assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<DirectPendingCaptureResult.Rejected>(
            store.captureDirectPendingCompletion(lease, entity(id = replaced.id), replacedPending.id, UUID.randomUUID(), reason),
        ).reason)
        assertEquals(listOf(replacedPending), store.fetchPendingSendsForMessage(entity(id = replaced.id)))

        val row = message(status = MessageStatus.PENDING)
        val queued = pending(messageID = row.id)
        store.upsertPendingSend(queued)
        val claim = begin(row, pendingId = queued.id, lease = lease)
        store.recordDirectSendAccepted(claim.attempt, claim.wire, 123u)
        store.settleDirectAck(claim.attempt, 123u, 42u)
        val captured = assertIs<DirectPendingCaptureResult.Captured>(
            store.captureDirectPendingCompletion(lease, entity(id = row.id), queued.id, UUID.randomUUID(), reason),
        )
        val competingID = UUID.randomUUID()
        assertIs<DirectAckIntentResult.Captured>(
            store.captureDirectAckIntent(lease, entity(id = row.id), competingID, DirectAckMode.RESEND, queued.id),
        )
        val completionID = UUID.randomUUID()
        assertEquals(DirectAckRejection.NOT_ELIGIBLE, assertIs<DirectPendingCompletionResult.Rejected>(
            store.completeDirectPendingWithoutDispatch(captured.completion, completionID),
        ).reason)
        assertEquals(listOf(queued), store.fetchPendingSendsForMessage(entity(id = row.id)))
        assertEquals(DirectAckIntentRetirement.Retired, store.retireDirectAckIntent(lease, competingID))
        assertIs<DirectPendingCompletionResult.Applied>(
            store.completeDirectPendingWithoutDispatch(captured.completion, completionID),
        )
    }

    @Test fun deliveredQueuedRecoveryCannotDeleteAReplacementInsertedAfterCapture() = runTest {
        val lease = activate()
        val row = message(status = MessageStatus.PENDING)
        val queued = pending(messageID = row.id)
        store.upsertPendingSend(queued)
        val claim = begin(row, pendingId = queued.id, lease = lease)
        store.recordDirectSendAccepted(claim.attempt, claim.wire, 123u)
        store.settleDirectAck(claim.attempt, 123u, 42u)
        val delivered = assertNotNull(store.fetchMessage(entity(id = row.id)))
        val captured = assertIs<DirectPendingCaptureResult.Captured>(
            store.captureDirectPendingCompletion(
                lease, entity(id = row.id), queued.id, UUID.randomUUID(),
                DirectPendingCompletionReason.DeliveredQueuedRecovery,
            ),
        )
        store.upsertPendingSend(queued)
        assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectPendingCompletionResult.Rejected>(
            store.completeDirectPendingWithoutDispatch(captured.completion, UUID.randomUUID()),
        ).reason)
        assertEquals(listOf(queued), store.fetchPendingSendsForMessage(entity(id = row.id)))
        assertEquals(delivered, store.fetchMessage(entity(id = row.id)))
    }

    @Test fun noDispatchCaptureCannotDeleteAfterRawDaoMessagePendingRestoreOrGenerationMutation() = runTest {
        var lease = activate()
        for (mutation in listOf<suspend (MessageDTO, PendingSendDTO) -> Unit>(
            { row, _ -> db.messages().setStatus(RADIO_A.value, row.id, row.status.rawValue) },
            { _, queued -> store.upsertPendingSend(queued) },
            { _, _ -> db.openHelper.writableDatabase.execSQL("UPDATE pending_sends SET messageText = messageText") },
            { _, _ -> db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO pending_sends SELECT * FROM pending_sends") },
            { _, _ -> store.withBackupRestore { _, _ -> ImportResult() } },
            { _, _ -> lease = activate(session = token.copy(generation = Generation(2))) },
        )) {
            val row = message()
            val queued = pending(messageID = row.id)
            store.saveMessage(row)
            store.upsertPendingSend(queued)
            val claim = assertIs<DirectPendingCaptureResult.Captured>(
                store.captureDirectPendingCompletion(lease, entity(id = row.id), queued.id, UUID.randomUUID(),
                    DirectPendingCompletionReason.ZeroAttempts(0)),
            )
            mutation(row, queued)
            assertIs<DirectPendingCompletionResult.Rejected>(
                store.completeDirectPendingWithoutDispatch(claim.completion, UUID.randomUUID()),
            )
            assertEquals(listOf(queued), store.fetchPendingSendsForMessage(entity(id = row.id)))
        }
    }

    @Test fun noDispatchCompletionInvalidatesExistingAckAndCannotHideNestedPendingMutation() = runTest {
        val row = message()
        val queued = pending(messageID = row.id)
        store.upsertPendingSend(queued)
        val claim = begin(row, pendingId = queued.id)
        val capture = assertIs<DirectPendingCaptureResult.Captured>(
            store.captureDirectPendingCompletion(claim.lease, entity(id = row.id), queued.id, UUID.randomUUID(),
                DirectPendingCompletionReason.ZeroAttempts(0)),
        )
        store.completeDirectPendingWithoutDispatch(capture.completion, UUID.randomUUID())
        rejected(store.settleDirectAck(claim.attempt, 123u, 1u))
        store.upsertPendingSend(queued)
        store.upsertPendingSend(pending(messageID = row.id, sequence = 1))
        val before = store.fetchPendingSendsForMessage(entity(id = row.id))
        val second = assertIs<DirectPendingCaptureResult.Captured>(
            store.captureDirectPendingCompletion(claim.lease, entity(id = row.id), queued.id, UUID.randomUUID(),
                DirectPendingCompletionReason.ZeroAttempts(0)),
        )
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER independently_repeat_pending AFTER DELETE ON pending_sends BEGIN " +
                "UPDATE pending_sends SET messageText = messageText WHERE messageID = OLD.messageID; END",
        )
        assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException> {
            store.completeDirectPendingWithoutDispatch(second.completion, UUID.randomUUID())
        }.error)
        assertEquals(before, store.fetchPendingSendsForMessage(entity(id = row.id)))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER independently_repeat_pending")
    }

    @Test fun fileReopenOtherHandleAndProcessStoreCloseCannotAdoptOldHandles() = runTest {
        val name = "direct-ack-handles-${UUID.randomUUID()}.db"
        fun file(): MeshCoreDatabase = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).addCallback(DirectAckStorage.callback).build()
        try {
            val first = file()
            val original = newStore(first)
            val lease = activate(original)
            val claim = begin(lease = lease, target = original)
            val second = file()
            try {
                second.messages().setStatus(RADIO_A.value, claim.row.id, 4)
                rejected(original.settleDirectAck(claim.attempt, 123u, 123u))
                val replacement = newStore(second)
                rejected(replacement.recordDirectFailure(claim.attempt), DirectAckRejection.INVALID_HANDLE)
                replacement.close()
            } finally { second.close() }
            original.close()
            first.close()
            val reopened = file()
            try {
                val replacement = newStore(reopened)
                rejected(replacement.settleDirectAck(claim.attempt, 123u, 1u), DirectAckRejection.INVALID_HANDLE)
                val row = assertNotNull(replacement.fetchMessage(entity(id = claim.row.id)))
                assertEquals(MessageStatus.FAILED, row.status)
                assertNull(row.roundTripTime)
                replacement.close()
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun issuedIntentCannotCrossOtherHandleMutationOrFileReopen() = runTest {
        val name = "direct-intent-handles-${UUID.randomUUID()}.db"
        fun file(): MeshCoreDatabase = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).addCallback(DirectAckStorage.callback).build()
        try {
            val first = file()
            val original = newStore(first)
            try {
                val lease = activate(original)
                val row = message()
                original.saveMessage(row)
                val queued = pending(messageID = row.id)
                original.upsertPendingSend(queued)
                for (pendingMutation in listOf(false, true)) {
                    val issued = assertIs<DirectAckIntentResult.Captured>(
                        original.captureDirectAckIntent(lease, entity(id = row.id), UUID.randomUUID(),
                            DirectAckMode.SEND, queued.id),
                    )
                    val second = file()
                    try {
                        if (pendingMutation) second.pendingSends().upsert(queued.toEntity())
                        else second.messages().upsert(row.toEntity())
                    } finally { second.close() }
                    assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectAckBeginResult.Rejected>(
                        original.beginDirectAckAttempt(issued.intent, UUID.randomUUID(), 123u),
                    ).reason)
                    assertEquals(row, original.fetchMessage(entity(id = row.id)))
                }
                val issued = assertIs<DirectAckIntentResult.Captured>(
                    original.captureDirectAckIntent(lease, entity(id = row.id), UUID.randomUUID(),
                        DirectAckMode.SEND, queued.id),
                )
                original.close()
                first.close()
                val reopened = file()
                val replacement = newStore(reopened)
                try {
                    assertEquals(DirectAckRejection.INVALID_HANDLE, assertIs<DirectAckBeginResult.Rejected>(
                        replacement.beginDirectAckAttempt(issued.intent, UUID.randomUUID(), 123u),
                    ).reason)
                    assertEquals(row, replacement.fetchMessage(entity(id = row.id)))
                    assertEquals(listOf(queued), replacement.fetchPendingSendsForMessage(entity(id = row.id)))
                    assertPayloadColumnsNullIn(reopened, "direct_ack_intents", row.id, "message_", "pending_")
                } finally { replacement.close(); reopened.close() }
            } finally { original.close(); first.close() }
        } finally { context.deleteDatabase(name) }
    }
}
