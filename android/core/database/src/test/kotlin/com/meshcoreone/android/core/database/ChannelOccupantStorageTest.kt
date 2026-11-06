// AndroidOnly: WP-202 Real Room slot-epoch mutation controls; no authenticated radio or origin issuer.
package com.meshcoreone.android.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.File
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
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
class ChannelOccupantStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: MeshCoreDatabase
    private val name = "wp202-occupant.db"

    @Before fun open() {
        context.deleteDatabase(name)
        db = fileDatabase()
    }

    @After fun close() {
        db.close()
        context.deleteDatabase(name)
    }

    @Test fun initialRowsAreUnresolvedAndResolutionRequiresUniqueEnabledExactRowKeyAndEpoch() = runTest {
        val row = channel().toEntity()
        db.devices().insert(device().toEntity())
        db.channels().insert(row)
        val initial = state(row.index)
        assertFalse(initial.isResolved)
        assertNull(initial.boundRowId)
        val dao = db.channelOccupantAuthorities()
        assertEquals(0, dao.resolveCurrent(RADIO_A, row.index, "wrong", row.id, row.secret))
        assertEquals(0, dao.resolveCurrent(RADIO_A, row.index, initial.epoch, UUID.randomUUID(), row.secret))
        assertEquals(0, dao.resolveCurrent(RADIO_A, row.index, initial.epoch, row.id, Bytes.of(42)))
        assertEquals(initial, state(row.index))
        val resolved = resolve(row)
        assertTrue(resolved.isResolved)
        assertEquals(row.id, resolved.boundRowId)
        assertEquals(initial.epoch, resolved.epoch)
    }

    @Test fun metadataAndEqualIdentityUpdatesPreserveButSecretAndBothEnableDirectionsInvalidate() = runTest {
        val row = prepare()
        val original = resolve(row)
        db.channels().upsert(row.copy(name = "metadata", unreadCount = 7))
        assertEquals(original, state(row.index))
        db.openHelper.writableDatabase.execSQL("UPDATE channels SET secret = secret, isEnabled = isEnabled")
        assertEquals(original, state(row.index))
        db.channels().upsert(row.copy(secret = Bytes.of(2)))
        assertInvalidated(original, state(row.index))
        val changed = resolve(row.copy(secret = Bytes.of(2)))
        db.channels().upsert(row.copy(secret = Bytes.of(2), isEnabled = false))
        val disabled = state(row.index)
        assertInvalidated(changed, disabled)
        assertEquals(0, db.channelOccupantAuthorities().resolveCurrent(
            RADIO_A, row.index, disabled.epoch, row.id, Bytes.of(2),
        ))
        db.channels().upsert(row.copy(secret = Bytes.of(2), isEnabled = true))
        assertInvalidated(disabled, state(row.index))
    }

    @Test fun duplicatesRefuseResolutionWithoutMutatingAuthorityAndRemovingOneDoesNotResolve() = runTest {
        val row = prepare()
        val original = resolve(row)
        val duplicate = row.copy(id = UUID.randomUUID())
        db.channels().insert(duplicate)
        val ambiguous = state(row.index)
        assertInvalidated(original, ambiguous)
        assertEquals(0, db.channelOccupantAuthorities().resolveCurrent(
            RADIO_A, row.index, ambiguous.epoch, row.id, row.secret,
        ))
        assertEquals(ambiguous, state(row.index))
        db.channels().delete(RADIO_A, duplicate.id)
        assertInvalidated(ambiguous, state(row.index))
        assertEquals(row.id, resolve(row).boundRowId)
    }

    @Test fun rawReplaceMovingTheSameRowFencesOldAndNewSlotsWithoutDeleteTriggerAssumptions() = runTest {
        val row = prepare()
        val original = resolve(row)
        db.openHelper.writableDatabase.execSQL("PRAGMA recursive_triggers=OFF")
        replaceFromSelf("channels", mapOf("index" to "2"))
        assertInvalidated(original, state(row.index))
        assertNull(state(row.index).boundRowId)
        assertFalse(state(2).isResolved)
        assertEquals(row.id, db.channels().forIndex(RADIO_A, 2).single().id)
        assertEquals(0, db.channelOccupantAuthorities().resolveCurrent(
            RADIO_A, row.index, original.epoch, row.id, row.secret,
        ))
    }

    @Test fun rawKeyMoveDeleteRecreateAndAbaNeverReviveOldEpoch() = runTest {
        val row = prepare()
        val first = resolve(row)
        db.openHelper.writableDatabase.execSQL("UPDATE channels SET `index`=3")
        assertInvalidated(first, state(row.index))
        assertFalse(state(3).isResolved)
        db.openHelper.writableDatabase.execSQL("UPDATE channels SET `index`=0")
        assertInvalidated(first, state(row.index))
        val returned = resolve(row)
        db.channels().delete(RADIO_A, row.id)
        val tombstone = state(row.index)
        assertInvalidated(returned, tombstone)
        db.channels().insert(row)
        assertInvalidated(tombstone, state(row.index))
        assertEquals(0, db.channelOccupantAuthorities().resolveCurrent(
            RADIO_A, row.index, first.epoch, row.id, row.secret,
        ))
    }

    @Test fun deviceMetadataPreservesButDeleteRecreateAndEqualRawReplaceInvalidate() = runTest {
        val row = prepare()
        val first = resolve(row)
        val d = db.devices().forRadio(RADIO_A).single()
        db.devices().upsert(d.copy(nodeName = "metadata"))
        assertEquals(first, state(row.index))
        replaceFromSelf("devices")
        val replaced = state(row.index)
        assertInvalidated(first, replaced)
        val again = resolve(row)
        db.devices().delete(d.id)
        val erased = state(row.index)
        assertInvalidated(again, erased)
        assertEquals(0, db.channelOccupantAuthorities().resolveCurrent(
            RADIO_A, row.index, erased.epoch, row.id, row.secret,
        ))
        db.devices().insert(d)
        assertInvalidated(erased, state(row.index))
        assertTrue(resolve(row).isResolved)
    }

    @Test fun restoreInvalidationIsAtomicCoversUnimportedSlotsAndRollsBackOnCancellation() = runTest {
        val row = prepare()
        val second = row.copy(id = UUID.randomUUID(), index = 2)
        db.channels().insert(second)
        val firstState = resolve(row)
        val secondState = resolve(second)
        val cancellation = CancellationException("rollback")
        assertSame(cancellation, assertFailsWith<CancellationException> {
            db.withTransaction {
                assertEquals(2, db.channelOccupantAuthorities().invalidateAll())
                db.channels().upsert(row.copy(name = "rolled back"))
                throw cancellation
            }
        })
        assertEquals(firstState, state(row.index))
        assertEquals(secondState, state(second.index))
        db.withTransaction {
            assertEquals(2, db.channelOccupantAuthorities().invalidateAll())
            db.channels().upsert(row.copy(name = "imported metadata"))
        }
        assertInvalidated(firstState, state(row.index))
        assertInvalidated(secondState, state(second.index))
    }

    @Test fun separateHandlesObserveRawInvalidationAndTombstoneSurvivesReopen() = runTest {
        val row = prepare()
        val first = resolve(row)
        val other = fileDatabase()
        try {
            other.openHelper.writableDatabase.execSQL("DELETE FROM channels")
            assertInvalidated(first, state(row.index))
            assertEquals(state(row.index), other.channelOccupantAuthorities().forSlot(RADIO_A, row.index))
        } finally { other.close() }
        val deleted = state(row.index)
        db.close()
        db = fileDatabase()
        assertEquals(deleted, state(row.index))
        db.channels().insert(row)
        assertInvalidated(deleted, state(row.index))
    }

    @Test fun missingTriggerIsReportedAndReopenBlocksCallerMutationWithoutRepair() = runTest {
        val row = prepare()
        val original = resolve(row)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER channel_occupant_update")
        assertEquals(
            "Channel occupant authority triggers are missing or incompatible",
            assertFailsWith<IllegalStateException> { db.requireChannelOccupantTriggersInstalled() }.message,
        )
        db.close()
        var callerRan = false
        db = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(database: SupportSQLiteDatabase) {
                    callerRan = true
                    database.execSQL("UPDATE channels SET secret=X'22'")
                }
            }).build()
        assertFailsWith<IllegalStateException> { db.devices().count() }
        assertFalse(callerRan)
        android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { raw ->
            raw.rawQuery("SELECT epoch,isResolved FROM channel_occupant_authority", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(original.epoch, it.getString(0))
                assertEquals(1, it.getInt(1))
            }
            raw.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='channel_occupant_update'", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test fun genuineV2MigrationPreservesRowsAndSeedsEveryLegacySlotUnresolved() = runTest {
        db.close()
        context.deleteDatabase(name)
        val file = File(requireNotNull(System.getProperty("roomSchemaDirectory")),
            "com.meshcoreone.android.core.database.MeshCoreDatabase${File.separator}2.json")
        val export = JSONObject(file.readText()).getJSONObject("database")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { raw ->
            val entities = export.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) raw.execSQL(
                    indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")),
                )
            }
            val setup = export.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
            ChannelReactionIdentitySql.triggers.values.forEach(raw::execSQL)
            raw.execSQL("""
                INSERT INTO channels VALUES (
                  '${ValueConverters().uuidToText(RADIO_A)}', '${ValueConverters().uuidToText(UUID.randomUUID())}', 0, 'legacy', X'01', 1,
                  NULL, NULL, 0, 0, 0, NULL, 0, 'default', NULL
                )
            """.trimIndent())
            raw.version = 2
        }
        db = fileDatabase()
        val rows = db.channels().forRadio(RADIO_A)
        assertEquals(1, rows.size)
        assertEquals("legacy", rows.single().name)
        assertFalse(state(0).isResolved)
        assertNull(state(0).boundRowId)
        db.requireChannelOccupantTriggersInstalled()
        db.channels().delete(RADIO_A, rows.single().id)
        assertFalse(state(0).isResolved)
    }

    private suspend fun prepare(): ChannelEntity {
        db.devices().insert(device().toEntity())
        return channel().toEntity().also { db.channels().insert(it) }
    }

    private suspend fun state(index: Long): ChannelOccupantAuthorityEntity =
        assertNotNull(db.channelOccupantAuthorities().forSlot(RADIO_A, index))

    // Direct DAO resolution exercises storage predicates only; this is not a fake lifecycle issuer.
    private suspend fun resolve(row: ChannelEntity): ChannelOccupantAuthorityEntity {
        val before = state(row.index)
        assertEquals(1, db.channelOccupantAuthorities().resolveCurrent(
            row.radioId, row.index, before.epoch, row.id, row.secret,
        ))
        return state(row.index)
    }

    private fun assertInvalidated(before: ChannelOccupantAuthorityEntity, after: ChannelOccupantAuthorityEntity) {
        assertNotEquals(before.epoch, after.epoch)
        assertFalse(after.isResolved)
        assertNull(after.boundRowId)
    }

    private fun fileDatabase(): MeshCoreDatabase =
        Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()

    private fun replaceFromSelf(table: String, replacements: Map<String, String> = emptyMap()) {
        val sql = db.openHelper.writableDatabase
        val columns = sql.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        val projection = columns.joinToString { replacements[it] ?: "`$it`" }
        sql.execSQL("INSERT OR REPLACE INTO `$table` SELECT $projection FROM `$table`")
    }
}
