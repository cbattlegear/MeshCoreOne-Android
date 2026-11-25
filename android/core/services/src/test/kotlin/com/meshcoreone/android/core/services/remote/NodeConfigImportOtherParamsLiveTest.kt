// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/NodeConfigServiceTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.model.AdvertLocationPolicy
import com.meshcoreone.android.core.model.MeshCoreNodeConfig
import com.meshcoreone.android.core.model.TelemetryModes
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.TestFactory

/**
 * Drives `importOtherParams` over a live MockTransport-backed MeshCoreSession. Swift routes through
 * `SettingsService`; [SessionSettings] reproduces the two SettingsService calls involved
 * (`getSelfInfo` = appStart, `setOtherParams` = `session.setOtherParams` with `!autoAddContacts`).
 */
class NodeConfigImportOtherParamsLiveTest {
    @TestFactory
    fun sourceCases() = nodeConfigSourceCases(
        "NodeConfigImportOtherParamsLiveTests",
        "importOtherParams forwards an unmodeled advert_loc_policy byte to the device verbatim" to {
            val unmodeledAdvertPolicyByte: UByte = 99u
            assertNull(AdvertLocationPolicy.fromRawValue(unmodeledAdvertPolicyByte)) // confirms 99 is unmodeled
            withStartedSession { transport, service ->
                val beforeImport = transport.sentData.size
                coroutineScope {
                    val importTask = async(Dispatchers.Default) {
                        service.importOtherParams(MeshCoreNodeConfig.OtherSettings(manualAddContacts = 0u, advertLocationPolicy = unmodeledAdvertPolicyByte))
                    }
                    nodeConfigWaitUntil("importOtherParams should send getSelfInfo appStart") { transport.sentData.size == beforeImport + 1 }
                    transport.simulateReceive(selfInfoPacket())
                    nodeConfigWaitUntil("importOtherParams should send setOtherParams") { transport.sentData.size == beforeImport + 2 }
                    transport.simulateOK()
                    importTask.await()
                }
                val advertLocationPolicyByteIndex = 3 // [0]=cmd,[1]=manualAdd,[2]=telemetry,[3]=policy,[4]=multiAcks
                val otherParamsPacket = assertNotNull(transport.sentData.firstOrNull { it[0] == CommandCode.SET_OTHER_PARAMS.rawValue })
                assertEquals(unmodeledAdvertPolicyByte, otherParamsPacket[advertLocationPolicyByteIndex])
            }
        },
        "importOtherParams whose merge matches the device sends no setOtherParams commit" to {
            withStartedSession { transport, service ->
                val beforeImport = transport.sentData.size
                coroutineScope {
                    // All-nil import: every merged field falls back to the device value, so the commit is elided.
                    val importTask = async(Dispatchers.Default) { service.importOtherParams(MeshCoreNodeConfig.OtherSettings()) }
                    nodeConfigWaitUntil("importOtherParams should fetch getSelfInfo") { transport.sentData.size == beforeImport + 1 }
                    transport.simulateReceive(selfInfoPacket())
                    importTask.await()
                }
                assertFalse(
                    transport.sentData.any { it[0] == CommandCode.SET_OTHER_PARAMS.rawValue },
                    "A no-op merge must not commit /new_prefs",
                )
            }
        },
    )

    private suspend fun withStartedSession(block: suspend (MockTransport, NodeConfigService) -> Unit) {
        val transport = MockTransport()
        val session = MeshCoreSession(transport)
        try {
            coroutineScope {
                val startTask = async(Dispatchers.Default) { session.start() }
                nodeConfigWaitUntil("session should send app start") { transport.sentData.size == 1 }
                transport.simulateReceive(selfInfoPacket())
                startTask.await()
            }
            val service = NodeConfigService(
                session, SessionSettings(session), { _, _, _, _ -> }, NodeConfigContactSaver { _, _ -> }, null, NodeConfigLogger.NONE,
            )
            block(transport, service)
        } finally {
            session.stop()
        }
    }

    /**
     * Self-info response. The Swift fixture omits the manual-add byte (three option bytes before the
     * frequency); this one is laid out per the parser (multiAcks, policy, telemetry, manualAdd).
     */
    private fun selfInfoPacket(): Bytes = ByteWriter().appendUInt8(ResponseCode.SELF_INFO.rawValue)
        .appendUInt8(1u).appendUInt8(22u).appendUInt8(22u).append(nodeConfigBytes(0x01, 32))
        .appendInt32LE(0).appendInt32LE(0)
        .appendUInt8(0u).appendUInt8(0u).appendUInt8(0u).appendUInt8(0u)
        .appendUInt32LE(915_000u).appendUInt32LE(125_000u).appendUInt8(7u).appendUInt8(5u)
        .append(Bytes.utf8("Test")).toBytes()

    private class SessionSettings(private val session: MeshCoreSession) : NodeConfigSettingsPort {
        override suspend fun getSelfInfo(): SelfInfo = session.sendAppStart()
        override suspend fun queryDevice(): DeviceCapabilities = session.queryDevice()
        override suspend fun exportPrivateKey(): Bytes = session.exportPrivateKey()
        override suspend fun importPrivateKey(key: Bytes) = session.importPrivateKey(key)
        override suspend fun setNodeName(name: String) = session.setName(name)
        override suspend fun setLocation(latitude: Double, longitude: Double) = session.setCoordinates(latitude, longitude)
        override suspend fun setRadioParams(frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte, codingRate: UByte) =
            session.setRadio(frequencyKHz.toDouble() / 1000.0, bandwidthKHz.toDouble() / 1000.0, spreadingFactor, codingRate)
        override suspend fun setTxPower(power: Byte) = session.setTxPower(power)
        override suspend fun setOtherParams(autoAddContacts: Boolean, telemetryModes: TelemetryModes, advertLocationPolicyRaw: UByte, multiAcks: UByte) =
            session.setOtherParams(
                !autoAddContacts, telemetryModes.environment, telemetryModes.location, telemetryModes.base, advertLocationPolicyRaw, multiAcks,
            )
    }
}
