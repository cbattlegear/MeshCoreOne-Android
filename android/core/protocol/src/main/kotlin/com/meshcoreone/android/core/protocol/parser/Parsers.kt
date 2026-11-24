// PortedFrom: MeshCore/Sources/MeshCore/Protocol/Parsers.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/Parsers+Auth.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/Parsers+Binary.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/Parsers+Channels.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/Parsers+Contacts.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/Parsers+Device.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/Parsers+Diagnostics.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/Parsers+Messaging.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/Parsers+Network.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/Parsers+Stats.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/Parsers+Status.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.parser

import com.meshcoreone.android.core.protocol.bytes.ByteReader
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.AdvertPathResponse as AdvertPathPayload
import com.meshcoreone.android.core.protocol.event.ChannelDatagram as DatagramPayload
import com.meshcoreone.android.core.protocol.event.ChannelInfo as ChannelInfoPayload
import com.meshcoreone.android.core.protocol.event.ChannelMessage as ChannelMessagePayload
import com.meshcoreone.android.core.protocol.event.ContactMessage as ContactMessagePayload
import com.meshcoreone.android.core.protocol.event.ControlDataInfo
import com.meshcoreone.android.core.protocol.event.CoreStats as CoreStatsPayload
import com.meshcoreone.android.core.protocol.event.DefaultFloodScope as FloodScopePayload
import com.meshcoreone.android.core.protocol.event.DiscoverResponse
import com.meshcoreone.android.core.protocol.event.FrequencyRange
import com.meshcoreone.android.core.protocol.event.LogDataInfo
import com.meshcoreone.android.core.protocol.event.LoginInfo
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.PacketStats as PacketStatsPayload
import com.meshcoreone.android.core.protocol.event.PathInfo
import com.meshcoreone.android.core.protocol.event.RadioStats as RadioStatsPayload
import com.meshcoreone.android.core.protocol.event.RawDataInfo
import com.meshcoreone.android.core.protocol.event.StatusResponse as StatusPayload
import com.meshcoreone.android.core.protocol.event.TelemetryResponse as TelemetryPayload
import com.meshcoreone.android.core.protocol.event.TraceInfo
import com.meshcoreone.android.core.protocol.event.TraceNode
import com.meshcoreone.android.core.protocol.event.TuningParamsResponse as TuningPayload
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.model.PacketSize
import com.meshcoreone.android.core.protocol.model.SelfInfo as SelfInfoPayload
import com.meshcoreone.android.core.protocol.model.decodePathLen
import java.time.Instant

object Parsers {
    object SelfInfo {
        fun parse(data: Bytes): MeshEvent {
            if (data.size < PacketSize.SELF_INFO_MINIMUM) {
                return short(data, "SelfInfo response", PacketSize.SELF_INFO_MINIMUM)
            }
            val reader = ByteReader(data)
            val advType = reader.readUInt8()
            val txPower = reader.readInt8()
            val maxTxPower = reader.readInt8()
            val publicKey = reader.readBytes(32)
            val latitude = reader.readInt32LE() / 1_000_000.0
            val longitude = reader.readInt32LE() / 1_000_000.0
            val multiAcks = reader.readUInt8()
            val advLocPolicy = reader.readUInt8()
            val telemetryMode = reader.readUInt8().toInt()
            val manualAdd = reader.readUInt8() > 0u
            val frequency = reader.readUInt32LE().toDouble() / 1000.0
            val bandwidth = reader.readUInt32LE().toDouble() / 1000.0
            val sf = reader.readUInt8()
            val cr = reader.readUInt8()
            val name = reader.readBytes(reader.remaining).exactUtf8OrEmpty("SelfInfo name").trimControls()
            return MeshEvent.SelfInfo(SelfInfoPayload(
                advType, txPower, maxTxPower, publicKey, latitude, longitude, multiAcks, advLocPolicy,
                ((telemetryMode shr 4) and 3).toUByte(), ((telemetryMode shr 2) and 3).toUByte(),
                (telemetryMode and 3).toUByte(), manualAdd, frequency, bandwidth, sf, cr, name,
            ))
        }
    }

