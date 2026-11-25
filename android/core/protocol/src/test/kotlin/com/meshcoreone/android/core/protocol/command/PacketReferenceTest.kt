// PortedFrom: MeshCore/Tests/MeshCoreTests/Fixtures/PythonReferenceBytes.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.command

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal object PinnedCommandSource {
    const val REVISION = "db14559b39d32322b06477c6ae676112f583db50"
    val root: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { it.resolve("MeshCore").isDirectory() && it.resolve("android").isDirectory() }

    fun read(path: String): String {
        val process = ProcessBuilder("git", "--no-pager", "-C", root.toString(), "show", "$REVISION:$path")
            .redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Pinned command source read timed out")
        assertEquals(0, process.exitValue(), "Missing original Git object $path: $text")
        return text
    }
}

class PacketReferenceTest {
    private val time = java.time.Instant.ofEpochSecond(1_704_067_200)
    private val prefix = Bytes.of(0x01, 0x23, 0x45, 0x67, 0x89, 0xab)
    private val key = prefix.paddedOrTruncated(32)

    private fun expect(name: String, actual: Bytes) = assertEquals(original.getValue(name), actual, name)

    @Test
    fun appStart() = expect("appStart", PacketBuilder.appStart())
    @Test
    fun deviceQuery() = expect("deviceQuery", PacketBuilder.deviceQuery())
    @Test
    fun getBattery() = expect("getBattery", PacketBuilder.getBattery())
    @Test
    fun getTime() = expect("getTime", PacketBuilder.getTime())
    @Test
    fun setTime() = expect("setTime_1704067200", PacketBuilder.setTime(time))
    @Test
    fun setName() = expect("setName_TestNode", PacketBuilder.setName("TestNode"))
    @Test
    fun setCoordinates() = expect("setCoords_SF", PacketBuilder.setCoordinates(37.7749, -122.4194))
    @Test
    fun setTxPower() = expect("setTxPower_20", PacketBuilder.setTxPower(20))
    @Test
    fun setRadio() = expect("setRadio_default", PacketBuilder.setRadio(906.875, 250.0, 11u, 8u))
    @Test
    fun sendAdvertisement() = expect("sendAdvertisement", PacketBuilder.sendAdvertisement())
    @Test
    fun sendAdvertisementFlood() = expect("sendAdvertisement_flood", PacketBuilder.sendAdvertisement(true))
    @Test
    fun reboot() = expect("reboot", PacketBuilder.reboot())
    @Test
    fun getContacts() = expect("getContacts", PacketBuilder.getContacts())
    @Test
    fun getMessage() = expect("getMessage", PacketBuilder.getMessage())
    @Test
    fun sendMessage() = expect("sendMessage_Hello", PacketBuilder.sendMessage(prefix, "Hello", time))
    @Test
    fun sendCommand() = expect("sendCommand_status", PacketBuilder.sendCommand(prefix, "status", time))
    @Test
    fun sendChannelMessage() = expect("sendChannelMessage_0_Hi", PacketBuilder.sendChannelMessage(0u, "Hi", time))
    @Test
    fun sendLogin() = expect("sendLogin", PacketBuilder.sendLogin(key, "secret"))
    @Test
    fun sendLogout() = expect("sendLogout", PacketBuilder.sendLogout(key))
    @Test
    fun sendStatusRequest() = expect("sendStatusRequest", PacketBuilder.sendStatusRequest(key))
    @Test
    fun getChannel() = expect("getChannel_0", PacketBuilder.getChannel(0u))
    @Test
    fun setChannel() = expect(
        "setChannel_0_General",
        PacketBuilder.setChannel(0u, "General", Bytes(ByteArray(16) { it.toByte() })),
    )
    @Test
    fun getStatsCore() = expect("getStatsCore", PacketBuilder.getStatsCore())
    @Test
    fun getStatsRadio() = expect("getStatsRadio", PacketBuilder.getStatsRadio())
    @Test
    fun getStatsPackets() = expect("getStatsPackets", PacketBuilder.getStatsPackets())
    @Test
    fun getSelfTelemetry() = expect("getSelfTelemetry", PacketBuilder.getSelfTelemetry())
    @Test
    fun exportPrivateKey() = expect("exportPrivateKey", PacketBuilder.exportPrivateKey())
    @Test
    fun signStart() = expect("signStart", PacketBuilder.signStart())
    @Test
    fun signFinish() = expect("signFinish", PacketBuilder.signFinish())
    @Test
    fun pathDiscovery() = expect("pathDiscovery", PacketBuilder.sendPathDiscovery(key))
    @Test
    fun sendTrace() = expect("sendTrace", PacketBuilder.sendTrace(12_345u, 67_890u, 0u))

    companion object {
        private val original: Map<String, Bytes> by lazy {
            val reference = PinnedCommandSource.read(
                "MeshCore/Tests/MeshCoreTests/Fixtures/PythonReferenceBytes.swift",
            )
            val original = Regex("static let ([A-Za-z0-9_]+) = Data\\(\\[(.*?)\\]\\)", RegexOption.DOT_MATCHES_ALL)
                .findAll(reference).associate { entry ->
                    val literals = entry.groupValues[2].replace(Regex("//[^\\r\\n]*"), "")
                    entry.groupValues[1] to Bytes(
                        Regex("0x([0-9a-fA-F]{2})").findAll(literals)
                            .map { it.groupValues[1].toInt(16).toByte() }.toList().toByteArray(),
                    )
                }.filterKeys { !it.startsWith("lpp_") }
            assertEquals(31, original.size, "Independent builder fixture count changed")
            original
        }
    }
}
