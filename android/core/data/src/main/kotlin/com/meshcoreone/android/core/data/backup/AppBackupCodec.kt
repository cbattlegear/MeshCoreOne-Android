// PortedFrom: MC1Services/Sources/MC1Services/Services/AppBackupEnvelope.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Stream overloads own and close their input/output, including failure and cancellation. */
class AppBackupCodec internal constructor(private val observer: JsonMaterializationObserver?) {
    constructor() : this(null)
    fun parseBackup(
        input: InputStream,
        maxUncompressedBytes: Long = BackupContract.MAX_EXPANDED_BYTES,
        checkCancellation: () -> Unit = {},
    ): AppBackupEnvelope = try {
        BackupInflater(input, maxUncompressedBytes, checkCancellation = checkCancellation).use { inflated ->
            val envelope = try {
                decodeJson(inflated, checkCancellation)
            } catch (cause: BackupValueException) {
                requireCompleteInflation(inflated)
                throw cause
            } catch (cause: SerializationException) {
                requireCompleteInflation(inflated)
                throw cause
            } catch (cause: java.nio.charset.CharacterCodingException) {
                requireCompleteInflation(inflated)
                throw cause
            }
            envelope.also { it.validate() }
        }
    } catch (cause: BackupValueException) {
        throw AppBackupException(AppBackupError.InvalidFile, cause)
    } catch (cause: SerializationException) {
        throw AppBackupException(AppBackupError.InvalidFile, cause)
    } catch (cause: IOException) {
        throw AppBackupException(AppBackupError.InvalidFile, cause)
    }

    private fun requireCompleteInflation(inflated: InputStream) {
        // Source inflation/size errors precede Codable errors, even for non-JSON bombs.
        val buffer = ByteArray(BACKUP_STREAM_CHUNK)
        while (inflated.read(buffer) != -1) Unit
    }

    fun parseBackup(data: Bytes, maxUncompressedBytes: Long = BackupContract.MAX_EXPANDED_BYTES): AppBackupEnvelope {
        BackupContract.validateCompressedSize(data.size.toLong())
        return parseBackup(ByteArrayInputStream(data.toByteArray()), maxUncompressedBytes)
    }

    fun encode(
        envelope: AppBackupEnvelope,
        output: OutputStream,
        checkCancellation: () -> Unit = {},
    ) {
        BackupDeflater(output, checkCancellation = checkCancellation).use { compressed ->
            envelope.validateVersionAndOwnership()
            encodeJson(envelope, compressed, checkCancellation)
            compressed.finish()
        }
    }

    fun encode(envelope: AppBackupEnvelope): Bytes {
        val output = ByteArrayOutputStream(BACKUP_STREAM_CHUNK)
        encode(envelope, output)
        return Bytes(output.toByteArray())
    }

    internal fun decodeJson(input: InputStream, checkCancellation: () -> Unit = {}): AppBackupEnvelope {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val stream = BackupJsonStream(InputStreamReader(input, decoder).buffered(BACKUP_STREAM_CHUNK), checkCancellation, observer)
        val metadata = linkedMapOf<String, JsonElement>()
        var devices = SnapshotList.empty<DeviceDTO>()
        var contacts = SnapshotList.empty<ContactDTO>()
        var channels = SnapshotList.empty<ChannelDTO>()
        var messages = SnapshotList.empty<MessageDTO>()
        var repeats = SnapshotList.empty<MessageRepeatDTO>()
        var reactions = SnapshotList.empty<ReactionDTO>()
        var roomMessages = SnapshotList.empty<RoomMessageDTO>()
        var sessions = SnapshotList.empty<RemoteNodeSessionDTO>()
        var paths = SnapshotList.empty<SavedTracePathDTO>()
        var blocked = SnapshotList.empty<BlockedChannelSenderDTO>()
        var snapshots = SnapshotList.empty<NodeStatusSnapshotDTO>()
        var discovered = SnapshotList.empty<DiscoveredNodeDTO>()
        val arrays = hashSetOf<String>()
        stream.objectMembers(BackupWireSelections.envelope) { key, selection ->
            fun <T> rows(decode: (JsonElement) -> T): SnapshotList<T> =
                stream.arrayValues(BackupWireSelections.records.getValue(key), decode).snapshot()
            when (key) {
                "devices" -> devices = rows(::decodeDevice)
                "contacts" -> contacts = rows(::decodeContact)
                "channels" -> channels = rows(::decodeChannel)
                "messages" -> messages = rows(::decodeMessage)
                "messageRepeats" -> repeats = rows(::decodeRepeat)
                "reactions" -> reactions = rows(::decodeReaction)
                "roomMessages" -> roomMessages = rows(::decodeRoomMessage)
                "remoteNodeSessions" -> sessions = rows(::decodeSession)
                "savedTracePaths" -> paths = rows(::decodeTracePath)
                "blockedChannelSenders" -> blocked = rows(::decodeBlockedSender)
                "nodeStatusSnapshots" -> snapshots = rows(::decodeSnapshot)
                "discoveredNodes" -> {
                    if (stream.nextIsNull()) {
                        if (stream.element() !is JsonNull) invalidValue(key, BackupValueProblem.TYPE)
                    } else discovered = rows(::decodeDiscoveredNode)
                }
                "version", "exportDate", "appVersion", "appBuild", "manifest", "userDefaults" -> metadata[key] = stream.element(selection)
            }
            if (key in BackupContract.modelArrayKeys) arrays += key
        }
        stream.requireEnd()
        for (key in BackupContract.modelArrayKeys) {
            if (key !in arrays && key !in BackupContract.legacyOptionalArrayKeys) invalidValue(key, BackupValueProblem.MISSING)
        }
        val row = BackupJsonRow(JsonObject(metadata), "envelope")
        return AppBackupEnvelope(
            row.long("version"), row.date("exportDate"), row.string("appVersion"), row.string("appBuild"),
            decodeManifest(row.required("manifest")), devices, contacts, channels, messages, repeats, reactions,
            roomMessages, sessions, paths, blocked, snapshots, discovered, row.optional("userDefaults")?.let(BackupUserDefaults::decode),
        )
    }

