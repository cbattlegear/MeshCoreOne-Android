// AndroidOnly: WP-217 canonical text dump of the seed, field-for-field with the Swift oracle driver.
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageRepeatDTO
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.canonicalString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * Produces the same lines as the oracle's `main.swift`, in `SimulatorConnectionMode` seed order. Doubles are
 * IEEE bit patterns, dates epoch milliseconds, bytes lowercase hex and UUIDs uppercase, so a match is exact.
 */
internal object SimulatorSeedDump {
    fun lines(now: Instant): List<String> = buildList {
        add(device(MockDataProvider.simulatorDevice(now)))
        val contacts = MockDataProvider.contacts(now)
        // The extra channel belongs to v1.7.1, not the independently frozen reference oracle.
        val channels = MockDataProvider.channels(now).filterNot { it.id == MockDataProvider.emergencyChannelID }
        contacts.forEach { add(contact(it)) }
        channels.forEach { add(channel(it)) }
        contacts.forEach { c -> MockDataProvider.messages(c.id, now).forEach { add(message(it)) } }
        channels.forEach { c -> MockDataProvider.channelMessages(c.index, now).forEach { add(message(it)) } }
        MockDataProvider.linkPreviewSeeds.forEach {
            add(row("linkPreview", uuid(it.messageID), it.url, it.title, it.imageData.size.toString(), sha256(it.imageData.toByteArray())))
        }
        MockDataProvider.reactedMessages.forEach { reacted ->
            MockDataProvider.reactions(reacted.messageID, now).forEach { add(reaction(it)) }
            add(row("summary", uuid(reacted.messageID), reacted.summary))
        }
        MockDataProvider.messagesWithRepeats.forEach { id -> MockDataProvider.messageRepeats(id, now).forEach { add(repeatRow(it)) } }
        MockDataProvider.rxLogEntries(now).forEach { add(rx(it)) }
        MockDataProvider.nodeStatusSnapshots(now).forEach { add(snapshot(it)) }
        val image = MockDataProvider.demoImageData
        add(row("demoImage", image.size.toString(), sha256(image.toByteArray())))
    }

    fun sha256(lines: List<String>): String = sha256(lines.joinToString("") { it + "\n" }.toByteArray(Charsets.UTF_8))

    private fun device(d: DeviceDTO) = row(
        "device", uuid(d.id), uuid(d.radioId.value), hex(d.publicKey), d.nodeName, num(d.firmwareVersion),
        d.firmwareVersionString, d.manufacturerName, d.buildDate, num(d.maxContacts), num(d.maxChannels),
        num(d.frequency), num(d.bandwidth), num(d.spreadingFactor), num(d.codingRate), num(d.txPower),
        num(d.maxTxPower), dbl(d.latitude), dbl(d.longitude), num(d.blePin), d.clientRepeat.toString(), num(d.pathHashMode),
        d.manualAddContacts.toString(), num(d.autoAddConfig), num(d.autoAddMaxHops), num(d.multiAcks),
        num(d.telemetryModeBase), num(d.telemetryModeLoc), num(d.telemetryModeEnv), num(d.advertLocationPolicy),
        ms(d.lastConnected), num(d.lastContactSync), d.isActive.toString(), str(d.ocvPreset), str(d.customOCVArrayString),
    )

    private fun contact(c: ContactDTO) = row(
        "contact", uuid(c.id), uuid(c.radioId.value), hex(c.publicKey), c.name, num(c.typeRawValue), num(c.flags),
        num(c.outPathLength), hex(c.outPath), num(c.lastAdvertTimestamp), dbl(c.latitude), dbl(c.longitude),
        num(c.lastModified), num(c.lastHeardTimestamp), str(c.nickname), c.isBlocked.toString(), c.isMuted.toString(),
        c.isFavorite.toString(), ms(c.lastMessageDate), num(c.unreadCount), num(c.unreadMentionCount), str(c.ocvPreset),
        str(c.customOCVArrayString), hex(c.avatarImageData),
    )

