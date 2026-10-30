// PortedFrom: MC1Tests/AppState/AppStateRegionTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.datastore.BackupPreferenceKeys
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.app.state.RegionSelectionStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessRegionSelectionIntegrationTest : RoomProcessTest() {
    @Test fun actualAppStateLoadsPersistsAndClearsTheRealProcessPreferenceAcrossConnections() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MeshCoreStorage.get(context)
        val preferences = DataStoreRegionSelectionStore(storage.preferences)
        val original = RegionSelection("US", RegionSelection.Source.LOCATION, "US-CA", "los angeles")
        val replacement = RegionSelection("CA", RegionSelection.Source.MANUAL, "CA-ON")
        preferences.persist(original)
        val writes = Channel<RegionSelection?>(Channel.UNLIMITED)
        val observedPreferences = object : RegionSelectionStore by preferences {
            override suspend fun persist(selection: RegionSelection?) {
                preferences.persist(selection)
                writes.send(selection)
            }
        }
        val h = ContainerHarness(this, store, regionStore = observedPreferences)
        try {
            h.container.start().join()
            assertEquals(original, h.appState.regionSelection)
            assertEquals(original, preferences.load())
            h.appState.regionSelection = replacement
            assertEquals(replacement, writes.receive())
            assertEquals(replacement, preferences.load())
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            assertEquals(replacement, h.appState.regionSelection)
            assertEquals(replacement, preferences.load())
            h.appState.regionSelection = null
            assertNull(writes.receive())
            assertNull(preferences.load())
            assertTrue(BackupPreferenceKeys.regionSelection.rawValue !in storage.preferences.snapshot().storedValues)
        } finally {
            h.container.close()
            writes.close()
            storage.close()
        }
    }
}