    object DeviceInfo {
        fun parse(data: Bytes): MeshEvent {
            if (data.isEmpty) return MeshEvent.ParseFailure(data, "DeviceInfo response empty")
            val reader = ByteReader(data)
            val version = reader.readUInt8()
            if (version >= 3u && data.size < PacketSize.DEVICE_INFO_V3_FULL) {
                return short(data, "DeviceInfo v$version response", PacketSize.DEVICE_INFO_V3_FULL)
            }
            var maxContacts = 0L
            var maxChannels = 0L
            var pin = 0u
            var build = ""
            var model = ""
            var hardwareVersion = ""
            if (version >= 3u) {
                maxContacts = reader.readUInt8().toLong() * 2
                maxChannels = reader.readUInt8().toLong()
                pin = reader.readUInt32LE()
                build = reader.readBytes(12).exactUtf8OrEmpty("DeviceInfo build").trimControls()
                model = reader.readBytes(40).exactUtf8OrEmpty("DeviceInfo model").trimControls()
                hardwareVersion = reader.readBytes(20).exactUtf8OrEmpty("DeviceInfo version").trimControls()
            }
            val repeat = version >= 9u && reader.remaining > 0 && reader.readUInt8() != 0.toUByte()
            val mode = if (version >= 10u && reader.remaining > 0) reader.readUInt8() else 0.toUByte()
            return MeshEvent.DeviceInfo(DeviceCapabilities(
                version, maxContacts, maxChannels, pin, build, model, hardwareVersion, repeat, mode,
            ))
        }
    }

    object PrivateKey {
        fun parse(data: Bytes): MeshEvent = if (data.size < PacketSize.PRIVATE_KEY_MINIMUM) {
            short(data, "PrivateKey response", PacketSize.PRIVATE_KEY_MINIMUM)
        } else MeshEvent.PrivateKey(data.prefix(PacketSize.PRIVATE_KEY_MINIMUM))
    }

    object CustomVars {
        fun parse(data: Bytes): MeshEvent {
            val values = linkedMapOf<String, String>()
            for (pair in data.exactUtf8OrEmpty("CustomVars").splitOmittingEmpty(',')) {
                val parts = pair.splitOmittingEmpty(':', maxSplits = 1)
                if (parts.size == 2) values[parts[0]] = parts[1]
            }
            return MeshEvent.CustomVars(values)
        }
    }

    fun parseContactData(data: Bytes): MeshContact? {
        if (data.size < PacketSize.CONTACT) {
            parserLogger.fine("Contact data too short: ${data.size} < ${PacketSize.CONTACT}")
            return null
        }
        val reader = ByteReader(data)
        val publicKey = reader.readBytes(32)
        val typeByte = reader.readUInt8()
        val flags = ContactFlags(reader.readUInt8())
        val pathLength = reader.readUInt8()
        if (pathLength != 0xff.toUByte() && decodePathLen(pathLength) == null) {
            parserLogger.fine("Contact data uses reserved path length encoding: ${pathLength.hexByte()}")
            return null
        }
        val path = reader.readBytes(64).prefix(decodePathLen(pathLength)?.byteLength ?: 0)
        val name = reader.readBytes(32).beforeNull().decodingLongestValidUtf8Prefix().trimControls()
        val lastAdvert = reader.readUInt32LE().epoch()
        val latitude = reader.readInt32LE() / 1_000_000.0
        val longitude = reader.readInt32LE() / 1_000_000.0
        val lastModified = reader.readUInt32LE().epoch()
        return MeshContact(
            publicKey.hexString, publicKey, ContactType.fromRawValue(typeByte) ?: ContactType.UNKNOWN,
            flags, pathLength, path, name, lastAdvert, latitude, longitude, lastModified, typeByte,
        )
    }

    object Contact {
        fun parse(data: Bytes): MeshEvent {
            if (data.size >= PacketSize.CONTACT) {
                val length = data[34]
                if (length != 0xff.toUByte() && decodePathLen(length) == null) {
                    return MeshEvent.ParseFailure(
                        data, "Contact response uses reserved path length encoding: 0x${length.hexByte()}",
                    )
                }
            }
            val contact = parseContactData(data) ?: return short(data, "Contact response", PacketSize.CONTACT)
            return MeshEvent.Contact(contact)
        }
    }

