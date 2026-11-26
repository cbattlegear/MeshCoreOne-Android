// PortedFrom: MeshCore/Sources/MeshCore/Protocol/PacketParser.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: inject the wall clock only for the source's missing contact-end timestamp.
package com.meshcoreone.android.core.protocol.parser

import com.meshcoreone.android.core.protocol.bytes.ByteReader
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import com.meshcoreone.android.core.protocol.model.BatteryInfo
import com.meshcoreone.android.core.protocol.model.PacketSize
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.model.StatsType
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Clock

object PacketParser {
    fun parse(data: Bytes, clock: Clock = Clock.systemUTC()): MeshEvent {
        if (data.isEmpty) return MeshEvent.ParseFailure(data, "Empty packet")
        val code = ResponseCode.fromRawValue(data[0])
            ?: return MeshEvent.ParseFailure(data, "Unknown response code: 0x${data[0].hexByte()}")
        val payload = data.slice(1, data.size)
        return when (code) {
            ResponseCode.OK -> MeshEvent.Ok(if (payload.size >= 4) ByteReader(payload).readUInt32LE() else null)
            ResponseCode.ERROR -> MeshEvent.Error(payload.firstOrNull())
            ResponseCode.BATTERY -> parseBattery(payload)
            ResponseCode.CURRENT_TIME -> if (payload.size < 4) short(payload, "CurrentTime response", 4)
                else MeshEvent.CurrentTime(ByteReader(payload).readUInt32LE().epoch())
            ResponseCode.DISABLED -> MeshEvent.Disabled("private_key_export_disabled")
            ResponseCode.SELF_INFO -> Parsers.SelfInfo.parse(payload)
            ResponseCode.DEVICE_INFO -> Parsers.DeviceInfo.parse(payload)
            ResponseCode.PRIVATE_KEY -> Parsers.PrivateKey.parse(payload)
            ResponseCode.ADVERT_PATH -> Parsers.AdvertPathResponse.parse(payload)
            ResponseCode.TUNING_PARAMS -> Parsers.TuningParamsResponse.parse(payload)
            ResponseCode.AUTO_ADD_CONFIG -> if (payload.isEmpty) short(payload, "AutoAddConfig response", 1)
                else MeshEvent.AutoAddConfig(AutoAddConfig(payload[0], if (payload.size >= 2) payload[1] else 0u))
            ResponseCode.ALLOWED_REPEAT_FREQ -> Parsers.AllowedRepeatFreq.parse(payload)
            ResponseCode.DEFAULT_FLOOD_SCOPE -> Parsers.DefaultFloodScope.parse(payload)
            ResponseCode.CLI_REPLY -> decodeCliReply(payload)
            ResponseCode.CONTACT_START -> if (payload.size < PacketSize.CONTACTS_START_MINIMUM) {
                short(payload, "ContactStart response", PacketSize.CONTACTS_START_MINIMUM)
            } else MeshEvent.ContactsStart(ByteReader(payload).readUInt32LE().toLong())
            ResponseCode.CONTACT -> Parsers.Contact.parse(payload)
            ResponseCode.CONTACT_END -> MeshEvent.ContactsEnd(
                if (payload.size >= 4) ByteReader(payload).readUInt32LE().epoch() else clock.instant(),
            )
            ResponseCode.CONTACT_URI -> MeshEvent.ContactURI("meshcore://${payload.hexString}")
            ResponseCode.MESSAGE_SENT -> parseMessageSent(payload)
            ResponseCode.NO_MORE_MESSAGES -> MeshEvent.NoMoreMessages
            ResponseCode.CONTACT_MESSAGE_RECEIVED -> Parsers.ContactMessage.parse(payload, Parsers.ContactMessage.Version.V1)
            ResponseCode.CONTACT_MESSAGE_RECEIVED_V3 -> Parsers.ContactMessage.parse(payload, Parsers.ContactMessage.Version.V3)
            ResponseCode.CHANNEL_MESSAGE_RECEIVED -> Parsers.ChannelMessage.parse(payload, Parsers.ChannelMessage.Version.V1)
            ResponseCode.CHANNEL_MESSAGE_RECEIVED_V3 -> Parsers.ChannelMessage.parse(payload, Parsers.ChannelMessage.Version.V3)
            ResponseCode.CHANNEL_DATA_RECEIVED -> Parsers.ChannelDatagram.parse(payload)
            ResponseCode.ACK -> parseAck(payload)
            ResponseCode.MESSAGES_WAITING -> MeshEvent.MessagesWaiting
            ResponseCode.ADVERTISEMENT -> Parsers.Advertisement.parse(payload)
            ResponseCode.NEW_ADVERTISEMENT -> Parsers.NewAdvertisement.parse(payload)
            ResponseCode.PATH_UPDATE -> Parsers.PathUpdate.parse(payload)
            ResponseCode.STATUS_RESPONSE -> Parsers.StatusResponse.parse(payload)
            ResponseCode.TELEMETRY_RESPONSE -> Parsers.TelemetryResponse.parse(payload)
            ResponseCode.BINARY_RESPONSE -> Parsers.BinaryResponse.parse(payload)
            ResponseCode.PATH_DISCOVERY_RESPONSE -> Parsers.PathDiscoveryResponse.parse(payload)
            ResponseCode.CONTROL_DATA -> Parsers.ControlData.parse(payload)
            ResponseCode.CONTACT_DELETED -> Parsers.ContactDeleted.parse(payload)
            ResponseCode.CONTACTS_FULL -> Parsers.ContactsFull.parse(payload)
            ResponseCode.LOGIN_SUCCESS -> Parsers.LoginSuccess.parse(payload)
            ResponseCode.LOGIN_FAILED -> MeshEvent.LoginFailed(if (payload.size >= 7) payload.slice(1, 7) else null)
            ResponseCode.SIGN_START -> if (payload.size < PacketSize.SIGN_START_MINIMUM) {
                short(payload, "SignStart response", PacketSize.SIGN_START_MINIMUM)
            } else MeshEvent.SignStart(ByteReader(payload, 1).readUInt32LE().toLong())
            ResponseCode.SIGNATURE -> Parsers.Signature.parse(payload)
            ResponseCode.STATS -> parseStats(payload)
            ResponseCode.CHANNEL_INFO -> Parsers.ChannelInfo.parse(payload)
            ResponseCode.CUSTOM_VARS -> Parsers.CustomVars.parse(payload)
            ResponseCode.RAW_DATA -> Parsers.RawData.parse(payload)
            ResponseCode.LOG_DATA -> Parsers.LogData.parse(payload)
            ResponseCode.TRACE_DATA -> Parsers.TraceData.parse(payload)
        }
    }

