// PortedFrom: MC1/Views/Tools/TracePath/TracePathViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RepeaterResolvable
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.TraceInfo

/** Pure path transforms and name resolution shared by the state holder and the map logic. */
internal object TracePathEditing {
    /** Rebuilds every hop to [size] bytes: re-prefix from the key, or zero-pad a key-less hop. */
    fun rehash(path: List<TracePathHop>, size: Int): List<TracePathHop> =
        path.map { hop -> hop.copy(hashBytes = (hop.publicKey ?: hop.hashBytes).paddedOrTruncated(size)) }

    /** `Array.move(fromOffsets:toOffset:)`: [destination] is an offset in the original list. */
    fun <T> move(list: List<T>, fromOffsets: Set<Int>, destination: Int): List<T> {
        val indices = fromOffsets.filter { it in list.indices }.sorted()
        val moved = indices.map { list[it] }
        val remaining = list.filterIndexed { index, _ -> index !in fromOffsets }
        val insertAt = (destination - indices.count { it < destination }).coerceIn(0, remaining.size)
        return remaining.subList(0, insertAt) + moved + remaining.subList(insertAt, remaining.size)
    }

    fun hopFor(node: RepeaterResolvable, hashSize: Int): TracePathHop =
        TracePathHop(node.publicKey.prefix(hashSize), node.publicKey, node.resolvableName)

    /** Default save name: one endpoint, both endpoints, or a hex prefix when nothing resolved. */
    fun pathName(path: List<TracePathHop>, fullPathString: String, strings: TracePathStrings): String {
        val names = path.mapNotNull { it.resolvedName }
        return when (names.size) {
            0 -> strings.pathNamePrefix(fullPathString.take(8))
            1 -> names[0]
            2 -> strings.pathNameTwoEndpoints(names[0], names[1])
            else -> strings.pathNameMultipleEndpoints(names[0], names[names.size - 1])
        }
    }
}

/** Contacts first, then discovered repeaters, for one snapshot and device location. */
internal class TraceNodeResolver(private val state: TracePathState, private val location: Coordinate?) {
    fun resolve(hashBytes: Bytes): RepeaterResolvable? =
        RepeaterResolver.bestMatch(hashBytes, state.availableNodes, location)
            ?: RepeaterResolver.bestMatch(hashBytes, state.discoveredRepeaters, location)

    fun resolve(hop: TracePathHop): RepeaterResolvable? =
        RepeaterResolver.bestMatch(hop, state.availableNodes, location)
            ?: RepeaterResolver.bestMatch(hop, state.discoveredRepeaters, location)

    /**
     * Receiver attribution: the start node received nothing (SNR 0), each repeater shows what it
     * measured, and the end node shows the final entry's SNR. A hop already in the outbound path
     * resolves through its stored full key, so a prefix collision keeps the user's choice.
     */
    fun responseHops(traceInfo: TraceInfo, device: DeviceDTO?, myDeviceName: String): SnapshotList<TraceHop> {
        val deviceName = device?.nodeName ?: myDeviceName
        val deviceLat = location?.latitude
        val deviceLon = location?.longitude
        val hops = ArrayList<TraceHop>()
        hops += TraceHop(null, deviceName, 0.0, true, false, deviceLat, deviceLon)
        for (node in traceInfo.path) {
            val bytes = node.hashBytes ?: continue
            val matchingHop = state.outboundPath.firstOrNull { it.hashBytes == bytes }
            val match = matchingHop?.let(::resolve) ?: resolve(bytes)
            val name = match?.resolvableName ?: matchingHop?.resolvedName
            val located = match?.takeIf { it.hasLocation }
            hops += TraceHop(bytes, name, node.snr, false, false, located?.latitude, located?.longitude)
        }
        val endSnr = traceInfo.path.lastOrNull()?.snr ?: 0.0
        hops += TraceHop(null, deviceName, endSnr, false, true, deviceLat, deviceLon)
        return hops.snapshot()
    }
}
