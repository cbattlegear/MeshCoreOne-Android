// AndroidOnly: WP-305 Onboarding entry; the real flow runs only when the app binds OnboardingFeatureDependencies.
package com.meshcoreone.android.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.ui.FeatureShellCopy
import com.meshcoreone.android.core.ui.ScaffoldFeatureContent
import com.meshcoreone.android.feature.onboarding.ui.OnboardingFlow

/**
 * Without [dependencies] (the WP-303 adapters are not bound yet) this keeps the honest
 * "not yet ported" shell: no permission request, no pairing, never marks onboarding complete.
 * Completion navigates to the chats root.
 */
@Composable
fun OnboardingEntry(
    route: FeatureRoute,
    onNavigate: (FeatureRoute) -> Unit,
    dependencies: OnboardingFeatureDependencies? = null,
) {
    if (dependencies != null) {
        OnboardingFlow(dependencies, onCompleted = { onNavigate(FeatureRoute(FeatureId.CHATS)) })
        return
    }
    ScaffoldFeatureContent(
        FeatureId.ONBOARDING, route,
        FeatureShellCopy(
            stringResource(R.string.scaffold_onboarding_title),
            stringResource(R.string.scaffold_onboarding_description),
            stringResource(R.string.scaffold_connect_radio),
        ),
        onNavigate,
    )
}
