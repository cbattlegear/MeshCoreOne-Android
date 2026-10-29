// PortedFrom: MC1Services/Tests/MC1ServicesTests/ConnectionManagerBLEHealthTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class ListenerRecoveryIntegrationTest : RoomProcessTest() {
    @Test
    fun stoppedPollerRecoversWithoutReplacingHealthyGraphOrCollectors() = runTest {
        withConnectedGraph { harness, graph ->
            val jobs = harness.appState.activeSessionJobCount
            val subscribers = graph.syncCoordinator.dataEventBroadcaster.subscriberCount
            graph.messagePollingService.stopAutoFetch()
            assertTrue(graph.isEventMonitoringActive)
            assertFalse(graph.messagePollingService.isAutoFetching)

            harness.manager.checkBLEConnectionHealth()
            harness.settle()

            assertTrue(graph.messagePollingService.isAutoFetching)
            assertTrue(graph.isEventMonitoringActive)
            assertSame(graph, harness.sessions.current)
            assertEquals(1, harness.sessions.created)
            assertEquals(jobs, harness.appState.activeSessionJobCount)
            assertEquals(subscribers, graph.syncCoordinator.dataEventBroadcaster.subscriberCount)
        }
    }

    @OriginalCase("ConnectionManagerBLEHealthTests::BLE connected ready state restarts missing event monitoring()")
    @Test
    fun stoppedMonitorsAndPollerRecoverTogether() = runTest {
        withConnectedGraph { harness, graph ->
            graph.stopEventMonitoring()
            assertFalse(graph.isEventMonitoringActive)
            assertFalse(graph.messagePollingService.isAutoFetching)

            harness.manager.checkBLEConnectionHealth()
            harness.settle()

            assertTrue(graph.isEventMonitoringActive)
            assertTrue(graph.messagePollingService.isAutoFetching)
            assertSame(graph, harness.sessions.current)
            assertEquals(1, harness.sessions.created)
        }
    }

    @OriginalCase("ConnectionManagerBLEHealthTests::BLE connected with healthy listeners does not rebuild()")
    @Test
    fun concurrentHealthRecoveryPreservesHealthyListenerOwnership() = runTest {
        withConnectedGraph { harness, graph ->
            val subscribers = graph.syncCoordinator.dataEventBroadcaster.subscriberCount
            val jobs = harness.appState.activeSessionJobCount
            List(8) { async { harness.manager.checkBLEConnectionHealth() } }.awaitAll()
            harness.settle()

            assertSame(graph, harness.sessions.current)
            assertEquals(1, harness.sessions.created)
            assertEquals(subscribers, graph.syncCoordinator.dataEventBroadcaster.subscriberCount)
            assertEquals(jobs, harness.appState.activeSessionJobCount)
            assertTrue(graph.messagePollingService.isAutoFetching)
        }
    }

    @Test
    fun concurrentRecoveryOfStoppedPollingKeepsOneLiveGraph() = runTest {
        withConnectedGraph { harness, graph ->
            graph.messagePollingService.stopAutoFetch()
            List(8) { async { harness.manager.checkBLEConnectionHealth() } }.awaitAll()
            harness.settle()
            assertTrue(graph.messagePollingService.isAutoFetching)
            assertTrue(graph.isEventMonitoringActive)
            assertEquals(1, harness.sessions.outstanding)
            assertEquals(1, harness.links.radios.single().readers)
        }
    }

    @Test
    fun closedGraphCannotRestartPollingOrEventMonitoring() = runTest {
        val harness = ContainerHarness(this, store, observeBleLink = true)
        try {
            harness.manager.connect(harness.target())
            harness.settle()
            harness.assertReady()
            val graph = checkNotNull(harness.sessions.current)
            harness.manager.disconnect(com.meshcoreone.android.core.runtime.RuntimeDisconnectReason.USER_INITIATED)
            graph.ensureListeners()
            harness.settle()

            assertTrue(graph.isTornDown)
            assertFalse(graph.isEventMonitoringActive)
            assertFalse(graph.messagePollingService.isAutoFetching)
            assertEquals(0, graph.liveJobCount)
            assertEquals(0, graph.syncCoordinator.dataEventBroadcaster.subscriberCount)
            assertEquals(0, harness.sessions.outstanding)
        } finally {
            harness.container.close()
        }
    }

    private suspend fun TestScope.withConnectedGraph(
        block: suspend TestScope.(ContainerHarness, RadioSessionContainer) -> Unit,
    ) {
        val harness = ContainerHarness(this, store, observeBleLink = true)
        try {
            harness.manager.connect(harness.target())
            harness.settle()
            harness.assertReady()
            block(harness, checkNotNull(harness.sessions.current))
        } finally {
            harness.container.close()
        }
    }
}
