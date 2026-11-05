// AndroidOnly: WP-202 Real repository identity, prefix, region, notification and mention-policy assertions.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NativeRadioPolicyTest : RepositoryTest() {
    @Test fun knownRegionRemovalIsAtomicCaseSensitiveAndResetsOnlyMatchingRadioScopes() = runTest {
        val d = device()
        store.saveDevice(d)
        store.addDeviceKnownRegion(RADIO_A, "Germany")
        store.addDeviceKnownRegion(RADIO_A, "Germany")
        store.addDeviceKnownRegion(RADIO_A, "germany")
        val scoped = ChannelDTO(radioId = RADIO_A, index = 1u, name = "scoped").withFloodScope(ChannelFloodScope.Region("Germany"))
        val otherCase = scoped.copy(id = UUID.randomUUID(), index = 2u).withFloodScope(ChannelFloodScope.Region("germany"))
        val otherRadio = scoped.copy(radioId = RADIO_B)
        for (channel in listOf(scoped, otherCase, otherRadio)) store.saveChannel(channel)
        store.saveDevice(d.copy(knownRegions = SnapshotList.empty(), nodeName = "updated"))
        assertEquals(listOf("Germany", "germany"), assertNotNull(store.fetchDevice(RADIO_A)).knownRegions)
        store.removeDeviceKnownRegion(RADIO_A, "Germany")
        assertEquals(listOf("germany"), assertNotNull(store.fetchDevice(RADIO_A)).knownRegions)
        val reset = assertNotNull(store.fetchChannel(entity(id = scoped.id)))
        assertEquals("inherit", reset.floodScopeModeRawValue)
        assertNull(reset.regionScope)
        assertEquals(ChannelFloodScope.Region("germany"), assertNotNull(store.fetchChannel(entity(id = otherCase.id))).floodScope)
        assertEquals(ChannelFloodScope.Region("Germany"), assertNotNull(store.fetchChannel(entity(RADIO_B, scoped.id))).floodScope)
    }

    @Test fun prefixGroupsRetainCollisionsWhileGlobalRoomNameHintsRemainExplicitExceptions() = runTest {
        val first = contact().copy(nickname = "")
        val changed = first.publicKey.toByteArray()
        changed[6] = 0x45
        val second = contact(id = UUID.randomUUID(), publicKey = Bytes(changed), name = "second")
        val crossRadio = contact(RADIO_B, publicKey = key(0x80), name = "cross radio")
        for (c in listOf(first, second, crossRadio)) store.saveContact(c)
        val groups = store.fetchContactPublicKeysByPrefix(RADIO_A)
        assertEquals(setOf(first.publicKey, second.publicKey), groups[0x44u]?.toSet())
        assertEquals(setOf(first.publicKey, second.publicKey), store.fetchContactPublicKeys(RADIO_A))
        assertNull(store.fetchContact(RADIO_A, crossRadio.publicKey))
        assertEquals(crossRadio.id, store.findContactByPublicKey(crossRadio.publicKey)?.id)
        assertEquals("cross radio", store.findContactNameByKeyPrefix(crossRadio.publicKey.prefix(4)))
        assertEquals("", store.findContactNameByKeyPrefix(first.publicKey.prefix(6)))
        assertNotNull(store.fetchContactByPrefix(RADIO_A, first.publicKey.prefix(6)))
        assertNull(store.fetchContactByPrefix(RADIO_A, first.publicKey.prefix(4)))
    }

    @Test fun dtoMutationsPreserveContactIdentityAndMonotonicHeardButRadioFramesOwnRadioFields() = runTest {
        val original = contact().copy(lastHeardTimestamp = UInt.MAX_VALUE, nickname = "local",
            isFavorite = true, flags = 1u, avatarImageData = Bytes.of(0x80))
        store.saveContact(original)
        store.saveContact(original.copy(publicKey = key(0xFF), lastHeardTimestamp = 1u, name = "updated",
            outPathLength = 255u, outPath = Bytes.EMPTY))
        val applied = assertNotNull(store.fetchContact(entity()))
        assertEquals(original.publicKey, applied.publicKey)
        assertEquals(UInt.MAX_VALUE, applied.lastHeardTimestamp)
        assertEquals("updated", applied.name)
        store.saveContact(RADIO_A, frame(name = "radio", flags = 0xFEu))
        val fromRadio = assertNotNull(store.fetchContact(entity()))
        assertEquals(255.toUByte(), fromRadio.flags)
        assertTrue(fromRadio.isFavorite)
        assertEquals("local", fromRadio.nickname)
        assertEquals(Bytes.of(0x80), fromRadio.avatarImageData)
        assertEquals(UInt.MAX_VALUE, fromRadio.lastHeardTimestamp)
        assertEquals(Bytes.of(1, 2), fromRadio.outPath)
    }

    @Test fun channelDtoAndRadioInfoHaveDeliberatelyDifferentSecretReplacementPolicies() = runTest {
        val channel = ChannelDTO(radioId = RADIO_A, index = 1u, name = "before",
            isEnabled = false, unreadCount = 2, notificationLevel = NotificationLevel.MENTIONS_ONLY)
        store.saveChannel(channel)
        val m = message(contactID = null, channelIndex = 1u)
        store.saveMessage(m)
        store.saveChannel(channel.copy(index = 7u, name = "dto", secret = Bytes(ByteArray(16) { 0x42 })))
        val applied = assertNotNull(store.fetchChannel(RADIO_A, 1u))
        assertNotEquals(channel.id, applied.id)
        assertEquals(1.toUByte(), applied.index)
        assertEquals(2L, applied.unreadCount)
        assertFalse(applied.isEnabled)
        assertTrue(store.fetchMessages(RADIO_A, 1u).isEmpty())
        store.saveChannel(RADIO_A, ChannelInfo(1u, "radio", Bytes(ByteArray(16) { 0x43 })))
        val fromRadio = assertNotNull(store.fetchChannel(RADIO_A, 1u))
        assertNotEquals(applied.id, fromRadio.id)
        assertEquals(0L, fromRadio.unreadCount)
        assertEquals(NotificationLevel.MENTIONS_ONLY, fromRadio.notificationLevel)
        assertFalse(fromRadio.isEnabled)
        assertTrue(store.fetchMessages(RADIO_A, 1u).isEmpty())
    }

    @Test fun mentionQueriesUseOldestWireClockAndSeenFlagsAndCountersDoNotCrossRadios() = runTest {
        store.saveContact(contact())
        store.saveContact(contact(RADIO_B))
        val rows = listOf(30u, 10u, 20u).map {
            message(timestamp = it).copy(containsSelfMention = true)
        }
        for (m in rows) {
            store.saveMessage(m)
            store.saveMessage(m.copy(radioId = RADIO_B))
        }
        assertEquals(listOf(rows[1].id, rows[2].id, rows[0].id), store.fetchUnseenMentionIDs(entity()))
        store.markMentionSeen(entity(id = rows[1].id))
        assertEquals(listOf(rows[2].id, rows[0].id), store.fetchUnseenMentionIDs(entity()))
        assertEquals(3, store.fetchUnseenMentionIDs(entity(RADIO_B)).size)
        store.incrementUnreadMentionCount(entity())
        store.decrementUnreadMentionCount(entity())
        store.decrementUnreadMentionCount(entity())
        assertEquals(0L, assertNotNull(store.fetchContact(entity())).unreadMentionCount)
        store.incrementUnreadMentionCount(entity())
        store.clearUnreadMentionCount(entity())
        assertEquals(0L, assertNotNull(store.fetchContact(entity())).unreadMentionCount)
        val channelRows = rows.map { it.copy(id = UUID.randomUUID(), contactID = null, channelIndex = 2u) }
        for (m in channelRows) store.saveMessage(m)
        assertEquals(listOf(channelRows[1].id, channelRows[2].id, channelRows[0].id),
            store.fetchUnseenChannelMentionIDs(RADIO_A, 2u))
        store.markMentionSeen(entity(id = channelRows[1].id))
        assertEquals(listOf(channelRows[2].id, channelRows[0].id), store.fetchUnseenChannelMentionIDs(RADIO_A, 2u))
    }

    @Test fun channelAndRoomNotificationFavoriteUnreadAndMentionMutationsPreserveRawUnrelatedFields() = runTest {
        val channel = ChannelDTO(radioId = RADIO_A, index = 1u, name = "channel", floodScopeModeRawValue = "futureMode", regionScope = "")
        store.saveChannel(channel)
        val k = entity(id = channel.id)
        store.setChannelMuted(k, true)
        assertEquals(NotificationLevel.MUTED, assertNotNull(store.fetchChannel(k)).notificationLevel)
        store.setChannelMuted(k, false)
        store.setChannelFavorite(k, true)
        store.incrementChannelUnreadCount(k)
        assertEquals(1L, store.getChannelUnreadCount(k))
        store.clearChannelUnreadCount(RADIO_A, 1u)
        store.incrementChannelUnreadMentionCount(k)
        store.decrementChannelUnreadMentionCount(k)
        store.decrementChannelUnreadMentionCount(k)
        store.clearChannelUnreadMentionCount(k)
        val updated = assertNotNull(store.fetchChannel(k))
        assertTrue(updated.isFavorite)
        assertEquals(NotificationLevel.ALL, updated.notificationLevel)
        assertEquals(0L, updated.unreadCount)
        assertEquals(0L, updated.unreadMentionCount)
        assertEquals("futureMode", updated.floodScopeModeRawValue)
        assertEquals("", updated.regionScope)
        assertEquals(0L, store.getChannelUnreadCount(entity(id = UUID.randomUUID())))
        assertEquals(0L, store.getUnreadCount(entity(id = UUID.randomUUID())))
        val room = session()
        store.saveRemoteNodeSessionDTO(room)
        val r = entity(id = room.id)
        store.setSessionMuted(r, true)
        store.setSessionFavorite(r, true)
        store.incrementRoomUnreadCount(r)
        assertEquals(1L, assertNotNull(store.fetchRemoteNodeSession(r)).unreadCount)
        store.resetRoomUnreadCount(r)
        store.setSessionNotificationLevel(r, NotificationLevel.ALL)
        assertEquals(0L, assertNotNull(store.fetchRemoteNodeSession(r)).unreadCount)
        assertTrue(assertNotNull(store.fetchRemoteNodeSession(r)).isFavorite)
    }

    @Test fun nullableRoomAckUpdatesPreserveExistingHighBitBookkeepingAndLongRetryCounts() = runTest {
        val room = session()
        store.saveRemoteNodeSessionDTO(room)
        val m = RoomMessageDTO(sessionID = room.id, authorKeyPrefix = Bytes.EMPTY, text = "room", timestamp = UInt.MAX_VALUE,
            createdAt = AT, ackCode = UInt.MAX_VALUE, roundTripTime = 0x8000_0000u)
        store.saveRoomMessage(RADIO_A, m)
        store.updateRoomMessageStatus(entity(id = m.id), MessageStatus.SENT, null, null)
        store.updateRoomMessageRetryStatus(entity(id = m.id), MessageStatus.RETRYING, 3_000_000_000L, Long.MAX_VALUE)
        val updated = assertNotNull(store.fetchRoomMessage(entity(id = m.id)))
        assertEquals(UInt.MAX_VALUE, updated.ackCode)
        assertEquals(0x8000_0000u, updated.roundTripTime)
        assertEquals(3_000_000_000L, updated.retryAttempt)
        assertEquals(Long.MAX_VALUE, updated.maxRetryAttempts)
    }
}