    private fun decodeCliReply(payload: Bytes): MeshEvent.CliReply = try {
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload.toByteArray())).toString()
        MeshEvent.CliReply(text)
    } catch (_: CharacterCodingException) {
        MeshEvent.CliReply("", validUtf8 = false)
    }

    private fun parseBattery(data: Bytes): MeshEvent {
        if (data.size < PacketSize.BATTERY_MINIMUM) return short(data, "Battery response", PacketSize.BATTERY_MINIMUM)
        if (data.size > PacketSize.BATTERY_MINIMUM && data.size < PacketSize.BATTERY_EXTENDED) {
            return MeshEvent.ParseFailure(data, "Battery response has partial extended payload: ${data.size} < ${PacketSize.BATTERY_EXTENDED}")
        }
        val reader = ByteReader(data)
        val level = reader.readUInt16LE().toLong()
        val used = if (data.size >= PacketSize.BATTERY_EXTENDED) reader.readUInt32LE().toLong() else null
        val total = if (data.size >= PacketSize.BATTERY_EXTENDED) reader.readUInt32LE().toLong() else null
        return MeshEvent.Battery(BatteryInfo(level, used, total))
    }

    private fun parseMessageSent(data: Bytes): MeshEvent {
        if (data.size < PacketSize.MESSAGE_SENT_MINIMUM) return short(data, "MessageSent response", PacketSize.MESSAGE_SENT_MINIMUM)
        val reader = ByteReader(data)
        return MeshEvent.MessageSent(MessageSentInfo(reader.readUInt8(), reader.readBytes(4), reader.readUInt32LE()))
    }

    private fun parseAck(data: Bytes): MeshEvent {
        if (data.size < PacketSize.ACK_MINIMUM) return short(data, "Ack response", PacketSize.ACK_MINIMUM)
        val reader = ByteReader(data)
        return MeshEvent.Acknowledgement(reader.readBytes(4), if (reader.remaining >= 4) reader.readUInt32LE() else null)
    }

    private fun parseStats(data: Bytes): MeshEvent {
        if (data.isEmpty) return short(data, "Stats response", 1)
        val payload = data.slice(1, data.size)
        return when (StatsType.fromRawValue(data[0])) {
            StatsType.CORE -> Parsers.CoreStats.parse(payload)
            StatsType.RADIO -> Parsers.RadioStats.parse(payload)
            StatsType.PACKETS -> Parsers.PacketStats.parse(payload)
            null -> MeshEvent.ParseFailure(data, "Unknown stats type: ${data[0]}")
        }
    }
}
