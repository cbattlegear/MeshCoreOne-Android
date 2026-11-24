// Defensive values, raw widths, native field policies and original computed-behavior boundaries.
// PortedFrom: MC1Services/Sources/MC1Services/Models/Device.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/Message.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/NodeStatusSnapshot.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Extensions/String+StableUUID.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.*
import com.meshcoreone.android.core.protocol.lpp.*
import com.meshcoreone.android.core.protocol.model.*
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test

class DomainBoundaryTest {
    @Test fun copiedBinaryAndNestedCollectionValuesCannotBeMutatedThroughInputsOrAccessors() {
        val raw = byteArrayOf(0, 0x80.toByte(), 0xFF.toByte())
        val dto = testContact().copy(publicKey = Bytes(raw), avatarImageData = Bytes(raw))
        raw.fill(1); dto.publicKey.toByteArray().fill(2)
        assertEquals(Bytes.of(0, 128, 255), dto.publicKey); assertEquals(dto.publicKey, dto.avatarImageData)
        val methods = mutableListOf<ConnectionMethod>(ConnectionMethod.WiFi("host", 65535u))
        val device = testDevice().copy(connectionMethods = methods.snapshot())
        methods.clear(); assertEquals(1, device.connectionMethods.size)
        assertFails { (device.connectionMethods as MutableList<ConnectionMethod>).clear() }
        val ids = mutableSetOf(UUID.randomUUID()); val keys = FailedSendConversationKeys(contactIDs = ids.snapshotSet())
        ids.clear(); assertEquals(1, keys.contactIDs.size)
        val map = mutableMapOf("a" to SnapshotList.of(1L)); val frozen = map.snapshotMap()
        map.clear(); assertEquals(listOf(1L), assertNotNull(frozen["a"]).toList())
        assertFails { (frozen as MutableMap<String, SnapshotList<Long>>).clear() }
    }

    @Test fun binaryKeysHaveContentEqualityAndSourceFloatingEqualityTreatsSignedZeroEqually() {
        val first = testContact().copy(publicKey = Bytes.of(128, 255))
        val second = first.copy(publicKey = Bytes(byteArrayOf(128.toByte(), 255.toByte())))
        assertEquals(first, second); assertEquals(first.hashCode(), second.hashCode())
        assertEquals("same", mapOf(first.publicKey to "same")[second.publicKey])
    }

    @Test fun signedZeroAndNaNFollowSwiftValueSemanticsAcrossNestedSnapshots() {
        val d = testDevice(); assertEquals(d.copy(latitude = 0.0), d.copy(latitude = -0.0))
        assertEquals(d.copy(latitude = 0.0).hashCode(), d.copy(latitude = -0.0).hashCode())
        val m = testMessage(); assertEquals(m.copy(snr = 0.0), m.copy(snr = -0.0))
        assertEquals(Coordinate(0.0, 1.0), Coordinate(-0.0, 1.0))
        assertNotEquals(Coordinate(Double.NaN, 1.0), Coordinate(Double.NaN, 1.0))
        val run = testRun(); assertEquals(run.copy(hopsSNR = SnapshotList.of(0.0)), run.copy(hopsSNR = SnapshotList.of(-0.0)))
        assertEquals(run.copy(hopsSNR = SnapshotList.of(0.0)).hashCode(), run.copy(hopsSNR = SnapshotList.of(-0.0)).hashCode())
    }

    @Test fun sourceStableUUIDUsesTwoWrappingLittleEndianDjb2StreamsWithoutVersionRewriting() {
        assertEquals("05150000-0000-0000-0515-000000000000", "".stableUUID.canonicalString())
        assertEquals("A87B880B-0000-0000-B677-590000000000", "hello".stableUUID.canonicalString())
        assertEquals("9A2DB2E2-3F15-04CD-0566-06553AC049C5", DeviceIdentity.deriveUUID(KEY).canonicalString())
        assertNotEquals("hello".stableUUID, "Hello".stableUUID)
        assertEquals(("a".repeat(1000)).stableUUID, ("a".repeat(1000)).stableUUID)
    }

