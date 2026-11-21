// PortedFrom: MC1/Views/Chats/Components/MessagePathArrival.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/MessagePathViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/RepeatRowView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/SenderNameLabel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.actions

import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.maps.distanceMetersTo
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageRepeatDTO
import com.meshcoreone.android.core.model.RepeaterResolvable
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.decodePathLen
import java.time.Instant
import java.util.UUID

enum class NodeNameMatchKind { EXACT, FALLBACK, UNRESOLVED }

data class NodeNameResolution(
    val displayName: String,
    val matchKind: NodeNameMatchKind,
    val unverifiedNickname: String? = null,
) {
    val isFallback: Boolean get() = matchKind == NodeNameMatchKind.FALLBACK
}

data class MessagePathArrival(
    val id: UUID,
    val pathNodes: Bytes,
    val pathLength: UByte,
    val snr: Double?,
    val rssi: Long?,
    val receivedAt: Instant,
    val isFirst: Boolean,
) {
    val hopCount: Int get() = decodePathLen(pathLength)?.hopCount ?: (pathLength.toInt() and 63)
    val isZeroHop: Boolean get() = pathNodes.isEmpty && hopCount == 0
    val isPathUnavailable: Boolean get() = pathNodes.isEmpty && !isZeroHop
    val hashSize: Int get() = decodePathLen(pathLength)?.hashSize ?: 1
    val pathHops: List<PathHopBytes>
        get() = (0 until pathNodes.size step hashSize).map { start ->
            val bytes = pathNodes.slice(start, minOf(start + hashSize, pathNodes.size))
            PathHopBytes(bytes, bytes.uppercaseHexString())
        }
    val pathString: String get() = pathHops.joinToString(" \u2192 ") { it.hex }
    val pathStringForClipboard: String get() = pathHops.joinToString(",") { it.hex }
}

data class PathHopBytes(val data: Bytes, val hex: String)

object MessagePathArrivals {
    fun assemble(message: MessageDTO, repeats: List<MessageRepeatDTO>): List<MessagePathArrival> {
        val extras = repeats.sortedBy { it.receivedAt }.map { it.toArrival() }
        if (message.isOutgoing) return extras
        return listOf(
            MessagePathArrival(
                id = message.id,
                pathNodes = message.pathNodes ?: Bytes.EMPTY,
                pathLength = message.pathLength,
                snr = message.snr,
                rssi = null,
                receivedAt = message.createdAt,
                isFirst = true,
            ),
        ) + extras
    }

    fun arrivalCount(message: MessageDTO): Long =
        if (message.isOutgoing) message.heardRepeats else 1 + message.heardRepeats

    fun resolvedSelection(preferred: UUID?, arrivals: List<MessagePathArrival>): UUID? =
        preferred?.takeIf { id -> arrivals.any { it.id == id } } ?: arrivals.firstOrNull()?.id

    private fun MessageRepeatDTO.toArrival() = MessagePathArrival(
        id, pathNodes, pathLength, snr, rssi, receivedAt, isFirst = false,
    )
}

data class ResolvedPathNode<T : RepeaterResolvable>(
    val node: T,
    val matchKind: NodeNameMatchKind,
)

object PathNodeResolver {
    private const val EXACT_PREFIX_SIZE = 6

    fun resolution(
        prefix: Bytes?,
        contacts: List<ContactDTO>,
        discoveredNodes: List<DiscoveredNodeDTO>,
        userLocation: Coordinate?,
        unknownName: String,
    ): NodeNameResolution {
        if (prefix == null || prefix.isEmpty) {
            return NodeNameResolution(unknownName, NodeNameMatchKind.UNRESOLVED)
        }
        val contact = resolve(prefix, contacts, userLocation)
        val discovered = resolve(prefix, discoveredNodes, userLocation)
        val selected = contact ?: discovered
            ?: return NodeNameResolution(unknownName, NodeNameMatchKind.UNRESOLVED)
        val totalKeys = (contacts.asSequence().map { it.publicKey } +
            discoveredNodes.asSequence().map { it.publicKey })
            .filter { it.prefix(prefix.size) == prefix }
            .distinct()
            .count()
        val kind = if (prefix.size >= EXACT_PREFIX_SIZE || totalKeys == 1) {
            NodeNameMatchKind.EXACT
        } else {
            NodeNameMatchKind.FALLBACK
        }
        return NodeNameResolution(selected.node.resolvableName, kind)
    }

    fun repeatResolution(
        hash: Bytes?,
        contacts: List<ContactDTO>,
        discoveredNodes: List<DiscoveredNodeDTO>,
        userLocation: Coordinate?,
        unknownName: String,
    ): NodeNameResolution = resolution(
        hash,
        contacts.filter { it.type == ContactType.REPEATER },
        discoveredNodes.filter { it.nodeType == ContactType.REPEATER },
        userLocation,
        unknownName,
    )

    fun <T : RepeaterResolvable> resolve(
        prefix: Bytes,
        nodes: List<T>,
        userLocation: Coordinate?,
    ): ResolvedPathNode<T>? {
        if (prefix.isEmpty) return null
        val matches = nodes.filter { it.publicKey.prefix(prefix.size) == prefix }
        if (matches.isEmpty()) return null
        val selected = matches.sortedWith(
            compareBy<T> { node ->
                if (userLocation != null && node.hasLocation) {
                    GeoPoint(userLocation.latitude, userLocation.longitude)
                        .distanceMetersTo(GeoPoint(node.latitude, node.longitude))
                } else {
                    Double.POSITIVE_INFINITY
                }
            }.thenByDescending { it.lastAdvertTimestamp }
                .thenByDescending { it.recencyDate }
                .thenBy { it.resolvableName },
        ).first()
        val kind = if (prefix.size >= EXACT_PREFIX_SIZE || matches.map { it.publicKey }.distinct().size == 1) {
            NodeNameMatchKind.EXACT
        } else {
            NodeNameMatchKind.FALLBACK
        }
        return ResolvedPathNode(selected, kind)
    }
}

data class MessagePathDirectory(
    val contacts: List<ContactDTO> = emptyList(),
    val repeaters: List<ContactDTO> = emptyList(),
    val discoveredRepeaters: List<DiscoveredNodeDTO> = emptyList(),
) {
    fun senderResolution(message: MessageDTO, localDeviceName: String, unknownName: String): NodeNameResolution {
        if (message.isOutgoing) return NodeNameResolution(localDeviceName, NodeNameMatchKind.EXACT)
        val senderNodeName = message.senderNodeName
        if (message.isChannelMessage && senderNodeName != null) {
            return NodeNameResolution(senderNodeName, NodeNameMatchKind.EXACT)
        }
        return PathNodeResolver.resolution(
            message.senderKeyPrefix,
            contacts,
            emptyList(),
            null,
            unknownName,
        )
    }

    fun senderNodeId(message: MessageDTO): String? =
        message.senderKeyPrefix?.takeUnless { it.isEmpty }?.get(0)?.let {
            "%02X".format(it.toInt() and 0xFF)
        }

    fun senderContact(message: MessageDTO): ContactDTO? {
        message.senderKeyPrefix?.takeUnless { it.isEmpty }?.let { prefix ->
            return contacts.firstOrNull { it.publicKeyPrefix == prefix }
        }
        val senderName = message.senderNodeName ?: return null
        if (!message.isChannelMessage || senderName.isEmpty()) return null
        return contacts.filter { it.name.equals(senderName, ignoreCase = true) }
            .singleOrNull()
    }

    fun locatedSender(message: MessageDTO): ContactDTO? = senderContact(message)?.takeIf { it.hasLocation }
}
