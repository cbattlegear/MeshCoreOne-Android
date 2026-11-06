// AndroidOnly: WP-215 Mounted explicit recovery, clipboard custody and cancellation controls.
package com.meshcoreone.android.app

import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.platform.notifications.messaging.NotificationActionRecovery
import com.meshcoreone.android.platform.notifications.messaging.NotificationActionRecoveryStore
import com.meshcoreone.android.platform.notifications.messaging.NotificationRecoveryRead
import com.meshcoreone.android.platform.notifications.messaging.NotificationRecoveryRetention
import com.meshcoreone.android.platform.notifications.messaging.NotificationRecoveryWrite
import com.meshcoreone.android.platform.notifications.messaging.RejectedNotificationAction
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.meshcoreone.android.platform.notifications.R as NotificationStrings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w400dp-h800dp")
class NotificationRecoveryClipboardTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val folder = TemporaryFolder()
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java)
    private lateinit var context: Context
    private var clipboardUnavailable = false
    private var clipboardFailure: RuntimeException? = null
    private var mounted = false
    private var closed = false
    private val otherDialog = mutableStateOf(false)

    @Before
    fun prepare() {
        activity.setup().visible()
        context = object : ContextWrapper(activity.get()) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = folder.root
            override fun getSystemService(name: String): Any? {
                if (name == CLIPBOARD_SERVICE) {
                    clipboardFailure?.let { throw it }
                    if (clipboardUnavailable) return null
                }
                return super.getSystemService(name)
            }
        }
        NotificationActionRecovery.acknowledgeFailure(NotificationActionRecovery.load(context).version)
    }

    @After
    fun close() {
        if (!closed) activity.pause().stop().destroy()
        NotificationActionRecovery.load(context).items.filter { !it.persistent }.forEach {
            assertNull(NotificationActionRecovery.dispose(context, it))
        }
        NotificationActionRecovery.acknowledgeFailure(NotificationActionRecovery.load(context).version)
    }

    @Test
    fun clipboardTransferPreservesExactTextAndSensitiveFlagButDoesNotItselfDelete() {
        val original = " \tprivate\uD83D\uDE80 text\n "
        val entry = save(1, original)
        assertTrue(copyRejectedNotificationText(context, original))
        val clip = requireNotNull(context.getSystemService(ClipboardManager::class.java)).primaryClip!!
        assertEquals(original, clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        assertEquals(entry.id, entries().single().id)
    }

    @Test
    fun unavailableClipboardIsNotReportedAsASuccessfulDisposition() {
        val entry = save(2, "keep")
        clipboardUnavailable = true
        assertFalse(copyRejectedNotificationText(context, "keep"))
        assertEquals(entry.id, entries().single().id)
    }

    @Test
    fun cancelClosesTheActualDialogWithoutDeletingOrReplaying() {
        val entry = save(3, "cancel keeps this")
        mount()
        awaitText("cancel keeps this")
        compose.onNodeWithText(text(AppLocalizableStrings.commonCancel)).performClick()
        compose.waitForIdle()
        assertEquals(entry.id, entries().single().id)
        compose.onNodeWithText(text(NotificationStrings.string.notification_action_recovery_title)).assertDoesNotExist()
    }

    @Test
    fun oldDialogCancelCannotAcknowledgeANewerUnseenRejection() {
        val entry = save(30, "older displayed response")
        mount()
        awaitText("older displayed response")
        val oldCancel = requireNotNull(
            compose.onNodeWithText(text(AppLocalizableStrings.commonCancel))
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action,
        )
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread {
                assertEquals(
                    NotificationRecoveryRetention.NOT_RETAINED,
                    NotificationActionRecovery.retain(context, response(31, "x".repeat(128 * 1024))),
                )
                // Invoke the displayed callback in this same event, before a new dialog can compose.
                assertTrue(oldCancel())
            }
            val afterOldCancel = NotificationActionRecovery.load(context)
            assertTrue(afterOldCancel.notRetained)
            assertEquals(entry.id, afterOldCancel.items.single().entry.id)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        awaitText(text(NotificationStrings.string.notification_action_recovery_not_retained))
        assertTrue(NotificationActionRecovery.load(context).notRetained)
        compose.onNodeWithText(text(AppLocalizableStrings.commonCancel)).performClick()
        compose.waitForIdle()
        assertFalse(NotificationActionRecovery.load(context).notRetained)
        assertEquals(entry.id, entries().single().id)
    }

    @Test
    fun destroyingTheHostDoesNotDisposeTheDurableRecoveryRecord() {
        val entry = save(4, "keep on host cancellation")
        mount()
        awaitText("keep on host cancellation")
        activity.pause().stop().destroy()
        closed = true
        assertEquals(entry.id, entries().single().id)
    }

    @Test
    fun anExistingModalDefersRecoveryWithoutAcknowledgingOrDisposingIt() {
        val entry = save(32, "deferred original")
        otherDialog.value = true
        mount()
        compose.waitForIdle()
        compose.onNodeWithText(text(NotificationStrings.string.notification_action_recovery_title)).assertDoesNotExist()
        assertEquals(entry.id, entries().single().id)
        compose.runOnIdle { otherDialog.value = false }
        awaitText("deferred original")
        assertEquals(entry.id, entries().single().id)
    }

    @Test
    fun explicitCopyFromTheActualDialogRemovesOnlyTheCopiedDraft() {
        val first = save(5, " \tfirst unchanged\n ")
        val second = save(6, "second remains")
        mount()
        awaitText(" \tfirst unchanged\n ")
        compose.onNodeWithText(text(AppChatsStrings.chatsMessageActionCopy)).performClick()
        compose.waitUntil(5_000) { entries().map { it.id } == listOf(second.id) }
        val clip = requireNotNull(context.getSystemService(ClipboardManager::class.java)).primaryClip!!
        assertEquals(" \tfirst unchanged\n ", clip.getItemAt(0).text.toString())
        assertFalse(entries().any { it.id == first.id })
        awaitText("second remains")
    }

    @Test
    fun copyFailureRemainsVisibleAndLeavesTheRecordForExplicitRecovery() {
        val entry = save(7, "failed copy keeps this")
        mount()
        awaitText("failed copy keeps this")
        clipboardUnavailable = true
        compose.onNodeWithText(text(AppChatsStrings.chatsMessageActionCopy)).performClick()
        awaitText(text(NotificationStrings.string.notification_action_recovery_copy_failed))
        assertEquals(entry.id, entries().single().id)
    }

    @Test
    fun wrappedClipboardServiceDeathIsVisibleAndDoesNotDisposeTheOriginal() {
        val entry = save(33, "clipboard service failed")
        mount()
        awaitText("clipboard service failed")
        clipboardFailure = RuntimeException(android.os.DeadObjectException())
        compose.onNodeWithText(text(AppChatsStrings.chatsMessageActionCopy)).performClick()
        awaitText(text(NotificationStrings.string.notification_action_recovery_copy_failed))
        assertEquals(entry.id, entries().single().id)
    }

    @Test
    fun unrelatedProgrammingFailureIsNotHiddenAsAnUnavailableClipboard() {
        val expected = UnsupportedOperationException("synthetic unexpected service programming failure")
        clipboardFailure = expected
        val actual = org.junit.Assert.assertThrows(UnsupportedOperationException::class.java) {
            copyRejectedNotificationText(context, "original")
        }
        org.junit.Assert.assertSame(expected, actual)
    }

    @Test
    fun explicitDiscardOfRejectedMarkReadNeverInvokesAnAutomaticRetry() {
        val response = response(8, null, NotificationAction.MARK_READ)
        val entry = (NotificationActionRecoveryStore(context).retain(response) as NotificationRecoveryWrite.Stored).entry
        mount()
        awaitText(text(NotificationStrings.string.notification_action_recovery_rejected_mark_read))
        compose.onNodeWithText(text(AppChatsStrings.chatsMessageActionCopy)).assertDoesNotExist()
        compose.onNodeWithText(text(AppLocalizableStrings.commonTryAgain)).assertDoesNotExist()
        compose.onNodeWithText(text(AppChatsStrings.chatsMessageActionDelete)).performClick()
        compose.waitUntil(5_000) { entries().isEmpty() }
        assertFalse(entries().any { it.id == entry.id })
    }

    @Test
    fun failedDiscardSurfacesAnErrorWithoutErasingTheOriginal() {
        val entry = save(9, "disk error keeps this")
        mount()
        awaitText("disk error keeps this")
        val file = File(context.noBackupFilesDir, "notification-action-recovery.sqlite")
        android.database.sqlite.SQLiteDatabase.openDatabase(
            file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        ).use { it.version = 99 }
        compose.onNodeWithText(text(AppChatsStrings.chatsMessageActionDelete)).performClick()
        awaitText(text(NotificationStrings.string.notification_action_recovery_discard_failed))
        android.database.sqlite.SQLiteDatabase.openDatabase(
            file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use {
            it.rawQuery("SELECT id FROM drafts", null).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(entry.id.toString(), row.getString(0))
            }
        }
    }

    @Test
    fun processOnlyRecoveryIsDistinguishedFromAPrivateSavedRecord() {
        val blocked = File(folder.root, "not-a-directory").apply { writeText("occupied") }
        context = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = blocked
        }
        assertEquals(
            NotificationRecoveryRetention.PROCESS_ONLY,
            NotificationActionRecovery.retain(context, response(10, "process only")),
        )
        mount()
        awaitText("process only")
        compose.onNodeWithText(text(NotificationStrings.string.notification_action_recovery_memory_only)).assertIsDisplayed()
        compose.onNodeWithText(text(NotificationStrings.string.notification_action_recovery_saved)).assertDoesNotExist()
        compose.onNodeWithText(text(AppLocalizableStrings.commonCancel)).performClick()
        assertEquals("process only", NotificationActionRecovery.load(context).items.single().entry.response.userText)
    }

    @Test
    fun blockedCustodyStillShowsNewFailureWithoutAcknowledgingUnseenRecords() {
        val original = save(40, "older saved original")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        context = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Storage gate was not released" }
                return super.getNoBackupFilesDir()
            }
        }
        val custody = CompletableFuture.supplyAsync {
            NotificationActionRecovery.retain(context, response(41, "new pending original"))
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            NotificationActionRecovery.requireManualOpen()
            mount()
            awaitText(text(NotificationStrings.string.notification_action_recovery_storage_unavailable))
            awaitText(text(NotificationStrings.string.notification_action_recovery_launch_failed))
            compose.onNodeWithText("older saved original").assertDoesNotExist()
            val oldCancel = requireNotNull(
                compose.onNodeWithText(text(AppLocalizableStrings.commonCancel))
                    .fetchSemanticsNode().config[SemanticsActions.OnClick].action,
            )
            compose.runOnUiThread {
                // Queue overflow/status publication must not acquire the blocked custody lock.
                NotificationActionRecovery.handoffNotRetained()
                assertTrue(oldCancel())
            }
            awaitText(text(NotificationStrings.string.notification_action_recovery_not_retained))
            assertFalse(NotificationActionRecovery.statusSnapshot().recordsAvailable)
            assertTrue(NotificationActionRecovery.statusSnapshot().notRetained)
            compose.onNodeWithText(text(AppChatsStrings.chatsMessageActionDelete)).assertDoesNotExist()
            compose.onNodeWithText(text(AppLocalizableStrings.commonCancel)).performClick()
            assertTrue(NotificationActionRecovery.statusSnapshot().notRetained)
        } finally {
            release.countDown()
            assertEquals(NotificationRecoveryRetention.DURABLE, custody.get(5, TimeUnit.SECONDS))
        }
        awaitText("older saved original")
        assertEquals(listOf(original.id), entries().take(1).map { it.id })
        assertEquals(2, entries().size)
    }

    @Test
    fun ordinaryStartupDoesNotShowRecoveryWhileItsInitialReadIsPending() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        context = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return super.getNoBackupFilesDir()
            }
        }
        try {
            mount()
            compose.waitUntil(5_000) { entered.count == 0L }
            compose.onNodeWithText(text(NotificationStrings.string.notification_action_recovery_title))
                .assertDoesNotExist()
            assertFalse(NotificationActionRecovery.statusSnapshot().manualOpenRequired)
            assertFalse(NotificationActionRecovery.statusSnapshot().notRetained)
        } finally {
            release.countDown()
        }
        compose.waitForIdle()
        compose.onNodeWithText(text(NotificationStrings.string.notification_action_recovery_title))
            .assertDoesNotExist()
    }

    @Test
    fun failedReadCancelDoesNotAcknowledgeFlagsOrUnknownDurableRecords() {
        val original = save(42, "original in unavailable storage")
        val available = context
        context = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = throw SecurityException("synthetic read denial")
        }
        NotificationActionRecovery.requireManualOpen()
        NotificationActionRecovery.handoffNotRetained()
        val unread = NotificationActionRecovery.load(context)
        assertFalse(unread.recordsAvailable)
        assertTrue(unread.storageFailure != null)
        mount()
        awaitText(text(NotificationStrings.string.notification_action_recovery_storage_unavailable))
        awaitCompletedRead()
        compose.onNodeWithText(text(AppLocalizableStrings.commonCancel)).performClick()
        compose.waitForIdle()
        val afterCancel = NotificationActionRecovery.statusSnapshot()
        assertEquals(unread.version, afterCancel.version)
        assertTrue(afterCancel.manualOpenRequired)
        assertTrue(afterCancel.notRetained)
        compose.onNodeWithText(text(NotificationStrings.string.notification_action_recovery_title))
            .assertDoesNotExist()
        context = available
        assertEquals(original.id, entries().single().id)
    }

    @Test
    fun dismissedPendingReadCannotHideSameVersionFailedReadProcessCopy() {
        val available = context
        val blocked = File(folder.root, "memory-fallback").apply { writeText("not a directory") }
        val failing = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = blocked
        }
        val original = " \tprocess original must become visible\n "
        assertEquals(
            NotificationRecoveryRetention.PROCESS_ONLY,
            NotificationActionRecovery.retain(failing, response(43, original)),
        )
        NotificationActionRecovery.requireManualOpen()
        val version = NotificationActionRecovery.changes.value
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        context = object : ContextWrapper(failing) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return super.getNoBackupFilesDir()
            }
        }
        try {
            mount()
            compose.waitUntil(5_000) { entered.count == 0L }
            compose.onNodeWithTag("notification-recovery-read-pending").assertExists()
            compose.onNodeWithText(original).assertDoesNotExist()
            compose.onNodeWithText(text(AppLocalizableStrings.commonCancel)).performClick()
            assertEquals(version, NotificationActionRecovery.changes.value)
            compose.onNodeWithText(text(NotificationStrings.string.notification_action_recovery_title))
                .assertDoesNotExist()
        } finally {
            release.countDown()
        }
        awaitText(original)
        awaitCompletedRead()
        assertEquals(version, NotificationActionRecovery.changes.value)
        compose.onNodeWithText(text(AppChatsStrings.chatsMessageActionCopy)).assertIsDisplayed()
        assertTrue(NotificationActionRecovery.statusSnapshot().manualOpenRequired)
        compose.onNodeWithText(text(AppLocalizableStrings.commonCancel)).performClick()
        assertTrue(NotificationActionRecovery.statusSnapshot().manualOpenRequired)
        context = available
        assertEquals(original, NotificationActionRecovery.load(failing).items.single().entry.response.userText)
    }

    @Test
    fun completedStorageFailureDoesNotInventARejectedActionOnOrdinaryStartup() {
        val available = context
        context = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = throw SecurityException("synthetic read denial")
        }
        try {
            mount()
            awaitCompletedRead()
            awaitText(text(NotificationStrings.string.notification_action_recovery_storage_unavailable))
            compose.onNodeWithText("Notification action not accepted").assertDoesNotExist()
            assertEquals("Notification action recovery", text(NotificationStrings.string.notification_action_recovery_title))
            assertFalse(NotificationActionRecovery.statusSnapshot().manualOpenRequired)
            assertFalse(NotificationActionRecovery.statusSnapshot().notRetained)
        } finally {
            context = available
        }
    }

    @Test
    fun completingOlderDeleteCannotHideANewerUnseenLoss() {
        dispositionDoesNotHideNewLoss(copy = false)
    }

    @Test
    fun completingOlderCopyCannotHideANewerUnseenLoss() {
        dispositionDoesNotHideNewLoss(copy = true)
    }

    private fun dispositionDoesNotHideNewLoss(copy: Boolean) {
        val original = save(44, "dispose only this original")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gated = AtomicBoolean()
        context = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File {
                if (gated.get()) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                return super.getNoBackupFilesDir()
            }
        }
        mount()
        awaitText("dispose only this original")
        val observed = NotificationActionRecovery.changes.value
        gated.set(true)
        try {
            compose.onNodeWithText(text(
                if (copy) AppChatsStrings.chatsMessageActionCopy else AppChatsStrings.chatsMessageActionDelete,
            )).performClick()
            compose.waitUntil(5_000) { entered.count == 0L }
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                NotificationActionRecovery.handoffNotRetained()
                release.countDown()
            }
            val completionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (NotificationActionRecovery.changes.value < observed + 2 &&
                System.nanoTime() < completionDeadline
            ) {
                Thread.sleep(1)
            }
            assertTrue(NotificationActionRecovery.changes.value >= observed + 2)
            // No frame has displayed the new loss before the older disposition completes.
            compose.onNodeWithText(text(NotificationStrings.string.notification_action_recovery_not_retained))
                .assertDoesNotExist()
        } finally {
            release.countDown()
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(5_000) { NotificationActionRecovery.changes.value >= observed + 2 }
        awaitCompletedRead()
        awaitText(text(NotificationStrings.string.notification_action_recovery_not_retained))
        assertTrue(NotificationActionRecovery.load(context).notRetained)
        assertFalse(entries().any { it.id == original.id })
        if (copy) {
            val clipboard = requireNotNull(context.getSystemService(ClipboardManager::class.java))
            assertEquals("dispose only this original", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test
    fun failedOlderDeleteRemainsVisibleAfterItsItemTemporarilyLeavesTheSnapshot() {
        val original = save(45, "keep after failed deletion")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failOnce = AtomicBoolean()
        context = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File {
                if (failOnce.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    throw SecurityException("synthetic fail-once disposition")
                }
                return super.getNoBackupFilesDir()
            }
        }
        mount()
        awaitText("keep after failed deletion")
        failOnce.set(true)
        try {
            compose.onNodeWithText(text(AppChatsStrings.chatsMessageActionDelete)).performClick()
            compose.waitUntil(5_000) { entered.count == 0L }
            compose.runOnUiThread { NotificationActionRecovery.handoffNotRetained() }
            awaitText(text(NotificationStrings.string.notification_action_recovery_not_retained))
            compose.onNodeWithTag("notification-recovery-read-pending").assertExists()
            compose.onNodeWithText("keep after failed deletion").assertDoesNotExist()
            compose.onNodeWithText(text(AppLocalizableStrings.commonCancel)).assertIsNotEnabled()
        } finally {
            release.countDown()
        }
        awaitText(text(NotificationStrings.string.notification_action_recovery_discard_failed))
        awaitText("keep after failed deletion")
        awaitCompletedRead()
        assertEquals(original.id, entries().single().id)
        compose.onNodeWithText(text(NotificationStrings.string.notification_action_recovery_discard_failed))
            .assertIsDisplayed()
    }

    private fun awaitCompletedRead() {
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("notification-recovery-read-complete"))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun mount() {
        check(!mounted)
        mounted = true
        activity.get().setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MeshCoreTheme(motionScale = 0f) {
                    NotificationActionRecoveryDialog(blockedByOtherDialog = otherDialog.value)
                }
            }
        }
    }

    private fun awaitText(value: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(value)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(value).assertIsDisplayed()
    }

    private fun text(resource: Int) = activity.get().getString(resource)

    private fun save(id: Int, text: String): RejectedNotificationAction =
        (NotificationActionRecoveryStore(context).retain(response(id, text)) as NotificationRecoveryWrite.Stored).entry

    private fun entries(): List<RejectedNotificationAction> =
        (NotificationActionRecoveryStore(context).read() as NotificationRecoveryRead.Ready).entries

    private fun response(id: Int, text: String?, action: NotificationAction = NotificationAction.REPLY) =
        NotificationResponse(
            NotificationPayload.ChannelMessage(
                RadioId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
                7u,
                UUID(0, id.toLong()),
            ),
            action,
            text,
        )
}
