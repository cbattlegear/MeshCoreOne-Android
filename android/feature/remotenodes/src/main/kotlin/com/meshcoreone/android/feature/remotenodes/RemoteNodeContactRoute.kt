// PortedFrom: MC1/Views/Contacts/ContactDetailView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/NodeTelemetryView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.common.SystemRemoteNodesClock
import com.meshcoreone.android.feature.remotenodes.dependencies.*
import com.meshcoreone.android.feature.remotenodes.map.*
import com.meshcoreone.android.feature.remotenodes.status.NodeTelemetryStateHolder
import com.meshcoreone.android.feature.remotenodes.ui.*
import kotlinx.coroutines.launch
import java.util.UUID

enum class RemoteNodeLaunchAction { MANAGEMENT, TELEMETRY, SAVED_HISTORY, JOIN_ROOM }
data class RemoteNodeLaunch(val contact: ContactDTO, val action: RemoteNodeLaunchAction) {
    init {
        require(action != RemoteNodeLaunchAction.JOIN_ROOM || contact.type == ContactType.ROOM)
        require(action != RemoteNodeLaunchAction.MANAGEMENT || contact.type != ContactType.CHAT)
    }
}

@Composable
internal fun RemoteNodeContactRoute(
    launch: RemoteNodeLaunch,
    dependencies: RemoteNodesFeatureDependencies?,
    catalog: RemoteNodesCatalog,
    connection: RemoteNodesConnection,
    loading: Boolean,
    error: RemoteNodesText?,
    onDismiss: () -> Unit,
    onJoinRoom: (RemoteNodeSessionDTO) -> Unit,
    refresh: () -> Unit,
    cliContent: RemoteNodeCliContent?,
    radioOptions: RemoteRadioOptions,
    mapSurface: RemoteNodesMapSurface,
) {
    val contact = catalog.contacts.firstOrNull {
        it.radioId == launch.contact.radioId && it.publicKey == launch.contact.publicKey
    } ?: launch.contact
    val ready = connection.ready && connection.radioId == contact.radioId && !loading && error == null
    val historyStore = remember(dependencies) { dependencies?.historyStore() }
    val session = catalog.sessions.firstOrNull { it.radioId == contact.radioId && it.publicKey == contact.publicKey }
    var authenticated by remember(launch, connection.generation) { mutableStateOf<RemoteNodeSessionDTO?>(null) }
    var authenticate by remember(launch, connection.generation) { mutableStateOf(launch.action != RemoteNodeLaunchAction.SAVED_HISTORY && contact.type != ContactType.CHAT) }
    BackHandler(onBack = onDismiss)
    Column(Modifier.fillMaxSize().padding(16.dp).testTag("remote-contact-route")) {
        RemoteFailure(error)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            launch.action == RemoteNodeLaunchAction.SAVED_HISTORY -> {
                TextButton(onDismiss) { Text(stringResource(L.remoteNodesDone)) }
                RemoteHeading(contact.displayName)
                RemoteNodeHistoryRoute(
                    contact.publicKey, contact.radioId, contact.type == ContactType.REPEATER,
                    historyStore, Modifier.weight(1f), mapSurface,
                    dependencies?.clock ?: SystemRemoteNodesClock,
                )
            }
            contact.type == ContactType.CHAT && launch.action == RemoteNodeLaunchAction.TELEMETRY ->
                DirectNodeTelemetryRoute(contact, dependencies, ready, onDismiss, mapSurface)
            authenticate || (session ?: authenticated) == null -> {
                TextButton(onDismiss) { Text(stringResource(L.remoteNodesDone)) }
                RemoteHeading(contact.displayName)
                if (!ready || dependencies == null) Text(stringResource(L.remoteNodesRoomDisconnectedBanner))
                else RemoteNodeAuthenticationDialog(contact, dependencies, catalog, onDismiss) {
                    if (launch.action == RemoteNodeLaunchAction.JOIN_ROOM) onJoinRoom(it)
                    else {
                        authenticated = it.copy(isConnected = false, permissionLevel = RoomPermissionLevel.GUEST)
                        authenticate = false
                        refresh()
                    }
                }
            }
            else -> (session ?: authenticated)?.let { selected ->
                key(selected.id, selected.permissionLevel, connection.generation, connection.ready, error != null) {
                    RemoteNodeManagementRoute(
                        selected, dependencies, catalog, ready && selected.isConnected, Modifier.weight(1f),
                        onDismiss, { authenticate = true }, cliContent, radioOptions = radioOptions,
                        mapSurface = mapSurface, telemetryOnly = launch.action == RemoteNodeLaunchAction.TELEMETRY,
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectNodeTelemetryRoute(
    contact: ContactDTO,
    dependencies: RemoteNodesFeatureDependencies?,
    ready: Boolean,
    onDismiss: () -> Unit,
    mapSurface: RemoteNodesMapSurface,
) {
    val scope = rememberCoroutineScope()
    val holder = remember(dependencies, contact.radioId, contact.publicKey) {
        dependencies?.let { NodeTelemetryStateHolder(it.clock, it.faults) }
    }
    var showMap by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val store = remember(dependencies) { dependencies?.historyStore() }
    LaunchedEffect(holder) {
        if (holder != null && dependencies != null) {
            holder.configure(dependencies::binaryTelemetry, dependencies::contactOcv, dependencies::nodeSnapshots, contact)
            holder.helper.setTelemetryExpanded(true)
            holder.helper.loadOCVSettings(contact.publicKey, contact.radioId)
        }
    }
    BackHandler(showMap || showHistory) { showMap = false; showHistory = false }
    Column(Modifier.fillMaxSize().testTag("direct-node-telemetry")) {
        TextButton(onDismiss) { Text(stringResource(L.remoteNodesDone)) }
        RemoteHeading(contact.displayName)
        TextButton({ showHistory = !showHistory }) { Text(stringResource(L.remoteNodesHistoryOverviewTitle)) }
        if (showHistory) {
            RemoteNodeHistoryRoute(
                contact.publicKey, contact.radioId, false, store, Modifier.weight(1f), mapSurface,
                dependencies?.clock ?: SystemRemoteNodesClock,
            )
            return@Column
        }
        if (holder == null) RemoteFailure(RemoteNodesText.Resource(L.remoteNodesSettingsNoService))
        else {
            val state by holder.helper.state.collectAsStateWithLifecycle()
            val available = ready && dependencies?.binaryTelemetry() != null
            if (!available) Text(stringResource(L.remoteNodesRoomDisconnectedBanner))
            if (showMap) {
                TextButton({ showMap = false }) { Text(stringResource(L.remoteNodesDone)) }
                state.currentLocationFix?.let { fix ->
                    val point = MapPoint(
                        UUID.nameUUIDFromBytes(contact.publicKey.toByteArray()), fix.coordinate,
                        PinStyle.LOCATION_FIX_LATEST, contact.displayName, false, null, null,
                    )
                    RemoteNodeMapContent(
                        stringResource(L.remoteNodesStatusLocationMapTitle), listOf(point), emptyList(),
                        CoordinateRegion.around(fix.coordinate, .05), surface = mapSurface,
                    )
                }
            } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                val request: () -> Unit = { if (available) scope.launch { holder.requestTelemetry() } }
                TextButton(request, enabled = available && !state.isLoadingTelemetry, modifier = Modifier.testTag("direct-telemetry-refresh")) { Text(stringResource(L.remoteNodesStatusRefresh)) }
                RemoteTelemetrySection(holder.helper, available, request) { showMap = true }
            }
        }
    }
}
