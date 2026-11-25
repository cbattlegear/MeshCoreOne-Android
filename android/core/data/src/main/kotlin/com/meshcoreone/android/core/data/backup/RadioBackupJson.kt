// PortedFrom: MC1Services/Sources/MC1Services/Models/Device.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/Contact.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/Channel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/ConnectionMethod.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.model.*
import kotlinx.serialization.json.*

internal fun decodeDevice(value: JsonElement): DeviceDTO = value.row("devices").run {
    DeviceDTO(
        id = uuid("id"), radioId = radioId(), publicKey = bytes("publicKey"), nodeName = string("nodeName"),
        firmwareVersion = ubyte("firmwareVersion"), firmwareVersionString = string("firmwareVersionString"),
        manufacturerName = string("manufacturerName"), buildDate = string("buildDate"),
        maxContacts = ushort("maxContacts"), maxChannels = ubyte("maxChannels"), frequency = uint("frequency"),
        bandwidth = uint("bandwidth"), spreadingFactor = ubyte("spreadingFactor"), codingRate = ubyte("codingRate"),
        txPower = byte("txPower"), maxTxPower = byte("maxTxPower"), latitude = double("latitude"),
        longitude = double("longitude"), blePin = uint("blePin"), clientRepeat = boolean("clientRepeat"),
        pathHashMode = ubyte("pathHashMode"), defaultFloodScopeName = optionalString("defaultFloodScopeName"),
        preRepeatFrequency = optionalUInt("preRepeatFrequency"), preRepeatBandwidth = optionalUInt("preRepeatBandwidth"),
        preRepeatSpreadingFactor = optionalUByte("preRepeatSpreadingFactor"), preRepeatCodingRate = optionalUByte("preRepeatCodingRate"),
        manualAddContacts = boolean("manualAddContacts"), autoAddConfig = ubyte("autoAddConfig"),
        autoAddMaxHops = ubyte("autoAddMaxHops"), multiAcks = ubyte("multiAcks"),
        telemetryModeBase = ubyte("telemetryModeBase"), telemetryModeLoc = ubyte("telemetryModeLoc"),
        telemetryModeEnv = ubyte("telemetryModeEnv"), advertLocationPolicy = ubyte("advertLocationPolicy"),
        lastConnected = date("lastConnected"), lastContactSync = uint("lastContactSync"), isActive = boolean("isActive"),
        ocvPreset = optionalString("ocvPreset"), appliedRadioPresetID = optionalString("appliedRadioPresetID"),
        customOCVArrayString = optionalString("customOCVArrayString"),
        connectionMethods = array("connectionMethods").map(::decodeConnectionMethod).snapshot(), knownRegions = strings("knownRegions"),
    )
}

internal fun encodeDevice(dto: DeviceDTO): JsonObject = dto.run {
    wireObject {
        uuid("id", id); radioId(radioId); binary("publicKey", publicKey); put("nodeName", nodeName)
        put("firmwareVersion", firmwareVersion.toLong()); put("firmwareVersionString", firmwareVersionString)
        put("manufacturerName", manufacturerName); put("buildDate", buildDate)
        put("maxContacts", maxContacts.toLong()); put("maxChannels", maxChannels.toLong())
        put("frequency", frequency.toLong()); put("bandwidth", bandwidth.toLong())
        put("spreadingFactor", spreadingFactor.toLong()); put("codingRate", codingRate.toLong())
        put("txPower", txPower.toLong()); put("maxTxPower", maxTxPower.toLong())
        number("latitude", latitude); number("longitude", longitude); put("blePin", blePin.toLong())
        put("clientRepeat", clientRepeat); put("pathHashMode", pathHashMode.toLong())
        text("defaultFloodScopeName", defaultFloodScopeName)
        integer("preRepeatFrequency", preRepeatFrequency?.toLong()); integer("preRepeatBandwidth", preRepeatBandwidth?.toLong())
        integer("preRepeatSpreadingFactor", preRepeatSpreadingFactor?.toLong()); integer("preRepeatCodingRate", preRepeatCodingRate?.toLong())
        put("manualAddContacts", manualAddContacts); put("autoAddConfig", autoAddConfig.toLong())
        put("autoAddMaxHops", autoAddMaxHops.toLong()); put("multiAcks", multiAcks.toLong())
        put("telemetryModeBase", telemetryModeBase.toLong()); put("telemetryModeLoc", telemetryModeLoc.toLong())
        put("telemetryModeEnv", telemetryModeEnv.toLong()); put("advertLocationPolicy", advertLocationPolicy.toLong())
        date("lastConnected", lastConnected); put("lastContactSync", lastContactSync.toLong()); put("isActive", isActive)
        text("ocvPreset", ocvPreset); text("appliedRadioPresetID", appliedRadioPresetID); text("customOCVArrayString", customOCVArrayString)
        put("connectionMethods", JsonArray(connectionMethods.map(::encodeConnectionMethod))); strings("knownRegions", knownRegions)
    }
}

