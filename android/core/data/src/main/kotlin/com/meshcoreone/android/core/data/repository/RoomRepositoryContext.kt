// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Diagnostics.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.room.withTransaction
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.RxLogRetention
import com.meshcoreone.android.core.database.DatabaseValueException
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.RxLogEntryEntity
import com.meshcoreone.android.core.model.RadioId
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun interface RepositoryIssueReporter {
    fun report(operation: String, failure: PersistenceStoreException)
}

object AndroidRepositoryIssueReporter : RepositoryIssueReporter {
    override fun report(operation: String, failure: PersistenceStoreException) {
        Log.e("PersistenceStore", "$operation: ${failure.error.javaClass.simpleName}")
    }
}

internal class RepositoryTransaction(
    val database: MeshCoreDatabase,
    val clock: Clock,
    pending: Map<EntityKey, RxLogEntryEntity>,
) {
    val pendingRx = LinkedHashMap(pending)
    var saves = false
        private set
    private val committed = mutableListOf<() -> Unit>()

    fun save() { saves = true }
    fun afterCommit(action: () -> Unit) { committed += action }
    fun complete() { committed.forEach { it() } }

    suspend fun rxRows(radioId: RadioId): List<RxLogEntryEntity> {
        requireCanonicalRxKeys(radioId)
        val rows = database.rxLogs().newest(radioId.value, -1).associateByTo(LinkedHashMap()) { it.id }
        for ((key, row) in pendingRx) if (key.radioId == radioId) rows[row.id] = row
        return rows.values.toList()
    }

    suspend fun updateRx(row: RxLogEntryEntity) {
        val key = EntityKey(RadioId(row.radioId), row.id)
        requireCanonicalRxKeys(key.radioId)
        if (key in pendingRx) pendingRx[key] = row else database.rxLogs().upsert(row)
        requireCanonicalRxKeys(key.radioId)
    }
}

