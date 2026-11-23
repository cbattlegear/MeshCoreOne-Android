// AndroidOnly: WP-403 API 31-37 registration, redaction cache and bounded bitmap assertions.
package com.meshcoreone.android.platform.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.UserManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class WidgetPlatformTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [31])
    fun api31ComponentsAreOptionalAndRequireOnlyPlatformBindings() {
        assertComponents()
    }

    @Test
    fun api31Through37UseTheSameStatusAndRequestContract() {
        (31..33).forEach { sdk ->
            assertFalse("sdk=$sdk", WidgetPlatformPolicy.usesPendingIntentTileLaunch(sdk))
        }
        (34..37).forEach { sdk ->
            assertTrue("sdk=$sdk", WidgetPlatformPolicy.usesPendingIntentTileLaunch(sdk))
        }
    }

    @Test
    @Config(sdk = [31])
    fun cachedStatusContainsNoRadioNameIdentityOrMessageContent() {
        val view = WidgetConnectionView(
            status = WidgetRadioStatus.READY,
            hasSavedDevice = true,
            reconnecting = false,
            permissionDenied = false,
        )
        WidgetStatusStore(context).write(view.copy(metrics = WidgetRadioMetrics(71, 8, 2)))

        assertEquals(view, WidgetStatusStore(context).read())
        val raw = context.getSharedPreferences("mc1.widget-status", Context.MODE_PRIVATE).all.toString()
        assertFalse(raw.contains("name", ignoreCase = true))
        assertFalse(raw.contains("radio", ignoreCase = true))
        assertFalse(raw.contains("message", ignoreCase = true))
    }

    @Test
    @Config(sdk = [31, 37])
    fun localizedMetricsRequireAHomeHostAndEnoughSpaceAtCurrentFontScale() {
        val french = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.FRENCH)
            fontScale = 1f
        })
        val home = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
        val view = WidgetConnectionView(WidgetRadioStatus.READY, true, metrics = WidgetRadioMetrics(0, 0, 2))
        assertEquals(listOf("Batterie 0 pour cent", "0 paquets par minute", "2 messages non lus"),
            widgetMetricLines(french, view, home, 240f, 160f))
        assertEquals(listOf("0 paquets par minute"), widgetMetricLines(
            french, view.copy(metrics = WidgetRadioMetrics(null, 0, null)), home, 240f, 160f,
        ))
        assertTrue(widgetMetricLines(french, view, AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD, 240f, 160f).isEmpty())
        assertTrue(widgetMetricLines(french, view, 0, 240f, 160f).isEmpty())
        assertTrue(widgetMetricLines(french, view.copy(status = WidgetRadioStatus.DISCONNECTED), home, 240f, 160f).isEmpty())
        assertTrue(widgetMetricLines(french, view, home, 110f, 70f).isEmpty())
        val largeText = context.createConfigurationContext(Configuration(french.resources.configuration).apply { fontScale = 2f })
        assertTrue(widgetMetricLines(largeText, view, home, 240f, 160f).isEmpty())
    }

    @Test
    @Config(sdk = [31], qualifiers = "en-rUS-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @OptIn(ExperimentalCoroutinesApi::class)
    fun widgetRemoteViewsStackLongMetricsAt220DpAndDoNotRehydrateCachedReady() = runTest {
        val longRate = "123456789012345678 packets per minute"
        val ready = WidgetConnectionView(
            WidgetRadioStatus.READY, false, metrics = WidgetRadioMetrics(71, 123456789012345678L, 2),
        )
        WidgetStatusStore(context).write(ready)
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
        }
        fun View.labels(): List<TextView> = when (this) {
            is TextView -> listOf(this)
            is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).labels() }
            else -> emptyList()
        }
        suspend fun rendered(): FrameLayout {
            val host = FrameLayout(context)
            val remoteViews = MeshStatusWidget().compose(context, options = options, size = DpSize(220.dp, 180.dp))
            host.addView(remoteViews.apply(context, host))
            host.measure(
                View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY),
            )
            host.layout(0, 0, 220, 180)
            return host
        }

        assertFalse(rendered().labels().any { it.text.toString() == longRate })
        val state = MutableStateFlow(ready)
        try {
            WidgetConnectionBridge.install(context, backgroundScope, object : WidgetConnectionOwner {
                override val status = state
                override suspend fun requestConnection(origin: ConnectionRequestOrigin) = ConnectionRequestResult.AlreadyActive
            })
            runCurrent()
            val host = rendered()
            val labels = host.labels()
            val metrics = listOf("Battery 71 percent", longRate, "2 unread messages").map { label ->
                labels.single { it.text.toString() == label }
            }
            val rateLayout = checkNotNull(metrics[1].layout)
            assertTrue("Long rate must wrap", rateLayout.lineCount > 1)
            assertEquals(longRate.length, rateLayout.getLineEnd(rateLayout.lineCount - 1))
            assertTrue((0 until rateLayout.lineCount).all { rateLayout.getEllipsisCount(it) == 0 })
            fun bounds(label: TextView): Rect = Rect(0, 0, label.width, label.height).also {
                host.offsetDescendantRectToMyCoords(label, it)
            }
            val boxes = metrics.map(::bounds)
            val header = labels.single { it.text.toString() == host.context.getString(
                com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonStatusReady,
            ) }
            assertTrue(bounds(header).bottom <= boxes.first().top)
            assertTrue(boxes.zipWithNext().all { (above, below) -> above.bottom <= below.top && above.left == below.left })
            assertTrue(boxes.all { !it.isEmpty && Rect(0, 0, 220, 180).contains(it) })
            state.value = ready.copy(metrics = WidgetRadioMetrics(null, 0, 0))
            runCurrent()
            val updated = rendered().labels().map { it.text.toString() }
            assertTrue(updated.contains("0 packets per minute"))
            assertFalse(updated.contains("Battery 71 percent"))
        } finally {
            WidgetConnectionBridge.resetForTesting()
        }
    }

    @Test
    @Config(sdk = [31])
    fun preUnlockReadIsRedactedAndDoesNotExposeCredentialCache() {
        WidgetStatusStore(context).write(WidgetConnectionView(WidgetRadioStatus.READY, hasSavedDevice = true))
        Shadows.shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)

        assertEquals(
            WidgetConnectionView(WidgetRadioStatus.DISCONNECTED, hasSavedDevice = false),
            WidgetStatusStore(context).read(),
        )
    }

    @Test
    @Config(sdk = [31])
    fun everyRemoteViewsBitmapIsFixedSizeAndArgb8888() {
        WidgetRadioStatus.entries.forEach { status ->
            val bitmap = StatusBitmap.render(status)
            assertEquals(StatusBitmap.EDGE_PX, bitmap.width)
            assertEquals(StatusBitmap.EDGE_PX, bitmap.height)
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
            assertTrue(bitmap.byteCount <= StatusBitmap.EDGE_PX * StatusBitmap.EDGE_PX * 4)
        }
    }

    private fun assertComponents() {
        @Suppress("DEPRECATION")
        val receiver = context.packageManager.getReceiverInfo(
            ComponentName(context, MeshStatusWidgetReceiver::class.java),
            PackageManager.GET_META_DATA,
        )
        @Suppress("DEPRECATION")
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, MeshConnectionTileService::class.java),
            PackageManager.GET_META_DATA,
        )
        assertTrue(receiver.directBootAware)
        assertTrue(receiver.exported)
        assertTrue(service.directBootAware)
        assertTrue(service.exported)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", service.permission)
        assertNotNull(service.metaData)
        @Suppress("DEPRECATION")
        val requestedPermissions = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty().toSet()
        assertFalse("android.permission.RECEIVE_BOOT_COMPLETED" in requestedPermissions)
        assertFalse("android.permission.WAKE_LOCK" in requestedPermissions)
    }
}
