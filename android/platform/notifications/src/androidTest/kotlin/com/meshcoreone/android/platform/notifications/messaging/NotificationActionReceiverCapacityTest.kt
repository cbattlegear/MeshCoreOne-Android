// AndroidOnly: WP-215 Real receiver/RemoteInput controls for pre-admission overflow custody.
package com.meshcoreone.android.platform.notifications.messaging

import android.app.RemoteInput
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.model.RadioId
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationActionReceiverCapacityTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var context: Context
    private val startedActivities = mutableListOf<Intent>()
    private val radio = RadioId(UUID.fromString("10000000-0000-0000-0000-000000000001"))

    @Before
    fun prepare() {
        context = recoveryContext(File(folder.root, "private").apply { mkdir() })
        NotificationActionDispatcher.resetForTesting()
        NotificationActionRecovery.acknowledgeFailure(NotificationActionRecovery.load(context).version)
    }

    @After
    fun reset() {
        NotificationActionDispatcher.resetForTesting()
        NotificationActionRecovery.load(context).items.filter { !it.persistent }.forEach {
            assertNull(NotificationActionRecovery.dispose(context, it))
        }
        NotificationActionRecovery.acknowledgeFailure(NotificationActionRecovery.load(context).version)
    }

    @Test
    fun queueFullReplyRetainsOriginalRemoteInputAndDoesNotReorderAcceptedActions() = runTest {
        val accepted = fillQueue()
        val original = " \tkeep this exact reply\n "
        NotificationActionReceiver().onReceive(context, intent(999, NotificationAction.REPLY, original))

        val saved = entries().single()
        assertEquals(response(999, NotificationAction.REPLY, original), saved.response)
        val delivered = mutableListOf<NotificationResponse>()
        NotificationActionDispatcher.install(this) { delivered += it; true }
        assertTrue(NotificationActionDispatcher.drainOnce())
        assertEquals(accepted, delivered)
        assertEquals(saved.id, entries().single().id)
    }

    @Test
    fun queueFullMarkReadRetainsExactOriginalMessageWithoutPerformingIt() = runTest {
        val accepted = fillQueue()
        NotificationActionReceiver().onReceive(context, intent(700, NotificationAction.MARK_READ))
        val saved = entries().single()
        assertEquals(response(700, NotificationAction.MARK_READ), saved.response)
        assertNull(saved.response.userText)
        val delivered = mutableListOf<NotificationResponse>()
        NotificationActionDispatcher.install(this) { delivered += it; true }
        assertTrue(NotificationActionDispatcher.drainOnce())
        assertEquals(accepted, delivered)
    }

    @Test
    fun acceptedReplyKeepsExistingTrimAndNeverCreatesARecoveryDraft() = runTest {
        NotificationActionReceiver().onReceive(context, intent(5, NotificationAction.REPLY, " \taccepted\n "))
        assertTrue(entries().isEmpty())
        assertFalse(File(context.noBackupFilesDir, "notification-action-recovery.sqlite").exists())
        val delivered = mutableListOf<NotificationResponse>()
        NotificationActionDispatcher.install(this) { delivered += it; true }
        assertTrue(NotificationActionDispatcher.drainOnce())
        assertEquals(listOf(response(5, NotificationAction.REPLY, "accepted")), delivered)
    }

    @Test
    fun acceptedMarkReadEntersOnlyTheDispatcher() = runTest {
        NotificationActionReceiver().onReceive(context, intent(6, NotificationAction.MARK_READ))
        assertTrue(entries().isEmpty())
        val delivered = mutableListOf<NotificationResponse>()
        NotificationActionDispatcher.install(this) { delivered += it; true }
        assertTrue(NotificationActionDispatcher.drainOnce())
        assertEquals(listOf(response(6, NotificationAction.MARK_READ)), delivered)
    }

    @Test
    fun blankReplyDoesNotEnterEitherQueue() = runTest {
        NotificationActionReceiver().onReceive(context, intent(5, NotificationAction.REPLY, " \t\n "))
        assertTrue(entries().isEmpty())
        assertTrue(NotificationActionDispatcher.drainOnce())
    }

    @Test
    fun committedRecoveryCanBeReadByAFreshStoreWithoutNormalizingText() {
        val text = " \n\u0000\uD83D\uDE80\uD800original\uDC00\t "
        val original = response(9, NotificationAction.REPLY, text)
        val stored = NotificationActionRecoveryStore(context).retain(original) as NotificationRecoveryWrite.Stored
        val reopened = entries().single()
        assertEquals(stored.entry.id, reopened.id)
        assertEquals(original, reopened.response)
        assertEquals(text.toCharArray().toList(), reopened.response.userText!!.toCharArray().toList())
    }

    @Test
    fun originalDirectAndRoomTargetMetadataSurvivePrivateRecoveryWithoutRouting() = runTest {
        val entity = EntityKey(radio, UUID.fromString("20000000-0000-0000-0000-000000000002"))
        val originals = listOf(
            NotificationResponse(
                NotificationPayload.DirectMessage(entity, UUID(0, 100)),
                NotificationAction.REPLY, " \tprivate direct reply\n ",
            ),
            NotificationResponse(
                NotificationPayload.RoomMessage(" Room;#% \uD83D\uDE80\u0000 ", entity, UUID(0, 101)),
                NotificationAction.REPLY, " \tprivate room reply\n ",
            ),
        )
        originals.forEach {
            assertEquals(NotificationRecoveryRetention.DURABLE, NotificationActionRecovery.retain(context, it))
        }
        assertEquals(originals, entries().map { it.response })
        assertTrue(NotificationActionDispatcher.drainOnce())
    }

    @Test
    fun durableCountLimitNeverEvictsAnOlderDraft() {
        val store = NotificationActionRecoveryStore(context)
        val originals = (1..32).map { response(it, NotificationAction.REPLY, "original-$it") }
        originals.forEach { assertTrue(store.retain(it) is NotificationRecoveryWrite.Stored) }
        val rejected = store.retain(response(33, NotificationAction.REPLY, "not stored")) as NotificationRecoveryWrite.Failed
        assertEquals(NotificationRecoveryFailure.CAPACITY, rejected.reason)
        assertEquals(originals, entries().map { it.response })
    }

    @Test
    fun durableByteLimitRejectsBeforeCountLimitAndKeepsEveryExistingDraft() {
        val store = NotificationActionRecoveryStore(context)
        val text = "x".repeat(64 * 1024)
        (1..15).forEach { assertTrue(store.retain(response(it, NotificationAction.REPLY, text)) is NotificationRecoveryWrite.Stored) }
        val rejected = store.retain(response(16, NotificationAction.REPLY, text)) as NotificationRecoveryWrite.Failed
        assertEquals(NotificationRecoveryFailure.CAPACITY, rejected.reason)
        assertEquals((1..15).map { response(it, NotificationAction.REPLY, text) }, entries().map { it.response })
    }

    @Test
    fun multibyteOverLimitReplyIsNeverReportedRetainedOrTruncated() {
        val text = "\u00e9".repeat(64 * 1024)
        assertEquals(
            NotificationRecoveryRetention.NOT_RETAINED,
            NotificationActionRecovery.retain(context, response(1, NotificationAction.REPLY, text)),
        )
        val snapshot = NotificationActionRecovery.load(context)
        assertTrue(snapshot.items.isEmpty())
        assertTrue(snapshot.notRetained)
    }

    @Test
    fun staleSnapshotCannotAcknowledgeANewerLossOrForegroundFailure() {
        val displayed = NotificationActionRecovery.load(context)
        assertEquals(
            NotificationRecoveryRetention.NOT_RETAINED,
            NotificationActionRecovery.retain(context, response(91, NotificationAction.REPLY, "x".repeat(128 * 1024))),
        )
        NotificationActionRecovery.requireManualOpen()
        val unseen = NotificationActionRecovery.load(context)
        assertTrue(unseen.version > displayed.version)
        assertNull(NotificationActionRecovery.acknowledgeFailure(displayed.version))
        val unchanged = NotificationActionRecovery.load(context)
        assertEquals(unseen.version, unchanged.version)
        assertTrue(unchanged.notRetained)
        assertTrue(unchanged.manualOpenRequired)

        val acknowledged = NotificationActionRecovery.acknowledgeFailure(unchanged.version)
        val afterAcknowledgment = NotificationActionRecovery.load(context)
        assertEquals(acknowledged, afterAcknowledgment.version)
        assertFalse(afterAcknowledgment.notRetained)
        assertFalse(afterAcknowledgment.manualOpenRequired)
    }

    @Test
    fun staleSnapshotCannotHideANewerDurablyRetainedResponse() {
        val displayed = NotificationActionRecovery.load(context)
        val original = response(92, NotificationAction.REPLY, "new response")
        assertEquals(NotificationRecoveryRetention.DURABLE, NotificationActionRecovery.retain(context, original))
        val unseen = NotificationActionRecovery.load(context)
        assertNull(NotificationActionRecovery.acknowledgeFailure(displayed.version))
        assertEquals(unseen.version, NotificationActionRecovery.changes.value)
        assertEquals(original, NotificationActionRecovery.load(context).items.single().entry.response)
    }

    @Test
    fun durableFullUsesOnlyBoundedProcessMemoryAndReportsActualExhaustion() {
        (1..32).forEach {
            assertEquals(
                NotificationRecoveryRetention.DURABLE,
                NotificationActionRecovery.retain(context, response(it, NotificationAction.REPLY, "saved-$it")),
            )
        }
        (33..64).forEach {
            assertEquals(
                NotificationRecoveryRetention.PROCESS_ONLY,
                NotificationActionRecovery.retain(context, response(it, NotificationAction.REPLY, "memory-$it")),
            )
        }
        assertEquals(
            NotificationRecoveryRetention.NOT_RETAINED,
            NotificationActionRecovery.retain(context, response(65, NotificationAction.REPLY, "not retained")),
        )
        val snapshot = NotificationActionRecovery.load(context)
        assertEquals(64, snapshot.items.size)
        assertEquals((1..64).map { UUID(0, it.toLong()) }, snapshot.items.map { (it.entry.response.payload as NotificationPayload.ChannelMessage).messageID })
        assertTrue(snapshot.notRetained)
    }

    @Test
    fun storageFailureRetainsExactTextOnlyInProcessMemory() {
        val blocked = File(folder.root, "not-a-directory").apply { writeText("occupied") }
        val failing = recoveryContext(blocked)
        fillQueue()
        val original = response(81, NotificationAction.REPLY, " \tprivate text\n ")
        NotificationActionReceiver().onReceive(failing, intent(81, NotificationAction.REPLY, original.userText))
        val snapshot = NotificationActionRecovery.load(failing)
        assertEquals(NotificationRecoveryFailure.STORAGE_UNAVAILABLE, snapshot.storageFailure)
        assertEquals(original, snapshot.items.single().entry.response)
        assertFalse(snapshot.items.single().persistent)
        assertFalse(snapshot.notRetained)
    }

    @Test
    fun deniedPrivateDirectoryIsATypedFailureAndStillPreservesExactProcessCustody() {
        val denied = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = throw SecurityException("synthetic private-directory denial")
        }
        val original = response(82, NotificationAction.REPLY, " \tkeep despite storage denial\n ")
        val store = NotificationActionRecoveryStore(denied)
        assertEquals(
            NotificationRecoveryFailure.STORAGE_UNAVAILABLE,
            (store.retain(original) as NotificationRecoveryWrite.Failed).reason,
        )
        assertEquals(NotificationRecoveryRetention.PROCESS_ONLY, NotificationActionRecovery.retain(denied, original))
        val snapshot = NotificationActionRecovery.load(denied)
        assertEquals(NotificationRecoveryFailure.STORAGE_UNAVAILABLE, snapshot.storageFailure)
        assertEquals(original, snapshot.items.single().entry.response)
        assertFalse(snapshot.items.single().persistent)
    }

    @Test
    fun actionBroadcastRequiresUserOpeningWithoutAttemptingARecoveryTrampoline() {
        fillQueue()
        NotificationActionReceiver().onReceive(context, intent(90, NotificationAction.REPLY, "original"))
        val snapshot = NotificationActionRecovery.load(context)
        assertTrue(snapshot.manualOpenRequired)
        assertTrue(snapshot.items.single().persistent)
        assertEquals("original", snapshot.items.single().entry.response.userText)
        assertTrue(startedActivities.isEmpty())
    }

    @Test
    fun corruptExistingVersionIsNotRecreatedOrReportedAsEmpty() {
        val file = File(context.noBackupFilesDir, "notification-action-recovery.sqlite")
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.execSQL("CREATE TABLE preserved (value INTEGER NOT NULL)")
            it.execSQL("INSERT INTO preserved VALUES (17)")
            it.version = 99
        }
        val store = NotificationActionRecoveryStore(context)
        assertEquals(NotificationRecoveryFailure.INVALID_RECORD, (store.read() as NotificationRecoveryRead.Failed).reason)
        assertEquals(
            NotificationRecoveryFailure.INVALID_RECORD,
            (store.retain(response(1, NotificationAction.REPLY, "original")) as NotificationRecoveryWrite.Failed).reason,
        )
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(99, it.version)
            it.rawQuery("SELECT value FROM preserved", null).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(17, row.getInt(0))
            }
        }
    }

    @Test
    fun explicitDispositionDeletesOnlyTheChosenDraftAndAStaleIdFails() {
        val store = NotificationActionRecoveryStore(context)
        val first = (store.retain(response(1, NotificationAction.REPLY, "first")) as NotificationRecoveryWrite.Stored).entry
        val second = (store.retain(response(2, NotificationAction.MARK_READ)) as NotificationRecoveryWrite.Stored).entry
        assertNotEquals(first.id, second.id)
        assertEquals(listOf(first.id, second.id), entries().map { it.id })
        assertNull(store.dispose(first.id))
        assertEquals(listOf(second.id), entries().map { it.id })
        assertEquals(NotificationRecoveryFailure.INVALID_RECORD, store.dispose(first.id))
        assertEquals(listOf(second.id), entries().map { it.id })
    }

    @Test
    fun failedDispositionDoesNotRemoveTheStoredOriginal() {
        val store = NotificationActionRecoveryStore(context)
        val entry = (store.retain(response(4, NotificationAction.REPLY, "keep")) as NotificationRecoveryWrite.Stored).entry
        val file = File(context.noBackupFilesDir, "notification-action-recovery.sqlite")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 99 }
        assertEquals(NotificationRecoveryFailure.INVALID_RECORD, store.dispose(entry.id))
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
            it.rawQuery("SELECT id FROM drafts", null).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(entry.id.toString(), row.getString(0))
                assertFalse(row.moveToNext())
            }
        }
    }

    @Test
    fun malformedExistingRecordPreventsNewDurableSuccessAndUsesExplicitMemoryFallback() {
        val store = NotificationActionRecoveryStore(context)
        val first = (store.retain(response(1, NotificationAction.MARK_READ)) as NotificationRecoveryWrite.Stored).entry
        SQLiteDatabase.openDatabase(
            File(context.noBackupFilesDir, "notification-action-recovery.sqlite").path,
            null, SQLiteDatabase.OPEN_READWRITE,
        ).use { it.execSQL("UPDATE drafts SET action = 'UNKNOWN'") }
        val original = response(2, NotificationAction.REPLY, " \tkeep exactly ")
        assertEquals(NotificationRecoveryRetention.PROCESS_ONLY, NotificationActionRecovery.retain(context, original))
        val snapshot = NotificationActionRecovery.load(context)
        assertEquals(NotificationRecoveryFailure.INVALID_RECORD, snapshot.storageFailure)
        assertEquals(original, snapshot.items.single().entry.response)
        assertFalse(snapshot.items.single().persistent)
        assertEquals(
            NotificationRecoveryFailure.INVALID_RECORD,
            (store.read() as NotificationRecoveryRead.Failed).reason,
        )
        assertNotEquals(first.id, snapshot.items.single().entry.id)
    }

    @Test
    fun duplicateIdsAreCorruptAndDispositionRollsBackInsteadOfDeletingBothRows() {
        val store = NotificationActionRecoveryStore(context)
        val entry = (store.retain(response(1, NotificationAction.REPLY, "original")) as NotificationRecoveryWrite.Stored).entry
        val file = File(context.noBackupFilesDir, "notification-action-recovery.sqlite")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("ALTER TABLE drafts RENAME TO original_drafts")
            it.execSQL("CREATE TABLE drafts AS SELECT * FROM original_drafts")
            it.execSQL("INSERT INTO drafts SELECT * FROM original_drafts")
        }
        assertEquals(
            NotificationRecoveryFailure.INVALID_RECORD,
            (store.read() as NotificationRecoveryRead.Failed).reason,
        )
        assertEquals(NotificationRecoveryFailure.INVALID_RECORD, store.dispose(entry.id))
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
            it.rawQuery("SELECT COUNT(*) FROM drafts", null).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(2, row.getInt(0))
            }
        }
    }

    @Test
    fun cancellationOfAnAcceptedDrainNeverDisposesTheRejectedDraft() = runTest {
        fillQueue()
        NotificationActionReceiver().onReceive(context, intent(800, NotificationAction.REPLY, "recover me"))
        val savedId = entries().single().id
        val owner = Job()
        val scope = CoroutineScope(coroutineContext + owner)
        NotificationActionDispatcher.install(scope) { awaitCancellation() }
        runCurrent()
        owner.cancelAndJoin()
        assertEquals(savedId, entries().single().id)
        val delivered = mutableListOf<NotificationResponse>()
        NotificationActionDispatcher.install(this) { delivered += it; true }
        assertTrue(NotificationActionDispatcher.drainOnce())
        assertEquals((1..32).map { response(it, NotificationAction.MARK_READ) }, delivered)
        assertEquals(savedId, entries().single().id)
    }

    @Test
    fun cancellingARecoveryReaderDoesNotCountAsDisposition() = runTest {
        val store = NotificationActionRecoveryStore(context)
        val entry = (store.retain(response(12, NotificationAction.REPLY, "keep")) as NotificationRecoveryWrite.Stored).entry
        val reader = launch {
            assertEquals(entry.id, (store.read() as NotificationRecoveryRead.Ready).entries.single().id)
            awaitCancellation()
        }
        runCurrent()
        reader.cancelAndJoin()
        assertEquals(entry.id, entries().single().id)
    }

    private fun fillQueue(): List<NotificationResponse> = (1..32).map {
        response(it, NotificationAction.MARK_READ).also { response ->
            assertEquals(EnqueueResult.DELIVERED_OR_QUEUED, NotificationActionDispatcher.enqueue(response))
        }
    }

    private fun entries(): List<RejectedNotificationAction> =
        (NotificationActionRecoveryStore(context).read() as NotificationRecoveryRead.Ready).entries

    private fun response(id: Int, action: NotificationAction, text: String? = null) =
        NotificationResponse(NotificationPayload.ChannelMessage(radio, 7u, UUID(0, id.toLong())), action, text)

    private fun intent(id: Int, action: NotificationAction, text: String? = null): Intent {
        val intent = NotificationPayloadCodec.write(Intent(), response(id, action, text).payload)
            .putExtra(NotificationActionReceiver.EXTRA_ACTION, action.rawValue)
        if (text != null) {
            RemoteInput.addResultsToIntent(
                arrayOf(RemoteInput.Builder(NotificationActionReceiver.REMOTE_INPUT_KEY).build()),
                intent,
                Bundle().apply { putCharSequence(NotificationActionReceiver.REMOTE_INPUT_KEY, text) },
            )
        }
        return intent
    }

    private fun recoveryContext(directory: File): Context =
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir(): File = directory
            override fun startActivity(intent: Intent) {
                startedActivities += intent
            }
        }
}
