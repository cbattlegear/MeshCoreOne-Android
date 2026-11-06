// AndroidOnly: WP-202 Real Room construction, message lineage and unproven-safe reaction controls.
package com.meshcoreone.android.core.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.data.backup.AppBackupService
import com.meshcoreone.android.core.data.backup.BackupModelKind
import com.meshcoreone.android.core.data.backup.envelope
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelMessage
import java.time.Instant
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ChannelReactionTargetAuthorityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "channel-reaction-authority.db"
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var db: MeshCoreDatabase
    private lateinit var store: RoomPersistenceStore

    @Before fun openStore() {
        context.deleteDatabase(name)
        db = MeshCoreDatabase.open(context, name)
        store = RoomPersistenceStore(db, owner)
    }

    @After fun closeStore() {
        runBlocking { store.close() }
        owner.cancel()
        db.close()
        context.deleteDatabase(name)
    }

    @Test fun rawInMemoryBuilderInstallsTriggersBeforeCallerCallbacksAndPreservesConfiguration() = runTest {
        var creates = 0
        var opens = 0
        val directExecutor = java.util.concurrent.Executor { it.run() }
        val raw = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(database: SupportSQLiteDatabase) {
                    database.query(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'channel_reaction_identity_%'",
                    ).use {
                        assertTrue(it.moveToFirst())
                        assertEquals(3, it.getInt(0))
                    }
                    creates++
                }

                override fun onOpen(database: SupportSQLiteDatabase) {
                    opens++
                }
            })
            .build()
        try {
            val original = incoming().toEntity()
            raw.messages().insert(original)
            val identity = assertNotNull(raw.channelReactionIdentities().forMessage(original.radioId, original.id))
            assertEquals(1, creates)
            assertEquals(1, opens)
            assertSame(directExecutor, raw.queryExecutor)
            raw.messages().upsert(original)
            val updated = assertNotNull(raw.channelReactionIdentities().forMessage(original.radioId, original.id))
            assertEquals(identity.incarnation, updated.incarnation)
            assertEquals(identity.revision + 1, updated.revision)
            raw.openHelper.writableDatabase.execSQL(
                "UPDATE messages SET text = text WHERE radioId = ? AND id = ?",
                arrayOf(RADIO_A.canonicalString, original.id.toString()),
            )
            assertEquals(
                updated.revision + 1,
                assertNotNull(raw.channelReactionIdentities().forMessage(original.radioId, original.id)).revision,
            )
            raw.messages().delete(original.radioId, original.id)
            assertNull(raw.channelReactionIdentities().forMessage(original.radioId, original.id))
            raw.messages().insert(original)
            assertNotEquals(
                identity.incarnation,
                assertNotNull(raw.channelReactionIdentities().forMessage(original.radioId, original.id)).incarnation,
            )
        } finally {
            raw.close()
        }
    }

    @Test fun rawFileBuilderReopenPreservesIdentityAndStillInvalidatesEqualUpserts() = runTest {
        val fileName = "channel-reaction-raw-builder.db"
        context.deleteDatabase(fileName)
        val original = incoming().toEntity()
        val first = rawFileDatabase(fileName)
        val identity = try {
            first.messages().insert(original)
            assertNotNull(first.channelReactionIdentities().forMessage(original.radioId, original.id))
        } finally {
            first.close()
        }
        val reopened = rawFileDatabase(fileName)
        try {
            assertEquals(identity, reopened.channelReactionIdentities().forMessage(original.radioId, original.id))
            reopened.messages().upsert(original)
            val updated = assertNotNull(reopened.channelReactionIdentities().forMessage(original.radioId, original.id))
            assertEquals(identity.incarnation, updated.incarnation)
            assertEquals(identity.revision + 1, updated.revision)
        } finally {
            reopened.close()
            context.deleteDatabase(fileName)
        }
    }

    @Test fun rawBuilderReopenRejectsMissingOrSameNamedNoOpTriggersWithoutRepair() = runTest {
        for (replacement in listOf<String?>(
            null,
            """
            CREATE TRIGGER channel_reaction_identity_update AFTER UPDATE ON messages
            BEGIN SELECT 1; END
            """.trimIndent(),
        )) {
            val fileName = "channel-reaction-broken-trigger.db"
            context.deleteDatabase(fileName)
            val original = incoming().toEntity()
            val first = rawFileDatabase(fileName)
            try {
                first.messages().insert(original)
                first.openHelper.writableDatabase.execSQL("DROP TRIGGER channel_reaction_identity_update")
                if (replacement != null) first.openHelper.writableDatabase.execSQL(replacement)
            } finally {
                first.close()
            }
            val reopened = rawFileDatabase(fileName)
            try {
                val failure = assertFails { reopened.openHelper.writableDatabase }
                assertTrue(
                    generateSequence(failure) { it.cause }.any {
                        it.message == "Channel reaction identity triggers are missing or incompatible"
                    },
                )
            } finally {
                reopened.close()
                context.deleteDatabase(fileName)
            }
        }
    }

    @Test fun callerOpenCallbackCannotLeaveTheDatabaseOperationalWithMissingTriggers() {
        val raw = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(database: SupportSQLiteDatabase) {
                    database.execSQL("DROP TRIGGER channel_reaction_identity_update")
                }
            })
            .build()
        try {
            val failure = assertFails { raw.openHelper.writableDatabase }
            assertTrue(
                generateSequence(failure) { it.cause }.any {
                    it.message == "Channel reaction identity triggers are missing or incompatible"
                },
            )
        } finally {
            raw.close()
        }
    }

    @Test fun missingTriggerOnReopenPreventsCallerOpenMutationAndRemainsUnrepaired() = runTest {
        val fileName = "channel-reaction-precheck.db"
        context.deleteDatabase(fileName)
        val original = incoming().toEntity()
        val first = rawFileDatabase(fileName)
        val identity = try {
            first.messages().insert(original)
            val stored = assertNotNull(first.channelReactionIdentities().forMessage(original.radioId, original.id))
            first.openHelper.writableDatabase.execSQL("DROP TRIGGER channel_reaction_identity_update")
            stored
        } finally {
            first.close()
        }
        var callerOpens = 0
        val reopened = Room.databaseBuilder(context, MeshCoreDatabase::class.java, fileName)
            .allowMainThreadQueries()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(database: SupportSQLiteDatabase) {
                    callerOpens++
                    database.execSQL(
                        "UPDATE messages SET text = 'must not commit' WHERE radioId = ? AND id = ?",
                        arrayOf(RADIO_A.canonicalString, original.id.toString()),
                    )
                }
            })
            .build()
        try {
            try {
                val failure = assertFails { reopened.openHelper.writableDatabase }
                assertTrue(
                    generateSequence(failure) { it.cause }.any {
                        it.message == "Channel reaction identity triggers are missing or incompatible"
                    },
                )
                assertEquals(0, callerOpens)
            } finally {
                reopened.close()
            }
            val sqlite = SQLiteDatabase.openDatabase(
                context.getDatabasePath(fileName).path, null, SQLiteDatabase.OPEN_READONLY,
            )
            try {
                sqlite.rawQuery(
                    """
                    SELECT m.text, i.incarnation, i.revision FROM messages m
                    JOIN channel_reaction_identity i ON i.radioId = m.radioId AND i.messageID = m.id
                    WHERE m.radioId = ? AND m.id = ?
                    """.trimIndent(),
                    arrayOf(RADIO_A.canonicalString, original.id.toString()),
                ).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(original.text, it.getString(0))
                    assertEquals(identity.incarnation, it.getString(1))
                    assertEquals(identity.revision, it.getLong(2))
                    assertFalse(it.moveToNext())
                }
                sqlite.rawQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name = 'channel_reaction_identity_update'",
                    null,
                ).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
            } finally {
                sqlite.close()
            }
        } finally {
            context.deleteDatabase(fileName)
        }
    }

    @Test fun conflictingCallerMigrationIsRejectedWithoutReplacingItsImplementation() {
        val conflicting = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                error("Conflicting migration must never execute")
            }
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
                .addMigrations(conflicting)
                .build()
        }
        assertEquals("MeshCoreDatabase cannot replace a different migration from version 1 to 2", failure.message)
    }

    @Test fun sqliteDriverBuilderIsRejectedBeforeOpeningAnUnprotectedConnection() {
        var opens = 0
        val driver = object : androidx.sqlite.SQLiteDriver {
            override fun open(fileName: String): androidx.sqlite.SQLiteConnection {
                opens++
                error("Unsupported driver must never open")
            }
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
                .setDriver(driver)
                .build()
        }
        assertEquals(
            "MeshCoreDatabase requires SupportSQLiteOpenHelper for durable identity triggers",
            failure.message,
        )
        assertEquals(0, opens)
    }

    @Test fun failedIdentityMutationRollsBackTheMessageAndRevisionTogether() = runTest {
        val original = incoming().toEntity()
        db.messages().insert(original)
        val identity = assertNotNull(db.channelReactionIdentities().forMessage(original.radioId, original.id))
        val failure = IOException("controlled identity rollback")

        val actual = assertFailsWith<IOException> {
            db.withTransaction {
                db.messages().upsert(original.copy(text = "rolled back text"))
                val changed = assertNotNull(db.channelReactionIdentities().forMessage(original.radioId, original.id))
                assertEquals(identity.incarnation, changed.incarnation)
                assertEquals(identity.revision + 1, changed.revision)
                throw failure
            }
        }

        assertSame(failure, actual)
        assertEquals(original, db.messages().byId(original.radioId, original.id))
        assertEquals(identity, db.channelReactionIdentities().forMessage(original.radioId, original.id))
    }

    @Test fun cancellationExceptionDuringDeleteRecreateRollsBackTheOriginalIncarnation() = runTest {
        val original = incoming().toEntity()
        db.messages().insert(original)
        val identity = assertNotNull(db.channelReactionIdentities().forMessage(original.radioId, original.id))
        val cancellation = CancellationException("controlled identity cancellation")

        val actual = assertFailsWith<CancellationException> {
            db.withTransaction {
                assertEquals(1, db.messages().delete(original.radioId, original.id))
                assertNull(db.channelReactionIdentities().forMessage(original.radioId, original.id))
                db.messages().insert(original)
                val recreated = assertNotNull(
                    db.channelReactionIdentities().forMessage(original.radioId, original.id),
                )
                assertNotEquals(identity.incarnation, recreated.incarnation)
                assertEquals(0L, recreated.revision)
                throw cancellation
            }
        }

        assertSame(cancellation, actual)
        assertEquals(original, db.messages().byId(original.radioId, original.id))
        assertEquals(identity, db.channelReactionIdentities().forMessage(original.radioId, original.id))
    }

    @Test fun revisionOverflowRotatesIncarnationInsteadOfReusingAnEarlierRevision() = runTest {
        val original = incoming().toEntity()
        db.messages().insert(original)
        val identity = assertNotNull(db.channelReactionIdentities().forMessage(original.radioId, original.id))
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE channel_reaction_identity SET revision = ? WHERE radioId = ? AND messageID = ?",
                arrayOf(Long.MAX_VALUE, RADIO_A.canonicalString, original.id.toString()),
            )
            assertEquals(
                Long.MAX_VALUE,
                assertNotNull(db.channelReactionIdentities().forMessage(original.radioId, original.id)).revision,
            )
            db.openHelper.writableDatabase.execSQL(
                "UPDATE messages SET text = text WHERE radioId = ? AND id = ?",
                arrayOf(RADIO_A.canonicalString, original.id.toString()),
            )
        }

        val rotated = assertNotNull(db.channelReactionIdentities().forMessage(original.radioId, original.id))
        assertNotEquals(identity.incarnation, rotated.incarnation)
        assertEquals(0L, rotated.revision)
        assertEquals(original, db.messages().byId(original.radioId, original.id))

        db.messages().upsert(original)
        val next = assertNotNull(db.channelReactionIdentities().forMessage(original.radioId, original.id))
        assertEquals(rotated.incarnation, next.incarnation)
        assertEquals(1L, next.revision)
    }

    @Test fun actualCallerCancellationRollsBackTheMessageAndRevisionTogether() = runTest {
        val original = incoming().toEntity()
        db.messages().insert(original)
        val identity = assertNotNull(db.channelReactionIdentities().forMessage(original.radioId, original.id))
        val mutated = CompletableDeferred<Unit>()
        val caller = launch {
            db.withTransaction {
                db.messages().upsert(original.copy(text = "cancelled text"))
                val changed = assertNotNull(db.channelReactionIdentities().forMessage(original.radioId, original.id))
                assertEquals(identity.incarnation, changed.incarnation)
                assertEquals(identity.revision + 1, changed.revision)
                mutated.complete(Unit)
                awaitCancellation()
            }
        }

        try {
            mutated.await()
        } finally {
            caller.cancelAndJoin()
        }

        assertTrue(caller.isCancelled)
        assertEquals(original, db.messages().byId(original.radioId, original.id))
        assertEquals(identity, db.channelReactionIdentities().forMessage(original.radioId, original.id))
    }

    @Test fun targetedHarvestReadStatusAndSummaryWritesPreserveIdentityWithoutMintingATarget() = runTest {
        val original = incoming()
        val inserted = assertIs<ChannelReactionIngressResult.InsertedUnproven>(
            store.channelReactionTargetAuthority.saveIncomingChannelMessageWithReactionTarget(original, occurrence(original)),
        )
        assertEquals(original, inserted.message)
        val identity = assertNotNull(db.channelReactionIdentities().forMessage(RADIO_A.value, original.id))
        val key = EntityKey(RADIO_A, original.id)

        assertTrue(store.adoptIncomingPathIfUnknown(key, com.meshcoreone.android.core.protocol.bytes.Bytes.of(0xAA), 1u))
        store.incrementMessageHeardRepeats(key)
        store.updateMessageHeardRepeats(key, 3)
        store.markMessageAsRead(key)
        store.updateMessageStatus(key, MessageStatus.DELIVERED)
        store.updateMessageReactionSummary(key, "prior:1")

        assertEquals(identity, db.channelReactionIdentities().forMessage(RADIO_A.value, original.id))
        val actual = assertNotNull(store.fetchMessage(key))
        assertTrue(actual.isRead)
        assertEquals(3L, actual.heardRepeats)
        assertEquals(MessageStatus.DELIVERED, actual.status)
        assertEquals("prior:1", actual.reactionSummary)
        val history = assertIs<UnprovenChannelReactionMessage>(
            store.channelReactionTargetAuthority.fetchChannelReactionWindow(RADIO_A, 1u, 10, 0).single(),
        )
        assertEquals(actual, history.message)
        assertEquals(ChannelReactionUnprovenReason.MISSING_ORIGIN, history.reason)
        assertTrue(store.fetchReactions(key, 100).isEmpty())
    }

    @Test fun equalGenericUpsertInvalidatesEvenWhenTheDtoIsUnchanged() = runTest {
        val original = incoming()
        val inserted = assertIs<ChannelReactionIngressResult.InsertedUnproven>(
            store.channelReactionTargetAuthority.saveIncomingChannelMessageWithReactionTarget(original, occurrence(original)),
        )
        assertEquals(original, inserted.message)
        val initialIdentity = assertNotNull(
            db.channelReactionIdentities().forMessage(RADIO_A.value, original.id),
        )
        val existing = store.channelReactionTargetAuthority.saveIncomingChannelMessageWithReactionTarget(
            original, occurrence(original),
        )
        assertIs<ChannelReactionIngressResult.Existing>(existing)
        assertEquals(EntityKey(RADIO_A, original.id), existing.key)
        val afterIngressUpsert = assertNotNull(
            db.channelReactionIdentities().forMessage(RADIO_A.value, original.id),
        )
        assertEquals(initialIdentity.incarnation, afterIngressUpsert.incarnation)
        assertEquals(initialIdentity.revision + 1, afterIngressUpsert.revision)
        assertIs<UnprovenChannelReactionMessage>(
            store.channelReactionTargetAuthority.fetchChannelReactionWindow(RADIO_A, 1u, 10, 0).single(),
        )
        val beforeDaoUpsert = assertNotNull(
            db.channelReactionIdentities().forMessage(RADIO_A.value, original.id),
        )
        db.messages().upsert(original.toEntity())
        val afterDaoUpsert = assertNotNull(
            db.channelReactionIdentities().forMessage(RADIO_A.value, original.id),
        )
        assertEquals(beforeDaoUpsert.incarnation, afterDaoUpsert.incarnation)
        assertEquals(beforeDaoUpsert.revision + 1, afterDaoUpsert.revision)
        store.saveMessage(original)
        val beforeRepositoryUpsert = assertNotNull(
            db.channelReactionIdentities().forMessage(RADIO_A.value, original.id),
        )
        store.saveMessage(original)
        val afterRepositoryReupsert = assertNotNull(
            db.channelReactionIdentities().forMessage(RADIO_A.value, original.id),
        )
        assertEquals(beforeRepositoryUpsert.incarnation, afterRepositoryReupsert.incarnation)
        assertEquals(beforeRepositoryUpsert.revision + 1, afterRepositoryReupsert.revision)
        assertIs<UnprovenChannelReactionMessage>(
            store.channelReactionTargetAuthority.fetchChannelReactionWindow(RADIO_A, 1u, 10, 0).single(),
        )
        assertTrue(store.fetchReactions(EntityKey(RADIO_A, original.id), 100).isEmpty())
    }

    @Test fun correctedSenderTimestampRawSqlIdentityWriteAndDeleteRecreateInvalidate() = runTest {
        val original = incoming()
        store.saveMessage(original)
        val originalIdentity = assertNotNull(
            db.channelReactionIdentities().forMessage(RADIO_A.value, original.id),
        )
        store.saveMessage(original.copy(senderTimestamp = 1_700_000_123u))
        val correctedIdentity = assertNotNull(
            db.channelReactionIdentities().forMessage(RADIO_A.value, original.id),
        )
        assertEquals(originalIdentity.incarnation, correctedIdentity.incarnation)
        assertEquals(originalIdentity.revision + 1, correctedIdentity.revision)
        val corrected = assertNotNull(store.fetchMessage(EntityKey(RADIO_A, original.id)))
        assertEquals(1_700_000_123u, corrected.senderTimestamp)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE messages SET text = text WHERE radioId = ? AND id = ?",
            arrayOf(RADIO_A.canonicalString, original.id.toString()),
        )
        val rawWriteIdentity = assertNotNull(
            db.channelReactionIdentities().forMessage(RADIO_A.value, original.id),
        )
        assertEquals(correctedIdentity.incarnation, rawWriteIdentity.incarnation)
        assertEquals(correctedIdentity.revision + 1, rawWriteIdentity.revision)
        store.deleteMessage(EntityKey(RADIO_A, original.id))
        assertNull(db.channelReactionIdentities().forMessage(RADIO_A.value, original.id))
        store.saveMessage(corrected)
        val recreatedIdentity = assertNotNull(
            db.channelReactionIdentities().forMessage(RADIO_A.value, original.id),
        )
        assertNotEquals(rawWriteIdentity.incarnation, recreatedIdentity.incarnation)
        assertEquals(0L, recreatedIdentity.revision)
        val history = assertIs<UnprovenChannelReactionMessage>(
            store.channelReactionTargetAuthority.fetchChannelReactionWindow(RADIO_A, 1u, 10, 0).single(),
        )
        assertEquals(corrected, history.message)
        assertTrue(store.fetchReactions(EntityKey(RADIO_A, original.id), 100).isEmpty())
    }

    @Test fun originalHistoryOrderAndAnchoredHasMoreMatchTheMessageRepository() = runTest {
        val messages = (0..3).map { offset ->
            incoming(id = UUID.randomUUID(), timestamp = 100u + offset.toUInt(), createdAt = AT.plusSeconds(offset.toLong()))
        }
        messages.forEach { store.saveMessage(it) }
        val offsetWindow = store.channelReactionTargetAuthority.fetchChannelReactionWindow(RADIO_A, 1u, 2, 1)
        assertEquals(
            store.fetchMessages(RADIO_A, 1u, 2, 1),
            offsetWindow.map { it.message },
        )
        assertEquals(2, offsetWindow.size)
        offsetWindow.forEach { assertIs<UnprovenChannelReactionMessage>(it) }

        val anchored = store.channelReactionTargetAuthority.fetchChannelReactionWindow(
            RADIO_A, 1u, AT.plusSeconds(2), 1,
        )
        assertTrue(anchored.hasMore)
        assertEquals(
            store.fetchMessageWindow(RADIO_A, 1u, AT.plusSeconds(2), 1).messages,
            anchored.messages.map { it.message },
        )
        anchored.messages.forEach { assertIs<UnprovenChannelReactionMessage>(it) }
    }

    @Test fun identityAndVisibleUnprovenHistorySurviveStoreAndRoomHandleReopen() = runTest {
        val original = incoming()
        assertIs<ChannelReactionIngressResult.InsertedUnproven>(
            store.channelReactionTargetAuthority.saveIncomingChannelMessageWithReactionTarget(original, occurrence(original)),
        )
        val identity = assertNotNull(db.channelReactionIdentities().forMessage(RADIO_A.value, original.id))
        store.close()
        db.close()

        db = MeshCoreDatabase.open(context, name)
        store = RoomPersistenceStore(db, owner)
        assertEquals(identity, db.channelReactionIdentities().forMessage(RADIO_A.value, original.id))
        val history = assertIs<UnprovenChannelReactionMessage>(
            store.channelReactionTargetAuthority.fetchChannelReactionWindow(RADIO_A, 1u, 10, 0).single(),
        )
        assertEquals(original, history.message)
        assertEquals(ChannelReactionUnprovenReason.MISSING_ORIGIN, history.reason)
    }

    @Test fun genericUpsertFromASeparateRoomHandleInvalidatesIdentityWithoutGrantingAuthority() = runTest {
        val original = incoming()
        store.saveMessage(original)
        val identity = assertNotNull(db.channelReactionIdentities().forMessage(RADIO_A.value, original.id))
        val otherOwner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val otherDb = MeshCoreDatabase.open(context, name)
        val otherStore = RoomPersistenceStore(otherDb, otherOwner)
        try {
            otherStore.saveMessage(original)
            val updated = assertNotNull(db.channelReactionIdentities().forMessage(RADIO_A.value, original.id))
            assertEquals(identity.incarnation, updated.incarnation)
            assertEquals(identity.revision + 1, updated.revision)
            val history = assertIs<UnprovenChannelReactionMessage>(
                store.channelReactionTargetAuthority.fetchChannelReactionWindow(RADIO_A, 1u, 10, 0).single(),
            )
            assertEquals(original, history.message)
            assertTrue(store.fetchReactions(EntityKey(RADIO_A, original.id), 100).isEmpty())
        } finally {
            otherStore.close()
            otherOwner.cancel()
            otherDb.close()
        }
    }

    @Test fun everyUnprovenReasonCommitsOrdinaryIngressButCannotSelectOrApplyAReaction() = runTest {
        for (reason in ChannelReactionUnprovenReason.entries) {
            val original = incoming().copy(reactionSummary = "prior:1")
            val unproven = occurrence(original, reason)
            val result = assertIs<ChannelReactionIngressResult.InsertedUnproven>(
                store.channelReactionTargetAuthority.saveIncomingChannelMessageWithReactionTarget(original, unproven),
            )
            assertEquals(original, result.message)
            assertEquals(reason, result.reason)
            val key = EntityKey(RADIO_A, original.id)
            val savedReaction = reaction(original).copy(senderName = "EarlierPeer")
            store.saveReaction(savedReaction)
            val identity = assertNotNull(db.channelReactionIdentities().forMessage(RADIO_A.value, original.id))
            val parsed = ParsedReaction("x", "Peer", savedReaction.messageHash)
            val selected = assertIs<ChannelReactionSelectionResult.Unproven>(
                store.channelReactionTargetAuthority.findChannelReactionTarget(
                    unproven, parsed, null, 0u..UInt.MAX_VALUE, 100,
                ),
            )
            assertEquals(reason, selected.reason)
            val candidates = assertIs<ChannelReactionSelectionResult.Unproven>(
                store.channelReactionTargetAuthority.fetchChannelReactionTargets(unproven, 0u..UInt.MAX_VALUE, 100),
            )
            assertEquals(reason, candidates.reason)
            val applied = assertIs<ChannelReactionApplyResult.Unproven>(
                store.channelReactionTargetAuthority.applyChannelReaction(ForeignTarget, reaction(original), unproven),
            )
            assertEquals(reason, applied.reason)
            assertEquals(original, store.fetchMessage(key))
            assertEquals(listOf(savedReaction), store.fetchReactions(key, 100))
            assertEquals(identity, db.channelReactionIdentities().forMessage(RADIO_A.value, original.id))
        }
    }

    @Test fun unprovenIngressReturnsActualStoredNormalizationAndPreservesOrdinaryUpsertBehavior() = runTest {
        val original = incoming(timestamp = 0u).copy(
            linkPreviewURL = "https://example.com",
            linkPreviewImageData = Bytes.of(1, 2),
            linkPreviewIconData = Bytes.of(3),
            linkPreviewFetched = true,
        )
        val inserted = assertIs<ChannelReactionIngressResult.InsertedUnproven>(
            store.channelReactionTargetAuthority.saveIncomingChannelMessageWithReactionTarget(original, occurrence(original)),
        )
        val normalized = original.copy(
            timestamp = original.createdAt.epochSecond.toUInt(),
            linkPreviewImageData = null,
            linkPreviewIconData = null,
            linkPreviewFetched = false,
        )
        assertEquals(normalized, inserted.message)
        assertEquals(normalized, store.fetchMessage(EntityKey(RADIO_A, original.id)))

        val replacement = original.copy(text = "replacement ordinary message")
        val existing = assertIs<ChannelReactionIngressResult.Existing>(
            store.channelReactionTargetAuthority.saveIncomingChannelMessageWithReactionTarget(
                replacement, occurrence(replacement),
            ),
        )
        assertEquals(EntityKey(RADIO_A, original.id), existing.key)
        assertEquals(
            normalized.copy(text = replacement.text),
            store.fetchMessage(EntityKey(RADIO_A, original.id)),
        )
        val history = assertIs<UnprovenChannelReactionMessage>(
            store.channelReactionTargetAuthority.fetchChannelReactionWindow(RADIO_A, 1u, 10, 0).single(),
        )
        assertEquals(normalized.copy(text = replacement.text), history.message)
    }

    @Test fun callerProvenMarkersAndEqualCopiedEnvelopesCannotUpgradeAnyAuthoritySurface() = runTest {
        val original = incoming()
        val initial = occurrence(original)
        val envelopes = listOf(
            initial.envelope,
            initial.envelope.copy(),
            initial.envelope.copy(
                session = initial.envelope.session.copy(generation = Generation(2)),
            ),
            initial.envelope.copy(occurrenceID = UUID.randomUUID()),
        )
        for ((index, copied) in envelopes.withIndex()) {
            val forged = object : ChannelReactionOccurrence.Proven {
                override val envelope: ChannelReceiveEnvelope = copied
            }
            if (index == 0) {
                val inserted = assertIs<ChannelReactionIngressResult.InsertedUnproven>(
                    store.channelReactionTargetAuthority.saveIncomingChannelMessageWithReactionTarget(original, forged),
                )
                assertEquals(ChannelReactionUnprovenReason.INVALID_PROOF, inserted.reason)
            } else {
                assertIs<ChannelReactionIngressResult.Existing>(
                    store.channelReactionTargetAuthority.saveIncomingChannelMessageWithReactionTarget(original, forged),
                )
            }
            val selected = assertIs<ChannelReactionSelectionResult.Unproven>(
                store.channelReactionTargetAuthority.findChannelReactionTarget(
                    forged, ParsedReaction("x", "Peer", "hash"), null, 0u..UInt.MAX_VALUE, 100,
                ),
            )
            assertEquals(ChannelReactionUnprovenReason.INVALID_PROOF, selected.reason)
            val candidates = assertIs<ChannelReactionSelectionResult.Unproven>(
                store.channelReactionTargetAuthority.fetchChannelReactionTargets(forged, 0u..UInt.MAX_VALUE, 100),
            )
            assertEquals(ChannelReactionUnprovenReason.INVALID_PROOF, candidates.reason)
            val applied = assertIs<ChannelReactionApplyResult.Unproven>(
                store.channelReactionTargetAuthority.applyChannelReaction(ForeignTarget, reaction(original), forged),
            )
            assertEquals(ChannelReactionUnprovenReason.INVALID_PROOF, applied.reason)
        }
        val history = assertIs<UnprovenChannelReactionMessage>(
            store.channelReactionTargetAuthority.fetchChannelReactionWindow(RADIO_A, 1u, 10, 0).single(),
        )
        assertEquals(original, history.message)
        assertEquals(original, store.fetchMessage(EntityKey(RADIO_A, original.id)))
        assertTrue(store.fetchReactions(EntityKey(RADIO_A, original.id), 100).isEmpty())
    }

    @Test fun malformedIncomingChannelInputsAreRejectedWithoutMessageOrIdentityWrites() = runTest {
        val original = incoming()
        val unproven = occurrence(original)
        for (invalid in listOf(
            original.copy(direction = MessageDirection.OUTGOING),
            original.copy(channelIndex = null),
            original.copy(contactID = UUID.randomUUID()),
        )) {
            val rejected = assertIs<ChannelReactionIngressResult.Rejected>(
                store.channelReactionTargetAuthority.saveIncomingChannelMessageWithReactionTarget(invalid, unproven),
            )
            assertEquals(ChannelReactionRejection.NOT_ELIGIBLE, rejected.reason)
            assertNull(store.fetchMessage(EntityKey(RADIO_A, original.id)))
            assertNull(db.channelReactionIdentities().forMessage(RADIO_A.value, original.id))
        }
    }

    @Test fun actualRoomInsertFailureIsNotReportedAsCommittedUnprovenIngress() = runTest {
        val original = incoming()
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER controlled_incoming_failure BEFORE INSERT ON messages
            BEGIN SELECT RAISE(ABORT, 'controlled incoming failure'); END
            """.trimIndent(),
        )
        try {
            val failure = assertFailsWith<PersistenceStoreException> {
                store.channelReactionTargetAuthority.saveIncomingChannelMessageWithReactionTarget(original, occurrence(original))
            }
            assertIs<PersistenceStoreError.SaveFailed>(failure.error)
            assertNull(store.fetchMessage(EntityKey(RADIO_A, original.id)))
            assertNull(db.channelReactionIdentities().forMessage(RADIO_A.value, original.id))
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER controlled_incoming_failure")
        }
    }

    @Test fun anchoredUnprovenHistoryPreservesMoreThanTwoHundredLoadedMessagesAndHasMore() = runTest {
        for (index in 0 until 205) {
            store.saveMessage(incoming(timestamp = 100u + index.toUInt(), createdAt = AT.plusSeconds(index.toLong())))
        }
        val expected = store.fetchMessageWindow(RADIO_A, 1u, AT.plusSeconds(2), 201)
        val actual = store.channelReactionTargetAuthority.fetchChannelReactionWindow(RADIO_A, 1u, AT.plusSeconds(2), 201)
        assertEquals(203, actual.messages.size)
        assertTrue(actual.hasMore)
        assertEquals(expected.hasMore, actual.hasMore)
        assertEquals(expected.messages, actual.messages.map { it.message })
        actual.messages.forEach { assertIs<UnprovenChannelReactionMessage>(it) }
    }

    @Test fun realBackupRestoreAndSameSlotReplayNeverTurnMissingOriginIntoATarget() = runTest {
        val storage = MeshCoreStorage.get(context)
        try {
            val original = incoming()
            val channel = ChannelDTO(
                UUID.randomUUID(), RADIO_A, 1u, "Restored", Bytes(ByteArray(16) { 0x41 }),
            )
            val fixture = envelope(devices = listOf(device()), channels = listOf(channel), messages = listOf(original))
            val service = AppBackupService(storage.backupPreferences, "test", "1")
            val result = service.importBackup(fixture, store)
            assertEquals(1L, result.counts.getValue(BackupModelKind.MESSAGES).inserted)
            val identity = assertNotNull(db.channelReactionIdentities().forMessage(RADIO_A.value, original.id))
            repeat(2) {
                val history = assertIs<UnprovenChannelReactionMessage>(
                    store.channelReactionTargetAuthority.fetchChannelReactionWindow(RADIO_A, 1u, 10, 0).single(),
                )
                assertEquals(original, history.message)
                assertEquals(ChannelReactionUnprovenReason.MISSING_ORIGIN, history.reason)
                val replay = service.importBackup(fixture, store)
                assertEquals(0L, replay.counts.getValue(BackupModelKind.MESSAGES).inserted)
            }
            assertEquals(identity, db.channelReactionIdentities().forMessage(RADIO_A.value, original.id))
            assertIs<UnprovenChannelReactionMessage>(
                store.channelReactionTargetAuthority.fetchChannelReactionWindow(RADIO_A, 1u, 10, 0).single(),
            )
            assertTrue(store.fetchReactions(EntityKey(RADIO_A, original.id), 100).isEmpty())
        } finally {
            storage.close()
        }
    }

    @Test fun v1RoomSchemaMigratesWithoutDestructiveFallbackAndInstallsIdentityTriggers() {
        val migrationName = "channel-reaction-v1-migration.db"
        context.deleteDatabase(migrationName)
        val schemaFile = File(
            "../database/schemas/com.meshcoreone.android.core.database.MeshCoreDatabase/1.json",
        )
        check(schemaFile.isFile) { "Trusted Room v1 schema fixture is missing: ${schemaFile.path}" }
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        val legacyRadioId = RADIO_A.canonicalString
        val legacyMessageId = UUID.randomUUID().toString()
        val legacy = object : SQLiteOpenHelper(context, migrationName, null, 1) {
            override fun onCreate(database: SQLiteDatabase) {
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.getJSONArray("indices")
                    for (indexInEntity in 0 until indices.length()) {
                        val sql = indices.getJSONObject(indexInEntity).getString("createSql")
                        database.execSQL(sql.replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) database.execSQL(setup.getString(index))
            }

            override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val legacyDatabase = legacy.writableDatabase
        legacyDatabase.execSQL(
            """
            INSERT INTO messages (
              radioId, id, channelIndex, text, timestamp, directionRawValue, statusRawValue,
              textTypeRawValue, pathLength, isRead, heardRepeats, sendCount, retryAttempt,
              maxRetryAttempts, linkPreviewFetched, containsSelfMention, mentionSeen,
              failureSeen, timestampCorrected, routeTypeRawValue, regionScopeMatches,
              createdAt_seconds, createdAt_nanos, sortDate_seconds, sortDate_nanos
            ) VALUES (?, ?, 1, 'legacy', 1, 0, 3, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, -1,
                      '[]', 1700000000, 0, 1700000000, 0)
            """.trimIndent(),
            arrayOf(legacyRadioId, legacyMessageId),
        )
        val originalColumns: List<String>
        val originalValues: List<String?>
        legacyDatabase.rawQuery(
            "SELECT * FROM messages WHERE radioId = ? AND id = ?",
            arrayOf(legacyRadioId, legacyMessageId),
        ).use {
            assertTrue(it.moveToFirst())
            originalColumns = it.columnNames.toList()
            originalValues = originalColumns.indices.map { index -> if (it.isNull(index)) null else it.getString(index) }
            assertFalse(it.moveToNext())
        }
        legacyDatabase.close()
        legacy.close()
        val migrated = rawFileDatabase(migrationName)
        try {
            val sqlite = migrated.openHelper.readableDatabase
            assertEquals(2, sqlite.version)
            sqlite.query(
                "SELECT * FROM messages WHERE radioId = ? AND id = ?",
                arrayOf(legacyRadioId, legacyMessageId),
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals(originalColumns, it.columnNames.toList())
                assertEquals(
                    originalValues,
                    originalColumns.indices.map { index -> if (it.isNull(index)) null else it.getString(index) },
                )
                assertFalse(it.moveToNext())
            }
            sqlite.query("PRAGMA foreign_key_check").use {
                assertFalse(it.moveToFirst(), "Migration must not introduce foreign-key violations")
            }
            sqlite.query(
                "SELECT incarnation, revision FROM channel_reaction_identity WHERE radioId = ? AND messageID = ?",
                arrayOf(legacyRadioId, legacyMessageId),
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals(32, it.getString(0).length)
                assertEquals(0L, it.getLong(1))
                assertFalse(it.moveToNext())
            }
            sqlite.query(
                """
                SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger'
                  AND name IN (
                    'channel_reaction_identity_insert',
                    'channel_reaction_identity_update',
                    'channel_reaction_identity_delete'
                  )
                """.trimIndent(),
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals(3, it.getInt(0))
            }
            val migratedStore = RoomPersistenceStore(migrated, owner)
            try {
                runBlocking {
                    val history = assertIs<UnprovenChannelReactionMessage>(
                        migratedStore.channelReactionTargetAuthority.fetchChannelReactionWindow(RADIO_A, 1u, 10, 0).single(),
                    )
                    assertEquals(UUID.fromString(legacyMessageId), history.message.id)
                    assertEquals("legacy", history.message.text)
                    assertEquals(ChannelReactionUnprovenReason.MISSING_ORIGIN, history.reason)
                }
            } finally {
                runBlocking { migratedStore.close() }
            }
        } finally {
            migrated.close()
            context.deleteDatabase(migrationName)
        }
    }

    private fun rawFileDatabase(fileName: String): MeshCoreDatabase =
        Room.databaseBuilder(context, MeshCoreDatabase::class.java, fileName)
            .allowMainThreadQueries()
            .build()

    private fun occurrence(
        message: MessageDTO,
        reason: ChannelReactionUnprovenReason = ChannelReactionUnprovenReason.UNORDERED_INGRESS,
    ) = ChannelReactionOccurrence.Unproven(
        ChannelReceiveEnvelope(
            UUID.randomUUID(),
            SessionToken(ProcessEpoch(UUID(0, 10)), Generation(1), message.radioId),
            ChannelMessage(
                checkNotNull(message.channelIndex), 0u, 0u,
                Instant.ofEpochSecond(message.reactionTimestamp.toLong()), "Peer: ${message.text}", null,
            ),
            DeliveryContext.Live,
            ChannelReceiveOrigin.Unproved,
        ),
        reason,
    )

    private object ForeignTarget : ChannelReactionTarget

    private fun incoming(
        id: UUID = UUID.randomUUID(),
        timestamp: UInt = 1_700_000_000u,
        createdAt: Instant = AT,
    ) = MessageDTO(
        id = id,
        radioId = RADIO_A,
        channelIndex = 1u,
        text = "original channel message",
        timestamp = timestamp,
        createdAt = createdAt,
        sortDate = createdAt,
        direction = MessageDirection.INCOMING,
        status = MessageStatus.DELIVERED,
        senderNodeName = "Peer",
    )

    private fun reaction(message: MessageDTO) = ReactionDTO(
        messageID = message.id,
        emoji = "\uD83D\uDC4D",
        senderName = "Peer",
        messageHash = RepositoryReactionPolicy.messageHash(message.text, message.reactionTimestamp),
        rawText = "reaction",
        receivedAt = AT,
        channelIndex = message.channelIndex,
        radioId = message.radioId,
    )
}
