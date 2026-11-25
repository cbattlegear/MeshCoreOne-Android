// PortedFrom: MC1/Utilities/RepeaterResolver.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/NodeNameResolution.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.RepeaterResolvable
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType

/** Feature-local mirror of WP-213 `NodeNameMatchKind`. */
enum class NodeNameMatchKind { EXACT, FALLBACK, UNRESOLVED }

/** Feature-local mirror of WP-213 `NodeNameResolution` (the channel-only nickname is omitted). */
data class NodeNameResolution(val displayName: String, val matchKind: NodeNameMatchKind) {
    val isFallback: Boolean get() = matchKind == NodeNameMatchKind.FALLBACK
}

data class ResolvedNode<T : RepeaterResolvable>(val node: T, val matchKind: NodeNameMatchKind)

/** A neighbour resolution with the resolved node's location, when it has one. */
data class ResolvedNeighbor(
    val displayName: String,
    val matchKind: NodeNameMatchKind,
    val latitude: Double?,
    val longitude: Double?,
) {
    val coordinate: Coordinate?
        get() {
            val lat = latitude ?: return null
            val lon = longitude ?: return null
            return Coordinate(lat, lon)
        }
}

/** One stored routing hop resolved to a repeater name. */
data class ResolvedPathHop(val id: Int, val hex: String, val resolution: NodeNameResolution)

/** A stored path hop's bytes and their display hex. */
data class PathHopBytes(val data: Bytes, val hex: String)

/** Resolves hash-prefix collisions by proximity, then advert recency, then name. */
object RepeaterResolver {
    private const val EXACT_PREFIX_LENGTH = 6

    /** Exact full-key match first, then the hash-byte fallback. */
    fun <T : RepeaterResolvable> bestMatch(hop: TracePathHop, nodes: List<T>, userLocation: Coordinate?): T? =
        resolve(hop, nodes, userLocation)?.node

    fun <T : RepeaterResolvable> resolve(hop: TracePathHop, nodes: List<T>, userLocation: Coordinate?): ResolvedNode<T>? {
        val key = hop.publicKey
        if (key != null) {
            nodes.firstOrNull { it.publicKey == key }?.let { return ResolvedNode(it, NodeNameMatchKind.EXACT) }
        }
        return resolve(hop.hashBytes, nodes, userLocation)
    }

    fun <T : RepeaterResolvable> bestMatch(hashBytes: Bytes, nodes: List<T>, userLocation: Coordinate?): T? =
        resolve(hashBytes, nodes, userLocation)?.node

    fun <T : RepeaterResolvable> resolve(hashBytes: Bytes, nodes: List<T>, userLocation: Coordinate?): ResolvedNode<T>? {
        if (hashBytes.isEmpty) return null
        val prefixLength = hashBytes.size
        val candidates = nodes.filter { it.publicKey.prefix(prefixLength) == hashBytes }.map { node ->
            val distance = if (userLocation != null && node.hasLocation) {
                GeoDistance.meters(userLocation.latitude, userLocation.longitude, node.latitude, node.longitude)
            } else {
                null
            }
            Candidate(node, distance)
        }
        if (candidates.isEmpty()) return null
        // Collators are only built when two candidates tie on everything but the name.
        val names = lazy { SourceCollation.localizedStandard() }
        val best = candidates.sortedWith { lhs, rhs -> compare(lhs, rhs, names) }.first().node
        val distinctKeys = candidates.map { it.node.publicKey }.toSet()
        val matchKind = if (prefixLength >= EXACT_PREFIX_LENGTH || distinctKeys.size == 1) {
            NodeNameMatchKind.EXACT
        } else {
            NodeNameMatchKind.FALLBACK
        }
        return ResolvedNode(best, matchKind)
    }

    private data class Candidate<T : RepeaterResolvable>(val node: T, val distance: Double?)

    /** Located before unlocated, nearer first, then newer advert, newer recency, then name. */
    private fun <T : RepeaterResolvable> compare(lhs: Candidate<T>, rhs: Candidate<T>, names: Lazy<Comparator<String>>): Int {
        val left = lhs.distance
        val right = rhs.distance
        if (left != null && right != null && left != right) return left.compareTo(right)
        if (left != null && right == null) return -1
        if (left == null && right != null) return 1
        if (lhs.node.lastAdvertTimestamp != rhs.node.lastAdvertTimestamp) {
            return rhs.node.lastAdvertTimestamp.compareTo(lhs.node.lastAdvertTimestamp)
        }
        if (lhs.node.recencyDate != rhs.node.recencyDate) return rhs.node.recencyDate.compareTo(lhs.node.recencyDate)
        return names.value.compare(lhs.node.resolvableName, rhs.node.resolvableName)
    }
}