internal fun decodeContact(value: JsonElement): ContactDTO = value.row("contacts").run {
    ContactDTO(
        id = uuid("id"), radioId = radioId(), publicKey = bytes("publicKey"), name = string("name"),
        typeRawValue = ubyte("typeRawValue"), flags = ubyte("flags"), outPathLength = ubyte("outPathLength"),
        outPath = bytes("outPath"), lastAdvertTimestamp = uint("lastAdvertTimestamp"),
        latitude = double("latitude"), longitude = double("longitude"), lastModified = uint("lastModified"),
        lastHeardTimestamp = optionalUInt("lastHeardTimestamp"), nickname = optionalString("nickname"),
        isBlocked = boolean("isBlocked"), isMuted = boolean("isMuted"), isFavorite = boolean("isFavorite"),
        isPinned = optionalBoolean("isPinned") ?: boolean("isFavorite"),
        lastMessageDate = optionalDate("lastMessageDate"), unreadCount = long("unreadCount"),
        unreadMentionCount = long("unreadMentionCount"), ocvPreset = optionalString("ocvPreset"),
        customOCVArrayString = optionalString("customOCVArrayString"), avatarImageData = optionalBytes("avatarImageData"),
    )
}

internal fun encodeContact(dto: ContactDTO): JsonObject = dto.run {
    wireObject {
        uuid("id", id); radioId(radioId); binary("publicKey", publicKey); put("name", name)
        put("typeRawValue", typeRawValue.toLong()); put("flags", flags.toLong()); put("outPathLength", outPathLength.toLong())
        binary("outPath", outPath); put("lastAdvertTimestamp", lastAdvertTimestamp.toLong())
        number("latitude", latitude); number("longitude", longitude); put("lastModified", lastModified.toLong())
        integer("lastHeardTimestamp", lastHeardTimestamp?.toLong()); text("nickname", nickname)
        put("isBlocked", isBlocked); put("isMuted", isMuted); put("isFavorite", isFavorite)
        put("isPinned", isPinned)
        date("lastMessageDate", lastMessageDate); put("unreadCount", unreadCount); put("unreadMentionCount", unreadMentionCount)
        text("ocvPreset", ocvPreset); text("customOCVArrayString", customOCVArrayString); binary("avatarImageData", avatarImageData)
    }
}

internal fun decodeChannel(value: JsonElement): ChannelDTO = value.row("channels").run {
    ChannelDTO.fromLegacyFields(
        uuid("id"), radioId(), ubyte("index"), string("name"), bytes("secret"), boolean("isEnabled"),
        optionalDate("lastMessageDate"), long("unreadCount"), optionalLong("unreadMentionCount"),
        optionalNotification("notificationLevel")?.rawValue, optionalBoolean("isPinned") ?: optionalBoolean("isFavorite"),
        optionalString("floodScopeModeRawValue"), optionalString("regionScope"),
    )
}

internal fun encodeChannel(dto: ChannelDTO): JsonObject = dto.run {
    wireObject {
        uuid("id", id); radioId(radioId); put("index", index.toLong()); put("name", name); binary("secret", secret)
        put("isEnabled", isEnabled); date("lastMessageDate", lastMessageDate)
        put("unreadCount", unreadCount); put("unreadMentionCount", unreadMentionCount)
        put("notificationLevel", notificationLevel.rawValue); put("isFavorite", isFavorite)
        put("isPinned", isPinned)
        put("floodScopeModeRawValue", floodScopeModeRawValue); text("regionScope", regionScope)
    }
}

private fun decodeConnectionMethod(value: JsonElement): ConnectionMethod {
    val objectValue = value.row("connectionMethods").value
    if (objectValue.size != 1) invalidValue("connectionMethods", BackupValueProblem.ENUM)
    val (kind, payload) = objectValue.entries.single()
    val row = payload.row("connectionMethods.$kind")
    return when (kind) {
        "bluetooth" -> ConnectionMethod.Bluetooth(row.uuid("peripheralUUID"), row.optionalString("displayName"))
        "wifi" -> ConnectionMethod.WiFi(row.string("host"), row.ushort("port"), row.optionalString("displayName"))
        else -> invalidValue("connectionMethods", BackupValueProblem.ENUM)
    }
}

private fun encodeConnectionMethod(method: ConnectionMethod): JsonObject = wireObject {
    when (method) {
        is ConnectionMethod.Bluetooth -> put("bluetooth", wireObject {
            uuid("peripheralUUID", method.peripheralUUID); text("displayName", method.displayName)
        })
        is ConnectionMethod.WiFi -> put("wifi", wireObject {
            put("host", method.host); put("port", method.port.toLong()); text("displayName", method.displayName)
        })
    }
}
