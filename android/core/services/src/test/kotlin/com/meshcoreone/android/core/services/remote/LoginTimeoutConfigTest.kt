// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/LoginTimeoutConfigTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/** Swift suite "LoginTimeoutConfig Tests". */
class LoginTimeoutConfigTest {
    private fun case(name: String, body: () -> Unit): DynamicTest =
        DynamicTest.dynamicTest("LoginTimeoutConfigTests::$name()", body)

    private fun sentInfo(timeoutMs: UInt) = MessageSentInfo(0u, Bytes.of(0x00), timeoutMs)

    @TestFactory
    fun sourceCases(): List<DynamicTest> = listOf(
        // Mode 0, 0 hops encodes as 0x00.
        case("Direct path (mode 0) uses base timeout only") { assertEquals(5.seconds, LoginTimeoutConfig.timeout(0x00u)) },
        // Mode 1, 0 hops encodes as 0x40.
        case("Direct path (mode 1) uses base timeout only, not mode bits") { assertEquals(5.seconds, LoginTimeoutConfig.timeout(0x40u)) },
        // Mode 2, 0 hops encodes as 0x80.
        case("Direct path (mode 2) uses base timeout only, not mode bits") { assertEquals(5.seconds, LoginTimeoutConfig.timeout(0x80u)) },
        // Mode 1, 3 hops encodes as 0x43: 5 + 3*10.
        case("Mode 1 with 3 hops computes timeout from hop count") { assertEquals(35.seconds, LoginTimeoutConfig.timeout(0x43u)) },
        case("Mode 0 with 5 hops computes correct timeout") { assertEquals(55.seconds, LoginTimeoutConfig.timeout(5u)) },
        // 0xFF is mode 3 (reserved): no known path, the login floods both ways.
        case("Flood routing (0xFF) budgets for the worst case") {
            assertEquals(LoginTimeoutConfig.maximumTimeout, LoginTimeoutConfig.timeout(0xFFu))
        },
        // A short firmware estimate must not starve a flood login; the policy clamps to loginMaximum.
        case("Login timeout policy gives flood logins the full login maximum") {
            assertEquals(20.seconds, RemoteOperationTimeoutPolicy.loginTimeout(sentInfo(4724u), 0xFFu))
        },
        // Mode 0, 6 hops gives 5 + 60 = 65, capped at 60.
        case("Timeout is capped at maximum") { assertEquals(60.seconds, LoginTimeoutConfig.timeout(6u)) },
        case("Login timeout policy clamps long firmware suggestions") {
            assertEquals(20.seconds, RemoteOperationTimeoutPolicy.loginTimeout(sentInfo(20000u), 0u))
        },
        case("Login timeout policy respects path floor when firmware is shorter") {
            assertEquals(20.seconds, RemoteOperationTimeoutPolicy.loginTimeout(sentInfo(1000u), 0x43u))
        },
        case("CLI timeout policy clamps long firmware suggestions") {
            assertEquals(15.seconds, RemoteOperationTimeoutPolicy.cliTimeout(sentInfo(20000u), 10.seconds))
        },
        case("CLI timeout policy keeps caller budget when firmware is shorter") {
            assertEquals(10.seconds, RemoteOperationTimeoutPolicy.cliTimeout(sentInfo(1000u), 10.seconds))
        },
    )
}
