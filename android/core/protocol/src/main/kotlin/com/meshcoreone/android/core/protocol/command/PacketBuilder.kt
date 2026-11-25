// PortedFrom: MeshCore/Sources/MeshCore/Protocol/PacketBuilder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.command

import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.bytes.graphemePattern
import com.meshcoreone.android.core.protocol.bytes.utf8PaddedOrTruncated
import com.meshcoreone.android.core.protocol.bytes.utf8Prefix
import com.meshcoreone.android.core.protocol.model.AnonRequestType
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import com.meshcoreone.android.core.protocol.model.BinaryRequestType
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.ControlType
import com.meshcoreone.android.core.protocol.model.FloodScope
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.model.StatsType
import java.security.SecureRandom
import java.time.Instant
import kotlin.math.floor

object PacketBuilder {
    const val PUBLIC_KEY_SIZE = 32
    const val PRIVATE_KEY_SIZE = 64
    const val RAW_DATA_MAX_PATH_BYTES = 64
    const val RAW_DATA_MAX_PAYLOAD_BYTES = 184
    const val CHANNEL_DATA_MAX_PAYLOAD_BYTES = 163
    val FLOOD_PATH_SENTINEL: UByte = 0xffu
    val LATITUDE_RANGE = -90.0..90.0
    val LONGITUDE_RANGE = -180.0..180.0
    val FREQUENCY_RANGE_KHZ = 150_000u..2_500_000u
    val BANDWIDTH_RANGE_HZ = 7_000u..500_000u
    val SPREADING_FACTOR_RANGE: IntRange = 5..12
    val CODING_RATE_RANGE: IntRange = 5..8
    const val TX_POWER_FLOOR: Byte = -9

    private val random by lazy { SecureRandom() }

    private fun packet(code: CommandCode): ByteWriter = ByteWriter().appendUInt8(code.rawValue)
    private fun oneByte(code: CommandCode): Bytes = packet(code).toBytes()
    private fun withKey(code: CommandCode, key: Bytes): Bytes =
        packet(code).append(key.prefix(PUBLIC_KEY_SIZE)).toBytes()

    fun scaledCoordinate(degrees: Double, range: ClosedFloatingPointRange<Double>): Int =
        ((if (degrees.isFinite()) degrees.coerceIn(range.start, range.endInclusive) else 0.0) *
            1_000_000.0).toInt()

    internal fun scaledRadioValue(value: Double, range: UIntRange): UInt {
        if (!value.isFinite()) return range.first
        // Swift's rounded() uses half away from zero; all valid wire values are positive.
        return floor(value * 1_000.0 + 0.5)
            .coerceIn(range.first.toDouble(), range.last.toDouble()).toUInt()
    }

    internal fun epochSeconds32(date: Instant): UInt =
        date.epochSecond.coerceIn(0L, UInt.MAX_VALUE.toLong()).toUInt()

    fun appStart(clientId: String = "MCore"): Bytes {
        val matcher = graphemePattern.matcher(clientId)
        var end = 0
        repeat(5) { if (matcher.find()) end = matcher.end() }
        return packet(CommandCode.APP_START).appendUInt8(0x03u)
            .append(Bytes.of(0x20, 0x20, 0x20, 0x20, 0x20, 0x20))
            .append(Bytes.utf8(clientId.substring(0, end))).toBytes()
    }

    fun deviceQuery(): Bytes = packet(CommandCode.DEVICE_QUERY).appendUInt8(0x03u).toBytes()
    fun getBattery(): Bytes = oneByte(CommandCode.GET_BATTERY)
    fun getTime(): Bytes = oneByte(CommandCode.GET_TIME)
    fun setTime(date: Instant): Bytes =
        packet(CommandCode.SET_TIME).appendUInt32LE(epochSeconds32(date)).toBytes()

    fun setName(name: String): Bytes =
        packet(CommandCode.SET_NAME).append(Bytes.utf8(name.utf8Prefix(31))).toBytes()

    fun setCoordinates(latitude: Double, longitude: Double): Bytes =
        packet(CommandCode.SET_COORDINATES)
            .appendInt32LE(scaledCoordinate(latitude, LATITUDE_RANGE))
            .appendInt32LE(scaledCoordinate(longitude, LONGITUDE_RANGE))
            .appendUInt32LE(0u).toBytes()

    fun setTxPower(power: Byte): Bytes =
        packet(CommandCode.SET_TX_POWER).appendInt8(power).toBytes()

