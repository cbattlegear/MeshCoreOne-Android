// AndroidOnly: WP-316 Native RX log feed/reconnect/bounded-history/filter cases and row presentation text.
package com.meshcoreone.android.feature.tools.diagnostics.rxlog

import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.feature.tools.diagnostics.support.TestScheduler
import com.meshcoreone.android.feature.tools.diagnostics.support.XmlDiagnosticsText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Test

class RxLogFeedAndRowTest {
    private class FakeFeed(var existing: List<RxLogEntryDTO> = emptyList()) : RxLogFeed {
        val live = MutableSharedFlow<RxLogEntryDTO>(extraBufferCapacity = 2048)
        val rates = kotlinx.coroutines.flow.MutableStateFlow(0L)
        var cleared = 0
        override suspend fun loadExistingEntries(): List<RxLogEntryDTO> = existing
        override fun entryStream(): Flow<RxLogEntryDTO> = live
        override fun packetRates(): Flow<Long> = rates
        override suspend fun clearEntries() {
            cleared++
        }
    }

    private val scheduler = TestScheduler()
    private val holder = RxLogStateHolder(scheduler.scope)
    private val text = XmlDiagnosticsText()
    private val presentation = RxLogPresentation(text)
    private val state get() = holder.state.value

    private fun entry(
        hash: String,
        route: RouteType = RouteType.FLOOD,
        status: DecryptStatus = DecryptStatus.NOT_APPLICABLE,
    ): RxLogEntryDTO = rxEntry(routeType = route).copy(packetHash = hash, decryptStatus = status)

    private fun connect(feed: FakeFeed?, contacts: List<com.meshcoreone.android.core.model.ContactDTO> = emptyList()) =
        holder.configure(RxLogFeatureDependencies({ feed }, { RxLogContactSource { contacts } }, { RX_RADIO }))

    @Test
    fun `subscribe loads persisted entries then follows the live stream newest first`() {
        val feed = FakeFeed(existing = listOf(entry("b"), entry("a")))
        connect(feed)
        scheduler.run { holder.subscribe() }
        assertEquals(listOf("b", "a"), state.entries.map { it.packetHash })
        feed.live.tryEmit(entry("c"))
        feed.live.tryEmit(entry("a"))
        scheduler.runCurrent()
        assertEquals(listOf("a", "c", "b", "a"), state.entries.map { it.packetHash })
        assertEquals(mapOf("a" to 2, "b" to 1, "c" to 1), state.groupCounts)
    }

    @Test
    fun `resubscribing does not double the stream and unsubscribe stops it`() {
        val feed = FakeFeed()
        connect(feed)
        scheduler.run { holder.subscribe() }
        scheduler.run { holder.subscribe() }
        feed.live.tryEmit(entry("x"))
        scheduler.runCurrent()
        assertEquals(1, state.entries.size)
        holder.unsubscribe()
        scheduler.runCurrent()
        feed.live.tryEmit(entry("y"))
        scheduler.runCurrent()
        assertEquals(1, state.entries.size)
    }

    @Test
    fun `a new service after reconnect resets the log while the same service keeps it`() {
        val first = FakeFeed()
        var current = first
        holder.configure(RxLogFeatureDependencies({ current }, { null }, { null }))
        scheduler.run { holder.subscribe() }
        first.live.tryEmit(entry("old"))
        scheduler.runCurrent()
        val second = FakeFeed(existing = emptyList())
        current = second
        scheduler.run { holder.subscribe() }
        assertTrue(state.entries.isEmpty())
        holder.configure(RxLogFeatureDependencies({ null }, { null }, { null }))
        scheduler.run { holder.subscribe() }
        assertTrue(state.entries.isEmpty())
    }

    @Test
    fun `history is bounded to 1000 and counts follow pruned entries`() {
        val feed = FakeFeed()
        connect(feed)
        scheduler.run { holder.subscribe() }
        feed.live.tryEmit(entry("first"))
        repeat(1000) { feed.live.tryEmit(entry("h${it % 10}")) }
        scheduler.runCurrent()
        assertEquals(RxLogStateHolder.MAX_ENTRIES, state.entries.size)
        assertFalse("first" in state.groupCounts)
        assertEquals(100, state.groupCounts["h3"])
    }

