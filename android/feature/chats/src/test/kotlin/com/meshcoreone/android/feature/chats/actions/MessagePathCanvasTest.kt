// PortedFrom: MC1Tests/Views/Chats/Components/MessagePathDetailSelectionTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/Views/Chats/Components/MessagePathPreviewMapTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/Views/Chats/Components/MessagePathPreviewSnapshotTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.actions

import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.maps.MapPinStyle
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class MessagePathCanvasTest {
    @Test
    fun `stable endpoint ids and zero hop receiver remain mappable`() {
        val incoming = message(pathLength = 0u, pathNodes = Bytes.EMPTY)
        val arrivals = MessagePathArrivals.assemble(incoming, emptyList())
        val first = MessagePathCanvasBuilder.build(incoming, arrivals, null, MessagePathDirectory(), null, Coordinate(37.5, -122.3))
        val second = MessagePathCanvasBuilder.build(incoming, arrivals, null, MessagePathDirectory(), null, Coordinate(37.5, -122.3))
        assertEquals(first.points.map { it.id }, second.points.map { it.id })
        assertTrue(first.points.isNotEmpty())
        assertTrue(first.showsPathMap)
    }

    @Test
    fun `outgoing path starts at located device and includes selected hop`() {
        val outgoing = message(direction = MessageDirection.OUTGOING, pathNodes = null, heardRepeats = 1)
        val echo = repeat(outgoing.id, Bytes.of(0xAA))
        val hop = contact(0xAA, name = "HopA", latitude = 51.51, longitude = -0.14)
        val device = PathEndpoint(UUID.randomUUID(), Bytes.of(1), "Radio", GeoPoint(51.5074, -0.1278))
        val canvas = MessagePathCanvasBuilder.build(
            outgoing,
            MessagePathArrivals.assemble(outgoing, listOf(echo)),
            null,
            MessagePathDirectory(repeaters = listOf(hop)),
            device,
            Coordinate(51.0, -0.2),
        )
        assertEquals(device.id, canvas.points.first { it.style == MapPinStyle.POINT_A }.id)
        assertEquals(device.coordinate, canvas.lines.single().points.first())
        assertTrue(canvas.lines.single().points.contains(GeoPoint(51.51, -0.14)))
    }

    @Test
    fun `selection changes only the chosen line and hop while endpoints survive`() {
        val sender = contact(0x11, name = "Alice", type = ContactType.CHAT, latitude = 36.0, longitude = -121.0)
        val firstHop = contact(0xAA, name = "HopA", latitude = 37.11, longitude = -122.1)
        val extraHop = contact(0xBB, name = "HopB", latitude = 38.22, longitude = -123.2)
        val incoming = message(
            channelIndex = 0u,
            pathLength = 1u,
            pathNodes = Bytes.of(0xAA),
            senderKeyPrefix = sender.publicKeyPrefix,
            heardRepeats = 1,
        )
        val arrivals = MessagePathArrivals.assemble(
            incoming,
            listOf(repeat(incoming.id, Bytes.of(0xBB), receivedAt = TEST_TIME.plusSeconds(2))),
        )
        val directory = MessagePathDirectory(
            contacts = listOf(sender, firstHop, extraHop),
            repeaters = listOf(firstHop, extraHop),
        )
        val first = MessagePathCanvasBuilder.build(incoming, arrivals, null, directory, null, Coordinate(37.5, -122.3))
        val extra = MessagePathCanvasBuilder.build(incoming, arrivals, arrivals[1].id, directory, null, Coordinate(37.5, -122.3))
        assertEquals("message-path-${arrivals[0].id}", first.lines.single().id)
        assertEquals("message-path-${arrivals[1].id}", extra.lines.single().id)
        assertNotEquals(first.camera?.center, extra.camera?.center)
        assertEquals(
            first.points.filter { it.style != MapPinStyle.REPEATER_HOP }.map { it.id },
            extra.points.filter { it.style != MapPinStyle.REPEATER_HOP }.map { it.id },
        )
        assertTrue(first.points.any { it.position.latitude == 37.11 })
        assertFalse(first.points.any { it.position.latitude == 38.22 })
        assertTrue(extra.points.any { it.position.latitude == 38.22 })
        assertFalse(extra.points.any { it.position.latitude == 37.11 })
    }

    @Test
    fun `distance completeness follows exact located hop placement`() {
        val exact = contact(0xAA, name = "Hop", latitude = 37.1, longitude = -122.1)
        assertFalse(canvas(Bytes.of(0xAA), 1u, listOf(exact)).isDistanceIncomplete)
        assertTrue(canvas(Bytes.of(0xFF), 1u, emptyList()).isDistanceIncomplete)
        assertTrue(canvas(Bytes.of(0xAA), 1u, listOf(exact.copy(latitude = 0.0, longitude = 0.0))).isDistanceIncomplete)
        assertTrue(
            canvas(
                Bytes.of(0xAA),
                1u,
                listOf(exact, contact(0xAA, 2, "Other", latitude = 37.2, longitude = -122.2)),
            ).isDistanceIncomplete,
        )
        assertFalse(canvas(Bytes.of(0xAA, 0xAA), 2u, listOf(exact)).isDistanceIncomplete)
    }

    @Test
    fun `map visibility rejects shortcuts but allows two placed hops with a skipped hop`() {
        assertFalse(canvas(Bytes.EMPTY, 0u, emptyList(), null).showsPathMap)
        val one = contact(0xAA, name = "A", latitude = 37.1, longitude = -122.1)
        val collision = canvas(
            Bytes.of(0xAA),
            1u,
            listOf(one),
            Coordinate(37.5, -122.3),
            discovered = listOf(discovered(0xAA, 2)),
        )
        assertFalse(collision.showsPathMap)
        assertTrue(collision.points.none { it.style == MapPinStyle.REPEATER_HOP })
        assertFalse(canvas(Bytes.of(0xAA, 0xFF), 2u, listOf(one)).showsPathMap)
        val two = contact(0xBB, name = "B", latitude = 38.2, longitude = -123.2)
        assertTrue(canvas(Bytes.of(0xAA, 0xBB, 0xFF), 3u, listOf(one, two)).showsPathMap)
    }

    @Test
    fun `preview width buckets ignore one point jitter`() {
        assertEquals(MessagePathPreviewSizing.bucketedWidth(357f), MessagePathPreviewSizing.bucketedWidth(358f))
        assertNotEquals(MessagePathPreviewSizing.bucketedWidth(357f), MessagePathPreviewSizing.bucketedWidth(340f))
        val id = UUID.randomUUID()
        assertEquals(
            MessagePathPreviewSizing.key(id, false, false, 390f),
            MessagePathPreviewSizing.key(id, false, false, 391f),
        )
    }

    private fun canvas(
        path: Bytes,
        length: UByte,
        repeaters: List<com.meshcoreone.android.core.model.ContactDTO>,
        location: Coordinate? = Coordinate(37.5, -122.3),
        discovered: List<com.meshcoreone.android.core.model.DiscoveredNodeDTO> = emptyList(),
    ): MessagePathCanvas {
        val incoming = message(pathLength = length, pathNodes = path)
        return MessagePathCanvasBuilder.build(
            incoming,
            MessagePathArrivals.assemble(incoming, emptyList()),
            null,
            MessagePathDirectory(repeaters = repeaters, discoveredRepeaters = discovered),
            null,
            location,
        )
    }
}
