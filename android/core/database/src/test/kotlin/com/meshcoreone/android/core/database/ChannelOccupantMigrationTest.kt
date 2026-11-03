// AndroidOnly: WP-201 Real exported-v1 SQLite upgrade retains legacy rows without manufacturing channel intent.
package com.meshcoreone.android.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.canonicalString
import java.io.File
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
class ChannelOccupantMigrationTest {
    @Test fun registeredMigrationUpgradesAnActualV1DatabaseWithoutBackfillingOrDeletingLegacyIntent() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "occupant-upgrade-${UUID.randomUUID()}.db"
        val radio = UUID.randomUUID()
        val message = UUID.randomUUID()
        val pending = UUID.randomUUID()
        val channel = UUID.randomUUID()
        val schema = JSONObject(File(requireNotNull(System.getProperty("roomSchemaDirectory")),
            "com.meshcoreone.android.core.database.MeshCoreDatabase${File.separator}1.json").readText())
            .getJSONObject("database")
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { legacy ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.getJSONArray("indices")
                    for (j in 0 until indices.length()) {
                        legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    when (table) {
                        "channels" -> insertLegacy(legacy, entity, mapOf(
                            "radioId" to radio.canonicalString(), "id" to channel.canonicalString(), "index" to 5L,
                            "secret" to ByteArray(16) { 1 }, "name" to "original", "isEnabled" to 1L,
                            "floodScopeModeRawValue" to "inherit",
                        ))
                        "messages" -> insertLegacy(legacy, entity, mapOf(
                            "radioId" to radio.canonicalString(), "id" to message.canonicalString(),
                            "channelIndex" to 5L, "text" to "legacy original\n", "directionRawValue" to 1L,
                            "regionScopeMatches" to "[]",
                        ))
                        "pending_sends" -> insertLegacy(legacy, entity, mapOf(
                            "radioId" to radio.canonicalString(), "id" to pending.canonicalString(),
                            "messageID" to message.canonicalString(), "kindRawValue" to 1L,
                            "channelIndex" to 5L, "messageText" to "legacy original\n", "attemptCount" to 0L,
                        ))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) legacy.execSQL(setup.getString(i))
                legacy.version = 1
            }
            val upgraded = MeshCoreDatabase.open(context, name)
            try {
                val row = assertNotNull(upgraded.messages().byId(radio, message)).toDTO()
                assertEquals("legacy original\n", row.text)
                assertNull(row.channelOccupantID)
                val queue = upgraded.pendingSends().forRadio(radio).single().toDTO()
                assertEquals("legacy original\n", queue.messageText)
                assertNull(queue.channelMessageEnvelope()?.channelOccupantID)
                assertEquals(channel, upgraded.channels().forIndex(radio, 5).single().id)
                assertNull(upgraded.channelOccupants().forIndex(radio, 5))
                val fresh = UUID.randomUUID()
                upgraded.channelOccupants().upsert(ChannelOccupantEntity(radio, 5, fresh))
                assertNull(upgraded.messages().byId(radio, message)?.channelOccupantID)
                assertNull(upgraded.pendingSends().forRadio(radio).single().channelOccupantID)
            } finally {
                upgraded.close()
            }
            val reopened = MeshCoreDatabase.open(context, name)
            try {
                assertNotNull(reopened.channelOccupants().forIndex(radio, 5))
                assertNull(reopened.messages().byId(RadioId(radio).value, message)?.channelOccupantID)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun insertLegacy(db: SQLiteDatabase, entity: JSONObject, values: Map<String, Any>) {
        val fields = entity.getJSONArray("fields")
        val columns = mutableListOf<String>()
        val arguments = mutableListOf<Any?>()
        for (i in 0 until fields.length()) {
            val field = fields.getJSONObject(i)
            val name = field.getString("columnName")
            val value = values[name] ?: if (!field.optBoolean("notNull")) null else when (field.getString("affinity")) {
                "TEXT" -> ""
                "BLOB" -> ByteArray(0)
                else -> 0L
            }
            require(value == null || value is String || value is ByteArray || value is Long) {
                "Unsupported legacy fixture field $name"
            }
            columns += "`${name.replace("`", "``")}`"
            arguments += value
        }
        val table = entity.getString("tableName").replace("`", "``")
        db.execSQL(
            "INSERT INTO `$table` (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})",
            arguments.toTypedArray(),
        )
    }
}
