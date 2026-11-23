// AndroidOnly: WP-210 Native BinaryProtocolService cases (Swift has no unit tests for it): delegation, timeout path-reset retry, error mapping, push handlers and cancellation.
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.ACLEntry
import com.meshcoreone.android.core.protocol.event.ACLResponse
import com.meshcoreone.android.core.protocol.event.MMAResponse
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.Neighbour
import com.meshcoreone.android.core.protocol.event.NeighboursResponse
import com.meshcoreone.android.core.protocol.event.TelemetryResponse
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BinaryProtocolServiceTest {
    private val publicKey = remoteCoreKey(0xF1)
    private val hex = publicKey.hexString
    private val prefix = publicKey.prefix(6)

    private class Fixture(val session: RemoteAdminFakeBinarySession, val scope: CoroutineScope) {
        var fallbackEnabled = true
        var recoveryOwnerCurrent = true
        val service = BinaryProtocolService(session, scope, { fallbackEnabled }, { recoveryOwnerCurrent })
    }

    private suspend fun withBinaryService(block: suspend Fixture.() -> Unit) {
        val dispatcher = checkNotNull(kotlinx.coroutines.currentCoroutineContext()[ContinuationInterceptor])
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val fixture = Fixture(RemoteAdminFakeBinarySession(), scope)
        try {
            fixture.block()
        } finally {
            fixture.service.close()
            scope.cancel()
        }
    }

    /** One binary request: the scripted session operation name, its expected call line and the service call. */
    private data class Operation(val name: String, val call: String, val invoke: suspend BinaryProtocolService.() -> Any?)

    private val telemetry = TelemetryResponse(prefix, null, Bytes.of(1, 0x67, 0x00, 0xFA))
    private val neighbours = NeighboursResponse(prefix, Bytes.of(1, 2, 3, 4), 1, listOf(Neighbour(Bytes.of(9, 9, 9, 9, 9, 9), 30, 6.25)))
    private val acl = ACLResponse(prefix, Bytes.of(5, 6, 7, 8), listOf(ACLEntry(Bytes.of(1, 2, 3, 4, 5, 6), 3u)))
    private val mma = MMAResponse(prefix, Bytes.of(1, 1, 1, 1), emptyList())
    private val start = Instant.ofEpochSecond(1_700_000_000)
    private val end = Instant.ofEpochSecond(1_700_003_600)

    private val operations = listOf(
        Operation("status", "status $hex") { requestStatus(publicKey) },
        Operation("statusTyped", "statusTyped $hex ROOM") { requestStatus(publicKey, ContactType.ROOM) },
        Operation("telemetry", "telemetry $hex") { requestTelemetry(publicKey) },
        Operation("neighbours", "neighbours $hex 255 0 0 6") { requestNeighbours(publicKey) },
        Operation("allNeighbours", "allNeighbours $hex 0 6") { fetchAllNeighbours(publicKey) },
        Operation("mma", "mma $hex 1700000000 1700003600") { requestMMA(publicKey, start, end) },
        Operation("acl", "acl $hex") { requestACL(publicKey) },
    )

    private fun answerFor(name: String): Any = when (name) {
        "status", "statusTyped" -> remoteAdminStatus(prefix)
        "telemetry" -> telemetry
        "neighbours", "allNeighbours" -> neighbours
        "mma" -> mma
        else -> acl
    }

    @TestFactory
    fun requestCases(): List<DynamicTest> = operations.map { op ->
        remoteCoreNative("${op.name}: success returns the session response with the Swift default arguments and no path reset") {
            withBinaryService {
                val answer = answerFor(op.name)
                session.script(op.name, { answer })
                assertSame(answer, service.(op.invoke)())
                assertEquals(listOf(op.call), session.calls)
            }
        }
    } + operations.map { op ->
        remoteCoreNative("${op.name}: a mesh timeout resets the path to flood and retries exactly once") {
            withBinaryService {
                val answer = answerFor(op.name)
                session.script(op.name, { throw MeshCoreException.Timeout() }, { answer })
                assertSame(answer, service.(op.invoke)())
                assertEquals(listOf(op.call, "resetPath $hex", op.call), session.calls)
            }
        }
    } + operations.map { op ->
        remoteCoreNative("${op.name}: disabled fallback preserves the original mesh timeout without a reset") {
            withBinaryService {
                fallbackEnabled = false
                val first = MeshCoreException.Timeout()
                session.script(op.name, { throw first })
                assertSame(first, assertFailsWith<BinaryProtocolError.SessionError> { service.(op.invoke)() }.error)
                assertEquals(listOf(op.call), session.calls)
            }
        }
    } + listOf(
        remoteCoreNative("non-default neighbour arguments pass through unchanged") {
            withBinaryService {
                session.script("neighbours", { neighbours })
                session.script("allNeighbours", { neighbours })
                service.requestNeighbours(publicKey, count = 10u, offset = 40u, orderBy = 2u, pubkeyPrefixLength = 4u)
                service.fetchAllNeighbours(publicKey, orderBy = 3u, pubkeyPrefixLength = 8u)
                assertEquals(listOf("neighbours $hex 10 40 2 4", "allNeighbours $hex 3 8"), session.calls)
                assertEquals(6.toUByte(), BinaryProtocolService.DEFAULT_PUBKEY_PREFIX_LENGTH)
            }
        },
    )

    @TestFactory
    fun recoveryCases(): List<DynamicTest> = listOf(
        remoteCoreNative("a replacement before recovery prevents binary reset") {
            withBinaryService {
                session.script("telemetry", { recoveryOwnerCurrent = false; throw MeshCoreException.Timeout() })
                assertFailsWith<CancellationException> { service.requestTelemetry(publicKey) }
                assertEquals(listOf("telemetry $hex"), session.calls)
            }
        },
        remoteCoreNative("owner cancellation while resetting prevents a binary retry") {
            withBinaryService {
                session.script("acl", { throw MeshCoreException.Timeout() }, { acl })
                session.resetPathGate = CompletableDeferred()
                val request = async { service.requestACL(publicKey) }
                remoteCoreAwait("reset never started") { session.resetPathPublicKeys.size == 1 }
                scope.cancel()
                assertFailsWith<CancellationException> { request.await() }
                assertEquals(listOf("acl $hex", "resetPath $hex"), session.calls)
            }
        },
        remoteCoreNative("a second timeout after the reset surfaces as sessionError(timeout), not BinaryProtocolError.timeout") {
            withBinaryService {
                val second = MeshCoreException.Timeout()
                session.script("acl", { throw MeshCoreException.Timeout() }, { throw second })
                val error = assertFailsWith<BinaryProtocolError.SessionError> { service.requestACL(publicKey) }
                assertSame(second, error.error)
                assertEquals(listOf("acl $hex", "resetPath $hex", "acl $hex"), session.calls)
            }
        },
        remoteCoreNative("a failed path reset rethrows the first timeout as sessionError and does not retry") {
            withBinaryService {
                val first = MeshCoreException.Timeout()
                session.script("status", { throw first })
                session.resetPathError = MeshCoreException.DeviceError(2u)
                val error = assertFailsWith<BinaryProtocolError.SessionError> { service.requestStatus(publicKey) }
                assertSame(first, error.error)
                assertEquals(listOf("status $hex", "resetPath $hex"), session.calls)
                session.resetPathError = IllegalStateException("not a mesh error")
                session.script("status", { throw first })
                assertSame(first, assertFailsWith<BinaryProtocolError.SessionError> { service.requestStatus(publicKey) }.error)
            }
        },
        remoteCoreNative("non-timeout mesh errors map to sessionError without a reset, on the first try and on the retry") {
            withBinaryService {
                val device = MeshCoreException.DeviceError(6u)
                session.script("telemetry", { throw device })
                assertSame(device, assertFailsWith<BinaryProtocolError.SessionError> { service.requestTelemetry(publicKey) }.error)
                assertTrue(session.resetPathPublicKeys.isEmpty())
                val parse = MeshCoreException.ParseError("short")
                session.script("telemetry", { throw MeshCoreException.Timeout() }, { throw parse })
                assertSame(parse, assertFailsWith<BinaryProtocolError.SessionError> { service.requestTelemetry(publicKey) }.error)
                assertEquals(1, session.resetPathPublicKeys.size)
            }
        },
        remoteCoreNative("non-mesh errors propagate unchanged with no reset, on the first try and on the retry") {
            withBinaryService {
                val boom = IllegalStateException("boom")
                session.script("mma", { throw boom })
                assertSame(boom, assertFailsWith<IllegalStateException> { service.requestMMA(publicKey, start, end) })
                assertTrue(session.resetPathPublicKeys.isEmpty())
                session.script("mma", { throw MeshCoreException.Timeout() }, { throw boom })
                assertSame(boom, assertFailsWith<IllegalStateException> { service.requestMMA(publicKey, start, end) })
            }
        },
        remoteCoreNative("cancellation during the path reset propagates and skips the retry") {
            withBinaryService {
                session.script("acl", { throw MeshCoreException.Timeout() }, { acl })
                session.resetPathGate = CompletableDeferred()
                val request = async { service.requestACL(publicKey) }
                remoteCoreAwait("reset never started") { session.resetPathPublicKeys.size == 1 }
                request.cancel()
                assertFailsWith<CancellationException> { request.await() }
                assertEquals(listOf("acl $hex", "resetPath $hex"), session.calls)
            }
        },
        remoteCoreNative("self telemetry, path discovery and trace pass through and map mesh errors to sessionError") {
            withBinaryService {
                val sent = RemoteCoreFakeSession.sentInfo(700u)
                session.script("selfTelemetry", { telemetry }, { throw MeshCoreException.NotConnected() })
                session.script("pathDiscovery", { sent }, { throw MeshCoreException.Timeout() })
                session.script("trace", { sent }, { sent }, { throw MeshCoreException.InvalidInput("no path") })
                assertSame(telemetry, service.getSelfTelemetry())
                assertSame(sent, service.sendPathDiscovery(publicKey))
                assertSame(sent, service.sendTrace(tag = 7u, authCode = 9u, flags = 1u, path = Bytes.of(0xAB)))
                assertSame(sent, service.sendTrace())
                assertIs<MeshCoreException.NotConnected>(assertFailsWith<BinaryProtocolError.SessionError> { service.getSelfTelemetry() }.error)
                // A path-discovery timeout is not retried: only the binary requests reset the path.
                assertIs<MeshCoreException.Timeout>(assertFailsWith<BinaryProtocolError.SessionError> { service.sendPathDiscovery(publicKey) }.error)
                assertIs<MeshCoreException.InvalidInput>(assertFailsWith<BinaryProtocolError.SessionError> { service.sendTrace() }.error)
                assertEquals(
                    listOf("selfTelemetry", "pathDiscovery $hex", "trace 7 9 1 ab", "trace null null 0 null",
                        "selfTelemetry", "pathDiscovery $hex", "trace null null 0 null"),
                    session.calls,
                )
                assertTrue(session.resetPathPublicKeys.isEmpty())
            }
        },
    )

    @TestFactory
    fun monitoringCases(): List<DynamicTest> = listOf(
        remoteCoreNative("monitoring routes status, telemetry and neighbours pushes to their handlers in arrival order") {
            withBinaryService {
                val seen = mutableListOf<String>()
                service.setStatusResponseHandler { seen += "status ${it.uptime}" }
                service.setTelemetryResponseHandler { seen += "telemetry ${it.rawData.size}" }
                service.setNeighboursResponseHandler { seen += "neighbours ${it.totalCount}" }
                service.startEventMonitoring()
                assertEquals(1, session.eventSubscriptionCount)
                session.yieldEvent(MeshEvent.NeighboursResponse(neighbours))
                session.yieldEvent(MeshEvent.AclResponse(acl))
                session.yieldEvent(MeshEvent.StatusResponse(remoteAdminStatus(prefix, uptime = 42u)))
                session.yieldEvent(MeshEvent.TelemetryResponse(telemetry))
                session.yieldEvent(MeshEvent.StatusResponse(remoteAdminStatus(prefix, uptime = 43u)))
                remoteCoreAwait("pushes never delivered") { seen.size == 4 }
                remoteCoreSettle()
                assertEquals(listOf("neighbours 1", "status 42", "telemetry 4", "status 43"), seen)
            }
        },
        remoteCoreNative("a throwing push handler is contained and monitoring keeps delivering later pushes") {
            withBinaryService {
                val seen = mutableListOf<UInt>()
                service.setStatusResponseHandler {
                    if (it.uptime == 1u) throw IllegalStateException("view model gone")
                    seen += it.uptime
                }
                service.startEventMonitoring()
                session.yieldEvent(MeshEvent.StatusResponse(remoteAdminStatus(prefix, uptime = 1u)))
                session.yieldEvent(MeshEvent.StatusResponse(remoteAdminStatus(prefix, uptime = 2u)))
                remoteCoreAwait("monitoring stopped after a throwing handler") { seen.isNotEmpty() }
                assertEquals(listOf(2u), seen)
                assertEquals(1, session.eventSubscriptionCount)
            }
        },
        remoteCoreNative("pushes without a handler are ignored; a handler set later receives later pushes") {
            withBinaryService {
                service.startEventMonitoring()
                session.yieldEvent(MeshEvent.StatusResponse(remoteAdminStatus(prefix, uptime = 1u)))
                remoteCoreSettle()
                val seen = mutableListOf<UInt>()
                service.setStatusResponseHandler { seen += it.uptime }
                session.yieldEvent(MeshEvent.StatusResponse(remoteAdminStatus(prefix, uptime = 2u)))
                remoteCoreAwait("push never delivered") { seen.isNotEmpty() }
                assertEquals(listOf(2u), seen)
            }
        },
        remoteCoreNative("restarting monitoring replaces the subscription; stop and close end delivery") {
            withBinaryService {
                val seen = mutableListOf<UInt>()
                service.setStatusResponseHandler { seen += it.uptime }
                service.startEventMonitoring()
                service.startEventMonitoring()
                remoteCoreSettle()
                assertEquals(1, session.eventSubscriptionCount)
                session.yieldEvent(MeshEvent.StatusResponse(remoteAdminStatus(prefix, uptime = 1u)))
                remoteCoreAwait("push never delivered") { seen.size == 1 }
                service.stopEventMonitoring()
                remoteCoreSettle()
                assertEquals(0, session.eventSubscriptionCount)
                session.yieldEvent(MeshEvent.StatusResponse(remoteAdminStatus(prefix, uptime = 2u)))
                remoteCoreSettle()
                assertEquals(listOf(1u), seen)
                service.startEventMonitoring()
                assertEquals(1, session.eventSubscriptionCount)
                service.close()
                remoteCoreSettle()
                assertEquals(0, session.eventSubscriptionCount)
                service.startEventMonitoring()
                remoteCoreSettle()
                assertEquals(0, session.eventSubscriptionCount, "a closed service never subscribes again")
            }
        },
        remoteCoreNative("a slow handler delays later pushes without dropping them") {
            withBinaryService {
                val gate = CompletableDeferred<Unit>()
                val seen = mutableListOf<UInt>()
                service.setStatusResponseHandler {
                    if (it.uptime == 1u) gate.await()
                    seen += it.uptime
                }
                service.startEventMonitoring()
                (1u..3u).forEach { session.yieldEvent(MeshEvent.StatusResponse(remoteAdminStatus(prefix, uptime = it))) }
                remoteCoreSettle()
                assertTrue(seen.isEmpty())
                gate.complete(Unit)
                remoteCoreAwait("pushes never delivered") { seen.size == 3 }
                assertEquals(listOf(1u, 2u, 3u), seen)
            }
        },
    )

    @TestFactory
    fun errorCases(): List<DynamicTest> = listOf(
        remoteCoreNative("BinaryProtocolError descriptions and debug names match the Swift cases") {
            val mesh = MeshCoreException.NotConnected()
            listOf(
                BinaryProtocolError.NotConnected() to "Not connected to device.",
                BinaryProtocolError.SendFailed() to "Failed to send request.",
                BinaryProtocolError.Timeout() to "Request timed out.",
                BinaryProtocolError.InvalidResponse() to "Invalid response from device.",
                BinaryProtocolError.SessionError(mesh) to "Transport is not connected",
            ).forEach { (error, text) ->
                assertEquals(text, error.message)
                assertEquals(text, error.errorDescription)
            }
            assertSame(mesh, BinaryProtocolError.SessionError(mesh).cause)
            assertEquals("BinaryProtocolError.sendFailed", BinaryProtocolError.SendFailed().toString())
            assertEquals("BinaryProtocolError.sessionError(NotConnected)", BinaryProtocolError.SessionError(mesh).toString())
        },
    )
}
