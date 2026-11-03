// AndroidOnly: WP-201 Real exported-v1 SQLite upgrade retains legacy rows without manufacturing channel intent.
package com.meshcoreone.android.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
        verifyUpgrade(1)
    }

    @Test fun versionTwoPendingProofRemainsUnknownRatherThanBecomingReadyOrAccepted() = runTest {
        verifyUpgrade(2)
    }

    private suspend fun verifyUpgrade(startVersion: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "occupant-upgrade-${UUID.randomUUID()}.db"
        val radio = UUID.randomUUID()
        val message = UUID.randomUUID()
        val pending = UUID.randomUUID()
        val channel = UUID.randomUUID()
        val intended = UUID.randomUUID().takeIf { startVersion == 2 }
        val schema = JSONObject(File(requireNotNull(System.getProperty("roomSchemaDirectory")),
            "com.meshcoreone.android.core.database.MeshCoreDatabase${File.separator}1.json").readText())
            .getJSONObject("database")
        try {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                    .callback(object : SupportSQLiteOpenHelper.Callback(startVersion) {
                        override fun onCreate(db: SupportSQLiteDatabase) { createLegacy(db, schema, radio, channel, message, pending, intended) }
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            error("Legacy fixture must be created at its requested version")
                        }
                    }).build(),
            )
            try {
                assertEquals(startVersion, helper.writableDatabase.version)
            } finally {
                helper.close()
            }
            val upgraded = MeshCoreDatabase.open(context, name)
            try {
                val row = assertNotNull(upgraded.messages().byId(radio, message)).toDTO()
                assertEquals("legacy original\n", row.text)
                assertEquals(intended, row.channelOccupantID)
                val queue = upgraded.pendingSends().forRadio(radio).single().toDTO()
                assertEquals("legacy original\n", queue.messageText)
                assertEquals(intended, assertNotNull(queue.channelMessageEnvelope()).channelOccupantID)
                assertNull(queue.channelDispatchState)
                assertNull(queue.channelAttemptID)
                assertTrue(upgraded.queuedChannelAttempts().forPending(radio, pending).isEmpty())
                assertEquals(channel, upgraded.channels().forIndex(radio, 5).single().id)
                assertNull(upgraded.channelOccupants().forIndex(radio, 5))
                if (intended != null) {
                    val unresolved = assertNotNull(upgraded.channelOccupants().stateForIndex(radio, 5))
                    assertEquals(intended, unresolved.occupantID)
                    assertTrue(unresolved.unresolved)
                }
                val fresh = UUID.randomUUID()
                upgraded.channelOccupants().upsert(ChannelOccupantEntity(radio, 5, fresh))
                assertEquals(intended, upgraded.messages().byId(radio, message)?.channelOccupantID)
                assertEquals(intended, upgraded.pendingSends().forRadio(radio).single().channelOccupantID)
            } finally {
                upgraded.close()
            }
            val reopened = MeshCoreDatabase.open(context, name)
            try {
                assertNotNull(reopened.channelOccupants().forIndex(radio, 5))
                assertEquals(intended, reopened.messages().byId(RadioId(radio).value, message)?.channelOccupantID)
                val retained = reopened.pendingSends().forRadio(radio).single()
                assertNull(retained.channelDispatchStateRawValue)
                assertNull(retained.channelAttemptID)
                assertEquals("legacy original\n", retained.messageText)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun createLegacy(
        legacy: SupportSQLiteDatabase, schema: JSONObject, radio: UUID, channel: UUID,
        message: UUID, pending: UUID, intended: UUID?,
    ) {
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = if (entity.has("indices")) entity.getJSONArray("indices") else org.json.JSONArray()
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
        if (intended != null) {
            MeshCoreDatabase.MIGRATION_1_2.migrate(legacy)
            legacy.execSQL("UPDATE messages SET channelOccupantID = ?", arrayOf(intended.canonicalString()))
            legacy.execSQL("UPDATE pending_sends SET channelOccupantID = ?", arrayOf(intended.canonicalString()))
            legacy.execSQL(
                "INSERT INTO channel_occupants (radioId, `index`, occupantID, unresolved) VALUES (?, 5, ?, 1)",
                arrayOf(radio.canonicalString(), intended.canonicalString()),
            )
        }
    }

    private fun insertLegacy(db: SupportSQLiteDatabase, entity: JSONObject, values: Map<String, Any>) {
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
