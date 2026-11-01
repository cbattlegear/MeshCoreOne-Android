// AndroidOnly: WP-304 Mounted WP-305 scanner consumes shared signal bars without duplicating announcements or changing selection.
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeColor
import com.meshcoreone.android.core.designsystem.accessibleForeground
import com.meshcoreone.android.core.designsystem.toComposeColor
import com.meshcoreone.android.core.designsystem.toThemeColor
import com.meshcoreone.android.core.ui.RSSITuning
import com.meshcoreone.android.core.ui.SharedEmittedForeground
import com.meshcoreone.android.core.ui.SharedPaintedSurface
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionSnapshot
import com.meshcoreone.android.feature.onboarding.OnboardingScanDiscovery
import com.meshcoreone.android.feature.onboarding.PermissionStatus
import com.meshcoreone.android.feature.onboarding.ui.DeviceScannerSheetContent
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScannerSignalPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun mountedScannerKeepsOneLocalizedNameTierAnnouncementAndItsActualSelection() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val discoveries = RSSITuning.SignalTier.entries.mapIndexed { index, tier ->
            OnboardingScanDiscovery(UUID(0, index.toLong() + 1), "Radio $index", tier)
        }
        val selected = mutableListOf<UUID>()
        val tiers = listOf("Weak signal", "Medium signal", "Strong signal")
        val seeds = listOf(0xFF3B30, 0xFFCC00, 0x34C759)
        try {
            activity.get().setContent {
                MeshCoreTheme(motionScale = 0f) {
                    DeviceScannerSheetContent(discoveries,
                        OnboardingPermissionSnapshot(bluetooth = PermissionStatus.GRANTED),
                        selected::add, {})
                }
            }
            for ((index, device) in discoveries.withIndex()) {
                val announcement = "Radio $index, ${tiers[index]}"
                compose.onNodeWithText(tiers[index]).assertIsDisplayed()
                compose.onAllNodes(hasContentDescription(announcement)).assertCountEquals(1)
                val row = compose.onNodeWithContentDescription(announcement).fetchSemanticsNode()
                fun glyphs(node: androidx.compose.ui.semantics.SemanticsNode): Int =
                    (if (node.config.contains(SharedEmittedForeground)) 1 else 0) + node.children.sumOf(::glyphs)
                val unmerged = compose.onNodeWithContentDescription(announcement, useUnmergedTree = true).fetchSemanticsNode()
                assertEquals(1, glyphs(unmerged))
                val glyph = compose.onNode(SemanticsMatcher.keyIsDefined(SharedEmittedForeground) and
                    hasAnyAncestor(hasContentDescription(announcement)), useUnmergedTree = true)
                val surface = glyph.fetchSemanticsNode().config[SharedPaintedSurface]
                val foreground = accessibleForeground(ThemeColor.hex(seeds[index]), surface.toThemeColor(), 4.5)
                    .toComposeColor()
                assertEquals(foreground, glyph.fetchSemanticsNode().config[SharedEmittedForeground])
                val pixels = glyph.captureToImage().toPixelMap()
                for (bar in 0..<3) {
                    val expected = if (bar <= index) foreground else foreground.copy(alpha = 0.2f).compositeOver(surface)
                    val actual = pixels[(pixels.width * (bar + 0.375f) / 3).toInt(), pixels.height * 5 / 6].toArgb()
                    for (shift in listOf(0, 8, 16)) {
                        assertTrue(abs(((actual ushr shift) and 255) - ((expected.toArgb() ushr shift) and 255)) <= 1,
                            "Mounted $announcement bar $bar does not match its independent tier")
                    }
                }
                assertFalse(row.config.contains(SemanticsProperties.ProgressBarRangeInfo))
                compose.onNodeWithContentDescription(announcement).performClick()
                assertEquals(device.id, selected.last())
            }
            assertEquals(discoveries.map { it.id }, selected)
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
