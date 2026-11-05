// AndroidOnly: WP-309 Original enqueue errors remain visible alongside explicit duplicate-send uncertainty.
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.feature.chats.composer.*
import com.meshcoreone.android.feature.chats.composer.ui.*
import com.meshcoreone.android.platform.shortcuts.*
import org.junit.*
import org.junit.Assert.*
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnqueueUncertaintyPresentationTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    @Test fun savedComposerErrorShowsOriginalReasonAndUncertaintyAtDoubleFont() {
        val resources = host.get().resources
        val original = resources.getString(AppChatsStrings.chatsErrorSendQueuePersistFailed)
        val uncertain = resources.getString(R.string.enqueue_outcome_unconfirmed)
        render(messageSaved = true, scale = 2f)
        compose.onNodeWithText("$original\n$uncertain").assertIsDisplayed()
        compose.onNodeWithText("$original\n$uncertain").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
    }

    @Test fun creationFailureDoesNotAcquireAnEnqueueUncertaintyClaim() {
        render(messageSaved = false)
        compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsErrorSendQueuePersistFailed))
            .assertIsDisplayed()
        compose.onAllNodesWithText(host.get().getString(R.string.enqueue_outcome_unconfirmed), substring = true)
            .assertCountEquals(0)
    }

    @Test fun shortcutRetainsEachOriginalErrorAndAddsUncertaintyOnlyForTheEnqueueWrapper() {
        val text = AndroidShortcutText(host.get())
        val warning = host.get().getString(R.string.enqueue_outcome_unconfirmed)
        for (error in listOf(
            ShortcutError.SEND_FAILED, ShortcutError.NOT_CONNECTED,
            ShortcutError.INVALID_RECIPIENT, ShortcutError.MESSAGE_TOO_LONG,
        )) {
            val original = text.error(error)
            assertEquals("$original\n$warning", text.error(ShortcutError.EnqueueUnconfirmed(error)))
            assertFalse(original.contains(warning))
        }
    }

    @Test fun enqueueUncertaintyUsesEverySupportedLocalizedResourceWithoutAnEnglishFallback() {
        fun localized(tag: String) = host.get().createConfigurationContext(
            Configuration(host.get().resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) },
        ).getString(R.string.enqueue_outcome_unconfirmed)
        val english = localized("en")
        for (locale in listOf("de", "es", "fr", "it", "ko", "nl", "pl", "pt", "ru", "uk", "zh-Hans")) {
            val translated = localized(locale)
            assertTrue("$locale must have nonempty uncertainty copy", translated.isNotBlank())
            assertNotEquals("$locale must not silently fall back to English", english, translated)
        }
    }

    private fun render(messageSaved: Boolean, scale: Float = 1f) {
        compose.runOnUiThread {
            host.get().setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    MeshCoreTheme(motionScale = 0f) {
                        ComposerBar(
                            ComposerUiState(sendPhase = ComposerSendPhase.Failed(ComposerSendFailure.OTHER, messageSaved)),
                            TextFieldValue(""), "Message", ShareAvailability(false, false),
                            ComposerBarActions({}, {}, {}, {}, {}, null, {}),
                            FocusRequester(),
                        )
                    }
                }
            }
        }
    }
}
