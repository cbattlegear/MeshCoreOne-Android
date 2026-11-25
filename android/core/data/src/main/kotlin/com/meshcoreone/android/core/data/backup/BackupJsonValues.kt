// PortedFrom: MC1Services/Sources/MC1Services/Services/AppBackupEnvelope.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Errors/AppBackupError.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Checked Codable value adapters, without logging input values.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.*

enum class BackupValueProblem { MISSING, TYPE, RANGE, ENUM, UUID, BASE64, DATE, JSON, RELATIONSHIP, IDENTITY }

class BackupValueException(
    val field: String,
    val problem: BackupValueProblem,
    cause: Throwable? = null,
) : Exception("$field:${problem.name}", cause)

internal fun invalidValue(field: String, problem: BackupValueProblem, cause: Throwable? = null): Nothing =
    throw BackupValueException(field, problem, cause)

internal val backupJson = Json { isLenient = false; allowSpecialFloatingPointValues = false }

internal class BackupJsonRow(val value: JsonObject, private val path: String) {
    fun required(key: String): JsonElement = value[key]?.takeUnless { it is JsonNull }
        ?: invalidValue("$path.$key", BackupValueProblem.MISSING)
    fun optional(key: String): JsonElement? = value[key]?.takeUnless { it is JsonNull }
    private fun primitive(key: String, element: JsonElement): JsonPrimitive =
        element as? JsonPrimitive ?: invalidValue("$path.$key", BackupValueProblem.TYPE)

    fun string(key: String): String = string(key, required(key))
    fun optionalString(key: String): String? = optional(key)?.let { string(key, it) }
    private fun string(key: String, element: JsonElement): String = primitive(key, element).let {
        if (!it.isString) invalidValue("$path.$key", BackupValueProblem.TYPE)
        it.content
    }

    fun boolean(key: String): Boolean = boolean(key, required(key))
    fun optionalBoolean(key: String): Boolean? = optional(key)?.let { boolean(key, it) }
    private fun boolean(key: String, element: JsonElement): Boolean = primitive(key, element).let {
        if (it.isString) invalidValue("$path.$key", BackupValueProblem.TYPE)
        it.booleanOrNull ?: invalidValue("$path.$key", BackupValueProblem.TYPE)
    }

    fun long(key: String): Long = long(key, required(key))
    fun optionalLong(key: String): Long? = optional(key)?.let { long(key, it) }
    private fun long(key: String, element: JsonElement): Long = try {
        decimal(key, element).longValueExact()
    } catch (cause: ArithmeticException) {
        invalidValue("$path.$key", BackupValueProblem.RANGE, cause)
    }

    fun unsigned(key: String, maximum: Long): Long = ranged(key, long(key), 0, maximum)
    fun optionalUnsigned(key: String, maximum: Long): Long? = optionalLong(key)?.let { ranged(key, it, 0, maximum) }
    fun ubyte(key: String): UByte = unsigned(key, 255).toUByte()
    fun optionalUByte(key: String): UByte? = optionalUnsigned(key, 255)?.toUByte()
    fun ushort(key: String): UShort = unsigned(key, 65535).toUShort()
    fun optionalUShort(key: String): UShort? = optionalUnsigned(key, 65535)?.toUShort()
    fun uint(key: String): UInt = unsigned(key, 0xFFFF_FFFFL).toUInt()
    fun optionalUInt(key: String): UInt? = optionalUnsigned(key, 0xFFFF_FFFFL)?.toUInt()
    fun byte(key: String): Byte = ranged(key, long(key), -128, 127).toByte()
    fun optionalShort(key: String): Short? = optionalLong(key)?.let { ranged(key, it, -32768, 32767).toShort() }

    private fun ranged(key: String, number: Long, minimum: Long, maximum: Long): Long {
        if (number !in minimum..maximum) invalidValue("$path.$key", BackupValueProblem.RANGE)
        return number
    }

    fun double(key: String): Double = double(key, required(key))
    fun optionalDouble(key: String): Double? = optional(key)?.let { double(key, it) }
    private fun double(key: String, element: JsonElement): Double = primitive(key, element).let {
        if (it.isString) invalidValue("$path.$key", BackupValueProblem.TYPE)
        val number = it.doubleOrNull ?: invalidValue("$path.$key", BackupValueProblem.TYPE)
        if (!number.isFinite()) invalidValue("$path.$key", BackupValueProblem.RANGE)
        number
    }

    private fun decimal(key: String, element: JsonElement): BigDecimal = try {
        primitive(key, element).let {
            if (it.isString) invalidValue("$path.$key", BackupValueProblem.TYPE)
            BigDecimal(it.content)
        }
    } catch (cause: NumberFormatException) {
        invalidValue("$path.$key", BackupValueProblem.TYPE, cause)
    }