    @Test fun applicationHexFilteringIsNotTheProtocolOddNibbleParser() {
        assertEquals(Bytes.of(0xAA, 0xBB, 0xCC), applicationBytesFromHex("AA BB:CC!"))
        assertEquals(Bytes.EMPTY, applicationBytesFromHex("xyz!?"))
        assertNull(applicationBytesFromHex("ABC")); assertNull(applicationBytesFromHex("0xAA"))
        assertEquals(Bytes.of(128, 255), applicationBytesFromHex("80-ff"))
        assertEquals("80:FF", Bytes.of(128, 255).uppercaseHexString(":"))
        assertEquals(0u, Bytes.of(1, 2, 3).ackCodeUInt32)
        assertEquals(0xFFFF_0080u, Bytes.of(128, 0, 255, 255, 1).ackCodeUInt32)
        assertTrue(Bytes.of(1, 2).pathHops(0).isEmpty()); assertTrue(Bytes.of(1, 2).pathHops(-1).isEmpty())
    }

    @Test fun unknownContactTypesEncodedModesAndFavoriteBitsRoundTripVerbatim() {
        val c = testContact().copy(typeRawValue = 254u, flags = 0x81u, outPathLength = 0x81u, outPath = Bytes.of(128, 255, 0, 1))
        assertEquals(ContactType.CHAT, c.type); assertEquals(254.toUByte(), c.toContactFrame().typeRawValue)
        assertEquals(0x81.toUByte(), c.toContactFrame().outPathLength); assertEquals(c.outPath, c.toContactFrame().outPath)
        assertEquals(3L, c.pathHashSize); assertEquals(1L, c.pathHopCount)
        val updated = c.updating(c.toContactFrame().copy(flags = 0x02u))
        assertEquals(3.toUByte(), updated.flags); assertEquals(254.toUByte(), updated.typeRawValue)
        val flooded = c.floodedContactFrame(UInt.MAX_VALUE)
        assertEquals(255.toUByte(), flooded.outPathLength); assertEquals(Bytes.EMPTY, flooded.outPath)
        assertEquals(UInt.MAX_VALUE, flooded.lastModified); assertEquals(c.typeRawValue, flooded.typeRawValue)
    }

    @Test fun recencyIsMonotonicAndNeverUsesTheBluetoothHandleAsRadioIdentity() {
        val c = testContact().copy(lastModified = 5u, lastHeardTimestamp = UInt.MAX_VALUE)
        assertEquals(UInt.MAX_VALUE, c.recencyTimestamp); assertFalse(c.matchesStaleNodePrune(10u))
        assertFalse(c.copy(isFavorite = true, lastHeardTimestamp = 0u).matchesStaleNodePrune(10u))
        assertTrue(c.copy(lastHeardTimestamp = null).matchesStaleNodePrune(10u))
        val a = RadioId(UUID.randomUUID()); val b = RadioId(UUID.randomUUID()); val id = UUID.randomUUID()
        assertNotEquals(ChatConversationID.dm(a, id), ChatConversationID.dm(b, id))
        assertEquals("${a.canonicalString}|dm|${id.canonicalString()}", ChatConversationID.dm(a, id).draftStorageKey)
        assertEquals("${a.canonicalString}|ch|255", ChatConversationID.channel(a, 255u).draftStorageKey)
        assertEquals(mapOf(id to 2L), listOf(id, id, id).indexByID { it })
    }

