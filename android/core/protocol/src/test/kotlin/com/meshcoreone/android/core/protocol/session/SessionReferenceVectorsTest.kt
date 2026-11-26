// PortedFrom: MeshCore/Tests/MeshCoreTests/Fixtures/PythonReferenceBytes.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: immutable original packets exercise the session, not just the already-ported packer.
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PinnedCommandSource
import com.meshcoreone.android.core.protocol.event.MessageResult
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

class SessionReferenceVectorsTest {
    @TestFactory
    fun independentSessionPackets() = originals.map { (name, expected) ->
        nativeCase("immutable Python session packet $name") {
            val f = fixture(); start(f)
            val prefix = hex("0123456789ab")
            val key = prefix.paddedOrTruncated(32)
            when (name) {
                "appStart" -> assertEquals("Test", command(f, expected, selfPacket()) { f.session.sendAppStart() }.name)
                "deviceQuery" -> assertEquals(2u.toUByte(), command(f, expected, raw(0x0d, Bytes.of(2))) { f.session.queryDevice() }.firmwareVersion)
                "getBattery" -> assertEquals(4018L, command(f, expected, batteryPacket(4018)) { f.session.getBattery() }.level)
                "getTime" -> assertEquals(testEpoch, command(f, expected, raw(0x09, little32(testEpoch.epochSecond))) { f.session.getTime() })
                "setTime_1704067200" -> command(f, expected, hex("00")) { f.session.setTime(testEpoch) }
                "setName_TestNode" -> command(f, expected, hex("00")) { f.session.setName("TestNode") }
                "setCoords_SF" -> command(f, expected, hex("00")) { f.session.setCoordinates(37.7749, -122.4194) }
                "setTxPower_20" -> command(f, expected, hex("00")) { f.session.setTxPower(20) }
                "setRadio_default" -> command(f, expected, hex("00")) { f.session.setRadio(906.875, 250.0, 11u, 8u) }
                "sendAdvertisement" -> command(f, expected, hex("00")) { f.session.sendAdvertisement() }
                "sendAdvertisement_flood" -> command(f, expected, hex("00")) { f.session.sendAdvertisement(true) }
                "reboot" -> {
                    f.transport.onSend = { assertEquals(expected, it) }
                    f.session.reboot()
                    assertEquals(expected, f.transport.sent.last())
                }
                "getContacts" -> {
                    f.transport.onSend = {
                        assertEquals(expected, it)
                        f.transport.receive(contactsStart(0)); f.transport.receive(contactsEnd())
                    }
                    val result = f.session.getContactsReportingTotal()
                    assertTrue(result.contacts.isEmpty()); assertEquals(0L, result.reportedTotal)
                }
                "getMessage" -> assertEquals(MessageResult.NoMoreMessages, command(f, expected, hex("0a")) { f.session.getMessage() })
                "sendMessage_Hello" -> assertEquals(hex("aabbccdd"), command(f, expected, sentPacket()) { f.session.sendMessage(prefix, "Hello", testEpoch) }.expectedAck)
                "sendCommand_status" -> assertEquals(hex("aabbccdd"), command(f, expected, sentPacket()) { f.session.sendCommand(prefix, "status", testEpoch) }.expectedAck)
                "sendChannelMessage_0_Hi" -> command(f, expected, hex("00")) { f.session.sendChannelMessage(0u, "Hi", testEpoch) }
                "sendLogin" -> command(f, expected, sentPacket()) { f.session.sendLogin(key, "secret") }
                "sendLogout" -> command(f, expected, hex("00")) { f.session.sendLogout(key) }
                "sendStatusRequest" -> command(f, expected, sentPacket()) { f.session.sendStatusRequest(key) }
                "getChannel_0" -> assertEquals("zero", command(f, expected, channelPacket(0, "zero")) { f.session.getChannel(0u) }.name)
                "setChannel_0_General" -> command(f, expected, hex("00")) { f.session.setChannel(0u, "General", Bytes(ByteArray(16) { it.toByte() })) }
                "getStatsCore" -> command(f, expected, raw(0x18, hex("00") + little16(4018) + little32(123) + little16(2) + hex("00"))) { f.session.getStatsCore() }
                "getStatsRadio" -> command(f, expected, raw(0x18, hex("01") + little16(-110) + hex("ab28") + little32(1) + little32(2))) { f.session.getStatsRadio() }
                "getStatsPackets" -> command(f, expected, raw(0x18, hex("02") + filled(0, 24))) { f.session.getStatsPackets() }
                "getSelfTelemetry" -> assertEquals(filled(1, 6), command(f, expected, telemetryPacket(filled(1, 6), hex("016700f0"))) { f.session.getSelfTelemetry() }.publicKeyPrefix)
                "exportPrivateKey" -> assertEquals(filled(0x22, 64), command(f, expected, raw(0x0e, filled(0x22, 64))) { f.session.exportPrivateKey() })
                "signStart" -> assertEquals(120L, command(f, expected, raw(0x13, hex("00") + little32(120))) { f.session.signStart() })
                "signFinish" -> assertEquals(rfcSignature, command(f, expected, raw(0x14, rfcSignature)) { f.session.signFinish() })
                "pathDiscovery" -> command(f, expected, sentPacket()) { f.session.sendPathDiscovery(key) }
                "sendTrace" -> command(f, expected + hex("11"), sentPacket()) { f.session.sendTrace(12_345u, 67_890u, 0u, hex("11")) }
                else -> fail("Unmapped immutable Python fixture $name")
            }
            f.session.stop()
        }
    }

    companion object {
        internal val rfcSignature = hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555f" +
                "b8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        )
        internal val rfcPublicKey = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        private val originals: Map<String, Bytes> by lazy {
            val source = PinnedCommandSource.read("MeshCore/Tests/MeshCoreTests/Fixtures/PythonReferenceBytes.swift")
            val vectors = Regex("static let ([A-Za-z0-9_]+) = Data\\(\\[(.*?)\\]\\)", RegexOption.DOT_MATCHES_ALL)
                .findAll(source).associate { entry ->
                    val data = entry.groupValues[2].replace(Regex("//[^\\r\\n]*"), "")
                    entry.groupValues[1] to Bytes(Regex("0x([0-9a-fA-F]{2})").findAll(data)
                        .map { it.groupValues[1].toInt(16).toByte() }.toList().toByteArray())
                }.filterKeys { !it.startsWith("lpp_") }
            assertEquals(31, vectors.size)
            vectors
        }
    }
}
