// PortedFrom: MC1/State/AppState.swift@db14559b39d32322b06477c6ae676112f583db50
// AndroidOnly: WP-303 Present the real connection/UI state beneath the adaptive shell's toolbar.
package com.meshcoreone.android.app.container

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.ui.SyncingPillActions
import com.meshcoreone.android.core.ui.SyncingPillView

@Composable
fun AppConnectionStatus(container: AppContainer, onDisconnectedTap: () -> Unit) {
    val ui by container.appState.connectionUI.state.collectAsStateWithLifecycle()
    val connection by container.connectionManager.snapshot.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        SyncingPillView(container.appState.statusPillState(ui, connection.state), SyncingPillActions(onDisconnectedTap))
    }
}
