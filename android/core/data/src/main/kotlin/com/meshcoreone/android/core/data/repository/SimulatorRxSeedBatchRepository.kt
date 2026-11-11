// AndroidOnly: WP-202 Issue #614 Atomic simulator fixture decisions, real pending batch and retention.
package com.meshcoreone.android.core.data.repository

import android.database.Cursor
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.RxLogRetention
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.RxLogEntryEntity
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.canonicalString
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class SimulatorRxSeedResult { INSERTED, PRESERVED_STORED, PRESERVED_PENDING }

/** PENDING denotes the unchanged local buffer, not exclusive absence from storage. */
enum class SimulatorRxSeedPresence { STORED, PENDING, PRUNED }

/** Decision is made before any DML; normal retention can prune a pre-existing preserved row. */
data class SimulatorRxSeedOutcome(
    val id: UUID,
    val decision: SimulatorRxSeedResult,
    val presence: SimulatorRxSeedPresence,
)

internal data class SimulatorRxSeedBatchWrite(
    val outcomes: List<SimulatorRxSeedOutcome>,
    val inserted: Boolean,
)

private suspend fun RepositoryTransaction.simulatorRxSeedDecision(row: RxLogEntryEntity): SimulatorRxSeedResult {
    val dao = database.rxLogs()
    val count = dao.countSimulatorIdentity(row.radioId, row.id)
    if (count > 1L) invalidData("Simulator RX identity has multiple stored rows")
    if (EntityKey(RadioId(row.radioId), row.id) in pendingRx) {
        if (count == 1L && dao.byId(row.radioId, row.id) == null) {
            invalidData("Simulator RX pending identity conflicts with a stored UUID alias")
        }
        return SimulatorRxSeedResult.PRESERVED_PENDING
    }
    return if (count == 1L) SimulatorRxSeedResult.PRESERVED_STORED else SimulatorRxSeedResult.INSERTED
}

internal suspend fun RepositoryTransaction.insertSimulatorRxBatchIfAbsent(
    rows: List<RxLogEntryEntity>,
    cachedFlushRadios: Set<RadioId> = emptySet(),
): SimulatorRxSeedBatchWrite {
    currentCoroutineContext().ensureActive()
    if (!database.inTransaction()) invalidData("Simulator RX insertion requires the owned Room transaction")
    if (rows.isEmpty() || rows.map { it.radioId }.distinct().size != 1 ||
        rows.map { it.id }.distinct().size != rows.size
    ) invalidData("Simulator RX fixture batch requires unique IDs on one radio")

    val radios = linkedSetOf<RadioId>().apply {
        addAll(cachedFlushRadios)
        addAll(pendingRx.keys.map { it.radioId })
        add(RadioId(rows.first().radioId))
    }
    for (radio in radios) requireCanonicalRxKeys(radio)
    val decisions = rows.map { simulatorRxSeedDecision(it) }
    val dao = database.rxLogs()
    for (pending in pendingRx.values) {
        val count = dao.countSimulatorIdentity(pending.radioId, pending.id)
        if (count > 1L) invalidData("Simulator RX pending batch identity has multiple stored rows")
        if (count == 1L && dao.byId(pending.radioId, pending.id) == null) {
            invalidData("Simulator RX pending batch identity conflicts with a stored UUID alias")
        }
    }
    if (SimulatorRxSeedResult.INSERTED !in decisions) {
        return SimulatorRxSeedBatchWrite(
            rows.zip(decisions) { row, decision ->
                SimulatorRxSeedOutcome(row.id, decision, if (decision == SimulatorRxSeedResult.PRESERVED_PENDING) {
                    SimulatorRxSeedPresence.PENDING
                } else SimulatorRxSeedPresence.STORED)
            },
            inserted = false,
        )
    }

    val state = SimulatorRxBatchPreimages(database, radios)
    state.capture()
    // The real pending map precedes newly appended fixtures in the original generic batch.
    for (pending in pendingRx.values) state.write(pending, insert = false)
    for ((row, decision) in rows.zip(decisions)) {
        if (decision == SimulatorRxSeedResult.INSERTED) {
            if (dao.countSimulatorIdentity(row.radioId, row.id) != 0L) simulatorRxBatchFailed()
            state.write(row, insert = true)
        }
    }

    val insertedIDs = rows.zip(decisions).filter { it.second == SimulatorRxSeedResult.INSERTED }
        .map { it.first.id }.toSet()
    for (radio in radios) {
        val count = dao.count(radio.value)
        if (count > checkedAdd(RxLogRetention.KEEP_COUNT, RxLogRetention.PRUNE_THRESHOLD)) {
            val excess = count - RxLogRetention.KEEP_COUNT
            val victims = dao.oldest(radio.value, excess)
            if (victims.size.toLong() != excess || victims.map { it.id }.distinct().size != victims.size) {
                simulatorRxBatchFailed()
            }
            if (radio.value == rows.first().radioId && victims.any { it.id in insertedIDs }) simulatorRxBatchFailed()
            for (victim in victims) state.delete(victim)
            if (dao.count(radio.value) != RxLogRetention.KEEP_COUNT) simulatorRxBatchFailed()
        }
    }
    state.verify()
    val outcomes = rows.zip(decisions) { row, decision ->
        val count = dao.countSimulatorIdentity(row.radioId, row.id)
        if (count > 1L || (decision == SimulatorRxSeedResult.INSERTED &&
                (count != 1L || dao.byId(row.radioId, row.id) != row))
        ) simulatorRxBatchFailed()
        SimulatorRxSeedOutcome(row.id, decision,
            if (count == 0L) SimulatorRxSeedPresence.PRUNED else SimulatorRxSeedPresence.STORED)
    }
    return SimulatorRxSeedBatchWrite(outcomes, inserted = true)
}

