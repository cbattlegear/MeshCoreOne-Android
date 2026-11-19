// AndroidOnly: WP-215 Preserve prior notification drafts while retaining uncertain direct replies copy-only.
package com.meshcoreone.android.platform.notifications.messaging

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.model.RadioId
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class NotificationActionRecoveryStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun v1RecoveryDraftSurvivesUpgradeBesideUncertainDirectReply() {
        val context = recoveryContext()
        val previousText = "  earlier rejected draft\n"
        val previous = directReply(1, previousText)
        val file = File(folder.root, "notification-action-recovery.sqlite")
        val payload = NotificationPayloadCodec.write(Intent(), previous.payload).toUri(0)
        val bytes = payload.toByteArray(Charsets.UTF_8).size.toLong() +
            previousText.toByteArray(Charsets.UTF_8).size
        val database = SQLiteDatabase.openOrCreateDatabase(file.path, null)
        try {
            database.execSQL(
                "CREATE TABLE drafts (sequence INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "id TEXT NOT NULL UNIQUE, payload TEXT NOT NULL, action TEXT NOT NULL, " +
                    "original_text BLOB, byte_count INTEGER NOT NULL CHECK(byte_count >= 0))",
            )
            database.insertOrThrow(
                "drafts",
                null,
                ContentValues().apply {
                    put("id", "40000000-0000-0000-0000-000000000001")
                    put("payload", payload)
                    put("action", NotificationAction.REPLY.rawValue)
                    put("original_text", encodeText(previousText))
                    put("byte_count", bytes)
                },
            )
            database.version = 1
        } finally {
            database.close()
        }

        val store = NotificationActionRecoveryStore(context)
        val uncertainText = " reply with uncertain result "
        val uncertain = directReply(2, uncertainText)
        val write = store.retainUncertainDirectReply(uncertain)
        assertTrue(write is NotificationRecoveryWrite.Stored)

        val recovered = store.read() as NotificationRecoveryRead.Ready
        assertEquals(2, recovered.entries.size)
        assertEquals(previousText, recovered.entries[0].response.userText)
        assertEquals(NotificationRecoveryKind.NOT_ADMITTED, recovered.entries[0].kind)
        assertEquals(uncertainText, recovered.entries[1].response.userText)
        assertEquals(NotificationRecoveryKind.OUTCOME_UNKNOWN, recovered.entries[1].kind)
    }

    @Test
    fun uncertainRecoveryRejectsActionsThatCouldRetargetOrChangeActionKind() {
        val context = recoveryContext()
        val invalid = NotificationResponse(
            NotificationPayload.ChannelMessage(
                RadioId(UUID(0, 1)),
                7u,
                UUID(0, 2),
            ),
            NotificationAction.REPLY,
            "text",
        )
        assertEquals(
            NotificationRecoveryFailure.INVALID_RECORD,
            (NotificationActionRecoveryStore(context).retainUncertainDirectReply(invalid)
                as NotificationRecoveryWrite.Failed).reason,
        )
    }

    private fun recoveryContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = folder.root
        }
    }

    private fun directReply(id: Int, text: String): NotificationResponse {
        val radio = RadioId(UUID(0, 1))
        return NotificationResponse(
            NotificationPayload.DirectMessage(
                EntityKey(radio, UUID(0, 2)),
                UUID(0, id.toLong()),
            ),
            NotificationAction.REPLY,
            text,
        )
    }

    private fun encodeText(text: String): ByteArray = ByteArray(text.length * 2).also { bytes ->
        text.forEachIndexed { index, char ->
            bytes[index * 2] = (char.code shr 8).toByte()
            bytes[index * 2 + 1] = char.code.toByte()
        }
    }
}
