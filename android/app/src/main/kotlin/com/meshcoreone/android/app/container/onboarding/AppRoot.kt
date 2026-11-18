// AndroidOnly: WP-303 First-run gate: onboarding replaces the main shell until hasCompletedOnboarding is set (Swift ContentView rule).
package com.meshcoreone.android.app.container.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.app.navigation.OnboardingCompletionRecovery
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.feature.onboarding.OnboardingEntry

/**
 * First-run gate: an existing completed install opens the shell; an active flow stays mounted until its
 * exact durable completion publication has navigated through its retained entry and session witness.
 * Without [onboarding] (container not built yet, or construction failed) the shell is shown, never a fake flow.
 */
@Composable
fun OnboardingGate(
    coordinator: NavigationCoordinator,
    onboarding: AppOnboarding?,
    shell: @Composable (AppOnboarding?) -> Unit,
) {
    onboarding?.pairingState?.let { SystemPairingSetupHost(it) }
    val retention = remember(coordinator, onboarding) {
        onboarding?.let(coordinator::onboardingRetention)
    }
    val shellVisible = onboardingShellVisible(coordinator, onboarding)
    LaunchedEffect(onboarding, retention) {
        if (onboarding != null && retention != null) onboarding.observeNavigationEntries(retention)
    }
    if (shellVisible) {
        shell(onboarding)
    } else {
        checkNotNull(onboarding)
        checkNotNull(retention)
        val presented by coordinator.publishedNavigation.collectAsState()
        val entry by retention.firstRunEntry.collectAsState()
        val decisionEpoch by retention.firstRunDecisionEpoch.collectAsState()
        val revision by onboarding.flowCoordinatorRevision.collectAsState()
        val availability by onboarding.firstRunAvailabilityRevision.collectAsState()
        LaunchedEffect(retention, presented, revision, availability, decisionEpoch) {
            retention.mountFirstRun(presented, decisionEpoch)
        }
        val current = entry
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val recovery by current.recovery.collectAsState()
            OnboardingEntry(
                route = FeatureRoute(FeatureId.ONBOARDING),
                onNavigate = {},
                dependencies = current.owner,
                onCompletionPublication = { flow, completion, commit ->
                    retention.deliver(current, flow, completion, commit)
                },
                completionDeliveryKey = presented.revision to recovery.deliveryRevision,
                flowCoordinator = current.flow,
            )
            OnboardingCompletionRecovery(retention, current)
        }
    }
}

@Composable
internal fun onboardingShellVisible(coordinator: NavigationCoordinator, onboarding: AppOnboarding?): Boolean {
    if (onboarding == null) return true
    val retention = remember(coordinator, onboarding) { coordinator.onboardingRetention(onboarding) }
    val completed by onboarding.hasCompleted.collectAsState()
    val pending by retention.firstRunPending.collectAsState()
    val decisionEpoch by retention.firstRunDecisionEpoch.collectAsState()
    if (!completed) SideEffect { retention.requireFirstRun(decisionEpoch) }
    return completed && !pending
}

/** What the app root learned about onboarding bindings: [bindings] is null when none exist (shell only). */
class OnboardingResolution(val bindings: AppOnboarding?)
