// AndroidOnly: WP-401 Cold-start-safe notification action receiver and bounded process dispatcher.
// AndroidOnly: WP-215 Process-memory acceptance fencing and lossless reply intake.
// AndroidOnly: WP-215 Separate, bounded custody of rejected and uncertain direct replies.
package com.meshcoreone.android.platform.notifications.messaging

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationReadReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.platform.notifications.R
import java.io.File
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

open class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val payload = NotificationPayloadCodec.read(intent) ?: return
        val action = intent.getStringExtra(EXTRA_ACTION)?.let { raw ->
            NotificationAction.entries.firstOrNull { it.rawValue == raw } ?: return
        }
        val originalText = if (action == NotificationAction.REPLY) {
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REMOTE_INPUT_KEY)?.toString()
                ?.takeIf(String::isNotBlank) ?: return
        } else {
            null
        }
        val response = NotificationResponse(payload, action, originalText, directReplyActionToken(intent, payload, action))
        val result = NotificationActionDispatcher.enqueue(response)
        if (result == EnqueueResult.REJECTED_CAPACITY &&
            (action == NotificationAction.REPLY || action == NotificationAction.MARK_READ)
        ) {
            // Android 12 forbids an action broadcast from starting a recovery activity.
            NotificationActionRecovery.requireManualOpen()
            val pending = checkNotNull(goAsync()) { "Recovery requires a platform-dispatched broadcast" }
            recoveryQueue.submit(
                response,
                work = {
                    showRetention(context, NotificationActionRecovery.retain(context, response))
                },
                rejected = {
                    NotificationActionRecovery.handoffNotRetained()
                    showRetention(context, NotificationRecoveryRetention.NOT_RETAINED)
                },
                timedOut = {
                    NotificationActionRecovery.requireManualOpen()
                    logger.severe("Notification recovery broadcast lifetime ended; custody outcome remains unconfirmed")
                },
                finish = pending::finish,
            )
        }
        if (action == null) {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }?.let(context::startActivity)
        }
    }

    companion object {
        internal const val EXTRA_ACTION = "mc1.notification.action"
        internal const val REMOTE_INPUT_KEY = "mc1.notification.reply"
        private val recoveryQueue = NotificationRecoveryBroadcastQueue()
        private val main = Handler(Looper.getMainLooper())
        private val logger = Logger.getLogger("com.mc1.NotificationRecovery")

        private fun directReplyActionToken(
            intent: Intent,
            payload: NotificationPayload,
            action: NotificationAction?,
        ): String? {
            if (action != NotificationAction.REPLY || payload !is NotificationPayload.DirectMessage) return null
            val data = intent.data ?: return null
            if (intent.action != AndroidMessagingNotificationDelivery.ACTION_PREFIX + action.rawValue ||
                data.scheme != "meshcore-notification" ||
                data.host != "action" ||
                data.pathSegments.lastOrNull() != action.rawValue ||
                data.getQueryParameter("post").isNullOrBlank()
            ) {
                return null
            }
            return data.toString()
        }

        internal fun showRetention(context: Context, retained: NotificationRecoveryRetention) {
            val status = when (retained) {
                NotificationRecoveryRetention.DURABLE -> R.string.notification_action_recovery_saved
                NotificationRecoveryRetention.PROCESS_ONLY -> R.string.notification_action_recovery_memory_only
                NotificationRecoveryRetention.NOT_RETAINED -> R.string.notification_action_recovery_not_retained
            }
            main.post { Toast.makeText(context, status, Toast.LENGTH_LONG).show() }
        }

        fun showChannelReplyRecovery(context: Context, retained: NotificationRecoveryRetention) =
            showRetention(context, retained)

        fun showChannelReadUnavailable(context: Context) {
            main.post {
                Toast.makeText(context, R.string.notification_action_channel_read_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }
}

/**
 * FIFO handoff, not durable custody. A deadline finishes the broadcast without cancelling a
 * possibly committing write. Timed-out work still occupies its bounded slot until it settles.
 */
internal class NotificationRecoveryBroadcastQueue(
    private val timeoutMillis: Long = 8_000,
) : AutoCloseable {
    private val lock = Any()
    private var count = 0
    private var bytes = 0L
    private val worker = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(32),
        { task -> Thread(task, "mc1-rejected-actions").apply { isDaemon = true } },
    )
    private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "mc1-rejected-action-deadlines").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun submit(
        response: NotificationResponse,
        work: () -> Unit,
        rejected: () -> Unit,
        timedOut: () -> Unit,
        finish: () -> Unit,
    ): Boolean {
        val size = if ((response.userText?.length ?: 0) > NotificationActionRecoveryStore.MAX_ENTRY_BYTES) {
            NotificationActionRecoveryStore.MAX_ENTRY_BYTES + 1
        } else {
            NotificationActionRecovery.recoveryBytes(response)
        }
        val completed = AtomicBoolean()
        val finishOnce = { if (completed.compareAndSet(false, true)) finish() }
        synchronized(lock) {
            if (size > NotificationActionRecoveryStore.MAX_ENTRY_BYTES ||
                count >= NotificationActionRecoveryStore.MAX_ENTRIES ||
                bytes + size > NotificationActionRecoveryStore.MAX_TOTAL_BYTES
            ) {
                try { rejected() } finally { finishOnce() }
                return false
            }
            count += 1
            bytes += size
            val deadline = try {
                deadlines.schedule({
                    if (completed.compareAndSet(false, true)) {
                        try { timedOut() } finally { finish() }
                    }
                }, timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                count -= 1
                bytes -= size
                try { rejected() } finally { finishOnce() }
                return false
            }
            try {
                worker.execute {
                    try {
                        work()
                    } finally {
                        deadline.cancel(false)
                        synchronized(lock) {
                            count -= 1
                            bytes -= size
                        }
                        finishOnce()
                    }
                }
            } catch (_: RejectedExecutionException) {
                deadline.cancel(false)
                count -= 1
                bytes -= size
                try { rejected() } finally { finishOnce() }
                return false
            }
        }
        return true
    }

    override fun close() {
        worker.shutdown()
        deadlines.shutdown()
    }
}

enum class NotificationContentTapResult { NOT_NOTIFICATION, ENQUEUED, MALFORMED, QUEUE_FULL }

object NotificationContentTap {
    fun enqueue(intent: Intent): NotificationContentTapResult {
        if (intent.action != AndroidMessagingNotificationDelivery.ACTION_PREFIX + AndroidMessagingNotificationDelivery.ACTION_OPEN) {
            return NotificationContentTapResult.NOT_NOTIFICATION
        }
        val payload = NotificationPayloadCodec.read(intent) ?: return NotificationContentTapResult.MALFORMED
        return when (NotificationActionDispatcher.enqueue(NotificationResponse(payload, null, null))) {
            EnqueueResult.DELIVERED_OR_QUEUED -> NotificationContentTapResult.ENQUEUED
            EnqueueResult.REJECTED_CAPACITY -> NotificationContentTapResult.QUEUE_FULL
        }
    }
}

class NotificationActionCommit internal constructor() : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NotificationActionCommit>

    @Volatile private var committed = false

    fun navigationCommitted() { committed = true }
    internal fun reset() { committed = false }
    internal fun isCommitted() = committed
}