    fun date(key: String): Instant = date(key, required(key))
    fun optionalDate(key: String): Instant? = optional(key)?.let { date(key, it) }
    private fun date(key: String, element: JsonElement): Instant = try {
        val number = decimal(key, element)
        val bounded = number.toDouble()
        if (!bounded.isFinite() || bounded < Instant.MIN.epochSecond.toDouble() || bounded > Instant.MAX.epochSecond.toDouble()) {
            invalidValue("$path.$key", BackupValueProblem.DATE)
        }
        if (number.abs() <= BigDecimal("0.0000000005")) Instant.EPOCH
        else instantFromUnixSeconds(number.setScale(9, java.math.RoundingMode.HALF_EVEN))
    } catch (cause: ArithmeticException) {
        invalidValue("$path.$key", BackupValueProblem.DATE, cause)
    } catch (cause: DateTimeException) {
        invalidValue("$path.$key", BackupValueProblem.DATE, cause)
    }

    fun uuid(key: String): UUID = uuid(key, required(key))
    fun optionalUUID(key: String): UUID? = optional(key)?.let { uuid(key, it) }
    private fun uuid(key: String, element: JsonElement): UUID {
        val text = string(key, element)
        if (!UUID_PATTERN.matches(text)) invalidValue("$path.$key", BackupValueProblem.UUID)
        return try { UUID.fromString(text) } catch (cause: IllegalArgumentException) {
            invalidValue("$path.$key", BackupValueProblem.UUID, cause)
        }
    }

    fun radioId(): RadioId = RadioId(uuid("radioID"))
    fun bytes(key: String): Bytes = bytes(key, required(key))
    fun optionalBytes(key: String): Bytes? = optional(key)?.let { bytes(key, it) }
    private fun bytes(key: String, element: JsonElement): Bytes {
        val text = string(key, element)
        if (text.length % 4 != 0 || !BASE64_PATTERN.matches(text)) invalidValue("$path.$key", BackupValueProblem.BASE64)
        return try { Bytes(Base64.getDecoder().decode(text)) } catch (cause: IllegalArgumentException) {
            invalidValue("$path.$key", BackupValueProblem.BASE64, cause)
        }
    }

    fun array(key: String): JsonArray = required(key) as? JsonArray ?: invalidValue("$path.$key", BackupValueProblem.TYPE)
    fun optionalArray(key: String): JsonArray? =
        optional(key)?.let { it as? JsonArray ?: invalidValue("$path.$key", BackupValueProblem.TYPE) }
    fun strings(key: String): SnapshotList<String> = array(key).mapIndexed { index, item ->
        BackupJsonRow(buildJsonObject { put("value", item) }, "$path.$key[$index]").string("value")
    }.snapshot()
    fun optionalStrings(key: String): SnapshotList<String>? = optional(key)?.let { strings(key) }
    fun notification(key: String): NotificationLevel = NotificationLevel.fromRawValue(long(key))
        ?: invalidValue("$path.$key", BackupValueProblem.ENUM)
    fun optionalNotification(key: String): NotificationLevel? = optional(key)?.let { notification(key) }

    companion object {
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val BASE64_PATTERN = Regex("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?")
    }
}

internal fun JsonElement.row(path: String): BackupJsonRow =
    BackupJsonRow(this as? JsonObject ?: invalidValue(path, BackupValueProblem.TYPE), path)

internal fun wireObject(block: JsonObjectBuilder.() -> Unit): JsonObject =
    JsonObject(buildJsonObject(block).toSortedMap())

internal fun JsonObjectBuilder.uuid(key: String, value: UUID?) {
    if (value != null) put(key, value.canonicalString())
}
internal fun JsonObjectBuilder.radioId(value: RadioId) { uuid("radioID", value.value) }
internal fun JsonObjectBuilder.binary(key: String, value: Bytes?) {
    if (value != null) put(key, Base64.getEncoder().encodeToString(value.toByteArray()))
}
internal fun JsonObjectBuilder.date(key: String, value: Instant?) {
    if (value != null) put(key, JsonUnquotedLiteral(value.unixSeconds().stripTrailingZeros().toPlainString()))
}
internal fun JsonObjectBuilder.strings(key: String, value: Iterable<String>?) {
    if (value != null) put(key, JsonArray(value.map(::JsonPrimitive)))
}
internal fun JsonObjectBuilder.number(key: String, value: Double?) {
    if (value != null) {
        if (!value.isFinite()) invalidValue(key, BackupValueProblem.RANGE)
        put(key, value)
    }
}
internal fun JsonObjectBuilder.integer(key: String, value: Long?) { if (value != null) put(key, value) }
internal fun JsonObjectBuilder.text(key: String, value: String?) { if (value != null) put(key, value) }
internal fun JsonObjectBuilder.flag(key: String, value: Boolean?) { if (value != null) put(key, value) }

fun AppBackupError.description(): String = when (this) {
    AppBackupError.InvalidFile -> "The backup file is invalid or could not be read."
    is AppBackupError.FileTooLarge -> "The backup file is too large to import (${actualBytes / 1_048_576} MB; limit is ${maxBytes / 1_048_576} MB)."
    is AppBackupError.DecompressedTooLarge -> "The backup file expands past the safe size limit (${maxBytes / 1_048_576} MB uncompressed)."
    is AppBackupError.UnsupportedVersion -> "This backup was created with a newer format (version $found). This app supports up to version $maxSupported. Please update the app and try again."
    AppBackupError.CorruptedManifest -> "The backup file appears to be corrupted. The declared item counts do not match the actual data."
    is AppBackupError.ExportFailed -> "Failed to create backup."
    is AppBackupError.ImportFailed -> "Failed to import backup."
}
