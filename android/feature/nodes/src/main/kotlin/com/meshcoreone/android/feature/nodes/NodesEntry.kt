// AndroidOnly: WP-002 Registered nodes entry; auxiliary navigation uses neutral routes only.
// AndroidOnly: WP-311 Native contacts/nodes list, detail, discovery, QR/add/share and path-edit host.
package com.meshcoreone.android.feature.nodes

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.ui.FeatureShellCopy
import com.meshcoreone.android.core.ui.ScaffoldFeatureContent
import com.meshcoreone.android.core.ui.ScaffoldLink
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.ui.ContactDetailScreen
import com.meshcoreone.android.feature.nodes.ui.DiscoveryScreen
import com.meshcoreone.android.feature.nodes.ui.NodesListScreen

@Composable
fun NodesEntry(
    route: FeatureRoute,
    onNavigate: (FeatureRoute) -> Unit,
    dependencies: NodesFeatureDependencies? = null,
    destination: NodesDestination = NodesDestination.List,
    navigation: NodesNavigation = NodesNavigation.NONE,
) {
    if (dependencies == null) {
        ScaffoldFeatureContent(
            FeatureId.NODES, route,
            FeatureShellCopy(
                stringResource(R.string.tab_nodes),
                stringResource(R.string.scaffold_nodes_description),
                stringResource(R.string.scaffold_discover_nodes),
            ),
            onNavigate,
            links = listOf(ScaffoldLink(stringResource(R.string.scaffold_remote_nodes_title), FeatureRoute(FeatureId.REMOTE_NODES))),
        )
        return
    }
    when (destination) {
        NodesDestination.List -> NodesListScreen(dependencies, navigation)
        NodesDestination.Discovery -> DiscoveryScreen(dependencies)
        is NodesDestination.Detail -> ContactDetailScreen(destination.contact, dependencies, navigation)
    }
}
