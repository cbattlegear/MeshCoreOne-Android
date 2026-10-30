// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/RxLogServiceRegionReprocessTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.protocol.parser.RegionScopeKey
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class RxLogServiceRegionReprocessTest {
    private fun case(name: String, body: suspend () -> Unit): DynamicTest =
        DynamicTest.dynamicTest(if (name.startsWith("WP-212::")) name else "RxLogServiceRegionReprocessTests::$name()") {
            runBlocking { body() }
        }

    /** Collects region update IDs from a stream registered before the triggering call. */
    private class RegionIdCollector(h: RxLogSvcHarness) {
        private val ids = mutableListOf<UUID>()
        private val stream = h.service.regionUpdateEvents()
        val job: Job = h.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            stream.collect { batch -> synchronized(ids) { ids += batch } }
        }
        val received: List<UUID> get() = synchronized(ids) { ids.toList() }
    }

    private fun transportCodedEntry(
        radioId: RadioId,
        payload: Bytes,
        senderTimestamp: UInt,
        channelIndex: UByte?,
        regionScope: String?,
        regionScopeMatches: List<String>,
        payloadType: PayloadType = PayloadType.GROUP_TEXT,
        transportRegion: String = "Germany",
    ): RxLogEntryDTO {
        val parsed = rxLogSvcParsed(
            payloadType, payload, routeType = RouteType.TC_FLOOD, payloadTypeBits = 5u,
            transportCode = rxLogSvcTransportCode(transportRegion, 5u, payload), snr = 5.0, rssi = -80,
        )
        return RxLogEntryDTO.fromParsed(
            radioId, parsed, channelIndex = channelIndex, channelName = channelIndex?.let { "Public" },
            decryptStatus = DecryptStatus.SUCCESS, senderTimestamp = senderTimestamp,
            regionScope = regionScope, regionScopeMatches = regionScopeMatches.snapshot(),
        )
    }

    @TestFactory
    fun cases(): List<DynamicTest> = listOf(
        case("replaceScopeKeyCache rewrites sticky first-match to ambiguous multi-match") {
            RxLogSvcHarness().use { h ->
                val radioId = rxLogSvcRadio()
                h.store.saveDevice(rxLogSvcDevice(radioId, listOf("First")))
                val scopeKey = rxLogSvcScopeKey("Germany")
                val senderTimestamp = 1_704_000_000u
                val entry = transportCodedEntry(
                    radioId, Bytes.of(0x10, 0x20, 0x30, 0x40, 0x50), senderTimestamp, 0u, "First", listOf("First"),
                )
                h.store.saveRxLogEntry(entry)
                // Correlated channel message must follow the RxLog sticky -> ambiguous rewrite.
                val message = RxLogSvcMessage(channelIndex = 0u, senderTimestamp = senderTimestamp)
                h.store.saveMessage(message)

                h.service.startEventMonitoring(radioId)
                val collector = RegionIdCollector(h)

                // Two display names sharing one scope key: the multi-match fixture.
                h.service.replaceScopeKeyCacheAndReprocess(listOf(RegionScopeKey("First", scopeKey), RegionScopeKey("Second", scopeKey)))

                val updated = assertNotNull(h.store.fetchRxLogEntries(radioId, 500).firstOrNull { it.id == entry.id })
                assertNull(updated.regionScope, "ambiguous must clear sticky regionScope")
                assertEquals(setOf("First", "Second"), updated.regionScopeMatches.toSet())

                rxLogSvcWaitUntil("region update event should fire for multi-match message rewrite") { collector.received.isNotEmpty() }
                collector.job.cancel()
                assertTrue(message.id in collector.received)

                val savedMessage = assertNotNull(h.store.message(message.id))
                assertNull(savedMessage.regionScope, "ambiguous must clear message regionScope")
                assertEquals(setOf("First", "Second"), savedMessage.regionScopeMatches.toSet())
            }
        },
        case("empty known regions clears sticky labels") {
            RxLogSvcHarness().use { h ->
                val radioId = rxLogSvcRadio()
                h.store.saveDevice(rxLogSvcDevice(radioId, listOf("Germany")))
                val entry = transportCodedEntry(
                    radioId, Bytes.of(0xAA, 0xBB, 0xCC), 1_704_000_100u, null, "Germany", listOf("Germany"),
                )
                h.store.saveRxLogEntry(entry)

                h.service.startEventMonitoring(radioId)
                // Wait for the secret load so knownRegions is ["Germany"] before clearing;
                // updateKnownRegions([]) while still default-empty is a no-op.
                rxLogSvcWaitUntil("known regions loaded from device") { h.service.knownRegions == listOf("Germany") }

                h.service.updateKnownRegions(emptyList())

                val updated = assertNotNull(h.store.fetchRxLogEntries(radioId, 500).firstOrNull { it.id == entry.id })
                assertNull(updated.regionScope)
                assertEquals(emptyList(), updated.regionScopeMatches)
            }
        },
        case("live resolve persists unique name and matches array together") {
            RxLogSvcHarness().use { h ->
                val radioId = rxLogSvcRadio()
                h.store.saveDevice(rxLogSvcDevice(radioId, listOf("Germany")))
                h.service.startEventMonitoring(radioId)
                rxLogSvcWaitUntil("known regions loaded from device") { h.service.knownRegions == listOf("Germany") }

                val payload = Bytes.of(0x01, 0x02, 0x03, 0x04)
                val transportCode = rxLogSvcTransportCode("Germany", 5u, payload)
                h.service.process(rxLogSvcParsed(
                    PayloadType.GROUP_TEXT, payload, routeType = RouteType.TC_FLOOD, payloadTypeBits = 5u,
                    transportCode = transportCode, snr = 5.0, rssi = -80,
                ))

                val live = assertNotNull(h.store.fetchRxLogEntries(radioId, 500).firstOrNull { it.transportCode == transportCode })
                assertEquals("Germany", live.regionScope)
                assertEquals(listOf("Germany"), live.regionScopeMatches)
            }
        },
        case("regionUpdateEvents yields message IDs after channel message reprocess") {
            RxLogSvcHarness().use { h ->
                val radioId = rxLogSvcRadio()
                // Non-matching seed so the secret load can finish without resolving the Germany code.
                h.store.saveDevice(rxLogSvcDevice(radioId, listOf("Placeholder")))
                val senderTimestamp = 1_704_111_000u
                val entry = transportCodedEntry(radioId, Bytes.of(0xDE, 0xAD, 0xBE, 0xEF), senderTimestamp, 0u, null, emptyList())
                h.store.saveRxLogEntry(entry)
                val message = RxLogSvcMessage(channelIndex = 0u, senderTimestamp = senderTimestamp)
                h.store.saveMessage(message)

                h.service.startEventMonitoring(radioId)
                val collector = RegionIdCollector(h)
                // Wait for the load so its placeholder cache cannot clobber Germany.
                rxLogSvcWaitUntil("known regions loaded from device") { h.service.knownRegions == listOf("Placeholder") }

                h.service.updateKnownRegions(listOf("Germany"))

                rxLogSvcWaitUntil("region update event should fire for correlated message") { collector.received.isNotEmpty() }
                collector.job.cancel()
                assertTrue(message.id in collector.received)
                val saved = assertNotNull(h.store.message(message.id))
                assertEquals("Germany", saved.regionScope)
                assertEquals(listOf("Germany"), saved.regionScopeMatches)
            }
        },
        case("regionUpdateEvents yields message IDs after DM message reprocess") {
            RxLogSvcHarness().use { h ->
                val radioId = rxLogSvcRadio()
                h.store.saveDevice(rxLogSvcDevice(radioId, listOf("Placeholder")))
                // Byte at offset 1 is the unencrypted sender prefix used for DM correlation.
                val senderPrefixByte = 0xAB
                val packetPayload = Bytes.of(0x00, senderPrefixByte, 0xCC, 0xDD, 0xEE)
                val senderTimestamp = 1_704_222_000u
                // nil channelIndex marks a DM entry; correlation uses packetPayload[1].
                val entry = transportCodedEntry(
                    radioId, packetPayload, senderTimestamp, null, null, emptyList(), payloadType = PayloadType.TEXT_MESSAGE,
                )
                h.store.saveRxLogEntry(entry)
                val message = RxLogSvcMessage(
                    channelIndex = null, senderTimestamp = senderTimestamp,
                    senderKeyPrefix = Bytes.of(senderPrefixByte, 0x11, 0x22, 0x33),
                )
                h.store.saveMessage(message)

                h.service.startEventMonitoring(radioId)
                val collector = RegionIdCollector(h)
                rxLogSvcWaitUntil("known regions loaded from device") { h.service.knownRegions == listOf("Placeholder") }

                h.service.updateKnownRegions(listOf("Germany"))

                rxLogSvcWaitUntil("region update event should fire for correlated DM") { collector.received.isNotEmpty() }
                collector.job.cancel()
                assertTrue(message.id in collector.received)
                val saved = assertNotNull(h.store.message(message.id))
                assertEquals("Germany", saved.regionScope)
                assertEquals(listOf("Germany"), saved.regionScopeMatches)
            }
        },
        case("reprocess overwrites intermediate resolution with final cache") {
            RxLogSvcHarness().use { h ->
                val radioId = rxLogSvcRadio()
                h.store.saveDevice(rxLogSvcDevice(radioId, emptyList()))
                val scopeKey = rxLogSvcScopeKey("Germany")
                val senderTimestamp = 1_704_333_000u
                val entry = transportCodedEntry(radioId, Bytes.of(0xCA, 0xFE, 0xBA, 0xBE), senderTimestamp, 0u, "Stale", listOf("Stale"))
                h.store.saveRxLogEntry(entry)
                val message = RxLogSvcMessage(channelIndex = 0u, senderTimestamp = senderTimestamp)
                h.store.saveMessage(message)

                h.service.startEventMonitoring(radioId)

                // Intermediate unique cache must land before the final multi-match overwrite.
                h.service.replaceScopeKeyCacheAndReprocess(listOf(RegionScopeKey("Germany", scopeKey)))
                val mid = assertNotNull(h.store.entry(entry.id))
                assertEquals("Germany", mid.regionScope)
                assertEquals(listOf("Germany"), mid.regionScopeMatches)

                h.service.replaceScopeKeyCacheAndReprocess(listOf(RegionScopeKey("First", scopeKey), RegionScopeKey("Second", scopeKey)))
                val finalEntry = assertNotNull(h.store.entry(entry.id))
                assertNull(finalEntry.regionScope)
                assertEquals(setOf("First", "Second"), finalEntry.regionScopeMatches.toSet())
                val finalMessage = assertNotNull(h.store.message(message.id))
                assertNull(finalMessage.regionScope)
                assertEquals(setOf("First", "Second"), finalMessage.regionScopeMatches.toSet())
            }
        },
        case("WP-212::region reprocess scans the full retention window (keepCount + pruneThreshold)") {
            RxLogSvcHarness().use { h ->
                val radioId = rxLogSvcRadio()
                h.service.startEventMonitoring(radioId)
                rxLogSvcWaitUntil("connect-path reprocess fetched") { h.store.transportCodeFetchLimits.isNotEmpty() }
                assertEquals(1100L, h.store.transportCodeFetchLimits.first())
            }
        },
        case("WP-212::overlapping region reprocess callers park and converge on the final cache") {
            RxLogSvcHarness().use { h ->
                val radioId = rxLogSvcRadio()
                h.store.saveDevice(rxLogSvcDevice(radioId, emptyList()))
                val entry = transportCodedEntry(radioId, Bytes.of(0x01, 0x23), 1u, 0u, null, emptyList())
                h.store.saveRxLogEntry(entry)
                h.service.startEventMonitoring(radioId)
                rxLogSvcWaitUntil("connect-path reprocess finished") { h.store.transportCodeFetchLimits.size == 1 && !h.service.isReprocessingRegions }

                val gate = CompletableDeferred<Unit>()
                h.store.beforeTransportFetch = { gate.await() }
                val owner = h.scope.async { h.service.updateKnownRegions(listOf("Other")) }
                rxLogSvcWaitUntil("owner pass is fetching") { h.store.transportCodeFetchLimits.size == 2 }
                try {
                    // Run each caller through cache installation to its parked await in the intended order.
                    val waiterA = h.scope.async(start = CoroutineStart.UNDISPATCHED) {
                        h.service.updateKnownRegions(listOf("Elsewhere"))
                    }
                    val waiterB = h.scope.async(start = CoroutineStart.UNDISPATCHED) {
                        h.service.updateKnownRegions(listOf("Germany"))
                    }
                    assertEquals(listOf("Germany"), h.service.knownRegions)
                    assertTrue(!waiterA.isCompleted && !waiterB.isCompleted, "callers park while the owner drains")
                    h.store.beforeTransportFetch = {}
                    gate.complete(Unit)
                    withTimeout(5_000) { owner.await(); waiterA.await(); waiterB.await() }
                } finally {
                    h.store.beforeTransportFetch = {}
                    gate.complete(Unit)
                }

                assertEquals(listOf("Germany"), h.service.knownRegions)
                assertEquals("Germany", h.store.entry(entry.id)?.regionScope, "the final cache wins")
                // Owner pass + one dirty re-run absorb both overlapping callers.
                assertEquals(3, h.store.transportCodeFetchLimits.size)
            }
        },
        case("WP-212::cancelling a parked region reprocess caller propagates cancellation without wedging the owner") {
            RxLogSvcHarness().use { h ->
                val radioId = rxLogSvcRadio()
                h.service.startEventMonitoring(radioId)
                rxLogSvcWaitUntil("connect-path reprocess finished") { h.store.transportCodeFetchLimits.size == 1 && !h.service.isReprocessingRegions }
                val gate = CompletableDeferred<Unit>()
                h.store.beforeTransportFetch = { gate.await() }
                val owner = h.scope.async { h.service.updateKnownRegions(listOf("A")) }
                rxLogSvcWaitUntil("owner pass is fetching") { h.store.transportCodeFetchLimits.size == 2 }
                val waiter = h.scope.async { h.service.updateKnownRegions(listOf("B")) }
                kotlinx.coroutines.delay(50)
                waiter.cancel()
                val failure = try { waiter.await(); null } catch (cancelled: CancellationException) { cancelled }
                assertNotNull(failure, "a cancelled waiter surfaces CancellationException")
                h.store.beforeTransportFetch = {}
                gate.complete(Unit)
                withTimeout(5_000) { owner.await() }
                // The guard reset: a fresh call runs its own pass.
                val before = h.store.transportCodeFetchLimits.size
                h.service.updateKnownRegions(listOf("C"))
                assertTrue(h.store.transportCodeFetchLimits.size > before)
            }
        },
        case("WP-212::a failed region pass is logged and the next reprocess still runs") {
            RxLogSvcHarness().use { h ->
                val radioId = rxLogSvcRadio()
                h.store.transportFetchFailure = IllegalStateException("db locked")
                h.service.startEventMonitoring(radioId)
                rxLogSvcWaitUntil("failure logged") {
                    h.logs.all.any { it.category == "RxLogService.Region" && it.message == "Failed to re-process region entries: db locked" }
                }
                h.store.transportFetchFailure = null
                val entry = transportCodedEntry(radioId, Bytes.of(0x05), 2u, null, null, emptyList())
                h.store.saveRxLogEntry(entry)
                h.service.updateKnownRegions(listOf("Germany"))
                assertEquals("Germany", h.store.entry(entry.id)?.regionScope)
            }
        },
        case("WP-212::a slower device load cannot overwrite a newer region update") {
            RxLogSvcHarness().use { h ->
                val radioId = rxLogSvcRadio()
                h.store.saveDevice(rxLogSvcDevice(radioId, listOf("FromDevice")))
                val gate = CompletableDeferred<Unit>()
                h.store.beforeDeviceFetch = { gate.await() }
                h.service.startEventMonitoring(radioId)
                rxLogSvcWaitUntil("device load is in flight") { h.store.deviceFetchCount == 1 }
                h.service.updateKnownRegions(listOf("Newer"))
                gate.complete(Unit)
                rxLogSvcWaitUntil("load finished and monitor subscribed") { h.session.subscriberCount == 1 }
                assertEquals(listOf("Newer"), h.service.knownRegions, "the generation check discards the stale load")
            }
        },
        case("WP-212::scope-key cache skips dollar-prefixed and blank region names") {
            val cache = RxLogRegionReprocessor.buildScopeKeyCache(listOf("\$hidden", "  ", "", "Germany"))
            assertEquals(listOf("Germany"), cache.map { it.name })
            assertEquals(rxLogSvcScopeKey("Germany"), cache.single().key)
        },
        case("WP-212::regionUpdateEvents subscribers end when the stream is finished") {
            RxLogSvcHarness().use { h ->
                val stream = h.service.regionUpdateEvents()
                val done = h.scope.async { stream.collect {}; true }
                h.service.finishEntryStream()
                assertTrue(withTimeout(5_000) { done.await() })
                assertEquals(0, h.service.regionUpdateBroadcaster.subscriberCount)
            }
        },
    )
}
