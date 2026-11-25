// PortedFrom: MeshCore/Sources/MeshCore/Transport/MockTransport.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.transport.mock

import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

sealed class MockTransportException(message: String) : Exception(message) {
    class NotConnected : MockTransportException("Transport is not connected")
    class SendFailed(val reason: String) : MockTransportException(reason)
}

class MockTransport : MeshTransport {
    private val lock = Any()
    private val sent = mutableListOf<Bytes>()
    private var connected = false
    private var writeWithoutResponse = false
    private var failSendsFromIndex: Long? = null
    private var incoming = Channel<Bytes>(Channel.UNLIMITED)
    private var stream = incoming.receiveAsFlow()
    private var streamFinished = false

    val sentData: List<Bytes> get() = synchronized(lock) { sent.toList() }

    override suspend fun connect() {
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (streamFinished) {
                incoming = Channel(Channel.UNLIMITED)
                stream = incoming.receiveAsFlow()
                streamFinished = false
            }
            connected = true
        }
    }

    override suspend fun disconnect() {
        synchronized(lock) {
            connected = false
            incoming.close()
            streamFinished = true
        }
    }

    override suspend fun send(data: Bytes) {
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (!connected) throw MockTransportException.NotConnected()
            if (failSendsFromIndex?.let { sent.size.toLong() + 1 >= it } == true) {
                throw MockTransportException.SendFailed("simulated send failure")
            }
            sent += data
        }
    }

    override suspend fun isConnected(): Boolean = synchronized(lock) { connected }

    override suspend fun receivedData(): Flow<Bytes> = synchronized(lock) { stream }

    override suspend fun supportsWriteWithoutResponse(): Boolean = synchronized(lock) { writeWithoutResponse }

    suspend fun setSupportsWriteWithoutResponse(supported: Boolean) {
        synchronized(lock) { writeWithoutResponse = supported }
    }

    suspend fun failSends(fromSendIndex: Long) {
        synchronized(lock) { failSendsFromIndex = fromSendIndex }
    }

    suspend fun simulateReceive(data: Bytes) {
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (streamFinished) throw MockTransportException.NotConnected()
            incoming.trySend(data).getOrThrow()
        }
    }

    suspend fun simulateOK(value: UInt? = null) {
        val writer = ByteWriter().appendUInt8(ResponseCode.OK.rawValue)
        if (value != null) writer.appendUInt32LE(value)
        simulateReceive(writer.toBytes())
    }

    suspend fun simulateError(code: UByte) {
        simulateReceive(Bytes.of(ResponseCode.ERROR.rawValue.toInt(), code.toInt()))
    }

    suspend fun clearSentData() {
        synchronized(lock) { sent.clear() }
    }
}
