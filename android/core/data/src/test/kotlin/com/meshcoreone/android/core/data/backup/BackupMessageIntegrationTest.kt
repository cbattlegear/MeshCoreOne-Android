// PortedFrom: MC1Services/Tests/MC1ServicesTests/BackupIntegrationTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.TextRuleAdmission
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupMessageIntegrationTest : BackupRoomTest() {
    @Test fun receivedZeroTimestampsSurviveExportAndIndependentRestore() = runBlocking {
        seed(envelope(devices = listOf(device()), contacts = listOf(contact())))
        store.saveChannel(RADIO, ChannelInfo(0u, "General", channel().secret))
        val peer = requireNotNull(store.fetchContact(EntityKey(RADIO, contact().id)))
        val channel = requireNotNull(store.fetchChannel(RADIO, 0u))
        val originals = mutableListOf<MessageDTO>()
        val dates = listOf(Instant.ofEpochSecond(-1), Instant.ofEpochSecond(123), Instant.ofEpochSecond(4_294_967_296))
        for ((index, date) in dates.withIndex()) {
            val direct = message(id = id(100L + index), direction = MessageDirection.INCOMING,
                text = "direct $index", date = date).copy(
                timestamp = 0u, sortDate = date, senderKeyPrefix = contact().publicKey.prefix(6))
            val broadcast = message(id = id(200L + index), direction = MessageDirection.INCOMING,
                contactID = null, index = 0u, text = "channel $index", date = date).copy(
                timestamp = 0u, sortDate = date, senderNodeName = "Alice")
            assertEquals(TextRuleAdmission.SAVED,
                store.admitTextMessage(direct, peer, null, direct.text, 0u, false, date, false))
            assertEquals(TextRuleAdmission.SAVED,
                store.admitTextMessage(broadcast, null, channel, "Alice: ${broadcast.text}", 0u, false, date, false))
            originals += listOf(direct, broadcast)
        }

        val backup = AppBackupCodec().parseBackup(service.export(store).data)
        assertEquals(originals.map { it.id }.toSet(), backup.messages.map { it.id }.toSet())
        assertTrue(backup.messages.all { it.timestamp == 0u })
        withIndependentStore { destination, destinationStore ->
            val result = service.importBackup(backup, destinationStore)
            assertEquals(6L, result.count(BackupModelKind.MESSAGES).inserted)
            for (original in originals) {
                val restored = requireNotNull(destination.messages().byId(RADIO.value, original.id)).toDTO()
                assertEquals(original.text, restored.text)
                assertEquals(original.contactID, restored.contactID)
                assertEquals(original.channelIndex, restored.channelIndex)
                assertEquals(0u, restored.timestamp)
                assertEquals(original.createdAt, restored.createdAt)
                assertEquals(original.sortDate, restored.sortDate)
            }
            val repeated = service.importBackup(backup, destinationStore)
            assertEquals(0L, repeated.count(BackupModelKind.MESSAGES).inserted)
            assertEquals(6L, repeated.count(BackupModelKind.MESSAGES).skipped)
        }
    }

    @OriginalCase("BackupIntegrationTests::Export assigns content-based dedup keys to incoming messages with nil deduplicationKey()")
    @Test fun onlyIncomingMissingKeysAreBackfilledDuringExport() = runBlocking {
        val dm = message(direction = MessageDirection.INCOMING, text = "Pre-migration DM").copy(timestamp = 12345u)
        val ch = message(id = id(40), contactID = null, index = 2u, direction = MessageDirection.INCOMING,
            text = "Pre-migration channel msg").copy(timestamp = 67890u, senderNodeName = "Node1")
        seed(envelope(contacts = listOf(contact()), messages = listOf(dm, ch, message(id = id(41)))))
        val exported = AppBackupCodec().parseBackup(service.export(store).data).messages
        assertTrue(requireNotNull(exported.single { it.id == dm.id }.deduplicationKey).startsWith("dm-"))
        assertTrue(requireNotNull(exported.single { it.id == ch.id }.deduplicationKey).startsWith("ch-"))
        assertFalse(exported.any { it.deduplicationKey?.startsWith("backup-") == true })
        assertNull(exported.single { it.id == id(41) }.deduplicationKey)
        assertNull(db.messages().byId(RADIO.value, dm.id)?.deduplicationKey)
        assertNull(db.messages().byId(RADIO.value, ch.id)?.deduplicationKey)
    }

    @OriginalCase("BackupIntegrationTests::Export preserves existing content-based dedup keys unchanged()")
    @Test fun existingKeysAreNotNormalizedOrReplaced() = runBlocking {
        val key = "dm-${id(2).canonicalString()}-99999-AABBCCDD"
        seed(envelope(messages = listOf(message().copy(deduplicationKey = key),
            message(id = id(40), direction = MessageDirection.INCOMING).copy(deduplicationKey = key + "-incoming"))))
        val exported = AppBackupCodec().parseBackup(service.export(store).data).messages
        assertEquals(key, exported.single { it.id == id(4) }.deduplicationKey)
        assertEquals(key + "-incoming", exported.single { it.id == id(40) }.deduplicationKey)
    }

    @OriginalCase("BackupIntegrationTests::Outgoing messages with identical recipient/text/timestamp survive round-trip without deduplication()")
    @Test fun identicalIntentionalSendsRetainBothUuids() = runBlocking {
        val first = message(text = "ok").copy(timestamp = 1_700_001_234u)
        val second = first.copy(id = id(40))
        seed(envelope(devices = listOf(device()), contacts = listOf(contact()), messages = listOf(first, second)))
        val backup = AppBackupCodec().parseBackup(service.export(store).data)
        db.messages().clearRadio(RADIO.value)
        val result = service.importBackup(backup, store)
        assertEquals(2L, result.count(BackupModelKind.MESSAGES).inserted); assertEquals(0L, result.count(BackupModelKind.MESSAGES).skipped)
        val actual = service.exportEnvelope(store).messages
        assertEquals(2, actual.size); assertEquals(setOf(first.id, second.id), actual.map { it.id }.toSet())
        assertTrue(actual.all { it.deduplicationKey == null })
    }

    @OriginalCase("BackupIntegrationTests::Repeats with identical path but distinct ids survive round-trip and heardRepeats is recomputed()")
    @Test fun outgoingDistinctHearingsOfSamePathRetainBothIdentities() = runBlocking {
        seed(envelope(devices = listOf(device()), messages = listOf(message())))
        val first = repeat(path = Bytes.of(0x42)).copy(rxLogEntryID = id(100))
        val second = first.copy(id = id(50), rxLogEntryID = id(101))
        db.repeats().insert(first.toEntity(RADIO, id(4))); db.repeats().insert(second.toEntity(RADIO, id(4)))
        val backup = AppBackupCodec().parseBackup(service.export(store).data)
        db.messages().clearRadio(RADIO.value)
        val result = service.importBackup(backup, store)
        assertEquals(2L, result.count(BackupModelKind.MESSAGE_REPEATS).inserted); assertEquals(0L, result.count(BackupModelKind.MESSAGE_REPEATS).skipped)
        val actual = db.repeats().forMessage(RADIO.value, id(4))
        assertEquals(2, actual.size); assertEquals(setOf(first.id, second.id), actual.map { it.id }.toSet())
        assertEquals(2L, db.messages().byId(RADIO.value, id(4))?.heardRepeats)
    }

    @OriginalCase("BackupIntegrationTests::Skipped incoming parent with a distinct path is promoted to a MessageRepeat()")
    @Test fun differentIncomingPathPromotesExtraButKeepsLocalCanonical() = runBlocking {
        val local = incoming(path = Bytes.of(0xB2), key = "incoming-path-promote")
        seed(envelope(messages = listOf(local)))
        val backup = local.copy(id = id(40), pathNodes = Bytes.of(0xA1))
        val result = service.importBackup(envelope(messages = listOf(backup), repeats = listOf(repeat(backup.id, path = Bytes.of(0xB2)))), store)
        assertEquals(1L, result.count(BackupModelKind.MESSAGES).skipped)
        assertEquals(setOf(Bytes.of(0xA1)), db.repeats().forMessage(RADIO.value, local.id).map { it.pathNodes }.toSet())
        assertEquals(Bytes.of(0xB2), db.messages().byId(RADIO.value, local.id)?.pathNodes)
        assertEquals(1L, db.messages().byId(RADIO.value, local.id)?.heardRepeats)
    }

    @OriginalCase("BackupIntegrationTests::Incoming extra that matches an existing extra path is not inserted again()")
    @Test fun incomingExtraPathAlreadyPresentIsNotCountedTwice() = runBlocking {
        val local = incoming(path = Bytes.of(0xA1), key = "incoming-path-collapse").copy(heardRepeats = 1)
        seed(envelope(messages = listOf(local)))
        db.repeats().insert(repeat(path = Bytes.of(0xB2)).toEntity(RADIO, local.id))
        val backup = local.copy(id = id(40))
        val result = service.importBackup(envelope(messages = listOf(backup), repeats = listOf(repeat(backup.id, id(50), Bytes.of(0xB2)))), store)
        assertEquals(1L, result.count(BackupModelKind.MESSAGES).skipped)
        val actual = db.repeats().forMessage(RADIO.value, local.id)
        assertEquals(1, actual.size); assertEquals(Bytes.of(0xB2), actual.single().pathNodes)
        assertEquals(1L, db.messages().byId(RADIO.value, local.id)?.heardRepeats)
    }

    @OriginalCase("BackupIntegrationTests::Outgoing same-path repeats are not collapsed on merge import()")
    @Test fun outgoingSamePathMergePreservesFourSeparateHearings() = runBlocking {
        seed(envelope(messages = listOf(message())))
        db.repeats().insert(repeat().toEntity(RADIO, id(4))); db.repeats().insert(repeat(id = id(50)).toEntity(RADIO, id(4)))
        val result = service.importBackup(envelope(messages = listOf(message()),
            repeats = listOf(repeat(id = id(51)), repeat(id = id(52)))), store)
        assertEquals(1L, result.count(BackupModelKind.MESSAGES).skipped); assertEquals(2L, result.count(BackupModelKind.MESSAGE_REPEATS).inserted)
        assertEquals(4, db.repeats().forMessage(RADIO.value, id(4)).size)
        assertEquals(4L, db.messages().byId(RADIO.value, id(4))?.heardRepeats)
    }

    @OriginalCase("BackupIntegrationTests::Skipped incoming parent with nil pathNodes does not invent a 0-hop extra()")
    @Test fun missingForeignPathNeverInventsDirectHearing() = runBlocking {
        val local = incoming(path = Bytes.of(0xB2), key = "incoming-path-nil-foreign")
        seed(envelope(messages = listOf(local)))
        val result = service.importBackup(envelope(messages = listOf(local.copy(id = id(40), pathNodes = null))), store)
        assertEquals(1L, result.count(BackupModelKind.MESSAGES).skipped)
        assertTrue(db.repeats().forMessage(RADIO.value, local.id).isEmpty())
        assertEquals(local.pathNodes, db.messages().byId(RADIO.value, local.id)?.pathNodes)
        assertEquals(0L, db.messages().byId(RADIO.value, local.id)?.heardRepeats)
    }

    @OriginalCase("BackupIntegrationTests::Skipped incoming parent adopts a path onto a local nil column()")
    @Test fun unknownLocalPathAdoptsKnownForeignPath() = runBlocking {
        val local = incoming(path = null, key = "incoming-path-adopt").copy(pathLength = 0u)
        seed(envelope(messages = listOf(local)))
        service.importBackup(envelope(messages = listOf(local.copy(id = id(40), pathNodes = Bytes.of(0xA1), pathLength = 1u))), store)
        val actual = requireNotNull(db.messages().byId(RADIO.value, local.id))
        assertEquals(Bytes.of(0xA1), actual.pathNodes); assertEquals(1L, actual.pathLength)
        assertTrue(db.repeats().forMessage(RADIO.value, local.id).isEmpty()); assertEquals(0L, actual.heardRepeats)
    }

    @OriginalCase("BackupIntegrationTests::Skipped incoming parent adopts a 0-hop Data onto a local nil column()")
    @Test fun unknownLocalPathAdoptsExplicitEmptyDirectPath() = runBlocking {
        val local = incoming(path = null, key = "incoming-path-adopt-zero").copy(pathLength = 0u)
        seed(envelope(messages = listOf(local)))
        service.importBackup(envelope(messages = listOf(local.copy(id = id(40), pathNodes = Bytes.EMPTY))), store)
        val actual = requireNotNull(db.messages().byId(RADIO.value, local.id))
        assertEquals(Bytes.EMPTY, actual.pathNodes); assertEquals(0L, actual.pathLength)
        assertTrue(db.repeats().forMessage(RADIO.value, local.id).isEmpty()); assertEquals(0L, actual.heardRepeats)
    }

    @OriginalCase("BackupIntegrationTests::Local 0-hop is not overwritten when the skipped parent has a hop list()")
    @Test fun explicitLocalDirectPathIsNotAnUnknownColumn() = runBlocking {
        val local = incoming(path = Bytes.EMPTY, key = "incoming-path-keep-zero").copy(pathLength = 0u)
        seed(envelope(messages = listOf(local)))
        service.importBackup(envelope(messages = listOf(local.copy(id = id(40), pathNodes = Bytes.of(0xA1), pathLength = 1u))), store)
        val actual = requireNotNull(db.messages().byId(RADIO.value, local.id))
        assertEquals(Bytes.EMPTY, actual.pathNodes); assertEquals(0L, actual.pathLength)
        assertEquals(listOf(Bytes.of(0xA1)), db.repeats().forMessage(RADIO.value, local.id).map { it.pathNodes })
        assertEquals(1L, actual.heardRepeats)
    }

    private fun incoming(path: Bytes?, key: String): MessageDTO =
        message(contactID = null, index = 0u, direction = MessageDirection.INCOMING, text = "flood")
            .copy(pathNodes = path, pathLength = 1u, senderNodeName = "Alice", deduplicationKey = key)
}
