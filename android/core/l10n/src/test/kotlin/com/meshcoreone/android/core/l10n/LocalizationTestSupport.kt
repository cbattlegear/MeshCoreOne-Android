// AndroidOnly: WP-005 Independent rendering of pinned raw source records against compiled Android resources.
package com.meshcoreone.android.core.l10n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.json.JSONObject

internal val sourceLocales = listOf("en", "de", "es", "fr", "it", "ko", "nl", "pl", "pt", "ru", "uk", "zh-Hans")
internal const val sourcePin = "5e980a53e077c45a1270170612fdf5dae97792ff"

internal fun resourcesForTag(tag: String): Resources {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocales(LocaleList(Locale.forLanguageTag(tag)))
    return context.createConfigurationContext(configuration).resources
}

internal fun resourcesForLocale(locale: String): Resources = resourcesForTag(if (locale == "pt") "pt-PT" else locale)

internal enum class ArgumentKind { STRING, INT, LONG, DOUBLE }
internal data class Argument(val position: Int, val kind: ArgumentKind)

internal data class SourceRecord(
    val name: String,
    val kind: String,
    val text: String?,
    val forms: Map<String, String>,
    val quantityArgument: Int?,
    val arguments: List<Argument>,
    val namedArguments: List<String>,
) {
    fun id(resources: Resources): Int {
        val resourcePackage = resources.getResourcePackageName(R.string.app_name)
        val id = resources.getIdentifier(name, kind, resourcePackage)
        assertNotEquals(0, id, "Missing compiled $kind $resourcePackage:$name")
        assertEquals(name, resources.getResourceEntryName(id))
        return id
    }

    fun sampleArguments(quantity: Long = 21): Array<Any> = arguments.map { argument ->
        when (argument.kind) {
            ArgumentKind.STRING -> "\u4e2d\u6587 arg${argument.position} <&> '\"\n\\ %@"
            ArgumentKind.INT -> if (argument.position == quantityArgument) Math.toIntExact(quantity) else 13 + argument.position
            ArgumentKind.LONG -> if (argument.position == quantityArgument) quantity else 3_000_000_000L + argument.position
            ArgumentKind.DOUBLE -> 12.5
        }
    }.toTypedArray()
}

internal fun sourceRecords(locale: String): List<SourceRecord> {
    val stream = requireNotNull(SourceRecord::class.java.classLoader.getResourceAsStream("l10n-source-$locale.json")) {
        "Missing pinned raw-source oracle for $locale"
    }
    val root = JSONObject(stream.reader(Charsets.UTF_8).use { it.readText() })
    assertEquals(sourcePin, root.getString("source_pin"))
    assertEquals(locale, root.getString("locale"))
    val records = root.getJSONArray("records")
    assertEquals(2641, records.length())
    return (0 until records.length()).map { index ->
        val record = records.getJSONObject(index)
        val kind = record.getString("kind")
        val arguments = record.getJSONArray("arguments")
        val named = record.getJSONArray("named_arguments")
        val forms = if (kind == "plurals") {
            val raw = record.getJSONObject("raw")
            raw.keys().asSequence().associateWith(raw::getString)
        } else emptyMap()
        SourceRecord(
            record.getString("name"), kind,
            if (kind == "string") record.getString("raw") else null,
            forms,
            if (record.isNull("quantity_argument")) null else record.getInt("quantity_argument"),
            (0 until arguments.length()).map { position ->
                val argument = arguments.getJSONObject(position)
                Argument(argument.getInt("position"), ArgumentKind.valueOf(argument.getString("type").uppercase(Locale.ROOT)))
            },
            (0 until named.length()).map(named::getString),
        )
    }
}

private val sourcePrintf = Regex("""%(?:(\d+)\$)?(lld|ld|d|@|%)""")
private val sourceNamed = Regex("""\$\{([A-Za-z][A-Za-z0-9]*)}""")

internal fun referenceText(raw: String, args: Array<out Any>, named: List<String> = emptyList()): String {
    if (named.isNotEmpty()) {
        return sourceNamed.replace(raw) { match -> args[named.indexOf(match.groupValues[1])].toString() }
    }
    var implicit = 0
    return sourcePrintf.replace(raw) { match ->
        if (match.groupValues[2] == "%") "%" else {
            val position = match.groupValues[1].takeIf(String::isNotEmpty)?.toInt()?.minus(1) ?: implicit++
            args[position].toString()
        }
    }
}

internal fun integerQuantityCategory(locale: String, quantity: Long): String {
    val one = quantity == 1L || quantity == -1L
    val last10 = abs(quantity % 10).toInt()
    val last100 = abs(quantity % 100).toInt()
    return when (locale) {
        "ko", "zh-Hans" -> "other"
        "ru", "uk" -> when {
            last10 == 1 && last100 != 11 -> "one"
            last10 in 2..4 && last100 !in 12..14 -> "few"
            else -> "many"
        }
        "pl" -> when {
            one -> "one"
            last10 in 2..4 && last100 !in 12..14 -> "few"
            else -> "many"
        }
        "fr", "pt-BR" -> if (quantity == 0L || one) "one" else "other"
        else -> if (one) "one" else "other"
    }
}

internal fun referencePlural(record: SourceRecord, locale: String, quantity: Long, args: Array<out Any>): String {
    val category = integerQuantityCategory(locale, quantity)
    val raw = record.forms[category] ?: record.forms.getValue("other")
    return referenceText(raw, args)
}
