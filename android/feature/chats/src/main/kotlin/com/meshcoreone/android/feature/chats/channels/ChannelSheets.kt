// PortedFrom: MC1/Views/Chats/Sheets/ChannelOptionsSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/CreatePrivateChannelView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Sheets/JoinPrivateChannelView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Sheets/JoinPublicChannelView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Sheets/JoinHashtagChannelView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.channels

import com.meshcoreone.android.core.designsystem.AccentButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.ui.sharedTouchTarget
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelOptionsSheet(
    holder: ChannelSheetsStateHolder,
    onDismiss: () -> Unit,
    onChannelReady: (com.meshcoreone.android.core.model.ChannelDTO) -> Unit,
    onScanRequested: () -> Unit,
    onShare: (String) -> Unit,
    onCopy: (String) -> Unit,
    qrCode: @Composable (String) -> Unit,
    failureText: @Composable (Throwable) -> String = { it.message ?: it.javaClass.simpleName },
) {
    val state by holder.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(holder) { holder.load() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(titleFor(state.page), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                TextButton(if (state.page == ChannelSheetPage.OPTIONS) onDismiss else holder::back, Modifier.sharedTouchTarget()) {
                    Text(androidx.compose.ui.res.stringResource(
                        if (state.page == ChannelSheetPage.OPTIONS) R.string.l10n_app_chats_chats_common_cancel
                        else R.string.scaffold_back,
                    ))
                }
            }
            state.failure?.let {
                Text(
                    failureText(it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable(onClick = holder::clearFailure).padding(8.dp),
                )
            }
            when (state.page) {
                ChannelSheetPage.OPTIONS -> Options(state, holder::open, onScanRequested)
                ChannelSheetPage.CREATE_PRIVATE -> ChannelNameAndSecretForm(
                    state, holder::updateName, secretEditable = false, holder::updateSecret,
                ) { scope.launch { holder.createPrivate()?.let(onChannelReady) } }
                ChannelSheetPage.JOIN_PRIVATE -> ChannelNameAndSecretForm(
                    state, holder::updateName, secretEditable = true, holder::updateSecret,
                ) { scope.launch { holder.joinPrivate()?.let(onChannelReady) } }
                ChannelSheetPage.JOIN_PUBLIC -> {
                    Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_joinpublic_description))
                    SubmitButton(state.isSubmitting, R.string.l10n_app_chats_chats_joinpublic_addbutton, enabled = state.committedWrite == null) {
                        scope.launch { holder.joinPublic()?.let(onChannelReady) }
                    }
                }
                ChannelSheetPage.JOIN_HASHTAG -> {
                    OutlinedTextField(
                        state.name,
                        holder::updateHashtag,
                        Modifier.fillMaxWidth().sharedTouchTarget(),
                        prefix = { Text("#") },
                        placeholder = { Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_joinhashtag_placeholder)) },
                        singleLine = true,
                    )
                    Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_joinhashtag_footer))
                    SubmitButton(
                        state.isSubmitting,
                        R.string.l10n_app_chats_chats_joinprivate_joinbutton,
                        enabled = state.committedWrite == null && ChannelSheetsStateHolder.isValidHashtag(state.name),
                    ) { scope.launch { holder.joinHashtag()?.let(onChannelReady) } }
                }
                ChannelSheetPage.SCAN_QR -> onScanRequested()
                ChannelSheetPage.SHARE -> {
                    val uri = state.shareUri
                    val channel = state.createdChannel
                    if (uri != null && channel != null) {
                        qrCode(uri)
                        Text(channel.name, style = MaterialTheme.typography.titleMedium)
                        Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_channelinfo_scantojoin))
                        Text(uri, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ onCopy(uri) }, Modifier.sharedTouchTarget()) {
                                Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_channelinfo_copy))
                            }
                            Button({ onShare(uri) }, Modifier.sharedTouchTarget()) {
                                Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_channelinfo_sharechannel))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinChannelConfirmationSheet(
    holder: ChannelSheetsStateHolder,
    link: ChannelLink,
    onDismiss: () -> Unit,
    onJoined: (com.meshcoreone.android.core.model.ChannelDTO) -> Unit,
    failureText: @Composable (Throwable) -> String = { it.message ?: it.javaClass.simpleName },
) {
    val state by holder.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(holder) { holder.load() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_joinfrommessage_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(link.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                link.secret.uppercaseHexString().let { hex ->
                    if (hex.length < 16) hex else "${hex.take(8)}…${hex.takeLast(8)}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            link.regionScope?.let {
                Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_joinfrommessage_regionscope, it))
            }
            if (link.hasHashtagSecretMismatch) {
                Text(
                    androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_joinfrommessage_hashtagsecretmismatch),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.failure?.let {
                Text(failureText(it), color = MaterialTheme.colorScheme.error)
            }
            when {
                state.isLoading -> CircularProgressIndicator()
                state.availableSlots.isEmpty() -> {
                    Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_joinfrommessage_noslots_title))
                    Text(
                        androidx.compose.ui.res.stringResource(
                            R.string.l10n_app_chats_chats_joinfrommessage_noslots_description,
                            link.name,
                        ),
                    )
                }
                else -> Button(
                    {
                        scope.launch {
                            holder.joinLink(link)?.let {
                                onJoined(it)
                                onDismiss()
                            }
                        }
                    },
                    enabled = !state.isSubmitting && state.committedWrite == null,
                    modifier = Modifier.fillMaxWidth().sharedTouchTarget(),
                ) {
                    Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_joinprivate_joinbutton))
                }
            }
            TextButton(onDismiss, Modifier.fillMaxWidth().sharedTouchTarget()) {
                Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_common_cancel))
            }
        }
    }
}

