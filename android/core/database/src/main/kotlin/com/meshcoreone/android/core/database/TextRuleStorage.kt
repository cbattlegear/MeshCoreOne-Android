// AndroidOnly: WP-309 Issue #729 retirement and archive ownership, including writers outside the receive repository.
package com.meshcoreone.android.core.database

import androidx.sqlite.db.SupportSQLiteDatabase

object TextRuleStorage {
    const val ROOM_MESSAGE_INCARNATION = "room-message"

    fun install(db: SupportSQLiteDatabase, roomArchiveOwners: Boolean = true) {
        val bind = """
            UPDATE recently_blocked_texts SET contactID = (
                SELECT id FROM contacts WHERE contacts.radioId = recently_blocked_texts.radioId
                  AND substr(contacts.publicKey, 1, 6) = recently_blocked_texts.senderPrefix LIMIT 1
            ) WHERE conversationID IS NULL AND contactID IS NULL AND length(senderPrefix) = 6
              AND (SELECT count(*) FROM contacts WHERE contacts.radioId = recently_blocked_texts.radioId
                AND substr(contacts.publicKey, 1, 6) = recently_blocked_texts.senderPrefix) = 1;
        """.trimIndent()
        for ((table, family) in listOf("contacts" to "contact", "remote_node_sessions" to "room", "channels" to "channel")) {
            val collation = if (family == "room") " COLLATE NOCASE" else ""
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS text_rules_delete_$family AFTER DELETE ON $table BEGIN
                  ${if (family != "channel") """DELETE FROM recently_blocked_texts WHERE radioId = OLD.radioId$collation AND
                    (conversationID$collation IN (SELECT identity FROM text_rule_conversations
                       WHERE radioId = OLD.radioId$collation AND family = '$family' AND parentID = OLD.id$collation)
                       ${if (family == "contact") "OR contactID = OLD.id" else if (roomArchiveOwners) "OR roomSessionID = OLD.id COLLATE NOCASE" else ""});""" else ""}
                  DELETE FROM text_rule_conversations WHERE radioId = OLD.radioId$collation AND family = '$family' AND parentID = OLD.id$collation;
                  ${if (family == "contact") bind else ""}
                END
            """.trimIndent())
        }
        db.execSQL("CREATE TRIGGER IF NOT EXISTS text_rules_bind_contact AFTER INSERT ON contacts BEGIN $bind END")
        for (operation in listOf("DELETE", "UPDATE OF radioId")) {
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS text_rules_retire_radio_${operation.substringBefore(' ')}
                AFTER $operation ON devices WHEN NOT EXISTS (SELECT 1 FROM devices WHERE radioId = OLD.radioId) BEGIN
                  DELETE FROM recently_blocked_texts WHERE radioId = OLD.radioId;
                  DELETE FROM text_rule_conversations WHERE radioId = OLD.radioId;
                END
            """.trimIndent())
        }
        val identity = """
            INSERT OR REPLACE INTO direct_ack_incarnations (radioId, id, kind, incarnation, revision)
            VALUES (NEW.radioId, NEW.id, '$ROOM_MESSAGE_INCARNATION', lower(hex(randomblob(32))), 0);
        """.trimIndent()
        val retire = """
            DELETE FROM direct_ack_incarnations
            WHERE radioId = OLD.radioId AND id = OLD.id AND kind = '$ROOM_MESSAGE_INCARNATION';
        """.trimIndent()
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS text_rules_room_message_insert AFTER INSERT ON room_messages BEGIN
              $identity
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS text_rules_room_message_update
            AFTER UPDATE OF radioId, id, sessionID, authorKeyPrefix, authorName, text, timestamp,
              createdAt_seconds, createdAt_nanos, isFromSelf, deduplicationKey ON room_messages BEGIN
              $retire
              $identity
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS text_rules_room_message_delete AFTER DELETE ON room_messages BEGIN
              $retire
            END
        """.trimIndent())
    }
}