object NeighborNameResolver {
    const val MINIMUM_KEY_DISPLAY_BYTE_COUNT = 2
    const val MAXIMUM_KEY_DISPLAY_BYTE_COUNT = 3

    /** Contacts first, then discovered nodes; match kind refined across both sources. */
    fun resolveLocated(
        prefix: Bytes,
        contacts: List<ContactDTO>,
        discoveredNodes: List<DiscoveredNodeDTO>,
        userLocation: Coordinate?,
    ): ResolvedNeighbor? {
        val contact = RepeaterResolver.resolve(prefix, contacts, userLocation)
        if (contact != null) return located(contact.node, contact.matchKind, prefix, contacts, discoveredNodes)
        val node = RepeaterResolver.resolve(prefix, discoveredNodes, userLocation) ?: return null
        return located(node.node, node.matchKind, prefix, contacts, discoveredNodes)
    }

    fun resolve(
        prefix: Bytes,
        contacts: List<ContactDTO>,
        discoveredNodes: List<DiscoveredNodeDTO>,
        userLocation: Coordinate?,
    ): NodeNameResolution? = resolveLocated(prefix, contacts, discoveredNodes, userLocation)
        ?.let { NodeNameResolution(it.displayName, it.matchKind) }

    fun resolveName(
        prefix: Bytes,
        contacts: List<ContactDTO>,
        discoveredNodes: List<DiscoveredNodeDTO>,
        userLocation: Coordinate?,
    ): String? = resolve(prefix, contacts, discoveredNodes, userLocation)?.displayName

    /** Device hash size clamped to 2...3; absent hash size shows 2 bytes. */
    fun keyDisplayByteCount(deviceHashSize: Long?): Int {
        val size = deviceHashSize ?: return MINIMUM_KEY_DISPLAY_BYTE_COUNT
        return size.coerceIn(MINIMUM_KEY_DISPLAY_BYTE_COUNT.toLong(), MAXIMUM_KEY_DISPLAY_BYTE_COUNT.toLong()).toInt()
    }

    /** Uppercase hex of at most three leading bytes, never more than [prefix] has. */
    fun fallbackName(prefix: Bytes, byteCount: Int): String {
        val count = minOf(byteCount.coerceAtLeast(0), MAXIMUM_KEY_DISPLAY_BYTE_COUNT, prefix.size)
        return prefix.prefix(count).uppercaseHexString()
    }

    /** Resolves each stored hop against repeaters only; unmatched hops get [unknownName]. */
    fun resolvePath(
        hops: List<PathHopBytes>,
        contacts: List<ContactDTO>,
        discoveredNodes: List<DiscoveredNodeDTO>,
        userLocation: Coordinate?,
        unknownName: String,
    ): List<ResolvedPathHop> {
        val repeaters = contacts.filter { it.type == ContactType.REPEATER }
        val discoveredRepeaters = discoveredNodes.filter { it.nodeType == ContactType.REPEATER }
        return hops.mapIndexed { index, hop ->
            val resolution = resolve(hop.data, repeaters, discoveredRepeaters, userLocation)
                ?: NodeNameResolution(unknownName, NodeNameMatchKind.UNRESOLVED)
            ResolvedPathHop(index, hop.hex, resolution)
        }
    }

    private fun located(
        node: RepeaterResolvable,
        resolvedMatchKind: NodeNameMatchKind,
        prefix: Bytes,
        contacts: List<ContactDTO>,
        discoveredNodes: List<DiscoveredNodeDTO>,
    ): ResolvedNeighbor = ResolvedNeighbor(
        node.resolvableName,
        matchKind(prefix, resolvedMatchKind, contacts, discoveredNodes),
        if (node.hasLocation) node.latitude else null,
        if (node.hasLocation) node.longitude else null,
    )

    private fun matchKind(
        prefix: Bytes,
        resolvedMatchKind: NodeNameMatchKind,
        contacts: List<ContactDTO>,
        discoveredNodes: List<DiscoveredNodeDTO>,
    ): NodeNameMatchKind {
        if (resolvedMatchKind == NodeNameMatchKind.UNRESOLVED || prefix.size >= 6) return resolvedMatchKind
        val keys = contacts.filter { it.publicKey.prefix(prefix.size) == prefix }.map { it.publicKey } +
            discoveredNodes.filter { it.publicKey.prefix(prefix.size) == prefix }.map { it.publicKey }
        return if (keys.toSet().size > 1) NodeNameMatchKind.FALLBACK else NodeNameMatchKind.EXACT
    }
}
