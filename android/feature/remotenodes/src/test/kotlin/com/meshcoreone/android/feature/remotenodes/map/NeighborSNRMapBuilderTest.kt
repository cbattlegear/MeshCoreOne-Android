// PortedFrom: MC1Tests/Views/RemoteNodes/NeighborSNRMapBuilderTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.map

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.event.Neighbour
import com.meshcoreone.android.feature.remotenodes.resolver.NeighborNameResolver
import com.meshcoreone.android.feature.remotenodes.resolver.NodeNameMatchKind
import com.meshcoreone.android.feature.remotenodes.support.OriginalCase
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class NeighborSNRMapBuilderTest {
    private fun build(
        session: RemoteNodeSessionDTO,
        neighbors: List<Neighbour>,
        contacts: List<ContactDTO>,
        discoveredNodes: List<DiscoveredNodeDTO>,
        filter: MapFilterState,
        keyDisplayByteCount: Int = NeighborNameResolver.MINIMUM_KEY_DISPLAY_BYTE_COUNT,
    ): NeighborSNRMapBuilder.PlottedNeighbors = NeighborSNRMapBuilder.build(
        session = session,
        neighbors = neighbors,
        contacts = contacts,
        discoveredNodes = discoveredNodes,
        userLocation = null,
        filter = filter,
        keyDisplayByteCount = keyDisplayByteCount,
        locale = Locale.US,
    )

    private fun coordinatesMatch(lhs: Coordinate, rhs: Coordinate): Boolean =
        abs(lhs.latitude - rhs.latitude) < 1e-9 && abs(lhs.longitude - rhs.longitude) < 1e-9

    private fun List<MapPoint>.countStyle(style: PinStyle): Int = count { it.pinStyle == style }

    // Plotting

    @Test
    @OriginalCase("NeighborSNRMapBuilderTests::center located plus exact located neighbor draws pins, an SNR line, and a badge()")
    fun centerLocatedPlusExactLocatedNeighborDrawsPinsLineAndBadge() {
        val centerCoordinate = Coordinate(37.0, -122.0)
        val neighborCoordinate = Coordinate(37.1, -122.1)
        val contact = contact(EXACT_PREFIX, "Ridge", neighborCoordinate.latitude, neighborCoordinate.longitude)
        val neighbor = neighbor(EXACT_PREFIX)

        val result = build(
            centerSession(centerCoordinate.latitude, centerCoordinate.longitude),
            listOf(neighbor), listOf(contact), emptyList(), MapFilterState(),
        )

        assertEquals(1, result.points.countStyle(PinStyle.REPEATER_RING_WHITE))
        assertEquals(1, result.points.countStyle(PinStyle.REPEATER))
        assertEquals(1, result.points.countStyle(PinStyle.BADGE))
        assertTrue(result.unplottable.isEmpty())

        val line = assertNotNull(result.lines.firstOrNull())
        assertEquals(1, result.lines.size)
        assertEquals(MapLineStyle.forSNR(neighbor.snr), line.style)
        assertEquals(MapLineStyle.TRACE_MEDIUM, line.style)
        assertEquals(2, line.coordinates.size)
        assertTrue(coordinatesMatch(line.coordinates[0], centerCoordinate))
        assertTrue(coordinatesMatch(line.coordinates[1], neighborCoordinate))

        val region = assertNotNull(result.region)
        assertTrue(region.brackets(centerCoordinate))
        assertTrue(region.brackets(neighborCoordinate))
    }

    @Test
    @OriginalCase("NeighborSNRMapBuilderTests::center plus two located neighbors draws a ring, two repeater pins, two badges, and two lines()")
    fun centerPlusTwoLocatedNeighborsDrawsRingTwoPinsTwoBadgesTwoLines() {
        val centerCoordinate = Coordinate(37.0, -122.0)
        val firstCoordinate = Coordinate(37.1, -122.1)
        val secondCoordinate = Coordinate(37.2, -121.9)
        val contacts = listOf(
            contact(EXACT_PREFIX, "Ridge", firstCoordinate.latitude, firstCoordinate.longitude),
            contact(SECOND_EXACT_PREFIX, "Valley", secondCoordinate.latitude, secondCoordinate.longitude),
        )

        val result = build(
            centerSession(centerCoordinate.latitude, centerCoordinate.longitude),
            listOf(neighbor(EXACT_PREFIX), neighbor(SECOND_EXACT_PREFIX)), contacts, emptyList(), MapFilterState(),
        )

        assertEquals(1, result.points.countStyle(PinStyle.REPEATER_RING_WHITE))
        assertEquals(2, result.points.countStyle(PinStyle.REPEATER))
        assertEquals(2, result.points.countStyle(PinStyle.BADGE))
        assertEquals(2, result.lines.size)
        assertTrue(result.unplottable.isEmpty())

        val region = assertNotNull(result.region)
        assertTrue(region.brackets(centerCoordinate))
        assertTrue(region.brackets(firstCoordinate))
        assertTrue(region.brackets(secondCoordinate))
    }

    @Test
    @OriginalCase("NeighborSNRMapBuilderTests::center not located draws neighbor pin without line or badge()")
    fun centerNotLocatedDrawsNeighborPinWithoutLineOrBadge() {
        val result = build(
            centerSession(0.0, 0.0),
            listOf(neighbor(EXACT_PREFIX)), listOf(contact(EXACT_PREFIX, "Ridge", 37.1, -122.1)), emptyList(),
            MapFilterState(),
        )

        assertEquals(0, result.points.countStyle(PinStyle.REPEATER_RING_WHITE))
        assertEquals(1, result.points.countStyle(PinStyle.REPEATER))
        assertEquals(0, result.points.countStyle(PinStyle.BADGE))
        assertTrue(result.lines.isEmpty())
        assertTrue(result.unplottable.isEmpty())
    }

    @Test
    @OriginalCase("NeighborSNRMapBuilderTests::ambiguous fallback neighbor is not plotted and is listed as fallback()")
    fun ambiguousFallbackNeighborIsNotPlottedAndIsListedAsFallback() {
        // A sub-6-byte prefix that collides across a contact and a discovered node forces the
        // resolver's refinement gate to return FALLBACK; production 6-byte prefixes never do.
        val contact = contact(listOf(0xAB, 0xCD), "Saved", 37.1, -122.1)
        val node = discoveredNode(listOf(0xAB, 0xEF), "Advert", 38.0, -123.0)

        val result = build(
            centerSession(0.0, 0.0), listOf(neighbor(listOf(0xAB))), listOf(contact), listOf(node),
            MapFilterState(showDiscovered = true),
        )

        assertEquals(0, result.points.countStyle(PinStyle.REPEATER))
        assertTrue(result.lines.isEmpty())
        assertEquals(1, result.unplottable.size)
        assertEquals(NodeNameMatchKind.FALLBACK, result.unplottable.firstOrNull()?.matchKind)
    }

    @Test
    @OriginalCase("NeighborSNRMapBuilderTests::exact neighbor without a location is not plotted()")
    fun exactNeighborWithoutALocationIsNotPlotted() {
        val result = build(
            centerSession(37.0, -122.0), listOf(neighbor(EXACT_PREFIX)),
            listOf(contact(EXACT_PREFIX, "No GPS", 0.0, 0.0)), emptyList(), MapFilterState(),
        )

        assertEquals(0, result.points.countStyle(PinStyle.REPEATER))
        assertEquals(1, result.unplottable.size)
        assertEquals(NodeNameMatchKind.EXACT, result.unplottable.firstOrNull()?.matchKind)
        assertEquals("No GPS", result.unplottable.firstOrNull()?.displayName)
    }

    @Test
    @OriginalCase("NeighborSNRMapBuilderTests::exact neighbor with an out-of-range coordinate is rejected by the builder guard()")
    fun exactNeighborWithAnOutOfRangeCoordinateIsRejectedByTheBuilderGuard() {
        // DiscoveredNodeDTO.hasLocation only checks non-(0,0), so an out-of-range latitude reaches the
        // builder; its own validity guard must reject it.
        val node = discoveredNode(EXACT_PREFIX, "Bad GPS", 200.0, -122.1)

        val result = build(
            centerSession(37.0, -122.0), listOf(neighbor(EXACT_PREFIX)), emptyList(), listOf(node),
            MapFilterState(showDiscovered = true),
        )

        assertEquals(0, result.points.countStyle(PinStyle.REPEATER))
        assertEquals(1, result.unplottable.size)
        assertEquals(NodeNameMatchKind.EXACT, result.unplottable.firstOrNull()?.matchKind)
    }

    @Test
    @OriginalCase("NeighborSNRMapBuilderTests::unresolved neighbor falls back to a hex name and is listed()")
    fun unresolvedNeighborFallsBackToAHexNameAndIsListed() {
        val neighbor = neighbor(listOf(0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01))
        val keyDisplayByteCount = NeighborNameResolver.MINIMUM_KEY_DISPLAY_BYTE_COUNT

        val result = build(
            centerSession(37.0, -122.0), listOf(neighbor), emptyList(), emptyList(), MapFilterState(),
            keyDisplayByteCount,
        )

        assertEquals(0, result.points.countStyle(PinStyle.REPEATER))
        assertEquals(1, result.unplottable.size)
        assertEquals(NodeNameMatchKind.UNRESOLVED, result.unplottable.firstOrNull()?.matchKind)
        assertEquals("DEAD", result.unplottable.firstOrNull()?.displayName)
    }

    @Test
    @OriginalCase("NeighborSNRMapBuilderTests::unresolved title hex length matches secondary formatting for the same count(keyDisplayByteCount : Int)")
    fun unresolvedTitleHexLengthMatchesSecondaryFormattingForTheSameCount() {
        for ((keyDisplayByteCount, expected) in listOf(2 to "DEAD", 3 to "DEADBE")) {
            val neighbor = neighbor(listOf(0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01))

            val result = build(
                centerSession(37.0, -122.0), listOf(neighbor), emptyList(), emptyList(), MapFilterState(),
                keyDisplayByteCount,
            )

            val unplottable = result.unplottable.firstOrNull()
            assertEquals(NodeNameMatchKind.UNRESOLVED, unplottable?.matchKind, "count $keyDisplayByteCount")
            assertEquals(expected, unplottable?.displayName, "count $keyDisplayByteCount")
            assertEquals(keyDisplayByteCount * 2, unplottable?.displayName?.length, "count $keyDisplayByteCount")
        }
    }

    @Test
    @OriginalCase("NeighborSNRMapBuilderTests::colliding unresolved titles widen to the full prefix()")
    fun collidingUnresolvedTitlesWidenToTheFullPrefix() {
        val neighbors = listOf(
            neighbor(listOf(0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01)),
            neighbor(listOf(0xDE, 0xAD, 0x01, 0x02, 0x03, 0x04)),
            neighbor(listOf(0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF)),
        )

        val result = build(
            centerSession(37.0, -122.0), neighbors, emptyList(), emptyList(), MapFilterState(),
            NeighborNameResolver.MINIMUM_KEY_DISPLAY_BYTE_COUNT,
        )

        assertEquals(listOf("DEADBEEF0001", "DEAD01020304", "AABB"), result.unplottable.map { it.displayName })
    }

    @Test
    @OriginalCase("NeighborSNRMapBuilderTests::all neighbors unplottable keeps only the center pin and lists them all()")
    fun allNeighborsUnplottableKeepsOnlyTheCenterPinAndListsThemAll() {
        val neighbors = listOf(
            neighbor(listOf(0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01)),
            neighbor(listOf(0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x02)),
        )

        val result = build(centerSession(37.0, -122.0), neighbors, emptyList(), emptyList(), MapFilterState())

        assertEquals(1, result.points.size)
        assertEquals(PinStyle.REPEATER_RING_WHITE, result.points.firstOrNull()?.pinStyle)
        assertTrue(result.lines.isEmpty())
        assertNotNull(result.region)
        assertEquals(2, result.unplottable.size)
    }

    @Test
    @OriginalCase("NeighborSNRMapBuilderTests::nothing plotted yields a nil region()")
    fun nothingPlottedYieldsANilRegion() {
        val result = build(
            centerSession(0.0, 0.0), listOf(neighbor(listOf(0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01))),
            emptyList(), emptyList(), MapFilterState(),
        )

        assertTrue(result.points.isEmpty())
        assertNull(result.region)
        assertEquals(1, result.unplottable.size)
    }

    // Filter

    @Test
    @OriginalCase("NeighborSNRMapBuilderTests::favorites only keeps session pin and drops non-favorite neighbor()")
    fun favoritesOnlyKeepsSessionPinAndDropsNonFavoriteNeighbor() {
        val favorite = contact(EXACT_PREFIX, "Fav", 37.1, -122.1, isFavorite = true)
        val other = contact(SECOND_EXACT_PREFIX, "Other", 37.2, -121.9, isFavorite = false)

        val result = build(
            centerSession(37.0, -122.0), listOf(neighbor(EXACT_PREFIX), neighbor(SECOND_EXACT_PREFIX)),
            listOf(favorite, other), emptyList(), MapFilterState(favoritesOnly = true),
        )

        assertEquals(1, result.points.countStyle(PinStyle.REPEATER_RING_WHITE))
        assertEquals(1, result.points.countStyle(PinStyle.REPEATER))
        assertTrue(result.points.any { it.pinStyle == PinStyle.REPEATER && it.label == "Fav" })
        assertEquals(1, result.unplottable.size)
        // Non-favorite is excluded from the contact pool, so resolution falls back to hex.
        assertTrue(
            result.unplottable.any {
                it.matchKind == NodeNameMatchKind.UNRESOLVED && it.neighbor.publicKeyPrefix == prefixBytes(SECOND_EXACT_PREFIX)
            },
        )
    }

    @Test
    @OriginalCase("NeighborSNRMapBuilderTests::discovered off drops discovered-only exact neighbor into unplottable()")
    fun discoveredOffDropsDiscoveredOnlyExactNeighborIntoUnplottable() {
        val session = centerSession(37.0, -122.0)
        val discovered = discoveredNode(EXACT_PREFIX, "Heard", 37.1, -122.1)
        val neighbor = neighbor(EXACT_PREFIX)

        val withDiscovered = build(
            session, listOf(neighbor), emptyList(), listOf(discovered), MapFilterState(showDiscovered = true),
        )
        assertEquals(1, withDiscovered.points.countStyle(PinStyle.REPEATER))

        val withoutDiscovered = build(
            session, listOf(neighbor), emptyList(), listOf(discovered), MapFilterState(showDiscovered = false),
        )
        assertEquals(0, withoutDiscovered.points.countStyle(PinStyle.REPEATER))
        assertEquals(1, withoutDiscovered.points.countStyle(PinStyle.REPEATER_RING_WHITE))
        assertEquals(1, withoutDiscovered.unplottable.size)
    }

    @Test
    @OriginalCase("NeighborSNRMapBuilderTests::same inputs twice produce equal points under MapPoint equality()")
    fun sameInputsTwiceProduceEqualPointsUnderMapPointEquality() {
        val session = centerSession(37.0, -122.0)
        val contact = contact(EXACT_PREFIX, "Ridge", 37.1, -122.1)
        val neighbor = neighbor(EXACT_PREFIX)

        val first = build(session, listOf(neighbor), listOf(contact), emptyList(), MapFilterState())
        val second = build(session, listOf(neighbor), listOf(contact), emptyList(), MapFilterState())

        assertEquals(first.points, second.points)
        assertEquals(first.lines.map { it.id }, second.lines.map { it.id })
    }

    @Test
    @OriginalCase("NeighborSNRMapBuilderTests::favorites with discovered true still drops discovered-only neighbor()")
    fun favoritesWithDiscoveredTrueStillDropsDiscoveredOnlyNeighbor() {
        val discovered = discoveredNode(EXACT_PREFIX, "Heard", 37.1, -122.1)

        val result = build(
            centerSession(37.0, -122.0), listOf(neighbor(EXACT_PREFIX)), emptyList(), listOf(discovered),
            MapFilterState(favoritesOnly = true, showDiscovered = true),
        )

        assertEquals(1, result.points.countStyle(PinStyle.REPEATER_RING_WHITE))
        assertEquals(0, result.points.countStyle(PinStyle.REPEATER))
        assertEquals(1, result.unplottable.size)
    }

    @Test
    @OriginalCase("NeighborSNRMapBuilderTests::stableID namespaces separate roles for same key()")
    fun stableIdNamespacesSeparateRolesForSameKey() {
        val key = prefixBytes(EXACT_PREFIX)
        val center = NeighborSNRMapBuilder.stableId(NeighborSNRMapBuilder.PinRole.CENTER, key)
        val neighbor = NeighborSNRMapBuilder.stableId(NeighborSNRMapBuilder.PinRole.NEIGHBOR, key)
        val badge = NeighborSNRMapBuilder.stableId(NeighborSNRMapBuilder.PinRole.BADGE, key)
        assertEquals(center, NeighborSNRMapBuilder.stableId(NeighborSNRMapBuilder.PinRole.CENTER, key))
        assertNotEquals(center, neighbor)
        assertNotEquals(neighbor, badge)
        assertNotEquals(center, badge)
    }

    // SNR bucketing

    /**
     * The 0.0 and -6.0 cases sit on SNRQuality's strict-greater thresholds, so they catch a `>` to
     * `>=` regression that the interior values would not.
     */
    @Test
    @OriginalCase("NeighborSNRMapBuilderTests::SNR buckets map to the expected trace line style(snr : Double ? , style : MapLine . LineStyle)")
    fun snrBucketsMapToTheExpectedTraceLineStyle() {
        val cases = listOf<Pair<Double?, MapLineStyle>>(
            7.0 to MapLineStyle.TRACE_GOOD,
            3.0 to MapLineStyle.TRACE_GOOD,
            0.0 to MapLineStyle.TRACE_MEDIUM,
            -3.0 to MapLineStyle.TRACE_MEDIUM,
            -6.0 to MapLineStyle.TRACE_WEAK,
            -10.0 to MapLineStyle.TRACE_WEAK,
            null to MapLineStyle.TRACE_UNTRACED,
        )
        for ((snr, style) in cases) {
            assertEquals(style, MapLineStyle.forSNR(snr), "snr $snr")
        }
    }

    @Test
    @OriginalCase("NeighborSNRMapBuilderTests::SNR badge midpoint stays between coordinates that straddle the antimeridian()")
    fun snrBadgeMidpointStaysBetweenCoordinatesThatStraddleTheAntimeridian() {
        val west = Coordinate(0.0, 179.0)
        val east = Coordinate(0.0, -179.0)

        val badge = SnrBadges.snrBadge(UUID.randomUUID(), west, east, -3.0)

        // The midpoint must land on the date line near ±180, not on the opposite hemisphere near 0.
        assertTrue(abs(abs(badge.coordinate.longitude) - 180) < 0.0001)
    }
}
