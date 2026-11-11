// AndroidOnly: WP-312 Mounted production map frame/footer layout, not native GL pixel evidence.
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.maps.MapLibreOpenFreeMap
import com.meshcoreone.android.core.maps.MapSurfaceLayout
import org.junit.After
import org.junit.Assert.*
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
@Config(sdk = [31, 37], qualifiers = "en-rUS-w1000dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapAttributionLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    @Test fun inlineFooterKeepsEveryCreditReadableAndClickableAcrossResizeFontAndDirectionChanges() {
        val width = mutableStateOf(320)
        val font = mutableStateOf(2f)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val opened = mutableListOf<String>()
        var viewportMounts = 0
        var viewportDisposals = 0
        var gestures = 0
        var overlayClicks = 0
        host.get().setContent {
            MeshCoreTheme(motionScale = 0f) {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, font.value),
                    LocalLayoutDirection provides direction.value,
                    LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) { opened += uri }
                    },
                ) {
                    MapSurfaceLayout(
                        MapLibreOpenFreeMap.attribution,
                        Modifier.requiredSize(width.value.dp, 220.dp).testTag("map-frame"),
                        overlay = {
                            TextButton(
                                { overlayClicks++ },
                                Modifier.align(Alignment.TopEnd).testTag("map-overlay"),
                            ) { Text("Overlay") }
                        },
                    ) {
                        val identity = remember { Any() }
                        DisposableEffect(identity) {
                            viewportMounts++
                            onDispose { viewportDisposals++ }
                        }
                        Box(Modifier.fillMaxSize().testTag("map-viewport").clickable { gestures++ })
                    }
                }
            }
        }
        for ((newWidth, newFont, newDirection) in listOf(
            Triple(320, 2f, LayoutDirection.Ltr),
            Triple(600, 2f, LayoutDirection.Rtl),
            Triple(320, 1f, LayoutDirection.Rtl),
            Triple(840, 1f, LayoutDirection.Ltr),
            Triple(320, 2f, LayoutDirection.Rtl),
        )) {
            compose.runOnIdle {
                width.value = newWidth
                font.value = newFont
                direction.value = newDirection
            }
            val frame = compose.onNodeWithTag("map-frame").fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNodeWithTag("map-viewport").fetchSemanticsNode().boundsInRoot
            assertTrue("Inline map must retain a touchable viewport: $viewport", viewport.height >= 48f)
            for (provider in MapLibreOpenFreeMap.attribution) {
                val credit = compose.onNodeWithText(provider.label)
                credit.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                val bounds = credit.fetchSemanticsNode().boundsInRoot
                assertTrue("Footer must stay below map and overlays: $bounds, $viewport", bounds.top >= viewport.bottom)
                assertTrue("Footer must not be clipped by frame: $bounds, $frame",
                    bounds.bottom <= frame.bottom && bounds.left >= frame.left && bounds.right <= frame.right)
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(provider.label, useUnmergedTree = true)
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
                        it(layouts)
                    }
                assertEquals(1, layouts.size)
                val layout = layouts.single()
                assertFalse(
                    "Full credit must remain readable: ${provider.label}, width=$newWidth font=$newFont " +
                        "direction=$newDirection textSize=${layout.size} lines=${layout.lineCount} " +
                        "constraints=${layout.layoutInput.constraints} bounds=$bounds viewport=$viewport",
                    layout.hasVisualOverflow,
                )
                assertEquals(newFont, layouts.single().layoutInput.density.fontScale, 0f)
                credit.performClick()
            }
            compose.onNodeWithTag("map-overlay").performClick()
            compose.onNodeWithTag("map-viewport").performTouchInput { click(bottomCenter + androidx.compose.ui.geometry.Offset(0f, -4f)) }
            assertEquals(1, viewportMounts)
            assertEquals(0, viewportDisposals)
        }
        assertEquals(List(5) { MapLibreOpenFreeMap.attribution.map { it.legalUri } }.flatten(), opened)
        assertEquals(5, overlayClicks)
        assertEquals(5, gestures)
    }
}