    @Test fun firmwareFeatureGatesDisambiguateStringVersionsAndPreserveRawSettings() {
        val d = testDevice().copy(firmwareVersion = 8u, firmwareVersionString = "MeshCore v1.11.0")
        assertTrue(d.supportsTraceHashSizeOverride); assertFalse(d.supportsClientRepeat); assertFalse(d.supportsAutoAddConfig)
        assertTrue(d.copy(firmwareVersionString = "v1.12").supportsAutoAddConfig)
        assertTrue(d.copy(firmwareVersionString = "v1.14").supportsAutoAddMaxHops)
        assertTrue(d.copy(firmwareVersion = 13u, firmwareVersionString = "fork").supportsAdHocRepeaterRequest)
        assertTrue(d.copy(firmwareVersionString = "v1.16").supportsAdHocRepeaterRequest)
        assertFalse(d.copy(firmwareVersion = 13u, firmwareVersionString = "v99.0").supportsRadioCLI)
        assertTrue(d.copy(firmwareVersion = 14u).supportsRadioCLI)
        assertTrue(d.copy(firmwareVersion = 11u).supportsDefaultFloodScope)
        assertTrue(d.copy(firmwareVersion = 12u).supportsUnscopedFloodSend)
        assertEquals(4L, d.copy(pathHashMode = 2u).traceHashSize)
        assertEquals(256L, d.copy(pathHashMode = 255u).hashSize)
        assertEquals(0L, d.copy(pathHashMode = 64u).traceHashSize)
        assertEquals(TelemetryModes.of(3u, 2u, 1u), TelemetryModes.fromPacked(0x1Bu))
        assertEquals(0x3F.toUByte(), TelemetryModes.of(255u, 255u, 255u).packed)
        assertEquals(AutoAddMode.ALL, AutoAddMode.mode(false, 0u))
        assertEquals(AutoAddMode.MANUAL, AutoAddMode.mode(true, 1u))
        assertEquals(AutoAddMode.SELECTED_TYPES, AutoAddMode.mode(true, 0x10u))
        assertFailsWith<IllegalArgumentException> { d.updating(testSelfInfo().copy(radioFrequency = Double.NaN)) }
    }

    @Test fun remoteCliEligibilityUsesRawContactTypeAndBlockState() {
        val chat = testContact().copy(typeRawValue = 1u, isBlocked = false)
        assertTrue(chat.showsRemoteCLI(true))
        assertFalse(chat.showsRemoteCLI(false))
        assertFalse(chat.copy(isBlocked = true).showsRemoteCLI(true))
        assertFalse(chat.copy(typeRawValue = 4u).showsRemoteCLI(true))
        assertFalse(chat.copy(typeRawValue = 255u).showsRemoteCLI(true))
    }

    @Test fun backupCleaningAndRedactionPreserveReconciliationKeysAndIntentionalSourceFields() {
        val d = testDevice().copy(
            radioId = RADIO, publicKey = KEY, clientRepeat = true, blePin = 123456u, appliedRadioPresetID = "br",
            defaultFloodScopeName = "US", knownRegions = SnapshotList.of("US"), connectionMethods = SnapshotList.of(
                ConnectionMethod.Bluetooth(UUID.randomUUID()), ConnectionMethod.WiFi("radio.local", 5000u),
            ),
        ).savingPreRepeatSettings()
        val cleaned = d.cleanedForImport(); assertFalse(cleaned.isActive); assertEquals(1, cleaned.connectionMethods.size)
        val newId = UUID.randomUUID(); val redacted = d.redactedForBackup(newId)
        assertEquals(newId, redacted.id); assertEquals(d.radioId, redacted.radioId); assertEquals(d.publicKey, redacted.publicKey)
        assertEquals(0u, redacted.blePin); assertFalse(redacted.clientRepeat); assertFalse(redacted.hasPreRepeatSettings)
        assertNull(redacted.appliedRadioPresetID); assertEquals("US", redacted.defaultFloodScopeName)
        assertEquals(d.knownRegions, redacted.knownRegions); assertEquals(1, redacted.connectionMethods.size)
        assertEquals(d.isActive, redacted.isActive)
    }

