// PortedFrom: MC1/Views/RemoteNodes/NodeAuthenticationSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.remotenodes.auth.*
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodesFeatureDependencies

@Composable
internal fun RemoteNodeAuthenticationDialog(
    contact: ContactDTO,
    dependencies: RemoteNodesFeatureDependencies,
    onCancel: () -> Unit,
    onSuccess: (RemoteNodeSessionDTO) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val success by rememberUpdatedState(onSuccess)
    val role = if (contact.type == ContactType.REPEATER) RemoteNodeRole.REPEATER else RemoteNodeRole.ROOM_SERVER
    val holder = remember(contact.id, dependencies) {
        NodeAuthenticationStateHolder(
            contact, role, dependencies::login, dependencies::connectedRadioId,
            dependencies.faults, dependencies.clock, scope,
        ) { success(it) }
    }
    val state by holder.state.collectAsStateWithLifecycle()
    var prefillError by remember { mutableStateOf<RemoteNodesText?>(null) }
    LaunchedEffect(holder) {
        try {
            holder.loadSavedPassword()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            prefillError = RemoteNodesText.Failure(failure)
        }
    }
    DisposableEffect(holder) { onDispose { holder.cancel() } }
    val view = LocalView.current
    var previousCountdown by remember { mutableStateOf<Int?>(null) }
    val announcement = state.authSecondsRemaining?.let { remoteText(NodeAuthenticationPresentation.announcement(it)) }
    LaunchedEffect(state.authSecondsRemaining) {
        if (NodeAuthenticationPresentation.shouldAnnounce(previousCountdown, state.authSecondsRemaining)) {
            view.announceForAccessibility(announcement)
        }
        previousCountdown = state.authSecondsRemaining
    }
    AlertDialog(
        onDismissRequest = { holder.cancel(); onCancel() },
        title = { Text(remoteText(NodeAuthenticationPresentation.title(role, null))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RemoteValue(stringResource(L.remoteNodesAuthName), contact.displayName)
                RemoteValue(stringResource(L.remoteNodesAuthType), remoteText(NodeAuthenticationPresentation.typeLabel(role)))
                OutlinedTextField(
                    state.password, holder::setPassword, Modifier.fillMaxWidth().testTag("remote-login-password"),
                    label = { Text(stringResource(L.remoteNodesAuthPassword)) },
                    visualTransformation = PasswordVisualTransformation(), enabled = !state.isAuthenticating,
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(state.rememberPassword, holder::setRememberPassword, enabled = !state.isAuthenticating)
                    Text(stringResource(L.remoteNodesAuthRememberPassword))
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(state.useFloodRouting, holder::setUseFloodRouting, enabled = holder.hasStoredPath && !state.isAuthenticating)
                    Text(stringResource(L.remoteNodesAuthFloodRouting))
                }
                RemoteValue(stringResource(L.remoteNodesAuthPath), if (contact.isFloodRouted) stringResource(L.remoteNodesAuthNoRouteSet) else contact.pathString)
                Text(remoteText(NodeAuthenticationPresentation.pathFooter(holder.hasStoredPath, state.useFloodRouting)))
                RemoteFailure(prefillError)
                NodeAuthenticationPresentation.authenticationFooter(state, role).forEach { Text(remoteText(it)) }
                if (state.isAuthenticating) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        dismissButton = { TextButton({ holder.cancel(); onCancel() }) { Text(stringResource(L.remoteNodesCancel)) } },
        confirmButton = {
            TextButton(
                { holder.authenticate() }, enabled = !state.isAuthenticating,
                modifier = Modifier.heightIn(min = 48.dp).testTag("remote-login"),
            ) { Text(remoteText(NodeAuthenticationPresentation.connectLabel(role))) }
        },
    )
}