    internal fun encodeJson(envelope: AppBackupEnvelope, output: OutputStream, checkCancellation: () -> Unit = {}) {
        val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val writer = OutputStreamWriter(output, encoder).buffered(BACKUP_STREAM_CHUNK)
        val fields = sortedMapOf<String, () -> Unit>()
        fun value(key: String, element: JsonElement) { fields[key] = { writer.write(element.toString()) } }
        fun <T> array(key: String, rows: List<T>, encode: (T) -> JsonObject) {
            fields[key] = {
                writer.write("[")
                rows.forEachIndexed { index, row ->
                    checkCancellation()
                    if (index > 0) writer.write(",")
                    writer.write(encode(row).toString())
                }
                writer.write("]")
            }
        }
        value("version", JsonPrimitive(envelope.version))
        value("exportDate", wireObject { date("date", envelope.exportDate) }.getValue("date"))
        value("appVersion", JsonPrimitive(envelope.appVersion)); value("appBuild", JsonPrimitive(envelope.appBuild))
        value("manifest", encodeManifest(envelope.manifest))
        array("devices", envelope.devices, ::encodeDevice); array("contacts", envelope.contacts, ::encodeContact)
        array("channels", envelope.channels, ::encodeChannel); array("messages", envelope.messages, ::encodeMessage)
        array("messageRepeats", envelope.messageRepeats, ::encodeRepeat); array("reactions", envelope.reactions, ::encodeReaction)
        array("roomMessages", envelope.roomMessages, ::encodeRoomMessage); array("remoteNodeSessions", envelope.remoteNodeSessions, ::encodeSession)
        array("savedTracePaths", envelope.savedTracePaths, ::encodeTracePath); array("blockedChannelSenders", envelope.blockedChannelSenders, ::encodeBlockedSender)
        array("nodeStatusSnapshots", envelope.nodeStatusSnapshots, ::encodeSnapshot); array("discoveredNodes", envelope.discoveredNodes, ::encodeDiscoveredNode)
        envelope.userDefaults?.let { value("userDefaults", it.encode()) }
        writer.write("{")
        fields.entries.forEachIndexed { index, (key, encode) ->
            checkCancellation()
            if (index > 0) writer.write(",")
            writer.write(JsonPrimitive(key).toString()); writer.write(":"); encode()
        }
        writer.write("}")
        writer.flush()
    }
}

private fun decodeManifest(value: JsonElement): BackupManifest = value.row("manifest").run {
    BackupManifest(long("deviceCount"), long("contactCount"), long("channelCount"), long("messageCount"),
        long("messageRepeatCount"), long("reactionCount"), long("roomMessageCount"), long("remoteNodeSessionCount"),
        long("savedTracePathCount"), long("blockedChannelSenderCount"), long("nodeStatusSnapshotCount"), optionalLong("discoveredNodeCount") ?: 0)
}
private fun encodeManifest(manifest: BackupManifest): JsonObject = wireObject {
    for (kind in BackupModelKind.entries) put(kind.countKey, manifest.count(kind))
}
