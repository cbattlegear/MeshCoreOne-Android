// AndroidOnly: WP-209 Current-main backup UUID remapping and idempotent same-slot DTO replay for issue 601.
package com.meshcoreone.android.core.data.repository

import androidx.room.withTransaction
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.data.backup.BackupRoomTest
import com.meshcoreone.android.core.data.backup.BackupImportHooks
import com.meshcoreone.android.core.data.backup.device as backupDevice
import com.meshcoreone.android.core.data.backup.envelope as backupEnvelope
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.AppBackupError
import com.meshcoreone.android.core.model.AppBackupException
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.PendingSendKind
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.io.IOException
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
        val customized = seed.copy(
            name = "Restored custom name", isFavorite = true, notificationLevel = NotificationLevel.MENTIONS_ONLY,
            unreadCount = 8, unreadMentionCount = 3, lastMessageDate = AT,
            floodScopeModeRawValue = "specific", regionScope = "restored-region",
        )
        service.importBackup(backupEnvelope(
            devices = listOf(backupDevice(radio = RADIO_A)), channels = listOf(customized),
        ), store)
        val imported = store.fetchChannels(RADIO_A).single()
        assertNotEquals(seed.id, imported.id)
        assertEquals(customized.copy(id = imported.id), imported)
        val importedAuthority = assertNotNull(db.channelOccupantAuthorities().forSlot(RADIO_A.value, 2L))
        assertFalse(importedAuthority.isResolved)
        assertNull(importedAuthority.boundRowId)
        assertNull(store.fetchChannelOccupantEpoch(RADIO_A, 2u))
        val sent = message(contactID = null, channelIndex = 2u)
        val incoming = message(contactID = null, channelIndex = 2u, direction = MessageDirection.INCOMING)
        val unsent = message(contactID = null, channelIndex = 2u, status = MessageStatus.FAILED)
        listOf(sent, incoming, unsent).forEach { store.saveMessage(it) }
        val queued = queuedChannel(unsent)
        store.upsertPendingSend(queued)
        repeat(3) {
            store.saveChannel(seed)
            assertEquals(imported, store.fetchChannels(RADIO_A).single())
            assertEquals(importedAuthority, db.channelOccupantAuthorities().forSlot(RADIO_A.value, 2L))
            assertNull(store.fetchChannelOccupantEpoch(RADIO_A, 2u))
            assertNull(store.fetchChannel(EntityKey(RADIO_A, seed.id)))
            for (row in listOf(sent, incoming, unsent)) {
                assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
            }
            assertEquals(listOf(queued), store.fetchPendingSendsForMessage(EntityKey(RADIO_A, unsent.id)).toList())
        }
    }

    @Test
    fun missingUUIDChangedKeyReplayFailsWithoutChangingCurrentIdentityHistoryOrPendingText() = runTest {
        val current = seedChannel()
        store.saveChannel(current)
        val sent = message(contactID = null, channelIndex = 2u, status = MessageStatus.FAILED)
        store.saveMessage(sent)
        val queued = queuedChannel(sent)
        store.upsertPendingSend(queued)
        val replay = current.copy(id = UUID.randomUUID(), name = "changed", secret = Bytes(ByteArray(16) { 2 }))
        repeat(3) {
            val failure = assertFailsWith<PersistenceStoreException> { store.saveChannel(replay) }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
        }
        assertEquals(current, store.fetchChannels(RADIO_A).single())
        assertNull(store.fetchChannel(EntityKey(RADIO_A, replay.id)))
        assertEquals(sent, store.fetchMessage(EntityKey(RADIO_A, sent.id)))
        assertEquals(listOf(queued), store.fetchPendingSendsForMessage(EntityKey(RADIO_A, sent.id)).toList())
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
        assertEquals(current, store.fetchChannels(RADIO_A).single())
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
    fun matchingUnknownUUIDReplayIsATrueNoOpEvenWhenSQLiteRejectsChannelWrites() = runTest {
        val current = seedChannel().copy(isFavorite = true, unreadCount = 5)
        store.saveChannel(current)
        for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_channel_$operation BEFORE $operation ON channels " +
                    "BEGIN SELECT RAISE(ABORT, 'unexpected channel mutation'); END",
            )
        }
        repeat(3) { store.saveChannel(seedChannel()) }
        assertEquals(current, store.fetchChannels(RADIO_A).single())
        val failure = assertFailsWith<PersistenceStoreException> {
            store.saveChannel(current.copy(name = "explicit known-ID update"))
        }
        assertIs<PersistenceStoreError.SaveFailed>(failure.error)
        assertEquals(current, store.fetchChannels(RADIO_A).single())
    }

    @Test
    fun missingUUIDEnableMismatchFailsButKnownUUIDRetainsItsExplicitUpdateSemantics() = runTest {
        val current = seedChannel()
        val otherSlot = current.copy(id = UUID.randomUUID(), index = 3u, name = "other slot")
        store.saveChannel(current)
        store.saveChannel(otherSlot)
        val text = message(contactID = null, channelIndex = 2u, status = MessageStatus.FAILED)
        store.saveMessage(text)
        val queued = queuedChannel(text)
        store.upsertPendingSend(queued)
        for (enabled in listOf(false, true, false, true)) {
            val before = assertNotNull(store.fetchChannel(EntityKey(RADIO_A, current.id)))
            val mismatch = before.copy(id = UUID.randomUUID(), isEnabled = !before.isEnabled)
            val failure = assertFailsWith<PersistenceStoreException> { store.saveChannel(mismatch) }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
            assertEquals(before, store.fetchChannel(EntityKey(RADIO_A, current.id)))
            store.saveChannel(before.copy(id = UUID.randomUUID(), name = "same occupant"))
            store.saveChannel(current.copy(isEnabled = enabled))
            assertEquals(current.copy(isEnabled = enabled), store.fetchChannel(EntityKey(RADIO_A, current.id)))
            assertEquals(otherSlot, store.fetchChannel(RADIO_A, 3u))
            assertEquals(2, store.fetchChannels(RADIO_A).size)
            assertEquals(text, store.fetchMessage(EntityKey(RADIO_A, text.id)))
            assertEquals(listOf(queued), store.fetchPendingSendsForMessage(EntityKey(RADIO_A, text.id)).toList())
        }
        val updated = current.copy(secret = Bytes(ByteArray(16) { 4 }), isEnabled = false)
        store.saveChannel(updated)
        assertEquals(updated, store.fetchChannel(EntityKey(RADIO_A, current.id)))
        assertEquals(text, store.fetchMessage(EntityKey(RADIO_A, text.id)))
        assertEquals(listOf(queued), store.fetchPendingSendsForMessage(EntityKey(RADIO_A, text.id)).toList())
    }

    @Test
    fun realBackupImportRollsBackStorageEpochOnFailureAndInvalidatesItOnCommit() = runTest {
        val local = seedChannel().copy(name = "local custom", isFavorite = true, unreadCount = 4)
        store.saveDevice(backupDevice(radio = RADIO_A))
        store.saveChannel(local)
        // Explicit DAO fixture tests storage/restore only, not a live lifecycle authority issuer.
        val resolved = db.withTransaction {
            val initial = assertNotNull(db.channelOccupantAuthorities().forSlot(RADIO_A.value, 2L))
            assertEquals(1, db.channelOccupantAuthorities().resolveCurrent(
                RADIO_A.value, 2L, initial.epoch, local.id, local.secret,
            ))
            assertNotNull(db.channelOccupantAuthorities().forSlot(RADIO_A.value, 2L))
        }
        val text = message(contactID = null, channelIndex = 2u, status = MessageStatus.FAILED)
        val queued = queuedChannel(text)
        store.saveMessage(text)
        store.upsertPendingSend(queued)
        val value = backupEnvelope(
            devices = listOf(backupDevice(radio = RADIO_A)),
            channels = listOf(local.copy(id = UUID.randomUUID(), name = "imported metadata")),
        )
        val faulted = newService(BackupImportHooks(beforeCommit = { throw IOException("injected epoch rollback") }))
        val failure = assertFailsWith<AppBackupException> { faulted.importBackup(value, store) }
        assertIs<AppBackupError.ImportFailed>(failure.error)
        assertEquals(local, store.fetchChannel(EntityKey(RADIO_A, local.id)))
        assertEquals(resolved, db.channelOccupantAuthorities().forSlot(RADIO_A.value, 2L))
        assertEquals(resolved.epoch, store.fetchChannelOccupantEpoch(RADIO_A, 2u))
        assertEquals(text, store.fetchMessage(EntityKey(RADIO_A, text.id)))
        assertEquals(listOf(queued), store.fetchPendingSendsForMessage(EntityKey(RADIO_A, text.id)).toList())

        service.importBackup(value, store)
        val invalidated = assertNotNull(db.channelOccupantAuthorities().forSlot(RADIO_A.value, 2L))
        assertNotEquals(resolved.epoch, invalidated.epoch)
        assertFalse(invalidated.isResolved)
        assertNull(invalidated.boundRowId)
        assertNull(store.fetchChannelOccupantEpoch(RADIO_A, 2u))
        assertEquals(text, store.fetchMessage(EntityKey(RADIO_A, text.id)))
        assertEquals(listOf(queued), store.fetchPendingSendsForMessage(EntityKey(RADIO_A, text.id)).toList())
        val imported = store.fetchChannels(RADIO_A).single()
        repeat(3) {
            store.saveChannel(seedChannel())
            assertEquals(imported, store.fetchChannels(RADIO_A).single())
            assertEquals(invalidated, db.channelOccupantAuthorities().forSlot(RADIO_A.value, 2L))
            assertNull(store.fetchChannelOccupantEpoch(RADIO_A, 2u))
        }
    }
}