internal enum class EnqueueResult { DELIVERED_OR_QUEUED, REJECTED_CAPACITY }

enum class NotificationRecoveryFailure { CAPACITY, TOO_LARGE, STORAGE_UNAVAILABLE, INVALID_RECORD }

enum class NotificationRecoveryKind { NOT_ADMITTED, OUTCOME_UNKNOWN }

class RejectedNotificationAction(
    val id: UUID,
    val response: NotificationResponse,
    val kind: NotificationRecoveryKind = NotificationRecoveryKind.NOT_ADMITTED,
)

sealed interface NotificationRecoveryRead {
    class Ready(val entries: List<RejectedNotificationAction>) : NotificationRecoveryRead
    class Failed(val reason: NotificationRecoveryFailure) : NotificationRecoveryRead
}

sealed interface NotificationRecoveryWrite {
    class Stored(val entry: RejectedNotificationAction) : NotificationRecoveryWrite
    class Failed(val reason: NotificationRecoveryFailure) : NotificationRecoveryWrite
}

sealed interface NotificationActionAcceptanceLookup {
    data object NotApplicable : NotificationActionAcceptanceLookup
    data object NotAccepted : NotificationActionAcceptanceLookup
    data object ExactReplay : NotificationActionAcceptanceLookup
    data object ChangedText : NotificationActionAcceptanceLookup
    class Failed(val reason: NotificationRecoveryFailure) : NotificationActionAcceptanceLookup
}

sealed interface NotificationActionAcceptanceWrite {
    data object NotApplicable : NotificationActionAcceptanceWrite
    data object Stored : NotificationActionAcceptanceWrite
    data object AlreadyStored : NotificationActionAcceptanceWrite
    class Failed(val reason: NotificationRecoveryFailure) : NotificationActionAcceptanceWrite
}

/**
 * Copy-only recovery. Rejected actions and uncertain direct replies are retained separately from
 * accepted-action receipts; neither record grants permission to resend or replay a channel index.
 */
class NotificationActionRecoveryStore(private val context: Context) {
    internal data class DirectReplyIdentity(val tokenKey: String, val fingerprint: String)

    fun checkDirectReplyFence(response: NotificationResponse): NotificationActionAcceptanceLookup =
        synchronized(lock) {
            val identity = directReplyIdentity(response)
                ?: return@synchronized NotificationActionAcceptanceLookup.NotApplicable
            try {
                val database = open(create = false)
                    ?: return@synchronized NotificationActionAcceptanceLookup.NotAccepted
                database.use {
                    it.lookupDirectReply(identity)
                }
            } catch (_: InvalidRecoveryRecord) {
                NotificationActionAcceptanceLookup.Failed(NotificationRecoveryFailure.INVALID_RECORD)
            } catch (_: SQLiteException) {
                NotificationActionAcceptanceLookup.Failed(NotificationRecoveryFailure.STORAGE_UNAVAILABLE)
            } catch (_: SecurityException) {
                NotificationActionAcceptanceLookup.Failed(NotificationRecoveryFailure.STORAGE_UNAVAILABLE)
            }
        }

