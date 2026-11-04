// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.ContactIdentity
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AdoptionSourceTest : RepositoryTest() {
    @Test @OriginalCase("PersistenceStoreTests::adoptOrphanedDirectMessages links orphan DM and updates unread()")
    fun adoptionLinksAndRekeysExactlyOnceWithoutDoubleCounting() = runTest {
        val c = contact()
        store.saveContact(c)
        val orphan = message(contactID = null, direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED)
            .copy(senderKeyPrefix = c.publicKey.prefix(6), containsSelfMention = true)
        store.saveMessage(orphan)
        val identities = SnapshotList.of(ContactIdentity(c.id, c.publicKey))
        assertEquals(mapOf(c.id to 1L), store.adoptOrphanedDirectMessages(RADIO_A, identities))
        val m = store.fetchMessages(entity()).single()
        assertEquals(c.id, m.contactID)
        assertTrue(assertNotNull(m.deduplicationKey).contains(c.id.canonicalString()))
        val updated = assertNotNull(store.fetchContact(entity()))
        assertEquals(1L, updated.unreadCount)
        assertEquals(1L, updated.unreadMentionCount)
        assertEquals(orphan.sortDate, updated.lastMessageDate)
        assertTrue(store.adoptOrphanedDirectMessages(RADIO_A, identities).isEmpty())
        assertEquals(1L, assertNotNull(store.fetchContact(entity())).unreadCount)
        assertEquals(1L, assertNotNull(store.fetchContact(entity())).unreadMentionCount)
    }

    @Test @OriginalCase("PersistenceStoreTests::adoptOrphanedDirectMessages never adopts a channel message()")
    @OriginalCase("PersistenceStoreTests::adoptOrphanedDirectMessages never adopts an outgoing message()")
    @OriginalCase("PersistenceStoreTests::adoptOrphanedDirectMessages never adopts reaction wire text()")
    fun ineligibleDirectionsChannelsAndActualReactionFormatsStayOrphaned() = runTest {
        val c = contact()
        store.saveContact(c)
        val channel = message(contactID = null, channelIndex = 1u, direction = MessageDirection.INCOMING)
            .copy(senderKeyPrefix = c.publicKey.prefix(6))
        val outgoing = message(contactID = null).copy(senderKeyPrefix = c.publicKey.prefix(6))
        val reaction = message(contactID = null, text = "r:abcd:01", direction = MessageDirection.INCOMING)
            .copy(senderKeyPrefix = c.publicKey.prefix(6))
        for (m in listOf(channel, outgoing, reaction)) store.saveMessage(m)
        assertTrue(store.adoptOrphanedDirectMessages(RADIO_A, SnapshotList.of(ContactIdentity(c.id, c.publicKey))).isEmpty())
        for (m in listOf(channel, outgoing, reaction)) {
            assertNull(assertNotNull(store.fetchMessage(entity(id = m.id))).contactID)
        }
        assertEquals(1.toUByte(), assertNotNull(store.fetchMessage(entity(id = channel.id))).channelIndex)
    }

    @Test @OriginalCase("PersistenceStoreTests::adoptOrphanedDirectMessages skips multi-match prefix()")
    fun ambiguousPrefixCannotGuessAContact() = runTest {
        val c = contact()
        val bytes = c.publicKey.toByteArray()
        bytes[6] = 0x45
        val other = contact(id = UUID.randomUUID(), publicKey = Bytes(bytes), name = "B")
        store.saveContact(c)
        store.saveContact(other)
        val m = message(contactID = null, direction = MessageDirection.INCOMING).copy(senderKeyPrefix = c.publicKey.prefix(6))
        store.saveMessage(m)
        assertTrue(store.adoptOrphanedDirectMessages(RADIO_A,
            SnapshotList.of(ContactIdentity(c.id, c.publicKey), ContactIdentity(other.id, other.publicKey))).isEmpty())
        assertNull(assertNotNull(store.fetchMessage(entity(id = m.id))).contactID)
    }

    // AndroidOnly: WP-202 Delta subsets cannot resolve a prefix that matches multiple persisted contacts.
    @Test
    fun deltaSubsetCannotResolvePersistedPrefixCollision() = runTest {
        val supplied = contact()
        val bytes = supplied.publicKey.toByteArray()
        bytes[6] = 0x45
        val omitted = contact(id = UUID.randomUUID(), publicKey = Bytes(bytes), name = "Omitted")
        store.saveContact(supplied)
        store.saveContact(omitted)
        val orphan = message(contactID = null, direction = MessageDirection.INCOMING)
            .copy(senderKeyPrefix = supplied.publicKey.prefix(6))
        store.saveMessage(orphan)

        assertTrue(
            store.adoptOrphanedDirectMessages(
                RADIO_A,
                SnapshotList.of(ContactIdentity(supplied.id, supplied.publicKey)),
            ).isEmpty(),
        )
        assertNull(assertNotNull(store.fetchMessage(entity(id = orphan.id))).contactID)
    }

    @Test @OriginalCase("PersistenceStoreTests::adoptOrphanedDirectMessages adopts blocked contact without unread bump()")
    fun blockedContactCanRecoverHistoryButNotUnreadOrMentionBadges() = runTest {
        val c = contact().copy(isBlocked = true)
        store.saveContact(c)
        val m = message(contactID = null, direction = MessageDirection.INCOMING)
            .copy(senderKeyPrefix = c.publicKey.prefix(6), containsSelfMention = true)
        store.saveMessage(m)
        assertEquals(mapOf(c.id to 1L), store.adoptOrphanedDirectMessages(RADIO_A, SnapshotList.of(ContactIdentity(c.id, c.publicKey))))
        assertEquals(1, store.fetchMessages(entity()).size)
        assertEquals(0L, assertNotNull(store.fetchContact(entity())).unreadCount)
        assertEquals(0L, assertNotNull(store.fetchContact(entity())).unreadMentionCount)
    }

    @Test @OriginalCase("PersistenceStoreTests::setInboundHopCount round-trips onto an existing discovered node()")
    @OriginalCase("PersistenceStoreTests::setInboundHopCount keeps the closest copy within the same broadcast()")
    @OriginalCase("PersistenceStoreTests::a newer advert timestamp raises the inbound hop count()")
    @OriginalCase("PersistenceStoreTests::an older advert timestamp is a no-op even when it has a closer hop count()")
    @OriginalCase("PersistenceStoreTests::upsertDiscoveredNode does not reset a stored inbound hop count()")
    @OriginalCase("PersistenceStoreTests::DiscoveredNodeDTO carries inboundHopCount and equality discriminates on it()")
    fun advertOrderingUsesTimestampThenClosestCopyAndNeverResetsOnUpsert() = runTest {
        val f = frame(name = "Advertiser")
        val node = store.upsertDiscoveredNode(RADIO_A, f).node
        store.setInboundHopCount(RADIO_A, f.publicKey, 4, 100u)
        assertEquals(4L, store.fetchDiscoveredNodes(RADIO_A).single().inboundHopCount)
        assertEquals(100u, store.fetchDiscoveredNodes(RADIO_A).single().inboundHopAdvertTimestamp)
        store.setInboundHopCount(RADIO_A, f.publicKey, 0, 100u)
        store.setInboundHopCount(RADIO_A, f.publicKey, 5, 100u)
        assertEquals(0L, store.fetchDiscoveredNodes(RADIO_A).single().inboundHopCount)
        store.setInboundHopCount(RADIO_A, f.publicKey, 3, 200u)
        store.setInboundHopCount(RADIO_A, f.publicKey, 1, 100u)
        assertEquals(3L, store.fetchDiscoveredNodes(RADIO_A).single().inboundHopCount)
        assertEquals(200u, store.fetchDiscoveredNodes(RADIO_A).single().inboundHopAdvertTimestamp)
        store.upsertDiscoveredNode(RADIO_A, f.copy(outPathLength = 255u, outPath = Bytes.EMPTY, lastAdvertTimestamp = 300u))
        val updated = store.fetchDiscoveredNodes(RADIO_A).single()
        assertEquals(node.id, updated.id)
        assertEquals(3L, updated.inboundHopCount)
        assertNotEquals(updated, updated.copy(inboundHopCount = 99))
    }

    @Test @OriginalCase("PersistenceStoreTests::setInboundHopCount is a no-op when no matching row exists()")
    @OriginalCase("PersistenceStoreTests::an inbound hop heard before the node row exists is applied when the advert upsert lands()")
    @OriginalCase("PersistenceStoreTests::a buffered inbound hop keeps the closest copy of the same broadcast before the row lands()")
    fun preAdvertBufferIsPartitionedAndAdoptsOnlyWhenItsRowAppears() = runTest {
        val f = frame(name = "Advertiser")
        store.setInboundHopCount(RADIO_A, f.publicKey, 4, 100u)
        store.setInboundHopCount(RADIO_A, f.publicKey, 1, 100u)
        store.setInboundHopCount(RADIO_A, f.publicKey, 6, 100u)
        assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
        assertNull(store.upsertDiscoveredNode(RADIO_B, f).node.inboundHopCount)
        val result = store.upsertDiscoveredNode(RADIO_A, f)
        assertTrue(result.isNew)
        assertEquals(1L, result.node.inboundHopCount)
        assertEquals(1L, store.fetchDiscoveredNodes(RADIO_A).single().inboundHopCount)
    }
}
