// AndroidOnly: WP-217 Simulator RX fixture preservation through the production Room adapter.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.RxLogRegionUpdate
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.services.simulator.MockDataProvider
import com.meshcoreone.android.core.services.simulator.SimulatorConnectionMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SimulatorRxSeedRegressionTest : RoomProcessTest() {
    @Test fun oldFixturesOutsideTheRecentWindowRemainUnchanged() = runBlocking<Unit> {
        val first = Instant.ofEpochSecond(1_700_000_000)
        val radio = MockDataProvider.simulatorRadioId
        SimulatorConnectionMode(clock = Clock.fixed(first, ZoneOffset.UTC))
            .seedDataStore(RoomSimulatorSeedStore(store))
        store.flushPendingRxLogEntries()
        val fixtures = store.fetchRxLogEntries(radio, 10)
        assertEquals(2, fixtures.size)
        store.batchUpdateRxLogRegion(
            radio,
            SnapshotList(fixtures.map {
                RxLogRegionUpdate(it.id, "personalized", SnapshotList.of("personalized"))
            }),
        )
        val original = store.fetchRxLogEntries(radio, 10).associateBy { it.id }
        repeat(501) { index ->
            store.saveRxLogEntry(
                fixtures[0].copy(
                    id = UUID(0x7000000000000000L, index.toLong() + 1),
                    receivedAt = first.plusSeconds(index.toLong() + 1),
                    regionScope = "newer",
                    regionScopeMatches = SnapshotList.of("newer"),
                ),
            )
        }
        store.flushPendingRxLogEntries()
        val recent = store.fetchRxLogEntries(radio, 500).map { it.id }.toSet()
        assertTrue(fixtures.none { it.id in recent })

        SimulatorConnectionMode(clock = Clock.fixed(first.plusSeconds(86_400), ZoneOffset.UTC))
            .seedDataStore(RoomSimulatorSeedStore(store))
        store.flushPendingRxLogEntries()

        val after = store.fetchRxLogEntries(radio, 600).associateBy { it.id }
        for (fixture in fixtures) assertEquals(original.getValue(fixture.id), after.getValue(fixture.id))
        assertEquals(503L, store.countRxLogEntries(radio))
    }
}
