// PortedFrom: MeshCore/Sources/MeshCore/Transport/MeshTransport.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.transport

import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlinx.coroutines.flow.Flow

interface MeshTransport {
    suspend fun connect()
    suspend fun disconnect()
    suspend fun send(data: Bytes)
    suspend fun sendWithoutResponse(data: Bytes) = send(data)
    suspend fun supportsWriteWithoutResponse(): Boolean = false
    suspend fun supportsPipelinedReads(): Boolean = supportsWriteWithoutResponse()
    suspend fun receivedData(): Flow<Bytes>
    suspend fun isConnected(): Boolean
}
