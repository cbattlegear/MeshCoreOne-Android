// AndroidOnly: WP-215 Real Room notification admission, rollback and acknowledgement generation controls.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.ReactionDao
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.ReactionDTO
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReactionNotificationPersistenceTest : RepositoryTest() {
    private suspend fun reaction(): ReactionDTO {
        val target = message()
        store.saveMessage(target)
        return reaction(target)
    }

    private fun reaction(target: MessageDTO) = ReactionDTO(
        messageID = target.id, emoji = "\uD83D\uDC4D", senderName = "Peer", messageHash = "12345678",
        rawText = "original", receivedAt = AT, contactID = target.contactID, radioId = target.radioId,
    )

    @Test fun onlyExplicitNewIncomingCommitsAdmitAndAnAcknowledgedOccurrenceNeverRearms() = runTest {
        val row = reaction()
        store.saveReaction(row)
        assertTrue(store.reactionNotifications.pendingReactionNotifications(row.radioId).isEmpty())
        store.saveReaction(row.copy(retainNotification = true))
        assertTrue(store.reactionNotifications.pendingReactionNotifications(row.radioId).isEmpty())
        val incoming = row.copy(id = UUID.randomUUID())
        store.saveReaction(incoming.copy(retainNotification = true))
        val work = store.reactionNotifications.pendingReactionNotifications(row.radioId).single()
        assertEquals(incoming, assertNotNull(store.reactionNotifications.fetchPendingReactionNotification(work)).reaction)
        store.reactionNotifications.acknowledgeReactionNotification(work.copy(messageID = UUID.randomUUID()))
        assertEquals(work, store.reactionNotifications.pendingReactionNotifications(row.radioId).single())
        store.saveReaction(incoming.copy(retainNotification = true))
        assertEquals(work, store.reactionNotifications.pendingReactionNotifications(row.radioId).single())
        store.reactionNotifications.acknowledgeReactionNotification(work)
        store.saveReaction(incoming.copy(retainNotification = true))
        assertTrue(store.reactionNotifications.pendingReactionNotifications(row.radioId).isEmpty())
        assertNotNull(store.fetchCommittedReaction(work.occurrence))
        assertNull(store.reactionNotifications.fetchPendingReactionNotification(work))
    }

    @Test fun staleAcknowledgementCannotConsumeAnIdenticalDeletedAndReinsertedOccurrence() = runTest {
        val row = reaction()
        store.saveReaction(row.copy(retainNotification = true))
        val old = store.reactionNotifications.pendingReactionNotifications(row.radioId).single()
        store.deleteReactionsForMessage(EntityKey(row.radioId, row.messageID))
        store.saveReaction(row.copy(retainNotification = true))
        val replacement = store.reactionNotifications.pendingReactionNotifications(row.radioId).single()
        assertNotEquals(old.token, replacement.token)
        assertNull(store.reactionNotifications.fetchPendingReactionNotification(old))
        store.reactionNotifications.acknowledgeReactionNotification(old)
        assertEquals(replacement, store.reactionNotifications.pendingReactionNotifications(row.radioId).single())
        assertNotNull(store.reactionNotifications.fetchPendingReactionNotification(replacement))
    }

    @Test fun changedSameIdOccurrenceCannotInheritNotificationAdmission() = runTest {
        val row = reaction()
        store.saveReaction(row.copy(retainNotification = true))
        val work = store.reactionNotifications.pendingReactionNotifications(row.radioId).single()
        store.saveReaction(row.copy(senderName = "Replacement", retainNotification = true))
        assertTrue(store.reactionNotifications.pendingReactionNotifications(row.radioId).isEmpty())
        assertNull(store.reactionNotifications.fetchPendingReactionNotification(work))
    }

    @Test fun failedReactionTransactionCannotLeaveNotificationCustody() = runTest {
        val row = reaction()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_notification_admission AFTER INSERT ON reactions " +
                "BEGIN SELECT RAISE(ABORT, 'notification admission fixture'); END",
        )
        try {
            assertFailsWith<PersistenceStoreException> { store.saveReaction(row.copy(retainNotification = true)) }
            assertTrue(store.reactionNotifications.pendingReactionNotifications(row.radioId).isEmpty())
            assertTrue(store.fetchReactions(EntityKey(row.radioId, row.messageID)).isEmpty())
            assertTrue(issues.isNotEmpty())
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_notification_admission") }
    }

    @Test fun failedAcknowledgementLeavesExactRequestAndDoesNotRevokeNavigationProof() = runTest {
        val row = reaction()
        store.saveReaction(row.copy(retainNotification = true))
        val work = store.reactionNotifications.pendingReactionNotifications(row.radioId).single()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_notification_ack AFTER UPDATE OF notificationToken ON reactions " +
                "BEGIN SELECT RAISE(ABORT, 'notification acknowledgement fixture'); END",
        )
        try {
            assertFailsWith<PersistenceStoreException> { store.reactionNotifications.acknowledgeReactionNotification(work) }
            assertEquals(work, store.reactionNotifications.pendingReactionNotifications(row.radioId).single())
            assertNotNull(store.fetchCommittedReaction(work.occurrence))
            assertTrue(issues.isNotEmpty())
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_notification_ack") }
        store.reactionNotifications.acknowledgeReactionNotification(work)
        assertTrue(store.reactionNotifications.pendingReactionNotifications(row.radioId).isEmpty())
        assertEquals(row, assertNotNull(store.fetchCommittedReaction(work.occurrence)).reaction)
    }

    @Test fun boundedPagesPreserveCommitOrderRatherThanReceivedTimeWithoutDroppingOverflow() = runTest {
        val first = reaction()
        val rows = (0..100).map {
            first.copy(id = UUID.randomUUID(), receivedAt = AT.minusSeconds(it.toLong()), retainNotification = true)
        }
        rows.forEach { store.saveReaction(it) }
        val firstPage = store.reactionNotifications.pendingReactionNotifications(first.radioId)
        assertEquals(rows.take(100).map { it.id }, firstPage.map { it.occurrence.id })
        firstPage.forEach { store.reactionNotifications.acknowledgeReactionNotification(it) }
        assertEquals(rows.last().id, store.reactionNotifications.pendingReactionNotifications(first.radioId).single().occurrence.id)
    }

    @Test fun pendingReadUsesAnIndexedNonNullRangeRatherThanCompletedHistory() {
        val plan = db.openHelper.readableDatabase.query(
            "EXPLAIN QUERY PLAN ${ReactionDao.PENDING_NOTIFICATIONS_SQL}",
            arrayOf(RADIO_A.value.toString().uppercase()),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
        }
        assertTrue(plan.any { "index_reactions_radioId_notificationToken" in it && "notificationToken>?" in it }, plan.toString())
    }
}
