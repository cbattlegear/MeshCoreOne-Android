// PortedFrom: MC1/Services/LinkPreviewService+Scrape.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Services/RedirectSafetyDelegate.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Utilities/URLSafetyChecker.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.content

import com.meshcoreone.android.core.services.content.BoundedHttpFetching
import com.meshcoreone.android.core.services.content.HttpFetchAttempt
import com.meshcoreone.android.core.services.content.UrlSafetyChecker
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

class OkHttpContentFetching internal constructor(
    private val lookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val addressSafe: (String, InetAddress) -> Boolean = { _, address ->
        !UrlSafetyChecker.isPrivateOrReserved(address.hostAddress.orEmpty())
    },
) : BoundedHttpFetching, AutoCloseable {
    private val dispatcher = Dispatcher()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val closed = AtomicBoolean()
    private val template = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .proxy(Proxy.NO_PROXY)
        .build()

    override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt {
        require(timeoutMs > 0) { "HTTP timeout must be positive" }
        if (closed.get()) return HttpFetchAttempt.Failed("HTTP producer is closed")
        val initial = url.toHttpUrlOrNull() ?: return HttpFetchAttempt.Failed("Invalid HTTP URL")
        if (initial.username.isNotEmpty() || initial.password.isNotEmpty()) {
            return HttpFetchAttempt.Failed("Credential-bearing preview URLs are not allowed")
        }
        return try {
            withTimeout(timeoutMs) {
                var target = initial
                repeat(MAX_REDIRECTS + 1) { hop ->
                    val dns = SnapshotDns(target.host, lookup, addressSafe)
                    val pool = ConnectionPool(0, 1, TimeUnit.SECONDS)
                    val client = template.newBuilder()
                        .dns(dns)
                        .socketFactory(RouteSocketFactory(dns))
                        .connectionPool(pool)
                        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .build()
                    val request = Request.Builder().url(target).header("User-Agent", "MC1LinkPreview/1.0").apply {
                        if (rangeHeader != null) header("Range", rangeHeader)
                    }.build()
                    val call = client.newCall(request)
                    calls.add(call)
                    val response = try {
                        awaitResponse(call)
                    } catch (error: Throwable) {
                        call.cancel()
                        calls.remove(call)
                        pool.evictAll()
                        throw error
                    }
                    if (response.code in REDIRECT_CODES) {
                        val next = response.header("Location")?.let(target::resolve)
                        response.close()
                        calls.remove(call)
                        pool.evictAll()
                        if (next == null || hop == MAX_REDIRECTS ||
                            next.username.isNotEmpty() || next.password.isNotEmpty()) {
                            return@withTimeout HttpFetchAttempt.Failed("Invalid or excessive HTTP redirect")
                        }
                        target = next
                    } else {
                        val body = response.body
                        val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }
                        return@withTimeout HttpFetchAttempt.Started(
                            response.code,
                            mime,
                            body.contentLength().takeIf { it >= 0 },
                            closeResponse = {
                                call.cancel()
                                response.close()
                                calls.remove(call)
                                pool.evictAll()
                            },
                            chunks = { receive -> stream(response, call, receive) },
                        )
                    }
                }
                HttpFetchAttempt.Failed("HTTP redirect limit exceeded")
            }
        } catch (_: TimeoutCancellationException) {
            HttpFetchAttempt.Failed("HTTP deadline exceeded")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            HttpFetchAttempt.Failed("HTTP network, route safety or TLS failure")
        }
    }

    private suspend fun awaitResponse(call: Call): Response = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean()
        continuation.invokeOnCancellation {
            completed.set(true)
            call.cancel()
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (completed.compareAndSet(false, true)) continuation.resumeWith(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                if (completed.compareAndSet(false, true)) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                } else {
                    response.close()
                }
            }
        })
    }

    private suspend fun stream(
        response: Response,
        call: Call,
        receive: suspend (ByteArray) -> Boolean,
    ) = coroutineScope {
        val reader = async(Dispatchers.IO) {
            val source = response.body.source()
            val buffer = Buffer()
            while (true) {
                ensureActive()
                val count = try {
                    source.read(buffer, CHUNK_BYTES)
                } catch (error: IOException) {
                    ensureActive()
                    throw error
                }
                if (count == -1L) break
                if (!receive(buffer.readByteArray(count))) break
            }
        }
        try {
            reader.await()
        } catch (cancellation: CancellationException) {
            // Interrupt the socket read first. Response.close can itself read the body and
            // must wait until coroutineScope has joined the reader.
            call.cancel()
            throw cancellation
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            calls.forEach(Call::cancel)
            calls.clear()
            dispatcher.executorService.shutdownNow()
        }
    }

    private class SnapshotDns(
        private val host: String,
        private val resolve: (String) -> List<InetAddress>,
        private val safe: (String, InetAddress) -> Boolean,
    ) : Dns {
        @Volatile private var snapshot: List<InetAddress>? = null

        @Synchronized
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname != host) throw UnknownHostException("Unexpected preview route host")
            snapshot?.let { return it }
            val addresses = resolve(host).toList()
            if (addresses.isEmpty() || addresses.any { !safe(host, it) }) {
                throw UnknownHostException("Unsafe or empty preview DNS result")
            }
            snapshot = addresses
            return addresses
        }

        fun authorize(endpoint: SocketAddress) {
            val address = (endpoint as? InetSocketAddress)?.address
                ?: throw IOException("Unresolved or unsupported preview socket route")
            if (address !in lookup(host) || !safe(host, address)) {
                throw IOException("Preview socket route differs from its validated DNS snapshot")
            }
        }
    }

    private class RouteSocketFactory(private val dns: SnapshotDns) : SocketFactory() {
        private fun socket() = object : Socket() {
            override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                dns.authorize(endpoint)
                super.connect(endpoint, timeout)
            }
        }

        override fun createSocket(): Socket = socket()
        override fun createSocket(host: String, port: Int): Socket =
            socket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
            socket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress, port: Int): Socket =
            socket().apply { connect(InetSocketAddress(address, port)) }
        override fun createSocket(address: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            socket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(address, port)) }
    }

    private companion object {
        const val MAX_REDIRECTS = 20
        const val CHUNK_BYTES = 8192L
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
