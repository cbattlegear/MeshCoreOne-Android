// AndroidOnly: WP-309 Issue #729 retirement and archive ownership, including writers outside the receive repository.
package com.meshcoreone.android.core.database

import androidx.sqlite.db.SupportSQLiteDatabase

object TextRuleStorage {
    fun install(db: SupportSQLiteDatabase) {
        val bind = """
            UPDATE recently_blocked_texts SET contactID = (
                SELECT id FROM contacts WHERE contacts.radioId = recently_blocked_texts.radioId
                  AND substr(contacts.publicKey, 1, 6) = recently_blocked_texts.senderPrefix LIMIT 1
            ) WHERE conversationID IS NULL AND contactID IS NULL AND length(senderPrefix) = 6
              AND (SELECT count(*) FROM contacts WHERE contacts.radioId = recently_blocked_texts.radioId
                AND substr(contacts.publicKey, 1, 6) = recently_blocked_texts.senderPrefix) = 1;
        """.trimIndent()
        for ((table, family) in listOf("contacts" to "contact", "remote_node_sessions" to "room", "channels" to "channel")) {
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS text_rules_delete_$family AFTER DELETE ON $table BEGIN
                  ${if (family != "channel") """DELETE FROM recently_blocked_texts WHERE radioId = OLD.radioId AND
                    (conversationID IN (SELECT identity FROM text_rule_conversations
                       WHERE radioId = OLD.radioId AND family = '$family' AND parentID = OLD.id)
                       ${if (family == "contact") "OR contactID = OLD.id" else ""});""" else ""}
                  DELETE FROM text_rule_conversations WHERE radioId = OLD.radioId AND family = '$family' AND parentID = OLD.id;
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
    }
}
