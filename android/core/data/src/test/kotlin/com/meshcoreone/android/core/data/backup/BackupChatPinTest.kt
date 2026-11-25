// AndroidOnly: WP-203 Current-upstream chat pin coding, merge and persistence through the real streaming codec and Room.
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.toDTO
import com.meshcoreone.android.core.model.snapshot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class BackupChatPinTest : BackupRoomTest() {
    private val codec = AppBackupCodec()

    @Test fun modernPinsOverrideLegacyFavoritesIncludingExplicitFalseAndNullFallback() {
        val source = envelope(contacts = listOf(contact().copy(isFavorite = true)),
            channels = listOf(channel().copy(isFavorite = true)), sessions = listOf(session().copy(isFavorite = true)))
        for (pin in listOf(true, false, null)) {
            var json = source.jsonObject()
            for (family in listOf("contacts", "channels", "remoteNodeSessions")) {
                json = json.modifyingRow(family) { it.replacing("isPinned", pin?.let(::JsonPrimitive)) }
            }
            val decoded = codec.parseBackup(json.compressed())
            assertTrue(decoded.contacts.single().isFavorite)
            assertEquals(pin ?: true, decoded.contacts.single().isPinned)
            assertEquals(pin ?: true, decoded.channels.single().isPinned)
            assertEquals(pin ?: true, decoded.remoteNodeSessions.single().isPinned)
        }
        var modern = source.jsonObject()
        for (family in listOf("channels", "remoteNodeSessions")) {
            modern = modern.modifyingRow(family) { it.replacing("isFavorite", null) }
        }
        val decoded = codec.parseBackup(modern.compressed())
        assertTrue(decoded.channels.single().isPinned)
        assertTrue(decoded.remoteNodeSessions.single().isPinned)
        assertThrows(com.meshcoreone.android.core.model.AppBackupException::class.java) {
            codec.parseBackup(modern.modifyingRow("contacts") {
                it.replacing("isPinned", JsonPrimitive("true"))
            }.compressed())
        }
    }

    @Test fun localPinsSurviveStaleSavesRadioFavoriteWritesAndRoundTripWithoutChangingFlags() = runBlocking<Unit> {
        val contact = contact().copy(flags = 1u, isFavorite = true)
        val channel = channel()
        val room = session()
        seed(envelope(devices = listOf(device()), contacts = listOf(contact), channels = listOf(channel), sessions = listOf(room)))
        store.setContactPinned(contact, true)
        store.setChannelPinned(channel, true)
        store.setRoomPinned(room, true)
        store.saveContact(contact)
        store.saveChannel(channel)
        store.saveRemoteNodeSessionDTO(room)
        store.updateContactFavorite(EntityKey(contact.radioId, contact.id), contact.publicKey, false)
        val stored = requireNotNull(store.fetchContact(EntityKey(contact.radioId, contact.id)))
        assertTrue(stored.isPinned)
        assertFalse(stored.isFavorite)
        assertEquals(0.toUByte(), stored.flags)
        val exported = codec.parseBackup(service.export(store).data)
        assertTrue(exported.contacts.single().isPinned)
        assertFalse(exported.contacts.single().isFavorite)
        assertTrue(exported.channels.single().isPinned)
        assertTrue(exported.remoteNodeSessions.single().isPinned)
        store.setContactPinned(stored, false)
        assertFalse(requireNotNull(store.fetchContact(EntityKey(contact.radioId, contact.id))).isPinned)
        assertThrows(PersistenceStoreException::class.java) {
            runBlocking { store.setContactPinned(contact.copy(publicKey = key(0xFF)), true) }
        }
        assertThrows(PersistenceStoreException::class.java) {
            runBlocking { store.setChannelPinned(channel.copy(secret = key(0xFF)), true) }
        }
    }

    @Test fun restoreOrMergesPinsIndependentlyOfRadioFavoritesWithRemappedParents() = runBlocking<Unit> {
        val local = device(OTHER_RADIO, device().publicKey, id(90))
        seed(envelope(devices = listOf(local),
            contacts = listOf(contact(OTHER_RADIO).copy(isFavorite = true)),
            channels = listOf(channel(OTHER_RADIO)), sessions = listOf(session(OTHER_RADIO))))
        val imported = envelope(devices = listOf(device()),
            contacts = listOf(contact().copy(isPinned = true)),
            channels = listOf(channel().copy(isFavorite = true)), sessions = listOf(session().copy(isFavorite = true)))
        service.importBackup(codec.parseBackup(codec.encode(imported)), store)
        val merged = requireNotNull(db.contacts().byId(OTHER_RADIO.value, id(2))).toDTO()
        assertTrue(merged.isFavorite)
        assertTrue(merged.isPinned)
        assertTrue(requireNotNull(db.channels().byId(OTHER_RADIO.value, id(3))).toDTO().isPinned)
        assertTrue(requireNotNull(db.sessions().byId(OTHER_RADIO.value, id(8))).toDTO().isPinned)
        service.importBackup(imported.copy(
            contacts = imported.contacts.map { it.copy(isPinned = false) }.snapshot(),
            channels = imported.channels.map { it.copy(isFavorite = false) }.snapshot(),
            remoteNodeSessions = imported.remoteNodeSessions.map { it.copy(isFavorite = false) }.snapshot(),
        ), store)
        assertTrue(requireNotNull(db.contacts().byId(OTHER_RADIO.value, id(2))).isPinned)
        assertTrue(requireNotNull(db.channels().byId(OTHER_RADIO.value, id(3))).isFavorite)
        assertTrue(requireNotNull(db.sessions().byId(OTHER_RADIO.value, id(8))).isFavorite)
    }
}
