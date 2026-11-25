// PortedFrom: MC1Services/Sources/MC1Services/Models/NodeConfig.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.model.MeshCoreNodeConfig
import com.meshcoreone.android.core.model.MeshCoreNodeConfig.ChannelConfig
import com.meshcoreone.android.core.model.MeshCoreNodeConfig.ContactConfig
import com.meshcoreone.android.core.model.MeshCoreNodeConfig.OtherSettings
import com.meshcoreone.android.core.model.MeshCoreNodeConfig.PositionSettings
import com.meshcoreone.android.core.model.MeshCoreNodeConfig.RadioSettings
import com.meshcoreone.android.core.model.snapshot
import java.math.BigInteger

/** Swift `DecodingError` equivalents for a node config file that is not valid for import. */
sealed class NodeConfigDecodingException(message: String) : IllegalArgumentException(message) {
    class DataCorrupted(val codingPath: String, val reason: String) :
        NodeConfigDecodingException("Data corrupted at '$codingPath': $reason")
    class KeyNotFound(val key: String, val codingPath: String) :
        NodeConfigDecodingException("No value associated with key '$key' at '$codingPath'")
    class ValueNotFound(val codingPath: String, val expectedType: String) :
        NodeConfigDecodingException("Expected $expectedType value but found null at '$codingPath'")
    class TypeMismatch(val codingPath: String, val expectedType: String) :
        NodeConfigDecodingException("Expected to decode $expectedType at '$codingPath'")
}

/**
 * The `Codable` conformance of `MeshCoreNodeConfig`: snake_case keys, optional members omitted when
 * nil, and `ContactConfig`'s custom encoder writing explicit `null` for `custom_name`/`out_path`
 * while omitting a nil `path_hash_mode`. The iOS exporter uses `[.prettyPrinted, .sortedKeys]`, which
 * are the defaults here so Android exports are byte-identical and shareable across platforms.
 */
object NodeConfigJson {
    fun encode(config: MeshCoreNodeConfig, prettyPrinted: Boolean = true, sortedKeys: Boolean = true): String =
        NodeConfigJsonWriter.write(configValue(config), prettyPrinted, sortedKeys)

    fun encodeToBytes(config: MeshCoreNodeConfig, prettyPrinted: Boolean = true, sortedKeys: Boolean = true): ByteArray =
        encode(config, prettyPrinted, sortedKeys).toByteArray(Charsets.UTF_8)

    fun encodeContact(contact: ContactConfig, prettyPrinted: Boolean = false, sortedKeys: Boolean = false): String =
        NodeConfigJsonWriter.write(contactValue(contact), prettyPrinted, sortedKeys)

    fun decode(bytes: ByteArray): MeshCoreNodeConfig = config(parse { NodeConfigJsonReader.parse(bytes) })

    fun decode(text: String): MeshCoreNodeConfig = config(parse { NodeConfigJsonReader.parse(text) })

    fun decodeContact(text: String): ContactConfig = contact(parse { NodeConfigJsonReader.parse(text) })

    private inline fun parse(block: () -> NodeConfigJsonValue): Node = try {
        Node(block(), "")
    } catch (error: NodeConfigJsonSyntaxException) {
        throw NodeConfigDecodingException.DataCorrupted("", error.message ?: "The given data was not valid JSON.")
    }

    // MARK: Encoding (member order = Swift CodingKeys / custom encode order)

    private fun configValue(config: MeshCoreNodeConfig) = obj(
        "name" to config.name?.let(::text),
        "public_key" to config.publicKey?.let(::text),
        "private_key" to config.privateKey?.let(::text),
        "radio_settings" to config.radioSettings?.let(::radioValue),
        "position_settings" to config.positionSettings?.let { obj("latitude" to text(it.latitude), "longitude" to text(it.longitude)) },
        "other_settings" to config.otherSettings?.let(::otherValue),
        "channels" to config.channels?.let { list -> NodeConfigJsonValue.Array(list.map { obj("name" to text(it.name), "secret" to text(it.secret)) }) },
        "contacts" to config.contacts?.let { list -> NodeConfigJsonValue.Array(list.map(::contactValue)) },
    )

