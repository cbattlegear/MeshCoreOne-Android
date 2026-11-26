// PortedFrom: MC1/Views/Onboarding/WelcomeView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O

@Composable
fun WelcomeScreen(onGetStarted: () -> Unit, modifier: Modifier = Modifier) {
    OnboardingColumn(modifier, verticalArrangement = Arrangement.SpaceBetween) {
        Spacer(Modifier)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(OnboardingMetrics.largeSpacing)) {
            MeshAnimation()
            Text(
                stringResource(O.welcomeTitle), Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center,
            )
            Text(
                stringResource(O.welcomeSubtitle), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
        OnboardingPrimaryButton(
            stringResource(O.welcomeGetStarted), onGetStarted, Modifier.padding(bottom = OnboardingMetrics.cardSpacing),
        )
    }
}
