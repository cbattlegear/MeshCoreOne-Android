// AndroidOnly: WP-401 Exercise SQLite storage in an isolated test directory.
package com.meshcoreone.android.platform.notifications.messaging

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class NotificationReplySqliteTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun temporaryFolderSupportsDurableSqliteTransactionsWithLongRobolectricTestNames() {
        val file = File(temporary.root, "reply-staging-${UUID.randomUUID()}.db")
        try {
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS or
                    SQLiteDatabase.CREATE_IF_NECESSARY,
            ).use { db ->
                db.execSQL("PRAGMA synchronous=FULL")
                db.beginTransaction()
                try {
                    db.execSQL("CREATE TABLE reply_test(value INTEGER NOT NULL)")
                    db.execSQL("INSERT INTO reply_test VALUES(42)")
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT value FROM reply_test", null).use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals(42, cursor.getInt(0))
                }
            }
        } finally {
            SQLiteDatabase.deleteDatabase(file)
        }
    }
}
