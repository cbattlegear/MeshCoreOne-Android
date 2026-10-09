// PortedFrom: MC1/Views/Tools/LineOfSight/LineOfSightView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.los

import com.meshcoreone.android.core.model.RadioId
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

/**
 * App-owned adapters for the process store, WP-218 elevation service and WP-212 RF calculator.
 * The feature never constructs networking, persistence or radio services during composition.
 */
data class LineOfSightFeatureDependencies(
    val elevationSource: LineOfSightElevationSource,
    val pathAnalyzer: LineOfSightPathAnalyzer,
    val contactSource: () -> LineOfSightContactSource?,
    val radioId: () -> RadioId?,
    val deviceFrequencyKHz: () -> UInt?,
    val failureReporter: LineOfSightFailureReporter = LineOfSightFailureReporter.NONE,
    val analysisContext: CoroutineContext = Dispatchers.Default,
)
