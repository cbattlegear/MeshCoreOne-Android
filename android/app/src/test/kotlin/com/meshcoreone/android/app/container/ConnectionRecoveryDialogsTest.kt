// PortedFrom: MC1/ContentView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.meshcoreone.android.app.state.AppState
import com.meshcoreone.android.core.connectivity.pairing.PairingError
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as S
import com.meshcoreone.android.core.runtime.LinkFailure
import com.meshcoreone.android.core.ui.UiText
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w400dp-h800dp")
class ConnectionRecoveryDialogsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val port = FakeConnectionPort()
    private val id = UUID.randomUUID()
    private lateinit var state: AppState

    @Before fun mount() {
        state = appStateOf(scope, port)
        activity.setup().visible().get().setContent { MeshCoreTheme { AppConnectionRecoveryDialogs(state) } }
    }

    @After fun close() {
        activity.pause().stop().destroy()
        scope.cancel()
    }

    private fun text(resource: Int) = activity.get().getString(resource)

    @Test fun authenticationRecoveryRequiresConfirmationAndOffersNoNonDestructiveRetry() {
        compose.runOnIdle { state.handleAuthenticationFailure(id, isAppActive = true) }
        compose.onNodeWithText(text(S.alertPairingFailedTitle)).assertIsDisplayed()
        compose.onNodeWithText(text(S.commonTryAgain)).assertDoesNotExist()
        compose.onNodeWithText(text(S.alertConnectionFailedRemoveAndRetry)).assertIsDisplayed()
        assertTrue(port.calls.none { it.startsWith("removeFailed:") })
        compose.onNodeWithText(text(S.commonCancel)).performClick()
        assertNull(state.connectionUI.failedPairingDeviceId)
        assertFalse(state.connectionUI.showingConnectionFailedAlert)
        assertTrue(port.calls.none { it.startsWith("removeFailed:") })
    }

    @Test fun confirmedAuthenticationRecoveryInvokesTheRealPairingFlowOnce() {
        compose.runOnIdle { state.handleAuthenticationFailure(id, isAppActive = true) }
        compose.onNodeWithText(text(S.alertConnectionFailedRemoveAndRetry)).performClick()
        compose.waitForIdle()
        assertEquals(1, port.calls.count { it == "removeFailed:$id" })
        assertTrue(state.connectionUI.shouldShowPickerOnForeground)
        assertNull(state.connectionUI.failedPairingDeviceId)
    }

    @Test fun transientRecoveryRetriesWithoutRemovingTheBond() {
        compose.runOnIdle {
            state.connectionUI.presentPairingFailure(PairingError.ConnectionFailed(id, IllegalStateException("temporary"), false))
        }
        compose.onNodeWithText(text(S.commonTryAgain)).performClick()
        compose.waitForIdle()
        assertEquals(1, port.calls.count { it == "connect:$id:true" })
        assertTrue(port.calls.none { it.startsWith("removeFailed:") })
    }

    @Test fun synchronousConfirmedRemovalFailureStaysVisibleAfterTheClickReturns() {
        compose.runOnIdle {
            port.removeFailure = IllegalStateException("Removal failed immediately")
            state.handleAuthenticationFailure(id, isAppActive = true)
        }
        compose.onNodeWithText(text(S.alertConnectionFailedRemoveAndRetry)).performClick()
        compose.onNodeWithText("Removal failed immediately").assertIsDisplayed()
        compose.onNodeWithText(text(S.alertConnectionFailedRemoveAndRetry)).assertDoesNotExist()
        assertTrue(state.connectionUI.showingConnectionFailedAlert)
        assertEquals(1, port.calls.count { it == "removeFailed:$id" })
        assertFalse(state.connectionUI.shouldShowPickerOnForeground)
    }

    @Test fun genericFailureDoesNotInheritTheDestructivePairingActions() {
        compose.runOnIdle {
            state.handleAuthenticationFailure(id, isAppActive = true)
            state.connectionUI.presentConnectionFailure(UiText.Verbatim("generic failure"))
        }
        compose.onNodeWithText("generic failure").assertIsDisplayed()
        compose.onNodeWithText(text(S.alertConnectionFailedRemoveAndRetry)).assertDoesNotExist()
        compose.onNodeWithText(text(S.commonOk)).performClick()
        assertFalse(state.connectionUI.showingConnectionFailedAlert)
    }

    @Test fun otherAppWarningHasOnlyAnAcknowledgement() {
        compose.runOnIdle { state.connectionUI.presentSavedDeviceConnectFailure(id, LinkFailure.DeviceConnectedToOtherApp()) }
        compose.onNodeWithText(text(S.alertCouldNotConnectOtherAppMessage)).assertIsDisplayed()
        compose.onNodeWithText(text(S.alertConnectionFailedRemoveAndRetry)).assertDoesNotExist()
        compose.onNodeWithText(text(S.commonOk)).performClick()
        assertNull(state.connectionUI.otherAppWarningDeviceId)
        assertTrue(port.calls.none { it.startsWith("removeFailed:") })
    }
}
