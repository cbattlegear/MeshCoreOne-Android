// PortedFrom: MC1/Views/Components/NavigationHeaderModifier.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/View+ScrollRevealTitle.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.LocalMeshTheme

fun shouldRevealNavigationTitle(scrollOffset: Double, revealAfter: Double = 150.0): Boolean {
    require(scrollOffset.isFinite() && revealAfter.isFinite() && revealAfter >= 0)
    return scrollOffset > revealAfter
}

@Composable
fun NavigationHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    onTitleTap: (() -> Unit)? = null,
    onMeasuredHeight: ((Double) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val click = onTitleTap?.let { Modifier.clickable(role = Role.Button, onClick = it).sharedTouchTarget() } ?: Modifier
    Row(modifier.fillMaxWidth().then(click).padding(horizontal = 16.dp, vertical = 12.dp)
        .onSizeChanged { onMeasuredHeight?.invoke(it.height.toDouble() / density.density) },
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        icon?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ScrollRevealNavigationTitle(title: String, scrollOffset: Double, revealAfter: Double, modifier: Modifier = Modifier) {
    val revealed = shouldRevealNavigationTitle(scrollOffset, revealAfter)
    val alpha by animateFloatAsState(if (revealed) 1f else 0f,
        tween(LocalMeshTheme.current.duration(200)), label = "scroll-title")
    if (revealed) Text(title, modifier.alpha(alpha).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}
