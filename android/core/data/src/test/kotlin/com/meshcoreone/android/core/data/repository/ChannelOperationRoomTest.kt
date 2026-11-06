// AndroidOnly: WP-209 Real Room scoped channel writes, restore exclusion and capability lifetime.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.ChannelIdentityWriteScope
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
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
}
