// PortedFrom: MC1Services/Sources/MC1Services/Simulator/SimulatorMockTransport.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MeshTransportError
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Mock transport for simulator connections: a minimal stub that fulfills [MeshTransport] but never
 * communicates with a device. Sends are accepted and dropped while connected; nothing is ever received.
 *
 * It implements [MeshTransport] directly rather than reusing the protocol module's test `MockTransport`,
 * because Swift's stub differs from it in two observable ways that are kept here: a send while disconnected
 * fails with [MeshTransportError.NotConnected] (not `MockTransportException`), and the received stream is
 * created once and finished for good by the first [disconnect] (a later [connect] does not reopen it).
 */
class SimulatorMockTransport : MeshTransport {
    private val lock = Any()
    private var connected = false

    // Never sent to: the stream is always empty and completes when the transport is first disconnected.
    private val incoming = Channel<Bytes>(Channel.RENDEZVOUS)
    private val stream: Flow<Bytes> = incoming.receiveAsFlow()

    override suspend fun isConnected(): Boolean = synchronized(lock) { connected }

    /** Stream of received data (always empty for the simulator). */
    override suspend fun receivedData(): Flow<Bytes> = stream

    override suspend fun connect() {
        synchronized(lock) { connected = true }
    }

    override suspend fun disconnect() {
        synchronized(lock) {
            incoming.close()
            connected = false
        }
    }

    override suspend fun send(data: Bytes) {
        synchronized(lock) {
            if (!connected) throw MeshTransportError.NotConnected
        }
        // Simulator transport doesn't actually send data.
    }
}
