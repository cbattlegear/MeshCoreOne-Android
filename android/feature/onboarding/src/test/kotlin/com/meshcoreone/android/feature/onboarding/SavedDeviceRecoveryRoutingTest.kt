// AndroidOnly: WP-303 A typed app recovery owns its dialog and cannot retain the preceding other-app retry action.
package com.meshcoreone.android.feature.onboarding

import com.meshcoreone.android.core.ui.UiText
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SavedDeviceRecoveryRoutingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun close() { scope.cancel() }

    @Test fun typedRecoveryDoesNotPresentASecondFailureOrKeepTheOtherAppRetry() {
        val device = UUID.randomUUID()
        val pairing = FakePairing(OnboardingConnectOutcome.Failed(UiText.Verbatim("other app"), device))
        val scan = DeviceScanCoordinator(scope, pairing, FakeDemo(), FakePermissions()) {
            error("A failed retry must not advance onboarding")
        }
        scan.retryConnection(device)
        assertEquals(device, scan.state.value.otherAppDeviceId)
        pairing.outcome = OnboardingConnectOutcome.Failed(UiText.Verbatim("repair required"), presentedByConnectionUi = true)
        scan.retryConnection(device)
        assertNull(scan.state.value.failure)
        assertNull(scan.state.value.otherAppDeviceId)
    }

    @Test fun genericFailureClearsAnObsoleteOtherAppRetry() {
        val device = UUID.randomUUID()
        val pairing = FakePairing(OnboardingConnectOutcome.Failed(UiText.Verbatim("other app"), device))
        val scan = DeviceScanCoordinator(scope, pairing, FakeDemo(), FakePermissions()) {}
        scan.retryConnection(device)
        pairing.outcome = OnboardingConnectOutcome.Failed(UiText.Verbatim("generic"))
        scan.retryConnection(UUID.randomUUID())
        assertNull(scan.state.value.otherAppDeviceId)
        assertEquals(UiText.Verbatim("generic"), scan.state.value.failure)
    }

    @Test fun genericFailureOnTheSameExplicitRetryKeepsItsKnownOtherAppTarget() {
        val device = UUID.randomUUID()
        val pairing = FakePairing(OnboardingConnectOutcome.Failed(UiText.Verbatim("other app"), device))
        val scan = DeviceScanCoordinator(scope, pairing, FakeDemo(), FakePermissions()) {}
        scan.retryConnection(device)
        pairing.outcome = OnboardingConnectOutcome.Failed(UiText.Verbatim("still unavailable"))
        scan.retryConnection(device)
        assertEquals(device, scan.state.value.otherAppDeviceId)
        assertEquals(UiText.Verbatim("still unavailable"), scan.state.value.failure)
    }
}
