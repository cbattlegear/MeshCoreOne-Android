// PortedFrom: MeshCore/Sources/MeshCore/Models/Contact.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Models/ContactTypes.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Models/Destination.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Models/DeviceInfo.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Session/SessionConfiguration.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Session/OtherParamsConfig.swift@db14559b39d32322b06477c6ae676112f583db50
// Source-derived value assertions include independent standard-SHA expectations.
package com.meshcoreone.android.core.protocol.primitives

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.OtherParamsConfig
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.model.AnonRequestType
import com.meshcoreone.android.core.protocol.model.BatteryInfo
import com.meshcoreone.android.core.protocol.model.BinaryRequestType
import com.meshcoreone.android.core.protocol.model.ChannelSecret
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.ControlType
import com.meshcoreone.android.core.protocol.model.Destination
import com.meshcoreone.android.core.protocol.model.DestinationError
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.model.ErrorCode
import com.meshcoreone.android.core.protocol.model.FloodScope
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.model.PacketSize
import com.meshcoreone.android.core.protocol.model.ResponseCategory
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.core.protocol.model.StatsType
import com.meshcoreone.android.core.protocol.model.TextType
import com.meshcoreone.android.core.protocol.model.encodePathLen
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ValueModelsTest {
    private val key = Bytes(ByteArray(32) { it.toByte() })

    private fun contact(path: UByte = 0xffu, rawType: UByte = ContactType.CHAT.rawValue) = MeshContact(
        id = key.hexString,
        publicKey = key,
        type = ContactType.CHAT,
        flags = ContactFlags.FAVORITE or ContactFlags.TELEMETRY_BASE,
        outPathLength = path,
        outPath = Bytes.of(1, 2, 3, 4),
        advertisedName = "Node",
        lastAdvertisement = Instant.ofEpochSecond(100),
        latitude = 45.0,
        longitude = -120.0,
        lastModified = Instant.ofEpochSecond(200),
        typeRawValue = rawType,
    )

    @Test
    fun `Contact identity is immutable and unknown raw type is preserved`() {
        val original = contact(rawType = 0xfeu)
        assertEquals("000102030405", original.publicKeyPrefix)
        assertEquals(key.hexString, original.id)
        assertEquals(0xfe.toUByte(), original.typeRawValue)
        assertEquals(ContactType.CHAT, original.type)
        assertEquals(original, original.copy(publicKey = Bytes(key.toByteArray())))
        assertNull(ContactType.fromRawValue(original.typeRawValue))
        assertEquals(ContactType.SENSOR, ContactType.fromRawValue(4u))
        assertNull(ContactType.fromRawValue(0u))
    }

    @Test
    fun `Contact flood reserved and multi-byte path properties match the source`() {
        val flood = contact()
        assertTrue(flood.isFloodPath)
        assertEquals(1, flood.pathHashSize)
        assertEquals(0, flood.pathHopCount)
        assertEquals(0, flood.pathByteLength)
        val path = contact(encodePathLen(2, 3))
        assertFalse(path.isFloodPath)
        assertEquals(2, path.pathHashSize)
        assertEquals(3, path.pathHopCount)
        assertEquals(6, path.pathByteLength)
        val reserved = contact(0xc0u)
        assertFalse(reserved.isFloodPath)
        assertEquals(1, reserved.pathHashSize)
        assertEquals(0, reserved.pathByteLength)
    }

    @Test
    fun `Contact flags keep every bit including reserved bits`() {
        val flags = ContactFlags(0xfbu)
        assertTrue(ContactFlags.FAVORITE in flags)
        assertTrue(ContactFlags.TELEMETRY_BASE in flags)
        assertFalse(ContactFlags.TELEMETRY_LOCATION in flags)
        assertTrue(ContactFlags.TELEMETRY_ENVIRONMENT in flags)
        assertEquals(0xfb.toUByte(), flags.rawValue)
        assertEquals(
            ContactFlags.TELEMETRY_ALL,
            ContactFlags.TELEMETRY_BASE or ContactFlags.TELEMETRY_LOCATION or ContactFlags.TELEMETRY_ENVIRONMENT,
        )
    }

    @Test
    fun `Destination variants use identical byte prefix and full-key rules`() {
        for (destination in listOf(Destination.Data(key), Destination.HexString(key.hexString), Destination.Contact(contact()))) {
            assertEquals(key.prefix(6), destination.publicKey())
            assertEquals(key, destination.fullPublicKey())
            assertEquals(Bytes.EMPTY, destination.publicKey(0))
            assertEquals(key.prefix(1), destination.publicKey(1))
        }
        val tooShort = assertFailsWith<DestinationError.InsufficientLength> {
            Destination.Data(Bytes.of(1, 2)).publicKey()
        }
        assertEquals(6, tooShort.expected)
        assertEquals(2, tooShort.actual)
        val invalid = assertFailsWith<DestinationError.InvalidHexString> {
            Destination.HexString("GG").fullPublicKey()
        }
        assertEquals("GG", invalid.input)
        assertFailsWith<IllegalArgumentException> { Destination.Data(key).publicKey(-1) }
    }

    @Test
    fun `Name and region keys match independently computed SHA-256 vectors`() {
        assertEquals("9f86d081884c7d659a2feaa0c55ad015", FloodScope.ChannelName("test").scopeKey().hexString)
        assertEquals("47a33374e5302e7bfc38a71edfddf156", FloodScope.Region("Europe").scopeKey().hexString)
        assertEquals(FloodScope.Region("Europe").scopeKey(), FloodScope.Region("#Europe").scopeKey())
        assertEquals("e595f45dc6c9e7665d108e8ea7940885", FloodScope.ChannelName("Europe").scopeKey().hexString)
        assertEquals("efa1f375d76194fa51a3556a97e641e6", ChannelSecret.DeriveFromName.secretData("public").hexString)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb924", ChannelSecret.DeriveFromName.secretData("").hexString)
    }

    @Test
    fun `Explicit scope and channel secrets preserve source padding and truncation`() {
        assertEquals(Bytes.EMPTY.paddedOrTruncated(16), FloodScope.Disabled.scopeKey())
        for (value in listOf(Bytes.EMPTY, Bytes.of(1, 2, 3), key)) {
            assertEquals(value.paddedOrTruncated(16), FloodScope.RawKey(value).scopeKey())
            assertEquals(value.paddedOrTruncated(16), ChannelSecret.Explicit(value).secretData("ignored"))
        }
    }

    @Test
    fun `Device values retain signed radio power unsigned PIN and widened counts`() {
        val value = DeviceCapabilities(9u, UInt.MAX_VALUE.toLong(), 8, UInt.MAX_VALUE, "build", "radio", "1.15")
        assertFalse(value.supportsPathHashMode)
        assertEquals(1, value.hashSize)
        assertFalse(value.clientRepeat)
        assertEquals(UInt.MAX_VALUE, value.blePin)
        assertEquals(4_294_967_295L, value.maxContacts)
        val newer = value.copy(firmwareVersion = 10u, pathHashMode = 2u, clientRepeat = true)
        assertTrue(newer.supportsPathHashMode)
        assertEquals(3, newer.hashSize)
        assertEquals(256, newer.copy(pathHashMode = 255u).hashSize)
        assertEquals(null, BatteryInfo(3700).usedStorageKB)
        assertEquals(4_294_967_295L, BatteryInfo(3700, 4_294_967_295L, 4_294_967_295L).totalStorageKB)
    }

    @Test
    fun `Other params copy all six fields without inventing defaults or shared mutations`() {
        val info = SelfInfo(
            advertisementType = 1u, txPower = -10, maxTxPower = 22, publicKey = key,
            latitude = 0.0, longitude = 0.0, multiAcks = 2u, advertisementLocationPolicy = 3u,
            telemetryModeEnvironment = 4u, telemetryModeLocation = 5u, telemetryModeBase = 6u,
            manualAddContacts = true, radioFrequency = 915.0, radioBandwidth = 250.0,
            radioSpreadingFactor = 7u, radioCodingRate = 5u, name = "Device",
        )
        val config = OtherParamsConfig(info)
        assertEquals(OtherParamsConfig(true, 6u, 5u, 4u, 3u, 2u), config)
        assertEquals(OtherParamsConfig(), OtherParamsConfig(false, 0u, 0u, 0u, 0u, 0u))
        assertEquals(6.toUByte(), config.telemetryModeBase)
        assertEquals(6.toUByte(), info.telemetryModeBase)
        assertEquals(7.toUByte(), config.copy(telemetryModeBase = 7u).telemetryModeBase)
    }

    @Test
    fun `Session configuration retains every source timeout and default`() {
        val config = SessionConfiguration.DEFAULT
        assertEquals(5.0, config.defaultTimeout)
        assertEquals("MeshCore-Swift", config.clientIdentifier)
        assertEquals(40.0, config.binaryRequestOverallTimeout)
        assertEquals(1.0, config.binaryRequestRetransmitInterval)
        assertEquals(15.0, config.contactStreamInactivityTimeout)
        assertEquals(180.0, config.contactStreamHardTimeout)
        assertEquals(8L, config.channelPipelineWindow)
        assertEquals(1.5, config.channelPipelineIdleTimeout)
        assertEquals(30.0, config.channelPipelineHardTimeout)
        assertEquals(0.05, config.channelPipelinePostDrainGrace)
        assertEquals(2.0, SessionConfiguration.BINARY_RETRANSMIT_RTT_HEADROOM)
        assertEquals(1000.0, SessionConfiguration.MILLISECONDS_PER_SECOND)
        assertEquals(1.2, SessionConfiguration.RETRY_ACK_TIMEOUT_MULTIPLIER)
        assertNull(config.copy(binaryRequestRetransmitInterval = null).binaryRequestRetransmitInterval)
        assertEquals(8L, config.copy(channelPipelineWindow = Long.MAX_VALUE).copy(channelPipelineWindow = 8).channelPipelineWindow)
    }

    @Test
    fun `Packet tables preserve every wire value rather than ordinal`() {
        val commands = (1..0x2b).toList() + listOf(
            0x32, 0x33, 0x34, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x3b, 0x3c, 0x3d, 0x3e, 0x3f, 0x40, 0x41, 0x42,
        )
        val responses = (0..0x1d).toList() + (0x80..0x90).toList()
        assertEquals(commands, CommandCode.entries.map { it.rawValue.toInt() })
        assertEquals(responses, ResponseCode.entries.map { it.rawValue.toInt() })
        for (raw in 0..255) {
            assertEquals(raw in commands, CommandCode.fromRawValue(raw.toUByte()) != null)
            assertEquals(raw in responses, ResponseCode.fromRawValue(raw.toUByte()) != null)
        }
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), BinaryRequestType.entries.map { it.rawValue.toInt() })
        assertEquals(listOf(1, 2, 3), AnonRequestType.entries.map { it.rawValue.toInt() })
        assertEquals(listOf(0x80, 0x90), ControlType.entries.map { it.rawValue.toInt() })
        assertEquals(listOf(0, 1, 2), StatsType.entries.map { it.rawValue.toInt() })
        assertEquals(listOf(0, 1, 2), TextType.entries.map { it.rawValue.toInt() })
        assertEquals(listOf(1, 2, 3, 4, 5, 6), ErrorCode.entries.map { it.rawValue.toInt() })
        assertEquals(CommandCode.BINARY_REQUEST, CommandCode.fromRawValue(0x32u))
        assertEquals(ResponseCode.ACK, ResponseCode.fromRawValue(0x82u))
    }

    @Test
    fun `Response routing preserves source categories including miscellaneous push codes`() {
        assertEquals(ResponseCategory.MISC, ResponseCode.RAW_DATA.category)
        assertEquals(ResponseCategory.MISC, ResponseCode.LOG_DATA.category)
        assertEquals(ResponseCategory.MISC, ResponseCode.TRACE_DATA.category)
        assertEquals(ResponseCategory.LOGIN, ResponseCode.LOGIN_SUCCESS.category)
        assertEquals(ResponseCategory.LOGIN, ResponseCode.LOGIN_FAILED.category)
        assertEquals(ResponseCategory.PUSH, ResponseCode.ACK.category)
        assertEquals(ResponseCategory.SIGNING, ResponseCode.SIGNATURE.category)
        assertEquals(ResponseCategory.DEVICE, ResponseCode.DEFAULT_FLOOD_SCOPE.category)
        assertEquals(ResponseCategory.MESSAGE, ResponseCode.CHANNEL_DATA_RECEIVED.category)
        assertEquals(ResponseCategory.CONTACT, ResponseCode.CONTACT_URI.category)
    }

    @Test
    fun `Unknown device subcodes and all exception metadata survive`() {
        assertEquals(ErrorCode.NOT_FOUND, MeshCoreException.DeviceError(2u).deviceErrorCode)
        assertNull(MeshCoreException.DeviceError(0xfeu).deviceErrorCode)
        assertEquals(0xfe.toUByte(), MeshCoreException.DeviceError(0xfeu).code)
        assertNull(MeshCoreException.Timeout().deviceErrorCode)
        val cause = IllegalStateException("fixture cause")
        assertSame(cause, MeshCoreException.ConnectionLost(cause).cause)
        val large = MeshCoreException.DataTooLarge(172, 200)
        assertEquals(172L, large.maxSize)
        assertEquals(200L, large.actualSize)
        val failed = MeshCoreException.CommandFailed(CommandCode.GET_TIME, "fixture reason")
        assertEquals(CommandCode.GET_TIME, failed.command)
        assertEquals("fixture reason", failed.reason)
        assertEquals(key.prefix(6), MeshCoreException.ContactNotFound(key.prefix(6)).publicKeyPrefix)
    }

    @Test
    fun `Packet validation limits match the source schema widths`() {
        assertEquals(147, PacketSize.CONTACT)
        assertEquals(57, PacketSize.SELF_INFO_MINIMUM)
        assertEquals(49, PacketSize.CHANNEL_INFO_MINIMUM)
        assertEquals(4, PacketSize.ACK_MINIMUM)
        assertEquals(8, PacketSize.ACK_WITH_TRIP_TIME)
        assertEquals(7, PacketSize.LOGIN_SUCCESS_MINIMUM)
        assertEquals(13, PacketSize.LOGIN_SUCCESS_EXTENDED)
        assertEquals(47, PacketSize.DEFAULT_FLOOD_SCOPE_SET)
        assertEquals(31, PacketSize.DEFAULT_FLOOD_SCOPE_NAME_FIELD)
        assertEquals(16, PacketSize.DEFAULT_FLOOD_SCOPE_KEY_BYTES)
        assertEquals(48, PacketSize.BINARY_RESPONSE_STATUS_BASE)
        assertEquals(52, PacketSize.BINARY_RESPONSE_STATUS_WITH_RX_AIRTIME)
        assertEquals(56, PacketSize.BINARY_RESPONSE_STATUS_WITH_RECEIVE_ERRORS)
    }
}
