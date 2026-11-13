// PortedFrom: MC1/Views/RemoteNodes/NodeAuthenticationSheet.swift@5e980a53e077c45a1270170612fdf5dae97792ff
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.DialogProperties
import com.meshcoreone.android.core.l10n.R
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
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodesCatalog
import java.util.Locale

@Composable
internal fun RemoteNodeAuthenticationDialog(
    contact: ContactDTO,
    dependencies: RemoteNodesFeatureDependencies,
    catalog: RemoteNodesCatalog,
    onCancel: () -> Unit,
    requireSensorAdmin: Boolean = true,
    onSuccess: (RemoteNodeSessionDTO) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val success by rememberUpdatedState(onSuccess)
    val role = requireNotNull(RemoteNodeRole.fromContactType(contact.type))
    val holder = remember(contact.id, dependencies) {
        NodeAuthenticationStateHolder(
            contact, role, dependencies::login, dependencies::connectedRadioId,
            dependencies.faults, dependencies.clock, scope, requireSensorAdmin,
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
    // A stray tap outside must not throw away a login that is already on the air; back and Stop end the attempt first.
    val dismiss = { if (state.isAuthenticating) holder.cancel() else onCancel() }
    AlertDialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(dismissOnClickOutside = !state.isAuthenticating),
        title = { Text(remoteText(NodeAuthenticationPresentation.title(role, null))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RemoteValue(stringResource(L.remoteNodesAuthName), contact.displayName)
                RemoteValue(stringResource(L.remoteNodesAuthType), remoteText(NodeAuthenticationPresentation.typeLabel(role)))
                OutlinedTextField(
                    state.password, holder::setPassword, Modifier.fillMaxWidth().testTag("remote-login-password"),
                    label = { Text(stringResource(L.remoteNodesAuthPassword)) },
                    visualTransformation = PasswordVisualTransformation(), enabled = !state.isAuthenticating,
                    // Enter used to append a newline to the password (the holder now strips line breaks, including pasted
                    // ones); Done submits. The password keyboard turns off suggestions and autocorrect, which would
                    // otherwise rewrite what was typed. singleLine is avoided: it re-measures endlessly inside this
                    // scrolling dialog under the Robolectric window harness.
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (!state.isAuthenticating) holder.authenticate() }),
                )
                RemoteToggle(L.remoteNodesAuthRememberPassword, state.rememberPassword, holder::setRememberPassword, !state.isAuthenticating)
                RemoteToggle(L.remoteNodesAuthFloodRouting, state.useFloodRouting, holder::setUseFloodRouting, holder.hasStoredPath && !state.isAuthenticating)
                RemoteHeading(stringResource(L.remoteNodesAuthPath))
                when (val route = NodeRoutePathPresentation.of(contact, catalog.contacts, catalog.discoveredNodes, null, Locale.getDefault())) {
                    NodeRoutePath.NoRoute -> Text(stringResource(L.remoteNodesAuthNoRouteSet))
                    is NodeRoutePath.Route -> if (!state.useFloodRouting) {
                        Text(remoteText(route.summary))
                        route.hops.forEachIndexed { index, hop ->
                            key(index, hop.hex) {
                                val name = remoteText(hop.name)
                                RemoteValue(hop.hex, name)
                                if (hop.possibleMatch) RemoteNodePossibleMatch(name)
                            }
                        }
                    }
                }
                Text(remoteText(NodeAuthenticationPresentation.pathFooter(holder.hasStoredPath, state.useFloodRouting)))
                RemoteFailure(prefillError)
                RemoteFailure(state.errorMessage)
                if (state.isAuthenticating) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        remoteText(NodeAuthenticationPresentation.progressLine(state)),
                        modifier = Modifier.testTag("remote-login-progress").semantics { liveRegion = LiveRegionMode.Polite },
                    )
                } else if (state.errorMessage == null) {
                    NodeAuthenticationPresentation.authenticationFooter(state, role).forEach { Text(remoteText(it)) }
                }
            }
        },
        dismissButton = {
            TextButton(dismiss, Modifier.heightIn(min = 48.dp).testTag("remote-login-cancel")) {
                Text(stringResource(if (state.isAuthenticating) R.string.remote_login_stop else L.remoteNodesCancel))
            }
        },
        confirmButton = {
            TextButton(
                { holder.authenticate() }, enabled = !state.isAuthenticating,
                modifier = Modifier.heightIn(min = 48.dp).testTag("remote-login"),
            ) {
                Text(
                    if (state.errorMessage != null) stringResource(R.string.remote_login_retry)
                    else remoteText(NodeAuthenticationPresentation.connectLabel(role)),
                )
            }
        },
    )
}
