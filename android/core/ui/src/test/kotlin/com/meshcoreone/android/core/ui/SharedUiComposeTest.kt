// AndroidOnly: WP-304 Real native Compose/Material flows, meaningful PNGs and accessible compact/expanded/resize/font/dialog/tip states.
package com.meshcoreone.android.core.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.View
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.pm.ApplicationInfo
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.ColorScheme
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.designsystem.ThemeColor
import com.meshcoreone.android.core.designsystem.accessibleForeground
import com.meshcoreone.android.core.designsystem.toComposeColor
import com.meshcoreone.android.core.designsystem.toThemeColor
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as C
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppMapStrings as M
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.BackupContract
import com.meshcoreone.android.core.model.CommittedBackupCounts
import com.meshcoreone.android.core.model.CommittedBackupReceipt
import com.meshcoreone.android.core.model.CommittedBackupPreferenceFailure
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.datastore.StorageFailure
import com.meshcoreone.android.core.datastore.StorageProblem
import com.meshcoreone.android.core.datastore.StorageOperation
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.BatteryInfo
import com.meshcoreone.android.core.services.contacts.AdvertisementError
import com.meshcoreone.android.core.services.remote.BinaryProtocolError
import com.meshcoreone.android.core.services.remote.RoomServerError
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.Locale
import java.time.Duration
import kotlin.test.*
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowViewRootImpl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedUiComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var controller: ActivityController<ComponentActivity>
    private val device = DeviceDTO(
        id = UUID.fromString("12345678-1234-1234-1234-123456789ABC"),
        radioId = RadioId(UUID.fromString("11111111-2222-3333-4444-555555555555")),
        publicKey = Bytes(ByteArray(32) { 0x42 }),
        nodeName = "Fixture radio",
    )
    private val resources get() = controller.get().resources

    @Before fun attachNativeWindow() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        controller.get().actionBar?.hide()
        controller.windowFocusChanged(true)
        assertTrue(controller.get().window.decorView.hasWindowFocus())
    }
    @After fun disposeNativeWindow() {
        controller.windowFocusChanged(false)
        controller.pause().stop().destroy()
    }
    private fun content(block: @Composable () -> Unit) { controller.get().setContent(content = block) }

    private fun nativeGlyphPixels(node: SemanticsNode): Bitmap {
        // Draw the owning Compose view; Robolectric does not deliver PixelCopy callbacks.
        val view = assertIs<ViewRootForTest>(node.root).view
        val bounds = node.boundsInRoot
        return compose.runOnUiThread {
            val window = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(window))
            Bitmap.createBitmap(window, bounds.left.roundToInt(), bounds.top.roundToInt(),
                bounds.width.roundToInt(), bounds.height.roundToInt())
        }
    }

    private fun nativeFrame() {
        // MainTestClock does not drain Android's snapshot and window-attachment queues.
        compose.runOnUiThread {
            Snapshot.sendApplyNotifications()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
    }

    private fun focusNativeDialog(view: View) {
        // Apply Robolectric's ActivityController focus path to the separate dialog ViewRoot.
        val root = assertNotNull(View::class.java.getMethod("getViewRootImpl").invoke(view))
        Shadow.extract<ShadowViewRootImpl>(root).callWindowFocusChanged(true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(view.hasWindowFocus(), "Native dialog ViewRoot did not receive focus")
    }

    @Suppress("DEPRECATION")
    private fun configureSystemFontAndDirection(fontScale: Float, direction: LayoutDirection) {
        compose.runOnUiThread {
            val application = ApplicationProvider.getApplicationContext<Context>()
            application.applicationInfo.flags = application.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
            controller.get().applicationInfo.flags = controller.get().applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
            for (target in setOf(application.resources, controller.get().resources)) {
                val configuration = Configuration(target.configuration).apply {
                    this.fontScale = fontScale
                    setLayoutDirection(Locale.forLanguageTag(if (direction == LayoutDirection.Rtl) "ar" else "en"))
                }
                target.updateConfiguration(configuration, target.displayMetrics)
            }
            controller.get().window.decorView.layoutDirection =
                if (direction == LayoutDirection.Rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
    }

    private fun resize(width: Int, height: Int = 900) {
        nativeFrame()
        compose.runOnUiThread {
            val view = controller.get().window.decorView
            controller.get().window.setLayout(width, height)
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val dialog = ShadowDialog.getLatestDialog()
            if (dialog?.isShowing == true) {
                val dialogView = assertNotNull(dialog.window).decorView
                assertTrue(dialogView.isAttachedToWindow,
                    "Native dialog decor is not attached after the Android frame")
                dialogView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
                dialogView.layout(0, 0, dialogView.measuredWidth, dialogView.measuredHeight)
                focusNativeDialog(dialogView)
            }
        }
        nativeFrame()
        nativeFrame()
        compose.waitForIdle()
    }

    @Composable
    private fun sample(
        error: UiErrorState? = null,
        tip: SharedTip? = null,
        status: StatusPillState = StatusPillState.Ready,
        notificationLevel: NotificationLevel = NotificationLevel.ALL,
        onLevel: (NotificationLevel) -> Unit = {},
        onDisconnected: () -> Unit = {},
    ) {
        SharedUiScaffold(topBar = {
            NavigationHeader("S\u00f8ren \u706f\u706b", "\u05e9\u05dc\u05d5\u05dd #general",
                Modifier.testTag("header"))
        }, bottomBar = { ErrorBanner(error, {}, Modifier.testTag("banner")) }) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    ContactAvatar("Ada Lovelace", 49.dp)
                    NodeAvatar(RemoteNodeRole.REPEATER, 49.dp, UiText.Verbatim("Repeater"))
                    SignalBars(RSSITuning.SignalTier.MEDIUM,
                        accessibilityLabel = UiText.Resource(RSSITuning.SignalTier.MEDIUM.accessibilityResource))
                }
                Text("\u4e2d\u6587\u6d88\u606f \u05e9\u05dc\u05d5\u05dd ".repeat(8),
                    Modifier.testTag("message-sample"), style = MaterialTheme.typography.bodyLarge)
                SyncingPillView(status, Modifier.testTag("status"), onDisconnected)
                ConversationQuickActionsSection(false, notificationLevel, {}, onLevel, Modifier.testTag("quick-actions"))
                ExpandableSettingsSection(UiText.Verbatim("Fixture settings"), MeshSymbol.SETTINGS,
                    ExpandableSectionState(true, false, false, true), {}, {}, Modifier.testTag("section")) {
                    Text("\u8a2d\u5b9a \u05d4\u05d2\u05d3\u05e8\u05d5\u05ea")
                }
                GlassFilterBar(SnapshotList.of(FilterChoice("all", UiText.Resource(C.chatsFilterAll)),
                    FilterChoice("unread", UiText.Resource(C.chatsFilterUnread))),
                    "all", false, UiText.Resource(C.chatsFilterTitle), {}, Modifier.testTag("filters"))
                SharedTipContent(tip, {}, Modifier.testTag("tip"))
            }
        }
    }

    @Test fun compactAndExpandedSourceTiersProduceMeaningfulNativeScreensNotAThemePicker() {
        val selected = mutableStateOf(ThemeRegistry.default)
        val dark = mutableStateOf(false)
        val high = mutableStateOf(false)
        content {
            MeshCoreTheme(selected.value, systemDark = dark.value, highContrast = high.value, motionScale = 0f) { sample() }
        }
        resize(360)
        compose.onNodeWithTag("header").assertIsDisplayed()
        compose.onNodeWithTag("message-sample").assertIsDisplayed()
        capture("compact-light")
        compose.runOnIdle { selected.value = assertNotNull(ThemeRegistry.theme("marine")); dark.value = true; high.value = true }
        resize(840)
        compose.onNodeWithTag("header").assertIsDisplayed()
        capture("expanded-dark-hc")
    }

    @Test fun everyUnlockedEffectiveThemeRendersSharedStateAndSelectedControlsWithoutChangingIdentity() {
        val selected = mutableStateOf(ThemeRegistry.default)
        val preference = mutableStateOf(AppColorSchemePreference.LIGHT)
        val high = mutableStateOf(false)
        content {
            MeshCoreTheme(selected.value, preference.value, highContrast = high.value, motionScale = 0f) { sample() }
        }
        resize(840)
        for (theme in ThemeRegistry.allThemes) {
            for (scheme in ColorScheme.entries.filter { theme.preferredColorScheme == null || it == theme.preferredColorScheme }) {
                for (contrast in listOf(false, true)) {
                    compose.runOnIdle {
                        selected.value = theme
                        preference.value = if (scheme == ColorScheme.DARK) AppColorSchemePreference.DARK else AppColorSchemePreference.LIGHT
                        high.value = contrast
                    }
                    compose.onNodeWithTag("header").assertIsDisplayed()
                    compose.onNode(hasText(resources.getString(C.chatsNotificationLevelAll)) and
                        hasAnyAncestor(hasTestTag("quick-actions")))
                        .performScrollTo().assertIsSelected().assertHeightIsAtLeast(49.dp).assertWidthIsAtLeast(49.dp)
                }
            }
        }
    }

    @Test fun nativeResizePreservesRememberedAndCallerSelectedDetailState() {
        val selected = mutableStateOf(NotificationLevel.ALL)
        var retained = -1
        content {
            val detail by remember { mutableIntStateOf(37) }
            SideEffect { retained = detail }
            MeshCoreTheme(motionScale = 0f) { sample(notificationLevel = selected.value, onLevel = { selected.value = it }) }
        }
        resize(360)
        compose.onNodeWithText(resources.getString(C.chatsNotificationLevelMuted)).performScrollTo().performClick()
        resize(840)
        compose.onNodeWithText(resources.getString(C.chatsNotificationLevelMuted)).performScrollTo().assertIsSelected()
        compose.runOnIdle { assertEquals(37, retained); assertEquals(NotificationLevel.MUTED, selected.value) }
        capture("resize")
    }

    @Test fun font200CjkRtlLongTextDoesNotClipAndTargetsRemainNativeSized() {
        configureSystemFontAndDirection(2f, LayoutDirection.Rtl)
        content {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                MeshCoreTheme(highContrast = true, motionScale = 0f) { sample() }
            }
        }
        resize(360)
        compose.onNodeWithTag("message-sample").performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("message-sample").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
            assertTrue(get(layouts))
        }
        assertTrue(layouts.single().lineCount > 1)
        assertEquals(2f, layouts.single().layoutInput.density.fontScale)
        assertEquals(LayoutDirection.Rtl, layouts.single().layoutInput.layoutDirection)
        assertFalse(layouts.single().hasVisualOverflow)
        compose.onNode(hasText(resources.getString(C.chatsNotificationLevelAll)) and
            hasAnyAncestor(hasTestTag("quick-actions"))).performScrollTo()
            .assertHeightIsAtLeast(49.dp).assertWidthIsAtLeast(49.dp)
        compose.onNodeWithTag("header").assertIsDisplayed()
        capture("font200-cjk-rtl")
    }

    @Test fun failureRetryClearsTheNativeDialogBeforeCallingTheInjectedRecovery() {
        val error = mutableStateOf<UiErrorState?>(UiErrorMapper().present(MeshCoreException.Timeout()).content)
        val order = mutableListOf<String>()
        content {
            MeshCoreTheme(motionScale = 0f) {
                sample(error.value)
                ErrorAlert(error.value, { order += "dismiss"; error.value = null },
                    onRetry = { assertNull(error.value); order += "retry" })
            }
        }
        resize(360)
        val retry = compose.onNode(hasText(resources.getString(L.commonTryAgain)) and hasAnyAncestor(isDialog()))
        retry.assertIsDisplayed().assertHeightIsAtLeast(49.dp)
        capture("failure-retry", dialog = true)
        retry.performClick()
        compose.runOnIdle { assertEquals(listOf("dismiss", "retry"), order) }
    }

    @Test fun selectedTypedErrorsRenderIndependentFrozenCopyInTheActualMaterialDialog() {
        val error = mutableStateOf<PresentedUiError?>(null)
        content {
            MeshCoreTheme(motionScale = 0f) {
                PresentedErrorAlert(error.value, { error.value = null }, UiErrorActions())
            }
        }
        val cases = listOf(
            AdvertisementError.NotConnected() to "Not connected to device.",
            RoomServerError.PermissionDenied() to "Permission denied.",
            BinaryProtocolError.Timeout() to "Request timed out.",
            PersistenceStoreException(PersistenceStoreError.ContactNotFound) to "Contact not found.",
            SettingsServiceException(SettingsServiceError.SessionError(MeshCoreException.NotConnected())) to "Not connected to device.",
        )
        for ((failure, expected) in cases) {
            compose.runOnIdle { error.value = UiErrorMapper().present(failure) }
            compose.onNode(hasText(expected) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.runOnIdle { assertSame(failure, assertNotNull(error.value).originalFailure) }
            compose.onNode(hasText("OK") and hasAnyAncestor(isDialog())).performClick()
            compose.runOnIdle { assertNull(error.value) }
        }
    }

    @Test fun actualRadioMenuUsesTheCurrentOcvBatteryReadingAndSharedNativeGlyph() {
        val radio = mutableStateOf(RadioStatusState(DeviceConnectionState.READY, device, BatteryInfo(3100)))
        var normalForeground = Color.Unspecified
        content {
            MeshCoreTheme(motionScale = 0f) {
                val primary = MaterialTheme.colorScheme.onSurface
                SideEffect { normalForeground = primary }
                BLEStatusIndicatorView(radio.value, RadioStatusActions({}, {}, {}, {}))
            }
        }
        resize(360)
        compose.onNodeWithContentDescription(resources.getString(S.bleStatusAccessibilityLabel)).performClick()
        for ((voltage, expected, filled) in listOf(
            Triple(3100L, "0% (3.10v)", 0), Triple(3360L, "15% (3.36v)", 1),
            Triple(3475L, "25% (3.48v)", 1), Triple(3720L, "50% (3.72v)", 2),
            Triple(3940L, "75% (3.94v)", 3), Triple(4190L, "100% (4.19v)", 4),
        )) {
            compose.runOnIdle { radio.value = radio.value.copy(battery = BatteryInfo(voltage)) }
            compose.onNodeWithText(expected).assertIsDisplayed()
            val row = compose.onNodeWithContentDescription("Battery: $expected", useUnmergedTree = true).fetchSemanticsNode()
            assertEquals(1, row.children.count { it.config.contains(SharedEmittedForeground) })
            val glyph = compose.onNode(SemanticsMatcher.keyIsDefined(SharedEmittedForeground) and
                hasAnyAncestor(hasContentDescription("Battery: $expected")), useUnmergedTree = true)
            val surface = glyph.fetchSemanticsNode().config[SharedPaintedSurface]
            val seed = when (voltage) {
                3100L -> ThemeColor.hex(0xFF3B30)
                3360L -> ThemeColor.hex(0xFF9500)
                else -> normalForeground.toThemeColor()
            }
            val foreground = accessibleForeground(seed, surface.toThemeColor(), 4.5).toComposeColor()
            assertEquals(foreground, glyph.fetchSemanticsNode().config[SharedEmittedForeground])
            assertFalse(row.config.contains(SemanticsProperties.ProgressBarRangeInfo))
            val pixels = nativeGlyphPixels(glyph.fetchSemanticsNode())
            val span = pixels.width * 0.88f - 6f
            for (segment in 0..<4) {
                val actual = pixels.getPixel((3f + span * (segment + 0.5f) / 4).toInt(), pixels.height / 2)
                val expectedPixel = if (segment < filled) foreground.toArgb() else surface.toArgb()
                for (shift in listOf(0, 8, 16)) {
                    assertTrue(abs(((actual ushr shift) and 255) - ((expectedPixel ushr shift) and 255)) <= 1,
                        "Mounted $expected battery segment $segment does not match its OCV reading")
                }
            }
        }
    }

    @Test fun nativeRegionDialogKeepsInvalidInputFocusAndBackCancelsWithoutAnAction() {
        assertTrue(compose.mainClock.autoAdvance)
        val state = mutableStateOf(RegionManagementState(SnapshotList.of("known"),
            addDialogVisible = true, newRegionName = "invalid region"))
        var adds = 0
        val actions = RegionManagementActions({}, {}, {}, {},
            { state.value = state.value.copy(newRegionName = it) },
            { state.value = state.value.copy(validationError = it) },
            { adds++ }, { state.value = state.value.copy(addDialogVisible = false) })
        content { MeshCoreTheme(motionScale = 0f) { RegionManagementView(state.value, actions) } }
        resize(360)
        compose.onNodeWithText("invalid region").assertIsFocused()
        compose.onNodeWithText(resources.getString(C.chatsChannelInfoRegionAddSelected)).performClick()
        nativeFrame()
        compose.onNodeWithText("invalid region").assertIsFocused()
        compose.runOnIdle { assertEquals(RegionValidationError.InvalidCharacters, state.value.validationError); assertEquals(0, adds) }
        capture("dialog", dialog = true)
        compose.runOnUiThread {
            val dialog = assertNotNull(ShadowDialog.getLatestDialog())
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            assertNotNull(dialog.window).decorView.dispatchWindowFocusChanged(false)
        }
        nativeFrame()
        compose.runOnIdle { assertEquals(0, adds) }
        compose.runOnIdle { assertFalse(state.value.addDialogVisible) }
    }

    @Test fun claimedTipRendersPassiveReadableCopyAndAnExplicitDismissControl() = runBlocking {
        val root = temporary.newFolder()
        val application = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val storage = MeshCoreStorage.get(context)
        try {
            val tips = ShowOnceTips(storage.preferences)
            tips.donateCompletedOnboarding()
            assertTrue(tips.claim(SharedTip.DEVICE_MENU) { TipHostState(true, true, false) })
            assertTrue(tips.hasDisplayed(SharedTip.DEVICE_MENU))
            content { MeshCoreTheme(motionScale = 0f) { sample(tip = SharedTip.DEVICE_MENU) } }
            resize(840)
            compose.onNodeWithTag("tip").performScrollTo().assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SharedTipId, "DeviceMenuTip"))
            compose.onNodeWithText(resources.getString(C.chatsTipDeviceMenuTitle)).assertIsDisplayed()
            capture("tips")
        } finally { storage.close() }
    }

    @Test fun cropDialogHasActualNativeImagePixelsBoundedGeometryAndAccessibleNonGestureControls() {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLUE }
        canvas.drawRect(0f, 0f, 100f, 100f, paint)
        paint.color = android.graphics.Color.GREEN
        canvas.drawRect(100f, 0f, 200f, 100f, paint)
        val image = bitmap.asImageBitmap()
        val geometry = mutableStateOf(AvatarCropGeometry(300.0, CropSize(200.0, 100.0)))
        var chosen: AvatarCropGeometry? = null
        content {
            MeshCoreTheme(motionScale = 0f) {
                AvatarCropView(image, geometry.value, { geometry.value = it }, {}, { chosen = it })
            }
        }
        resize(360)
        compose.onNodeWithText(resources.getString(AppContactsStrings.contactsDetailAvatarCropChoose)).assertIsDisplayed()
        capture("crop", dialog = true)
        compose.onNodeWithText(resources.getString(AppContactsStrings.contactsDetailAvatarCropChoose)).performClick()
        compose.runOnIdle {
            assertNotNull(chosen)
            val cropped = cropAvatarImage(image, assertNotNull(chosen))
            assertEquals(512, cropped.width); assertEquals(512, cropped.height)
        }
    }

    @Test fun uriKeyboardNextDoneClearAndResizePreserveTheSourceFormAndFocus() {
        val state = mutableStateOf(WiFiAddressState("radio.local", "5000", WiFiField.IP_ADDRESS))
        var submissions = 0
        content {
            MeshCoreTheme(motionScale = 0f) {
                WiFiAddressFields(state.value,
                    { state.value = state.value.copy(ipAddress = it) },
                    { state.value = state.value.copy(port = it) },
                    { state.value = state.value.copy(focusedField = it) }, { submissions++ },
                    UiText.Resource(O.wifiConnectionConnectionDetailsHeader),
                    UiText.Resource(O.wifiConnectionConnectionDetailsFooter))
            }
        }
        resize(360)
        compose.onNodeWithText("radio.local").performTextReplacement("192,168,1,50")
        compose.runOnIdle { assertEquals("192.168.1.50", state.value.ipAddress) }
        compose.onNodeWithText("192.168.1.50").performImeAction()
        compose.onNodeWithText("5000").assertIsFocused()
        resize(840)
        compose.runOnIdle { assertEquals(WiFiField.PORT, state.value.focusedField); assertEquals("5000", state.value.port) }
        compose.onNodeWithText("5000").performImeAction()
        compose.runOnIdle { assertEquals(1, submissions) }
    }

    @Test fun font200RtlStorageRecoveryHasRealTypedMetadataAndNativeBackDismissesWithoutRetry() {
        configureSystemFontAndDirection(2f, LayoutDirection.Rtl)
        val error = mutableStateOf<PresentedUiError?>(UiErrorMapper().present(
            StorageFailure(StorageProblem.DeviceLocked, StorageOperation.WRITE)))
        var unlocked = 0
        content {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                MeshCoreTheme(highContrast = true, motionScale = 0f) {
                    sample()
                    PresentedErrorAlert(error.value, { error.value = null }, UiErrorActions(unlockDevice = { unlocked++ }))
                }
            }
        }
        resize(360)
        compose.onNodeWithText(resources.getString(R.string.ui_storage_locked), substring = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(resources.getString(R.string.ui_storage_locked), substring = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(2f, layouts.single().layoutInput.density.fontScale)
        assertEquals(LayoutDirection.Rtl, layouts.single().layoutInput.layoutDirection)
        assertFalse(layouts.single().hasVisualOverflow)
        capture("storage-recovery-font200-rtl", dialog = true)
        compose.runOnUiThread {
            val dialog = assertNotNull(ShadowDialog.getLatestDialog())
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        compose.runOnIdle { assertNull(error.value); assertEquals(0, unlocked) }
    }

    @Test fun committedPreferenceDialogUsesOnlyTheActualMarkerCompletionActionAndKeepsTheReceipt() {
        val receipt = CommittedBackupReceipt(BackupContract.modelArrayKeys.associateWith { CommittedBackupCounts(1, 0, 0, 0) },
            false, emptyMap())
        val storage = StorageFailure(StorageProblem.OwnerClosed, StorageOperation.WRITE)
        val marker = object : Exception("consumer-marker-fixture", storage), CommittedBackupPreferenceFailure {
            override val committedReceipt = receipt
            override val preferenceFailure: Throwable = storage
        }
        val error = mutableStateOf<PresentedUiError?>(UiErrorMapper().present(marker))
        var importRetries = 0
        var completed: CommittedBackupPreferenceFailure? = null
        content {
            MeshCoreTheme(motionScale = 0f) {
                sample()
                PresentedErrorAlert(error.value, { error.value = null }, UiErrorActions(
                    retry = { importRetries++ }, completeBackupPreferences = { completed = it },
                ))
            }
        }
        resize(360)
        compose.onNodeWithText(resources.getString(R.string.ui_backup_complete_preferences)).assertIsDisplayed()
            .assertHeightIsAtLeast(49.dp)
        capture("committed-preference-only", dialog = true)
        compose.onNodeWithText(resources.getString(R.string.ui_backup_complete_preferences)).performClick()
        compose.runOnIdle {
            assertNull(error.value)
            assertEquals(0, importRetries)
            assertSame(marker, completed)
            assertEquals(receipt, completed?.committedReceipt)
        }
    }

    @Test fun mapMenusDismissAfterSelectionAndDispatchOnlyTheSelectedAction() {
        val calls = mutableListOf<String>()
        val state = MapControlsState(false, false, true, true,
            SnapshotList.of(MapControlChoice("base", UiText.Verbatim("Fixture base map"), true)),
            SnapshotList.of(MapControlChoice("favorite", UiText.Verbatim("Fixture favorites"), false)))
        val actions = MapControlsActions({ calls += "center" }, { calls += "style:$it" },
            { calls += "filter:$it" }, { calls += "north:$it" }, { calls += "labels:$it" },
            { calls += "clustering:$it" })
        content { MeshCoreTheme(motionScale = 0f) { MapControlsToolbar(state, actions) } }
        resize(360)
        compose.onNodeWithContentDescription(resources.getString(M.mapControlsFilter)).performClick()
        compose.onNodeWithText("Fixture favorites").assertIsDisplayed().performClick()
        compose.onNodeWithText("Fixture favorites").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("filter:favorite"), calls) }
        compose.onNodeWithContentDescription(resources.getString(M.mapControlsMapOptions)).performClick()
        compose.onNodeWithText(resources.getString(M.mapControlsShowLabels)).performClick()
        compose.onNodeWithText("Fixture base map").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("filter:favorite", "labels:false"), calls) }
    }

    @Test fun unloadedSectionFailureShowsInlineRetryWithoutAnotherHeaderReload() {
        val state = mutableStateOf(ExpandableSectionState(true, false, false, true))
        var loads = 0
        content {
            MeshCoreTheme(motionScale = 0f) {
                ExpandableSettingsSection(UiText.Verbatim("Fixture section"), MeshSymbol.SETTINGS,
                    state.value, {}, { loads++ }) {}
            }
        }
        resize(360)
        compose.onNodeWithContentDescription("Fixture section").assertDoesNotExist()
        compose.onNodeWithText(resources.getString(L.commonTryAgain)).performClick()
        compose.runOnIdle {
            assertEquals(1, loads)
            state.value = ExpandableSectionState(true, true, false, false)
        }
        compose.onNodeWithText(resources.getString(L.commonTryAgain)).assertDoesNotExist()
        compose.onNodeWithContentDescription("Fixture section").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, loads) }
    }

    @Test fun radioAndNotificationActionsExposeSourceHintsSelectedStateAndCallerCallbacks() {
        val radio = mutableStateOf(RadioStatusState(DeviceConnectionState.DISCONNECTED, null))
        val selection = mutableStateOf(NotificationLevel.ALL)
        val adverts = mutableListOf<Boolean>()
        content {
            MeshCoreTheme(motionScale = 0f) {
                Column {
                    BLEStatusIndicatorView(radio.value, RadioStatusActions({}, {}, { adverts.add(it) }, {}))
                    NotificationLevelPicker(selection.value, { selection.value = it })
                }
            }
        }
        resize(360)
        val radioLabel = resources.getString(S.bleStatusAccessibilityLabel)
        val disconnected = compose.onNodeWithContentDescription(radioLabel).fetchSemanticsNode().config
        assertEquals(resources.getString(S.bleStatusStatusDisconnected), disconnected[SemanticsProperties.StateDescription])
        assertEquals(resources.getString(S.bleStatusAccessibilityHintDisconnected), disconnected[SemanticsActions.OnClick].label)
        compose.runOnIdle { radio.value = RadioStatusState(DeviceConnectionState.READY, device) }
        val connected = compose.onNodeWithContentDescription(radioLabel).fetchSemanticsNode().config
        assertEquals(resources.getString(S.bleStatusAccessibilityHintConnected), connected[SemanticsActions.OnClick].label)
        compose.onNodeWithContentDescription(radioLabel).performClick()
        val zeroHop = compose.onNodeWithText(resources.getString(S.bleStatusSendZeroHopAdvert))
        assertEquals(resources.getString(S.bleStatusSendZeroHopAdvertHint), zeroHop.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        zeroHop.performClick()
        compose.runOnIdle { assertEquals(listOf(false), adverts) }
        val muted = compose.onNodeWithText(resources.getString(C.chatsNotificationLevelMuted))
        assertEquals(resources.getString(C.chatsNotificationLevelHint), muted.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        muted.performClick()
        muted.assertIsSelected()
        compose.runOnIdle { assertEquals(NotificationLevel.MUTED, selection.value) }
    }

    @Test fun overlayShowsTheIncomingStateOnTheFirstVisibleFrameAndRemovesHiddenActions() {
        val state = mutableStateOf<StatusPillState>(StatusPillState.Hidden)
        var taps = 0
        var composedState: StatusPillState = StatusPillState.Hidden
        var composedWindowFocused = false
        content {
            val incoming = state.value
            val windowFocused = LocalWindowInfo.current.isWindowFocused
            SideEffect { composedState = incoming; composedWindowFocused = windowFocused }
            MeshCoreTheme(motionScale = 1f) {
                SyncingPillOverlay(incoming, { taps++ }) { Text("Fixture content") }
            }
        }
        resize(360)
        compose.runOnIdle { assertTrue(composedWindowFocused) }
        compose.mainClock.autoAdvance = false
        val schedulingStart = compose.mainClock.currentTime
        compose.runOnIdle { state.value = StatusPillState.Syncing }
        nativeFrame()
        compose.runOnIdle { assertEquals(StatusPillState.Syncing, composedState) }
        nativeFrame()
        nativeFrame()
        compose.waitForIdle()
        assertEquals(48L, compose.mainClock.currentTime - schedulingStart)
        compose.onNodeWithText(resources.getString(L.commonStatusSyncing)).assertExists()
        println("WP304_FIRST_VISIBLE_FRAME|frames=3|animationPlaytimeMillis=16|state=Syncing")
        compose.mainClock.advanceTimeBy(320)
        compose.runOnIdle { state.value = StatusPillState.Disconnected }
        nativeFrame()
        compose.onNodeWithText(resources.getString(L.commonStatusDisconnected)).performClick()
        compose.runOnIdle { assertEquals(1, taps); state.value = StatusPillState.Hidden }
        nativeFrame()
        compose.onNodeWithText(resources.getString(L.commonStatusDisconnected)).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(320)
        compose.mainClock.autoAdvance = true
    }

    private fun capture(id: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = if (dialog) assertNotNull(assertNotNull(ShadowDialog.getLatestDialog()).window).decorView
                else controller.get().window.decorView
            assertTrue(view.width > 0 && view.height > 0)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue(pixels.toSet().size > 16, "Blank/decorative bitmap is not shared UI evidence")
        val bytes = ByteArrayOutputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray()
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val directory = File(requireNotNull(System.getProperty("sharedUiArtifactDirectory")))
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$id.png").writeBytes(bytes)
        println("WP304_PNG|$id|${bitmap.width}|${bitmap.height}|$sha|${Base64.getEncoder().encodeToString(bytes)}")
    }
}
