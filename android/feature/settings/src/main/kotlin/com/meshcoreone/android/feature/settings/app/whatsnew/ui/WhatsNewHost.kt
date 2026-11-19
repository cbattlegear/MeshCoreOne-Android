// AndroidOnly: WP-318 mounted host that presents WhatsNewState.pendingRelease once the connection UI is quiescent (Swift ContentView sheet binding).
package com.meshcoreone.android.feature.settings.app.whatsnew.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.meshcoreone.android.feature.settings.app.whatsnew.WhatsNewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Mounts the What's New sheet over the app content. While [connectionUiQuiescent] is false (a connection alert or
 * scan picker is presenting) the pending release stays set and re-presents once it clears. Continue, Support and
 * Back persist through [WhatsNewState.markShown]; a failed write preserves pending and reports to [onStorageFailure].
 */
@Composable
fun WhatsNewHost(state: WhatsNewState, connectionUiQuiescent: Boolean, onSupport: () -> Unit, onStorageFailure: (Throwable) -> Unit) {
    val release by state.pendingRelease.collectAsState()
    val scope = rememberCoroutineScope()
    val pending = release
    if (connectionUiQuiescent && pending != null) {
        WhatsNewSheet(
            release = pending,
            onSupport = onSupport,
            onDismiss = {
                scope.launch {
                    try {
                        state.markShown()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        onStorageFailure(failure)
                    }
                }
            },
        )
    }
}
