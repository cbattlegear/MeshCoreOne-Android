// AndroidOnly: WP-202 Real-Room tests for occupant-conditional metadata updates and restore serialization.
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.contracts.domain.ChannelHistoryScope
import com.meshcoreone.android.core.contracts.domain.ChannelIdentityMutex
import com.meshcoreone.android.core.contracts.domain.ChannelOccupantMetadataMutationOutcome
import com.meshcoreone.android.core.contracts.domain.ChannelOccupantSnapshot
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.junit.Test

class ChannelOccupantMetadataPersistenceTest : BackupRoomTest() {
    private fun info(byte: Int = 1) = ChannelInfo(5u, "Channel", secret(byte))
    private fun secret(byte: Int) = Bytes(ByteArray(16) { byte.toByte() })
    private fun scope(snapshot: ChannelOccupantSnapshot) = ChannelHistoryScope.from(snapshot)

    @Test fun currentOccupantMetadataAndUnreadIncrementCommitTogether() = runBlocking {
        val rowID = store.saveChannel(RADIO, info())
        val original = scope(assertNotNull(store.fetchChannelOccupantSnapshot(RADIO, 5u)))
        val lastMessage = AT.plusSeconds(20)

        assertEquals(
            ChannelOccupantMetadataMutationOutcome.APPLIED,
            store.applyChannelMessageMetadataIfCurrentOccupant(original, lastMessage, incrementUnreadCount = true),
        )
        val updated = assertNotNull(store.fetchChannel(RADIO, 5u))
        assertEquals(rowID, updated.id)
        assertEquals(lastMessage, updated.lastMessageDate)
        assertEquals(1L, updated.unreadCount)
    }

    @Test fun heldOldOccupantCannotMutateReplacementWithTheSameChannelRowID() = runBlocking {
        val rowID = store.saveChannel(RADIO, info(1))
        val oldScope = scope(assertNotNull(store.fetchChannelOccupantSnapshot(RADIO, 5u)))
        store.saveChannel(RADIO, info(2))

        val replacement = assertNotNull(store.fetchChannel(RADIO, 5u))
        assertEquals(rowID, replacement.id)
        assertNotEquals(oldScope.expectedOccupantID, store.fetchChannelOccupantSnapshot(RADIO, 5u)?.occupantID)
        assertEquals(
            ChannelOccupantMetadataMutationOutcome.STALE_OR_UNPROVED,
            store.applyChannelMessageMetadataIfCurrentOccupant(oldScope, AT.plusSeconds(21), incrementUnreadCount = true),
        )
        val unchanged = assertNotNull(store.fetchChannel(RADIO, 5u))
        assertNull(unchanged.lastMessageDate)
        assertEquals(0L, unchanged.unreadCount)
    }

    @Test fun sameSecretAbaCreatesANewIncarnationAndRejectsTheOriginalScope() = runBlocking {
        val rowID = store.saveChannel(RADIO, info(1))
        val original = scope(assertNotNull(store.fetchChannelOccupantSnapshot(RADIO, 5u)))
        store.saveChannel(RADIO, info(2))
        store.saveChannel(RADIO, info(1))

        val returned = assertNotNull(store.fetchChannelOccupantSnapshot(RADIO, 5u))
        assertEquals(rowID, returned.channel.id)
        assertNotEquals(original.expectedOccupantID, returned.occupantID)
        assertEquals(
            ChannelOccupantMetadataMutationOutcome.STALE_OR_UNPROVED,
            store.applyChannelMessageMetadataIfCurrentOccupant(original, AT.plusSeconds(22), incrementUnreadCount = true),
        )
        assertNull(returned.channel.lastMessageDate)
        assertEquals(0L, returned.channel.unreadCount)
    }

