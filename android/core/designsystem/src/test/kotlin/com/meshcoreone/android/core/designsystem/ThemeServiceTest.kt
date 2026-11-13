// PortedFrom: MC1Tests/ThemeServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/AppStateThemeWiringTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.designsystem

import android.content.Context
import android.content.ContextWrapper
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.datastore.AppearanceStorageKey
import com.meshcoreone.android.core.datastore.BackupPreferenceSnapshot
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.datastore.PreferenceValue
import com.meshcoreone.android.core.datastore.StorageIssueReporter
import com.meshcoreone.android.core.datastore.StorageProblem
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ThemeServiceTest {
    @get:Rule val temporary = TemporaryFolder()

    internal class Harness(val directory: File) {
        val application: Context = ApplicationProvider.getApplicationContext()
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        val storageFailures = ConcurrentLinkedQueue<com.meshcoreone.android.core.datastore.StorageFailure>()
        val themeFailures = ConcurrentLinkedQueue<ThemeServiceFailure>()
        val parent = SupervisorJob()
        val processScope = CoroutineScope(parent + Dispatchers.Default)
        val store = MeshCoreStorage.get(context, StorageIssueReporter(storageFailures::add))
        suspend fun open(): ThemeService = ThemeService.create(store.preferences, processScope, ThemeIssueReporter(themeFailures::add))
        suspend fun close() {
            parent.cancelAndJoin()
            store.close()
        }
    }

    private suspend fun <T> harness(before: suspend (Harness) -> Unit = {}, block: suspend (Harness, ThemeService) -> T): T {
        val h = Harness(temporary.newFolder())
        var service: ThemeService? = null
        return try {
            before(h)
            val opened = h.open().also { service = it }
            block(h, opened)
        } finally {
            service?.close()
            h.close()
        }
    }
    private fun ready(service: ThemeService): ThemeServiceState.Ready = assertIs<ThemeServiceState.Ready>(service.state.value)
    private suspend fun restore(h: Harness, theme: String? = null, scheme: String? = null) {
        h.store.preferences.remove(AppearanceStorageKey.selectedThemeID)
        h.store.preferences.remove(AppearanceStorageKey.appColorSchemePreference)
        val values = buildMap {
            theme?.let { put(AppearanceStorageKey.selectedThemeID.rawValue, PreferenceValue.StringValue(it)) }
            scheme?.let { put(AppearanceStorageKey.appColorSchemePreference.rawValue, PreferenceValue.StringValue(it)) }
        }
        assertEquals(values.keys, h.store.backupPreferences.restoreMissing(BackupPreferenceSnapshot(values)).toSet())
    }

    @OriginalCase("ThemeServicePureTests::missing selectedThemeID uses default in memory and does NOT write back()")
    @Test fun missingThemeDoesNotWriteDefault() = runBlocking<Unit> {
        harness { h, s ->
            assertEquals(ThemeId.DEFAULT, ready(s).selection.current.id)
            assertFalse(h.store.preferences.snapshot().contains(AppearanceStorageKey.selectedThemeID))
        }
    }
    @OriginalCase("ThemeServicePureTests::unknown selectedThemeID falls back to default and overwrites the stored value()")
    @Test fun unknownStoredThemeIsExplicitlyCorrected() = runBlocking<Unit> {
        harness(before = { it.store.preferences.set(AppearanceStorageKey.selectedThemeID, "ghost-theme") }) { h, s ->
            assertEquals(ThemeId.DEFAULT, ready(s).selection.current.id)
            assertEquals("default", h.store.preferences.get(AppearanceStorageKey.selectedThemeID))
            assertEquals(listOf("selectedThemeID"), ready(s).correctedKeys)
        }
    }
    @OriginalCase("ThemeServicePureTests::cold start retains a persisted paid theme even with empty ownership()")
    @Test fun coldStartRetainsNamedThemeWithoutAnyEntitlementService() = runBlocking<Unit> {
        harness(before = { it.store.preferences.set(AppearanceStorageKey.selectedThemeID, "ember") }) { h, s ->
            assertEquals(ThemeId.EMBER, ready(s).selection.current.id)
            assertEquals("ember", h.store.preferences.get(AppearanceStorageKey.selectedThemeID))
            assertTrue(ready(s).correctedKeys.isEmpty())
        }
    }
    @OriginalCase("ThemeServicePureTests::with no purchases, only the default theme is available and paid themes throw on setCurrent()")
    @Test fun approvedUnlockedAdaptationMakesAllElevenSelectableWithoutPurchases() = runBlocking<Unit> {
        harness { h, s ->
            assertEquals(11, s.availableToCurrentUser.size)
            for (theme in s.availableToCurrentUser) {
                s.setCurrent(theme.id)
                assertEquals(theme.id, ready(s).selection.current.id)
                assertEquals(theme.id.rawValue, h.store.preferences.get(AppearanceStorageKey.selectedThemeID))
            }
        }
    }
    @OriginalCase("ThemeServicePureTests::missing appColorSchemePreference uses .system without writing back()")
    @Test fun missingSchemeDoesNotWriteDefault() = runBlocking<Unit> {
        harness { h, s ->
            assertEquals(AppColorSchemePreference.SYSTEM, ready(s).selection.colorSchemePreference)
            assertFalse(h.store.preferences.snapshot().contains(AppearanceStorageKey.appColorSchemePreference))
        }
    }
    @OriginalCase("ThemeServicePureTests::unknown appColorSchemePreference falls back to .system and overwrites()")
    @Test fun unknownStoredSchemeIsExplicitlyCorrected() = runBlocking<Unit> {
        harness(before = { it.store.preferences.set(AppearanceStorageKey.appColorSchemePreference, "auto") }) { h, s ->
            assertEquals(AppColorSchemePreference.SYSTEM, ready(s).selection.colorSchemePreference)
            assertEquals("system", h.store.preferences.get(AppearanceStorageKey.appColorSchemePreference))
            assertEquals(listOf("appColorSchemePreference"), ready(s).correctedKeys)
        }
    }
    @OriginalCase("ThemeServicePureTests::setColorSchemePreference persists the raw value and updates state()")
    @Test fun schemeChangesArePersistedBeforeSuccess() = runBlocking<Unit> {
        harness { h, s ->
            s.setColorSchemePreference(AppColorSchemePreference.DARK)
            assertEquals(AppColorSchemePreference.DARK, ready(s).selection.colorSchemePreference)
            assertEquals("dark", h.store.preferences.get(AppearanceStorageKey.appColorSchemePreference))
        }
    }
    @OriginalCase("ThemeServicePureTests::effectiveColorScheme: default theme defers to the preference()")
    @Test fun defaultEffectiveSchemeUsesAllThreePreferences() = runBlocking<Unit> {
        harness { _, s ->
            for (preference in AppColorSchemePreference.entries) {
                s.setColorSchemePreference(preference)
                assertEquals(preference.colorScheme, ready(s).selection.effectiveColorScheme)
            }
        }
    }
    @OriginalCase("ThemeServicePureTests::effectiveColorScheme: Ember forces .dark regardless of the preference()")
    @Test fun emberEffectiveSchemeAlwaysDark() = runBlocking<Unit> {
        harness(before = { it.store.preferences.set(AppearanceStorageKey.selectedThemeID, "ember") }) { _, s ->
            for (preference in AppColorSchemePreference.entries) {
                s.setColorSchemePreference(preference)
                assertEquals(ColorScheme.DARK, ready(s).selection.effectiveColorScheme)
            }
        }
    }
    @OriginalCase("ThemeServicePureTests::refreshFromUserDefaults adopts an externally written scheme and retains the theme while unloaded()")
    @Test fun refreshUsesActualExternalPreferenceWrites() = runBlocking<Unit> {
        harness { h, s ->
            h.store.preferences.update {
                this[AppearanceStorageKey.selectedThemeID] = "fern"
                this[AppearanceStorageKey.appColorSchemePreference] = "dark"
            }
            val updated = s.refreshFromPreferences()
            assertEquals(ThemeId.FERN, updated.selection.current.id)
            assertEquals(AppColorSchemePreference.DARK, updated.selection.colorSchemePreference)
        }
    }
    @OriginalCase("ThemeServicePureTests::refreshFromUserDefaults does not revert a persisted theme while the store is unloaded()")
    @Test fun refreshNeverAppliesRemovedOwnershipGates() = runBlocking<Unit> {
        harness(before = { it.store.preferences.set(AppearanceStorageKey.selectedThemeID, "ember") }) { h, s ->
            assertEquals(ThemeId.EMBER, s.refreshFromPreferences().selection.current.id)
            assertEquals("ember", h.store.preferences.get(AppearanceStorageKey.selectedThemeID))
        }
    }
    @OriginalCase("ThemeServicePureTests::refreshFromUserDefaults overwrites an unknown selectedThemeID with the default()")
    @Test fun refreshCorrectsUnknownRestoreTheme() = runBlocking<Unit> {
        harness { h, s ->
            s.setCurrent(ThemeId.MARINE)
            h.store.preferences.set(AppearanceStorageKey.selectedThemeID, "future-build-theme-id")
            val update = s.refreshFromPreferences()
            assertEquals(ThemeId.DEFAULT, update.selection.current.id)
            assertEquals("default", h.store.preferences.get(AppearanceStorageKey.selectedThemeID))
        }
    }
    @OriginalCase("ThemeServicePureTests::refreshFromUserDefaults overwrites an unknown color-scheme preference with .system()")
    @Test fun refreshCorrectsUnknownRestoreScheme() = runBlocking<Unit> {
        harness { h, s ->
            h.store.preferences.set(AppearanceStorageKey.appColorSchemePreference, "auto")
            assertEquals(AppColorSchemePreference.SYSTEM, s.refreshFromPreferences().selection.colorSchemePreference)
            assertEquals("system", h.store.preferences.get(AppearanceStorageKey.appColorSchemePreference))
        }
    }
    @OriginalCase("ThemeServicePureTests::restore-origin downgrade: an unknown backed-up scheme falls back to .system on init()")
    @Test fun actualBackupPreferenceRestoreUnknownScheme() = runBlocking<Unit> {
        harness(before = { restore(it, scheme = "auto") }) { h, s ->
            assertEquals(AppColorSchemePreference.SYSTEM, ready(s).selection.colorSchemePreference)
            assertEquals("system", h.store.preferences.get(AppearanceStorageKey.appColorSchemePreference))
        }
    }
    @OriginalCase("ThemeServicePureTests::restore-origin downgrade: an unknown backed-up theme ID falls back to default on init()")
    @Test fun actualBackupPreferenceRestoreUnknownTheme() = runBlocking<Unit> {
        harness(before = { restore(it, theme = "theme-from-a-future-build") }) { h, s ->
            assertEquals(ThemeId.DEFAULT, ready(s).selection.current.id)
            assertEquals("default", h.store.preferences.get(AppearanceStorageKey.selectedThemeID))
        }
    }
    @OriginalCase("ThemeServiceOwnershipTests::a theme owned via the bundle is accessible and selectable()")
    @Test fun approvedBundleRemovalStillAllowsSelection() = runBlocking<Unit> {
        harness { _, s ->
            assertTrue(s.availableToCurrentUser.any { it.id == ThemeId.EMBER })
            s.setCurrent(ThemeId.EMBER)
            assertEquals(ThemeId.EMBER, ready(s).selection.current.id)
        }
    }
    @OriginalCase("ThemeServiceOwnershipTests::owning the bundle makes every theme available()")
    @Test fun approvedBundleRemovalMakesEveryThemeAvailableAtStartup() = runBlocking<Unit> {
        harness { _, s -> assertEquals(ThemeRegistry.allThemes, s.availableToCurrentUser) }
    }
    @OriginalCase("ThemeServiceOwnershipTests::the load() walk does not wipe a persisted theme on an empty ownership read()")
    @Test fun approvedRemovalHasNoOwnershipWalkToWipePreferences() = runBlocking<Unit> {
        harness(before = { it.store.preferences.set(AppearanceStorageKey.selectedThemeID, "ember") }) { h, s ->
            val before = h.store.preferences.snapshot()
            s.refreshFromPreferences()
            assertEquals(before, h.store.preferences.snapshot())
            assertEquals(ThemeId.EMBER, ready(s).selection.current.id)
        }
    }
    @OriginalCase("ThemeServiceOwnershipTests::refreshFromUserDefaults reverts an unowned restored theme once the store is loaded()")
    @Test fun approvedUnlockedAdaptationRetainsKnownRestoredThemeAtEveryLifetimeStage() = runBlocking<Unit> {
        harness { h, s ->
            restore(h, theme = "ember")
            assertEquals(ThemeId.EMBER, s.refreshFromPreferences().selection.current.id)
            assertEquals("ember", h.store.preferences.get(AppearanceStorageKey.selectedThemeID))
        }
    }
    @OriginalCase("AppStateThemeWiringTests::AppState exposes a themeService defaulting to the default theme()")
    @Test fun actualProcessPreferenceConsumerStartsAtDefaultWithoutConstructingAppGraph() = runBlocking<Unit> {
        harness { _, s -> assertEquals(ThemeId.DEFAULT, ready(s).selection.current.id) }
    }
    @OriginalCase("AppStateThemeWiringTests::notifyDataRestored does not wipe a restored paid theme while the store is unloaded()")
    @Test fun actualRestoreRefreshConsumerAdoptsKnownThemeWithoutRadioOrStoreGraph() = runBlocking<Unit> {
        harness { h, s ->
            restore(h, theme = "ember")
            assertEquals(ThemeId.EMBER, s.refreshFromPreferences().selection.current.id)
        }
    }
    @Test fun externalUpdatesAreObservedByTheRealProcessStore() = runBlocking<Unit> {
        harness { h, s ->
            for (theme in ThemeId.entries) {
                h.store.preferences.set(AppearanceStorageKey.selectedThemeID, theme.rawValue)
                withTimeout(10_000) { s.state.first { it is ThemeServiceState.Ready && it.selection.current.id == theme } }
            }
        }
    }
    @Test fun concurrentSelectorsSerializeAndLastAcknowledgedValueMatchesPersistence() = runBlocking<Unit> {
        harness { h, s ->
            coroutineScope {
                List(100) { index -> async(Dispatchers.Default) { s.setCurrent(ThemeId.entries[index % 10]) } }.forEach { it.await() }
            }
            s.setCurrent(ThemeId.SAKURA)
            assertEquals("sakura", h.store.preferences.get(AppearanceStorageKey.selectedThemeID))
            assertEquals(ThemeId.SAKURA, ready(s).selection.current.id)
        }
    }
    @Test fun invalidSelectionAndCancelledCallerNeverReportSuccessOrMutatePreferences() = runBlocking<Unit> {
        harness { h, s ->
            val before = h.store.preferences.snapshot()
            val invalid = assertFailsWith<ThemeServiceFailure> { s.setCurrent("future") }
            assertIs<ThemeProblem.UnknownThemeID>(invalid.problem)
            assertEquals(before, h.store.preferences.snapshot())
            val cancelled = async(start = kotlinx.coroutines.CoroutineStart.LAZY) { s.setCurrent(ThemeId.EMBER) }
            cancelled.cancel()
            assertFailsWith<CancellationException> { cancelled.await() }
            assertEquals(before, h.store.preferences.snapshot())
        }
    }
    @Test fun closeCancelsOnlyOwnedObservationAndAllCallersAwaitCompletion() = runBlocking<Unit> {
        harness { h, s ->
            coroutineScope { List(20) { async { s.close() } }.forEach { it.await() } }
            assertIs<ThemeServiceState.Closed>(s.state.value)
            assertTrue(h.parent.isActive)
            h.store.preferences.set(AppearanceStorageKey.selectedThemeID, "nord")
            assertEquals("nord", h.store.preferences.get(AppearanceStorageKey.selectedThemeID))
            val failed = assertFailsWith<ThemeServiceFailure> { s.setCurrent(ThemeId.EMBER) }
            assertEquals(ThemeProblem.OwnerClosed, failed.problem)
        }
    }
    @Test fun closingOneThemeConsumerDoesNotCloseOtherConsumerOrPreferences() = runBlocking<Unit> {
        harness { h, first ->
            val second = h.open()
            try {
                first.close()
                second.setCurrent(ThemeId.OLIVE)
                assertEquals(ThemeId.OLIVE, ready(second).selection.current.id)
                assertEquals("olive", h.store.preferences.get(AppearanceStorageKey.selectedThemeID))
            } finally { second.close() }
        }
    }
    @Test fun parentCancellationProducesTerminalStateAndNoLiveChildJobs() = runBlocking<Unit> {
        harness { h, s ->
            s.setCurrent(ThemeId.NORD)
            h.parent.cancelAndJoin()
            assertEquals(ThemeId.NORD, assertIs<ThemeServiceState.Closed>(s.state.value).previous?.current?.id)
            assertFalse(h.parent.children.any())
            val failure = assertFailsWith<ThemeServiceFailure> { s.refreshFromPreferences() }
            assertEquals(ThemeProblem.OwnerClosed, failure.problem)
        }
    }
    @Test fun lockedStorageIsTypedAndExplicitRefreshCanRecoverWithoutRecreatingFiles() = runBlocking<Unit> {
        harness { h, s ->
            s.setCurrent(ThemeId.FERN)
            val users = Shadows.shadowOf(h.application.getSystemService(UserManager::class.java))
            users.setUserUnlocked(false)
            try {
                val failure = assertFailsWith<ThemeServiceFailure> { s.refreshFromPreferences() }
                assertEquals(StorageProblem.LockedBeforeFirstUnlock,
                    assertIs<ThemeProblem.PreferenceFailure>(failure.problem).failure.problem)
                assertIs<ThemeServiceState.Failed>(s.state.value)
            } finally { users.setUserUnlocked(true) }
            assertEquals(ThemeId.FERN, s.refreshFromPreferences().selection.current.id)
            assertEquals("fern", h.store.preferences.get(AppearanceStorageKey.selectedThemeID))
        }
    }
    @Test fun corruptAndIoFailedInitializationNeverReplacesStateWithDefaults() = runBlocking<Unit> {
        for (corrupt in listOf(true, false)) {
            val directory = temporary.newFolder()
            val file = File(File(directory, "meshcoreone-datastore"), "app.preferences_pb")
            assertTrue(file.parentFile.mkdirs())
            if (corrupt) file.writeBytes(byteArrayOf(-1, -1, -1, -1)) else assertTrue(file.mkdir())
            val h = Harness(directory)
            try {
                val failure = assertFailsWith<ThemeServiceFailure> { h.open() }
                assertIs<ThemeProblem.PreferenceFailure>(failure.problem)
                if (corrupt) assertContentEquals(byteArrayOf(-1, -1, -1, -1), file.readBytes()) else assertTrue(file.isDirectory)
                assertTrue(h.parent.isActive)
            } finally { h.close() }
        }
    }
    @Test fun actualDiskReopenRetainsThemeAndSchemeAcrossConsumerClose() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        var h = Harness(directory)
        var s = h.open()
        try {
            s.setCurrent(ThemeId.CATPPUCCIN)
            s.setColorSchemePreference(AppColorSchemePreference.DARK)
        } finally { s.close(); h.close() }
        h = Harness(directory)
        s = h.open()
        try {
            assertEquals(ThemeId.CATPPUCCIN, ready(s).selection.current.id)
            assertEquals(AppColorSchemePreference.DARK, ready(s).selection.colorSchemePreference)
        } finally { s.close(); h.close() }
    }
    @Test fun unknownRestoreReversionHasOneClaimAndDoesNotRepeatOnRefreshOrManualSelection() = runBlocking<Unit> {
        harness { h, s ->
            s.setCurrent(ThemeId.MARINE)
            assertNull(ready(s).themeReversion)
            h.store.preferences.set(AppearanceStorageKey.selectedThemeID, "future-build")
            s.refreshFromPreferences()
            val version = assertNotNull(ready(s).themeReversion)
            assertTrue(s.claimThemeReversion(version))
            assertFalse(s.claimThemeReversion(version))
            assertNull(s.refreshFromPreferences().themeReversion)
            s.setCurrent(ThemeId.EMBER)
            s.setCurrent(ThemeId.DEFAULT)
            assertNull(ready(s).themeReversion)
        }
    }
    @Test fun queuedAnnouncementClaimIsTerminalFalseWhenCloseWinsTheMutexRace() = runBlocking<Unit> {
        harness { h, s ->
            s.setCurrent(ThemeId.MARINE)
            h.store.preferences.set(AppearanceStorageKey.selectedThemeID, "future")
            s.refreshFromPreferences()
            val version = assertNotNull(ready(s).themeReversion)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            coroutineScope {
                val transaction = async(Dispatchers.IO) {
                    h.store.preferences.update {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "Test transaction release timed out" }
                    }
                }
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    val setter = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        assertFailsWith<ThemeServiceFailure> { s.setColorSchemePreference(AppColorSchemePreference.DARK) }
                    }
                    val claim = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { s.claimThemeReversion(version) }
                    val close = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { s.close() }
                    release.countDown()
                    transaction.await()
                    assertEquals(ThemeProblem.OwnerClosed, setter.await().problem)
                    assertFalse(claim.await())
                    close.await()
                    assertIs<ThemeServiceState.Closed>(s.state.value)
                    assertFalse(s.claimThemeReversion(version))
                    assertTrue(h.themeFailures.all { it.problem == ThemeProblem.OwnerClosed })
                } finally { release.countDown() }
            }
        }
    }
    @Test fun cancelledAnnouncementCallerStillThrowsBeforeTheClosedOwnerShortcut() = runBlocking<Unit> {
        harness { _, s ->
            s.close()
            var cancellationObserved = false
            coroutineScope {
                val caller = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    currentCoroutineContext().cancel()
                    try {
                        s.claimThemeReversion(1L)
                        fail("A cancelled caller must not receive a terminal success value")
                    } catch (cancelled: CancellationException) {
                        cancellationObserved = true
                        throw cancelled
                    }
                }
                assertFailsWith<CancellationException> { caller.await() }
            }
            assertTrue(cancellationObserved)
            assertFalse(s.claimThemeReversion(1L))
        }
    }
}
