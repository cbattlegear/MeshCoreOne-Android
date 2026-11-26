// PortedFrom: MC1Tests/Views/Chats/Components/MessagePathArrivalTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/ViewModels/MessagePathViewModelTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/Views/Chats/Components/RepeatRowViewTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.actions

import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class MessagePathModelsTest {
    @Test
    fun `incoming assembly keeps canonical first then time ordered extras`() {
        val incoming = message(pathNodes = Bytes.of(0xAA), heardRepeats = 2)
        val later = repeat(incoming.id, Bytes.of(0xBB), receivedAt = TEST_TIME.plusSeconds(5))
        val earlier = repeat(incoming.id, Bytes.of(0xCC), receivedAt = TEST_TIME.plusSeconds(1))
        val arrivals = MessagePathArrivals.assemble(incoming, listOf(later, earlier))
        assertEquals(3, arrivals.size)
        assertTrue(arrivals[0].isFirst)
        assertEquals(incoming.id, arrivals[0].id)
        assertEquals(Bytes.of(0xCC), arrivals[1].pathNodes)
        assertEquals(Bytes.of(0xBB), arrivals[2].pathNodes)
        assertEquals(3, MessagePathArrivals.arrivalCount(incoming))
    }

    @Test
    fun `zero hop and unavailable are distinct`() {
        val incoming = message(pathLength = 0u, pathNodes = null, heardRepeats = 1)
        val arrivals = MessagePathArrivals.assemble(incoming, listOf(repeat(incoming.id)))
        assertTrue(arrivals.first().isZeroHop)
        assertFalse(arrivals.first().isPathUnavailable)
        assertTrue(arrivals.first().pathHops.isEmpty())
        val unavailable = MessagePathArrival(UUID.randomUUID(), Bytes.EMPTY, 1u, null, null, TEST_TIME, true)
        assertTrue(unavailable.isPathUnavailable)
    }

    @Test
    fun `outgoing assembly includes every echo and reads its path`() {
        val outgoing = message(direction = MessageDirection.OUTGOING, pathNodes = null, heardRepeats = 3)
        val repeats = listOf(
            repeat(outgoing.id, Bytes.of(0x42)),
            repeat(outgoing.id, Bytes.of(0x42), receivedAt = TEST_TIME.plusSeconds(1)),
            repeat(outgoing.id, Bytes.of(0x42), receivedAt = TEST_TIME.plusSeconds(2)),
        )
        val arrivals = MessagePathArrivals.assemble(outgoing, repeats)
        assertEquals(3, arrivals.size)
        assertTrue(arrivals.none { it.isFirst })
        assertEquals(3, MessagePathArrivals.arrivalCount(outgoing))
        assertEquals("42", arrivals.first().pathStringForClipboard)
        val twoHops = MessagePathArrivals.assemble(
            outgoing.copy(heardRepeats = 1),
            listOf(repeat(outgoing.id, Bytes.of(0xA3, 0x7F), pathLength = 2u)),
        ).first()
        assertEquals(listOf("A3", "7F"), twoHops.pathHops.map { it.hex })
        assertEquals("A3,7F", twoHops.pathStringForClipboard)
    }

    @Test
    fun `selection preserves current id and otherwise falls back to first`() {
        val arrivals = (0..2).map {
            MessagePathArrival(UUID.randomUUID(), Bytes.of(it), 1u, null, null, TEST_TIME.plusSeconds(it.toLong()), it == 0)
        }
        assertEquals(arrivals[1].id, MessagePathArrivals.resolvedSelection(arrivals[1].id, arrivals))
        assertEquals(arrivals[0].id, MessagePathArrivals.resolvedSelection(UUID.randomUUID(), arrivals))
        assertEquals(arrivals[0].id, MessagePathArrivals.resolvedSelection(null, arrivals))
        assertNull(MessagePathArrivals.resolvedSelection(UUID.randomUUID(), emptyList()))
    }

    @Test
    fun `sender resolution preserves exact fallback channel and local identities`() {
        val older = contact(0xAA, 1, "Older", ContactType.CHAT, lastAdvert = 100u)
        val newer = contact(0xAA, 2, "Newer", ContactType.CHAT, lastAdvert = 200u)
        val directory = MessagePathDirectory(contacts = listOf(older, newer))
        val fallback = directory.senderResolution(
            message(senderKeyPrefix = Bytes.of(0xAA), senderNodeName = null),
            "",
            "Unknown",
        )
        assertEquals("Newer", fallback.displayName)
        assertEquals(NodeNameMatchKind.FALLBACK, fallback.matchKind)
        val exact = MessagePathDirectory(contacts = listOf(older)).senderResolution(
            message(senderKeyPrefix = Bytes.of(0xAA), senderNodeName = null),
            "",
            "Unknown",
        )
        assertEquals(NodeNameMatchKind.EXACT, exact.matchKind)
        assertEquals(
            "RemoteNode",
            directory.senderResolution(message(channelIndex = 0u), "Radio", "Unknown").displayName,
        )
        assertEquals(
            "Radio",
            directory.senderResolution(
                message(direction = MessageDirection.OUTGOING, channelIndex = 0u),
                "Radio",
                "Unknown",
            ).displayName,
        )
        assertEquals(
            "Unknown",
            directory.senderResolution(
                message(senderKeyPrefix = Bytes.of(0x11), senderNodeName = null),
                "",
                "Unknown",
            ).displayName,
        )
    }

    @Test
    fun `sender node id handles missing empty and leading zero prefixes`() {
        val directory = MessagePathDirectory()
        assertEquals("AB", directory.senderNodeId(message(senderKeyPrefix = Bytes.of(0xAB))))
        assertEquals("0A", directory.senderNodeId(message(senderKeyPrefix = Bytes.of(0x0A))))
        assertNull(directory.senderNodeId(message(senderKeyPrefix = null)))
        assertNull(directory.senderNodeId(message(senderKeyPrefix = Bytes.EMPTY)))
    }

    @Test
    fun `located sender prefers prefix and requires unique located channel name`() {
        val prefixed = contact(0xAA, 1, "Alpha", ContactType.CHAT, 37.7, -122.4)
        val named = contact(0xBB, 2, "RemoteNode", ContactType.CHAT, 37.8, -122.5)
        val directory = MessagePathDirectory(contacts = listOf(prefixed, named))
        assertEquals(
            prefixed.id,
            directory.locatedSender(
                message(channelIndex = 0u, senderKeyPrefix = prefixed.publicKeyPrefix, senderNodeName = "RemoteNode"),
            )?.id,
        )
        assertEquals(
            named.id,
            directory.locatedSender(message(channelIndex = 0u, senderKeyPrefix = null, senderNodeName = "remotenode"))?.id,
        )
        assertNull(
            MessagePathDirectory(contacts = listOf(named, named.copy(id = UUID.randomUUID())))
                .locatedSender(message(channelIndex = 0u, senderKeyPrefix = null)),
        )
        assertNull(
            MessagePathDirectory(contacts = listOf(named.copy(latitude = 0.0, longitude = 0.0)))
                .locatedSender(message(channelIndex = 0u, senderKeyPrefix = null)),
        )
        assertNull(
            directory.locatedSender(message(channelIndex = 0u, senderKeyPrefix = null, senderNodeName = "Missing")),
        )
        val blocked = named.copy(isBlocked = true)
        assertEquals(
            blocked.id,
            MessagePathDirectory(contacts = listOf(blocked))
                .locatedSender(message(channelIndex = 0u, senderKeyPrefix = null))?.id,
        )
    }

    @Test
    fun `repeat resolution reports exact fallback and unresolved`() {
        val ridge = contact(0x3F, 1, "Ridge")
        assertEquals(
            NodeNameMatchKind.EXACT,
            PathNodeResolver.repeatResolution(Bytes.of(0x3F), listOf(ridge), emptyList(), null, "Unknown").matchKind,
        )
        assertEquals(
            NodeNameMatchKind.FALLBACK,
            PathNodeResolver.repeatResolution(
                Bytes.of(0x3F),
                listOf(ridge, contact(0x3F, 2, "Valley")),
                emptyList(),
                Coordinate(37.0, -122.0),
                "Unknown",
            ).matchKind,
        )
        assertEquals(
            NodeNameMatchKind.UNRESOLVED,
            PathNodeResolver.repeatResolution(Bytes.of(0xAA), listOf(ridge), emptyList(), null, "Unknown").matchKind,
        )
        assertEquals(
            "Unknown",
            PathNodeResolver.repeatResolution(null, emptyList(), emptyList(), null, "Unknown").displayName,
        )
    }
}
