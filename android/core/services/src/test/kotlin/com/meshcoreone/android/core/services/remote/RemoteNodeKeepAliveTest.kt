// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/RemoteNodeKeepAliveTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.services.remote.KeepAliveRetryPolicy.Action
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/** Swift suite "RemoteNodeService keep-alive retry logic". */
class RemoteNodeKeepAliveTest {
    private fun case(name: String, body: () -> Unit): DynamicTest =
        DynamicTest.dynamicTest("RemoteNodeKeepAliveTests::$name()", body)

    private fun assertEvaluation(error: Throwable, failures: Int, expectedFailures: Int, expectedAction: Action) {
        val evaluation = KeepAliveRetryPolicy.evaluate(error, failures)
        assertEquals(expectedFailures, evaluation.consecutiveFailures)
        assertEquals(expectedAction, evaluation.action)
    }

    private class PersistenceError : Exception()

    @TestFactory
    fun sourceCases(): List<DynamicTest> = listOf(
        // MARK: - Transient failures
        case("single transient failure retries without disconnecting") {
            assertEvaluation(RemoteNodeError.SessionError(MeshCoreException.Timeout()), 0, 1, Action.RETRY_NEXT_INTERVAL)
        },
        case("two consecutive transient failures triggers disconnect") {
            assertEvaluation(RemoteNodeError.SessionError(MeshCoreException.Timeout()), 1, 2, Action.DISCONNECT)
        },
        case("success resets failure counter") { assertEquals(0, KeepAliveRetryPolicy.recordSuccess(1)) },
        // MARK: - Terminal failures
        case("sessionNotFound disconnects immediately without incrementing counter") {
            assertEvaluation(RemoteNodeError.SessionNotFound(), 0, 0, Action.DISCONNECT_NOW)
        },
        case("contactNotFound disconnects immediately without incrementing counter") {
            assertEvaluation(RemoteNodeError.ContactNotFound(), 0, 0, Action.DISCONNECT_NOW)
        },
        // MARK: - Transient error variants
        case("deviceError is treated as transient failure") {
            assertEvaluation(RemoteNodeError.SessionError(MeshCoreException.DeviceError(7u)), 0, 1, Action.RETRY_NEXT_INTERVAL)
        },
        case("notConnected is treated as transient failure") {
            assertEvaluation(RemoteNodeError.SessionError(MeshCoreException.NotConnected()), 0, 1, Action.RETRY_NEXT_INTERVAL)
        },
        // MARK: - Skip and stop
        case("floodRouted is not counted as a failure") { assertEvaluation(RemoteNodeError.FloodRouted(), 0, 0, Action.SKIP) },
        case("CancellationError stops the loop quietly") { assertEvaluation(CancellationException("cancelled"), 0, 0, Action.STOP) },
        case("RemoteNodeError.cancelled stops the loop quietly") { assertEvaluation(RemoteNodeError.Cancelled(), 0, 0, Action.STOP) },
        case("unknown non-RemoteNodeError disconnects immediately") {
            assertEvaluation(PersistenceError(), 0, 0, Action.DISCONNECT_NOW)
        },
        // MARK: - Failure reasons
        case("failure reason describes timeout") {
            assertEquals("timeout", KeepAliveRetryPolicy.failureReason(RemoteNodeError.SessionError(MeshCoreException.Timeout())))
        },
        case("failure reason describes device error with code") {
            assertEquals(
                "device error (code: 42)",
                KeepAliveRetryPolicy.failureReason(RemoteNodeError.SessionError(MeshCoreException.DeviceError(42u))),
            )
        },
        case("failure reason describes transport not connected") {
            assertEquals(
                "transport not connected",
                KeepAliveRetryPolicy.failureReason(RemoteNodeError.SessionError(MeshCoreException.NotConnected())),
            )
        },
        case("failure reason describes session not found") {
            assertEquals("session not found", KeepAliveRetryPolicy.failureReason(RemoteNodeError.SessionNotFound()))
        },
        case("failure reason describes contact not found") {
            assertEquals("contact not found", KeepAliveRetryPolicy.failureReason(RemoteNodeError.ContactNotFound()))
        },
    )
}
