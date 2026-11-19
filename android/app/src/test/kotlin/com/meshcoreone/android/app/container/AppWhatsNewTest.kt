// AndroidOnly: WP-318 Mounted local catalog/connection/persistence journey; not device or whole-WP acceptance.
// ReferenceInput: MC1Tests/State/WhatsNewStateTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.app.navigation.NavigationDestination
import com.meshcoreone.android.app.navigation.SettingsDetail
import com.meshcoreone.android.app.state.ConnectionUiSnapshot
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.datastore.StorageFailure
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.l10n.generated.AppWhatsNewStrings
import com.meshcoreone.android.feature.settings.app.whatsnew.WhatsNewCatalog
import com.meshcoreone.android.feature.settings.app.whatsnew.WhatsNewVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(application = Application::class, sdk = [31, 37], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class AppWhatsNewTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var storage: MeshCoreStorage
    private val connection = MutableStateFlow(ConnectionUiSnapshot())
    private val scanning = MutableStateFlow(false)
    private val navigation = NavigationCoordinator()
    private val title get() = activity.get().getString(AppWhatsNewStrings.whatsNewTitle)
    private val continueLabel get() = activity.get().getString(AppWhatsNewStrings.whatsNewContinueButton)

    @Before fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        activity.get().actionBar?.hide()
        activity.windowFocusChanged(true)
        storage = MeshCoreStorage.get(activity.get())
    }

    @Config(application = Application::class, sdk = [31, 37])
    class AppWhatsNewStartupBindingTest : RoomProcessTest() {
        @Test fun processSettingsFactoryEvaluatesThePersistedLaunchFlagBeforePresentation() = runTest {
            val storage = MeshCoreStorage.get(org.robolectric.RuntimeEnvironment.getApplication())
            val h = ContainerHarness(this, store)
            try {
                storage.preferences.set(AppStorageKey.hasCompletedOnboarding, true)
                storage.preferences.set(AppStorageKey.lastShownWhatsNewVersion, "1.4")
                val settings = AppSettingsFeature.create(h.container, storage, "1.5")
                val host = requireNotNull(settings.whatsNew)
                assertEquals(WhatsNewVersion(1, 5), host.state.pendingRelease.value?.version)
                assertEquals("1.4", storage.preferences.snapshot().stored(AppStorageKey.lastShownWhatsNewVersion))
                assertTrue(h.links.radios.isEmpty())
                host.state.markShown()
                assertEquals("1.5", storage.preferences.snapshot().stored(AppStorageKey.lastShownWhatsNewVersion))
            } finally {
                h.container.close()
                storage.close()
            }
        }
    }

    @After fun tearDown() {
        activity.pause().stop().destroy()
        runBlocking { storage.close() }
    }

    private fun launch(version: String = "1.5", screenshot: Boolean = false): AppWhatsNew = runBlocking {
        AppWhatsNew.create(
            storage.preferences, version, storage.preferences.get(AppStorageKey.hasCompletedOnboarding), screenshot,
        )
    }

    private fun baseline(): String? = runBlocking {
        storage.preferences.snapshot().stored(AppStorageKey.lastShownWhatsNewVersion)
    }

    private fun seed(onboarded: Boolean = true, baseline: String? = "1.4") = runBlocking {
        storage.preferences.set(AppStorageKey.hasCompletedOnboarding, onboarded)
        if (baseline == null) storage.preferences.remove(AppStorageKey.lastShownWhatsNewVersion)
        else storage.preferences.set(AppStorageKey.lastShownWhatsNewVersion, baseline)
    }

    private fun mount(host: AppWhatsNew) {
        compose.runOnUiThread {
            activity.get().setContent {
                MeshCoreTheme(motionScale = 0f) {
                    AppWhatsNewHost(
                        host, connection, scanning,
                        onSupport = { navigation.navigateToSetting(SettingsDetail.SUPPORT) },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun dismiss(host: AppWhatsNew) {
        compose.onNodeWithText(continueLabel).performClick()
        compose.waitUntil(10_000) { host.state.pendingRelease.value == null }
        compose.onNodeWithText(title).assertDoesNotExist()
    }

    @Test fun upgradeShowsActualCatalogAndPersistsOnlyOnContinueAcrossColdStart() {
        seed()
        val host = launch("1.5.7")
        mount(host)
        compose.onNodeWithText(title).assertIsDisplayed()
        for (item in WhatsNewCatalog.releases.single().items) {
            compose.onNodeWithText(activity.get().getString(item.titleResource)).assertExists()
        }
        assertEquals("1.4", baseline())
        dismiss(host)
        assertEquals("1.5.7", baseline())
        compose.runOnUiThread { activity.get().setContent {} }
        compose.waitForIdle()
        runBlocking { storage.close() }
        storage = MeshCoreStorage.get(activity.get())
        val coldStart = launch("1.5.7")
        mount(coldStart)
        assertNull(coldStart.state.pendingRelease.value)
        compose.onNodeWithText(title).assertDoesNotExist()
        assertEquals("1.5.7", baseline())
    }

    @Test fun connectionAlertAndScanPickerDeferWithoutConsumingThePendingRelease() {
        seed()
        val host = launch()
        connection.value = ConnectionUiSnapshot(showingConnectionFailedAlert = true)
        mount(host)
        compose.onNodeWithText(title).assertDoesNotExist()
        assertNotNull(host.state.pendingRelease.value)
        scanning.value = true
        connection.value = ConnectionUiSnapshot()
        compose.waitForIdle()
        compose.onNodeWithText(title).assertDoesNotExist()
        assertEquals("1.4", baseline())
        scanning.value = false
        compose.waitForIdle()
        compose.onNodeWithText(title).assertIsDisplayed()
        connection.value = ConnectionUiSnapshot(otherAppWarningDeviceId = java.util.UUID(0, 1))
        compose.waitForIdle()
        compose.onNodeWithText(title).assertDoesNotExist()
        assertEquals("1.4", baseline())
        connection.value = ConnectionUiSnapshot()
        compose.waitForIdle()
        connection.value = ConnectionUiSnapshot(
            pendingSystemPairingSetup = com.meshcoreone.android.core.connectivity.pairing.SystemPairingSetupPrompt(emptyList()),
        )
        compose.waitForIdle()
        compose.onNodeWithText(title).assertDoesNotExist()
        assertEquals("1.4", baseline())
        connection.value = ConnectionUiSnapshot()
        compose.waitForIdle()
        dismiss(host)
    }

    @Test fun remountRetainsPendingWithoutEvaluatingOrFinalizingAgain() {
        seed()
        val host = launch()
        mount(host)
        compose.runOnUiThread { activity.get().setContent {} }
        compose.waitForIdle()
        assertEquals("1.4", baseline())
        assertNotNull(host.state.pendingRelease.value)
        mount(host)
        compose.onNodeWithText(title).assertIsDisplayed()
        dismiss(host)
    }

    @Test fun systemBackPersistsThroughTheSameDismissalPath() {
        seed()
        val host = launch()
        mount(host)
        compose.onNodeWithText(title).assertIsDisplayed()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        compose.runOnIdle {
            dialog.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            dialog.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        compose.waitUntil(10_000) { host.state.pendingRelease.value == null }
        assertEquals("1.5", baseline())
    }

    @Test fun supportNavigatesToTheRealSettingsDestinationAndFinalizes() {
        seed()
        val host = launch()
        mount(host)
        compose.onNodeWithText(activity.get().getString(AppSettingsStrings.supportTitle)).performClick()
        compose.waitUntil(10_000) { host.state.pendingRelease.value == null }
        val destination = navigation.state.value.activeStack.last().destination as NavigationDestination.Setting
        assertEquals(SettingsDetail.SUPPORT, destination.selection)
        assertEquals("1.5", baseline())
    }

    @Test fun newInstallFinalizesBeforeOnboardingCompletionAndNeverShowsOnRemount() {
        seed(onboarded = false, baseline = null)
        val host = launch()
        assertEquals("1.5", baseline())
        runBlocking { storage.preferences.set(AppStorageKey.hasCompletedOnboarding, true) }
        mount(host)
        compose.onNodeWithText(title).assertDoesNotExist()
        assertNull(host.state.pendingRelease.value)
    }

    @Test fun patchAndMissingCatalogVersionsSuppressWithTheRealStore() {
        seed(baseline = "1.5")
        assertNull(launch("1.5.9").state.pendingRelease.value)
        assertEquals("1.5.9", baseline())
        assertNull(launch("2.0").state.pendingRelease.value)
        assertEquals("2.0", baseline())
    }

    @Test fun screenshotAndMalformedVersionNeverTouchTheBaseline() {
        seed()
        assertNull(launch(screenshot = true).state.pendingRelease.value)
        assertEquals("1.4", baseline())
        assertNull(launch("unknown").state.pendingRelease.value)
        assertEquals("1.4", baseline())
    }

    @Test fun onboardedWithoutBaselinePresentsTheCurrentEntry() {
        seed(baseline = null)
        val host = launch()
        assertEquals(WhatsNewVersion(1, 5), host.state.pendingRelease.value?.version)
        assertNull(baseline())
        mount(host)
        dismiss(host)
        assertEquals("1.5", baseline())
    }

    @Test fun failedPersistenceIsVisibleAndDoesNotClaimDismissal() {
        seed()
        val host = launch()
        mount(host)
        runBlocking { storage.close() }
        compose.onNodeWithText(continueLabel).performClick()
        compose.waitUntil(10_000) { host.failure.value != null }
        assertTrue(host.failure.value is StorageFailure)
        assertNotNull(host.state.pendingRelease.value)
        compose.onNodeWithText(activity.get().getString(AppLocalizableStrings.commonOk)).assertIsDisplayed()
        storage = MeshCoreStorage.get(activity.get())
        assertEquals("1.4", baseline())
    }
}
