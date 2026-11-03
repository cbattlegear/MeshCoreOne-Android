// AndroidOnly: WP-201 Exported-v1 nullable-origin upgrade, exact attribution projection and disk reopen controls.
package com.meshcoreone.android.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.model.*
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.*
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
class ChannelOriginV2MigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val radio = UUID.randomUUID()
    private val messageID = UUID.randomUUID()
    private val pendingID = UUID.randomUUID()
    private val channelID = UUID.randomUUID()

    @Test fun actualExportedV1UpgradePreservesEveryOldColumnAndLeavesBothOriginsUnknownAcrossReopen() = runTest {
        val name = "origin-v2-${UUID.randomUUID()}.db"
        val before = createV1(name)
        try {
            val upgraded = MeshCoreDatabase.open(context, name)
            try {
                val sql = upgraded.openHelper.readableDatabase
                for ((table, oldValues) in before) {
                    assertEquals(oldValues, snapshot(sql, table).filterKeys { it != "channelOccupantID" }, table)
                }
                assertNull(upgraded.messages().byId(radio, messageID)?.channelOccupantID)
                assertNull(upgraded.pendingSends().forRadio(radio).single().channelOccupantID)
                assertEquals(channelID, upgraded.channels().forIndex(radio, 5).single().id)
                assertEquals("legacy original\n", upgraded.messages().byId(radio, messageID)?.text)
                assertEquals(17, sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('android_metadata', 'room_master_table')").use { it.count })
            } finally { upgraded.close() }
            val reopened = MeshCoreDatabase.open(context, name)
            try {
                val retainedMessage = assertNotNull(reopened.messages().byId(radio, messageID)).toDTO()
                assertNull(retainedMessage.channelOccupantID)
                val pending = reopened.pendingSends().forRadio(radio).single().toDTO()
                assertNull(pending.channelOccupantID)
                assertNull(assertNotNull(pending.channelMessageEnvelope()).channelOccupantID)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun explicitlySuppliedAttributionRoundTripsWithoutSamplingReplacementMetadata() = runTest {
        val name = "origin-roundtrip-${UUID.randomUUID()}.db"
        createV1(name)
        val intended = UUID.randomUUID()
        lateinit var savedMessage: MessageEntity
        lateinit var savedPending: PendingSendEntity
        try {
            val database = MeshCoreDatabase.open(context, name)
            try {
                val legacyMessage = assertNotNull(database.messages().byId(radio, messageID)).toDTO()
                val attributed = legacyMessage.copy(channelOccupantID = intended)
                assertNotEquals(legacyMessage, attributed)
                assertEquals(attributed, attributed.copy())
                assertEquals(attributed.hashCode(), attributed.copy().hashCode())
                savedMessage = attributed.toEntity()
                val legacyPending = database.pendingSends().forRadio(radio).single().toDTO()
                savedPending = legacyPending.copy(channelOccupantID = intended).toEntity()
                database.messages().upsert(savedMessage)
                database.pendingSends().upsert(savedPending)
                database.openHelper.writableDatabase.execSQL("UPDATE channels SET id = ?, name = ?, secret = ? WHERE radioId = ?",
                    arrayOf(UUID.randomUUID().canonicalString(), "replacement", ByteArray(16) { 9 }, radio.canonicalString()))
            } finally { database.close() }
            val reopened = MeshCoreDatabase.open(context, name)
            try {
                assertEquals(savedMessage, reopened.messages().byId(radio, messageID))
                assertEquals(savedPending, reopened.pendingSends().forRadio(radio).single())
                assertEquals(intended, reopened.messages().byId(radio, messageID)?.toDTO()?.channelOccupantID)
                val pending = reopened.pendingSends().forRadio(radio).single().toDTO()
                val envelope = assertNotNull(pending.channelMessageEnvelope())
                assertEquals(intended, envelope.channelOccupantID)
                assertEquals(pending, PendingSendDTO.fromEnvelope(envelope, pending.radioId,
                    id = pending.id, enqueuedAt = pending.enqueuedAt).copy(
                        sequence = pending.sequence, attemptCount = pending.attemptCount))
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun oldConstructorsAndDirectMessageEnvelopeRemainUnknownWithoutAnIdentityFactory() {
        val envelope = ChannelMessageEnvelope(messageID, 5u, false, "original", 42u, "node")
        val pending = PendingSendDTO.fromEnvelope(envelope, RadioId(radio), id = pendingID, enqueuedAt = Instant.EPOCH)
        assertNull(envelope.channelOccupantID)
        assertNull(pending.channelOccupantID)
        assertEquals(pending, pending.toEntity().toDTO())
        val direct = DirectMessageEnvelope(messageID, UUID.randomUUID())
        val dm = PendingSendDTO.fromEnvelope(direct, RadioId(radio), id = pendingID, enqueuedAt = Instant.EPOCH)
        assertNull(dm.channelOccupantID)
        assertEquals(direct, dm.toEntity().toDTO().directMessageEnvelope())
    }

    private fun createV1(name: String): Map<String, Map<String, Any?>> {
        val schema = JSONObject(File(requireNotNull(System.getProperty("roomSchemaDirectory")),
            "com.meshcoreone.android.core.database.MeshCoreDatabase${File.separator}1.json").readText()).getJSONObject("database")
        assertEquals(1, schema.getInt("version"))
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.optJSONArray("indices")
                            if (indices != null) for (j in 0 until indices.length()) {
                                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                            }
                            val values: Map<String, Any> = when (table) {
                                "channels" -> mapOf("radioId" to radio.canonicalString(), "id" to channelID.canonicalString(),
                                    "index" to 5L, "secret" to ByteArray(16) { 1 }, "name" to "original",
                                    "isEnabled" to 1L, "floodScopeModeRawValue" to "inherit")
                                "messages" -> mapOf("radioId" to radio.canonicalString(), "id" to messageID.canonicalString(),
                                    "channelIndex" to 5L, "text" to "legacy original\n", "directionRawValue" to 1L,
                                    "regionScopeMatches" to "[]", "timestamp" to 42L)
                                "pending_sends" -> mapOf("radioId" to radio.canonicalString(), "id" to pendingID.canonicalString(),
                                    "messageID" to messageID.canonicalString(), "kindRawValue" to 1L, "channelIndex" to 5L,
                                    "messageText" to "legacy original\n", "messageTimestamp" to 42L)
                                else -> emptyMap()
                            }
                            if (values.isNotEmpty()) insert(db, entity, values)
                        }
                        val queries = schema.getJSONArray("setupQueries")
                        for (i in 0 until queries.length()) db.execSQL(queries.getString(i))
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("Fixture must remain independently exported v1")
                }).build())
        try {
            val db = helper.writableDatabase
            assertEquals(1, db.version)
            return listOf("channels", "messages", "pending_sends").associateWith { snapshot(db, it) }
        } finally { helper.close() }
    }

    private fun insert(db: SupportSQLiteDatabase, entity: JSONObject, values: Map<String, Any>) {
        val fields = entity.getJSONArray("fields")
        val columns = mutableListOf<String>()
        val arguments = mutableListOf<Any?>()
        for (i in 0 until fields.length()) {
            val field = fields.getJSONObject(i)
            val name = field.getString("columnName")
            columns += "`$name`"
            arguments += values[name] ?: if (!field.optBoolean("notNull")) null else when (field.getString("affinity")) {
                "TEXT" -> ""
                "BLOB" -> ByteArray(0)
                else -> 0L
            }
        }
        db.execSQL("INSERT INTO `${entity.getString("tableName")}` (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})",
            arguments.toTypedArray())
    }

    private fun snapshot(db: SupportSQLiteDatabase, table: String): Map<String, Any?> =
        db.query("SELECT * FROM `$table`").use { cursor ->
            assertEquals(1, cursor.count, table)
            assertTrue(cursor.moveToFirst())
            cursor.columnNames.indices.associate { index ->
                cursor.columnNames[index] to when (cursor.getType(index)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> null
                    android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                    android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                    android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).toList()
                    else -> cursor.getString(index)
                }
            }
        }
}
