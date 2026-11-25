// PortedFrom: MC1/Utilities/RepeaterResolver.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/NodeNameResolution.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Feature-local mirror of the prefix-resolution half of RepeaterResolver (WP-314 owns the original in
// feature:tools; features may not depend on each other). See docs/android/deviations/WP-313.md.
package com.meshcoreone.android.feature.remotenodes.resolver

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.PathHop
import com.meshcoreone.android.core.model.RepeaterResolvable
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.remotenodes.common.GeoDistance
import com.meshcoreone.android.feature.remotenodes.common.LocalizedStandardOrder
import java.util.Locale

/** Confidence of a node-name resolution (Swift `NodeNameMatchKind`). */
enum class NodeNameMatchKind { EXACT, FALLBACK, UNRESOLVED }

/** A resolved display name and its confidence (Swift `NodeNameResolution`). */
data class NodeNameResolution(val displayName: String, val matchKind: NodeNameMatchKind)

/** A neighbor resolution carrying the resolved node's location (Swift `ResolvedNeighbor`). */
data class ResolvedNeighbor(
    val displayName: String,
    val matchKind: NodeNameMatchKind,
    val latitude: Double?,
    val longitude: Double?,
) {
    val coordinate: Coordinate? get() = if (latitude != null && longitude != null) Coordinate(latitude, longitude) else null
}

/**
 * One stored-path hop resolved to a repeater (Swift `ResolvedPathHop`). A null [resolution] is the
 * Swift placeholder `NodeNameResolution(L10n...Auth.pathHopUnknown, .unresolved)`; the screen renders
 * the localized "unknown" text.
 */
data class ResolvedPathHop(val id: Int, val hex: String, val resolution: NodeNameResolution?)

internal data class ResolvedNode<T : RepeaterResolvable>(val node: T, val matchKind: NodeNameMatchKind)

/** Resolves repeater-prefix collisions by proximity, then recency, then name. */
internal object RepeaterResolver {
    private const val EXACT_PREFIX_LENGTH = 6

    fun <T : RepeaterResolvable> resolve(
        hashBytes: Bytes,
        nodes: List<T>,
        userLocation: Coordinate?,
        locale: Locale,
    ): ResolvedNode<T>? {
        if (hashBytes.isEmpty) return null
        val prefixLength = hashBytes.size
        val candidates = nodes.mapNotNull { node ->
            if (node.publicKey.prefix(prefixLength) != hashBytes) return@mapNotNull null
            val distance = if (userLocation != null && node.hasLocation) {
                GeoDistance.meters(userLocation, Coordinate(node.latitude, node.longitude))
            } else null
            node to distance
        }
        if (candidates.isEmpty()) return null
        val names = LocalizedStandardOrder(locale)
        val ordered = candidates.sortedWith { left, right ->
            val a = left.second
            val b = right.second
            when {
                a != null && b != null && a != b -> a.compareTo(b)
                a != null && b == null -> -1
                a == null && b != null -> 1
                left.first.lastAdvertTimestamp != right.first.lastAdvertTimestamp ->
                    right.first.lastAdvertTimestamp.compareTo(left.first.lastAdvertTimestamp)
                left.first.recencyDate != right.first.recencyDate ->
                    right.first.recencyDate.compareTo(left.first.recencyDate)
                else -> names.compare(left.first.resolvableName, right.first.resolvableName)
            }
        }
        val node = ordered.first().first
        val matchingKeys = candidates.map { it.first.publicKey }.toSet()
        val kind = if (prefixLength >= EXACT_PREFIX_LENGTH || matchingKeys.size == 1) {
            NodeNameMatchKind.EXACT
        } else {
            NodeNameMatchKind.FALLBACK
        }
        return ResolvedNode(node, kind)
    }
}

/** Swift `NeighborNameResolver`: contacts first, then discovered nodes, with cross-source refinement. */
object NeighborNameResolver {
    const val MINIMUM_KEY_DISPLAY_BYTE_COUNT = 2
    const val MAXIMUM_KEY_DISPLAY_BYTE_COUNT = 3

    fun resolveLocated(
        prefix: Bytes,
        contacts: List<ContactDTO>,
        discoveredNodes: List<DiscoveredNodeDTO>,
        userLocation: Coordinate?,
        locale: Locale = Locale.getDefault(),
    ): ResolvedNeighbor? {
        RepeaterResolver.resolve(prefix, contacts, userLocation, locale)?.let {
            return located(it.node, it.matchKind, prefix, contacts, discoveredNodes)
        }
        RepeaterResolver.resolve(prefix, discoveredNodes, userLocation, locale)?.let {
            return located(it.node, it.matchKind, prefix, contacts, discoveredNodes)
        }
        return null
    }

    fun resolve(
        prefix: Bytes,
        contacts: List<ContactDTO>,
        discoveredNodes: List<DiscoveredNodeDTO>,
        userLocation: Coordinate?,
        locale: Locale = Locale.getDefault(),
    ): NodeNameResolution? = resolveLocated(prefix, contacts, discoveredNodes, userLocation, locale)
        ?.let { NodeNameResolution(it.displayName, it.matchKind) }

    fun resolveName(
        prefix: Bytes,
        contacts: List<ContactDTO>,
        discoveredNodes: List<DiscoveredNodeDTO>,
        userLocation: Coordinate?,
        locale: Locale = Locale.getDefault(),
    ): String? = resolve(prefix, contacts, discoveredNodes, userLocation, locale)?.displayName

    /** Hex bytes to show for a neighbour identity: the device hash size clamped to 2...3. */
    fun keyDisplayByteCount(deviceHashSize: Int?): Int {
        val size = deviceHashSize ?: return MINIMUM_KEY_DISPLAY_BYTE_COUNT
        return size.coerceIn(MINIMUM_KEY_DISPLAY_BYTE_COUNT, MAXIMUM_KEY_DISPLAY_BYTE_COUNT)
    }

    /** Uppercase hex of the leading bytes, clamped to the maximum display width and the prefix. */
    fun fallbackName(prefix: Bytes, byteCount: Int): String {
        val count = minOf(maxOf(byteCount, 0), MAXIMUM_KEY_DISPLAY_BYTE_COUNT, prefix.size)
        return prefix.prefix(count).uppercaseHexString()
    }

    /** Resolves each stored path hop against repeaters only (only repeaters relay). */
    fun resolvePath(
        hops: List<PathHop>,
        contacts: List<ContactDTO>,
        discoveredNodes: List<DiscoveredNodeDTO>,
        userLocation: Coordinate?,
        locale: Locale = Locale.getDefault(),
    ): List<ResolvedPathHop> {
        val repeaters = contacts.filter { it.type == ContactType.REPEATER }
        val discoveredRepeaters = discoveredNodes.filter { it.nodeType == ContactType.REPEATER }
        return hops.mapIndexed { index, hop ->
            ResolvedPathHop(index, hop.hex, resolve(hop.data, repeaters, discoveredRepeaters, userLocation, locale))
        }
    }

    private fun located(
        node: RepeaterResolvable,
        resolvedMatchKind: NodeNameMatchKind,
        prefix: Bytes,
        contacts: List<ContactDTO>,
        discoveredNodes: List<DiscoveredNodeDTO>,
    ): ResolvedNeighbor = ResolvedNeighbor(
        displayName = node.resolvableName,
        matchKind = matchKind(prefix, resolvedMatchKind, contacts, discoveredNodes),
        latitude = if (node.hasLocation) node.latitude else null,
        longitude = if (node.hasLocation) node.longitude else null,
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
