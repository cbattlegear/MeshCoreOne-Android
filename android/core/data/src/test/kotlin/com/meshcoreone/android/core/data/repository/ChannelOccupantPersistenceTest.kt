// AndroidOnly: WP-202 Durable channel intent, ABA replacement, legacy and restart regressions using real Room.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.ChannelIdentityMutex
import com.meshcoreone.android.core.contracts.domain.ChannelPersisting
import com.meshcoreone.android.core.contracts.domain.ChannelIdentityWriteScope
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChannelOccupantPersistenceTest : RepositoryTest() {
    private fun info(name: String = "original", byte: Int = 1) =
        ChannelInfo(5u, name, Bytes(ByteArray(16) { byte.toByte() }))

    @Test fun renamePreservesEpochButReplacementAndSameSecretRecreationDoNotReuseIt() = runTest {
        val rowID = store.saveChannel(RADIO_A, info())
        val first = assertNotNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        val selected = assertNotNull(store.fetchChannelOccupantSnapshot(RADIO_A, 5u))
        assertEquals(first, selected.occupantID)
        assertEquals(info().secret, selected.channel.secret)
        store.saveChannel(RADIO_A, info("renamed"))
        assertEquals(first, store.fetchChannelOccupantID(RADIO_A, 5u))
        assertEquals(rowID, store.saveChannel(RADIO_A, info("replacement", 2)))
        val second = assertNotNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        assertNotEquals(first, second)
        store.saveChannel(RADIO_A, info())
        val third = assertNotNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        assertNotEquals(first, third)
        assertNotEquals(second, third)
        store.deleteChannel(EntityKey(RADIO_A, rowID))
        assertNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        store.saveChannel(RADIO_A, info())
        assertNotEquals(third, assertNotNull(store.fetchChannelOccupantID(RADIO_A, 5u)))
    }

    @Test fun dtoAndBatchWritersRotateInTheirTransactionAndPartitionByRadio() = runTest {
        store.saveChannel(RADIO_A, info())
        store.saveChannel(RADIO_B, info())
        val first = assertNotNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        val foreign = assertNotNull(store.fetchChannelOccupantID(RADIO_B, 5u))
        val channel = assertNotNull(store.fetchChannel(RADIO_A, 5u))
        val ordinary = message(contactID = null, channelIndex = 5u).copy(channelOccupantID = first)
        val unresolved = ordinary.copy(id = UUID.randomUUID(), status = MessageStatus.PENDING)
        store.saveMessage(ordinary)
        store.saveMessage(unresolved)
        store.saveChannel(channel.copy(name = "renamed"))
        assertEquals(first, store.fetchChannelOccupantID(RADIO_A, 5u))
        store.saveChannel(channel.copy(secret = info(byte = 2).secret))
        val second = assertNotNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        assertNotEquals(first, second)
        assertEquals(ordinary, store.fetchMessage(EntityKey(RADIO_A, ordinary.id)))
        assertEquals(unresolved, store.fetchMessage(EntityKey(RADIO_A, unresolved.id)))
        store.batchSaveChannels(RADIO_A, listOf(info(byte = 3)).snapshot(), SnapshotList.empty(), null)
        assertNotEquals(second, store.fetchChannelOccupantID(RADIO_A, 5u))
        assertNull(store.fetchMessage(EntityKey(RADIO_A, ordinary.id)))
        assertEquals(unresolved.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(RADIO_A, unresolved.id)))
        store.batchSaveChannels(RADIO_A, SnapshotList.empty(), listOf<UByte>(5u).snapshot(), null)
        assertNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        assertEquals(foreign, store.fetchChannelOccupantID(RADIO_B, 5u))
    }

    @Test fun aLatePendingCommitKeepsOriginalIntentInsteadOfAdoptingTheReplacement() = runTest {
        store.saveChannel(RADIO_A, info())
        val intended = assertNotNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        val captured = message(contactID = null, channelIndex = 5u, text = "original text\n", status = MessageStatus.PENDING)
            .copy(channelOccupantID = intended)
        store.saveChannel(RADIO_A, info("replacement", 2))
        // The create call's captured DTO is saved only after replacement committed.
        store.saveMessage(captured)
        assertEquals(captured, store.fetchMessage(EntityKey(RADIO_A, captured.id)))
        assertNotEquals(intended, store.fetchChannelOccupantID(RADIO_A, 5u))
        val envelope = ChannelMessageEnvelope(captured.id, 5u, false, captured.text, captured.timestamp, null, intended)
        val queued = PendingSendDTO.fromEnvelope(envelope, RADIO_A, enqueuedAt = AT)
        store.insertPendingSendAssigningSequence(queued)
        assertEquals(envelope, store.fetchPendingSends(RADIO_A).single().channelMessageEnvelope())
        store.replacePendingSendForRetry(captured.id, queued.copy(id = UUID.randomUUID(), isResend = true))
        val retry = store.fetchPendingSends(RADIO_A).single()
        assertEquals(intended, retry.channelOccupantID)
        assertEquals(captured.text, retry.messageText)
    }

    @Test fun replacementRetainsUnresolvedTextAndQueueButStillRemovesOrdinaryHistory() = runTest {
        store.saveChannel(RADIO_A, info())
        val occupant = assertNotNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        val unresolved = message(contactID = null, channelIndex = 5u, text = "recover me", status = MessageStatus.PENDING)
            .copy(channelOccupantID = occupant)
        val history = message(contactID = null, channelIndex = 5u, status = MessageStatus.SENT)
        store.saveMessage(unresolved)
        store.saveMessage(history)
        val queued = PendingSendDTO.fromEnvelope(
            ChannelMessageEnvelope(unresolved.id, 5u, false, unresolved.text, unresolved.timestamp, null, occupant),
            RADIO_A, enqueuedAt = AT,
        )
        store.insertPendingSendAssigningSequence(queued)
        store.saveChannel(RADIO_A, info(byte = 2))
        assertEquals(unresolved.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(RADIO_A, unresolved.id)))
        assertNull(store.fetchMessage(EntityKey(RADIO_A, history.id)))
        assertEquals(occupant, store.fetchPendingSends(RADIO_A).single().channelOccupantID)
        assertNotEquals(occupant, store.fetchChannelOccupantID(RADIO_A, 5u))
    }

    @Test fun legacyRowsStayUnprovedAndDuplicateSlotRowsCannotSupplyAuthority() = runTest {
        store.saveChannel(RADIO_A, info())
        val legacy = message(contactID = null, channelIndex = 5u, status = MessageStatus.FAILED)
        store.saveMessage(legacy)
        store.insertPendingSendAssigningSequence(PendingSendDTO.fromEnvelope(
            ChannelMessageEnvelope(legacy.id, 5u, true, legacy.text, legacy.timestamp, null), RADIO_A, enqueuedAt = AT,
        ))
        assertNull(store.fetchMessage(EntityKey(RADIO_A, legacy.id))?.channelOccupantID)
        assertNull(store.fetchPendingSends(RADIO_A).single().channelMessageEnvelope()?.channelOccupantID)
        db.channels().insert(assertNotNull(store.fetchChannel(RADIO_A, 5u)).copy(id = UUID.randomUUID()).toEntity())
        assertNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        assertEquals(legacy.text, store.fetchMessage(EntityKey(RADIO_A, legacy.id))?.text)
    }

    @Test fun actualDatabaseReopenPreservesOriginalEpochAndHydratedEnvelope() = runTest {
        val name = "channel-occupant-${UUID.randomUUID()}.db"
        var disk = MeshCoreDatabase.open(context, name)
        var persisted = RoomPersistenceStore(disk, owner, clock)
        try {
            persisted.saveChannel(RADIO_A, info())
            val occupant = assertNotNull(persisted.fetchChannelOccupantID(RADIO_A, 5u))
            val row = message(contactID = null, channelIndex = 5u, text = "durable original", status = MessageStatus.PENDING)
                .copy(channelOccupantID = occupant)
            persisted.saveMessage(row)
            val envelope = ChannelMessageEnvelope(row.id, 5u, false, row.text, row.timestamp, null, occupant)
            persisted.insertPendingSendAssigningSequence(PendingSendDTO.fromEnvelope(envelope, RADIO_A, enqueuedAt = AT))
            persisted.close()
            disk.close()
            disk = MeshCoreDatabase.open(context, name)
            persisted = RoomPersistenceStore(disk, owner, clock)
            assertEquals(occupant, persisted.fetchChannelOccupantID(RADIO_A, 5u))
            assertEquals(row, persisted.fetchMessage(EntityKey(RADIO_A, row.id)))
            assertEquals(envelope, persisted.fetchPendingSends(RADIO_A).single().channelMessageEnvelope())
            persisted.saveChannel(RADIO_A, info(byte = 2))
            assertNotEquals(occupant, persisted.fetchChannelOccupantID(RADIO_A, 5u))
            assertEquals(occupant, persisted.fetchPendingSends(RADIO_A).single().channelOccupantID)
        } finally {
            persisted.close()
            disk.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun independentRepositoryContextsCannotCommitAReplacementInsideAnActiveIdentityLease() = runTest {
        val gate = ChannelIdentityMutex()
        val writer = RoomPersistenceStore(db, owner, clock, channelIdentityMutation = gate)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dispatch = async(start = CoroutineStart.UNDISPATCHED) {
            gate.withChannelOperation {
                gate.withChannelIdentityMutation {
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        entered.await()
        val replacement = async(start = CoroutineStart.UNDISPATCHED) { writer.saveChannel(RADIO_A, info()) }
        try {
            assertFalse(replacement.isCompleted)
            assertNull(store.fetchChannel(RADIO_A, 5u))
            release.complete(Unit)
            dispatch.await()
            replacement.await()
            assertNotNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        } finally {
            release.complete(Unit)
            writer.close()
        }
    }

    @Test fun restoreWaitsForTheWholeRadioOperationAndReleasesBothLanesBeforeItsCallback() = runTest {
        val gate = ChannelIdentityMutex()
        val writer = RoomPersistenceStore(db, owner, clock, channelIdentityMutation = gate)
        val release = CompletableDeferred<Unit>()
        val radio = async(start = CoroutineStart.UNDISPATCHED) {
            gate.withChannelOperation { release.await() }
        }
        var restoring = false
        var callbackRan = false
        val restore = async(start = CoroutineStart.UNDISPATCHED) {
            writer.withBackupRestore(afterCommit = {
                val callback = async(start = CoroutineStart.UNDISPATCHED) {
                    gate.withChannelIdentityRestore { callbackRan = true }
                }
                assertTrue(callback.isCompleted, "Restore callbacks must not run while either lane is held")
            }) { database, _ ->
                restoring = true
                database.channelOccupants().invalidate(RADIO_A.value, 5)
            }
        }
        try {
            assertFalse(restoring)
            assertFalse(restore.isCompleted)
            release.complete(Unit)
            radio.await()
            restore.await()
            assertTrue(restoring)
            assertTrue(callbackRan)
        } finally {
            release.complete(Unit)
            writer.close()
        }
    }

    @Test fun modelEqualityIncludesTheCapturedIntentAndNeverMakesLegacyEqualToProved() {
        val row = message(contactID = null, channelIndex = 5u)
        val proved = row.copy(channelOccupantID = UUID.fromString("00000000-0000-0000-0000-000000000001"))
        assertNotEquals(row, proved)
        assertNotEquals(row.hashCode(), proved.hashCode())
        assertEquals(proved, proved.copy())
    }

    @Test fun invalidatedAuthorityCannotBeReadOrReusedUntilAReconciledWriteCreatesAFreshEpoch() = runTest {
        store.saveChannel(RADIO_A, info())
        val original = assertNotNull(store.fetchChannelOccupantSnapshot(RADIO_A, 5u))
        store.invalidateChannelOccupant(RADIO_A, 5u)
        assertNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        assertNull(store.fetchChannelOccupantSnapshot(RADIO_A, 5u))
        assertEquals(original.channel, store.fetchChannel(RADIO_A, 5u))
        store.saveChannel(RADIO_A, info())
        assertNotEquals(original.occupantID, assertNotNull(store.fetchChannelOccupantID(RADIO_A, 5u)))
    }

    @Test fun aDirectWriterCannotEnterBetweenRadioAcceptanceAndTheScopedPersistenceCommit() = runTest {
        val gate = ChannelIdentityMutex()
        val writer = RoomPersistenceStore(db, owner, clock, channelIdentityMutation = gate)
        val second = RoomPersistenceStore(db, owner, clock, channelIdentityMutation = gate)
        val accepted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            writer.withChannelOperation { scoped, scope ->
                gate.requireChannelOperationScope(scope)
                accepted.complete(Unit)
                release.await()
                scoped.saveChannel(RADIO_A, info())
                assertEquals(info().secret, assertNotNull(scoped.fetchChannel(RADIO_A, 5u)).secret)
            }
        }
        accepted.await()
        val bypass = async(start = CoroutineStart.UNDISPATCHED) { second.saveChannel(RADIO_A, info(byte = 2)) }
        try {
            assertFalse(bypass.isCompleted, "Direct DB writes must wait for the full radio operation, not just its DB phase")
            assertNull(store.fetchChannel(RADIO_A, 5u))
            release.complete(Unit)
            operation.await()
            bypass.await()
            assertEquals(info(byte = 2).secret, assertNotNull(store.fetchChannel(RADIO_A, 5u)).secret)
        } finally {
            release.complete(Unit)
            writer.close()
            second.close()
        }
    }

    @Test fun scopedWritersRejectChildJobsAndExpiredFacadesWithoutMutatingAuthority() = runTest {
        var leaked: ChannelPersisting? = null
        store.withChannelOperation { scoped, _ ->
            leaked = scoped
            assertFailsWith<IllegalStateException> { scoped.withChannelOperation { _, _ -> Unit } }
            assertFailsWith<IllegalStateException> { store.saveChannel(RADIO_A, info(byte = 3)) }
            supervisorScope {
                val child = async { scoped.saveChannel(RADIO_A, info()) }
                assertFailsWith<IllegalStateException> { child.await() }
            }
            assertNull(store.fetchChannel(RADIO_A, 5u))
            scoped.saveChannel(RADIO_A, info())
        }
        val epoch = assertNotNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        assertFailsWith<IllegalStateException> { assertNotNull(leaked).saveChannel(RADIO_A, info(byte = 2)) }
        assertEquals(epoch, store.fetchChannelOccupantID(RADIO_A, 5u))
        assertEquals(info().secret, assertNotNull(store.fetchChannel(RADIO_A, 5u)).secret)
    }

    @Test fun gateValidationRejectsForgedForeignExpiredAndCrossJobCapabilities() = runTest {
        val gate = ChannelIdentityMutex()
        val other = ChannelIdentityMutex()
        val writer = RoomPersistenceStore(db, owner, clock, channelIdentityMutation = gate)
        var leaked: ChannelIdentityWriteScope? = null
        try {
            writer.withChannelOperation { _, scope ->
                leaked = scope
                gate.requireChannelOperationScope(scope)
                assertFailsWith<IllegalStateException> { other.requireChannelOperationScope(scope) }
                supervisorScope {
                    val child = async { gate.requireChannelOperationScope(scope) }
                    assertFailsWith<IllegalStateException> { child.await() }
                }
                val forged = object : ChannelIdentityWriteScope {
                    override suspend fun <T> withChannelIdentityMutation(block: suspend () -> T): T = block()
                }
                assertFailsWith<IllegalStateException> { gate.requireChannelOperationScope(forged) }
            }
            assertFailsWith<IllegalStateException> { gate.requireChannelOperationScope(assertNotNull(leaked)) }
        } finally {
            writer.close()
        }
    }

    @Test fun exceptionAndCancellationAlwaysExpireTheScopedMutationCapability() = runTest {
        val gate = ChannelIdentityMutex()
        val writer = RoomPersistenceStore(db, owner, clock, channelIdentityMutation = gate)
        try {
            for (failure in listOf(IllegalStateException("controlled"), CancellationException("controlled"))) {
                var escaped: ChannelPersisting? = null
                try {
                    writer.withChannelOperation { scoped, _ ->
                        escaped = scoped
                        throw failure
                    }
                    fail("Controlled operation failure must propagate")
                } catch (actual: Exception) {
                    assertSame(failure, actual)
                }
                assertFailsWith<IllegalStateException> { assertNotNull(escaped).saveChannel(RADIO_A, info()) }
                assertNull(store.fetchChannelOccupantID(RADIO_A, 5u))
            }
            writer.saveChannel(RADIO_A, info())
            assertNotNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        } finally {
            writer.close()
        }
    }

    @Test fun preRadioMutationMarkerSurvivesActualRestartAndDtoMetadataCannotClearIt() = runTest {
        val name = "channel-unresolved-${UUID.randomUUID()}.db"
        var disk = MeshCoreDatabase.open(context, name)
        var persisted = RoomPersistenceStore(disk, owner, clock)
        try {
            persisted.saveChannel(RADIO_A, info())
            val original = assertNotNull(persisted.fetchChannelOccupantSnapshot(RADIO_A, 5u))
            persisted.withChannelOperation { scoped, _ -> scoped.beginChannelMutation(RADIO_A, 5u) }
            assertNull(persisted.fetchChannelOccupantID(RADIO_A, 5u))
            assertTrue(assertNotNull(disk.channelOccupants().stateForIndex(RADIO_A.value, 5)).unresolved)
            persisted.close()
            disk.close()
            disk = MeshCoreDatabase.open(context, name)
            persisted = RoomPersistenceStore(disk, owner, clock)
            assertNull(persisted.fetchChannelOccupantSnapshot(RADIO_A, 5u))
            persisted.saveChannel(original.channel.copy(name = "metadata only"))
            assertNull(persisted.fetchChannelOccupantID(RADIO_A, 5u))
            assertEquals(original.occupantID, disk.channelOccupants().stateForIndex(RADIO_A.value, 5)?.occupantID)
            persisted.saveChannel(RADIO_A, info("reconciled rename"))
            assertEquals(original.occupantID, persisted.fetchChannelOccupantID(RADIO_A, 5u))
            assertFalse(assertNotNull(disk.channelOccupants().stateForIndex(RADIO_A.value, 5)).unresolved)
        } finally {
            persisted.close()
            disk.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun failedReconciliationRollsBackChannelReplacementAndRetainsTheDurableMarker() = runTest {
        store.saveChannel(RADIO_A, info())
        val original = assertNotNull(store.fetchChannelOccupantSnapshot(RADIO_A, 5u))
        store.withChannelOperation { scoped, _ -> scoped.beginChannelMutation(RADIO_A, 5u) }
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_occupant_reconcile BEFORE UPDATE ON channel_occupants " +
                "WHEN NEW.unresolved = 0 BEGIN SELECT RAISE(ABORT, 'controlled occupant commit failure'); END",
        )
        try {
            assertFailsWith<PersistenceStoreException> { store.saveChannel(RADIO_A, info(byte = 2)) }
            assertEquals(original.channel, store.fetchChannel(RADIO_A, 5u))
            assertNull(store.fetchChannelOccupantID(RADIO_A, 5u))
            val authority = assertNotNull(db.channelOccupants().stateForIndex(RADIO_A.value, 5))
            assertTrue(authority.unresolved)
            assertEquals(original.occupantID, authority.occupantID)
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_occupant_reconcile")
        }
        store.saveChannel(RADIO_A, info(byte = 2))
        assertNotEquals(original.occupantID, assertNotNull(store.fetchChannelOccupantID(RADIO_A, 5u)))
    }

    @Test fun markingRequiresAnActiveScopedOperationAndCancellationDoesNotUndoCommittedUncertainty() = runTest {
        assertFailsWith<IllegalStateException> { store.beginChannelMutation(RADIO_A, 5u) }
        store.saveChannel(RADIO_A, info())
        val original = assertNotNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        var escaped: ChannelPersisting? = null
        assertFailsWith<CancellationException> {
            store.withChannelOperation { scoped, _ ->
                escaped = scoped
                scoped.beginChannelMutation(RADIO_A, 5u)
                throw CancellationException("Controlled cancellation after durable marker, before radio/save")
            }
        }
        assertNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        assertEquals(original, db.channelOccupants().stateForIndex(RADIO_A.value, 5)?.occupantID)
        assertFailsWith<IllegalStateException> { assertNotNull(escaped).beginChannelMutation(RADIO_A, 5u) }
        val channel = assertNotNull(store.fetchChannel(RADIO_A, 5u))
        assertFailsWith<IllegalStateException> { assertNotNull(escaped).setChannelFavorite(EntityKey(RADIO_A, channel.id), true) }
        assertEquals(channel, store.fetchChannel(RADIO_A, 5u))
        store.batchSaveChannels(RADIO_A, listOf(info()).snapshot(), SnapshotList.empty(), null)
        assertEquals(original, store.fetchChannelOccupantID(RADIO_A, 5u))
    }

    @Test fun scopedOrphanSlotCleanupRetainsUnresolvedIntentButExplicitHistoryEraseStillDeletesIt() = runTest {
        val intended = UUID.randomUUID()
        val unresolved = message(contactID = null, channelIndex = 5u, text = "orphan pending text\n", status = MessageStatus.PENDING)
            .copy(channelOccupantID = intended)
        val history = message(contactID = null, channelIndex = 5u, status = MessageStatus.SENT)
        store.saveMessage(unresolved)
        store.saveMessage(history)
        val queued = PendingSendDTO.fromEnvelope(
            ChannelMessageEnvelope(unresolved.id, 5u, false, unresolved.text, unresolved.timestamp, null, intended),
            RADIO_A, enqueuedAt = AT,
        )
        store.insertPendingSendAssigningSequence(queued)
        assertNull(store.fetchChannel(RADIO_A, 5u))
        store.withChannelOperation { scoped, _ ->
            scoped.deleteMessagesForChannel(RADIO_A, 5u)
            scoped.invalidateChannelOccupant(RADIO_A, 5u)
        }
        assertEquals(unresolved.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(RADIO_A, unresolved.id)))
        assertNull(store.fetchMessage(EntityKey(RADIO_A, history.id)))
        val retained = store.fetchPendingSends(RADIO_A).single()
        assertEquals(queued.id, retained.id)
        assertEquals(intended, retained.channelOccupantID)
        assertEquals(unresolved.text, retained.messageText)
        assertNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        store.deleteMessagesForChannel(RADIO_A, 5u)
        assertNull(store.fetchMessage(EntityKey(RADIO_A, unresolved.id)))
        assertTrue(store.fetchPendingSends(RADIO_A).isEmpty())
    }
}
