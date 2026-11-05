// AndroidOnly: WP-305 Real Room controls for per-device authority, shared-store CAS and atomic regions.
package com.meshcoreone.android.app.container

import androidx.room.withTransaction
import com.meshcoreone.android.core.contracts.domain.DeviceWriteConflict
import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.editDevice
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.snapshot
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DevicePersistenceAuthorityRoomTest : RoomProcessTest() {
    private class PausedStore(private val room: RoomPersistenceStore) : PersistenceStoreProtocol by room {
        var beforeSave: suspend () -> Unit = {}
        var rejected = 0

        override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
            val hook = beforeSave
            beforeSave = {}
            hook()
            return room.saveDeviceIfUnchanged(dto, expected).also { if (!it) rejected++ }
        }
    }

    @Test fun unrelatedDemotionDoesNotInvalidateAnInflightManagerEdit() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        val release = CompletableDeferred<Unit>()
        try {
            h.manager.connect(h.target())
            h.settle()
            val original = assertNotNull(h.manager.connectedDevice)
            val unrelated = device(601)
            store.saveDevice(unrelated)
            val entered = CompletableDeferred<Unit>()
            paused.beforeSave = { entered.complete(Unit); release.await() }
            val edit = async {
                h.container.deviceEditor.updatePathHashMode(2u, h.manager.snapshot.value.token!!)
            }
            entered.await()
            store.demoteDeviceToGhost(unrelated.id)
            release.complete(Unit)
            assertTrue(edit.await())
            assertEquals(0, paused.rejected, "Unrelated device writes must not invalidate this ticket")
            assertEquals(original.copy(pathHashMode = 2u), store.fetchDevice(original.id))
            assertEquals(store.fetchDevice(original.id), h.manager.connectedDevice)
        } finally { release.complete(Unit); h.container.close() }
    }

    @Test fun sameDatabaseSecondStoreKeyReplacementStopsStaleTransformRetry() = runTest {
        val original = device(602)
        store.saveDevice(original)
        val other = RoomPersistenceStore(db, backgroundScope)
        val paused = PausedStore(store)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var transforms = 0
        try {
            paused.beforeSave = { entered.complete(Unit); release.await() }
            val edit = async {
                runCatching { paused.editDevice(original.id) { transforms++; it.copy(pathHashMode = 2u) } }
            }
            entered.await()
            assertEquals(original.copy(publicKey = key(9)), other.editDevice(original.id) { it.copy(publicKey = key(9)) })
            release.complete(Unit)
            assertIs<DeviceWriteConflict>(edit.await().exceptionOrNull())
            assertEquals(1, transforms, "Never apply an old device-key transform under a new authority")
            assertEquals(1, paused.rejected)
            assertEquals(original.copy(publicKey = key(9)), store.fetchDevice(original.id))
        } finally { release.complete(Unit); other.close() }
    }

    @Test fun sameDatabaseSecondStoreTargetedWriteRebasesWithoutLosingItsWatermark() = runTest {
        val original = device(603)
        store.saveDevice(original)
        val other = RoomPersistenceStore(db, backgroundScope)
        val paused = PausedStore(store)
        try {
            paused.beforeSave = { other.updateDeviceLastContactSync(original.radioId, 9876u) }
            val saved = paused.editDevice(original.id) { it.copy(pathHashMode = 2u) }
            assertEquals(1, paused.rejected)
            assertEquals(original.copy(pathHashMode = 2u, lastContactSync = 9876u), saved)
            assertEquals(saved, other.fetchDevice(original.id))
        } finally { other.close() }
    }

    @Test fun identicalFullReplacementIsStillAnAuthorityBarrierAcrossStores() = runTest {
        val original = device(604)
        store.saveDevice(original)
        val ticket = assertNotNull(store.fetchDeviceWriteSnapshot(original.id))
        val other = RoomPersistenceStore(db, backgroundScope)
        try {
            other.saveDevice(original)
            assertFalse(store.saveDeviceIfUnchanged(original.copy(pathHashMode = 2u), ticket))
            val current = assertNotNull(store.fetchDeviceWriteSnapshot(original.id))
            assertEquals(ticket.authorityRevision + 1, current.authorityRevision)
            assertEquals(original, current.device)
        } finally { other.close() }
    }

    @Test fun activationInvalidatesOnlyTargetAndPreviouslyActiveDevice() = runTest {
        val first = device(605)
        val next = device(606)
        val unrelated = device(607)
        listOf(first, next, unrelated).forEach { store.saveDevice(it) }
        store.setActiveDevice(first.id)
        val firstTicket = assertNotNull(store.fetchDeviceWriteSnapshot(first.id))
        val nextTicket = assertNotNull(store.fetchDeviceWriteSnapshot(next.id))
        val unrelatedTicket = assertNotNull(store.fetchDeviceWriteSnapshot(unrelated.id))
        store.setActiveDevice(next.id)
        assertFalse(store.saveDeviceIfUnchanged(firstTicket.device.copy(pathHashMode = 2u), firstTicket))
        assertFalse(store.saveDeviceIfUnchanged(next.copy(pathHashMode = 2u), nextTicket))
        assertTrue(store.saveDeviceIfUnchanged(unrelated.copy(pathHashMode = 2u), unrelatedTicket))
        assertEquals(next.id, store.fetchActiveDevice()!!.id)
    }

    @Test fun genericRegionEditPersistsExactlyAndResetsOnlyRemovedRegionScopes() = runTest {
        val original = seedRegions(608)
        val channels = listOf(
            channel(original.radioId, 1, ChannelFloodScope.Region("US915")),
            channel(original.radioId, 2, ChannelFloodScope.Region("EU868")),
            channel(original.radioId, 3, ChannelFloodScope.Region("AU915")),
            channel(original.radioId, 4, ChannelFloodScope.AllRegions),
            channel(original.radioId, 5, ChannelFloodScope.Inherit),
            channel(RadioId(UUID(0, 999)), 1, ChannelFloodScope.Region("US915")),
        )
        channels.forEach { store.saveChannel(it) }
        val saved = store.editDevice(original.id) {
            it.copy(knownRegions = listOf("AU915", "JP920").snapshot(), defaultFloodScopeName = null)
        }
        assertEquals(original.copy(knownRegions = listOf("AU915", "JP920").snapshot(), defaultFloodScopeName = null), saved)
        assertEquals(saved, store.fetchDevice(original.id), "Success must describe the actual persisted DTO")
        for ((index, row) in channels.withIndex()) {
            val expected = if (index < 2) ChannelFloodScope.Inherit else row.floodScope
            assertEquals(expected, store.fetchChannel(row.radioId, row.index)!!.floodScope)
        }
    }

    @Test fun genericRegionEditRejectsAnOmittedDefaultClearRatherThanReturningANormalizedDto() = runTest {
        val original = seedRegions(609)
        val result = runCatching {
            store.editDevice(original.id) { it.copy(knownRegions = listOf("AU915").snapshot()) }
        }
        assertEquals(PersistenceStoreError.InvalidData, assertIs<PersistenceStoreException>(result.exceptionOrNull()).error)
        assertEquals(original, store.fetchDevice(original.id))
    }

    @Test fun failedChannelResetRollsBackRegionEditAndBothRevisionCounters() = runTest {
        val original = seedRegions(610)
        val scoped = channel(original.radioId, 1, ChannelFloodScope.Region("US915"))
        store.saveChannel(scoped)
        val before = assertNotNull(store.fetchDeviceWriteSnapshot(original.id))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_region_scope BEFORE UPDATE ON channels " +
                "BEGIN SELECT RAISE(FAIL, 'scope reset failed'); END",
        )
        try {
            val result = runCatching {
                store.editDevice(original.id) {
                    it.copy(knownRegions = listOf("AU915").snapshot(), defaultFloodScopeName = null)
                }
            }
            assertIs<PersistenceStoreException>(result.exceptionOrNull())
            assertEquals(original, store.fetchDevice(original.id))
            assertEquals(scoped, store.fetchChannel(scoped.radioId, scoped.index))
            val after = assertNotNull(store.fetchDeviceWriteSnapshot(original.id))
            assertEquals(before.revision, after.revision)
            assertEquals(before.authorityRevision, after.authorityRevision)
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_region_scope") }
    }

    @Test fun retirementDetectedAfterStagedRegionAndChannelWritesRollsBackEverything() = runTest {
        val original = seedRegions(611)
        val scoped = channel(original.radioId, 1, ChannelFloodScope.Region("US915"))
        store.saveChannel(scoped)
        val ticket = assertNotNull(store.fetchDeviceWriteSnapshot(original.id))
        var guardCalls = 0
        val accepted = store.saveDeviceIfUnchanged(
            original.copy(knownRegions = listOf("AU915").snapshot(), defaultFloodScopeName = null),
            ticket.copy(isCurrent = { ++guardCalls == 1 }),
        )
        assertFalse(accepted)
        assertEquals(2, guardCalls, "The second guard follows the staged DAO work, not only transaction entry")
        assertEquals(original, store.fetchDevice(original.id))
        assertEquals(scoped, store.fetchChannel(scoped.radioId, scoped.index))
        val after = assertNotNull(store.fetchDeviceWriteSnapshot(original.id))
        assertEquals(ticket.revision, after.revision)
        assertEquals(ticket.authorityRevision, after.authorityRevision)
    }

    @Test fun retirementDetectedAfterStagedActivationDoesNotCommitTheOldDevice() = runTest {
        val original = device(612)
        val successor = device(613)
        store.saveDevice(original)
        store.saveDevice(successor)
        store.setActiveDevice(successor.id)
        val before = assertNotNull(store.fetchDeviceWriteSnapshot(successor.id))
        var guardCalls = 0
        assertFalse(store.activateDeviceIfCurrent(original.id) { ++guardCalls == 1 })
        assertEquals(2, guardCalls)
        assertEquals(successor.id, store.fetchActiveDevice()!!.id)
        assertFalse(store.fetchDevice(original.id)!!.isActive)
        val after = assertNotNull(store.fetchDeviceWriteSnapshot(successor.id))
        assertEquals(before.revision, after.revision)
        assertEquals(before.authorityRevision, after.authorityRevision)
    }

    @Test fun ticketAuthorityLeaseSpansTheActualRoomWaitCommitAndRevisionPublication() = runTest {
        val original = device(614)
        store.saveDevice(original)
        val ticket = assertNotNull(store.fetchDeviceWriteSnapshot(original.id))
        val authority = Mutex()
        val transactionEntered = CompletableDeferred<Unit>()
        val releaseTransaction = CompletableDeferred<Unit>()
        val authorityEntered = CompletableDeferred<Unit>()
        val retirementSubmitted = CompletableDeferred<Unit>()
        var current = true
        val holder = async(Dispatchers.IO) {
            db.withTransaction { transactionEntered.complete(Unit); releaseTransaction.await() }
        }
        try {
            transactionEntered.await()
            val saved = async(Dispatchers.IO) {
                store.saveDeviceIfUnchanged(original.copy(pathHashMode = 2u), ticket.copy(
                    isCurrent = { current },
                    withAuthority = { write ->
                        authority.withLock {
                            authorityEntered.complete(Unit)
                            write().also { accepted ->
                                assertTrue(accepted)
                                assertEquals(original.copy(pathHashMode = 2u), store.fetchDevice(original.id))
                                assertEquals(ticket.revision + 1, store.fetchDeviceWriteSnapshot(original.id)!!.revision)
                            }
                        }
                    },
                ))
            }
            authorityEntered.await()
            val retired = async {
                retirementSubmitted.complete(Unit)
                authority.withLock { current = false }
            }
            retirementSubmitted.await()
            assertFalse(retired.isCompleted, "Retirement cannot cross the authority lease while Room is suspended")
            assertFalse(saved.isCompleted)
            releaseTransaction.complete(Unit)
            assertTrue(saved.await())
            retired.await()
            holder.await()
            assertFalse(current)
            assertEquals(original.copy(pathHashMode = 2u), store.fetchDevice(original.id))
        } finally { releaseTransaction.complete(Unit) }
    }

    @Test fun retirementWinningTheAuthorityLeasePreventsTheConditionalWrite() = runTest {
        val original = device(615)
        store.saveDevice(original)
        val ticket = assertNotNull(store.fetchDeviceWriteSnapshot(original.id))
        val authority = Mutex()
        var current = true
        authority.withLock { current = false }
        assertFalse(store.saveDeviceIfUnchanged(original.copy(pathHashMode = 2u), ticket.copy(
            isCurrent = { current },
            withAuthority = { write -> authority.withLock { write() } },
        )))
        assertEquals(original, store.fetchDevice(original.id))
        assertEquals(ticket.revision, store.fetchDeviceWriteSnapshot(original.id)!!.revision)
    }

    private suspend fun seedRegions(id: Long): DeviceDTO {
        val original = device(id)
        store.saveDevice(original)
        listOf("US915", "EU868", "AU915").forEach { store.addDeviceKnownRegion(original.radioId, it) }
        val ticket = assertNotNull(store.fetchDeviceWriteSnapshot(original.id))
        assertTrue(store.saveDeviceIfUnchanged(ticket.device.copy(defaultFloodScopeName = "US915"), ticket))
        return assertNotNull(store.fetchDevice(original.id))
    }

    private fun device(id: Long) = DeviceDTO(
        id = UUID(1, id), radioId = RadioId(UUID(0, id)), publicKey = key(7), nodeName = "Device $id",
    )

    private fun channel(radioId: RadioId, index: Int, scope: ChannelFloodScope) =
        ChannelDTO(radioId = radioId, index = index.toUByte(), name = "Region $index").withFloodScope(scope)
}
