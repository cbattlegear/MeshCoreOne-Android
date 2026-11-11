// AndroidOnly: WP-217 Simulator RX seed dispatch and reference-order controls.
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.SnapshotList
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SimulatorRxSeedDispatchTest {
    private val now = Instant.ofEpochSecond(1_700_000_000)
    private val radio = MockDataProvider.simulatorRadioId

    @Test fun referenceStoreFetchesBeforeBuildingAndSavingFixtures() = runTest {
        val store = SimulatorInMemorySeedStore()
        val fixtures = MockDataProvider.rxLogEntries(now)

        store.seedRxLogEntries(radio) {
            assertEquals(listOf("fetchRxLogEntries"), store.recordedCalls)
            fixtures
        }

        assertEquals(
            listOf("fetchRxLogEntries", "saveRxLogEntry", "saveRxLogEntry"),
            store.recordedCalls,
        )
        assertEquals(fixtures, store.allRxEntries())
    }

    @Test fun connectionDispatchesRealFixturesThroughTheStoreHookOnce() = runTest {
        val backing = SimulatorInMemorySeedStore()
        var invocations = 0
        var observed: SnapshotList<RxLogEntryDTO>? = null
        val store = object : SimulatorSeedStore by backing {
            override suspend fun seedRxLogEntries(
                radioId: RadioId,
                buildEntries: () -> SnapshotList<RxLogEntryDTO>,
            ) {
                assertEquals(radio, radioId)
                invocations += 1
                observed = buildEntries()
            }
        }

        SimulatorConnectionMode(clock = Clock.fixed(now, ZoneOffset.UTC)).seedDataStore(store)

        assertEquals(1, invocations)
        assertEquals(MockDataProvider.rxLogEntries(now), observed)
        assertFalse(backing.recordedCalls.contains("fetchRxLogEntries"))
        assertFalse(backing.recordedCalls.contains("saveRxLogEntry"))
        assertTrue(backing.recordedCalls.contains("fetchLatestNodeStatusSnapshot"))
    }

    @Test fun hookFailureStopsTheSeedBeforeNodeSnapshots() = runTest {
        val backing = SimulatorInMemorySeedStore()
        val expected = PersistenceStoreException(PersistenceStoreError.InvalidData)
        val store = object : SimulatorSeedStore by backing {
            override suspend fun seedRxLogEntries(
                radioId: RadioId,
                buildEntries: () -> SnapshotList<RxLogEntryDTO>,
            ) {
                throw expected
            }
        }

        val failure = assertFailsWith<PersistenceStoreException> {
            SimulatorConnectionMode(clock = Clock.fixed(now, ZoneOffset.UTC)).seedDataStore(store)
        }

        assertTrue(failure === expected)
        assertFalse(backing.recordedCalls.contains("fetchLatestNodeStatusSnapshot"))
    }
}
