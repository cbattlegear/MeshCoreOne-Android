// AndroidOnly: WP-309 Localized non-color-only notice shared by standalone and App consumers.
package com.meshcoreone.android.feature.chats.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.contracts.domain.DirectAckRadioConfirmationEvidence
import com.meshcoreone.android.core.l10n.R

/**
 * Standalone route only: the active radio graph must own and start one shared monitor.
 * Screen recreation/parallel routes must not allocate another storage observer.
 */
@Composable
fun DirectAckConfirmationNoticeRoute(
    observation: DirectAckConfirmationObservation,
    holder: DirectAckConfirmationStateHolder,
    modifier: Modifier = Modifier,
) {
    val state by holder.state.collectAsStateWithLifecycle()
    // Never expose another radio/session/epoch's previous snapshot while its owner is being replaced.
    val current = state.takeIf { it.observation == observation } ?: DirectAckConfirmationState(
        observation, DirectAckConfirmationStatus.NotInspected,
    )
    DirectAckConfirmationNotice(
        current,
        onCheckAgain = {
            if (holder.state.value.observation == observation) holder.recheck()
        },
        modifier = modifier,
    )
}

@Composable
fun DirectAckConfirmationNotice(
    state: DirectAckConfirmationState,
    onCheckAgain: () -> Unit,
    modifier: Modifier = Modifier,
    additionalUncertainty: Boolean = false,
    messageDiagnosticsUnavailable: Boolean = false,
) {
    val body = when (val status = state.status) {
        DirectAckConfirmationStatus.NotInspected,
        DirectAckConfirmationStatus.Interrupted -> R.string.direct_ack_confirmation_interrupted
        DirectAckConfirmationStatus.Checking -> {
            // Keep the explicitly historical, non-authorizing wording stable during background polls.
            if (state.lastEvidence is DirectAckRadioConfirmationEvidence.NoPersistedWildcard) {
                R.string.direct_ack_confirmation_checked
            } else R.string.direct_ack_confirmation_checking
        }
        DirectAckConfirmationStatus.CapabilityUnavailable -> R.string.direct_ack_confirmation_missing
        is DirectAckConfirmationStatus.ReadUnavailable,
        is DirectAckConfirmationStatus.RadioMismatch,
        DirectAckConfirmationStatus.StreamEnded,
        DirectAckConfirmationStatus.UnexpectedUnavailable -> R.string.direct_ack_confirmation_unavailable
        is DirectAckConfirmationStatus.Inspected -> when (status.evidence) {
            is DirectAckRadioConfirmationEvidence.PersistedWildcard -> R.string.direct_ack_confirmation_uncertain
            is DirectAckRadioConfirmationEvidence.NoPersistedWildcard -> R.string.direct_ack_confirmation_checked
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.direct_ack_confirmation_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            val historicalWarning = (state.lastKnownPersistedWildcard != null || additionalUncertainty) &&
                body != R.string.direct_ack_confirmation_uncertain
            Text(
                stringResource(if (historicalWarning) R.string.direct_ack_confirmation_uncertain else body),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (historicalWarning && state.status != DirectAckConfirmationStatus.Checking) {
                Text(
                    stringResource(body),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (messageDiagnosticsUnavailable) {
                Text(
                    stringResource(R.string.direct_ack_confirmation_message_evidence_unavailable),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (state.observation != null && state.isActive &&
                (state.status != DirectAckConfirmationStatus.Checking || state.lastEvidence != null) &&
                state.status != DirectAckConfirmationStatus.CapabilityUnavailable
            ) {
                TextButton(onClick = onCheckAgain) {
                    Text(stringResource(R.string.direct_ack_confirmation_check_again))
                }
            }
        }
    }
}