    fun setRadio(
        frequency: Double,
        bandwidth: Double,
        spreadingFactor: UByte,
        codingRate: UByte,
        clientRepeat: Boolean? = null,
    ): Bytes {
        val result = packet(CommandCode.SET_RADIO)
            .appendUInt32LE(scaledRadioValue(frequency, FREQUENCY_RANGE_KHZ))
            .appendUInt32LE(scaledRadioValue(bandwidth, BANDWIDTH_RANGE_HZ))
            .appendUInt8(spreadingFactor).appendUInt8(codingRate)
        if (clientRepeat != null) result.appendUInt8(if (clientRepeat) 1u else 0u)
        return result.toBytes()
    }

    fun getRepeatFreq(): Bytes = oneByte(CommandCode.GET_REPEAT_FREQ)
    fun sendAdvertisement(flood: Boolean = false): Bytes {
        val result = packet(CommandCode.SEND_ADVERTISEMENT)
        if (flood) result.appendUInt8(1u)
        return result.toBytes()
    }

    fun reboot(): Bytes = packet(CommandCode.REBOOT).append(Bytes.utf8("reboot")).toBytes()
    fun getContacts(since: Instant? = null): Bytes {
        val result = packet(CommandCode.GET_CONTACTS)
        if (since != null) result.appendUInt32LE(epochSeconds32(since))
        return result.toBytes()
    }

    fun resetPath(publicKey: Bytes): Bytes = withKey(CommandCode.RESET_PATH, publicKey)
    fun removeContact(publicKey: Bytes): Bytes = withKey(CommandCode.REMOVE_CONTACT, publicKey)
    fun shareContact(publicKey: Bytes): Bytes = withKey(CommandCode.SHARE_CONTACT, publicKey)
    fun exportContact(publicKey: Bytes? = null): Bytes =
        if (publicKey == null) oneByte(CommandCode.EXPORT_CONTACT)
        else withKey(CommandCode.EXPORT_CONTACT, publicKey)

    fun getMessage(): Bytes = oneByte(CommandCode.GET_MESSAGE)
    fun sendMessage(
        destination: Bytes,
        text: String,
        timestamp: Instant = Instant.now(),
        attempt: UByte = 0u,
    ): Bytes = packet(CommandCode.SEND_MESSAGE).appendUInt8(0u).appendUInt8(attempt)
        .appendUInt32LE(epochSeconds32(timestamp)).append(destination.prefix(6))
        .append(Bytes.utf8(text)).toBytes()

    fun sendCommand(
        destination: Bytes,
        command: String,
        timestamp: Instant = Instant.now(),
    ): Bytes = packet(CommandCode.SEND_MESSAGE).appendUInt8(1u).appendUInt8(0u)
        .appendUInt32LE(epochSeconds32(timestamp)).append(destination.prefix(6))
        .append(Bytes.utf8(command)).toBytes()

    fun sendRadioCLICommand(
        destination: Bytes,
        command: String,
        timestamp: Instant = Instant.now(),
    ): Bytes = packet(CommandCode.SEND_MESSAGE).appendUInt8(3u).appendUInt8(0u)
        .appendUInt32LE(epochSeconds32(timestamp)).append(destination.prefix(6))
        .append(Bytes.utf8(command)).toBytes()

    fun sendChannelMessage(
        channel: UByte,
        text: String,
        timestamp: Instant = Instant.now(),
    ): Bytes = packet(CommandCode.SEND_CHANNEL_MESSAGE).appendUInt8(0u).appendUInt8(channel)
        .appendUInt32LE(epochSeconds32(timestamp)).append(Bytes.utf8(text)).toBytes()

    fun sendLogin(destination: Bytes, password: String): Bytes =
        packet(CommandCode.SEND_LOGIN).append(destination.prefix(PUBLIC_KEY_SIZE))
            .append(Bytes.utf8(password)).toBytes()

    fun sendLogout(destination: Bytes): Bytes = withKey(CommandCode.SEND_LOGOUT, destination)
    fun sendStatusRequest(destination: Bytes): Bytes = withKey(CommandCode.SEND_STATUS_REQUEST, destination)
    fun binaryRequest(
        destination: Bytes,
        type: BinaryRequestType,
        payload: Bytes? = null,
    ): Bytes {
        val result = packet(CommandCode.BINARY_REQUEST).append(destination.prefix(PUBLIC_KEY_SIZE))
            .appendUInt8(type.rawValue)
        if (payload != null) result.append(payload)
        return result.toBytes()
    }

    fun getChannel(index: UByte): Bytes = packet(CommandCode.GET_CHANNEL).appendUInt8(index).toBytes()
    fun setChannel(index: UByte, name: String, secret: Bytes): Bytes =
        packet(CommandCode.SET_CHANNEL).appendUInt8(index)
            .append(name.utf8PaddedOrTruncated(32)).append(secret.prefix(16)).toBytes()

