// PortedFrom: MC1/Views/RemoteNodes/NodeAuthenticationSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.ui.FeatureShellCopy
import com.meshcoreone.android.core.ui.ScaffoldFeatureContent
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.dependencies.*
import com.meshcoreone.android.feature.remotenodes.ui.*
import kotlinx.coroutines.flow.collectLatest

private data class CatalogState(
    val catalog: RemoteNodesCatalog = RemoteNodesCatalog(emptyList(), emptyList(), emptyList()),
    val connection: RemoteNodesConnection = RemoteNodesConnection(null, null, false),
    val loading: Boolean = true,
    val error: RemoteNodesText? = null,
)

@Composable
fun RemoteNodesEntry(
    route: FeatureRoute,
    onNavigate: (FeatureRoute) -> Unit,
    dependencies: RemoteNodesUiDependencies? = null,
    cliContent: RemoteNodeCliContent? = null,
    mapSurface: RemoteNodesMapSurface = NativeRemoteNodesMapSurface,
    launch: RemoteNodeLaunch? = null,
    onDismiss: () -> Unit = {},
    onJoinRoom: (RemoteNodeSessionDTO) -> Unit = {},
) {
    if (dependencies == null) {
        ScaffoldFeatureContent(
            FeatureId.REMOTE_NODES, route,
            FeatureShellCopy(
                stringResource(R.string.scaffold_remote_nodes_title),
                stringResource(R.string.scaffold_remote_nodes_description),
                stringResource(R.string.scaffold_manage_remote_node),
            ),
            onNavigate,
        )
        return
    }
    var refresh by remember { mutableIntStateOf(0) }
    val data by produceState(CatalogState(), dependencies, refresh) {
        dependencies.updates.collectLatest { connection ->
            value = value.copy(connection = connection, loading = true, error = null)
            try {
                value = CatalogState(dependencies.catalog(), connection, loading = false)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                value = value.copy(loading = false, error = RemoteNodesText.Failure(failure))
            }
        }
    }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var historySession by remember { mutableStateOf<RemoteNodeSessionDTO?>(null) }
    var initialHistory by remember { mutableStateOf(false) }
    var historyLaunch by remember { mutableIntStateOf(0) }
    var loginContact by remember { mutableStateOf<ContactDTO?>(null) }
    val selected = data.catalog.sessions.firstOrNull { "${it.radioId}:${it.publicKeyHex}" == selectedKey }
        ?: historySession?.takeIf { "${it.radioId}:${it.publicKeyHex}" == selectedKey }
    val services = remember(dependencies, data.connection.generation) { dependencies?.services() }
    if (launch != null) {
        RemoteNodeContactRoute(
            launch, services, data.catalog, data.connection, data.loading, data.error,
            onDismiss, onJoinRoom, { refresh++ }, cliContent, dependencies.radioOptions, mapSurface,
        )
        return
    }
    BackHandler(selectedKey != null || loginContact != null) {
        selectedKey = null
        loginContact = null
    }
    Column(Modifier.fillMaxSize().imePadding().padding(16.dp)) {
        RemoteHeading(stringResource(L.remoteNodesAuthManagement))
        RemoteFailure(data.error)
        if (data.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!data.connection.ready) Text(stringResource(L.remoteNodesRoomDisconnectedBanner))
        TextButton({ refresh++ }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(L.remoteNodesStatusRefresh)) }
        BoxWithConstraints(Modifier.weight(1f)) {
            val showList = maxWidth >= 780.dp || selected == null
            Row(Modifier.fillMaxSize()) {
                if (showList) LazyColumn(
                    Modifier.weight(if (selected == null) 1f else .4f).testTag("remote-node-list"),
                ) {
                    items(data.catalog.contacts.filter { it.type == ContactType.REPEATER || it.type == ContactType.ROOM }, key = { "${it.radioId}:${it.id}" }) { contact ->
                        val session = data.catalog.sessions.firstOrNull { it.publicKey == contact.publicKey && it.radioId == contact.radioId }
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp)) {
                            RemoteHeading(contact.displayName)
                            Text(contact.publicKeyHex, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(
                                onClick = {
                                    initialHistory = false
                                    if (session != null) selectedKey = "${session.radioId}:${session.publicKeyHex}"
                                    else loginContact = contact
                                },
                                enabled = !data.loading && data.error == null && (session != null || data.connection.ready),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("open-node:${contact.id}"),
                            ) { Text(stringResource(L.remoteNodesAuthManagement)) }
                            TextButton({
                                initialHistory = true
                                historyLaunch++
                                selectedKey = "${contact.radioId}:${contact.publicKeyHex}"
                                historySession = RemoteNodeSessionDTO(
                                    radioId = contact.radioId, publicKey = contact.publicKey, name = contact.displayName,
                                    role = if (contact.type == ContactType.REPEATER) com.meshcoreone.android.core.model.RemoteNodeRole.REPEATER
                                    else com.meshcoreone.android.core.model.RemoteNodeRole.ROOM_SERVER,
                                    latitude = contact.latitude, longitude = contact.longitude,
                                )
                            }, enabled = !data.loading && data.error == null) { Text(stringResource(L.remoteNodesHistoryOverviewTitle)) }
                            HorizontalDivider()
                        }
                    }
                    items(data.catalog.sessions.filter { session -> data.catalog.contacts.none { it.publicKey == session.publicKey && it.radioId == session.radioId } },
                        key = { "${it.radioId}:${it.id}" }) { session ->
                        TextButton({ initialHistory = false; selectedKey = "${session.radioId}:${session.publicKeyHex}" }) { Text(session.name) }
                    }
                }
                if (selected != null) key(selected.id, data.connection.generation, selected.permissionLevel, data.connection.ready, data.error != null, historyLaunch, initialHistory) {
                    RemoteNodeManagementRoute(
                        selected, services, data.catalog, data.connection.ready && !data.loading && data.error == null && selected.isConnected && selected.radioId == data.connection.radioId,
                        Modifier.weight(.6f), onBack = { selectedKey = null },
                        onAuthenticate = {
                            loginContact = data.catalog.contacts.firstOrNull {
                                it.publicKey == selected.publicKey && it.radioId == selected.radioId
                            }
                        },
                        cliContent = cliContent,
                        initialHistory = initialHistory,
                        radioOptions = dependencies?.radioOptions,
                        mapSurface = mapSurface,
                    )
                }
            }
        }
    }
    loginContact?.let { contact ->
        if (services != null) RemoteNodeAuthenticationDialog(contact, services, data.catalog, onCancel = { loginContact = null }) {
            historySession = it.copy(isConnected = false, permissionLevel = com.meshcoreone.android.core.model.RoomPermissionLevel.GUEST)
            selectedKey = "${it.radioId}:${it.publicKeyHex}"
            initialHistory = false
            loginContact = null
            refresh++
        }
    }
}
