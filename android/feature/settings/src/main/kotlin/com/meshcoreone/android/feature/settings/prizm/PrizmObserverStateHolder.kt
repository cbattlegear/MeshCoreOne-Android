// AndroidOnly: WP-101 Prizm Mobile Observer main-screen state: switches, sign-in and permission gating.
package com.meshcoreone.android.feature.settings.prizm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A one-line hint under the switches; the screen maps each to a string. */
enum class PrizmNotice { NEEDS_SIGN_IN, NEEDS_PERMISSION, NEEDS_LOCATION_PERMISSION, SIGN_IN_WAITING }

/**
 * Screen logic for the Prizm Observer page. The port owns the truth (settings, session, counters); this class only decides
 * what a tap is allowed to do: uploads need a signed-in user and the notification and precise-location permissions (the
 * geofence uses the real position even when sharing is off), sharing needs only the location permission.
 */
class PrizmObserverStateHolder(
    private val port: PrizmObserverPort,
    private val scope: CoroutineScope,
    private val permissions: PrizmPermissionRequester,
) {
    val state: StateFlow<PrizmObserverState> get() = port.state
    private val noticeState = MutableStateFlow<PrizmNotice?>(null)
    private val failureState = MutableStateFlow<String?>(null)
    private val busyState = MutableStateFlow(false)
    val notice: StateFlow<PrizmNotice?> = noticeState.asStateFlow()
    val signInFailure: StateFlow<String?> = failureState.asStateFlow()
    val busy: StateFlow<Boolean> = busyState.asStateFlow()

    fun setUploadEnabled(enabled: Boolean) {
        noticeState.value = null
        if (!enabled) { port.setUploadEnabled(false); return }
        if (!state.value.signedIn) { noticeState.value = PrizmNotice.NEEDS_SIGN_IN; return }
        permissions.request(needsLocation = true, needsNotifications = true) { granted ->
            if (granted) port.setUploadEnabled(true) else noticeState.value = PrizmNotice.NEEDS_PERMISSION
        }
    }

    fun setShareLocation(enabled: Boolean) {
        noticeState.value = null
        if (!enabled) { port.setShareLocation(false); return }
        permissions.request(needsLocation = true, needsNotifications = false) { granted ->
            if (granted) port.setShareLocation(true) else noticeState.value = PrizmNotice.NEEDS_LOCATION_PERMISSION
        }
    }

    fun signIn() {
        if (busyState.value) return
        busyState.value = true
        failureState.value = null
        noticeState.value = null
        scope.launch {
            try {
                when (val result = port.signIn()) {
                    PrizmSignInResult.BrowserOpened -> noticeState.value = PrizmNotice.SIGN_IN_WAITING
                    PrizmSignInResult.SignedIn -> Unit
                    is PrizmSignInResult.Failed -> failureState.value = result.message
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failureState.value = error.message ?: error.javaClass.simpleName
            } finally {
                busyState.value = false
            }
        }
    }

    fun signOut() {
        scope.launch {
            try { port.signOut() } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
                failureState.value = error.message ?: error.javaClass.simpleName
            }
        }
    }

    fun clearFailure() { failureState.value = null }
}