    private fun channel(c: ChannelDTO) = row(
        "channel", uuid(c.id), uuid(c.radioId.value), num(c.index), c.name, hex(c.secret), c.isEnabled.toString(),
        ms(c.lastMessageDate), num(c.unreadCount), num(c.unreadMentionCount), num(c.notificationLevel.rawValue),
        c.isFavorite.toString(), c.floodScopeModeRawValue, str(c.regionScope),
    )

    private fun message(m: MessageDTO) = row(
        "message", uuid(m.id), uuid(m.radioId.value), uuid(m.contactID), num(m.channelIndex), m.text, num(m.timestamp),
        ms(m.createdAt), ms(m.sortDate), num(m.direction.rawValue), num(m.status.rawValue), num(m.textType.rawValue),
        num(m.ackCode), num(m.pathLength), dbl(m.snr), hex(m.pathNodes), hex(m.senderKeyPrefix), str(m.senderNodeName),
        m.isRead.toString(), uuid(m.replyToID), num(m.roundTripTime), num(m.heardRepeats), num(m.sendCount),
        num(m.retryAttempt), num(m.maxRetryAttempts), str(m.deduplicationKey), str(m.linkPreviewURL),
        str(m.linkPreviewTitle), hex(m.linkPreviewImageData), hex(m.linkPreviewIconData), m.linkPreviewFetched.toString(),
        m.containsSelfMention.toString(), m.mentionSeen.toString(), m.failureSeen.toString(), m.timestampCorrected.toString(),
        num(m.senderTimestamp), str(m.reactionSummary), num(m.routeType?.rawValue), str(m.regionScope),
        list(m.regionScopeMatches),
    )

    private fun reaction(r: ReactionDTO) = row(
        "reaction", uuid(r.id), uuid(r.messageID), r.emoji, r.senderName, r.messageHash, r.rawText,
        ms(r.receivedAt), num(r.channelIndex), uuid(r.contactID), uuid(r.radioId.value),
    )

    private fun repeatRow(r: MessageRepeatDTO) = row(
        "repeat", uuid(r.id), uuid(r.messageID), ms(r.receivedAt), hex(r.pathNodes), num(r.pathLength),
        dbl(r.snr), num(r.rssi), uuid(r.rxLogEntryID),
    )

    private fun rx(e: RxLogEntryDTO) = row(
        "rx", uuid(e.id), uuid(e.radioId.value), ms(e.receivedAt), dbl(e.snr), num(e.rssi), num(e.routeType.rawValue),
        num(e.payloadType.rawValue), num(e.payloadVersion), hex(e.transportCode), num(e.pathLength),
        hex(e.pathNodes), hex(e.packetPayload), hex(e.rawPayload), e.packetHash, num(e.channelIndex),
        str(e.channelName), num(e.decryptStatus.rawValue), str(e.regionScope), list(e.regionScopeMatches),
        num(e.payloadTypeBits),
    )

    private fun snapshot(s: NodeStatusSnapshotDTO) = row(
        "snapshot", ms(s.timestamp), hex(s.nodePublicKey), num(s.batteryMillivolts), dbl(s.lastSNR),
        num(s.lastRSSI), num(s.noiseFloor), num(s.uptimeSeconds), dbl(s.latitude), dbl(s.longitude), dbl(s.altitude),
    )

    private fun row(vararg fields: String): String = fields.joinToString("|")
    private fun hex(bytes: Bytes?): String = bytes?.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt()) } ?: "nil"
    private fun dbl(value: Double?): String =
        value?.let { String.format(Locale.ROOT, "%016x", java.lang.Double.doubleToRawLongBits(it)) } ?: "nil"
    private fun ms(instant: Instant?): String = instant?.toEpochMilli()?.toString() ?: "nil"
    private fun str(value: String?): String = value ?: "nil"
    private fun num(value: Any?): String = value?.toString() ?: "nil"
    private fun uuid(value: UUID?): String = value?.canonicalString() ?: "nil"
    private fun list(values: List<String>): String = values.joinToString(",", prefix = "[", postfix = "]")
    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { String.format(Locale.ROOT, "%02x", it) }
}