    @Test fun messageRoutingReactionDatesAndSenderClusterOrderFollowSourcePrecedence() {
        val m = testMessage().copy(pathLength = 255u, senderTimestamp = UInt.MAX_VALUE)
        assertTrue(m.isDirectRouted); assertEquals(UInt.MAX_VALUE, m.reactionTimestamp); assertEquals(63L, m.hopCount)
        assertNull(m.pathHashSizeIfKnown); assertEquals(Instant.ofEpochSecond(0xFFFF_FFFFL), m.wireSentDate)
        assertTrue(m.copy(channelIndex = 0u, routeType = RouteType.DIRECT).isFloodRouted)
        assertTrue(m.copy(routeType = RouteType.TC_FLOOD).isFloodRouted)
        assertTrue(m.copy(pathLength = 1u, routeType = RouteType.TC_DIRECT).isDirectRouted)
        val first = testMessage().copy(timestamp = 3u, createdAt = AT, sortDate = AT)
        val second = first.copy(id = UUID.randomUUID(), timestamp = 1u, createdAt = AT.plusSeconds(1), sortDate = AT.plusSeconds(1))
        val outside = first.copy(id = UUID.randomUUID(), timestamp = 0u, sortDate = AT.plusSeconds(7))
        assertEquals(listOf(second, first, outside), MessageDTO.reorderSameSenderClusters(listOf(first, second, outside)))
        assertEquals(listOf(first, second.copy(direction = MessageDirection.INCOMING)), MessageDTO.reorderSameSenderClusters(listOf(first, second.copy(direction = MessageDirection.INCOMING))))
        assertEquals(listOf(first.copy(channelIndex = 1u), second.copy(channelIndex = 1u)), MessageDTO.reorderSameSenderClusters(listOf(first.copy(channelIndex = 1u), second.copy(channelIndex = 1u))))
    }

    @Test fun repeatAndTraceComputedValuesRetainPartialHashesAndSiUnits() {
        val r = testRepeat().copy(pathLength = encodePathLen(3, 2), pathNodes = Bytes.of(128, 255))
        assertEquals(Bytes.of(128, 255), r.repeaterHash); assertEquals(0L, r.hopCount); assertEquals("80FF", r.repeaterHashFormatted)
        assertEquals("-90 dBm", r.rssiFormatted); assertEquals("8.5 dB", r.snrFormatted(Locale.US))
        assertEquals("00", r.copy(pathNodes = Bytes.EMPTY).repeaterHashFormatted)
        val path = testPath(); assertEquals(100L, path.successRate); assertNull(path.averageRoundTripMs)
        val runs = (1L..12L).map { testRun().copy(date = AT.plusSeconds(it), roundTripMs = it) }.snapshot()
        val full = path.copy(runs = runs); assertEquals(12L, full.runCount); assertEquals(6L, full.averageRoundTripMs)
        assertEquals((3L..12L).toList(), full.recentRTTs); assertEquals(listOf(0x31.toUByte(), 0xA7.toUByte()), full.pathHashBytes)
        assertEquals(50L, path.copy(runs = SnapshotList.of(testRun(), testRun().copy(success = false))).successRate)
        assertEquals(11, OCVPreset.LI_ION.ocvArray.size); assertEquals(16, OCVPreset.entries.size)
        assertEquals(OCVPreset.TRACKER_T1000_E, OCVPreset.presetForManufacturer("Seeed Tracker T1000-E"))
        assertEquals(OCVPreset.LILYGO_TBEAM_1W, OCVPreset.presetForManufacturer("LilyGo T-Beam 1W"))
        assertNull(OCVPreset.presetForManufacturer("unknown"))
        assertEquals((1L..11L).toList(), testDevice().copy(ocvPreset = "custom", customOCVArrayString = (1..11).joinToString(",")).activeOCVArray)
        assertEquals(OCVPreset.LI_ION.ocvArray, testContact().copy(ocvPreset = "custom", customOCVArrayString = "bad").activeOCVArray)
    }

