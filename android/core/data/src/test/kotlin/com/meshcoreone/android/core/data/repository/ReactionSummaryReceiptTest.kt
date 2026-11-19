// AndroidOnly: WP-214 Real SQLite summary/receipt commit, rollback, identity and reopen boundaries.
package com.meshcoreone.android.core.data.repository

import androidx.room.Room
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.ReactionSummaryMutation
import com.meshcoreone.android.core.database.DirectAckStorage
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ReactionSummaryReceiptTest : RepositoryTest() {
    private suspend fun seed(): ReactionSummaryMutation {
        store.saveChannel(RADIO_A, ChannelInfo(3u, "General", Bytes(ByteArray(16))))
        val target = message(contactID = null, channelIndex = 3u)
        store.saveMessage(target)
        val incarnation = assertNotNull(store.fetchChannelReactionMatch(entity(id = target.id))?.channelReactionIncarnation)
        return ReactionSummaryMutation(entity(id = UUID.randomUUID()), target.id, incarnation, "x:1")
    }

    @Test fun receiptInsertFailureRollsBackSummaryAndSurfacesTypedStorageFailure() = runTest {
        val request = seed()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_summary_receipt BEFORE INSERT ON reaction_summary_receipts " +
                "BEGIN SELECT RAISE(ABORT, 'receipt storage failed'); END",
        )
        try {
            val error = assertFailsWith<PersistenceStoreException> { store.updateReactionSummary(request) }
            assertIs<PersistenceStoreError.SaveFailed>(error.error)
            assertSame(error, issues.single())
            assertNull(store.fetchMessage(entity(id = request.messageID))?.reactionSummary)
            assertNull(store.fetchCommittedReactionSummary(request.operation))
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_summary_receipt")
        }
    }

    @Test fun exactOperationIsIdempotentAndCannotRewindALaterSummaryOrChangeItsIdentity() = runTest {
        val request = seed()
        store.updateReactionSummary(request)
        store.updateMessageReactionSummary(entity(id = request.messageID), "x:2", request.targetIncarnation)
        store.updateReactionSummary(request)
        val proof = assertNotNull(store.fetchCommittedReactionSummary(request.operation))
        assertEquals(request, proof.mutation)
        assertEquals("x:2", proof.target.reactionSummary)
        val error = assertFailsWith<PersistenceStoreException> {
            store.updateReactionSummary(request.copy(summary = "other:1"))
        }
        assertEquals(PersistenceStoreError.InvalidData, error.error)
        assertEquals("x:2", store.fetchMessage(entity(id = request.messageID))?.reactionSummary)
        assertNull(store.fetchCommittedReactionSummary(EntityKey(RADIO_B, request.operation.id)))
    }

    @Test fun originalReceiptCannotProveOrMutateAReplacementIncarnation() = runTest {
        val request = seed()
        store.updateReactionSummary(request)
        val key = entity(id = request.messageID)
        val original = assertNotNull(store.fetchMessage(key))
        store.saveMessage(original.copy(reactionSummary = null))
        assertNull(store.fetchCommittedReactionSummary(request.operation))
        val error = assertFailsWith<PersistenceStoreException> { store.updateReactionSummary(request) }
        assertEquals(PersistenceStoreError.ReactionTargetChanged, error.error)
        assertNull(store.fetchMessage(key)?.reactionSummary)
    }

    @Test fun deletingTheTargetAlsoDeletesItsReceipt() = runTest {
        val request = seed()
        store.updateReactionSummary(request)
        store.deleteMessage(entity(id = request.messageID))
        assertNull(db.reactionSummaryReceipts().byOperation(RADIO_A.value, request.operation.id))
    }

    @Test fun receiptAndSummarySurviveClosingAndReopeningTheRealFile() = runTest {
        val file = databaseDirectory.newFolder().resolve("summary.db")
        fun open() = Room.databaseBuilder(context, MeshCoreDatabase::class.java, file.absolutePath)
            .addCallback(DirectAckStorage.callback).allowMainThreadQueries()
            .setQueryExecutor(Executor { it.run() }).setTransactionExecutor(Executor { it.run() }).build()
        val first = open()
        val firstStore = RoomPersistenceStore(first, owner, clock)
        val target = MessageDTO(radioId = RADIO_A, channelIndex = 3u, text = "original", timestamp = 42u)
        val request: ReactionSummaryMutation
        try {
            firstStore.saveChannel(RADIO_A, ChannelInfo(3u, "General", Bytes(ByteArray(16))))
            firstStore.saveMessage(target)
            request = ReactionSummaryMutation(
                entity(id = UUID.randomUUID()), target.id,
                assertNotNull(firstStore.fetchChannelReactionMatch(entity(id = target.id))?.channelReactionIncarnation),
                "x:1",
            )
            firstStore.updateReactionSummary(request)
        } finally {
            try { firstStore.close() } finally { first.close() }
        }
        val second = open()
        val secondStore = RoomPersistenceStore(second, owner, clock)
        try {
            val proof = assertNotNull(secondStore.fetchCommittedReactionSummary(request.operation))
            assertEquals(request, proof.mutation)
            assertEquals("x:1", proof.target.reactionSummary)
        } finally {
            try { secondStore.close() } finally { second.close() }
        }
    }
}
