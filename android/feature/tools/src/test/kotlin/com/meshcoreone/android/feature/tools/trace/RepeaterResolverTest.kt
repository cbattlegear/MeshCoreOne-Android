// PortedFrom: MC1Tests/Utilities/RepeaterResolverTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class RepeaterResolverTest {
    private val user = Coordinate(37.0005, -122.0005)

    private fun repeater(prefix: Int, second: Int, name: String, advert: UInt, lat: Double, lon: Double) =
        contact(prefix, second, name = name, lastAdvertTimestamp = advert, latitude = lat, longitude = lon, lastHeardTimestamp = null)

    private fun node(prefix: Int, second: Int, name: String, advert: UInt, lat: Double, lon: Double, heard: Instant = T0) =
        discovered(key(prefix, second), name, advert, heard, lat, lon)

    @Test @OriginalCase("RepeaterResolverTests::prefers closest repeater when location available()")
    fun `prefers closest repeater when location available`() {
        val near = repeater(0x3F, 0x01, "Near", 10u, 37.0, -122.0)
        val far = repeater(0x3F, 0x02, "Far", 200u, 38.0, -123.0)
        assertEquals("Near", RepeaterResolver.bestMatch(Bytes.of(0x3F), listOf(near, far), user)?.displayName)
    }

    @Test @OriginalCase("RepeaterResolverTests::exact match with full public key ignores proximity/recency()")
    fun `exact match with full public key ignores proximity or recency`() {
        val target = repeater(0x3F, 0x01, "Target", 10u, 38.0, -123.0)
        val closer = repeater(0x3F, 0x02, "Closer and Newer", 200u, 37.0, -122.0)
        val hop = TracePathHop(Bytes.of(0x3F), target.publicKey, "Target")
        assertEquals("Target", RepeaterResolver.bestMatch(hop, listOf(target, closer), user)?.displayName)
    }

    @Test @OriginalCase("RepeaterResolverTests::PathHop without public key falls back to proximity/recency()")
    fun `PathHop without public key falls back to proximity or recency`() {
        val far = repeater(0x3F, 0x01, "Far", 10u, 38.0, -123.0)
        val near = repeater(0x3F, 0x02, "Near", 200u, 37.0, -122.0)
        assertEquals("Near", RepeaterResolver.bestMatch(TracePathHop(Bytes.of(0x3F)), listOf(far, near), user)?.displayName)
    }

    @Test @OriginalCase("RepeaterResolverTests::PathHop with deleted contact key falls back to hash byte match()")
    fun `PathHop with deleted contact key falls back to hash byte match`() {
        val only = repeater(0x3F, 0x01, "Only Match", 10u, 0.0, 0.0)
        val hop = TracePathHop(Bytes.of(0x3F), key(0x3F, 0xFF), "Deleted")
        assertEquals("Only Match", RepeaterResolver.bestMatch(hop, listOf(only), null)?.displayName)
    }

    @Test @OriginalCase("RepeaterResolverTests::prefers closest discovered node when location available()")
    fun `prefers closest discovered node when location available`() {
        val near = node(0x3F, 0x01, "Near Node", 10u, 37.0, -122.0)
        val far = node(0x3F, 0x02, "Far Node", 200u, 38.0, -123.0)
        assertEquals("Near Node", RepeaterResolver.bestMatch(Bytes.of(0x3F), listOf(near, far), user)?.name)
    }

    @Test @OriginalCase("RepeaterResolverTests::prefers most recent discovered node without location()")
    fun `prefers most recent discovered node without location`() {
        val older = node(0x3F, 0x01, "Older Node", 10u, 0.0, 0.0)
        val newer = node(0x3F, 0x02, "Newer Node", 200u, 0.0, 0.0)
        assertEquals("Newer Node", RepeaterResolver.bestMatch(Bytes.of(0x3F), listOf(older, newer), null)?.name)
    }

    @Test @OriginalCase("RepeaterResolverTests::exact match with full public key for discovered node PathHop variant()")
    fun `exact match with full public key for discovered node PathHop variant`() {
        val target = node(0x3F, 0x01, "Target Node", 10u, 38.0, -123.0)
        val closer = node(0x3F, 0x02, "Closer and Newer", 200u, 37.0, -122.0)
        val hop = TracePathHop(Bytes.of(0x3F), target.publicKey, "Target Node")
        assertEquals("Target Node", RepeaterResolver.bestMatch(hop, listOf(target, closer), user)?.name)
    }

    @Test @OriginalCase("RepeaterResolverTests::prefers most recent when location unavailable()")
    fun `prefers most recent when location unavailable`() {
        val older = repeater(0x3F, 0x01, "Older", 10u, 0.0, 0.0)
        val newer = repeater(0x3F, 0x02, "Newer", 200u, 0.0, 0.0)
        assertEquals("Newer", RepeaterResolver.bestMatch(Bytes.of(0x3F), listOf(older, newer), null)?.displayName)
    }

    private fun ridge() = discovered(key(0xAB, 0xCD, 0xEF), "Ridge Repeater", 100u)

    @Test @OriginalCase("RepeaterResolverTests::neighbor name resolver uses discovered node when contact is absent()")
    fun `neighbor name resolver uses discovered node when contact is absent`() =
        assertEquals("Ridge Repeater", NeighborNameResolver.resolveName(Bytes.of(0xAB, 0xCD), emptyList(), listOf(ridge()), null))

    @Test @OriginalCase("RepeaterResolverTests::neighbor name resolver marks unique short discovered prefix as exact()")
    fun `neighbor name resolver marks unique short discovered prefix as exact`() = assertEquals(
        NodeNameResolution("Ridge Repeater", NodeNameMatchKind.EXACT),
        NeighborNameResolver.resolve(Bytes.of(0xAB, 0xCD), emptyList(), listOf(ridge()), null),
    )

    @Test @OriginalCase("RepeaterResolverTests::neighbor name resolver marks full prefix contact match as exact()")
    fun `neighbor name resolver marks full prefix contact match as exact`() {
        val saved = repeater(0xAB, 0xCD, "Saved Repeater", 10u, 0.0, 0.0)
        assertEquals(
            NodeNameResolution("Saved Repeater", NodeNameMatchKind.EXACT),
            NeighborNameResolver.resolve(saved.publicKeyPrefix, listOf(saved), emptyList(), null),
        )
    }

    @Test @OriginalCase("RepeaterResolverTests::neighbor name resolver prefers contacts over discovered nodes()")
    fun `neighbor name resolver prefers contacts over discovered nodes`() {
        val saved = repeater(0xAB, 0xCD, "Saved Repeater", 10u, 0.0, 0.0)
        val advert = discovered(saved.publicKey, "Advert Repeater", 200u)
        assertEquals("Saved Repeater", NeighborNameResolver.resolveName(Bytes.of(0xAB, 0xCD), listOf(saved), listOf(advert), null))
    }

    @Test @OriginalCase("RepeaterResolverTests::neighbor name resolver marks cross-source short prefix ambiguity as fallback()")
    fun `neighbor name resolver marks cross-source short prefix ambiguity as fallback`() {
        val saved = repeater(0xAB, 0xCD, "Saved Repeater", 10u, 0.0, 0.0)
        val advert = discovered(key(0xAB, 0xEF), "Advert Repeater", 200u)
        assertEquals(
            NodeNameResolution("Saved Repeater", NodeNameMatchKind.FALLBACK),
            NeighborNameResolver.resolve(Bytes.of(0xAB), listOf(saved), listOf(advert), null),
        )
    }

    private fun olderNewer() = listOf(
        discovered(key(0xAB, 0xCD, 0x01), "Older Repeater", 100u, Instant.ofEpochSecond(1000)),
        discovered(key(0xAB, 0xCD, 0x02), "Newer Repeater", 200u, Instant.ofEpochSecond(2000)),
    )

    @Test @OriginalCase("RepeaterResolverTests::neighbor name resolver disambiguates short discovered prefixes by recency()")
    fun `neighbor name resolver disambiguates short discovered prefixes by recency`() =
        assertEquals("Newer Repeater", NeighborNameResolver.resolveName(Bytes.of(0xAB, 0xCD), emptyList(), olderNewer(), null))

    @Test @OriginalCase("RepeaterResolverTests::neighbor name resolver marks ambiguous short discovered prefix as fallback()")
    fun `neighbor name resolver marks ambiguous short discovered prefix as fallback`() = assertEquals(
        NodeNameResolution("Newer Repeater", NodeNameMatchKind.FALLBACK),
        NeighborNameResolver.resolve(Bytes.of(0xAB, 0xCD), emptyList(), olderNewer(), null),
    )

    @Test
    @OriginalCase("RepeaterResolverTests::key display byte count floors at 2 and caps at 3(deviceHashSize : Int ? , expected : Int)")
    fun `key display byte count floors at 2 and caps at 3`() {
        // The five source argument rows: (nil, 2), (1, 2), (2, 2), (3, 3), (99, 3).
        val rows = listOf<Pair<Long?, Int>>(null to 2, 1L to 2, 2L to 2, 3L to 3, 99L to 3)
        for ((hashSize, expected) in rows) {
            assertEquals(expected, NeighborNameResolver.keyDisplayByteCount(hashSize), "deviceHashSize=$hashSize")
        }
    }

    @Test @OriginalCase("RepeaterResolverTests::fallback name with byte count formats two or three prefix bytes()")
    fun `fallback name with byte count formats two or three prefix bytes`() {
        val prefix = Bytes.of(0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01)
        assertEquals("DEAD", NeighborNameResolver.fallbackName(prefix, 2))
        assertEquals("DEADBE", NeighborNameResolver.fallbackName(prefix, 3))
    }

    @Test @OriginalCase("RepeaterResolverTests::fallback name with byte count clamps display max and short prefixes()")
    fun `fallback name with byte count clamps display max and short prefixes`() {
        val prefix = Bytes.of(0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01)
        assertEquals("DEADBE", NeighborNameResolver.fallbackName(prefix, 6))
        assertEquals("DEADBE", NeighborNameResolver.fallbackName(prefix, 99))
        assertEquals("AB", NeighborNameResolver.fallbackName(Bytes.of(0xAB), 3))
        assertEquals("", NeighborNameResolver.fallbackName(Bytes.EMPTY, 2))
    }

    @Test
    fun `resolve marks a short colliding prefix as fallback and a six-byte prefix as exact`() {
        val a = contact(0x3F, 0x01, 0, 0, 0, 0, name = "A", lastAdvertTimestamp = 1u)
        val b = contact(0x3F, 0x01, 0, 0, 0, 0, 0x01, name = "B", lastAdvertTimestamp = 2u)
        assertEquals(NodeNameMatchKind.FALLBACK, RepeaterResolver.resolve(Bytes.of(0x3F, 0x01), listOf(a, b), null)?.matchKind)
        assertEquals(NodeNameMatchKind.EXACT, RepeaterResolver.resolve(Bytes.of(0x3F, 0x01, 0, 0, 0, 0), listOf(a, b), null)?.matchKind)
        assertEquals(NodeNameMatchKind.EXACT, RepeaterResolver.resolve(Bytes.of(0x3F, 0x01), listOf(a), null)?.matchKind)
    }
}
