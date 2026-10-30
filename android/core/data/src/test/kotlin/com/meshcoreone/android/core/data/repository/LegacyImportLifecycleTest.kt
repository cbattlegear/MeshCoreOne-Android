// PortedFrom: MC1Services/Tests/MC1ServicesTests/ChannelFloodScopeMigrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/RadioIDMigrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/RepeaterUnreadMigrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/SortDateBackfillMigrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/SortDateResetMigrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Native-v1 import consequences under the maintainer-approved Apple historical-sweep exclusions.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.data.backup.AppBackupCodec
import com.meshcoreone.android.core.data.backup.AppBackupEnvelope
import com.meshcoreone.android.core.data.backup.AppBackupService
import com.meshcoreone.android.core.data.backup.BackupModelKind
import com.meshcoreone.android.core.data.backup.BackupDeflater
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test

class LegacyImportLifecycleTest : RepositoryTest() {
    private fun legacyInput(
        envelope: AppBackupEnvelope,
        transform: (String, JsonObject) -> JsonObject,
    ): AppBackupEnvelope {
        val codec = AppBackupCodec()
        val json = ByteArrayOutputStream().also { codec.encodeJson(envelope.withActualManifest(), it) }
        val root = Json.parseToJsonElement(json.toString(Charsets.UTF_8)).jsonObject
        val changed = JsonObject(root.mapValues { (key, value) ->
            if (value is JsonArray) JsonArray(value.map { transform(key, it.jsonObject) }) else value
        })
        val compressed = ByteArrayOutputStream()
        BackupDeflater(compressed, checkCancellation = {}).use {
            it.write(changed.toString().toByteArray(Charsets.UTF_8))
            it.finish()
        }
        return codec.parseBackup(Bytes(compressed.toByteArray()))
    }

    @Test fun omittedChannelFieldsReachTheRealImporterAndRepeatedImportKeepsLaterExplicitChoices() = runTest {
        val storage = MeshCoreStorage.get(context)
        try {
            val channels = listOf(null, "Germany", "France").mapIndexed { index, region ->
                ChannelDTO(radioId = RADIO_A, index = index.toUByte(), name = "Chan$index",
                    secret = Bytes(ByteArray(16) { (index + 1).toByte() }), regionScope = region)
            } + ChannelDTO(radioId = RADIO_A, index = 3u, name = "Explicit",
                secret = Bytes(ByteArray(16) { 4 }), floodScopeModeRawValue = "inherit", regionScope = "Germany")
            val envelope = legacyInput(AppBackupEnvelope(exportDate = AT, appVersion = "1", appBuild = "1",
                devices = SnapshotList.of(device()), channels = SnapshotList(channels))) { array, row ->
                if (array == "channels" && row.getValue("index").jsonPrimitive.int < 3) {
                    JsonObject(row - setOf("floodScopeModeRawValue", "notificationLevel", "unreadMentionCount", "isFavorite"))
                } else row
            }
            assertEquals(listOf("inherit", "specific", "specific", "inherit"),
                envelope.channels.map { it.floodScopeModeRawValue })
            val service = AppBackupService(storage.backupPreferences, "test", "1")
            assertEquals(4L, service.importBackup(envelope, store).counts.getValue(BackupModelKind.CHANNELS).inserted)
            val restored = store.fetchChannels(RADIO_A)
            assertEquals(listOf(ChannelFloodScope.Inherit, ChannelFloodScope.Region("Germany"),
                ChannelFloodScope.Region("France"), ChannelFloodScope.Inherit), restored.map { it.floodScope })
            assertEquals(listOf(null, "Germany", "France", "Germany"), restored.map { it.regionScope })
            assertTrue(restored.all { it.notificationLevel == NotificationLevel.ALL && !it.isFavorite && it.unreadMentionCount == 0L })
            assertEquals(0L, service.importBackup(envelope, store).totalInserted)
            assertEquals(restored, store.fetchChannels(RADIO_A))
            val germany = restored.single { it.index == 1.toUByte() }
            store.setChannelFloodScope(entity(id = germany.id), ChannelFloodScope.AllRegions)
            service.importBackup(envelope, store)
            store.warmUp()
            val laterChoice = assertNotNull(store.fetchChannel(entity(id = germany.id)))
            assertEquals(ChannelFloodScope.AllRegions, laterChoice.floodScope)
            assertEquals("allRegions", db.channels().byId(RADIO_A.value, germany.id)?.floodScopeModeRawValue)
            assertNull(db.channels().byId(RADIO_A.value, germany.id)?.regionScope)
        } finally {
            storage.close()
        }
    }

