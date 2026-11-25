// PortedFrom: MC1Tests/ViewModels/RxLogViewModelTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.diagnostics.rxlog

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ParsedRxLogData
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.feature.tools.diagnostics.support.OriginalCase
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

internal val RX_RADIO = RadioId(UUID.fromString("00000000-0000-0000-0000-0000000000BB"))

internal fun rxEntry(
    routeType: RouteType = RouteType.FLOOD,
    payloadType: PayloadType = PayloadType.UNKNOWN,
    pathLength: UByte = 0u,
    pathNodes: List<UByte> = emptyList(),
    packetPayload: Bytes = Bytes.EMPTY,
): RxLogEntryDTO = RxLogEntryDTO.fromParsed(
    RX_RADIO,
    ParsedRxLogData(
        snr = null, rssi = null, rawPayload = Bytes.EMPTY, routeType = routeType, payloadType = payloadType,
        payloadVersion = 0u, payloadTypeBits = (payloadType.rawValue.toInt() and 0x0F).toUByte(), transportCode = null,
        pathLength = pathLength, pathNodes = pathNodes, packetPayload = packetPayload,
    ),
)

internal fun rxContact(name: String, publicKey: Bytes, nickname: String? = null): ContactDTO = ContactDTO(
    radioId = RX_RADIO, publicKey = publicKey, name = name, lastHeardTimestamp = null, nickname = nickname,
)

/** These cases exercise core:model `RxLogEntryDTO` (consumed here) and the holder's node-name map. */
class RxLogTest {
    private fun tracePayload(flags: Int, vararg hashes: Int): Bytes {
        val header = ByteArray(9).also { it[8] = flags.toByte() }
        return Bytes(header) + Bytes.of(*hashes)
    }

    private val directText = Bytes.of(0xDD, 0xAA, 0xFF, 0xFF)

    // MARK: - RxLogEntryDTO computed properties

    @Test @OriginalCase("RxLogViewModelTests::traceTargetHashes extracts 1-byte hashes when path_sz=0()")
    fun `traceTargetHashes extracts 1-byte hashes when path_sz=0`() {
        val hashes = rxEntry(payloadType = PayloadType.TRACE, packetPayload = tracePayload(0x00, 0xAA, 0xBB, 0xCC)).traceTargetHashes
        assertEquals(listOf(Bytes.of(0xAA), Bytes.of(0xBB), Bytes.of(0xCC)), hashes?.toList())
    }

    @Test @OriginalCase("RxLogViewModelTests::traceTargetHashes extracts 2-byte hashes when path_sz=1()")
    fun `traceTargetHashes extracts 2-byte hashes when path_sz=1`() {
        val hashes = rxEntry(payloadType = PayloadType.TRACE, packetPayload = tracePayload(0x01, 0xAA, 0xBB, 0xCC, 0xDD)).traceTargetHashes
        assertEquals(listOf(Bytes.of(0xAA, 0xBB), Bytes.of(0xCC, 0xDD)), hashes?.toList())
    }

    @Test @OriginalCase("RxLogViewModelTests::traceTargetHashes returns nil for non-TRACE payload type()")
    fun `traceTargetHashes returns nil for non-TRACE payload type`() =
        assertNull(rxEntry(payloadType = PayloadType.TEXT_MESSAGE, packetPayload = Bytes(ByteArray(12))).traceTargetHashes)

    @Test @OriginalCase("RxLogViewModelTests::traceTargetHashes returns nil when payload is too short()")
    fun `traceTargetHashes returns nil when payload is too short`() =
        assertNull(rxEntry(payloadType = PayloadType.TRACE, packetPayload = Bytes(ByteArray(8))).traceTargetHashes)

    @Test @OriginalCase("RxLogViewModelTests::traceTargetHashes returns nil when hash bytes don't divide evenly()")
    fun `traceTargetHashes returns nil when hash bytes don't divide evenly`() =
        assertNull(rxEntry(payloadType = PayloadType.TRACE, packetPayload = tracePayload(0x01, 0xAA, 0xBB, 0xCC)).traceTargetHashes)

