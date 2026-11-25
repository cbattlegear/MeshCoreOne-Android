// AndroidOnly: WP-005 All pinned source values/arguments/quantities are checked against AAPT-compiled resources.
package com.meshcoreone.android.core.l10n

import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.core.l10n.generated.ShortcutAppShortcutsStrings
import com.meshcoreone.android.core.l10n.generated.WidgetLocalizableStrings
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class LocalizationResourceTest {
    @Test
    fun advancedMapFiltersUseLocalizedCopyAndEnglishFallback() {
        val french = resourcesForLocale("fr")
        assertEquals("Filtres avancés", french.getString(R.string.map_advanced_title))
        assertEquals("Supprimer le filtre : Sauts", french.getString(R.string.map_advanced_clear, "Sauts"))
        assertEquals("高级筛选", resourcesForLocale("zh-Hans").getString(R.string.map_advanced_title))
        assertEquals("Advanced Filters", resourcesForTag("ja").getString(R.string.map_advanced_title))
    }

    @Test
    fun everyStaticSourceValueRoundTripsAllTwelveCompiledLocales() {
        sourceLocales.forEach { locale ->
            val resources = resourcesForLocale(locale)
            val records = sourceRecords(locale).filter { it.kind == "string" && it.arguments.isEmpty() }
            assertTrue(records.size > 2000, "$locale has incomplete plain-string coverage")
            records.forEach { record ->
                assertEquals(referenceText(requireNotNull(record.text), emptyArray()), resources.getString(record.id(resources)), "$locale/${record.name}")
            }
        }
    }

    @Test
    fun everyFormattedSourceValuePreservesArgumentsPositionsAndLiteralText() {
        sourceLocales.forEach { locale ->
            val resources = resourcesForLocale(locale)
            val records = sourceRecords(locale).filter { it.kind == "string" && it.arguments.isNotEmpty() }
            assertTrue(records.size > 250, "$locale has incomplete formatted-string coverage")
            records.forEach { record ->
                val args = record.sampleArguments()
                assertEquals(
                    referenceText(requireNotNull(record.text), args, record.namedArguments),
                    L10nFormatting.string(resources, record.id(resources), *args),
                    "$locale/${record.name}",
                )
            }
        }
    }

    @Test
    fun everyPluralUsesLocaleQuantityAndTheOriginalDisplayedArgument() {
        val quantities = longArrayOf(0, 1, 2, 3, 4, 5, 11, 12, 14, 21, 22, 24, 25, 101, 102, 111, 112, 1_000_000, Int.MAX_VALUE.toLong(), Int.MIN_VALUE.toLong())
        sourceLocales.forEach { locale ->
            val resources = resourcesForLocale(locale)
            val records = sourceRecords(locale).filter { it.kind == "plurals" }
            assertEquals(12, records.size)
            records.forEach { record ->
                val values = if (record.arguments.singleOrNull()?.kind == ArgumentKind.LONG) {
                    quantities + longArrayOf(3_000_000_001, 3_000_000_002, Long.MAX_VALUE, Long.MIN_VALUE)
                } else quantities
                values.forEach { quantity ->
                    val args = record.sampleArguments(quantity)
                    assertEquals(
                        referencePlural(record, locale, quantity, args),
                        L10nFormatting.plural(resources, record.id(resources), quantity, *args),
                        "$locale/${record.name}/$quantity",
                    )
                }
            }
        }
    }

    @Test
    fun widgetTypedLongArgumentsAreNotNarrowedOrReformattedAsStrings() {
        sourceLocales.forEach { locale ->
            val resources = resourcesForLocale(locale)
            val records = sourceRecords(locale).associateBy(SourceRecord::name)
            val packets = records.getValue("l10n_widget_localizable_lld_packets_per_minute")
            val battery = records.getValue("l10n_widget_localizable_battery_lld_percent")
            assertEquals(referenceText(requireNotNull(packets.text), arrayOf(Long.MAX_VALUE)), WidgetLocalizableStrings.lldPacketsPerMinute(resources, Long.MAX_VALUE), locale)
            assertEquals(referenceText(requireNotNull(battery.text), arrayOf(84L)), WidgetLocalizableStrings.batteryLldPercent(resources, 84), locale)
        }
    }

    @Test
    fun shortcutAndAppIntentNamedParametersReorderAndItalianFallsBackToEnglish() {
        sourceLocales.forEach { locale ->
            val resources = resourcesForLocale(locale)
            val records = sourceRecords(locale).associateBy(SourceRecord::name)
            val shortcut = records.getValue("l10n_shortcut_appshortcuts_send_a_reach_advert_in_applicationname")
            assertEquals(referenceText(requireNotNull(shortcut.text), arrayOf("REACH", "APP"), shortcut.namedArguments), ShortcutAppShortcutsStrings.sendAReachAdvertInApplicationName(resources, "REACH", "APP"), locale)
            val summary = records.getValue("l10n_app_tools_send_message_to_target")
            assertEquals(referenceText(requireNotNull(summary.text), arrayOf("MESSAGE", "TARGET"), summary.namedArguments), AppToolsStrings.sendMessageToTarget(resources, "MESSAGE", "TARGET"), locale)
            assertEquals(9, records.values.count { it.namedArguments.isNotEmpty() })
        }
        assertEquals("Send a REACH advert in APP", ShortcutAppShortcutsStrings.sendAReachAdvertInApplicationName(resourcesForLocale("it"), "REACH", "APP"))
    }

    @Test
    fun longGermanAndCjkCopyAreNotTruncatedOrNormalized() {
        val german = sourceRecords("de").filter { it.text != null }.maxBy { requireNotNull(it.text).length }
        assertTrue(requireNotNull(german.text).length > 300)
        val germanArguments = german.sampleArguments()
        val expected = referenceText(requireNotNull(german.text), germanArguments, german.namedArguments)
        val germanResources = resourcesForLocale("de")
        val actual = if (germanArguments.isEmpty()) germanResources.getString(german.id(germanResources)) else {
            L10nFormatting.string(germanResources, german.id(germanResources), *germanArguments)
        }
        assertEquals(expected, actual)
        assertEquals(expected.length, actual.length)
        val chinese = sourceRecords("zh-Hans").filter { it.text?.any { char -> char.code in 0x4e00..0x9fff } == true && it.arguments.isEmpty() }
        assertTrue(chinese.size > 1000)
        val chineseResources = resourcesForLocale("zh-Hans")
        chinese.forEach { record ->
            assertEquals(referenceText(requireNotNull(record.text), emptyArray()), chineseResources.getString(record.id(chineseResources)))
        }
    }

    @Test
    fun localeConfigurationAndExistingScaffoldIdsRemainUsable() {
        val resources = resourcesForLocale("en")
        val tags = mutableListOf<String>()
        resources.getXml(R.xml.l10n_locales).use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    tags += requireNotNull(parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name"))
                }
                parser.next()
            }
        }
        assertEquals(sourceLocales.map { if (it == "pt") "pt-PT" else it }, tags)
        assertEquals("MeshCore One", resources.getString(R.string.app_name))
        assertEquals("Not yet ported", resources.getString(R.string.scaffold_not_yet_ported))
        assertEquals("Chats", resources.getString(R.string.tab_chats))
        sourceLocales.forEach { locale ->
            val localized = resourcesForLocale(locale)
            assertEquals(localized.getString(R.string.l10n_app_localizable_tabs_chats), localized.getString(R.string.tab_chats))
            assertEquals("Not yet ported", localized.getString(R.string.scaffold_not_yet_ported))
        }
    }
}
