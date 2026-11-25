// PortedFrom: MeshCore/Sources/MeshCore/Protocol/PacketParser.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/Parsers+Contacts.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/Parsers+Status.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: exhaustive wire boundaries, explicit request contexts and lossless diagnostics.
package com.meshcoreone.android.core.protocol.parser

import com.meshcoreone.android.core.protocol.bytes.ByteReader
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.bytes.PacketBoundsException
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.core.protocol.lpp.LPPDecodeDiagnostic
import com.meshcoreone.android.core.protocol.lpp.LPPDecodeResult
import com.meshcoreone.android.core.protocol.lpp.LPPSensorType
import com.meshcoreone.android.core.protocol.lpp.LPPValue
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.ErrorCode
import com.meshcoreone.android.core.protocol.model.ResponseCode
import org.junit.jupiter.api.TestFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserBoundaryTest {
    private data class Route(val code: Int, val payload: Bytes, val event: String)

    private val routes = listOf(
        Route(0x00, Bytes.EMPTY, "ok"), Route(0x01, Bytes.of(255), "error"),
        Route(0x02, le(0xffffffffu), "contactsStart"), Route(0x03, contactBody(), "contact"),
        Route(0x04, le(1_704_067_200u), "contactsEnd"), Route(0x05, selfBody(), "selfInfo"),
        Route(0x06, hex("ffdeadbeefffffffff"), "messageSent"),
        Route(0x07, nodePrefix + hex("0200") + le(1_704_067_200u), "contactMessageReceived"),
        Route(0x08, hex("03ff00") + le(1_704_067_200u), "channelMessageReceived"),
        Route(0x09, le(0xffffffffu), "currentTime"), Route(0x0a, Bytes.EMPTY, "noMoreMessages"),
        Route(0x0b, hex("0080ff"), "contactURI"), Route(0x0c, hex("ffffffffffffffffffff"), "battery"),
        Route(0x0d, deviceBody(), "deviceInfo"), Route(0x0e, filled(0xaa, 65), "privateKey"),
        Route(0x0f, Bytes.of(255), "disabled"),
        Route(0x10, hex("800102") + nodePrefix + hex("ff00") + le(1_704_067_200u), "contactMessageReceived"),
        Route(0x11, hex("80123407ff00") + le(1_704_067_200u), "channelMessageReceived"),
        Route(0x12, Bytes.of(1) + Bytes.utf8("channel").paddedOrTruncated(32) + filled(0x80, 16), "channelInfo"),
        Route(0x13, hex("aaffffffff"), "signStart"), Route(0x14, filled(0x80, 37), "signature"),
        Route(0x15, Bytes.utf8("A:x,B:y"), "customVars"), Route(0x16, le(1_704_067_200u) + Bytes.of(0), "advertPathResponse"),
        Route(0x17, le(0xffffffffu) + le(0x80000000u), "tuningParamsResponse"),
        Route(0x18, Bytes.of(0) + filled(0xff, 9), "statsCore"), Route(0x19, hex("ff02"), "autoAddConfig"),
        Route(0x1a, le(0u) + le(0xffffffffu), "allowedRepeatFreq"),
        Route(0x1b, hex("ff000003ffff7f0200ff"), "channelDataReceived"),
        Route(0x1c, Bytes.utf8("region").paddedOrTruncated(31) + filled(0x80, 16), "defaultFloodScope"),
        Route(0x80, filled(0xaa, 32), "advertisement"), Route(0x81, filled(0xaa, 32), "pathUpdate"),
        Route(0x82, requestTag + le(0xffffffffu), "acknowledgement"), Route(0x83, Bytes.EMPTY, "messagesWaiting"),
        Route(0x84, hex("80ff7f00ff"), "rawData"), Route(0x85, loginBody(1u, 3u, 1_704_067_200u), "loginSuccess"),
        Route(0x86, Bytes.of(0) + nodePrefix, "loginFailed"), Route(0x87, Bytes.of(0) + nodePrefix + statusBody(), "statusResponse"),
        Route(0x88, hex("ff801500deadbeef"), "rxLogData"), Route(0x89, traceBody(Bytes.EMPTY, Bytes.EMPTY), "traceData"),
        Route(0x8a, contactBody(), "newContact"), Route(0x8b, Bytes.of(0) + nodePrefix + hex("016700fa"), "telemetryResponse"),
        Route(0x8c, Bytes.of(255) + requestTag + hex("aaff"), "binaryResponse"),
        Route(0x8d, Bytes.of(0) + nodePrefix + hex("0000"), "pathResponse"),
        Route(0x8e, hex("28ab029114") + le(100u) + hex("1122334455667788"), "discoverResponse"),
        Route(0x8f, filled(0xaa, 32), "contactDeleted"), Route(0x90, Bytes.EMPTY, "contactsFull"),
    )

    @TestFactory
    fun everyResponseCode() = routes.map { route ->
        nativeCase("route:0x${route.code.toString(16)}") {
            assertEquals(ResponseCode.entries.map { it.rawValue.toInt() }.toSet(), routes.map { it.code }.toSet())
            assertEquals(ResponseCode.entries.size, routes.size)
            assertEquals(route.event, PacketParser.parse(rawFrame(route.code, route.payload)).caseName)
        }
    }

    @TestFactory
    fun everyUnknownResponseCode() = (0..255).filter { ResponseCode.fromRawValue(it.toUByte()) == null }.map { code ->
        nativeCase("unknown-code:$code") {
            val frame = rawFrame(code, hex("deadbeef"))
            assertEquals(MeshEvent.ParseFailure(frame, "Unknown response code: 0x${code.toString(16).uppercase().padStart(2, '0')}"), PacketParser.parse(frame))
        }
    }

    @TestFactory
    fun everyMandatoryPrefix(): List<org.junit.jupiter.api.DynamicTest> {
        val minima = mapOf(
            0x02 to 4, 0x03 to 147, 0x05 to 57, 0x06 to 9, 0x07 to 12, 0x08 to 7,
            0x09 to 4, 0x0c to 2, 0x0d to 79, 0x0e to 64, 0x10 to 15, 0x11 to 10,
            0x12 to 49, 0x13 to 5, 0x16 to 5, 0x17 to 8, 0x18 to 10, 0x19 to 1,
            0x1b to 8, 0x80 to 32, 0x81 to 32, 0x82 to 4, 0x84 to 3, 0x87 to 58,
            0x89 to 12, 0x8a to 147, 0x8b to 7, 0x8c to 5, 0x8d to 9, 0x8e to 4, 0x8f to 32,
        )
        return minima.flatMap { (code, minimum) ->
            (0 until minimum).map { size ->
                nativeCase("truncated:$code:$size") {
                    val payload = if (code == 0x0d && size > 0) Bytes.of(3) + filled(size = size - 1) else filled(size = size)
                    val failureData = if (code == 0x18 && size > 0) payload.slice(1, payload.size) else payload
                    expectFailure(PacketParser.parse(rawFrame(code, payload)), failureData)
                }
            }
        }
    }

    @TestFactory
    fun contactPathEncodings() = (0..255).map { encoded ->
        nativeCase("contact-path:$encoded") {
            val body = contactBody(pathLength = encoded, path = Bytes(ByteArray(64) { it.toByte() }))
            if (encoded in 0xc0..0xfe) {
                expectFailure(Parsers.Contact.parse(body), body, "reserved path length encoding")
                assertNull(Parsers.parseContactData(body))
            } else {
                val contact = assertIs<MeshEvent.Contact>(Parsers.Contact.parse(body)).contact
                val size = if (encoded == 255) 0 else minOf(64, (encoded and 63) * ((encoded shr 6) + 1))
                assertEquals(encoded.toUByte(), contact.outPathLength); assertEquals(Bytes(ByteArray(size) { it.toByte() }), contact.outPath)
                assertEquals(encoded == 255, contact.isFloodPath)
            }
        }
    }

    @TestFactory
    fun unknownContactTypes() = (0..255).map { raw ->
        nativeCase("contact-type:$raw") {
            val contact = assertIs<MeshEvent.Contact>(Parsers.Contact.parse(contactBody(type = raw))).contact
            assertEquals(raw.toUByte(), contact.typeRawValue); assertEquals(ContactType.fromRawValue(raw.toUByte()) ?: ContactType.UNKNOWN, contact.type)
            assertEquals(contact.publicKey.hexString, contact.id)
        }
    }

    @Test
    fun sourceFailureOrderAndDataShape() {
        assertEquals(MeshEvent.ParseFailure(Bytes.EMPTY, "Empty packet"), PacketParser.parse(Bytes.EMPTY))
        val contact = contactBody(pathLength = 0xc1)
        assertEquals("Contact response uses reserved path length encoding: 0xC1", expectFailure(Parsers.Contact.parse(contact)).reason)
        assertEquals("Contact response too short: 146 < 147", expectFailure(Parsers.Contact.parse(contact.prefix(146))).reason)
        assertEquals("NewAdvertisement has public key but insufficient contact data: 147 < 147", expectFailure(Parsers.NewAdvertisement.parse(contact)).reason)
        val unknownStats = hex("fe010203")
        assertEquals(MeshEvent.ParseFailure(unknownStats, "Unknown stats type: 254"), PacketParser.parse(rawFrame(0x18, unknownStats)))
        val core = hex("000102")
        assertEquals(MeshEvent.ParseFailure(hex("0102"), "CoreStats too short: 2 < 9"), PacketParser.parse(rawFrame(0x18, core)))
        assertFailsWith<PacketBoundsException> { ByteReader(hex("010203")).readUInt32LE() }
    }

    @Test
    fun inlineUnsignedFieldsAndOptionalValues() {
        for (size in 0..3) assertNull(parsed<MeshEvent.Ok>(rawFrame(0, filled(0xff, size))).value)
        assertEquals(0xffffffffu, parsed<MeshEvent.Ok>(hex("00ffffffffaa")).value)
        assertEquals(4294967295L, parsed<MeshEvent.ContactsStart>(hex("02ffffffff")).count)
        assertEquals(Instant.ofEpochSecond(4294967295L), parsed<MeshEvent.CurrentTime>(hex("09ffffffff")).time)
        val sent = parsed<MeshEvent.MessageSent>(hex("06ffdeadbeefffffffff")).info
        assertEquals(255u.toUByte(), sent.route); assertEquals(requestTag, sent.expectedAck); assertEquals(0xffffffffu, sent.suggestedTimeoutMs)
        assertEquals(4294967295L, parsed<MeshEvent.SignStart>(hex("13aaffffffff")).maxLength)
        assertEquals("meshcore://0080ff", parsed<MeshEvent.ContactURI>(hex("0b0080ff")).uri)
        assertEquals(MeshEvent.Disabled("private_key_export_disabled"), PacketParser.parse(hex("0fffff")))
        assertEquals(filled(0xaa, 64), parsed<MeshEvent.PrivateKey>(rawFrame(0x0e, filled(0xaa, 65))).key)
        assertEquals(Bytes.EMPTY, parsed<MeshEvent.Signature>(Bytes.of(0x14)).signature)
        assertEquals(hex("00ff80"), parsed<MeshEvent.Signature>(hex("1400ff80")).signature)
        for (size in 0..7) {
            val info = parsed<MeshEvent.LoginFailed>(rawFrame(0x86, filled(0xaa, size)))
            assertEquals(if (size >= 7) filled(0xaa, 6) else null, info.publicKeyPrefix)
        }
        val now = Instant.parse("2026-10-04T01:02:03.123456789Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        for (size in 0..3) assertEquals(MeshEvent.ContactsEnd(now), PacketParser.parse(rawFrame(4, filled(0xff, size)), clock))
        assertEquals(MeshEvent.ContactsEnd(Instant.ofEpochSecond(4294967295L)), PacketParser.parse(hex("04ffffffff"), clock))
    }

    @Test
    fun batteryTrailerAndErrorCodeFamilies() {
        val simple = parsed<MeshEvent.Battery>(hex("0cffff")).info
        assertEquals(65535L, simple.level); assertNull(simple.usedStorageKB); assertNull(simple.totalStorageKB)
        for (size in 3..9) expectFailure(PacketParser.parse(rawFrame(0x0c, filled(0xff, size))), reason = "partial extended payload")
        val extended = parsed<MeshEvent.Battery>(hex("0cffffffffffffffffffffaa")).info
        assertEquals(65535L, extended.level); assertEquals(4294967295L, extended.usedStorageKB); assertEquals(4294967295L, extended.totalStorageKB)
        assertNull(parsed<MeshEvent.Error>(Bytes.of(1)).code)
        for (raw in 0..255) {
            val error = parsed<MeshEvent.Error>(Bytes.of(1, raw, 6))
            assertEquals(raw.toUByte(), error.code); assertEquals(ErrorCode.fromRawValue(raw.toUByte()), error.errorCode)
        }
        for (code in listOf(0x0a, 0x83, 0x90)) {
            assertEquals(PacketParser.parse(Bytes.of(code)), PacketParser.parse(rawFrame(code, hex("0080ff"))))
        }
    }

    @Test
    fun exactLossyAndLongestPrefixPoliciesAreDistinct() {
        val corrupt = hex("eda080")
        val contact = assertIs<MeshEvent.Contact>(Parsers.Contact.parse(contactBody(name = Bytes.utf8("good") + corrupt))).contact
        assertEquals("good", contact.advertisedName)
        assertEquals("", assertIs<MeshEvent.SelfInfo>(Parsers.SelfInfo.parse(selfBody(name = corrupt))).info.name)
        val device = deviceBody().toByteArray(); device[7] = 0xff.toByte()
        val caps = assertIs<MeshEvent.DeviceInfo>(Parsers.DeviceInfo.parse(Bytes(device))).info
        assertEquals("", caps.firmwareBuild); assertEquals("T-Deck", caps.model)
        assertEquals("a\u0000b", assertIs<MeshEvent.SelfInfo>(Parsers.SelfInfo.parse(selfBody(name = Bytes.utf8("\u0000a\u0000b\u0000")))).info.name)
        val channel = assertIs<MeshEvent.ChannelInfo>(Parsers.ChannelInfo.parse(Bytes.of(1) + corrupt.paddedOrTruncated(32) + filled(size = 16))).info
        assertEquals("\ufffd\ufffd\ufffd", channel.name)
        val scope = assertNotNull(assertIs<MeshEvent.DefaultFloodScope>(Parsers.DefaultFloodScope.parse(corrupt.paddedOrTruncated(31) + filled(0xaa, 16))).scope)
        assertEquals("\ufffd\ufffd\ufffd", scope.name); assertEquals(filled(0xaa, 16), scope.scopeKey)
        val edgeControl = "\uDB40\uDC01\u0000name\u0000\uDB40\uDC01"
        assertEquals("name", assertIs<MeshEvent.SelfInfo>(Parsers.SelfInfo.parse(selfBody(name = Bytes.utf8(edgeControl)))).info.name)
        assertEquals(emptyMap(), assertIs<MeshEvent.CustomVars>(Parsers.CustomVars.parse(corrupt)).values)
        val vars = assertIs<MeshEvent.CustomVars>(Parsers.CustomVars.parse(Bytes.utf8(",,a:1,a:2,b:,c::v,:d:v,bad,x:y:z"))).values
        assertEquals(mapOf("a" to "2", "c" to ":v", "d" to "v", "x" to "y:z"), vars)
    }

    @TestFactory
    fun scopeLengths() = (0..80).map { size ->
        nativeCase("default-scope-length:$size") {
            val payload = filled(size = size)
            if (size == 0) assertNull(parsed<MeshEvent.DefaultFloodScope>(rawFrame(0x1c, payload)).scope)
            else if (size == 47) assertNotNull(parsed<MeshEvent.DefaultFloodScope>(rawFrame(0x1c, payload)).scope)
            else assertEquals(MeshEvent.ParseFailure(payload, "DefaultFloodScope response wrong size: $size, expected 0 or 47"), PacketParser.parse(rawFrame(0x1c, payload)))
        }
    }

    @Test
    fun sourceVersionTolerancesAndRawHashModes() {
        for (version in listOf(0, 1, 2)) {
            val info = parsed<MeshEvent.DeviceInfo>(rawFrame(0x0d, Bytes.of(version, 255, 255))).info
            assertEquals(0L, info.maxContacts); assertFalse(info.clientRepeat); assertEquals(0u.toUByte(), info.pathHashMode)
        }
        for (version in listOf(3, 8, 9, 10, 255)) {
            val info = parsed<MeshEvent.DeviceInfo>(rawFrame(0x0d, deviceBody(version.toUByte(), 255u, 255u, 0xffffffffu, extensions = hex("80ff")))).info
            assertEquals(510L, info.maxContacts); assertEquals(255L, info.maxChannels); assertEquals(0xffffffffu, info.blePin)
            assertEquals(version >= 9, info.clientRepeat); assertEquals(if (version >= 10) 255 else 0, info.pathHashMode.toInt())
        }
        for (length in 7..12) {
            val info = parsed<MeshEvent.LoginSuccess>(rawFrame(0x85, Bytes.of(255) + nodePrefix + filled(size = length - 7))).info
            assertFalse(info.isAdmin); assertEquals(1u.toUByte(), info.permissions); assertNull(info.serverTime)
        }
        for (admin in listOf(0, 1, 2, 255)) for (acl in 0..255) {
            val info = parsed<MeshEvent.LoginSuccess>(rawFrame(0x85, loginBody(admin.toUByte(), acl.toUByte(), 0xffffffffu))).info
            assertEquals(admin == 1, info.isAdmin)
            assertEquals(if (admin == 1) 2 else if (acl == 2) 1 else 0, info.permissions.toInt())
            assertEquals(Instant.ofEpochSecond(4294967295L), info.serverTime)
        }
    }

    @Test
    fun messageVersionOffsetsSignaturesAndBinaryPayloads() {
        for (v3 in listOf(false, true)) {
            val header = if (v3) hex("800102") else Bytes.EMPTY
            val body = header + nodePrefix + hex("ff02") + le(0xffffffffu) + requestTag + Bytes.utf8("A\u0000B")
            val message = parsed<MeshEvent.ContactMessageReceived>(rawFrame(if (v3) 0x10 else 7, body)).message
            assertEquals(requestTag, message.signature); assertEquals("A\u0000B", message.text)
            assertEquals(nodePrefix, message.senderPublicKeyPrefix); assertEquals(if (v3) -32.0 else null, message.snr)
            assertEquals(Instant.ofEpochSecond(4294967295L), message.senderTimestamp)
        }
        val unknownText = parsed<MeshEvent.ContactMessageReceived>(rawFrame(7, nodePrefix + hex("ffff") + le(0u) + hex("deadbeef"))).message
        assertNull(unknownText.signature); assertEquals(255u.toUByte(), unknownText.textType)
        for (declared in 0..255) {
            val data = parsed<MeshEvent.ChannelDataReceived>(rawFrame(0x1b, hex("80010203ffffff") + Bytes.of(declared) + hex("0080ff"))).datagram
            assertEquals(hex("0080ff").prefix(declared), data.data); assertEquals(-32.0, data.snr); assertEquals(0xffffu.toUShort(), data.dataType)
        }
    }

    @Test
    fun independentContextFormsRetainTelemetryDiagnostics() {
        val wire = rawFrame(0x8c, Bytes.of(255) + requestTag + hex("016700fa02ff9980"))
        val binary = parsed<MeshEvent.BinaryResponse>(wire)
        assertEquals(requestTag, binary.tag); assertEquals(hex("016700fa02ff9980"), binary.data)
        val response = Parsers.TelemetryResponse.parseFromBinaryResponse(binary.data, nodePrefix)
        assertEquals(nodePrefix, response.publicKeyPrefix); assertNull(response.tag); assertEquals(binary.data, response.rawData)
        val decoded = assertIs<LPPDecodeResult.Incomplete>(response.decodeResult)
        assertEquals(4, decoded.consumedByteCount); assertEquals(hex("02ff9980"), decoded.remainingData)
        assertEquals(LPPDecodeDiagnostic.UnknownSensorType(4, 2u, 255u), decoded.diagnostic)
        assertEquals(LPPValue.Float(25.0), decoded.dataPoints.single().value)
        val push = parsed<MeshEvent.TelemetryResponse>(rawFrame(0x8b, Bytes.of(255) + nodePrefix + binary.data)).response
        assertEquals(response, push); assertIs<LPPDecodeResult.Incomplete>(push.decodeResult)
        for (type in 0..255) {
            val event = parsed<MeshEvent.BinaryResponse>(rawFrame(0x8c, Bytes.of(type) + requestTag))
            assertEquals(requestTag, event.tag); assertTrue(event.data.isEmpty)
        }
        val status = assertIs<MeshEvent.StatusResponse>(Parsers.StatusResponse.parse(filled(size = 58))).response
        assertEquals(0u, status.rxAirtime)
        val room = assertIs<MeshEvent.StatusResponse>(Parsers.StatusResponse.parse(filled(size = 58), StatusResponse.Layout.ROOM_SERVER)).response
        assertNull(room.roomServerPostedCount); assertNull(room.roomServerPostPushCount)
    }

    @Test
    fun pathFailureOrderAndReservedContextSemantics() {
        val outbound = Bytes.of(0) + nodePrefix + hex("82010203")
        assertEquals("PathDiscoveryResponse truncated outbound path: need 6 bytes, have 3", expectFailure(Parsers.PathDiscoveryResponse.parse(outbound)).reason)
        val inbound = Bytes.of(0) + nodePrefix + hex("0082010203")
        assertEquals("PathDiscoveryResponse truncated inbound path: need 6 bytes, have 3", expectFailure(Parsers.PathDiscoveryResponse.parse(inbound)).reason)
        val missingInbound = assertIs<MeshEvent.PathResponse>(Parsers.PathDiscoveryResponse.parse(Bytes.of(0) + nodePrefix + hex("021122"))).path
        assertEquals(hex("1122"), missingInbound.outPath); assertEquals(0u.toUByte(), missingInbound.inPathLength); assertTrue(missingInbound.inPath.isEmpty)
        val reserved = assertIs<MeshEvent.PathResponse>(Parsers.PathDiscoveryResponse.parse(Bytes.of(0) + nodePrefix + hex("c1ff"))).path
        assertEquals(0xc1u.toUByte(), reserved.outPathLength); assertEquals(0xffu.toUByte(), reserved.inPathLength)
        assertNull(reserved.outHopCount); assertNull(reserved.inHopCount)
        expectFailure(Parsers.AdvertPathResponse.parse(le(0u) + Bytes.of(255)), reason = "reserved path length encoding")
        expectFailure(Parsers.AdvertPathResponse.parse(le(0u) + hex("82010203")), reason = "path truncated")
    }

    @Test
    fun traceAndRadioPathsUseDifferentHashSizeEncodings() {
        for (mode in 0..3) {
            val width = 1 shl mode
            val hashes = filled(0xff, width) + filled(0x80, width)
            val trace = assertIs<MeshEvent.TraceData>(Parsers.TraceData.parse(traceBody(hashes, hex("807f"), 255, (0xfcu or mode.toUInt()).toUByte()))).trace
            assertEquals(3, trace.path.size); assertNull(trace.path[0].hashBytes); assertEquals(filled(0x80, width), trace.path[1].hashBytes)
            assertEquals(listOf(-32.0, 31.75, -0.25), trace.path.map { it.snr })
            assertEquals((0xfc or mode).toUByte(), trace.flags)
        }
        val remainder = assertIs<MeshEvent.TraceData>(Parsers.TraceData.parse(traceBody(hex("1122334455"), hex("28"), 20, 2u))).trace
        assertEquals(2, remainder.path.size); assertEquals(hex("11223344"), remainder.path[0].hashBytes); assertEquals(5.0, remainder.path[1].snr)
        expectFailure(Parsers.TraceData.parse(traceBody(hex("1122"), Bytes.EMPTY).prefix(12)), reason = "too short for path")
        for (length in listOf(0xc0, 0xc1, 0xff)) assertNull(RxLogParser.parse(null, null, Bytes.of(0x15, length)))
        for (mode in 0..2) {
            val length = mode * 64 + 63
            val path = filled(0xff, 63 * (mode + 1))
            val data = assertNotNull(RxLogParser.parse(null, null, Bytes.of(0x15, length) + path + Bytes.of(0xaa)))
            assertEquals(path.toList(), data.pathNodes); assertEquals(Bytes.of(0xaa), data.packetPayload)
            assertNull(RxLogParser.parse(null, null, Bytes.of(0x15, length) + path.prefix(path.size - 1)))
        }
    }

    @Test
    fun logFallbackRetainsEveryRawByteAndSignal() {
        for (payload in listOf(Bytes.EMPTY, Bytes.of(0xff), hex("0baabb"), hex("15c1aabb"))) {
            val info = assertIs<MeshEvent.LogData>(Parsers.LogData.parse(hex("80ff") + payload)).info
            assertEquals(-32.0, info.snr); assertEquals(-1L, info.rssi); assertEquals(payload, info.payload)
        }
        for (payload in listOf(Bytes.EMPTY, Bytes.of(0x15))) {
            val info = assertIs<MeshEvent.LogData>(Parsers.LogData.parse(payload)).info
            assertNull(info.snr); assertNull(info.rssi); assertEquals(payload, info.payload)
        }
        val rx = assertIs<MeshEvent.RxLogData>(Parsers.LogData.parse(hex("80ff1500aabb"))).data
        assertEquals(-32.0, rx.snr); assertEquals(-1L, rx.rssi); assertEquals(hex("1500aabb"), rx.rawPayload); assertEquals(hex("aabb"), rx.packetPayload)
    }

    @Test
    fun binaryListsRetainPrefixesAndTypedMalformedSuffixes() {
        val validAcl = hex("112233445566ff000000000000ff")
        val suffix = hex("aabb")
        val acl = assertIs<BinaryListDecodeResult.Incomplete<*>>(ACLParser.decode(validAcl + suffix))
        assertEquals(1, acl.entries.size); assertEquals(validAcl.size, acl.bytesConsumed); assertEquals(suffix, acl.remainingData)
        assertEquals(BinaryParseDiagnostic.TruncatedRecord(14, 7, 2), acl.diagnostic)
        assertEquals(acl.diagnostic, assertFailsWith<BinaryParseException> { acl.requireComplete() }.diagnostic)
        val validMma = hex("016700c8012c00fa")
        val unknown = assertIs<BinaryListDecodeResult.Incomplete<*>>(MMAParser.decode(validMma + hex("02ffdead")))
        assertEquals(1, unknown.entries.size); assertEquals(8, unknown.bytesConsumed); assertEquals(hex("02ffdead"), unknown.remainingData)
        assertEquals(BinaryParseDiagnostic.UnknownSensorType(8, 2u, 255u), unknown.diagnostic)
        for (type in LPPSensorType.entries) {
            for (size in 0 until type.dataSize * 3) {
                val input = validMma + Bytes.of(2, type.rawValue.toInt()) + filled(0xff, size)
                val result = assertIs<BinaryListDecodeResult.Incomplete<*>>(MMAParser.decode(input))
                assertEquals(1, result.entries.size); assertEquals(8, result.bytesConsumed); assertEquals(input.slice(8, input.size), result.remainingData)
                assertEquals(BinaryParseDiagnostic.TruncatedRecord(8, 2 + type.dataSize * 3, 2 + size), result.diagnostic)
            }
        }
        val header = assertIs<BinaryListDecodeResult.Incomplete<*>>(MMAParser.decode(validMma + Bytes.of(255)))
        assertEquals(BinaryParseDiagnostic.TruncatedRecord(8, 2, 1), header.diagnostic)
        assertEquals(validMma + Bytes.of(255), validMma.prefix(header.bytesConsumed) + header.remainingData)
        assertEquals(MMAParser.decode(validMma).entries, MMAParser.parse(validMma))
    }

    @Test
    fun neighboursContextAndFailureBoundaries() {
        for (size in 0..3) {
            val raw = filled(0xff, size); val result = NeighboursParser.decode(raw, nodePrefix, requestTag)
            assertEquals(BinaryParseDiagnostic.TruncatedRecord(0, 4, size), result.diagnostic)
            assertEquals(raw, result.remainingData); assertEquals(0, result.bytesConsumed)
            assertEquals(nodePrefix, result.response.publicKeyPrefix); assertEquals(requestTag, result.response.tag)
            assertFailsWith<BinaryParseException> { result.requireComplete() }
        }
        for (length in listOf(-1, 256, Int.MAX_VALUE)) assertFailsWith<MeshCoreException.InvalidInput> { NeighboursParser.parse(Bytes.EMPTY, nodePrefix, requestTag, length) }
        val negative = assertFailsWith<BinaryParseException> { NeighboursParser.decode(hex("0000ffff"), nodePrefix, requestTag) }
        assertEquals(BinaryParseDiagnostic.NegativeResultsCount(2, -1), negative.diagnostic)
        val raw = hex("0300020011223344ffffffff80aabb")
        val result = NeighboursParser.decode(raw, nodePrefix, requestTag)
        assertEquals(1, result.response.neighbours.size); assertEquals(-1L, result.response.neighbours[0].secondsAgo)
        assertEquals(-32.0, result.response.neighbours[0].snr); assertEquals(hex("aabb"), result.remainingData)
        assertEquals(BinaryParseDiagnostic.TruncatedRecord(13, 9, 2), result.diagnostic)
        val trailing = NeighboursParser.decode(hex("0100010011223344ffffffff80aabb"), nodePrefix, requestTag)
        assertNull(trailing.diagnostic); assertEquals(hex("aabb"), trailing.remainingData); assertEquals(1, trailing.requireComplete().neighbours.size)
    }

    @Test
    fun regionFailuresAndNativePresentationAdaptation() {
        for (size in 0..3) {
            val failure = assertFailsWith<MeshCoreException.ParseError> { RegionsParser.parse(filled(size = size)) }
            assertEquals("Region response too short ($size bytes)", failure.reason)
        }
        assertEquals("Invalid UTF-8 in region response", assertFailsWith<MeshCoreException.ParseError> { RegionsParser.parse(le(0u) + hex("eda080")) }.reason)
        assertEquals(listOf("\$private", "Europe", "Europe", "**", "UK\n"), RegionsParser.parse(le(0u) + Bytes.utf8("\u0000*, \$private,Europe,Europe,**,UK\n,\u0000")))
        val key = hex("a5f3117485052a62a83bcd690748091f")
        val scopes = mutableListOf(RegionScopeKey("region10", key), RegionScopeKey("region2", key), RegionScopeKey(" region2 ", key), RegionScopeKey("\t\n", key))
        val match = TransportCodeRegionResolver.matchRegions(scopes, 31_787u, 5u, hex("42deadbeef010203"), Locale.ROOT)
        scopes.clear()
        assertEquals(RegionMatchResult.Ambiguous(listOf("region2", "region10")), match)
        assertEquals(TransportCodeRegionResolver.deriveScopeKey("\u00a0Europe\u2028"), TransportCodeRegionResolver.deriveScopeKey("Europe"))
        assertTrue(TransportCodeRegionResolver.deriveScopeKey("Cafe\u0301") != TransportCodeRegionResolver.deriveScopeKey("Caf\u00e9"))
    }

    @Test
    fun frozenCatalogParserAndLppInputsActuallyExecute() {
        val vectors = PinnedParserVectors.values
        expectFailure(Parsers.RawData.parse(vectors.getValue("swift.raw-data-truncated")))
        assertFailsWith<MeshCoreException.ParseError> { RegionsParser.parse(vectors.getValue("swift.region-response-truncated")) }
        val expected = mapOf(
            "python.lpp_temperature_25_5" to LPPValue.Float(25.5),
            "python.lpp_humidity_65" to LPPValue.Float(65.0),
            "python.lpp_analog_3_3" to LPPValue.Float(3.3),
            "python.lpp_gps_sf" to LPPValue.Gps(37.7749, -122.4194, 10.0),
            "python.lpp_barometer_1013" to LPPValue.Float(1013.2),
            "python.lpp_accelerometer_1g" to LPPValue.Vector3(0.0, 0.0, 1.0),
            "swift.lpp-negative-int24" to LPPValue.Float(-1.5),
            "swift.lpp-high-bit-uint32" to LPPValue.Integer(2147483648L),
        )
        for ((identity, value) in expected) {
            val response = Parsers.TelemetryResponse.parseFromBinaryResponse(vectors.getValue(identity), nodePrefix)
            assertIs<LPPDecodeResult.Complete>(response.decodeResult); assertEquals(value, response.dataPoints.single().value, identity)
        }
    }
}