    object Advertisement {
        fun parse(data: Bytes): MeshEvent = if (data.size < 32) {
            short(data, "Advertisement", 32)
        } else MeshEvent.Advertisement(data.prefix(32))
    }

    object NewAdvertisement {
        fun parse(data: Bytes): MeshEvent {
            parseContactData(data)?.let { return MeshEvent.NewContact(it) }
            return MeshEvent.ParseFailure(data, if (data.size >= 32) {
                "NewAdvertisement has public key but insufficient contact data: ${data.size} < ${PacketSize.CONTACT}"
            } else "NewAdvertisement too short: ${data.size}")
        }
    }

    object PathUpdate {
        fun parse(data: Bytes): MeshEvent = if (data.size < 32) {
            short(data, "PathUpdate", 32)
        } else MeshEvent.PathUpdate(data.prefix(32))
    }

    object ContactDeleted {
        fun parse(data: Bytes): MeshEvent = if (data.size < PacketSize.CONTACT_DELETED_PUBLIC_KEY) {
            short(data, "ContactDeleted", PacketSize.CONTACT_DELETED_PUBLIC_KEY)
        } else MeshEvent.ContactDeleted(data.prefix(PacketSize.CONTACT_DELETED_PUBLIC_KEY))
    }

    object ContactsFull {
        fun parse(data: Bytes): MeshEvent = MeshEvent.ContactsFull
    }

    object ContactMessage {
        enum class Version { V1, V3 }

        fun parse(data: Bytes, version: Version): MeshEvent {
            val minimum = if (version == Version.V3) {
                PacketSize.CONTACT_MESSAGE_V3_MINIMUM
            } else PacketSize.CONTACT_MESSAGE_V1_MINIMUM
            if (data.size < minimum) return short(data, "ContactMessage response", minimum)
            val reader = ByteReader(data)
            val snr = if (version == Version.V3) reader.readInt8().toDouble() / 4.0 else null
            if (version == Version.V3) reader.readBytes(2)
            val prefix = reader.readBytes(6)
            val pathLength = reader.readUInt8()
            val textType = reader.readUInt8()
            val timestamp = reader.readUInt32LE().epoch()
            val signature = if (textType == 2.toUByte()) {
                if (reader.remaining < 4) {
                    return MeshEvent.ParseFailure(
                        data, "ContactMessage signature truncated: ${data.size} < ${reader.position + 4}",
                    )
                }
                reader.readBytes(4)
            } else null
            return MeshEvent.ContactMessageReceived(ContactMessagePayload(
                prefix, pathLength, textType, timestamp, signature,
                reader.readBytes(reader.remaining).lossyUtf8("ContactMessage"), snr,
            ))
        }
    }

    object ChannelMessage {
        enum class Version { V1, V3 }

        fun parse(data: Bytes, version: Version): MeshEvent {
            val minimum = if (version == Version.V3) {
                PacketSize.CHANNEL_MESSAGE_V3_MINIMUM
            } else PacketSize.CHANNEL_MESSAGE_V1_MINIMUM
            if (data.size < minimum) return short(data, "ChannelMessage response", minimum)
            val reader = ByteReader(data)
            val snr = if (version == Version.V3) reader.readInt8().toDouble() / 4.0 else null
            if (version == Version.V3) reader.readBytes(2)
            val channel = reader.readUInt8()
            val pathLength = reader.readUInt8()
            val textType = reader.readUInt8()
            val timestamp = reader.readUInt32LE().epoch()
            return MeshEvent.ChannelMessageReceived(ChannelMessagePayload(
                channel, pathLength, textType, timestamp, reader.readBytes(reader.remaining).lossyUtf8("ChannelMessage"), snr,
            ))
        }
    }

