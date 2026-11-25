// PortedFrom: MC1/Services/LinkPreviewService+Scrape.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.content

import android.os.Build
import com.meshcoreone.android.core.services.content.HttpFetchAttempt
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class OkHttpDefaultTlsTest {
    private val host = "preview-fixture.example"
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private class CertificateFixture(host: String) : AutoCloseable {
        private val directory = Files.createTempDirectory("wp218-disposable-tls-")
        val store: Path = directory.resolve("fixture.p12")
        private val log = directory.resolve("keytool.log")
        val password = "disposable-test-only"
        val keys: KeyStore
        val serverContext: SSLContext

        init {
            try {
                val process = ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                    "-genkeypair", "-alias", "fixture", "-keyalg", "EC", "-groupname", "secp256r1",
                    "-dname", "CN=$host", "-ext", "SAN=dns:$host", "-validity", "2",
                    "-storetype", "PKCS12", "-keystore", store.toString(),
                    "-storepass", password, "-keypass", password, "-noprompt",
                ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    check(process.waitFor(5, TimeUnit.SECONDS)) { "Disposable keytool process leaked" }
                    error("Disposable certificate generation exceeded its deadline")
                }
                check(process.exitValue() == 0) { Files.readString(log) }
                keys = KeyStore.getInstance("PKCS12").apply {
                    Files.newInputStream(store).use { load(it, password.toCharArray()) }
                }
                val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                    init(keys, password.toCharArray())
                }
                serverContext = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        fun withPlatformTrust(block: () -> Unit) {
            val values = mapOf(
                "javax.net.ssl.trustStore" to store.toString(),
                "javax.net.ssl.trustStoreType" to "PKCS12",
                "javax.net.ssl.trustStorePassword" to password,
            )
            val previous = values.keys.associateWith(System::getProperty)
            try {
                values.forEach(System::setProperty)
                val defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                    init(null as KeyStore?)
                }
                val trusted = defaults.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>()
                assertTrue(trusted.any { manager ->
                    manager.acceptedIssuers.any { it.encoded.contentEquals(keys.getCertificate("fixture").encoded) }
                }, "The fixture must enter the JVM platform's default trust store, not a client override")
                block()
            } finally {
                previous.forEach { (name, value) ->
                    if (value == null) System.clearProperty(name) else System.setProperty(name, value)
                }
            }
        }

        override fun close() {
            Files.deleteIfExists(log)
            Files.deleteIfExists(store)
            Files.deleteIfExists(directory)
        }
    }

    private data class Outcome(val handshook: Boolean, val request: String?, val failure: IOException?)

    private class TlsFixture(context: SSLContext, address: InetAddress) : AutoCloseable {
        private val server = context.serverSocketFactory.createServerSocket(0, 1, address).apply {
            soTimeout = 5_000
        }
        private val sockets = ConcurrentLinkedQueue<Socket>()
        val outcome = CompletableDeferred<Outcome>()
        val port: Int get() = server.localPort
        private val worker = Thread {
            var handshook = false
            var request: String? = null
            try {
                assertIs<SSLSocket>(server.accept()).also(sockets::add).use { socket ->
                    socket.soTimeout = 3_000
                    socket.startHandshake()
                    handshook = true
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    request = reader.readLine()
                    if (request != null) {
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc"
                                .toByteArray(Charsets.US_ASCII),
                        )
                        socket.getOutputStream().flush()
                    }
                }
                outcome.complete(Outcome(handshook, request, null))
            } catch (error: IOException) {
                outcome.complete(Outcome(handshook, request, error))
            }
        }.apply { name = "WP218-owned-default-tls"; start() }

        override fun close() {
            server.close()
            sockets.forEach(Socket::close)
            worker.join(5_000)
            check(!worker.isAlive) { "Default TLS fixture worker leaked" }
        }
    }

    private fun client(routeHost: String = host) = OkHttpContentFetching(
        lookup = { listOf(loopback) },
        addressSafe = { hostname, address -> hostname == routeHost && address.isLoopbackAddress },
    )

    private fun markApi() {
        assertTrue(Build.VERSION.SDK_INT in setOf(31, 37))
        println("WP218_DEFAULT_TLS_SDK|${Build.VERSION.SDK_INT}")
    }

    @Test
    fun `unchanged platform trust rejects a real untrusted certificate before HTTP`() = runBlocking<Unit> {
        markApi()
        CertificateFixture(host).use { certificate ->
            TlsFixture(certificate.serverContext, loopback).use { server ->
                client().use { client ->
                    assertIs<HttpFetchAttempt.Failed>(client.fetch("https://$host:${server.port}/photo", 4_000))
                }
                val result = withTimeout(5_000) { server.outcome.await() }
                assertTrue(!result.handshook)
                assertNull(result.request)
                assertIs<javax.net.ssl.SSLException>(result.failure)
            }
        }
    }

    @Test
    fun `default client accepts a platform trusted certificate for its actual hostname`() {
        markApi()
        CertificateFixture(host).use { certificate ->
            certificate.withPlatformTrust {
                runBlocking {
                    TlsFixture(certificate.serverContext, loopback).use { server ->
                        client().use { client ->
                            val response = assertIs<HttpFetchAttempt.Started>(
                                client.fetch("https://$host:${server.port}/photo", 4_000),
                            )
                            response.use { assertEquals("abc", it.readBounded(3)?.toString(Charsets.US_ASCII)) }
                        }
                        val result = withTimeout(5_000) { server.outcome.await() }
                        assertTrue(result.handshook)
                        assertEquals("GET /photo HTTP/1.1", result.request)
                        assertNull(result.failure)
                    }
                }
            }
        }
    }

    @Test
    fun `default hostname verifier rejects a trusted certificate for another host before HTTP`() {
        markApi()
        val other = "wrong-preview-fixture.example"
        CertificateFixture(host).use { certificate ->
            certificate.withPlatformTrust {
                runBlocking {
                    TlsFixture(certificate.serverContext, loopback).use { server ->
                        client(other).use { client ->
                            assertIs<HttpFetchAttempt.Failed>(
                                client.fetch("https://$other:${server.port}/photo", 4_000),
                            )
                        }
                        val result = withTimeout(5_000) { server.outcome.await() }
                        assertTrue(result.handshook, "Trust must succeed so this is a hostname rejection")
                        assertNull(result.request)
                    }
                }
            }
        }
    }
}
