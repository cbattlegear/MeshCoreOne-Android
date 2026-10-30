// AndroidOnly: WP-303 Native tests: the DataStore-backed process roles over the real merged storage module.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.PersistenceKeys
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.runtime.RuntimePreferenceValue
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class DataStoreAdaptersTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val storage get() = MeshCoreStorage.get(context)

    @After fun close() = runBlocking { storage.close() }

    @Test fun connectionPreferencesRoundTripEveryValueKindAndRemoveOnDelete() = runBlocking<Unit> {
        val preferences = DataStoreConnectionPreferences(storage.preferences)
        val id = UUID.randomUUID().toString()
        val at = Instant.ofEpochMilli(1_704_067_200_123)
        val written = preferences.update {
            it[PersistenceKeys.LAST_CONNECTED_DEVICE_ID] = RuntimePreferenceValue.Text(id)
            it[PersistenceKeys.USER_EXPLICITLY_DISCONNECTED] = RuntimePreferenceValue.Flag(true)
            it[PersistenceKeys.LAST_BOND_VERIFIED_DATE] = RuntimePreferenceValue.Date(at)
        }
        assertEquals(id, written.text(PersistenceKeys.LAST_CONNECTED_DEVICE_ID))
        val read = preferences.read()
        assertEquals(id, read.text(PersistenceKeys.LAST_CONNECTED_DEVICE_ID))
        assertEquals(true, read.flag(PersistenceKeys.USER_EXPLICITLY_DISCONNECTED))
        assertEquals(at, read.date(PersistenceKeys.LAST_BOND_VERIFIED_DATE), "dates keep millisecond precision")
        preferences.update { it.remove(PersistenceKeys.USER_EXPLICITLY_DISCONNECTED); it.remove(PersistenceKeys.LAST_BOND_VERIFIED_DATE) }
        val after = preferences.read()
        assertNull(after.flag(PersistenceKeys.USER_EXPLICITLY_DISCONNECTED))
        assertNull(after.date(PersistenceKeys.LAST_BOND_VERIFIED_DATE))
        assertEquals(id, after.text(PersistenceKeys.LAST_CONNECTED_DEVICE_ID), "unrelated keys are untouched")
        preferences.update { it.remove(PersistenceKeys.LAST_CONNECTED_DEVICE_ID) }
    }

    @Test fun regionSelectionPersistsClearsAndDropsACorruptValue() = runBlocking<Unit> {
        val store = DataStoreRegionSelectionStore(storage.preferences)
        assertNull(store.load())
        val region = RegionSelection("US", RegionSelection.Source.LOCATION, "US-CA", "los angeles")
        store.persist(region)
        assertEquals(region, DataStoreRegionSelectionStore(storage.preferences).load())
        store.persist(null)
        assertNull(store.load())
        // A stored value that is not valid JSON is cleared and reported absent (the Swift loader removes the key).
        storage.preferences.set(com.meshcoreone.android.core.datastore.BackupPreferenceKeys.regionSelection,
            com.meshcoreone.android.core.protocol.bytes.Bytes.utf8("not json"))
        assertNull(store.load())
        assertNull(storage.preferences.snapshot().storedValues[com.meshcoreone.android.core.datastore.BackupPreferenceKeys.regionSelection.rawValue])
    }

    @Test fun staleCleanupPreferencesStoreThresholdAndReferenceDate() = runBlocking<Unit> {
        val preferences = DataStoreStaleCleanupPreferences(storage.preferences)
        assertEquals(0, preferences.thresholdDays())
        assertNull(preferences.lastRun())
        val at = Instant.ofEpochSecond(1_704_067_200, 500_000_000)
        preferences.recordRun(at)
        assertEquals(at, preferences.lastRun())
        assertEquals(725_760_000.5, storage.preferences.get(com.meshcoreone.android.core.datastore.AppStorageKey.lastStaleCleanupDate))
    }

    @Test fun demoModeDefaultsConfirmWritesBeforePublishingAndReopenBothFlags() = runBlocking<Unit> {
        val keys = com.meshcoreone.android.core.datastore.AppStorageKey
        storage.preferences.update { this[keys.isDemoModeUnlocked] = false; this[keys.isDemoModeEnabled] = false }
        val defaults = DataStoreDemoModeDefaults.load(storage.preferences)
        assertEquals(false, defaults.bool("isDemoModeUnlocked"))
        defaults.set(true, "isDemoModeUnlocked")
        defaults.set(false, "isDemoModeUnlocked")
        defaults.set(true, "isDemoModeUnlocked")
        defaults.set(true, "isDemoModeEnabled")
        assertEquals(true, storage.preferences.get(keys.isDemoModeUnlocked))
        assertEquals(true, storage.preferences.get(keys.isDemoModeEnabled))
        storage.close()
        val reopened = MeshCoreStorage.get(context)
        val reloaded = DataStoreDemoModeDefaults.load(reopened.preferences)
        assertEquals(true, reloaded.bool("isDemoModeUnlocked"), "the last committed write survives reopening")
        assertEquals(true, reloaded.bool("isDemoModeEnabled"))
        assertFailsWith<IllegalArgumentException> { reloaded.set(true, "somethingElse") }
    }

    @Test fun failedDemoWriteLeavesStateUnchangedAndCanBeRetriedAfterStorageReopens() = runBlocking<Unit> {
        val keys = com.meshcoreone.android.core.datastore.AppStorageKey
        val original = storage
        original.preferences.update { this[keys.isDemoModeUnlocked] = false; this[keys.isDemoModeEnabled] = false }
        val defaults = DataStoreDemoModeDefaults.load(original.preferences)
        val manager = com.meshcoreone.android.core.services.simulator.DemoModeManager(defaults)
        original.close()
        assertFailsWith<com.meshcoreone.android.core.datastore.StorageFailure> { manager.unlock() }
        assertEquals(false, manager.isUnlocked)
        assertEquals(false, manager.isEnabled)
        assertEquals(false, defaults.bool("isDemoModeUnlocked"))
        assertEquals(false, defaults.bool("isDemoModeEnabled"))
        val reopened = MeshCoreStorage.get(context)
        val retry = com.meshcoreone.android.core.services.simulator.DemoModeManager(
            DataStoreDemoModeDefaults.load(reopened.preferences),
        )
        retry.unlock()
        assertEquals(true, reopened.preferences.get(keys.isDemoModeUnlocked))
        assertEquals(true, reopened.preferences.get(keys.isDemoModeEnabled))
    }
}
