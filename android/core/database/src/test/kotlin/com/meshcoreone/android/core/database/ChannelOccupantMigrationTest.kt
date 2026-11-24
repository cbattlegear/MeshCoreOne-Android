// AndroidOnly: WP-201 Real exported-v1 SQLite upgrade retains legacy rows without manufacturing channel intent.
package com.meshcoreone.android.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.QueuedChannelDispatchState
import com.meshcoreone.android.core.model.canonicalString
import java.io.File
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ChannelOccupantMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun versionTenSnapshotMigrationPreservesPayloadAndAddsNullOwner() = verifySnapshotUpgrade(10)

    @Test fun versionElevenSnapshotMigrationPreservesOwner() = verifySnapshotUpgrade(11)

    private fun verifySnapshotUpgrade(version: Int) = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = File(temporary.root, "snapshot.db").absolutePath
        val snapshot = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val schema = JSONObject(File(requireNotNull(System.getProperty("roomSchemaDirectory")),
            "com.meshcoreone.android.core.database.MeshCoreDatabase${File.separator}$version.json").readText())
            .getJSONObject("database")
        try {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                    .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            val entities = schema.getJSONArray("entities")
                            for (i in 0 until entities.length()) {
                                val entity = entities.getJSONObject(i)
                                val table = entity.getString("tableName")
                                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                                for (j in 0 until indices.length()) {
                                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                                }
                                if (table == "node_status_snapshots") insertLegacy(db, entity, mapOf(
                                    "id" to snapshot.canonicalString(), "nodePublicKey" to byteArrayOf(1, 2, 3),
                                    "timestamp_seconds" to 1234L, "timestamp_nanos" to 456L,
                                    "batteryMillivolts" to 3888L, "latitude" to 12L, "longitude" to 34L,
                                ) + if (version == 11) mapOf("selfRadioID" to owner.canonicalString()) else emptyMap())
                            }
                            DirectAckStorage.callback.onCreate(db)
                            val setup = schema.getJSONArray("setupQueries")
                            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                        }
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                            error("Fixture must start at version $version")
                    }).build(),
            )
            try { helper.writableDatabase } finally { helper.close() }
            val upgraded = MeshCoreDatabase.open(context, name)
            try {
                val actual = assertNotNull(upgraded.nodeSnapshots().byId(snapshot))
                if (version == 10) assertNull(actual.selfRadioID)
                else assertEquals(owner, actual.selfRadioID)
                assertEquals(3888L, actual.batteryMillivolts)
                assertEquals(1234L, actual.timestamp.seconds)
                assertEquals(456, actual.timestamp.nanos)
                assertEquals(12.0, actual.latitude)
                assertEquals(34.0, actual.longitude)
                assertEquals(MeshCoreDatabase.SCHEMA_VERSION, upgraded.openHelper.readableDatabase.version)
            } finally { upgraded.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun versionSevenUpgradePreservesHistoryWithoutManufacturingSummaryReceipts() = runTest {
        verifySummaryUpgrade(7)
    }

    @Test fun versionEightUpgradePreservesHistoryWithoutManufacturingClassifiedCustody() = runTest {
        verifySummaryUpgrade(8)
    }

    private suspend fun verifySummaryUpgrade(startVersion: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "summary-upgrade-${UUID.randomUUID()}.db"
        val radio = UUID.randomUUID()
        val message = UUID.randomUUID()
        val schema = JSONObject(File(requireNotNull(System.getProperty("roomSchemaDirectory")),
            "com.meshcoreone.android.core.database.MeshCoreDatabase${File.separator}$startVersion.json").readText())
            .getJSONObject("database")
        try {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                    .callback(object : SupportSQLiteOpenHelper.Callback(startVersion) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            val entities = schema.getJSONArray("entities")
                            for (i in 0 until entities.length()) {
                                val entity = entities.getJSONObject(i)
                                val table = entity.getString("tableName")
                                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                                for (j in 0 until indices.length()) {
                                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                                }
                                if (table == "messages") insertLegacy(db, entity, mapOf(
                                    "radioId" to radio.canonicalString(), "id" to message.canonicalString(),
                                    "channelIndex" to 3L, "text" to "preserved", "reactionSummary" to "x:1",
                                    "regionScopeMatches" to "[]",
                                ))
                            }
                            DirectAckStorage.callback.onCreate(db)
                            val setup = schema.getJSONArray("setupQueries")
                            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                        }
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                            error("Fixture must start at its exported version")
                    }).build(),
            )
            try { assertEquals(startVersion, helper.writableDatabase.version) } finally { helper.close() }
            val upgraded = MeshCoreDatabase.open(context, name)
            try {
                assertEquals(MeshCoreDatabase.SCHEMA_VERSION, upgraded.openHelper.readableDatabase.version)
                val target = assertNotNull(upgraded.messages().byId(radio, message))
                assertEquals("preserved", target.text)
                assertEquals("x:1", target.reactionSummary)
                upgraded.openHelper.readableDatabase.query("SELECT COUNT(*) FROM reaction_summary_receipts").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
                assertTrue(upgraded.classifiedReactionQueue().entries().isEmpty())
                assertNull(upgraded.classifiedReactionQueue().state())
                DirectAckStorage.verify(upgraded.openHelper.writableDatabase)
            } finally { upgraded.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun registeredMigrationUpgradesAnActualV1DatabaseWithoutBackfillingOrDeletingLegacyIntent() = runTest {
        verifyUpgrade(1)
    }

    @Test fun versionTwoPendingProofRemainsUnknownRatherThanBecomingReadyOrAccepted() = runTest {
        verifyUpgrade(2)
    }

    @Test fun composedV1ToV3DispatchingFixtureIsPreservedWithoutInventingSubmissionReceipts() = runTest {
        verifyUpgrade(3)
    }

    private suspend fun verifyUpgrade(startVersion: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "occupant-upgrade-${UUID.randomUUID()}.db"
        val radio = UUID.randomUUID()
        val message = UUID.randomUUID()
        val pending = UUID.randomUUID()
        val channel = UUID.randomUUID()
        val intended = UUID.randomUUID().takeIf { startVersion >= 2 }
        val attempt = UUID.randomUUID()
        val schema = JSONObject(File(requireNotNull(System.getProperty("roomSchemaDirectory")),
            "com.meshcoreone.android.core.database.MeshCoreDatabase${File.separator}1.json").readText())
            .getJSONObject("database")
        try {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                    .callback(object : SupportSQLiteOpenHelper.Callback(startVersion) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createLegacy(db, schema, radio, channel, message, pending, intended)
                            if (startVersion == 3) {
                                MeshCoreDatabase.MIGRATION_2_3.migrate(db)
                                db.execSQL("UPDATE pending_sends SET channelDispatchStateRawValue = 1, channelAttemptID = ? WHERE id = ?",
                                    arrayOf(attempt.canonicalString(), pending.canonicalString()))
                                db.execSQL("INSERT INTO queued_channel_attempts (radioId, attemptID, pendingID, messageID, channelIndex, channelOccupantID, wireTimestamp, isResend, phaseRawValue, isCompleted) VALUES (?, ?, ?, ?, 5, ?, 42, 0, 0, 0)",
                                    arrayOf(radio.canonicalString(), attempt.canonicalString(), pending.canonicalString(),
                                        message.canonicalString(), assertNotNull(intended).canonicalString()))
                            }
                        }
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
                assertEquals(if (startVersion == 3) QueuedChannelDispatchState.DISPATCHING else null, queue.channelDispatchState)
                assertEquals(attempt.takeIf { startVersion == 3 }, queue.channelAttemptID)
                val journal = upgraded.queuedChannelAttempts().forPending(radio, pending)
                assertEquals(if (startVersion == 3) listOf(attempt) else emptyList(), journal.map { it.attemptID })
                assertNull(upgraded.channelSubmissions().byId(pending))
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
                assertEquals(if (startVersion == 3) 1L else null, retained.channelDispatchStateRawValue)
                assertEquals(attempt.takeIf { startVersion == 3 }, retained.channelAttemptID)
                assertNull(reopened.channelSubmissions().byId(pending))
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
