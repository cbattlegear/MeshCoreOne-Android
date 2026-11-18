// AndroidOnly: WP-303 Real factory publication cannot resurrect a graph retired during its created callback.
// AndroidOnly: WP-311 Nodes observes actual registry publication and withdrawal without unrelated UI state changes.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import com.meshcoreone.android.feature.nodes.deps.NodesConnectionSnapshot
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FactoryPublicationIntegrationTest : RoomProcessTest() {
    @Test
    fun observableRegistryTracksTheLiveGraphWithoutHoldingLifecycleLocks() = runTest {
        val h = ContainerHarness(this, store)
        val lockProbe = CompletableDeferred<Boolean>()
        backgroundScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            h.sessions.currentSessions.collect { graph ->
                if (graph != null && !lockProbe.isCompleted) {
                    val released = CountDownLatch(1)
                    Thread {
                        try {
                            h.container.isCurrentSession(graph)
                        } finally {
                            released.countDown()
                        }
                    }.apply { isDaemon = true; start() }
                    lockProbe.complete(released.await(5, TimeUnit.SECONDS))
                }
            }
        }
        try {
            h.manager.connect(h.target(SessionLifecycleTestTarget.FIRST))
            h.settle()
            val first = assertNotNull(h.sessions.current)
            assertTrue(lockProbe.await(), "Registry observers must run outside lifecycle locks")
            assertSame(first, h.sessions.currentSessions.value)
            assertEquals(first.token, h.sessions.currentToken.value)

            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            assertNull(h.sessions.currentSessions.value)
            assertNull(h.sessions.currentToken.value)

            h.manager.connect(h.target(SessionLifecycleTestTarget.SECOND))
            h.settle()
            val second = assertNotNull(h.sessions.current)
            assertEquals(first.token.radioId, second.token.radioId)
            assertTrue(first.token != second.token)
            h.sessions.withdraw(first)
            assertSame(second, h.sessions.currentSessions.value, "A retired predecessor cannot clear its successor")
            assertEquals(second.token, h.sessions.currentToken.value)
        } finally {
            h.container.close()
        }
    }

    @Test
    fun retiredFactoryCannotPublishBeforeSuccessfulSuccessor() = runTest {
        assertRetiredFactoryPublication(successorFails = false)
    }

    @Test
    fun retiredFactoryCannotRemainCurrentAfterFailedSuccessor() = runTest {
        assertRetiredFactoryPublication(successorFails = true)
    }

    private suspend fun TestScope.assertRetiredFactoryPublication(successorFails: Boolean) {
        val created = CompletableDeferred<RadioSessionContainer>()
        val releaseCreated = CountDownLatch(1)
        val successorAllocated = CompletableDeferred<Unit>()
        val releaseSuccessor = CompletableDeferred<Unit>()
        val allocations = AtomicInteger()
        val unavailable = IllegalStateException("Successor link unavailable")
        val h = ContainerHarness(
            this,
            store,
            runtimeContext = Dispatchers.Default,
            onSessionCreated = { container ->
                if (created.complete(container)) {
                    check(releaseCreated.await(15, TimeUnit.SECONDS)) { "Created callback was not released" }
                }
            },
        )
        h.links.configureRadio = { radio ->
            if (allocations.incrementAndGet() > 1) {
                radio.connectGate = releaseSuccessor
                if (successorFails) radio.connectFailure = unavailable
                successorAllocated.complete(Unit)
            }
        }
        val observed = mutableListOf<NodesConnectionSnapshot>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            h.container.nodesFeature.session.connectionSnapshots().collect { observed += it }
        }
        try {
            val first = backgroundScope.async(Dispatchers.Default) {
                try {
                    h.manager.connect(h.target(SessionLifecycleTestTarget.FIRST))
                    null
                } catch (failure: Exception) { failure }
            }
            val retired = created.await()
            assertNull(h.sessions.current, "The created callback precedes registry publication")
            val successor = backgroundScope.async(Dispatchers.Default) {
                try {
                    h.manager.connect(h.target(SessionLifecycleTestTarget.SECOND))
                    null
                } catch (failure: Exception) { failure }
            }
            h.eventually("registered factory graph to finish teardown while its created callback is held") {
                h.lifecycle.tornDown.any { it === retired }
            }
            assertTrue(retired.isTornDown)
            assertEquals(0, retired.liveJobCount)
            assertNull(h.sessions.current)
            releaseCreated.countDown()
            successorAllocated.await()
            assertIs<CancellationException>(first.await(), "The superseded connect must not succeed")
            assertNull(h.sessions.current, "Returning from the held callback cannot republish the retired graph")
            assertTrue(observed.none { it.identity === retired }, "Nodes must not observe a retired factory graph")
            assertEquals(1, h.sessions.created)
            assertEquals(1, h.sessions.tornDown)
            assertEquals(0, h.sessions.outstanding)
            assertSame(retired, h.lifecycle.created.single())
            assertSame(retired, h.lifecycle.tornDown.single())
            releaseSuccessor.complete(Unit)
            val failure = successor.await()
            if (successorFails) {
                val propagated = assertIs<IllegalStateException>(failure)
                assertEquals(unavailable.message, propagated.message)
                // Coroutine debug stack recovery may copy the exception, retaining the original as its direct cause.
                assertSame(unavailable, if (propagated === unavailable) propagated else propagated.cause)
                assertEquals(DeviceConnectionState.DISCONNECTED, h.manager.connectionState)
                assertNull(h.sessions.current, "A failed successor cannot leave the retired graph as current")
                assertEquals(0, h.sessions.outstanding)
            } else {
                assertNull(failure)
                assertEquals(DeviceConnectionState.READY, h.manager.connectionState)
                val live = assertNotNull(h.sessions.current)
                h.eventually("nodes to observe registry publication without an unrelated UI change") {
                    observed.lastOrNull()?.identity === live
                }
                h.eventually("nodes to observe the ready device after registry publication") {
                    observed.lastOrNull()?.let {
                        it.identity === live && it.device == h.container.appState.connectedDevice &&
                            it.device != null && it.state == DeviceConnectionState.READY
                    } == true
                }
                assertFalse(live.isTornDown)
                assertSame(live.token, h.manager.snapshot.value.token)
                assertEquals(2, h.sessions.created)
                assertEquals(1, h.sessions.tornDown)
                assertEquals(1, h.sessions.outstanding)
            }
        } finally {
            releaseCreated.countDown()
            releaseSuccessor.complete(Unit)
            h.container.close()
        }
        assertNull(h.sessions.current)
        h.eventually("nodes to observe registry withdrawal") {
            observed.isNotEmpty() && observed.last().identity == null
        }
        assertEquals(0, h.sessions.outstanding)
        assertTrue(h.lifecycle.created.all { it.isTornDown && it.liveJobCount == 0 })
        assertTrue(h.links.radios.all { it.readers == 0 && !it.connected })
    }

    private object SessionLifecycleTestTarget {
        val FIRST: java.util.UUID = java.util.UUID.fromString("00000000-0000-0000-0000-00000000f001")
        val SECOND: java.util.UUID = java.util.UUID.fromString("00000000-0000-0000-0000-00000000f002")
    }
}
