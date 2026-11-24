// PortedFrom: MC1Services/Sources/MC1Services/Models/SavedTracePath.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/BlockedChannelSender.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/NodeStatusSnapshot.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/DiscoveredNode.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.model.*
import kotlinx.serialization.json.*

internal fun decodeTracePath(value: JsonElement): SavedTracePathDTO = value.row("savedTracePaths").run {
    SavedTracePathDTO(uuid("id"), radioId(), string("name"), bytes("pathBytes"), long("hashSize"), date("createdDate"),
        array("runs").map { element ->
            element.row("savedTracePaths.runs").run {
                TracePathRunDTO(uuid("id"), date("date"), boolean("success"), long("roundTripMs"),
                    array("hopsSNR").map { item ->
                        BackupJsonRow(buildJsonObject { put("number", item) }, "savedTracePaths.runs.hopsSNR").double("number")
                    }.snapshot())
            }
        }.snapshot())
}
internal fun encodeTracePath(dto: SavedTracePathDTO): JsonObject = dto.run {
    wireObject {
        uuid("id", id); radioId(radioId); put("name", name); binary("pathBytes", pathBytes)
        put("hashSize", hashSize); date("createdDate", createdDate)
        put("runs", JsonArray(runs.map { run ->
            wireObject {
                uuid("id", run.id); date("date", run.date); put("success", run.success); put("roundTripMs", run.roundTripMs)
                put("hopsSNR", JsonArray(run.hopsSNR.map {
                    if (!it.isFinite()) invalidValue("savedTracePaths.runs.hopsSNR", BackupValueProblem.RANGE)
                    JsonPrimitive(it)
                }))
            }
        }))
    }
}

internal fun decodeBlockedSender(value: JsonElement): BlockedChannelSenderDTO = value.row("blockedChannelSenders").run {
    BlockedChannelSenderDTO(uuid("id"), string("name"), radioId(), date("dateBlocked"))
}
internal fun encodeBlockedSender(dto: BlockedChannelSenderDTO): JsonObject = dto.run {
    wireObject { uuid("id", id); put("name", name); radioId(radioId); date("dateBlocked", dateBlocked) }
}

internal fun decodeSnapshot(value: JsonElement): NodeStatusSnapshotDTO = value.row("nodeStatusSnapshots").run {
    NodeStatusSnapshotDTO(
        id = uuid("id"), timestamp = date("timestamp"), nodePublicKey = bytes("nodePublicKey"),
        batteryMillivolts = optionalUShort("batteryMillivolts"), lastSNR = optionalDouble("lastSNR"),
        lastRSSI = optionalShort("lastRSSI"), noiseFloor = optionalShort("noiseFloor"), uptimeSeconds = optionalUInt("uptimeSeconds"),
        rxAirtimeSeconds = optionalUInt("rxAirtimeSeconds"), packetsSent = optionalUInt("packetsSent"),
        packetsReceived = optionalUInt("packetsReceived"), receiveErrors = optionalUInt("receiveErrors"),
        sentDirect = optionalUInt("sentDirect"), sentFlood = optionalUInt("sentFlood"),
        receivedDirect = optionalUInt("receivedDirect"), receivedFlood = optionalUInt("receivedFlood"),
        directDuplicates = optionalUInt("directDuplicates"), floodDuplicates = optionalUInt("floodDuplicates"),
        postedCount = optionalUShort("postedCount"), postPushCount = optionalUShort("postPushCount"),
        neighborSnapshots = optionalArray("neighborSnapshots")?.map {
            it.row("nodeStatusSnapshots.neighborSnapshots").run {
                NeighborSnapshotEntry(bytes("publicKeyPrefix"), double("snr"), long("secondsAgo"))
            }
        }?.snapshot(),
        telemetryEntries = optionalArray("telemetryEntries")?.map {
            it.row("nodeStatusSnapshots.telemetryEntries").run {
                TelemetrySnapshotEntry(long("channel"), string("type"), double("value"))
            }
        }?.snapshot(),
        latitude = optionalDouble("latitude"), longitude = optionalDouble("longitude"), altitude = optionalDouble("altitude"),
        selfRadioID = optionalUUID("selfRadioID")?.let(::RadioId),
    )
}
internal fun encodeSnapshot(dto: NodeStatusSnapshotDTO): JsonObject = dto.run {
    wireObject {
        uuid("id", id); date("timestamp", timestamp); binary("nodePublicKey", nodePublicKey); uuid("selfRadioID", selfRadioID?.value)
        integer("batteryMillivolts", batteryMillivolts?.toLong()); number("lastSNR", lastSNR)
        integer("lastRSSI", lastRSSI?.toLong()); integer("noiseFloor", noiseFloor?.toLong())
        integer("uptimeSeconds", uptimeSeconds?.toLong()); integer("rxAirtimeSeconds", rxAirtimeSeconds?.toLong())
        integer("packetsSent", packetsSent?.toLong()); integer("packetsReceived", packetsReceived?.toLong())
        integer("receiveErrors", receiveErrors?.toLong()); integer("sentDirect", sentDirect?.toLong())
        integer("sentFlood", sentFlood?.toLong()); integer("receivedDirect", receivedDirect?.toLong())
        integer("receivedFlood", receivedFlood?.toLong()); integer("directDuplicates", directDuplicates?.toLong())
        integer("floodDuplicates", floodDuplicates?.toLong()); integer("postedCount", postedCount?.toLong())
        integer("postPushCount", postPushCount?.toLong())
        neighborSnapshots?.let { rows -> put("neighborSnapshots", JsonArray(rows.map {
            wireObject { binary("publicKeyPrefix", it.publicKeyPrefix); number("snr", it.snr); put("secondsAgo", it.secondsAgo) }
        })) }
        telemetryEntries?.let { rows -> put("telemetryEntries", JsonArray(rows.map {
            wireObject { put("channel", it.channel); put("type", it.type); number("value", it.value) }
        })) }
        number("latitude", latitude); number("longitude", longitude); number("altitude", altitude)
    }
}

internal fun decodeDiscoveredNode(value: JsonElement): DiscoveredNodeDTO = value.row("discoveredNodes").run {
    DiscoveredNodeDTO(
        uuid("id"), radioId(), bytes("publicKey"), string("name"), ubyte("typeRawValue"), date("lastHeard"),
        uint("lastAdvertTimestamp"), double("latitude"), double("longitude"), ubyte("outPathLength"), bytes("outPath"),
        optionalLong("inboundHopCount"), optionalUInt("inboundHopAdvertTimestamp"),
    )
}
internal fun encodeDiscoveredNode(dto: DiscoveredNodeDTO): JsonObject = dto.run {
    wireObject {
        uuid("id", id); radioId(radioId); binary("publicKey", publicKey); put("name", name); put("typeRawValue", typeRawValue.toLong())
        date("lastHeard", lastHeard); put("lastAdvertTimestamp", lastAdvertTimestamp.toLong())
        number("latitude", latitude); number("longitude", longitude); put("outPathLength", outPathLength.toLong()); binary("outPath", outPath)
        integer("inboundHopCount", inboundHopCount); integer("inboundHopAdvertTimestamp", inboundHopAdvertTimestamp?.toLong())
    }
}
