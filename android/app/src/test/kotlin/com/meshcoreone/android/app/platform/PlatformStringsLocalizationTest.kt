// AndroidOnly: WP-407 Verify native platform guidance is localized for every supported language.
package com.meshcoreone.android.app.platform

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.R
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class PlatformStringsLocalizationTest {
    @Test fun platformMessagesResolveInEverySupportedLanguage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val messages = listOf(
            R.string.platform_open_source_licenses,
            R.string.platform_system_language,
            R.string.platform_chat_link_unavailable,
            R.string.platform_channel_sync_incomplete,
            R.string.platform_channel_reconciliation_required,
            R.string.platform_channel_already_saved,
            R.string.platform_channel_delete_stale,
            R.string.platform_channel_delete_unreconciled,
        )
        fun strings(tag: String): Map<Int, String> {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(tag))
            }
            val resources = context.createConfigurationContext(configuration).resources
            return messages.associateWith { resources.getString(it) }
        }
        val english = strings("en")
        for (tag in AppLanguages.supported) {
            val localized = strings(tag)
            for ((id, text) in localized) {
                assertTrue(text.isNotBlank(), "$tag has an empty platform message for $id")
                if (tag != "en") {
                    assertNotEquals(english.getValue(id), text, "$tag falls back to English for $id")
                }
            }
        }
    }
}
