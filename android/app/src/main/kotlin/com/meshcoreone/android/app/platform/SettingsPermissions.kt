// AndroidOnly: WP-407 Activity-owned permission prompts; no radio session is created by a platform permission.
package com.meshcoreone.android.app.platform

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.meshcoreone.android.app.container.onboarding.RequestedPermissions
import com.meshcoreone.android.feature.settings.device.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

internal class SettingsPermissions(
    private val context: Context,
    private val launchNotifications: () -> Unit,
    private val launchLocation: () -> Unit,
    private val permissionHistory: RequestedPermissions,
) {
    private var notificationAnswer: CompletableDeferred<Boolean>? = null
    private var locationAnswer: CompletableDeferred<Boolean>? = null
    private val locationState = MutableStateFlow(LocationAuthorization.NOT_DETERMINED)
    private val requesting = MutableStateFlow(false)

    val notifications = object : NotificationPermissionPort {
        override suspend fun status(): NotificationAuthorization {
            val enabled = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
            val granted = Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT >= 33 && granted) permissionHistory.record(listOf(Manifest.permission.POST_NOTIFICATIONS))
            return when {
                enabled && granted -> NotificationAuthorization.AUTHORIZED
                granted || Build.VERSION.SDK_INT < 33 || permissionHistory.contains(Manifest.permission.POST_NOTIFICATIONS) -> NotificationAuthorization.DENIED
                else -> NotificationAuthorization.NOT_DETERMINED
            }
        }
        override suspend fun request(): Boolean {
            if (Build.VERSION.SDK_INT < 33) return status() == NotificationAuthorization.AUTHORIZED
            notificationAnswer?.let { return it.await() }
            val answer = CompletableDeferred<Boolean>()
            notificationAnswer = answer
            try { launchNotifications() }
            catch (failure: RuntimeException) {
                notificationAnswer = null
                answer.completeExceptionally(failure)
                throw failure
            }
            return answer.await()
        }
    }
    val location = object : LocationPermissionPort {
        override val authorization = locationState
        override val isRequestingLocation = requesting
        override fun requestPermissionIfNeeded() {
            refresh()
            if (locationState.value == LocationAuthorization.NOT_DETERMINED && !requesting.value) {
                locationAnswer = CompletableDeferred()
                requesting.value = true
                try { launchLocation() }
                catch (failure: RuntimeException) {
                    requesting.value = false
                    locationAnswer?.completeExceptionally(failure)
                    locationAnswer = null
                    throw failure
                }
            }
        }
        override suspend fun requestCurrentLocation() {
            requestPermissionIfNeeded()
            locationAnswer?.await()
            refresh()
        }
    }
    fun refresh() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            permissionHistory.record(listOf(Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        locationState.value = when {
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ->
                LocationAuthorization.AUTHORIZED
            permissionHistory.contains(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationAuthorization.DENIED
            else -> LocationAuthorization.NOT_DETERMINED
        }
    }
    fun notificationResult(grants: Map<String, Boolean>) {
        val answer = notificationAnswer
        notificationAnswer = null
        val granted = grants[Manifest.permission.POST_NOTIFICATIONS]
        if (granted == null) answer?.cancel()
        else {
            permissionHistory.record(listOf(Manifest.permission.POST_NOTIFICATIONS))
            answer?.complete(granted)
        }
    }
    fun locationResult(grants: Map<String, Boolean>) {
        val answer = locationAnswer
        locationAnswer = null
        requesting.value = false
        val granted = grants[Manifest.permission.ACCESS_COARSE_LOCATION]
        if (granted == null) answer?.cancel()
        else {
            permissionHistory.record(listOf(Manifest.permission.ACCESS_COARSE_LOCATION))
            answer?.complete(granted)
        }
        refresh()
    }
    fun close() {
        notificationAnswer?.cancel(); notificationAnswer = null
        locationAnswer?.cancel(); locationAnswer = null
        requesting.value = false
    }
}

private class SettingsPermissionLaunchers {
    var notifications: () -> Unit = { error("Notification permission launcher is not ready") }
    var location: () -> Unit = { error("Location permission launcher is not ready") }
}

@Composable
internal fun rememberSettingsPermissions(permissionHistory: RequestedPermissions): SettingsPermissions {
    val context = LocalContext.current
    val launchers = remember { SettingsPermissionLaunchers() }
    val result = remember(context, launchers, permissionHistory) {
        SettingsPermissions(context, { launchers.notifications() }, { launchers.location() }, permissionHistory)
    }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result.notificationResult(it) }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result.locationResult(it) }
    SideEffect {
        launchers.notifications = { notification.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
        launchers.location = { location.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)) }
    }
    LifecycleResumeEffect(result) { result.refresh(); onPauseOrDispose {} }
    DisposableEffect(result) { onDispose { result.close() } }
    return result
}
