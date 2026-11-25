// PortedFrom: MeshCore/Tests/MeshCoreTests/Validation/PythonReferenceTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Tests/MeshCoreTests/Fixtures/PythonReferenceBytes.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: consume the complete immutable WP-004 catalog without depending on core:testing.
package com.meshcoreone.android.core.protocol.parser

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.command.PinnedCommandSource
import org.junit.jupiter.api.TestFactory
import kotlin.io.path.readLines
import kotlin.test.assertEquals

internal object PinnedParserVectors {
    val values: Map<String, Bytes> by lazy {
        val rows = PinnedCommandSource.root.resolve("android").resolve("core").resolve("testing")
            .resolve("fixtures").resolve("protocol-vectors.tsv").readLines(Charsets.UTF_8)
            .filterNot { it.startsWith("#") }.drop(1).filter { it.isNotEmpty() }.map { it.split('\t') }
        assertEquals(49, rows.size, "The complete pinned catalog must be present")
        assertEquals(49, rows.map { it[0] }.toSet().size)
        rows.associate { row ->
            val bytes = hex(row[3])
            assertEquals(row[4].toInt(), bytes.size, row[0])
            row[0] to bytes
        }
    }
}

class OriginalPythonReferenceCasesTest {
    private val key = hex("0123456789ab").paddedOrTruncated(32)
    private fun expect(name: String, actual: Bytes) = assertEquals(PinnedParserVectors.values.getValue("python.$name"), actual)
    private fun reference(name: String, assertions: () -> Unit) = original("PythonReferenceTests", "$name matches Python", assertions)

    @TestFactory
    fun originalReferenceCases() = listOf(
        reference("appStart") { expect("appStart", PacketBuilder.appStart("MCore")) },
        reference("deviceQuery") { expect("deviceQuery", PacketBuilder.deviceQuery()) },
        reference("getBattery") { expect("getBattery", PacketBuilder.getBattery()) },
        reference("getTime") { expect("getTime", PacketBuilder.getTime()) },
        reference("setTime") { expect("setTime_1704067200", PacketBuilder.setTime(epoch2024)) },
        reference("setName") { expect("setName_TestNode", PacketBuilder.setName("TestNode")) },
        reference("setCoordinates") { expect("setCoords_SF", PacketBuilder.setCoordinates(37.7749, -122.4194)) },
        reference("setTxPower") { expect("setTxPower_20", PacketBuilder.setTxPower(20)) },
        reference("setRadio") { expect("setRadio_default", PacketBuilder.setRadio(906.875, 250.0, 11u, 8u)) },
        reference("sendAdvertisement") {
            expect("sendAdvertisement", PacketBuilder.sendAdvertisement(false)); expect("sendAdvertisement_flood", PacketBuilder.sendAdvertisement(true))
        },
        reference("reboot") { expect("reboot", PacketBuilder.reboot()) },
        reference("getContacts") { expect("getContacts", PacketBuilder.getContacts()) },
        reference("getMessage") { expect("getMessage", PacketBuilder.getMessage()) },
        reference("sendMessage") { expect("sendMessage_Hello", PacketBuilder.sendMessage(key.prefix(6), "Hello", epoch2024, 0u)) },
        reference("sendCommand") { expect("sendCommand_status", PacketBuilder.sendCommand(key.prefix(6), "status", epoch2024)) },
        reference("sendChannelMessage") { expect("sendChannelMessage_0_Hi", PacketBuilder.sendChannelMessage(0u, "Hi", epoch2024)) },
        reference("sendLogin") { expect("sendLogin", PacketBuilder.sendLogin(key, "secret")) },
        reference("sendLogout") { expect("sendLogout", PacketBuilder.sendLogout(key)) },
        reference("sendStatusRequest") { expect("sendStatusRequest", PacketBuilder.sendStatusRequest(key)) },
        reference("getChannel") { expect("getChannel_0", PacketBuilder.getChannel(0u)) },
        reference("setChannel") { expect("setChannel_0_General", PacketBuilder.setChannel(0u, "General", Bytes(ByteArray(16) { it.toByte() }))) },
        reference("getStats") {
            expect("getStatsCore", PacketBuilder.getStatsCore()); expect("getStatsRadio", PacketBuilder.getStatsRadio()); expect("getStatsPackets", PacketBuilder.getStatsPackets())
        },
        reference("getSelfTelemetry") { expect("getSelfTelemetry", PacketBuilder.getSelfTelemetry()) },
        reference("exportPrivateKey") { expect("exportPrivateKey", PacketBuilder.exportPrivateKey()) },
        reference("signStart") { expect("signStart", PacketBuilder.signStart()) },
        reference("signFinish") { expect("signFinish", PacketBuilder.signFinish()) },
        reference("pathDiscovery") { expect("pathDiscovery", PacketBuilder.sendPathDiscovery(key)) },
        reference("sendTrace") { expect("sendTrace", PacketBuilder.sendTrace(12_345u, 67_890u, 0u)) },
    )
}
