// AndroidOnly: WP-403 API 31-37 registration, redaction cache and bounded bitmap assertions.
package com.meshcoreone.android.platform.widgets

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

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
        WidgetStatusStore(context).write(view)

        assertEquals(view, WidgetStatusStore(context).read())
        val raw = context.getSharedPreferences("mc1.widget-status", Context.MODE_PRIVATE).all.toString()
        assertFalse(raw.contains("name", ignoreCase = true))
        assertFalse(raw.contains("radio", ignoreCase = true))
        assertFalse(raw.contains("message", ignoreCase = true))
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
    }
}
