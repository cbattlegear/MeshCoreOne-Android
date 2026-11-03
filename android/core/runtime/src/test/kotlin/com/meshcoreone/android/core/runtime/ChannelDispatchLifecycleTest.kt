// AndroidOnly: WP-207 Real manager ownership revokes exact-session dispatch before asynchronous cleanup.
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlin.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelDispatchLifecycleTest {
    @TestFactory
    fun lifecycleCases() = listOf(
        nativeCase("factory receives the manager's exact token session and ownership admission") {
            withFixture {
                var published = false
                onFactory = { service, ownership ->
                    val inputs = service.inputs
                    assertSame(inputs.dispatchAdmission, ownership.dispatchAdmission)
                    assertEquals(inputs.connection.token, inputs.dispatchAdmission.token)
                    assertSame(inputs.connection.session, inputs.dispatchAdmission.connectionIdentity)
                    inputs.callbacks.publishServices(service) { published = true }
                }
                connect()
                assertTrue(published)
                val inputs = services.single().inputs
                assertNull(inputs.dispatchAdmission.tryAcquire(inputs.connection.token, Any()))
                val permit = assertNotNull(inputs.dispatchAdmission.tryAcquire(inputs.connection.token, inputs.connection.session))
                assertEquals(1L, inputs.dispatchAdmission.inFlightCount)
                permit.complete()
                assertEquals(0L, inputs.dispatchAdmission.inFlightCount)
            }
        },
        nativeCase("detach revokes a noncooperative factory before it can publish") {
            withFixture {
                val resumeFactory = CompletableDeferred<Unit>()
                val enteredFactory = CompletableDeferred<Unit>()
                var published = false
                onFactory = { service, ownership ->
                    enteredFactory.complete(Unit)
                    withContext(NonCancellable) {
                        resumeFactory.await()
                        assertTrue(ownership.dispatchAdmission.isRevoked)
                        assertFailsWith<ConnectionError.FactoryOwnershipViolation> {
                            service.inputs.callbacks.publishServices(service) { published = true }
                        }
                    }
                }
                val connecting = backgroundScope.async { runCatching { manager.connect(platform.target) } }
                runCurrent()
                assertTrue(enteredFactory.isCompleted)
                val inputs = services.single().inputs
                val stopping = backgroundScope.async { manager.disconnect() }
                runCurrent()
                assertTrue(inputs.dispatchAdmission.isRevoked)
                assertNull(inputs.dispatchAdmission.tryAcquire(inputs.connection.token, inputs.connection.session))
                assertFalse(stopping.isCompleted, "factory is still held; revocation did not wait for cleanup")
                resumeFactory.complete(Unit)
                runCurrent()
                stopping.await()
                assertNotNull(connecting.await().exceptionOrNull())
                assertFalse(published)
                assertEquals(1, services.single().teardowns)
            }
        },
        nativeCase("retirement preserves admitted accounting but never reopens the old handle") {
            withFixture {
                connect()
                val old = services.single().inputs
                val permit = assertNotNull(old.dispatchAdmission.tryAcquire(old.connection.token, old.connection.session))
                manager.teardownSessionForReconnect()
                assertTrue(old.dispatchAdmission.isRevoked)
                assertEquals(1L, old.dispatchAdmission.inFlightCount)
                manager.rebuildSession(platform.target.deviceId)
                val fresh = services.last().inputs
                assertNotSame(old.dispatchAdmission, fresh.dispatchAdmission)
                assertNotSame(old.connection.session, fresh.connection.session)
                assertFalse(fresh.dispatchAdmission.isRevoked)
                assertNull(old.dispatchAdmission.tryAcquire(old.connection.token, old.connection.session))
                permit.complete()
                permit.complete()
                assertEquals(0L, old.dispatchAdmission.inFlightCount)
                assertTrue(old.dispatchAdmission.isRevoked, "zero accounting is not drain or reactivation")
            }
        },
        nativeCase("display-only disconnect during rebuild does not retire its unbound owner or reactivate an old admission") {
            withFixture {
                connect()
                val old = services.single().inputs
                manager.teardownSessionForReconnect()
                val resumeHandshake = CompletableDeferred<Unit>()
                createRadio = { TestRadio().also { radio ->
                    radio.beforeSend = { frame -> if (frame[0].toInt() == 1) resumeHandshake.await() }
                } }
                val rebuild = backgroundScope.async { manager.rebuildSession(platform.target.deviceId) }
                runCurrent()
                assertEquals(2, radios.size)
                assertFalse(rebuild.isCompleted)
                manager.setConnectionState(DeviceConnectionState.DISCONNECTED)
                assertTrue(old.dispatchAdmission.isRevoked)
                assertNull(old.dispatchAdmission.tryAcquire(old.connection.token, old.connection.session))
                resumeHandshake.complete(Unit)
                runCurrent()
                rebuild.await()
                val fresh = services.last().inputs
                assertNotSame(old.dispatchAdmission, fresh.dispatchAdmission)
                assertFalse(fresh.dispatchAdmission.isRevoked)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                manager.setConnectionState(DeviceConnectionState.DISCONNECTED)
                assertFailsWith<IllegalArgumentException> {
                    manager.setConnectionState(DeviceConnectionState.READY)
                }
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                assertNull(manager.snapshot.value.token)
                assertTrue(fresh.dispatchAdmission.isRevoked, "display recovery cannot reopen an existing handle")
                assertNull(fresh.dispatchAdmission.tryAcquire(fresh.connection.token, fresh.connection.session))
            }
        },
        nativeCase("stale callbacks from a retired owner cannot revoke its successor") {
            withFixture {
                connect()
                val old = services.single().inputs
                val callbacks = assertNotNull(links.single().callbacks)
                manager.disconnect()
                connect(target())
                val fresh = services.last().inputs
                callbacks.onDisconnected(IllegalStateException("old loss"))
                callbacks.onAutoReconnecting("old reconnect")
                callbacks.onReconnected()
                runCurrent()
                assertTrue(old.dispatchAdmission.isRevoked)
                assertFalse(fresh.dispatchAdmission.isRevoked)
                assertEquals(fresh.connection.token, manager.snapshot.value.token)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
            }
        },
        nativeCase("link loss revokes admission synchronously before its queued recovery runs") {
            withFixture {
                connect()
                val old = services.single().inputs
                assertNotNull(links.single().callbacks).onDisconnected(null)
                assertTrue(old.dispatchAdmission.isRevoked)
                assertNull(old.dispatchAdmission.tryAcquire(old.connection.token, old.connection.session))
                assertEquals(old.connection.token, manager.snapshot.value.token, "async loss has not run yet")
                runCurrent()
                assertNull(manager.snapshot.value.token)
            }
        },
        nativeCase("correlation uncertainty revokes before dispatched recovery while admitted accounting can finish") {
            withRecoveryFixture { recovery ->
                connect()
                val old = services.single()
                val inputs = old.inputs
                val permit = assertNotNull(inputs.dispatchAdmission.tryAcquire(inputs.connection.token, inputs.connection.session))
                val accounting = CompletableDeferred<Unit>()
                old.beforeChannelQueryAccounting = { accounting.await() }
                radios.single().channelResponse = { null }
                val query = backgroundScope.async { runCatching { inputs.connection.session.getChannel(0u) } }
                runCurrent()
                testScheduler.advanceTimeBy(1_000)
                runCurrent()
                assertIs<MeshCoreException.Timeout>(query.await().exceptionOrNull())
                assertTrue(recovery.queuedCount > 0)
                assertTrue(inputs.dispatchAdmission.isRevoked)
                assertNull(inputs.dispatchAdmission.tryAcquire(inputs.connection.token, inputs.connection.session))
                assertEquals(inputs.connection.token, manager.snapshot.value.token)
                recovery.open()
                runCurrent()
                assertEquals(1, old.channelQueryAccountingCalls)
                assertEquals(0, old.teardowns)
                permit.complete()
                assertEquals(0L, inputs.dispatchAdmission.inFlightCount)
                assertTrue(inputs.dispatchAdmission.isRevoked)
                accounting.complete(Unit)
                runCurrent()
                assertEquals(1, old.teardowns)
            }
        },
        nativeCase("early factory close rejects later registration and manager publication") {
            withFixture {
                onFactory = { service, ownership ->
                    ownership.close()
                    assertTrue(ownership.dispatchAdmission.isRevoked)
                    assertFailsWith<ConnectionError.FactoryOwnershipViolation> { ownership.register(service) }
                    assertFailsWith<ConnectionError.FactoryOwnershipViolation> {
                        service.inputs.callbacks.publishServices(service) { fail("closed factory published") }
                    }
                }
                assertFailsWith<ConnectionError.FactoryOwnershipViolation> {
                    manager.connect(ConnectionTarget.WiFi("127.0.0.1", 5000u))
                }
                runCurrent()
                assertTrue(services.single().inputs.dispatchAdmission.isRevoked)
                assertEquals(1, services.single().teardowns)
                assertNull(manager.snapshot.value.token)
            }
        },
        nativeCase("reconnection failure and process shutdown revoke before service teardown waits") {
            withFixture {
                connect()
                val old = services.single().inputs
                manager.handleReconnectionFailure()
                assertTrue(old.dispatchAdmission.isRevoked)
                assertNull(old.dispatchAdmission.tryAcquire(old.connection.token, old.connection.session))
            }
            withFixture {
                connect()
                val service = services.single()
                val closing = CompletableDeferred<Unit>()
                service.beforeTeardown = { closing.await() }
                val stop = backgroundScope.async { manager.close() }
                runCurrent()
                assertTrue(service.inputs.dispatchAdmission.isRevoked)
                assertFalse(stop.isCompleted)
                closing.complete(Unit)
                runCurrent()
                stop.await()
            }
        },
    )
}