    @Test fun firstGpsPointIsAuthoritativeAltitudeIsIndependentAndStatusMetricsSaturateOnlySourceNarrowFields() {
        fun point(lat: Double, alt: Double) = LPPDataPoint(1u, LPPSensorType.GPS, LPPValue.Gps(lat, 1.0, alt))
        assertNull(NodeLocationFix.primaryFix(listOf(point(999.0, 1.0), point(1.0, 2.0))))
        assertEquals(0.0, NodeLocationFix.primaryFix(listOf(point(1.0, 0.0)))?.altitude)
        assertEquals(-500.0, NodeLocationFix.primaryFix(listOf(point(1.0, -500.0)))?.altitude)
        assertEquals(10000.0, NodeLocationFix.primaryFix(listOf(point(1.0, 10000.0)))?.altitude)
        assertNull(NodeLocationFix.primaryFix(listOf(point(1.0, 10000.1)))?.altitude)
        assertFalse(Coordinate(Double.NaN, 1.0).isValid); assertFalse(Coordinate(0.0, 0.0).isValidFix)
        val status = StatusResponse(Bytes.EMPTY, 100000, 0, Long.MIN_VALUE, Long.MAX_VALUE,
            UInt.MAX_VALUE, UInt.MAX_VALUE, 0u, UInt.MAX_VALUE, 0u, 0u, 0u, 0u, 0, -4.0, -1, Long.MAX_VALUE, 0u)
        val metrics = NodeStatusMetrics.fromStatus(status, rxAirtimeSeconds = UInt.MAX_VALUE)
        assertEquals(65535.toUShort(), metrics.batteryMillivolts); assertEquals(Short.MAX_VALUE, metrics.lastRSSI)
        assertEquals(Short.MIN_VALUE, metrics.noiseFloor); assertEquals(0u, metrics.directDuplicates)
        assertEquals(UInt.MAX_VALUE, metrics.floodDuplicates); assertEquals(UInt.MAX_VALUE, metrics.rxAirtimeSeconds)
        assertEquals(UInt.MAX_VALUE, metrics.uptimeSeconds); assertEquals(-4.0, metrics.lastSNR)
        assertNull(testSnapshot().copy(latitude = 1.0, longitude = null).validCoordinate)
    }

    @Test fun sendEnvelopesPreserveThreeAttemptStatesDiscriminatorsCapturedTextAndResendIdentity() {
        val direct = DirectMessageEnvelope(UUID.randomUUID(), UUID.randomUUID(), true)
        val dm = PendingSendDTO.fromEnvelope(direct, RADIO, enqueuedAt = AT)
        assertEquals(direct, dm.directMessageEnvelope()); assertNull(dm.channelMessageEnvelope())
        assertEquals(0L, dm.sequence); assertEquals(0L, dm.attemptCount); assertEquals(0u, dm.messageTimestamp)
        assertNull(dm.copy(attemptCount = null).attemptCount)
        val channel = ChannelMessageEnvelope(UUID.randomUUID(), 255u, true, "captured", UInt.MAX_VALUE, "sender")
        val row = PendingSendDTO.fromEnvelope(channel, RADIO, enqueuedAt = AT)
        assertEquals(channel, row.channelMessageEnvelope()); assertNull(row.directMessageEnvelope())
        assertNull(row.copy(channelIndex = null).channelMessageEnvelope())
        assertNull(dm.copy(contactID = null).directMessageEnvelope())
        val envelope = MessageEnvelope<String, String>(dm.messageID, true, "sender", "resolved", MessageStatus.SENT, AT, false, false, false, null)
        assertTrue(envelope.withStatus(MessageStatus.FAILED).hasFailed)
        listOf(MessageStatus.PENDING, MessageStatus.SENDING, MessageStatus.RETRYING, MessageStatus.DELIVERED).forEach {
            assertFalse(envelope.withStatus(it).hasFailed)
        }
    }

