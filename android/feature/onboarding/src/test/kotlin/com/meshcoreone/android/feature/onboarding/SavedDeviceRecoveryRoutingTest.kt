// AndroidOnly: WP-303 A typed app recovery owns its dialog and cannot retain the preceding other-app retry action.
package com.meshcoreone.android.feature.onboarding

import com.meshcoreone.android.core.ui.UiText
import java.util.UUID
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SavedDeviceRecoveryRoutingTest {
    @Test fun typedRecoveryDoesNotPresentASecondFailureOrKeepTheOtherAppRetry() = runTest {
        val device = UUID.randomUUID()
        val pairing = FakePairing(OnboardingConnectOutcome.Failed(UiText.Verbatim("other app"), device))
        val scan = DeviceScanCoordinator(backgroundScope, pairing, FakeDemo(), FakePermissions()) {
            error("A failed retry must not advance onboarding")
        }
        scan.retryConnection(device)
        runCurrent()
        assertEquals(device, scan.state.value.otherAppDeviceId)
        pairing.outcome = OnboardingConnectOutcome.Failed(UiText.Verbatim("repair required"), presentedByConnectionUi = true)
        scan.retryConnection(device)
        runCurrent()
        assertNull(scan.state.value.failure)
        assertNull(scan.state.value.otherAppDeviceId)
    }

    @Test fun genericFailureClearsAnObsoleteOtherAppRetry() = runTest {
        val device = UUID.randomUUID()
        val pairing = FakePairing(OnboardingConnectOutcome.Failed(UiText.Verbatim("other app"), device))
        val scan = DeviceScanCoordinator(backgroundScope, pairing, FakeDemo(), FakePermissions()) {}
        scan.retryConnection(device)
        runCurrent()
        pairing.outcome = OnboardingConnectOutcome.Failed(UiText.Verbatim("generic"))
        scan.retryConnection(device)
        runCurrent()
        assertNull(scan.state.value.otherAppDeviceId)
        assertEquals(UiText.Verbatim("generic"), scan.state.value.failure)
    }
}
