// AndroidOnly: WP-203 Actual Room producer/consumer for data-only frozen Swift interoperability.
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.datastore.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BackupInteropTest : BackupRoomTest() {
    @Test fun actualRoomKotlinExportProducesAllFamiliesForSwift() = runBlocking {
        val input = fullEnvelope().copy(
            contacts = SnapshotList.of(contact().copy(lastHeardTimestamp = 1_700_000_500u,
                avatarImageData = Bytes.of(0, 0x80, 0xFF), outPathLength = 255u, outPath = Bytes.EMPTY)),
            messages = SnapshotList.of(message().copy(text = "Hi\u4F60\uD83D\uDE00\u05E9\u05DC\u05D5\u05DD",
                createdAt = fraction("1700000501.1234567"), sortDate = fraction("-0.25"),
                direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED, textType = TextType.SIGNED_PLAIN,
                pathNodes = Bytes.of(0x80, 0xFF), ackCode = 0x8000_0000u, senderKeyPrefix = Bytes.of(0xAB, 0xCD),
                timestamp = UInt.MAX_VALUE, regionScope = "Germany", regionScopeMatches = SnapshotList.of("Germany", "France"),
                failureSeen = true, routeType = com.meshcoreone.android.core.protocol.event.RouteType.TC_DIRECT)),
            userDefaults = BackupUserDefaults(hasCompletedOnboarding = true, showInlineImages = false, selectedThemeID = "ember",
                frequentEmojis = SnapshotList.of("\uD83D\uDC4D", "\u2764\uFE0F"),
                regionSelection = RegionSelection("US", RegionSelection.Source.MANUAL, "US-CA", "los angeles")),
        )
        val result = service.importBackup(input, store)
        assertEquals(12L, result.totalInserted)
        assertTrue(result.userDefaultsRestored)
        db.openHelper.writableDatabase.execSQL("UPDATE contacts SET outPathLength = -1")
        db.openHelper.writableDatabase.execSQL("UPDATE discovered_nodes SET outPathLength = -1")
        assertEquals(-1L, requireNotNull(db.contacts().byId(RADIO.value, input.contacts.single().id)).outPathLength)
        assertEquals(-1L, db.discoveredNodes().backupAll().single().outPathLength)
        val exported = service.export(store)
        val envelope = AppBackupCodec().parseBackup(exported.data)
        assertEquals(12L, BackupModelKind.entries.sumOf { envelope.manifest.count(it) })
        assertEquals(input.messages.single().sortDate, envelope.messages.single().sortDate)
        assertEquals(255.toUByte(), envelope.contacts.single().outPathLength)
        assertEquals(255.toUByte(), envelope.discoveredNodes.single().outPathLength)
        val output = output()
        output.mkdirs()
        File(output, "kotlin-export.meshcoreone").writeBytes(exported.data.toByteArray())
        File(output, "kotlin-export.json").writeText(envelope.jsonObject().toString(), Charsets.UTF_8)
        File(output, "kotlin-room-proof.json").writeText(wireObject {
            put("producer", "actual-Room-Kotlin-export"); put("inserted", result.totalInserted); put("skipped", result.totalSkipped)
            put("preferencesRestored", result.userDefaultsRestored)
            put("compressedSha256", digest(exported.data.toByteArray()))
            put("messageId", envelope.messages.single().id.canonicalString())
            put("radioId", envelope.devices.single().radioId.canonicalString)
            put("restoreVerified", true)
        }.toString(), Charsets.UTF_8)
    }

    @Test fun actualSwiftExportRestoresIntoRealRoomAndMatchesSourceSemantics() = runBlocking {
        val configured = System.getProperty("wp203.interop.input")
        val input = if (configured == null) File("..").resolve("testing").resolve("fixtures").resolve("reference-codec")
            else File(configured)
        val compressed = File(input, if (configured == null) "reference-envelope.meshcoreone" else "swift-export.meshcoreone")
        val expected = File(input, if (configured == null) "reference-envelope.json" else "swift-export.json")
        assertTrue(compressed.isFile); assertTrue(expected.isFile)
        val codec = AppBackupCodec()
        val envelope = compressed.inputStream().use { codec.parseBackup(it) }
        val decoded = envelope.jsonObject()
        assertJsonSemantics(backupJson.parseToJsonElement(expected.readText(Charsets.UTF_8)), decoded)
        val result = service.importBackup(envelope, store)
        assertEquals(12L, result.totalInserted)
        val actual = service.exportEnvelope(store)
        for (kind in BackupModelKind.entries) assertEquals(1L, actual.manifest.count(kind))
        val message = requireNotNull(db.messages().byId(envelope.messages.single().radioId.value, envelope.messages.single().id)).toDTO()
        val expectedMessage = envelope.messages.single()
        assertEquals(expectedMessage.text, message.text); assertEquals(expectedMessage.timestamp, message.timestamp)
        assertEquals(expectedMessage.createdAt, message.createdAt); assertEquals(expectedMessage.sortDate, message.sortDate)
        assertEquals(expectedMessage.ackCode, message.ackCode); assertEquals(expectedMessage.senderKeyPrefix, message.senderKeyPrefix)
        assertEquals(expectedMessage.regionScope, message.regionScope); assertEquals(expectedMessage.regionScopeMatches, message.regionScopeMatches)
        assertEquals(expectedMessage.routeType, message.routeType); assertEquals(expectedMessage.failureSeen, message.failureSeen)
        assertEquals(envelope.contacts.single().avatarImageData, actual.contacts.single().avatarImageData)
        assertEquals(envelope.contacts.single().outPathLength, actual.contacts.single().outPathLength)
        assertEquals(envelope.discoveredNodes.single().outPathLength, actual.discoveredNodes.single().outPathLength)
        assertEquals(envelope.remoteNodeSessions.single().id, actual.roomMessages.single().sessionID)
        assertEquals(envelope.savedTracePaths.single().runs, actual.savedTracePaths.single().runs)
        assertEquals(envelope.nodeStatusSnapshots.single(), actual.nodeStatusSnapshots.single())
        assertEquals(envelope.userDefaults, BackupUserDefaults.snapshot(storage.backupPreferences))
        assertEquals(0L, service.importBackup(envelope, store).totalInserted)
        if (configured != null) {
            val output = output()
            output.mkdirs()
            File(output, "kotlin-restored.json").writeText(actual.jsonObject().toString(), Charsets.UTF_8)
            File(output, "swift-to-kotlin-room-proof.json").writeText(wireObject {
                put("consumer", "actual-Swift-export-Room-restore"); put("inserted", result.totalInserted)
                put("compressedSha256", digest(compressed.readBytes())); put("sourceSemanticsCompared", true)
                put("radioId", actual.devices.single().radioId.canonicalString); put("messageId", message.id.canonicalString())
                put("preferencesRestored", result.userDefaultsRestored); put("reimportInserted", 0)
            }.toString(), Charsets.UTF_8)
        }
    }

    private fun output(): File = File(requireNotNull(System.getProperty("wp203.interop.output")) { "Declared interop output is required" })
    private fun digest(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    private fun assertJsonSemantics(expected: JsonElement, actual: JsonElement) {
        when (expected) {
            is JsonObject -> {
                assertTrue(actual is JsonObject)
                val objectValue = actual.jsonObject
                assertEquals(expected.keys, objectValue.keys)
                for ((key, value) in expected) assertJsonSemantics(value, objectValue.getValue(key))
            }
            is JsonArray -> {
                assertTrue(actual is JsonArray)
                val array = actual.jsonArray
                assertEquals(expected.size, array.size)
                for (index in expected.indices) assertJsonSemantics(expected[index], array[index])
            }
            is JsonPrimitive -> {
                assertTrue(actual is JsonPrimitive)
                val primitive = actual.jsonPrimitive
                if (expected.isString || expected is JsonNull || expected.booleanOrNull != null) assertEquals(expected, primitive)
                else assertEquals(0, java.math.BigDecimal(expected.content).compareTo(java.math.BigDecimal(primitive.content)))
            }
        }
    }
}