    @Test
    fun `route and decrypt filters combine and group duplicates keeps the newest`() {
        val feed = FakeFeed(
            existing = listOf(
                entry("d1", RouteType.DIRECT, DecryptStatus.SUCCESS),
                entry("f1", RouteType.TC_FLOOD, DecryptStatus.HMAC_FAILED),
                entry("f1", RouteType.FLOOD, DecryptStatus.DM_NO_MATCHING_KEY),
                entry("f2", RouteType.FLOOD, DecryptStatus.PENDING),
            ),
        )
        connect(feed)
        scheduler.run { holder.subscribe() }
        holder.setRouteFilter(RxLogRouteFilter.FLOOD_ONLY)
        assertEquals(listOf("f1", "f1", "f2"), state.filteredEntries.map { it.packetHash })
        holder.setDecryptFilter(RxLogDecryptFilter.FAILED)
        assertEquals(2, state.filteredEntries.size)
        assertTrue(state.isFiltering)
        val list = RxLogListState().togglingGroupDuplicates()
        assertEquals(listOf(DecryptStatus.HMAC_FAILED), list.displayEntries(state).map { it.decryptStatus })
        assertEquals(2, list.groupCount(state, state.entries[1]))
        holder.setRouteFilter(RxLogRouteFilter.DIRECT_ONLY)
        holder.setDecryptFilter(RxLogDecryptFilter.DECRYPTED)
        assertEquals(listOf("d1"), state.filteredEntries.map { it.packetHash })
        assertEquals(1, RxLogListState().groupCount(state, state.entries[1]))
    }

    @Test
    fun `clearing empties the service and the list state`() {
        val feed = FakeFeed(existing = listOf(entry("a")))
        connect(feed)
        scheduler.run { holder.subscribe() }
        scheduler.run { holder.clearLog() }
        assertEquals(1, feed.cleared)
        assertTrue(state.entries.isEmpty())
        assertEquals(emptySet(), RxLogListState().settingExpanded("a", true).cleared().expandedHashes)
    }

    @Test
    fun `node names load from contacts and stay put while disconnected`() {
        connect(FakeFeed(), listOf(rxContact("Alice", Bytes.of(0xAA, 0xBB, 0xCC))))
        scheduler.run { holder.loadNodeNames() }
        assertEquals("Alice", state.nodeNames[Bytes.of(0xAA)])
        holder.configure(RxLogFeatureDependencies({ null }, { null }, { null }))
        scheduler.run { holder.loadNodeNames() }
        assertEquals("Alice", state.nodeNames[Bytes.of(0xAA)])
    }

    @Test
    fun `path display chunks by hash size, marks the local node and elides long paths`() {
        val nodes = (1..8).map { it.toUByte() }
        val long = rxEntry(pathLength = 0x08u, pathNodes = nodes)
        assertEquals("01 → 02 → 03  →  …  →  06 → 07 → 08", presentation.row(long, 1, null, emptyMap()).path)
        val twoByte = rxEntry(pathLength = 0x42u, pathNodes = listOf(0xAAu, 0xBBu, 0x01u, 0x02u))
        assertEquals("You → 0102", presentation.row(twoByte, 1, Bytes.of(0xAA, 0xBB, 0xCC), emptyMap()).path)
        assertEquals("Direct", presentation.row(rxEntry(), 1, null, emptyMap()).path)
        val detail = presentation.row(twoByte, 1, null, emptyMap()).details.first { it.label == "Path:" }
        assertEquals("2 hops [AABB, 0102]", detail.value)
    }

    @Test
    fun `trace rows show the target route or a hop count`() {
        val payload = Bytes(ByteArray(9)) + Bytes.of(0x11, 0x22)
        val trace = rxEntry(payloadType = PayloadType.TRACE, pathLength = 0x01u, pathNodes = listOf(0x05u), packetPayload = payload)
        val row = presentation.row(trace, 1, null, emptyMap())
        assertEquals("11 → 22", row.path)
        assertTrue(row.details.any { it.label == "Route:" && it.value == "11 → 22" })
        val noTargets = rxEntry(payloadType = PayloadType.TRACE, pathLength = 0x01u, pathNodes = listOf(0x05u))
        assertEquals("1 hop", presentation.row(noTargets, 1, null, emptyMap()).path)
    }

