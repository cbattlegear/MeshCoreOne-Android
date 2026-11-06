// AndroidOnly: WP-209 Actual manager-issued channel lease binding, retirement, callback ownership and drain.
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.ChannelIdentityMutex
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChannelOperationLeaseTest {
    @TestFactory
    fun nativeCases() = listOf(
        nativeCase("channel lease requires the actual current token connection radio and process scope") {
            withFixture {
                connect()
                val inputs = services.single().inputs.connection
                val otherSession = MeshCoreSession(TestRadio(), coroutineContext = test.backgroundScope.coroutineContext)
                try {
                    ChannelIdentityMutex.PROCESS.withChannelOperationScope { scope ->
                        manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) { lease ->
                            var executed = false
                            lease.withChannelIdentityMutation(inputs.token.radioId) { executed = true }
                            assertTrue(executed)
                        }
                        for ((token, session, radio) in listOf(
                            Triple(inputs.token.copy(generation = Generation(inputs.token.generation.value + 1)), inputs.session, inputs.token.radioId),
                            Triple(inputs.token, otherSession, inputs.token.radioId),
                            Triple(inputs.token, inputs.session, RadioId(UUID.randomUUID())),
                        )) {
                            assertFailsWith<IllegalStateException> {
                                manager.withChannelOperationLease(token, session, radio, scope) { fail("Wrong binding admitted") }
                            }
                        }
                    }
                    ChannelIdentityMutex().withChannelOperationScope { foreign ->
                        assertFailsWith<IllegalStateException> {
                            manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, foreign) {
                                fail("Foreign process gate admitted")
                            }
                        }
                    }
                } finally {
                    otherSession.stop()
                }
            }
        },
        nativeCase("lease cannot escape its callback borrow a child Job or recursively retire itself") {
            withFixture {
                connect()
                val inputs = services.single().inputs.connection
                lateinit var escaped: ConnectionManager.ChannelOperationLease
                ChannelIdentityMutex.PROCESS.withChannelOperationScope { scope ->
                    manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) { lease ->
                        escaped = lease
                        test.backgroundScope.async {
                            assertFailsWith<IllegalStateException> {
                                lease.withChannelIdentityMutation(inputs.token.radioId) { fail("Foreign Job admitted") }
                            }
                        }.await()
                        assertFailsWith<IllegalStateException> { manager.disconnect() }
                        assertFailsWith<IllegalStateException> { manager.close() }
                        assertFailsWith<IllegalStateException> { manager.teardownSessionForReconnect() }
                        assertFailsWith<IllegalStateException> { manager.rebuildSession(platform.target.deviceId) }
                        assertFailsWith<IllegalStateException> { manager.disconnectTransport() }
                        assertFailsWith<IllegalStateException> { manager.disconnectTransport(requireComplete = true) }
                        assertFailsWith<IllegalStateException> { manager.handleReconnectionFailure() }
                        assertFailsWith<IllegalStateException> { manager.awaitShutdown() }
                        assertEquals(DeviceConnectionState.READY, manager.connectionState)
                        assertEquals(0, services.single().teardowns)
                        lease.withChannelIdentityMutation(inputs.token.radioId) { Unit }
                        assertFailsWith<IllegalStateException> {
                            manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) {
                                fail("Recursive lease admitted")
                            }
                        }
                    }
                    assertFailsWith<IllegalStateException> {
                        escaped.withChannelIdentityMutation(inputs.token.radioId) { fail("Expired lease admitted") }
                    }
                }
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
            }
        },
        nativeCase("an internal constructor is not authority without the managers private issued registry") {
            withFixture {
                connect()
                val inputs = services.single().inputs.connection
                ChannelIdentityMutex.PROCESS.withChannelOperationScope { scope ->
                    val forgedGeneration = ConnectionManager.RadioGeneration(
                        inputs.token.generation.value, platform.target, 0,
                        test.backgroundScope.coroutineContext, checkNotNull(currentCoroutineContext()[Job]),
                    )
                    try {
                        forgedGeneration.token = inputs.token
                        forgedGeneration.session = inputs.session
                        val forged = ConnectionManager.ChannelOperationLease(
                            manager, forgedGeneration, inputs.token, inputs.session, scope,
                            checkNotNull(currentCoroutineContext()[Job]),
                        )
                        assertFailsWith<IllegalStateException> {
                            forged.withChannelIdentityMutation(inputs.token.radioId) { fail("Unregistered lease admitted") }
                        }
                    } finally {
                        forgedGeneration.job.cancelAndJoin()
                    }
                }
            }
        },
        nativeCase("disconnect revokes new admissions but drains the admitted identity callback before teardown") {
            withFixture {
                connect()
                val handle = services.single()
                val inputs = handle.inputs.connection
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val effects = mutableListOf<String>()
                val operation = test.backgroundScope.launch {
                    ChannelIdentityMutex.PROCESS.withChannelOperationScope { scope ->
                        manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) { lease ->
                            lease.withChannelIdentityMutation(inputs.token.radioId) {
                                entered.complete(Unit)
                                release.await()
                                effects += "admitted operation terminal"
                            }
                        }
                    }
                }
                entered.await()
                val disconnect = test.backgroundScope.async {
                    manager.disconnect().also { effects += "disconnect terminal" }
                }
                test.runCurrent()
                assertFalse(disconnect.isCompleted)
                assertEquals(0, handle.teardowns)
                assertTrue(radios.single().isConnected())
                release.complete(Unit)
                operation.join()
                disconnect.await()
                assertEquals(listOf("admitted operation terminal", "disconnect terminal"), effects)
                assertEquals(1, handle.teardowns)
                ChannelIdentityMutex.PROCESS.withChannelOperationScope { scope ->
                    assertFailsWith<IllegalStateException> {
                        manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) {
                            fail("Retired session admitted")
                        }
                    }
                }
            }
        },
        nativeCase("replacement cannot install a successor until an old admitted operation reaches terminal outcome") {
            withFixture {
                connect()
                val old = services.single().inputs.connection
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val held = test.backgroundScope.launch {
                    ChannelIdentityMutex.PROCESS.withChannelOperationScope { scope ->
                        manager.withChannelOperationLease(old.token, old.session, old.token.radioId, scope) { lease ->
                            lease.withChannelIdentityMutation(old.token.radioId) {
                                entered.complete(Unit)
                                release.await()
                            }
                        }
                    }
                }
                entered.await()
                val replacement = test.backgroundScope.async {
                    manager.connect(com.meshcoreone.android.core.contracts.domain.ConnectionTarget.WiFi("localhost", 5000u))
                }
                test.runCurrent()
                assertFalse(replacement.isCompleted)
                assertEquals(1, services.size)
                release.complete(Unit)
                held.join()
                replacement.await()
                val fresh = services.last().inputs.connection
                assertNotEquals(old.token, fresh.token)
                assertNotSame(old.session, fresh.session)
                ChannelIdentityMutex.PROCESS.withChannelOperationScope { scope ->
                    assertFailsWith<IllegalStateException> {
                        manager.withChannelOperationLease(old.token, old.session, old.token.radioId, scope) {
                            fail("Replaced session admitted")
                        }
                    }
                    manager.withChannelOperationLease(fresh.token, fresh.session, fresh.token.radioId, scope) { lease ->
                        lease.withChannelIdentityMutation(fresh.token.radioId) { Unit }
                    }
                }
            }
        },
        nativeCase("throw and cancellation release only their actual permits so retirement can finish") {
            withFixture {
                connect()
                val inputs = services.single().inputs.connection
                ChannelIdentityMutex.PROCESS.withChannelOperationScope { scope ->
                    assertFailsWith<IllegalArgumentException> {
                        manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) {
                            throw IllegalArgumentException("operation failure")
                        }
                    }
                }
                val entered = CompletableDeferred<Unit>()
                val cancelled = test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    ChannelIdentityMutex.PROCESS.withChannelOperationScope { scope ->
                        manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) {
                            entered.complete(Unit)
                            CompletableDeferred<Unit>().await()
                        }
                    }
                }
                entered.await()
                cancelled.cancelAndJoin()
                val report = manager.disconnect()
                assertTrue(report.isComplete)
                assertEquals(1, services.single().teardowns)
            }
        },
    )
}
