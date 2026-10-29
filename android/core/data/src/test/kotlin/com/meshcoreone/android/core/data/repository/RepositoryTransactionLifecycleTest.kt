// PortedFrom: MC1Services/Tests/MC1ServicesTests/ChannelFloodScopeTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import kotlin.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryTransactionLifecycleTest : RepositoryTest() {
    @OriginalCase("ChannelFloodScopeTests::setChannelFloodScope updates existing channel atomically()")
    @Test fun floodScopeSetterCommitsBothColumnsWithoutChangingOtherFieldsOrRadios() = runTest {
        val channel = ChannelDTO(radioId = RADIO_A, index = 1u, name = "General")
            .withFloodScope(ChannelFloodScope.Region("Germany"))
        store.saveChannel(channel)
        store.saveChannel(channel.copy(radioId = RADIO_B))
        val foreign = assertNotNull(db.channels().byId(RADIO_B.value, channel.id))
        for ((scope, mode, region) in listOf(
            Triple(ChannelFloodScope.AllRegions, "allRegions", null),
            Triple(ChannelFloodScope.Inherit, "inherit", null),
            Triple(ChannelFloodScope.Region("France"), "specific", "France"),
        )) {
            store.setChannelFloodScope(entity(id = channel.id), scope)
            val expected = channel.withFloodScope(scope)
            assertEquals(expected, store.fetchChannel(entity(id = channel.id)))
            val raw = assertNotNull(db.channels().byId(RADIO_A.value, channel.id))
            assertEquals(mode, raw.floodScopeModeRawValue)
            assertEquals(region, raw.regionScope)
            assertEquals(expected.toEntity(), raw)
            assertEquals(foreign, db.channels().byId(RADIO_B.value, channel.id))
        }
    }

    @Test fun floodScopeSetterFailureRollsBackItsUpdateAndKeepsTheBufferedDiagnostic() = runTest(scheduler) {
        val channel = ChannelDTO(radioId = RADIO_A, index = 1u, name = "General")
            .withFloodScope(ChannelFloodScope.Region("Germany"))
        store.saveChannel(channel)
        val entry = rx()
        store.saveRxLogEntry(entry)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_scope AFTER UPDATE OF floodScopeModeRawValue ON channels " +
                "BEGIN SELECT RAISE(FAIL, 'controlled scope failure'); END",
        )
        try {
            val failure = assertFailsWith<PersistenceStoreException> {
                store.setChannelFloodScope(entity(id = channel.id), ChannelFloodScope.AllRegions)
            }
            assertIs<PersistenceStoreError.SaveFailed>(failure.error)
            assertSame(failure, issues.single())
            assertEquals(channel.toEntity(), db.channels().byId(RADIO_A.value, channel.id))
            assertEquals(channel, store.fetchChannel(entity(id = channel.id)))
            assertNull(db.rxLogs().byId(RADIO_A.value, entry.id))
            assertEquals(entry, store.fetchRxLogEntries(RADIO_A).single())
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_scope")
        }
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(entry.toEntity(), db.rxLogs().byId(RADIO_A.value, entry.id))
        store.setChannelFloodScope(entity(id = channel.id), ChannelFloodScope.AllRegions)
        assertEquals(channel.withFloodScope(ChannelFloodScope.AllRegions), store.fetchChannel(entity(id = channel.id)))
    }

    @Test fun missingChannelScopeFailsWithoutFlushingOrCreatingRows() = runTest {
        val entry = rx()
        store.saveRxLogEntry(entry)
        val failure = assertFailsWith<PersistenceStoreException> {
            store.setChannelFloodScope(entity(), ChannelFloodScope.Region("Germany"))
        }
        assertEquals(PersistenceStoreError.ChannelNotFound, failure.error)
        assertTrue(store.fetchChannels(RADIO_A).isEmpty())
        assertNull(db.rxLogs().byId(RADIO_A.value, entry.id))
        assertEquals(entry, store.fetchRxLogEntries(RADIO_A).single())
    }

    @OriginalCase("PersistenceStoreTests::RX log prune is deferred until threshold is exceeded()")
    @Test fun automaticPartialFlushPrunesOnlyAbove1100AndRetainsTheNewest1000() = runTest(scheduler) {
        val rows = (0 until 1100).map { rx(timestamp = it.toUInt(), receivedAt = AT.plusNanos(it.toLong())) }
        db.rxLogs().upsert(rows.map { it.toEntity() })
        val foreign = rx(RADIO_B)
        db.rxLogs().insert(foreign.toEntity())
        val newest = rx(timestamp = 1100u, receivedAt = AT.plusNanos(1100))
        store.saveRxLogEntry(newest)
        assertEquals(1100L, db.rxLogs().count(RADIO_A.value))
        assertEquals(1101, store.fetchRxLogEntries(RADIO_A, 1200).size)
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1100L, db.rxLogs().count(RADIO_A.value))
        assertNull(db.rxLogs().byId(RADIO_A.value, newest.id))
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1000L, db.rxLogs().count(RADIO_A.value))
        val actual = store.fetchRxLogEntries(RADIO_A, 1200)
        assertEquals((1100 downTo 101).map { it.toUInt() }, actual.map { it.senderTimestamp })
        assertEquals(newest.id, actual.first().id)
        assertEquals(foreign.toEntity(), db.rxLogs().byId(RADIO_B.value, foreign.id))
        assertEquals(1L, store.rxInitiatedSaveCount)
    }

    @Test fun exact1100AutomaticBatchDoesNotPruneAndTheNextFullBatchDoes() = runTest(scheduler) {
        db.rxLogs().upsert((0 until 1080).map {
            rx(timestamp = it.toUInt(), receivedAt = AT.plusNanos(it.toLong())).toEntity()
        })
        for (index in 1080 until 1100) {
            store.saveRxLogEntry(rx(timestamp = index.toUInt(), receivedAt = AT.plusNanos(index.toLong())))
        }
        assertEquals(1100L, db.rxLogs().count(RADIO_A.value))
        assertEquals(1L, store.rxInitiatedSaveCount)
        for (index in 1100 until 1119) {
            store.saveRxLogEntry(rx(timestamp = index.toUInt(), receivedAt = AT.plusNanos(index.toLong())))
        }
        assertEquals(1100L, db.rxLogs().count(RADIO_A.value))
        assertEquals(1119, store.fetchRxLogEntries(RADIO_A, 1200).size)
        store.saveRxLogEntry(rx(timestamp = 1119u, receivedAt = AT.plusNanos(1119)))
        assertEquals(1000L, db.rxLogs().count(RADIO_A.value))
        assertEquals((1119 downTo 120).map { it.toUInt() },
            store.fetchRxLogEntries(RADIO_A, 1200).map { it.senderTimestamp })
        assertEquals(2L, store.rxInitiatedSaveCount)
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(2L, store.rxInitiatedSaveCount)
    }

    @Test fun closeFlushesAPartialBatchAndCancelsOnlyTheRepositoryJobs() = runTest(scheduler) {
        val entry = rx()
        store.saveRxLogEntry(entry)
        runCurrent()
        val processJob = requireNotNull(owner.coroutineContext[Job])
        store.close()
        store.close()
        assertTrue(processJob.isActive)
        assertTrue(processJob.children.none { it.isActive })
        assertEquals(entry.toEntity(), db.rxLogs().byId(RADIO_A.value, entry.id))
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(1L, store.rxInitiatedSaveCount)
        assertFailsWith<PersistenceStoreException> { store.saveRxLogEntry(rx()) }
        assertEquals(1L, db.rxLogs().count(RADIO_A.value))
    }

    @Test fun processCancellationStopsAutomaticFlushWithoutDroppingTheBatchOrClosingTheDatabase() = runTest(scheduler) {
        val entry = rx()
        store.saveRxLogEntry(entry)
        runCurrent()
        owner.cancel()
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(0L, store.rxInitiatedSaveCount)
        assertNull(db.rxLogs().byId(RADIO_A.value, entry.id))
        assertEquals(entry, store.fetchRxLogEntries(RADIO_A).single())
        store.close()
        assertEquals(entry.toEntity(), db.rxLogs().byId(RADIO_A.value, entry.id))
        assertEquals(1L, store.rxInitiatedSaveCount)
    }
}