    object ChannelDatagram {
        fun parse(data: Bytes): MeshEvent {
            if (data.size < PacketSize.CHANNEL_DATAGRAM_MINIMUM) {
                return short(data, "ChannelDatagram response", PacketSize.CHANNEL_DATAGRAM_MINIMUM)
            }
            val reader = ByteReader(data)
            val snr = reader.readInt8().toDouble() / 4.0
            reader.readBytes(2)
            val channel = reader.readUInt8()
            val pathLength = reader.readUInt8()
            val type = reader.readUInt16LE()
            val declared = reader.readUInt8().toInt()
            return MeshEvent.ChannelDataReceived(DatagramPayload(
                channel, pathLength, type, reader.readBytes(minOf(declared, reader.remaining)), snr,
            ))
        }
    }

    object DefaultFloodScope {
        fun parse(data: Bytes): MeshEvent {
            if (data.isEmpty) return MeshEvent.DefaultFloodScope(null)
            if (data.size != PacketSize.DEFAULT_FLOOD_SCOPE_SET) {
                return MeshEvent.ParseFailure(
                    data, "DefaultFloodScope response wrong size: ${data.size}, expected 0 or ${PacketSize.DEFAULT_FLOOD_SCOPE_SET}",
                )
            }
            val reader = ByteReader(data)
            val name = reader.readBytes(PacketSize.DEFAULT_FLOOD_SCOPE_NAME_FIELD).beforeNull().lossyUtf8("DefaultFloodScope")
            return MeshEvent.DefaultFloodScope(FloodScopePayload(name, reader.readBytes(PacketSize.DEFAULT_FLOOD_SCOPE_KEY_BYTES)))
        }
    }

    object ChannelInfo {
        fun parse(data: Bytes): MeshEvent {
            if (data.size < PacketSize.CHANNEL_INFO_MINIMUM) return short(data, "ChannelInfo", PacketSize.CHANNEL_INFO_MINIMUM)
            val reader = ByteReader(data)
            val index = reader.readUInt8()
            val name = reader.readBytes(32).beforeNull().lossyUtf8("ChannelInfo")
            return MeshEvent.ChannelInfo(ChannelInfoPayload(index, name, reader.readBytes(16)))
        }
    }

    object AdvertPathResponse {
        fun parse(data: Bytes): MeshEvent {
            if (data.size < 5) return MeshEvent.ParseFailure(data, "AdvertPathResponse too short: ${data.size} bytes, need 5")
            val reader = ByteReader(data)
            val timestamp = reader.readUInt32LE()
            val pathLength = reader.readUInt8()
            val decoded = decodePathLen(pathLength) ?: return MeshEvent.ParseFailure(
                data, "AdvertPathResponse uses reserved path length encoding: 0x${pathLength.hexByte()}",
            )
            if (reader.remaining < decoded.byteLength) {
                return MeshEvent.ParseFailure(data, "AdvertPathResponse path truncated: ${data.size} < ${5 + decoded.byteLength}")
            }
            return MeshEvent.AdvertPathResponse(AdvertPathPayload(timestamp, pathLength, reader.readBytes(decoded.byteLength)))
        }
    }

    object TuningParamsResponse {
        fun parse(data: Bytes): MeshEvent {
            if (data.size < 8) return MeshEvent.ParseFailure(data, "TuningParamsResponse too short: ${data.size} bytes, need 8")
            val reader = ByteReader(data)
            return MeshEvent.TuningParamsResponse(TuningPayload(
                reader.readUInt32LE().toDouble() / 1000.0, reader.readUInt32LE().toDouble() / 1000.0,
            ))
        }
    }

    object AllowedRepeatFreq {
        fun parse(data: Bytes): MeshEvent {
            val reader = ByteReader(data)
            val ranges = mutableListOf<FrequencyRange>()
            while (reader.remaining >= 8) ranges += FrequencyRange(reader.readUInt32LE(), reader.readUInt32LE())
            if (reader.remaining > 0) parserLogger.fine("AllowedRepeatFreq: Incomplete trailing range (${reader.remaining} bytes)")
            return MeshEvent.AllowedRepeatFreq(ranges)
        }
    }

