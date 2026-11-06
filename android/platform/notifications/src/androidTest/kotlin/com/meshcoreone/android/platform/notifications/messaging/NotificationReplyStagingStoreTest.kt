// AndroidOnly: WP-401 Real SQLite reply staging, lossless text, conflicts and process-store lifecycle.
package com.meshcoreone.android.platform.notifications.messaging

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.model.RadioId
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class NotificationReplyStagingStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val file = File(context.cacheDir, "reply-staging-${UUID.randomUUID()}.db")
    private val limits = NotificationReplyLimits(20, 20_000, 100_000)
    private val store get() = NotificationReplyStagingStore(file, limits)
    private val post = NotificationReplyPost(1, UUID.randomUUID(), "REPLY")
    private val contact = EntityKey(RadioId(UUID(0, 1)), UUID(0, 2))
    private val payload = NotificationPayload.DirectMessage(contact, UUID(0, 3))

    @Before
    fun initialize() = runTest {
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeNew())
    }

    @After
    fun cleanup() {
        listOf(file, File("${file.path}-journal"), File("${file.path}-wal"), File("${file.path}-shm"))
            .filter(File::exists).forEach { check(it.delete()) }
    }

    @Test
    fun copyTextAndContainingInventoryRedactRenderingWithoutChangingValueSemantics() = runTest {
        val exact = "private-copy-sentinel \t\u0000e\u0301\uD800 \r\n "
        assertTrue(store.stage(post, payload, exact) is NotificationReplyStagingStore.StageResult.Staged)
        val retained = store.readCopyOnly() as NotificationReplyStagingStore.CopyInventory.Retained
        val record = retained.records.single()
        val same = record.copy()
        val changed = record.copy(exactText = "$exact ")

        assertEquals(exact.toCharArray().toList(), record.exactText.toCharArray().toList())
        assertEquals(record, same)
        assertEquals(record.hashCode(), same.hashCode())
        assertNotEquals(record, changed)
        assertEquals("$exact ", changed.exactText)
        assertEquals(retained, NotificationReplyStagingStore.CopyInventory.Retained(listOf(same)))
        assertEquals("CopyText(<redacted>)", record.toString())
        assertEquals(record.toString(), changed.toString())
        listOf(record, same, changed, retained).forEach {
            assertFalse(it.toString().contains("private-copy-sentinel"))
            assertFalse(it.toString().contains(exact))
        }
    }

    @Test
    fun exactTextAndStableIdsSurviveReopenAndIdenticalSecondSendNeedsNewToken() = runTest {
        val text = " \t\r\nhello\u0000 e\u0301 \u00e9 \uD83D\uDE80 \uD800 \uDC00 \n "
        val first = store.stage(post, payload, text) as NotificationReplyStagingStore.StageResult.Staged
        val again = store.stage(post, payload, text) as NotificationReplyStagingStore.StageResult.AlreadyStaged
        assertEquals(first.identity, again.identity)
        assertEquals(first.outgoingMessageId, again.outgoingMessageId)
        val reopened = inventory().single() as NotificationReplyStagingStore.RecoveryEntry.StagedReply
        assertEquals(text.toCharArray().toList(), reopened.exactText.toCharArray().toList())
        assertEquals(payload, reopened.payload)
        assertEquals(post, reopened.post)
        assertEquals(NotificationReplyStagingStore.TargetAuthority.UNPROVED, reopened.targetAuthority)
        assertEquals(first.identity, reopened.identity)
        assertEquals(first.outgoingMessageId, reopened.outgoingMessageId)
        val next = store.stage(post.copy(postId = UUID.randomUUID()), payload, text)
            as NotificationReplyStagingStore.StageResult.Staged
        assertNotEquals(first.identity.recordId, next.identity.recordId)
        assertNotEquals(first.outgoingMessageId, next.outgoingMessageId)
        assertFalse(reopened.toString().contains(text))
    }

    @Test
    fun journalRoutingHasIndependentVersionedDirectVectorWithoutIntentOrOpenCodec() = runTest {
        store.stage(post, payload, "raw")
        val expected = "1|direct|00000000-0000-0000-0000-000000000001|" +
            "00000000-0000-0000-0000-000000000002|00000000-0000-0000-0000-000000000003"
        val expectedBytes = ByteBuffer.allocate(expected.length * Char.SIZE_BYTES).apply {
            expected.forEach { putChar(it) }
        }.array()
        val actual = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT payload FROM reply_records WHERE sequence=1", null).use {
                assertTrue(it.moveToFirst())
                it.getBlob(0)
            }
        }
        assertEquals(expectedBytes.toList(), actual.toList())
        assertEquals(payload, (inventory().single() as NotificationReplyStagingStore.RecoveryEntry.StagedReply).payload)
    }

    @Test
    fun currentMainChannelBoundaryRoutesRoundTripWithoutInventingOccupantOrOrigin() = runTest {
        val zero = NotificationPayload.ChannelMessage(RadioId(UUID(0, 0)), 0u, UUID(0, 0))
        val maximum = NotificationPayload.ChannelMessage(
            RadioId(UUID(-1, -1)), 255u, UUID(-1, -1),
        )
        val expected = listOf(zero, maximum)
        expected.forEach { route ->
            assertTrue(store.stage(post.copy(postId = UUID.randomUUID()), route, " unbound target ") is
                NotificationReplyStagingStore.StageResult.Staged)
        }
        val entries = inventory().map { it as NotificationReplyStagingStore.RecoveryEntry.StagedReply }
        assertEquals(expected, entries.map { it.payload })
        assertTrue(entries.all { it.targetAuthority == NotificationReplyStagingStore.TargetAuthority.UNPROVED })
        assertTrue(entries.all { it.custody == null })
    }

    @Test
    fun changedTextAndChangedTargetAreUnboundDurableConflictsNotAnotherSend() = runTest {
        val first = store.stage(post, payload, " first ") as NotificationReplyStagingStore.StageResult.Staged
        val changed = NotificationPayload.ChannelMessage(contact.radioId, 7u, UUID(0, 4))
        val textConflict = store.stage(post, payload, " second ") as
            NotificationReplyStagingStore.StageResult.ConflictRetained
        val targetConflict = store.stage(post, changed, " first ") as
            NotificationReplyStagingStore.StageResult.ConflictRetained
        assertFalse(textConflict.alreadyRetained)
        assertFalse(targetConflict.alreadyRetained)
        assertNotEquals(first.identity, textConflict.identity)
        assertNotEquals(textConflict.identity, targetConflict.identity)
        assertEquals(
            textConflict.copy(alreadyRetained = true),
            store.stage(post, payload, " second "),
        )
        assertEquals(
            targetConflict.copy(alreadyRetained = true),
            store.stage(post, changed, " first "),
        )
        val entries = inventory()
        assertEquals(3, entries.size)
        assertEquals(" first ", entries.first().exactText)
        assertTrue(entries.drop(1).all { it is NotificationReplyStagingStore.RecoveryEntry.UnboundConflict })
        assertTrue(entries.all { it.targetAuthority == NotificationReplyStagingStore.TargetAuthority.UNPROVED })
        assertEquals(listOf(" second ", " first "), entries.drop(1).map { it.exactText })
        assertEquals(first.identity, entries.first().identity)
    }

    @Test
    fun genuinelyConcurrentSeparateHandlesConvergeForDuplicateAndConflictingInputs() = runTest {
        val start = CountDownLatch(1)
        val work = (0 until 8).map { index ->
            async(Dispatchers.Default) {
                start.await()
                store.stage(post, payload, if (index % 2 == 0) "raw A " else " raw B")
            }
        }
        start.countDown()
        val results = work.awaitAll()
        assertEquals(1, results.count { it is NotificationReplyStagingStore.StageResult.Staged })
        val entries = inventory()
        assertEquals(2, entries.size)
        assertEquals(setOf("raw A ", " raw B"), entries.map { it.exactText }.toSet())
        assertEquals(1, entries.count { it is NotificationReplyStagingStore.RecoveryEntry.StagedReply })
        assertEquals(1, entries.count { it is NotificationReplyStagingStore.RecoveryEntry.UnboundConflict })
        assertTrue(results.all {
            it is NotificationReplyStagingStore.StageResult.Staged ||
                it is NotificationReplyStagingStore.StageResult.AlreadyStaged ||
                it is NotificationReplyStagingStore.StageResult.ConflictRetained
        })
    }

    @Test
    fun explicitRecordCapacityNeverEvictsAndExactReplayWorksWhenFull() = runTest {
        cleanup()
        val small = NotificationReplyStagingStore(file, NotificationReplyLimits(2, 20_000, 40_000))
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, small.initializeNew())
        val first = small.stage(post, payload, "a") as NotificationReplyStagingStore.StageResult.Staged
        val conflict = small.stage(post, payload, "b") as NotificationReplyStagingStore.StageResult.ConflictRetained
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.RECORD_CAPACITY),
            small.stage(post, payload, "c"),
        )
        assertEquals(conflict.copy(alreadyRetained = true), small.stage(post, payload, "b"))
        assertEquals(
            NotificationReplyStagingStore.StageResult.AlreadyStaged(first.identity, first.outgoingMessageId),
            small.stage(post, payload, "a"),
        )
        val entries = (small.read() as NotificationReplyStagingStore.ReadResult.Inventory).entries
        assertEquals(listOf("a", "b"), entries.map { it.exactText })
    }

    @Test
    fun perRecordAndAggregatePrivateByteLimitsUseActualSerializedContent() = runTest {
        store.stage(post, payload, "x")
        val size = sqlLong("SELECT content_bytes FROM reply_metadata")
        cleanup()
        val exact = NotificationReplyStagingStore(file, NotificationReplyLimits(10, size, size * 2))
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, exact.initializeNew())
        assertTrue(exact.stage(post, payload, "x") is NotificationReplyStagingStore.StageResult.Staged)
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(
                NotificationReplyStagingStore.Failure.PRIVATE_DATA_CAPACITY,
            ),
            exact.stage(post.copy(postId = UUID.randomUUID()), payload, "xx"),
        )
        assertTrue(exact.stage(post.copy(postId = UUID.randomUUID()), payload, "y") is
            NotificationReplyStagingStore.StageResult.Staged)
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(
                NotificationReplyStagingStore.Failure.PRIVATE_DATA_CAPACITY,
            ),
            exact.stage(post.copy(postId = UUID.randomUUID()), payload, "z"),
        )
        assertEquals(2, (exact.read() as NotificationReplyStagingStore.ReadResult.Inventory).entries.size)
    }

    @Test
    fun rollbackAfterRecordInsertPreservesAllPriorTextAndAllowsExactRetry() = runTest {
        val first = store.stage(post, payload, "first") as NotificationReplyStagingStore.StageResult.Staged
        exec(
            """CREATE TRIGGER reject_update BEFORE UPDATE ON reply_metadata
               BEGIN SELECT RAISE(ABORT, 'metadata fault'); END""",
        )
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(
                NotificationReplyStagingStore.Failure.STORAGE_UNAVAILABLE,
            ),
            store.stage(post, payload, "conflict"),
        )
        assertEquals(listOf("first"), inventory().map { it.exactText })
        assertEquals(first.identity, inventory().single().identity)
        exec("DROP TRIGGER reject_update")
        val conflict = store.stage(post, payload, "conflict") as
            NotificationReplyStagingStore.StageResult.ConflictRetained
        assertEquals(2L, conflict.identity.sequence)
        assertEquals(listOf("first", "conflict"), inventory().map { it.exactText })
    }

    @Test
    fun ignoredRecordInsertRollsBackMetadataAndNeverReportsRetention() = runTest {
        store.stage(post, payload, "first")
        exec(
            """CREATE TRIGGER ignore_reply BEFORE INSERT ON reply_records
               BEGIN SELECT RAISE(IGNORE); END""",
        )
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.stage(post, payload, "not retained"),
        )
        assertEquals(listOf("first"), inventory().map { it.exactText })
        assertEquals(1L, sqlLong("SELECT record_count FROM reply_metadata"))
        exec("DROP TRIGGER ignore_reply")
        val retry = store.stage(post, payload, "not retained") as
            NotificationReplyStagingStore.StageResult.ConflictRetained
        assertEquals(2L, retry.identity.sequence)
    }

    @Test
    fun ignoredMetadataUpdateRollsBackInsertedTextAndNeverReportsRetention() = runTest {
        store.stage(post, payload, "first")
        exec(
            """CREATE TRIGGER ignore_metadata BEFORE UPDATE ON reply_metadata
               BEGIN SELECT RAISE(IGNORE); END""",
        )
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.stage(post, payload, "not retained"),
        )
        assertEquals(listOf("first"), inventory().map { it.exactText })
        assertEquals(1L, sqlLong("SELECT count(*) FROM reply_records"))
        exec("DROP TRIGGER ignore_metadata")
        val retry = store.stage(post, payload, "not retained") as
            NotificationReplyStagingStore.StageResult.ConflictRetained
        assertEquals(2L, retry.identity.sequence)
    }

    @Test
    fun ignoredRecordAndMetadataTogetherCannotMasqueradeAsSuccessfulNoOp() = runTest {
        exec(
            """CREATE TRIGGER ignore_reply BEFORE INSERT ON reply_records
               BEGIN SELECT RAISE(IGNORE); END""",
        )
        exec(
            """CREATE TRIGGER ignore_metadata BEFORE UPDATE ON reply_metadata
               BEGIN SELECT RAISE(IGNORE); END""",
        )
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.stage(post, payload, "not retained"),
        )
        assertTrue(inventory().isEmpty())
        assertEquals(0L, sqlLong("SELECT record_count FROM reply_metadata"))
    }

    @Test
    fun invalidPostKindVersionPayloadAndEmptyTextNeverRetainAnything() = runTest {
        listOf(post.copy(version = 0), post.copy(version = 2), post.copy(action = "OPEN"),
            post.copy(action = "reply")).forEach {
            assertEquals(
                NotificationReplyStagingStore.StageResult.Rejected(
                    NotificationReplyStagingStore.Failure.INVALID_IDENTITY,
                ),
                store.stage(it, payload, "text"),
            )
        }
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.INVALID_PAYLOAD),
            store.stage(post, NotificationPayload.NewContact(contact), "text"),
        )
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.INVALID_TEXT),
            store.stage(post, payload, ""),
        )
        assertTrue(inventory().isEmpty())
        assertTrue(store.stage(post, payload, " \t\n") is NotificationReplyStagingStore.StageResult.Staged)
    }

    @Test
    fun changedLimitsCannotSilentlyReinterpretPrivateDataPolicy() = runTest {
        store.stage(post, payload, "retained")
        val changed = NotificationReplyStagingStore(file, limits.copy(maxRecords = 21))
        assertEquals(
            NotificationReplyStagingStore.ReadResult.Failed(NotificationReplyStagingStore.Failure.LIMITS_MISMATCH),
            changed.read(),
        )
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.LIMITS_MISMATCH),
            changed.stage(post, payload, "retained"),
        )
        assertEquals("retained", inventory().single().exactText)
        val invalid = NotificationReplyStagingStore(file, limits.copy(maxRecordBytes = 0))
        assertEquals(
            NotificationReplyStagingStore.InitializeResult.Failed(NotificationReplyStagingStore.Failure.INVALID_LIMITS),
            invalid.initializeNew(),
        )
    }

    @Test
    fun absentStoreIsNeverRecreatedByOrdinaryReadOrStage() = runTest {
        store.stage(post, payload, "lost")
        cleanup()
        assertEquals(
            NotificationReplyStagingStore.ReadResult.Failed(NotificationReplyStagingStore.Failure.MISSING_STORAGE),
            store.read(),
        )
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.MISSING_STORAGE),
            store.stage(post, payload, "lost"),
        )
        assertFalse(file.exists())
    }

    @Test
    fun corruptDatabaseIsPreservedNotReplacedByReadStageOrBootstrap() = runTest {
        cleanup()
        val original = "not sqlite private content".toByteArray()
        file.writeBytes(original)
        assertEquals(
            NotificationReplyStagingStore.ReadResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.read(),
        )
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.stage(post, payload, "attempt"),
        )
        assertEquals(
            NotificationReplyStagingStore.InitializeResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.initializeNew(),
        )
        assertEquals(original.toList(), file.readBytes().toList())
    }

    @Test
    fun retainedRowGapsAndChangedBytesAreReportedAsCorruptNotEmptyOrReady() = runTest {
        store.stage(post, payload, "primary")
        store.stage(post, payload, "conflict")
        exec("UPDATE reply_records SET reply_text = X'0061' WHERE sequence=2")
        assertEquals(
            NotificationReplyStagingStore.ReadResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.read(),
        )
        exec("DELETE FROM reply_records WHERE sequence=2")
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.stage(post, payload, "primary"),
        )
    }

    @Test
    fun unknownSchemaAndVersionAreNotBootstrappedOrErased() = runTest {
        cleanup()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { it.execSQL("CREATE TABLE foreign_data(value TEXT)") }
        assertEquals(
            NotificationReplyStagingStore.InitializeResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.initializeNew(),
        )
        assertEquals(1L, sqlLong("SELECT count(*) FROM sqlite_master WHERE name='foreign_data'"))
        cleanup()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { it.version = 2 }
        assertEquals(
            NotificationReplyStagingStore.InitializeResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.initializeNew(),
        )
    }

    @Test
    fun unsupportedColumnLayoutAndNonNumericMetadataAreExplicitCorruption() = runTest {
        exec("UPDATE reply_metadata SET record_count='not-a-count'")
        assertEquals(
            NotificationReplyStagingStore.ReadResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.read(),
        )
        exec("UPDATE reply_metadata SET record_count=0")
        exec("ALTER TABLE reply_records RENAME COLUMN reply_text TO unknown_text")
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.stage(post, payload, "not retained"),
        )
    }

    @Test
    fun storedVersionCannotMasqueradeAsSupportedVersionThroughIntegerNarrowing() = runTest {
        store.stage(post, payload, "retained")
        listOf(4_294_967_297L, -4_294_967_295L, Long.MAX_VALUE, Long.MIN_VALUE).forEach { version ->
            exec("UPDATE reply_records SET version=$version WHERE sequence=1")
            assertEquals(
                NotificationReplyStagingStore.ReadResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
                store.read(),
            )
            assertEquals(
                NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
                store.stage(post, payload, "retained"),
            )
        }
        exec("UPDATE reply_records SET version=1 WHERE sequence=1")
        assertEquals("retained", inventory().single().exactText)
    }

    @Test
    fun storedCapacityCannotMasqueradeAsConfiguredPolicyThroughIntegerNarrowing() = runTest {
        store.stage(post, payload, "retained")
        listOf(4_294_967_296L + limits.maxRecords, -4_294_967_296L + limits.maxRecords,
            Long.MAX_VALUE, Long.MIN_VALUE).forEach { capacity ->
            exec("UPDATE reply_metadata SET max_records=$capacity")
            assertEquals(
                NotificationReplyStagingStore.ReadResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
                store.read(),
            )
            assertEquals(
                NotificationReplyStagingStore.InitializeResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
                store.initializeNew(),
            )
        }
        exec("UPDATE reply_metadata SET max_records=${limits.maxRecords}")
        assertEquals("retained", inventory().single().exactText)
    }

    @Test
    fun storeIdentityMutationCannotRebindPreviouslyRetainedRecords() = runTest {
        store.stage(post, payload, "retained")
        val originalId = inventory().single().identity.storeId
        exec("UPDATE reply_metadata SET store_id='${UUID.randomUUID()}'")
        assertEquals(
            NotificationReplyStagingStore.ReadResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.read(),
        )
        exec("UPDATE reply_metadata SET store_id='$originalId'")
        assertEquals(originalId, inventory().single().identity.storeId)
    }

    @Test
    fun onlyExplicitFreshBootstrapCanRetryRolledBackInitialization() = runTest {
        cleanup()
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.beginTransaction()
            try {
                it.execSQL("CREATE TABLE interrupted(value TEXT)")
            } finally {
                it.endTransaction()
            }
        }
        assertEquals(
            NotificationReplyStagingStore.ReadResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.read(),
        )
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeNew())
        assertTrue(store.stage(post, payload, "retained") is NotificationReplyStagingStore.StageResult.Staged)
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeNew())
        assertEquals("retained", inventory().single().exactText)
    }

    @Test
    fun foreignViewIsNotEvidenceOfFreshInstall() = runTest {
        cleanup()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { it.execSQL("CREATE VIEW foreign_view AS SELECT 1") }
        assertEquals(
            NotificationReplyStagingStore.InitializeResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.initializeNew(),
        )
        assertEquals(1L, sqlLong("SELECT count(*) FROM sqlite_master WHERE name='foreign_view'"))
    }

    @Test
    fun sqliteWildcardLookalikeForeignTableIsNotEvidenceOfFreshInstall() = runTest {
        cleanup()
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.execSQL("CREATE TABLE sqliteX_user_data(value TEXT)")
            it.execSQL("INSERT INTO sqliteX_user_data VALUES('preserve')")
        }
        assertEquals(
            NotificationReplyStagingStore.InitializeResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.initializeNew(),
        )
        assertEquals(0L, sqlLong("PRAGMA user_version"))
        assertEquals(0L, sqlLong("SELECT count(*) FROM sqlite_master WHERE name='reply_metadata'"))
        assertEquals(1L, sqlLong("SELECT count(*) FROM sqliteX_user_data WHERE value='preserve'"))
    }

    @Test
    fun sqliteWildcardLookalikeForeignViewIsNotEvidenceOfFreshInstall() = runTest {
        cleanup()
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.execSQL("CREATE VIEW sqliteX_foreign_view AS SELECT 7 AS value")
        }
        assertEquals(
            NotificationReplyStagingStore.InitializeResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
            store.initializeNew(),
        )
        assertEquals(0L, sqlLong("PRAGMA user_version"))
        assertEquals(0L, sqlLong("SELECT count(*) FROM sqlite_master WHERE name='reply_metadata'"))
        assertEquals(7L, sqlLong("SELECT value FROM sqliteX_foreign_view"))
    }

    @Test
    fun sqliteWildcardLookalikeTableIsCorruptionInBothInitializedJournalVersions() = runTest {
        store.stage(post, payload, "retained")
        val original = inventory().single().identity
        listOf(1L, 2L).forEach { version ->
            if (version == 2L) {
                assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeLifecycle(100_000))
            }
            exec("CREATE TABLE sqliteX_user_data(value TEXT)")
            exec("INSERT INTO sqliteX_user_data VALUES('preserve')")
            assertEquals(
                NotificationReplyStagingStore.ReadResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
                store.read(),
            )
            assertEquals(
                NotificationReplyStagingStore.StageResult.Rejected(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
                store.stage(post, payload, "not retained"),
            )
            assertEquals(
                NotificationReplyStagingStore.InitializeResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
                store.initializeNew(),
            )
            assertEquals(
                NotificationReplyStagingStore.InitializeResult.Failed(NotificationReplyStagingStore.Failure.CORRUPT_STORAGE),
                store.initializeLifecycle(100_000),
            )
            assertEquals(version, sqlLong("PRAGMA user_version"))
            assertEquals(1L, sqlLong("SELECT count(*) FROM sqliteX_user_data WHERE value='preserve'"))
            assertEquals(1L, sqlLong("SELECT count(*) FROM reply_records"))
            exec("DROP TABLE sqliteX_user_data")
            val retained = inventory().single()
            assertEquals(original, retained.identity)
            assertEquals("retained", retained.exactText)
        }
    }

    @Test
    fun literalSqliteInternalAndAndroidMetadataExclusionsStillPermitBootstrapAndReopen() = runTest {
        cleanup()
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.execSQL("CREATE TABLE IF NOT EXISTS android_metadata(locale TEXT)")
            it.execSQL("CREATE TABLE temporary_sequence(id INTEGER PRIMARY KEY AUTOINCREMENT)")
            it.execSQL("DROP TABLE temporary_sequence")
        }
        assertEquals(1L, sqlLong("SELECT count(*) FROM sqlite_master WHERE name='sqlite_sequence'"))
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeNew())
        assertTrue(store.stage(post, payload, "retained") is NotificationReplyStagingStore.StageResult.Staged)
        assertEquals(NotificationReplyStagingStore.InitializeResult.Ready, store.initializeLifecycle(100_000))
        assertEquals("retained", inventory().single().exactText)
    }

    @Test
    fun unavailableStorageNeverReportsRetention() = runTest {
        cleanup()
        check(file.mkdir())
        assertEquals(
            NotificationReplyStagingStore.StageResult.Rejected(
                NotificationReplyStagingStore.Failure.STORAGE_UNAVAILABLE,
            ),
            store.stage(post, payload, "not retained"),
        )
    }

    private suspend fun inventory(): List<NotificationReplyStagingStore.RecoveryEntry> =
        (store.read() as NotificationReplyStagingStore.ReadResult.Inventory).entries

    private fun exec(sql: String) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(sql) }
    }

    private fun sqlLong(sql: String): Long =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.compileStatement(sql).use { it.simpleQueryForLong() }
        }
}
