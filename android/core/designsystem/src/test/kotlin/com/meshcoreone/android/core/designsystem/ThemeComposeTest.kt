// PortedFrom: MC1Tests/AppThemeEnvironmentTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/AppStateThemeWiringTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.designsystem

import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.datastore.AppearanceStorageKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w1080dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var controller: ActivityController<ComponentActivity>
    private val sample = ThemePreviewContent(
        "S\u00f8ren", "S", "\u706f\u706b #general \u05e9\u05dc\u05d5\u05dd",
        "\u3053\u3093\u306b\u3061\u306f \uD83D\uDC69\u200d\uD83D\uDCBB", SignalColorRole.GOOD, RadioColorRole.DISCONNECTED,
    )

    @Before fun attachRealNativeHost() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        controller.get().actionBar?.hide()
    }
    @After fun disposeHost() {
        controller.pause().stop().destroy()
    }
    private fun content(block: @Composable () -> Unit) {
        controller.get().setContent(content = block)
    }

    @OriginalCase("AppThemeEnvironmentTests::the default appTheme environment value is Theme.default()")
    @Test fun actualDefaultCompositionLocalNeedsNoAppGraph() {
        var observed: ThemeId? = null
        content {
            val theme = LocalAppTheme.current
            SideEffect { observed = theme.id }
            Text(themeName(theme))
        }
        compose.runOnIdle { assertEquals(ThemeId.DEFAULT, observed) }
        compose.onNodeWithText("Default").assertIsDisplayed()
    }
    @Test fun everyThemeAndForcedSchemeRendersSourceDrivenMaterialAndAccessibleSelection() {
        val selected = mutableStateOf(ThemeRegistry.default)
        val preference = mutableStateOf(AppColorSchemePreference.SYSTEM)
        val high = mutableStateOf(false)
        var observed: MeshThemeTokens? = null
        content {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.width(1080.dp).height(900.dp)) {
                    MeshCoreTheme(selected.value, preference.value, systemDark = false, highContrast = high.value, motionScale = 0f) {
                        val tokens = LocalMeshTheme.current
                        SideEffect { observed = tokens }
                        ThemeFoundationContent(sample, { selected.value = assertNotNull(ThemeRegistry.theme(it)) })
                    }
                }
            }
        }
        for (theme in ThemeRegistry.allThemes) {
            compose.runOnIdle { selected.value = theme }
            val schemes = ColorScheme.entries.filter { theme.preferredColorScheme == null || theme.preferredColorScheme == it }
            for (scheme in schemes) for (contrast in listOf(false, true)) {
                compose.runOnIdle {
                    high.value = contrast
                    preference.value = if (scheme == ColorScheme.DARK) AppColorSchemePreference.DARK else AppColorSchemePreference.LIGHT
                }
                val tokens = compose.runOnIdle { assertNotNull(observed) }
                assertEquals(theme.id, tokens.frame.theme.id)
                assertEquals(scheme, tokens.frame.colorScheme)
                assertEquals(contrast, tokens.frame.highContrast)
                compose.onNodeWithTag("theme:${theme.id.rawValue}").performScrollTo()
                    .assertIsSelected().assertIsNotEnabled().assertHeightIsAtLeast(49.dp).assertWidthIsAtLeast(49.dp)
                capture("theme-${theme.id.rawValue}-${scheme.name.lowercase()}-${if (contrast) "hc" else "standard"}")
            }
        }
        compose.runOnIdle { selected.value = ThemeRegistry.default }
        compose.onNodeWithTag("theme:marine").performScrollTo().performClick()
        compose.onNodeWithTag("theme:marine").assertIsSelected()
        assertEquals(ThemeId.MARINE, compose.runOnIdle { assertNotNull(observed).frame.theme.id })
        compose.runOnIdle { selected.value = assertNotNull(ThemeRegistry.theme("ember")); preference.value = AppColorSchemePreference.LIGHT }
        assertEquals(ColorScheme.DARK, compose.runOnIdle { assertNotNull(observed).frame.colorScheme })
    }
    @Test fun twoHundredPercentFontCjkRtlAndResizeDoNotClipOrLoseRememberedState() {
        val width = mutableStateOf(360.dp)
        val selected = mutableStateOf(ThemeRegistry.default)
        var remembered = -1
        val long = sample.copy(incomingText = "\u4e2d\u6587\u6d88\u606f ".repeat(20) + "\u05e9\u05dc\u05d5\u05dd ".repeat(20))
        val preview = mutableStateOf(long)
        val showError = mutableStateOf(false)
        content {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.width(width.value).height(900.dp)) {
                    MeshCoreTheme(selected.value, highContrast = true, motionScale = 0f) {
                        val detail = remember { mutableIntStateOf(37) }
                        SideEffect { remembered = detail.intValue }
                        androidx.compose.foundation.layout.Column {
                            if (showError.value) ThemeFailureContent(
                                ThemeServiceState.Failed(ThemeServiceFailure(ThemeProblem.UnknownThemeID("preview")), null),
                            ) {}
                            ThemeFoundationContent(preview.value, { selected.value = assertNotNull(ThemeRegistry.theme(it)) })
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("incoming-sample").performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("incoming-sample").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue(action(layouts))
        }
        assertTrue(layouts.single().lineCount > 1)
        assertFalse(layouts.single().hasVisualOverflow)
        for (theme in ThemeRegistry.allThemes) compose.onNodeWithTag("theme:${theme.id.rawValue}")
            .performScrollTo().assertHeightIsAtLeast(49.dp).assertWidthIsAtLeast(49.dp)
        compose.runOnIdle { width.value = 840.dp; selected.value = assertNotNull(ThemeRegistry.theme("marine")) }
        compose.runOnIdle { assertEquals(37, remembered) }
        compose.runOnIdle { selected.value = ThemeRegistry.default }
        compose.runOnIdle { assertEquals(37, remembered) }
        compose.runOnIdle { preview.value = sample; showError.value = true }
        compose.onNodeWithTag("theme-error").assertIsDisplayed()
        compose.onNodeWithText(controller.get().getString(com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonTryAgain))
            .assertIsDisplayed().assertHeightIsAtLeast(49.dp)
        capture("font200-rtl-cjk-expanded")
    }
    @Test fun realPreferenceConsumerChangesTheMaterialHostWithoutRecreatingContent() = runBlocking<Unit> {
        val harness = ThemeServiceTest.Harness(temporary.newFolder())
        val service = harness.open()
        var count = 0
        try {
            content {
                ThemeServiceHost(service) { state ->
                    val retained by remember { mutableIntStateOf(91) }
                    SideEffect { count = retained }
                    when (state) {
                        is ThemeServiceState.Ready -> Text(themeName(LocalAppTheme.current), Modifier.testTag("persisted-theme"))
                        is ThemeServiceState.Failed -> ThemeFailureContent(state) {}
                        else -> Text("", Modifier.testTag("non-ready-theme"))
                    }
                }
            }
            compose.onNodeWithTag("persisted-theme").assertIsDisplayed()
            service.setCurrent(ThemeId.FERN)
            compose.onNodeWithText("Fern").assertIsDisplayed()
            assertEquals(91, count)
            harness.store.preferences.set(AppearanceStorageKey.selectedThemeID, "ember")
            service.refreshFromPreferences()
            compose.onNodeWithText("Ember").assertIsDisplayed()
            assertEquals(91, count)
            harness.store.preferences.set(AppearanceStorageKey.selectedThemeID, "future-build")
            service.refreshFromPreferences()
            compose.onNodeWithText("Default").assertIsDisplayed()
            assertEquals(91, count)
        } finally { service.close(); harness.close() }
    }
    @Test fun statusAndFailureAreExpressedByActualLocalizedTextAndTypedSemantics() {
        val error = ThemeServiceState.Failed(ThemeServiceFailure(ThemeProblem.UnknownThemeID("future")), null)
        var retries = 0
        content {
            MeshCoreTheme(highContrast = true, motionScale = 0f) {
                androidx.compose.foundation.layout.Column {
                    RadioStatusBadge(RadioColorRole.DISCONNECTED, Modifier.testTag("status"))
                    SignalQualityBadge(SignalColorRole.POOR, Modifier.testTag("signal"))
                    ThemeFailureContent(error) { retries++ }
                }
            }
        }
        compose.onNodeWithTag("status").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription,
                listOf(controller.get().getString(RadioColorRole.DISCONNECTED.labelResource))),
        )
        compose.onNodeWithTag("signal").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription,
                listOf(controller.get().getString(SignalColorRole.POOR.labelResource))),
        )
        compose.onNodeWithTag("theme-error").assert(SemanticsMatcher.expectValue(ThemeFailureCode, "UnknownThemeID"))
        compose.onNodeWithText(controller.get().getString(com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonTryAgain))
            .assertHeightIsAtLeast(49.dp).performClick()
        assertEquals(1, retries)
    }
    @Test fun nativeContrastAndRemoveAnimationSettingsUpdateAndDisposeWithComposition() {
        val resolver = controller.get().contentResolver
        Settings.Secure.putInt(resolver, "high_text_contrast_enabled", 0)
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        var contrast = false
        var scale = -1f
        content {
            val high = rememberHighContrast()
            val motion = rememberMotionDurationScale()
            SideEffect { contrast = high; scale = motion }
            Text("")
        }
        compose.runOnIdle { assertFalse(contrast); assertEquals(1f, scale) }
        compose.runOnIdle {
            Settings.Secure.putInt(resolver, "high_text_contrast_enabled", 1)
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        }
        compose.runOnIdle { assertTrue(contrast); assertEquals(0f, scale) }
        content { Text("Sakura") }
        compose.runOnIdle {
            Settings.Secure.putInt(resolver, "high_text_contrast_enabled", 0)
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
        compose.runOnIdle { assertTrue(contrast); assertEquals(0f, scale) }
    }

    @Test fun actualSurfaceTextButtonUsesLegibleNativePrimaryAcrossEveryEffectiveFrame() {
        val selected = mutableStateOf(ThemeRegistry.default)
        val scheme = mutableStateOf(AppColorSchemePreference.LIGHT)
        val high = mutableStateOf(false)
        var textColor: androidx.compose.ui.graphics.Color? = null
        var surface: androidx.compose.ui.graphics.Color? = null
        content {
            MeshCoreTheme(selected.value, scheme.value, highContrast = high.value, motionScale = 0f) {
                val background = MaterialTheme.colorScheme.surface
                androidx.compose.material3.Surface(color = background) {
                    androidx.compose.material3.TextButton(onClick = {}) {
                        val foreground = androidx.compose.material3.LocalContentColor.current
                        SideEffect { textColor = foreground; surface = background }
                        Text(themeName(LocalAppTheme.current), Modifier.testTag("surface-text-button"))
                    }
                }
            }
        }
        for (frame in effectiveFrames()) {
            compose.runOnIdle {
                selected.value = frame.theme
                scheme.value = if (frame.colorScheme == ColorScheme.DARK) AppColorSchemePreference.DARK else AppColorSchemePreference.LIGHT
                high.value = frame.highContrast
            }
            compose.onNodeWithTag("surface-text-button", useUnmergedTree = true).assertIsDisplayed()
            compose.runOnIdle {
                assertTrue(WCAGContrast.contrastRatio(assertNotNull(textColor).toThemeColor(),
                    assertNotNull(surface).toThemeColor()) >= WCAGContrast.floor(frame.highContrast), frame.theme.id.rawValue)
            }
        }
    }

    private fun capture(id: String) {
        compose.onNodeWithTag("preview-heading").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("incoming-sample").assertIsDisplayed()
        compose.onNodeWithTag("outgoing-sample").assertIsDisplayed()
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = controller.get().window.decorView
            assertTrue(view.width > 0 && view.height > 0)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue(pixels.toSet().size > 16, "A blank screenshot is not UI evidence")
        val bytes = ByteArrayOutputStream().use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            stream.toByteArray()
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val root = File(assertNotNull(System.getProperty("themeArtifactDirectory")))
        assertTrue(root.isDirectory || root.mkdirs())
        File(root, "$id.png").writeBytes(bytes)
        // Complete bytes travel with the existing verbatim module JUnit bundle, without a shared CI edit.
        println("WP301_RENDER|$id|${bitmap.width}|${bitmap.height}|$digest|${Base64.getEncoder().encodeToString(bytes)}")
    }
}
