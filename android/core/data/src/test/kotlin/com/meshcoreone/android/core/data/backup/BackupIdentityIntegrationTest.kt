// PortedFrom: MC1Services/Tests/MC1ServicesTests/BackupIntegrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.ContactIdentity
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupIdentityIntegrationTest : BackupRoomTest() {
    private val codec = AppBackupCodec()

    @OriginalCase("BackupIntegrationTests::Full round-trip: export then import into fresh store restores all data()")
    @Test fun fullNativePipelineRestoresActualFieldsAndParents() = runBlocking {
        seed(envelope(devices = listOf(device()), contacts = listOf(contact()), channels = listOf(channel()),
            messages = listOf(message().copy(deduplicationKey = "integration-dedup"))))
        db.reactions().insert(reaction().toEntity())
        val exported = codec.parseBackup(service.export(store).data)
        assertTrue(exported.manifest.validate(exported))
        store.deleteMessage(EntityKey(RADIO, id(4)))
        db.contacts().clearRadio(RADIO.value); db.channels().clearRadio(RADIO.value); db.devices().delete(id(1))
        val result = service.importBackup(exported, store)
        for (kind in listOf(BackupModelKind.DEVICES, BackupModelKind.CONTACTS, BackupModelKind.CHANNELS,
            BackupModelKind.MESSAGES, BackupModelKind.REACTIONS)) assertEquals(1L, result.count(kind).inserted)
        assertEquals(0L, result.totalSkipped)
        assertEquals("Alice", db.contacts().forRadio(RADIO.value).single().name)
        assertEquals("General", db.channels().forRadio(RADIO.value).single().name)
        assertEquals("Hello", db.messages().byId(RADIO.value, id(4))?.text)
        val restoredReaction = db.reactions().backupAll().single()
        assertNotNull(db.messages().byId(restoredReaction.radioId, restoredReaction.messageID))
    }

    @OriginalCase("BackupIntegrationTests::Cross-bundle: child records are remapped to local radioID on publicKey match()")
    @Test fun crossBundleContactUsesLocalIdentity() = runBlocking {
        val sourceDevice = device(publicKey = key(0xCC))
        val sourceContact = contact(publicKey = key(0xDD)).copy(name = "Bob")
        seed(envelope(devices = listOf(sourceDevice), contacts = listOf(sourceContact)))
        val backup = codec.parseBackup(service.export(store).data)
        withIndependentStore { destination, destinationStore ->
            assertNotSame(db, destination)
            val localDevice = device(OTHER_RADIO, key(0xCC), id(90))
            destination.devices().insert(localDevice.toEntity())
            assertTrue(destination.contacts().backupAll().isEmpty())
            val result = service.importBackup(backup, destinationStore)
            assertEquals(0L, result.count(BackupModelKind.DEVICES).inserted)
            assertEquals(1L, result.count(BackupModelKind.CONTACTS).inserted)
            assertEquals(sourceContact.copy(radioId = OTHER_RADIO), destination.contacts().forRadio(OTHER_RADIO.value).single().toDTO())
            assertTrue(destination.contacts().forRadio(RADIO.value).isEmpty())
            assertEquals(localDevice.id, destination.devices().all().single().id)
            assertEquals(OTHER_RADIO.value, destination.devices().all().single().radioId)
            assertEquals(sourceDevice, db.devices().all().single().toDTO())
            assertEquals(sourceContact, db.contacts().forRadio(RADIO.value).single().toDTO())
            assertTrue(db.contacts().forRadio(OTHER_RADIO.value).isEmpty())
        }
    }

    @Test fun collidingIncomingMessagesAndTheirChildrenSurviveExportAndRestore() = runBlocking {
        val firstBody = "room-message-23870"
        val secondBody = "room-message-35318"
        val stamp = 1_700_000_000u
        val prefix = contact().publicKey.prefix(6)
        val firstKey = DeduplicationKey.contentBased(id(2), null, null, stamp, firstBody)
        val secondKey = DeduplicationKey.contentBased(id(2), null, null, stamp, secondBody)
        assertEquals(firstKey, secondKey)
        val first = message(direction = MessageDirection.INCOMING, text = firstBody).copy(
            senderKeyPrefix = prefix, deduplicationKey = firstKey)
        val second = first.copy(id = id(40), text = secondBody, deduplicationKey = secondKey)
        val otherType = first.copy(id = id(43), textType = TextType.SIGNED_PLAIN)
        val otherStamp = first.copy(id = id(44), timestampCorrected = true, senderTimestamp = stamp + 1u)
        val orphanKey = DeduplicationKey.contentBased(null, null, null, stamp, "same text")
        val orphan = message(id = id(41), contactID = null, direction = MessageDirection.INCOMING, text = "same text")
            .copy(senderKeyPrefix = key(0xB1).prefix(6), deduplicationKey = orphanKey)
        val otherOrphan = orphan.copy(id = id(42), senderKeyPrefix = key(0xB2).prefix(6))
        val unknownSender = orphan.copy(id = id(45), senderKeyPrefix = null)
        val anotherUnknownSender = unknownSender.copy(id = id(46))
        val unknownStamp = first.copy(id = id(47), timestampCorrected = true, senderTimestamp = null)
        val anotherUnknownStamp = unknownStamp.copy(id = id(48))
        val author = Bytes.of(1, 2, 3, 4)
        val firstRoomKey = RoomMessageDTO.generateDeduplicationKey(stamp, author, firstBody)
        val secondRoomKey = RoomMessageDTO.generateDeduplicationKey(stamp, author, secondBody)
        assertEquals(firstRoomKey, secondRoomKey)
        val firstRoom = roomMessage().copy(text = firstBody, timestamp = stamp, deduplicationKey = firstRoomKey)
        val secondRoom = firstRoom.copy(id = id(70), text = secondBody, deduplicationKey = secondRoomKey)
        val otherAuthor = firstRoom.copy(id = id(71), authorKeyPrefix = Bytes.of(5, 6, 7, 8))
        val otherRoomStamp = firstRoom.copy(id = id(72), timestamp = stamp + 1u)
        seed(envelope(devices = listOf(device()), contacts = listOf(contact()), sessions = listOf(session()),
            messages = listOf(first, second, otherType, otherStamp, orphan, otherOrphan,
                unknownSender, anotherUnknownSender, unknownStamp, anotherUnknownStamp)))
        for (row in listOf(firstRoom, secondRoom, otherAuthor, otherRoomStamp)) db.roomMessages().insert(row.toEntity(RADIO))
        db.repeats().insert(repeat(first.id, id(50), Bytes.of(0x11)).toEntity(RADIO, first.id))
        db.repeats().insert(repeat(second.id, id(51), Bytes.of(0x22)).toEntity(RADIO, second.id))
        db.reactions().insert(reaction(messageID = first.id, id = id(60)).copy(emoji = "A").toEntity())
        db.reactions().insert(reaction(messageID = second.id, id = id(61)).copy(emoji = "B").toEntity())
        val backup = codec.parseBackup(service.export(store).data)

        suspend fun assertRestored(destination: MeshCoreDatabase, firstID: java.util.UUID) {
            val rows = destination.messages().backupPageWithoutPreviewBlobs(RADIO.value, 100, 0)
            assertEquals(10, rows.size)
            assertEquals(firstBody, destination.messages().byId(RADIO.value, firstID)?.text)
            assertEquals(secondBody, destination.messages().byId(RADIO.value, second.id)?.text)
            assertEquals(setOf(orphan.senderKeyPrefix, otherOrphan.senderKeyPrefix),
                rows.filter { it.contactID == null }.mapNotNull { it.senderKeyPrefix }.toSet())
            assertTrue(listOf(unknownSender.id, anotherUnknownSender.id, unknownStamp.id, anotherUnknownStamp.id)
                .all { id -> rows.any { it.id == id } })
            assertEquals(TextType.SIGNED_PLAIN, destination.messages().byId(RADIO.value, otherType.id)?.toDTO()?.textType)
            assertEquals(stamp + 1u, destination.messages().byId(RADIO.value, otherStamp.id)?.toDTO()?.senderTimestamp)
            assertEquals(Bytes.of(0x11), destination.repeats().forMessage(RADIO.value, firstID).single().pathNodes)
            assertEquals(Bytes.of(0x22), destination.repeats().forMessage(RADIO.value, second.id).single().pathNodes)
            assertEquals("A", destination.reactions().forMessage(RADIO.value, firstID, -1).single().emoji)
            assertEquals("B", destination.reactions().forMessage(RADIO.value, second.id, -1).single().emoji)
            assertEquals(listOf(firstRoom, secondRoom, otherAuthor, otherRoomStamp)
                .map { Triple(it.text, it.authorKeyPrefix, it.timestamp) }.toSet(),
                destination.roomMessages().forSession(RADIO.value, session().id)
                    .map { Triple(it.text, it.authorKeyPrefix, it.timestamp.toUInt()) }.toSet())
        }

        withIndependentStore { destination, destinationStore ->
            val result = service.importBackup(backup, destinationStore)
            assertEquals(10L, result.count(BackupModelKind.MESSAGES).inserted)
            assertEquals(4L, result.count(BackupModelKind.ROOM_MESSAGES).inserted)
            assertRestored(destination, first.id)
            assertEquals(0L, service.importBackup(backup, destinationStore).totalInserted)
            assertRestored(destination, first.id)
        }
        withIndependentStore { destination, destinationStore ->
            val localID = id(90)
            destination.devices().insert(device().toEntity())
            destination.contacts().insert(contact().toEntity())
            destination.sessions().insert(session().toEntity())
            destination.messages().insert(first.copy(id = localID, timestamp = stamp + 20u,
                timestampCorrected = true, senderTimestamp = stamp).toEntity())
            destination.roomMessages().insert(firstRoom.copy(id = id(91)).toEntity(RADIO))
            val result = service.importBackup(backup, destinationStore)
            assertEquals(9L, result.count(BackupModelKind.MESSAGES).inserted)
            assertEquals(1L, result.count(BackupModelKind.MESSAGES).skipped)
            assertEquals(3L, result.count(BackupModelKind.ROOM_MESSAGES).inserted)
            assertEquals(1L, result.count(BackupModelKind.ROOM_MESSAGES).skipped)
            assertRestored(destination, localID)
        }
    }

    @OriginalCase("BackupIntegrationTests::Import repeats onto existing message sets relationship and enables cascade delete()")
    @Test fun mergedParentRepeatUsesRealForeignKeyCascade() = runBlocking {
        val local = message(direction = MessageDirection.INCOMING).copy(deduplicationKey = "existing-msg-key")
        seed(envelope(devices = listOf(device()), contacts = listOf(contact()), messages = listOf(local)))
        val backup = local.copy(id = id(40))
        val result = service.importBackup(envelope(devices = listOf(device()), contacts = listOf(contact()),
            messages = listOf(backup), repeats = listOf(repeat(messageID = backup.id))), store)
        assertEquals(1L, result.count(BackupModelKind.MESSAGES).skipped)
        assertEquals(1L, result.count(BackupModelKind.MESSAGE_REPEATS).inserted)
        val child = db.repeats().forMessage(RADIO.value, local.id).single()
        assertEquals(local.id, child.parentMessageID)
        store.deleteMessage(EntityKey(RADIO, local.id))
        assertTrue(db.repeats().forMessage(RADIO.value, local.id).isEmpty())
    }

    @OriginalCase("BackupIntegrationTests::Fresh-store import sets MessageRepeat relationship and cascade deletes work()")
    @Test fun freshParentRepeatUsesRealForeignKeyCascade() = runBlocking {
        val result = service.importBackup(envelope(devices = listOf(device()), messages = listOf(message()),
            repeats = listOf(repeat(path = Bytes.of(0x42)))), store)
        assertEquals(1L, result.count(BackupModelKind.MESSAGES).inserted)
        assertEquals(1L, result.count(BackupModelKind.MESSAGE_REPEATS).inserted)
        assertEquals(id(4), db.repeats().forMessage(RADIO.value, id(4)).single().parentMessageID)
        store.deleteMessage(EntityKey(RADIO, id(4)))
        assertTrue(db.repeats().backupAll().isEmpty())
    }

    @OriginalCase("BackupIntegrationTests::Export preserves orphaned radio-scoped data after device-only delete()")
    @Test fun orphanRadioHistoryIsNotLostDuringExport() = runBlocking {
        service.importBackup(fullEnvelope(), store)
        val oldDevice = db.devices().all().single()
        db.devices().delete(oldDevice.id)
        val exported = codec.parseBackup(service.export(store).data)
        assertTrue(exported.devices.isEmpty())
        for (kind in BackupModelKind.entries.filter { it != BackupModelKind.DEVICES }) assertEquals(1L, exported.manifest.count(kind))
        db.contacts().clearRadio(RADIO.value); db.channels().clearRadio(RADIO.value)
        store.deleteMessage(EntityKey(RADIO, id(4)))
        db.sessions().clearRadio(RADIO.value); db.roomMessages().clearRadio(RADIO.value)
        db.tracePaths().clearRadio(RADIO.value); db.traceRuns().clearRadio(RADIO.value); db.blockedSenders().clearRadio(RADIO.value)
        val result = service.importBackup(exported, store)
        assertEquals(0L, result.count(BackupModelKind.DEVICES).inserted)
        for (kind in listOf(BackupModelKind.CONTACTS, BackupModelKind.CHANNELS, BackupModelKind.MESSAGES,
            BackupModelKind.REMOTE_NODE_SESSIONS, BackupModelKind.ROOM_MESSAGES, BackupModelKind.SAVED_TRACE_PATHS,
            BackupModelKind.BLOCKED_CHANNEL_SENDERS)) assertEquals(1L, result.count(kind).inserted)
        assertEquals(0L, service.importBackup(exported, store).totalInserted)
    }

    @OriginalCase("BackupIntegrationTests::Orphan DM survives export import and adoption under reminted device id()")
    @Test fun orphanDmCanBeAdoptedAfterCrossRadioRestore() = runBlocking {
        val source = message(contactID = null, direction = MessageDirection.INCOMING, text = "pre-contact dm").copy(
            timestamp = 1_700_000_700u, createdAt = AT.plusSeconds(700), sortDate = AT.plusSeconds(700),
            status = MessageStatus.DELIVERED, senderKeyPrefix = key(0xAD).prefix(6))
        seed(envelope(devices = listOf(device()), messages = listOf(source)))
        val sourceContact = contact(publicKey = key(0xAD)).copy(name = "Adopted")
        db.contacts().insert(sourceContact.toEntity())
        val backup = codec.parseBackup(service.export(store).data)
        assertNull(backup.messages.single().contactID)
        withIndependentStore { destination, destinationStore ->
            assertNotSame(db, destination)
            val localDevice = device(OTHER_RADIO, id = id(90))
            destination.devices().insert(localDevice.toEntity())
            assertTrue(destination.messages().backupRadioIds().isEmpty())
            val result = service.importBackup(backup, destinationStore)
            assertEquals(0L, result.count(BackupModelKind.DEVICES).inserted)
            assertEquals(1L, result.count(BackupModelKind.CONTACTS).inserted)
            assertEquals(1L, result.count(BackupModelKind.MESSAGES).inserted)
            val parent = destination.contacts().forRadio(OTHER_RADIO.value).single().toDTO()
            val identities = SnapshotList.of(ContactIdentity(parent.id, parent.publicKey))
            val adopted = destinationStore.adoptOrphanedDirectMessages(OTHER_RADIO, identities)
            assertTrue(adopted.isEmpty() || adopted[parent.id] == 1L)
            assertTrue(destinationStore.adoptOrphanedDirectMessages(OTHER_RADIO, identities).isEmpty())
            val row = requireNotNull(destination.messages().byId(OTHER_RADIO.value, source.id)).toDTO()
            assertEquals(OTHER_RADIO, row.radioId)
            assertEquals(parent.id, row.contactID)
            assertEquals("pre-contact dm", row.text)
            assertEquals(1_700_000_700u, row.timestamp)
            assertEquals(source.createdAt, row.createdAt)
            assertEquals(source.sortDate, row.sortDate)
            assertEquals(MessageDirection.INCOMING, row.direction)
            assertEquals(MessageStatus.DELIVERED, row.status)
            assertEquals(key(0xAD).prefix(6), row.senderKeyPrefix)
            assertEquals(source.id, destination.messages().newestForContact(OTHER_RADIO.value, parent.id, 10).single().id)
            assertEquals(sourceContact.publicKey, parent.publicKey)
            assertEquals(localDevice.id, destination.devices().all().single().id)
            assertEquals(listOf(OTHER_RADIO.value), destination.messages().backupRadioIds())
            assertTrue(destination.contacts().forRadio(RADIO.value).isEmpty())
            assertEquals(source, db.messages().byId(RADIO.value, source.id)?.toDTO())
            assertEquals(sourceContact, db.contacts().forRadio(RADIO.value).single().toDTO())
        }
    }

    @OriginalCase("BackupIntegrationTests::Duplicate device public keys in one envelope skip the loser and remap its children()")
    @Test fun duplicateDeviceKeyFirstWinsAndChildrenConverge() = runBlocking {
        val value = envelope(devices = listOf(device(publicKey = key(0x42)),
            device(OTHER_RADIO, key(0x42), id(90))),
            contacts = listOf(contact(publicKey = key(1)), contact(OTHER_RADIO, key(2), id(20))))
        val result = service.importBackup(value, store)
        assertEquals(1L, result.count(BackupModelKind.DEVICES).skipped)
        assertEquals(1L, result.count(BackupModelKind.DEVICES).inserted)
        assertEquals(2, db.contacts().forRadio(RADIO.value).size)
        assertTrue(db.contacts().forRadio(OTHER_RADIO.value).isEmpty())
        assertEquals(RADIO.value, db.devices().all().single().radioId)
    }

    @OriginalCase("BackupIntegrationTests::Duplicate messages within one envelope: children of the skipped duplicate link to the inserted message()")
    @Test fun contentDuplicateChildrenRemapToWinningParent() = runBlocking {
        val first = message(direction = MessageDirection.INCOMING, text = "Duplicate in envelope").copy(timestamp = 1_700_000_500u)
        val second = first.copy(id = id(40))
        val result = service.importBackup(envelope(devices = listOf(device()), contacts = listOf(contact()),
            messages = listOf(first, second), repeats = listOf(repeat(messageID = second.id, path = Bytes.of(0x11))),
            reactions = listOf(reaction(messageID = second.id).copy(emoji = "\uD83C\uDF36\uFE0F", senderName = "Dup"))), store)
        assertEquals(1L, result.count(BackupModelKind.MESSAGES).inserted); assertEquals(1L, result.count(BackupModelKind.MESSAGES).skipped)
        assertEquals(1L, result.count(BackupModelKind.MESSAGE_REPEATS).inserted); assertEquals(1L, result.count(BackupModelKind.REACTIONS).inserted)
        assertEquals(1, db.repeats().forMessage(RADIO.value, first.id).size)
        assertTrue(db.repeats().forMessage(RADIO.value, second.id).isEmpty())
        val row = requireNotNull(db.messages().byId(RADIO.value, first.id))
        assertEquals(1L, row.heardRepeats); assertEquals("\uD83C\uDF36\uFE0F:1", row.reactionSummary)
    }

    @OriginalCase("BackupIntegrationTests::Duplicate message ids within one envelope: the second is skipped and repeats attach to the first()")
    @Test fun duplicateMessageUuidFirstBodyWins() = runBlocking {
        val first = message(direction = MessageDirection.INCOMING, text = "First body").copy(deduplicationKey = "shared-id-first")
        val second = first.copy(text = "Second body", timestamp = 1_700_000_600u, deduplicationKey = "shared-id-second")
        val result = service.importBackup(envelope(devices = listOf(device()), contacts = listOf(contact()),
            messages = listOf(first, second), repeats = listOf(repeat())), store)
        assertEquals(1L, result.count(BackupModelKind.MESSAGES).inserted); assertEquals(1L, result.count(BackupModelKind.MESSAGES).skipped)
        assertEquals(1L, result.count(BackupModelKind.MESSAGE_REPEATS).inserted)
        assertEquals("First body", db.messages().byId(RADIO.value, first.id)?.text)
        assertEquals(first.id, db.repeats().forMessage(RADIO.value, first.id).single().parentMessageID)
    }

    @OriginalCase("BackupIntegrationTests::Reply remaps onto local parent when parent is merged during import()")
    @Test fun repliesResolveAfterAllDuplicateParents() = runBlocking {
        val local = message(direction = MessageDirection.INCOMING, text = "Parent").copy(deduplicationKey = "reply-remap-parent")
        seed(envelope(devices = listOf(device()), contacts = listOf(contact()), messages = listOf(local)))
        val backup = local.copy(id = id(40))
        val reply = message(id = id(41), direction = MessageDirection.INCOMING, text = "Reply").copy(replyToID = backup.id, deduplicationKey = "reply-remap-reply")
        val result = service.importBackup(envelope(devices = listOf(device()), contacts = listOf(contact()), messages = listOf(reply, backup)), store)
        assertEquals(1L, result.count(BackupModelKind.MESSAGES).skipped); assertEquals(1L, result.count(BackupModelKind.MESSAGES).inserted)
        assertEquals(local.id, db.messages().byId(RADIO.value, reply.id)?.replyToID)
    }

    @OriginalCase("BackupIntegrationTests::Backup restores the same channel packet under each companion radio that received it()")
    @Test fun sameWirePacketDoesNotCrossRadioDedup() = runBlocking {
        val first = message(contactID = null, index = 0u, direction = MessageDirection.INCOMING, text = "aaaaa")
            .copy(senderNodeName = "Alice", deduplicationKey = "ch-0-1700000000-Alice-A1B2C3D4")
        val second = first.copy(id = id(40), radioId = OTHER_RADIO)
        val result = service.importBackup(envelope(devices = listOf(device(publicKey = key(0xAA)), device(OTHER_RADIO, key(0xBB), id(90))),
            channels = listOf(channel(), channel(OTHER_RADIO, id = id(30))), messages = listOf(first, second)), store)
        assertEquals(2L, result.count(BackupModelKind.MESSAGES).inserted); assertEquals(0L, result.count(BackupModelKind.MESSAGES).skipped)
        assertEquals("aaaaa", db.messages().byId(RADIO.value, first.id)?.text)
        assertEquals("aaaaa", db.messages().byId(OTHER_RADIO.value, second.id)?.text)
    }
}
