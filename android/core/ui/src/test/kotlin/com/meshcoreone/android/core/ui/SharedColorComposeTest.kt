// AndroidOnly: WP-304 Actual emitted Material foreground/background and native signal pixels across all effective source theme tiers.
package com.meshcoreone.android.core.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.ColorScheme
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.designsystem.ThemeColor
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.designsystem.WCAGContrast
import com.meshcoreone.android.core.designsystem.accessibleForeground
import com.meshcoreone.android.core.designsystem.toComposeColor
import com.meshcoreone.android.core.designsystem.toThemeColor
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import kotlin.test.*
import kotlin.math.abs
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS-w840dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedColorComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    @Before fun openNativeWindow() { controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible() }
    @After fun closeNativeWindow() { controller.pause().stop().destroy() }

    @Test fun sectionErrorsAndActualPillTextClearThePaintedSurfaceOnEveryEffectiveScheme() {
        val theme = mutableStateOf(ThemeRegistry.default)
        val preference = mutableStateOf(AppColorSchemePreference.LIGHT)
        val high = mutableStateOf(false)
        val states = listOf(StatusPillState.Connecting, StatusPillState.Syncing, StatusPillState.Ready,
            StatusPillState.Disconnected, StatusPillState.Failed(UiText.Verbatim("Fixture failure")))
        controller.get().setContent {
            MeshCoreTheme(theme.value, preference.value, highContrast = high.value, motionScale = 0f) {
                Column {
                    ExpandableSettingsSection(UiText.Verbatim("Fixture section"), MeshSymbol.SETTINGS,
                        ExpandableSectionState(true, false, false, true), {}, {}, Modifier.testTag("section")) {}
                    for ((index, state) in states.withIndex()) SyncingPillView(state, Modifier.testTag("pill-$index"))
                }
            }
        }
        for (current in ThemeRegistry.allThemes) {
            for (scheme in ColorScheme.entries.filter { current.preferredColorScheme == null || it == current.preferredColorScheme }) {
                for (contrast in listOf(false, true)) {
                    compose.runOnIdle {
                        theme.value = current
                        preference.value = if (scheme == ColorScheme.DARK) AppColorSchemePreference.DARK else AppColorSchemePreference.LIGHT
                        high.value = contrast
                    }
                    val surface = compose.onNodeWithTag("section").fetchSemanticsNode().config[SharedPaintedSurface]
                    val text = controller.get().getString(AppLocalizableStrings.commonErrorFailedToLoad)
                    val layouts = mutableListOf<TextLayoutResult>()
                    compose.onNodeWithText(text).assertIsDisplayed()
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                    val foreground = layouts.single().layoutInput.style.color
                    assertTrue(WCAGContrast.contrastRatio(foreground.toThemeColor(), surface.toThemeColor()) >= WCAGContrast.floor(contrast))
                    for ((index, state) in states.withIndex()) {
                        val actual = compose.onNodeWithTag("pill-$index").fetchSemanticsNode().config
                        val background = actual[SharedPaintedSurface]
                        val emitted = actual[SharedEmittedForeground]
                        val icon = actual[SharedEmittedIcon]
                        assertTrue(WCAGContrast.contrastRatio(emitted.toThemeColor(), background.toThemeColor()) >= WCAGContrast.floor(contrast))
                        assertTrue(WCAGContrast.contrastRatio(icon.toThemeColor(), background.toThemeColor()) >= WCAGContrast.floor(contrast))
                        state.textColorRole.sourceSeed?.let { raw ->
                            assertEquals(accessibleForeground(raw, background.toThemeColor(), WCAGContrast.floor(contrast)).toComposeColor(), emitted)
                        }
                        state.iconColorRole.sourceSeed?.let { raw ->
                            assertEquals(accessibleForeground(raw, background.toThemeColor(), WCAGContrast.floor(contrast)).toComposeColor(), icon)
                        }
                    }
                }
            }
        }
    }

    @Test fun nativeSignalPixelsActuallyUseSourceGreenYellowAndRedNotOnlySemanticLabels() {
        val background = Color.White
        controller.get().setContent {
            MeshCoreTheme(highContrast = false, motionScale = 0f) {
                Surface(color = background) {
                    Row(Modifier.padding(16.dp)) {
                        for (tier in RSSITuning.SignalTier.entries) SignalBars(tier,
                            Modifier.testTag("signal-${tier.rawValue}"), paintedSurface = background)
                    }
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = controller.get().window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val seeds = mapOf(
            RSSITuning.SignalTier.WEAK to ThemeColor.hex(0xFF3B30),
            RSSITuning.SignalTier.MEDIUM to ThemeColor.hex(0xFFCC00),
            RSSITuning.SignalTier.STRONG to ThemeColor.hex(0x34C759),
        )
        for ((tier, seed) in seeds) {
            val node = compose.onNodeWithTag("signal-${tier.rawValue}").fetchSemanticsNode()
            val actual = node.config[SharedEmittedForeground]
            val expected = accessibleForeground(seed, ThemeColor.WHITE, 4.5).toComposeColor()
            assertEquals(expected, actual)
            val bounds = node.boundsInWindow
            val pixel = bitmap.getPixel((bounds.left + bounds.width / 8).toInt(), (bounds.bottom - bounds.height / 6).toInt())
            val expectedArgb = expected.toArgb()
            for (shift in listOf(0, 8, 16)) {
                assertTrue(abs(((pixel ushr shift) and 255) - ((expectedArgb ushr shift) and 255)) <= 1)
            }
            for (index in 0..<3) {
                val barPixel = bitmap.getPixel((bounds.left + bounds.width * (index + 0.375f) / 3).toInt(),
                    (bounds.bottom - bounds.height / 6).toInt())
                if (index <= tier.rawValue.toInt()) {
                    assertEquals(expectedArgb, barPixel)
                } else {
                    assertNotEquals(expectedArgb, barPixel)
                    assertNotEquals(android.graphics.Color.WHITE, barPixel)
                }
            }
        }
    }

    @Test fun nativeBatteryPixelsPinAllFiveFillStepsAndWarningColorsWithTextAndProgress() {
        val percentages = listOf(0, 9, 19, 25, 50, 75, 100)
        var normalForeground = Color.Unspecified
        controller.get().setContent {
            MeshCoreTheme(motionScale = 0f) {
                val primary = MaterialTheme.colorScheme.onSurface
                SideEffect { normalForeground = primary }
                Surface(color = Color.White) {
                    Row(Modifier.padding(16.dp)) {
                        for (percentage in percentages) BatteryGlyph(percentage, Modifier.testTag("battery-$percentage"),
                            UiText.Verbatim("Battery: $percentage%"), paintedSurface = Color.White)
                    }
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = controller.get().window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        for ((percentage, filled) in listOf(0 to 0, 9 to 0, 19 to 1, 25 to 1, 50 to 2, 75 to 3, 100 to 4)) {
            val node = compose.onNodeWithTag("battery-$percentage").fetchSemanticsNode()
            assertEquals(listOf("Battery: $percentage%"), node.config[SemanticsProperties.ContentDescription])
            assertEquals(percentage.toFloat(), node.config[SemanticsProperties.ProgressBarRangeInfo].current)
            val foreground = node.config[SharedEmittedForeground]
            val expected = when (percentage) {
                0, 9 -> accessibleForeground(ThemeColor.hex(0xFF3B30), ThemeColor.WHITE, 4.5).toComposeColor()
                19 -> accessibleForeground(ThemeColor.hex(0xFF9500), ThemeColor.WHITE, 4.5).toComposeColor()
                else -> normalForeground
            }
            assertEquals(expected, foreground)
            val bounds = node.boundsInWindow
            for ((x, y) in listOf(bounds.left + bounds.width * 0.95f to bounds.top + bounds.height / 2,
                bounds.left + bounds.width * 0.44f to bounds.top)) {
                val pixel = bitmap.getPixel(x.toInt(), y.toInt())
                for (shift in listOf(0, 8, 16)) {
                    assertTrue(abs(((pixel ushr shift) and 255) - ((expected.toArgb() ushr shift) and 255)) <= 1,
                        "Battery $percentage must paint its cap and outline in the expected role color")
                }
            }
            val inset = 3f
            val span = bounds.width * 0.88f - 6f
            for (index in 0..<4) {
                val pixel = bitmap.getPixel((bounds.left + inset + span * (index + 0.5f) / 4).toInt(),
                    (bounds.top + bounds.height / 2).toInt())
                assertEquals(if (index < filled) foreground.toArgb() else android.graphics.Color.WHITE, pixel)
            }
        }
    }

    @Test fun disconnectedRadioActuallyPaintsASlashInsteadOfOnlyChangingItsColor() {
        controller.get().setContent {
            MeshCoreTheme(motionScale = 0f) {
                Surface(color = Color.White) {
                    Row(Modifier.padding(16.dp)) {
                        RadioConnectionIcon(DeviceConnectionState.READY, Color.Black, "Ready",
                            Modifier.testTag("ready-radio"))
                        RadioConnectionIcon(DeviceConnectionState.DISCONNECTED, Color.Black, "Disconnected",
                            Modifier.testTag("disconnected-radio"))
                    }
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = controller.get().window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        fun pixel(tag: String): Int {
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
            return bitmap.getPixel((bounds.left + bounds.width * 0.75f).toInt(),
                (bounds.top + bounds.height * 0.75f).toInt())
        }
        assertEquals(android.graphics.Color.WHITE, pixel("ready-radio"))
        assertTrue(android.graphics.Color.red(pixel("disconnected-radio")) < 64)
    }
}
