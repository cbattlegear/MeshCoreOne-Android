// AndroidOnly: WP-401 Cold-start-safe notification action receiver and bounded process dispatcher.
// AndroidOnly: WP-215 Separate, bounded custody of actions rejected before dispatcher admission.
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
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.platform.notifications.R
import java.io.File
import java.net.URISyntaxException
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
                ?.takeIf { it.isNotBlank() } ?: return
        } else {
            null
        }
        val result = NotificationActionDispatcher.enqueue(NotificationResponse(payload, action, originalText?.trim()))
        if (result == EnqueueResult.REJECTED_CAPACITY &&
            (action == NotificationAction.REPLY || action == NotificationAction.MARK_READ)
        ) {
            // Android 12 forbids an action broadcast from starting a recovery activity.
            NotificationActionRecovery.requireManualOpen()
            val pending = checkNotNull(goAsync()) { "Recovery requires a platform-dispatched broadcast" }
            recoveryQueue.submit(
                NotificationResponse(payload, action, originalText),
                work = {
                    showRetention(context, NotificationActionRecovery.retain(
                        context, NotificationResponse(payload, action, originalText),
                    ))
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

        private fun showRetention(context: Context, retained: NotificationRecoveryRetention) {
            val status = when (retained) {
                NotificationRecoveryRetention.DURABLE -> R.string.notification_action_recovery_saved
                NotificationRecoveryRetention.PROCESS_ONLY -> R.string.notification_action_recovery_memory_only
                NotificationRecoveryRetention.NOT_RETAINED -> R.string.notification_action_recovery_not_retained
            }
            main.post { Toast.makeText(context, status, Toast.LENGTH_LONG).show() }
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

class RejectedNotificationAction(val id: UUID, val response: NotificationResponse)

sealed interface NotificationRecoveryRead {
    class Ready(val entries: List<RejectedNotificationAction>) : NotificationRecoveryRead
    class Failed(val reason: NotificationRecoveryFailure) : NotificationRecoveryRead
}

sealed interface NotificationRecoveryWrite {
    class Stored(val entry: RejectedNotificationAction) : NotificationRecoveryWrite
    class Failed(val reason: NotificationRecoveryFailure) : NotificationRecoveryWrite
}

/**
 * Pre-admission drafts, never accepted-action receipts or permission to replay a channel index.
 * The original target and untrimmed text are kept until an explicit, confirmed disposition.
 */
class NotificationActionRecoveryStore(private val context: Context) {
    fun retain(response: NotificationResponse): NotificationRecoveryWrite = synchronized(lock) {
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
        val entry = RejectedNotificationAction(UUID.randomUUID(), response)
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
                    "SELECT id, payload, action, original_text, byte_count FROM drafts ORDER BY sequence", null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        if (entries.size >= MAX_ENTRIES || (0..2).any(cursor::isNull) || cursor.isNull(4)) {
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
                        entries += RejectedNotificationAction(id, NotificationResponse(payload, action, text))
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
                            "original_text BLOB, byte_count INTEGER NOT NULL CHECK(byte_count >= 0))",
                    )
                    database.version = 1
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
            if (database.version != 1) throw InvalidRecoveryRecord()
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
        internal const val MAX_ENTRIES = 32
        internal const val MAX_ENTRY_BYTES = 128 * 1024L
        internal const val MAX_TOTAL_BYTES = 1024 * 1024L
        private val lock = Any()
        private val preserveCorruption = android.database.DatabaseErrorHandler {
            throw SQLiteDatabaseCorruptException("Notification recovery storage is corrupt; original bytes preserved")
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

    fun retain(context: Context, response: NotificationResponse): NotificationRecoveryRetention = synchronized(lock) {
        val retained = when (val result = NotificationActionRecoveryStore(context).retain(response)) {
            is NotificationRecoveryWrite.Stored -> NotificationRecoveryRetention.DURABLE
            is NotificationRecoveryWrite.Failed -> {
                val bytes = recoveryBytes(response)
                if (!NotificationActionRecoveryStore.isRecoverable(response) ||
                    bytes > NotificationActionRecoveryStore.MAX_ENTRY_BYTES ||
                    memory.size >= NotificationActionRecoveryStore.MAX_ENTRIES ||
                    memoryBytes + bytes > NotificationActionRecoveryStore.MAX_TOTAL_BYTES
                ) {
                    logger.severe("Rejected notification action could not be retained; reason=${result.reason}")
                    NotificationRecoveryRetention.NOT_RETAINED
                } else {
                    val entry = RejectedNotificationAction(UUID.randomUUID(), response)
                    memory[entry.id] = entry
                    memoryBytes += bytes
                    logger.warning("Rejected notification action retained only in process memory; reason=${result.reason}")
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
    private const val RETRY_COUNT = 60
    private const val RETRY_DELAY_MILLIS = 250L

    private val logger = Logger.getLogger("com.mc1.NotificationActions")
    private val lock = Any()
    private val pending = ArrayDeque<NotificationResponse>()
    private var scope: CoroutineScope? = null
    private var router: (suspend (NotificationResponse) -> Boolean)? = null
    private var installation: Any? = null
    private var drainJob: Job? = null
    private var retryRequested = false

    fun install(scope: CoroutineScope, router: suspend (NotificationResponse) -> Boolean) {
        synchronized(lock) {
            this.scope = scope
            this.router = router
            installation = Any()
        }
        retry()
    }

    fun enqueue(response: NotificationResponse): EnqueueResult {
        val accepted = synchronized(lock) {
            if (pending.size >= MAX_PENDING) false else {
                pending.addLast(response)
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
        val job = owner.launch(context = NotificationActionCommit(), start = CoroutineStart.LAZY) {
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

    internal suspend fun drainOnce(): Boolean {
        while (true) {
            currentCoroutineContext().ensureActive()
            val next = synchronized(lock) {
                retryRequested = false
                pending.firstOrNull()
            } ?: return true
            val target = synchronized(lock) { router } ?: return false
            val commit = currentCoroutineContext()[NotificationActionCommit]
            commit?.reset()
            var returned = false
            val accepted = try {
                target(next).also { returned = true }
            } finally {
                if (!returned && commit?.isCommitted() == true) removeCurrent(next)
            }
            if (!accepted && commit?.isCommitted() != true) return false
            if (!accepted) logger.severe("Notification handler rejected an already committed navigation")
            removeCurrent(next)
        }
    }

    private fun removeCurrent(response: NotificationResponse) = synchronized(lock) {
        if (pending.firstOrNull() === response) {
            pending.removeFirst()
            if (pending.isNotEmpty()) retryRequested = true
        }
    }

    internal fun resetForTesting() {
        val job = synchronized(lock) {
            val previous = drainJob
            drainJob = null
            pending.clear()
            scope = null
            router = null
            installation = null
            retryRequested = false
            previous
        }
        job?.cancel()
    }
}