    private fun radioValue(radio: RadioSettings) = obj(
        "frequency" to number(radio.frequency.toString()),
        "bandwidth" to number(radio.bandwidth.toString()),
        "spreading_factor" to number(radio.spreadingFactor.toString()),
        "coding_rate" to number(radio.codingRate.toString()),
        "tx_power" to number(radio.txPower.toString()),
    )

    private fun otherValue(other: OtherSettings) = obj(
        "manual_add_contacts" to other.manualAddContacts?.let { number(it.toString()) },
        "advert_location_policy" to other.advertLocationPolicy?.let { number(it.toString()) },
        "telemetry_mode_base" to other.telemetryModeBase?.let { number(it.toString()) },
        "telemetry_mode_location" to other.telemetryModeLocation?.let { number(it.toString()) },
        "telemetry_mode_environment" to other.telemetryModeEnvironment?.let { number(it.toString()) },
        "multi_acks" to other.multiAcks?.let { number(it.toString()) },
        "advertisement_type" to other.advertisementType?.let { number(it.toString()) },
    )

    private fun contactValue(contact: ContactConfig) = obj(
        "type" to number(contact.type.toString()),
        "name" to text(contact.name),
        "custom_name" to (contact.customName?.let(::text) ?: NodeConfigJsonValue.Null),
        "public_key" to text(contact.publicKey),
        "flags" to number(contact.flags.toString()),
        "latitude" to text(contact.latitude),
        "longitude" to text(contact.longitude),
        "last_advert" to number(contact.lastAdvert.toString()),
        "last_modified" to number(contact.lastModified.toString()),
        "out_path" to (contact.outPath?.let(::text) ?: NodeConfigJsonValue.Null),
        "path_hash_mode" to contact.pathHashMode?.let { number(it.toString()) },
    )

    private fun obj(vararg members: Pair<String, NodeConfigJsonValue?>) =
        NodeConfigJsonValue.Object(members.mapNotNull { (key, value) -> value?.let { key to it } })

    private fun text(value: String) = NodeConfigJsonValue.Text(value)
    private fun number(literal: String) = NodeConfigJsonValue.Number(literal)

    // MARK: Decoding

    private fun config(root: Node): MeshCoreNodeConfig {
        root.requireObject()
        return MeshCoreNodeConfig(
            name = root.optionalString("name"),
            publicKey = root.optionalString("public_key"),
            privateKey = root.optionalString("private_key"),
            radioSettings = root.optional("radio_settings")?.let(::radio),
            positionSettings = root.optional("position_settings")?.let(::position),
            otherSettings = root.optional("other_settings")?.let(::other),
            channels = root.optional("channels")?.elements()?.map(::channel)?.snapshot(),
            contacts = root.optional("contacts")?.elements()?.map(::contact)?.snapshot(),
        )
    }

    private fun radio(node: Node) = node.requireObject().let {
        RadioSettings(
            frequency = node.uint32("frequency"),
            bandwidth = node.uint32("bandwidth"),
            spreadingFactor = node.uint8("spreading_factor"),
            codingRate = node.uint8("coding_rate"),
            txPower = node.int8("tx_power"),
        )
    }

    private fun position(node: Node) = node.requireObject().let {
        PositionSettings(latitude = node.string("latitude"), longitude = node.string("longitude"))
    }

    private fun other(node: Node) = node.requireObject().let {
        OtherSettings(
            manualAddContacts = node.optionalUInt8("manual_add_contacts"),
            advertLocationPolicy = node.optionalUInt8("advert_location_policy"),
            telemetryModeBase = node.optionalUInt8("telemetry_mode_base"),
            telemetryModeLocation = node.optionalUInt8("telemetry_mode_location"),
            telemetryModeEnvironment = node.optionalUInt8("telemetry_mode_environment"),
            multiAcks = node.optionalUInt8("multi_acks"),
            advertisementType = node.optionalUInt8("advertisement_type"),
        )
    }

    private fun channel(node: Node) = node.requireObject().let {
        ChannelConfig(name = node.string("name"), secret = node.string("secret"))
    }

