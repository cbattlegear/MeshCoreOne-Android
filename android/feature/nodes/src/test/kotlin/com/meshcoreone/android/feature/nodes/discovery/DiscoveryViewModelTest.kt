// PortedFrom: MC1Tests/ViewModels/DiscoveryViewModelTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.nodes.discovery

import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.model.DiscoverSegment
import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import com.meshcoreone.android.feature.nodes.support.Fixtures
import com.meshcoreone.android.feature.nodes.support.Fixtures.node
import com.meshcoreone.android.feature.nodes.support.Harness
import com.meshcoreone.android.feature.nodes.support.OriginalCase
import com.meshcoreone.android.feature.nodes.support.scenario
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.Test

class DiscoveryViewModelTest {
    private fun DiscoveryStateHolder.filtered(
        searchText: String = "",
        segment: DiscoverSegment = DiscoverSegment.ALL,
        sortOrder: NodeSortOrder = NodeSortOrder.NAME,
        userLocation: Coordinate? = null,
    ) = filteredNodes(searchText, segment, sortOrder, userLocation)

    @Test @OriginalCase("DiscoveryViewModelTests::filteredNodes matches name case-insensitively via localizedStandardContains()")
    fun `filteredNodes matches name case-insensitively via localizedStandardContains`() = scenario {
        val holder = DiscoveryStateHolder(Harness(clock).dependencies, scope)
        holder.seed { it.copy(discoveredNodes = listOf(node(name = "Alpha Repeater"), node(name = "Beta Chat"))) }
        assertEquals(listOf("Alpha Repeater"), holder.filtered(searchText = "alpha").map { it.name })
    }

    @Test @OriginalCase("DiscoveryViewModelTests::filteredNodes matches hex prefix case-insensitively()")
    fun `filteredNodes matches hex prefix case-insensitively`() = scenario {
        val key = Bytes(ByteArray(32).also { it[0] = 0x00; it[1] = 0xAA.toByte() })
        val holder = DiscoveryStateHolder(Harness(clock).dependencies, scope)
        holder.seed { it.copy(discoveredNodes = listOf(node(publicKey = key, name = "HexNode"), node(publicKey = Fixtures.repeated(0xFF), name = "Other"))) }
        assertEquals(listOf("HexNode"), holder.filtered(searchText = "00AA").map { it.name })
        assertEquals(listOf("HexNode"), holder.filtered(searchText = "00aa").map { it.name })
        assertEquals(listOf("HexNode"), holder.filtered(searchText = "00Aa").map { it.name })
    }

    @Test @OriginalCase("DiscoveryViewModelTests::filteredNodes ignores segment while searching()")
    fun `filteredNodes ignores segment while searching`() = scenario {
        val holder = DiscoveryStateHolder(Harness(clock).dependencies, scope)
        holder.seed { it.copy(discoveredNodes = listOf(node(name = "ChatOne"), node(name = "RepeaterOne", type = ContactType.REPEATER))) }
        val result = holder.filtered(searchText = "One", segment = DiscoverSegment.CONTACTS)
        assertEquals(setOf("ChatOne", "RepeaterOne"), result.map { it.name }.toSet())
    }

    @Test @OriginalCase("DiscoveryViewModelTests::filteredNodes applies segment filter when not searching()")
    fun `filteredNodes applies segment filter when not searching`() = scenario {
        val holder = DiscoveryStateHolder(Harness(clock).dependencies, scope)
        holder.seed {
            it.copy(discoveredNodes = listOf(node(name = "Chat"), node(name = "Repeater", type = ContactType.REPEATER), node(name = "Room", type = ContactType.ROOM)))
        }
        assertEquals(listOf("Chat"), holder.filtered(segment = DiscoverSegment.CONTACTS).map { it.name })
        assertEquals(listOf("Repeater"), holder.filtered(segment = DiscoverSegment.REPEATERS).map { it.name })
        assertEquals(listOf("Room"), holder.filtered(segment = DiscoverSegment.ROOMS).map { it.name })
    }

    @Test @OriginalCase("DiscoveryViewModelTests::lastHeard sort uses receiver lastHeard not lastAdvertTimestamp()")
    fun `lastHeard sort uses receiver lastHeard not lastAdvertTimestamp`() = scenario {
        val holder = DiscoveryStateHolder(Harness(clock).dependencies, scope)
        // A future sender clock on the stale-heard node must not pin it to the top.
        val futureSender = node(name = "FutureSender", lastHeard = Instant.ofEpochSecond(1_000_000), lastAdvertTimestamp = 4_000_000_000u)
        val recentlyHeard = node(name = "RecentlyHeard", lastHeard = Instant.ofEpochSecond(2_000_000), lastAdvertTimestamp = 1_000_000u)
        holder.seed { it.copy(discoveredNodes = listOf(futureSender, recentlyHeard)) }
        assertEquals(listOf("RecentlyHeard", "FutureSender"), holder.filtered(sortOrder = NodeSortOrder.LAST_HEARD).map { it.name })
    }

