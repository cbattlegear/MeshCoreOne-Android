// PortedFrom: MC1Tests/Views/Map/MapFilterMigrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-312 Real process DataStore and backup-key consumer regressions.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.BackupPreferenceSnapshot
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.datastore.PreferenceValue
import com.meshcoreone.android.core.maps.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class AppMapPreferencesTest {
    private lateinit var storage: MeshCoreStorage
    private lateinit var adapter: AppMapPreferences
    private val legacy get() = RuntimeEnvironment.getApplication().getSharedPreferences("shared-map", 0)

    @Before fun openStore() = runBlocking {
        storage = MeshCoreStorage.get(RuntimeEnvironment.getApplication())
        storage.preferences.resetAppPreferences()
        assertTrue(legacy.edit().clear().commit())
        adapter = AppMapPreferences(storage.preferences, legacy)
    }

    @After fun closeStore() = runBlocking { storage.close() }

    @Test fun allMissingHostsUseTheirSeedsWithoutWritingOrExportingKeys() = runBlocking {
        for (host in MapFilterHost.entries) {
            assertEquals(MapFilterState.seed(host), adapter.observe(host).first().filter)
        }
        val snapshot = storage.preferences.snapshot()
        assertFalse(snapshot.contains(AppStorageKey.mapFilterMainMap))
        assertFalse(snapshot.contains(AppStorageKey.mapFilterTracePath))
        assertFalse(snapshot.contains(AppStorageKey.mapFilterNeighborSNR))
        assertTrue(storage.backupPreferences.snapshotForBackup().presentValues.isEmpty())
    }

    @Test fun legacyFalseIsPresentAndMigratesOnlyMainMapOnce() = runBlocking {
        storage.preferences.set(AppStorageKey.showDiscoveredNodesOnMap, false)
        assertFalse(adapter.observe(MapFilterHost.MAIN_MAP).first().filter.showDiscovered)
        assertNotNull(storage.preferences.snapshot().stored(AppStorageKey.mapFilterMainMap))
        assertTrue(adapter.observe(MapFilterHost.TRACE_PATH).first().filter.showDiscovered)
        assertFalse(adapter.observe(MapFilterHost.NEIGHBOR_SNR).first().filter.showDiscovered)
        assertFalse(storage.preferences.snapshot().contains(AppStorageKey.mapFilterTracePath))
        assertFalse(storage.preferences.snapshot().contains(AppStorageKey.mapFilterNeighborSNR))
        storage.preferences.set(AppStorageKey.showDiscoveredNodesOnMap, true)
        assertFalse(adapter.observe(MapFilterHost.MAIN_MAP).first().filter.showDiscovered)
    }

    @Test fun aLegacyRestoreAfterPureSeedStillMigratesTheMountedHostKey() = runBlocking {
        assertFalse(adapter.observe(MapFilterHost.MAIN_MAP).first().filter.showDiscovered)
        assertFalse(storage.preferences.snapshot().contains(AppStorageKey.mapFilterMainMap))
        storage.backupPreferences.restoreMissing(BackupPreferenceSnapshot(mapOf(
            "showDiscoveredNodesOnMap" to PreferenceValue.BooleanValue(true),
        )))
        assertTrue(adapter.observe(MapFilterHost.MAIN_MAP).first().filter.showDiscovered)
        val repaired = requireNotNull(storage.preferences.snapshot().stored(AppStorageKey.mapFilterMainMap))
        assertTrue(requireNotNull(MapFilterState.decode(repaired)).showDiscovered)
    }

    @Test fun validHostValuesWinOverLegacyAndRetainTheirExactRawBytes() = runBlocking {
        val raw = """{"showRoom":true,"showChat":false,"showRepeater":true,"favoritesOnly":false,"showDiscovered":false}"""
        storage.preferences.set(AppStorageKey.mapFilterMainMap, raw)
        storage.preferences.set(AppStorageKey.showDiscoveredNodesOnMap, true)
        assertEquals(MapFilterState(showChat = false), adapter.observe(MapFilterHost.MAIN_MAP).first().filter)
        assertEquals(raw, storage.preferences.get(AppStorageKey.mapFilterMainMap))
    }

    @Test fun corruptAndAllTypesOffHostValuesAreRepairedInTheActualBackupStore() = runBlocking {
        val invalid = listOf("{not-json", "", MapFilterState(
            showChat = false, showRepeater = false, showRoom = false,
        ).encode())
        for (raw in invalid) {
            storage.preferences.set(AppStorageKey.mapFilterMainMap, raw)
            val resolved = adapter.observe(MapFilterHost.MAIN_MAP).first().filter
            assertEquals(MapFilterState(), resolved)
            val persisted = storage.preferences.get(AppStorageKey.mapFilterMainMap)
            assertEquals(resolved, MapFilterState.decode(persisted))
            assertEquals(PreferenceValue.StringValue(persisted),
                storage.backupPreferences.snapshotForBackup().presentValues["mapFilterMainMap"])
        }
    }

    @Test fun privateMigrationPreservesCameraButCannotOverrideRestoredAuthoritativeValues() = runBlocking {
        assertTrue(legacy.edit().putString("filter-v1", MapFilterState(showDiscovered = true).encode())
            .putBoolean("cluster-v1", false).putBoolean("labels-v1", false)
            .putString("camera-v1", "10,20,0.5,0.5").commit())
        storage.preferences.set(AppStorageKey.mapFilterMainMap, MapFilterState(favoritesOnly = true).encode())
        storage.preferences.set(AppStorageKey.mapClusteringEnabled, true)
        val actual = adapter.observe(MapFilterHost.MAIN_MAP).first()
        assertTrue(actual.filter.favoritesOnly)
        assertFalse(actual.filter.showDiscovered)
        assertTrue(actual.clustering)
        assertFalse(actual.labels)
        assertFalse(legacy.contains("filter-v1"))
        assertFalse(legacy.contains("cluster-v1"))
        assertFalse(legacy.contains("labels-v1"))
        assertEquals("10,20,0.5,0.5", legacy.getString("camera-v1", null))
    }

    @Test fun privateSettingsMoveToTheDeclaredHostAndBackupKeysAndSurviveANewAdapter() = runBlocking {
        val filter = MapFilterState(showDiscovered = true, showRoom = false)
        assertTrue(legacy.edit().putString("filter-v1", filter.encode())
            .putBoolean("cluster-v1", false).putBoolean("labels-v1", false).commit())
        assertEquals(MapPreferences(filter, false, false, MapColorPreference.SYSTEM),
            adapter.observe(MapFilterHost.MAIN_MAP).first())
        val backup = storage.backupPreferences.snapshotForBackup().presentValues
        assertEquals(PreferenceValue.StringValue(filter.encode()), backup["mapFilterMainMap"])
        assertEquals(PreferenceValue.BooleanValue(false), backup["mapClusteringEnabled"])
        assertEquals(PreferenceValue.BooleanValue(false), backup["mapShowLabels"])
        val replacement = AppMapPreferences(storage.preferences, legacy)
        assertEquals(adapter.observe(MapFilterHost.MAIN_MAP).first(), replacement.observe(MapFilterHost.MAIN_MAP).first())
    }

    @Test fun privateFilterMigrationPreservesOtherFieldsWhenLegacyDiscoveredValueIsPresent() = runBlocking {
        val privateFilter = MapFilterState(
            favoritesOnly = true, showChat = false, showRepeater = true, showRoom = false,
        )
        for (legacyDiscovered in listOf(false, true)) {
            storage.preferences.resetAppPreferences()
            assertTrue(legacy.edit().clear().putString("filter-v1", privateFilter.encode()).commit())
            storage.preferences.set(AppStorageKey.showDiscoveredNodesOnMap, legacyDiscovered)

            val expected = privateFilter.copy(showDiscovered = legacyDiscovered)
            assertEquals(expected, adapter.observe(MapFilterHost.MAIN_MAP).first().filter)
            val persisted = storage.preferences.snapshot().stored(AppStorageKey.mapFilterMainMap)
            assertEquals(expected, MapFilterState.decode(requireNotNull(persisted)))
            assertEquals(
                PreferenceValue.StringValue(requireNotNull(persisted)),
                storage.backupPreferences.snapshotForBackup().presentValues["mapFilterMainMap"],
            )
            assertFalse(legacy.contains("filter-v1"))
            assertEquals(expected, AppMapPreferences(storage.preferences, legacy)
                .observe(MapFilterHost.MAIN_MAP).first().filter)
        }
    }

    @Test fun hostWritesStayIndependentAndAppearanceUsesTheExistingProcessKey() = runBlocking {
        val trace = MapFilterState(favoritesOnly = true, showDiscovered = true)
        adapter.setFilter(MapFilterHost.TRACE_PATH, trace)
        assertEquals(trace, adapter.observe(MapFilterHost.TRACE_PATH).first().filter)
        assertEquals(MapFilterState(), adapter.observe(MapFilterHost.MAIN_MAP).first().filter)
        assertEquals(MapFilterState(), adapter.observe(MapFilterHost.NEIGHBOR_SNR).first().filter)
        adapter.setClustering(false)
        adapter.setLabels(false)
        for ((raw, expected) in listOf("system" to MapColorPreference.SYSTEM,
            "light" to MapColorPreference.LIGHT, "dark" to MapColorPreference.DARK)) {
            storage.preferences.set(AppStorageKey.mapColorSchemePreference, raw)
            val actual = adapter.observe(MapFilterHost.MAIN_MAP).first()
            assertEquals(expected, actual.appearance)
            assertFalse(actual.clustering)
            assertFalse(actual.labels)
        }
    }
}
