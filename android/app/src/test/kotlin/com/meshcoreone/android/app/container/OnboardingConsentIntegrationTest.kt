// AndroidOnly: WP-303 Actual onboarding host through the production pairing coordinator and Room-backed App graph.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.container.onboarding.AppStateOnboardingHost
import com.meshcoreone.android.feature.onboarding.OnboardingConnectOutcome
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingConsentIntegrationTest : RoomProcessTest() {
    @Test fun actualOnboardingHostWaitsForConsentAndRejectingPreservesTheRegistry() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val id = UUID.randomUUID()
            h.pairing.registered[id] = "Leftover radio"
            val request = async { AppStateOnboardingHost(h.container).startPairing() }
            h.eventually("mounted setup state") { h.appState.connectionUI.pendingSystemPairingSetup != null }
            assertFalse(request.isCompleted)
            assertEquals(0, h.pairing.removeCalls)
            assertTrue(h.links.links.isEmpty())
            h.appState.cancelSystemPairingSetup()
            assertEquals(OnboardingConnectOutcome.Cancelled, request.await())
            assertEquals(mapOf(id to "Leftover radio"), h.pairing.registered)
            assertFalse(h.container.connectionPort.isPairingFlowActive)
        } finally { h.container.close() }
    }

    @Test fun actualOnboardingHostOnlyRemovesConfirmedUnsavedAssociations() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val id = UUID.randomUUID()
            h.pairing.registered[id] = "Leftover radio"
            val request = async { AppStateOnboardingHost(h.container).startPairing() }
            h.eventually("setup prompt") { h.appState.connectionUI.pendingSystemPairingSetup != null }
            val sibling = UUID.randomUUID()
            h.pairing.registered[sibling] = "Unconfirmed sibling"
            assertNotNull(h.appState.confirmSystemPairingSetup()).join()
            request.await()
            assertEquals(listOf(id), h.pairing.removed)
            assertEquals(mapOf(sibling to "Unconfirmed sibling"), h.pairing.registered)
            assertFalse(h.appState.connectionUI.isBusy)
            assertFalse(h.container.connectionPort.isPairingFlowActive)
        } finally { h.container.close() }
    }

    @Test fun actualOnboardingHostCancellationReleasesConsentWithoutTouchingTheRegistry() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val id = UUID.randomUUID()
            h.pairing.registered[id] = "Leftover radio"
            val request = async { AppStateOnboardingHost(h.container).startPairing() }
            h.eventually("setup prompt") { h.appState.connectionUI.pendingSystemPairingSetup != null }
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            assertNull(h.appState.connectionUI.pendingSystemPairingSetup)
            assertEquals(mapOf(id to "Leftover radio"), h.pairing.registered)
            assertFalse(h.appState.connectionUI.isBusy)
            assertFalse(h.container.connectionPort.isPairingFlowActive)
        } finally { h.container.close() }
    }

    @Test fun decliningWhileConnectedDoesNotReportTheOldRadioAsANewPairingSuccess() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val live = assertNotNull(h.manager.connectedDevice)
            val graph = assertNotNull(h.sessions.current)
            h.pairing.registered[UUID.randomUUID()] = "Leftover radio"
            val request = async { AppStateOnboardingHost(h.container).startPairing() }
            h.eventually("setup prompt") { h.appState.connectionUI.pendingSystemPairingSetup != null }
            h.appState.cancelSystemPairingSetup()
            assertEquals(OnboardingConnectOutcome.Cancelled, request.await())
            assertEquals(live.id, h.manager.connectedDevice?.id)
            assertEquals(graph, h.sessions.current)
            assertFalse(graph.isTornDown)
            assertEquals(0, h.pairing.removeCalls)
        } finally { h.container.close() }
    }
}
