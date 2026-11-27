// AndroidOnly: WP-101 Prizm Mobile Observer settings page: account, the two switches, session counters and status.
package com.meshcoreone.android.feature.settings.prizm.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.settings.device.ui.ActionRow
import com.meshcoreone.android.feature.settings.device.ui.ConfirmDialog
import com.meshcoreone.android.feature.settings.device.ui.RowDivider
import com.meshcoreone.android.feature.settings.device.ui.SettingsScreenFrame
import com.meshcoreone.android.feature.settings.device.ui.SettingsSection
import com.meshcoreone.android.feature.settings.device.ui.SwitchRow
import com.meshcoreone.android.feature.settings.prizm.PrizmClaimStateHolder
import com.meshcoreone.android.feature.settings.prizm.PrizmCounters
import com.meshcoreone.android.feature.settings.prizm.PrizmNotice
import com.meshcoreone.android.feature.settings.prizm.PrizmObserverState
import com.meshcoreone.android.feature.settings.prizm.PrizmObserverStateHolder

/** Settings > Prizm Observer. Claim prompts for the connected node are drawn on top by [PrizmClaimDialogs]. */
@Composable
fun PrizmObserverScreen(
    holder: PrizmObserverStateHolder,
    claim: PrizmClaimStateHolder,
    onOpenPrivacy: () -> Unit,
    onDone: (() -> Unit)?,
) {
    val state by holder.state.collectAsState()
    val notice by holder.notice.collectAsState()
    val failure by holder.signInFailure.collectAsState()
    val busy by holder.busy.collectAsState()
    var confirmSignOut by remember { mutableStateOf(false) }
    SettingsScreenFrame(UiText.Resource(R.string.prizm_title), onDone) {
        Text(stringResource(R.string.prizm_intro), style = MaterialTheme.typography.bodyMedium)
        AccountSection(state, busy, holder::signIn) { confirmSignOut = true }
        SettingsSection(null, listOfNotNull(noticeText(notice, state.signedIn)?.let { UiText.Verbatim(it) })) {
            SwitchRow(
                UiText.Resource(R.string.prizm_upload_switch), state.uploadEnabled, enabled = true,
                onChange = holder::setUploadEnabled, description = UiText.Resource(R.string.prizm_upload_switch_desc),
            )
            RowDivider()
            SwitchRow(
                UiText.Resource(R.string.prizm_share_location_switch), state.shareLocation, enabled = true,
                onChange = holder::setShareLocation, description = UiText.Resource(R.string.prizm_share_location_desc),
            )
        }
        StatusSection(state)
        if (state.node != null && state.signedIn) {
            SettingsSection(null) { ActionRow(UiText.Resource(R.string.prizm_claim_this_node), true, claim::accept) }
        }
        SettingsSection(null) { ActionRow(UiText.Resource(R.string.prizm_privacy_row), true, onOpenPrivacy) }
    }
    failure?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = holder::clearFailure,
            text = { Text(stringResource(R.string.prizm_sign_in_failed, message)) },
            confirmButton = {
                androidx.compose.material3.TextButton(holder::clearFailure, Modifier.padding(4.dp)) { Text(stringResource(R.string.prizm_dismiss)) }
            },
        )
    }
    ConfirmDialog(
        confirmSignOut, UiText.Resource(R.string.prizm_sign_out_title), UiText.Resource(R.string.prizm_sign_out_message),
        UiText.Resource(R.string.prizm_sign_out), onConfirm = { confirmSignOut = false; holder.signOut() }, onDismiss = { confirmSignOut = false },
    )
    PrizmClaimDialogs(state, claim)
}

@Composable
private fun AccountSection(state: PrizmObserverState, busy: Boolean, onSignIn: () -> Unit, onSignOut: () -> Unit) {
    val footers = buildList {
        if (!state.signedIn) add(UiText.Resource(R.string.prizm_signed_out_footer))
        if (state.standIn) add(UiText.Resource(R.string.prizm_stand_in_notice))
    }
    SettingsSection(UiText.Resource(R.string.prizm_account_header), footers) {
        val name = state.signedInAs
        if (name == null) {
            ActionRow(UiText.Resource(R.string.prizm_sign_in), !busy, onSignIn)
        } else {
            val signedIn = stringResource(R.string.prizm_signed_in_as, name)
            Text(signedIn, Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics { contentDescription = signedIn })
            RowDivider()
            ActionRow(UiText.Resource(R.string.prizm_sign_out), true, onSignOut, destructive = true)
        }
    }
}

@Composable
private fun StatusSection(state: PrizmObserverState) {
    val summary = when {
        !state.uploadEnabled -> stringResource(R.string.prizm_status_off)
        state.gateText != null -> state.gateText
        !state.running -> stringResource(R.string.prizm_status_waiting_node)
        else -> stringResource(R.string.prizm_status_uploading)
    }
    SettingsSection(UiText.Resource(R.string.prizm_status_header), listOf(UiText.Resource(R.string.prizm_counters_footer))) {
        Text(summary, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
        RowDivider()
        CounterRows(state.counters)
    }
}

@Composable
private fun CounterRows(counters: PrizmCounters) {
    CounterRow(R.string.prizm_counter_heard, counters.heard)
    CounterRow(R.string.prizm_counter_uploaded, counters.uploaded)
    CounterRow(R.string.prizm_counter_metadata_only, counters.metadataOnly)
    CounterRow(R.string.prizm_counter_queued, counters.queued)
}

@Composable
private fun CounterRow(label: Int, value: Int) {
    val name = stringResource(label)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) { contentDescription = "$name: $value" },
    ) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value.toString(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun noticeText(notice: PrizmNotice?, signedIn: Boolean): String? = when (notice) {
    PrizmNotice.NEEDS_SIGN_IN -> stringResource(R.string.prizm_needs_sign_in)
    PrizmNotice.NEEDS_PERMISSION -> stringResource(R.string.prizm_needs_permission)
    PrizmNotice.NEEDS_LOCATION_PERMISSION -> stringResource(R.string.prizm_needs_location_permission)
    PrizmNotice.SIGN_IN_WAITING -> if (signedIn) null else stringResource(R.string.prizm_sign_in_waiting)
    null -> null
}
