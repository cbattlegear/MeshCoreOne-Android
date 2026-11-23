// AndroidOnly: WP-311 Sensor settings using the authenticated remote-node session.
package com.meshcoreone.android.feature.remotenodes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodesFeatureDependencies
import com.meshcoreone.android.feature.remotenodes.dependencies.SensorSettingEntry
import com.meshcoreone.android.feature.remotenodes.settings.NodeManagementTab
import com.meshcoreone.android.feature.remotenodes.settings.NodeSettingsNoServiceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun SensorSettingsRoute(
    session: RemoteNodeSessionDTO,
    dependencies: RemoteNodesFeatureDependencies?,
    ready: Boolean,
    modifier: Modifier,
    onBack: () -> Unit,
    onAuthenticate: () -> Unit,
    cliContent: RemoteNodeCliContent?,
) {
    val port = remember(dependencies) { dependencies?.sensorSettings() }
    val key = remember(session.radioId, session.id) { EntityKey(session.radioId, session.id) }
    val authorized = ready && session.isConnected && session.isAdmin && port != null
    val canWrite by rememberUpdatedState(authorized)
    androidx.compose.runtime.key(key, port, session.lastConnectedDate) {
        var settings by remember(key, port) { mutableStateOf<List<SensorSettingEntry>>(emptyList()) }
        var drafts by remember(key, port) { mutableStateOf<Map<String, String>>(emptyMap()) }
        var loaded by remember(key, port) { mutableStateOf(false) }
        var busy by remember(key, port) { mutableStateOf(false) }
        var requiresReload by remember(key, port) { mutableStateOf(false) }
        var error by remember(key, port) { mutableStateOf<RemoteNodesText?>(null) }
        var confirmReboot by remember(key, port) { mutableStateOf(false) }
        var showCli by rememberSaveable(session.id) { mutableStateOf(false) }

        androidx.compose.runtime.key(authorized) {
            val scope = rememberCoroutineScope()
            suspend fun reload() {
                val service = port ?: return
                if (!canWrite || busy) return
                busy = true
                error = null
                try {
                    val fresh = service.fetchSettings(key)
                    currentCoroutineContext().ensureActive()
                    settings = fresh
                    drafts = fresh.associate { it.key to it.value }
                    loaded = true
                    requiresReload = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = RemoteNodesText.Failure(failure)
                } finally {
                    busy = false
                }
            }

            LaunchedEffect(authorized) {
                if (authorized && !loaded) {
                    snapshotFlow { busy }.first { !it }
                    if (!loaded) reload()
                }
            }

            Column(modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("sensor-settings")) {
                TextButton(onBack, Modifier.heightIn(min = 48.dp)) { Text(stringResource(L.remoteNodesDone)) }
                RemoteHeading(session.name)
                if (session.isAdmin && cliContent != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !showCli, onClick = { showCli = false },
                            label = { Text(stringResource(NodeManagementTab.SETTINGS.labelRes)) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("remote-tab:SETTINGS"),
                        )
                        FilterChip(
                            selected = showCli, onClick = { showCli = true },
                            label = { Text(stringResource(NodeManagementTab.CLI.labelRes)) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("remote-tab:CLI"),
                        )
                    }
                }
                val cliSelected = showCli && session.isAdmin && cliContent != null
                RetainedTabContent(cliSelected, Modifier.weight(1f)) {
                    if (cliContent != null) cliContent(session, { requested, command, timeout ->
                        if (!canWrite || requested != key) throw NodeSettingsNoServiceException()
                        (port ?: throw NodeSettingsNoServiceException()).sendRawCommand(key, command, timeout)
                    }, authorized)
                }
                if (!cliSelected) Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    RemoteHeading(stringResource(R.string.sensor_settings_title))
                    if (!authorized) {
                        Text(stringResource(L.remoteNodesRoomDisconnectedBanner))
                        TextButton(onAuthenticate) { Text(stringResource(L.remoteNodesAuthAuthentication)) }
                    }
                    RemoteFailure(error)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton({ scope.launch { reload() } }, enabled = authorized && !busy) {
                        Text(stringResource(R.string.sensor_settings_reload))
                    }
                    if (loaded && settings.isEmpty()) Text(stringResource(R.string.sensor_settings_empty))
                    settings.forEach { setting ->
                        androidx.compose.runtime.key(setting.key) {
                            OutlinedTextField(
                                value = drafts[setting.key] ?: setting.value,
                                onValueChange = { drafts = drafts + (setting.key to it) },
                                label = { Text(setting.key) },
                                enabled = authorized && !busy && !requiresReload,
                                modifier = Modifier.fillMaxWidth().testTag("sensor-setting:${setting.key}"),
                            )
                            val value = drafts[setting.key] ?: setting.value
                            TextButton(
                                onClick = {
                                    busy = true
                                    error = null
                                    scope.launch {
                                        try {
                                            if (!canWrite) throw NodeSettingsNoServiceException()
                                            val updated = checkNotNull(port).setSetting(key, setting, value)
                                            currentCoroutineContext().ensureActive()
                                            settings = settings.map { if (it.key == updated.key) updated else it }
                                        } catch (cancelled: CancellationException) {
                                            requiresReload = true
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            if (dependencies?.faults?.isSensorOutcomeUnknown(failure) == true) requiresReload = true
                                            error = RemoteNodesText.Failure(failure)
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                enabled = authorized && loaded && !busy && !requiresReload && value != setting.value,
                            ) { Text(stringResource(R.string.sensor_settings_save)) }
                        }
                    }
                    TextButton({ confirmReboot = true }, enabled = authorized && !busy) {
                        Text(stringResource(L.remoteNodesSettingsRebootDevice))
                    }
                }
            }
            if (confirmReboot && authorized) {
                RemoteConfirmation(
                    stringResource(L.remoteNodesSettingsRebootDevice),
                    stringResource(L.remoteNodesSettingsRebootMessage),
                    onCancel = { confirmReboot = false },
                ) {
                    confirmReboot = false
                    busy = true
                    scope.launch {
                        try {
                            if (!canWrite) throw NodeSettingsNoServiceException()
                            checkNotNull(port).reboot(key)
                            currentCoroutineContext().ensureActive()
                            onBack()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = RemoteNodesText.Failure(failure)
                        } finally {
                            busy = false
                        }
                    }
                }
            }
            LaunchedEffect(authorized) {
                if (!authorized) confirmReboot = false
            }
        }
    }
}
