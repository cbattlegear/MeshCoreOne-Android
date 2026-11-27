// AndroidOnly: WP-101 Prizm Mobile Observer claim dialogs: prompt, node-type picker, takeover confirmation and result.
package com.meshcoreone.android.feature.settings.prizm.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.feature.settings.prizm.PrizmClaimPhase
import com.meshcoreone.android.feature.settings.prizm.PrizmClaimResult
import com.meshcoreone.android.feature.settings.prizm.PrizmClaimStateHolder
import com.meshcoreone.android.feature.settings.prizm.PrizmNodeType
import com.meshcoreone.android.feature.settings.prizm.PrizmObserverState

/** Shows whichever claim dialog matches the holder's phase; the first prompt appears while [PrizmObserverState.claimPending]. */
@Composable
fun PrizmClaimDialogs(state: PrizmObserverState, claim: PrizmClaimStateHolder) {
    val phase by claim.phase.collectAsState()
    val node = state.node
    when (val current = phase) {
        PrizmClaimPhase.Idle -> if (state.claimPending && node != null) {
            AlertDialog(
                onDismissRequest = claim::notNow,
                title = { Text(stringResource(R.string.prizm_claim_title)) },
                text = { Text(stringResource(R.string.prizm_claim_message, node.name, node.model)) },
                confirmButton = { TextButton(claim::accept, Modifier.sharedTouchTarget()) { Text(stringResource(R.string.prizm_claim_action)) } },
                dismissButton = { TextButton(claim::notNow, Modifier.sharedTouchTarget()) { Text(stringResource(R.string.prizm_claim_not_now)) } },
            )
        }
        PrizmClaimPhase.PickType -> AlertDialog(
            onDismissRequest = claim::dismiss,
            title = { Text(stringResource(R.string.prizm_claim_type_title)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    for (type in PrizmNodeType.entries) {
                        val label = stringResource(typeLabel(type))
                        Text(
                            label,
                            Modifier.fillMaxWidth().sharedTouchTarget().clickable(role = Role.Button) { claim.choose(type) }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(claim::dismiss, Modifier.sharedTouchTarget()) { Text(stringResource(R.string.prizm_claim_not_now)) } },
        )
        is PrizmClaimPhase.Working -> AlertDialog(
            onDismissRequest = {},
            text = { Column { CircularProgressIndicator(Modifier.padding(bottom = 12.dp)); Text(stringResource(R.string.prizm_claim_working)) } },
            confirmButton = {},
        )
        is PrizmClaimPhase.ConfirmTakeover -> AlertDialog(
            onDismissRequest = claim::dismiss,
            title = { Text(stringResource(R.string.prizm_claim_takeover_title)) },
            text = {
                val owner = current.ownerLabel ?: stringResource(R.string.prizm_claim_takeover_unknown_owner)
                Text(stringResource(R.string.prizm_claim_takeover_message, owner))
            },
            confirmButton = { TextButton(claim::confirmTakeover, Modifier.sharedTouchTarget()) { Text(stringResource(R.string.prizm_claim_takeover_action)) } },
            dismissButton = { TextButton(claim::dismiss, Modifier.sharedTouchTarget()) { Text(stringResource(R.string.prizm_claim_not_now)) } },
        )
        is PrizmClaimPhase.Finished -> AlertDialog(
            onDismissRequest = claim::dismiss,
            text = { Text(resultText(current.result)) },
            confirmButton = { TextButton(claim::dismiss, Modifier.sharedTouchTarget()) { Text(stringResource(R.string.prizm_dismiss)) } },
        )
    }
}

private fun typeLabel(type: PrizmNodeType): Int = when (type) {
    PrizmNodeType.HANDHELD -> R.string.prizm_claim_type_handheld
    PrizmNodeType.VEHICLE -> R.string.prizm_claim_type_vehicle
    PrizmNodeType.HOME_BASE -> R.string.prizm_claim_type_home_base
    PrizmNodeType.PORTABLE -> R.string.prizm_claim_type_portable
}

@Composable
private fun resultText(result: PrizmClaimResult): String = when (result) {
    PrizmClaimResult.Linked -> stringResource(R.string.prizm_claim_linked)
    PrizmClaimResult.InfoUpdated -> stringResource(R.string.prizm_claim_info_updated)
    PrizmClaimResult.Expired -> stringResource(R.string.prizm_claim_expired)
    is PrizmClaimResult.Rejected -> stringResource(R.string.prizm_claim_rejected, result.reason)
    PrizmClaimResult.SigningFailed -> stringResource(R.string.prizm_claim_signing_failed)
    is PrizmClaimResult.NetworkFailed -> stringResource(R.string.prizm_claim_network_failed, result.message)
    is PrizmClaimResult.NeedsConfirmation -> stringResource(R.string.prizm_claim_takeover_message, result.ownerLabel.orEmpty())
}
