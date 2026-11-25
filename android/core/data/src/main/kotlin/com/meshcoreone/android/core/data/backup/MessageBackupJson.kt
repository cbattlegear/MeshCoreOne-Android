// PortedFrom: MC1Services/Sources/MC1Services/Models/Message.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/MessageRepeat.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/Reaction.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/RemoteNodeSession.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/RoomMessage.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.event.RouteType
import kotlinx.serialization.json.*

internal fun decodeMessage(value: JsonElement): MessageDTO = value.row("messages").run {
    val createdAt = date("createdAt")
    MessageDTO(
        id = uuid("id"), radioId = radioId(), contactID = optionalUUID("contactID"), channelIndex = optionalUByte("channelIndex"),
        text = string("text"), timestamp = uint("timestamp"), createdAt = createdAt, sortDate = optionalDate("sortDate") ?: createdAt,
        direction = MessageDirection.fromRawValue(long("direction")) ?: invalidValue("messages.direction", BackupValueProblem.ENUM),
        status = MessageStatus.fromRawValue(long("status")) ?: invalidValue("messages.status", BackupValueProblem.ENUM),
        textType = TextType.fromRawValue(ubyte("textType")) ?: invalidValue("messages.textType", BackupValueProblem.ENUM),
        ackCode = optionalUInt("ackCode"), pathLength = ubyte("pathLength"), snr = optionalDouble("snr"),
        pathNodes = optionalBytes("pathNodes"), senderKeyPrefix = optionalBytes("senderKeyPrefix"),
        senderNodeName = optionalString("senderNodeName"), isRead = boolean("isRead"), replyToID = optionalUUID("replyToID"),
        roundTripTime = optionalUInt("roundTripTime"), heardRepeats = long("heardRepeats"), sendCount = long("sendCount"),
        retryAttempt = long("retryAttempt"), maxRetryAttempts = long("maxRetryAttempts"), deduplicationKey = optionalString("deduplicationKey"),
        linkPreviewURL = optionalString("linkPreviewURL"), linkPreviewTitle = optionalString("linkPreviewTitle"),
        linkPreviewImageData = optionalBytes("linkPreviewImageData"), linkPreviewIconData = optionalBytes("linkPreviewIconData"),
        linkPreviewFetched = boolean("linkPreviewFetched"), containsSelfMention = boolean("containsSelfMention"),
        mentionSeen = boolean("mentionSeen"), failureSeen = optionalBoolean("failureSeen") ?: false,
        timestampCorrected = boolean("timestampCorrected"), senderTimestamp = optionalUInt("senderTimestamp"),
        reactionSummary = optionalString("reactionSummary"),
        routeType = optionalUByte("routeType")?.let {
            RouteType.fromRawValue(it) ?: invalidValue("messages.routeType", BackupValueProblem.ENUM)
        },
        regionScope = optionalString("regionScope"), regionScopeMatches = optionalStrings("regionScopeMatches") ?: SnapshotList.empty(),
        reactionTargetMessageID = optionalUUID("reactionTargetMessageID"), reactionSenderName = optionalString("reactionSenderName"),
    )
}

internal fun encodeMessage(dto: MessageDTO): JsonObject = dto.run {
    wireObject {
        uuid("id", id); radioId(radioId); uuid("contactID", contactID); integer("channelIndex", channelIndex?.toLong())
        put("text", text); put("timestamp", timestamp.toLong()); date("createdAt", createdAt); date("sortDate", sortDate)
        put("direction", direction.rawValue); put("status", status.rawValue); put("textType", textType.rawValue.toLong())
        integer("ackCode", ackCode?.toLong()); put("pathLength", pathLength.toLong()); number("snr", snr)
        binary("pathNodes", pathNodes); binary("senderKeyPrefix", senderKeyPrefix); text("senderNodeName", senderNodeName)
        put("isRead", isRead); uuid("replyToID", replyToID); integer("roundTripTime", roundTripTime?.toLong())
        put("heardRepeats", heardRepeats); put("sendCount", sendCount); put("retryAttempt", retryAttempt)
        put("maxRetryAttempts", maxRetryAttempts); text("deduplicationKey", deduplicationKey)
        text("linkPreviewURL", linkPreviewURL); text("linkPreviewTitle", linkPreviewTitle)
        binary("linkPreviewImageData", linkPreviewImageData); binary("linkPreviewIconData", linkPreviewIconData)
        put("linkPreviewFetched", linkPreviewFetched); put("containsSelfMention", containsSelfMention); put("mentionSeen", mentionSeen)
        put("failureSeen", failureSeen); put("timestampCorrected", timestampCorrected); integer("senderTimestamp", senderTimestamp?.toLong())
        text("reactionSummary", reactionSummary); integer("routeType", routeType?.rawValue?.toLong())
        text("regionScope", regionScope); strings("regionScopeMatches", regionScopeMatches)
        uuid("reactionTargetMessageID", reactionTargetMessageID); text("reactionSenderName", reactionSenderName)
    }
}