    private fun SQLiteDatabase.lookupDirectReply(identity: DirectReplyIdentity): NotificationActionAcceptanceLookup {
        rawQuery(
            "SELECT fingerprint FROM accepted_direct_replies WHERE token_key = ?",
            arrayOf(identity.tokenKey),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return if (cursor.getString(0) == identity.fingerprint) NotificationActionAcceptanceLookup.ExactReplay
                else NotificationActionAcceptanceLookup.ChangedText
            }
        }
        // Version 3 retained only the exact fingerprint, so the token key cannot be backfilled.
        return rawQuery(
            "SELECT 1 FROM accepted_direct_replies WHERE fingerprint = ? AND token_key IS NULL",
            arrayOf(identity.fingerprint),
        ).use { cursor ->
            if (cursor.moveToFirst()) NotificationActionAcceptanceLookup.ExactReplay
            else NotificationActionAcceptanceLookup.NotAccepted
        }
    }

    fun recordDirectReplyFence(response: NotificationResponse): NotificationActionAcceptanceWrite =
        synchronized(lock) {
            val identity = directReplyIdentity(response)
                ?: return@synchronized NotificationActionAcceptanceWrite.NotApplicable
            try {
                (open(create = true) ?: throw InvalidRecoveryRecord()).use { database ->
                    database.beginTransaction()
                    val alreadyStored: Boolean
                    try {
                        alreadyStored = database.lookupDirectReply(identity) != NotificationActionAcceptanceLookup.NotAccepted
                        if (!alreadyStored) {
                            database.rawQuery("SELECT COUNT(*) FROM accepted_direct_replies", null).use {
                                if (!it.moveToFirst()) throw InvalidRecoveryRecord()
                                if (it.getLong(0) >= MAX_ACCEPTED_DIRECT_REPLIES &&
                                    database.delete(
                                        "accepted_direct_replies",
                                        "sequence = (SELECT MIN(sequence) FROM accepted_direct_replies)",
                                        null,
                                    ) != 1
                                ) {
                                    throw InvalidRecoveryRecord()
                                }
                            }
                            database.insertOrThrow(
                                "accepted_direct_replies",
                                null,
                                ContentValues().apply {
                                    put("fingerprint", identity.fingerprint)
                                    put("token_key", identity.tokenKey)
                                },
                            )
                        }
                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                    if (alreadyStored) NotificationActionAcceptanceWrite.AlreadyStored
                    else NotificationActionAcceptanceWrite.Stored
                }
            } catch (_: InvalidRecoveryRecord) {
                NotificationActionAcceptanceWrite.Failed(NotificationRecoveryFailure.INVALID_RECORD)
            } catch (_: SQLiteException) {
                NotificationActionAcceptanceWrite.Failed(NotificationRecoveryFailure.STORAGE_UNAVAILABLE)
            } catch (_: SecurityException) {
                NotificationActionAcceptanceWrite.Failed(NotificationRecoveryFailure.STORAGE_UNAVAILABLE)
            }
        }

    fun retain(response: NotificationResponse): NotificationRecoveryWrite =
        retain(response, NotificationRecoveryKind.NOT_ADMITTED)

    fun retainUncertainDirectReply(response: NotificationResponse): NotificationRecoveryWrite {
        if (response.action != NotificationAction.REPLY ||
            response.payload !is NotificationPayload.DirectMessage
        ) {
            return NotificationRecoveryWrite.Failed(NotificationRecoveryFailure.INVALID_RECORD)
        }
        return retain(response, NotificationRecoveryKind.OUTCOME_UNKNOWN)
    }

    private fun retain(
        response: NotificationResponse,
        kind: NotificationRecoveryKind,
    ): NotificationRecoveryWrite = synchronized(lock) {
        val action = response.action
            ?: return@synchronized NotificationRecoveryWrite.Failed(NotificationRecoveryFailure.INVALID_RECORD)
        if (!isRecoverable(response)) {
            return@synchronized NotificationRecoveryWrite.Failed(NotificationRecoveryFailure.INVALID_RECORD)
        }
        val payload = NotificationPayloadCodec.write(Intent(), response.payload).toUri(0)
        val bytes = payload.toByteArray(Charsets.UTF_8).size.toLong() +
            (response.userText?.toByteArray(Charsets.UTF_8)?.size ?: 0)
        if (bytes > MAX_ENTRY_BYTES) {
            return@synchronized NotificationRecoveryWrite.Failed(NotificationRecoveryFailure.TOO_LARGE)
        }
        val decodedPayload = try {
            NotificationPayloadCodec.read(Intent.parseUri(payload, 0))
        } catch (_: URISyntaxException) {
            null
        }
        if (decodedPayload != response.payload) {
            return@synchronized NotificationRecoveryWrite.Failed(NotificationRecoveryFailure.INVALID_RECORD)
        }
        when (val existing = read()) {
            is NotificationRecoveryRead.Failed -> return@synchronized NotificationRecoveryWrite.Failed(existing.reason)
            is NotificationRecoveryRead.Ready -> Unit
        }
        val entry = RejectedNotificationAction(UUID.randomUUID(), response, kind)
        try {
            (open(create = true) ?: throw InvalidRecoveryRecord()).use { database ->
                database.beginTransaction()
                try {
                    database.rawQuery("SELECT COUNT(*), COALESCE(SUM(byte_count), 0) FROM drafts", null).use {
                        if (!it.moveToFirst()) throw InvalidRecoveryRecord()
                        if (it.getLong(0) >= MAX_ENTRIES || it.getLong(1) + bytes > MAX_TOTAL_BYTES) {
                            return@synchronized NotificationRecoveryWrite.Failed(NotificationRecoveryFailure.CAPACITY)
                        }
                    }
                    database.insertOrThrow(
                        "drafts", null,
                        ContentValues().apply {
                            put("id", entry.id.toString())
                            put("payload", payload)
                            put("action", action.rawValue)
                            put("original_text", response.userText?.let(::encodeText))
                            put("byte_count", bytes)
                            put("recovery_kind", kind.name)
                        },
                    )
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
            NotificationRecoveryWrite.Stored(entry)
        } catch (_: InvalidRecoveryRecord) {
            NotificationRecoveryWrite.Failed(NotificationRecoveryFailure.INVALID_RECORD)
        } catch (_: SQLiteException) {
            NotificationRecoveryWrite.Failed(NotificationRecoveryFailure.STORAGE_UNAVAILABLE)
        } catch (_: SecurityException) {
            NotificationRecoveryWrite.Failed(NotificationRecoveryFailure.STORAGE_UNAVAILABLE)
        }
    }

    fun read(): NotificationRecoveryRead = synchronized(lock) {
        try {
            val database = open(create = false) ?: return@synchronized NotificationRecoveryRead.Ready(emptyList())
            database.use {
                val entries = mutableListOf<RejectedNotificationAction>()
                val ids = mutableSetOf<UUID>()
                var bytes = 0L
                it.rawQuery(
                    "SELECT id, payload, action, original_text, byte_count, recovery_kind FROM drafts ORDER BY sequence",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        if (entries.size >= MAX_ENTRIES || (0..2).any(cursor::isNull) ||
                            cursor.isNull(4) || cursor.isNull(5)
                        ) {
                            throw InvalidRecoveryRecord()
                        }
                        val payloadText = cursor.getString(1)
                        val text = if (cursor.isNull(3)) null else decodeText(cursor.getBlob(3))
                        val size = payloadText.toByteArray(Charsets.UTF_8).size.toLong() +
                            (text?.toByteArray(Charsets.UTF_8)?.size ?: 0)
                        bytes += size
                        if (size != cursor.getLong(4) || size > MAX_ENTRY_BYTES || bytes > MAX_TOTAL_BYTES) {
                            throw InvalidRecoveryRecord()
                        }
                        val id = try {
                            UUID.fromString(cursor.getString(0))
                        } catch (_: IllegalArgumentException) {
                            throw InvalidRecoveryRecord()
                        }
                        if (!ids.add(id)) throw InvalidRecoveryRecord()
                        val action = NotificationAction.entries.firstOrNull { action ->
                            action.rawValue == cursor.getString(2)
                        }
                        if (action != NotificationAction.REPLY && action != NotificationAction.MARK_READ) {
                            throw InvalidRecoveryRecord()
                        }
                        if ((action == NotificationAction.REPLY && text.isNullOrBlank()) ||
                            (action == NotificationAction.MARK_READ && text != null)
                        ) {
                            throw InvalidRecoveryRecord()
                        }
                        val payload = try {
                            NotificationPayloadCodec.read(Intent.parseUri(payloadText, 0))
                        } catch (_: URISyntaxException) {
                            throw InvalidRecoveryRecord()
                        } ?: throw InvalidRecoveryRecord()
                        val kind = NotificationRecoveryKind.entries.firstOrNull { it.name == cursor.getString(5) }
                            ?: throw InvalidRecoveryRecord()
                        if (kind == NotificationRecoveryKind.OUTCOME_UNKNOWN &&
                            (action != NotificationAction.REPLY || payload !is NotificationPayload.DirectMessage)
                        ) {
                            throw InvalidRecoveryRecord()
                        }
                        entries += RejectedNotificationAction(id, NotificationResponse(payload, action, text), kind)
                    }
                }
                NotificationRecoveryRead.Ready(entries)
            }
        } catch (_: InvalidRecoveryRecord) {
            NotificationRecoveryRead.Failed(NotificationRecoveryFailure.INVALID_RECORD)
        } catch (_: SQLiteException) {
            NotificationRecoveryRead.Failed(NotificationRecoveryFailure.STORAGE_UNAVAILABLE)
        } catch (_: SecurityException) {
            NotificationRecoveryRead.Failed(NotificationRecoveryFailure.STORAGE_UNAVAILABLE)
        }
    }

    /** Call only after explicit discard or successful clipboard transfer, never on dialog dismissal. */
    fun dispose(id: UUID): NotificationRecoveryFailure? = synchronized(lock) {
        try {
            val database = open(create = false) ?: return@synchronized NotificationRecoveryFailure.INVALID_RECORD
            database.use {
                it.beginTransaction()
                try {
                    if (it.delete("drafts", "id = ?", arrayOf(id.toString())) != 1) {
                        NotificationRecoveryFailure.INVALID_RECORD
                    } else {
                        it.setTransactionSuccessful()
                        null
                    }
                } finally {
                    it.endTransaction()
                }
            }
        } catch (_: InvalidRecoveryRecord) {
            NotificationRecoveryFailure.INVALID_RECORD
        } catch (_: SQLiteException) {
            NotificationRecoveryFailure.STORAGE_UNAVAILABLE
        } catch (_: SecurityException) {
            NotificationRecoveryFailure.STORAGE_UNAVAILABLE
        }
    }

    private fun open(create: Boolean): SQLiteDatabase? {
        val directory = context.noBackupFilesDir ?: throw SQLiteException("Recovery storage directory unavailable")
        val file = File(directory, "notification-action-recovery.sqlite")
        if (file.parentFile?.isDirectory != true) throw SQLiteException("Recovery storage directory unavailable")
        val existed = file.exists()
        if (!existed && !create) return null
        val database = if (existed) {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE, preserveCorruption)
        } else {
            SQLiteDatabase.openOrCreateDatabase(file.path, null, preserveCorruption)
        }
        try {
            if (database.version == 0) {
                database.beginTransaction()
                try {
                    if (database.version != 0) throw InvalidRecoveryRecord()
                    // Android creates its locale metadata even before our first schema transaction.
                    database.rawQuery(
                        "SELECT COUNT(*) FROM sqlite_master WHERE NOT " +
                            "(type = 'table' AND name = 'android_metadata' AND " +
                            "tbl_name = 'android_metadata' AND sql = 'CREATE TABLE android_metadata (locale TEXT)')",
                        null,
                    ).use {
                        if (!it.moveToFirst() || it.getLong(0) != 0L) throw InvalidRecoveryRecord()
                    }
                    database.execSQL(
                        "CREATE TABLE drafts (sequence INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "id TEXT NOT NULL UNIQUE, payload TEXT NOT NULL, action TEXT NOT NULL, " +
                            "original_text BLOB, byte_count INTEGER NOT NULL CHECK(byte_count >= 0), " +
                            "recovery_kind TEXT NOT NULL DEFAULT 'NOT_ADMITTED')",
                    )
                    database.version = 2
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            } else if (database.version == 1) {
                database.beginTransaction()
                try {
                    if (database.version != 1) throw InvalidRecoveryRecord()
                    database.execSQL(
                        "ALTER TABLE drafts ADD COLUMN recovery_kind TEXT NOT NULL DEFAULT 'NOT_ADMITTED'",
                    )
                    database.version = 2
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
            if (database.version == 2) {
                database.beginTransaction()
                try {
                    if (database.version != 2) throw InvalidRecoveryRecord()
                    database.execSQL(
                        "CREATE TABLE accepted_direct_replies (" +
                            "sequence INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint TEXT NOT NULL UNIQUE)",
                    )
                    database.version = 3
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
            if (database.version == 3) {
                database.beginTransaction()
                try {
                    database.execSQL("ALTER TABLE accepted_direct_replies ADD COLUMN token_key TEXT")
                    database.execSQL("CREATE UNIQUE INDEX direct_reply_token_key ON accepted_direct_replies(token_key)")
                    database.version = DATABASE_VERSION
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
            if (database.version != DATABASE_VERSION) throw InvalidRecoveryRecord()
            database.execSQL("PRAGMA synchronous = FULL")
            database.rawQuery("PRAGMA max_page_count = 1024", null).use {
                if (!it.moveToFirst() || it.getLong(0) != 1024L) throw InvalidRecoveryRecord()
            }
            return database
        } catch (failure: SQLiteException) {
            database.close()
            throw failure
        }
    }

    private class InvalidRecoveryRecord : SQLiteException("Invalid notification recovery record")

    companion object {
        private const val DATABASE_VERSION = 4
        internal const val MAX_ACCEPTED_DIRECT_REPLIES = 4096
        internal const val MAX_ENTRIES = 32
        internal const val MAX_ENTRY_BYTES = 128 * 1024L
        internal const val MAX_TOTAL_BYTES = 1024 * 1024L
        private val lock = Any()
        private val preserveCorruption = android.database.DatabaseErrorHandler {
            throw SQLiteDatabaseCorruptException("Notification recovery storage is corrupt; original bytes preserved")
        }

        internal fun directReplyIdentity(response: NotificationResponse): DirectReplyIdentity? {
            val action = response.action
            if (action != NotificationAction.REPLY ||
                response.payload !is NotificationPayload.DirectMessage
            ) {
                return null
            }
            val token = response.actionToken?.takeIf(String::isNotBlank) ?: return null
            val text = response.userText?.takeIf(String::isNotBlank) ?: return null
            val payload = NotificationPayloadCodec.write(Intent(), response.payload).toUri(0)
            val tokenBytes = token.toByteArray(StandardCharsets.UTF_8)
            return DirectReplyIdentity(
                tokenKey = hashParts("meshcore-direct-reply-token-v1".toByteArray(StandardCharsets.UTF_8), tokenBytes),
                fingerprint = hashParts(
                    "meshcore-direct-reply-v1".toByteArray(StandardCharsets.UTF_8),
                    tokenBytes,
                    action.rawValue.toByteArray(StandardCharsets.UTF_8),
                    payload.toByteArray(StandardCharsets.UTF_8),
                    encodeText(text),
                ),
            )
        }

        private fun hashParts(vararg parts: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            for (part in parts) {
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(part.size).array())
                digest.update(part)
            }
            val hash = digest.digest()
            val hex = "0123456789abcdef"
            return buildString(hash.size * 2) {
                hash.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(hex[value ushr 4])
                    append(hex[value and 0x0f])
                }
            }
        }

        internal fun isRecoverable(response: NotificationResponse): Boolean = when (response.action) {
            NotificationAction.REPLY -> !response.userText.isNullOrBlank()
            NotificationAction.MARK_READ -> response.userText == null
            else -> false
        }

        // Preserve RemoteInput's exact UTF-16 code units, including unpaired surrogates.
        private fun encodeText(text: String): ByteArray = ByteArray(text.length * 2).also { bytes ->
            text.forEachIndexed { index, char ->
                bytes[index * 2] = (char.code shr 8).toByte()
                bytes[index * 2 + 1] = char.code.toByte()
            }
        }

        private fun decodeText(bytes: ByteArray): String {
            if (bytes.size % 2 != 0) throw InvalidRecoveryRecord()
            return CharArray(bytes.size / 2) { index ->
                (((bytes[index * 2].toInt() and 255) shl 8) or
                    (bytes[index * 2 + 1].toInt() and 255)).toChar()
            }.concatToString()
        }
    }
}

class NotificationRecoveryItem(val entry: RejectedNotificationAction, val persistent: Boolean)

class NotificationRecoverySnapshot(
    val version: Long,
    val items: List<NotificationRecoveryItem>,
    val storageFailure: NotificationRecoveryFailure?,
    val notRetained: Boolean,
    val manualOpenRequired: Boolean,
    val recordsAvailable: Boolean = true,
    val readCompleted: Boolean = true,
)

enum class NotificationRecoveryRetention { DURABLE, PROCESS_ONLY, NOT_RETAINED }

/** Separate, bounded process-only fallback when durable custody is unavailable. Never evicts a draft. */
object NotificationActionRecovery {
    private val lock = Any()
    // Failure/status publication must never wait for the storage lock on the receiver thread.
    private val statusLock = Any()
    private val memory = linkedMapOf<UUID, RejectedNotificationAction>()
    private var memoryBytes = 0L
    private var lost = false
    private var manualOpenRequired = false
    private val updates = MutableStateFlow(0L)
    val changes: StateFlow<Long> = updates.asStateFlow()
    private val logger = Logger.getLogger("com.mc1.NotificationRecovery")

    /** Status only: an empty item list here never asserts that storage contains no drafts. */
    fun statusSnapshot(): NotificationRecoverySnapshot = synchronized(statusLock) {
        NotificationRecoverySnapshot(
            updates.value, emptyList(), null, lost, manualOpenRequired,
            recordsAvailable = false, readCompleted = false,
        )
    }

    fun retain(context: Context, response: NotificationResponse): NotificationRecoveryRetention =
        retain(context, response, NotificationRecoveryKind.NOT_ADMITTED)

    internal fun retainUncertainDirectReply(
        context: Context,
        response: NotificationResponse,
    ): NotificationRecoveryRetention {
        if (response.action != NotificationAction.REPLY ||
            response.payload !is NotificationPayload.DirectMessage
        ) return NotificationRecoveryRetention.NOT_RETAINED
        return retain(context, response, NotificationRecoveryKind.OUTCOME_UNKNOWN)
    }

    private fun retain(
        context: Context,
        response: NotificationResponse,
        kind: NotificationRecoveryKind,
    ): NotificationRecoveryRetention = synchronized(lock) {
        val store = NotificationActionRecoveryStore(context)
        val write = if (kind == NotificationRecoveryKind.OUTCOME_UNKNOWN) {
            store.retainUncertainDirectReply(response)
        } else {
            store.retain(response)
        }
        val retained = when (val result = write) {
            is NotificationRecoveryWrite.Stored -> NotificationRecoveryRetention.DURABLE
            is NotificationRecoveryWrite.Failed -> {
                val bytes = recoveryBytes(response)
                if (!NotificationActionRecoveryStore.isRecoverable(response) ||
                    bytes > NotificationActionRecoveryStore.MAX_ENTRY_BYTES ||
                    memory.size >= NotificationActionRecoveryStore.MAX_ENTRIES ||
                    memoryBytes + bytes > NotificationActionRecoveryStore.MAX_TOTAL_BYTES
                ) {
                    logger.severe("${kind.name.lowercase()} notification reply could not be retained; reason=${result.reason}")
                    NotificationRecoveryRetention.NOT_RETAINED
                } else {
                    val entry = RejectedNotificationAction(UUID.randomUUID(), response, kind)
                    memory[entry.id] = entry
                    memoryBytes += bytes
                    logger.warning(
                        "${kind.name.lowercase().replace('_', ' ')} notification reply retained only in process memory; " +
                            "reason=${result.reason}",
                    )
                    NotificationRecoveryRetention.PROCESS_ONLY
                }
            }
        }
        synchronized(statusLock) {
            if (retained == NotificationRecoveryRetention.NOT_RETAINED) lost = true
            refresh()
        }
        retained
    }

    fun load(context: Context): NotificationRecoverySnapshot = synchronized(lock) {
        val stored = NotificationActionRecoveryStore(context).read()
        val persistent = (stored as? NotificationRecoveryRead.Ready)?.entries.orEmpty()
        synchronized(statusLock) {
            NotificationRecoverySnapshot(
                updates.value,
                persistent.map { NotificationRecoveryItem(it, persistent = true) } +
                    memory.values.map { NotificationRecoveryItem(it, persistent = false) },
                (stored as? NotificationRecoveryRead.Failed)?.reason,
                lost,
                manualOpenRequired,
                recordsAvailable = stored is NotificationRecoveryRead.Ready,
            )
        }
    }

    fun dispose(context: Context, item: NotificationRecoveryItem): NotificationRecoveryFailure? = synchronized(lock) {
        val failure = if (item.persistent) {
            NotificationActionRecoveryStore(context).dispose(item.entry.id)
        } else {
            val removed = memory.remove(item.entry.id)
            if (removed == null) NotificationRecoveryFailure.INVALID_RECORD else {
                memoryBytes -= recoveryBytes(removed.response)
                null
            }
        }
        if (failure == null) refresh()
        failure
    }

    fun requireManualOpen() = synchronized(statusLock) {
        manualOpenRequired = true
        logger.info("Rejected notification action recovery requires user-directed app opening")
        refresh()
    }

    /** A stale dialog cannot acknowledge a failure or response it has never displayed. */
    fun acknowledgeFailure(observedVersion: Long): Long? = synchronized(statusLock) {
        if (updates.value != observedVersion) return@synchronized null
        lost = false
        manualOpenRequired = false
        refresh()
        updates.value
    }

    fun handoffNotRetained() = synchronized(statusLock) {
        lost = true
        logger.severe("Rejected notification action exceeded bounded recovery handoff; input not retained")
        refresh()
    }

    fun refresh() = synchronized(statusLock) {
        updates.value += 1
    }

    internal fun recoveryBytes(response: NotificationResponse): Long =
        NotificationPayloadCodec.write(Intent(), response.payload).toUri(0).toByteArray(Charsets.UTF_8).size.toLong() +
            (response.userText?.toByteArray(Charsets.UTF_8)?.size ?: 0)
}
internal object NotificationActionDispatcher {
    private const val MAX_PENDING = 32
    private const val MAX_ACCEPTED_DIRECT_REPLY_CACHE = 256
    private const val RETRY_COUNT = 60
    private const val RETRY_DELAY_MILLIS = 250L

    private val logger = Logger.getLogger("com.mc1.NotificationActions")
    private val lock = Any()
    private class PendingAction(
        val response: NotificationResponse,
        val receipt: NotificationActionReceipt = NotificationActionReceipt(),
        val readReceipt: NotificationReadReceipt = NotificationReadReceipt(),
        val commit: NotificationActionCommit = NotificationActionCommit(),
    ) {
        var acceptedPersistenceBound = false
        val preventsReplay: Boolean
            get() = receipt.preventsReplay || readReceipt.preventsReplay || commit.isCommitted()
    }
    private val pending = ArrayDeque<PendingAction>()
    // Never re-route uncertain actions; direct reply text is separately retained copy-only when possible.
    private val unresolved = ArrayDeque<PendingAction>()
    private val directReplyFences =
        object : java.util.LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > MAX_ACCEPTED_DIRECT_REPLY_CACHE
        }
    private val delivery = Mutex()
    private var scope: CoroutineScope? = null
    private var router: (suspend (NotificationResponse) -> Boolean)? = null
    private var recoveryContext: Context? = null
    private var installation: Any? = null
    private var drainJob: Job? = null
    private var retryRequested = false

    fun install(scope: CoroutineScope, router: suspend (NotificationResponse) -> Boolean) {
        install(scope, recoveryContext = null, router = router)
    }

    fun install(
        scope: CoroutineScope,
        recoveryContext: Context?,
        router: suspend (NotificationResponse) -> Boolean,
    ) {
        synchronized(lock) {
            this.scope = scope
            this.router = router
            this.recoveryContext = recoveryContext?.applicationContext
            installation = Any()
        }
        retry()
    }

    fun enqueue(response: NotificationResponse): EnqueueResult {
        val accepted = synchronized(lock) {
            if (pending.size + unresolved.size >= MAX_PENDING) false else {
                pending.addLast(PendingAction(response))
                true
            }
        }
        if (!accepted) {
            logger.warning("Notification action queue is full; action was not enqueued")
            return EnqueueResult.REJECTED_CAPACITY
        }
        retry()
        return EnqueueResult.DELIVERED_OR_QUEUED
    }

    fun retry() {
        val job = synchronized(lock) {
            retryRequested = true
            prepareDrainLocked()
        }
        job?.start()
    }

    private fun prepareDrainLocked(): Job? {
        val owner = scope ?: return null
        if (drainJob != null || owner.coroutineContext[Job]?.isActive == false) return null
        val installed = installation
        val job = owner.launch(start = CoroutineStart.LAZY) {
            repeat(RETRY_COUNT) {
                synchronized(lock) { retryRequested = false }
                if (drainOnce()) return@launch
                delay(RETRY_DELAY_MILLIS)
            }
        }
        drainJob = job
        job.invokeOnCompletion { failure ->
            if (failure != null && failure !is CancellationException) {
                logger.log(Level.WARNING, "Notification action drain failed", failure)
            }
            val next = synchronized(lock) {
                if (drainJob !== job) return@invokeOnCompletion
                drainJob = null
                // An old drain can consume a replacement installation's retry before it stops.
                if (retryRequested || installation !== installed) prepareDrainLocked() else null
            }
            next?.start()
        }
        return job
    }

    internal suspend fun drainOnce(): Boolean = delivery.withLock {
        while (true) {
            currentCoroutineContext().ensureActive()
            val next = synchronized(lock) {
                retryRequested = false
                pending.firstOrNull()
            } ?: return@withLock true
            val target = synchronized(lock) { router } ?: return@withLock false
            var handled = false
            var uncertainDirectReply: Pair<Context, NotificationResponse>? = null
            try {
                val context = synchronized(lock) { recoveryContext }
                val identity = NotificationActionRecoveryStore.directReplyIdentity(next.response)
                if (context != null && identity != null) {
                    val fencedFingerprint = synchronized(lock) {
                        directReplyFences[identity.tokenKey]
                    }
                    val lookup = if (fencedFingerprint != null) {
                        if (fencedFingerprint == identity.fingerprint) NotificationActionAcceptanceLookup.ExactReplay
                        else NotificationActionAcceptanceLookup.ChangedText
                    } else {
                        withContext(Dispatchers.IO) {
                            NotificationActionRecoveryStore(context).checkDirectReplyFence(next.response)
                        }
                    }
                    when (lookup) {
                        NotificationActionAcceptanceLookup.NotApplicable -> Unit
                        NotificationActionAcceptanceLookup.ExactReplay -> {
                            // Do not infer a token-wide cache entry from a legacy exact-only receipt.
                            logger.info("Previously dispatched direct notification reply suppressed")
                            handled = true
                        }
                        NotificationActionAcceptanceLookup.ChangedText -> {
                            withContext(NonCancellable + Dispatchers.IO) {
                                val retained = NotificationActionRecovery.retain(context, next.response)
                                handled = true
                                NotificationActionReceiver.showRetention(context, retained)
                            }
                        }
                        NotificationActionAcceptanceLookup.NotAccepted -> {
                            val bind = synchronized(lock) {
                                if (next.acceptedPersistenceBound) false else {
                                    next.acceptedPersistenceBound = true
                                    true
                                }
                            }
                            if (bind) {
                                next.receipt.bindAcceptedPersistence {
                                    synchronized(lock) { directReplyFences[identity.tokenKey] = identity.fingerprint }
                                    val write = withContext(NonCancellable + Dispatchers.IO) {
                                        NotificationActionRecoveryStore(context)
                                            .recordDirectReplyFence(next.response)
                                    }
                                    when (write) {
                                        NotificationActionAcceptanceWrite.Stored,
                                        NotificationActionAcceptanceWrite.AlreadyStored -> true
                                        NotificationActionAcceptanceWrite.NotApplicable -> {
                                            logger.severe("Accepted direct notification reply had no stable action identity")
                                            false
                                        }
                                        is NotificationActionAcceptanceWrite.Failed -> {
                                            logger.severe(
                                                "Accepted direct notification reply could not persist its replay fence; " +
                                                    "reason=${write.reason}",
                                            )
                                            false
                                        }
                                    }
                                }
                            }
                        }
                        is NotificationActionAcceptanceLookup.Failed -> {
                            logger.severe(
                                "Direct notification reply acceptance could not be checked; " +
                                    "routing suppressed, reason=${lookup.reason}",
                            )
                            NotificationActionRecovery.requireManualOpen()
                            withContext(NonCancellable + Dispatchers.IO) {
                                val retained = NotificationActionRecovery.retainUncertainDirectReply(context, next.response)
                                handled = true
                                NotificationActionReceiver.showRetention(context, retained)
                            }
                        }
                    }
                }
                if (!next.preventsReplay) {
                    // Set this before withContext's cancellable return to the dispatcher.
                    if (!handled) withContext(next.receipt + next.readReceipt + next.commit) {
                        target(next.response).also {
                            handled = it
                            if (!it && next.commit.isCommitted()) {
                                logger.severe("Notification handler rejected an already committed navigation")
                            }
                        }
                    }
                }
            } finally {
                if (handled || next.preventsReplay) {
                    synchronized(lock) {
                        if (pending.firstOrNull() === next) {
                            val state = next.receipt.state
                            if (state == NotificationActionReceipt.State.IN_FLIGHT ||
                                state == NotificationActionReceipt.State.UNCERTAIN ||
                                next.readReceipt.state == NotificationReadReceipt.State.STARTED ||
                                next.readReceipt.state == NotificationReadReceipt.State.UNCERTAIN
                            ) {
                                next.receipt.uncertain()
                                next.readReceipt.uncertain()
                                unresolved.addLast(next)
                                logger.warning("Notification action outcome unknown; automatic replay suppressed")
                                if (next.receipt.state == NotificationActionReceipt.State.UNCERTAIN &&
                                    next.response.action == NotificationAction.REPLY &&
                                    next.response.payload is NotificationPayload.DirectMessage
                                ) {
                                    recoveryContext?.let { uncertainDirectReply = it to next.response }
                                }
                            }
                            pending.removeFirst()
                            if (pending.isNotEmpty()) retryRequested = true
                        }
                    }
                }
                uncertainDirectReply?.let { (context, response) ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        NotificationActionRecoveryStore.directReplyIdentity(response)?.let { identity ->
                            synchronized(lock) { directReplyFences[identity.tokenKey] = identity.fingerprint }
                            val write = NotificationActionRecoveryStore(context).recordDirectReplyFence(response)
                            if (write is NotificationActionAcceptanceWrite.Failed) {
                                logger.severe("Uncertain direct reply replay fence was not saved; reason=${write.reason}")
                            }
                        }
                        val retained = NotificationActionRecovery.retainUncertainDirectReply(context, response)
                        if (retained != NotificationRecoveryRetention.DURABLE) {
                            logger.warning(
                                "Uncertain direct reply is not durably retained; retention=$retained",
                            )
                        }
                    }
                }
                if (next.receipt.isAccepted) {
                    if (next.receipt.persistAccepted() == false) {
                        logger.severe("Accepted direct notification reply replay fence remains unpersisted")
                    }
                }
            }
            if (!handled && !next.preventsReplay) return@withLock false
        }
        @Suppress("UNREACHABLE_CODE")
        true
    }

    internal fun resetForTesting() {
        val job = synchronized(lock) {
            val previous = drainJob
            drainJob = null
            pending.clear()
            unresolved.clear()
            directReplyFences.clear()
            scope = null
            router = null
            recoveryContext = null
            installation = null
            retryRequested = false
            previous
        }
        job?.cancel()
    }
}