    object CoreStats {
        fun parse(data: Bytes): MeshEvent {
            if (data.size < PacketSize.CORE_STATS_MINIMUM) return short(data, "CoreStats", PacketSize.CORE_STATS_MINIMUM)
            val reader = ByteReader(data)
            return MeshEvent.StatsCore(CoreStatsPayload(
                reader.readUInt16LE(), reader.readUInt32LE(), reader.readUInt16LE(), reader.readUInt8(),
            ))
        }
    }

    object RadioStats {
        fun parse(data: Bytes): MeshEvent {
            if (data.size < PacketSize.RADIO_STATS_MINIMUM) return short(data, "RadioStats", PacketSize.RADIO_STATS_MINIMUM)
            val reader = ByteReader(data)
            return MeshEvent.StatsRadio(RadioStatsPayload(
                reader.readInt16LE(), reader.readInt8(), reader.readInt8().toDouble() / 4.0,
                reader.readUInt32LE(), reader.readUInt32LE(),
            ))
        }
    }

    object PacketStats {
        fun parse(data: Bytes): MeshEvent {
            if (data.size < PacketSize.PACKET_STATS_MINIMUM) return short(data, "PacketStats", PacketSize.PACKET_STATS_MINIMUM)
            val reader = ByteReader(data)
            return MeshEvent.StatsPackets(PacketStatsPayload(
                reader.readUInt32LE(), reader.readUInt32LE(), reader.readUInt32LE(), reader.readUInt32LE(),
                reader.readUInt32LE(), reader.readUInt32LE(), if (reader.remaining >= 4) reader.readUInt32LE() else 0u,
            ))
        }
    }

    object LoginSuccess {
        fun parse(data: Bytes): MeshEvent {
            if (data.size < PacketSize.LOGIN_SUCCESS_MINIMUM) {
                parserLogger.warning("LoginSuccess: Short payload; preserving the source non-admin, zero-permission fallback")
                return MeshEvent.LoginSuccess(LoginInfo(0u, false, Bytes.EMPTY))
            }
            val prefix = data.slice(1, 7)
            if (data.size >= PacketSize.LOGIN_SUCCESS_EXTENDED) {
                val admin = data[0] == 1.toUByte()
                val permissions = if (admin) 2 else if (data[11] == 2.toUByte()) 1 else 0
                val timestamp = ByteReader(data, 7).readUInt32LE()
                return MeshEvent.LoginSuccess(LoginInfo(permissions.toUByte(), admin, prefix, timestamp.takeUnless { it == 0u }?.epoch()))
            }
            val admin = data[0] == 1.toUByte()
            val permissions = when (data[0].toInt()) { 1 -> 2; 2 -> 0; else -> 1 }
            return MeshEvent.LoginSuccess(LoginInfo(permissions.toUByte(), admin, prefix))
        }
    }

    object StatusResponse {
        fun parse(data: Bytes, layout: StatusPayload.Layout = StatusPayload.Layout.REPEATER): MeshEvent {
            if (data.size < PacketSize.STATUS_RESPONSE_MINIMUM) {
                return short(data, "StatusResponse", PacketSize.STATUS_RESPONSE_MINIMUM)
            }
            return MeshEvent.StatusResponse(readStatus(data, data.slice(1, 7), 7, layout, push = true))
        }

        fun parseFromBinaryResponse(
            data: Bytes,
            publicKeyPrefix: Bytes,
            layout: StatusPayload.Layout = StatusPayload.Layout.REPEATER,
        ): StatusPayload? {
            if (data.size < PacketSize.BINARY_RESPONSE_STATUS_BASE) {
                parserLogger.fine("Binary status too short: ${data.size} < ${PacketSize.BINARY_RESPONSE_STATUS_BASE}")
                return null
            }
            return readStatus(data, publicKeyPrefix, 0, layout, push = false)
        }
    }

    object TelemetryResponse {
        fun parse(data: Bytes): MeshEvent {
            if (data.size < 7) return MeshEvent.ParseFailure(data, "TelemetryResponse too short: ${data.size} bytes, need 7")
            return MeshEvent.TelemetryResponse(TelemetryPayload(data.slice(1, 7), null, data.slice(7, data.size)))
        }