    @Test @OriginalCase("DiscoveryViewModelTests::name sort is locale ascending()")
    fun `name sort is locale ascending`() = scenario {
        val holder = DiscoveryStateHolder(Harness(clock).dependencies, scope)
        holder.seed { it.copy(discoveredNodes = listOf(node(name = "Charlie"), node(name = "Alice"), node(name = "Bob"))) }
        assertEquals(listOf("Alice", "Bob", "Charlie"), holder.filtered().map { it.name })
    }

    @Test @OriginalCase("DiscoveryViewModelTests::distance sort places nearer located nodes first()")
    fun `distance sort places nearer located nodes first`() = scenario {
        val holder = DiscoveryStateHolder(Harness(clock).dependencies, scope)
        holder.seed {
            it.copy(
                discoveredNodes = listOf(
                    node(name = "Far", latitude = 38.0, longitude = -122.0),
                    node(name = "Near", latitude = 37.01, longitude = -122.0),
                    node(name = "Unlocated"),
                ),
            )
        }
        val result = holder.filtered(sortOrder = NodeSortOrder.DISTANCE, userLocation = Coordinate(37.0, -122.0))
        assertEquals(listOf("Near", "Far", "Unlocated"), result.map { it.name })
    }

    @Test @OriginalCase("DiscoveryViewModelTests::hops sort orders by hop count then distance()")
    fun `hops sort orders by hop count then distance`() = scenario {
        val holder = DiscoveryStateHolder(Harness(clock).dependencies, scope)
        holder.seed {
            it.copy(
                discoveredNodes = listOf(
                    node(name = "ThreeHop", latitude = 37.01, longitude = -122.0, outPathLength = 3u, outPath = Bytes.of(1, 2, 3)),
                    node(name = "OneHopNear", latitude = 37.01, longitude = -122.0, outPathLength = 1u, outPath = Bytes.of(1)),
                    node(name = "OneHopFar", latitude = 38.0, longitude = -122.0, outPathLength = 1u, outPath = Bytes.of(1)),
                ),
            )
        }
        val result = holder.filtered(sortOrder = NodeSortOrder.HOPS, userLocation = Coordinate(37.0, -122.0))
        assertEquals(listOf("OneHopNear", "OneHopFar", "ThreeHop"), result.map { it.name })
    }

    @Test @OriginalCase("DiscoveryViewModelTests::updateVisibleNodes stores result in visibleNodes()")
    fun `updateVisibleNodes stores result in visibleNodes`() = scenario {
        val holder = DiscoveryStateHolder(Harness(clock).dependencies, scope)
        holder.seed { it.copy(discoveredNodes = listOf(node(name = "Alpha"), node(name = "Beta"))) }
        holder.updateVisibleNodes("alp", DiscoverSegment.ALL, NodeSortOrder.NAME, null)
        assertEquals(listOf("Alpha"), holder.state.value.visibleNodes.map { it.name })
        holder.updateVisibleNodes("bet", DiscoverSegment.ALL, NodeSortOrder.NAME, null)
        assertEquals(listOf("Beta"), holder.state.value.visibleNodes.map { it.name })
    }

    @Test @OriginalCase("DiscoveryViewModelTests::deleteDiscoveredNode reapplies visibleNodes without re-calling updateVisibleNodes()", "native-equivalent")
    fun `deleteDiscoveredNode reapplies visibleNodes without re-calling updateVisibleNodes`() = scenario {
        // In-memory store double replaces the SwiftData PersistenceStore container.
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        harness.store.discovered += node(radioId = radio, publicKey = Fixtures.repeated(0x11), name = "Keep")
        val dropNode = node(radioId = radio, publicKey = Fixtures.repeated(0x22), name = "Drop")
        harness.store.discovered += dropNode
        val holder = DiscoveryStateHolder(harness.dependencies, scope)
        holder.loadDiscoveredNodes()
        holder.updateVisibleNodes("", DiscoverSegment.ALL, NodeSortOrder.NAME, null)
        assertEquals(2, holder.state.value.visibleNodes.size)

        // deleteDiscoveredNode re-applies the filter itself; no second updateVisibleNodes.
        holder.deleteDiscoveredNode(dropNode)
        assertEquals(listOf("Keep"), holder.state.value.discoveredNodes.map { it.name })
        assertEquals(listOf("Keep"), holder.state.value.visibleNodes.map { it.name })
    }
}
