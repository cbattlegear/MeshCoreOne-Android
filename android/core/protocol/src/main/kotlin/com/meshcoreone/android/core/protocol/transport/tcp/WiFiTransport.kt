// PortedFrom: MeshCore/Sources/MeshCore/Transport/WiFiTransport.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// HistoricalPortedFrom: docs/guides/WiFi_Transport.md@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.transport.tcp

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class WiFiTransport(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val socketFactory: () -> Socket = { Socket() },
    private val connectionTimeoutMillis: Long = CONNECTION_TIMEOUT_MILLIS,
    private val writeTimeoutMillis: Long = WRITE_TIMEOUT_MILLIS,
    private val addressResolver: (String, Int) -> InetSocketAddress = { host, port -> InetSocketAddress(host, port) },
) : MeshTransport {
    data class ConnectionInfo(val host: String, val port: Int)

    private class DataStream {
        val channel = Channel<Bytes>(Channel.UNLIMITED)
        val flow = channel.receiveAsFlow()
        var finished = false
    }

    private class Generation(val socket: Socket, val data: DataStream, dispatcher: CoroutineDispatcher) {
        val decoder = WiFiFrameDecoder()
        val scope = CoroutineScope(Job() + dispatcher)
        var established = false
        var reader: Job? = null
        val operations = mutableSetOf<Job>()
        private var closeAttempted = false
        private var closeFailure: IOException? = null

        @Synchronized
        fun closeSocket(): IOException? {
            if (!closeAttempted) {
                closeAttempted = true
                try {
                    socket.close()
                } catch (failure: IOException) {
                    closeFailure = failure
                }
            }
            return closeFailure
        }
    }

    private val stateLock = Any()
    private val connectMutex = Mutex()
    private val writeMutex = Mutex()
    private var configured: ConnectionInfo? = null
    private var data = DataStream()
    private var active: Generation? = null
    private var disconnectVersion = 0L
    private var disconnectionHandler: ((Throwable?) -> Unit)? = null

    init {
        require(connectionTimeoutMillis in 1..Int.MAX_VALUE.toLong()) { "Invalid TCP connection timeout" }
        require(writeTimeoutMillis > 0) { "Invalid TCP write timeout" }
    }

    val connectionInfo: ConnectionInfo? get() = synchronized(stateLock) { configured }

    suspend fun setConnectionInfo(host: String, port: Int) {
        synchronized(stateLock) { configured = ConnectionInfo(host, port) }
    }

    suspend fun setDisconnectionHandler(handler: (Throwable?) -> Unit) {
        synchronized(stateLock) { disconnectionHandler = handler }
    }

    suspend fun clearDisconnectionHandler() {
        synchronized(stateLock) { disconnectionHandler = null }
    }

    override suspend fun receivedData(): Flow<Bytes> = synchronized(stateLock) { data.flow }

    override suspend fun isConnected(): Boolean = synchronized(stateLock) { active?.established == true }

    override suspend fun supportsPipelinedReads(): Boolean = true

    override suspend fun connect() {
        val requestedVersion = synchronized(stateLock) { disconnectVersion }
        connectMutex.withLock {
            currentCoroutineContext().ensureActive()
            val (generation, target) = synchronized(stateLock) {
                if (disconnectVersion != requestedVersion) {
                    throw WiFiTransportException(WiFiTransportError.ConnectionFailed("Disconnected"))
                }
                if (active?.established == true) return
                val target = configured ?: throw WiFiTransportException(WiFiTransportError.NotConfigured)
                if (target.host.isBlank()) throw WiFiTransportException(WiFiTransportError.InvalidHost)
                if (target.port !in 1..0xffff) throw WiFiTransportException(WiFiTransportError.InvalidPort)
                val socket = try {
                    socketFactory()
                } catch (failure: IOException) {
                    throw WiFiTransportException(WiFiTransportError.ConnectionFailed(reason(failure)), failure)
                }
                if (data.finished) data = DataStream()
                val generation = Generation(socket, data, ioDispatcher)
                active = generation
                generation to target
            }
            try {
                socketIo(generation, connectionTimeoutMillis, WiFiTransportError.ConnectionTimeout) {
                    generation.socket.tcpNoDelay = true
                    val endpoint = addressResolver(target.host, target.port)
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("TCP connect cancelled")
                    generation.socket.connect(endpoint, connectionTimeoutMillis.toInt())
                }
                currentCoroutineContext().ensureActive()
                synchronized(stateLock) {
                    if (active !== generation) {
                        throw WiFiTransportException(WiFiTransportError.ConnectionFailed("Disconnected"))
                    }
                    generation.established = true
                    generation.reader = generation.scope.launch(start = CoroutineStart.LAZY) {
                        receiveLoop(generation)
                    }.also { it.start() }
                }
                currentCoroutineContext().ensureActive()
            } catch (failure: CancellationException) {
                terminate(generation, failure, notify = false)
                awaitClosedGeneration(generation)
                throw failure
            } catch (failure: WiFiTransportException) {
                terminate(generation, failure, notify = false)
                awaitClosedGeneration(generation)
                throw failure
            } catch (failure: SocketTimeoutException) {
                val mapped = WiFiTransportException(WiFiTransportError.ConnectionTimeout, failure)
                terminate(generation, mapped, notify = false)
                awaitClosedGeneration(generation)
                throw mapped
            } catch (failure: IOException) {
                val failureReason = synchronized(stateLock) {
                    if (disconnectVersion != requestedVersion) "Disconnected" else reason(failure)
                }
                val mapped = WiFiTransportException(WiFiTransportError.ConnectionFailed(failureReason), failure)
                terminate(generation, mapped, notify = false)
                awaitClosedGeneration(generation)
                throw mapped
            }
        }
    }

    override suspend fun disconnect() {
        val termination = synchronized(stateLock) {
            disconnectVersion = Math.addExact(disconnectVersion, 1)
            disconnectionHandler = null
            active?.let { takeTermination(it, notify = false) }.also {
                if (it == null && !data.finished) {
                    data.finished = true
                    data.channel.close()
                }
            }
        }
        if (termination != null) {
            val failure = completeTermination(termination, null)
            awaitClosedGeneration(termination.generation)
            if (failure != null) throw failure
        }
    }

    override suspend fun send(data: Bytes) {
        val generation = synchronized(stateLock) {
            active?.takeIf { it.established }
                ?: throw WiFiTransportException(WiFiTransportError.NotConnected)
        }
        writeMutex.withLock {
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                if (active !== generation || !generation.established) {
                    throw WiFiTransportException(WiFiTransportError.NotConnected)
                }
            }
            val frame = WiFiFrameCodec.encode(data).toByteArray()
            try {
                socketIo(generation, writeTimeoutMillis, WiFiTransportError.SendTimeout) {
                    generation.socket.getOutputStream().apply {
                        write(frame)
                        flush()
                    }
                }
            } catch (failure: CancellationException) {
                terminate(generation, failure, notify = true)
                awaitClosedGeneration(generation)
                throw failure
            } catch (failure: WiFiTransportException) {
                awaitClosedGeneration(generation)
                throw failure
            } catch (failure: IOException) {
                val mapped = WiFiTransportException(WiFiTransportError.SendFailed(reason(failure)), failure)
                terminate(generation, mapped, notify = true)
                awaitClosedGeneration(generation)
                throw mapped
            }
        }
    }

    private suspend fun receiveLoop(generation: Generation) {
        val buffer = ByteArray(RECEIVE_CHUNK_SIZE)
        try {
            while (isCurrent(generation)) {
                val count = socketIo(generation, fromReader = true) { generation.socket.getInputStream().read(buffer) }
                if (count == -1) {
                    generation.decoder.finish()
                    terminate(generation, null, notify = true)
                    awaitClosedGeneration(generation, fromReader = true)
                    return
                }
                if (count !in 1..buffer.size) throw IOException("Invalid TCP read count: $count")
                val frames = generation.decoder.decode(Bytes(buffer.copyOf(count)))
                synchronized(stateLock) {
                    if (active !== generation) return
                    for (frame in frames) generation.data.channel.trySend(frame).getOrThrow()
                }
            }
        } catch (failure: CancellationException) {
            terminate(generation, failure, notify = true)
            awaitClosedGeneration(generation, fromReader = true)
            throw failure
        } catch (failure: WiFiFrameException) {
            terminate(generation, failure, notify = true)
            awaitClosedGeneration(generation, fromReader = true)
        } catch (failure: IOException) {
            terminate(generation, WiFiReceiveException(failure), notify = true)
            awaitClosedGeneration(generation, fromReader = true)
        }
    }

    private suspend fun <T : Any> socketIo(
        generation: Generation,
        timeoutMillis: Long? = null,
        timeoutError: WiFiTransportError? = null,
        fromReader: Boolean = false,
        block: () -> T,
    ): T = supervisorScope {
        val operation = async(ioDispatcher, start = CoroutineStart.LAZY) { runInterruptible { block() } }
        try {
            synchronized(stateLock) {
                if (active !== generation) {
                    operation.cancel()
                    throw SocketException("Disconnected")
                }
                generation.operations += operation
                operation.start()
            }
            if (timeoutMillis == null) {
                operation.await()
            } else {
                val result = withTimeoutOrNull(timeoutMillis) { IoResult(operation.await()) }
                if (result == null) {
                    val failure = WiFiTransportException(requireNotNull(timeoutError))
                    terminate(generation, failure, notify = generation.established)
                    operation.cancel()
                    awaitClosedGeneration(generation, fromReader)
                    throw failure
                }
                result.value
            }
        } catch (failure: CancellationException) {
            terminate(generation, failure, notify = generation.established)
            operation.cancel()
            awaitClosedGeneration(generation, fromReader)
            throw failure
        } finally {
            if (!operation.isCompleted) {
                withContext(NonCancellable) { operation.cancelAndJoin() }
            }
            synchronized(stateLock) { generation.operations -= operation }
        }
    }

    private fun isCurrent(generation: Generation): Boolean =
        synchronized(stateLock) { active === generation && generation.established }

    private fun terminate(generation: Generation, failure: Throwable?, notify: Boolean): Throwable? {
        val termination = takeTermination(generation, notify) ?: return null
        return completeTermination(termination, failure)
    }

    private fun takeTermination(generation: Generation, notify: Boolean): Termination? =
        synchronized(stateLock) {
            if (active !== generation) return null
            active = null
            generation.data.finished = true
            Termination(generation, if (notify && generation.established) disconnectionHandler else null)
        }

    private fun completeTermination(termination: Termination, failure: Throwable?): Throwable? {
        val generation = termination.generation
        val closeFailure = generation.closeSocket()
        val terminalFailure = failure ?: closeFailure?.let {
            WiFiTransportException(WiFiTransportError.ConnectionFailed("Socket close failed: ${reason(it)}"), it)
        }
        if (failure != null && closeFailure != null) failure.addSuppressed(closeFailure)
        generation.data.channel.close(terminalFailure)
        generation.scope.cancel()
        termination.handler?.invoke(terminalFailure)
        return terminalFailure
    }

    private suspend fun awaitClosedGeneration(generation: Generation, fromReader: Boolean = false) {
        withContext(NonCancellable) {
            val operations = synchronized(stateLock) { generation.operations.toList() }
            for (operation in operations) operation.join()
            // Receive-side teardown cannot join the reader that is executing it.
            if (!fromReader) generation.reader?.join()
        }
    }

    private data class Termination(val generation: Generation, val handler: ((Throwable?) -> Unit)?)

    private data class IoResult<T>(val value: T)

    companion object {
        const val CONNECTION_TIMEOUT_MILLIS = 10_000L
        const val WRITE_TIMEOUT_MILLIS = 5_000L
        const val RECEIVE_CHUNK_SIZE = 65_536

        private fun reason(failure: IOException): String =
            failure.message ?: failure.javaClass.simpleName
    }
}
