// AndroidOnly: WP-202 Reject ambiguous RX UUID spellings without modifying user records.
package com.meshcoreone.android.core.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.meshcoreone.android.core.model.RadioId

/**
 * Call in the same Room transaction as the affected read/write. UUID conversion loses the physical
 * spelling, so inspecting decoded entities or a process-local cache cannot detect these aliases.
 */
internal fun RepositoryTransaction.requireCanonicalRxKeys(radioId: RadioId) {
    val canonical = radioId.canonicalString
    database.query(SimpleSQLiteQuery(
        "SELECT 1 FROM rx_log_entries WHERE radioId COLLATE NOCASE = ? " +
            "AND (radioId COLLATE BINARY != ? OR id COLLATE BINARY != UPPER(id)) LIMIT 1",
        arrayOf(canonical, canonical),
    )).use { rows ->
        if (rows.moveToFirst()) {
            invalidData("RX log UUID case aliases require explicit data repair; automatic overwrite or normalization is refused")
        }
    }
}

internal suspend fun RepositoryTransaction.persistPendingRx() {
    val radios = pendingRx.keys.mapTo(LinkedHashSet()) { it.radioId }
    for (radio in radios) requireCanonicalRxKeys(radio)
    database.rxLogs().upsert(pendingRx.values.toList())
    // Include aliases introduced by triggers in this transaction, before pruning or commit.
    for (radio in radios) requireCanonicalRxKeys(radio)
}
