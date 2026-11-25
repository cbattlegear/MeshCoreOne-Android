// AndroidOnly: WP-309 Issue #729 versioned Android-local storage, intentionally outside backup envelope v1.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

internal object TextRuleJson {
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.optional(key: String) = getValue(key).jsonPrimitive.contentOrNull
    private fun JsonObject.uuid(key: String) = UUID.fromString(string(key))
    private fun key(value: TextRuleKey) = buildJsonObject {
        put("radio", value.radioId.canonicalString); put("conversation", value.conversationID.toString())
    }
    private fun key(value: JsonElement) = value.jsonObject.let {
        TextRuleKey(RadioId(it.uuid("radio")), it.uuid("conversation"))
    }
    private fun rule(value: TextRule) = buildJsonObject {
        put("id", value.id.toString()); put("phrase", value.phrase)
        put("field", value.field.name); put("match", value.match.name)
    }
    private fun rule(value: JsonElement) = value.jsonObject.let {
        val phrase = it.getValue("phrase")
        require(phrase is JsonPrimitive && phrase.isString) { "Text-rule phrase must be a string" }
        TextRule(it.uuid("id"), phrase.content, TextRuleField.valueOf(it.string("field")),
            TextRuleMatch.valueOf(it.string("match")))
    }
    fun encode(value: TextRulesPreference): String {
        value.validate()
        return buildJsonObject {
            put("version", 1)
            put("rules", JsonArray(value.rules.map(::rule)))
            put("selections", buildJsonObject {
                for ((kind, selection) in value.selections) put(kind.name, buildJsonObject {
                    put("mode", selection.mode.name)
                    put("keys", JsonArray(selection.keys.map(::key)))
                })
            })
        }.toString()
    }
    fun preference(payload: String): TextRulesPreference {
        val value = Json.parseToJsonElement(payload).jsonObject
        require(value.getValue("version").jsonPrimitive.int == 1)
        return TextRulesPreference(value.getValue("rules").jsonArray.map(::rule),
            value.getValue("selections").jsonObject.map { (kind, raw) ->
                val selection = raw.jsonObject
                TextRuleKind.valueOf(kind) to TextRuleSelection(
                    TextRuleSelectionMode.valueOf(selection.string("mode")),
                    selection.getValue("keys").jsonArray.map(::key).toSet())
            }.toMap()).also { it.validate() }
    }
    private fun highlights(values: List<TextRuleHighlight>) = JsonArray(values.map {
        buildJsonObject { put("location", it.location); put("length", it.length) }
    })
    private fun highlights(value: JsonElement) = value.jsonArray.map {
        TextRuleHighlight(it.jsonObject.getValue("location").jsonPrimitive.int,
            it.jsonObject.getValue("length").jsonPrimitive.int)
    }
    fun encode(value: RecentlyBlockedText): String = buildJsonObject {
        put("id", value.id.toString()); put("radio", value.identity.radioId.canonicalString)
        put("conversation", value.identity.conversationID?.toString())
        put("prefix", value.identity.senderPrefix?.hexString)
        put("timestamp", value.identity.senderTimestamp.toLong()); put("type", value.identity.textType.toInt())
        put("wire", value.identity.wireText); put("arrived", value.arrivedAt.toString())
        put("body", value.body); put("sender", value.sender)
        put("conversationName", value.conversationName); put("radioName", value.radioName)
        put("rule", rule(value.hit.rule))
        put("bodyHighlights", highlights(value.hit.bodyHighlights))
        put("senderHighlights", highlights(value.hit.senderHighlights))
        put("contact", value.contactID?.toString())
    }.toString()
    fun archived(payload: String): RecentlyBlockedText = Json.parseToJsonElement(payload).jsonObject.let {
        RecentlyBlockedText(it.uuid("id"), BlockedTextIdentity(
            RadioId(it.uuid("radio")), it.optional("conversation")?.let(UUID::fromString),
            it.optional("prefix")?.let { prefix -> Bytes.fromHex(prefix) }, it.string("timestamp").toUInt(),
            it.string("type").toUByte(), it.string("wire")),
            Instant.parse(it.string("arrived")), it.string("body"), it.optional("sender"),
            it.optional("conversationName"), it.optional("radioName"),
            TextRuleHit(rule(it.getValue("rule")), highlights(it.getValue("bodyHighlights")),
                highlights(it.getValue("senderHighlights"))),
            it.optional("contact")?.let(UUID::fromString))
    }
}