    private fun getStats(type: StatsType): Bytes =
        packet(CommandCode.GET_STATS).appendUInt8(type.rawValue).toBytes()

    fun getStatsCore(): Bytes = getStats(StatsType.CORE)
    fun getStatsRadio(): Bytes = getStats(StatsType.RADIO)
    fun getStatsPackets(): Bytes = getStats(StatsType.PACKETS)
    fun updateContact(contact: MeshContact): Bytes = packet(CommandCode.UPDATE_CONTACT)
        .append(contact.publicKey.paddedOrTruncated(32))
        .appendUInt8(contact.typeRawValue).appendUInt8(contact.flags.rawValue)
        .appendUInt8(contact.outPathLength).append(contact.outPath.paddedOrTruncated(64))
        .append(contact.advertisedName.utf8PaddedOrTruncated(32))
        .appendUInt32LE(epochSeconds32(contact.lastAdvertisement))
        .appendInt32LE(scaledCoordinate(contact.latitude, LATITUDE_RANGE))
        .appendInt32LE(scaledCoordinate(contact.longitude, LONGITUDE_RANGE))
        .append(Bytes.of(0, 0, 0)).toBytes()

    fun setTuning(rxDelay: UInt, af: UInt): Bytes =
        packet(CommandCode.SET_TUNING).appendUInt32LE(rxDelay).appendUInt32LE(af)
            .appendUInt16LE(0u).toBytes()

    fun setOtherParams(
        manualAddContacts: Boolean,
        telemetryModeEnvironment: UByte,
        telemetryModeLocation: UByte,
        telemetryModeBase: UByte,
        advertisementLocationPolicy: UByte,
        multiAcks: UByte? = null,
    ): Bytes {
        val telemetryMode = ((telemetryModeEnvironment.toInt() and 3) shl 4) or
            ((telemetryModeLocation.toInt() and 3) shl 2) or (telemetryModeBase.toInt() and 3)
        val result = packet(CommandCode.SET_OTHER_PARAMS)
            .appendUInt8(if (manualAddContacts) 1u else 0u).appendUInt8(telemetryMode.toUByte())
            .appendUInt8(advertisementLocationPolicy)
        if (multiAcks != null) result.appendUInt8(multiAcks)
        return result.toBytes()
    }

    fun getAutoAddConfig(): Bytes = oneByte(CommandCode.GET_AUTO_ADD_CONFIG)
    fun setAutoAddConfig(config: AutoAddConfig): Bytes =
        packet(CommandCode.SET_AUTO_ADD_CONFIG).appendUInt8(config.bitmask)
            .appendUInt8(config.maxHops).toBytes()

    fun getSelfTelemetry(destination: Bytes? = null): Bytes {
        val result = packet(CommandCode.GET_SELF_TELEMETRY).append(Bytes.of(0, 0, 0))
        if (destination != null) result.append(destination.prefix(PUBLIC_KEY_SIZE))
        return result.toBytes()
    }

    fun setDevicePin(pin: UInt): Bytes = packet(CommandCode.SET_DEVICE_PIN).appendUInt32LE(pin).toBytes()
    fun getCustomVars(): Bytes = oneByte(CommandCode.GET_CUSTOM_VARS)
    fun setCustomVar(key: String, value: String): Bytes =
        packet(CommandCode.SET_CUSTOM_VAR).append(Bytes.utf8("$key:$value")).toBytes()

    fun exportPrivateKey(): Bytes = oneByte(CommandCode.EXPORT_PRIVATE_KEY)
    fun importPrivateKey(key: Bytes): Bytes = packet(CommandCode.IMPORT_PRIVATE_KEY).append(key).toBytes()
    fun signStart(): Bytes = oneByte(CommandCode.SIGN_START)
    fun signData(chunk: Bytes): Bytes = packet(CommandCode.SIGN_DATA).append(chunk).toBytes()
    fun signFinish(): Bytes = oneByte(CommandCode.SIGN_FINISH)
    fun sendPathDiscovery(destination: Bytes): Bytes =
        packet(CommandCode.PATH_DISCOVERY).appendUInt8(0u)
            .append(destination.prefix(PUBLIC_KEY_SIZE)).toBytes()

    fun sendTrace(tag: UInt, authCode: UInt, flags: UByte, path: Bytes? = null): Bytes {
        val result = packet(CommandCode.SEND_TRACE).appendUInt32LE(tag).appendUInt32LE(authCode)
            .appendUInt8(flags)
        if (path != null) result.append(path)
        return result.toBytes()
    }

    fun setFloodScope(scopeKey: Bytes): Bytes =
        packet(CommandCode.SET_FLOOD_SCOPE).appendUInt8(0u).append(scopeKey.prefix(16)).toBytes()

