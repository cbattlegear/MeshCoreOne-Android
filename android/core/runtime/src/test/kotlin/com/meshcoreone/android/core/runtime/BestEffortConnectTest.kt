// AndroidOnly: WP-207 Source try? paths during connect stay non-fatal and are reported, not silently defaulted.
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BestEffortConnectTest {
    @TestFactory
    fun bestEffortCases() = listOf(
        nativeCase("an auto-add timeout reports its fallback but cannot publish a graph before queued physical recovery") {
            withRecoveryFixture { recovery ->
                createRadio = { TestRadio().also { it.beforeSend = { frame -> if (frame[0].toInt() == 0x3b) delay(5_000) } } }
                val work = backgroundScope.async { runCatching { manager.connect(platform.target) } }
                advanceTimeBy(10_000); runCurrent()
                assertFalse(work.isCompleted, "queued recovery must retire the revoked owner before construction")
                assertTrue(services.isEmpty())
                assertNull(manager.snapshot.value.token)
                assertTrue(diagnostics.any { it is RuntimeDiagnostic.Failure && it.operation == "getAutoAddConfig" })
                assertTrue(manager.shouldAllowConnection(false), "A best-effort query failure must not burn retries or trip the breaker")
                assertEquals(1, radios.size)
                assertEquals(1, recovery.queuedCount)
                createRadio = { TestRadio() }
                recovery.open(); runCurrent()
                assertIs<CancellationException>(work.await().exceptionOrNull())
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                assertEquals(1, radios.first().closes)
                advanceTimeBy(30_000); runCurrent()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(0u, manager.connectedDevice!!.autoAddConfig.toUInt())
                assertEquals(2, radios.size)
                assertTrue(manager.shouldAllowConnection(false))
            }
        },
        nativeCase("a failing warmUp is reported and the connection still reaches ready") {
            withFixture {
                warmUpFailure = IllegalStateException("purge failed")
                connect()
                assertEquals(1, warmed)
                assertTrue(diagnostics.any { it is RuntimeDiagnostic.Failure && it.operation == "maintenance.warmUp" })
                assertEquals(1, radios.size)
            }
        },
    )
}
