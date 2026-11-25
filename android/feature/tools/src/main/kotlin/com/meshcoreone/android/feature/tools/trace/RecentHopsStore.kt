// PortedFrom: MC1/Views/PathEditing/RecentHopsStore.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Feature-local mirror of WP-311's per-radio recent-hop LRU; the storage port replaces UserDefaults.
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.applicationBytesFromHex
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.Locale

/** String-list preference storage (the source used `UserDefaults.stringArray`). */
interface RecentHopsStorage {
    fun stringList(key: String): List<String>?
    fun setStringList(key: String, value: List<String>)
}

/** Most-recent-first public keys, capped at [LIMIT], persisted as lowercase hex per radio. */
class RecentHopsStore(private val storage: RecentHopsStorage) {
    fun load(radioId: RadioId): List<Bytes> =
        storage.stringList(defaultsKey(radioId)).orEmpty().mapNotNull(::applicationBytesFromHex)

    /** Moves [publicKey] to the front (no duplicate), trims to [LIMIT], persists and returns the list. */
    fun record(publicKey: Bytes, current: List<Bytes>, radioId: RadioId): List<Bytes> {
        val updated = (listOf(publicKey) + current.filter { it != publicKey }).take(LIMIT)
        storage.setStringList(defaultsKey(radioId), updated.map { it.hexString })
        return updated
    }

    companion object {
        /** Frozen key prefix shared with the contact path editor's recents. */
        const val KEY_PREFIX = "pathEdit.recentPublicKeys."
        const val LIMIT = 8

        /** `UUID.uuidString` is uppercase. */
        fun defaultsKey(radioId: RadioId): String = KEY_PREFIX + radioId.value.toString().uppercase(Locale.ROOT)
    }
}
