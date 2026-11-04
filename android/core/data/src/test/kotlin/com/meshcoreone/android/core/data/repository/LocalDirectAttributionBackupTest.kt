// AndroidOnly: WP-202 Android codec/Room round-trip for unresolved attribution and occurrence retention.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.data.backup.AppBackupCodec
import com.meshcoreone.android.core.data.backup.BackupModelKind
import com.meshcoreone.android.core.data.backup.BackupRoomTest
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.Test

class LocalDirectAttributionBackupTest : BackupRoomTest() {
    @Test fun ambiguousSameContentOccurrencesSurviveShrinkExportRestoreAndAdoption() = runBlocking {
        val prefix = Bytes.of(1, 2, 3, 4, 5, 6)
        val first = contact(publicKey = Bytes(prefix.toByteArray() + ByteArray(26) { 10 }))
        val second = first.copy(id = UUID(0, 11), publicKey = Bytes(prefix.toByteArray() + ByteArray(26) { 11 }))
        store.saveDevice(device())
        store.saveContact(first)
        store.saveContact(second)
        val observed = store.classifyLocalDirectContact(RADIO_A, prefix)
        assertEquals(LocalDirectContactResolution.Ambiguous, observed)
        val original = message(contactID = null, direction = MessageDirection.INCOMING, text = "Exact original text")
            .copy(senderKeyPrefix = prefix, timestamp = 42u, senderTimestamp = 0u, timestampCorrected = true)
        val one = store.saveIncomingDirectMessage(original, observed).message
        val two = store.saveIncomingDirectMessage(original.copy(id = UUID.randomUUID()), observed).message
        store.deleteContact(EntityKey(RADIO_A, second.id))
        val bytes = service.export(store).data
        val decoded = AppBackupCodec().parseBackup(bytes)
        assertEquals(2, decoded.messages.size)
        assertEquals(setOf(one.id, two.id), decoded.messages.map { it.id }.toSet())
        assertEquals(2, decoded.messages.map { it.deduplicationKey }.toSet().size)
        withIndependentStore { _, destination ->
            val result = service.importBackup(decoded, destination)
            assertEquals(2L, result.counts.getValue(BackupModelKind.MESSAGES).inserted)
            val restoredContact = assertNotNull(destination.fetchContact(RADIO_A, first.publicKey))
            assertTrue(destination.adoptOrphanedDirectMessages(
                RADIO_A, listOf(ContactIdentity(restoredContact.id, restoredContact.publicKey)).snapshot(),
            ).isEmpty())
            for (dto in listOf(one, two)) {
                val restored = assertNotNull(destination.fetchMessage(EntityKey(RADIO_A, dto.id)))
                assertNull(restored.contactID)
                assertEquals(dto.deduplicationKey, restored.deduplicationKey)
                assertEquals(dto.text, restored.text)
                assertEquals(dto.senderKeyPrefix, restored.senderKeyPrefix)
                assertEquals(dto.senderTimestamp, restored.senderTimestamp)
            }
            val secondImport = service.importBackup(decoded, destination)
            assertEquals(0L, secondImport.counts.getValue(BackupModelKind.MESSAGES).inserted)
            assertEquals(2L, secondImport.counts.getValue(BackupModelKind.MESSAGES).skipped)
        }
    }

    @Test fun realBackupRestoreInvalidatesAnEarlierLocalObservation() = runBlocking {
        val peer = contact()
        store.saveDevice(device())
        store.saveContact(peer)
        val prefix = peer.publicKey.prefix(6)
        val observed = store.classifyLocalDirectContact(RADIO_A, prefix)
        val decoded = AppBackupCodec().parseBackup(service.export(store).data)
        service.importBackup(decoded, store)
        val result = store.saveIncomingDirectMessage(message(
            contactID = peer.id, direction = MessageDirection.INCOMING,
        ).copy(senderKeyPrefix = prefix), observed)
        assertEquals(LocalDirectContactResolution.Stale, result.resolution)
        assertNull(result.message.contactID)
    }
}
