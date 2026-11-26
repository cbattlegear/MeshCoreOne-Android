// AndroidOnly: WP-213 Offline MECP catalog rendering for Android resources.
package com.meshcoreone.android.core.l10n

import android.content.res.AssetManager
import android.content.res.Resources
import android.icu.number.NumberFormatter
import android.icu.text.PluralRules
import android.util.Log
import com.meshcoreone.android.core.model.MecpMessage
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

object MecpReading {
    private val languages = setOf("de", "en", "es", "fr", "it", "nl", "pl", "pt", "ru", "uk")
    private val bracket = Regex("\\[([^\\[\\]]+)\\]")
    private val packs = mutableMapOf<String, Pack?>()

    fun lines(text: String, resources: Resources): List<String>? {
        val message = MecpMessage.decode(text) ?: return null
        val locale = resources.configuration.locales[0] ?: Locale.ENGLISH
        val stem = when {
            locale.language in languages -> locale.language
            locale.language == "zh" && (locale.script == "Hans" ||
                locale.script.isEmpty() && locale.country in setOf("", "CN", "SG")) -> "zh-cn"
            else -> "en"
        }
        val english = pack(resources.assets, "en") ?: return null
        val current = if (stem == "en") english else pack(resources.assets, stem) ?: english
        val severity = message.severity.wireDigit.toString()
        return buildList {
            // Lead with the drill marker so a truncated preview never starts with only "MAYDAY".
            if ("D01" in message.codes) add(current.codes["D01"] ?: english.codes.getValue("D01"))
            add(current.severities[severity] ?: english.severities.getValue(severity))
            for (code in message.codes) {
                if (code == "D01") continue
                val template = current.codes[code] ?: english.codes[code]
                add(if (template == null) "$code: ${current.unknownCode}" else when {
                    code == "R03" && message.etaMinutes != null ->
                        fill(template) { "${message.etaMinutes} $it" }
                    code == "H07" && message.personCount != null ->
                        fill(template) { "${message.personCount}" }
                    else -> template
                })
            }
            message.personCount?.let { add(current.quantity(it)) }
            message.utcClock?.let { add("${it.take(2)}:${it.takeLast(2)} UTC") }
            message.tail?.let(::add)
        }
    }

    fun display(text: String, resources: Resources, separator: String = " "): String =
        lines(text, resources)?.joinToString(separator) ?: text

    private fun fill(template: String, slot: (String) -> String): String {
        val match = bracket.find(template) ?: return template
        return template.replaceRange(match.range, slot(match.groupValues[1]))
    }

    private fun pack(assets: AssetManager, stem: String): Pack? = synchronized(packs) {
        if (packs.containsKey(stem)) return@synchronized packs[stem]
        val loaded = try {
            fun json(name: String) = assets.open("mecp/$name.json").bufferedReader().use { JSONObject(it.readText()) }
            val file = json(stem)
            val severities = file.getJSONObject("severities")
            val locale = Locale.forLanguageTag(if (stem == "pt") "pt-PT" else stem)
            Pack(
                severities.keys().asSequence().associateWith { severities.getJSONObject(it).getString("local") },
                file.getJSONObject("codes").strings(),
                file.getJSONObject("ui").getString("unknown_code"),
                json("quantities").getJSONObject(stem).strings(),
                locale,
            ).also {
                if (stem == "en" && ("D01" !in it.codes ||
                        MecpMessage.Severity.entries.any { severity -> severity.wireDigit.toString() !in it.severities })
                ) throw JSONException("Incomplete English MECP catalog")
            }
        } catch (failure: IOException) {
            Log.e("MecpReading", "Cannot read bundled MECP catalog: $stem", failure)
            null
        } catch (failure: JSONException) {
            Log.e("MecpReading", "Cannot parse bundled MECP catalog: $stem", failure)
            null
        }
        packs[stem] = loaded
        loaded
    }

    private fun JSONObject.strings(): Map<String, String> = keys().asSequence().associateWith(::getString)

    private class Pack(
        val severities: Map<String, String>,
        val codes: Map<String, String>,
        val unknownCode: String,
        private val quantities: Map<String, String>,
        locale: Locale,
    ) {
        private val formatter = NumberFormatter.withLocale(locale)
        private val plurals = PluralRules.forLocale(locale)

        fun quantity(count: Long): String {
            val category = plurals.select(formatter.format(count))
            return (quantities[category] ?: quantities.getValue("other")).replace("%lld", count.toString())
        }
    }
}
