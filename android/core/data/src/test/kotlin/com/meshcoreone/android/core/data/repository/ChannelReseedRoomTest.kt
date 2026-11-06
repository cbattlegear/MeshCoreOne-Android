// AndroidOnly: WP-209 Current-main backup UUID remapping and idempotent same-slot DTO replay for issue 601.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.data.backup.BackupRoomTest
import com.meshcoreone.android.core.data.backup.device as backupDevice
import com.meshcoreone.android.core.data.backup.envelope as backupEnvelope
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.PendingSendKind
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChannelReseedRoomTest : BackupRoomTest() {
    private fun seedChannel() =
        ChannelDTO(radioId = RADIO_A, index = 2u, name = "seed", secret = Bytes(ByteArray(16) { 1 }))

    private fun queuedChannel(text: MessageDTO) = pending(messageID = text.id).copy(
        kind = PendingSendKind.CHANNEL, contactID = null, channelIndex = text.channelIndex,
        messageText = text.text, messageTimestamp = text.timestamp,
    )

    @Test
    fun actualEmptyStoreImportThenThreeFixedDTOReplaysPreserveTheImportedUUIDAndHistory() = runTest {
        val seed = seedChannel()
        service.importBackup(backupEnvelope(
            devices = listOf(backupDevice(radio = RADIO_A)), channels = listOf(seed),
        ), store)
        val imported = store.fetchChannels(RADIO_A).single()
        assertNotEquals(seed.id, imported.id)
        val sent = message(contactID = null, channelIndex = 2u)
        val incoming = message(contactID = null, channelIndex = 2u, direction = MessageDirection.INCOMING)
        val unsent = message(contactID = null, channelIndex = 2u, status = MessageStatus.FAILED)
        listOf(sent, incoming, unsent).forEach { store.saveMessage(it) }
        val queued = queuedChannel(unsent)
        store.upsertPendingSend(queued)
        repeat(3) {
            store.saveChannel(seed)
            assertEquals(imported, store.fetchChannels(RADIO_A).single())
            assertNull(store.fetchChannel(EntityKey(RADIO_A, seed.id)))
            for (row in listOf(sent, incoming, unsent)) {
                assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
            }
            assertEquals(listOf(queued), store.fetchPendingSendsForMessage(EntityKey(RADIO_A, unsent.id)).toList())
        }
    }

    @Test
    fun changedKeyDTOReplayKeepsExistingDTOUpdateSemanticsWithoutDeletingHistoryOrAddingASlot() = runTest {
        val current = seedChannel()
        store.saveChannel(current)
        val sent = message(contactID = null, channelIndex = 2u)
        store.saveMessage(sent)
        val replay = current.copy(id = UUID.randomUUID(), name = "changed", secret = Bytes(ByteArray(16) { 2 }))
        repeat(3) { store.saveChannel(replay) }
        assertEquals(current.copy(name = replay.name, secret = replay.secret), store.fetchChannels(RADIO_A).single())
        assertNull(store.fetchChannel(EntityKey(RADIO_A, replay.id)))
        assertEquals(sent, store.fetchMessage(EntityKey(RADIO_A, sent.id)))
        store.saveChannel(RADIO_A, ChannelInfo(2u, "actual radio replacement", Bytes(ByteArray(16) { 3 })))
        assertEquals(1, store.fetchChannels(RADIO_A).size)
        assertNull(store.fetchMessage(EntityKey(RADIO_A, sent.id)))
    }

    @Test
    fun ambiguousSlotRefusesKnownAndMissingUUIDReplaysWithoutDeletingRowsOrPendingText() = runTest {
        val first = seedChannel()
        store.saveChannel(first)
        val second = first.copy(id = UUID.randomUUID(), name = "legacy duplicate")
        db.channels().insert(second.toEntity())
        val before = store.fetchChannels(RADIO_A)
        val text = message(contactID = null, channelIndex = 2u, status = MessageStatus.FAILED)
        store.saveMessage(text)
        val queued = queuedChannel(text)
        store.upsertPendingSend(queued)
        for (dto in listOf(first, second, first.copy(id = UUID.randomUUID()))) {
            val failure = assertFailsWith<PersistenceStoreException> { store.saveChannel(dto) }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
            assertEquals(before, store.fetchChannels(RADIO_A))
            assertEquals(text, store.fetchMessage(EntityKey(RADIO_A, text.id)))
            assertEquals(listOf(queued), store.fetchPendingSendsForMessage(EntityKey(RADIO_A, text.id)).toList())
        }
    }

    @Test
    fun replayUsesOnlyItsRadioPartitionAndKnownUUIDKeepsItsOriginalSlot() = runTest {
        val current = seedChannel()
        val foreign = current.copy(radioId = RADIO_B, name = "foreign", secret = Bytes(ByteArray(16) { 2 }))
        store.saveChannel(current)
        store.saveChannel(foreign)
        store.saveChannel(current.copy(id = UUID.randomUUID(), name = "replayed"))
        assertEquals(current.copy(name = "replayed"), store.fetchChannels(RADIO_A).single())
        assertEquals(foreign, store.fetchChannels(RADIO_B).single())
        store.saveChannel(current.copy(index = 7u, name = "known id metadata"))
        assertEquals(current.copy(name = "known id metadata"), store.fetchChannels(RADIO_A).single())
        assertNull(store.fetchChannel(RADIO_A, 7u))
        assertEquals(foreign, store.fetchChannels(RADIO_B).single())
    }

    @Test
    fun emptyUnoccupiedSlotStillUsesTheSuppliedDTOIdentity() = runTest {
        val seed = seedChannel()
        store.saveChannel(seed)
        assertEquals(seed, store.fetchChannels(RADIO_A).single())
    }

    @Test
    fun enableStateReplayPreservesCurrentIdentityRelatedRowsAndOtherSlots() = runTest {
        val current = seedChannel()
        val otherSlot = current.copy(id = UUID.randomUUID(), index = 3u, name = "other slot")
        store.saveChannel(current)
        store.saveChannel(otherSlot)
        val text = message(contactID = null, channelIndex = 2u, status = MessageStatus.FAILED)
        store.saveMessage(text)
        val queued = queuedChannel(text)
        store.upsertPendingSend(queued)
        for (enabled in listOf(false, true, false, true)) {
            store.saveChannel(current.copy(id = UUID.randomUUID(), isEnabled = enabled))
            assertEquals(current.copy(isEnabled = enabled), store.fetchChannel(EntityKey(RADIO_A, current.id)))
            assertEquals(otherSlot, store.fetchChannel(RADIO_A, 3u))
            assertEquals(2, store.fetchChannels(RADIO_A).size)
            assertEquals(text, store.fetchMessage(EntityKey(RADIO_A, text.id)))
            assertEquals(listOf(queued), store.fetchPendingSendsForMessage(EntityKey(RADIO_A, text.id)).toList())
        }
    }
}
