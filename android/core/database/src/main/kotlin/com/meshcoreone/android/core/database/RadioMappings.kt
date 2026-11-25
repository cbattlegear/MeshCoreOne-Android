// PortedFrom: MC1Services/Sources/MC1Services/Models/Device.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/Contact.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/Channel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/DiscoveredNode.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/BlockedChannelSender.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Explicit DTO/row separation and checked stored/wire widths.
package com.meshcoreone.android.core.database

import com.meshcoreone.android.core.model.*

fun DeviceDTO.toEntity(): DeviceEntity = DeviceEntity(
    id, radioId.value, publicKey, nodeName, firmwareVersion.toLong(), firmwareVersionString, manufacturerName, buildDate,
    maxContacts.toLong(), maxChannels.toLong(), frequency.toLong(), bandwidth.toLong(), spreadingFactor.toLong(), codingRate.toLong(),
    txPower.toLong(), maxTxPower.toLong(), latitude, longitude, blePin.toLong(), clientRepeat, pathHashMode.toLong(), defaultFloodScopeName,
    preRepeatFrequency?.toLong(), preRepeatBandwidth?.toLong(), preRepeatSpreadingFactor?.toLong(), preRepeatCodingRate?.toLong(),
    manualAddContacts, autoAddConfig.toLong(), autoAddMaxHops.toLong(), multiAcks.toLong(), telemetryModeBase.toLong(),
    telemetryModeLoc.toLong(), telemetryModeEnv.toLong(), advertLocationPolicy.toLong(), StoredInstant.from(lastConnected),
    lastContactSync.toLong(), isActive, ocvPreset, appliedRadioPresetID, customOCVArrayString, connectionMethods, knownRegions,
)

fun DeviceEntity.toDTO(): DeviceDTO = DeviceDTO(
    id = id, radioId = RadioId(radioId), publicKey = publicKey, nodeName = nodeName,
    firmwareVersion = firmwareVersion.ubyte("device.firmwareVersion"), firmwareVersionString = firmwareVersionString,
    manufacturerName = manufacturerName, buildDate = buildDate, maxContacts = maxContacts.ushort("device.maxContacts"),
    maxChannels = maxChannels.ubyte("device.maxChannels"), frequency = frequency.uint("device.frequency"), bandwidth = bandwidth.uint("device.bandwidth"),
    spreadingFactor = spreadingFactor.ubyte("device.spreadingFactor"), codingRate = codingRate.ubyte("device.codingRate"),
    txPower = txPower.byte("device.txPower"), maxTxPower = maxTxPower.byte("device.maxTxPower"), latitude = latitude, longitude = longitude,
    blePin = blePin.uint("device.blePin"), clientRepeat = clientRepeat, pathHashMode = pathHashMode.ubyte("device.pathHashMode"),
    defaultFloodScopeName = defaultFloodScopeName, preRepeatFrequency = preRepeatFrequency?.uint("device.preRepeatFrequency"),
    preRepeatBandwidth = preRepeatBandwidth?.uint("device.preRepeatBandwidth"),
    preRepeatSpreadingFactor = preRepeatSpreadingFactor?.ubyte("device.preRepeatSpreadingFactor"),
    preRepeatCodingRate = preRepeatCodingRate?.ubyte("device.preRepeatCodingRate"),
    manualAddContacts = manualAddContacts, autoAddConfig = autoAddConfig.ubyte("device.autoAddConfig"),
    autoAddMaxHops = autoAddMaxHops.ubyte("device.autoAddMaxHops"), multiAcks = multiAcks.ubyte("device.multiAcks"),
    telemetryModeBase = telemetryModeBase.ubyte("device.telemetryModeBase"), telemetryModeLoc = telemetryModeLoc.ubyte("device.telemetryModeLoc"),
    telemetryModeEnv = telemetryModeEnv.ubyte("device.telemetryModeEnv"), advertLocationPolicy = advertLocationPolicy.ubyte("device.advertLocationPolicy"),
    lastConnected = lastConnected.toInstant(), lastContactSync = lastContactSync.uint("device.lastContactSync"), isActive = isActive,
    ocvPreset = ocvPreset, appliedRadioPresetID = appliedRadioPresetID, customOCVArrayString = customOCVArrayString,
    connectionMethods = connectionMethods, knownRegions = knownRegions,
)

fun DeviceEntity.applying(dto: DeviceDTO): DeviceEntity = dto.toEntity().copy(id = id, knownRegions = knownRegions)

