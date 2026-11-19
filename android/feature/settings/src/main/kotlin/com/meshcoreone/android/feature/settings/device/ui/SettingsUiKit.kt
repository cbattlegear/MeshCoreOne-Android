// AndroidOnly: WP-317 Material3 building blocks shared by the settings screens (48 dp targets, merged TalkBack semantics, dialogs for retry, errors and confirmations).
package com.meshcoreone.android.feature.settings.device.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.ui.SharedUiScaffold
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.radioDisabledHint
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.settings.device.RetryAlertController
import com.meshcoreone.android.feature.settings.device.RetryAlertState
import androidx.compose.runtime.collectAsState

/** Starts a section holder while its screen is composed and stops it on leaving, like SwiftUI `onAppear`/`onDisappear`. */
@Composable
internal fun HolderLifecycle(start: () -> Unit, stop: () -> Unit) {
    DisposableEffect(Unit) {
        start()
        onDispose(stop)
    }
}

/** A screen frame: edge-to-edge safe-drawing and IME insets (via core:ui), a title row and a scrolling column. */
@Composable
internal fun SettingsScreenFrame(title: UiText, onDone: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    SharedUiScaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(uiString(title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                if (onDone != null) {
                    TextButton(onDone, Modifier.sharedTouchTarget()) { Text(stringResource(AppLocalizableStrings.commonDone)) }
                }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

/** A titled group of rows with an optional footer, the Compose form of a SwiftUI `Section`. */
@Composable
internal fun SettingsSection(header: UiText?, footers: List<UiText> = emptyList(), content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (header != null) {
            Text(uiString(header), Modifier.padding(horizontal = 4.dp).semantics { heading() },
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), content = content)
        }
        for (footer in footers) {
            Text(uiString(footer), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun res(id: Int): UiText = UiText.Resource(id)

/** Label, optional description and a switch as one merged TalkBack control with the on/off state announced. */
@Composable
internal fun SwitchRow(label: UiText, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, description: UiText? = null, indent: Boolean = false) {
    val text = uiString(label)
    Row(
        Modifier.fillMaxWidth().padding(start = if (indent) 24.dp else 0.dp).sharedTouchTarget()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics(mergeDescendants = true) { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(uiString(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, null, enabled = enabled)
    }
}

/** A row that shows the current value and opens a menu of choices. */
@Composable
internal fun <T> DropdownRow(
    label: UiText, value: UiText, options: List<Pair<UiText, T>>, enabled: Boolean, onSelect: (T) -> Unit,
    accessibilityHint: UiText? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val hint = accessibilityHint?.let { uiString(it) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().sharedTouchTarget().clickable(enabled = enabled, role = Role.DropdownList) { expanded = true }
                .semantics(mergeDescendants = true) { if (hint != null) stateDescription = hint },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(uiString(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(uiString(value), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded, { expanded = false }) {
            for ((text, option) in options) {
                DropdownMenuItem(text = { Text(uiString(text)) }, onClick = { expanded = false; onSelect(option) },
                    modifier = Modifier.sharedTouchTarget())
            }
        }
    }
}

@Composable
internal fun ActionRow(text: UiText, enabled: Boolean, onClick: () -> Unit, destructive: Boolean = false, detail: UiText? = null) {
    val label = uiString(text)
    Row(
        Modifier.fillMaxWidth().sharedTouchTarget().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                destructive -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            })
        if (detail != null) Text(uiString(detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

/** Inline numeric field whose validation message sits under it and is announced as the error. */
@Composable
internal fun ValidatedField(
    label: UiText, text: String, onTextChange: (String) -> Unit, enabled: Boolean, keyboard: KeyboardType,
    error: UiText? = null, supporting: UiText? = null, placeholder: UiText? = null,
) {
    val message = error ?: supporting
    OutlinedTextField(
        value = text, onValueChange = onTextChange, enabled = enabled, singleLine = true, isError = error != null,
        label = { Text(uiString(label)) },
        placeholder = placeholder?.let { { Text(uiString(it)) } },
        supportingText = message?.let { { Text(uiString(it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().sharedTouchTarget(),
    )
}

/** Retry dialog: Retry/Cancel for the first attempts, an OK-only "unable to save" dialog once retries are exhausted. */
@Composable
internal fun RetryAlertDialog(controller: RetryAlertController) {
    val state: RetryAlertState by controller.state.collectAsState()
    if (!state.isPresented) return
    val maxed = state.isMaxRetriesExceeded
    AlertDialog(
        onDismissRequest = { if (maxed) controller.acknowledgeMaxRetries() else controller.cancel() },
        title = { Text(stringResource(if (maxed) AppSettingsStrings.alertRetryUnableToSave else AppSettingsStrings.alertRetryConnectionError)) },
        text = { Text(if (maxed) stringResource(AppSettingsStrings.alertRetryEnsureConnected) else state.message?.let { uiString(it) }.orEmpty()) },
        confirmButton = {
            TextButton(
                onClick = { if (maxed) controller.acknowledgeMaxRetries() else controller.retry() },
                modifier = Modifier.sharedTouchTarget(),
            ) { Text(stringResource(if (maxed) AppLocalizableStrings.commonOk else AppSettingsStrings.alertRetryRetry)) }
        },
        dismissButton = if (maxed) null else ({
            TextButton({ controller.cancel() }, Modifier.sharedTouchTarget()) { Text(stringResource(AppLocalizableStrings.commonCancel)) }
        }),
    )
}

@Composable
internal fun ErrorMessageDialog(message: UiText?, onDismiss: () -> Unit) {
    if (message == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(AppSettingsStrings.alertErrorTitle)) },
        text = { Text(uiString(message)) },
        confirmButton = { TextButton(onDismiss, Modifier.sharedTouchTarget()) { Text(stringResource(AppLocalizableStrings.commonOk)) } },
    )
}

/** Confirmation for an irreversible or disruptive action; the confirm button is styled destructive when asked. */
@Composable
internal fun ConfirmDialog(
    visible: Boolean, title: UiText, message: UiText, confirm: UiText, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = true,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiString(title)) },
        text = { Text(uiString(message)) },
        confirmButton = {
            TextButton(onConfirm, Modifier.sharedTouchTarget()) {
                Text(uiString(confirm), color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onDismiss, Modifier.sharedTouchTarget()) { Text(stringResource(AppLocalizableStrings.commonCancel)) } },
    )
}

/** Applies the radio-disabled hint (TalkBack explains why a control is off) for a connection state. */
@Composable
internal fun Modifier.radioHint(state: DeviceConnectionState): Modifier = radioDisabledHint(state)
