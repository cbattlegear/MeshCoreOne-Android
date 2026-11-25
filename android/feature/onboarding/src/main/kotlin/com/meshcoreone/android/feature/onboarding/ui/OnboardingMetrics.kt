// PortedFrom: MC1/Views/Onboarding/OnboardingMetrics.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.onboarding.ui

import androidx.compose.ui.unit.dp

/** Named layout constants; 48dp replaces the 44pt iOS hit target to meet Android's minimum. */
object OnboardingMetrics {
    val heroSize = 130.dp
    val iconSize = 60.dp
    val cardCornerRadius = 12.dp
    val compactSpacing = 4.dp
    val titleStackSpacing = 8.dp
    val mediumSpacing = 12.dp
    val cardSpacing = 16.dp
    val contentPadding = 20.dp
    val largeSpacing = 24.dp
    val sheetTopPadding = 32.dp
    val minHitTarget = 48.dp
    val headerTopPadding = 40.dp
    /** Keeps content readable on tablets and unfolded foldables. */
    val maxContentWidth = 560.dp
    val meshAnimationHeight = 150.dp
}