    @Test fun unresolvedOccupantCannotAuthorizeMetadataMutation() = runBlocking {
        val dto = ChannelDTO(radioId = RADIO, index = 5u, name = "Unresolved", secret = secret(1))
        store.saveChannel(dto)
        val unresolved = assertNotNull(db.channelOccupants().stateForIndex(RADIO.value, 5))
        assertTrue(unresolved.unresolved)

        assertEquals(
            ChannelOccupantMetadataMutationOutcome.STALE_OR_UNPROVED,
            store.applyChannelMessageMetadataIfCurrentOccupant(
                ChannelHistoryScope(RADIO, 5u, unresolved.occupantID), AT.plusSeconds(23), incrementUnreadCount = true,
            ),
        )
        val unchanged = assertNotNull(store.fetchChannel(RADIO, 5u))
        assertNull(unchanged.lastMessageDate)
        assertEquals(0L, unchanged.unreadCount)
        assertNull(store.fetchChannelOccupantSnapshot(RADIO, 5u))
    }

    @Test fun independentRoomHandlesSerializeReplacementBeforeTheOldMetadataMutation() = runBlocking {
        val name = "channel-metadata-${UUID.randomUUID()}.db"
        val firstDatabase = MeshCoreDatabase.open(context, name)
        val secondDatabase = MeshCoreDatabase.open(context, name)
        val firstStore = RoomPersistenceStore(firstDatabase, owner, clock)
        val secondStore = RoomPersistenceStore(secondDatabase, owner, clock)
        val releaseGate = CompletableDeferred<Unit>()
        try {
            val rowID = firstStore.saveChannel(RADIO, info(1))
            val oldScope = scope(assertNotNull(secondStore.fetchChannelOccupantSnapshot(RADIO, 5u)))
            val gateEntered = CompletableDeferred<Unit>()
            val gate = async(start = CoroutineStart.UNDISPATCHED) {
                ChannelIdentityMutex.PROCESS.withChannelIdentityRestore {
                    gateEntered.complete(Unit)
                    releaseGate.await()
                }
            }
            gateEntered.await()
            val replacement = async(start = CoroutineStart.UNDISPATCHED) { firstStore.saveChannel(RADIO, info(2)) }
            val staleMutation = async(start = CoroutineStart.UNDISPATCHED) {
                secondStore.applyChannelMessageMetadataIfCurrentOccupant(
                    oldScope, AT.plusSeconds(24), incrementUnreadCount = true,
                )
            }
            assertFalse(replacement.isCompleted)
            assertFalse(staleMutation.isCompleted)
            releaseGate.complete(Unit)
            gate.await()
            assertEquals(rowID, replacement.await())
            assertEquals(ChannelOccupantMetadataMutationOutcome.STALE_OR_UNPROVED, staleMutation.await())

            val current = assertNotNull(secondStore.fetchChannel(RADIO, 5u))
            assertEquals(rowID, current.id)
            assertNull(current.lastMessageDate)
            assertEquals(0L, current.unreadCount)
        } finally {
            releaseGate.complete(Unit)
            firstStore.close()
            secondStore.close()
            firstDatabase.close()
            secondDatabase.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun backupRestoreReplacementBlocksOldScopeAndNeverMutatesInsertedChannel() = runBlocking {
        val name = "channel-metadata-restore-${UUID.randomUUID()}.db"
        val restoreDatabase = MeshCoreDatabase.open(context, name)
        val mutationDatabase = MeshCoreDatabase.open(context, name)
        val restoreStore = RoomPersistenceStore(restoreDatabase, owner, clock)
        val mutationStore = RoomPersistenceStore(mutationDatabase, owner, clock)
        val channelWriteEntered = CompletableDeferred<Unit>()
        val continueRestore = CompletableDeferred<Unit>()
        try {
            val oldID = restoreStore.saveChannel(RADIO, info(1))
            val oldScope = scope(assertNotNull(mutationStore.fetchChannelOccupantSnapshot(RADIO, 5u)))
            restoreStore.deleteChannel(EntityKey(RADIO, oldID))

            val backupDate = AT.plusSeconds(25)
            val backupChannel = channel(
                radio = RADIO, index = 5u, secret = secret(2), id = id(25),
            ).copy(lastMessageDate = backupDate, unreadCount = 7)
            val restoreService = newService(BackupImportHooks(beforeWrite = { kind ->
                if (kind == BackupModelKind.CHANNELS) {
                    channelWriteEntered.complete(Unit)
                    continueRestore.await()
                }
            }))
            val importing = async(start = CoroutineStart.UNDISPATCHED) {
                restoreService.importBackup(envelope(channels = listOf(backupChannel)), restoreStore)
            }
            channelWriteEntered.await()
            val staleMutation = async(start = CoroutineStart.UNDISPATCHED) {
                mutationStore.applyChannelMessageMetadataIfCurrentOccupant(
                    oldScope, AT.plusSeconds(26), incrementUnreadCount = true,
                )
            }
            assertFalse(staleMutation.isCompleted)
            continueRestore.complete(Unit)
            importing.await()
            assertEquals(ChannelOccupantMetadataMutationOutcome.STALE_OR_UNPROVED, staleMutation.await())

            val restored = assertNotNull(mutationStore.fetchChannel(RADIO, 5u))
            assertEquals(backupDate, restored.lastMessageDate)
            assertEquals(7L, restored.unreadCount)
            assertNull(mutationStore.fetchChannelOccupantSnapshot(RADIO, 5u))
        } finally {
            continueRestore.complete(Unit)
            restoreStore.close()
            mutationStore.close()
            restoreDatabase.close()
            mutationDatabase.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun databaseFailuresRemainTypedInsteadOfReturningAStaleOutcome() = runBlocking {
        store.saveChannel(RADIO, info())
        val original = scope(assertNotNull(store.fetchChannelOccupantSnapshot(RADIO, 5u)))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_channel_metadata_insert BEFORE INSERT ON channels " +
                "WHEN EXISTS(SELECT 1 FROM channels WHERE radioId = NEW.radioId AND id = NEW.id) " +
                "BEGIN SELECT RAISE(ABORT, 'controlled channel metadata failure'); END",
        )
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_channel_metadata_update BEFORE UPDATE OF unreadCount, lastMessageDate ON channels " +
                "BEGIN SELECT RAISE(ABORT, 'controlled channel metadata failure'); END",
        )

        assertFailsWith<PersistenceStoreException> {
            store.applyChannelMessageMetadataIfCurrentOccupant(
                original, AT.plusSeconds(27), incrementUnreadCount = true,
            )
        }
        val unchanged = assertNotNull(store.fetchChannel(RADIO, 5u))
        assertNull(unchanged.lastMessageDate)
        assertEquals(0L, unchanged.unreadCount)
    }

    @Test fun ignoredInsertDoesNotSuppressTheActualMetadataUpdate() = runBlocking {
        val channelID = store.saveChannel(RADIO, info())
        val original = scope(assertNotNull(store.fetchChannelOccupantSnapshot(RADIO, 5u)))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER ignore_channel_metadata_insert BEFORE INSERT ON channels " +
                "WHEN EXISTS(SELECT 1 FROM channels WHERE radioId = NEW.radioId AND id = NEW.id) " +
                "BEGIN SELECT RAISE(IGNORE); END",
        )

        assertEquals(
            ChannelOccupantMetadataMutationOutcome.APPLIED,
            store.applyChannelMessageMetadataIfCurrentOccupant(original, AT.plusSeconds(28), incrementUnreadCount = true),
        )
        val updated = assertNotNull(store.fetchChannel(RADIO, 5u))
        assertEquals(channelID, updated.id)
        assertEquals(AT.plusSeconds(28), updated.lastMessageDate)
        assertEquals(1L, updated.unreadCount)
    }

    @Test fun ignoredUpdateReturnsTypedSaveFailureWithoutSuccessShapedOutcome() = runBlocking {
        store.saveChannel(RADIO, info())
        val original = scope(assertNotNull(store.fetchChannelOccupantSnapshot(RADIO, 5u)))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER ignore_channel_metadata_update BEFORE UPDATE OF unreadCount, lastMessageDate_seconds, " +
                "lastMessageDate_nanos ON channels BEGIN SELECT RAISE(IGNORE); END",
        )

        val failure = assertFailsWith<PersistenceStoreException> {
            store.applyChannelMessageMetadataIfCurrentOccupant(
                original, AT.plusSeconds(29), incrementUnreadCount = true,
            )
        }
        assertEquals(PersistenceStoreError.SaveFailed("Channel message metadata update was not applied"), failure.error)
        val unchanged = assertNotNull(store.fetchChannel(RADIO, 5u))
        assertNull(unchanged.lastMessageDate)
        assertEquals(0L, unchanged.unreadCount)
    }
}
