// AndroidOnly: WP-407 API-boundary permission state regressions under Robolectric, not device/API acceptance.
package com.meshcoreone.android.app.platform

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.onboarding.AndroidOnboardingPermissionPort
import com.meshcoreone.android.app.container.onboarding.FakePermissionFacts
import com.meshcoreone.android.app.container.onboarding.RequestedPermissions
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionKind
import com.meshcoreone.android.feature.onboarding.PermissionStatus
import com.meshcoreone.android.feature.settings.device.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33, 37], application = Application::class)
class PermissionStateTest {
    @Test fun grantedThenRevokedCoarseLocationHasAnHonestDeniedState() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("platform_permission_requests", Context.MODE_PRIVATE).edit().clear().commit()
        val permission = SettingsPermissions(app, { error("No notification prompt expected") }, { error("No location prompt expected") }, RequestedPermissions())
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        permission.refresh()
        assertEquals(LocationAuthorization.AUTHORIZED, permission.location.authorization.value)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        permission.refresh()
        assertEquals(LocationAuthorization.DENIED, permission.location.authorization.value)
        permission.close()
    }

    @Test fun notificationDenialAndRevocationDoNotLaunchLocationOrConnectAnything() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("platform_permission_requests", Context.MODE_PRIVATE).edit().clear().commit()
        var notificationPrompts = 0
        val permission = SettingsPermissions(app, { notificationPrompts++ }, { error("Notification request must not request location") }, RequestedPermissions())
        if (Build.VERSION.SDK_INT >= 33) {
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            assertEquals(NotificationAuthorization.AUTHORIZED, permission.notifications.status())
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            assertEquals(NotificationAuthorization.DENIED, permission.notifications.status())
        }
        assertEquals(0, notificationPrompts)
        permission.close()
    }

    @Test @Config(sdk = [33])
    fun notificationHistoryIsSharedAcrossSettingsAndOnboardingAndCancellationDoesNotDeny() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val history = RequestedPermissions()
        val onboarding = AndroidOnboardingPermissionPort(FakePermissionFacts(sdk = 33), history, false)
        var prompts = 0
        val settings = SettingsPermissions(app, { prompts++ }, { error("Unexpected location prompt") }, history)
        assertEquals(NotificationAuthorization.NOT_DETERMINED, settings.notifications.status())
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { settings.notifications.request() }
        settings.notificationResult(emptyMap())
        yield()
        assertTrue(cancelled.isCancelled)
        assertEquals(NotificationAuthorization.NOT_DETERMINED, settings.notifications.status())
        onboarding.refresh()
        assertEquals(PermissionStatus.NOT_DETERMINED, onboarding.snapshot.value.notifications)

        val denied = async(start = CoroutineStart.UNDISPATCHED) { settings.notifications.request() }
        settings.notificationResult(mapOf(Manifest.permission.POST_NOTIFICATIONS to false))
        assertFalse(denied.await())
        assertEquals(2, prompts)
        onboarding.refresh()
        assertEquals(PermissionStatus.DENIED, onboarding.snapshot.value.notifications)
        assertEquals(NotificationAuthorization.DENIED, settings.notifications.status())
        settings.close()

        val otherHistory = RequestedPermissions()
        val otherOnboarding = AndroidOnboardingPermissionPort(FakePermissionFacts(sdk = 33), otherHistory, false)
        otherOnboarding.onRequestFinished(OnboardingPermissionKind.NOTIFICATIONS)
        val otherSettings = SettingsPermissions(app, { error("Unexpected prompt") }, { error("Unexpected location prompt") }, otherHistory)
        assertEquals(NotificationAuthorization.DENIED, otherSettings.notifications.status())
        otherSettings.close()
    }
}
