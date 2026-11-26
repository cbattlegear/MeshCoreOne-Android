// PortedFrom: MC1Services/Tests/MC1ServicesTests/BlockedChannelSenderPersistenceTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/BlockedSenderMessageDeletionTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.model.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BlockedSenderPersistenceTest : RepositoryTest() {
    @Test @OriginalCase("BlockedChannelSenderPersistenceTests::Save and fetch round-trip returns the blocked sender()")
    @OriginalCase("BlockedChannelSenderPersistenceTests::Name preserves original casing for display()")
    fun roundTripPreservesNameCaseRadioAndIdentity() = runTest {
        val dto = BlockedChannelSenderDTO(name = "Spammer", radioId = RADIO_A, dateBlocked = AT)
        store.saveBlockedChannelSender(dto)
        assertEquals(listOf(dto), store.fetchBlockedChannelSenders(RADIO_A))
        val alice = BlockedChannelSenderDTO(name = "Alice", radioId = RADIO_A, dateBlocked = AT)
        store.saveBlockedChannelSender(alice)
        assertTrue(store.fetchBlockedChannelSenders(RADIO_A).any { it.name == "Alice" })
    }

    @Test @OriginalCase("BlockedChannelSenderPersistenceTests::Re-saving same name updates dateBlocked instead of creating duplicate()")
    fun nameUpsertKeepsTheOriginalIDAndUpdatesDate() = runTest {
        val first = BlockedChannelSenderDTO(name = "Troll", radioId = RADIO_A, dateBlocked = AT.minusSeconds(10))
        store.saveBlockedChannelSender(first)
        store.saveBlockedChannelSender(BlockedChannelSenderDTO(name = "Troll", radioId = RADIO_A, dateBlocked = AT))
        assertEquals(listOf(first.copy(dateBlocked = AT)), store.fetchBlockedChannelSenders(RADIO_A))
    }

    @Test @OriginalCase("BlockedChannelSenderPersistenceTests::Delete removes the blocked sender entry()")
    @OriginalCase("BlockedChannelSenderPersistenceTests::Delete requires exact case match()")
    fun deleteRequiresExactCaseAndCommits() = runTest {
        val dto = BlockedChannelSenderDTO(name = "Alice", radioId = RADIO_A, dateBlocked = AT)
        store.saveBlockedChannelSender(dto)
        store.deleteBlockedChannelSender(RADIO_A, "ALICE")
        assertEquals(listOf(dto), store.fetchBlockedChannelSenders(RADIO_A))
        store.deleteBlockedChannelSender(RADIO_A, "Alice")
        assertTrue(store.fetchBlockedChannelSenders(RADIO_A).isEmpty())
    }

    @Test @OriginalCase("BlockedChannelSenderPersistenceTests::Fetch returns only senders blocked for the specified device()")
    @OriginalCase("BlockedChannelSenderPersistenceTests::Saving same name with different case creates separate entries()")
    fun namesAndRadioPartitionsRemainIndependent() = runTest {
        store.saveBlockedChannelSender(BlockedChannelSenderDTO(name = "Alice", radioId = RADIO_A, dateBlocked = AT))
        store.saveBlockedChannelSender(BlockedChannelSenderDTO(name = "ALICE", radioId = RADIO_A, dateBlocked = AT))
        store.saveBlockedChannelSender(BlockedChannelSenderDTO(name = "Bob", radioId = RADIO_B, dateBlocked = AT))
        assertEquals(setOf("Alice", "ALICE"), store.fetchBlockedChannelSenders(RADIO_A).map { it.name }.toSet())
        assertEquals(listOf("Bob"), store.fetchBlockedChannelSenders(RADIO_B).map { it.name })
    }

    @Test @OriginalCase("BlockedChannelSenderPersistenceTests::Fetch returns senders sorted by most recently blocked first()")
    fun blockedDatesSortNewestFirstIncludingFractions() = runTest {
        for ((name, date) in listOf("first" to AT, "second" to AT.plusNanos(2), "third" to AT.plusNanos(1))) {
            store.saveBlockedChannelSender(BlockedChannelSenderDTO(name = name, radioId = RADIO_A, dateBlocked = date))
        }
        assertEquals(listOf("second", "third", "first"), store.fetchBlockedChannelSenders(RADIO_A).map { it.name })
    }

    @Test @OriginalCase("BlockedSenderMessageDeletionTests::Deletes all channel messages from a named sender()")
    @OriginalCase("BlockedSenderMessageDeletionTests::Does not delete DMs from the same sender()")
    @OriginalCase("BlockedSenderMessageDeletionTests::Scopes deletion to the specified device()")
    @OriginalCase("BlockedSenderMessageDeletionTests::Preserves messages with nil senderNodeName()")
    fun senderWipeSpansSlotsButNotDMsOtherRadiosOtherNamesOrNil() = runTest {
        val spamA = message(contactID = null, channelIndex = 0u).copy(senderNodeName = "Spammer")
        val spamB = message(contactID = null, channelIndex = 1u).copy(senderNodeName = "Spammer")
        val dm = message().copy(senderNodeName = "Spammer")
        val otherRadio = spamA.copy(radioId = RADIO_B)
        val legit = message(contactID = null, channelIndex = 0u).copy(senderNodeName = "Legit")
        val unnamed = message(contactID = null, channelIndex = 0u)
        for (m in listOf(spamA, spamB, dm, otherRadio, legit, unnamed)) store.saveMessage(m)
        store.deleteChannelMessages("Spammer", RADIO_A)
        assertNull(store.fetchMessage(entity(id = spamA.id)))
        assertNull(store.fetchMessage(entity(id = spamB.id)))
        for (m in listOf(dm, otherRadio, legit, unnamed)) {
            assertEquals(m.id, assertNotNull(store.fetchMessage(entity(m.radioId, m.id))).id)
        }
    }

    @Test @OriginalCase("BlockedSenderMessageDeletionTests::No-op when sender has no messages()")
    fun absentSenderCannotDeleteAnotherSender() = runTest {
        val kept = message(contactID = null, channelIndex = 0u).copy(senderNodeName = "Legit")
        store.saveMessage(kept)
        store.deleteChannelMessages("Ghost", RADIO_A)
        assertEquals(kept.id, assertNotNull(store.fetchMessage(entity(id = kept.id))).id)
    }

    @Test @OriginalCase("BlockedSenderMessageDeletionTests::Deletes reactions associated with deleted channel messages()")
    fun senderWipeCascadesOnlyTheTargetMessagesReactions() = runTest {
        val spam = message(contactID = null, channelIndex = 0u).copy(senderNodeName = "Spammer")
        val legit = message(contactID = null, channelIndex = 0u).copy(senderNodeName = "Legit")
        for (m in listOf(spam, legit)) {
            store.saveMessage(m)
            store.saveReaction(ReactionDTO(messageID = m.id, emoji = "\uD83D\uDC4D", senderName = "Reactor",
                messageHash = "AABB1122", rawText = ":thumbsup:", receivedAt = AT, channelIndex = 0u, radioId = RADIO_A))
        }
        store.deleteChannelMessages("Spammer", RADIO_A)
        assertTrue(store.fetchReactions(entity(id = spam.id)).isEmpty())
        assertEquals(1, store.fetchReactions(entity(id = legit.id)).size)
    }
}
