// AndroidOnly: WP-311 Sensor settings using the authenticated remote-node session.
package com.meshcoreone.android.feature.remotenodes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SensorSettingsRoute(
    session: RemoteNodeSessionDTO,
    dependencies: RemoteNodesFeatureDependencies?,
    ready: Boolean,
    modifier: Modifier,
    onBack: () -> Unit,
    onAuthenticate: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val port = remember(dependencies) { dependencies?.sensorSettings() }
    val key = remember(session.radioId, session.id) { EntityKey(session.radioId, session.id) }
    var settings by remember(key, port) { mutableStateOf<List<SensorSettingEntry>>(emptyList()) }
    var drafts by remember(key, port) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loaded by remember(key, port) { mutableStateOf(false) }
    var busy by remember(key, port) { mutableStateOf(false) }
    var requiresReload by remember(key, port) { mutableStateOf(false) }
    var error by remember(key, port) { mutableStateOf<RemoteNodesText?>(null) }
    var confirmReboot by remember(key, port) { mutableStateOf(false) }
    val authorized = ready && session.isConnected && session.isAdmin && port != null

    suspend fun reload() {
        val service = port ?: return
        if (!authorized || busy) return
        busy = true
        error = null
        try {
            val fresh = service.fetchSettings(key)
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

    LaunchedEffect(key, port, authorized) {
        if (authorized && !loaded) reload()
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
        .testTag("sensor-settings")) {
        TextButton(onBack, Modifier.heightIn(min = 48.dp)) { Text(stringResource(L.remoteNodesDone)) }
        RemoteHeading(session.name)
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
                    modifier = Modifier.fillMaxWidth(),
                )
                val value = drafts[setting.key] ?: setting.value
                TextButton(
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                val updated = checkNotNull(port).setSetting(key, setting, value)
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
    if (confirmReboot) {
        RemoteConfirmation(
            stringResource(L.remoteNodesSettingsRebootDevice),
            stringResource(L.remoteNodesSettingsRebootMessage),
            onCancel = { confirmReboot = false },
        ) {
            confirmReboot = false
            busy = true
            scope.launch {
                try {
                    checkNotNull(port).reboot(key)
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
}
