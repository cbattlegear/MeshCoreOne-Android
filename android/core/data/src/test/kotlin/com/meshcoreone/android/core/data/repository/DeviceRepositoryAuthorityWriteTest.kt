// AndroidOnly: WP-305 Test device-writer authority for simulator identity reconciliation.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.canonicalString
import com.meshcoreone.android.core.model.snapshot
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceRepositoryAuthorityWriteTest : RepositoryTest() {
    private lateinit var authorityContext: RoomRepositoryContext
    private lateinit var repository: DeviceRepository

    @Before fun openAuthorityRepository() {
        authorityContext = RoomRepositoryContext(
            db,
            clock,
            owner,
            RepositoryIssueReporter { _, failure -> issues += failure },
        )
        repository = DeviceRepository(authorityContext)
    }

    @After fun closeAuthorityRepository() {
        runBlocking { authorityContext.close() }
    }

    @Test fun simulatorRestoreRetainsWholeSnapshotAndInvalidatesOldAuthority() = runTest(scheduler) {
        val restored = device(radio = RADIO_A, id = UUID.randomUUID()).copy(
            publicKey = key(0x31),
            defaultFloodScopeName = "US915",
            knownRegions = listOf("US915", "EU868").snapshot(),
            preRepeatFrequency = 123u,
            ocvPreset = "user-preset",
            isActive = false,
        )
        val fixture = restored.copy(
            id = RADIO_A.value,
            defaultFloodScopeName = null,
            knownRegions = emptyList<String>().snapshot(),
            preRepeatFrequency = null,
            ocvPreset = null,
        )
        repository.saveDevice(restored)
        val ticket = assertNotNull(repository.fetchDeviceWriteSnapshot(restored.id))

        repository.saveSimulatorDevice(fixture)

        assertNull(repository.fetchDevice(restored.id))
        assertEquals(restored.copy(id = fixture.id), repository.fetchDevice(fixture.id))
        assertFalse(repository.saveDeviceIfUnchanged(ticket.device.copy(nodeName = "stale"), ticket))
        val beforeReseed = assertNotNull(repository.fetchDeviceWriteSnapshot(fixture.id))
        repository.saveSimulatorDevice(fixture)
        assertEquals(beforeReseed, repository.fetchDeviceWriteSnapshot(fixture.id))
    }

    @Test fun simulatorLegacyConflictDoesNotMergeOrDeleteEitherSnapshot() = runTest(scheduler) {
        val fixture = device(radio = RADIO_A, id = RADIO_A.value).copy(
            publicKey = key(0x32), defaultFloodScopeName = null, knownRegions = emptyList<String>().snapshot(),
        )
        val restored = fixture.copy(
            id = UUID.randomUUID(), defaultFloodScopeName = "US915", knownRegions = listOf("US915").snapshot(),
            isActive = false,
        )
        repository.saveDevice(fixture)
        repository.saveDevice(restored)
        val failure = assertFailsWith<PersistenceStoreException> { repository.saveSimulatorDevice(fixture) }
        assertEquals(PersistenceStoreError.InvalidData, failure.error)
        assertEquals(fixture, repository.fetchDevice(fixture.id))
        assertEquals(restored, repository.fetchDevice(restored.id))
    }

    @Test fun simulatorRecoveryKeepsExactlyTheSelectedDeviceSnapshot() = runTest(scheduler) {
        for ((radio, choice) in listOf(
            RADIO_A to SimulatorPreferenceChoice.KEEP_CANONICAL,
            RADIO_B to SimulatorPreferenceChoice.KEEP_IMPORTED,
        )) {
            val canonical = device(radio = radio, id = radio.value).copy(
                publicKey = key(if (radio == RADIO_A) 0x41 else 0x42),
                defaultFloodScopeName = null,
                knownRegions = emptyList<String>().snapshot(),
                frequency = 915_000u,
                isActive = true,
            )
            val imported = canonical.copy(
                id = UUID.randomUUID(), isActive = false, nodeName = "Restored device",
                defaultFloodScopeName = "US915", knownRegions = listOf("US915").snapshot(),
                frequency = 868_000u,
            )
            repository.saveDevice(canonical)
            repository.saveDevice(imported)
            val conflict = assertNotNull(repository.simulatorDevicePreferenceConflict(canonical))
            val canonicalTicket = assertNotNull(repository.fetchDeviceWriteSnapshot(canonical.id))
            repository.resolveSimulatorDevicePreferenceConflict(canonical, conflict, choice)
            val selected = if (choice == SimulatorPreferenceChoice.KEEP_CANONICAL) canonical else imported
            assertEquals(
                selected.copy(id = canonical.id, isActive = canonical.isActive, lastConnected = canonical.lastConnected),
                repository.fetchDevice(canonical.id),
            )
            assertNull(repository.fetchDevice(imported.id))
            assertNull(repository.simulatorDevicePreferenceConflict(canonical))
            assertFalse(repository.saveDeviceIfUnchanged(canonicalTicket.device.copy(nodeName = "stale"), canonicalTicket))
        }
    }

    @Test fun simulatorRecoveryRejectsAStaleChoiceAndLeavesBothRows() = runTest(scheduler) {
        val canonical = device(radio = RADIO_A, id = RADIO_A.value).copy(publicKey = key(0x43))
        val imported = canonical.copy(id = UUID.randomUUID(), defaultFloodScopeName = "US915")
        repository.saveDevice(canonical)
        repository.saveDevice(imported)
        val stale = assertNotNull(repository.simulatorDevicePreferenceConflict(canonical))
        val edited = canonical.copy(defaultFloodScopeName = "EU868")
        repository.saveDevice(edited)
        val failure = assertFailsWith<PersistenceStoreException> {
            repository.resolveSimulatorDevicePreferenceConflict(canonical, stale, SimulatorPreferenceChoice.KEEP_IMPORTED)
        }
        assertEquals(PersistenceStoreError.InvalidData, failure.error)
        assertEquals(edited, repository.fetchDevice(canonical.id))
        assertEquals(imported, repository.fetchDevice(imported.id))
        val refreshed = assertNotNull(repository.simulatorDevicePreferenceConflict(canonical))
        repository.resolveSimulatorDevicePreferenceConflict(canonical, refreshed, SimulatorPreferenceChoice.KEEP_CANONICAL)
        assertEquals(edited, repository.fetchDevice(canonical.id))
        assertNull(repository.fetchDevice(imported.id))
    }

    @Test fun cancelledSimulatorRecoveryWaitingForTheWriterLeavesBothSnapshotsAndAuthority() = runTest(scheduler) {
        val canonical = device(radio = RADIO_A, id = RADIO_A.value).copy(publicKey = key(0x45))
        val imported = canonical.copy(id = UUID.randomUUID(), defaultFloodScopeName = "US915")
        repository.saveDevice(canonical)
        repository.saveDevice(imported)
        val conflict = assertNotNull(repository.simulatorDevicePreferenceConflict(canonical))
        val ticket = assertNotNull(repository.fetchDeviceWriteSnapshot(canonical.id))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operations = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val writer = operations.async {
            authorityContext.write("hold-simulator-recovery") {
                entered.complete(Unit)
                release.await()
            }
        }
        try {
            entered.await()
            val resolving = async(start = CoroutineStart.UNDISPATCHED) {
                repository.resolveSimulatorDevicePreferenceConflict(
                    canonical, conflict, SimulatorPreferenceChoice.KEEP_IMPORTED,
                )
            }
            resolving.cancelAndJoin()
        } finally {
            release.complete(Unit)
            try {
                writer.await()
            } finally {
                operations.cancel()
            }
        }
        assertEquals(canonical, repository.fetchDevice(canonical.id))
        assertEquals(imported, repository.fetchDevice(imported.id))
        assertEquals(ticket, repository.fetchDeviceWriteSnapshot(canonical.id))
        repository.resolveSimulatorDevicePreferenceConflict(canonical, conflict, SimulatorPreferenceChoice.KEEP_IMPORTED)
        assertNull(repository.fetchDevice(imported.id))
    }

    @Test fun simulatorRecoveryChecksAfterPendingRxFlushAndRollsBackOnTriggerMutation() = runTest(scheduler) {
        val canonical = device(radio = RADIO_A, id = RADIO_A.value).copy(publicKey = key(0x44))
        val imported = canonical.copy(id = UUID.randomUUID(), defaultFloodScopeName = "US915")
        repository.saveDevice(canonical)
        repository.saveDevice(imported)
        val conflict = assertNotNull(repository.simulatorDevicePreferenceConflict(canonical))
        val pending = rx(radio = RADIO_A)
        authorityContext.saveRx(pending.toEntity())
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER mutate_device_after_rx AFTER INSERT ON rx_log_entries " +
                "BEGIN UPDATE devices SET nodeName = 'unexpected'; END",
        )
        try {
            assertFailsWith<PersistenceStoreException> {
                repository.resolveSimulatorDevicePreferenceConflict(
                    canonical, conflict, SimulatorPreferenceChoice.KEEP_IMPORTED,
                )
            }
            assertEquals(canonical, repository.fetchDevice(canonical.id))
            assertEquals(imported, repository.fetchDevice(imported.id))
            assertEquals(pending.toEntity(), authorityContext.read("pending-test") { pendingRx.values.single() })
            assertNull(db.rxLogs().byId(RADIO_A.value, pending.id))
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER mutate_device_after_rx")
        }
        repository.resolveSimulatorDevicePreferenceConflict(canonical, conflict, SimulatorPreferenceChoice.KEEP_IMPORTED)
        assertNotNull(db.rxLogs().byId(RADIO_A.value, pending.id))
        assertEquals(imported.copy(id = canonical.id, lastConnected = canonical.lastConnected), repository.fetchDevice(canonical.id))
    }

    @Test fun simulatorRestoreRollsBackWhenRetiringTheOldIdentityFails() = runTest(scheduler) {
        val restored = device(radio = RADIO_A, id = UUID.randomUUID()).copy(
            publicKey = key(0x33), isActive = false,
        )
        val fixture = restored.copy(id = RADIO_A.value)
        repository.saveDevice(restored)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_device_retirement BEFORE DELETE ON devices " +
                "BEGIN SELECT RAISE(ABORT, 'controlled retirement failure'); END",
        )
        try {
            assertFailsWith<PersistenceStoreException> { repository.saveSimulatorDevice(fixture) }
            assertEquals(restored, repository.fetchDevice(restored.id))
            assertNull(repository.fetchDevice(fixture.id))
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_device_retirement")
        }
        repository.saveSimulatorDevice(fixture)
        assertEquals(restored.copy(id = fixture.id), repository.fetchDevice(fixture.id))
    }

    @Test fun simulatorDoesNotOverwriteAnOccupiedCanonicalIdentity() = runTest(scheduler) {
        val fixture = device(radio = RADIO_A, id = RADIO_A.value).copy(publicKey = key(0x35))
        val occupied = fixture.copy(publicKey = key(0x36), nodeName = "Other radio")
        repository.saveDevice(occupied)
        val failure = assertFailsWith<PersistenceStoreException> { repository.saveSimulatorDevice(fixture) }
        assertEquals(PersistenceStoreError.InvalidData, failure.error)
        assertEquals(occupied, repository.fetchDevice(fixture.id))
    }

    @Test fun simulatorRefusesAnAliasInsteadOfCreatingAnotherIdentity() = runTest(scheduler) {
        val restored = device(
            radio = RADIO_A,
            id = UUID.fromString("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"),
        ).copy(publicKey = key(0x37))
        val fixture = restored.copy(id = RADIO_A.value)
        repository.saveDevice(restored)
        db.openHelper.writableDatabase.execSQL("UPDATE devices SET id = lower(id)")
        val failure = assertFailsWith<PersistenceStoreException> { repository.saveSimulatorDevice(fixture) }
        assertEquals(PersistenceStoreError.InvalidData, failure.error)
        assertEquals(listOf(restored), repository.fetchDevices())
    }

    @Test fun simulatorReconciliationDoesNotReadOrModifyUnrelatedMalformedDevices() = runTest(scheduler) {
        val fixture = device(radio = RADIO_A, id = RADIO_A.value).copy(publicKey = key(0x38))
        val unrelated = device(radio = RADIO_B, id = UUID.randomUUID()).copy(publicKey = key(0x39))
        repository.saveDevice(unrelated)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE devices SET id = ? WHERE id = ?", arrayOf("NOT-A-UUID", unrelated.id.canonicalString()),
        )

        repository.saveSimulatorDevice(fixture)

        assertEquals(fixture.copy(isActive = false), repository.fetchDevice(fixture.id))
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM devices WHERE id = 'NOT-A-UUID'").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
    }

    @Test fun committedGhostRetirementRejectsTicketCapturedBeforeKeepCanonical() = runTest(scheduler) {
        val canonical = device(
            radio = RADIO_A,
            id = UUID.fromString("00000000-0000-0000-0000-000000000101"),
        ).copy(publicKey = key(0x31), nodeName = "Canonical")
        val ghost = device(
            radio = RADIO_B,
            id = UUID.fromString("00000000-0000-0000-0000-000000000102"),
        ).copy(publicKey = key(0x32), nodeName = "Restored")
        repository.saveDevice(canonical)
        repository.saveDevice(ghost)

        val beforeResolution = assertNotNull(repository.fetchDeviceWriteSnapshot(canonical.id))
        repository.writeWithDeviceAuthority("resolveSimulatorIdentityKeepCanonical") {
            assertNotNull(database.devices().byId(ghost.id))
            database.devices().delete(ghost.id)
            save()
        }

        assertNull(repository.fetchDevice(ghost.id))
        assertEquals(canonical, repository.fetchDevice(canonical.id))
        val afterResolution = assertNotNull(repository.fetchDeviceWriteSnapshot(canonical.id))
        assertEquals(beforeResolution.device, afterResolution.device)
        assertNotEquals(beforeResolution.revision, afterResolution.revision)
        assertNotEquals(beforeResolution.authorityRevision, afterResolution.authorityRevision)
        assertFalse(
            repository.saveDeviceIfUnchanged(
                beforeResolution.device.copy(nodeName = "Stale edit"),
                beforeResolution,
            ),
        )
        assertEquals(canonical, repository.fetchDevice(canonical.id))
    }

    @Test fun noWriteCanonicalReseedLeavesFreshTicketValid() = runTest(scheduler) {
        val canonical = device(
            radio = RADIO_A,
            id = UUID.fromString("00000000-0000-0000-0000-000000000201"),
        ).copy(
            publicKey = key(0x41),
            nodeName = "Canonical",
            isActive = true,
            pathHashMode = 2u,
            appliedRadioPresetID = "new-preset",
        )
        repository.saveDevice(canonical)

        val beforeReseed = assertNotNull(repository.fetchDeviceWriteSnapshot(canonical.id))
        val inserted = repository.writeWithDeviceAuthority("saveSimulatorDevice") {
            if (database.devices().byId(canonical.id) != null) return@writeWithDeviceAuthority false
            database.devices().upsert(canonical.copy(isActive = false).toEntity())
            save()
            true
        }
        assertFalse(inserted)
        assertEquals(canonical, repository.fetchDevice(canonical.id))

        val freshTicket = assertNotNull(repository.fetchDeviceWriteSnapshot(canonical.id))
        assertEquals(beforeReseed.revision, freshTicket.revision)
        assertEquals(beforeReseed.authorityRevision, freshTicket.authorityRevision)
        assertTrue(
            repository.saveDeviceIfUnchanged(
                freshTicket.device.copy(nodeName = "Fresh edit"),
                freshTicket,
            ),
        )
        assertEquals(canonical.copy(nodeName = "Fresh edit"), repository.fetchDevice(canonical.id))
    }

    @Test fun retiredConditionalSaveWaitedBehindRepositoryWriterDoesNotChangeRoomWithoutRowRevision() = runTest(scheduler) {
        val original = device().copy(pathHashMode = 0u)
        repository.saveDevice(original)
        val ticket = assertNotNull(repository.fetchDeviceWriteSnapshot(original.id))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operations = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val writer = operations.async {
            authorityContext.write("hold-device-writer") {
                entered.complete(Unit)
                release.await()
            }
        }
        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            entered.await()
            val edit = async(start = CoroutineStart.UNDISPATCHED) {
                repository.saveDeviceIfUnchanged(ticket.device.copy(pathHashMode = 2u), ticket) { current.get() }
            }
            current.set(false)
            release.complete(Unit)
            writer.await()
            testScheduler.advanceUntilIdle()
            assertFalse(edit.await())
            assertEquals(original, repository.fetchDevice(original.id))
        } finally {
            release.complete(Unit)
            operations.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test fun retiredSameRadioRegionRemovalWaitedBehindRepositoryWriterLeavesRoomScopesIntact() = runTest(scheduler) {
        val original = device().copy(
            knownRegions = listOf("US915", "EU868").snapshot(),
            defaultFloodScopeName = "US915",
        )
        repository.saveDevice(original)
        val regional = ChannelDTO(radioId = original.radioId, index = 1u, name = "Scoped")
            .withFloodScope(ChannelFloodScope.Region("US915"))
        store.saveChannel(regional)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operations = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val writer = operations.async {
            authorityContext.write("hold-device-writer") {
                entered.complete(Unit)
                release.await()
            }
        }
        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            entered.await()
            val edit = async(start = CoroutineStart.UNDISPATCHED) {
                repository.removeDeviceKnownRegionIfCurrent(
                    original.id, original.radioId, "US915",
                ) { current.get() }
            }
            current.set(false)
            release.complete(Unit)
            writer.await()
            testScheduler.advanceUntilIdle()
            assertFalse(edit.await())
            assertEquals(original, repository.fetchDevice(original.id))
            assertEquals(ChannelFloodScope.Region("US915"), store.fetchChannels(original.radioId).single().floodScope)
        } finally {
            release.complete(Unit)
            operations.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test fun committedAuthorityWriteInvalidatesTicketBeforeFailingAfterCommitCallback() = runTest(scheduler) {
        val canonical = device(
            radio = RADIO_A,
            id = UUID.fromString("00000000-0000-0000-0000-000000000251"),
        ).copy(publicKey = key(0x49), nodeName = "Canonical")
        val ghost = device(
            radio = RADIO_B,
            id = UUID.fromString("00000000-0000-0000-0000-000000000252"),
        ).copy(publicKey = key(0x4A), nodeName = "Restored")
        repository.saveDevice(canonical)
        repository.saveDevice(ghost)
        val ticket = assertNotNull(repository.fetchDeviceWriteSnapshot(canonical.id))

        val failure = assertFailsWith<IllegalStateException> {
            repository.writeWithDeviceAuthority("resolveSimulatorIdentityKeepCanonical") {
                database.devices().delete(ghost.id)
                save()
                afterCommit { error("Injected after-commit failure") }
            }
        }

        assertEquals("Injected after-commit failure", failure.message)
        assertNull(repository.fetchDevice(ghost.id))
        assertFalse(
            repository.saveDeviceIfUnchanged(ticket.device.copy(nodeName = "Stale edit"), ticket),
        )
        assertEquals(canonical, repository.fetchDevice(canonical.id))
    }

    @Test fun ignoredGhostDeleteFailsAndPreservesRowsAndTicketAuthority() = runTest(scheduler) {
        val canonical = device(
            radio = RADIO_A,
            id = UUID.fromString("00000000-0000-0000-0000-000000000301"),
        ).copy(publicKey = key(0x51), nodeName = "Canonical")
        val ghost = device(
            radio = RADIO_B,
            id = UUID.fromString("00000000-0000-0000-0000-000000000302"),
        ).copy(publicKey = key(0x52), nodeName = "Restored")
        repository.saveDevice(canonical)
        repository.saveDevice(ghost)

        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER ignore_ghost_retirement BEFORE DELETE ON devices " +
                "WHEN OLD.id = '${ghost.id}' BEGIN SELECT RAISE(IGNORE); END",
        )
        val beforeResolution = assertNotNull(repository.fetchDeviceWriteSnapshot(canonical.id))
        val failure = assertFailsWith<PersistenceStoreException> {
            repository.writeWithDeviceAuthority("resolveSimulatorIdentityKeepCanonical") {
                database.devices().upsert(canonical.copy(nodeName = "Uncommitted change").toEntity())
                save()
                val deleted = database.devices().delete(ghost.id)
                if (deleted != 1) {
                    throw PersistenceStoreException(
                        PersistenceStoreError.SaveFailed("ghost retirement deleted $deleted rows"),
                    )
                }
            }
        }
        assertEquals(PersistenceStoreError.SaveFailed("ghost retirement deleted 0 rows"), failure.error)
        assertEquals(canonical, repository.fetchDevice(canonical.id))
        assertEquals(ghost, repository.fetchDevice(ghost.id))
        val afterResolution = assertNotNull(repository.fetchDeviceWriteSnapshot(canonical.id))
        assertEquals(beforeResolution.revision, afterResolution.revision)
        assertEquals(beforeResolution.authorityRevision, afterResolution.authorityRevision)
        assertTrue(
            repository.saveDeviceIfUnchanged(
                beforeResolution.device.copy(nodeName = "After ignored resolution"),
                beforeResolution,
            ),
        )
    }

    @Test fun rolledBackGhostRetirementPreservesRowsAndTicketAuthority() = runTest(scheduler) {
        val canonical = device(
            radio = RADIO_A,
            id = UUID.fromString("00000000-0000-0000-0000-000000000401"),
        ).copy(publicKey = key(0x61), nodeName = "Canonical")
        val ghost = device(
            radio = RADIO_B,
            id = UUID.fromString("00000000-0000-0000-0000-000000000402"),
        ).copy(publicKey = key(0x62), nodeName = "Restored")
        repository.saveDevice(canonical)
        repository.saveDevice(ghost)

        val rollbackTicket = assertNotNull(repository.fetchDeviceWriteSnapshot(canonical.id))
        val failure = assertFailsWith<IllegalStateException> {
            repository.writeWithDeviceAuthority("resolveSimulatorIdentityKeepCanonical") {
                database.devices().delete(ghost.id)
                save()
                error("Force Room rollback")
            }
        }
        assertEquals("Force Room rollback", failure.message)
        assertEquals(ghost, repository.fetchDevice(ghost.id))
        assertTrue(
            repository.saveDeviceIfUnchanged(
                rollbackTicket.device.copy(pathHashMode = 2u),
                rollbackTicket,
            ),
        )
        assertEquals(
            rollbackTicket.device.copy(pathHashMode = 2u),
            repository.fetchDevice(canonical.id),
        )
    }
}