    @Test
    fun `direct text rows resolve sender and recipient labels`() {
        val dm = rxEntry(RouteType.DIRECT, PayloadType.TEXT_MESSAGE, packetPayload = Bytes.of(0xDD, 0xAA, 0x00, 0x00))
        val row = presentation.row(dm, 3, Bytes.of(0xDD), mapOf(Bytes.of(0xAA) to "Alice"))
        assertEquals("Alice → You", row.fromTo)
        assertEquals("×3", row.groupBadge)
        assertEquals("Received 3 times", row.groupAccessibility)
        assertEquals("TEXT_MSG · 0 bytes", row.packetSummary)
        assertNull(row.messagePreview)
    }

    @Test
    fun `decrypted channel rows show hash, name, region and text`() {
        val channel = rxEntry(packetPayload = Bytes.of(0x0A, 0x01)).copy(
            decryptStatus = DecryptStatus.SUCCESS, channelIndex = 1u, channelName = "Ops", decodedText = "hi",
            transportCode = Bytes.of(0x01, 0x02), regionScopeMatches = SnapshotList(listOf("North", "East", "West")),
            rawPayload = Bytes.of(0x0A, 0xFF),
        )
        val row = presentation.row(channel, 1, null, emptyMap())
        val details = row.details.associate { it.label to it.value }
        assertEquals("0a", details["Channel Hash:"])
        assertEquals("Ops", details["Channel Name:"])
        assertEquals("East, North, and West", details["Region:"])
        assertEquals("hi", row.decodedText)
        assertEquals("\"hi\"", row.messagePreview)
        assertEquals("0A FF", row.rawPayloadHex)
        val unresolved = presentation.row(channel.copy(regionScopeMatches = SnapshotList(emptyList())), 1, null, emptyMap())
        assertEquals("Unknown", unresolved.details.first { it.label == "Region:" }.value)
    }

    @Test
    fun `rate is independent of history filters retention and clear and unsubscribes on reconnect`() {
        val first = FakeFeed(existing = listOf(entry("historical")))
        connect(first)
        scheduler.run { holder.subscribe() }
        first.rates.value = 1401
        repeat(1001) { first.live.tryEmit(entry("duplicate")) }
        scheduler.runCurrent()
        holder.setRouteFilter(RxLogRouteFilter.DIRECT_ONLY)
        holder.setDecryptFilter(RxLogDecryptFilter.DECRYPTED)
        assertTrue(state.filteredEntries.isEmpty())
        assertEquals(1401L, state.packetsPerMinute)
        scheduler.run { holder.clearLog() }
        assertEquals(1401L, state.packetsPerMinute)
        assertEquals(1, first.rates.subscriptionCount.value)
        holder.unsubscribe()
        scheduler.runCurrent()
        assertNull(state.packetsPerMinute)
        assertEquals(0, first.rates.subscriptionCount.value)
        first.rates.value = 99
        scheduler.runCurrent()
        assertNull(state.packetsPerMinute)
        val second = FakeFeed(existing = listOf(entry("retained")))
        connect(second)
        scheduler.run { holder.subscribe() }
        assertEquals(0L, state.packetsPerMinute)
        assertEquals(0, first.rates.subscriptionCount.value)
        assertEquals(1, second.rates.subscriptionCount.value)
        connect(null)
        scheduler.run { holder.subscribe() }
        assertNull(state.packetsPerMinute)
        assertEquals(0, second.rates.subscriptionCount.value)
    }

    @Test
    fun `header text counts packets and reports live state`() {
        connect(FakeFeed(existing = listOf(entry("a"), entry("b"))))
        scheduler.run { holder.subscribe() }
        assertEquals("Live, 2 packets", presentation.headerAccessibility(isConnected = true, state = state))
        assertEquals("Offline", presentation.liveLabel(isConnected = false))
    }
}