internal class RoomRepositoryContext(
    val database: MeshCoreDatabase,
    val clock: Clock,
    parentScope: CoroutineScope,
    val reporter: RepositoryIssueReporter,
) {
    private val mutex = Mutex()
    private val flushOwner = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + flushOwner)
    private val pendingRx = LinkedHashMap<EntityKey, RxLogEntryEntity>()
    private val rxCounts = LinkedHashMap<RadioId, Long>()
    private var rxInsertsSinceFlush = 0L
    private var flushTask: Job? = null
    private val closedSignal = CompletableDeferred<Unit>()
    @Volatile private var closed = false
    internal var rxInitiatedSaveCount = 0L
        private set

    suspend fun <T> read(operation: String, block: suspend RepositoryTransaction.() -> T): T =
        access(operation, writing = false) {
            mutex.withLock {
                ensureOpen()
                database.withTransaction { RepositoryTransaction(database, clock, pendingRx).block() }
            }
        }

    suspend fun <T> write(
        operation: String,
        flushBeforeRollback: Boolean = false,
        block: suspend RepositoryTransaction.() -> T,
    ): T = access(operation, writing = true) {
        mutex.withLock {
            ensureOpen()
            if (flushBeforeRollback) flushRxLocked()
            val transaction = RepositoryTransaction(database, clock, pendingRx)
            committedTransaction(
                afterCommit = {
                    if (transaction.saves) pendingRx.clear()
                    transaction.complete()
                },
            ) {
                val value = transaction.block()
                if (transaction.saves) transaction.persistPendingRx()
                value
            }
        }
    }

    suspend fun <T> guardedWrite(
        operation: String,
        unchanged: T,
        guard: suspend RepositoryTransaction.() -> Boolean,
        block: suspend RepositoryTransaction.() -> T,
    ): T = access(operation, writing = true) {
        mutex.withLock {
            ensureOpen()
            val eligible = database.withTransaction { RepositoryTransaction(database, clock, pendingRx).guard() }
            if (!eligible) return@withLock unchanged
            flushRxLocked()
            val transaction = RepositoryTransaction(database, clock, pendingRx)
            committedTransaction(
                afterCommit = {
                    if (transaction.saves) pendingRx.clear()
                    transaction.complete()
                },
            ) {
                if (!transaction.guard()) return@committedTransaction unchanged
                val value = transaction.block()
                if (transaction.saves) transaction.persistPendingRx()
                value
            }
        }
    }

    internal suspend fun <T> restoreBackup(
        afterCommit: () -> Unit,
        block: suspend RepositoryTransaction.() -> T,
    ): T = access("importBackupDatabase", writing = true) {
        mutex.withLock {
            ensureOpen()
            flushRxLocked()
            val transaction = RepositoryTransaction(database, clock, pendingRx)
            committedTransaction(afterCommit, returnCommittedOnCancellation = true) { transaction.block() }
        }
    }

    suspend fun saveRx(row: RxLogEntryEntity) = access("saveRxLogEntry", writing = true) {
        mutex.withLock {
            ensureOpen()
            val radio = RadioId(row.radioId)
            val nextCount = database.withTransaction {
                // Re-read under the DB transaction: another context may have changed disk rows,
                // and a repeated ordinary save replaces one pending identity rather than adding one.
                val rows = RepositoryTransaction(database, clock, pendingRx).rxRows(radio)
                val count = rows.size.toLong()
                if (rows.any { it.id == row.id }) count else checkedIncrement(count)
            }
            val nextDirty = checkedIncrement(rxInsertsSinceFlush)
            rxCounts[radio] = nextCount
            pendingRx[EntityKey(radio, row.id)] = row
            rxInsertsSinceFlush = nextDirty
            if (rxInsertsSinceFlush >= RxLogRetention.BATCH_SIZE) flushRxLocked() else scheduleFlush()
        }
    }

    suspend fun flushRx() = access("flushPendingRxLogEntries", writing = true) {
        mutex.withLock { ensureOpen(); flushRxLocked() }
    }

    suspend fun close() {
        access("close", writing = true) {
            mutex.withLock {
                if (!closed) {
                    flushRxLocked()
                    closed = true
                    closedSignal.complete(Unit)
                    flushTask?.cancel()
                    flushTask = null
                    flushOwner.cancel()
                }
            }
        }
    }

    fun forgetRxCount(radioId: RadioId) { rxCounts.remove(radioId) }
    fun setRxCount(radioId: RadioId, count: Long) { rxCounts[radioId] = count }

    fun <Row, Value : Any> observe(
        operation: String,
        rows: Flow<List<Row>>,
        projection: (List<Row>) -> Value,
    ): Flow<Value> = channelFlow {
        access(operation, writing = false) { mutex.withLock { ensureOpen() } }
        val collector = launch {
            access(operation, writing = false) {
                rows.collect { values ->
                    val projected = mutex.withLock { if (closed) null else projection(values) }
                    if (projected != null) send(projected)
                }
            }
        }
        val completion = launch {
            closedSignal.await()
            collector.cancel()
            channel.close()
        }
        awaitClose { collector.cancel(); completion.cancel() }
    }

    private fun ensureOpen() {
        if (closed) throw PersistenceStoreException(
            PersistenceStoreError.FetchFailed("PersistenceStore is closed"),
            IllegalStateException("Closed process repository"),
        )
    }

    private fun scheduleFlush() {
        if (flushTask != null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val current = currentCoroutineContext()[Job]
            delay(RxLogRetention.flushInterval.toMillis())
            try {
                flushRx()
            } catch (cause: PersistenceStoreException) {
                // access() already reported the typed failure; retain the batch for explicit retry.
                mutex.withLock { if (flushTask == current) flushTask = null }
            }
        }
        flushTask = job
        job.start()
    }

    private suspend fun flushRxLocked() {
        if (rxInsertsSinceFlush == 0L) return
        val caller = currentCoroutineContext()[Job]
        if (flushTask != caller) flushTask?.cancel()
        flushTask = null
        val pruned = LinkedHashMap<RadioId, Long>()
        val nextSaveCount = checkedIncrement(rxInitiatedSaveCount)
        committedTransaction(
            afterCommit = {
                pendingRx.clear()
                rxInsertsSinceFlush = 0
                rxInitiatedSaveCount = nextSaveCount
                rxCounts.putAll(pruned)
            },
        ) {
            val transaction = RepositoryTransaction(database, clock, pendingRx)
            for (radio in rxCounts.keys) transaction.requireCanonicalRxKeys(radio)
            transaction.persistPendingRx()
            for (radio in rxCounts.keys) {
                val count = database.rxLogs().count(radio.value)
                if (count > checkedAdd(RxLogRetention.KEEP_COUNT, RxLogRetention.PRUNE_THRESHOLD)) {
                    for (row in database.rxLogs().oldest(radio.value, count - RxLogRetention.KEEP_COUNT)) {
                        database.rxLogs().delete(radio.value, row.id)
                    }
                    pruned[radio] = RxLogRetention.KEEP_COUNT
                } else {
                    pruned[radio] = count
                }
                transaction.requireCanonicalRxKeys(radio)
            }
        }
    }

    private suspend fun <T> committedTransaction(
        afterCommit: () -> Unit,
        returnCommittedOnCancellation: Boolean = false,
        block: suspend () -> T,
    ): T {
        if (database.inTransaction()) {
            invalidData("PersistenceStore owns its commit boundary; an external Room transaction must use its DAOs")
        }
        val caller = currentCoroutineContext()
        val worker = Job()
        val cancellation = Job(caller[Job])
        val phase = Any()
        var committing = false
        val listener = cancellation.invokeOnCompletion { cause ->
            if (cause != null) synchronized(phase) {
                if (!committing) {
                    worker.cancel(cause as? CancellationException
                        ?: CancellationException("Persistence transaction owner cancelled").also { it.initCause(cause) })
                }
            }
        }
        try {
            val value = withContext(caller + worker) {
                val result = database.withTransaction {
                    val staged = block()
                    synchronized(phase) {
                        caller.ensureActive()
                        committing = true
                    }
                    staged
                }
                // Only the final Room commit and bounded in-memory bookkeeping survive late cancellation.
                afterCommit()
                result
            }
            if (!returnCommittedOnCancellation) caller.ensureActive()
            return value
        } finally {
            listener.dispose()
            cancellation.complete()
            worker.complete()
        }
    }

    private suspend fun <T> access(operation: String, writing: Boolean, block: suspend () -> T): T = try {
        block()
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: DatabaseValueException) {
        fail(operation, writing, cause)
    } catch (cause: SQLiteException) {
        fail(operation, writing, cause)
    } catch (cause: PersistenceStoreException) {
        reporter.report(operation, cause)
        throw cause
    }

    private fun fail(operation: String, writing: Boolean, cause: Throwable): Nothing {
        val reason = if (cause is DatabaseValueException) "Invalid stored ${cause.field}" else operation
        val failure = PersistenceStoreException(
            if (writing) PersistenceStoreError.SaveFailed(reason) else PersistenceStoreError.FetchFailed(reason),
            cause,
        )
        reporter.report(operation, failure)
        throw failure
    }
}
