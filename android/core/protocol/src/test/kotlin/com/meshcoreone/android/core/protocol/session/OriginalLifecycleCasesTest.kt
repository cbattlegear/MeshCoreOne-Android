// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/ConnectionStateTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/BinaryRequestTimeoutTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/MeshCoreSessionStopTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OriginalLifecycleCasesTest {
    @TestFactory
    fun originalCases() = listOf(
        original("ConnectionStateTests", "initial start emits connecting then connected") {
            val f = fixture()
            val states = mutableListOf<ConnectionState>()
            val stream = f.session.connectionState
            val listener = backgroundScope.launch { stream.collect { states += it } }
            runCurrent()
            assertEquals(listOf<ConnectionState>(ConnectionState.Disconnected), states)
            start(f)
            assertEquals(listOf(ConnectionState.Disconnected, ConnectionState.Connecting, ConnectionState.Connected), states)
            f.session.stop()
            runCurrent()
            assertEquals(ConnectionState.Disconnected, states.last())
            assertTrue(listener.isCompleted)
            assertEquals(0, f.transport.activeCollectors)
        },
        original("ConnectionStateTests", "reconnect start emits reconnecting then connected") {
            val f = fixture()
            val states = mutableListOf<ConnectionState>()
            backgroundScope.launch { f.session.connectionState.collect { states += it } }
            runCurrent()
            val task = checkedRequest { f.session.start(reconnectingAttempt = 1) }
            runCurrent()
            f.transport.receive(selfPacket())
            runCurrent()
            task.await()
            assertEquals(listOf(ConnectionState.Disconnected, ConnectionState.Reconnecting(1), ConnectionState.Connected), states)
            f.session.stop()
        },
        original("ConnectionStateTests", "failed appStart unwinds start so it can be retried") {
            val f = fixture(SessionConfiguration(defaultTimeout = 0.05, clientIdentifier = "MCore"))
            val states = mutableListOf<ConnectionState>()
            backgroundScope.launch { f.session.connectionState.collect { states += it } }
            runCurrent()
            repeat(2) {
                val startup = checkedRequest { f.session.start() }
                runCurrent()
                advanceTimeBy(50)
                runCurrent()
                assertFailsWith<MeshCoreException.Timeout> { startup.await() }
                assertIs<ConnectionState.Failed>(states.last())
                assertNull(f.session.currentSelfInfo)
            }
            assertEquals(2, f.transport.sent.size)
            assertEquals(2, f.transport.disconnects)
            f.session.stop()
        },
        original("ConnectionStateTests", "getContact rejects short public key before sending") {
            val f = fixture()
            assertFailsWith<MeshCoreException.InvalidInput> { f.session.getContact(filled(0xaa, 31)) }
            assertTrue(f.transport.sent.isEmpty())
            f.session.stop()
        },
        original("ConnectionStateTests", "requestStatus rejects short public key before sending") {
            val f = fixture()
            assertFailsWith<MeshCoreException.InvalidInput> { f.session.requestStatus(filled(0xbb, 31)) }
            assertTrue(f.transport.sent.isEmpty())
            f.session.stop()
        },
        original("ConnectionStateTests", "setPathHashMode rejects reserved mode before sending") {
            val f = fixture()
            assertFailsWith<MeshCoreException.InvalidInput> { f.session.setPathHashMode(3u) }
            assertTrue(f.transport.sent.isEmpty())
            f.session.stop()
        },
        original("BinaryRequestTimeoutTests", "defaults use a 40 second overall budget and 1 second retransmit floor") {
            val configuration = SessionConfiguration()
            assertEquals(40.0, configuration.binaryRequestOverallTimeout)
            assertEquals(1.0, configuration.binaryRequestRetransmitInterval)
            assertEquals(2.0, SessionConfiguration.BINARY_RETRANSMIT_RTT_HEADROOM)
        },
        original("BinaryRequestTimeoutTests", "configuration accepts custom overall and nil retransmit disables in exchange resends") {
            val configuration = SessionConfiguration(binaryRequestOverallTimeout = 0.05, binaryRequestRetransmitInterval = null)
            assertEquals(0.05, configuration.binaryRequestOverallTimeout)
            assertNull(configuration.binaryRequestRetransmitInterval)
            val f = fixture(configuration.copy(clientIdentifier = "MCore"))
            start(f)
            val task = checkedRequest { f.session.requestStatus(filled(0x31, 32)) }
            runCurrent()
            f.transport.receive(sentPacket(timeoutMs = 1))
            runCurrent()
            advanceTimeBy(50)
            runCurrent()
            assertFailsWith<MeshCoreException.Timeout> { task.await() }
            assertEquals(2, f.transport.sent.size)
            f.session.stop()
        },
        original("MeshCoreSessionStopTests", "stop with disconnectTransport false leaves the transport connected") {
            val f = fixture()
            f.transport.connect()
            f.session.stop(disconnectTransport = false)
            assertEquals(0, f.transport.disconnects)
            assertTrue(f.transport.isConnected())
            f.transport.disconnect()
        },
        original("MeshCoreSessionStopTests", "stop by default disconnects the transport") {
            val f = fixture()
            f.transport.connect()
            f.session.stop()
            assertEquals(1, f.transport.disconnects)
            assertFalse(f.transport.isConnected())
            f.session.stop()
            assertEquals(1, f.transport.disconnects)
        },
    )
}