        fun parseFromBinaryResponse(data: Bytes, publicKeyPrefix: Bytes): TelemetryPayload =
            TelemetryPayload(publicKeyPrefix, null, data)
    }

    object BinaryResponse {
        fun parse(data: Bytes): MeshEvent = if (data.size < 5) short(data, "BinaryResponse", 5)
            else MeshEvent.BinaryResponse(data.slice(1, 5), data.slice(5, data.size))
    }

    object PathDiscoveryResponse {
        fun parse(data: Bytes): MeshEvent {
            if (data.size < PacketSize.PATH_DISCOVERY_MINIMUM) {
                return MeshEvent.ParseFailure(
                    data, "PathDiscoveryResponse too short: ${data.size} bytes, need ${PacketSize.PATH_DISCOVERY_MINIMUM}",
                )
            }
            val reader = ByteReader(data, 1)
            val prefix = reader.readBytes(6)
            val outLength = reader.readUInt8()
            val outSize = decodePathLen(outLength)?.byteLength ?: 0
            if (reader.remaining < outSize) {
                return MeshEvent.ParseFailure(
                    data, "PathDiscoveryResponse truncated outbound path: need $outSize bytes, have ${reader.remaining}",
                )
            }
            val outPath = reader.readBytes(outSize)
            val inLength = if (reader.remaining > 0) reader.readUInt8() else 0.toUByte()
            val inSize = decodePathLen(inLength)?.byteLength ?: 0
            if (reader.remaining < inSize) {
                return MeshEvent.ParseFailure(
                    data, "PathDiscoveryResponse truncated inbound path: need $inSize bytes, have ${reader.remaining}",
                )
            }
            return MeshEvent.PathResponse(PathInfo(prefix, outLength, outPath, inLength, reader.readBytes(inSize)))
        }
    }

    object ControlData {
        fun parse(data: Bytes): MeshEvent {
            if (data.size < PacketSize.CONTROL_DATA_MINIMUM) return short(data, "ControlData", PacketSize.CONTROL_DATA_MINIMUM)
            val reader = ByteReader(data)
            val snr = reader.readInt8().toDouble() / 4.0
            val rssi = reader.readInt8().toLong()
            val pathLength = reader.readUInt8()
            val type = reader.readUInt8()
            val payload = reader.readBytes(reader.remaining)
            if (type.toInt() and 0xf0 == 0x90 && payload.size >= 5) {
                val inner = ByteReader(payload)
                val snrIn = inner.readInt8().toDouble() / 4.0
                val tag = inner.readBytes(4)
                val keyLength = when {
                    inner.remaining >= 32 -> 32
                    inner.remaining >= 8 -> 8
                    else -> inner.remaining
                }
                return MeshEvent.DiscoverResponse(DiscoverResponse(
                    (type.toInt() and 0x0f).toUByte(), snrIn, snr, rssi, pathLength, tag, inner.readBytes(keyLength),
                ))
            }
            return MeshEvent.ControlData(ControlDataInfo(snr, rssi, pathLength, type, payload))
        }
    }

    object Signature {
        fun parse(data: Bytes): MeshEvent = MeshEvent.Signature(data)
    }

    object TraceData {
        fun parse(data: Bytes): MeshEvent {
            if (data.size < PacketSize.TRACE_DATA_MINIMUM) {
                return MeshEvent.ParseFailure(data, "TraceData too short: ${data.size} bytes, need ${PacketSize.TRACE_DATA_MINIMUM}")
            }
            val reader = ByteReader(data, 1)
            val length = reader.readUInt8()
            val flags = reader.readUInt8()
            val hashSize = 1 shl (flags.toInt() and 3)
            val hopCount = length.toInt() / hashSize
            val tag = reader.readUInt32LE()
            val auth = reader.readUInt32LE()
            val required = reader.position + length.toInt() + hopCount + 1
            if (data.size < required) return MeshEvent.ParseFailure(data, "TraceData too short for path: need $required, have ${data.size}")
            val hashes = reader.readBytes(length.toInt())
            val path = MutableList(hopCount) { index ->
                val hash = hashes.slice(index * hashSize, (index + 1) * hashSize)
                TraceNode(hash.takeUnless { it.all { byte -> byte == 0xff.toUByte() } }, reader.readInt8().toDouble() / 4.0)
            }
            path += TraceNode(null, reader.readInt8().toDouble() / 4.0)
            return MeshEvent.TraceData(TraceInfo(tag, auth, flags, length, path))
        }
    }

