// PortedFrom: MeshCore/Sources/MeshCore/Events/MeshEvent.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Sources/MeshCore/Events/EventFilter.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/RxLogTypes.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.event

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PinnedCommandSource
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import com.meshcoreone.android.core.protocol.model.BatteryInfo
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.model.SelfInfo
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PinnedEventVocabularyTest {
    @Test
    fun `Every pinned event has a real typed payload and exact low-cardinality case name`() {
        val empty = Bytes.EMPTY
        val contact = MeshContact("", empty, ContactType.CHAT, ContactFlags(0u), 0u, empty,
            "", Instant.EPOCH, 0.0, 0.0, Instant.EPOCH)
        val self = SelfInfo(
            advertisementType = 0u, txPower = 0, maxTxPower = 0, publicKey = empty,
            latitude = 0.0, longitude = 0.0, multiAcks = 0u, advertisementLocationPolicy = 0u,
            telemetryModeEnvironment = 0u, telemetryModeLocation = 0u, telemetryModeBase = 0u,
            manualAddContacts = false, radioFrequency = 0.0, radioBandwidth = 0.0,
            radioSpreadingFactor = 0u, radioCodingRate = 0u, name = "",
        )
        val values = listOf(
            MeshEvent.ConnectionStateChanged(ConnectionState.Connected),
            MeshEvent.Ok(null), MeshEvent.Error(null), MeshEvent.SelfInfo(self),
            MeshEvent.DeviceInfo(DeviceCapabilities(0u, 0, 0, 0u, "", "", "")),
            MeshEvent.Battery(BatteryInfo(0)), MeshEvent.CurrentTime(Instant.EPOCH),
            MeshEvent.CustomVars(emptyMap()), MeshEvent.ChannelInfo(ChannelInfo(0u, "", empty)),
            MeshEvent.StatsCore(CoreStats(0u, 0u, 0u, 0u)),
            MeshEvent.StatsRadio(RadioStats(0, 0, 0.0, 0u, 0u)),
            MeshEvent.StatsPackets(PacketStats(0u, 0u, 0u, 0u, 0u, 0u)),
            MeshEvent.AutoAddConfig(AutoAddConfig(0u)), MeshEvent.DefaultFloodScope(null),
            MeshEvent.AllowedRepeatFreq(emptyList()), MeshEvent.ContactsStart(0),
            MeshEvent.Contact(contact), MeshEvent.ContactsEnd(Instant.EPOCH), MeshEvent.NewContact(contact),
            MeshEvent.ContactDeleted(empty), MeshEvent.ContactsFull, MeshEvent.ContactURI(""),
            MeshEvent.MessageSent(MessageSentInfo(0u, empty, 0u)),
            MeshEvent.ContactMessageReceived(contactMessage()), MeshEvent.ChannelMessageReceived(channelMessage()),
            MeshEvent.ChannelDataReceived(ChannelDatagram(0u, 0u, 1u, empty, 0.0)),
            MeshEvent.NoMoreMessages, MeshEvent.MessagesWaiting, MeshEvent.Advertisement(empty),
            MeshEvent.PathUpdate(empty), MeshEvent.Acknowledgement(empty),
            MeshEvent.TraceData(TraceInfo(0u, 0u, 0u, 0u, emptyList())),
            MeshEvent.PathResponse(PathInfo(empty, 0u, empty, 0u, empty)),
            MeshEvent.LoginSuccess(LoginInfo(0u, false, empty)), MeshEvent.LoginFailed(null),
            MeshEvent.StatusResponse(statusResponse()), MeshEvent.TelemetryResponse(TelemetryResponse(empty, null, empty)),
            MeshEvent.BinaryResponse(empty, empty), MeshEvent.MmaResponse(MMAResponse(empty, empty, emptyList())),
            MeshEvent.AclResponse(ACLResponse(empty, empty, emptyList())),
            MeshEvent.NeighboursResponse(NeighboursResponse(empty, empty, 0, emptyList())),
            MeshEvent.SignStart(0), MeshEvent.Signature(empty), MeshEvent.Disabled(""),
            MeshEvent.RawData(RawDataInfo(0.0, 0, empty)), MeshEvent.LogData(LogDataInfo(null, null, empty)),
            MeshEvent.RxLogData(ParsedRxLogData(null, null, empty, RouteType.FLOOD, PayloadType.REQUEST,
                0u, 0u, null, 0u, emptyList(), empty)),
            MeshEvent.ControlData(ControlDataInfo(0.0, 0, 0u, 0u, empty)),
            MeshEvent.DiscoverResponse(DiscoverResponse(0u, 0.0, 0.0, 0, 0u, empty, empty)),
            MeshEvent.AdvertPathResponse(AdvertPathResponse(0u, 0u, empty)),
            MeshEvent.TuningParamsResponse(TuningParamsResponse(0.0, 0.0)),
            MeshEvent.PrivateKey(empty), MeshEvent.ParseFailure(empty, ""),
        )
        val original = PinnedCommandSource.read("MeshCore/Sources/MeshCore/Events/MeshEvent.swift")
            .substringBefore("// MARK: - Event Attributes for Filtering")
        val expected = Regex("^  case ([A-Za-z0-9]+)", RegexOption.MULTILINE)
            .findAll(original).map { it.groupValues[1] }.toList()
        assertEquals(53, expected.size)
        assertEquals(expected, values.map { it.caseName })
        assertEquals(values.size, values.map { it.javaClass }.toSet().size)
    }

    @Test
    fun `Every pinned filter factory and combinator is represented in the native contract`() {
        val original = PinnedCommandSource.read("MeshCore/Sources/MeshCore/Events/EventFilter.swift")
        val methods = Regex("public (?:static )?func ([A-Za-z0-9]+)").findAll(original)
            .map { it.groupValues[1] }.toSet()
        val properties = Regex("public (?:static )?var ([A-Za-z0-9]+)").findAll(original)
            .map { it.groupValues[1] }.toSet()
        assertEquals(setOf(
            "matches", "acknowledgement", "contactMessage", "channelMessage", "statusResponse",
            "telemetryResponse", "advertisement", "pathUpdate", "eventType", "or", "and",
        ), methods)
        assertEquals(setOf(
            "anyAcknowledgement", "anyContactMessage", "anyChannelMessage", "rxLogData", "anyAdvertisement",
            "ok", "error", "noMoreMessages", "messagesWaiting", "anyLoginSuccess", "anyLoginFailed", "negated",
        ), properties)
    }

    @Test
    fun `All original route and payload names wire values and reserved mappings match`() {
        val original = PinnedCommandSource.read("MeshCore/Sources/MeshCore/Protocol/RxLogTypes.swift")
        fun table(type: String): Map<String, Int> {
            val body = original.substringAfter("public enum $type:").substringBefore("\n}")
            return Regex("^  case ([A-Za-z0-9]+) = ([0-9]+)", RegexOption.MULTILINE)
                .findAll(body).associate { it.groupValues[1] to it.groupValues[2].toInt() }
        }
        assertEquals(listOf(0, 1, 2, 3), table("RouteType").values.toList())
        assertEquals(table("PayloadType").values.toList(), PayloadType.entries.map { it.rawValue.toInt() })
        assertEquals(listOf("TC_FLOOD", "FLOOD", "DIRECT", "TC_DIRECT"), RouteType.entries.map { it.displayName })
        for (bits in 0..255) {
            val value = PayloadType.fromBits(bits.toUByte())
            val expected = table("PayloadType").values.firstOrNull { it == bits } ?: 255
            assertEquals(expected, value.rawValue.toInt())
        }
        assertTrue(original.contains("payloadTypeBits"))
    }
}
