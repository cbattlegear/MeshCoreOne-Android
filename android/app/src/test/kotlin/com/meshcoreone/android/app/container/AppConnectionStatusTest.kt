// PortedFrom: MC1Tests/AppState/ConnectionUIStateTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/AppState/StatusPillStateTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as S
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import kotlin.test.assertEquals

class AppConnectionStatusTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun mountedStatusUsesActualSnapshotsPreservesPriorityAndExposesDisconnectedAction() = runTest {
        val h = ContainerHarness(this, store)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var radioSetupRequests = 0
        fun text(resource: Int) = activity.get().getString(resource)
        try {
            activity.get().setContent { MeshCoreTheme { AppConnectionStatus(h.container) { radioSetupRequests++ } } }
            val ui = h.appState.connectionUI
            compose.onNodeWithText(text(S.commonStatusReady)).assertDoesNotExist()
            compose.onNodeWithText(text(S.commonStatusDisconnected)).assertDoesNotExist()
            compose.runOnIdle { ui.showReadyToastBriefly() }
            compose.onNodeWithText(text(S.commonStatusReady)).assertIsDisplayed()
            compose.runOnIdle { ui.syncActivityCount = 1 }
            compose.onNodeWithText(text(S.commonStatusSyncing)).assertIsDisplayed()
            compose.onNodeWithText(text(S.commonStatusReady)).assertDoesNotExist()
            compose.runOnIdle { ui.showSyncFailedPill() }
            compose.onNodeWithText(text(S.statusPillSyncFailed)).assertIsDisplayed()
            compose.onNodeWithText(text(S.commonStatusSyncing)).assertDoesNotExist()
            compose.runOnIdle { ui.hideSyncFailedPill(); ui.syncActivityCount = 0 }
            compose.onNodeWithText(text(S.commonStatusReady)).assertIsDisplayed()
            compose.runOnIdle { ui.hideReadyToast() }
            compose.onNodeWithText(text(S.commonStatusReady)).assertDoesNotExist()

            val gate = CompletableDeferred<Unit>()
            h.links.configureRadio = { it.connectGate = gate }
            val connecting = backgroundScope.async { h.manager.connect(h.target()) }
            h.eventually("actual runtime connecting") { h.manager.connectionState == DeviceConnectionState.CONNECTING }
            compose.onNodeWithText(text(S.commonStatusConnecting)).assertIsDisplayed()
            gate.complete(Unit)
            h.eventually("actual runtime ready") { h.manager.connectionState == DeviceConnectionState.READY }
            connecting.await()
            testScheduler.runCurrent()
            compose.onNodeWithText(text(S.commonStatusReady)).assertIsDisplayed()

            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(1001)
            testScheduler.runCurrent()
            compose.onNodeWithText(text(S.commonStatusReady)).assertIsDisplayed()
            compose.runOnIdle { ui.hideReadyToast() }
            compose.onNodeWithText(text(S.commonStatusDisconnected)).assertIsDisplayed().performClick()
            assertEquals(1, radioSetupRequests)
        } finally {
            activity.pause().stop().destroy()
            h.container.close()
        }
    }
}
