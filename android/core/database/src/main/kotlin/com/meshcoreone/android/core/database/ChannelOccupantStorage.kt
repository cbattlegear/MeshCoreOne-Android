// AndroidOnly: WP-202 Independent persisted channel-occupant fencing; not receive-origin authority.
package com.meshcoreone.android.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.Locale
import java.util.UUID

@Entity(tableName = "channel_occupant_authority", primaryKeys = ["radioId", "index"])
data class ChannelOccupantAuthorityEntity(
    val radioId: UUID,
    val index: Long,
    val epoch: String,
    val boundRowId: UUID?,
    val isResolved: Boolean,
)

/**
 * Trusted storage primitives, not proof issuers. Resolution requires the consumer's validated
 * lifecycle/operation scope and Room transaction; a row or affected-row count is not radio evidence.
 * Supported raw channel/device DML is fenced by triggers. Arbitrary writes to this authority table
 * or trigger DDL are not an authenticated mutation route.
 */
@Dao
interface ChannelOccupantAuthorityDao {
    @Query("SELECT * FROM channel_occupant_authority WHERE radioId = :radioId AND `index` = :index")
    suspend fun forSlot(radioId: UUID, index: Long): ChannelOccupantAuthorityEntity?

    @Query("""
        UPDATE channel_occupant_authority
        SET epoch = lower(hex(randomblob(16))), boundRowId = NULL, isResolved = 0
    """)
    suspend fun invalidateAll(): Int

    @Query("""
        UPDATE channel_occupant_authority
        SET epoch = lower(hex(randomblob(16))), boundRowId = NULL, isResolved = 0
        WHERE radioId = :radioId
    """)
    suspend fun invalidateRadio(radioId: UUID): Int

    @Query("""
        UPDATE channel_occupant_authority SET boundRowId = :rowId, isResolved = 1
        WHERE radioId = :radioId AND `index` = :index AND epoch = :expectedEpoch
          AND EXISTS (SELECT 1 FROM devices WHERE radioId = :radioId)
          AND (SELECT COUNT(*) FROM channels WHERE radioId = :radioId AND `index` = :index) = 1
          AND EXISTS (
            SELECT 1 FROM channels WHERE radioId = :radioId AND `index` = :index
              AND id = :rowId AND secret = :secret AND isEnabled = 1
          )
    """)
    suspend fun resolveCurrent(
        radioId: UUID,
        index: Long,
        expectedEpoch: String,
        rowId: UUID,
        secret: Bytes,
    ): Int
}

internal object ChannelOccupantSql {
    private fun invalidateSlot(radio: String, index: String) = """
        INSERT INTO channel_occupant_authority (radioId, `index`, epoch, boundRowId, isResolved)
        VALUES ($radio, $index, lower(hex(randomblob(16))), NULL, 0)
        ON CONFLICT(radioId, `index`) DO UPDATE SET
          epoch = excluded.epoch, boundRowId = NULL, isResolved = 0;
    """.trimIndent()

    val triggers = linkedMapOf(
        "channel_occupant_insert" to """
            CREATE TRIGGER IF NOT EXISTS channel_occupant_insert AFTER INSERT ON channels
            BEGIN
              UPDATE channel_occupant_authority
              SET epoch = lower(hex(randomblob(16))), boundRowId = NULL, isResolved = 0
              WHERE radioId = NEW.radioId AND boundRowId = NEW.id;
              ${invalidateSlot("NEW.radioId", "NEW.`index`")}
            END
        """.trimIndent(),
        "channel_occupant_delete" to """
            CREATE TRIGGER IF NOT EXISTS channel_occupant_delete AFTER DELETE ON channels
            BEGIN
              ${invalidateSlot("OLD.radioId", "OLD.`index`")}
            END
        """.trimIndent(),
        "channel_occupant_update" to """
            CREATE TRIGGER IF NOT EXISTS channel_occupant_update
            AFTER UPDATE OF radioId, id, `index`, secret, isEnabled ON channels
            WHEN OLD.radioId IS NOT NEW.radioId OR OLD.id IS NOT NEW.id
              OR OLD.`index` IS NOT NEW.`index` OR OLD.secret IS NOT NEW.secret
              OR OLD.isEnabled IS NOT NEW.isEnabled
            BEGIN
              ${invalidateSlot("OLD.radioId", "OLD.`index`")}
              ${invalidateSlot("NEW.radioId", "NEW.`index`")}
            END
        """.trimIndent(),
        // A failed INSERT probe in Room's upsert must not invalidate equal metadata.
        "channel_occupant_device_replace" to """
            CREATE TRIGGER IF NOT EXISTS channel_occupant_device_replace BEFORE INSERT ON devices
            BEGIN
              UPDATE channel_occupant_authority
              SET epoch = lower(hex(randomblob(16))), boundRowId = NULL, isResolved = 0
              WHERE radioId IN (SELECT radioId FROM devices WHERE id = NEW.id AND radioId IS NOT NEW.radioId);
            END
        """.trimIndent(),
        "channel_occupant_device_insert" to """
            CREATE TRIGGER IF NOT EXISTS channel_occupant_device_insert AFTER INSERT ON devices
            BEGIN
              UPDATE channel_occupant_authority
              SET epoch = lower(hex(randomblob(16))), boundRowId = NULL, isResolved = 0
              WHERE radioId = NEW.radioId;
            END
        """.trimIndent(),
        "channel_occupant_device_delete" to """
            CREATE TRIGGER IF NOT EXISTS channel_occupant_device_delete AFTER DELETE ON devices
            BEGIN
              UPDATE channel_occupant_authority
              SET epoch = lower(hex(randomblob(16))), boundRowId = NULL, isResolved = 0
              WHERE radioId = OLD.radioId;
            END
        """.trimIndent(),
        "channel_occupant_device_update" to """
            CREATE TRIGGER IF NOT EXISTS channel_occupant_device_update
            AFTER UPDATE OF id, radioId, publicKey ON devices
            WHEN OLD.id IS NOT NEW.id OR OLD.radioId IS NOT NEW.radioId OR OLD.publicKey IS NOT NEW.publicKey
            BEGIN
              UPDATE channel_occupant_authority
              SET epoch = lower(hex(randomblob(16))), boundRowId = NULL, isResolved = 0
              WHERE radioId = OLD.radioId OR radioId = NEW.radioId;
            END
        """.trimIndent(),
    )

    fun install(database: SupportSQLiteDatabase) {
        triggers.values.forEach(database::execSQL)
    }

    fun requireInstalled(database: SupportSQLiteDatabase) {
        val actual = database.query(
            "SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'channel_occupant_%'",
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), normalize(cursor.getString(1)))
            }
        }
        check(actual == triggers.mapValues { (_, sql) -> normalize(sql) }) {
            "Channel occupant authority triggers are missing or incompatible"
        }
    }

    private fun normalize(sql: String): String =
        sql.replace(Regex("\\bIF\\s+NOT\\s+EXISTS\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT)
}

internal object Migration2To3 : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS channel_occupant_authority (
              radioId TEXT NOT NULL, `index` INTEGER NOT NULL, epoch TEXT NOT NULL,
              boundRowId TEXT, isResolved INTEGER NOT NULL, PRIMARY KEY(radioId, `index`)
            )
        """.trimIndent())
        database.execSQL("""
            INSERT INTO channel_occupant_authority (radioId, `index`, epoch, boundRowId, isResolved)
            SELECT radioId, `index`, lower(hex(randomblob(16))), NULL, 0 FROM channels GROUP BY radioId, `index`
        """.trimIndent())
        ChannelOccupantSql.install(database)
    }
}
