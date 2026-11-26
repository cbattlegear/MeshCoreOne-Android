// PortedFrom: MeshCore/Sources/MeshCore/Events/MeshEvent.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Sources/MeshCore/Events/DiagnosticsPayloads.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Sources/MeshCore/Events/RemoteAdminPayloads.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Tests/MeshCoreTests/Events/MeshEventErrorCodeTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.event

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.lpp.LPPDecodeDiagnostic
import com.meshcoreone.android.core.protocol.lpp.LPPDecodeResult
import com.meshcoreone.android.core.protocol.lpp.LPPValue
import com.meshcoreone.android.core.protocol.model.ErrorCode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun statusResponse(snr: Double = 0.0, key: Bytes = Bytes.of(1, 2)): StatusResponse =
    StatusResponse(
        publicKeyPrefix = key, battery = 0, txQueueLength = 0, noiseFloor = 0, lastRSSI = 0,
        packetsReceived = 0u, packetsSent = 0u, airtime = 0u, uptime = 0u, sentFlood = 0u,
        sentDirect = 0u, receivedFlood = 0u, receivedDirect = 0u, fullEvents = 0, lastSNR = snr,
        directDuplicates = 0, floodDuplicates = 0, rxAirtime = 0u,
    )

class EventPayloadTest {
    @Test
    fun `All six firmware subcodes retain original typed and raw error accessors`() {
        val expected = listOf(
            ErrorCode.UNSUPPORTED_COMMAND, ErrorCode.NOT_FOUND, ErrorCode.TABLE_FULL,
            ErrorCode.BAD_STATE, ErrorCode.FILE_IO_ERROR, ErrorCode.ILLEGAL_ARGUMENT,
        )
        for ((index, code) in expected.withIndex()) {
            val raw = (index + 1).toUByte()
            assertEquals(code, ErrorCode.fromRawValue(raw))
            assertEquals(code, MeshEvent.Error(raw).errorCode)
            assertEquals(raw, MeshEvent.Error(raw).code)
            assertEquals(code, MeshCoreException.DeviceError(raw).deviceErrorCode)
        }
    }

    @Test
    fun `Every unknown byte has null typed error but unchanged raw value`() {
        for (value in listOf(0) + (7..255)) {
            val event = MeshEvent.Error(value.toUByte())
            assertNull(event.errorCode)
            assertEquals(value.toUByte(), event.code)
            assertEquals(value.toUByte(), event.attributes["code"])
        }
    }

    @Test
    fun `A missing subcode retains its attribute entry and null typed code`() {
        val event = MeshEvent.Error(null)
        assertNull(event.errorCode)
        assertTrue(event.attributes.containsKey("code"))
        assertNull(event.attributes["code"])
    }

    @Test
    fun `Nonerror cases never gain a device error code`() {
        assertNull(MeshEvent.Ok(null).errorCode)
        assertNull(MeshEvent.NoMoreMessages.errorCode)
        assertNull(MeshCoreException.Timeout().deviceErrorCode)
        assertNull(MeshCoreException.DeviceError(7u).deviceErrorCode)
    }

    @Test
    fun `Attribute maps preserve exact source keys types absent fields and nulls`() {
        val key = Bytes.of(1, 2, 3, 4, 5, 6, 7)
        assertEquals(mapOf("publicKeyPrefix" to key.prefix(6)), MeshEvent.Advertisement(key).attributes)
        assertEquals(mapOf("publicKeyPrefix" to key.prefix(6)), MeshEvent.PathUpdate(key).attributes)
        assertEquals(mapOf("code" to key), MeshEvent.Acknowledgement(key).attributes)
        assertEquals(mapOf("code" to key, "tripTime" to UInt.MAX_VALUE), MeshEvent.Acknowledgement(key, UInt.MAX_VALUE).attributes)
        assertEquals(mapOf("value" to null), MeshEvent.Ok(null).attributes)
        assertEquals(
            mapOf("route" to 0xffu.toUByte(), "expectedAck" to key),
            MeshEvent.MessageSent(MessageSentInfo(0xffu, key, 1u)).attributes,
        )
        assertEquals(emptyMap(), MeshEvent.NoMoreMessages.attributes)
    }

    @Test
    fun `Message receipt attributes use raw type and channel bytes without enum ordinals`() {
        val direct = contactMessage().copy(textType = 0xffu)
        assertEquals(
            mapOf("publicKeyPrefix" to direct.senderPublicKeyPrefix, "textType" to 0xffu.toUByte()),
            MeshEvent.ContactMessageReceived(direct).attributes,
        )
        val channel = channelMessage(0xffu).copy(textType = 0x80u)
        assertEquals(
            mapOf("channelIndex" to 0xffu.toUByte(), "textType" to 0x80u.toUByte()),
            MeshEvent.ChannelMessageReceived(channel).attributes,
        )
    }

