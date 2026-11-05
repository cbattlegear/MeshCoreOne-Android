// AndroidOnly: WP-202 Real Room exclusion queries and forward v2 migration.
package com.meshcoreone.android.core.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
class DirectAckExclusionStorageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun v2MigrationRetainsLegacyUnknownAndKnownCodesAcrossReopen() = runTest {
        val name = "ack-exclusion-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        assertTrue(file.parentFile!!.exists() || file.parentFile!!.mkdirs())
        val schema = JSONObject(File(requireNotNull(System.getProperty("roomSchemaDirectory")),
            "com.meshcoreone.android.core.database.MeshCoreDatabase${File.separator}2.json").readText())
            .getJSONObject("database")
        val radio = UUID.randomUUID()
        val unknownClaim = UUID.randomUUID()
        val knownClaim = UUID.randomUUID()
        val unknownWire = UUID.randomUUID()
        val knownWire = UUID.randomUUID()
        val current = UUID.randomUUID()
        val unknown = DirectAckWireEntity(radio, unknownClaim, unknownWire, 123, null)
        val known = DirectAckWireEntity(radio, knownClaim, knownWire, 456, 789)
        try {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                    .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(old: SupportSQLiteDatabase) {
                            val entities = schema.getJSONArray("entities")
                            for (i in 0 until entities.length()) {
                                val entity = entities.getJSONObject(i)
                                val table = entity.getString("tableName")
                                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                                val indices = entity.getJSONArray("indices")
                                for (j in 0 until indices.length()) {
                                    old.execSQL(indices.getJSONObject(j).getString("createSql")
                                        .replace("\${TABLE_NAME}", table))
                                }
                            }
                            val setup = schema.getJSONArray("setupQueries")
                            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                            for (wire in listOf(unknown, known)) {
                                old.execSQL(
                                    "INSERT INTO direct_ack_wires VALUES (?,?,?,?,?)",
                                    arrayOf(wire.radioId.toString().uppercase(), wire.claimId.toString().uppercase(),
                                        wire.requestId.toString().uppercase(), wire.predictedCode, wire.acceptedCode),
                                )
                            }
                            DirectAckStorage.migration1To2.migrate(old)
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                            error("The v2 fixture must be newly created")
                    }).build(),
            )
            try { helper.writableDatabase } finally { helper.close() }
            repeat(2) {
                val db = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
                    .addMigrations(DirectAckExclusionStorage.migration2To3)
                    .addCallback(DirectAckStorage.callback).build()
                try {
                    assertEquals(3, db.openHelper.readableDatabase.version)
                    assertEquals(listOf(unknown), db.directAcks().wires(radio, unknownClaim))
                    assertEquals(listOf(known), db.directAcks().wires(radio, knownClaim))
                    val exclusions = db.directAckExclusions()
                    assertEquals(0, exclusions.count(radio).toInt())
                    assertEquals(2, exclusions.legacyCount(radio).toInt())
                    assertTrue(exclusions.unresolvedCompetitor(radio, current))
                    assertTrue(exclusions.competingCode(radio, current, 123))
                    assertTrue(exclusions.competingCode(radio, current, 456))
                    assertTrue(exclusions.competingCode(radio, current, 789))
                    assertFalse(exclusions.competingCode(radio, current, 999))
                    assertFalse(exclusions.saturated(radio))
                    DirectAckStorage.verify(db.openHelper.readableDatabase)
                } finally { db.close() }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun knownAndUnresolvedCompetitorsAreRadioScopedAndDefinitelyUnsentIsExcluded() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .addCallback(DirectAckStorage.callback).build()
        val radio = UUID.randomUUID()
        val otherRadio = UUID.randomUUID()
        val claim = UUID.randomUUID()
        val wire = UUID.randomUUID()
        val current = UUID.randomUUID()
        val issuer = UUID.randomUUID()
        try {
            db.directAcks().wire(DirectAckWireEntity(radio, claim, wire, 123, null))
            val marker = DirectAckEmissionEntity(radio, claim, wire, issuer, UUID.randomUUID(),
                123, "MAY_HAVE_WRITTEN", null, null)
            val exclusions = db.directAckExclusions()
            exclusions.emission(marker)
            assertTrue(exclusions.unresolvedCompetitor(radio, current))
            assertFalse(exclusions.unresolvedCompetitor(radio, claim))
            assertFalse(exclusions.unresolvedCompetitor(otherRadio, current))
            exclusions.emission(marker.copy(state = "DEFINITELY_UNSENT", resolutionRequestId = UUID.randomUUID()))
            assertFalse(exclusions.unresolvedCompetitor(radio, current))
            assertFalse(exclusions.competingCode(radio, current, 123))
            exclusions.emission(marker.copy(state = "CONFLICT"))
            assertTrue(exclusions.unresolvedCompetitor(radio, current))
            assertTrue(exclusions.conflictedClaim(radio, claim))
        } finally { db.close() }
    }
}
