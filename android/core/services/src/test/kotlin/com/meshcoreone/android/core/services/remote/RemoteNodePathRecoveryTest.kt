// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/RemoteNodePathRecoveryTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RoomPermissionLevel
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.core.protocol.model.ContactType
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Binary admin (status / telemetry / owner info) never resets the contact path on a mesh timeout;
 * only CLI uses `performWithDirectPathFloodRecovery`.
 */
class RemoteNodePathRecoveryTest {
    private val publicKey = remoteCoreKey(0xCD)
    private val directPath = Bytes.of(0x0A, 0x0B, 0x0C)

    private fun case(
        name: String, outPathLength: UByte = 3u, outPath: Bytes = directPath,
        body: suspend RemoteCoreHarness.(EntityKey) -> Unit,
    ): DynamicTest = remoteCoreOriginal("RemoteNodePathRecoveryTests", name) {
        withRemoteCoreHarness {
            store.saveContact(remoteCoreContact(radioId, publicKey, ContactType.REPEATER.rawValue, outPathLength, outPath))
            val key = addSession(
                remoteCoreSession(radioId, publicKey, RemoteNodeRole.REPEATER, RoomPermissionLevel.ADMIN),
            )
            body(key)
        }
    }

    private fun statusResponse() = StatusResponse(
        publicKeyPrefix = publicKey.prefix(6), battery = 4100, txQueueLength = 0, noiseFloor = -100, lastRSSI = -70,
        packetsReceived = 10u, packetsSent = 5u, airtime = 1u, uptime = 100u, sentFlood = 0u, sentDirect = 0u,
        receivedFlood = 0u, receivedDirect = 0u, fullEvents = 0, lastSNR = 9.0, directDuplicates = 0,
        floodDuplicates = 0, rxAirtime = 1u, receiveErrors = 0u,
    )

    @TestFactory
    fun sourceCases(): List<DynamicTest> = listOf(
        case("status timeout does not reset the path") { key ->
            session.requestStatusResult = Result.failure(MeshCoreException.Timeout())
            assertFailsWith<RemoteNodeError> { service.requestStatus(key, 5.seconds) }
            assertTrue(session.resetPathPublicKeys.isEmpty())
            assertEquals(false, store.contact(radioId, publicKey)?.isFloodRouted)
        },
        case("repeated status timeouts still do not reset the path") { key ->
            session.setRequestStatusResults(List(3) { Result.failure(MeshCoreException.Timeout()) })
            repeat(3) { assertFailsWith<RemoteNodeError> { service.requestStatus(key, 5.seconds) } }
            assertTrue(session.resetPathPublicKeys.isEmpty())
        },
        case("status success does not reset the path") { key ->
            session.requestStatusResult = Result.success(statusResponse())
            assertEquals(4100L, service.requestStatus(key, 5.seconds).battery)
            assertTrue(session.resetPathPublicKeys.isEmpty())
        },
        case("status timeout maps to RemoteNodeError timeout", outPathLength = 2u) { key ->
            session.requestStatusResult = Result.failure(MeshCoreException.Timeout())
            assertFailsWith<RemoteNodeError.Timeout> { service.requestStatus(key, 5.seconds) }
            assertTrue(session.resetPathPublicKeys.isEmpty())
        },
        case("telemetry timeout does not reset the path", outPathLength = 2u) { key ->
            session.setRequestTelemetryResults(listOf(Result.failure(MeshCoreException.Timeout())))
            assertFailsWith<RemoteNodeError> { service.requestTelemetry(key, 5.seconds) }
            assertTrue(session.resetPathPublicKeys.isEmpty())
        },
        case("owner info timeout does not reset the path", outPathLength = 1u, outPath = Bytes.of(0xFF)) { key ->
            session.setRequestOwnerInfoResults(listOf(Result.failure(MeshCoreException.Timeout())))
            assertFailsWith<RemoteNodeError> { service.requestOwnerInfo(key, 5.seconds) }
            assertTrue(session.resetPathPublicKeys.isEmpty())
        },
    )
}