    @Test fun nodeConfigOptionsAndRawFieldNamesArePreservedWithoutPretendingToEncodeAConfig() {
        assertFalse(ConfigSections().anySectionSelected); assertTrue(ConfigSections().selectAll().allSelected)
        assertFalse(ConfigSections().selectAll().deselectAll().anySectionSelected)
        assertTrue(MeshCoreNodeConfig.PositionSettings("invalid", "0").isZero)
        assertFalse(MeshCoreNodeConfig.PositionSettings("1", "0").isZero)
        assertEquals("public_key", NodeConfigWireContract.snakeCaseKeys["publicKey"])
        assertEquals(setOf("custom_name", "out_path"), NodeConfigWireContract.explicitNullContactKeys)
        val config = MeshCoreNodeConfig.OtherSettings(255u, 255u, 255u, 255u, 255u, 255u, 255u)
        assertEquals(255.toUByte(), config.advertisementType)
        assertEquals(listOf(NotificationLevel.MUTED, NotificationLevel.ALL), NotificationLevel.roomLevels)
        assertNull(RemoteNodeRole.fromContactType(ContactType.CHAT)); assertTrue(RoomPermissionLevel.ADMIN.canPost)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), MessageStatus.entries.map { it.rawValue })
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L, 6L), DecryptStatus.entries.map { it.rawValue })
    }

    @Test fun rxProjectionsKeepRawNibbleAndValidateTraceChunkingAndDmPrefixes() {
        val parsed = ParsedRxLogData(-2.0, -128, Bytes.of(128, 255), RouteType.TC_DIRECT, PayloadType.TEXT_MESSAGE,
            3u, 12u, Bytes.of(1, 2, 3, 4), 0u, emptyList(), Bytes.of(128, 255))
        val dto = RxLogEntryDTO.fromParsed(RADIO, parsed, receivedAt = AT)
        assertEquals(12.toUByte(), dto.payloadTypeBits); assertEquals(Bytes.of(128), dto.recipientPrefix); assertEquals(Bytes.of(255), dto.senderPrefix)
        assertNull(dto.copy(routeType = RouteType.FLOOD).senderPrefix)
        val trace = dto.copy(payloadType = PayloadType.TRACE, packetPayload = Bytes(ByteArray(8)) + Bytes.of(1, 128, 255, 0, 1))
        assertEquals(listOf(Bytes.of(128, 255), Bytes.of(0, 1)), assertNotNull(trace.traceTargetHashes).toList())
        assertNull(trace.copy(packetPayload = trace.packetPayload.prefix(12)).traceTargetHashes)
        assertEquals(parsed.packetHash, dto.packetHash); assertEquals("-2.0 dB", dto.snrDisplayString(Locale.US))
    }

    @Test fun sourceUnixFractionsLongCountsAndRoomRawStatusAreNotNarrowed() {
        val instant = instantFromUnixSeconds(BigDecimal("1700000501.1234567"))
        assertEquals(123456700, instant.nano); assertEquals(BigDecimal("1700000501.123456700"), instant.unixSeconds())
        assertEquals(Instant.ofEpochSecond(-1, 500_000_000), instantFromUnixSeconds(-0.5))
        assertFailsWith<IllegalArgumentException> { instantFromUnixSeconds(Double.NaN) }
        assertEquals(Long.MAX_VALUE, testMessage().copy(heardRepeats = Long.MAX_VALUE).heardRepeats)
        val room = testRoomMessage().copy(statusRawValue = 999)
        assertNull(room.knownStatus); assertEquals(MessageStatus.DELIVERED, room.status); assertEquals(999L, room.statusRawValue)
        assertEquals("1700000000-ABCDEF01-AB7FDD42", testRoomMessage().deduplicationKey)
    }
}
