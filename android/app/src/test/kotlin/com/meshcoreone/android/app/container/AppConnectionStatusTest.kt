// PortedFrom: MC1Tests/AppState/ConnectionUIStateTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// HistoricalPortedFrom: MC1Tests/AppState/StatusPillStateTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as S
import com.meshcoreone.android.core.ui.ShowOnceTips
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.Robolectric

class AppConnectionStatusTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun mountedStatusUsesActualSnapshotsPreservesPriorityAndExposesDisconnectedAction() = runTest {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val directory = temporary.newFolder()
        val application = ApplicationProvider.getApplicationContext<Context>()
        val storageContext = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = directory
        }
        val storage = MeshCoreStorage.get(storageContext)
        val h = ContainerHarness(this, store, deviceMenuTips = ShowOnceTips(storage.preferences))
        var radioSetupRequests = 0
        fun text(resource: Int) = activity.get().getString(resource)
        try {
            h.container.start().join()
            h.settle()
            activity.get().setContent {
                MeshCoreTheme {
                    AppConnectionStatus(h.container, AppTab.CHATS, tipHostVisible = true) { radioSetupRequests++ }
                }
            }
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

            h.links.links.last().callbacks!!.onDisconnected(IllegalStateException("link lost"))
            h.eventually("unexpected connection loss reaches the mounted state", virtualBudgetMillis = 0) {
                h.manager.connectionState == DeviceConnectionState.DISCONNECTED && h.sessions.current == null
            }
            compose.runOnIdle { ui.showReadyToastBriefly() }
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
            storage.close()
        }
    }
}
