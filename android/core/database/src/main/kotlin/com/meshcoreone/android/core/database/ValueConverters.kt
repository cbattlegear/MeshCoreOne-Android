// PortedFrom: MC1Services/Sources/MC1Services/Models/ConnectionMethod.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/NodeStatusSnapshot.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/SavedTracePath.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Lossless, fail-closed native column converters; not an app-backup codec.
package com.meshcoreone.android.core.database

import androidx.room.TypeConverter
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.NeighborSnapshotEntry
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.TelemetrySnapshotEntry
import com.meshcoreone.android.core.model.canonicalString
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.Base64
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class ValueConverters {
    @TypeConverter fun uuidToText(value: UUID): String = value.canonicalString()
    @TypeConverter fun textToUUID(value: String): UUID = decode("UUID") {
        val parsed = UUID.fromString(value)
        if (!parsed.canonicalString().equals(value, ignoreCase = true)) throw DatabaseValueException("UUID", "Noncanonical UUID shape")
        parsed
    }
    @TypeConverter fun bytesToBlob(value: Bytes): ByteArray = value.toByteArray()
    @TypeConverter fun blobToBytes(value: ByteArray): Bytes = Bytes(value)

    @TypeConverter fun stringsToText(values: SnapshotList<String>): String = JSONArray(values.toList()).toString()
    @TypeConverter fun textToStrings(value: String): SnapshotList<String> = decode("string list") {
        val array = JSONArray(value)
        (0 until array.length()).map { array.get(it) as? String ?: throw DatabaseValueException("string list", "Non-string element") }.snapshot()
    }

    @TypeConverter fun methodsToText(values: SnapshotList<ConnectionMethod>): String =
        JSONArray(values.map(::connectionMethodToJSON)).toString()
    @TypeConverter fun textToMethods(value: String): SnapshotList<ConnectionMethod> = decode("connection methods") {
        val array = JSONArray(value)
        (0 until array.length()).map { connectionMethodFromJSON(array.getJSONObject(it)) }.snapshot()
    }

    @TypeConverter fun neighborsToText(values: SnapshotList<NeighborSnapshotEntry>): String = JSONArray(values.map {
        JSONObject().put("publicKeyPrefix", Base64.getEncoder().encodeToString(it.publicKeyPrefix.toByteArray()))
            .put("secondsAgo", it.secondsAgo).put("snr", it.snr)
    }).toString()
    @TypeConverter fun textToNeighbors(value: String): SnapshotList<NeighborSnapshotEntry> = decode("neighbor snapshots") {
        val array = JSONArray(value)
        (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            NeighborSnapshotEntry(Bytes(Base64.getDecoder().decode(row.string("publicKeyPrefix"))), row.double("snr"), row.long("secondsAgo"))
        }.snapshot()
    }

    @TypeConverter fun telemetryToText(values: SnapshotList<TelemetrySnapshotEntry>): String = JSONArray(values.map {
        JSONObject().put("channel", it.channel).put("type", it.type).put("value", it.value)
    }).toString()
    @TypeConverter fun textToTelemetry(value: String): SnapshotList<TelemetrySnapshotEntry> = decode("telemetry entries") {
        val array = JSONArray(value)
        (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            TelemetrySnapshotEntry(row.long("channel"), row.string("type"), row.double("value"))
        }.snapshot()
    }

    fun doublesToBlob(values: SnapshotList<Double>): Bytes = decode("trace hops") {
        if (values.any { !it.isFinite() }) throw DatabaseValueException("trace hops", "Non-finite element")
        Bytes.utf8(JSONArray(values.toList()).toString())
    }
    fun blobToDoubles(value: Bytes): SnapshotList<Double> = decode("trace hops") {
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value.toByteArray())).toString()
        } catch (cause: CharacterCodingException) {
            throw DatabaseValueException("trace hops", "Malformed UTF-8", cause)
        }
        val array = JSONArray(text)
        (0 until array.length()).map { index ->
            val number = array.get(index) as? Number ?: throw DatabaseValueException("trace hops", "Non-numeric element")
            number.toDouble().also { if (!it.isFinite()) throw DatabaseValueException("trace hops", "Non-finite element") }
        }.snapshot()
    }

    fun connectionMethodToJSON(method: ConnectionMethod): JSONObject = when (method) {
        is ConnectionMethod.Bluetooth -> JSONObject().put("bluetooth", JSONObject().apply {
            method.displayName?.let { put("displayName", it) }
            put("peripheralUUID", method.peripheralUUID.canonicalString())
        })
        is ConnectionMethod.WiFi -> JSONObject().put("wifi", JSONObject().apply {
            method.displayName?.let { put("displayName", it) }
            put("host", method.host)
            put("port", method.port.toInt())
        })
    }

    fun connectionMethodFromJSON(value: JSONObject): ConnectionMethod = decode("connection method") {
        val keys = value.keys().asSequence().toSet()
        when (keys) {
            setOf("bluetooth") -> value.getJSONObject("bluetooth").let {
                ConnectionMethod.Bluetooth(textToUUID(it.string("peripheralUUID")), it.optionalString("displayName"))
            }
            setOf("wifi") -> value.getJSONObject("wifi").let {
                ConnectionMethod.WiFi(it.string("host"), it.long("port").ushort("connection port"), it.optionalString("displayName"))
            }
            else -> throw DatabaseValueException("connection method", "Unknown or ambiguous transport case")
        }
    }
}

private fun JSONObject.string(key: String): String =
    get(key) as? String ?: throw DatabaseValueException(key, "Expected a string")
private fun JSONObject.optionalString(key: String): String? = if (!has(key) || isNull(key)) null else string(key)
private fun JSONObject.long(key: String): Long = when (val raw = get(key)) {
    is Int -> raw.toLong()
    is Long -> raw
    else -> throw DatabaseValueException(key, "Expected an exact Int64 JSON number")
}
private fun JSONObject.double(key: String): Double {
    val raw = get(key) as? Number ?: throw DatabaseValueException(key, "Expected a JSON number")
    return raw.toDouble().also { if (!it.isFinite()) throw DatabaseValueException(key, "Non-finite JSON number") }
}

private inline fun <T> decode(field: String, block: () -> T): T = try {
    block()
} catch (cause: JSONException) {
    throw DatabaseValueException(field, "Malformed JSON", cause)
} catch (cause: IllegalArgumentException) {
    throw DatabaseValueException(field, "Malformed encoded value", cause)
}
