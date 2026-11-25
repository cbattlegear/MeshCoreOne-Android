// PortedFrom: MeshCore/Sources/MeshCore/Events/MeshEvent.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.event

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.AutoAddConfig as AutoAddValue
import com.meshcoreone.android.core.protocol.model.BatteryInfo
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.model.ErrorCode
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.model.SelfInfo as DeviceSelfInfo
import java.time.Instant

sealed class MeshEvent(val caseName: String) {
    data class ConnectionStateChanged(val state: ConnectionState) : MeshEvent("connectionStateChanged")
    data class Ok(val value: UInt?) : MeshEvent("ok")
    data class Error(val code: UByte?) : MeshEvent("error")
    data class SelfInfo(val info: DeviceSelfInfo) : MeshEvent("selfInfo")
    data class DeviceInfo(val info: DeviceCapabilities) : MeshEvent("deviceInfo")
    data class Battery(val info: BatteryInfo) : MeshEvent("battery")
    data class CurrentTime(val time: Instant) : MeshEvent("currentTime")
    data class CustomVars(val values: EventMap<String, String>) : MeshEvent("customVars") {
        constructor(values: Map<String, String>) : this(EventMap(values))
    }
    data class ChannelInfo(val info: com.meshcoreone.android.core.protocol.event.ChannelInfo) :
        MeshEvent("channelInfo")
    data class StatsCore(val stats: CoreStats) : MeshEvent("statsCore")
    data class StatsRadio(val stats: RadioStats) : MeshEvent("statsRadio")
    data class StatsPackets(val stats: PacketStats) : MeshEvent("statsPackets")
    data class AutoAddConfig(val config: AutoAddValue) : MeshEvent("autoAddConfig")
    data class DefaultFloodScope(val scope: com.meshcoreone.android.core.protocol.event.DefaultFloodScope?) :
        MeshEvent("defaultFloodScope")
    data class AllowedRepeatFreq(val ranges: EventList<FrequencyRange>) : MeshEvent("allowedRepeatFreq") {
        constructor(ranges: Collection<FrequencyRange>) : this(EventList(ranges))
    }
    data class ContactsStart(val count: Long) : MeshEvent("contactsStart")
    data class Contact(val contact: MeshContact) : MeshEvent("contact")
    data class ContactsEnd(val lastModified: Instant) : MeshEvent("contactsEnd")
    data class NewContact(val contact: MeshContact) : MeshEvent("newContact")
    data class ContactDeleted(val publicKey: Bytes) : MeshEvent("contactDeleted")
    data object ContactsFull : MeshEvent("contactsFull")
    data class ContactURI(val uri: String) : MeshEvent("contactURI")
    data class MessageSent(val info: MessageSentInfo) : MeshEvent("messageSent")
    data class ContactMessageReceived(val message: ContactMessage) : MeshEvent("contactMessageReceived")
    data class ChannelMessageReceived(val message: ChannelMessage) : MeshEvent("channelMessageReceived")
    data class ChannelDataReceived(val datagram: ChannelDatagram) : MeshEvent("channelDataReceived")
    data object NoMoreMessages : MeshEvent("noMoreMessages")
    data object MessagesWaiting : MeshEvent("messagesWaiting")
    data class Advertisement(val publicKey: Bytes) : MeshEvent("advertisement")
    data class PathUpdate(val publicKey: Bytes) : MeshEvent("pathUpdate")
    data class Acknowledgement(val code: Bytes, val tripTime: UInt? = null) : MeshEvent("acknowledgement")
    data class TraceData(val trace: TraceInfo) : MeshEvent("traceData")
    data class PathResponse(val path: PathInfo) : MeshEvent("pathResponse")
    data class LoginSuccess(val info: LoginInfo) : MeshEvent("loginSuccess")
    data class LoginFailed(val publicKeyPrefix: Bytes?) : MeshEvent("loginFailed")
    data class StatusResponse(val response: com.meshcoreone.android.core.protocol.event.StatusResponse) :
        MeshEvent("statusResponse")
    data class TelemetryResponse(val response: com.meshcoreone.android.core.protocol.event.TelemetryResponse) :
        MeshEvent("telemetryResponse")
    data class BinaryResponse(val tag: Bytes, val data: Bytes) : MeshEvent("binaryResponse")
    data class MmaResponse(val response: MMAResponse) : MeshEvent("mmaResponse")
    data class AclResponse(val response: ACLResponse) : MeshEvent("aclResponse")
    data class NeighboursResponse(val response: com.meshcoreone.android.core.protocol.event.NeighboursResponse) :
        MeshEvent("neighboursResponse")
    data class SignStart(val maxLength: Long) : MeshEvent("signStart")
    data class Signature(val signature: Bytes) : MeshEvent("signature")
    data class Disabled(val reason: String) : MeshEvent("disabled")
    data class RawData(val info: RawDataInfo) : MeshEvent("rawData")
    data class LogData(val info: LogDataInfo) : MeshEvent("logData")
    data class RxLogData(val data: ParsedRxLogData) : MeshEvent("rxLogData")
    data class ControlData(val info: ControlDataInfo) : MeshEvent("controlData")
    data class DiscoverResponse(val response: com.meshcoreone.android.core.protocol.event.DiscoverResponse) :
        MeshEvent("discoverResponse")
    data class AdvertPathResponse(val response: com.meshcoreone.android.core.protocol.event.AdvertPathResponse) :
        MeshEvent("advertPathResponse")
    data class TuningParamsResponse(val response: com.meshcoreone.android.core.protocol.event.TuningParamsResponse) :
        MeshEvent("tuningParamsResponse")
    data class PrivateKey(val key: Bytes) : MeshEvent("privateKey")
    data class CliReply(val text: String, val validUtf8: Boolean = true) : MeshEvent("cliReply") {
        override fun toString(): String = "CliReply(<redacted>)"
    }
    data class ParseFailure(val data: Bytes, val reason: String) : MeshEvent("parseFailure")

    val attributes: Map<String, Any?>
        get() = when (this) {
            is ContactMessageReceived -> mapOf(
                "publicKeyPrefix" to message.senderPublicKeyPrefix, "textType" to message.textType,
            )
            is ChannelMessageReceived -> mapOf(
                "channelIndex" to message.channelIndex, "textType" to message.textType,
            )
            is Acknowledgement -> if (tripTime == null) mapOf("code" to code)
                else mapOf("code" to code, "tripTime" to tripTime)
            is MessageSent -> mapOf("route" to info.route, "expectedAck" to info.expectedAck)
            is StatusResponse -> mapOf("publicKeyPrefix" to response.publicKeyPrefix)
            is TelemetryResponse -> mapOf("publicKeyPrefix" to response.publicKeyPrefix)
            is Advertisement -> mapOf("publicKeyPrefix" to publicKey.prefix(6))
            is PathUpdate -> mapOf("publicKeyPrefix" to publicKey.prefix(6))
            is NewContact -> mapOf("publicKey" to contact.publicKey)
            is Contact -> mapOf("publicKey" to contact.publicKey)
            is Error -> mapOf("code" to code)
            is Ok -> mapOf("value" to value)
            else -> emptyMap()
        }

    val errorCode: ErrorCode? get() = (this as? Error)?.code?.let(ErrorCode::fromRawValue)
}
