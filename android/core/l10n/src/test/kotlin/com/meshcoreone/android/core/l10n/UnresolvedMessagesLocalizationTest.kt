// AndroidOnly: WP-005 Native uncertainty copy completeness and format arguments in all supported app locales.
package com.meshcoreone.android.core.l10n

import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class UnresolvedMessagesLocalizationTest {
    @Test fun allTwelveCompiledLocalesHaveExplicitUncertaintyStatesAndSafeFormatArguments() {
        val plain = listOf(
            R.string.unresolved_messages_title, R.string.unresolved_messages_entry,
            R.string.unresolved_messages_notice, R.string.unresolved_messages_no_radio,
            R.string.unresolved_messages_loading, R.string.unresolved_messages_empty,
            R.string.unresolved_messages_error, R.string.unresolved_messages_more,
            R.string.unresolved_messages_refresh, R.string.unresolved_messages_sender,
            R.string.unresolved_messages_candidate, R.string.unresolved_messages_legacy,
            R.string.unresolved_messages_wire_time_unknown, R.string.unresolved_messages_received_time_unknown,
            R.string.unresolved_messages_back,
        )
        val formatted = listOf(R.string.unresolved_messages_radio,
            R.string.unresolved_messages_wire_time, R.string.unresolved_messages_received_time)
        val english = resourcesForLocale("en")
        for (locale in sourceLocales) {
            val resources = resourcesForLocale(locale)
            for (id in plain) {
                assertTrue(resources.getString(id).isNotBlank(), "$locale/$id")
                if (locale != "en") assertNotEquals(english.getString(id), resources.getString(id), "$locale/$id")
            }
            for (id in formatted) {
                val value = resources.getString(id, "559 \u4f60\u597d % <&>")
                assertTrue(value.contains("559 \u4f60\u597d % <&>"), "$locale/$id")
                assertFalse(value.contains("%1\$s"), "$locale/$id")
            }
        }
    }
}
