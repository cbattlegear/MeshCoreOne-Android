// PortedFrom: MC1/Views/PathEditing/PathManagementViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.nodes.path

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.encodePathLen
import com.meshcoreone.android.feature.nodes.deps.NodesMessage

/** The encoded out-path payload and its `out_path_len` byte. */
data class EncodedPath(val path: Bytes, val length: UByte)

/** Pure path-editing rules (the `nonisolated static` members of `PathManagementViewModel`). */
object PathEditing {
    /** Firmware `outPath` budget in bytes; `encodePathLen` also caps the 6-bit hop count. */
    const val PATH_BYTE_BUDGET = 64
    const val PATH_HOP_FIELD_CAP = 63

    /** Hop cap for [hashSize]: the smaller of the hop-count field and the byte budget. */
    fun maxHopCount(hashSize: Int): Int = minOf(PATH_HOP_FIELD_CAP, PATH_BYTE_BUDGET / hashSize)

    /**
     * Normalizes a stored hop to [targetHashSize]: a resolved hop re-slices its full key; an
     * unresolved wider hop truncates; an unresolved same-width or narrower hop is unchanged (save is
     * then refused by [saveRejection]).
     */
    fun normalizeHop(hop: PathHop, targetHashSize: Int): PathHop {
        requireHashSize(targetHashSize)
        val publicKey = hop.publicKey
        if (publicKey != null) return PathHop(publicKey.prefix(targetHashSize), publicKey, hop.resolvedName)
        if (hop.hashBytes.size > targetHashSize) return PathHop(hop.hashBytes.prefix(targetHashSize), null, hop.resolvedName)
        return hop
    }

    /**
     * Why a candidate edit cannot be saved, or null when encoding is safe. A hop without a full key
     * that is narrower than the target would serialize short and the firmware would mis-parse it.
     */
    fun saveRejection(hops: List<PathHop>, targetHashSize: Int, maxHopCount: Int): NodesMessage? {
        requireHashSize(targetHashSize)
        if (hops.any { it.publicKey == null && it.hashBytes.size < targetHashSize }) {
            return NodesMessage.res(R.string.l10n_app_contacts_contacts_pathmanagement_error_hopresizerequired)
        }
        if (hops.size > maxHopCount) {
            return NodesMessage.res(R.string.l10n_app_contacts_contacts_pathmanagement_error_toomanyhops, maxHopCount)
        }
        return null
    }

    /** Encodes at [targetHashSize], preferring the full key and falling back to the stored bytes. */
    fun encodeEditablePath(hops: List<PathHop>, targetHashSize: Int): EncodedPath {
        requireHashSize(targetHashSize)
        val path = hops.fold(Bytes.EMPTY) { result, hop -> result + (hop.publicKey ?: hop.hashBytes).prefix(targetHashSize) }
        return EncodedPath(path, encodePathLen(targetHashSize, hops.size))
    }

    /** Swift `Array.move(fromOffsets:toOffset:)`: [destination] is an index in the original list. */
    fun <T> move(list: List<T>, fromOffsets: Set<Int>, destination: Int): List<T> {
        val sources = fromOffsets.filter { it in list.indices }.sorted()
        val moved = sources.map { list[it] }
        val remaining = list.filterIndexed { index, _ -> index !in fromOffsets }
        val insertAt = (destination - sources.count { it < destination }).coerceIn(0, remaining.size)
        return remaining.subList(0, insertAt) + moved + remaining.subList(insertAt, remaining.size)
    }

    private fun requireHashSize(targetHashSize: Int) =
        require(targetHashSize in 1..3) { "targetHashSize must be 1, 2, or 3" }
}