    @Test
    fun `Case name never includes payload text keys or diagnostics`() {
        assertEquals("parseFailure", MeshEvent.ParseFailure(Bytes.of(1), "secret-marker").caseName)
        assertEquals("acknowledgement", MeshEvent.Acknowledgement(Bytes.of(0xaa)).caseName)
        assertEquals("contactsFull", MeshEvent.ContactsFull.caseName)
        assertEquals("contactMessageReceived", MeshEvent.ContactMessageReceived(contactMessage()).caseName)
        assertEquals("defaultFloodScope", MeshEvent.DefaultFloodScope(null).caseName)
    }

    @Test
    fun `Telemetry retains valid prefix plus malformed suffix diagnostic and raw bytes`() {
        val raw = Bytes.of(1, 103, 0, 0xff, 2, 0xff, 0xaa)
        val response = TelemetryResponse(Bytes.of(1, 2), Bytes.of(3), raw)
        val decoded = response.decodeResult
        assertTrue(decoded is LPPDecodeResult.Incomplete)
        assertEquals(4, decoded.consumedByteCount)
        assertEquals(Bytes.of(2, 0xff, 0xaa), decoded.remainingData)
        assertEquals(LPPDecodeDiagnostic.UnknownSensorType(4, 2u, 0xffu), decoded.diagnostic)
        assertEquals(1, response.dataPoints.size)
        assertEquals(LPPValue.Float(25.5), response.dataPoints[0].value)
        assertEquals(raw, response.rawData)
        assertTrue(response.decodeResult === decoded)
    }

    @Test
    fun `Empty telemetry is complete and malformed-only telemetry is not success shaped empty`() {
        assertTrue(TelemetryResponse(Bytes.EMPTY, null, Bytes.EMPTY).decodeResult.isComplete)
        val malformed = TelemetryResponse(Bytes.EMPTY, null, Bytes.of(1))
        assertEquals(emptyList(), malformed.dataPoints)
        assertFalse(malformed.decodeResult.isComplete)
        assertTrue(malformed.decodeResult is LPPDecodeResult.Incomplete)
    }

    @Test
    fun `Collection snapshots and generated copies cannot acquire mutable caller state`() {
        val trace = mutableListOf(TraceNode.fromHash(1u, 0.0))
        val response = TraceInfo(0u, 0u, 0u, 1u, trace)
        trace.clear()
        assertEquals(1, response.path.size)
        assertEquals(response, response.copy())
        val readOnly: List<TraceNode> = response.path
        assertFailsWith<ClassCastException> {
            (readOnly as MutableList<TraceNode>).clear()
        }
        val variables = mutableMapOf("key" to "before")
        val event = MeshEvent.CustomVars(variables)
        variables["key"] = "after"
        assertEquals("before", event.values["key"])
        assertFailsWith<UnsupportedOperationException> {
            (event.values.entries.first() as MutableMap.MutableEntry<String, String>).setValue("changed")
        }
    }

    @Test
    fun `Every Double payload preserves signed-zero equality and hash agreement`() {
        val factories: List<(Double) -> Any> = listOf(
            { contactMessage().copy(snr = it) }, { channelMessage().copy(snr = it) },
            { ChannelDatagram(0u, 0u, 1u, Bytes.EMPTY, it) },
            { DiscoverResponse(0u, it, it, 0, 0u, Bytes.EMPTY, Bytes.EMPTY) },
            { statusResponse(it) }, { TuningParamsResponse(it, it) },
            { RadioStats(0, 0, it, 0u, 0u) }, { TraceNode(null, it) },
            { RawDataInfo(it, 0, Bytes.EMPTY) }, { LogDataInfo(it, 0, Bytes.EMPTY) },
            { ControlDataInfo(it, 0, 0u, 0u, Bytes.EMPTY) }, { MMAEntry(0u, "", it, it, it) },
            { Neighbour(Bytes.EMPTY, 0, it) },
            { ParsedRxLogData(it, 0, Bytes.EMPTY, RouteType.FLOOD, PayloadType.ACK, 0u, 3u, null, 0u, emptyList(), Bytes.EMPTY) },
        )
        for (factory in factories) {
            val positive = factory(0.0)
            val negative = factory(-0.0)
            assertEquals(positive, negative)
            assertEquals(positive.hashCode(), negative.hashCode())
            assertNotEquals(factory(Double.NaN), factory(Double.NaN))
        }
    }

