// PortedFrom: MC1Tests/Utilities/RepeaterResolverTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.resolver

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.NeighborSnapshotEntry
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.Neighbour
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.status.NeighborResolutionContext
import com.meshcoreone.android.feature.remotenodes.status.NeighborRows
import com.meshcoreone.android.feature.remotenodes.status.contact
import com.meshcoreone.android.feature.remotenodes.support.OriginalCase
import com.meshcoreone.android.feature.remotenodes.support.TEST_RADIO
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class NeighborNameResolverParityTest {
    private val shortPrefix = Bytes.of(0xAB, 0xCD)

    private fun key(vararg prefix: Int) = Bytes.of(*prefix) + Bytes(ByteArray(32 - prefix.size))

    private fun saved() = contact(publicKey = key(0xAB, 0xCD), name = "Saved Repeater", outPathLength = 0u)
        .copy(lastAdvertTimestamp = 10u)

    private fun discovered(
        publicKey: Bytes = key(0xAB, 0xCD, 0xEF),
        name: String = "Ridge Repeater",
        advert: UInt = 100u,
        heard: Long = 1000,
    ) = DiscoveredNodeDTO(
        id = UUID(0, publicKey[2].toLong()),
        radioId = TEST_RADIO,
        publicKey = publicKey,
        name = name,
        typeRawValue = 2u,
        lastHeard = Instant.ofEpochSecond(heard),
        lastAdvertTimestamp = advert,
        latitude = 0.0,
        longitude = 0.0,
        outPathLength = 0u,
        outPath = Bytes.EMPTY,
        inboundHopCount = null,
        inboundHopAdvertTimestamp = null,
    )

    private fun olderNewer() = listOf(
        discovered(key(0xAB, 0xCD, 0x01), "Older Repeater", 100u, 1000),
        discovered(key(0xAB, 0xCD, 0x02), "Newer Repeater", 200u, 2000),
    )

    private fun assertResolutionAndRows(
        prefix: Bytes,
        contacts: List<ContactDTO>,
        discovered: List<DiscoveredNodeDTO>,
        expectedName: String,
        expectedKind: NodeNameMatchKind,
    ) {
        assertEquals(
            NodeNameResolution(expectedName, expectedKind),
            NeighborNameResolver.resolve(prefix, contacts, discovered, null, Locale.US),
        )
        assertEquals(expectedName, NeighborNameResolver.resolveName(prefix, contacts, discovered, null, Locale.US))
        val located = NeighborNameResolver.resolveLocated(prefix, contacts, discovered, null, Locale.US)
        assertEquals(ResolvedNeighbor(expectedName, expectedKind, null, null), located)
        val context = NeighborResolutionContext(contacts, discovered, null, Locale.US)
        val current = NeighborRows.rows(listOf(Neighbour(prefix, 30, 5.5)), null, emptySet(), 2, context).single()
        assertEquals(RemoteNodesText.Verbatim(expectedName), current.displayName)
        assertEquals(expectedKind, current.matchKind)
        val previous = NodeStatusSnapshotDTO(
            id = UUID(0, 1),
            timestamp = Instant.ofEpochSecond(1000),
            nodePublicKey = key(0x10),
            neighborSnapshots = listOf(NeighborSnapshotEntry(prefix, -2.375, 60)).snapshot(),
        )
        val disappeared = NeighborRows.disappeared(emptyList(), previous, 2, context).single()
        assertEquals(RemoteNodesText.Verbatim(expectedName), disappeared.displayName)
        assertEquals(expectedKind, disappeared.matchKind)
    }

    @Test
    @OriginalCase("RepeaterResolverTests::neighbor name resolver uses discovered node when contact is absent()")
    fun discoveredNameReachesLiveRowsWithoutContact() {
        assertResolutionAndRows(shortPrefix, emptyList(), listOf(discovered()), "Ridge Repeater", NodeNameMatchKind.EXACT)
    }

    @Test
    @OriginalCase("RepeaterResolverTests::neighbor name resolver marks unique short discovered prefix as exact()")
    fun uniqueShortDiscoveredPrefixIsExactInLiveRows() {
        assertResolutionAndRows(shortPrefix, emptyList(), listOf(discovered()), "Ridge Repeater", NodeNameMatchKind.EXACT)
    }

    @Test
    @OriginalCase("RepeaterResolverTests::neighbor name resolver marks full prefix contact match as exact()")
    fun fullContactPrefixIsExactInLiveRows() {
        assertResolutionAndRows(
            Bytes.of(0xAB, 0xCD, 0, 0, 0, 0), listOf(saved()), emptyList(),
            "Saved Repeater", NodeNameMatchKind.EXACT,
        )
    }

    @Test
    @OriginalCase("RepeaterResolverTests::neighbor name resolver prefers contacts over discovered nodes()")
    fun savedNameWinsOverNewerAdvertForSameKey() {
        val advert = discovered(key(0xAB, 0xCD), "Advert Repeater", 200u)
        assertResolutionAndRows(shortPrefix, listOf(saved()), listOf(advert), "Saved Repeater", NodeNameMatchKind.EXACT)
    }

    @Test
    @OriginalCase("RepeaterResolverTests::neighbor name resolver marks cross-source short prefix ambiguity as fallback()")
    fun differentKeysAcrossSourcesAreFallbackWithoutReplacingSavedName() {
        val advert = discovered(key(0xAB, 0xEF), "Advert Repeater", 200u)
        assertResolutionAndRows(Bytes.of(0xAB), listOf(saved()), listOf(advert), "Saved Repeater", NodeNameMatchKind.FALLBACK)
    }

    @Test
    @OriginalCase("RepeaterResolverTests::neighbor name resolver disambiguates short discovered prefixes by recency()")
    fun newerDiscoveredNameWinsRegardlessOfInputOrder() {
        listOf(olderNewer(), olderNewer().reversed()).forEach { nodes ->
            assertResolutionAndRows(shortPrefix, emptyList(), nodes, "Newer Repeater", NodeNameMatchKind.FALLBACK)
        }
    }

    @Test
    @OriginalCase("RepeaterResolverTests::neighbor name resolver marks ambiguous short discovered prefix as fallback()")
    fun collidingDiscoveredKeysStayFallbackInLiveRows() {
        assertResolutionAndRows(shortPrefix, emptyList(), olderNewer(), "Newer Repeater", NodeNameMatchKind.FALLBACK)
    }

    @Test
    @OriginalCase("RepeaterResolverTests::key display byte count floors at 2 and caps at 3(deviceHashSize : Int ? , expected : Int)")
    fun originalFiveDeviceHashSizesReachLiveKeyText() {
        val prefix = Bytes.of(0xDE, 0xAD, 0xBE, 0xEF, 0, 1)
        listOf(Triple(null, 2, "DEAD"), Triple(1, 2, "DEAD"), Triple(2, 2, "DEAD"),
            Triple(3, 3, "DEADBE"), Triple(99, 3, "DEADBE")).forEach { (size, width, text) ->
            val actualWidth = NeighborNameResolver.keyDisplayByteCount(size)
            assertEquals(width, actualWidth, "deviceHashSize=$size")
            assertFallbackAndRows(prefix, actualWidth, text)
        }
    }

    @Test
    @OriginalCase("RepeaterResolverTests::fallback name with byte count formats two or three prefix bytes()")
    fun originalTwoAndThreeByteFallbackNamesReachLiveRows() {
        val prefix = Bytes.of(0xDE, 0xAD, 0xBE, 0xEF, 0, 1)
        assertFallbackAndRows(prefix, 2, "DEAD")
        assertFallbackAndRows(prefix, 3, "DEADBE")
    }

    @Test
    @OriginalCase("RepeaterResolverTests::fallback name with byte count clamps display max and short prefixes()")
    fun originalOversizedShortAndEmptyFallbackInputsReachLiveRows() {
        val prefix = Bytes.of(0xDE, 0xAD, 0xBE, 0xEF, 0, 1)
        assertFallbackAndRows(prefix, 6, "DEADBE")
        assertFallbackAndRows(prefix, 99, "DEADBE")
        assertFallbackAndRows(Bytes.of(0xAB), 3, "AB")
        assertFallbackAndRows(Bytes.EMPTY, 2, "")
    }

    private fun assertFallbackAndRows(prefix: Bytes, width: Int, expectedHex: String) {
        assertEquals(expectedHex, NeighborNameResolver.fallbackName(prefix, width))
        assertNull(NeighborNameResolver.resolve(prefix, emptyList(), emptyList(), null, Locale.US))
        val context = NeighborResolutionContext(emptyList(), emptyList(), null, Locale.US)
        val current = NeighborRows.rows(listOf(Neighbour(prefix, 30, 5.5)), null, emptySet(), width, context).single()
        assertEquals(expectedHex, current.keyHex)
        assertEquals(RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesStatusUnknown), current.displayName)
        assertEquals(NodeNameMatchKind.UNRESOLVED, current.matchKind)
        val previous = NodeStatusSnapshotDTO(
            id = UUID(0, 1),
            timestamp = Instant.ofEpochSecond(1000),
            nodePublicKey = key(0x10),
            neighborSnapshots = listOf(NeighborSnapshotEntry(prefix, -2.375, 60)).snapshot(),
        )
        val disappeared = NeighborRows.disappeared(emptyList(), previous, width, context).single()
        assertEquals(RemoteNodesText.Verbatim(expectedHex), disappeared.displayName)
        assertEquals(NodeNameMatchKind.UNRESOLVED, disappeared.matchKind)
    }
}