fun ContactDTO.toEntity(): ContactEntity = ContactEntity(
    radioId.value, id, publicKey, name, typeRawValue.toLong(), flags.toLong(), outPathLength.toLong(), outPath,
    lastAdvertTimestamp.toLong(), latitude, longitude, lastModified.toLong(), lastHeardTimestamp?.toLong() ?: 0L, nickname,
    isBlocked, isMuted, isFavorite, lastMessageDate?.let(StoredInstant::from), unreadCount, unreadMentionCount,
    ocvPreset, customOCVArrayString, avatarImageData, isPinned,
)

fun ContactEntity.toDTO(): ContactDTO = ContactDTO(
    id, RadioId(radioId), publicKey, name, typeRawValue.ubyte("contact.typeRawValue"), flags.ubyte("contact.flags"),
    outPathLength.toUByte(), outPath, lastAdvertTimestamp.uint("contact.lastAdvertTimestamp"), latitude, longitude,
    lastModified.uint("contact.lastModified"), lastHeardTimestamp.uint("contact.lastHeardTimestamp"), nickname, isBlocked,
    isMuted, isFavorite, lastMessageDate?.toInstant(), unreadCount, unreadMentionCount, ocvPreset, customOCVArrayString, avatarImageData,
    isPinned = isPinned,
)

fun ContactEntity.applying(dto: ContactDTO): ContactEntity = dto.toEntity().copy(
    id = id, radioId = radioId, publicKey = publicKey, lastHeardTimestamp = maxOf(lastHeardTimestamp, dto.lastHeardTimestamp?.toLong() ?: 0L),
    isPinned = isPinned,
)

fun ChannelDTO.toEntity(): ChannelEntity = ChannelEntity(
    radioId.value, id, index.toLong(), name, secret, isEnabled, lastMessageDate?.let(StoredInstant::from),
    unreadCount, unreadMentionCount, notificationLevel.rawValue, null, isFavorite, floodScopeModeRawValue, regionScope,
)

fun ChannelEntity.toDTO(): ChannelDTO = ChannelDTO(
    id, RadioId(radioId), index.ubyte("channel.index"), name, secret, isEnabled, lastMessageDate?.toInstant(),
    unreadCount, unreadMentionCount, storedNotificationLevel(notificationLevelRawValue, legacyIsMuted), isFavorite, floodScopeModeRawValue, regionScope,
)

fun ChannelEntity.applying(dto: ChannelDTO): ChannelEntity = dto.toEntity().copy(
    id = id, radioId = radioId, index = index, legacyIsMuted = legacyIsMuted, isFavorite = isFavorite,
)

internal fun storedNotificationLevel(raw: Long, legacyMuted: Boolean?): NotificationLevel =
    if (raw == -1L) {
        if (legacyMuted == true) NotificationLevel.MUTED else NotificationLevel.ALL
    } else NotificationLevel.fromRawValue(raw) ?: throw DatabaseValueException("notificationLevel", "Unknown raw value $raw")

fun DiscoveredNodeDTO.toEntity(): DiscoveredNodeEntity = DiscoveredNodeEntity(
    radioId.value, id, publicKey, name, typeRawValue.toLong(), StoredInstant.from(lastHeard), lastAdvertTimestamp.toLong(),
    latitude, longitude, outPathLength.toLong(), outPath, inboundHopCount, inboundHopAdvertTimestamp?.toLong(),
)

fun DiscoveredNodeEntity.toDTO(): DiscoveredNodeDTO = DiscoveredNodeDTO(
    id, RadioId(radioId), publicKey, name, typeRawValue.ubyte("discoveredNode.typeRawValue"), lastHeard.toInstant(),
    lastAdvertTimestamp.uint("discoveredNode.lastAdvertTimestamp"), latitude, longitude, outPathLength.toUByte(), outPath,
    inboundHopCount, inboundHopAdvertTimestamp?.uint("discoveredNode.inboundHopAdvertTimestamp"),
)

fun BlockedChannelSenderDTO.toEntity(): BlockedChannelSenderEntity =
    BlockedChannelSenderEntity(radioId.value, id, name, StoredInstant.from(dateBlocked))
fun BlockedChannelSenderEntity.toDTO(): BlockedChannelSenderDTO =
    BlockedChannelSenderDTO(id, name, RadioId(radioId), dateBlocked.toInstant())