    @Test @OriginalCase("RxLogViewModelTests::senderPrefix extracts correct bytes for hashSize=1()")
    fun `senderPrefix extracts correct bytes for hashSize=1`() {
        val entry = rxEntry(RouteType.DIRECT, PayloadType.TEXT_MESSAGE, pathLength = 0x01u, packetPayload = directText)
        assertEquals(Bytes.of(0xAA), entry.senderPrefix)
        assertEquals(Bytes.of(0xDD), entry.recipientPrefix)
    }

    @Test @OriginalCase("RxLogViewModelTests::senderPrefix uses fixed 1-byte payload hashes when path hashSize=2()")
    fun `senderPrefix uses fixed 1-byte payload hashes when path hashSize=2`() {
        val entry = rxEntry(RouteType.DIRECT, PayloadType.TEXT_MESSAGE, pathLength = 0x41u, packetPayload = directText)
        assertEquals(Bytes.of(0xAA), entry.senderPrefix)
        assertEquals(Bytes.of(0xDD), entry.recipientPrefix)
    }

    @Test @OriginalCase("RxLogViewModelTests::senderPrefix uses fixed 1-byte payload hashes when path hashSize=3()")
    fun `senderPrefix uses fixed 1-byte payload hashes when path hashSize=3`() {
        val entry = rxEntry(RouteType.DIRECT, PayloadType.TEXT_MESSAGE, pathLength = 0x81u, packetPayload = directText)
        assertEquals(Bytes.of(0xAA), entry.senderPrefix)
        assertEquals(Bytes.of(0xDD), entry.recipientPrefix)
    }

    @Test @OriginalCase("RxLogViewModelTests::senderPrefix returns nil for flood route()")
    fun `senderPrefix returns nil for flood route`() {
        val entry = rxEntry(RouteType.FLOOD, PayloadType.TEXT_MESSAGE, pathLength = 0x01u, packetPayload = directText)
        assertNull(entry.senderPrefix)
        assertNull(entry.recipientPrefix)
    }

    @Test @OriginalCase("RxLogViewModelTests::senderPrefix returns nil for non-text payload()")
    fun `senderPrefix returns nil for non-text payload`() =
        assertNull(rxEntry(RouteType.DIRECT, PayloadType.TRACE, pathLength = 0x01u, packetPayload = directText).senderPrefix)

    @Test @OriginalCase("RxLogViewModelTests::pathHashSize decodes TRACE using standard pathLength encoding()")
    fun `pathHashSize decodes TRACE using standard pathLength encoding`() =
        assertEquals(2L, rxEntry(payloadType = PayloadType.TRACE, pathLength = 0x41u).pathHashSize)

    @Test @OriginalCase("RxLogViewModelTests::hopCount decodes TRACE using standard pathLength encoding()")
    fun `hopCount decodes TRACE using standard pathLength encoding`() =
        assertEquals(3L, rxEntry(payloadType = PayloadType.TRACE, pathLength = 0x43u).hopCount)

    @Test @OriginalCase("RxLogViewModelTests::hopCount uses decodePathLen for non-TRACE()")
    fun `hopCount uses decodePathLen for non-TRACE`() {
        val entry = rxEntry(payloadType = PayloadType.TEXT_MESSAGE, pathLength = 0x43u)
        assertEquals(3L, entry.hopCount)
        assertEquals(2L, entry.pathHashSize)
    }

    // MARK: - buildNodeNameMap

    @Test @OriginalCase("RxLogViewModelTests::Empty contacts produces empty map()")
    fun `Empty contacts produces empty map`() = assertTrue(RxLogStateHolder.buildNodeNameMap(emptyList()).isEmpty())

    @Test @OriginalCase("RxLogViewModelTests::Single contact generates entries for 1, 2, and 3-byte prefixes()")
    fun `Single contact generates entries for 1, 2, and 3-byte prefixes`() {
        val map = RxLogStateHolder.buildNodeNameMap(listOf(rxContact("Alice", Bytes.of(0xAA, 0xBB, 0xCC, 0xDD))))
        assertEquals("Alice", map[Bytes.of(0xAA)])
        assertEquals("Alice", map[Bytes.of(0xAA, 0xBB)])
        assertEquals("Alice", map[Bytes.of(0xAA, 0xBB, 0xCC)])
    }

