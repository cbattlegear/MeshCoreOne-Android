// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DeviceContactSourceTest : RepositoryTest() {
    @Test @OriginalCase("PersistenceStoreTests::Save and fetch device()")
    @OriginalCase("PersistenceStoreTests::Fetch all devices()")
    @OriginalCase("PersistenceStoreTests::Set active device()")
    fun deviceRegistryPreservesFieldsAndExclusiveActivation() = runTest {
        val a = device()
        val b = device(RADIO_B)
        store.saveDevice(a)
        store.saveDevice(b)
        val fetched = assertNotNull(store.fetchDevice(a.id))
        assertEquals("TestDevice", fetched.nodeName)
        assertEquals(8.toUByte(), fetched.firmwareVersion)
        assertEquals(915_000u, fetched.frequency)
        assertEquals(2, store.fetchDevices().size)
        store.setActiveDevice(a.id)
        assertEquals(a.id, assertNotNull(store.fetchActiveDevice()).id)
        assertTrue(assertNotNull(store.fetchDevice(a.id)).isActive)
        store.setActiveDevice(b.id)
        assertEquals(b.id, assertNotNull(store.fetchActiveDevice()).id)
        assertFalse(assertNotNull(store.fetchDevice(a.id)).isActive)
    }

    @Test @OriginalCase("PersistenceStoreTests::Re-pair after device deletion re-associates orphaned data()")
    fun deletingOnlyTheRegistryRowDoesNotBreakRePair() = runTest {
        val d = device()
        store.saveDevice(d)
        store.saveContact(RADIO_A, frame(name = "Survivor"))
        store.saveChannel(RADIO_A, ChannelInfo(0u, "General", Bytes(ByteArray(16))))
        store.deleteDevice(d.id)
        store.saveDevice(d)
        assertEquals(listOf("Survivor"), store.fetchContacts(RADIO_A).map { it.name })
        assertEquals(listOf("General"), store.fetchChannels(RADIO_A).map { it.name })
    }

    @Test @OriginalCase("PersistenceStoreTests::Demote device to ghost preserves publicKey and radioID with fresh id()")
    @OriginalCase("PersistenceStoreTests::Demote device strips all connection methods so it stays hidden()")
    @OriginalCase("PersistenceStoreTests::Removing a paired device preserves child contacts via radioID()")
    fun demotionKeepsIdentityAndChildrenButRemovesAllConnectionHandles() = runTest {
        val d = device().copy(isActive = true, connectionMethods = SnapshotList.of(
            ConnectionMethod.WiFi("10.0.0.5", 5000u), ConnectionMethod.Bluetooth(UUID.randomUUID())))
        store.saveDevice(d)
        store.saveContact(RADIO_A, frame(name = "Alice"))
        store.demoteDeviceToGhost(d.id)
        assertNull(store.fetchDevice(d.id))
        val ghost = assertNotNull(store.fetchDevice(d.publicKey))
        assertNotEquals(d.id, ghost.id)
        assertEquals(d.publicKey, ghost.publicKey)
        assertEquals(d.radioId, ghost.radioId)
        assertFalse(ghost.isActive)
        assertTrue(ghost.connectionMethods.isEmpty())
        assertEquals(listOf("Alice"), store.fetchContacts(RADIO_A).map { it.name })
    }

    @Test @OriginalCase("PersistenceStoreTests::Demote device with unknown id is a no-op()")
    @OriginalCase("PersistenceStoreTests::deleteDeviceData for non-existent device does not throw()")
    @OriginalCase("PersistenceStoreTests::deleteDeviceAndData for non-existent device does not throw()")
    fun absentRegistryActionsDoNotInventRecords() = runTest {
        store.demoteDeviceToGhost(UUID.randomUUID())
        store.deleteDeviceData(UUID.randomUUID())
        store.deleteDeviceAndData(UUID.randomUUID())
        assertTrue(store.fetchDevices().isEmpty())
    }

    @Test @OriginalCase("PersistenceStoreTests::Save and fetch contact from frame()")
    @OriginalCase("PersistenceStoreTests::Fetch contact by public key()")
    @OriginalCase("PersistenceStoreTests::saveContact from frame returns isNew true then false with stable id()")
    fun frameUpsertUsesRadioAndFullKeyWithStableIdentity() = runTest {
        val f = frame(name = "Alice")
        val saved = store.saveContact(RADIO_A, f)
        assertTrue(saved.isNew)
        val first = assertNotNull(store.fetchContact(entity(id = saved.id)))
        assertEquals("Alice", first.name)
        assertEquals(ContactType.CHAT, first.type)
        assertEquals(first, store.fetchContact(RADIO_A, f.publicKey))
        val second = store.saveContact(RADIO_A, f.copy(name = "Renamed", lastAdvertTimestamp = 2u, lastModified = 2u))
        assertFalse(second.isNew)
        assertEquals(saved.id, second.id)
        assertEquals("Renamed", assertNotNull(store.fetchContact(entity(id = saved.id))).name)
    }

    @Test @OriginalCase("PersistenceStoreTests::Update contact last message and unread count()")
    fun contactActivityAndUnreadArePersistedWithoutChangingAnotherRadio() = runTest {
        store.saveContact(contact())
        store.saveContact(contact(RADIO_B))
        store.updateContactLastMessage(entity(), AT)
        store.incrementUnreadCount(entity())
        store.incrementUnreadCount(entity())
        assertEquals(2L, assertNotNull(store.fetchContact(entity())).unreadCount)
        assertEquals(AT, assertNotNull(store.fetchContact(entity())).lastMessageDate)
        assertEquals(0L, assertNotNull(store.fetchContact(entity(RADIO_B))).unreadCount)
        store.clearUnreadCount(entity())
        assertEquals(0L, assertNotNull(store.fetchContact(entity())).unreadCount)
    }

    @Test @OriginalCase("PersistenceStoreTests::recomputeContactLastMessageDate keeps a conversation visible while older messages remain and clears it only when empty()")
    fun conversationDateTracksTheNewestRemainingArrival() = runTest {
        store.saveContact(contact())
        val rows = (0..2).map { message(text = "Message $it", timestamp = 1_700_000_000u + it.toUInt(), createdAt = AT.plusSeconds(it.toLong())) }
        for (m in rows) store.saveMessage(m)
        assertEquals(rows[2].date, store.recomputeContactLastMessageDate(entity()))
        assertEquals(CONTACT_A, store.fetchConversations(RADIO_A).single().id)
        store.deleteMessage(entity(id = rows[2].id))
        assertEquals(rows[1].date, store.recomputeContactLastMessageDate(entity()))
        assertEquals(CONTACT_A, store.fetchConversations(RADIO_A).single().id)
        store.deleteMessage(entity(id = rows[1].id))
        store.deleteMessage(entity(id = rows[0].id))
        assertNull(store.recomputeContactLastMessageDate(entity()))
        assertTrue(store.fetchConversations(RADIO_A).isEmpty())
    }

    @Test @OriginalCase("PersistenceStoreTests::deleteContactIfUnreferenced skips when messages exist and deletes when none()")
    fun insertOnlyRollbackCannotWipeReferencedHistory() = runTest {
        store.saveContact(contact())
        val m = message()
        store.saveMessage(m)
        store.deleteContactIfUnreferenced(entity())
        assertNotNull(store.fetchContact(entity()))
        assertEquals(m.id, store.fetchMessages(entity()).single().id)
        store.deleteMessage(entity(id = m.id))
        store.deleteContactIfUnreferenced(entity())
        assertNull(store.fetchContact(entity()))
    }

    @Test @OriginalCase("PersistenceStoreTests::touchContactHeard bumps contact and existing discovered node()")
    @OriginalCase("PersistenceStoreTests::touchContactHeard creates a missing discovered node()")
    fun heardEvidenceUpdatesRecencyAndCreatesOnlyAKnownContactsDiscoverRow() = runTest {
        val f = frame(name = "Heard")
        store.saveContact(RADIO_A, f)
        val stamp = AT.plusSeconds(100)
        assertTrue(store.touchContactHeard(RADIO_A, f.publicKey, stamp))
        val node = store.fetchDiscoveredNodes(RADIO_A).single()
        assertEquals(stamp, node.lastHeard)
        assertEquals(f.name, node.name)
        assertEquals(f.type.rawValue, node.typeRawValue)
        assertEquals(f.lastAdvertTimestamp, node.lastAdvertTimestamp)
        assertEquals(f.latitude, node.latitude)
        assertEquals(f.longitude, node.longitude)
        assertTrue(store.touchContactHeard(RADIO_A, f.publicKey, stamp.plusSeconds(1)))
        assertEquals((1_700_000_101u), assertNotNull(store.fetchContact(RADIO_A, f.publicKey)).lastHeardTimestamp)
        assertEquals(stamp.plusSeconds(1), store.fetchDiscoveredNodes(RADIO_A).single().lastHeard)
        assertFalse(store.touchContactHeard(RADIO_A, key(0xEE), stamp))
        assertEquals(1, store.fetchDiscoveredNodes(RADIO_A).size)
    }

    @Test @OriginalCase("PersistenceStoreTests::phone clock clamp agrees between touch and backup helpers()", "shared-clamp-native-policy-not-backup-import")
    @OriginalCase("PersistenceStoreTests::updateDeviceLastContactSync stores radio-ahead watermark without phone clamp()")
    fun phoneAndRadioClockPoliciesRemainDistinct() = runTest {
        assertEquals(1_700_000_300u, RoomPersistenceStore.clampedPhoneClockTimestamp(1_702_592_000u, AT))
        assertEquals(UInt.MAX_VALUE, RoomPersistenceStore.clampedPhoneClockTimestamp(UInt.MAX_VALUE,
            java.time.Instant.ofEpochSecond(UInt.MAX_VALUE.toLong() - 100)))
        val d = device()
        store.saveDevice(d)
        store.updateDeviceLastContactSync(RADIO_A, 1_702_592_000u)
        assertEquals(1_702_592_000u, assertNotNull(store.fetchDevice(RADIO_A)).lastContactSync)
    }

    @Test @OriginalCase("PersistenceStoreTests::Set contact muted()")
    @OriginalCase("PersistenceStoreTests::Muted contacts excluded from badge count()")
    @OriginalCase("PersistenceStoreTests::Get total unread counts excludes blocked contacts()")
    @OriginalCase("PersistenceStoreTests::Get total unread counts excludes repeater contacts()")
    fun contactBadgeExclusionsDoNotDiscardStoredCounters() = runTest {
        store.saveContact(contact().copy(unreadCount = 2))
        val other = contact(id = UUID.randomUUID(), publicKey = key(2), name = "Bob").copy(unreadCount = 1)
        store.saveContact(other)
        store.setContactMuted(entity(id = other.id), true)
        assertTrue(assertNotNull(store.fetchContact(entity(id = other.id))).isMuted)
        assertEquals(2L, store.getTotalUnreadCounts(RADIO_A).contacts)
        store.setContactMuted(entity(id = other.id), false)
        assertFalse(assertNotNull(store.fetchContact(entity(id = other.id))).isMuted)
        assertEquals(3L, store.getTotalUnreadCounts(RADIO_A).contacts)
        store.saveContact(other.copy(isBlocked = true, unreadCount = 5))
        store.saveContact(contact(id = UUID.randomUUID(), publicKey = key(3)).copy(typeRawValue = ContactType.REPEATER.rawValue, unreadCount = 3))
        assertEquals(2L, store.getTotalUnreadCounts(RADIO_A).contacts)
        assertEquals(5L, assertNotNull(store.fetchContact(entity(id = other.id))).unreadCount)
    }

    @Test @OriginalCase("PersistenceStoreTests::reconcileGhostIdentity rewrites current device when ghost matches publicKey()")
    @OriginalCase("PersistenceStoreTests::reconcileGhostIdentity finds ghost even when current device's publicKey already matches()")
    @OriginalCase("PersistenceStoreTests::reconcileGhostIdentity preserves non-BLE methods from backup ghost()")
    fun ghostReconciliationWorksOnRetryAndMergesWiFiWithoutDuplicatingBLE() = runTest {
        val wifi = ConnectionMethod.WiFi("10.0.0.7", 5000u, "Backup WiFi")
        val ghost = device().copy(publicKey = key(0x11), connectionMethods = SnapshotList.of(wifi))
        val ble = ConnectionMethod.Bluetooth(UUID.randomUUID(), "Current BLE")
        val current = device(RADIO_B).copy(publicKey = ghost.publicKey, isActive = true, connectionMethods = SnapshotList.of(ble))
        store.saveDevice(ghost)
        store.saveDevice(current)
        assertEquals(RADIO_A, store.reconcileGhostIdentity(current.id, ghost.publicKey))
        val updated = assertNotNull(store.fetchDevice(current.id))
        assertEquals(RADIO_A, updated.radioId)
        assertEquals(ghost.publicKey, updated.publicKey)
        assertEquals(listOf(ble, wifi), updated.connectionMethods)
        assertEquals(1, updated.connectionMethods.count { it.isBluetooth })
        assertNull(store.fetchDevice(ghost.id))
    }

    @Test @OriginalCase("PersistenceStoreTests::reconcileGhostIdentity is a no-op when current device already owns the publicKey()")
    @OriginalCase("PersistenceStoreTests::reconcileGhostIdentity returns nil when no ghost matches()")
    @OriginalCase("PersistenceStoreTests::reconcileGhostIdentity refuses to delete a saved-but-inactive device with BLE methods()")
    fun unmatchedOrRealInactiveRegistryRowsCannotBeMistakenForGhosts() = runTest {
        val current = device().copy(isActive = true)
        store.saveDevice(current)
        assertNull(store.reconcileGhostIdentity(current.id, current.publicKey))
        assertNull(store.reconcileGhostIdentity(current.id, key(0xEE)))
        assertEquals(current, store.fetchDevice(current.id))
        val real = device(RADIO_B).copy(publicKey = key(0x11), connectionMethods = SnapshotList.of(ConnectionMethod.Bluetooth(UUID.randomUUID())))
        store.saveDevice(real)
        assertNull(store.reconcileGhostIdentity(current.id, real.publicKey))
        assertEquals(real, store.fetchDevice(real.id))
        assertEquals(current, store.fetchDevice(current.id))
    }
}