    @Test
    fun `PathInfo validates encoded byte widths preserves reserved null hops and rejects empty match`() {
        val path = PathInfo(Bytes.of(1, 2), 0x42u, Bytes.of(1, 2, 3, 4), 0xffu, Bytes.EMPTY)
        assertEquals(2, path.outHopCount)
        assertNull(path.inHopCount)
        assertTrue(path.matches(Bytes.of(1, 2, 3)))
        assertFalse(path.matches(Bytes.of(1)))
        val reserved = PathInfo(Bytes.EMPTY, 0xc1u, Bytes.EMPTY, 0xc0u, Bytes.EMPTY)
        assertNull(reserved.outHopCount)
        assertFalse(reserved.matches(Bytes.of(1)))
        assertFailsWith<IllegalArgumentException> {
            PathInfo(Bytes.EMPTY, 0x42u, Bytes.of(1, 2), 0u, Bytes.EMPTY)
        }
        assertFailsWith<IllegalArgumentException> {
            PathInfo(Bytes.EMPTY, 0u, Bytes.EMPTY, 0xc1u, Bytes.of(1))
        }
    }

    @Test
    fun `Trace legacy hash accessor and constructor preserve absent empty and wide hash bytes`() {
        assertNull(TraceNode(null, 0.0).hash)
        assertNull(TraceNode(Bytes.EMPTY, 0.0).hash)
        assertEquals(0xffu.toUByte(), TraceNode(Bytes.of(0xff, 0x80), 0.0).hash)
        assertEquals(Bytes.of(0xff), TraceNode.fromHash(0xffu, 0.0).hashBytes)
        assertNull(TraceNode.fromHash(null, 0.0).hashBytes)
    }

    @Test
    fun `RF metadata preserves transport flags unknown raw nibble and independent SHA prefix`() {
        assertEquals(listOf(0, 1, 2, 3), RouteType.entries.map { it.rawValue.toInt() })
        assertEquals(listOf(true, false, false, true), RouteType.entries.map { it.hasTransportCode })
        assertEquals(listOf(true, true, false, false), RouteType.entries.map { it.isFlood })
        assertEquals(PayloadType.UNKNOWN, PayloadType.fromBits(12u))
        assertEquals(PayloadType.UNKNOWN, PayloadType.fromBits(14u))
        assertEquals(PayloadType.RAW_CUSTOM, PayloadType.fromBits(15u))
        assertEquals("e3b0c44298fc1c14", ParsedRxLogData.computePacketHash(Bytes.EMPTY))
        assertEquals("ba7816bf8f01cfea", ParsedRxLogData.computePacketHash(Bytes.utf8("abc")))
        val event = ParsedRxLogData(null, null, Bytes.of(0xaa), RouteType.TC_DIRECT,
            PayloadType.UNKNOWN, 3u, 12u, Bytes.of(1), 0u, emptyList(), Bytes.utf8("abc"))
        assertEquals(12u.toUByte(), event.payloadTypeBits)
        assertEquals("ba7816bf8f01cfea", event.packetHash)
    }

    @Test
    fun `Status layout and optional room counters retain source defaults and unsigned widths`() {
        val value = statusResponse().copy(
            layout = StatusResponse.Layout.ROOM_SERVER, receiveErrors = UInt.MAX_VALUE,
            roomServerPostedCount = UShort.MAX_VALUE, roomServerPostPushCount = 1u,
        )
        assertEquals(StatusResponse.Layout.REPEATER, statusResponse().layout)
        assertNull(statusResponse().roomServerPostedCount)
        assertEquals(0u, statusResponse().receiveErrors)
        assertEquals(UInt.MAX_VALUE, value.receiveErrors)
        assertEquals(UShort.MAX_VALUE, value.roomServerPostedCount)
        assertEquals(1u.toUShort(), value.roomServerPostPushCount)
    }

    @Test
    fun `Transport errors and connection associated values have structural source equality`() {
        assertEquals(MeshTransportError.ConnectionFailed("reason"), MeshTransportError.ConnectionFailed("reason"))
        assertNotEquals(MeshTransportError.ConnectionFailed("a"), MeshTransportError.ConnectionFailed("b"))
        assertEquals(ConnectionState.Failed(MeshTransportError.NotConnected),
            ConnectionState.Failed(MeshTransportError.NotConnected))
        assertEquals(Long.MAX_VALUE, ConnectionState.Reconnecting(Long.MAX_VALUE).attempt)
    }

    @Test
    fun `Message results hold actual message payloads and all four source variants`() {
        val contact = contactMessage()
        val channel = channelMessage()
        val datagram = ChannelDatagram(1u, 0xffu, 0xffffu, Bytes.of(1), -1.0)
        assertEquals(contact, MessageResult.ContactMessage(contact).message)
        assertEquals(channel, MessageResult.ChannelMessage(channel).message)
        assertEquals(datagram, MessageResult.ChannelDatagram(datagram).datagram)
        assertEquals(MessageResult.NoMoreMessages, MessageResult.NoMoreMessages)
    }
}
