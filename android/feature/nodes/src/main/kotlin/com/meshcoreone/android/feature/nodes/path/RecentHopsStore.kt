// PortedFrom: MC1/Views/PathEditing/RecentHopsStore.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.nodes.path

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.applicationBytesFromHex
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.nodes.deps.StringListPreferences

/**
 * Per-radio LRU of recently added hop public keys, most recent first, capped at [LIMIT]. Shared by
 * the contact path editor and the trace path builder; the owning state holder keeps the list.
 */
class RecentHopsStore(private val preferences: StringListPreferences) {
    /** Loads the persisted recents for [radioId], newest first; malformed entries are dropped. */
    fun load(radioId: RadioId): List<Bytes> =
        preferences.stringList(defaultsKey(radioId)).orEmpty().mapNotNull(::applicationBytesFromHex)

    /**
     * Moves [publicKey] to the front of [current] (no duplicates), trims to [LIMIT], persists the
     * lowercase hex for [radioId] and returns the new list.
     */
    fun record(publicKey: Bytes, current: List<Bytes>, radioId: RadioId): List<Bytes> {
        val updated = (listOf(publicKey) + current.filterNot { it == publicKey }).take(LIMIT)
        preferences.setStringList(defaultsKey(radioId), updated.map { it.hexString })
        return updated
    }

    companion object {
        /** Frozen storage-key prefix; existing recents persist under this exact key. */
        private const val KEY_PREFIX = "pathEdit.recentPublicKeys."
        const val LIMIT = 8

        /** `"pathEdit.recentPublicKeys.<UUID uppercase>"` (`UUID.uuidString`). */
        fun defaultsKey(radioId: RadioId): String = KEY_PREFIX + radioId.canonicalString
    }
}
