// AndroidOnly: WP-305 Exercise the mounted device-menu tip host against real process preferences.
package com.meshcoreone.android.app.container.onboarding

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.AppDeviceMenuTipHost
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.datastore.StorageIssueReporter
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.ui.SharedTip
import com.meshcoreone.android.core.ui.ShowOnceTips
import com.meshcoreone.android.core.ui.TipHostState
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h800dp")
class DeviceMenuTipHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var host: ActivityController<ComponentActivity>

    @After fun closeHost() {
        if (::host.isInitialized) host.pause().stop().destroy()
    }

    private fun context(directory: File): Context {
        val application = ApplicationProvider.getApplicationContext<Context>()
        return object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
    }

    private fun setContent(content: @Composable () -> Unit) {
        host.get().setContent {
            MeshCoreTheme(motionScale = 0f) { content() }
        }
    }

    @Test fun mountedHostWaitsForConnectionDismissesOnceAndSurvivesStorageReopen() = runBlocking {
        withTimeout(20_000) {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            val context = context(temporary.newFolder())
            var activeStorage = MeshCoreStorage.get(context)
            var ownerJob = SupervisorJob()
            var owner = CoroutineScope(ownerJob + Dispatchers.Default)
            try {
                val tips = ShowOnceTips(activeStorage.preferences)
                tips.completeOnboarding()
                val presenter = DeviceMenuTipPresenter(tips, owner)
                val presenterState = mutableStateOf(presenter)
                val selectedTab = mutableStateOf(AppTab.SETTINGS)
                val connected = mutableStateOf(false)
                setContent {
                    AppDeviceMenuTipHost(
                        presenterState.value,
                        TipHostState(selectedTab.value, true, connected.value, liveStatusPresented = false),
                    )
                }
                compose.waitForIdle()
                assertFalse(tips.hasDisplayed(SharedTip.DEVICE_MENU))
                compose.onNodeWithTag("device-menu-tip").assertDoesNotExist()

                compose.runOnIdle { selectedTab.value = AppTab.TOOLS }
                compose.waitForIdle()
                assertFalse(tips.hasDisplayed(SharedTip.DEVICE_MENU))
                compose.onNodeWithTag("device-menu-tip").assertDoesNotExist()

                compose.runOnIdle { connected.value = true }
                val connectedHost = TipHostState(
                    AppTab.TOOLS,
                    deviceMenuVisible = true,
                    connectedDevicePresent = true,
                    liveStatusPresented = false,
                )
                presenter.consider(connectedHost)?.join()
                assertTrue(
                    "Connected tip claim failed: ${presenter.presentation.value.failure}",
                    presenter.presentation.value.visible,
                )
                compose.waitForIdle()
                val title = host.get().getString(AppChatsStrings.chatsTipDeviceMenuTitle)
                val dismiss = host.get().getString(AppLocalizableStrings.commonOk)
                compose.onNodeWithTag("device-menu-tip").assertIsDisplayed()
                compose.onNodeWithText(title)
                    .assertIsDisplayed()
                    .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                compose.onNodeWithText(dismiss).assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("device-menu-tip").assertDoesNotExist()
                assertTrue(tips.hasDisplayed(SharedTip.DEVICE_MENU))

                ownerJob.cancelAndJoin()
                activeStorage.close()
                activeStorage = MeshCoreStorage.get(context)
                ownerJob = SupervisorJob()
                owner = CoroutineScope(ownerJob + Dispatchers.Default)
                val reopenedTips = ShowOnceTips(activeStorage.preferences)
                compose.runOnIdle {
                    presenterState.value = DeviceMenuTipPresenter(reopenedTips, owner)
                    selectedTab.value = AppTab.SETTINGS
                }
                compose.waitForIdle()
                compose.onNodeWithTag("device-menu-tip").assertDoesNotExist()
                assertTrue(reopenedTips.hasDisplayed(SharedTip.DEVICE_MENU))
            } finally {
                ownerJob.cancelAndJoin()
                activeStorage.close()
            }
        }
    }

    @Test fun mountedHostSurfacesAndRetriesARealPreferenceFailureWithoutConsumingTheTip() = runBlocking {
        withTimeout(20_000) {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            val context = context(temporary.newFolder())
            val failures = AtomicInteger()
            val retryReported = CompletableDeferred<Unit>()
            val storage = MeshCoreStorage.get(context, StorageIssueReporter {
                if (failures.incrementAndGet() >= 2) retryReported.complete(Unit)
            })
            val tips = ShowOnceTips(storage.preferences)
            tips.completeOnboarding()
            storage.close()

            val ownerJob = SupervisorJob()
            val owner = CoroutineScope(ownerJob + Dispatchers.Default)
            try {
                val presenter = DeviceMenuTipPresenter(tips, owner)
                val hostState = TipHostState(
                    AppTab.CHATS,
                    deviceMenuVisible = true,
                    connectedDevicePresent = true,
                    liveStatusPresented = false,
                )
                presenter.consider(hostState)?.join()
                assertTrue("The closed preference store should surface a retryable failure",
                    presenter.presentation.value.failure != null)
                setContent {
                    AppDeviceMenuTipHost(presenter, hostState)
                }
                compose.waitForIdle()
                val retry = host.get().getString(AppSettingsStrings.alertRetryRetry)
                compose.onNodeWithText(retry)
                    .assertIsDisplayed()
                    .assertHasClickAction()
                    .assertHeightIsAtLeast(48.dp)
                    .performClick()
                withTimeout(5_000) { retryReported.await() }
                assertFalse(presenter.presentation.value.visible)

                val reopened = MeshCoreStorage.get(context)
                try {
                    val reopenedTips = ShowOnceTips(reopened.preferences)
                    assertFalse(reopenedTips.hasDisplayed(SharedTip.DEVICE_MENU))
                } finally {
                    reopened.close()
                }
            } finally {
                ownerJob.cancelAndJoin()
            }
        }
    }
}