internal fun decodeRepeat(value: JsonElement): MessageRepeatDTO = value.row("messageRepeats").run {
    MessageRepeatDTO(
        uuid("id"), uuid("messageID"), date("receivedAt"), bytes("pathNodes"), ubyte("pathLength"),
        optionalDouble("snr"), optionalLong("rssi"), optionalUUID("rxLogEntryID"),
    )
}
internal fun encodeRepeat(dto: MessageRepeatDTO): JsonObject = dto.run {
    wireObject {
        uuid("id", id); uuid("messageID", messageID); date("receivedAt", receivedAt); binary("pathNodes", pathNodes)
        put("pathLength", pathLength.toLong()); number("snr", snr); integer("rssi", rssi); uuid("rxLogEntryID", rxLogEntryID)
    }
}

internal fun decodeReaction(value: JsonElement): ReactionDTO = value.row("reactions").run {
    ReactionDTO(
        uuid("id"), uuid("messageID"), string("emoji"), string("senderName"), string("messageHash"), string("rawText"),
        date("receivedAt"), optionalUByte("channelIndex"), optionalUUID("contactID"), radioId(),
    )
}
internal fun encodeReaction(dto: ReactionDTO): JsonObject = dto.run {
    wireObject {
        uuid("id", id); uuid("messageID", messageID); put("emoji", emoji); put("senderName", senderName)
        put("messageHash", messageHash); put("rawText", rawText); date("receivedAt", receivedAt)
        integer("channelIndex", channelIndex?.toLong()); uuid("contactID", contactID); radioId(radioId)
    }
}

internal fun decodeSession(value: JsonElement): RemoteNodeSessionDTO = value.row("remoteNodeSessions").run {
    RemoteNodeSessionDTO(
        id = uuid("id"), radioId = radioId(), publicKey = bytes("publicKey"), name = string("name"),
        role = RemoteNodeRole.fromRawValue(ubyte("role")) ?: invalidValue("remoteNodeSessions.role", BackupValueProblem.ENUM),
        latitude = double("latitude"), longitude = double("longitude"), isConnected = boolean("isConnected"),
        permissionLevel = RoomPermissionLevel.fromRawValue(ubyte("permissionLevel"))
            ?: invalidValue("remoteNodeSessions.permissionLevel", BackupValueProblem.ENUM),
        lastConnectedDate = optionalDate("lastConnectedDate"), lastBatteryMillivolts = optionalUShort("lastBatteryMillivolts"),
        lastUptimeSeconds = optionalUInt("lastUptimeSeconds"), lastNoiseFloor = optionalShort("lastNoiseFloor"),
        unreadCount = long("unreadCount"), notificationLevel = notification("notificationLevel"),
        isFavorite = optionalBoolean("isPinned") ?: optionalBoolean("isFavorite") ?: false,
        lastRxAirtimeSeconds = optionalUInt("lastRxAirtimeSeconds"), neighborCount = long("neighborCount"),
        lastSyncTimestamp = uint("lastSyncTimestamp"), lastMessageDate = optionalDate("lastMessageDate"),
    )
}
internal fun encodeSession(dto: RemoteNodeSessionDTO): JsonObject = dto.run {
    wireObject {
        uuid("id", id); radioId(radioId); binary("publicKey", publicKey); put("name", name); put("role", role.rawValue.toLong())
        number("latitude", latitude); number("longitude", longitude); put("isConnected", isConnected)
        put("permissionLevel", permissionLevel.rawValue.toLong()); date("lastConnectedDate", lastConnectedDate)
        integer("lastBatteryMillivolts", lastBatteryMillivolts?.toLong()); integer("lastUptimeSeconds", lastUptimeSeconds?.toLong())
        integer("lastNoiseFloor", lastNoiseFloor?.toLong()); put("unreadCount", unreadCount); put("notificationLevel", notificationLevel.rawValue)
        put("isFavorite", isFavorite); integer("lastRxAirtimeSeconds", lastRxAirtimeSeconds?.toLong()); put("neighborCount", neighborCount)
        put("isPinned", isFavorite)
        put("lastSyncTimestamp", lastSyncTimestamp.toLong()); date("lastMessageDate", lastMessageDate)
    }
}

internal fun decodeRoomMessage(value: JsonElement): RoomMessageDTO = value.row("roomMessages").run {
    RoomMessageDTO(
        uuid("id"), uuid("sessionID"), bytes("authorKeyPrefix"), optionalString("authorName"), string("text"),
        uint("timestamp"), date("createdAt"), boolean("isFromSelf"), string("deduplicationKey"), long("statusRawValue"),
        optionalUInt("ackCode"), optionalUInt("roundTripTime"), long("retryAttempt"), long("maxRetryAttempts"),
        optionalBoolean("failureSeen") ?: false,
    )
}
internal fun encodeRoomMessage(dto: RoomMessageDTO): JsonObject = dto.run {
    wireObject {
        uuid("id", id); uuid("sessionID", sessionID); binary("authorKeyPrefix", authorKeyPrefix); text("authorName", authorName)
        put("text", text); put("timestamp", timestamp.toLong()); date("createdAt", createdAt); put("isFromSelf", isFromSelf)
        put("deduplicationKey", deduplicationKey); put("statusRawValue", statusRawValue); integer("ackCode", ackCode?.toLong())
        integer("roundTripTime", roundTripTime?.toLong()); put("retryAttempt", retryAttempt); put("maxRetryAttempts", maxRetryAttempts)
        put("failureSeen", failureSeen)
    }
}
