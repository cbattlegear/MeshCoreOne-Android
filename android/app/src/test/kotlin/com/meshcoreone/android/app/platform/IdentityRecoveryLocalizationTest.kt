// AndroidOnly: WP-303 Verify identity recovery guidance is localized in every supported language.
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
class IdentityRecoveryLocalizationTest {
    @Test fun recoveryGuidanceResolvesInEverySupportedLanguage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val messages = listOf(
            R.string.identity_import_recovery_entry,
            R.string.identity_import_recovery_title,
            R.string.identity_import_recovery_unknown,
            R.string.identity_import_recovery_select,
            R.string.identity_import_recovery_selected,
            R.string.identity_import_recovery_invalid,
            R.string.identity_import_recovery_failed,
            R.string.identity_import_recovery_wrong_peer,
            R.string.identity_import_recovery_wrong_readback,
            R.string.identity_import_recovery_wrong_key,
            R.string.identity_import_recovery_identity_unknown,
            R.string.identity_import_recovery_unavailable,
            R.string.identity_import_recovery_confirm_title,
            R.string.identity_import_recovery_confirm,
            R.string.identity_import_recovery_apply,
            R.string.identity_import_recovery_complete,
        )
        fun strings(tag: String): Map<Int, String> {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(tag))
            }
            val resources = context.createConfigurationContext(configuration).resources
            return messages.associateWith { resources.getString(it, "Radio") }
        }
        val english = strings("en")
        for (tag in AppLanguages.supported) {
            val localized = strings(tag)
            for ((id, text) in localized) {
                assertTrue(text.isNotBlank(), "$tag has an empty recovery message for $id")
                if (tag != "en") assertNotEquals(english.getValue(id), text, "$tag falls back to English for $id")
            }
        }
    }
}
