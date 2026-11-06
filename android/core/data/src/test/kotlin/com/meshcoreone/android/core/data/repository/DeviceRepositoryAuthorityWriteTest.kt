// AndroidOnly: WP-305 Test device-writer authority for simulator identity reconciliation.
package com.meshcoreone.android.core.data.repository

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    @Test fun ignoredAndRolledBackGhostRetirementsPreserveRowsAndTicketAuthority() = runTest(scheduler) {
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

        val ignoredTicket = assertNotNull(repository.fetchDeviceWriteSnapshot(canonical.id))
        val ignored = repository.writeWithDeviceAuthority("resolveSimulatorIdentityKeepCanonical") {
            assertNotNull(database.devices().byId(ghost.id))
            false
        }
        assertFalse(ignored)
        assertEquals(ghost, repository.fetchDevice(ghost.id))
        assertTrue(
            repository.saveDeviceIfUnchanged(
                ignoredTicket.device.copy(nodeName = "After ignored resolution"),
                ignoredTicket,
            ),
        )

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
