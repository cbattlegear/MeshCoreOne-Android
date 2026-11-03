// AndroidOnly: WP-207 Real App graph publication, Room authority and protocol dispatch lifetime evidence.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.ChannelIdentityMutex
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.runtime.ConnectionError
import com.meshcoreone.android.core.services.contacts.ChannelDispatchFailure
import com.meshcoreone.android.core.services.contacts.ChannelDispatchFence
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelDispatchLifecycleIntegrationTest : RoomProcessTest() {
    @Test fun managerHandleGatesActualProtocolDispatchAfterFreshRoomReconciliation() = runTest {
        withHarness { h ->
            val graph = assertNotNull(h.sessions.current)
            val session = assertIs<MeshCoreSession>(graph.dispatchAdmission.connectionIdentity)
            val radio = h.links.radios.single()
            val fence = ChannelDispatchFence(ChannelIdentityMutex.PROCESS)
            val lease = fence.openSession(graph.token, session, graph.dispatchAdmission)
            val packet = Bytes.of(0x12, 0) + Bytes.utf8("Current").paddedOrTruncated(32) + Bytes(ByteArray(16) { 7 })
            radio.respond = { command ->
                when (command[0].toInt()) {
                    0x1f -> listOf(packet)
                    0x03 -> listOf(Bytes.of(0))
                    else -> null
                }
            }
            val read = backgroundScope.async {
                store.withChannelOperation { scoped, scope ->
                    val actual = session.getChannel(0u)
                    scoped.saveChannel(graph.token.radioId, actual)
                    fence.recordReconciled(lease, scope, listOf(0u))
                }
            }
            h.eventually("authoritative channel read persisted") { read.isCompleted }
            read.await()
            val occupant = assertNotNull(store.fetchChannelOccupantID(graph.token.radioId, 0u))
            val sending = backgroundScope.async {
                fence.dispatch(lease, 0u, occupant, store::fetchChannelOccupantID) {
                    session.sendChannelMessage(0u, "admitted", EPOCH)
                }
            }
            h.eventually("actual fenced protocol send") { sending.isCompleted }
            sending.await()
            assertEquals(1, radio.sent.count { it[0].toInt() == 0x03 })
            assertEquals(0L, graph.dispatchAdmission.inFlightCount)
            h.manager.disconnect()
            assertTrue(graph.dispatchAdmission.isRevoked)
            val frames = radio.sent.size
            assertFailsWith<ChannelDispatchFailure.RetiredSession> {
                fence.dispatch(lease, 0u, occupant, store::fetchChannelOccupantID) {
                    session.sendChannelMessage(0u, "must not send", EPOCH)
                }
            }
            assertEquals(frames, radio.sent.size)
        }
    }

    @Test fun managerRevocationDoesNotWaitForHeldRoomOperationLane() = runTest {
        withHarness { h ->
            val graph = assertNotNull(h.sessions.current)
            val callbacks = assertNotNull(h.links.links.single().callbacks)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = backgroundScope.async {
                store.withChannelOperation { _, _ ->
                    entered.complete(Unit)
                    release.await()
                }
            }
            runCurrent()
            assertTrue(entered.isCompleted)
            val executor = Executors.newSingleThreadExecutor()
            try {
                executor.submit { callbacks.onDisconnected(null) }.get(2, TimeUnit.SECONDS)
                assertTrue(graph.dispatchAdmission.isRevoked)
                assertFalse(holder.isCompleted, "manager did not wait for the Room/fence operation lane")
                assertNull(graph.dispatchAdmission.tryAcquire(graph.token, graph.dispatchAdmission.connectionIdentity))
            } finally {
                release.complete(Unit)
                holder.await()
                executor.shutdown()
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun graphEarlyCloseRevokesBeforeAnyLateRegistryPublication() = runTest {
        withHarness { h ->
            val graph = assertNotNull(h.sessions.current)
            val count = h.sessions.created
            graph.tearDown()
            assertTrue(graph.dispatchAdmission.isRevoked)
            assertTrue(graph.isTornDown)
            assertFailsWith<ConnectionError.FactoryOwnershipViolation> { h.sessions.publish(graph) }
            assertEquals(count, h.sessions.created)
            assertNull(h.sessions.current)
            h.manager.disconnect()
            assertEquals(0, h.sessions.outstanding)
        }
    }

    @Test fun staleOwnerCallbacksCannotRevokeOrWithdrawTheNewlyPublishedGraph() = runTest {
        withHarness { h ->
            val old = assertNotNull(h.sessions.current)
            val callbacks = assertNotNull(h.links.links.single().callbacks)
            h.manager.disconnect()
            h.manager.connect(ConnectionTarget.WiFi("127.0.0.2", 5001u))
            h.eventually("successor ready") {
                h.sessions.current?.token != old.token && h.manager.connectionState == DeviceConnectionState.READY
            }
            val fresh = assertNotNull(h.sessions.current)
            callbacks.onDisconnected(IllegalStateException("old same-device callback"))
            callbacks.onAutoReconnecting("old callback")
            callbacks.onReconnected()
            h.settle()
            assertTrue(old.dispatchAdmission.isRevoked)
            assertFalse(fresh.dispatchAdmission.isRevoked)
            assertSame(fresh, h.sessions.current)
            assertEquals(fresh.token, h.manager.snapshot.value.token)
            assertEquals(1, h.sessions.outstanding)
        }
    }

    private suspend fun TestScope.withHarness(assertions: suspend TestScope.(ContainerHarness) -> Unit) {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(ConnectionTarget.WiFi("127.0.0.1", 5000u))
            h.eventually("initial graph ready") {
                h.manager.connectionState == DeviceConnectionState.READY && h.sessions.current != null
            }
            assertions(h)
        } finally {
            h.container.close()
        }
    }
}
