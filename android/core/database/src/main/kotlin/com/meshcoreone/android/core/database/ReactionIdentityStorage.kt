// AndroidOnly: WP-202 Durable channel reaction target identity and migration.
package com.meshcoreone.android.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID
import java.util.Locale

@Entity(
    tableName = "channel_reaction_identity",
    primaryKeys = ["radioId", "messageID"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["radioId", "id"],
            childColumns = ["radioId", "messageID"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
)
data class ChannelReactionIdentityEntity(
    val radioId: UUID,
    val messageID: UUID,
    val incarnation: String,
    val revision: Long,
)

@Dao
interface ChannelReactionIdentityDao {
    @Query("SELECT * FROM channel_reaction_identity WHERE radioId = :radioId AND messageID = :messageID")
    suspend fun forMessage(radioId: UUID, messageID: UUID): ChannelReactionIdentityEntity?
}

internal object ChannelReactionIdentitySql {
    val triggers = linkedMapOf(
        "channel_reaction_identity_insert" to
        """
        CREATE TRIGGER IF NOT EXISTS channel_reaction_identity_insert
        AFTER INSERT ON messages
        BEGIN
          INSERT INTO channel_reaction_identity (radioId, messageID, incarnation, revision)
          VALUES (NEW.radioId, NEW.id, lower(hex(randomblob(16))), 0)
          ON CONFLICT(radioId, messageID) DO UPDATE SET
            incarnation = excluded.incarnation,
            revision = 0;
        END
        """.trimIndent(),
        "channel_reaction_identity_update" to """
        CREATE TRIGGER IF NOT EXISTS channel_reaction_identity_update
        AFTER UPDATE OF radioId, id, contactID, channelIndex, text, timestamp, senderTimestamp,
          directionRawValue, senderNodeName, senderKeyPrefix ON messages
        BEGIN
          UPDATE channel_reaction_identity
          SET incarnation = CASE
                WHEN OLD.radioId != NEW.radioId OR OLD.id != NEW.id
                  OR revision = 9223372036854775807 THEN lower(hex(randomblob(16)))
                ELSE incarnation
              END,
              revision = CASE
                WHEN OLD.radioId != NEW.radioId OR OLD.id != NEW.id
                  OR revision = 9223372036854775807 THEN 0
                ELSE revision + 1
              END
          WHERE radioId = NEW.radioId AND messageID = NEW.id;
        END
        """.trimIndent(),
        "channel_reaction_identity_delete" to """
        CREATE TRIGGER IF NOT EXISTS channel_reaction_identity_delete
        AFTER DELETE ON messages
        BEGIN
          DELETE FROM channel_reaction_identity
          WHERE radioId = OLD.radioId AND messageID = OLD.id;
        END
        """.trimIndent(),
    )

    fun install(database: SupportSQLiteDatabase) {
        for (trigger in triggers.values) database.execSQL(trigger)
    }

    fun requireInstalled(database: SupportSQLiteDatabase) {
        val cursor = database.query(
            """
            SELECT name, sql FROM sqlite_master
            WHERE type = 'trigger'
              AND name IN (
                'channel_reaction_identity_insert',
                'channel_reaction_identity_update',
                'channel_reaction_identity_delete'
              )
            """.trimIndent(),
        )
        cursor.use {
            val installed = buildMap {
                while (it.moveToNext()) put(it.getString(0), normalize(it.getString(1)))
            }
            check(installed == triggers.mapValues { (_, sql) -> normalize(sql) }) {
                "Channel reaction identity triggers are missing or incompatible"
            }
        }
    }

    private fun normalize(sql: String): String =
        sql.replace(Regex("\\bIF\\s+NOT\\s+EXISTS\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)
}

internal object Migration1To2 : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS channel_reaction_identity (
              radioId TEXT NOT NULL,
              messageID TEXT NOT NULL,
              incarnation TEXT NOT NULL,
              revision INTEGER NOT NULL,
              PRIMARY KEY(radioId, messageID),
              FOREIGN KEY(radioId, messageID) REFERENCES messages(radioId, id)
                ON UPDATE CASCADE ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO channel_reaction_identity (radioId, messageID, incarnation, revision)
            SELECT radioId, id, lower(hex(randomblob(16))), 0 FROM messages
            """.trimIndent(),
        )
        ChannelReactionIdentitySql.install(database)
    }
}
