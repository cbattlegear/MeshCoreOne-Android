// AndroidOnly: WP-303 Present the real connection/UI state beneath the adaptive shell's toolbar.
package com.meshcoreone.android.app.container

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.app.container.onboarding.DeviceMenuTipPresenter
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.ui.SharedTip
import com.meshcoreone.android.core.ui.SharedTipContent
import com.meshcoreone.android.core.ui.SyncingPillActions
import com.meshcoreone.android.core.ui.SyncingPillView
import com.meshcoreone.android.core.ui.TipHostState
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.uiString

@Composable
fun AppConnectionStatus(
    container: AppContainer,
    selectedTab: AppTab,
    tipHostVisible: Boolean,
    onDisconnectedTap: () -> Unit,
) {
    val ui by container.appState.connectionUI.state.collectAsStateWithLifecycle()
    val connection by container.connectionManager.snapshot.collectAsStateWithLifecycle()
    val presenter = checkNotNull(container.deviceMenuTipPresenter) {
        "The production app must bind its process-backed device-menu tip store"
    }
    val tipHost = TipHostState(
        selectedTab = selectedTab,
        deviceMenuVisible = tipHostVisible,
        connectedDevicePresent = container.connectionManager.connectedDevice != null,
        liveStatusPresented = false,
        overlayQuiescent = tipHostVisible,
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
            SyncingPillView(
                container.appState.statusPillState(ui, connection.state),
                SyncingPillActions(onDisconnectedTap),
            )
        }
        AppDeviceMenuTipHost(presenter, tipHost)
    }
}

@Composable
internal fun AppDeviceMenuTipHost(presenter: DeviceMenuTipPresenter, host: TipHostState) {
    val presentation by presenter.presentation.collectAsStateWithLifecycle()
    LaunchedEffect(presenter, host) { presenter.consider(host) }
    if (!host.eligible(SharedTip.DEVICE_MENU)) return

    if (presentation.visible) {
        SharedTipContent(
            SharedTip.DEVICE_MENU,
            onDismiss = presenter::dismiss,
            modifier = Modifier.testTag("device-menu-tip"),
        )
    }
    presentation.failure?.let { failure ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(uiString(failure), Modifier.weight(1f))
            TextButton(
                onClick = { presenter.retry() },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(uiString(UiText.Resource(S.alertRetryRetry)))
            }
        }
    }
}