    @Test fun importerRemapsLegacyRadioByPublicKeyAndKeepsDirectChildIdentityAndKeysAcrossRepeatedRestore() = runTest {
        val storage = MeshCoreStorage.get(context)
        try {
            val localDevice = device()
            store.saveDevice(localDevice)
            val importedContact = contact(RADIO_B)
            val contentKey = "dm-00000000-0000-0000-0000-000000000003-1704067200-41ABEB9C"
            val outgoing = message(RADIO_B, text = "Hello mesh", timestamp = 1_704_067_200u)
                .copy(deduplicationKey = contentKey)
            val incoming = message(RADIO_B, direction = MessageDirection.INCOMING).copy(deduplicationKey = null)
            val existingKey = message(RADIO_B, text = "Existing key").copy(deduplicationKey = "already-keyed")
            val envelope = legacyInput(AppBackupEnvelope(exportDate = AT, appVersion = "1", appBuild = "1",
                devices = SnapshotList.of(localDevice.copy(id = UUID.randomUUID(), radioId = RADIO_B)),
                contacts = SnapshotList.of(importedContact), messages = SnapshotList.of(outgoing, incoming, existingKey))) { _, row -> row }
            val service = AppBackupService(storage.backupPreferences, "test", "1")
            val first = service.importBackup(envelope, store)
            assertEquals(0L, first.counts.getValue(BackupModelKind.DEVICES).inserted)
            assertEquals(3L, first.counts.getValue(BackupModelKind.MESSAGES).inserted)
            assertEquals(localDevice, store.fetchDevice(localDevice.id))
            assertTrue(store.fetchContacts(RADIO_B).isEmpty())
            assertEquals(importedContact.copy(radioId = RADIO_A, lastMessageDate = AT),
                store.fetchContact(entity(id = importedContact.id)))
            for (row in listOf(outgoing, incoming, existingKey)) {
                assertNull(store.fetchMessage(entity(RADIO_B, row.id)))
                assertEquals(row.copy(radioId = RADIO_A), store.fetchMessage(entity(id = row.id)))
            }
            assertEquals(0L, service.importBackup(envelope, store).totalInserted)
            store.warmUp()
            store.warmUp()
            assertEquals(contentKey, store.fetchMessage(entity(id = outgoing.id))?.deduplicationKey)
            assertNull(store.fetchMessage(entity(id = incoming.id))?.deduplicationKey)
            assertEquals("already-keyed", store.fetchMessage(entity(id = existingKey.id))?.deduplicationKey)
            assertEquals(localDevice.id, store.fetchDevice(RADIO_A)?.id)
        } finally {
            storage.close()
        }
    }

    @Test fun omittedSortDatesAndRepeaterBadgeExclusionsSurviveRealDecodeImportAndRepeatWithoutHistoricalSweeps() = runTest {
        val storage = MeshCoreStorage.get(context)
        try {
            val chat = contact().copy(unreadCount = 4)
            val repeater = contact(id = UUID.randomUUID(), publicKey = key(2))
                .copy(typeRawValue = ContactType.REPEATER.rawValue, unreadCount = 42, unreadMentionCount = 3)
            val room = session().copy(unreadCount = 5)
            val repeaterSession = session(publicKey = key(3)).copy(role = RemoteNodeRole.REPEATER, unreadCount = 99)
            val created = Instant.ofEpochSecond(1_704_067_200)
            val absent = message(text = "Hello", timestamp = 1_704_067_200u, createdAt = created)
            val explicit = message(text = "Buried backlog", timestamp = 1_700_000_000u, createdAt = created)
                .copy(sortDate = Instant.ofEpochSecond(1_700_000_000))
            val envelope = legacyInput(AppBackupEnvelope(exportDate = AT, appVersion = "1", appBuild = "1",
                devices = SnapshotList.of(device()), contacts = SnapshotList.of(chat, repeater),
                remoteNodeSessions = SnapshotList.of(room, repeaterSession), messages = SnapshotList.of(absent, explicit))) { array, row ->
                if (array == "messages" && row.getValue("text").jsonPrimitive.content == "Hello") JsonObject(row - "sortDate") else row
            }
            assertEquals(created, envelope.messages.single { it.id == absent.id }.sortDate)
            val service = AppBackupService(storage.backupPreferences, "test", "1")
            assertEquals(7L, service.importBackup(envelope, store).totalInserted)
            assertEquals(created, store.fetchMessage(entity(id = absent.id))?.sortDate)
            assertEquals(explicit.sortDate, store.fetchMessage(entity(id = explicit.id))?.sortDate)
            assertEquals(4L, store.getTotalUnreadCounts(RADIO_A).contacts)
            assertEquals(5L, store.getTotalUnreadCounts(RADIO_A).rooms)
            assertEquals(42L, store.fetchContact(entity(id = repeater.id))?.unreadCount)
            assertEquals(3L, store.fetchContact(entity(id = repeater.id))?.unreadMentionCount)
            assertEquals(99L, store.fetchRemoteNodeSession(entity(id = repeaterSession.id))?.unreadCount)
            store.incrementUnreadCount(entity(id = repeater.id))
            assertEquals(0L, service.importBackup(envelope, store).totalInserted)
            store.warmUp()
            store.warmUp()
            assertEquals(43L, store.fetchContact(entity(id = repeater.id))?.unreadCount)
            assertEquals(explicit.sortDate, store.fetchMessage(entity(id = explicit.id))?.sortDate)
            assertEquals(created, store.fetchMessage(entity(id = absent.id))?.sortDate)
        } finally {
            storage.close()
        }
    }
}
