// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/RxLogServiceAdvertHopTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.protocol.model.encodePathLen
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class RxLogServiceAdvertHopTest {
    private val advertiserKey = Bytes(ByteArray(ProtocolLimits.PUBLIC_KEY_SIZE) { it.toByte() })

    /** An advert wire payload begins with the advertiser's full 32-byte public key. */
    private fun advertPayload(pubKey: Bytes): Bytes = pubKey + Bytes.of(0xAA, 0xBB, 0xCC, 0xDD)

    private fun hopCase(name: String, body: suspend RxLogSvcHarness.() -> Unit): DynamicTest =
        DynamicTest.dynamicTest(if (name.startsWith("WP-212::")) name else "RxLogServiceAdvertHopTests::$name()") {
            RxLogSvcHarness().use { harness ->
                runBlocking {
                    harness.service.startEventMonitoring(rxLogSvcRadio())
                    harness.body()
                }
            }
        }

    @TestFactory
    fun cases(): List<DynamicTest> = listOf(
        hopCase("advert RX-log entry stamps the inbound hop count keyed by full pubkey") {
            val hops = 3
            service.process(rxLogSvcParsed(
                PayloadType.ADVERT, advertPayload(advertiserKey), pathLength = encodePathLen(1, hops),
            ))
            val calls = store.inboundHopCountCalls
            assertEquals(1, calls.size)
            assertEquals(advertiserKey, calls.first().publicKey)
            assertEquals(hops.toLong(), calls.first().hopCount)
        },
        hopCase("a directly-heard advert stamps a hop count of zero") {
            service.process(rxLogSvcParsed(
                PayloadType.ADVERT, advertPayload(advertiserKey), pathLength = encodePathLen(1, 0),
            ))
            val calls = store.inboundHopCountCalls
            assertEquals(1, calls.size)
            assertEquals(0L, calls.first().hopCount)
        },
        hopCase("a truncated advert payload (shorter than a public key) is never stamped") {
            service.process(rxLogSvcParsed(
                PayloadType.ADVERT, Bytes.of(0x01, 0x02, 0x03), pathLength = encodePathLen(1, 1),
            ))
            assertTrue(store.inboundHopCountCalls.isEmpty(), "A payload shorter than a public key must not be stamped")
        },
        hopCase("advert timestamp is extracted from the payload and passed to the store") {
            // Payload: 32-byte key + 4-byte little-endian timestamp 0x00000064 (100 decimal).
            val timestamp = 100u
            service.process(rxLogSvcParsed(
                PayloadType.ADVERT, advertiserKey + rxLogSvcUInt32LE(timestamp), pathLength = encodePathLen(1, 2),
            ))
            val calls = store.inboundHopCountCalls
            assertEquals(1, calls.size)
            assertEquals(timestamp, calls.first().advertTimestamp)
        },
        hopCase("a payload exactly 32 bytes (no timestamp) passes nil advertTimestamp") {
            service.process(rxLogSvcParsed(PayloadType.ADVERT, advertiserKey, pathLength = encodePathLen(1, 1)))
            val calls = store.inboundHopCountCalls
            assertEquals(1, calls.size)
            assertNull(calls.first().advertTimestamp)
        },
        hopCase("a non-advert entry never stamps an inbound hop count") {
            service.process(rxLogSvcParsed(
                PayloadType.GROUP_TEXT, advertPayload(advertiserKey), pathLength = encodePathLen(1, 2),
            ))
            assertTrue(store.inboundHopCountCalls.isEmpty(), "Only advert payloads carry an inbound hop count")
        },
        hopCase("a direct-routed advert is never stamped: its path length is route, not hops traversed") {
            service.process(rxLogSvcParsed(
                PayloadType.ADVERT, advertPayload(advertiserKey), routeType = RouteType.DIRECT,
                pathLength = encodePathLen(1, 2),
            ))
            assertTrue(store.inboundHopCountCalls.isEmpty(), "Only flood-routed adverts accumulate a hop path")
        },
        hopCase("WP-212::tc-flood adverts stamp, reserved path-length modes skip the write") {
            service.process(rxLogSvcParsed(
                PayloadType.ADVERT, advertPayload(advertiserKey), routeType = RouteType.TC_FLOOD,
                pathLength = encodePathLen(2, 4),
            ))
            // Mode bits 0b11 are reserved: decodePathLen returns null, so nothing is stamped.
            service.process(rxLogSvcParsed(PayloadType.ADVERT, advertPayload(advertiserKey), pathLength = 0xC1u))
            assertEquals(listOf(4L), store.inboundHopCountCalls.map { it.hopCount })
        },
    )
}
