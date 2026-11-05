// AndroidOnly: WP-202 Real Room v1 migration and all-writer direct ACK invalidation controls.
package com.meshcoreone.android.core.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DirectAckStorageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun memory(): MeshCoreDatabase =
        Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .addCallback(DirectAckStorage.callback).build()

    private suspend fun grant(db: MeshCoreDatabase, row: MessageEntity): DirectAckClaimEntity {
        val incarnation = assertNotNull(db.directAcks().incarnation(row.radioId, row.id, "message"))
        return DirectAckClaimEntity(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), incarnation.incarnation, incarnation.revision, "FAILED", "SEND",
            row.statusRawValue, true, null, null, row.radioId, row.id, row,
        ).also { db.directAcks().claim(it) }
    }

    @Test fun daoAndRawSameValueFailedWritesInvalidateWithoutChangingTheSnapshot() = runTest {
        val db = memory()
        try {
            val row = message().toEntity().copy(statusRawValue = 4)
            db.messages().insert(row)
            for (write in listOf<suspend () -> Unit>(
                { db.messages().setStatus(row.radioId, row.id, 4) },
                { db.messages().setStatusUnlessDelivered(row.radioId, row.id, 4) },
                { db.messages().upsert(row) },
                { db.openHelper.writableDatabase.execSQL("UPDATE messages SET statusRawValue = statusRawValue") },
                { db.openHelper.writableDatabase.execSQL("UPDATE messages SET ackCode = ackCode") },
            )) {
                val receipt = grant(db, row)
                write()
                val stored = assertNotNull(db.directAcks().claim(row.radioId, receipt.requestId))
                assertFalse(stored.current)
                assertEquals(receipt.snapshot, stored.snapshot)
                assertEquals(row, db.messages().byId(row.radioId, row.id))
            }
        } finally { db.close() }
    }

    @Test fun deleteRecreateAndSqlReplaceHaveNewIncarnationsAndCannotRestoreAnOldGrant() = runTest {
        val db = memory()
        try {
            val row = message().toEntity()
            db.messages().insert(row)
            val first = grant(db, row)
            db.messages().delete(row.radioId, row.id)
            assertNull(db.directAcks().incarnation(row.radioId, row.id, "message"))
            db.messages().insert(row)
            val second = assertNotNull(db.directAcks().incarnation(row.radioId, row.id, "message"))
            assertNotEquals(first.incarnation, second.incarnation)
            assertFalse(assertNotNull(db.directAcks().claim(row.radioId, first.requestId)).current)
            val replacement = grant(db, row)
            db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO messages SELECT * FROM messages")
            assertFalse(assertNotNull(db.directAcks().claim(row.radioId, replacement.requestId)).current)
            assertNotEquals(second.incarnation,
                assertNotNull(db.directAcks().incarnation(row.radioId, row.id, "message")).incarnation)
        } finally { db.close() }
    }

    @Test fun primaryKeyRewriteInvalidatesBothMessageSlots() = runTest {
        val db = memory()
        try {
            val first = message().toEntity()
            val second = first.copy(id = UUID.randomUUID())
            db.messages().insert(first)
            db.messages().insert(second)
            val a = grant(db, first)
            val b = grant(db, second)
            db.openHelper.writableDatabase.execSQL(
                "UPDATE OR REPLACE messages SET id = ? WHERE radioId = ? AND id = ?",
                arrayOf(second.id.toString().uppercase(), first.radioId.toString().uppercase(), first.id.toString().uppercase()),
            )
            assertFalse(assertNotNull(db.directAcks().claim(first.radioId, a.requestId)).current)
            assertFalse(assertNotNull(db.directAcks().claim(second.radioId, b.requestId)).current)
            assertNull(assertNotNull(db.directAcks().claim(first.radioId, a.requestId)).snapshot)
            assertNull(assertNotNull(db.directAcks().claim(second.radioId, b.requestId)).snapshot)
            assertNull(db.directAcks().incarnation(first.radioId, first.id, "message"))
            assertNotEquals(b.incarnation,
                assertNotNull(db.directAcks().incarnation(second.radioId, second.id, "message")).incarnation)
        } finally { db.close() }
    }

    @Test fun exportedV1MigratesThroughRoomWithoutChangingAnyMessageColumn() = runTest {
        val name = "direct-ack-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        assertTrue(file.parentFile!!.exists() || file.parentFile!!.mkdirs())
        val schema = JSONObject(File(requireNotNull(System.getProperty("roomSchemaDirectory")),
            "com.meshcoreone.android.core.database.MeshCoreDatabase${File.separator}1.json").readText())
            .getJSONObject("database")
        val radio = UUID.randomUUID().toString().uppercase()
        val message = UUID.randomUUID().toString().uppercase()
        val entities = schema.getJSONArray("entities")
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = if (entity.has("indices")) entity.getJSONArray("indices") else org.json.JSONArray()
                    for (j in 0 until indices.length()) {
                        old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    if (table == "messages") {
                        val values = ContentValues()
                        val fields = entity.getJSONArray("fields")
                        for (j in 0 until fields.length()) {
                            val field = fields.getJSONObject(j)
                            val column = field.getString("columnName")
                            when {
                                column == "radioId" -> values.put(column, radio)
                                column == "id" -> values.put(column, message)
                                column == "regionScopeMatches" -> values.put(column, "[\"retained\"]")
                                !field.optBoolean("notNull") -> values.putNull(column)
                                field.getString("affinity") == "TEXT" -> values.put(column, "retained text")
                                field.getString("affinity") == "BLOB" -> values.put(column, byteArrayOf(0, -1))
                                field.getString("affinity") == "REAL" -> values.put(column, -1.5)
                                else -> values.put(column, 0L)
                            }
                        }
                        assertTrue(old.insertOrThrow("messages", null, values) > 0)
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.version = 1
            }
            val before = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { old ->
                old.rawQuery("SELECT * FROM messages", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    (0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }
                }
            }
            val db = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .addMigrations(DirectAckStorage.migration1To2, DirectAckExclusionStorage.migration2To3)
                .addCallback(DirectAckStorage.callback).build()
            try {
                val row = assertNotNull(db.messages().byId(UUID.fromString(radio), UUID.fromString(message)))
                assertEquals("retained text", row.text)
                assertEquals(3, db.openHelper.readableDatabase.version)
                val after = db.openHelper.readableDatabase.query("SELECT * FROM messages").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    (0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }
                }
                assertEquals(before, after)
                assertNotNull(db.directAcks().incarnation(row.radioId, row.id, "message"))
                assertTrue(db.directAcks().leases(row.radioId).isEmpty())
                DirectAckStorage.verify(db.openHelper.readableDatabase)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
