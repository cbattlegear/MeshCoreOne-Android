// AndroidOnly: WP-209 Real Room scoped channel writes, restore exclusion and capability lifetime.
package com.meshcoreone.android.core.data.repository

import androidx.room.Room
import androidx.room.withTransaction
import com.meshcoreone.android.core.contracts.domain.ChannelIdentityWriteScope
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChannelOperationRoomTest : RepositoryTest() {
    private fun repository(): ChannelRepository {
        val context = RoomRepositoryContext(db, clock, owner, RepositoryIssueReporter { _, error -> issues += error })
        return ChannelRepository(context, RoomRepository(context))
    }

    private fun channel() =
        ChannelDTO(radioId = RADIO_A, index = 2u, name = "original", secret = Bytes(ByteArray(16) { 1 }))

    // Deliberate persisted-state fixture, not a lifecycle issuer or evidence of live radio resolution.
    private suspend fun resolvedStorageFixture(row: ChannelDTO): String {
        store.saveDevice(device(radio = row.radioId))
        store.saveChannel(row)
        assertNull(store.fetchChannelOccupantEpoch(row.radioId, row.index))
        return db.withTransaction {
            val authority = assertNotNull(db.channelOccupantAuthorities().forSlot(row.radioId.value, row.index.toLong()))
            assertFalse(authority.isResolved)
            assertNull(authority.boundRowId)
            assertEquals(1, db.channelOccupantAuthorities().resolveCurrent(
                row.radioId.value, row.index.toLong(), authority.epoch, row.id, row.secret,
            ))
            authority.epoch
        }
    }

    @Test fun scopedWritesUseTheActualRoomTransactionWithoutReacquiringTheOperationLane() = runTest {
        val row = channel()
        store.saveChannel(row)
        repository().withChannelOperation { scoped, scope ->
            scoped.saveChannel(row.copy(name = "scoped metadata"))
            assertEquals(row.copy(name = "scoped metadata"), scoped.fetchChannel(entity(id = row.id)))
            assertFailsWith<IllegalStateException> { store.saveChannel(row.copy(name = "recursive ordinary")) }
            assertFailsWith<IllegalStateException> {
                scoped.withChannelOperation { _, _ -> fail("Scoped facade reacquired the outer operation") }
            }
            scope.withChannelIdentityMutation {
                assertEquals(row.copy(name = "scoped metadata"), store.fetchChannel(entity(id = row.id)))
            }
        }
        assertEquals(row.copy(name = "scoped metadata"), store.fetchChannel(entity(id = row.id)))
    }

    @Test fun escapedScopedFacadeAndForeignJobCannotWriteAfterOrDuringItsOwnerCallback() = runTest {
        val row = channel()
        store.saveChannel(row)
        lateinit var escaped: ChannelRepository
        lateinit var escapedScope: ChannelIdentityWriteScope
        repository().withChannelOperation { scoped, scope ->
            escaped = scoped
            escapedScope = scope
            async {
                assertFailsWith<IllegalStateException> { scoped.saveChannel(row.copy(name = "foreign job")) }
            }.await()
            assertEquals(row, scoped.fetchChannel(entity(id = row.id)))
        }
        assertFailsWith<IllegalStateException> { escaped.saveChannel(row.copy(name = "escaped facade")) }
        assertFailsWith<IllegalStateException> {
            escapedScope.withChannelIdentityMutation { fail("Expired scope admitted an identity write") }
        }
        assertEquals(row, store.fetchChannel(entity(id = row.id)))
    }

    @Test fun heldOperationExcludesAnotherRepositoryHandleAndActualRestoreUntilRelease() = runTest {
        val row = channel()
        store.saveChannel(row)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()
        val held = launch {
            repository().withChannelOperation { scoped, _ ->
                scoped.saveChannel(row.copy(name = "held metadata"))
                entered.complete(Unit)
                release.await()
                effects += "operation completed"
            }
        }
        entered.await()
        val replacement = launch {
            repository().saveChannel(row.copy(name = "later metadata"))
            effects += "other handle committed"
        }
        val restore = launch {
            store.withBackupRestore { _, _ -> effects += "restore transaction" }
        }
        runCurrent()
        assertFalse(replacement.isCompleted)
        assertFalse(restore.isCompleted)
        assertEquals(row.copy(name = "held metadata"), store.fetchChannel(entity(id = row.id)))
        release.complete(Unit)
        held.join()
        replacement.join()
        restore.join()
        assertEquals("operation completed", effects.first())
        assertEquals(setOf("operation completed", "other handle committed", "restore transaction"), effects.toSet())
    }

    @Test fun cancellingAQueuedWriterLeavesItsTextUnchangedAndReleasesOnlyItsOwnWait() = runTest {
        val row = channel()
        store.saveChannel(row)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = launch {
            repository().withChannelOperation { _, _ ->
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val queued = launch { repository().saveChannel(row.copy(name = "cancelled write")) }
        runCurrent()
        assertFalse(queued.isCompleted)
        queued.cancelAndJoin()
        assertEquals(row, store.fetchChannel(entity(id = row.id)))
        assertFalse(held.isCompleted)
        release.complete(Unit)
        held.join()
        store.saveChannel(row.copy(name = "subsequent write"))
        assertEquals(row.copy(name = "subsequent write"), store.fetchChannel(entity(id = row.id)))
    }

    @Test fun deviceIdentityErasureCannotOvertakeAnOwnedChannelOperation() = runTest {
        val device = device()
        store.saveDevice(device)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = launch {
            repository().withChannelOperation { _, _ ->
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val deleting = launch { store.deleteDevice(device.id) }
        runCurrent()
        assertFalse(deleting.isCompleted)
        assertEquals(device, store.fetchDevice(device.id))
        release.complete(Unit)
        held.join()
        deleting.join()
        assertNull(store.fetchDevice(device.id))
    }

    @Test fun metadataAndExactReseedPreserveTheOpaqueStorageEpochButKnownKeyChangeInvalidatesIt() = runTest {
        val row = channel()
        val epoch = resolvedStorageFixture(row)
        assertEquals(epoch, store.fetchChannelOccupantEpoch(row.radioId, row.index))
        val before = assertNotNull(db.channelOccupantAuthorities().forSlot(row.radioId.value, row.index.toLong()))
        val renamed = row.copy(name = "renamed", isFavorite = true, unreadCount = 8)
        store.saveChannel(renamed)
        repeat(3) { store.saveChannel(row.copy(id = UUID.randomUUID())) }
        assertEquals(renamed, store.fetchChannel(entity(id = row.id)))
        assertEquals(before, db.channelOccupantAuthorities().forSlot(row.radioId.value, row.index.toLong()))
        assertEquals(epoch, store.fetchChannelOccupantEpoch(row.radioId, row.index))
        store.saveChannel(renamed.copy(secret = Bytes(ByteArray(16) { 2 })))
        assertEquals(row.id, store.fetchChannels(row.radioId).single().id)
        val invalidated = assertNotNull(db.channelOccupantAuthorities().forSlot(row.radioId.value, row.index.toLong()))
        assertNotEquals(epoch, invalidated.epoch)
        assertNull(invalidated.boundRowId)
        assertFalse(invalidated.isResolved)
        assertNull(store.fetchChannelOccupantEpoch(row.radioId, row.index))
    }

    @Test fun mismatchedReseedRefusesBeforeEpochMutationAndBothEnableTransitionsInvalidate() = runTest {
        val row = channel()
        val epoch = resolvedStorageFixture(row)
        val before = db.channelOccupantAuthorities().forSlot(row.radioId.value, row.index.toLong())
        for (dto in listOf(
            row.copy(id = UUID.randomUUID(), secret = Bytes(ByteArray(16) { 3 })),
            row.copy(id = UUID.randomUUID(), isEnabled = false),
        )) {
            val failure = assertFailsWith<PersistenceStoreException> { store.saveChannel(dto) }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
            assertEquals(row, store.fetchChannel(entity(id = row.id)))
            assertEquals(before, db.channelOccupantAuthorities().forSlot(row.radioId.value, row.index.toLong()))
        }
        var previousEpoch = epoch
        for (enabled in listOf(false, true)) {
            store.saveChannel(row.copy(isEnabled = enabled))
            val state = assertNotNull(db.channelOccupantAuthorities().forSlot(row.radioId.value, row.index.toLong()))
            assertNotEquals(previousEpoch, state.epoch)
            assertFalse(state.isResolved)
            assertNull(state.boundRowId)
            assertNull(store.fetchChannelOccupantEpoch(row.radioId, row.index))
            previousEpoch = state.epoch
        }
    }

    @Test fun actualRestoreInvalidatesAllStorageEpochsAtomicallyAndRollbackPreservesThem() = runTest {
        val row = channel()
        val epoch = resolvedStorageFixture(row)
        val foreign = row.copy(radioId = RADIO_B, id = UUID.randomUUID())
        val foreignEpoch = resolvedStorageFixture(foreign)
        val before = db.channelOccupantAuthorities().forSlot(row.radioId.value, row.index.toLong())
        assertFailsWith<PersistenceStoreException> {
            store.withBackupRestore { _, _ ->
                throw PersistenceStoreException(PersistenceStoreError.InvalidData)
            }
        }
        assertEquals(before, db.channelOccupantAuthorities().forSlot(row.radioId.value, row.index.toLong()))
        assertEquals(epoch, store.fetchChannelOccupantEpoch(row.radioId, row.index))
        store.withBackupRestore { _, _ -> Unit }
        for ((channel, oldEpoch) in listOf(row to epoch, foreign to foreignEpoch)) {
            val state = assertNotNull(db.channelOccupantAuthorities().forSlot(channel.radioId.value, channel.index.toLong()))
            assertNotEquals(oldEpoch, state.epoch)
            assertFalse(state.isResolved)
            assertNull(state.boundRowId)
            assertNull(store.fetchChannelOccupantEpoch(channel.radioId, channel.index))
        }
    }

    @Test fun duplicateSlotsRefuseEpochReadAndRadioSyncBeforeAnyRowMutation() = runTest {
        val row = channel()
        resolvedStorageFixture(row)
        db.channels().insert(row.copy(id = UUID.randomUUID(), name = "duplicate").toEntity())
        val before = store.fetchChannels(row.radioId)
        val authority = db.channelOccupantAuthorities().forSlot(row.radioId.value, row.index.toLong())
        val readFailure = assertFailsWith<PersistenceStoreException> {
            store.fetchChannelOccupantEpoch(row.radioId, row.index)
        }
        assertEquals(PersistenceStoreError.InvalidData, readFailure.error)
        val info = ChannelInfo(row.index, "radio sync", row.secret)
        val syncFailure = assertFailsWith<PersistenceStoreException> { store.saveChannel(row.radioId, info) }
        assertEquals(PersistenceStoreError.InvalidData, syncFailure.error)
        assertEquals(before, store.fetchChannels(row.radioId))
        assertEquals(authority, db.channelOccupantAuthorities().forSlot(row.radioId.value, row.index.toLong()))
    }

    @Test fun missingInvalidationTriggerFailsClosedWithoutRepairOrRepositoryMutation() = runTest {
        val row = channel()
        val epoch = resolvedStorageFixture(row)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER channel_occupant_update")
        val readFailure = assertFailsWith<PersistenceStoreException> {
            store.fetchChannelOccupantEpoch(row.radioId, row.index)
        }
        assertEquals(PersistenceStoreError.InvalidData, readFailure.error)
        val writeFailure = assertFailsWith<PersistenceStoreException> {
            store.saveChannel(row.copy(secret = Bytes(ByteArray(16) { 4 })))
        }
        assertEquals(PersistenceStoreError.InvalidData, writeFailure.error)
        assertEquals(row, store.fetchChannel(entity(id = row.id)))
        assertEquals(epoch, db.channelOccupantAuthorities().forSlot(row.radioId.value, row.index.toLong())?.epoch)
        assertFailsWith<IllegalStateException> { db.requireChannelOccupantTriggersInstalled() }
    }

    @Test fun repositoryEpochBindingAndDurableInvalidationSurviveActualDatabaseCloseAndReopen() = runTest {
        val name = "channel-epoch-${UUID.randomUUID()}.db"
        val immediate = Executor { it.run() }
        fun open() = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        var disk = open()
        fun openStore() = RoomPersistenceStore(disk, owner, clock, RepositoryIssueReporter { _, error -> issues += error })
        var handle = openStore()
        try {
            val row = channel()
            handle.saveDevice(device())
            handle.saveChannel(row)
            assertNull(handle.fetchChannelOccupantEpoch(row.radioId, row.index))
            val original = disk.withTransaction {
                val state = assertNotNull(disk.channelOccupantAuthorities().forSlot(row.radioId.value, row.index.toLong()))
                assertEquals(1, disk.channelOccupantAuthorities().resolveCurrent(
                    row.radioId.value, row.index.toLong(), state.epoch, row.id, row.secret,
                ))
                assertNotNull(disk.channelOccupantAuthorities().forSlot(row.radioId.value, row.index.toLong()))
            }
            handle.close()
            disk.close()
            disk = open()
            handle = openStore()
            assertEquals(original, disk.channelOccupantAuthorities().forSlot(row.radioId.value, row.index.toLong()))
            assertEquals(original.epoch, handle.fetchChannelOccupantEpoch(row.radioId, row.index))
            handle.saveChannel(row.copy(isEnabled = false))
            val invalidated = assertNotNull(disk.channelOccupantAuthorities().forSlot(row.radioId.value, row.index.toLong()))
            assertNotEquals(original.epoch, invalidated.epoch)
            assertFalse(invalidated.isResolved)
            assertNull(invalidated.boundRowId)
            handle.close()
            disk.close()
            disk = open()
            handle = openStore()
            assertEquals(invalidated, disk.channelOccupantAuthorities().forSlot(row.radioId.value, row.index.toLong()))
            assertNull(handle.fetchChannelOccupantEpoch(row.radioId, row.index))
            assertEquals(row.copy(isEnabled = false), handle.fetchChannel(entity(id = row.id)))
        } finally {
            handle.close()
            disk.close()
            assertTrue(context.deleteDatabase(name))
        }
    }
}
