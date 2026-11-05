// AndroidOnly: WP-201 Actual v1 schema/constraints, nanosecond queries, rollback, persistence and radio-isolation evidence.
package com.meshcoreone.android.core.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RoomBoundaryTest {
    private lateinit var db: MeshCoreDatabase
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java).build() }
    @After fun close() { db.close() }

    @Test fun actualSqliteTablesColumnsPrimaryKeysForeignKeysAndIdentityMatchTheKspExport() = runTest {
        db.devices().count()
        val export = JSONObject(File(requireNotNull(System.getProperty("roomSchemaDirectory")),
            "com.meshcoreone.android.core.database.MeshCoreDatabase\\4.json".replace('\\', File.separatorChar)).readText()).getJSONObject("database")
        assertEquals(4, export.getInt("version")); assertEquals(30, export.getJSONArray("entities").length())
        val sqlite = db.openHelper.readableDatabase
        sqlite.query("PRAGMA user_version").use { assertTrue(it.moveToFirst()); assertEquals(4, it.getInt(0)) }
        sqlite.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
            assertTrue(it.moveToFirst()); assertEquals(export.getString("identityHash"), it.getString(0))
        }
        val entities = export.getJSONArray("entities")
        var foreignKeys = 0
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index); val name = entity.getString("tableName")
            val expected = entity.getJSONArray("fields")
            val actual = mutableMapOf<String, Pair<String, Boolean>>()
            sqlite.query("PRAGMA table_info(`$name`)").use { cursor ->
                while (cursor.moveToNext()) actual[cursor.getString(cursor.getColumnIndexOrThrow("name"))] =
                    cursor.getString(cursor.getColumnIndexOrThrow("type")) to (cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1)
            }
            assertEquals(expected.length(), actual.size, name)
            for (fieldIndex in 0 until expected.length()) {
                val field = expected.getJSONObject(fieldIndex)
                val notNull = if (field.has("notNull")) field.getBoolean("notNull") else false
                assertEquals(field.getString("affinity") to notNull, actual[field.getString("columnName")], "$name.${field.getString("columnName")}")
            }
            val pk = entity.getJSONObject("primaryKey").getJSONArray("columnNames")
            val authorityKeys = mapOf(
                "direct_ack_incarnations" to listOf("radioId", "id", "kind"),
                "direct_ack_leases" to listOf("radioId", "requestId"),
                "direct_ack_claims" to listOf("radioId", "requestId"),
                "direct_ack_deliveries" to listOf("radioId", "claimId"),
                "direct_ack_wires" to listOf("radioId", "claimId", "requestId"),
                "direct_ack_operations" to listOf("radioId", "claimId", "requestId"),
                "direct_pending_completions" to listOf("radioId", "captureRequestId"),
                "direct_ack_intents" to listOf("radioId", "requestId"),
                "direct_ack_emissions" to listOf("radioId", "claimId", "wireId", "markRequestId"),
                "direct_ack_radio_exclusions" to listOf("radioId"),
            )
            if (name !in setOf("devices", "debug_log_entries", "link_preview_data", "node_status_snapshots")) {
                assertEquals(authorityKeys[name] ?: listOf("radioId", "id"), (0 until pk.length()).map { pk.getString(it) })
            }
            val keys = if (entity.has("foreignKeys")) entity.getJSONArray("foreignKeys") else JSONArray()
            foreignKeys += keys.length()
            if (keys.length() > 0) {
                assertTrue(name == "message_repeats" || name == "trace_path_runs")
                assertEquals("CASCADE", keys.getJSONObject(0).getString("onDelete"))
            }
            val indices = if (entity.has("indices")) entity.getJSONArray("indices") else JSONArray()
            for (i in 0 until indices.length()) {
                val wanted = indices.getJSONObject(i); val columns = mutableListOf<String>()
                sqlite.query("PRAGMA index_info(`${wanted.getString("name")}`)").use {
                    while (it.moveToNext()) columns += it.getString(it.getColumnIndexOrThrow("name"))
                }
                val names = wanted.getJSONArray("columnNames")
                assertEquals((0 until names.length()).map { names.getString(it) }, columns, wanted.getString("name"))
            }
        }
        assertEquals(2, foreignKeys)
    }

    @Test fun equalIdsObservedRowsAndUpsertsStayIsolatedByRadio() = runTest {
        val id = UUID.randomUUID(); val contactId = UUID.randomUUID()
        val a = message(contactID = contactId, id = id, text = "A"); val b = message(RADIO_B, contactID = contactId, id = id, text = "B")
        db.messages().upsert(listOf(a.toEntity(), b.toEntity()))
        assertEquals("A", db.messages().observeContact(RADIO_A, contactId).first().single().text)
        assertEquals("B", db.messages().observeContact(RADIO_B, contactId).first().single().text)
        db.messages().upsert(a.copy(text = "A updated").toEntity())
        assertEquals("A updated", assertNotNull(db.messages().byId(RADIO_A, id)).text)
        assertEquals("B", assertNotNull(db.messages().byId(RADIO_B, id)).text)
        db.messages().delete(RADIO_A, id); assertNull(db.messages().byId(RADIO_A, id)); assertNotNull(db.messages().byId(RADIO_B, id))
    }

    @Test fun realCompositePrimaryKeyAndForeignKeyViolationsRollBackWithoutRemovingCommittedData() = runTest {
        val kept = message(); db.messages().insert(kept.toEntity())
        val uncommitted = message(RADIO_B)
        assertFailsWith<SQLiteConstraintException> {
            db.withTransaction {
                db.messages().insert(uncommitted.toEntity())
                db.messages().insert(kept.toEntity())
            }
        }
        assertNotNull(db.messages().byId(RADIO_A, kept.id)); assertNull(db.messages().byId(RADIO_B, uncommitted.id))
        val orphan = MessageRepeatDTO(messageID = kept.id, receivedAt = AT, pathNodes = Bytes.of(128))
        assertFailsWith<SQLiteConstraintException> {
            db.repeats().insert(orphan.toEntity(RadioId(RADIO_B), kept.id))
        }
        assertTrue(db.repeats().forMessage(RADIO_B, kept.id).isEmpty())
    }

    @Test fun cancellationBeforeCommitRollsBackBothRadioPartitionsAndPropagates() = runTest {
        val a = message(); val b = message(RADIO_B)
        assertFailsWith<CancellationException> {
            db.withTransaction {
                db.messages().insert(a.toEntity()); db.messages().insert(b.toEntity())
                throw CancellationException("Controlled pre-commit cancellation")
            }
        }
        assertNull(db.messages().byId(RADIO_A, a.id)); assertNull(db.messages().byId(RADIO_B, b.id))
    }

    @Test fun linkedRepeatsCascadeOnlyWithinTheirRadioWhileUnlinkedOrphansSurvive() = runTest {
        val id = UUID.randomUUID(); val a = message(id = id); val b = message(RADIO_B, id = id)
        db.messages().upsert(listOf(a.toEntity(), b.toEntity()))
        val repeat = MessageRepeatDTO(messageID = id, receivedAt = AT, pathNodes = Bytes.of(128, 255))
        db.repeats().insert(repeat.toEntity(RadioId(RADIO_A), id))
        db.repeats().insert(repeat.toEntity(RadioId(RADIO_B), id))
        val unlinked = repeat.copy(id = UUID.randomUUID()); db.repeats().insert(unlinked.toEntity(RadioId(RADIO_A), null))
        db.messages().upsert(a.copy(text = "upsert retains relationships").toEntity())
        assertEquals(2, db.repeats().forMessage(RADIO_A, id).size)
        db.messages().delete(RADIO_A, id)
        assertEquals(listOf(unlinked.id), db.repeats().forMessage(RADIO_A, id).map { it.id })
        assertEquals(1, db.repeats().forMessage(RADIO_B, id).size)
    }

    @Test fun traceRelationshipsAreScopedAndParentBeforeChildIsRequiredOnlyWhenLinked() = runTest {
        val id = UUID.randomUUID()
        val path = SavedTracePathDTO(id, RadioId(RADIO_A), "path", Bytes.of(128, 255), 4, AT, SnapshotList.empty())
        val other = path.copy(radioId = RadioId(RADIO_B))
        val run = TracePathRunDTO(UUID.randomUUID(), AT, true, Long.MAX_VALUE, SnapshotList.of(-1.5, 7.0))
        assertFailsWith<SQLiteConstraintException> { db.traceRuns().insert(run.toEntity(RadioId(RADIO_A), id)) }
        db.tracePaths().insert(path.toEntity()); db.tracePaths().insert(other.toEntity())
        db.traceRuns().insert(run.toEntity(RadioId(RADIO_A), id)); db.traceRuns().insert(run.toEntity(RadioId(RADIO_B), id))
        assertEquals(run, db.traceRuns().forPath(RADIO_A, id).single().toDTO())
        db.tracePaths().delete(RADIO_A, id)
        assertTrue(db.traceRuns().forPath(RADIO_A, id).isEmpty()); assertEquals(1, db.traceRuns().forPath(RADIO_B, id).size)
        val unlinked = run.copy(id = UUID.randomUUID()); db.traceRuns().insert(unlinked.toEntity(RadioId(RADIO_A), null))
        assertNotNull(db.traceRuns().byId(RADIO_A, unlinked.id))
    }

    @Test fun contactDeviceAndSessionValueReferencesDoNotBecomeInventedAutomaticCascades() = runTest {
        val d = device(); val c = contact(); val s = session()
        val m = message(contactID = c.id)
        db.devices().insert(d.toEntity()); db.contacts().insert(c.toEntity()); db.sessions().insert(s.toEntity())
        db.messages().insert(m.toEntity()); db.roomMessages().insert(roomMessage(s.id).toEntity(RadioId(RADIO_A)))
        db.contacts().delete(RADIO_A, c.id); assertNotNull(db.messages().byId(RADIO_A, m.id))
        db.sessions().delete(RADIO_A, s.id); assertEquals(1, db.roomMessages().forSession(RADIO_A, s.id).size)
        db.devices().delete(d.id); assertNotNull(db.messages().byId(RADIO_A, m.id))
    }

    @Test fun nanosecondOrderingAnchorsPaginationAndHighBitStoredCountersUseTheirActualRepresentations() = runTest {
        val c = UUID.randomUUID()
        val rows = listOf(1, 500, 999).map { nano ->
            message(contactID = c).copy(createdAt = AT.withNanos(nano), sortDate = AT.withNanos(nano),
                timestamp = UInt.MAX_VALUE, ackCode = 0x8000_0000u, heardRepeats = Long.MAX_VALUE, retryAttempt = 3_000_000_000L)
        }
        db.messages().upsert(rows.map { it.toEntity() })
        assertEquals(listOf(999, 500, 1), db.messages().newestForContact(RADIO_A, c, 50).map { it.sortDate.nanos })
        assertEquals(rows[1].id, db.messages().newestForContact(RADIO_A, c, 1, 1).single().id)
        assertEquals(2L, db.messages().countContactAtOrAfter(RADIO_A, c, AT.epochSecond, 500))
        val decoded = assertNotNull(db.messages().byId(RADIO_A, rows[0].id)).toDTO()
        assertEquals(UInt.MAX_VALUE, decoded.timestamp); assertEquals(0x8000_0000u, decoded.ackCode)
        assertEquals(Long.MAX_VALUE, decoded.heardRepeats); assertEquals(3_000_000_000L, decoded.retryAttempt)
        assertEquals(1, decoded.createdAt.nano)
    }

    @Test fun deliveryAndFailureTerminalRulesPreserveConditionalOutcomesAndPathFirstWins() = runTest {
        val m = message(status = MessageStatus.DELIVERED); db.messages().insert(m.toEntity())
        assertEquals(0, db.messages().setStatusUnlessDelivered(RADIO_A, m.id, 4))
        assertEquals(0, db.messages().clearRetryingToSent(RADIO_A, m.id))
        assertEquals(0, db.messages().setRetryStatus(RADIO_A, m.id, 5, 1, 3))
        assertEquals(0, db.messages().setAck(RADIO_A, m.id, 1, 2, null))
        assertEquals(1, db.messages().setAck(RADIO_A, m.id, 0xFFFF_FFFFL, 3, 0x8000_0000L))
        assertEquals(0, db.messages().setStatusUnlessDelivered(RADIO_A, UUID.randomUUID(), 4))
        assertEquals(1, db.messages().adoptPathIfUnknown(RADIO_A, m.id, Bytes.EMPTY, 255))
        assertEquals(0, db.messages().adoptPathIfUnknown(RADIO_A, m.id, Bytes.of(128), 1))
        assertEquals(Bytes.EMPTY, assertNotNull(db.messages().byId(RADIO_A, m.id)).pathNodes)
    }

    @Test fun legacyRawValuesRemainStoredButUnsupportedEnumsAndMalformedWidthsSurfaceExplicitly() = runTest {
        val c = contact().toEntity().copy(outPathLength = -1)
        db.contacts().insert(c); assertEquals(255.toUByte(), assertNotNull(db.contacts().byId(RADIO_A, c.id)).toDTO().outPathLength)
        val channel = channel().toEntity().copy(notificationLevelRawValue = -1, legacyIsMuted = true)
        db.channels().insert(channel)
        assertEquals(NotificationLevel.MUTED, assertNotNull(db.channels().byId(RADIO_A, channel.id)).toDTO().notificationLevel)
        assertEquals(-1L, assertNotNull(db.channels().byId(RADIO_A, channel.id)).notificationLevelRawValue)
        val bad = message().toEntity().copy(statusRawValue = 999); db.messages().insert(bad)
        val raw = assertNotNull(db.messages().byId(RADIO_A, bad.id)); assertEquals(999L, raw.statusRawValue)
        assertEquals("message.status", assertFailsWith<DatabaseValueException> { raw.toDTO() }.field)
        assertFailsWith<DatabaseValueException> { device().toEntity().copy(txPower = -129).toDTO() }
        assertFailsWith<DatabaseValueException> { StoredInstant(0, 1_000_000_000) }
    }

    @Test fun sharedNodeHistoryCannotBeDeletedWhileAnyOtherRadioSessionReferencesItsPublicKey() = runTest {
        val a = session(); val b = session(RADIO_B); db.sessions().insert(a.toEntity()); db.sessions().insert(b.toEntity())
        val snapshot = NodeStatusSnapshotDTO(timestamp = AT, nodePublicKey = KEY, uptimeSeconds = UInt.MAX_VALUE,
            latitude = 1.0, longitude = 2.0, altitude = 0.0, neighborSnapshots = SnapshotList.empty())
        db.nodeSnapshots().insert(snapshot.toEntity())
        db.sessions().delete(RADIO_A, a.id); assertEquals(0, db.nodeSnapshots().deleteIfUnreferenced(KEY))
        assertEquals(snapshot, assertNotNull(db.nodeSnapshots().latest(KEY)).toDTO())
        db.sessions().delete(RADIO_B, b.id); assertEquals(1, db.nodeSnapshots().deleteIfUnreferenced(KEY))
        assertNull(db.nodeSnapshots().latest(KEY))
    }

    @Test fun backupProjectionDoesNotReadPreviewBlobsAndSurrogateUpdatePreservesKnownRegionsAndFrozenIdentities() = runTest {
        val m = message().toEntity().copy(linkPreviewImageData = Bytes.of(128), linkPreviewIconData = Bytes.of(255), linkPreviewFetched = true)
        db.messages().insert(m)
        val export = db.messages().backupPageWithoutPreviewBlobs(RADIO_A, 1, 0).single()
        assertNull(export.linkPreviewImageData); assertNull(export.linkPreviewIconData); assertFalse(export.linkPreviewFetched)
        assertEquals(Bytes.of(128), assertNotNull(db.messages().byId(RADIO_A, m.id)).linkPreviewImageData)
        val d = device().copy(knownRegions = SnapshotList.of("source")).toEntity()
        assertEquals(listOf("source"), d.applying(device(id = d.id).copy(knownRegions = SnapshotList.empty())).knownRegions)
        val c = contact().toEntity().copy(lastHeardTimestamp = 0xFFFF_FFFFL)
        val changed = c.applying(contact(RADIO_B).copy(publicKey = Bytes.of(1), lastHeardTimestamp = 0u))
        assertEquals(c.id, changed.id); assertEquals(c.radioId, changed.radioId); assertEquals(c.publicKey, changed.publicKey)
        assertEquals(0xFFFF_FFFFL, changed.lastHeardTimestamp)
    }

    @Test
    fun processOwnedFileDatabaseSurvivesCloseReopenAndUnsupportedVersionDoesNotDestroyRows() = runTest {
        val name = "wp201-persistence-proof.db"
        context.deleteDatabase(name)
        val id = UUID.randomUUID()
        try {
            val first = fileDatabase(name)
            try { first.devices().insert(device(id = id).toEntity()) } finally { first.close() }
            val reopened = fileDatabase(name)
            try {
                assertEquals(id, assertNotNull(reopened.devices().byId(id)).id)
                reopened.openHelper.writableDatabase.execSQL("PRAGMA user_version=99")
            } finally { reopened.close() }
            val unsupported = fileDatabase(name)
            try { assertFailsWith<IllegalStateException> { unsupported.devices().count() } } finally { unsupported.close() }
            val raw = android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            raw.use { database ->
                database.rawQuery("SELECT COUNT(*) FROM devices", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            }
        } finally { context.deleteDatabase(name) }
    }

    // Robolectric's Windows file VFS cannot open WAL sidecars; production keeps Room's AUTOMATIC mode.
    private fun fileDatabase(name: String): MeshCoreDatabase =
        Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()

    private fun Instant.withNanos(nanos: Int): Instant = Instant.ofEpochSecond(epochSecond, nanos.toLong())
}