private typealias RawRxKey = Pair<String, String>
private typealias RawRxRow = List<Pair<Int, Any?>>

private class SimulatorRxBatchPreimages(
    private val database: MeshCoreDatabase,
    private val radios: Set<RadioId>,
) {
    private val expected = linkedMapOf<RadioId, MutableMap<RawRxKey, RawRxRow>>()

    fun capture() {
        for (radio in radios) expected[radio] = snapshot(radio).toMutableMap()
    }

    suspend fun write(row: RxLogEntryEntity, insert: Boolean) {
        currentCoroutineContext().ensureActive()
        val dao = database.rxLogs()
        if (insert) dao.insert(row) else dao.upsert(row)
        if (dao.byId(row.radioId, row.id) != row ||
            dao.countSimulatorIdentity(row.radioId, row.id) != 1L
        ) simulatorRxBatchFailed()
        val radio = RadioId(row.radioId)
        val key = row.radioId.canonicalString() to row.id.canonicalString()
        val actual = snapshot(radio)
        expected.getValue(radio)[key] = actual[key] ?: simulatorRxBatchFailed()
        verify()
    }

    suspend fun delete(row: RxLogEntryEntity) {
        currentCoroutineContext().ensureActive()
        val dao = database.rxLogs()
        if (dao.countSimulatorIdentity(row.radioId, row.id) != 1L ||
            dao.byId(row.radioId, row.id) != row || dao.delete(row.radioId, row.id) != 1 ||
            dao.countSimulatorIdentity(row.radioId, row.id) != 0L
        ) simulatorRxBatchFailed()
        val key = row.radioId.canonicalString() to row.id.canonicalString()
        if (expected.getValue(RadioId(row.radioId)).remove(key) == null) simulatorRxBatchFailed()
        verify()
    }

    fun verify() {
        for (radio in radios) if (snapshot(radio) != expected.getValue(radio)) simulatorRxBatchFailed()
    }

    private fun snapshot(radio: RadioId): Map<RawRxKey, RawRxRow> =
        database.openHelper.writableDatabase.query(
            "SELECT * FROM rx_log_entries WHERE radioId >= upper(?) AND radioId <= lower(?) " +
                "AND radioId = ? COLLATE NOCASE ORDER BY radioId, id",
            arrayOf(radio.value.canonicalString(), radio.value.canonicalString(), radio.value.canonicalString()),
        ).use { cursor ->
            val radioColumn = cursor.getColumnIndexOrThrow("radioId")
            val idColumn = cursor.getColumnIndexOrThrow("id")
            buildMap {
                while (cursor.moveToNext()) {
                    val key = cursor.getString(radioColumn) to cursor.getString(idColumn)
                    put(key, (0 until cursor.columnCount).map { column ->
                        val type = cursor.getType(column)
                        val value: Any? = when (type) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column).toRawBits()
                            Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                            else -> invalidData("Unsupported simulator RX storage type")
                        }
                        type to value
                    })
                }
            }
        }
}

private fun simulatorRxBatchFailed(): Nothing = throw PersistenceStoreException(
    PersistenceStoreError.SaveFailed("Simulator RX batch did not persist the expected rows"),
    IllegalStateException("Simulator RX batch did not persist the expected rows"),
)