    private fun contact(node: Node) = node.requireObject().let {
        ContactConfig(
            type = node.uint8("type"),
            name = node.string("name"),
            customName = node.optionalString("custom_name"),
            publicKey = node.string("public_key"),
            flags = node.uint8("flags"),
            latitude = node.string("latitude"),
            longitude = node.string("longitude"),
            lastAdvert = node.uint32("last_advert"),
            lastModified = node.uint32("last_modified"),
            outPath = node.optionalString("out_path"),
            pathHashMode = node.optionalUInt8("path_hash_mode"),
        )
    }

    /** A value plus its coding path, mirroring the keyed/unkeyed container checks of `JSONDecoder`. */
    private class Node(val value: NodeConfigJsonValue, val path: String) {
        fun requireObject(): NodeConfigJsonValue.Object =
            value as? NodeConfigJsonValue.Object ?: throw mismatch(this, "a keyed container (object)")

        fun elements(): List<Node> {
            val array = value as? NodeConfigJsonValue.Array ?: throw mismatch(this, "an unkeyed container (array)")
            return array.items.mapIndexed { position, item -> Node(item, "$path[$position]") }
        }

        fun optional(key: String): Node? {
            val member = requireObject()[key] ?: return null
            return if (member == NodeConfigJsonValue.Null) null else Node(member, child(key))
        }

        fun required(key: String, type: String): Node {
            val member = requireObject()[key] ?: throw NodeConfigDecodingException.KeyNotFound(key, path)
            if (member == NodeConfigJsonValue.Null) throw NodeConfigDecodingException.ValueNotFound(child(key), type)
            return Node(member, child(key))
        }

        fun string(key: String): String = required(key, "String").asString()
        fun optionalString(key: String): String? = optional(key)?.asString()
        fun uint8(key: String): UByte = required(key, "UInt8").integer("UInt8", 0, 255).toInt().toUByte()
        fun optionalUInt8(key: String): UByte? = optional(key)?.integer("UInt8", 0, 255)?.toInt()?.toUByte()
        fun int8(key: String): Byte = required(key, "Int8").integer("Int8", -128, 127).toInt().toByte()
        fun uint32(key: String): UInt = required(key, "UInt32").integer("UInt32", 0, 0xFFFF_FFFFL).toUInt()

        private fun asString(): String = when (value) {
            is NodeConfigJsonValue.Text -> value.value
            is NodeConfigJsonValue.RawText -> try {
                value.decode()
            } catch (error: NodeConfigJsonSyntaxException) {
                throw NodeConfigDecodingException.DataCorrupted(path, error.message ?: "The given data was not valid JSON.")
            }
            else -> throw mismatch(this, "String")
        }

        /**
         * Foundation semantics: an integer literal must fit the type exactly; a literal with a
         * fraction or exponent is parsed as a Double and accepted only when it is an exact in-range
         * integer (so `7.0` decodes as 7 and `7.5` is rejected).
         */
        private fun integer(type: String, min: Long, max: Long): Long {
            val literal = (value as? NodeConfigJsonValue.Number)?.literal ?: throw mismatch(this, type)
            if (!NodeConfigJsonReader.isValidNumber(literal)) {
                throw NodeConfigDecodingException.DataCorrupted(path, "Invalid JSON number <$literal>.")
            }
            val parsed: Long? = if (literal.none { it == '.' || it == 'e' || it == 'E' }) {
                BigInteger(literal).takeIf { it >= BigInteger.valueOf(min) && it <= BigInteger.valueOf(max) }?.toLong()
            } else {
                literal.toDouble().takeIf { it.isFinite() && it == Math.rint(it) && it >= min && it <= max }?.toLong()
            }
            return parsed ?: throw NodeConfigDecodingException.DataCorrupted(path, "Parsed JSON number <$literal> does not fit in $type.")
        }

        private fun child(key: String) = if (path.isEmpty()) key else "$path.$key"
    }

    private fun mismatch(node: Node, type: String) = NodeConfigDecodingException.TypeMismatch(node.path, type)
}
