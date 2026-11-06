// AndroidOnly: WP-209 Participating Room writers versus held dispatch; no original-admission identity surrogate.
package com.meshcoreone.android.core.data.repository

import androidx.room.Room
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.MessageStatus
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.junit.Test

class RepositoryMutationGuardRoomTest : RepositoryTest() {
    @Test fun dispatchHoldsSameStoreMessageAndPendingWritersButAllowsReadAfterValidation() = runBlocking {
        val original = message(status = MessageStatus.FAILED)
        store.saveMessage(original)
        val queued = pending(messageID = original.id)
        store.upsertPendingSend(queued)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dispatch = async(start = CoroutineStart.UNDISPATCHED) {
            RepositoryMutationGuard.withDispatch {
                assertEquals(original, store.fetchMessage(entity(id = original.id)))
                assertFalse(db.inTransaction())
                entered.complete(Unit)
                release.await()
                assertEquals(original, store.fetchMessage(entity(id = original.id)))
                assertEquals(listOf(queued), store.fetchPendingSendsForMessage(entity(id = original.id)).toList())
            }
        }
        entered.await()
        val write = async(start = CoroutineStart.UNDISPATCHED) {
            store.saveMessage(original.copy(text = "successor"))
            store.deletePendingSendsForMessage(entity(id = original.id))
        }
        assertFalse(write.isCompleted)
        write.cancelAndJoin()
        release.complete(Unit)
        dispatch.await()
        assertEquals(original, store.fetchMessage(entity(id = original.id)))
    }

