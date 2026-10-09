// AndroidOnly: WP-002 Registered operational tools entry.
package com.meshcoreone.android.feature.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.ui.FeatureShellCopy
import com.meshcoreone.android.core.ui.ScaffoldFeatureContent
import com.meshcoreone.android.feature.tools.diagnostics.ToolsDiagnosticsDependencies
import com.meshcoreone.android.feature.tools.diagnostics.ToolsDiagnosticsScreen

@Composable
fun ToolsEntry(
    route: FeatureRoute,
    onNavigate: (FeatureRoute) -> Unit,
    diagnostics: ToolsDiagnosticsDependencies? = null,
) {
    if (diagnostics != null) {
        ToolsDiagnosticsScreen(diagnostics)
    } else {
        ScaffoldFeatureContent(
            FeatureId.TOOLS, route,
            FeatureShellCopy(
                stringResource(R.string.tab_tools),
                stringResource(R.string.scaffold_tools_description),
                stringResource(R.string.scaffold_run_radio_tool),
            ),
            onNavigate,
        )
    }
}
