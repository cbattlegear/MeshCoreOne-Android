// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MessageSourceTest : RepositoryTest() {
    @Test @OriginalCase("PersistenceStoreTests::fetchMessage by dedup key returns the row for the same radio()")
    fun dedupLookupDoesNotSuppressAnotherRadiosReception() = runTest {
        val m = message(contactID = null, channelIndex = 0u).copy(deduplicationKey = "ch-0-1-Alice-ABCD")
        store.saveMessage(m)
        assertEquals(m.id, assertNotNull(store.fetchMessage("ch-0-1-Alice-ABCD", RADIO_A)).id)
        assertNull(store.fetchMessage("ch-0-1-Alice-ABCD", RADIO_B))
        assertFalse(store.isDuplicateMessage("ch-0-1-Alice-ABCD", RADIO_B))
        store.saveMessage(m.copy(radioId = RADIO_B))
        assertTrue(store.isDuplicateMessage("ch-0-1-Alice-ABCD", RADIO_B))
    }

    @Test @OriginalCase("PersistenceStoreTests::adoptIncomingPathIfUnknown writes the first path onto a nil column()")
    @OriginalCase("PersistenceStoreTests::adoptIncomingPathIfUnknown returns false when the row is missing()")
    fun pathAdoptionIsFirstWinsAndPreservesSignalAndRepeatCount() = runTest {
        val m = message(contactID = null, channelIndex = 0u).copy(snr = 7.5, pathNodes = null)
        store.saveMessage(m)
        assertTrue(store.adoptIncomingPathIfUnknown(entity(id = m.id), Bytes.of(0xAA), 1u))
        assertFalse(store.adoptIncomingPathIfUnknown(entity(id = m.id), Bytes.of(0xBB), 2u))
        val row = assertNotNull(store.fetchMessage(entity(id = m.id)))
        assertEquals(Bytes.of(0xAA), row.pathNodes)
        assertEquals(1.toUByte(), row.pathLength)
        assertEquals(0L, row.heardRepeats)
        assertEquals(7.5, row.snr)
        assertFalse(store.adoptIncomingPathIfUnknown(entity(id = UUID.randomUUID()), Bytes.of(0xAA), 1u))
    }

    @Test @OriginalCase("PersistenceStoreTests::Save and fetch messages for contact()")
    fun newestSelectionIsReturnedChronologically() = runTest {
        for (i in 0..4) store.saveMessage(message(text = "Message $i", timestamp = 1_700_000_000u + i.toUInt(),
            createdAt = AT.plusSeconds(i.toLong())))
        val rows = store.fetchMessages(entity())
        assertEquals(5, rows.size)
        assertEquals("Message 0", rows.first().text)
        assertEquals("Message 4", rows.last().text)
    }

    @Test @OriginalCase("PersistenceStoreTests::Interleaved backlog from multiple senders reassembles in sortDate (send) order()")
    @OriginalCase("PersistenceStoreTests::Live message stays last even when an earlier-sortDate backlog row is inserted after it()")
    fun explicitSortDateWinsOverArrivalOrder() = runTest {
        val send = Instant.ofEpochSecond(1_000_000)
        val drain = Instant.ofEpochSecond(2_000_000)
        for ((offset, index) in listOf(2, 0, 3, 1).withIndex()) {
            store.saveMessage(message(text = "Send $index", timestamp = 1_000_000u + index.toUInt(),
                createdAt = drain.plusSeconds(offset.toLong())).copy(sortDate = send.plusSeconds(index.toLong())))
        }
        assertEquals(listOf("Send 0", "Send 1", "Send 2", "Send 3"), store.fetchMessages(entity()).map { it.text })
        val other = UUID.randomUUID()
        store.saveMessage(message(contactID = other, text = "Live", timestamp = 3_000_000u,
            createdAt = Instant.ofEpochSecond(3_000_000)))
        store.saveMessage(message(contactID = other, text = "Backlog", timestamp = 2_996_400u,
            createdAt = Instant.ofEpochSecond(3_000_060)).copy(sortDate = Instant.ofEpochSecond(2_996_400)))
        assertEquals(listOf("Backlog", "Live"), store.fetchMessages(entity(id = other)).map { it.text })
    }

    @Test @OriginalCase("PersistenceStoreTests::Equal sortDate and timestamp fall back to createdAt order (tertiary key)()")
    @OriginalCase("PersistenceStoreTests::Backlog block orders by send time within a shared drain anchor()")
    fun allThreeOrderingKeysRemainDistinct() = runTest {
        for (i in 0..2) store.saveMessage(message(text = "Tie $i", timestamp = 4_000_000u,
            createdAt = Instant.ofEpochSecond(5_000_000L + i)).copy(sortDate = Instant.ofEpochSecond(4_000_000)))
        assertEquals(listOf("Tie 0", "Tie 1", "Tie 2"), store.fetchMessages(entity()).map { it.text })
        for ((sender, timestamp, offset) in listOf(Triple("Carol", 300u, 0L), Triple("Bob", 200u, 1L), Triple("Alice", 100u, 2L))) {
            store.saveMessage(message(contactID = null, channelIndex = 0u, text = sender, timestamp = timestamp,
                createdAt = AT.plusSeconds(offset), direction = MessageDirection.INCOMING).copy(sortDate = AT, senderNodeName = sender))
        }
        assertEquals(listOf("Alice", "Bob", "Carol"), store.fetchMessages(RADIO_A, 0u).map { it.text })
    }

    @Test @OriginalCase("PersistenceStoreTests::Find channel message for reaction within timestamp window()")
    @OriginalCase("PersistenceStoreTests::Find outgoing channel message for reaction using local node name()")
    fun reactionCorrelationUsesIndependentCrockfordVectorsAndSenderContext() = runTest {
        var target: UUID? = null
        for (i in 0..119) {
            val m = message(contactID = null, channelIndex = 1u, text = "Message $i", timestamp = 1_700_000_000u + i.toUInt(),
                createdAt = AT.plusSeconds(i.toLong()), direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED)
                .copy(senderNodeName = "RemoteNode")
            store.saveMessage(m)
            if (i == 80) target = m.id
        }
        val parsed = ParsedReaction("\uD83D\uDC4D", "RemoteNode", "dxm1cz0e")
        assertEquals(target, store.findChannelMessageForReaction(RADIO_A, 1u, parsed, "LocalNode",
            1_699_999_780u..1_700_000_380u, 200)?.id)
        val outgoing = message(contactID = null, channelIndex = 2u, text = "Local message", timestamp = 1_700_000_200u)
        store.saveMessage(outgoing)
        val local = ParsedReaction("\uD83D\uDD25", "LocalNode", "z6j5gpmd")
        assertEquals(outgoing.id, store.findChannelMessageForReaction(RADIO_A, 2u, local, "LocalNode",
            1_699_999_900u..1_700_000_500u, 200)?.id)
        assertNull(store.findChannelMessageForReaction(RADIO_A, 2u, local, null,
            1_699_999_900u..1_700_000_500u, 200))
    }

    @Test @OriginalCase("PersistenceStoreTests::Update message status()")
    @OriginalCase("PersistenceStoreTests::updateMessageRetryStatus does not resurrect a .failed row()")
    @OriginalCase("PersistenceStoreTests::updateMessageRetryStatus still advances a non-terminal row()")
    @OriginalCase("PersistenceStoreTests::updateMessageAck refuses to write .delivered onto a .failed row()")
    fun normalStatusAdvancesButStaleRetryAndAckCannotResurrectFailure() = runTest {
        val m = message(status = MessageStatus.PENDING)
        store.saveMessage(m)
        val k = entity(id = m.id)
        store.updateMessageStatus(k, MessageStatus.SENDING)
        assertEquals(MessageStatus.SENDING, assertNotNull(store.fetchMessage(k)).status)
        store.updateMessageStatus(k, MessageStatus.SENT)
        assertEquals(MessageStatus.SENT, assertNotNull(store.fetchMessage(k)).status)
        store.updateMessageRetryStatus(k, MessageStatus.RETRYING, 0, 4)
        assertEquals(MessageStatus.RETRYING, assertNotNull(store.fetchMessage(k)).status)
        store.updateMessageStatus(k, MessageStatus.FAILED)
        store.updateMessageRetryStatus(k, MessageStatus.RETRYING, 1, 4)
        store.updateMessageAck(k, 0xDEAD_BEEFu, MessageStatus.DELIVERED)
        assertEquals(MessageStatus.FAILED, assertNotNull(store.fetchMessage(k)).status)
    }

    @Test @OriginalCase("PersistenceStoreTests::clearRetryingToSent no-ops on .failed but promotes .retrying and .pending to .sent()")
    @OriginalCase("PersistenceStoreTests::clearRetryingToSent no-ops on a .delivered row()")
    @OriginalCase("PersistenceStoreTests::shared updateMessageStatusUnlessDelivered still remaps .failed to .pending for the offline queue()")
    fun terminalGuardsAndTheExplicitOfflineRecoveryTransitionRemainDifferent() = runTest {
        for (status in listOf(MessageStatus.FAILED, MessageStatus.DELIVERED, MessageStatus.RETRYING, MessageStatus.PENDING)) {
            val m = message(status = status)
            store.saveMessage(m)
            val k = entity(id = m.id)
            val terminal = status == MessageStatus.FAILED || status == MessageStatus.DELIVERED
            assertEquals(!terminal, store.clearRetryingToSent(k))
            assertEquals(if (terminal) status else MessageStatus.SENT, assertNotNull(store.fetchMessage(k)).status)
        }
        val failed = message(status = MessageStatus.FAILED)
        store.saveMessage(failed)
        val k = entity(id = failed.id)
        assertTrue(store.updateMessageStatusUnlessDelivered(k, MessageStatus.PENDING))
        store.updateMessageAck(k, 0x1234_5678u, MessageStatus.DELIVERED)
        assertEquals(MessageStatus.DELIVERED, assertNotNull(store.fetchMessage(k)).status)
        assertFalse(store.updateMessageStatusUnlessDelivered(k, MessageStatus.FAILED))
    }

    @Test @OriginalCase("PersistenceStoreTests::hasOutgoingSentDM flags a stuck .sent DM by ackCode and ignores other rows()")
    fun outgoingSentAckDiagnosticIsRadioScopedAndExcludesDeliveredAndChannels() = runTest {
        store.saveMessage(message().copy(ackCode = 0xCAFE_F00Du))
        assertTrue(store.hasOutgoingSentDM(RADIO_A, 0xCAFE_F00Du))
        assertFalse(store.hasOutgoingSentDM(RADIO_B, 0xCAFE_F00Du))
        assertFalse(store.hasOutgoingSentDM(RADIO_A, 1u))
        store.saveMessage(message(status = MessageStatus.DELIVERED).copy(ackCode = 0xBADC_0DE5u))
        assertFalse(store.hasOutgoingSentDM(RADIO_A, 0xBADC_0DE5u))
        store.saveMessage(message(contactID = null, channelIndex = 1u).copy(ackCode = 0xABCD_1234u))
        assertFalse(store.hasOutgoingSentDM(RADIO_A, 0xABCD_1234u))
    }

    @Test @OriginalCase("PersistenceStoreTests::newestUnreadIncomingMessage returns the newest unread incoming only()")
    @OriginalCase("PersistenceStoreTests::newestUnreadIncomingMessage returns nil when no unread incoming exists()")
    fun newestUnreadSelectionExcludesReadOutgoingAndOtherContactRows() = runTest {
        data class Arrival(val text: String, val offset: Long, val direction: MessageDirection, val read: Boolean)
        for ((text, offset, direction, read) in listOf(
            Arrival("old unread incoming", 10, MessageDirection.INCOMING, false),
            Arrival("new unread incoming", 30, MessageDirection.INCOMING, false),
            Arrival("newer but read", 40, MessageDirection.INCOMING, true),
            Arrival("newer but outgoing", 50, MessageDirection.OUTGOING, false),
        )) {
            val at = AT.plusSeconds(offset)
            store.saveMessage(message(text = text, timestamp = at.epochSecond.toUInt(), createdAt = at,
                direction = direction).copy(isRead = read))
        }
        store.saveMessage(message(contactID = UUID.randomUUID(), createdAt = AT.plusSeconds(60),
            direction = MessageDirection.INCOMING))
        assertEquals("new unread incoming", assertNotNull(store.newestUnreadIncomingMessage(entity())).text)
        assertNull(store.newestUnreadIncomingMessage(entity(id = UUID.randomUUID())))
        for (m in store.fetchMessages(entity())) store.markMessageAsRead(entity(id = m.id))
        assertNull(store.newestUnreadIncomingMessage(entity()))
    }
}