@Composable
private fun Options(
    state: ChannelSheetsState,
    open: (ChannelSheetPage) -> Unit,
    onScanRequested: () -> Unit,
) {
    if (state.isLoading) {
        CircularProgressIndicator()
        return
    }
    OptionRow(R.string.l10n_app_chats_chats_channeloptions_createprivate_title, state.availableSlots.isNotEmpty()) {
        open(ChannelSheetPage.CREATE_PRIVATE)
    }
    OptionRow(R.string.l10n_app_chats_chats_channeloptions_joinprivate_title, state.availableSlots.isNotEmpty()) {
        open(ChannelSheetPage.JOIN_PRIVATE)
    }
    OptionRow(R.string.l10n_app_chats_chats_channeloptions_scanqr_title, state.availableSlots.isNotEmpty()) {
        onScanRequested()
    }
    OptionRow(R.string.l10n_app_chats_chats_channeloptions_joinpublic_title, !state.hasPublicChannel) {
        open(ChannelSheetPage.JOIN_PUBLIC)
    }
    OptionRow(R.string.l10n_app_chats_chats_channeloptions_joinhashtag_title, state.availableSlots.isNotEmpty()) {
        open(ChannelSheetPage.JOIN_HASHTAG)
    }
    if (state.availableSlots.isEmpty()) Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_channeloptions_footer_noslots))
    else if (state.hasPublicChannel) Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_channeloptions_footer_haspublic))
}

@Composable
private fun OptionRow(label: Int, enabled: Boolean, onClick: () -> Unit) {
    Text(
        androidx.compose.ui.res.stringResource(label),
        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
        modifier = Modifier.fillMaxWidth().sharedTouchTarget()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(12.dp),
    )
}

@Composable
private fun ChannelNameAndSecretForm(
    state: ChannelSheetsState,
    onName: (String) -> Unit,
    secretEditable: Boolean,
    onSecret: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        state.name,
        onName,
        Modifier.fillMaxWidth().sharedTouchTarget(),
        label = { Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_createprivate_channelname)) },
        singleLine = true,
    )
    val secret = if (secretEditable) state.secretHex else state.generatedSecret?.uppercaseHexString().orEmpty()
    OutlinedTextField(
        secret,
        onSecret,
        Modifier.fillMaxWidth().sharedTouchTarget(),
        enabled = secretEditable,
        visualTransformation = if (secretEditable) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        label = { Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_channelinfo_secretkey)) },
        singleLine = true,
    )
    SubmitButton(
        state.isSubmitting,
        if (secretEditable) R.string.l10n_app_chats_chats_joinprivate_joinbutton else R.string.l10n_app_chats_chats_createprivate_createbutton,
        enabled = state.committedWrite == null && state.name.isNotEmpty() &&
            (!secretEditable || ChannelSheetsStateHolder.parseSecret(secret) != null),
        onClick = onSubmit,
    )
}

@Composable
private fun SubmitButton(isSubmitting: Boolean, label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    AccentButton(
        onClick,
        enabled = enabled && !isSubmitting,
        modifier = Modifier.fillMaxWidth().sharedTouchTarget(),
    ) {
        if (isSubmitting) CircularProgressIndicator() else Text(androidx.compose.ui.res.stringResource(label))
    }
}

@Composable
private fun titleFor(page: ChannelSheetPage): String = androidx.compose.ui.res.stringResource(
    when (page) {
        ChannelSheetPage.OPTIONS -> R.string.l10n_app_chats_chats_channeloptions_title
        ChannelSheetPage.CREATE_PRIVATE -> R.string.l10n_app_chats_chats_createprivate_titlecreate
        ChannelSheetPage.JOIN_PRIVATE -> R.string.l10n_app_chats_chats_joinprivate_title
        ChannelSheetPage.JOIN_PUBLIC -> R.string.l10n_app_chats_chats_joinpublic_title
        ChannelSheetPage.JOIN_HASHTAG -> R.string.l10n_app_chats_chats_joinhashtag_title
        ChannelSheetPage.SCAN_QR -> R.string.l10n_app_chats_chats_scanqr_title
        ChannelSheetPage.SHARE -> R.string.l10n_app_chats_chats_createprivate_titleshare
    },
)
