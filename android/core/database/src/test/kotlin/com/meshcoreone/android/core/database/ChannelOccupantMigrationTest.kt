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
    @get:Rule val databaseDirectory = TemporaryFolder()

    @Test fun versionTenUpgradePreservesHistoryAndCreatesEmptyTextRuleStorage() = runTest {
        verifySummaryUpgrade(10)
    }

    @Test fun versionTenSnapshotMigrationPreservesPayloadAndAddsNullOwner() = runTest {
        verifySnapshotMigration(10, null)
    }

    @Test fun versionElevenSnapshotMigrationPreservesOwnerAndCreatesTextRuleStorage() = runTest {
        verifySnapshotMigration(11, UUID.randomUUID())
    }

    @Test fun versionTwelveUpgradePreservesReactionIntentAndSnapshotOwner() = runTest {
        verifySnapshotMigration(12, UUID.randomUUID())
    }

    @Test fun versionThirteenArchiveUpgradeBackfillsOnlyProvenRoomOwnersAndReplacesTheOldTrigger() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = File(databaseDirectory.root, "text-rule-upgrade-${UUID.randomUUID()}.db").absolutePath
        val radioA = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val radioB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val room = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
        val identityA = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
        val identityB = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")
        val ambiguous = UUID.randomUUID()
        val ambiguousRoom = UUID.randomUUID()
        val contactCollision = UUID.randomUUID()
        val contactRoom = UUID.randomUUID()
        val contact = UUID.randomUUID()
        val schema = JSONObject(File(requireNotNull(System.getProperty("roomSchemaDirectory")),
            "com.meshcoreone.android.core.database.MeshCoreDatabase${File.separator}13.json").readText())
            .getJSONObject("database")
        try {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                    .callback(object : SupportSQLiteOpenHelper.Callback(13) {
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
                                if (table == "remote_node_sessions") {
                                    for ((radio, id) in listOf(
                                        radioA to room, radioB to room, radioA to ambiguousRoom, radioA to contactRoom,
                                    )) insertLegacy(db, entity, mapOf(
                                        "radioId" to radio.canonicalString(), "id" to id.canonicalString(),
                                    ))
                                }
                                if (table == "contacts") {
                                    insertLegacy(db, entity, mapOf(
                                        "radioId" to radioA.canonicalString(), "id" to contact.canonicalString(),
                                    ))
                                }
                            }
                            DirectAckStorage.callback.onCreate(db)
                            TextRuleStorage.install(db, roomArchiveOwners = false)
                            val setup = schema.getJSONArray("setupQueries")
                            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                            fun conversation(radio: UUID, family: String, parent: UUID, identity: UUID) {
                                db.execSQL(
                                    "INSERT INTO text_rule_conversations (radioId, family, parentID, fingerprint, identity, override) VALUES (?, ?, ?, '', ?, 'INHERIT')",
                                    arrayOf(radio.canonicalString(), family, parent.canonicalString(), identity.canonicalString()),
                                )
                            }
                            conversation(radioA, "room", room, identityA)
                            conversation(radioB, "room", room, identityB)
                            conversation(radioA, "room", ambiguousRoom, ambiguous)
                            conversation(radioA, "channel", UUID.randomUUID(), ambiguous)
                            conversation(radioA, "room", contactRoom, contactCollision)
                            conversation(radioA, "contact", contact, contactCollision)
                            fun archive(radio: UUID, identity: UUID, payload: String, canonical: Boolean = false) {
                                db.execSQL(
                                    "INSERT INTO recently_blocked_texts (id, radioId, conversationID, arrivedSeconds, arrivedNanos, payload) VALUES (?, ?, ?, 0, 0, ?)",
                                    arrayOf(UUID.randomUUID().canonicalString(),
                                        if (canonical) radio.canonicalString() else radio.toString(),
                                        if (canonical) identity.canonicalString() else identity.toString(), payload),
                                )
                            }
                            archive(radioA, identityA, "room A")
                            archive(radioB, identityB, "room B")
                            archive(radioA, UUID.randomUUID(), "unidentifiable")
                            archive(radioA, ambiguous, "ambiguous")
                            archive(radioA, contactCollision, "ambiguous contact", canonical = true)
                        }
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                            error("Fixture must start at its exported version")
                    }).build(),
            )
            try { assertEquals(13, helper.writableDatabase.version) } finally { helper.close() }
            val upgraded = MeshCoreDatabase.open(context, name)
            try {
                val archives = upgraded.textRules().archive().associateBy { it.payload }
                assertEquals(room, archives.getValue("room A").roomSessionID)
                assertEquals(room, archives.getValue("room B").roomSessionID)
                assertNull(archives.getValue("unidentifiable").roomSessionID)
                assertNull(archives.getValue("ambiguous").roomSessionID)
                assertNull(archives.getValue("ambiguous contact").roomSessionID)
                assertEquals(1, upgraded.sessions().delete(radioA, ambiguousRoom))
                assertEquals(archives.keys, upgraded.textRules().archive().map { it.payload }.toSet())
                upgraded.openHelper.writableDatabase.execSQL(
                    "UPDATE text_rule_conversations SET identity = ? WHERE radioId = ? AND family = 'room' AND parentID = ?",
                    arrayOf(UUID.randomUUID().canonicalString(), radioA.canonicalString(), room.canonicalString()),
                )
                upgraded.sessions().delete(radioA, room)
                assertEquals(setOf("room B", "unidentifiable", "ambiguous", "ambiguous contact"),
                    upgraded.textRules().archive().map { it.payload }.toSet())
                upgraded.sessions().delete(radioB, room)
                assertEquals(setOf("unidentifiable", "ambiguous", "ambiguous contact"),
                    upgraded.textRules().archive().map { it.payload }.toSet())

                upgraded.textRules().archive(RecentlyBlockedTextEntity(
                    UUID.randomUUID(), radioA, UUID.randomUUID(), null, null, 0, 0, "owned", contactRoom,
                ))
                val sqlite = upgraded.openHelper.writableDatabase
                sqlite.execSQL("DROP TRIGGER text_rules_delete_contact")
                sqlite.execSQL("DROP TRIGGER text_rules_delete_room")
                TextRuleStorage.install(sqlite, roomArchiveOwners = false)
                sqlite.execSQL("DROP TRIGGER text_rules_delete_room")
                sqlite.execSQL("""
                    CREATE TRIGGER text_rules_delete_room AFTER DELETE ON remote_node_sessions BEGIN
                      DELETE FROM recently_blocked_texts WHERE radioId = OLD.radioId COLLATE NOCASE AND
                        (conversationID COLLATE NOCASE IN (SELECT identity FROM text_rule_conversations
                          WHERE radioId = OLD.radioId COLLATE NOCASE AND family = 'room' AND parentID = OLD.id COLLATE NOCASE)
                          OR roomSessionID = OLD.id COLLATE NOCASE);
                      DELETE FROM text_rule_conversations WHERE radioId = OLD.radioId COLLATE NOCASE
                        AND family = 'room' AND parentID = OLD.id COLLATE NOCASE;
                    END
                """.trimIndent())
            } finally { upgraded.close() }

            val reopened = MeshCoreDatabase.open(context, name)
            try {
                assertEquals(1, reopened.sessions().delete(radioA, contactRoom))
                assertEquals(setOf("unidentifiable", "ambiguous", "ambiguous contact"),
                    reopened.textRules().archive().map { it.payload }.toSet())
                assertEquals(1, reopened.contacts().delete(radioA, contact))
                assertEquals(setOf("unidentifiable", "ambiguous", "ambiguous contact"),
                    reopened.textRules().archive().map { it.payload }.toSet())
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    private suspend fun verifySnapshotMigration(startVersion: Int, owner: UUID?) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = File(databaseDirectory.root, "snapshot-upgrade-${UUID.randomUUID()}.db").absolutePath
        val snapshot = UUID.randomUUID()
        val reaction = if (startVersion == 12) OutgoingReactionIntentEntity(
            radioId = UUID.randomUUID(), messageID = UUID.randomUUID(), pendingID = UUID.randomUUID(),
            messageIncarnation = "message-incarnation", pendingIncarnation = "pending-incarnation",
            targetID = UUID.randomUUID(), targetIncarnation = "target-incarnation", targetFingerprint = "target-fingerprint",
            senderName = "Peer", emoji = ":)", senderKey = "peer", emojiKey = ":)", messageHash = "hash",
            rawText = "retained reaction", channelIndex = 2L, contactID = null, contactPublicKey = null, completed = true,
        ) else null
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
                                if (table == "node_status_snapshots") insertLegacy(db, entity, buildMap {
                                    put("id", snapshot.canonicalString())
                                    put("nodePublicKey", byteArrayOf(1, 2, 3))
                                    put("timestamp_seconds", 1234L)
                                    put("timestamp_nanos", 456L)
                                    put("batteryMillivolts", 3888L)
                                    put("latitude", 12L)
                                    put("longitude", 34L)
                                    if (owner != null) put("selfRadioID", owner.canonicalString())
                                })
                                if (table == "outgoing_reaction_intents" && reaction != null) {
                                    insertLegacy(db, entity, mapOf(
                                        "radioId" to reaction.radioId.canonicalString(),
                                        "messageID" to reaction.messageID.canonicalString(),
                                        "pendingID" to reaction.pendingID.canonicalString(),
                                        "messageIncarnation" to reaction.messageIncarnation,
                                        "pendingIncarnation" to reaction.pendingIncarnation,
                                        "targetID" to reaction.targetID.canonicalString(),
                                        "targetIncarnation" to reaction.targetIncarnation,
                                        "targetFingerprint" to reaction.targetFingerprint,
                                        "senderName" to reaction.senderName, "emoji" to reaction.emoji,
                                        "senderKey" to reaction.senderKey, "emojiKey" to reaction.emojiKey,
                                        "messageHash" to reaction.messageHash, "rawText" to reaction.rawText,
                                        "channelIndex" to 2L, "completed" to 1L,
                                    ))
                                }
                            }
                            DirectAckStorage.callback.onCreate(db)
                            val setup = schema.getJSONArray("setupQueries")
                            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                        }
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                            error("Fixture must start at its exported version")
                    }).build(),
            )
            try { helper.writableDatabase } finally { helper.close() }
            val upgraded = MeshCoreDatabase.open(context, name)
            try {
                val actual = assertNotNull(upgraded.nodeSnapshots().byId(snapshot))
                assertEquals(owner, actual.selfRadioID)
                assertEquals(3888L, actual.batteryMillivolts)
                assertEquals(1234L, actual.timestamp.seconds)
                assertEquals(456, actual.timestamp.nanos)
                assertEquals(12.0, actual.latitude)
                assertEquals(34.0, actual.longitude)
                assertEquals(MeshCoreDatabase.SCHEMA_VERSION, upgraded.openHelper.readableDatabase.version)
                assertNull(upgraded.textRules().preference())
                assertTrue(upgraded.textRules().conversations().isEmpty())
                assertTrue(upgraded.textRules().archive().isEmpty())
                if (reaction != null) {
                    assertEquals(reaction, upgraded.outgoingReactionIntents().forMessage(reaction.radioId, reaction.messageID))
                }
                val plan = upgraded.openHelper.readableDatabase.query(
                    "EXPLAIN QUERY PLAN $DIRECT_WIRE_CANDIDATES_SQL",
                    arrayOf(UUID.randomUUID().canonicalString(), byteArrayOf(1, 2, 3, 4, 5, 6), 1234L),
                ).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
                }
                val searches = plan.filter { it.startsWith("SEARCH ") && "messages USING " in it }
                assertEquals(2, searches.size, plan.toString())
                assertTrue(searches.all { "index_messages_direct_wire_timestamp" in it && "senderTimestamp=?" in it },
                    plan.toString())
                assertTrue(searches.any { "timestampCorrected=?" in it && "timestamp=?" in it }, plan.toString())
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
        val name = File(databaseDirectory.root, "summary-upgrade-${UUID.randomUUID()}.db").absolutePath
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
                assertNull(upgraded.textRules().preference())
                assertTrue(upgraded.textRules().conversations().isEmpty())
                assertTrue(upgraded.textRules().archive().isEmpty())
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
        val name = File(databaseDirectory.root, "occupant-upgrade-${UUID.randomUUID()}.db").absolutePath
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
