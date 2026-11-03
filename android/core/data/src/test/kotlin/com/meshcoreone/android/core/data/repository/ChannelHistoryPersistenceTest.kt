// AndroidOnly: WP-202 Non-activated occupant-bound SQL history, late-save, restart and explicit unbound-history boundaries.
package com.meshcoreone.android.core.data.repository

import androidx.room.Room
import androidx.room.RoomDatabase
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChannelHistoryPersistenceTest : RepositoryTest() {
    private fun info(byte: Int = 1, index: UByte = 5u) =
        ChannelInfo(index, "channel", Bytes(ByteArray(16) { byte.toByte() }))

    private suspend fun origin(persisted: RoomPersistenceStore = store, radio: RadioId = RADIO_A, index: UByte = 5u) =
        ChannelHistoryScope.from(assertNotNull(persisted.fetchChannelOccupantSnapshot(radio, index)))

    private fun bound(scope: ChannelHistoryScope, order: Long, status: MessageStatus = MessageStatus.SENT) =
        message(
            radio = scope.radioId, contactID = null, channelIndex = scope.channelIndex,
            text = "original $order", timestamp = (1_700_000_000L + order).toUInt(),
            createdAt = AT.plusSeconds(order), status = status,
        ).copy(channelOccupantID = scope.expectedOccupantID)

    private suspend fun rejected(reason: ChannelHistoryConflict, operation: suspend () -> Any?): ChannelHistoryRejected {
        val failure = assertFailsWith<PersistenceStoreException> { operation() }
        assertEquals(PersistenceStoreError.InvalidData, failure.error)
        val cause = assertIs<ChannelHistoryRejected>(failure.cause)
        assertEquals(reason, cause.reason)
        assertEquals(reason.name, cause.message)
        return cause
    }

    @Test fun heldOldSaveCommitsAfterReplacementThenReopenKeepsRecoveryButExcludesReplacementHistory() = runTest {
        val name = "channel-history-${UUID.randomUUID()}.db"
        var disk = MeshCoreDatabase.open(context, name)
        var persisted = RoomPersistenceStore(disk, owner, clock)
        try {
            persisted.saveChannel(RADIO_A, info())
            val original = origin(persisted)
            val alreadyCommitted = bound(original, 1, MessageStatus.PENDING)
            persisted.saveChannelMessage(alreadyCommitted, original)
            val late = bound(original, 100)
            val reached = CompletableDeferred<Unit>()
            val allowSave = CompletableDeferred<Unit>()
            val saving = async(start = CoroutineStart.UNDISPATCHED) {
                reached.complete(Unit)
                allowSave.await()
                persisted.saveChannelMessage(late, original)
            }
            reached.await()
            persisted.saveChannel(RADIO_A, info(2))
            val replacement = origin(persisted)
            assertNotEquals(original.expectedOccupantID, replacement.expectedOccupantID)
            allowSave.complete(Unit)
            saving.await()
            val current = bound(replacement, 10)
            persisted.saveChannelMessage(current, replacement)
            persisted.close()
            disk.close()
            disk = MeshCoreDatabase.open(context, name)
            persisted = RoomPersistenceStore(disk, owner, clock)

            assertEquals(listOf(current), persisted.fetchChannelHistory(replacement).toList())
            assertEquals(listOf(current), persisted.fetchChannelHistoryWindow(replacement, late.sortDate, 1).messages.toList())
            assertEquals(1L, persisted.countChannelHistory(replacement))
            assertEquals(listOf(current), persisted.fetchLastChannelHistory(listOf(replacement).snapshot(), 1)[replacement]?.toList())
            assertEquals(late, persisted.fetchMessage(EntityKey(RADIO_A, late.id)))
            assertEquals(alreadyCommitted.copy(status = MessageStatus.FAILED),
                persisted.fetchMessage(EntityKey(RADIO_A, alreadyCommitted.id)))
            rejected(ChannelHistoryConflict.STALE_OCCUPANT) {
                persisted.validateChannelMessageOrigin(EntityKey(RADIO_A, late.id))
            }
            rejected(ChannelHistoryConflict.STALE_OCCUPANT) { persisted.fetchChannelHistory(original) }
            assertEquals(replacement, persisted.validateChannelMessageOrigin(EntityKey(RADIO_A, current.id)))
        } finally {
            persisted.close()
            disk.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun originPredicatesPrecedePageOffsetAnchorCountSummaryAndReactionCandidateLimit() = runTest {
        store.saveChannel(RADIO_A, info())
        val previous = origin()
        store.saveChannel(RADIO_A, info(2))
        val current = origin()
        val rows = listOf(10L, 20L, 30L).map { bound(current, it, MessageStatus.FAILED) }
        for (row in rows) store.saveChannelMessage(row, current)
        val stale = listOf(5L, 15L, 25L, 35L, 40L).map { bound(previous, it, MessageStatus.FAILED) }
        for (row in stale) store.saveChannelMessage(row, previous)
        assertEquals(listOf(rows[2]), store.fetchChannelHistory(current, 1, 0).toList())
        assertEquals(listOf(rows[1]), store.fetchChannelHistory(current, 1, 1).toList())
        assertEquals(listOf(rows[0]), store.fetchChannelHistory(current, 1, 2).toList())
        assertTrue(store.fetchChannelHistory(current, 1, 3).isEmpty())
        assertEquals(3L, store.countChannelHistory(current))
        assertEquals(3L, store.countUnseenChannelHistoryFailures(current))
        val window = store.fetchChannelHistoryWindow(current, rows[1].sortDate, 1)
        assertEquals(rows.drop(1), window.messages.toList())
        assertTrue(window.hasMore)
        val whole = store.fetchChannelHistoryWindow(current, rows[0].sortDate, 1)
        assertEquals(rows, whole.messages.toList())
        assertFalse(whole.hasMore)
        assertEquals(listOf(rows[2]), store.fetchLastChannelHistory(listOf(current).snapshot(), 1)[current]?.toList())
        assertEquals(listOf(rows[2]), store.fetchChannelHistoryCandidates(current, 0u..UInt.MAX_VALUE, 1).toList())
        val reaction = ParsedReaction("reaction", "local", RepositoryReactionPolicy.messageHash(rows[2].text, rows[2].reactionTimestamp))
        assertEquals(rows[2], store.findChannelHistoryMessageForReaction(current, reaction, "local", 0u..UInt.MAX_VALUE, 1))
        assertNull(store.findChannelHistoryMessageForReaction(current, reaction, null, 0u..UInt.MAX_VALUE, 1))
        for (row in stale) assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
    }

    @Test fun unchangedOccupantMetadataAndForeignRadioOrSlotDoNotChangeTheKnownHistory() = runTest {
        store.saveChannel(RADIO_A, info())
        store.saveChannel(RADIO_A, info(index = 6u))
        store.saveChannel(RADIO_B, info())
        val current = origin()
        val same = bound(current, 1)
        store.saveChannelMessage(same, current)
        val incoming = bound(current, 2).copy(direction = MessageDirection.INCOMING, senderNodeName = "peer")
        store.saveChannelMessage(incoming, current)
        val foreign = origin(radio = RADIO_B)
        store.saveChannelMessage(bound(foreign, 100), foreign)
        val otherSlot = origin(index = 6u)
        store.saveChannelMessage(bound(otherSlot, 200), otherSlot)
        store.saveMessage(message(radio = RADIO_B, contactID = null, channelIndex = 5u))
        store.saveMessage(message(contactID = null, channelIndex = 6u))
        val channel = assertNotNull(store.fetchChannel(RADIO_A, 5u))
        store.saveChannel(channel.copy(name = "same occupant metadata"))
        assertEquals(current, origin())
        assertEquals(listOf(same, incoming), store.fetchChannelHistory(current).toList())
        assertEquals(2L, store.countChannelHistory(current))
        assertEquals(current, store.validateChannelMessageOrigin(EntityKey(RADIO_A, same.id)))
        assertTrue(ChannelHistoryPersisting::class.java.isInstance(store))
        assertFalse(MessagePersisting::class.java.isAssignableFrom(ChannelHistoryPersisting::class.java))
        assertFalse(ChannelHistoryPersisting::class.java.isAssignableFrom(PersistenceStoreProtocol::class.java))
    }

    @Test fun unboundRowsOfEitherDirectionRejectEveryScopedReadWithoutPartialHistoryOrPromotion() = runTest {
        val name = "unbound-history-${UUID.randomUUID()}.db"
        var disk = MeshCoreDatabase.open(context, name)
        var persisted = RoomPersistenceStore(disk, owner, clock)
        try {
            persisted.saveChannel(RADIO_A, info())
            val current = origin(persisted)
            val known = bound(current, 50)
            persisted.saveChannelMessage(known, current)
            val unbound = MessageDirection.entries.mapIndexed { i, direction ->
                message(
                    contactID = null, channelIndex = 5u, text = "unbound $i",
                    createdAt = AT.minusSeconds(100L + i), direction = direction,
                )
            }
            for (row in unbound) persisted.saveMessage(row)
            persisted.close()
            disk.close()
            disk = MeshCoreDatabase.open(context, name)
            persisted = RoomPersistenceStore(disk, owner, clock)
            rejected(ChannelHistoryConflict.UNBOUND_HISTORY) { persisted.fetchChannelHistory(current, 1, 0) }
            rejected(ChannelHistoryConflict.UNBOUND_HISTORY) { persisted.fetchChannelHistory(current, 0, Long.MAX_VALUE) }
            rejected(ChannelHistoryConflict.UNBOUND_HISTORY) { persisted.fetchChannelHistoryWindow(current, known.sortDate, 1) }
            rejected(ChannelHistoryConflict.UNBOUND_HISTORY) { persisted.countChannelHistory(current) }
            rejected(ChannelHistoryConflict.UNBOUND_HISTORY) { persisted.fetchLastChannelHistory(listOf(current).snapshot(), 1) }
            rejected(ChannelHistoryConflict.UNBOUND_HISTORY) { persisted.countUnseenChannelHistoryFailures(current) }
            rejected(ChannelHistoryConflict.UNBOUND_HISTORY) { persisted.fetchChannelHistoryCandidates(current, 0u..1u, 1) }
            rejected(ChannelHistoryConflict.UNBOUND_HISTORY) {
                persisted.findChannelHistoryMessageForReaction(current, ParsedReaction("", "", ""), null, 0u..1u, 1)
            }
            for (row in unbound) {
                assertEquals(row, persisted.fetchMessage(EntityKey(RADIO_A, row.id)))
                val failure = rejected(ChannelHistoryConflict.UNKNOWN_MESSAGE_ORIGIN) {
                    persisted.validateChannelMessageOrigin(EntityKey(RADIO_A, row.id))
                }
                assertEquals(EntityKey(RADIO_A, row.id), failure.messageKey)
                rejected(ChannelHistoryConflict.UNKNOWN_MESSAGE_ORIGIN) {
                    persisted.saveChannelMessage(row.copy(channelOccupantID = current.expectedOccupantID), current)
                }
                assertEquals(row, persisted.fetchMessage(EntityKey(RADIO_A, row.id)))
            }
            assertEquals(current, persisted.validateChannelMessageOrigin(EntityKey(RADIO_A, known.id)))
            assertEquals(unbound.size + 1, persisted.fetchMessages(RADIO_A, 5u).size)
        } finally {
            persisted.close()
            disk.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun scopedSaveRejectsAbsentChangedOrWrongDestinationProofAndRollsBackActualSqlFailure() = runTest {
        store.saveChannel(RADIO_A, info())
        val current = origin()
        val row = bound(current, 1)
        store.saveChannelMessage(row, current)
        rejected(ChannelHistoryConflict.UNKNOWN_MESSAGE_ORIGIN) {
            store.saveChannelMessage(row.copy(channelOccupantID = null), current)
        }
        val wrongProof = current.copy(expectedOccupantID = UUID.randomUUID())
        rejected(ChannelHistoryConflict.ORIGIN_MISMATCH) {
            store.saveChannelMessage(row.copy(text = "must not overwrite", channelOccupantID = wrongProof.expectedOccupantID), wrongProof)
        }
        rejected(ChannelHistoryConflict.ORIGIN_MISMATCH) { store.saveChannelMessage(row, wrongProof) }
        rejected(ChannelHistoryConflict.MESSAGE_DESTINATION_MISMATCH) {
            store.saveChannelMessage(row.copy(channelIndex = 6u), current)
        }
        rejected(ChannelHistoryConflict.MESSAGE_DESTINATION_MISMATCH) {
            store.saveChannelMessage(row.copy(radioId = RADIO_B), current)
        }
        assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
        val staged = bound(current, 2)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_history BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT, 'controlled history failure'); END",
        )
        try {
            assertFailsWith<PersistenceStoreException> { store.saveChannelMessage(staged, current) }
            assertNull(store.fetchMessage(EntityKey(RADIO_A, staged.id)))
            assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
            assertTrue(issues.isNotEmpty())
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_history")
        }
        store.saveChannelMessage(staged, current)
        assertEquals(staged, store.fetchMessage(EntityKey(RADIO_A, staged.id)))
    }

    @Test fun unresolvedDisabledMissingAndAbaAuthorityNeverValidateAnOldReadOrRetryOrigin() = runTest {
        store.saveChannel(RADIO_A, info())
        val original = origin()
        val row = bound(original, 1, MessageStatus.PENDING)
        store.saveChannelMessage(row, original)
        store.withChannelOperation { scoped, _ -> scoped.beginChannelMutation(RADIO_A, 5u) }
        rejected(ChannelHistoryConflict.UNKNOWN_AUTHORITY) { store.fetchChannelHistory(original) }
        rejected(ChannelHistoryConflict.UNKNOWN_AUTHORITY) { store.validateChannelMessageOrigin(EntityKey(RADIO_A, row.id)) }
        store.saveChannel(RADIO_A, info())
        assertEquals(original, origin())
        assertEquals(listOf(row), store.fetchChannelHistory(original).toList())
        val channel = assertNotNull(store.fetchChannel(RADIO_A, 5u))
        store.saveChannel(channel.copy(isEnabled = false))
        rejected(ChannelHistoryConflict.UNKNOWN_AUTHORITY) { store.countChannelHistory(original) }
        store.saveChannel(channel.copy(isEnabled = true))
        rejected(ChannelHistoryConflict.UNKNOWN_AUTHORITY) { store.countChannelHistory(original) }
        store.saveChannel(RADIO_A, info(2))
        store.saveChannel(RADIO_A, info())
        assertNotEquals(original, origin())
        rejected(ChannelHistoryConflict.STALE_OCCUPANT) { store.validateChannelMessageOrigin(EntityKey(RADIO_A, row.id)) }
        store.deleteChannel(EntityKey(RADIO_A, channel.id))
        rejected(ChannelHistoryConflict.UNKNOWN_AUTHORITY) { store.fetchChannelHistory(original) }
        assertEquals(row.text, store.fetchMessage(EntityKey(RADIO_A, row.id))?.text)
        rejected(ChannelHistoryConflict.MESSAGE_NOT_FOUND) {
            store.validateChannelMessageOrigin(EntityKey(RADIO_B, row.id))
        }
    }

    @Test fun actualSqlAuthorityAndHistoryStatementsExecuteInsideTheSameRoomReadBoundary() = runTest {
        val reads = mutableListOf<Pair<String, Boolean>>()
        lateinit var database: MeshCoreDatabase
        val immediate = Executor { it.run() }
        database = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate)
            .setQueryCallback(RoomDatabase.QueryCallback { sql, _ ->
                if (sql.startsWith("SELECT") && (sql.contains("channels") || sql.contains("channel_occupants") || sql.contains("messages"))) {
                    reads += sql to database.inTransaction()
                }
            }, immediate).build()
        val persisted = RoomPersistenceStore(database, owner, clock)
        try {
            persisted.saveChannel(RADIO_A, info())
            val current = origin(persisted)
            val row = bound(current, 1)
            persisted.saveChannelMessage(row, current)
            reads.clear()
            assertEquals(listOf(row), persisted.fetchChannelHistory(current, 1, 0).toList())
            assertTrue(reads.any { it.first.contains("FROM channels") })
            assertTrue(reads.any { it.first.contains("FROM channel_occupants") })
            assertTrue(reads.any { it.first.contains("channelOccupantID =") && it.first.contains("LIMIT") })
            assertTrue(reads.isNotEmpty())
            assertTrue(reads.all { it.second }, reads.toString())
        } finally {
            persisted.close()
            database.close()
        }
    }
}
