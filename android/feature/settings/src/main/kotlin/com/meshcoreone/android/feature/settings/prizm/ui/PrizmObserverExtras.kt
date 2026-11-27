// AndroidOnly: WP-101 Prizm Mobile Observer settings extras: stop claiming a node and the debug-only server options.
package com.meshcoreone.android.feature.settings.prizm.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.settings.device.ui.ActionRow
import com.meshcoreone.android.feature.settings.device.ui.ConfirmDialog
import com.meshcoreone.android.feature.settings.device.ui.RowDivider
import com.meshcoreone.android.feature.settings.device.ui.SettingsSection
import com.meshcoreone.android.feature.settings.device.ui.SwitchRow
import com.meshcoreone.android.feature.settings.prizm.PrizmBaseUrlResult
import com.meshcoreone.android.feature.settings.prizm.PrizmObserverState
import com.meshcoreone.android.feature.settings.prizm.PrizmObserverStateHolder
import com.meshcoreone.android.feature.settings.prizm.PrizmRemoveResult
import com.meshcoreone.android.feature.settings.prizm.PrizmRemoveStatus

/** "Stop claiming this node" with its confirmation and result dialog; only shown for a claimed, connected node. */
@Composable
internal fun RemoveClaimSection(state: PrizmObserverState, holder: PrizmObserverStateHolder) {
    val status by holder.remove.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    if (state.node != null && state.signedIn && state.nodeClaimed) {
        SettingsSection(null) {
            ActionRow(UiText.Resource(R.string.prizm_remove_claim), status != PrizmRemoveStatus.Working, { confirm = true }, destructive = true)
        }
    }
    ConfirmDialog(
        confirm, UiText.Resource(R.string.prizm_remove_claim_title), UiText.Resource(R.string.prizm_remove_claim_message),
        UiText.Resource(R.string.prizm_remove_claim_confirm), onConfirm = { confirm = false; holder.removeNodeClaim() }, onDismiss = { confirm = false },
    )
    (status as? PrizmRemoveStatus.Finished)?.let { finished ->
        AlertDialog(
            onDismissRequest = holder::acknowledgeRemove,
            text = {
                Text(
                    when (val result = finished.result) {
                        PrizmRemoveResult.Removed -> stringResource(R.string.prizm_remove_claim_done)
                        is PrizmRemoveResult.Failed -> stringResource(R.string.prizm_remove_claim_failed, result.message)
                    },
                )
            },
            confirmButton = { TextButton(holder::acknowledgeRemove, Modifier.padding(4.dp)) { Text(stringResource(R.string.prizm_dismiss)) } },
        )
    }
}

/** Debug builds only ([PrizmObserverState.debug] is null in release builds): beta server URL and the stand-in switch. */
@Composable
internal fun DebugSection(state: PrizmObserverState, holder: PrizmObserverStateHolder) {
    val debug = state.debug ?: return
    var text by remember(debug.serverBaseUrl, debug.overridden) { mutableStateOf(if (debug.overridden) debug.serverBaseUrl else "") }
    var message by remember { mutableStateOf<String?>(null) }
    val invalid = stringResource(R.string.prizm_debug_server_invalid)
    val reset = stringResource(R.string.prizm_debug_server_reset)
    val appliedTemplate = stringResource(R.string.prizm_debug_server_applied, "%s")
    SettingsSection(UiText.Resource(R.string.prizm_debug_header), listOf(UiText.Resource(R.string.prizm_debug_footer))) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it; message = null }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.prizm_debug_server_label)) }, placeholder = { Text(debug.serverBaseUrl) },
            )
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        ActionRow(UiText.Resource(R.string.prizm_debug_server_apply), true, {
            message = when (val result = holder.applyServerBaseUrl(text)) {
                is PrizmBaseUrlResult.Applied -> appliedTemplate.replace("%s", result.url)
                PrizmBaseUrlResult.Reset -> reset
                PrizmBaseUrlResult.Invalid -> invalid
            }
        })
        RowDivider()
        SwitchRow(
            UiText.Resource(R.string.prizm_debug_stand_in), debug.standIn, enabled = true, onChange = holder::setUseStandIn,
            description = UiText.Resource(R.string.prizm_debug_stand_in_desc),
        )
    }
}
