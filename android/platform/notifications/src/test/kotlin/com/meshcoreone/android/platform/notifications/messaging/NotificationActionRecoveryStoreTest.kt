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

        val accepted = directReply(
            3,
            "accepted before upgrade",
            "meshcore-notification://action/source/REPLY?post=accepted",
        )
        assertEquals(
            NotificationActionAcceptanceWrite.Stored,
            store.recordDirectReplyFence(accepted),
        )
        assertEquals(
            NotificationActionAcceptanceLookup.ExactReplay,
            NotificationActionRecoveryStore(context).checkDirectReplyFence(accepted),
        )
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

    @Test
    fun uncertainChannelReplySurvivesReopeningWithoutChangingItsExactText() {
        val context = recoveryContext()
        val reply = NotificationResponse(
            NotificationPayload.ChannelMessage(RadioId(UUID(0, 1)), 7u, UUID(0, 2)),
            NotificationAction.REPLY,
            "  reply\n\uD800 ",
        )
        val store = NotificationActionRecoveryStore(context)
        assertTrue(store.retainUncertainChannelReply(reply) is NotificationRecoveryWrite.Stored)
        val entry = (NotificationActionRecoveryStore(context).read() as NotificationRecoveryRead.Ready).entries.single()
        assertEquals(reply, entry.response)
        assertEquals(NotificationRecoveryKind.OUTCOME_UNKNOWN, entry.kind)
        assertEquals(
            NotificationRecoveryFailure.INVALID_RECORD,
            (store.retainUncertainChannelReply(reply.copy(action = NotificationAction.MARK_READ, userText = null))
                as NotificationRecoveryWrite.Failed).reason,
        )
        assertEquals(
            NotificationRecoveryFailure.INVALID_RECORD,
            (store.retainUncertainChannelReply(directReply(2, "text")) as NotificationRecoveryWrite.Failed).reason,
        )
    }

    @Test
    fun replyFenceSurvivesStoreRecreationAndDistinguishesActionAndExactText() {
        val context = recoveryContext()
        val token = "meshcore-notification://action/source/REPLY?post=first"
        val accepted = directReply(3, "same text", token)
        val store = NotificationActionRecoveryStore(context)
        assertEquals(
            NotificationActionAcceptanceWrite.Stored,
            store.recordDirectReplyFence(accepted),
        )

        val reopened = NotificationActionRecoveryStore(context)
        assertEquals(NotificationActionAcceptanceLookup.ExactReplay, reopened.checkDirectReplyFence(accepted))
        assertEquals(
            NotificationActionAcceptanceLookup.ChangedText,
            reopened.checkDirectReplyFence(accepted.copy(userText = "different text")),
        )
        assertEquals(
            NotificationActionAcceptanceLookup.NotAccepted,
            reopened.checkDirectReplyFence(accepted.copy(actionToken = "meshcore-notification://action/source/REPLY?post=second")),
        )
        assertEquals(
            NotificationActionAcceptanceWrite.AlreadyStored,
            reopened.recordDirectReplyFence(accepted),
        )
        assertEquals(
            NotificationActionAcceptanceWrite.AlreadyStored,
            reopened.recordDirectReplyFence(accepted.copy(userText = "different text")),
        )
        assertEquals(
            NotificationActionAcceptanceWrite.Stored,
            reopened.recordDirectReplyFence(accepted.copy(actionToken = "meshcore-notification://action/source/REPLY?post=second")),
        )
    }

    @Test
    fun legacyExactFenceSurvivesVersionThreeMigration() {
        val context = recoveryContext()
        val reply = directReply(3, "original text", "legacy-token")
        val store = NotificationActionRecoveryStore(context)
        assertEquals(NotificationActionAcceptanceWrite.Stored, store.recordDirectReplyFence(reply))
        val file = File(folder.root, "notification-action-recovery.sqlite")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP INDEX direct_reply_token_key")
            it.execSQL("ALTER TABLE accepted_direct_replies RENAME TO replies_v4")
            it.execSQL(
                "CREATE TABLE accepted_direct_replies (" +
                    "sequence INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint TEXT NOT NULL UNIQUE)",
            )
            it.execSQL("INSERT INTO accepted_direct_replies(fingerprint) SELECT fingerprint FROM replies_v4")
            it.execSQL("DROP TABLE replies_v4")
            it.version = 3
        }
        assertEquals(NotificationActionAcceptanceLookup.ExactReplay, store.checkDirectReplyFence(reply))
        assertEquals(
            NotificationActionAcceptanceLookup.NotAccepted,
            store.checkDirectReplyFence(reply.copy(userText = "edited legacy text")),
        )
    }

    @Test
    fun fullFenceTableEvictsOnlyOldestWithoutRefreshingOnReplay() {
        val context = recoveryContext()
        val store = NotificationActionRecoveryStore(context)
        val oldest = directReply(3, "oldest", "oldest-token")
        val next = directReply(4, "next", "next-token")
        val newest = directReply(5, "newest", "newest-token")
        assertEquals(NotificationActionAcceptanceWrite.Stored, store.recordDirectReplyFence(oldest))
        assertEquals(NotificationActionAcceptanceWrite.Stored, store.recordDirectReplyFence(next))
        val file = File(folder.root, "notification-action-recovery.sqlite")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.beginTransaction()
            try {
                for (i in 2 until NotificationActionRecoveryStore.MAX_ACCEPTED_DIRECT_REPLIES) {
                    database.execSQL(
                        "INSERT INTO accepted_direct_replies(fingerprint, token_key) VALUES (?, ?)",
                        arrayOf("fingerprint-$i", "token-$i"),
                    )
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
        assertEquals(NotificationActionAcceptanceWrite.AlreadyStored, store.recordDirectReplyFence(oldest))
        assertEquals(NotificationActionAcceptanceWrite.Stored, store.recordDirectReplyFence(newest))
        assertEquals(NotificationActionAcceptanceLookup.NotAccepted, store.checkDirectReplyFence(oldest))
        assertEquals(NotificationActionAcceptanceLookup.ExactReplay, store.checkDirectReplyFence(next))
        assertEquals(NotificationActionAcceptanceLookup.ExactReplay, store.checkDirectReplyFence(newest))
    }

    private fun recoveryContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = folder.root
        }
    }

    private fun directReply(id: Int, text: String, actionToken: String? = null): NotificationResponse {
        val radio = RadioId(UUID(0, 1))
        return NotificationResponse(
            NotificationPayload.DirectMessage(
                EntityKey(radio, UUID(0, 2)),
                UUID(0, id.toLong()),
            ),
            NotificationAction.REPLY,
            text,
            actionToken,
        )
    }

    private fun encodeText(text: String): ByteArray = ByteArray(text.length * 2).also { bytes ->
        text.forEachIndexed { index, char ->
            bytes[index * 2] = (char.code shr 8).toByte()
            bytes[index * 2 + 1] = char.code.toByte()
        }
    }
}