    object RawData {
        fun parse(data: Bytes): MeshEvent {
            if (data.size < PacketSize.RAW_DATA_MINIMUM) {
                return MeshEvent.ParseFailure(data, "RawData too short: ${data.size} bytes, need ${PacketSize.RAW_DATA_MINIMUM}")
            }
            val reader = ByteReader(data)
            val snr = reader.readInt8().toDouble() / 4.0
            val rssi = reader.readInt8().toLong()
            reader.readUInt8()
            return MeshEvent.RawData(RawDataInfo(snr, rssi, reader.readBytes(reader.remaining)))
        }
    }

    object LogData {
        fun parse(data: Bytes): MeshEvent {
            val reader = ByteReader(data)
            val snr = if (data.size >= 2) reader.readInt8().toDouble() / 4.0 else null
            val rssi = if (data.size >= 2) reader.readInt8().toLong() else null
            val payload = reader.readBytes(reader.remaining)
            val parsed = RxLogParser.parse(snr, rssi, payload)
            return if (parsed != null) MeshEvent.RxLogData(parsed) else MeshEvent.LogData(LogDataInfo(snr, rssi, payload))
        }
    }
}

internal fun short(data: Bytes, name: String, minimum: Int): MeshEvent.ParseFailure =
    MeshEvent.ParseFailure(data, "$name too short: ${data.size} < $minimum")

internal fun UByte.hexByte(): String = toInt().toString(16).uppercase().padStart(2, '0')
internal fun UInt.epoch(): Instant = Instant.ofEpochSecond(toLong())

private fun readStatus(
    data: Bytes,
    prefix: Bytes,
    offset: Int,
    layout: StatusPayload.Layout,
    push: Boolean,
): StatusPayload {
    val reader = ByteReader(data, offset)
    val battery = reader.readUInt16LE().toLong()
    val queue = reader.readUInt16LE().toLong()
    val noise = reader.readInt16LE().toLong()
    val rssi = reader.readInt16LE().toLong()
    val received = reader.readUInt32LE()
    val sent = reader.readUInt32LE()
    val airtime = reader.readUInt32LE()
    val uptime = reader.readUInt32LE()
    val sentFlood = reader.readUInt32LE()
    val sentDirect = reader.readUInt32LE()
    val receivedFlood = reader.readUInt32LE()
    val receivedDirect = reader.readUInt32LE()
    val fullEvents = reader.readUInt16LE().toLong()
    val snr = reader.readInt16LE().toDouble() / 4.0
    val directDuplicates = reader.readUInt16LE().toLong()
    val floodDuplicates = reader.readUInt16LE().toLong()
    val tail = reader.position
    val repeater = layout == StatusPayload.Layout.REPEATER
    // The source admits a 58-byte push then calls its zero-on-short helper for
    // the UInt32 at 55. This is deliberately not a zero-producing checked read.
    val rxAirtime = if (!repeater) 0u else if (push) data.readUInt32LE(tail)
        else if (reader.remaining >= 4) reader.readUInt32LE() else 0u
    val errors = if (repeater && data.size >= tail + 8) ByteReader(data, tail + 4).readUInt32LE() else 0u
    val posted = if (!repeater && data.size >= tail + 4) ByteReader(data, tail).readUInt16LE() else null
    val pushed = if (!repeater && data.size >= tail + 4) ByteReader(data, tail + 2).readUInt16LE() else null
    return StatusPayload(
        prefix, battery, queue, noise, rssi, received, sent, airtime, uptime, sentFlood, sentDirect,
        receivedFlood, receivedDirect, fullEvents, snr, directDuplicates, floodDuplicates, rxAirtime,
        layout, errors, posted, pushed,
    )
}