    @Test @OriginalCase("RxLogViewModelTests::Two contacts with different first bytes resolve at all prefix lengths()")
    fun `Two contacts with different first bytes resolve at all prefix lengths`() {
        val map = RxLogStateHolder.buildNodeNameMap(
            listOf(rxContact("Alice", Bytes.of(0xAA, 0xBB, 0xCC, 0xDD)), rxContact("Bob", Bytes.of(0x11, 0x22, 0x33, 0x44))),
        )
        assertEquals("Alice", map[Bytes.of(0xAA)])
        assertEquals("Bob", map[Bytes.of(0x11)])
        assertEquals("Alice", map[Bytes.of(0xAA, 0xBB)])
        assertEquals("Bob", map[Bytes.of(0x11, 0x22)])
        assertEquals("Alice", map[Bytes.of(0xAA, 0xBB, 0xCC)])
        assertEquals("Bob", map[Bytes.of(0x11, 0x22, 0x33)])
    }

    @Test @OriginalCase("RxLogViewModelTests::Two contacts sharing first byte omit 1-byte entry but resolve at 2 and 3 bytes()")
    fun `Two contacts sharing first byte omit 1-byte entry but resolve at 2 and 3 bytes`() {
        val map = RxLogStateHolder.buildNodeNameMap(
            listOf(rxContact("Alice", Bytes.of(0xAA, 0xBB, 0xCC, 0xDD)), rxContact("Bob", Bytes.of(0xAA, 0x22, 0x33, 0x44))),
        )
        assertNull(map[Bytes.of(0xAA)])
        assertEquals("Alice", map[Bytes.of(0xAA, 0xBB)])
        assertEquals("Bob", map[Bytes.of(0xAA, 0x22)])
        assertEquals("Alice", map[Bytes.of(0xAA, 0xBB, 0xCC)])
        assertEquals("Bob", map[Bytes.of(0xAA, 0x22, 0x33)])
    }

    @Test @OriginalCase("RxLogViewModelTests::Two contacts sharing first two bytes omit 1 and 2-byte entries but resolve at 3 bytes()")
    fun `Two contacts sharing first two bytes omit 1 and 2-byte entries but resolve at 3 bytes`() {
        val map = RxLogStateHolder.buildNodeNameMap(
            listOf(rxContact("Alice", Bytes.of(0xAA, 0xBB, 0xCC, 0xDD)), rxContact("Bob", Bytes.of(0xAA, 0xBB, 0x33, 0x44))),
        )
        assertNull(map[Bytes.of(0xAA)])
        assertNull(map[Bytes.of(0xAA, 0xBB)])
        assertEquals("Alice", map[Bytes.of(0xAA, 0xBB, 0xCC)])
        assertEquals("Bob", map[Bytes.of(0xAA, 0xBB, 0x33)])
    }

    @Test @OriginalCase("RxLogViewModelTests::Contact with short public key only generates entries for available lengths()")
    fun `Contact with short public key only generates entries for available lengths`() {
        val map = RxLogStateHolder.buildNodeNameMap(listOf(rxContact("Short", Bytes.of(0xAA, 0xBB))))
        assertEquals("Short", map[Bytes.of(0xAA)])
        assertEquals("Short", map[Bytes.of(0xAA, 0xBB)])
        assertEquals(2, map.size)
    }

    @Test @OriginalCase("RxLogViewModelTests::Nickname takes precedence over name via displayName()")
    fun `Nickname takes precedence over name via displayName`() {
        val map = RxLogStateHolder.buildNodeNameMap(listOf(rxContact("Alice Jones", Bytes.of(0xAA, 0xBB, 0xCC, 0xDD), nickname = "AJ")))
        assertEquals("AJ", map[Bytes.of(0xAA)])
    }
}
