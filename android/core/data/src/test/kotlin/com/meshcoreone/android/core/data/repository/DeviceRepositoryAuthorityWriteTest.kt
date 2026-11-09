// AndroidOnly: WP-305 Test device-writer authority for simulator identity reconciliation.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
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
