// AndroidOnly: WP-316 Narrow app-composition seam for the diagnostics screens.
package com.meshcoreone.android.feature.tools.diagnostics

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliErrorPresentation
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliToolFeatureDependencies
import com.meshcoreone.android.feature.tools.diagnostics.noisefloor.RadioStatsSource
import com.meshcoreone.android.feature.tools.diagnostics.rxlog.RxLogFeatureDependencies
import kotlinx.coroutines.flow.StateFlow

data class ToolsDiagnosticsDependencies(
    val cli: CliToolFeatureDependencies,
    val cliErrors: CliErrorPresentation = CliErrorPresentation(),
    val rxLog: RxLogFeatureDependencies,
    val radioStats: () -> RadioStatsSource?,
    val isConnected: () -> Boolean,
    val localPublicKeyPrefix: () -> Bytes?,
    val connectionVersion: StateFlow<Int>,
)
