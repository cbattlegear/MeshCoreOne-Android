// AndroidOnly: WP-305 shared onboarding layout pieces (stand-ins for LiquidGlass button/container styles).
package com.meshcoreone.android.feature.onboarding.ui

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import com.meshcoreone.android.core.ui.Button
import com.meshcoreone.android.core.ui.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.ui.sharedTouchTarget

/** Centers content and caps its width so wide windows do not stretch single-column steps. */
@Composable
fun OnboardingColumn(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = OnboardingMetrics.maxContentWidth).fillMaxSize()
                .padding(horizontal = OnboardingMetrics.cardSpacing),
            horizontalAlignment = horizontalAlignment, verticalArrangement = verticalArrangement, content = content,
        )
    }
}

@Composable
fun OnboardingHeader(title: String, subtitle: String?, modifier: Modifier = Modifier, topPadding: androidx.compose.ui.unit.Dp = OnboardingMetrics.headerTopPadding) {
    Column(
        modifier.padding(top = topPadding), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OnboardingMetrics.titleStackSpacing),
    ) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun OnboardingPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingText: String? = null,
) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled && !loading) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(if (loading && loadingText != null) loadingText else text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

fun Modifier.onboardingTouchTarget(): Modifier = sharedTouchTarget()

/** True when the user disabled animations (animator duration scale 0), the Android analogue of Reduce Motion. */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
}