    @Test fun separateRepositoryContextsCannotReplaceRowsUntilWireAcceptanceReturns() = runBlocking {
        val second = newStore()
        val row = message()
        store.saveMessage(row)
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val dispatch = async(start = CoroutineStart.UNDISPATCHED) {
                RepositoryMutationGuard.withDispatch {
                    assertEquals(row, store.fetchMessage(entity(id = row.id)))
                    entered.complete(Unit)
                    release.await()
                    assertEquals(row, second.fetchMessage(entity(id = row.id)))
                }
            }
            entered.await()
            val writer = async(start = CoroutineStart.UNDISPATCHED) { second.saveMessage(row.copy(text = "new")) }
            assertFalse(writer.isCompleted)
            release.complete(Unit)
            dispatch.await()
            writer.await()
            assertEquals("new", store.fetchMessage(entity(id = row.id))?.text)
        } finally {
            second.close()
        }
    }

    @Test fun heldWriterCommitsBeforeWaitingDispatchReadsItsCurrentRow() = runBlocking {
        val row = message()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = async(start = CoroutineStart.UNDISPATCHED) {
            store.withBackupRestore { database, _ ->
                database.messages().upsert(row.toEntity())
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        var wire = false
        val dispatch = async(start = CoroutineStart.UNDISPATCHED) {
            RepositoryMutationGuard.withDispatch {
                assertEquals(row, store.fetchMessage(entity(id = row.id)))
                assertFalse(db.inTransaction())
                wire = true
            }
        }
        assertFalse(dispatch.isCompleted)
        assertFalse(wire)
        release.complete(Unit)
        writer.await()
        dispatch.await()
        assertTrue(wire)
    }

    @Test fun restoreGuardedDeleteRxFlushAndCloseAllWaitForTheProcessLane() = runBlocking {
        store.saveContact(contact())
        val writes: List<suspend () -> Unit> = listOf(
            { store.deleteContactIfUnreferenced(entity()) },
            { store.withBackupRestore { _, _ -> Unit } },
            { store.saveRxLogEntry(rx()) },
            { store.flushPendingRxLogEntries() },
            { store.close() },
        )
        for (write in writes) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val dispatch = async(start = CoroutineStart.UNDISPATCHED) {
                RepositoryMutationGuard.withDispatch {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val writer = async(start = CoroutineStart.UNDISPATCHED) { write() }
            assertFalse(writer.isCompleted)
            release.complete(Unit)
            dispatch.await()
            writer.await()
        }
    }

    @Test fun sameProcessSeparateDatabaseHandlesShareTheActualDispatchLane() = runBlocking {
        val name = "wp209-guard-${UUID.randomUUID()}.db"
        val firstDb = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val secondDb = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val first = RoomPersistenceStore(firstDb, owner, clock)
        val second = RoomPersistenceStore(secondDb, owner, clock)
        try {
            val row = message()
            first.saveMessage(row)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val dispatch = async(start = CoroutineStart.UNDISPATCHED) {
                RepositoryMutationGuard.withDispatch {
                    assertEquals(row, first.fetchMessage(entity(id = row.id)))
                    entered.complete(Unit)
                    release.await()
                    assertEquals(row, second.fetchMessage(entity(id = row.id)))
                }
            }
            entered.await()
            val writer = async(start = CoroutineStart.UNDISPATCHED) { second.deleteMessage(entity(id = row.id)) }
            assertFalse(writer.isCompleted)
            release.complete(Unit)
            dispatch.await()
            writer.await()
            assertNull(first.fetchMessage(entity(id = row.id)))
        } finally {
            first.close()
            second.close()
            firstDb.close()
            secondDb.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun nestedWriterAndDispatchFailExplicitlyIncludingInheritedChildren() = runBlocking {
        RepositoryMutationGuard.withDispatch {
            val failure = assertFailsWith<PersistenceStoreException> { store.saveMessage(message()) }
            assertIs<PersistenceStoreError.SaveFailed>(failure.error)
            assertFailsWith<PersistenceStoreException> { RepositoryMutationGuard.withDispatch {} }
            coroutineScope {
                val child = async {
                    assertFailsWith<PersistenceStoreException> { store.deletePendingSendsForMessage(entity()) }
                }
                child.await()
            }
        }
        assertEquals(2, issues.size)
        store.saveMessage(message())
    }

    @Test fun cancelledDispatchWaitNeverEntersAndCancelledHeldDispatchReleasesTheLane() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async(start = CoroutineStart.UNDISPATCHED) {
            RepositoryMutationGuard.withDispatch {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        var wire = false
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            RepositoryMutationGuard.withDispatch { wire = true }
        }
        waiter.cancelAndJoin()
        assertFalse(wire)
        holder.cancelAndJoin()
        val row = message()
        store.saveMessage(row)
        RepositoryMutationGuard.withDispatch {
            assertEquals(row, store.fetchMessage(entity(id = row.id)))
        }
    }

    @Test fun markerPreservesTheActualCallerJobAndRestorePostCommitCancellationResult() = runBlocking {
        val caller = currentCoroutineContext()[Job]
        RepositoryMutationGuard.withDispatch {
            assertSame(caller, currentCoroutineContext()[Job])
        }
        var committedResult: String? = null
        var remainsCancelled = false
        val restore = async(start = CoroutineStart.UNDISPATCHED) {
            val original = currentCoroutineContext()[Job]!!
            committedResult = store.withBackupRestore(afterCommit = { original.cancel() }) { _, _ -> "committed" }
            try {
                currentCoroutineContext().ensureActive()
            } catch (_: CancellationException) {
                remainsCancelled = true
            }
        }
        restore.join()
        assertTrue(restore.isCancelled)
        assertEquals("committed", committedResult)
        assertTrue(remainsCancelled)
        store.saveMessage(message())
    }

    @Test fun roomWorkerCannotReenterTheMutationLaneAndFailureRollsBackBeforeRelease() = runBlocking {
        val row = message()
        val failure = assertFailsWith<PersistenceStoreException> {
            store.withBackupRestore { database, _ ->
                database.messages().upsert(row.toEntity())
                store.saveMessage(message())
            }
        }
        assertIs<PersistenceStoreError.SaveFailed>(failure.error)
        assertNull(store.fetchMessage(entity(id = row.id)))
        RepositoryMutationGuard.withDispatch {
            assertNull(store.fetchMessage(entity(id = row.id)))
        }
    }
}
