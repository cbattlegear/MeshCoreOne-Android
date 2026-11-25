// PortedFrom: MC1Services/Tests/MC1ServicesTests/BackupIntegrationTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupMetadataIntegrationTest : BackupRoomTest() {
    @Test fun backupCannotGrantRemoteCliOnNewOrExistingContacts() = runBlocking {
        seed(envelope(devices = listOf(device())))
        db.contacts().insert(contact().copy(flags = 0x85u).toEntity())
        val existing = contact(id = id(20)).copy(flags = 0xffu)
        val inserted = contact(publicKey = key(0xF4), id = id(24)).copy(flags = 0xffu)
        service.importBackup(envelope(contacts = listOf(existing, inserted)), store)
        assertEquals(0x85L, db.contacts().byId(RADIO.value, id(2))?.flags)
        assertEquals(0xefL, db.contacts().byId(RADIO.value, id(24))?.flags)
    }

    @OriginalCase("BackupIntegrationTests::Import onto existing contact with nil lastMessageDate makes DM thread visible()")
    @Test fun importedDmMakesExistingContactConversationVisible() = runBlocking {
        seed(envelope(devices = listOf(device()), contacts = listOf(contact())))
        assertTrue(db.contacts().conversations(RADIO.value).isEmpty())
        val backup = contact(id = id(20)).copy(lastMessageDate = AT)
        val result = service.importBackup(envelope(devices = listOf(device()), contacts = listOf(backup),
            messages = listOf(message(contactID = backup.id).copy(deduplicationKey = "merge-dm"))), store)
        assertEquals(1L, result.count(BackupModelKind.CONTACTS).skipped)
        assertEquals(1L, result.count(BackupModelKind.MESSAGES).inserted)
        val after = db.contacts().conversations(RADIO.value).single()
        assertEquals("Alice", after.name); assertNotNull(after.lastMessageDate)
        assertEquals(id(2), db.messages().byId(RADIO.value, id(4))?.contactID)
    }

    @OriginalCase("BackupIntegrationTests::Import repeats/reactions onto existing message recomputes heardRepeats and reactionSummary()")
    @Test fun importedChildrenRecomputeRealCaches() = runBlocking {
        val local = message(direction = MessageDirection.INCOMING, text = "Cache test").copy(deduplicationKey = "cache-test-key")
        seed(envelope(devices = listOf(device()), contacts = listOf(contact()), messages = listOf(local)))
        val backup = local.copy(id = id(40))
        val result = service.importBackup(envelope(devices = listOf(device()), contacts = listOf(contact()), messages = listOf(backup),
            repeats = listOf(repeat(backup.id), repeat(backup.id, id(50), Bytes.of(0x42))),
            reactions = listOf(reaction(messageID = backup.id))), store)
        assertEquals(1L, result.count(BackupModelKind.MESSAGES).skipped)
        assertEquals(2L, result.count(BackupModelKind.MESSAGE_REPEATS).inserted)
        assertEquals(1L, result.count(BackupModelKind.REACTIONS).inserted)
        val after = requireNotNull(db.messages().byId(RADIO.value, local.id))
        assertEquals(2L, after.heardRepeats); assertEquals("\uD83D\uDC4D:1", after.reactionSummary)
    }

    @OriginalCase("BackupIntegrationTests::Import messages onto existing channel with nil lastMessageDate refreshes metadata()")
    @Test fun channelDateIsReconciledFromActualMessageCreatedAt() = runBlocking {
        seed(envelope(devices = listOf(device()), channels = listOf(channel())))
        assertNull(db.channels().forRadio(RADIO.value).single().lastMessageDate)
        val result = service.importBackup(envelope(devices = listOf(device()), channels = listOf(channel()),
            messages = listOf(message(contactID = null, index = 0u).copy(deduplicationKey = "channel-meta"))), store)
        assertEquals(1L, result.count(BackupModelKind.CHANNELS).skipped)
        assertEquals(1L, result.count(BackupModelKind.MESSAGES).inserted)
        assertEquals(AT, db.channels().forRadio(RADIO.value).single().lastMessageDate?.toInstant())
    }

    @OriginalCase("BackupIntegrationTests::Import onto existing contact restores backup-owned contact metadata()")
    @Test fun contactMetadataFillsOnlySourceOwnedHoles() = runBlocking {
        seed(envelope(devices = listOf(device()), contacts = listOf(contact())))
        val backup = contact(id = id(20)).copy(nickname = "Field Ops", isBlocked = true, isMuted = true, isFavorite = true,
            lastMessageDate = AT, unreadCount = 7, unreadMentionCount = 2, ocvPreset = "custom",
            customOCVArrayString = "4200,4100,4000", avatarImageData = Bytes(ByteArray(16) { 0xAA.toByte() }))
        val result = service.importBackup(envelope(devices = listOf(device()), contacts = listOf(backup)), store)
        assertEquals(0L, result.count(BackupModelKind.CONTACTS).inserted)
        assertEquals(1L, result.count(BackupModelKind.CONTACTS).skipped); assertEquals(1L, result.count(BackupModelKind.CONTACTS).merged)
        val actual = db.contacts().forRadio(RADIO.value).single().toDTO()
        assertEquals("Field Ops", actual.nickname); assertTrue(actual.isBlocked); assertTrue(actual.isMuted); assertTrue(actual.isFavorite)
        assertEquals(AT, actual.lastMessageDate); assertEquals(7L, actual.unreadCount); assertEquals(2L, actual.unreadMentionCount)
        assertEquals("custom", actual.ocvPreset); assertEquals("4200,4100,4000", actual.customOCVArrayString)
        assertEquals(backup.avatarImageData, actual.avatarImageData); assertEquals(id(2), actual.id)
    }

    @OriginalCase("BackupIntegrationTests::Contact avatarImageData survives encode decode export import and merge()")
    @Test fun avatarHasRealLegacyExportRestoreAndMergeBehavior() = runBlocking {
        val original = contact().copy(avatarImageData = Bytes(ByteArray(8) { 0x11 }))
        assertEquals(original.avatarImageData, decodeContact(encodeContact(original)).avatarImageData)
        assertNull(decodeContact(encodeContact(original).replacing("avatarImageData", null)).avatarImageData)
        seed(envelope(devices = listOf(device()), contacts = listOf(original.copy(avatarImageData = Bytes(ByteArray(8) { 0x22 })))))
        val export = service.export(store)
        db.contacts().clearRadio(RADIO.value)
        val first = service.importBackup(AppBackupCodec().parseBackup(export.data), store)
        assertEquals(1L, first.count(BackupModelKind.CONTACTS).inserted)
        assertEquals(Bytes(ByteArray(8) { 0x22 }), db.contacts().forRadio(RADIO.value).single().avatarImageData)
        db.contacts().insert(contact(publicKey = key(0xE4), id = id(21)).copy(name = "Carol").toEntity())
        val backup = contact(publicKey = key(0xE4), id = id(22)).copy(name = "Carol", avatarImageData = Bytes(ByteArray(8) { 0x33 }))
        val merge = service.importBackup(envelope(contacts = listOf(backup)), store)
        assertEquals(1L, merge.count(BackupModelKind.CONTACTS).skipped)
        assertEquals(backup.avatarImageData, db.contacts().byId(RADIO.value, id(21))?.avatarImageData)
    }

    @OriginalCase("BackupIntegrationTests::Contact lastHeardTimestamp survives encode decode export import and merge()")
    @Test fun heardStampLegacyMaxAndFutureClamp() = runBlocking {
        val original = contact().copy(lastHeardTimestamp = 1_700_000_500u)
        assertEquals(original.lastHeardTimestamp, decodeContact(encodeContact(original)).lastHeardTimestamp)
        val legacy = decodeContact(encodeContact(original).replacing("lastHeardTimestamp", null))
        assertNull(legacy.lastHeardTimestamp)
        service.importBackup(envelope(contacts = listOf(legacy)), store)
        assertEquals(0L, db.contacts().forRadio(RADIO.value).single().lastHeardTimestamp)
        for (stamp in listOf(1_700_000_900u, 1_700_000_100u, 1_700_001_000u, 1_700_086_400u)) {
            service.importBackup(envelope(contacts = listOf(original.copy(id = id(20), lastHeardTimestamp = stamp))), store)
            val actual = db.contacts().forRadio(RADIO.value).single().lastHeardTimestamp
            assertTrue(actual >= 1_700_000_900L)
            assertTrue(actual <= clock.instant().epochSecond + 300)
            if (stamp == 1_700_000_100u) assertEquals(1_700_000_900L, actual)
            if (stamp == 1_700_001_000u) assertEquals(1_700_001_000L, actual)
        }
        service.importBackup(envelope(contacts = listOf(contact(publicKey = key(0xF4), id = id(24)).copy(lastHeardTimestamp = UInt.MAX_VALUE))), store)
        val inserted = db.contacts().byId(RADIO.value, id(24))?.lastHeardTimestamp
        assertEquals(clock.instant().epochSecond + 300, inserted)
        val roundTrip = AppBackupCodec().parseBackup(service.export(store).data)
        assertEquals(inserted?.toUInt(), roundTrip.contacts.single { it.publicKey == key(0xF4) }.lastHeardTimestamp)
    }

    @OriginalCase("BackupIntegrationTests::Import onto existing channel restores backup-owned channel metadata()")
    @Test fun channelMergeKeepsIdentityAndAdoptsDefaultsOnly() = runBlocking {
        seed(envelope(devices = listOf(device()), channels = listOf(channel(index = 2u))))
        val backup = channel(index = 2u, id = id(30)).copy(lastMessageDate = AT.plusSeconds(100),
            unreadCount = 11, unreadMentionCount = 4, notificationLevel = NotificationLevel.MENTIONS_ONLY,
            isFavorite = true).withFloodScope(ChannelFloodScope.Region("US"))
        val result = service.importBackup(envelope(channels = listOf(backup)), store)
        assertEquals(0L, result.count(BackupModelKind.CHANNELS).inserted); assertEquals(1L, result.count(BackupModelKind.CHANNELS).skipped)
        val actual = db.channels().forRadio(RADIO.value).single().toDTO()
        assertEquals(backup.lastMessageDate, actual.lastMessageDate); assertEquals(11L, actual.unreadCount); assertEquals(4L, actual.unreadMentionCount)
        assertEquals(NotificationLevel.MENTIONS_ONLY, actual.notificationLevel); assertTrue(actual.isFavorite); assertEquals("US", actual.regionScope)
        assertEquals(id(3), actual.id)
    }

    @OriginalCase("BackupIntegrationTests::Re-importing the same backup is idempotent for unread counts and last-message dates()")
    @Test fun metadataReimportDoesNotDoubleUnreadOrOscillate() = runBlocking {
        seed(envelope(channels = listOf(channel(index = 1u, secret = Bytes(ByteArray(32) { 0x77 })).copy(lastMessageDate = AT, unreadCount = 5))))
        val value = envelope(channels = listOf(channel(index = 1u, secret = Bytes(ByteArray(32) { 0x77 }), id = id(30))
            .copy(lastMessageDate = AT.plusSeconds(9000), unreadCount = 9)))
        service.importBackup(value, store)
        val first = db.channels().forRadio(RADIO.value).single()
        service.importBackup(value, store)
        assertEquals(first, db.channels().forRadio(RADIO.value).single())
    }

    @OriginalCase("BackupIntegrationTests::Import inserts remote sessions as disconnected while preserving backup metadata()")
    @Test fun newRemoteSessionNeverLooksLikeLiveConnection() = runBlocking {
        val backup = session().copy(name = "Ops Room", isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN,
            lastConnectedDate = AT.plusSeconds(200), unreadCount = 5, notificationLevel = NotificationLevel.MENTIONS_ONLY,
            isFavorite = true, neighborCount = 4, lastSyncTimestamp = 88u, lastMessageDate = AT.plusSeconds(200))
        val result = service.importBackup(envelope(sessions = listOf(backup)), store)
        assertEquals(1L, result.count(BackupModelKind.REMOTE_NODE_SESSIONS).inserted)
        assertEquals(0L, result.count(BackupModelKind.REMOTE_NODE_SESSIONS).skipped)
        assertEquals(backup.copy(isConnected = false), db.sessions().forRadio(RADIO.value).single().toDTO())
    }

    @OriginalCase("BackupIntegrationTests::Import preserves room-message delivery metadata()")
    @Test fun roomDeliveryMetadataSurvivesAtomicRestore() = runBlocking {
        val dto = roomMessage().copy(authorKeyPrefix = Bytes.of(0xCA, 0xFE, 0xBA, 0xBE), authorName = "Ops",
            text = "Retry me", timestamp = 1_700_000_275u, createdAt = fraction("1700000275.25"), isFromSelf = true,
            statusRawValue = MessageStatus.FAILED.rawValue, ackCode = 0xDEAD_BEEFu, roundTripTime = 1450u, retryAttempt = 3, maxRetryAttempts = 7)
        val result = service.importBackup(envelope(sessions = listOf(session()), roomMessages = listOf(dto)), store)
        assertEquals(1L, result.count(BackupModelKind.ROOM_MESSAGES).inserted)
        assertEquals(dto, db.roomMessages().byId(RADIO.value, dto.id)?.toDTO())
    }

    @OriginalCase("BackupIntegrationTests::Import onto existing remote session restores backup metadata without clobbering live state()")
    @Test fun existingRemoteSessionKeepsLivePermissionsAndMaxCounters() = runBlocking {
        val local = session().copy(name = "Ops Room", isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN,
            lastConnectedDate = AT.plusSeconds(250), neighborCount = 2, lastSyncTimestamp = 123u)
        seed(envelope(sessions = listOf(local)))
        val backup = session(id = id(80)).copy(permissionLevel = RoomPermissionLevel.GUEST, lastConnectedDate = AT.plusSeconds(225),
            unreadCount = 9, notificationLevel = NotificationLevel.MENTIONS_ONLY, isFavorite = true,
            neighborCount = 7, lastSyncTimestamp = 8u, lastMessageDate = AT.plusSeconds(300))
        val result = service.importBackup(envelope(sessions = listOf(backup)), store)
        assertEquals(0L, result.count(BackupModelKind.REMOTE_NODE_SESSIONS).inserted)
        assertEquals(1L, result.count(BackupModelKind.REMOTE_NODE_SESSIONS).skipped)
        assertEquals(1L, result.count(BackupModelKind.REMOTE_NODE_SESSIONS).merged); assertTrue(result.hasRestoredChanges)
        val actual = db.sessions().forRadio(RADIO.value).single().toDTO()
        assertTrue(actual.isConnected); assertEquals(RoomPermissionLevel.ADMIN, actual.permissionLevel)
        assertEquals(local.lastConnectedDate, actual.lastConnectedDate); assertEquals(9L, actual.unreadCount)
        assertEquals(NotificationLevel.MENTIONS_ONLY, actual.notificationLevel); assertTrue(actual.isFavorite)
        assertEquals(123u, actual.lastSyncTimestamp); assertEquals(backup.lastMessageDate, actual.lastMessageDate)
        assertEquals(2L, actual.neighborCount)
    }

    @OriginalCase("BackupIntegrationTests::Import on the process store updates rows already registered in that context()")
    @Test fun processStoreReadsSeeImportedMutations() = runBlocking {
        seed(envelope(contacts = listOf(contact(publicKey = key(0x51)))))
        val warmed = store.fetchContact(RADIO, key(0x51))
        assertNull(warmed?.nickname); assertFalse(requireNotNull(warmed).isBlocked); assertEquals(0L, warmed.unreadCount)
        val result = service.importBackup(envelope(contacts = listOf(contact(publicKey = key(0x51), id = id(20))
            .copy(nickname = "Field Ops", isBlocked = true, lastMessageDate = AT, unreadCount = 7))), store)
        assertEquals(1L, result.count(BackupModelKind.CONTACTS).skipped); assertEquals(1L, result.count(BackupModelKind.CONTACTS).merged)
        val after = requireNotNull(store.fetchContact(RADIO, key(0x51)))
        assertEquals("Field Ops", after.nickname); assertTrue(after.isBlocked); assertEquals(7L, after.unreadCount); assertEquals(AT, after.lastMessageDate)
    }

    @OriginalCase("BackupIntegrationTests::Fresh-insert import preserves contact unread counts from backup()")
    @Test fun freshContactUnreadValuesArePreserved() = runBlocking {
        val result = service.importBackup(envelope(contacts = listOf(contact().copy(unreadCount = 7, unreadMentionCount = 2))), store)
        assertEquals(1L, result.count(BackupModelKind.CONTACTS).inserted); assertEquals(0L, result.count(BackupModelKind.CONTACTS).skipped)
        val actual = db.contacts().forRadio(RADIO.value).single()
        assertEquals(7L, actual.unreadCount); assertEquals(2L, actual.unreadMentionCount)
    }

    @OriginalCase("BackupIntegrationTests::Fresh-insert import preserves channel unread counts from backup()")
    @Test fun freshChannelUnreadValuesArePreserved() = runBlocking {
        val result = service.importBackup(envelope(channels = listOf(channel(index = 4u).copy(unreadCount = 3, unreadMentionCount = 1))), store)
        assertEquals(1L, result.count(BackupModelKind.CHANNELS).inserted); assertEquals(0L, result.count(BackupModelKind.CHANNELS).skipped)
        val actual = db.channels().forRadio(RADIO.value).single()
        assertEquals(3L, actual.unreadCount); assertEquals(1L, actual.unreadMentionCount)
    }

    @OriginalCase("BackupIntegrationTests::Merge import keeps local contact unread counts when they exceed backup values()")
    @Test fun contactUnreadMaxPreservesHigherLocal() = runBlocking {
        seed(envelope(contacts = listOf(contact().copy(unreadCount = 9, unreadMentionCount = 4))))
        val result = service.importBackup(envelope(contacts = listOf(contact(id = id(20)).copy(unreadCount = 2, unreadMentionCount = 1))), store)
        assertEquals(0L, result.count(BackupModelKind.CONTACTS).inserted); assertEquals(1L, result.count(BackupModelKind.CONTACTS).skipped)
        val actual = db.contacts().forRadio(RADIO.value).single()
        assertEquals(9L, actual.unreadCount); assertEquals(4L, actual.unreadMentionCount)
    }

    @OriginalCase("BackupIntegrationTests::Merge import keeps local channel unread counts when they exceed backup values()")
    @Test fun channelUnreadMaxPreservesHigherLocal() = runBlocking {
        seed(envelope(channels = listOf(channel(index = 5u).copy(unreadCount = 6, unreadMentionCount = 3))))
        val result = service.importBackup(envelope(channels = listOf(channel(index = 5u, id = id(30)).copy(unreadCount = 1))), store)
        assertEquals(0L, result.count(BackupModelKind.CHANNELS).inserted); assertEquals(1L, result.count(BackupModelKind.CHANNELS).skipped)
        val actual = db.channels().forRadio(RADIO.value).single()
        assertEquals(6L, actual.unreadCount); assertEquals(3L, actual.unreadMentionCount)
    }

    @OriginalCase("BackupIntegrationTests::Merge import keeps local remote-session unread count when it exceeds backup value()")
    @Test fun sessionUnreadMaxPreservesHigherLocal() = runBlocking {
        seed(envelope(sessions = listOf(session().copy(unreadCount = 8))))
        val result = service.importBackup(envelope(sessions = listOf(session(id = id(80)).copy(unreadCount = 2))), store)
        assertEquals(0L, result.count(BackupModelKind.REMOTE_NODE_SESSIONS).inserted); assertEquals(1L, result.count(BackupModelKind.REMOTE_NODE_SESSIONS).skipped)
        assertEquals(8L, db.sessions().forRadio(RADIO.value).single().unreadCount)
    }

    @OriginalCase("BackupIntegrationTests::Import onto a muted region-scoped channel preserves the local notification and flood settings()")
    @Test fun localMutedChannelAndRegionWin() = runBlocking {
        val local = channel(index = 2u).copy(notificationLevel = NotificationLevel.MUTED).withFloodScope(ChannelFloodScope.Region("SK"))
        seed(envelope(channels = listOf(local)))
        val result = service.importBackup(envelope(channels = listOf(channel(index = 2u, id = id(30))
            .copy(notificationLevel = NotificationLevel.MENTIONS_ONLY).withFloodScope(ChannelFloodScope.Region("US")))), store)
        assertEquals(0L, result.count(BackupModelKind.CHANNELS).inserted); assertEquals(1L, result.count(BackupModelKind.CHANNELS).skipped)
        assertEquals(NotificationLevel.MUTED, db.channels().forRadio(RADIO.value).single().toDTO().notificationLevel)
        assertEquals("SK", db.channels().forRadio(RADIO.value).single().regionScope)
    }

    @OriginalCase("BackupIntegrationTests::Import onto a muted remote session preserves the local muted notification level()")
    @Test fun localMutedSessionWins() = runBlocking {
        seed(envelope(sessions = listOf(session().copy(notificationLevel = NotificationLevel.MUTED))))
        val result = service.importBackup(envelope(sessions = listOf(session(id = id(80)).copy(notificationLevel = NotificationLevel.MENTIONS_ONLY))), store)
        assertEquals(0L, result.count(BackupModelKind.REMOTE_NODE_SESSIONS).inserted); assertEquals(1L, result.count(BackupModelKind.REMOTE_NODE_SESSIONS).skipped)
        assertEquals(NotificationLevel.MUTED, db.sessions().forRadio(RADIO.value).single().toDTO().notificationLevel)
    }

    @OriginalCase("BackupIntegrationTests::Import onto a muted contact never un-mutes, un-blocks, or un-favorites it()")
    @Test fun localContactSafetyFlagsAndNicknameWin() = runBlocking {
        val local = contact().copy(nickname = "Field Ops", isBlocked = true, isMuted = true, isFavorite = true)
        seed(envelope(contacts = listOf(local)))
        val result = service.importBackup(envelope(contacts = listOf(contact(id = id(20)).copy(nickname = "Elsewhere"))), store)
        assertEquals(0L, result.count(BackupModelKind.CONTACTS).inserted); assertEquals(1L, result.count(BackupModelKind.CONTACTS).skipped)
        val actual = db.contacts().forRadio(RADIO.value).single().toDTO()
        assertTrue(actual.isMuted); assertTrue(actual.isBlocked); assertTrue(actual.isFavorite); assertEquals("Field Ops", actual.nickname)
    }
}