    fun setFloodScopeUnscoped(): Bytes =
        packet(CommandCode.SET_FLOOD_SCOPE).appendUInt8(1u).toBytes()

    // Preserve the source's packer contract; the session validates path/type/capability consistency.
    fun sendChannelData(
        channelIndex: UByte,
        dataType: UShort,
        payload: Bytes,
        pathLength: UByte = FLOOD_PATH_SENTINEL,
        pathBytes: Bytes = Bytes.EMPTY,
    ): Bytes {
        val result = packet(CommandCode.SEND_CHANNEL_DATA).appendUInt8(channelIndex)
            .appendUInt8(pathLength)
        if (pathLength != FLOOD_PATH_SENTINEL) result.append(pathBytes)
        return result.appendUInt16LE(dataType).append(payload.prefix(CHANNEL_DATA_MAX_PAYLOAD_BYTES))
            .toBytes()
    }

    fun setDefaultFloodScope(name: String, scopeKey: Bytes): Bytes =
        if (name.isEmpty()) oneByte(CommandCode.SET_DEFAULT_FLOOD_SCOPE)
        else packet(CommandCode.SET_DEFAULT_FLOOD_SCOPE)
            .append(name.utf8Prefix(30).utf8PaddedOrTruncated(31))
            .append(scopeKey.paddedOrTruncated(16)).toBytes()

    fun setDefaultFloodScope(name: String, scope: FloodScope): Bytes =
        if (scope == FloodScope.Disabled) setDefaultFloodScope("", Bytes.EMPTY)
        else setDefaultFloodScope(name, scope.scopeKey())

    fun getDefaultFloodScope(): Bytes = oneByte(CommandCode.GET_DEFAULT_FLOOD_SCOPE)
    fun sendAnonReq(
        publicKey: Bytes,
        type: AnonRequestType,
        pathLength: UByte,
        path: Bytes,
    ): Bytes = packet(CommandCode.SEND_ANON_REQ).append(publicKey.prefix(PUBLIC_KEY_SIZE))
        .appendUInt8(type.rawValue).appendUInt8(pathLength)
        .append(Bytes(path.toByteArray().reversedArray())).toBytes()

    fun setPathHashMode(mode: UByte): Bytes =
        packet(CommandCode.SET_PATH_HASH_MODE).appendUInt8(0u).appendUInt8(minOf(mode, 2u)).toBytes()

    fun factoryReset(): Bytes = packet(CommandCode.FACTORY_RESET).append(Bytes.utf8("reset")).toBytes()
    fun sendControlData(type: UByte, payload: Bytes): Bytes =
        packet(CommandCode.SEND_CONTROL_DATA).appendUInt8(type).append(payload).toBytes()

    fun sendNodeDiscoverRequest(
        filter: UByte,
        prefixOnly: Boolean = true,
        tag: UInt? = null,
        since: UInt? = null,
    ): Bytes {
        val actualTag = tag ?: generateSequence { random.nextInt().toUInt() }.first { it != 0u }
        val controlType = ControlType.NODE_DISCOVER_REQUEST.rawValue.toInt() or
            (if (prefixOnly) 1 else 0)
        val result = packet(CommandCode.SEND_CONTROL_DATA).appendUInt8(controlType.toUByte())
            .appendUInt8(filter).appendUInt32LE(actualTag)
        if (since != null) result.appendUInt32LE(since)
        return result.toBytes()
    }

    fun sendRawData(path: Bytes, payload: Bytes): Bytes {
        val clampedPath = path.prefix(RAW_DATA_MAX_PATH_BYTES)
        return packet(CommandCode.SEND_RAW_DATA).appendUInt8(clampedPath.size.toUByte())
            .append(clampedPath).append(payload.prefix(RAW_DATA_MAX_PAYLOAD_BYTES)).toBytes()
    }

    fun hasConnection(publicKey: Bytes): Bytes =
        packet(CommandCode.HAS_CONNECTION).append(publicKey.paddedOrTruncated(PUBLIC_KEY_SIZE)).toBytes()

    fun getContactByKey(publicKey: Bytes): Bytes =
        packet(CommandCode.GET_CONTACT_BY_KEY).append(publicKey.paddedOrTruncated(PUBLIC_KEY_SIZE))
            .toBytes()

    fun getAdvertPath(publicKey: Bytes): Bytes =
        packet(CommandCode.GET_ADVERT_PATH).appendUInt8(0u)
            .append(publicKey.paddedOrTruncated(PUBLIC_KEY_SIZE)).toBytes()

    fun getTuningParams(): Bytes = oneByte(CommandCode.GET_TUNING_PARAMS)
}
