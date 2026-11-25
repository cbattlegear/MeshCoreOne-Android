// PortedFrom: MC1/Views/Chats/Components/MessagePathMapView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Components/MessagePathPreviewSnapshot.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.actions

import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.maps.MapCamera
import com.meshcoreone.android.core.maps.MapLine
import com.meshcoreone.android.core.maps.MapLineStyle
import com.meshcoreone.android.core.maps.MapMarker
import com.meshcoreone.android.core.maps.MapPinStyle
import com.meshcoreone.android.core.maps.boundingCamera
import com.meshcoreone.android.core.maps.totalDistanceMeters
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RepeaterResolvable
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

data class PathEndpoint(
    val id: UUID,
    val publicKey: Bytes,
    val name: String?,
    val coordinate: GeoPoint,
)

data class MessagePathCanvas(
    val points: List<MapMarker>,
    val lines: List<MapLine>,
    val camera: MapCamera?,
    val selectedCoordinates: List<GeoPoint>,
    val hopCount: Int,
    val isDistanceIncomplete: Boolean,
) {
    val locatedCount: Int get() = points.size
    val placedHopCount: Int get() = points.count { it.style == MapPinStyle.REPEATER_HOP }
    val showsPathMap: Boolean
        get() = when {
            hopCount == 0 -> locatedCount >= 1
            placedHopCount == 0 -> false
            placedHopCount == 1 && isDistanceIncomplete -> false
            else -> true
        }
    val totalDistanceMeters: Double? get() = selectedCoordinates.totalDistanceMeters()
}

object MessagePathCanvasBuilder {
    private val receiverId = stableId("message-path-receiver", Bytes.EMPTY)

    fun build(
        message: MessageDTO,
        arrivals: List<MessagePathArrival>,
        selectedId: UUID?,
        directory: MessagePathDirectory,
        localDevice: PathEndpoint?,
        userLocation: Coordinate?,
    ): MessagePathCanvas {
        val selected = MessagePathArrivals.resolvedSelection(selectedId, arrivals)
        val sender = directory.locatedSender(message)
        val receiver = localDevice?.coordinate ?: userLocation?.let { GeoPoint(it.latitude, it.longitude) }
        val points = mutableListOf<MapMarker>()
        val seenKeys = mutableSetOf<Bytes>()
        val start = when {
            message.isOutgoing && localDevice != null -> {
                points += endpointMarker(localDevice, MapPinStyle.POINT_A)
                seenKeys += localDevice.publicKey
                localDevice.coordinate
            }
            sender != null -> {
                points += MapMarker(sender.id, GeoPoint(sender.latitude, sender.longitude), MapPinStyle.POINT_A, sender.displayName, false)
                seenKeys += sender.publicKey
                GeoPoint(sender.latitude, sender.longitude)
            }
            else -> null
        }
        val arrival = arrivals.firstOrNull { it.id == selected }
        val located = if (arrival == null) LocatedPath(emptyList(), emptyList(), false) else {
            locatedPath(arrival, start, receiver, directory, userLocation)
        }
        located.hops.filter { seenKeys.add(it.first) }.forEach { points += it.second }
        if (receiver != null) {
            points += MapMarker(receiverId, receiver, MapPinStyle.POINT_B, localDevice?.name, false)
        }
        val lines = if (arrival != null && located.coordinates.size >= 2) {
            listOf(
                MapLine(
                    id = "message-path-${arrival.id}",
                    points = located.coordinates,
                    style = MapLineStyle.MESSAGE_PATH,
                    opacity = 1f,
                ),
            )
        } else {
            emptyList()
        }
        val camera = if (located.coordinates.size == 1) {
            MapCamera(located.coordinates.first(), 0.05, 0.05)
        } else {
            located.coordinates.boundingCamera(2.5)
        }
        return MessagePathCanvas(
            points,
            lines,
            camera,
            located.coordinates,
            arrival?.hopCount ?: 0,
            located.incomplete,
        )
    }

    private data class LocatedPath(
        val coordinates: List<GeoPoint>,
        val hops: List<Pair<Bytes, MapMarker>>,
        val incomplete: Boolean,
    )

    private fun locatedPath(
        arrival: MessagePathArrival,
        start: GeoPoint?,
        receiver: GeoPoint?,
        directory: MessagePathDirectory,
        userLocation: Coordinate?,
    ): LocatedPath {
        val coordinates = mutableListOf<GeoPoint>()
        start?.let(coordinates::add)
        val hops = mutableListOf<Pair<Bytes, MapMarker>>()
        val seen = mutableSetOf<Bytes>()
        var incomplete = false
        arrival.pathHops.forEachIndexed { index, hop ->
            val resolution = PathNodeResolver.repeatResolution(
                hop.data,
                directory.repeaters,
                directory.discoveredRepeaters,
                userLocation,
                "",
            )
            if (resolution.matchKind != NodeNameMatchKind.EXACT) {
                incomplete = true
                return@forEachIndexed
            }
            val resolved = PathNodeResolver.resolve(hop.data, directory.repeaters, userLocation)
                ?: PathNodeResolver.resolve(hop.data, directory.discoveredRepeaters, userLocation)
            val node: RepeaterResolvable = resolved?.node ?: run {
                incomplete = true
                return@forEachIndexed
            }
            if (!node.hasLocation) {
                incomplete = true
                return@forEachIndexed
            }
            if (seen.add(node.publicKey)) {
                val coordinate = GeoPoint(node.latitude, node.longitude)
                coordinates += coordinate
                hops += node.publicKey to MapMarker(
                    id = stableId("message-path-hop", node.publicKey),
                    position = coordinate,
                    style = MapPinStyle.REPEATER_HOP,
                    label = node.resolvableName,
                    clusterable = false,
                    hopIndex = index + 1,
                )
            }
        }
        if (receiver != null && coordinates.lastOrNull() != receiver) coordinates += receiver
        return LocatedPath(coordinates, hops, incomplete)
    }

    private fun endpointMarker(endpoint: PathEndpoint, style: MapPinStyle) =
        MapMarker(endpoint.id, endpoint.coordinate, style, endpoint.name, false)

    private fun stableId(namespace: String, bytes: Bytes): UUID {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(namespace.toByteArray(Charsets.UTF_8) + bytes.toByteArray())
        val buffer = ByteBuffer.wrap(digest)
        return UUID(buffer.long, buffer.long)
    }
}

data class MessagePathPreviewKey(
    val arrivalId: UUID,
    val isDark: Boolean,
    val isOffline: Boolean,
    val widthBucket: Int,
)

object MessagePathPreviewSizing {
    const val WIDTH_BUCKET_DP = 8
    const val HORIZONTAL_PADDING_DP = 16

    fun bucketedWidth(width: Float): Int =
        (kotlin.math.round(width / WIDTH_BUCKET_DP) * WIDTH_BUCKET_DP).toInt()

    fun key(arrivalId: UUID, isDark: Boolean, isOffline: Boolean, containerWidth: Float) =
        MessagePathPreviewKey(
            arrivalId,
            isDark,
            isOffline,
            bucketedWidth((containerWidth - HORIZONTAL_PADDING_DP * 2).coerceAtLeast(0f)),
        )
}
