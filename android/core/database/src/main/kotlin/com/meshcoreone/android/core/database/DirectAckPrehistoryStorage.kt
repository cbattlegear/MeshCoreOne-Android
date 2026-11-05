// AndroidOnly: WP-202 Durable uncertainty about physical sends outside retained app authority.
package com.meshcoreone.android.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "direct_ack_prehistory", primaryKeys = ["singleton"])
data class DirectAckPrehistoryEntity(val singleton: Long, val state: String)

@Dao
interface DirectAckPrehistoryDao {
    @Query("SELECT * FROM direct_ack_prehistory LIMIT 2")
    suspend fun rows(): List<DirectAckPrehistoryEntity>
}

object DirectAckPrehistoryStorage {
    private val triggers = mapOf(
        "preauthority_history_insert" to
            "CREATE TRIGGER preauthority_history_insert BEFORE INSERT ON direct_ack_prehistory " +
            "WHEN NEW.singleton != 1 OR NEW.state != 'UNKNOWN' " +
            "OR EXISTS(SELECT 1 FROM direct_ack_prehistory) " +
            "BEGIN SELECT RAISE(ABORT,'physical prehistory cannot be certified'); END",
        "preauthority_history_update" to
            "CREATE TRIGGER preauthority_history_update BEFORE UPDATE ON direct_ack_prehistory " +
            "BEGIN SELECT RAISE(ABORT,'physical prehistory is immutable'); END",
        "preauthority_history_delete" to
            "CREATE TRIGGER preauthority_history_delete BEFORE DELETE ON direct_ack_prehistory " +
            "BEGIN SELECT RAISE(ABORT,'physical prehistory is immutable'); END",
    )

    fun createForMigration(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE direct_ack_prehistory (" +
                "singleton INTEGER NOT NULL,state TEXT NOT NULL,PRIMARY KEY(singleton))",
        )
        initialize(db)
    }

    fun initialize(db: SupportSQLiteDatabase) {
        triggers.values.forEach(db::execSQL)
        db.execSQL("INSERT INTO direct_ack_prehistory (singleton,state) VALUES (1,'UNKNOWN')")
        verify(db)
    }

    fun verify(db: SupportSQLiteDatabase) {
        val actual = mutableMapOf<String, String>()
        db.query("SELECT name,sql FROM sqlite_master WHERE type='trigger' AND name LIKE 'preauthority_history_%'").use {
            while (it.moveToNext()) actual[it.getString(0)] = it.getString(1)
        }
        check(actual == triggers) { "Physical prehistory protection is missing or changed" }
        db.query("SELECT singleton,state FROM direct_ack_prehistory LIMIT 2").use {
            check(it.moveToNext() && it.getLong(0) == 1L && it.getString(1) == "UNKNOWN" && !it.moveToNext()) {
                "Physical prehistory metadata is missing or malformed"
            }
        }
    }
}
