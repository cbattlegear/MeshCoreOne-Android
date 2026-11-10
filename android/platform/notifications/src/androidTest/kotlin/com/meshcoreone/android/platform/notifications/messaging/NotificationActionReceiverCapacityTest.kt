// AndroidOnly: WP-215 Real receiver/RemoteInput controls for pre-admission overflow custody.
package com.meshcoreone.android.platform.notifications.messaging

import android.app.RemoteInput
import android.content.Context
import android.content.ContextWrapper
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.model.RadioId
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
        receive(context, intent(999, NotificationAction.REPLY, original))

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
        receive(context, intent(700, NotificationAction.MARK_READ))
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
        receive(context, intent(5, NotificationAction.REPLY, " \taccepted\n "))
        assertTrue(entries().isEmpty())
        assertFalse(File(context.noBackupFilesDir, "notification-action-recovery.sqlite").exists())
        val delivered = mutableListOf<NotificationResponse>()
        NotificationActionDispatcher.install(this) { delivered += it; true }
        assertTrue(NotificationActionDispatcher.drainOnce())
        assertEquals(listOf(response(5, NotificationAction.REPLY, "accepted")), delivered)
    }

    @Test
    fun acceptedMarkReadEntersOnlyTheDispatcher() = runTest {
        receive(context, intent(6, NotificationAction.MARK_READ))
        assertTrue(entries().isEmpty())
        val delivered = mutableListOf<NotificationResponse>()
        NotificationActionDispatcher.install(this) { delivered += it; true }
        assertTrue(NotificationActionDispatcher.drainOnce())
        assertEquals(listOf(response(6, NotificationAction.MARK_READ)), delivered)
    }

    @Test
    fun blankReplyDoesNotEnterEitherQueue() = runTest {
        receive(context, intent(5, NotificationAction.REPLY, " \t\n "))
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
        receive(failing, intent(81, NotificationAction.REPLY, original.userText))
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
        receive(context, intent(90, NotificationAction.REPLY, "original"))
        val snapshot = NotificationActionRecovery.load(context)
        assertTrue(snapshot.manualOpenRequired)
        assertTrue(snapshot.items.single().persistent)
        assertEquals("original", snapshot.items.single().entry.response.userText)
        assertTrue(startedActivities.isEmpty())
    }

    @Test
    fun rejectedMarkReadAlsoRequiresManualOpeningWithoutAnActivityTrampoline() {
        fillQueue()
        receive(context, intent(94, NotificationAction.MARK_READ))
        val snapshot = NotificationActionRecovery.load(context)
        assertTrue(snapshot.manualOpenRequired)
        assertEquals(response(94, NotificationAction.MARK_READ), snapshot.items.single().entry.response)
        assertTrue(snapshot.items.single().persistent)
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
    fun corruptDatabaseBytesAreNotDeletedOrRecreatedByTheDefaultSqliteErrorHandler() {
        val file = File(context.noBackupFilesDir, "notification-action-recovery.sqlite")
        val original = ByteArray(4096) { index -> (index % 127 + 1).toByte() }
        file.writeBytes(original)
        val store = NotificationActionRecoveryStore(context)
        assertTrue(store.read() is NotificationRecoveryRead.Failed)
        assertTrue(file.exists())
        org.junit.Assert.assertArrayEquals(original, file.readBytes())
        assertEquals(
            NotificationRecoveryRetention.PROCESS_ONLY,
            NotificationActionRecovery.retain(context, response(93, NotificationAction.REPLY, "keep outside corrupt store")),
        )
        org.junit.Assert.assertArrayEquals(original, file.readBytes())
        val snapshot = NotificationActionRecovery.load(context)
        assertEquals("keep outside corrupt store", snapshot.items.single().entry.response.userText)
        assertFalse(snapshot.items.single().persistent)
    }

    @Test
    fun rolledBackBootstrapCanInitializeThroughPreliminaryReadAndRetainAfterReopen() {
        val file = File(context.noBackupFilesDir, "notification-action-recovery.sqlite")
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.beginTransaction()
            try {
                it.execSQL("CREATE TABLE drafts (id TEXT)")
                it.version = 1
                // Simulate interruption before the schema transaction commits.
            } finally {
                it.endTransaction()
            }
            assertEquals(0, it.version)
            it.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name != 'android_metadata'", null).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(0, row.getInt(0))
            }
        }
        val original = response(95, NotificationAction.REPLY, " \tbootstrap retry original\n ")
        val result = NotificationActionRecoveryStore(context).retain(original)
        assertTrue(result is NotificationRecoveryWrite.Stored)
        assertEquals(original, entries().single().response)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(1, it.version)
        }
    }

    @Test
    fun emptyVersionZeroReadRetriesBootstrapWithoutInventingAnOriginal() {
        val file = File(context.noBackupFilesDir, "notification-action-recovery.sqlite")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { assertEquals(0, it.version) }
        val loaded = NotificationActionRecoveryStore(context).read() as NotificationRecoveryRead.Ready
        assertTrue(loaded.entries.isEmpty())
        assertTrue(entries().isEmpty())
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(1, it.version)
        }
    }

    @Test
    fun emptyUnknownVersionZeroTableIsNotBootstrapState() {
        val file = File(context.noBackupFilesDir, "notification-action-recovery.sqlite")
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.execSQL("CREATE TABLE unknown_empty (value TEXT)")
        }
        val store = NotificationActionRecoveryStore(context)
        assertEquals(
            NotificationRecoveryFailure.INVALID_RECORD,
            (store.read() as NotificationRecoveryRead.Failed).reason,
        )
        assertEquals(
            NotificationRecoveryFailure.INVALID_RECORD,
            (store.retain(response(97, NotificationAction.MARK_READ)) as NotificationRecoveryWrite.Failed).reason,
        )
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(0, it.version)
            it.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = 'unknown_empty'", null).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(1, row.getInt(0))
            }
            it.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = 'drafts'", null).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(0, row.getInt(0))
            }
        }
    }

    @Test
    fun versionZeroWithUnknownSchemaIsPreservedRatherThanInitialized() {
        val file = File(context.noBackupFilesDir, "notification-action-recovery.sqlite")
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.execSQL("CREATE TABLE preserved (value INTEGER NOT NULL)")
            it.execSQL("INSERT INTO preserved VALUES (23)")
            assertEquals(0, it.version)
        }
        val store = NotificationActionRecoveryStore(context)
        assertEquals(
            NotificationRecoveryFailure.INVALID_RECORD,
            (store.read() as NotificationRecoveryRead.Failed).reason,
        )
        assertEquals(
            NotificationRecoveryFailure.INVALID_RECORD,
            (store.retain(response(96, NotificationAction.MARK_READ)) as NotificationRecoveryWrite.Failed).reason,
        )
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(0, it.version)
            it.rawQuery("SELECT value FROM preserved", null).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(23, row.getInt(0))
            }
            it.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = 'drafts'", null).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(0, row.getInt(0))
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
        receive(context, intent(800, NotificationAction.REPLY, "recover me"))
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

    @Test
    fun receiverReturnsBeforeBlockedStorageAndFinishesItsRealPendingResultAfterCustody() {
        fillQueue()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val gated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File {
                assertFalse(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Storage gate was not released" }
                return context.noBackupFilesDir
            }
        }
        val receiver = object : NotificationActionReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                super.onReceive(gated, intent)
                returned.countDown()
            }
        }
        val application = ApplicationProvider.getApplicationContext<Context>()
        val action = "${application.packageName}.RECOVERY_ASYNC_TEST"
        application.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        try {
            application.sendOrderedBroadcast(
                intent(801, NotificationAction.REPLY, " \tstill original\n ").setAction(action)
                    .setPackage(application.packageName),
                null, object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) { finished.countDown() }
                }, null, 0, null, null,
            )
            waitForBroadcast(returned)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(1L, finished.count)
        } finally {
            release.countDown()
            waitForBroadcast(finished)
            application.unregisterReceiver(receiver)
        }
        assertEquals(response(801, NotificationAction.REPLY, " \tstill original\n "), entries().single().response)
    }

    @Test
    fun boundedHandoffKeepsFifoAndFinishesEveryAcceptedOrRejectedBroadcastOnce() {
        NotificationRecoveryBroadcastQueue().use { queue ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(33)
            val order = java.util.Collections.synchronizedList(mutableListOf<Int>())
            val finishes = AtomicInteger()
            val rejected = AtomicInteger()
            val unexpectedDeadlines = AtomicInteger()
            try {
                (1..33).forEach { id ->
                    val admitted = queue.submit(
                        response(id, NotificationAction.REPLY, " \t$id\n "),
                        work = {
                            if (id == 1) {
                                entered.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                            }
                            order += id
                        },
                        rejected = { rejected.incrementAndGet() },
                        timedOut = { unexpectedDeadlines.incrementAndGet() },
                        finish = { finishes.incrementAndGet(); finished.countDown() },
                    )
                    assertEquals(id <= 32, admitted)
                    if (id == 1) assertTrue(entered.await(5, TimeUnit.SECONDS))
                }
                assertEquals(1, rejected.get())
                assertEquals(1, finishes.get())
            } finally {
                release.countDown()
            }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals((1..32).toList(), order.toList())
            assertEquals(33, finishes.get())
            assertEquals(0, unexpectedDeadlines.get())
        }
    }

    @Test
    fun deadlineFinishesOnceWithoutCancellingAnUnconfirmedWriteOrReleasingItsBudget() {
        NotificationRecoveryBroadcastQueue(timeoutMillis = 50).use { queue ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val settled = CountDownLatch(1)
            val deadline = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val finishes = AtomicInteger()
            val text = "x".repeat(120 * 1024)
            try {
                assertTrue(queue.submit(
                    response(1, NotificationAction.REPLY, text),
                    work = {
                        entered.countDown()
                        try { check(release.await(5, TimeUnit.SECONDS)) } finally { settled.countDown() }
                    },
                    rejected = { error("Unexpected rejection") },
                    timedOut = { deadline.countDown() },
                    finish = { finishes.incrementAndGet(); finished.countDown() },
                ))
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertTrue(deadline.await(5, TimeUnit.SECONDS))
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                assertEquals(1L, settled.count)
                // Eight 120-KiB entries fit; the ninth cannot steal a timed-out work slot.
                (2..8).forEach { id ->
                    assertTrue(queue.submit(
                        response(id, NotificationAction.REPLY, text),
                        work = {}, rejected = { error("Unexpected rejection") },
                        timedOut = {}, finish = {},
                    ))
                }
                val rejected = AtomicInteger()
                val rejectedFinish = AtomicInteger()
                assertFalse(queue.submit(
                    response(9, NotificationAction.REPLY, text),
                    work = { error("Over-budget work must not run") },
                    rejected = { rejected.incrementAndGet() }, timedOut = {},
                    finish = { rejectedFinish.incrementAndGet() },
                ))
                assertEquals(1, rejected.get())
                assertEquals(1, rejectedFinish.get())
            } finally {
                release.countDown()
            }
            assertTrue(settled.await(5, TimeUnit.SECONDS))
            val drained = CountDownLatch(1)
            assertTrue(queue.submit(
                response(10, NotificationAction.MARK_READ),
                work = { drained.countDown() }, rejected = { error("Unexpected rejection") },
                timedOut = {}, finish = {},
            ))
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            assertEquals(1, finishes.get())
        }
    }

    @Test
    fun handoffRejectsOversizeInputWithoutRunningStorageOrClaimingRetention() {
        NotificationRecoveryBroadcastQueue().use { queue ->
            val rejected = AtomicInteger()
            val finishes = AtomicInteger()
            assertFalse(queue.submit(
                response(1, NotificationAction.REPLY, "x".repeat(128 * 1024 + 1)),
                work = { error("Oversize work must not run") },
                rejected = { rejected.incrementAndGet() },
                timedOut = { error("Rejected work has no deadline") },
                finish = { finishes.incrementAndGet() },
            ))
            assertEquals(1, rejected.get())
            assertEquals(1, finishes.get())
        }
    }

    private fun receive(recoveryContext: Context, intent: Intent) {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val action = "${application.packageName}.RECOVERY_TEST"
        val receiver = object : NotificationActionReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                super.onReceive(recoveryContext, intent)
            }
        }
        val finished = CountDownLatch(1)
        application.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        try {
            application.sendOrderedBroadcast(
                intent.setAction(action).setPackage(application.packageName),
                null, object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) { finished.countDown() }
                }, null, 0, null, null,
            )
            waitForBroadcast(finished)
        } finally {
            application.unregisterReceiver(receiver)
        }
    }

    private fun waitForBroadcast(completion: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (completion.count != 0L && System.nanoTime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            completion.await(1, TimeUnit.MILLISECONDS)
        }
        assertEquals("Broadcast did not complete", 0L, completion.count)
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
